package com.linkn.screenintake.store

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.linkn.screenintake.classify.ClassifyResult
import com.linkn.screenintake.classify.ExpensePurpose
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.json.JSONObject

/**
 * 把分类结果落盘到用户在设置里选定的那个文件夹（SAF tree），同一个文件夹建议就是
 * Syncthing / 坚果云之类会同步到电脑的目录。主要文件：
 *   财务/账本.csv —— 支出和收入两种，和 Mac 端脚本读的格式一致（日期,类型,分类,金额,备注），
 *                「类型」这一列是"支出"或"收入"
 *   工作/待办.md —— 待办事项，Markdown 复选框列表
 *   工作/灵感.md —— 秒记的想法/笔记
 *   健康/体重.csv —— 体重截图识别出来的读数，日期,体重_kg,备注
 *   健康/日常照片/三餐/、健康/日常照片/饮料/ —— 长按拍照存下来的原始照片，AI 只做"吃的还是
 *                喝的+一句话描述"这种轻量分类用来自动分文件夹，不做热量/成分这类
 *                更深的分析，那些留给以后同步给 Mac 之后更强的模型去看
 * 分类失败或看不懂的，连提取到的原始文字一起丢进「系统/待确认」，不静默丢弃。
 * 待办事项由 App 自己安排本地通知，不再写入系统或 Google 日历。
 */
class RecordStore(private val context: Context) {

    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
    private val stampFmt = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.CHINA)

    /** 所有写入同一个同步目录的操作必须串行：SAF 文件并不提供数据库事务。 */
    private fun <T> serializedWrite(block: () -> T): T = writeLock.withLock {
        LedgerReader.withStrictReads { VerifiedFileWrite.transaction(context, block) }
    }
    fun reconcilePendingWrites(): String = writeLock.withLock { VerifiedFileWrite.reconcile(context) }

    // Match the exact record the user opened, never blindly reuse its old row number.
    private fun <T> uniqueRecord(rows: List<T>, matches: (T) -> Boolean): T =
        selectUnchangedRecord(rows, matches)

    fun updateLedgerRow(folder: String, original: LedgerRow, updated: LedgerRow) = serializedWrite {
        val current = uniqueRecord(LedgerReader.readLedger(context, folder)) { it.copy(index = original.index) == original }
        updateLedgerRow(folder, current.index, updated)
    }

    fun deleteLedgerRow(folder: String, original: LedgerRow) = serializedWrite {
        val current = uniqueRecord(LedgerReader.readLedger(context, folder)) { it.copy(index = original.index) == original }
        deleteLedgerRow(folder, current.index)
    }

    fun updateTodo(folder: String, original: TodoItem, done: Boolean, text: String, domain: String, dueAt: String?) = serializedWrite {
        val current = uniqueRecord(LedgerReader.readTodos(context, folder)) { it.copy(index = original.index) == original }
        updateTodo(folder, current.index, done, text, domain, dueAt, current.reminderId)
    }

    fun deleteTodo(folder: String, original: TodoItem) = serializedWrite {
        val current = uniqueRecord(LedgerReader.readTodos(context, folder)) { it.copy(index = original.index) == original }
        deleteTodo(folder, current.index)
    }

    fun updateNote(folder: String, original: NoteItem, content: String, domain: String) = serializedWrite {
        val current = uniqueRecord(LedgerReader.readNotes(context, folder)) { it.copy(index = original.index) == original }
        updateNote(folder, current.index, content, domain)
    }

    fun deleteNote(folder: String, original: NoteItem) = serializedWrite {
        val current = uniqueRecord(LedgerReader.readNotes(context, folder)) { it.copy(index = original.index) == original }
        deleteNote(folder, current.index)
    }

    /** 工作 Tab 手动新建待办（必须有提醒时间）。 */
    fun addManualTodo(folder: String, text: String, domain: String, dueAt: String) = serializedWrite {
        require(text.isNotBlank()) { "请填写待办内容" }
        require(dueAt.isNotBlank()) { "待办必须设置提醒时间" }
        val todoId = "todo_" + java.util.UUID.randomUUID().toString()
        val safeDomain = if (domain == "其他" || domain == "生活") "其他" else "工作"
        val meta = JSONObject().put("id", todoId).put("kind", safeDomain).put("dueAt", dueAt).toString()
        val line = "- [ ] [" + safeDomain + "] " + text.trim() + " <!--TODO_META:" + meta + "-->"
        appendText(folder, "待办.md", "text/markdown", "# 待办" + "\n", line)
        runCatching { TodoReminderWorker.schedule(context, todoId, text.trim(), dueAt) }
            .onFailure { android.util.Log.w(TAG, "待办已写入，但本地提醒调度失败：$todoId", it) }
    }

    /** 工作 Tab 手动新建灵感。 */
    fun addManualNote(folder: String, content: String, domain: String) = serializedWrite {
        require(content.isNotBlank()) { "请填写灵感内容" }
        val safeDomain = if (domain == "工作") "工作" else "其他"
        val now = java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        val trimmed = content.trim().replace(Regex("(?m)^## "), "### ")
        val block = "## [" + safeDomain + "] " + now + "\n" + trimmed + "\n"
        appendText(folder, "灵感.md", "text/markdown", "# 灵感与笔记" + "\n", block)
    }

    fun updateCard(folder: String, original: CardAccount, updated: CardAccount) = serializedWrite {
        val current = uniqueRecord(LedgerReader.readCards(context, folder)) { it.copy(index = original.index) == original }
        updateCard(folder, current.index, updated)
    }
    fun deleteCard(folder: String, original: CardAccount) = serializedWrite {
        val current = uniqueRecord(LedgerReader.readCards(context, folder)) { it.copy(index = original.index) == original }
        deleteCard(folder, current.index)
    }
    fun updateHolding(folder: String, original: Holding, updated: Holding) = serializedWrite {
        val current = uniqueRecord(LedgerReader.readHoldings(context, folder)) { it.copy(index = original.index) == original }
        updateHolding(folder, current.index, updated)
    }
    fun deleteHolding(folder: String, original: Holding) = serializedWrite {
        val current = uniqueRecord(LedgerReader.readHoldings(context, folder)) { it.copy(index = original.index) == original }
        deleteHolding(folder, current.index)
    }
    fun updateWeight(folder: String, original: WeightRow, updated: WeightRow) = serializedWrite {
        val current = uniqueRecord(LedgerReader.readWeights(context, folder)) { it.copy(index = original.index) == original }
        updateWeight(folder, current.index, updated)
    }
    fun deleteWeight(folder: String, original: WeightRow) = serializedWrite {
        val current = uniqueRecord(LedgerReader.readWeights(context, folder)) { it.copy(index = original.index) == original }
        deleteWeight(folder, current.index)
    }

    private fun validate(result: ClassifyResult) {
        val finiteAmount = result.amount?.takeIf { it.isFinite() }
        if (result.type in setOf("expense", "income", "transfer", "trade", "weight") &&
            (finiteAmount == null || finiteAmount < 0.0)) {
            throw IllegalArgumentException("金额或数值无效，请调整后再保存")
        }
        if (listOf(result.summary, result.detail, result.merchant).any { it?.length ?: 0 > 4_000 }) {
            throw IllegalArgumentException("识别内容过长，请调整后再保存")
        }
    }

    private fun root(folderUri: String): DocumentFile {
        return HubRoot.resolve(context, folderUri)
            ?: throw IllegalStateException("保存文件夹已失效，请到设置里重新选择")
    }

    /**
     * 账本.csv「日期」列该写什么值：优先用模型从截图/文字里识别出的实际交易时间
     * （result.transactionAt，比如补记昨天忘记录的一笔），解析失败或者模型没识别出来，
     * 就退回到这次记账动作发生的当下时间。两种情况都带上具体到分钟的时间，是为了让
     * FinanceScreen 按时间倒序排序时，同一天内的多笔记录也能按真实先后顺序排好，
     * 而不是靠文件里原来的先后位置去猜（旧数据只有日期没有时间，排序时按字符串比较，
     * 精度不够时退化成原来的日期粒度，不影响兼容）。
     */
    private fun resolveLedgerDate(result: ClassifyResult, now: Date): String {
        val raw = result.transactionAt
        if (!raw.isNullOrBlank()) {
            try {
                val parsed = java.time.LocalDateTime.parse(raw)
                return parsed.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
            } catch (e: Exception) {
                // 格式不对就忽略，走下面的兜底
            }
        }
        return timeFmt.format(now)
    }

    fun route(folderUri: String, result: ClassifyResult): String = serializedWrite {
        validate(result)
        val now = Date()
        when (result.type) {
            "expense", "income" -> {
                val isIncome = result.type == "income"
                val amount = result.amount ?: 0.0
                val defaultCategory = if (isIncome) "其他收入" else "其他"
                val category = result.category?.ifBlank { defaultCategory } ?: defaultCategory
                val note = buildString {
                    result.merchant?.let { append(it) }
                    if (!result.summary.isNullOrBlank()) {
                        if (isNotEmpty()) append(" ")
                        append(result.summary)
                    }
                }
                val typeCn = if (isIncome) "收入" else "支出"
                val taggedNote = ExpensePurpose.withNote(note, if (result.isExpense) result.purpose else null, result.card)
                appendCsvRow(folderUri, resolveLedgerDate(result, now), typeCn, category, amount, taggedNote)
                applyCardDelta(folderUri, result.card, isIncome, amount)
                "已记$typeCn：¥%.2f %s".format(amount, category)
            }
            "todo" -> {
                val todoId = "todo_${UUID.randomUUID()}"
                val todoKind = if (result.domain == "其他" || result.domain == "生活") "其他" else "工作"
                val meta = JSONObject().put("id", todoId).put("kind", todoKind)
                    .put("dueAt", result.dueAt ?: JSONObject.NULL).toString()
                val line = buildString {
                    append("- [ ] ")
                    append("[$todoKind] ")
                    append(timeFmt.format(now))
                    append("  ")
                    append(result.summary ?: "（未提取到内容）")
                    val extra = listOfNotNull(
                        result.who?.let { "对象：$it" },
                        result.whenText?.let { "时间：$it" },
                        result.source?.let { "来源：$it" }
                    )
                    if (extra.isNotEmpty()) append("（${extra.joinToString("，")}）")
                    append(" <!--TODO_META:$meta-->")
                }
                appendText(folderUri, "待办.md", "text/markdown", "# 待办\n", line)
                TodoReminderWorker.schedule(context, todoId, result.summary.orEmpty(), result.dueAt)
                "已记待办：${result.summary ?: ""}"
            }
            "note" -> {
                val noteDomain = if (result.domain == "工作") "工作" else "其他"
                val block = buildString {
                    append("## ")
                    append("[$noteDomain] ")
                    append(timeFmt.format(now))
                    append("\n")
                    append((result.summary ?: "").replace(Regex("(?m)^## "), "### "))
                    if (!result.detail.isNullOrBlank()) {
                        append("\n\n")
                        append(result.detail.replace(Regex("(?m)^## "), "### "))
                    }
                    append("\n")
                }
                appendText(folderUri, "灵感.md", "text/markdown", "# 灵感与笔记\n", block)
                "已记灵感：${result.summary ?: ""}"
            }
            "meal_note" -> {
                val line = "- ${timeFmt.format(now)}  ${result.summary ?: "（未提取到内容）"}"
                appendText(folderUri, "饮食记录.md", "text/markdown", "# 饮食记录\n", line)
                "已记饮食：${result.summary ?: ""}"
            }
            "trade" -> {
                val isSell = result.tradeSide == "sell"
                val sideCn = if (isSell) "卖出" else "买入"
                val market = result.market?.trim()?.uppercase()?.takeIf { it in setOf("A", "US", "HK") } ?: "A"
                val rawCode = result.stockCode?.trim().orEmpty()
                val rawName = result.stockName?.trim().orEmpty()
                // 代码和名称至少要有一个能当「这是哪只股票」的钥匙，用来在持仓.csv 里定位、
                // 累加。两个都没有的极端情况（模型没抽出任何标识），退化成用备注文字当钥匙，
                // 保证不会因为空字符串互相冲突（把好几只不认识的票错误地合并成一只）。
                val key = rawCode.ifBlank { rawName }.ifBlank { "未知标的_${now.time}" }
                val name = rawName.ifBlank { rawCode.ifBlank { "未命名标的" } }
                val shares = result.shares ?: 0.0
                val price = result.price ?: 0.0
                val amount = result.amount ?: (shares * price)
                val dateStr = resolveLedgerDate(result, now)
                appendTradeRow(folderUri, dateStr, sideCn, market, key, name, shares, price, amount, result.summary.orEmpty())
                applyTradeToHoldings(folderUri, market, key, name, isSell, shares, price)
                "已记$sideCn：$name ${ClassifyResult.fmtNum(shares)}股"
            }
            "holding" -> {
                // 跟 "trade" 不一样：这是"现在持有多少"的存量陈述，不是一笔买卖动作，
                // 所以不写交易记录.csv（那是真实成交流水，写进去会污染审计记录），
                // 只直接把持仓.csv 里这只股票的股数/成本设成这次说的数字（覆盖，不是
                // 累加）——见 setHolding 的注释。
                val market = result.market?.trim()?.uppercase()?.takeIf { it in setOf("A", "US", "HK") } ?: "A"
                val rawCode = result.stockCode?.trim().orEmpty()
                val rawName = result.stockName?.trim().orEmpty()
                val key = rawCode.ifBlank { rawName }.ifBlank { "未知标的_${now.time}" }
                val name = rawName.ifBlank { rawCode.ifBlank { "未命名标的" } }
                val shares = result.shares ?: 0.0
                val avgCost = result.price ?: 0.0
                setHolding(folderUri, market, key, name, shares, avgCost)
                "已设置持仓：$name ${ClassifyResult.fmtNum(shares)}股 · 成本¥%.2f".format(avgCost)
            }
            "transfer" -> {
                // 用户自己的卡/账户之间互转——还信用卡、把工资卡的钱转到另一张卡这种，
                // 钱没有真的离开用户的总资产，所以不走 appendCsvRow（不写账本.csv、
                // 不计入收支总览），单独记一笔转账流水，再把两张卡的余额都改一下。
                val amount = result.amount ?: 0.0
                val fromCard = result.fromCard?.trim().orEmpty()
                val toCard = result.toCard?.trim().orEmpty()
                val dateStr = resolveLedgerDate(result, now)
                appendTransferRow(
                    folderUri, dateStr,
                    fromCard.ifBlank { "未知账户" }, toCard.ifBlank { "未知账户" },
                    amount, result.summary.orEmpty()
                )
                applyTransfer(folderUri, result.fromCard, result.toCard, amount)
                val fromLabel = fromCard.ifBlank { "某账户" }
                val toLabel = toCard.ifBlank { "某账户" }
                "已记转账：¥%.2f，$fromLabel → $toLabel".format(amount)
            }
            "weight" -> {
                val weightKg = result.amount ?: 0.0
                val dateStr = resolveLedgerDate(result, now)
                appendWeightRow(folderUri, dateStr, weightKg, result.summary.orEmpty())
                "已记体重：%.1fkg".format(weightKg)
            }
            "digital_health" -> {
                val minutes = result.amount?.toInt()?.coerceAtLeast(0) ?: 0
                appendDigitalHealthRow(folderUri, resolveLedgerDate(result, now), minutes, result.detail.orEmpty(), result.summary.orEmpty())
                "已记数字健康：${minutes}分钟"
            }
            "ignore" -> {
                "已忽略：${result.reason ?: "判断为无需记录的内容"}"
            }
            else -> throw IllegalStateException("未知类型：${result.type}")
        }
    }

    /** 报告建议经用户确认后写入待办；隐藏动作编号用于崩溃重试时防止重复追加。 */
    fun addReportTodo(folderUri: String, actionId: String, title: String, reason: String, domain: String, dueAt: String?) = serializedWrite {
        val safeId = actionId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(100)
        require(safeId.isNotBlank()) { "建议动作缺少编号" }
        val marker = "<!--AI_ACTION:$safeId-->"
        val r = root(folderUri)
        val existingFile = StorageLayout.readFile(r, "待办.md")
        val existing = existingFile?.let { file -> HubIO.openInput(context, file)
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } }.orEmpty()
        if (existing.contains(marker)) return@serializedWrite
        val safeDomain = if (domain == "其他" || domain == "生活") "其他" else "工作"
        val reminderId = "report_$safeId"
        val meta = JSONObject().put("id", reminderId).put("kind", safeDomain)
            .put("dueAt", dueAt ?: JSONObject.NULL).put("actionId", safeId).toString()
        val line = "- [ ] [$safeDomain] ${timeFmt.format(Date())}  ${title.trim()} $marker <!--TODO_META:$meta-->"
        appendText(folderUri, "待办.md", "text/markdown", "# 待办\n", line)
        TodoReminderWorker.schedule(context, reminderId, title.trim(), dueAt)
    }

    // ------------------------------------------------------------------
    // 卡片余额：卡片.csv 是「当前状态」而不是流水，每次消费/收入命中一张卡就整份重写。
    // ------------------------------------------------------------------

    /** 设置页「卡片管理」保存整份卡片列表用——新增/改名/改余额/删卡都走这一个函数整篇重写。 */
    fun saveCards(folderUri: String, cards: List<CardAccount>) {
        fun esc(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
        val header = "$BOM" + listOf("卡名", "类型", "余额").joinToString(",")
        val body = cards.joinToString("\n") { c ->
            listOf(esc(c.name), esc(c.type), c.balance.toString()).joinToString(",")
        }
        val content = if (cards.isEmpty()) "$header\n" else "$header\n$body\n"
        writeWholeFile(folderUri, "卡片.csv", "text/csv", content)
    }

    /** 卡片管理页新增一张卡：追加到列表末尾整篇重写。 */
    fun addCard(folderUri: String, card: CardAccount) = serializedWrite {
        val cards = LedgerReader.readCards(context, folderUri).toMutableList()
        cards.add(card.copy(index = cards.size))
        saveCards(folderUri, cards)
    }

    /** 卡片管理页改一张卡（改名/改类型/手动纠正余额）：按 index 定位、整篇重写。 */
    fun updateCard(folderUri: String, index: Int, updated: CardAccount) {
        val cards = LedgerReader.readCards(context, folderUri).toMutableList()
        if (index !in cards.indices) return
        cards[index] = updated
        saveCards(folderUri, cards)
    }

    /** 卡片管理页删一张卡：按 index 定位、整篇重写；不影响已经记过的历史流水。 */
    fun deleteCard(folderUri: String, index: Int) {
        val cards = LedgerReader.readCards(context, folderUri).toMutableList()
        if (index !in cards.indices) return
        cards.removeAt(index)
        saveCards(folderUri, cards)
    }

    /**
     * 消费/收入这笔钱如果能对上一张已经在「卡片管理」里建好的卡，就自动加减那张卡的余额；
     * 对不上（没建过这张卡、或者截图没识别出卡信息）就什么都不做，不阻塞正常记账——卡片
     * 余额是锦上添花的功能，不能因为它反过来影响最基本的记账流程。
     *
     * 口径（跟用户确认过）：储蓄卡记的是「现在有多少」，消费减、收入加；信用卡记的是
     * 「欠了多少」（欠款余额），跟储蓄卡刚好相反——消费会让欠款变多，收入（还款/退款）
     * 会让欠款变少。
     */
    private fun applyCardDelta(folderUri: String, cardHint: String?, isIncome: Boolean, amount: Double) {
        if (cardHint.isNullOrBlank()) return
        val cards = LedgerReader.readCards(context, folderUri)
        val idx = findCardIndex(cards, cardHint)
        if (idx < 0) return
        val card = cards[idx]
        val updated = cards.toMutableList()
        updated[idx] = card.copy(balance = card.balance + cardDelta(card.type, isIncome, amount))
        saveCards(folderUri, updated)
    }

    /** 一张卡「收到一笔钱」（isIncome=true）或者「花出去一笔钱」（isIncome=false）之后余额该
     * 变化多少——储蓄卡和信用卡口径相反，[applyCardDelta]（消费/收入）和 [applyTransfer]
     * （互转的两条腿）共用这一套换算，避免两个地方各写一份、以后改口径漏改一处。 */
    private fun cardDelta(cardType: String, isIncome: Boolean, amount: Double): Double =
        when (cardType) {
            "信用" -> if (isIncome) -amount else amount
            else -> if (isIncome) amount else -amount
        }

    /**
     * "transfer" 专用：转出卡按「花出去」处理，转入卡按「收到」处理——跟 [applyCardDelta]
     * 的换算完全一样（信用卡转入 = 还款 = 欠款变少），区别是这次要在同一份卡片列表快照上
     * 同时改两张卡、一次性存盘，不能分两次各调用一次 applyCardDelta（那样第二次读到的
     * 卡片列表还是文件里的旧内容，会把第一次刚存的改动覆盖掉）。转出/转入任意一个在
     * 「卡片管理」里对不上号，就只改另一张对得上的那张；两张都对不上，或者对上的是
     * 同一张卡（模型识别错把转出转入认成同一张），就只留一笔转账流水、不改余额——
     * 卡片余额是锦上添花，不能因为对不上号反过来阻塞转账记录本身落盘。 */
    private fun applyTransfer(folderUri: String, fromHint: String?, toHint: String?, amount: Double) {
        if (fromHint.isNullOrBlank() && toHint.isNullOrBlank()) return
        val cards = LedgerReader.readCards(context, folderUri).toMutableList()
        fun findIdx(hint: String?) = findCardIndex(cards, hint)
        val fromIdx = findIdx(fromHint)
        val toIdx = findIdx(toHint)
        if (fromIdx < 0 && toIdx < 0) return
        if (fromIdx >= 0) {
            val c = cards[fromIdx]
            cards[fromIdx] = c.copy(balance = c.balance + cardDelta(c.type, isIncome = false, amount = amount))
        }
        if (toIdx >= 0 && toIdx != fromIdx) {
            val c = cards[toIdx]
            cards[toIdx] = c.copy(balance = c.balance + cardDelta(c.type, isIncome = true, amount = amount))
        }
        saveCards(folderUri, cards)
    }

    /** 银行通知的卡名常带“储蓄卡(1234)”等不同格式；优先名称，再用尾号匹配。 */
    private fun findCardIndex(cards: List<CardAccount>, hint: String?): Int {
        if (hint.isNullOrBlank()) return -1
        val normalizedHint = hint.lowercase().filter { it.isLetterOrDigit() }
        cards.indexOfFirst { card ->
            val normalized = card.name.lowercase().filter { it.isLetterOrDigit() }
            normalizedHint.contains(normalized) || normalized.contains(normalizedHint)
        }.takeIf { it >= 0 }?.let { return it }
        val tail = Regex("\\d{4}").findAll(hint).lastOrNull()?.value ?: return -1
        return cards.indexOfFirst { Regex("\\d{4}").findAll(it.name).lastOrNull()?.value == tail }
    }

    /** 卡片页"转账"按钮专用：用户在表单里自己选好了转出/转入卡、填好金额，直接执行——
     * 不经过 AI 识别，也不用像截图/打字识别出来的转账结果那样走「待确认」二次确认，
     * 这是他自己在表单里明确选的，跟新增/编辑一张卡片是同一个信任级别（点了就直接生效），
     * 不是模型的猜测，没有"猜错"的风险，不需要额外的确认步骤。内部复用的落盘逻辑
     * （appendTransferRow/applyTransfer）跟 AI 识别转账走的是完全同一套，保证两条路径
     * 记出来的东西格式一致、卡片余额算法一致。 */
    fun recordManualTransfer(folderUri: String, fromCard: String, toCard: String, amount: Double, note: String) {
        val now = Date()
        appendTransferRow(folderUri, timeFmt.format(now), fromCard, toCard, amount, note)
        applyTransfer(folderUri, fromCard, toCard, amount)
    }

    /** 转账记录.csv 只追加，不提供改/删——跟交易记录.csv 是同一个思路：这是流水审计记录，
     * 想改的是"结果"（某张卡的余额不对了）就直接去「卡片」页手动改那张卡的余额，
     * 不去改流水本身，保持流水和实际发生的操作一一对应。 */
    private fun appendTransferRow(
        folderUri: String,
        date: String,
        fromCard: String,
        toCard: String,
        amount: Double,
        note: String
    ) {
        fun esc(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
        val row = listOf(esc(date), esc(fromCard), esc(toCard), amount.toString(), esc(note)).joinToString(",")
        appendText(
            folderUri,
            "转账记录.csv",
            "text/csv",
            "$BOM" + listOf("日期", "转出账户", "转入账户", "金额", "备注").joinToString(","),
            row
        )
    }

    /** 体重.csv 只追加，一天称好几次就记好几行——跟账本.csv 一个思路，具体挑哪一行当
     * "今天的体重"留给以后看数据的时候自己判断，这里不做"一天只留一条"的覆盖逻辑。 */
    private fun appendWeightRow(folderUri: String, date: String, weightKg: Double, note: String) {
        fun esc(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
        val row = listOf(esc(date), weightKg.toString(), esc(note)).joinToString(",")
        appendText(
            folderUri,
            "体重.csv",
            "text/csv",
            "$BOM" + listOf("日期", "体重_kg", "备注").joinToString(","),
            row
        )
    }

    /** 健康页编辑/删除体重时按原始行号安全重写；只影响这一条体重记录。 */
    fun updateWeight(folderUri: String, index: Int, updated: WeightRow) {
        val rows = LedgerReader.readWeights(context, folderUri).toMutableList()
        if (index !in rows.indices) return
        rows[index] = updated
        writeWeightRows(folderUri, rows)
    }

    fun deleteWeight(folderUri: String, index: Int) {
        val rows = LedgerReader.readWeights(context, folderUri).toMutableList()
        if (index !in rows.indices) return
        rows.removeAt(index)
        writeWeightRows(folderUri, rows)
    }

    private fun writeWeightRows(folderUri: String, rows: List<WeightRow>) {
        fun esc(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
        val header = "$BOM" + listOf("日期", "体重_kg", "备注").joinToString(",")
        val body = rows.joinToString("\n") { row -> listOf(esc(row.date), row.weightKg.toString(), esc(row.note)).joinToString(",") }
        writeWholeFile(folderUri, "体重.csv", "text/csv", if (body.isBlank()) "$header\n" else "$header\n$body\n")
    }

    private fun appendDigitalHealthRow(folderUri: String, date: String, totalMinutes: Int, appsJson: String, note: String) {
        fun esc(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
        appendText(folderUri, "数字健康.csv", "text/csv", "$BOM" + "日期,总分钟,App明细_JSON,摘要",
            listOf(esc(date), totalMinutes.toString(), esc(appsJson), esc(note)).joinToString(","))
    }

    /**
     * 长按拍照存下来的原始照片——三餐、饮料这类日常照片。AI 只负责判断"这是吃的还是
     * 喝的"外加一句极简描述（比如"牛肉面"），不做热量/成分这类更深的分析，那些留给
     * 以后同步给 Mac 之后更强的模型去看（见 [com.linkn.screenintake.classify.QwenClassifier.classifyMealOrDrink]
     * 头部注释）。归档在保存文件夹下 "日常照片/三餐/" 或 "日常照片/饮料/" 子目录里，
     * 文件名是拍摄时间戳（有摘要的话带一段摘要，方便直接在文件名里看到拍的是什么），
     * 靠 Syncthing 同步到 Mac。这条路径不弹「待确认」——AI 判断这一步万一分错了类
     * （比如把喝的分去了饮食），直接在 健康 tab 对应的照片列表里用 [movePhoto] 手动挪
     * 一下即可，操作量比走一遍确认通知小很多，也更适合一天要拍好几次的场景。
     */
    fun savePhoto(folderUri: String, imageBytes: ByteArray, category: String, caption: String?): String {
        val dirName = if (category == "drink") PHOTO_DRINK_DIR else PHOTO_MEAL_DIR
        val r = root(folderUri)
        val photoRoot = StorageLayout.writableDirectory(context, r, StorageLayout.HEALTH, PHOTO_ROOT_DIR)
        val dir = photoRoot.findFile(dirName)?.takeIf { it.isDirectory }
            ?: photoRoot.createDirectory(dirName)
            ?: throw IllegalStateException("创建\"$dirName\"文件夹失败")
        val stamp = stampFmt.format(Date())
        val safeCaption = caption
            ?.replace(Regex("[\\\\/:*?\"<>|_]"), "")
            ?.trim()
            ?.take(20)
        val fileName = if (!safeCaption.isNullOrBlank()) "${stamp}_$safeCaption.jpg" else "$stamp.jpg"
        val file = dir.createFile("image/jpeg", fileName)
            ?: throw IllegalStateException("创建照片文件失败")
        HubIO.openOutput(context, file, "wt")?.use { it.write(imageBytes) }
            ?: throw IllegalStateException("无法写入照片文件")
        DataChangeSignal.bump()
        return fileName
    }

    /** 健康 tab 的 饮食/饮料 列表里手动改归类用：AI 判断错了（比如把喝的分去了饮食），
     * 直接把这个文件从一个分类文件夹挪到另一个——不同存储提供方对 SAF 的 moveDocument
     * 支持不一致（比如 Syncthing-Fork 提供的目录不一定支持），这里统一用"复制字节再删除
     * 原文件"这种最保险的方式，不追求效率（这本来就是偶尔手动纠错才会用到的操作）。 */
    fun movePhoto(folderUri: String, fileName: String, fromCategory: String, toCategory: String): Boolean {
        if (fromCategory == toCategory) return true
        val r = root(folderUri)
        val photoRoot = StorageLayout.readDirectory(r, StorageLayout.HEALTH, PHOTO_ROOT_DIR) ?: return false
        val fromDirName = if (fromCategory == "drink") PHOTO_DRINK_DIR else PHOTO_MEAL_DIR
        val toDirName = if (toCategory == "drink") PHOTO_DRINK_DIR else PHOTO_MEAL_DIR
        val fromDir = photoRoot.findFile(fromDirName) ?: return false
        val file = fromDir.findFile(fileName) ?: return false
        val toDir = photoRoot.findFile(toDirName) ?: photoRoot.createDirectory(toDirName) ?: return false
        val bytes = HubIO.openInput(context, file)?.use { it.readBytes() } ?: return false
        val newFile = toDir.createFile("image/jpeg", fileName) ?: return false
        HubIO.openOutput(context, newFile, "wt")?.use { it.write(bytes) } ?: return false
        file.delete()
        DataChangeSignal.bump()
        return true
    }

    /** 健康 tab 的 饮食/饮料 列表里删掉一张拍糊了/拍错了的照片用。 */
    fun deletePhoto(folderUri: String, fileName: String, category: String): Boolean {
        val r = root(folderUri)
        val photoRoot = StorageLayout.readDirectory(r, StorageLayout.HEALTH, PHOTO_ROOT_DIR) ?: return false
        val dirName = if (category == "drink") PHOTO_DRINK_DIR else PHOTO_MEAL_DIR
        val dir = photoRoot.findFile(dirName) ?: return false
        val file = dir.findFile(fileName) ?: return false
        val ok = file.delete()
        if (ok) DataChangeSignal.bump()
        return ok
    }

    // ------------------------------------------------------------------
    // 股票持仓：交易记录.csv 只追加（买卖流水，审计用），持仓.csv 是根据流水滚动算出来的
    // 「当前状态」，每笔新交易都会重新整篇重写一次。实时价格/市值不存在这里，App 打开
    // 持仓页面时自己现取现算（见 QuoteFetcher），避免文件里存一个很快过期的数字，也避免
    // 「仅发送」同步模式下手机需要依赖外部写回价格数据的问题。
    // ------------------------------------------------------------------

    private fun appendTradeRow(
        folderUri: String,
        date: String,
        side: String,
        market: String,
        code: String,
        name: String,
        shares: Double,
        price: Double,
        amount: Double,
        note: String
    ) {
        fun esc(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
        val row = listOf(
            esc(date), esc(side), esc(market), esc(code), esc(name),
            shares.toString(), price.toString(), amount.toString(), esc(note)
        ).joinToString(",")
        appendText(
            folderUri,
            "交易记录.csv",
            "text/csv",
            "$BOM" + listOf("日期", "方向", "市场", "代码", "名称", "股数", "价格", "金额", "备注").joinToString(","),
            row
        )
    }

    /** 买入：命中已有持仓就按加权平均重新算成本，没有就新建一条；卖出：命中就减股数（不
     * 反向计算已实现盈亏，v1 先不做那么细），减到 0 或以下就整条移除，卖出一个从没记录过
     * 持仓的标的（比如导入前就已经买入过、没补录历史交易）就跳过，不凭空生成负持仓。 */
    private fun applyTradeToHoldings(
        folderUri: String,
        market: String,
        code: String,
        name: String,
        isSell: Boolean,
        shares: Double,
        price: Double
    ) {
        val holdings = LedgerReader.readHoldings(context, folderUri).toMutableList()
        val idx = holdings.indexOfFirst { it.market == market && it.code == code }
        if (isSell) {
            if (idx < 0) return
            val h = holdings[idx]
            val newShares = (h.shares - shares).coerceAtLeast(0.0)
            if (newShares <= 0.0) holdings.removeAt(idx) else holdings[idx] = h.copy(shares = newShares)
        } else {
            if (idx < 0) {
                holdings.add(Holding(market = market, code = code, name = name, shares = shares, avgCost = price, index = 0))
            } else {
                val h = holdings[idx]
                val newShares = h.shares + shares
                val newAvgCost = if (newShares > 0) (h.shares * h.avgCost + shares * price) / newShares else 0.0
                holdings[idx] = h.copy(shares = newShares, avgCost = newAvgCost, name = name.ifBlank { h.name })
            }
        }
        saveHoldings(folderUri, holdings)
    }

    /** "holding" 类型专用：直接把某只股票的持仓状态设成给定的股数/成本，覆盖而不是
     * 累加——用户说的是"我现在手上有多少"，不是"我刚买卖了多少"，所以不能跟 trade
     * 那样按加权平均滚动计算。股数给 0（或者没识别出股数）按"清空这只"处理，方便
     * 用一句"这只清仓了/不再持有xxx"直接把它从持仓列表里去掉。 */
    private fun setHolding(folderUri: String, market: String, code: String, name: String, shares: Double, avgCost: Double) {
        val holdings = LedgerReader.readHoldings(context, folderUri).toMutableList()
        val idx = holdings.indexOfFirst { it.market == market && it.code == code }
        if (shares <= 0.0) {
            if (idx >= 0) holdings.removeAt(idx)
        } else if (idx < 0) {
            holdings.add(Holding(market = market, code = code, name = name, shares = shares, avgCost = avgCost, index = 0))
        } else {
            holdings[idx] = holdings[idx].copy(shares = shares, avgCost = avgCost, name = name.ifBlank { holdings[idx].name })
        }
        saveHoldings(folderUri, holdings)
    }

    /** 持仓页面手动改一条（比如对账发现成本算错了）或者删一条，走这两个函数；跟财务列表
     * 编辑一样按 index 定位、整篇重写。 */
    fun updateHolding(folderUri: String, index: Int, updated: Holding) {
        val holdings = LedgerReader.readHoldings(context, folderUri).toMutableList()
        if (index !in holdings.indices) return
        holdings[index] = updated
        saveHoldings(folderUri, holdings)
    }

    fun deleteHolding(folderUri: String, index: Int) {
        val holdings = LedgerReader.readHoldings(context, folderUri).toMutableList()
        if (index !in holdings.indices) return
        holdings.removeAt(index)
        saveHoldings(folderUri, holdings)
    }

    fun saveHoldings(folderUri: String, holdings: List<Holding>) {
        fun esc(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
        val header = "$BOM" + listOf("市场", "代码", "名称", "股数", "成本").joinToString(",")
        val body = holdings.joinToString("\n") { h ->
            listOf(esc(h.market), esc(h.code), esc(h.name), h.shares.toString(), h.avgCost.toString()).joinToString(",")
        }
        val content = if (holdings.isEmpty()) "$header\n" else "$header\n$body\n"
        writeWholeFile(folderUri, "持仓.csv", "text/csv", content)
    }

    /**
     * 结果需要过一遍你的眼睛时，先存一份草稿到「待确认」——即使通知被手滑划掉，这份草稿
     * 还在，App 里的「待确认」模块或者翻文件夹都能看到、不会真的丢。人类可读的部分放前面
     * （状态/类型/识别内容/原始文字），方便直接打开文件看；最后附一段机读的 JSON（[JSON_MARKER]
     * 之后的内容），给 App 内的确认/编辑用，不需要重新识别一遍就能把这条草稿还原成结构化数据。
     * 点了确认或者编辑提交之后会调用 [deletePendingDraft] 把它删掉。
     */
    fun savePendingDraft(folderUri: String, draftId: String, result: ClassifyResult, screenText: String) {
        val r = root(folderUri)
        val dir = StorageLayout.writableDirectory(context, r, StorageLayout.SYSTEM, UNCONFIRMED_DIR)
        val name = draftFileName(draftId)
        val file = dir.findFile(name) ?: dir.createFile("text/plain", name)
        if (file != null) {
            val content = "状态：待确认（还没在通知上点确认/编辑）\n" +
                "类型：${result.typeLabel()}\n" +
                "识别内容：${result.displaySummary()}\n\n" +
                "原始文字：\n$screenText\n\n" +
                "$JSON_MARKER\n" +
                result.toJson()
            HubIO.openOutput(context, file, "wt")?.use {
                it.write(content.toByteArray(Charsets.UTF_8))
            }
            DataChangeSignal.bump()
        }
    }

    /** 确认或编辑完成后，把对应的草稿文件清掉，不管走的是哪个分支都不该再留着。 */
    fun deletePendingDraft(folderUri: String, draftId: String) {
        val r = root(folderUri)
        val dir = StorageLayout.readDirectory(r, StorageLayout.SYSTEM, UNCONFIRMED_DIR) ?: return
        dir.findFile(draftFileName(draftId))?.delete()
        DataChangeSignal.bump()
    }

    private fun draftFileName(draftId: String) = "$DRAFT_PREFIX$draftId.txt"

    /**
     * App 内「待确认」模块用：读出所有真正「待确认」的草稿（有 draftId、能确认/编辑那种）。
     * 旧版本（还没加机读 JSON 之前）存的草稿文件里没有 [JSON_MARKER] 那一段，`result` 会是
     * null——这种草稿在界面上只能看内容和删除，没法直接点确认/编辑（没有结构化数据可用），
     * 想处理的话还是要靠手动记一遍或者直接改文件。按保存时间从新到旧排。
     */
    fun readPendingDrafts(folderUri: String): List<PendingDraft> {
        val r = root(folderUri)
        val dir = StorageLayout.readDirectory(r, StorageLayout.SYSTEM, UNCONFIRMED_DIR) ?: return emptyList()
        return dir.listFiles()
            .filter { it.name?.startsWith(DRAFT_PREFIX) == true && it.name?.endsWith(".txt") == true }
            .mapNotNull { file ->
                val fileName = file.name ?: return@mapNotNull null
                val draftId = fileName.removePrefix(DRAFT_PREFIX).removeSuffix(".txt")
                val text = HubIO.openInput(context, file)
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: return@mapNotNull null
                val marker = "\n$JSON_MARKER\n"
                val markerIndex = text.indexOf(marker)
                val humanPart: String
                val result: ClassifyResult?
                if (markerIndex >= 0) {
                    humanPart = text.substring(0, markerIndex)
                    val json = text.substring(markerIndex + marker.length).trim()
                    result = runCatching { ClassifyResult.fromJson(json) }.getOrNull()
                } else {
                    humanPart = text
                    result = null
                }
                val screenText = humanPart.substringAfter("原始文字：\n", "").trim()
                PendingDraft(
                    draftId = draftId,
                    result = result,
                    screenText = screenText,
                    rawText = humanPart.trim(),
                    savedAtMillis = file.lastModified()
                )
            }
            .sortedByDescending { it.savedAtMillis }
    }

    /**
     * App 内「待确认」模块用：分类彻底失败（网络错误/解析失败/截屏失败/读屏读到空文字）
     * 时兜底存的说明文件——见 [saveUnconfirmed]，没有 draftId、没有结构化数据，纯粹是给
     * 人看的排查记录，界面上只读、能删。
     */
    fun readUnconfirmedNotes(folderUri: String): List<UnconfirmedNote> {
        val r = root(folderUri)
        val dir = StorageLayout.readDirectory(r, StorageLayout.SYSTEM, UNCONFIRMED_DIR) ?: return emptyList()
        return dir.listFiles()
            .filter { it.name?.startsWith(DRAFT_PREFIX) != true && it.name?.endsWith(".txt") == true }
            .mapNotNull { file ->
                val fileName = file.name ?: return@mapNotNull null
                val text = HubIO.openInput(context, file)
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: return@mapNotNull null
                UnconfirmedNote(fileName = fileName, content = text.trim(), savedAtMillis = file.lastModified())
            }
            .sortedByDescending { it.savedAtMillis }
    }

    /** 排查记录本身没法「确认」，只能看完之后手动清掉。 */
    fun deleteUnconfirmedNote(folderUri: String, fileName: String) {
        val r = root(folderUri)
        val dir = StorageLayout.readDirectory(r, StorageLayout.SYSTEM, UNCONFIRMED_DIR) ?: return
        dir.findFile(fileName)?.delete()
        DataChangeSignal.bump()
    }

    /** 分类失败 / 网络错误 / 解析失败时兜底：把提取到的原始文字连同失败原因存下来，人工看。 */
    fun saveUnconfirmed(folderUri: String, originalText: String, reason: String) {
        val r = root(folderUri)
        val dir = StorageLayout.writableDirectory(context, r, StorageLayout.SYSTEM, UNCONFIRMED_DIR)
        val stamp = stampFmt.format(Date())
        val note = dir.createFile("text/plain", "$stamp.txt")
        if (note != null) {
            val content = "$reason\n\n原始文字：\n$originalText"
            context.contentResolver.openOutputStream(note.uri)?.use {
                it.write(content.toByteArray(Charsets.UTF_8))
            }
            DataChangeSignal.bump()
        }
    }

    /** 财务列表里点一行改完保存：按 index 定位那一行，整份 CSV 重写。 */
    fun updateLedgerRow(folderUri: String, index: Int, updated: LedgerRow) {
        val rows = LedgerReader.readLedger(context, folderUri).toMutableList()
        if (index !in rows.indices) return
        val original = rows[index]
        rows[index] = updated
        writeLedgerRows(folderUri, rows)
        try {
            syncCardsForLedgerChange(folderUri, original, updated)
        } catch (e: Exception) {
            // 卡片余额没能同步时恢复原流水，避免界面显示修改成功但两份数据已经不一致。
            rows[index] = original
            runCatching { writeLedgerRows(folderUri, rows) }
            throw e
        }
    }

    fun deleteLedgerRow(folderUri: String, index: Int) {
        val rows = LedgerReader.readLedger(context, folderUri).toMutableList()
        if (index !in rows.indices) return
        val original = rows.removeAt(index)
        writeLedgerRows(folderUri, rows)
        try {
            syncCardsForLedgerChange(folderUri, original, null)
        } catch (e: Exception) {
            // 删除流水和反向冲销必须一起成功；余额写入失败时尽量恢复原流水。
            rows.add(index, original)
            runCatching { writeLedgerRows(folderUri, rows) }
            throw e
        }
    }

    /**
     * 把账本行的变化同步到卡片当前余额：旧行乘 -1 撤销，新行乘 +1 应用。
     * 只有备注中明确带【账户：…】且能匹配现有卡片时才调整，绝不根据分类或商户猜账户。
     */
    private fun syncCardsForLedgerChange(folderUri: String, old: LedgerRow?, new: LedgerRow?) {
        val cards = LedgerReader.readCards(context, folderUri).toMutableList()
        if (cards.isEmpty()) return
        var changed = false
        fun apply(row: LedgerRow, direction: Double) {
            val cardName = ExpensePurpose.cardFromNote(row.note) ?: return
            val index = findCardIndex(cards, cardName)
            if (index < 0) return
            val card = cards[index]
            val isIncome = row.type == "收入"
            cards[index] = card.copy(balance = card.balance + direction * cardDelta(card.type, isIncome, row.amount))
            changed = true
        }
        old?.let { apply(it, -1.0) }
        new?.let { apply(it, 1.0) }
        if (changed) saveCards(folderUri, cards)
    }

    private fun writeLedgerRows(folderUri: String, rows: List<LedgerRow>) {
        fun esc(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
        val header = "$BOM" + listOf("日期", "类型", "分类", "金额", "备注").joinToString(",")
        val body = rows.joinToString("\n") { row ->
            listOf(esc(row.date), esc(row.type), esc(row.category), row.amount.toString(), esc(row.note))
                .joinToString(",")
        }
        val content = if (rows.isEmpty()) "$header\n" else "$header\n$body\n"
        writeWholeFile(folderUri, "账本.csv", "text/csv", content)
    }

    /** 待办列表勾选/编辑内容：只重写第 index 条"- [ ]"/"- [x]"行，其余行（包括 # 待办 标题）原样保留。
     * domain 传的是（可能被用户改过的）新领域标签，重写这一行的时候要把 "[域名]" 标签一起拼回去，
     * 不然这一行就会退化成没有标签的老格式，下次读回来又会被兜底成"其他"。 */
    fun updateTodo(folderUri: String, index: Int, done: Boolean, text: String, domain: String, dueAt: String?, reminderId: String?) {
        val safeDomain = if (domain == "其他" || domain == "生活") "其他" else "工作"
        val safeId = reminderId ?: "todo_${UUID.randomUUID()}"
        val meta = JSONObject().put("id", safeId).put("kind", safeDomain)
            .put("dueAt", dueAt ?: JSONObject.NULL).toString()
        rewriteRawLines(folderUri, "待办.md") { lines ->
            var seen = -1
            lines.map { line ->
                val trimmed = line.trimStart()
                if (trimmed.startsWith("- [ ]") || trimmed.startsWith("- [x]") || trimmed.startsWith("- [X]")) {
                    seen++
                    if (seen == index) {
                        val box = line.takeWhile { it.isWhitespace() } + if (done) "- [x] " else "- [ ] "
                        // 编辑文字不能抹掉 AI_ACTION；它是报告建议去重的唯一身份。
                        val action = Regex("<!--AI_ACTION:[A-Za-z0-9._-]+-->").find(line)?.value.orEmpty()
                        box + "[$safeDomain] " + text
                            .replace(Regex("\\s*<!--AI_ACTION:[A-Za-z0-9._-]+-->\\s*"), "")
                            .replace(Regex("\\s*<!--TODO_META:.*-->\\s*$"), "") +
                            " ${action.takeIf { it.isNotBlank() } ?: ""} <!--TODO_META:$meta-->"
                    } else line
                } else line
            }
        }
        // 待办文件已经安全落盘后，提醒只是附加能力。WorkManager 处于重启、升级或系统
        // 限制状态时可能拒绝本次调度，绝不能因此把已经成功的“完成待办”误报成失败。
        runCatching {
            if (done) TodoReminderWorker.cancel(context, safeId)
            else TodoReminderWorker.schedule(context, safeId, text, dueAt)
        }.onFailure { Log.w(TAG, "待办已写入，但本地提醒更新失败：$safeId", it) }
    }

    fun deleteTodo(folderUri: String, index: Int) {
        val old = LedgerReader.readTodos(context, folderUri).firstOrNull { it.index == index }
        rewriteRawLines(folderUri, "待办.md") { lines ->
            var seen = -1
            lines.filterNot { line ->
                val trimmed = line.trimStart()
                val isTodoLine = trimmed.startsWith("- [ ]") || trimmed.startsWith("- [x]") || trimmed.startsWith("- [X]")
                if (isTodoLine) seen++
                isTodoLine && seen == index
            }
        }
        old?.reminderId?.let { id ->
            // 删除记录已完成后，取消通知失败不应让用户看到“删除失败”。
            runCatching { TodoReminderWorker.cancel(context, id) }
                .onFailure { Log.w(TAG, "待办已删除，但本地提醒取消失败：$id", it) }
        }
    }

    private fun rewriteRawLines(folderUri: String, fileName: String, transform: (List<String>) -> List<String>) = writeLock.withLock {
        val r = root(folderUri)
        val source = StorageLayout.readFile(r, fileName) ?: error("找不到 $fileName，请刷新后重试")
        val file = StorageLayout.writableFile(context, r, fileName, source.type ?: "text/plain")
        val existing = HubIO.openInput(context, file)
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: throw IllegalStateException("无法读取已有的 $fileName，已停止写入以保护原始数据")
        val lines = existing.split("\n")
        val newLines = transform(lines)
        val content = newLines.joinToString("\n")
        VerifiedFileWrite.replace(context, file, existing, content)
        DataChangeSignal.bump(StorageLayout.domainForFile(fileName))
    }

    /** 灵感编辑/删除：按 [LedgerReader.readNotes] 同一套 "## " 分块逻辑重新切一遍，保证读写对块的理解一致。
     * domain 语义同 [updateTodo]：允许在编辑的时候顺手改一下这条灵感归到哪个领域。 */
    fun updateNote(folderUri: String, index: Int, content: String, domain: String) {
        val safeDomain = domain.takeIf { it in ClassifyResult.DOMAINS } ?: "其他"
        rewriteNoteBlocks(folderUri) { blocks ->
            if (index !in blocks.indices) blocks
            else blocks.toMutableList().apply {
                this[index] = this[index].copy(content = content, domain = safeDomain)
            }
        }
    }

    fun deleteNote(folderUri: String, index: Int) {
        rewriteNoteBlocks(folderUri) { blocks ->
            if (index !in blocks.indices) blocks
            else blocks.toMutableList().apply { removeAt(index) }
        }
    }

    private fun rewriteNoteBlocks(folderUri: String, transform: (List<NoteItem>) -> List<NoteItem>) = writeLock.withLock {
        val fileName = "灵感.md"
        // readNotes 用倒序供界面展示；写文件前必须恢复原始顺序，不能把展示位置当成身份。
        val displayed = LedgerReader.readNotes(context, folderUri)
        val sourceOrder = displayed.sortedBy { it.index }
        val newBlocks = transform(sourceOrder)
        val body = newBlocks.sortedBy { it.index }.joinToString("") { block ->
            val heading = block.heading.trim()
                .removePrefix("#").trim().removePrefix("#").trim()
                .replace(Regex("""^(\[[^\]]+\]\s*)+"""), "")
                .trim()
            "\n## [${block.domain}] $heading\n${block.content}\n"
        }
        writeWholeFile(folderUri, fileName, "text/markdown", "# 灵感与笔记\n$body")
    }

    private fun writeWholeFile(folderUri: String, fileName: String, mime: String, content: String) {
        val r = root(folderUri)
        val file = StorageLayout.writableFile(context, r, fileName, mime)
        serializedWrite { VerifiedFileWrite.replace(context, file, LedgerReader.expectedText(file.uri.toString()), content) }
        DataChangeSignal.bump(StorageLayout.domainForFile(fileName))
    }

    private fun appendCsvRow(
        folderUri: String,
        date: String,
        type: String,
        category: String,
        amount: Double,
        note: String
    ) {
        fun esc(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
        val row = listOf(esc(date), esc(type), esc(category), amount.toString(), esc(note))
            .joinToString(",")
        appendText(
            folderUri,
            "账本.csv",
            "text/csv",
            "$BOM" + listOf("日期", "类型", "分类", "金额", "备注").joinToString(","),
            row
        )
    }

    /**
     * 通用追加：读出已有内容，拼上新的一行，整体重写。不依赖 SAF 的 append 模式是否被
     * 具体存储提供方支持，牺牲一点效率换稳定——这几个文件几年内都不会大到影响体感。
     */
    private fun appendText(
        folderUri: String,
        fileName: String,
        mime: String,
        headerIfNew: String,
        newLine: String
    ) = writeLock.withLock {
        val r = root(folderUri)
        var file = StorageLayout.readFile(r, fileName)
        val existing = if (file != null) HubIO.openInput(context, file)
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: throw IllegalStateException("无法读取已有的 $fileName，已停止写入以保护原始数据") else ""
        if (file == null) {
            // 账本.csv/待办.md/灵感.md 这几个文件正常情况下用过一次之后就应该一直存在——
            // 如果这次要新建的偏偏是这几个"核心记录"文件，很可能不是真的第一次用，而是
            // 这台设备跟保存文件夹之间出现了同步缺口（比如"仅发送"模式下，另一台设备
            // 那边把文件改名/重建过，这台设备没能收到重建后的新文件），继续往下写会把
            // 一份几乎空白的新文件当成"现状"同步出去，有覆盖掉别处完整数据的风险。这里
            // 留一条诊断记录到「待确认」，不阻塞这次记录（不能确定一定是坏情况，真正
            // 第一次用也会走到这里），但至少不会悄无声息地发生。
            if (fileName in CORE_LOG_FILES) {
                try {
                    saveUnconfirmed(
                        folderUri,
                        "",
                        "记录时发现「$fileName」在这个文件夹里读不到，已经当成全新文件重新创建。" +
                            "如果这是你第一次用这个功能，这条提示可以忽略；如果你确定以前已经记过" +
                            "内容、这个文件应该有历史数据，说明这台设备可能存在同步缺口——建议先去" +
                            "检查一下这个保存文件夹在其它设备（比如电脑）上是不是还留着一份完整的" +
                            "旧文件，确认没问题再继续记录，避免新文件把旧数据覆盖掉。"
                    )
                } catch (e: Exception) {
                    // 诊断记录本身失败不应该阻塞正常记录流程。
                }
            }
            file = StorageLayout.writableFile(context, r, fileName, mime)
        }
        val content = buildString {
            if (existing.isBlank()) {
                append(headerIfNew)
                if (!headerIfNew.endsWith("\n")) append("\n")
            } else {
                append(existing)
                if (!existing.endsWith("\n")) append("\n")
            }
            append(newLine)
            append("\n")
        }
        VerifiedFileWrite.replace(context, file, existing, content)
        DataChangeSignal.bump(StorageLayout.domainForFile(fileName))
    }

    companion object {
        private const val TAG = "RecordStore"
        private val writeLock = ReentrantLock(true)
        private const val UNCONFIRMED_DIR = "待确认"
        private const val DRAFT_PREFIX = "待确认_"
        private const val JSON_MARKER = "###RESULT_JSON###"
        private const val BOM = "\uFEFF"
        // 日常照片的根目录+两个分类子目录名，savePhoto/movePhoto/deletePhoto 共用
        private const val PHOTO_ROOT_DIR = "日常照片"
        private const val PHOTO_MEAL_DIR = "三餐"
        private const val PHOTO_DRINK_DIR = "饮料"
        // 走 appendText 的核心记录文件——这几个一旦"看着像丢了"就值得留个诊断记录，
        // 跟卡片.csv/持仓.csv/交易记录.csv 这类允许随时首次创建的"状态文件"区分开。
        private val CORE_LOG_FILES = setOf("账本.csv", "待办.md", "灵感.md", "体重.csv")
    }

}

/** 一条「待确认」草稿——App 内确认/编辑刀陸列表用。见 [RecordStore.readPendingDrafts]。 */
data class PendingDraft(
    val draftId: String,
    val result: ClassifyResult?,
    val screenText: String,
    val rawText: String,
    val savedAtMillis: Long
)

/** 一条分类失败兜底记录——只读，只能地和删。见 [RecordStore.readUnconfirmedNotes]。 */
data class UnconfirmedNote(
    val fileName: String,
    val content: String,
    val savedAtMillis: Long
)
