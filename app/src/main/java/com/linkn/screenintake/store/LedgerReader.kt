package com.linkn.screenintake.store

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** 账本.csv 里的一行，type 是"支出"或"收入"，跟 [RecordStore] 写进去的列一一对应。
 * index 是这一行在文件里（表头之后）从 0 开始数的原始位置，不受列表展示时按日期排序
 * 影响——编辑/删除靠这个字段告诉 [RecordStore] 到底改的是原始文件里的哪一行。 */
data class LedgerRow(
    val date: String,
    val type: String,
    val category: String,
    val amount: Double,
    val note: String,
    val index: Int
)

/** index 语义同上：待办.md 里所有 "- [ ]"/"- [x]" 行，按文件里出现的原始顺序从 0 数起。
 * domain 是这条待办归到"财务、健康、工作、习惯、其他"里的哪一个，从行首 "[域名]" 标签解析出来，
 * 没有这个标签的老数据（这个功能上线之前记的）统一兜底成"其他"，不当成解析失败。 */
data class TodoItem(
    val done: Boolean,
    val text: String,
    val domain: String,
    val index: Int,
    val dueAt: String? = null,
    val reminderId: String? = null
)
data class MealNote(val date: String, val text: String, val index: Int)

/** index 语义同上：灵感.md 里所有 "## " 块，按文件里出现的原始顺序从 0 数起。
 * domain 语义同 [TodoItem]，从 heading 开头的 "[域名]" 标签解析，没有就兜底"其他"。 */
data class NoteItem(val heading: String, val content: String, val domain: String, val index: Int)

/** 卡片.csv 里的一行——每张卡/账户当前的状态，覆盖写（不是流水），类型是"储蓄"或
 * "信用"，余额口径见 [com.linkn.screenintake.store.RecordStore]（储蓄卡是「现有多少」，
 * 信用卡是「欠了多少」，两种口径正好相反）。 */
data class CardAccount(
    val name: String,
    val type: String,
    val balance: Double,
    val index: Int
)

/** 交易记录.csv 里的一行——股票买卖流水，只追加不改写，作为持仓.csv 的原始依据。 */
data class TradeRow(
    val date: String,
    val side: String,
    val market: String,
    val code: String,
    val name: String,
    val shares: Double,
    val price: Double,
    val amount: Double,
    val note: String,
    val index: Int
)

/** 持仓.csv 里的一行——根据交易记录滚动算出来的当前持仓状态（覆盖写，不是流水），
 * avgCost 是加权平均成本；实时价格/市值不存在这个文件里，App 打开持仓页面时现取现算，
 * 避免文件里存一个很快就过期的数字。 */
data class Holding(
    val market: String,
    val code: String,
    val name: String,
    val shares: Double,
    val avgCost: Double,
    val index: Int
)

/** 转账记录.csv 里的一行——用户自己的卡/账户之间互转的流水（比如"还信用卡"、"把工资卡的
 * 钱转到零钱卡"），只追加不改写，作为流水审计用。跟消费/收入不一样：这笔钱没有真的离开
 * 用户的总资产，所以不写进账本.csv、不计入收支总览的支出/收入，只在这张单独的表里留痕，
 * 同时会自动加减「转出/转入」两张卡各自的余额（见 [com.linkn.screenintake.store.RecordStore]
 * 的 applyTransfer）。fromCard/toCard 是原始识别出来的账户文字，不一定能在「卡片管理」里
 * 对上号（对不上就只留痕不改余额，见 RecordStore 注释）。 */
data class TransferRow(
    val date: String,
    val fromCard: String,
    val toCard: String,
    val amount: Double,
    val note: String,
    val index: Int
)

/** 体重.csv 里的一行——体重截图识别出来的读数，只追加，一天称好几次就有好几行，
 * 具体挑哪一次当"这一天的体重"留给展示的地方自己判断（目前是全部当成独立的点画进
 * 趋势图里，不做每日去重/取平均）。 */
data class WeightRow(
    val date: String,
    val weightKg: Double,
    val note: String,
    val index: Int
)

/** 健康.csv 里的一行——不是这个 App 写的，是单独跑在 Mac mini 上的 Whoop 同步脚本
 * （whoop_sync.py，定时调用 Whoop API）写的，这里只读，不提供编辑/删除——要改应该去改
 * Whoop 那边的原始数据，不是在这个 App 里瞎改。各项指标都可能抓不到、留空，用可空类型
 * 接住，不强求每一行都齐全；没有 index 字段，因为这个文件不是这个 App 负责写的，
 * 用不上"按原始行号定位去改"这一套。 */
data class HealthMetricRow(
    val date: String,
    val recoveryScore: Double?,
    val restingHeartRate: Double?,
    val hrvMs: Double?,
    val respiratoryRate: Double?,
    val sleepHours: Double?,
    val sleepEfficiency: Double?,
    val sleepPerformance: Double?,
    val sleepConsistency: Double?,
    val lightSleepHours: Double?,
    val deepSleepHours: Double?,
    val remSleepHours: Double?,
    val awakeHours: Double?,
    val strain: Double?,
    val avgHeartRate: Double?,
    val maxHeartRate: Double?,
    val calories: Double?,
    val note: String
)

data class DigitalAppUsage(val name: String, val minutes: Int, val feed: Boolean, val growth: Boolean = false)
data class DigitalHealthRow(val date: String, val totalMinutes: Int, val apps: List<DigitalAppUsage>, val summary: String)
data class ExerciseRow(
    val startedAt: String,
    val endedAt: String,
    val type: String,
    val strain: Double?,
    val averageHeartRate: Double?,
    val maxHeartRate: Double?,
    val calories: Double?,
    val distanceMeters: Double?
)

/**
 * 把 [RecordStore] 写到保存文件夹里的三个文件读回来、解析成结构化列表，给财务/待办/
 * 灵感这几个 Tab 用，不用每次都打开文件管理器看内容。只读不写，读不到或格式不对就
 * 兜底返回空列表，不让页面因为这个崩掉；写入/编辑/删除都在 [RecordStore] 那边。
 */
/** 日常照片列表用（健康 tab 的 饮食/饮料 两个子视图）：category 是 "meal" 或 "drink"，
 * timestampMillis 从文件名里的拍摄时间戳解析（解析不出来就退回文件的最后修改时间），
 * caption 是拍照时 AI 给的一句话描述，文件名里没带就是 null（比如 AI 判断失败兜底存
 * 下来的那种）。 */
data class PhotoItem(
    val fileName: String,
    val category: String,
    val timestampMillis: Long,
    val caption: String?
)

object LedgerReader {
    private val strict = ThreadLocal<Boolean>()
    private val readVersions = ThreadLocal<MutableMap<String, String>?>()
    internal fun <T> withStrictReads(block: () -> T): T {
        val previous = strict.get()
        val previousVersions = readVersions.get()
        if (previous != true) readVersions.set(mutableMapOf())
        strict.set(true)
        try { return block() } finally { strict.set(previous); readVersions.set(previousVersions) }
    }
    internal fun expectedText(uri: String): String? = readVersions.get()?.get(uri)
    internal fun didWrite(uri: String, content: String) { readVersions.get()?.set(uri, content) }
    private fun <T> failedRead(context: Context, error: Exception, fallback: List<T>): List<T> {
        if (strict.get() == true) throw IllegalStateException("读取原记录失败，已停止保存", error)
        SystemStatus.failure(context, "读取", "部分文件读取失败，保留上次内容，请检查同步目录")
        return fallback
    }
    private fun readableRoot(context: Context, folderUri: String): DocumentFile {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(folderUri)) ?: error("同步目录不可访问")
        check(root.canRead()) { "同步目录读取权限已失效" }
        return root
    }

    private data class TextCacheEntry(
        val lastModified: Long,
        val length: Long,
        val text: String
    )

    private val textCache = ConcurrentHashMap<String, TextCacheEntry>()

    /** 文件没有变化时复用已经读出的文本，避免页面刷新时反复读取整份 CSV/Markdown。 */
    private fun readText(context: Context, file: DocumentFile): String? {
        val key = file.uri.toString()
        if (strict.get() == true) {
            val text = context.contentResolver.openInputStream(file.uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: error("无法读取原记录，已停止保存")
            readVersions.get()?.putIfAbsent(key, text)
            return text
        }
        val lastModified = file.lastModified()
        val length = file.length()
        textCache[key]?.let { cached ->
            if (cached.lastModified == lastModified && cached.length == length) return cached.text
        }
        FileSnapshotCache.get(context).read(key, lastModified, length)?.let { cached ->
            textCache[key] = TextCacheEntry(lastModified, length, cached)
            return cached
        }
        val text = context.contentResolver.openInputStream(file.uri)
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: error("文件读取失败")
        textCache[key] = TextCacheEntry(lastModified, length, text)
        FileSnapshotCache.get(context).write(key, lastModified, length, text)
        return text
    }

    fun invalidateCaches() {
        textCache.clear()
    }

    /** 探测一下保存文件夹这个 SAF 授权是不是还有效。授权一旦失效（最常见的原因是把
     * App 整个卸载重装了一遍——持久化的文件夹授权跟着卸载一起被系统收回，不会自动
     * 恢复），上面这些 read* 函数会因为拿不到文件夹/文件而统一兜底返回空列表，
     * 跟"这里真的还没记过任何东西"长得一模一样，很容易被误以为是账本数据丢了。
     * 这里单独探测一遍文件夹本身能不能打开、能不能列内容，给财务页一个区分
     * "真的是空的" 和 "只是手机这边看不到了" 的依据——数据其实还在 Mac 那边的
     * 同步文件夹里，没有真的丢，只需要回设置里重新选一下同一个文件夹。 */
    fun folderAccessible(context: Context, folderUri: String): Boolean {
        if (folderUri.isBlank()) return false
        return try {
            val root = DocumentFile.fromTreeUri(context, Uri.parse(folderUri)) ?: return false
            if (!root.isDirectory) return false
            root.listFiles() // 真正触发一次 provider 查询——授权失效通常在这一步抛异常
            true
        } catch (e: Exception) {
            false
        }
    }

    fun readLedger(context: Context, folderUri: String): List<LedgerRow> {
        if (folderUri.isBlank()) return emptyList()
        return try {
            val root = readableRoot(context, folderUri)
            val file = StorageLayout.readFile(root, "账本.csv") ?: return emptyList()
            val text = readText(context, file) ?: return emptyList()
            text.lineSequence()
                .drop(1) // 第一行是表头（日期,类型,分类,金额,备注）
                .filter { it.isNotBlank() }
                .toList()
                .mapIndexedNotNull { index, line -> parseCsvLine(line)?.copy(index = index) }
        } catch (e: Exception) {
            failedRead(context, e, UiDataCache.ledger)
        }
    }

    fun readTodos(context: Context, folderUri: String): List<TodoItem> {
        if (folderUri.isBlank()) return emptyList()
        return try {
            val root = readableRoot(context, folderUri)
            val file = StorageLayout.readFile(root, "待办.md") ?: return emptyList()
            val text = readText(context, file) ?: return emptyList()
            text.lineSequence()
                .map { it.trimStart() }
                .filter { it.startsWith("- [ ]") || it.startsWith("- [x]") || it.startsWith("- [X]") }
                .toList()
                .mapIndexed { index, line ->
                    val done = line.startsWith("- [x]") || line.startsWith("- [X]")
                    val rawContent = line
                        .removePrefix("- [ ]")
                        .removePrefix("- [x]")
                        .removePrefix("- [X]")
                        .trim()
                    val (domain, content) = parseDomainTag(rawContent)
                    val metaMatch = Regex("<!--TODO_META:(\\{.*?\\})-->").find(content)
                    val meta = metaMatch?.groupValues?.getOrNull(1)?.let {
                        runCatching { org.json.JSONObject(it) }.getOrNull()
                    }
                    val visible = content
                        .replace(Regex("\\s*<!--AI_ACTION:[A-Za-z0-9._-]+-->"), "")
                        .replace(Regex("\\s*<!--TODO_META:\\{.*?\\}-->\\s*$"), "")
                    TodoItem(
                        done = done,
                        text = visible,
                        domain = meta?.optString("kind")?.takeIf { it.isNotBlank() } ?: domain,
                        index = index,
                        dueAt = meta?.optString("dueAt")?.takeIf { it.isNotBlank() && it != "null" },
                        reminderId = meta?.optString("id")?.takeIf { it.isNotBlank() }
                    )
                }
                .reversed()
        } catch (e: Exception) {
            failedRead(context, e, UiDataCache.todos)
        }
    }

    fun readMealNotes(context: Context, folderUri: String): List<MealNote> {
        if (folderUri.isBlank()) return emptyList()
        return try {
            val root = DocumentFile.fromTreeUri(context, Uri.parse(folderUri)) ?: return emptyList()
            val file = StorageLayout.readFile(root, "饮食记录.md") ?: return emptyList()
            val text = readText(context, file) ?: return emptyList()
            text.lineSequence().filter { it.startsWith("- ") }.mapIndexed { index, line ->
                val raw = line.removePrefix("- ").trim()
                val date = raw.take(16)
                MealNote(date, raw.drop(16).trim(), index)
            }.toList().reversed()
        } catch (_: Exception) { emptyList() }
    }

    fun readNotes(context: Context, folderUri: String): List<NoteItem> {
        if (folderUri.isBlank()) return emptyList()
        return try {
            val root = readableRoot(context, folderUri)
            val file = StorageLayout.readFile(root, "灵感.md") ?: return emptyList()
            val text = readText(context, file) ?: return emptyList()
            // 只有 App 写出的“## [领域]”是记录边界；正文里普通 Markdown 二级标题不能
            // 意外拆成另一条灵感。
            val blocks = text.split(Regex("(?m)(?=^## \\[)")).drop(1)
            blocks.mapIndexed { index, block ->
                val lines = block.trimEnd().lines()
                val rawHeading = lines.firstOrNull()?.trim().orEmpty()
                val content = lines.drop(1).joinToString("\n").trim()
                val (domain, heading) = parseDomainTag(rawHeading)
                NoteItem(heading = heading, content = content, domain = domain, index = index)
            }.reversed()
        } catch (e: Exception) {
            failedRead(context, e, UiDataCache.notes)
        }
    }

    fun readCards(context: Context, folderUri: String): List<CardAccount> {
        if (folderUri.isBlank()) return emptyList()
        return try {
            val root = readableRoot(context, folderUri)
            val file = StorageLayout.readFile(root, "卡片.csv") ?: return emptyList()
            val text = readText(context, file) ?: return emptyList()
            text.lineSequence()
                .drop(1) // 表头：卡名,类型,余额
                .filter { it.isNotBlank() }
                .toList()
                .mapIndexedNotNull { index, line ->
                    val fields = splitCsvLine(line)
                    if (fields.size < 3) return@mapIndexedNotNull null
                    val balance = fields[2].toDoubleOrNull() ?: return@mapIndexedNotNull null
                    CardAccount(name = fields[0], type = fields[1], balance = balance, index = index)
                }
        } catch (e: Exception) {
            failedRead(context, e, UiDataCache.cards)
        }
    }

    fun readTrades(context: Context, folderUri: String): List<TradeRow> {
        if (folderUri.isBlank()) return emptyList()
        return try {
            val root = DocumentFile.fromTreeUri(context, Uri.parse(folderUri)) ?: return emptyList()
            val file = StorageLayout.readFile(root, "交易记录.csv") ?: return emptyList()
            val text = readText(context, file) ?: return emptyList()
            text.lineSequence()
                .drop(1) // 表头：日期,方向,市场,代码,名称,股数,价格,金额,备注
                .filter { it.isNotBlank() }
                .toList()
                .mapIndexedNotNull { index, line ->
                    val fields = splitCsvLine(line)
                    if (fields.size < 9) return@mapIndexedNotNull null
                    val shares = fields[5].toDoubleOrNull() ?: return@mapIndexedNotNull null
                    val price = fields[6].toDoubleOrNull() ?: return@mapIndexedNotNull null
                    val amount = fields[7].toDoubleOrNull() ?: return@mapIndexedNotNull null
                    TradeRow(
                        date = fields[0], side = fields[1], market = fields[2], code = fields[3],
                        name = fields[4], shares = shares, price = price, amount = amount,
                        note = fields[8], index = index
                    )
                }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun readHoldings(context: Context, folderUri: String): List<Holding> {
        if (folderUri.isBlank()) return emptyList()
        return try {
            val root = readableRoot(context, folderUri)
            val file = StorageLayout.readFile(root, "持仓.csv") ?: return emptyList()
            val text = readText(context, file) ?: return emptyList()
            text.lineSequence()
                .drop(1) // 表头：市场,代码,名称,股数,成本
                .filter { it.isNotBlank() }
                .toList()
                .mapIndexedNotNull { index, line ->
                    val fields = splitCsvLine(line)
                    if (fields.size < 5) return@mapIndexedNotNull null
                    val shares = fields[3].toDoubleOrNull() ?: return@mapIndexedNotNull null
                    val avgCost = fields[4].toDoubleOrNull() ?: return@mapIndexedNotNull null
                    Holding(
                        market = fields[0], code = fields[1], name = fields[2],
                        shares = shares, avgCost = avgCost, index = index
                    )
                }
        } catch (e: Exception) {
            failedRead(context, e, UiDataCache.holdings)
        }
    }

    fun readTransfers(context: Context, folderUri: String): List<TransferRow> {
        if (folderUri.isBlank()) return emptyList()
        return try {
            val root = readableRoot(context, folderUri)
            val file = StorageLayout.readFile(root, "转账记录.csv") ?: return emptyList()
            val text = readText(context, file) ?: return emptyList()
            text.lineSequence()
                .drop(1) // 表头：日期,转出账户,转入账户,金额,备注
                .filter { it.isNotBlank() }
                .toList()
                .mapIndexedNotNull { index, line ->
                    val fields = splitCsvLine(line)
                    if (fields.size < 5) return@mapIndexedNotNull null
                    val amount = fields[3].toDoubleOrNull() ?: return@mapIndexedNotNull null
                    TransferRow(
                        date = fields[0], fromCard = fields[1], toCard = fields[2],
                        amount = amount, note = fields[4], index = index
                    )
                }
        } catch (e: Exception) {
            failedRead(context, e, UiDataCache.transfers)
        }
    }

    fun readWeights(context: Context, folderUri: String): List<WeightRow> {
        if (folderUri.isBlank()) return emptyList()
        return try {
            val root = readableRoot(context, folderUri)
            val file = StorageLayout.readFile(root, "体重.csv") ?: return emptyList()
            val text = readText(context, file) ?: return emptyList()
            text.lineSequence()
                .drop(1) // 表头：日期,体重_kg,备注
                .filter { it.isNotBlank() }
                .toList()
                .mapIndexedNotNull { index, line ->
                    val fields = splitCsvLine(line)
                    if (fields.size < 3) return@mapIndexedNotNull null
                    val weightKg = fields[1].toDoubleOrNull() ?: return@mapIndexedNotNull null
                    WeightRow(date = fields[0], weightKg = weightKg, note = fields[2], index = index)
                }
        } catch (e: Exception) {
            failedRead(context, e, UiDataCache.weights)
        }
    }

    fun readDigitalHealth(context: Context, folderUri: String): List<DigitalHealthRow> {
        if (folderUri.isBlank()) return emptyList()
        return try {
            val root = DocumentFile.fromTreeUri(context, Uri.parse(folderUri)) ?: return emptyList()
            val file = StorageLayout.readFile(root, "数字健康.csv") ?: return emptyList()
            val text = readText(context, file) ?: return emptyList()
            text.lineSequence().drop(1).filter { it.isNotBlank() }.mapNotNull { line ->
                val f = splitCsvLine(line); if (f.size < 4) return@mapNotNull null
                val apps = runCatching { val a = org.json.JSONArray(f[2]); (0 until a.length()).map { i -> a.getJSONObject(i).let { o ->
                    val name = o.optString("name")
                    DigitalAppUsage(name, o.optInt("minutes"), o.optBoolean("feed"), o.optBoolean("growth") || isGrowthApp(name))
                } } }.getOrDefault(emptyList())
                f[1].toIntOrNull()?.let { DigitalHealthRow(f[0], it, apps, f[3]) }
            }.toList()
        } catch (e: Exception) { emptyList() }
    }

    fun readExercises(context: Context, folderUri: String): List<ExerciseRow> {
        if (folderUri.isBlank()) return emptyList()
        return try {
            val root = DocumentFile.fromTreeUri(context, Uri.parse(folderUri)) ?: return emptyList()
            val file = StorageLayout.readFile(root, "运动.csv") ?: return emptyList()
            val text = readText(context, file) ?: return emptyList()
            buildList<ExerciseRow> {
                text.lineSequence().drop(1).filter { it.isNotBlank() }.forEach { line ->
                    val f = splitCsvLine(line)
                    if (f.size >= 7) add(ExerciseRow(f[0], f[1], f[2], f[3].toDoubleOrNull(), f[4].toDoubleOrNull(), f[5].toDoubleOrNull(), f[6].toDoubleOrNull(), f.getOrNull(7)?.toDoubleOrNull()))
                }
            }.sortedByDescending { it.startedAt }
        } catch (_: Exception) { emptyList<ExerciseRow>() }
    }

    /** 不依赖某次识别的细分结果：常见学习/阅读软件即使旧记录没有 growth 字段也可归类。 */
    private fun isGrowthApp(name: String): Boolean {
        val value = name.lowercase(Locale.CHINA)
        return listOf("英语", "英文", "背单词", "扇贝", "百词", "流利说", "阅读", "读书", "微信读书", "kindle", "得到", "得到app", "coursera", "udemy", "mooc", "学习通").any { value.contains(it) }
    }

    fun readHealthMetrics(context: Context, folderUri: String): List<HealthMetricRow> {
        if (folderUri.isBlank()) return emptyList()
        return try {
            val root = DocumentFile.fromTreeUri(context, Uri.parse(folderUri)) ?: return emptyList()
            val file = StorageLayout.readFile(root, "健康.csv") ?: return emptyList()
            val text = readText(context, file) ?: return emptyList()
            fun numOrNull(s: String) = s.trim().toDoubleOrNull()
            text.lineSequence()
                // 表头（2026-09-16 起，Mac mini 上的 whoop_sync.py 换成了更详细的版本，
                // 在原来"压力值Strain"前面插入了 6 列睡眠细分数据，后面的列整体往后挪了
                // 6 位——这里必须跟着改，不然从这一列开始全部对不上号）：
                // 日期,恢复评分,静息心率,心率变异性HRV_ms,呼吸频率,睡眠时长_小时,
                // 睡眠效率,睡眠表现,睡眠规律,浅睡_小时,深睡_小时,REM_小时,清醒_小时,
                // 压力值Strain,平均心率,最高心率,消耗千卡,备注
                .drop(1)
                .filter { it.isNotBlank() }
                .toList()
                .mapNotNull { line ->
                    val fields = splitCsvLine(line)
                    if (fields.size < 18) return@mapNotNull null
                    HealthMetricRow(
                        date = fields[0],
                        recoveryScore = numOrNull(fields[1]),
                        restingHeartRate = numOrNull(fields[2]),
                        hrvMs = numOrNull(fields[3]),
                        respiratoryRate = numOrNull(fields[4]),
                        sleepHours = numOrNull(fields[5]),
                        sleepEfficiency = numOrNull(fields[6]),
                        sleepPerformance = numOrNull(fields[7]),
                        sleepConsistency = numOrNull(fields[8]),
                        lightSleepHours = numOrNull(fields[9]),
                        deepSleepHours = numOrNull(fields[10]),
                        remSleepHours = numOrNull(fields[11]),
                        awakeHours = numOrNull(fields[12]),
                        strain = numOrNull(fields[13]),
                        avgHeartRate = numOrNull(fields[14]),
                        maxHeartRate = numOrNull(fields[15]),
                        calories = numOrNull(fields[16]),
                        note = fields[17]
                    )
                }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private val photoStampFmt = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.CHINA)

    /** 见 [RecordStore.savePhoto]：文件名格式是 "yyyy-MM-dd_HHmmss.jpg" 或者带一段摘要的
     * "yyyy-MM-dd_HHmmss_摘要.jpg"——时间戳本身内部就带一个下划线（日期和时间之间），
     * 所以按下划线切开之后取前两段拼回时间戳，第三段往后（如果有）才是摘要。 */
    fun readPhotos(context: Context, folderUri: String, category: String): List<PhotoItem> {
        if (folderUri.isBlank()) return emptyList()
        return try {
            val root = DocumentFile.fromTreeUri(context, Uri.parse(folderUri)) ?: return emptyList()
            val dirName = if (category == "drink") "饮料" else "三餐"
            photoDirectories(root, dirName)
                .flatMap { dir -> dir.listFiles().toList() }
                .filter { it.isFile && it.name != null }
                .mapNotNull { f ->
                    val name = f.name ?: return@mapNotNull null
                    val base = name.substringBeforeLast(".")
                    val parts = base.split("_")
                    val stamp = if (parts.size >= 2) "${parts[0]}_${parts[1]}" else base
                    val caption = if (parts.size > 2) parts.drop(2).joinToString("_").ifBlank { null } else null
                    val millis = try {
                        photoStampFmt.parse(stamp)?.time
                    } catch (e: Exception) {
                        null
                    } ?: f.lastModified()
                    PhotoItem(fileName = name, category = category, timestampMillis = millis, caption = caption)
                }
                .associateBy { it.fileName }
                .values
                .sortedByDescending { it.timestampMillis }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 饮食/饮料列表里显示缩略图用：先只读图片边界算出缩放倍数，再按缩放后的尺寸解码，
     * 避免相机拍的原图（好几 MB）直接整张塞进内存——列表里本来就用不到那么高的分辨率。
     * 项目里没有引入 Coil/Glide 这类图片加载库，就手写这一个简单的按需缩放解码。 */
    fun loadPhotoThumbnail(
        context: Context,
        folderUri: String,
        category: String,
        fileName: String,
        maxSize: Int = 400
    ): Bitmap? {
        return try {
            val root = DocumentFile.fromTreeUri(context, Uri.parse(folderUri)) ?: return null
            val dirName = if (category == "drink") "饮料" else "三餐"
            val file = photoDirectories(root, dirName).firstNotNullOfOrNull { it.findFile(fileName) } ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(file.uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            var sample = 1
            while (bounds.outWidth / sample > maxSize || bounds.outHeight / sample > maxSize) {
                sample *= 2
            }
            // Keep normal colour fidelity: these previews are small on screen,
            // but they are still viewed on a high-density display.
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(file.uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 日常照片只使用统一中枢的“健康/日常照片”，不再并行扫描旧目录。 */
    private fun photoDirectories(root: DocumentFile, childName: String): List<DocumentFile> {
        // 照片是由 Syncthing 从外部写入的，绕过可能已过期的目录缓存，直接定位正式目录。
        val rootDir = root.findFile(StorageLayout.HEALTH)?.takeIf { it.isDirectory }
            ?.findFile("日常照片")?.takeIf { it.isDirectory }
            ?: return emptyList()
        return listOfNotNull(rootDir.findFile(childName)?.takeIf(DocumentFile::isDirectory))
    }

    private fun parseCsvLine(line: String): LedgerRow? {
        val fields = splitCsvLine(line)
        if (fields.size < 5) return null
        val amount = fields[3].toDoubleOrNull() ?: return null
        return LedgerRow(
            date = fields[0],
            type = fields[1],
            category = fields[2],
            amount = amount,
            note = fields[4],
            index = 0 // 调用方 mapIndexedNotNull 里会 .copy(index = ...) 覆盖成真实位置
        )
    }

    private val DOMAIN_TAG_REGEX = Regex("^\\[(财务|健康|工作|生活|习惯|其他)\\]\\s*(.*)$")

    /** 从待办/灵感的原始文字里解析开头的 "[域名]" 标签，返回 (域名, 去掉标签之后的文字)。
     * 解析不到（这个功能上线之前记的老数据，或者标签本身没写对）统一兜底成"其他"，
     * 原文字原样保留，不当成错误处理——向后兼容是硬要求。 */
    private fun parseDomainTag(raw: String): Pair<String, String> {
        val match = DOMAIN_TAG_REGEX.find(raw) ?: return "其他" to raw
        val domain = match.groupValues[1]
        val rest = match.groupValues[2]
        return domain to rest
    }

    /**
     * 简单的一行 CSV 拆分：支持双引号包裹字段、内部双引号用两个双引号转义，
     * 跟 [RecordStore] 写入时用的格式对应，普通的按逗号 split 处理不了备注里带逗号的情况。
     */
    private fun splitCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQuotes && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                    sb.append('"')
                    i++
                }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> {
                    result.add(sb.toString())
                    sb.clear()
                }
                else -> sb.append(c)
            }
            i++
        }
        result.add(sb.toString())
        return result
    }
}
