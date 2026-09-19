package com.linkn.screenintake.capture

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.widget.Toast
import com.linkn.screenintake.ScreenIntakeApp
import com.linkn.screenintake.classify.ClassifyResult
import com.linkn.screenintake.classify.QwenClassifier
import com.linkn.screenintake.health.HealthDataRepository
import com.linkn.screenintake.classify.RelativeDateNormalizer
import com.linkn.screenintake.settings.SecureSettingsStore
import com.linkn.screenintake.store.LedgerReader
import com.linkn.screenintake.store.RecordStore
import com.linkn.screenintake.store.SyncNotificationWorker

/**
 * 几条捕获路径的完整流程都在这里：组合键静默截屏 / 手动打字 → 发给模型判断 → 按类型
 * 写文件或弹确认通知 → 提示用户；长按拍照是例外，只用一个很轻量的独立提示词判断
 * "这是吃的还是喝的"（见 [classifyPhotoAndSave]），不走上面的九种类型完整分类，
 * 也不弹「待确认」——判断完直接自动归档。任何一步出错都不让人静默丢失这次内容：
 * 文字/截屏识别失败兜底存进「待确认」；拍照这条路径哪怕 AI 判断失败，照片本身也照样
 * 保留（按"饮食"兜底归档），只是另外留一条「待确认」诊断记录方便回头看。
 *
 * 模型一次可能判断出不止一条值得记的内容（比如一屏里有两笔不同的转账），[QwenClassifier]
 * 会把它们都放在一个列表里返回，这里对列表里的每一条都各自走一遍落盘 / 确认通知，互不
 * 影响——确认其中一条不会影响另一条，划掉一条的通知另一条的草稿还在。
 */
class CapturePipeline(private val context: Context) {

    /**
     * @param skipConfirmation 手动文字输入用——内容是你自己确认过的，不用再弹通知问一遍，
     * 直接落盘。截屏 / 拍照来的内容一律走确认通知，「忽略」也不例外——你会触发捕获，
     * 通常就是因为屏幕上确实有什么值得记的东西，模型判断成「忽略」反而是最该让你看一眼、
     * 有机会纠正的情况，不能因为判断成忽略就悄悄跳过。
     */
    fun classifyAndRoute(screenText: String, skipConfirmation: Boolean = false) {
        val settings = ScreenIntakeApp.instance.settingsStore
        if (!ensureReady(settings)) return
        if (screenText.isBlank()) {
            // 这种情况之前是纯震动+提示，什么痕迹都不留，出了问题都不知道多久发生一次。
            // 现在也存一份到「待确认」，方便回头看是不是总在同一个 App / 同一种情况下发生。
            toast("没读到屏幕上的文字，换个界面再试")
            vibrate(FAIL_PATTERN)
            saveFailureTrace(
                settings,
                "",
                "捕获于 ${System.currentTimeMillis()}，读到的屏幕文字是空的" +
                    "（无障碍节点里没有可读文字，常见原因是触发时机太快、界面还没渲染完）"
            )
            return
        }

        toast("已上传云端模型，识别中…")
        try {
            val classifier = QwenClassifier(settings.apiKey, settings.classifyRules, currentCardNames(settings), settings.expenseCategories)
            val results = classifier.classify(screenText).map { RelativeDateNormalizer.normalize(it, screenText) }
            finishWithResults(results, screenText, settings, skipConfirmation)
        } catch (e: Exception) {
            Log.e(TAG, "分类或写入失败", e)
            handleClassifyFailure(settings, screenText, e)
        }
    }

    /**
     * 长按音量上键拍照那条路径：三餐、饮料这类日常照片。只用一个很轻量的独立提示词
     * （[QwenClassifier.classifyMealOrDrink]）判断"这是吃的还是喝的"外加一句极简描述，
     * 不做热量/成分这类更深的分析，也不走上面九种类型的完整分类流程——判断完直接自动
     * 存进"日常照片/三餐/"或"日常照片/饮料/"，不弹「待确认」，不需要你每拍一次都点一下
     * 确认（一天要拍好几次，那样太烦）。AI 这一步万一判断错了类（比如把喝的分到了
     * 饮食），去 健康 tab 对应的照片列表里手动挪一下就行。AI 调用本身失败（网络问题、
     * 返回解析不了）不会丢失这次拍照——照片依然按"饮食"兜底存下来，同时留一条
     * 「待确认」诊断记录，方便回头看是不是经常失败。
     */
    fun classifyPhotoAndSave(imageBytes: ByteArray) {
        val settings = ScreenIntakeApp.instance.settingsStore
        if (!ensureReady(settings)) return
        val base64 = android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP)
        toast("已上传云端模型，识别中…")
        var category = "meal"
        var caption: String? = null
        var alcoholCandidate = false
        var classifyFailed = false
        var failureReason = ""
        try {
            val classifier = QwenClassifier(settings.apiKey, settings.classifyRules, currentCardNames(settings), settings.expenseCategories)
            val result = classifier.classifyMealOrDrink(base64)
            category = result.category
            caption = result.summary
            alcoholCandidate = result.isAlcohol
        } catch (e: Exception) {
            Log.e(TAG, "拍照分类失败", e)
            classifyFailed = true
            failureReason = e.message ?: "未知错误"
        }
        try {
            val fileName = RecordStore(context).savePhoto(settings.folderUri, imageBytes, category, caption)
            if (alcoholCandidate) {
                HealthDataRepository(context).createAlcoholCandidate(settings.folderUri, caption ?: "识别到可能饮酒", fileName)
                SyncNotificationWorker.scanNow(context)
            }
            if (classifyFailed) {
                toast("拍照已保存，不过没识别出是吃的还是喝的，先归到了「饮食」")
            } else {
                val label = if (category == "drink") "饮料" else "饮食"
                toast(if (alcoholCandidate) "已保存照片，请确认饮酒记录" else "已记一张$label：${caption ?: fileName}")
            }
            vibrate(OK_PATTERN)
        } catch (e: Exception) {
            Log.e(TAG, "保存照片失败", e)
            toast("保存照片失败：${e.message}")
            vibrate(FAIL_PATTERN)
        }
    }

    /** 组合键触发的主路径：无障碍服务已经在内存里把当前屏幕转成一张截图（没有落盘），
     * 这里直接把它转成 base64 送去视觉模型判断，流程跟拍照路径完全一致——图片本身
     * 用完即弃，这个函数返回之后没有任何地方还留着这张截图。 */
    fun classifyScreenshotAndRoute(imageBase64: String) {
        val settings = ScreenIntakeApp.instance.settingsStore
        if (!ensureReady(settings)) return
        toast("已上传云端模型，识别中…")
        try {
            val classifier = QwenClassifier(settings.apiKey, settings.classifyRules, currentCardNames(settings), settings.expenseCategories)
            val results = classifier.classifyScreenshot(imageBase64).map { RelativeDateNormalizer.normalize(it, "") }
            finishWithResults(results, "（截屏识别，没有屏幕文字，截图没有保留）", settings, skipConfirmation = false)
        } catch (e: Exception) {
            Log.e(TAG, "截屏分类失败", e)
            handleClassifyFailure(settings, "（截屏识别失败，截图没有保留）", e)
        }
    }

    /** 静默截屏本身就没能拿到图片（比较少见：系统短时间内限频、内部错误等）——不退回
     * 文字读取兜底，直接照抄"读屏读到空文字"那一套处理：存进「待确认」、toast 提示，
     * 不静默丢失这次触发。 */
    fun recordScreenshotFailure() {
        val settings = ScreenIntakeApp.instance.settingsStore
        if (!ensureReady(settings)) return
        toast("截屏没成功，换个时机再试一次")
        vibrate(FAIL_PATTERN)
        saveFailureTrace(
            settings,
            "",
            "捕获于 ${System.currentTimeMillis()}，静默截屏没有拿到图片" +
                "（可能是系统短时间内限频或内部错误）"
        )
    }

    /** 「卡片管理」里已经建好的卡/账户名字，给分类模型识别"转账"类型用（见 QwenClassifier
     * 头部注释）——读不到就当空列表，不影响其余分类照常进行，只是转账类型这块只能靠
     * 文字措辞本身判断，识别率会打折扣。 */
    private fun currentCardNames(settings: SecureSettingsStore): List<String> = try {
        LedgerReader.readCards(context, settings.folderUri).map { it.name }
    } catch (e: Exception) {
        emptyList()
    }

    private fun ensureReady(settings: SecureSettingsStore): Boolean {
        if (!settings.isReady()) {
            toast("还没配好：请先在设置里填 API Key、选择保存文件夹")
            vibrate(FAIL_PATTERN)
            return false
        }
        return true
    }

    /**
     * 分类成功之后统一走这里：手动输入直接落盘，读屏/拍照一律先弹确认通知。一次可能有
     * 好几条结果，每一条都各自走一遍——草稿文件名、通知 ID 都按序号区分开，互不覆盖。
     *
     * 落盘/弹通知之前先过一遍 [RecentCaptureDedup]：跟 20 秒内刚处理过的内容一模一样
     * 的，直接跳过，不再记一遍、也不再弹一条通知——这是防「手滑连按两次组合键」或者
     * 「对着同一屏幕/同一张截图又触发了一次」，不是真的按内容本身去重，所以像今天和
     * 明天都买同一瓶水、隔几天交一次一样金额的停车费这种正常重复消费不会受影响。
     */
    private fun finishWithResults(
        results: List<ClassifyResult>,
        sourceText: String,
        settings: SecureSettingsStore,
        skipConfirmation: Boolean
    ) {
        val store = RecordStore(context)
        val fresh = results.filter { result ->
            val signature = result.signature()
            signature == null || !RecentCaptureDedup.checkAndRemember(signature)
        }
        if (fresh.size < results.size) {
            Log.d(TAG, "跳过了 ${results.size - fresh.size} 条重复内容（20 秒内出现过一样的）")
        }
        if (fresh.isEmpty()) {
            // 这次识别到的都跟刚刚重复，安静提示一下就好，不用再弹通知、也不用再写文件
            toast("跟刚刚重复，没有再记")
            vibrate(IGNORE_PATTERN)
            return
        }

        if (skipConfirmation) {
            // 手动输入：你自己已经确认过内容，直接写——但待办事项有一条硬性要求，必须
            // 有具体提醒时间，不能是「有这么件事，但不知道啥时候提醒」。手动打字这种
            // 输入本该是最容易说清楚时间的，可模型偶尔还是会漏抽（比如这次没把「明天
            // 早晨八点15分」识别成 dueAt），一旦漏了就没有第二次机会——不像读屏/拍照
            // 还有确认通知能补救。所以这里单独把「待办但没有 dueAt」的结果摘出来，退回
            // 跟读屏/拍照一样的确认草稿流程，去「待确认」里用编辑弹窗的时间选择器补上，
            // 而不是悄悄存一条以后也提醒不了你的待办。
            // 股票交易/持仓设置/转账也一律退回确认流程，即使是手动打字：这类内容一旦记错
            // （代码认错、股数敲错，或者转账认错了转出/转入的卡），会悄悄污染持仓.csv 的
            // 滚动计算、覆盖掉正确的持仓状态，或者错改两张卡的余额，不像消费/收入记错一笔
            // 改一下就好，值得多一道确认，跟"待办缺时间"归为同一类"不能悄悄放行"的例外。
            val needsReview = fresh.filter { (it.isTodo && it.dueAt.isNullOrBlank()) || it.isTrade || it.isHolding || it.isTransfer }
            val autoRoute = fresh.filterNot { it in needsReview }

            // 手动输入这条路径专用的兜底：模型判成「忽略」意味着 route() 什么都不会存
            // （见 RecordStore.route 的 "ignore" 分支），但这里的内容是自己一个字一个字
            // 打出来的，不是随手截的屏——判成忽略这件事本身更可能是模型看走眼，而不是真的
            // 不值得记；不像截屏/拍照，手动打字没有"顺手再来一次"的低成本，悄悄丢掉就是
            // 白打了。所以这里退一步改存成灵感（用打的原文当内容），好过真的凭空消失，
            // 判错了域大不了去灵感里删掉/挪一下——跟截屏来的「忽略」草稿不是一回事：那边
            // 是先弹通知等你确认，你确认"没错就是该忽略"时才真的什么都不存，两种路径的
            // 「忽略」代表的信任程度本来就不一样。
            val messages = autoRoute.map { result ->
                val toRoute = if (result.isIgnore) {
                    result.copy(type = "note", summary = sourceText, reason = null)
                } else {
                    result
                }
                store.route(settings.folderUri, toRoute)
            }.toMutableList()
            if (needsReview.isNotEmpty()) {
                val baseId = System.currentTimeMillis()
                needsReview.forEachIndexed { index, result ->
                    val draftId = "${baseId}_review_$index"
                    store.savePendingDraft(settings.folderUri, draftId, result, sourceText)
                    PendingCaptureNotifier.show(context, draftId, result, sourceText)
                }
                val todoCount = needsReview.count { it.isTodo }
                val tradeCount = needsReview.count { it.isTrade }
                val holdingCount = needsReview.count { it.isHolding }
                val transferCount = needsReview.count { it.isTransfer }
                val parts = mutableListOf<String>()
                if (todoCount > 0) parts.add("$todoCount 条待办没识别出具体时间")
                if (tradeCount > 0) parts.add("$tradeCount 条股票交易")
                if (holdingCount > 0) parts.add("$holdingCount 条持仓设置")
                if (transferCount > 0) parts.add("$transferCount 条转账")
                messages.add("${parts.joinToString("，")}，已放进「待确认」，去确认一下")
            }
            toast(messages.joinToString("\n"))
            // 手动输入这条路径下已经没有真的被悄悄丢掉的内容了（忽略也改存成灵感，
            // 见上面），统一用「成功」震动，不用再区分忽略/非忽略。
            vibrate(OK_PATTERN)
        } else {
            // 读屏来的内容：不管判断成什么类型（包括「忽略」），一律先存草稿、弹通知等你
            // 确认或编辑，不直接落盘。多条结果就是多份草稿、多条通知，确认或划掉其中一条
            // 不影响别的。
            val baseId = System.currentTimeMillis()
            fresh.forEachIndexed { index, result ->
                val draftId = "${baseId}_$index"
                store.savePendingDraft(settings.folderUri, draftId, result, sourceText)
                PendingCaptureNotifier.show(context, draftId, result, sourceText)
            }
            toast(
                if (fresh.size == 1) "识别完成，1 条内容已放进「待确认」，去确认一下"
                else "识别完成，${fresh.size} 条内容已放进「待确认」，去确认一下"
            )
            vibrate(OK_PATTERN)
        }
    }

    private fun handleClassifyFailure(settings: SecureSettingsStore, sourceText: String, e: Exception) {
        saveFailureTrace(settings, sourceText, "捕获于 ${System.currentTimeMillis()}，失败原因：${e.message}")
        vibrate(FAIL_PATTERN)
    }

    private fun saveFailureTrace(settings: SecureSettingsStore, sourceText: String, reason: String) {
        try {
            RecordStore(context).saveUnconfirmed(settings.folderUri, sourceText, reason)
            toast("识别没成功，已把内容存进「待确认」文件夹")
        } catch (inner: Exception) {
            Log.e(TAG, "连兜底保存都失败了", inner)
            toast("捕获失败：$reason")
        }
    }

    private fun toast(message: String) {
        android.os.Handler(context.mainLooper).post {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun vibrate(pattern: LongArray) {
        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            val mgr = context.getSystemService(VibratorManager::class.java)
            mgr?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }

    companion object {
        private const val TAG = "CapturePipeline"

        // 震动反馈：一短=成功记录，两短=已忽略，长震=出问题
        private val OK_PATTERN = longArrayOf(0, 60)
        private val IGNORE_PATTERN = longArrayOf(0, 40, 80, 40)
        private val FAIL_PATTERN = longArrayOf(0, 200)
    }
}
