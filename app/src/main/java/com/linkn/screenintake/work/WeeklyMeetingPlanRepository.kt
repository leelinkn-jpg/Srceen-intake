package com.linkn.screenintake.work

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.linkn.screenintake.store.FileSnapshotCache
import com.linkn.screenintake.store.StorageLayout
import org.json.JSONObject

data class WeeklyMeetingPlan(
    val id: String,
    val week: String,
    val reason: String,
    val evidence: String,
    val suggestedAt: String
)

/** 周会时间只由用户确认；Mini 只写请求，不能把推测时间当作既定安排。 */
class WeeklyMeetingPlanRepository(private val context: Context) {
    fun pending(folderUri: String): List<WeeklyMeetingPlan> = runCatching {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(folderUri)) ?: return@runCatching emptyList()
        val work = StorageLayout.domain(root, StorageLayout.WORK, false) ?: return@runCatching emptyList()
        val dir = work.findFile("待确认计划") ?: return@runCatching emptyList()
        dir.listFiles().filter { it.isFile && it.name?.endsWith(".json") == true }.mapNotNull { file ->
            val json = FileSnapshotCache.readFile(context, file)?.let(::JSONObject) ?: return@mapNotNull null
            if (json.optString("status") != "pending") return@mapNotNull null
            WeeklyMeetingPlan(
                id = json.optString("planId").ifBlank { file.name.orEmpty().removeSuffix(".json") },
                week = json.optString("week"), reason = json.optString("reason"),
                evidence = json.optString("evidence"), suggestedAt = json.optString("suggestedAt")
            )
        }.sortedByDescending { it.week }
    }.getOrDefault(emptyList())

    fun respond(folderUri: String, plan: WeeklyMeetingPlan, scheduledAt: String?, skip: Boolean) {
        require(plan.id.matches(Regex("[A-Za-z0-9._-]{1,100}"))) { "无效周会计划" }
        if (!skip) require(!scheduledAt.isNullOrBlank()) { "请填写周会时间" }
        val root = DocumentFile.fromTreeUri(context, Uri.parse(folderUri)) ?: error("无法读取同步目录")
        val work = StorageLayout.domain(root, StorageLayout.WORK, true) ?: error("无法创建工作目录")
        val feedback = work.findFile("计划反馈") ?: work.createDirectory("计划反馈") ?: error("无法创建计划反馈目录")
        val file = feedback.findFile("${plan.id}.json") ?: feedback.createFile("application/json", "${plan.id}.json")
            ?: error("无法创建计划反馈")
        val result = JSONObject().put("planId", plan.id).put("status", if (skip) "skipped" else "confirmed")
            .put("scheduledAt", if (skip) JSONObject.NULL else scheduledAt)
            .put("respondedAt", System.currentTimeMillis()).toString(2)
        context.contentResolver.openOutputStream(file.uri, "wt")?.use { it.write(result.toByteArray(Charsets.UTF_8)) }
            ?: error("无法写入计划反馈")
    }
}
