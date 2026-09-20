package com.linkn.screenintake.work
import com.linkn.screenintake.store.HubIO
import com.linkn.screenintake.store.HubRoot

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.linkn.screenintake.store.FileSnapshotCache
import com.linkn.screenintake.store.StorageLayout
import org.json.JSONObject

data class WorkChange(val id: String, val kind: String, val title: String, val evidence: String, val before: String, val after: String)

class WorkChangeRepository(private val context: Context) {
    fun pending(folder: String): List<WorkChange> = runCatching {
        val root = HubRoot.resolve(context, folder) ?: return@runCatching emptyList()
        val work = StorageLayout.domain(root, StorageLayout.WORK, false) ?: return@runCatching emptyList()
        val dir = work.findFile("待确认变更") ?: return@runCatching emptyList()
        dir.listFiles().filter { it.isFile && it.name?.endsWith(".json") == true }.mapNotNull { file ->
            val j = FileSnapshotCache.readFile(context, file)?.let(::JSONObject) ?: return@mapNotNull null
            if (j.optString("status") != "pending") return@mapNotNull null
            WorkChange(j.optString("changeId").ifBlank { file.name.orEmpty().removeSuffix(".json") }, j.optString("kind"),
                j.optString("title", j.optString("kind")), j.optString("evidence"), j.opt("before")?.toString().orEmpty(), j.opt("after")?.toString().orEmpty())
        }
    }.getOrDefault(emptyList())
    fun respond(folder: String, change: WorkChange, accepted: Boolean) {
        require(change.id.matches(Regex("[A-Za-z0-9._-]{1,100}"))) { "无效变更编号" }
        val root = HubRoot.resolve(context, folder) ?: error("无法读取同步目录")
        val work = StorageLayout.domain(root, StorageLayout.WORK, true) ?: error("无法创建工作目录")
        val dir = work.findFile("变更反馈") ?: work.createDirectory("变更反馈") ?: error("无法创建变更反馈")
        val file = dir.findFile("${change.id}.json") ?: dir.createFile("application/json", "${change.id}.json") ?: error("无法创建反馈")
        val text = JSONObject().put("changeId", change.id).put("status", if (accepted) "accepted" else "rejected")
            .put("respondedAt", System.currentTimeMillis()).toString(2)
        HubIO.openOutput(context, file, "wt")?.use { it.write(text.toByteArray()) } ?: error("无法写入反馈")
    }
}
