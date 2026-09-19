package com.linkn.screenintake.report

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.linkn.screenintake.store.RecordStore
import com.linkn.screenintake.store.FileSnapshotCache
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

enum class ReportDomain(val wire: String, val label: String, val directory: String) {
    OVERALL("overall", "综合", "综合报告"), FINANCE("finance", "财务", "财务"),
    HEALTH("health", "健康", "健康"), WORK("work", "工作", "工作"), GROWTH("growth", "成长", "成长");
    companion object { fun fromWire(value: String) = entries.firstOrNull { it.wire == value } ?: OVERALL }
}

enum class ReportPeriod(val wire: String, val label: String) {
    DAILY("daily", "日报"), WEEKLY("weekly", "周报"), MONTHLY("monthly", "月报");
    companion object { fun fromWire(value: String) = entries.firstOrNull { it.wire == value } ?: DAILY }
}

data class ReportAction(val id: String, val title: String, val reason: String, val domain: String,
    val dueAt: String? = null)

data class ReportDomainSection(val domain: ReportDomain, val title: String, val summary: String,
    val content: String, val severity: String = "normal")

data class AiReport(val id: String, val domain: ReportDomain, val period: ReportPeriod, val title: String,
    val summary: String, val severity: String, val periodStart: String, val periodEnd: String,
    val generatedAt: Long, val dataCutoff: String, val missingData: List<String>, val actions: List<ReportAction>,
    val sections: List<ReportDomainSection>, val body: String, val metadataUri: String, val revision: Int = 1) {
    val important: Boolean get() = severity == "attention" || severity == "urgent"
}

class AiReportRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("ai_reports", Context.MODE_PRIVATE)

    private data class ReportCache(val signature: String, val reports: List<AiReport>)
    companion object {
        private val listCache = java.util.concurrent.ConcurrentHashMap<String, ReportCache>()
        private val responseLock = Any()
    }

    @Synchronized
    fun list(folderUri: String): List<AiReport> {
        if (folderUri.isBlank()) return emptyList()
        val root = DocumentFile.fromTreeUri(context, Uri.parse(folderUri)) ?: return emptyList()
        val reportDir = root.findFile(ReportDomain.OVERALL.directory) ?: return emptyList()
        val files = reportDir.listFiles().filter { file ->
                file.isFile && file.name?.endsWith(".json", true) == true &&
                    file.name?.startsWith(".") != true && file.name != "反馈.json"
            }
        // JSON 和 Markdown 是一个发布单元。只看 JSON 会在 Syncthing 乱序到达时把“半份
        // 报告”缓存成空列表，随后正文到了也不会重新读取。
        val signature = files.sortedBy { it.name }.joinToString("|") { metadata ->
            val base = metadata.name.orEmpty().removeSuffix(".json")
            val markdown = reportDir.findFile("$base.md")
            "${metadata.name}:${metadata.lastModified()}:${metadata.length()}:" +
                "${markdown?.lastModified() ?: -1}:${markdown?.length() ?: -1}"
        }
        listCache[folderUri]?.takeIf { it.signature == signature }?.let { return it.reports }
        val previous = listCache[folderUri]?.reports.orEmpty().associateBy { it.metadataUri }
        var incomplete = false
        return files.mapNotNull { file ->
            parseReport(reportDir, file, ReportDomain.OVERALL) ?: run {
                incomplete = true
                previous[file.uri.toString()] ?: loadValidated(file.uri.toString())
            }
        }
            .filter { it.domain == ReportDomain.OVERALL }
            .sortedWith(compareByDescending<AiReport> { it.generatedAt }.thenByDescending { it.revision })
            .distinctBy { it.id }
            .also { listCache[folderUri] = ReportCache(if (incomplete) "" else signature, it) }
    }

    fun latest(folderUri: String): AiReport? = list(folderUri).firstOrNull()
    fun isRead(report: AiReport) = prefs.getInt("read:${report.id}", 0) >= report.revision
    fun markRead(report: AiReport) { prefs.edit().putInt("read:${report.id}", report.revision).apply() }
    fun unreadCount(reports: List<AiReport>) = reports.count { !isRead(it) }
    fun wasNotified(report: AiReport) = prefs.getInt("notified:${report.id}", 0) >= report.revision
    fun markNotified(report: AiReport) { prefs.edit().putInt("notified:${report.id}", report.revision).apply() }

    @Synchronized
    fun respond(folderUri: String, report: AiReport, action: ReportAction, accepted: Boolean) = synchronized(responseLock) {
        require(action.id.isNotBlank()) { "建议动作缺少编号" }
        val existing = feedback(folderUri, report.id, action.id)
        if (existing == "accepted" || existing == "rejected") return@synchronized
        if (accepted) {
            RecordStore(context).addReportTodo(folderUri, action.id, action.title, action.reason,
                normalizeDomain(action.domain), action.dueAt)
        }
        writeFeedback(folderUri, report, action, if (accepted) "accepted" else "rejected")
        listCache.remove(folderUri)
        ReportChangeSignal.bump()
    }

    fun feedback(folderUri: String, reportId: String, actionId: String): String? = runCatching {
        val root = root(folderUri, false) ?: return@runCatching null
        val file = root.findFile("反馈")?.findFile("${safe(reportId)}_${safe(actionId)}.json") ?: return@runCatching null
        context.contentResolver.openInputStream(file.uri)?.bufferedReader()?.use {
            JSONObject(it.readText()).optString("status").takeIf(String::isNotBlank)
        }
    }.getOrNull()

    private fun parseReport(dir: DocumentFile, metadata: DocumentFile, fallbackDomain: ReportDomain): AiReport? = runCatching {
        val json = FileSnapshotCache.readFile(context, metadata)?.let { JSONObject(it) }
            ?: return@runCatching null
        if (json.optString("status") != "complete") return@runCatching null
        val base = metadata.name.orEmpty().removeSuffix(".json")
        val markdown = dir.findFile("$base.md") ?: return@runCatching null
        val body = FileSnapshotCache.readFile(context, markdown)
            ?: return@runCatching null
        val expectedMarkdownHash = json.optString("markdownSha256")
        if (expectedMarkdownHash.isNotBlank() && expectedMarkdownHash != sha256(body)) return@runCatching null
        val report = decodeReport(json, body, metadata.uri.toString(), fallbackDomain, metadata.lastModified())
        val cache = validatedFile(metadata.uri.toString())
        val stream = cache.startWrite()
        try {
            stream.write(JSONObject().put("metadata", json).put("body", body).toString().toByteArray(Charsets.UTF_8))
            cache.finishWrite(stream)
        } catch (e: Exception) { cache.failWrite(stream); throw e }
        report
    }.getOrNull()

    private fun validatedFile(uri: String) = android.util.AtomicFile(java.io.File(context.cacheDir, "report-${sha256(uri)}.json"))

    private fun loadValidated(uri: String): AiReport? = runCatching {
        val cached = JSONObject(validatedFile(uri).openRead().bufferedReader().use { it.readText() })
        decodeReport(cached.getJSONObject("metadata"), cached.getString("body"), uri, ReportDomain.OVERALL, 0)
    }.getOrNull()

    private fun decodeReport(json: JSONObject, body: String, uri: String, fallbackDomain: ReportDomain, modified: Long): AiReport {
        val missing = json.optJSONArray("missingData") ?: JSONArray()
        val actions = json.optJSONArray("suggestedActions") ?: JSONArray()
        val sections = json.optJSONArray("domainSections") ?: JSONArray()
        return AiReport(
            id = json.getString("reportId"), domain = ReportDomain.fromWire(json.optString("domain", fallbackDomain.wire)),
            period = ReportPeriod.fromWire(json.optString("periodType")), title = json.optString("title", "AI报告"),
            summary = json.optString("summary"), severity = json.optString("severity", "normal"),
            periodStart = json.optString("periodStart"), periodEnd = json.optString("periodEnd"),
            generatedAt = json.optLong("generatedAt", modified), dataCutoff = json.optString("dataCutoff"),
            missingData = (0 until missing.length()).map { missing.optString(it) }.filter(String::isNotBlank),
            actions = (0 until actions.length()).mapNotNull { index -> actions.optJSONObject(index)?.let { action ->
                ReportAction(action.getString("id"), action.getString("title"), action.optString("reason"),
                    action.optString("domain", fallbackDomain.label), action.optString("dueAt").takeIf(String::isNotBlank))
            } }, sections = (0 until sections.length()).mapNotNull { index -> sections.optJSONObject(index)?.let { section ->
                val domain = ReportDomain.fromWire(section.optString("domain"))
                if (domain == ReportDomain.OVERALL) null else ReportDomainSection(domain, section.optString("title"),
                    section.optString("summary"), section.optString("content"), section.optString("severity", "normal"))
            } }, body = body, metadataUri = uri, revision = json.optInt("revision", 1)
        )
    }

    private fun writeFeedback(folderUri: String, report: AiReport, action: ReportAction, status: String) {
        val root = root(folderUri, true) ?: error("无法创建综合报告目录")
        val dir = root.findFile("反馈") ?: root.createDirectory("反馈") ?: error("无法创建报告反馈目录")
        val name = "${safe(report.id)}_${safe(action.id)}.json"
        val file = dir.findFile(name) ?: dir.createFile("application/json", name) ?: error("无法创建反馈文件")
        val json = JSONObject().put("reportId", report.id).put("actionId", action.id).put("status", status)
            .put("respondedAt", System.currentTimeMillis()).put("title", action.title)
        context.contentResolver.openOutputStream(file.uri, "wt")?.use { it.write(json.toString(2).toByteArray()) }
            ?: error("无法写入报告反馈")
    }

    private fun root(folderUri: String, create: Boolean): DocumentFile? {
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(folderUri)) ?: return null
        return tree.findFile("综合报告") ?: if (create) tree.createDirectory("综合报告") else null
    }
    private fun safe(value: String) = value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(100)
    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun normalizeDomain(value: String) = when (value.lowercase()) {
        "finance", "财务" -> "财务"; "health", "健康" -> "健康"; "growth", "成长", "习惯" -> "习惯"
        else -> "工作"
    }
}

object ReportChangeSignal {
    val tick = androidx.compose.runtime.mutableStateOf(0L)
    fun bump() { tick.value = System.currentTimeMillis() }
}
