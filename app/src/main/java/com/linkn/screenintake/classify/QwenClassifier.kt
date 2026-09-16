package com.linkn.screenintake.classify

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 发给阿里云百炼的通义千问模型（走 OpenAI 兼容接口），判断内容属于支出/收入/待办/灵感/无关，
 * 并抽取结构化字段。三条路径共用同一套视觉/文本模型客户端，但不是同一套提示词：
 *  - [classify]：纯文字送模型，便宜、快，目前只在截屏失败时兜底用一次；
 *  - [classifyScreenshot]：组合键触发时对当前屏幕拍的一张静默截图（内存里转一下就发出去，
 *    不写进手机相册或任何文件），走视觉模型、走完整的九种类型提示词。
 *  - [classifyMealOrDrink]：长按音量上键拍照那条路径专用，是一套完全独立、小得多的
 *    提示词——只判断"这张照片是吃的还是喝的"外加一句话描述，不套用上面九种类型的
 *    完整提示词，也不产出 [ClassifyResult]（不需要走"待确认"草稿/确认通知那一整套，
 *    见 [com.linkn.screenintake.capture.CapturePipeline.classifyPhotoAndSave] 头部注释）。
 * 同步阻塞调用，调用方自己套 Dispatchers.IO。
 *
 * 返回的是一个列表：一屏内容里可能同时有好几条各自独立、都值得记的东西（比如一个通知列表
 * 页面里有两笔不同的银行转账，或者聊天记录里有两件不同的事），模型会把它们都放进同一个
 * JSON 数组里返回，而不是只挑一条——调用方（[CapturePipeline]）会把每一条都单独存草稿、
 * 单独弹一条确认通知，互不影响。
 *
 * 接口文档：https://help.aliyun.com/zh/model-studio/qwen-openai-compatible
 * Key 申请：https://bailian.console.aliyun.com （支付宝/微信支付充值，新用户有免费额度）
 */
class QwenClassifier(
    private val apiKey: String,
    private val rules: String,
    // 卡片管理里已经建好的卡/账户名字——给模型识别"转账"类型用：收款方/付款方要是
    // 能在这份名单里对上号（或者文字本身就写着"信用卡还款""本人账户互转"这类字样），
    // 说明这是用户自己名下账户之间挪钱，不是真的花出去/收进来一笔钱，应该判成
    // type="transfer" 而不是 expense/income。默认空列表（还没建过卡片管理），
    // 这种情况下模型只能靠文字本身的措辞判断，判不准就仍按 expense/income 处理，
    // 不强求。
    private val cardNames: List<String> = emptyList()
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun classify(screenText: String): List<ClassifyResult> {
        val body = JSONObject().apply {
            put("model", MODEL)
            put(
                "messages",
                JSONArray()
                    .put(
                        JSONObject().apply {
                            put("role", "system")
                            put("content", buildSystemPrompt(rules, cardNames))
                        }
                    )
                    .put(
                        JSONObject().apply {
                            put("role", "user")
                            put("content", buildUserPrompt(screenText))
                        }
                    )
            )
        }

        val request = Request.Builder()
            .url("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("content-type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { resp ->
            val respText = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw ClassifyException("接口返回 ${resp.code}：${respText.take(300)}")
            }
            val text = extractText(respText)
            return parseResults(text)
        }
    }

    /** 组合键触发的主路径：把当前屏幕的一张静默截图发给视觉模型判断，不管页面是用普通控件、
     * WebView 还是动画/Canvas 画出来的，模型看到的都是最终画面本身，不依赖界面暴露了哪些
     * 无障碍文字节点——这是切到截图路径的根本原因。截图只在内存里转一下就发出去，函数返回后
     * 没有任何地方再持有这张图。 */
    fun classifyScreenshot(imageBase64: String): List<ClassifyResult> = classifyWithImage(
        imageBase64,
        "这是当前手机屏幕的实时截图（不是相机拍照），请看图判断屏幕上显示的内容并按系统提示词" +
            "的规则回 JSON。截图里可能带着状态栏、导航栏、按钮这些界面元素，这些不是需要记录的" +
            "内容，请自动忽略，只关注屏幕上真正的信息本身（比如支付结果、转账金额、聊天内容、" +
            "笔记等）。"
    )

    /**
     * 长按拍照专用的最小分类器：不走上面九种类型的完整系统提示词，只让模型看一眼这张
     * 三餐/饮料照片，判断是"吃的"还是"喝的"，附一句极简描述（比如"牛肉面""一杯拿铁"），
     * 用来在 健康 tab 里自动分到 饮食/饮料 两个文件夹——不做热量、成分这类更深的分析，
     * 那些留给以后同步给 Mac 之后更强的模型。判断不出来的兜底成 "meal"，不抛异常；
     * 只有网络/接口本身出问题，或者返回内容完全解析不了 JSON 时才抛异常，调用方
     * （[com.linkn.screenintake.capture.CapturePipeline.classifyPhotoAndSave]）接住之后
     * 把照片按"饮食"兜底存下来，不会因为这次判断失败就把拍的照片弄丢。
     */
    fun classifyMealOrDrink(imageBase64: String): PhotoClassification {
        val userContent = JSONArray()
            .put(
                JSONObject().apply {
                    put("type", "text")
                    put(
                        "text",
                        """
                        这是一张长按拍照记录三餐/饮料的照片。请判断这张照片里拍的主要是「吃的
                        东西」还是「喝的东西」，只回一个 JSON 对象，不要 markdown 代码块标记，
                        不要任何 JSON 以外的文字，格式：{"category":"meal 或 drink","summary":
                        "一句话描述拍的是什么，比如'牛肉面'、'一杯拿铁'、'两瓶啤酒'，不超过 15
                        个字"}。实在看不出是吃的还是喝的（比如拍糊了、拍的根本不是食物饮料），
                        category 填 "meal" 兜底即可，summary 照实描述看到的东西。
                        """.trimIndent()
                    )
                }
            )
            .put(
                JSONObject().apply {
                    put("type", "image_url")
                    put(
                        "image_url",
                        JSONObject().put("url", "data:image/jpeg;base64,$imageBase64")
                    )
                }
            )

        val body = JSONObject().apply {
            put("model", VISION_MODEL)
            put(
                "messages",
                JSONArray()
                    .put(
                        JSONObject().apply {
                            put("role", "user")
                            put("content", userContent)
                        }
                    )
            )
        }

        val request = Request.Builder()
            .url("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("content-type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { resp ->
            val respText = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw ClassifyException("接口返回 ${resp.code}：${respText.take(300)}")
            }
            val text = extractText(respText)
            val cleaned = text.trim()
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```")
                .trim()
            val objStart = cleaned.indexOf('{')
            val objEnd = cleaned.lastIndexOf('}')
            if (objStart < 0 || objEnd < objStart) {
                throw ClassifyException("模型没有返回可解析的 JSON：${text.take(200)}")
            }
            val json = JSONObject(cleaned.substring(objStart, objEnd + 1))
            val category = if (json.optString("category") == "drink") "drink" else "meal"
            return PhotoClassification(category = category, summary = json.optStringOrNull("summary"))
        }
    }

    private fun classifyWithImage(imageBase64: String, promptText: String): List<ClassifyResult> {
        val userContent = JSONArray()
            .put(
                JSONObject().apply {
                    put("type", "text")
                    put("text", promptText)
                }
            )
            .put(
                JSONObject().apply {
                    put("type", "image_url")
                    put(
                        "image_url",
                        JSONObject().put("url", "data:image/jpeg;base64,$imageBase64")
                    )
                }
            )

        val body = JSONObject().apply {
            put("model", VISION_MODEL)
            put(
                "messages",
                JSONArray()
                    .put(
                        JSONObject().apply {
                            put("role", "system")
                            put("content", buildSystemPrompt(rules, cardNames))
                        }
                    )
                    .put(
                        JSONObject().apply {
                            put("role", "user")
                            put("content", userContent)
                        }
                    )
            )
        }

        val request = Request.Builder()
            .url("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("content-type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { resp ->
            val respText = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw ClassifyException("接口返回 ${resp.code}：${respText.take(300)}")
            }
            val text = extractText(respText)
            return parseResults(text)
        }
    }

    private fun buildUserPrompt(screenText: String): String =
        "以下是无障碍服务从当前屏幕文字节点里提取到的内容（大致按界面顺序排列，" +
            "可能夹杂按钮、标签等无关文字），请按系统提示词的规则判断并只回 JSON：\n\n$screenText"

    private fun extractText(responseJson: String): String {
        val root = JSONObject(responseJson)
        val choices = root.optJSONArray("choices")
            ?: throw ClassifyException("返回格式不对：${responseJson.take(300)}")
        if (choices.length() == 0) throw ClassifyException("返回内容为空")
        val message = choices.getJSONObject(0).optJSONObject("message")
            ?: throw ClassifyException("返回内容为空")
        return message.optString("content")
    }

    /**
     * 解析模型回复：正常情况下是一个 JSON 数组（哪怕只有一条也是数组）。为了不被模型偶尔
     * 抽风直接回单个对象搞挂，兜底也支持解析成只有一条的列表。数组本身是空的（模型判断
     * 这屏什么都不用记）也兜底成一条「忽略」，不让调用方处理空列表。
     */
    private fun parseResults(rawText: String): List<ClassifyResult> {
        val cleaned = rawText.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()
        val arrStart = cleaned.indexOf('[')
        val objStart = cleaned.indexOf('{')

        val results: List<ClassifyResult> = when {
            arrStart >= 0 && (objStart < 0 || arrStart < objStart) -> {
                val arrEnd = cleaned.lastIndexOf(']')
                if (arrEnd < arrStart) {
                    throw ClassifyException("模型没有返回可解析的 JSON：${rawText.take(200)}")
                }
                val arr = JSONArray(cleaned.substring(arrStart, arrEnd + 1))
                (0 until arr.length()).map { parseOne(arr.getJSONObject(it)) }
            }
            objStart >= 0 -> {
                // 兜底：模型这次没按数组格式回，当成只有一条处理
                val objEnd = cleaned.lastIndexOf('}')
                if (objEnd < objStart) {
                    throw ClassifyException("模型没有返回可解析的 JSON：${rawText.take(200)}")
                }
                listOf(parseOne(JSONObject(cleaned.substring(objStart, objEnd + 1))))
            }
            else -> throw ClassifyException("模型没有返回可解析的 JSON：${rawText.take(200)}")
        }

        return results.ifEmpty {
            listOf(ClassifyResult(type = "ignore", reason = "模型返回了空列表，没有判断出任何内容"))
        }
    }

    private fun parseOne(json: JSONObject): ClassifyResult = ClassifyResult(
        type = json.optString("type", "ignore"),
        amount = if (json.has("amount") && !json.isNull("amount")) json.optDouble("amount") else null,
        merchant = json.optStringOrNull("merchant"),
        category = json.optStringOrNull("category"),
        summary = json.optStringOrNull("summary"),
        detail = json.optStringOrNull("detail"),
        who = json.optStringOrNull("who"),
        whenText = json.optStringOrNull("when"),
        source = json.optStringOrNull("source"),
        reason = json.optStringOrNull("reason"),
        domain = json.optStringOrNull("domain"),
        dueAt = json.optStringOrNull("dueAt"),
        transactionAt = json.optStringOrNull("transactionAt"),
        card = json.optStringOrNull("card"),
        fromCard = json.optStringOrNull("fromCard"),
        toCard = json.optStringOrNull("toCard"),
        tradeSide = json.optStringOrNull("tradeSide"),
        market = json.optStringOrNull("market"),
        stockCode = json.optStringOrNull("stockCode"),
        stockName = json.optStringOrNull("stockName"),
        shares = if (json.has("shares") && !json.isNull("shares")) json.optDouble("shares") else null,
        price = if (json.has("price") && !json.isNull("price")) json.optDouble("price") else null
    )

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) getString(key) else null

    class ClassifyException(message: String) : Exception(message)

    companion object {
        // 文字路径用纯文本模型即可，便宜档，识别不准可以换成 "qwen-plus-latest" 或 "qwen-max"
        private const val MODEL = "qwen-plus"

        // 视觉模型，截屏路径 [classifyScreenshot] 和长按拍照路径 [classifyMealOrDrink] 共用
        private const val VISION_MODEL = "qwen-vl-plus"

        private fun buildSystemPrompt(rules: String, cardNames: List<String>): String {
            val today = java.time.LocalDate.now()
            val todayStr = today.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
            val weekday = today.dayOfWeek.getDisplayName(
                java.time.format.TextStyle.FULL, java.util.Locale.CHINA
            )
            return """
            你是用户的私人助理，负责判断一段文字或一张照片里有哪些值得记录的内容，并抽取结构化信息。
            只回 JSON，不要 markdown 代码块标记，不要任何 JSON 以外的文字。

            今天的真实日期是 $todayStr（$weekday），涉及"明天""下周三""周五"这类相对时间
            表达时，请自己换算成具体日期。

            重要：输出必须是一个 JSON 数组，数组里的每一项是下面九种格式之一。哪怕只判断出
            一条内容，也要用只有一项的数组包起来，不能直接回一个裸的对象。
            如果这一屏 / 这张照片里出现了多条各自独立、都值得记录的内容——比如同一个通知列表
            里有两笔不同的转账、聊天记录里同时提到两件不同的事、账单页面里有好几笔不同的
            消费——请把每一条都作为数组里单独的一项列出来，不要只挑其中一条、也不要把不同的
            事情合并成一条。只有一条内容的话，数组里就放这一项。

            九种类型，按文字内容判断：

            1) 微信/支付宝/银行等的付款成功、转账成功、扣款、动账提醒里的文字，钱是花出去的
               → type = "expense"
               {"type":"expense","amount":35.00,"merchant":"瑞幸咖啡","category":"餐饮","summary":"原始文字摘要","transactionAt":"2026-09-16T14:23:00"}
               category 只能是以下之一，不要自己发明新的分类名：
               ${Categories.EXPENSE.joinToString("、")}
               这类内容不止出现在付款成功页面本身，银行/微信支付/支付宝推送的"交易成功""动账
               提醒"消息（不管是在聊天记录、服务通知列表还是通知栏里）只要能看到金额和交易类型，
               同样按这条规则判断成 expense，不要因为它是"提醒消息"而不是"付款页面"就归到别的
               类型或忽略。

               但如果转出去的这笔钱最终去向是用户自己名下的另一张卡/账户——比如"信用卡
               还款""偿还信用卡""转入本人尾号XXXX储蓄卡""把工资卡的钱转到零钱卡"这类
               措辞，或者收款方账户名能在下面"用户已经建好的卡片/账户"名单里对上号——
               这笔钱并没有真的花出去，只是在自己的账户之间挪了个地方，不要判成 expense，
               应该判成下面第 7) 类 type="transfer"。转账给别人（朋友、商家、非本人账户）
               才是真的花出去，仍然按 expense 处理。

            2) 工资到账、转账收款、退款、报销到账、理财收益到账等钱是收进来的
               → type = "income"
               {"type":"income","amount":8000.00,"merchant":"XX公司","category":"工资","summary":"原始文字摘要","transactionAt":"2026-09-16T09:05:00"}
               category 只能是以下之一，不要自己发明新的分类名：
               ${Categories.INCOME.joinToString("、")}
               跟第 1 条一样，银行/微信支付/支付宝推送的"到账""动账提醒"消息只要能看出钱是
               收进来的，同样按这条规则判断成 income。同一条"动账提醒"先看清楚钱的方向再判断
               是 expense 还是 income，实在看不出方向的按 expense 处理、category 填"其他"。

               同样地，如果这笔钱是从用户自己名下的另一张卡/账户转过来的（比如刚才那张信用
               卡还款的另一头，或者两张储蓄卡之间互转），也不算真的收入，应该判成下面第 7)
               类 type="transfer"，不要判成 income。别人转给你的钱、工资、退款这些才是真的
               income。

               transactionAt（第 1、2 类都适用）：如果截图或文字里能看出这笔钱实际发生的
               具体日期+时间（比如付款成功页面上的"2026-09-16 14:23:05"这种时间戳，或者
               聊天记录里明确提到"昨天中午"这种能换算出具体日期的表达），请换算成
               "yyyy-MM-ddTHH:mm:ss"格式填进 transactionAt，用来给这笔账标注真实发生时间
               （用户经常会隔一天才补记之前的消费，需要靠这个字段而不是当前时间来排序）；
               只看得出日期看不出具体时间点的，时间部分填 00:00:00；完全看不出任何时间信息
               的，这个字段留空，不要瞎猜——留空时 App 会自动用记录当下的时间兜底，不影响
               正常使用。

               card（第 1、2 类都适用）：如果截图里能看出这笔钱用的是哪张卡/账户（比如付款
               方式一栏写着"招商银行储蓄卡(6789)""建设银行信用卡"这些字样），把这段文字
               原样填进 card 字段；看不出用的哪张卡（比如显示的是"零钱""余额宝"，或者压根
               没提到卡/账户信息）就留空，不要凭猜测编一个。

            3) 跟别人约定的事、任务、提醒，或者自己给自己定的提醒事项（不一定来自聊天记录，
               手动打字直接说"提醒我明天...""记得...""待办：..."这种也算）→ type = "todo"
               {"type":"todo","summary":"跟老王约下周三见面谈续签","who":"老王","when":"下周三","source":"微信","dueAt":"2026-09-23T15:00:00","domain":"工作"}
               {"type":"todo","summary":"晨会问厂配容业务的进度","when":"明天早晨8点15分","dueAt":"2026-09-16T08:15:00","domain":"工作"}
               dueAt 极其重要，是这条待办以后能不能真正提醒到用户的唯一依据，请特别仔细：
               只要文字里出现任何能确定具体日期+时间的表达（"明天早晨八点15分""下周三下午3点"
               "9月20号中午12点"这些），必须原样换算填进 dueAt，格式必须是
               "yyyy-MM-ddTHH:mm:ss"，几点几分要精确对应文字里说的，不能只取整点或者漏填分钟；
               只提到日期没提到具体几点，时间部分统一填 09:00:00；完全看不出任何时间信息（比如
               只说"有空的时候""回头"）才允许不填这个字段——但只要文字里有时间信息，就一定要
               填，这是硬性要求，不是可选项。

            4) 其他任何值得记下来的想法、笔记、网页内容、灵感 → type = "note"
               {"type":"note","summary":"一句话概括","detail":"更完整一点的内容，两三句话即可","domain":"其他"}

               domain（第 3、4 类都适用）：这条待办/灵感归到"财务、健康、工作、习惯、其他"
               五个里的哪一个，App 底部按这四个领域分了 Tab，需要靠这个字段决定显示在哪儿。
               大致按内容判断：提到钱、账单、还款、报销、投资这些 → "财务"；提到身体、运动、
               饮食、睡眠、看病、体检这些 → "健康"；提到工作、客户、会议、汇报、同事、项目
               这些 → "工作"；提到"坚持""打卡""每天/每周做某件小事"这种日常习惯养成
               （不是工作任务也不是健康问题本身）→ "习惯"；实在看不出跟这四块哪个相关，或者
               是纯粹的生活感想、跟工作生活都不沾边的想法 → "其他"。拿不准就填"其他"，不要
               为了凑一个领域而牵强判断——这个字段判错了后果很轻（用户在 App 里改一下就好），
               不像 dueAt 那样是硬性要求，但每条 todo/note 都应该填一个，不要空着。

            5) 证券账户里的股票/ETF买入卖出记录（券商App的成交确认页面、持仓变动截图，
               或者用户自己打字描述的一笔交易，比如"买入贵州茅台100股，均价1500"）
               → type = "trade"
               {"type":"trade","tradeSide":"buy","market":"A","stockCode":"600519","stockName":"贵州茅台","shares":100,"price":1500.00,"amount":150000.00,"transactionAt":"2026-09-16T10:30:00"}
               字段说明：
               - tradeSide：买入填"buy"，卖出填"sell"。
               - market：这笔交易属于哪个市场，只能三选一——"A"（沪深A股）、"US"（美股）、
                 "HK"（港股），根据代码格式、货币符号（¥/$/HK$）、券商App界面或者文字里
                 提到的市场自己判断：A股代码通常是6位数字（比如600519、000001），美股是
                 1-5位字母代码（比如AAPL、TSLA），港股是4-5位数字（比如00700、09988）。
                 实在判断不出来就填"A"。
               - stockCode：代码本身，必须是截图上直接看到的、或者用户自己明确说出的数字/
                 字母，原样填，不用额外加交易所前缀。**只报了股票名字、没有明确给代码或者
                 截图上没有代码的情况下，stockCode 一定要留空，绝对不能凭自己的记忆瞎猜一个
                 代码**——代码猜错比不填更危险：系统会拿这个代码去查实时价格，猜错代码会
                 查成完全不相关的另一只股票的价格（比如把"五粮液"错记成"000582"，实际上
                 000582 是北部湾港；把"天立国际控股"错记成"09988"，实际上 09988 是阿里巴巴），
                 冒充成看起来正常但彻底错误的数字，比明显缺代码更容易被忽略过去。stockCode
                 留空的话，系统会退回用 stockName 当识别依据，不影响正常记录。
               - stockName：证券名称，比如"贵州茅台""腾讯控股""苹果"，按截图/文字里出现的
                 原样填；看不出名字只有代码，就只填 stockCode，stockName 留空。
               - shares：股数，美股/港股可能有小数（零股/碎股），照实填，不用取整。
               - price：每股成交价格。
               - amount：这笔交易的总金额，如果截图上直接显示了总金额就按截图的填，没有
                 显示就用股数乘以价格算一下。
               - transactionAt：交易发生的具体时间，规则跟上面 expense/income 的
                 transactionAt 完全一样，看得出具体日期+时间就填 ISO 格式，看不出就留空。

            6) 证券账户当前持仓的状态描述——不是"刚买了/刚卖了"这种交易动作，而是"我现在
               手上有多少"这种存量陈述。典型来源：券商App的"持仓"总览页面截图（列着好几只
               股票，每只显示股数、成本，但没有"买入成功""卖出成功"这类成交字样），或者用户
               自己打字描述持仓现状（比如"我持有贵州茅台100股，成本1500"、"目前腾讯控股
               200股，均价320"）。判断关键：文字/截图里找不到"买入""卖出""成交""交易"这类
               明确的动作/事件字样，只是在陈述"现在持有多少、成本多少"，就归这一类，不要
               归成 trade——归错的话会被系统当成一笔子虚乌有的买卖，产生错误的持仓变动。
               → type = "holding"
               {"type":"holding","market":"A","stockCode":"600519","stockName":"贵州茅台","shares":100,"price":1500.00}
               字段说明（market/stockCode/stockName/shares 规则跟上面 trade 的完全一样）：
               - price：这里填的是成本价（每股的持仓成本），不是最新市价——截图/文字里
                 一般标注为"成本价""成本""摊薄成本"之类，不要跟"现价""市价"搞混。
               - 这个状态会直接覆盖系统里记的这只股票当前持仓（股数、成本都按这次填的为准，
                 不是在原来基础上累加），所以只在用户明确是在陈述"现在的状态"时才用这个
                 类型；一张截图里列了好几只股票，就返回好几条 type="holding" 的结果，
                 每只一条。

            7) 用户自己名下账户/卡之间互转的钱——最常见的是"信用卡还款"（从储蓄卡/零钱转
               一笔到信用卡，冲抵欠款）、"把工资卡的钱转出来"（转到另一张储蓄卡、零钱、
               其他自己的账户）。判断关键：转出方和转入方最终都是同一个人（用户本人）
               名下的账户，钱只是换了个地方放，没有真的变多也没有真的变少，不属于第 1)、
               2) 条的 expense/income
               → type = "transfer"
               {"type":"transfer","amount":2000.00,"fromCard":"招商银行储蓄卡(6789)","toCard":"建设银行信用卡","summary":"原始文字摘要","transactionAt":"2026-09-16T20:10:00"}
               字段说明：
               - fromCard：转出的那个账户/卡，原样保留截图/文字里看到的文字（比如"招商
                 银行储蓄卡(6789)""工资卡""零钱"）；看不出具体是哪张就留空，不要瞎猜。
               - toCard：转入的那个账户/卡，规则跟 fromCard 一样。信用卡还款场景下，
                 toCard 填的是被还款的那张信用卡。
               - amount：转账金额。
               - transactionAt：规则跟前面 expense/income 一样，看得出具体时间就填，
                 看不出就留空。
               判断这是不是"自己账户之间互转"，主要看两点：①文字本身有没有"还款""本人""
               自己的账户""互转"这类字样；②转出方或转入方的名字，能不能在下面这份用户
               已经在「卡片管理」里建好的账户名单里对上号——名单不为空时优先参考：
               ${if (cardNames.isEmpty()) "（用户还没在卡片管理里建过任何卡/账户，这里暂时是空的，只能靠文字本身的措辞判断）" else cardNames.joinToString("、")}
               两点都对不上、又看不出任何"这是本人账户"的线索时，说明更可能是转给了别人，
               请按第 1)/2) 条的 expense/income 处理，不要为了凑 transfer 类型而牵强判断。

            8) 体重秤/健康类 App/微信运动等界面上显示的体重读数截图（不是拍照，长按拍照
               那条路径不会走到这里）→ type = "weight"
               {"type":"weight","amount":68.5,"summary":"原始文字摘要","transactionAt":"2026-09-16T07:15:00"}
               字段说明：
               - amount：这里存的是体重数值，统一换算成"千克"再填——截图上如果显示的单位是
                 "斤"，换算成千克要乘以 0.5（比如"137斤"填 68.5，不是 137）；如果是磅(lb)，
                 乘以 0.453592 换算成千克；看不出单位、或者数值本身明显就是常见体重范围
                 （几十到一百多）时，默认当成已经是千克，不要重复换算。
               - transactionAt：规则跟前面 expense/income 一样，看得出具体时间就填，
                 看不出就留空。

            9) 提取到的文字看不出是什么、或者是不值得记录的内容（游戏界面、系统菜单、无关网页等）→ type = "ignore"
               {"type":"ignore","reason":"一句话说明为什么忽略"}

            用户的分类偏好（优先参考，与上面规则冲突时以这里为准）：
            $rules

            提取到的文字里可能夹杂界面上的按钮、标签、导航栏等无关词句，请自行忽略这些噪音，
            抓住其中真正的内容判断。拿不准具体分类时，type 仍然按实际情况判断（比如金额和
            "支付成功""动账提醒"字样很明确就是 expense），category 或其他细分字段拿不准就填
            "其他"或留空，不要因为拿不准某个字段就整体判成 ignore。
        """.trimIndent()
        }
    }
}

/** [QwenClassifier.classifyMealOrDrink] 的返回结果：category 是 "meal" 或 "drink"，
 * summary 是模型给的一句话描述，判断失败时可能为 null。 */
data class PhotoClassification(val category: String, val summary: String?)
