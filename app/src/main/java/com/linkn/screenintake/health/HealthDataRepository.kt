package com.linkn.screenintake.health
import com.linkn.screenintake.store.HubIO
import com.linkn.screenintake.store.HubRoot

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.linkn.screenintake.store.FileSnapshotCache
import com.linkn.screenintake.store.StorageLayout
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Mini 对照片和 Whoop 数据做出的当日汇总。数值为估算时由 Mini 在 summary 中明确说明。 */
data class NutritionSummary(
    val date: String,
    val intakeCalories: Int?,
    val expenditureCalories: Int?,
    val balanceCalories: Int?,
    val summary: String,
    val dataCutoff: String
)

data class AlcoholRecord(
    val id: String,
    val date: String,
    val drinks: String,
    val startedAt: String,
    val endedAt: String,
    val source: String
)

data class AlcoholCandidate(val id: String, val summary: String, val suggestedAt: String, val sourcePhoto: String)
data class GrowthUsageCandidate(val id: String, val date: String, val appName: String, val minutes: Int, val suggestedActivity: String)

/** 健康目录中的可读模型输出、酒精记录与待确认成长使用时长。 */
class HealthDataRepository(private val context: Context) {
    fun nutrition(folder: String): NutritionSummary? = runCatching {
        val file = healthFile(folder, "营养分析.json", false) ?: return@runCatching null
        val root = JSONObject(FileSnapshotCache.readFile(context, file).orEmpty())
        val latest = root.optJSONObject("latest") ?: root
        val intake = latest.optIntOrNull("intakeCalories", "摄入千卡")
        val burn = latest.optIntOrNull("expenditureCalories", "消耗千卡")
        NutritionSummary(
            date = latest.optString("date", latest.optString("日期")),
            intakeCalories = intake,
            expenditureCalories = burn,
            balanceCalories = latest.optIntOrNull("balanceCalories", "热量差额") ?: if (intake != null && burn != null) intake - burn else null,
            summary = latest.optString("summary", latest.optString("饮食情况")),
            dataCutoff = latest.optString("dataCutoff", root.optString("dataCutoff"))
        )
    }.getOrNull()

    fun alcoholRecords(folder: String): List<AlcoholRecord> = runCatching {
        val file = healthFile(folder, "酒精记录.json", false) ?: return@runCatching emptyList()
        val array = JSONObject(FileSnapshotCache.readFile(context, file).orEmpty()).optJSONArray("records") ?: JSONArray()
        (0 until array.length()).mapNotNull { array.optJSONObject(it)?.toAlcohol() }.sortedByDescending { it.startedAt.ifBlank { it.date } }
    }.getOrDefault(emptyList())

    fun alcoholCandidates(folder: String): List<AlcoholCandidate> = pendingFiles(folder, "酒精待确认") { file, j ->
        AlcoholCandidate(file.name.orEmpty().removeSuffix(".json"), j.optString("summary", j.optString("description")), j.optString("suggestedAt"), j.optString("sourcePhoto"))
    }

    fun createAlcoholCandidate(folder: String, summary: String, sourcePhoto: String) {
        val root = root(folder) ?: error("无法读取同步目录")
        val health = StorageLayout.domain(root, StorageLayout.HEALTH, true) ?: error("无法读取健康目录")
        val dir = health.findFile("酒精待确认") ?: health.createDirectory("酒精待确认") ?: error("无法创建待确认目录")
        val id = "alcohol_${UUID.randomUUID()}"
        val file = dir.createFile("application/json", "$id.json") ?: error("无法创建待确认记录")
        val body = JSONObject().put("id", id).put("status", "pending").put("summary", summary)
            .put("sourcePhoto", sourcePhoto).put("suggestedAt", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date())).toString(2)
        HubIO.openOutput(context, file, "wt")?.use { it.write(body.toByteArray()) } ?: error("无法写入待确认记录")
    }

    fun growthUsageCandidates(folder: String): List<GrowthUsageCandidate> = pendingFiles(folder, "成长使用待确认") { file, j ->
        GrowthUsageCandidate(file.name.orEmpty().removeSuffix(".json"), j.optString("date"), j.optString("appName"), j.optInt("minutes"), j.optString("suggestedActivity"))
    }

    fun addAlcohol(folder: String, drinks: String, startedAt: String, endedAt: String, source: String = "手动添加") {
        require(drinks.isNotBlank()) { "请填写酒类和饮用量" }
        val records = alcoholRecords(folder).toMutableList()
        val date = startedAt.take(10).ifBlank { SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date()) }
        records += AlcoholRecord(UUID.randomUUID().toString(), date, drinks.trim(), startedAt.trim(), endedAt.trim(), source)
        writeAlcohol(folder, records)
    }

    fun confirmAlcoholCandidate(folder: String, candidate: AlcoholCandidate, drinks: String, startedAt: String, endedAt: String) {
        addAlcohol(folder, drinks, startedAt, endedAt, if (candidate.sourcePhoto.isBlank()) "拍照识别确认" else "拍照识别：${candidate.sourcePhoto}")
        respond(folder, "酒精待确认", candidate.id, "confirmed")
    }

    fun rejectAlcoholCandidate(folder: String, id: String) = respond(folder, "酒精待确认", id, "rejected")
    fun respondGrowthUsage(folder: String, id: String, accepted: Boolean) = respond(folder, "成长使用待确认", id, if (accepted) "accepted" else "rejected", feedbackDir = "成长使用反馈")

    private fun writeAlcohol(folder: String, records: List<AlcoholRecord>) {
        val file = healthFile(folder, "酒精记录.json", true) ?: error("无法创建酒精记录")
        val array = JSONArray(records.map { it.toJson() })
        val body = JSONObject().put("schemaVersion", 1).put("updatedAt", System.currentTimeMillis()).put("records", array).toString(2)
        HubIO.openOutput(context, file, "wt")?.use { it.write(body.toByteArray()) } ?: error("无法写入酒精记录")
    }

    private fun <T> pendingFiles(folder: String, directory: String, map: (DocumentFile, JSONObject) -> T): List<T> = runCatching {
        val root = root(folder) ?: return@runCatching emptyList()
        val health = StorageLayout.domain(root, StorageLayout.HEALTH, false) ?: return@runCatching emptyList()
        val dir = health.findFile(directory) ?: return@runCatching emptyList()
        dir.listFiles().filter { it.isFile && it.name?.endsWith(".json", true) == true }.mapNotNull { file ->
            val json = FileSnapshotCache.readFile(context, file)?.let(::JSONObject) ?: return@mapNotNull null
            if (json.optString("status", "pending") != "pending") null else map(file, json)
        }
    }.getOrDefault(emptyList())

    private fun respond(folder: String, directory: String, id: String, status: String, feedbackDir: String = directory) {
        require(id.matches(Regex("[A-Za-z0-9._-]{1,120}"))) { "无效记录编号" }
        val root = root(folder) ?: error("无法读取同步目录")
        val health = StorageLayout.domain(root, StorageLayout.HEALTH, true) ?: error("无法读取健康目录")
        val dir = health.findFile(feedbackDir) ?: health.createDirectory(feedbackDir) ?: error("无法创建反馈目录")
        val file = dir.findFile("$id.json") ?: dir.createFile("application/json", "$id.json") ?: error("无法创建反馈")
        val body = JSONObject().put("id", id).put("status", status).put("respondedAt", System.currentTimeMillis()).toString(2)
        HubIO.openOutput(context, file, "wt")?.use { it.write(body.toByteArray()) } ?: error("无法写入反馈")
    }

    private fun healthFile(folder: String, name: String, create: Boolean): DocumentFile? {
        val root = root(folder) ?: return null
        return if (create) StorageLayout.writableFile(context, root, name, "application/json") else StorageLayout.readFile(root, name)
    }
    private fun root(folder: String) = folder.takeIf { it.isNotBlank() }?.let { HubRoot.resolve(context, it) }
    private fun JSONObject.optIntOrNull(vararg keys: String): Int? = keys.firstNotNullOfOrNull { key ->
        when (val value = opt(key)) { is Number -> value.toInt(); is String -> value.toIntOrNull(); else -> null }
    }
    private fun JSONObject.toAlcohol() = AlcoholRecord(optString("id"), optString("date"), optString("drinks"), optString("startedAt"), optString("endedAt"), optString("source"))
    private fun AlcoholRecord.toJson() = JSONObject().put("id", id).put("date", date).put("drinks", drinks).put("startedAt", startedAt).put("endedAt", endedAt).put("source", source)
}
