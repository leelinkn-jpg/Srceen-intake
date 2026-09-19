package com.linkn.screenintake.growth

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import androidx.documentfile.provider.DocumentFile
import com.linkn.screenintake.store.StorageLayout
import com.linkn.screenintake.store.FileSnapshotCache
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.UUID

data class GrowthActivity(val id: String, val name: String, val weeklyTargetMinutes: Int = 0, val archived: Boolean = false) {
    fun toJson() = JSONObject().put("id", id).put("name", name)
        .put("weeklyTargetMinutes", weeklyTargetMinutes).put("archived", archived)
    companion object { fun fromJson(j: JSONObject) = GrowthActivity(j.getString("id"), j.getString("name"),
        j.optInt("weeklyTargetMinutes"), j.optBoolean("archived")) }
}

data class GrowthArtifact(val name: String, val uri: String, val mimeType: String, val preview: String = "")

data class GrowthEffort(val id: String, val activityId: String, val activityName: String, val startedAt: Long,
    val durationMs: Long = 0, val artifacts: List<GrowthArtifact> = emptyList()) {
    fun toJson() = JSONObject().put("id", id).put("activityId", activityId).put("activityName", activityName)
        .put("kind", activityId).put("label", activityName).put("startedAt", startedAt).put("durationMs", durationMs)
    companion object { fun fromJson(j: JSONObject) = GrowthEffort(j.getString("id"),
        j.optString("activityId", j.optString("kind")), j.optString("activityName", j.optString("label")),
        j.getLong("startedAt"), j.optLong("durationMs")) }
}

/** 一次努力的 ID 是计时记录和外部学习文件之间唯一、稳定的关联键。 */
class GrowthEffortRepository(private val context: Context) {
    private val effortsFile = File(context.filesDir, "growth-efforts-v2.json")
    private val activeFile = File(context.filesDir, "growth-active-v2.json")
    private val activitiesFile = File(context.filesDir, "growth-activities.json")
    private val legacySessionsFile = File(context.filesDir, "growth-sessions.json")

    fun activities(folderUri: String = ""): List<GrowthActivity> {
        val stored = readArray(activitiesFile, GrowthActivity::fromJson)
        // 文件存在（即使为 []）代表用户明确保存过，不能被远端或默认项目覆盖。
        if (activitiesFile.exists()) return stored
        readRemoteActivities(folderUri).takeIf { it.isNotEmpty() }?.let {
            saveActivities(it)
            return it
        }
        return listOf(GrowthActivity("英语", "英语", 180), GrowthActivity("价值投资", "价值投资", 120),
            GrowthActivity("冥想", "冥想")).also(::saveActivities)
    }

    /** 把项目目标同步为可读的中文目录文件，供 Mini 的成长分析读取。 */
    fun syncActivities(folderUri: String) {
        if (folderUri.isBlank()) return
        val root = DocumentFile.fromTreeUri(context, Uri.parse(folderUri)) ?: return
        val file = StorageLayout.writableFile(context, root, "项目.json", "application/json")
        val payload = JSONObject()
            .put("schemaVersion", 1)
            .put("updatedAt", System.currentTimeMillis())
            .put("projects", JSONArray(activities().map(GrowthActivity::toJson)))
            .toString(2)
        context.contentResolver.openOutputStream(file.uri, "wt")?.use {
            it.write(payload.toByteArray(Charsets.UTF_8))
        } ?: error("无法写入成长项目")
    }

    fun addActivity(name: String, target: Int): GrowthActivity {
        val clean = validName(name)
        val all = activities()
        require(all.none { !it.archived && it.name.equals(clean, true) }) { "已经有同名项目" }
        return GrowthActivity(UUID.randomUUID().toString(), clean, target.coerceIn(0, 10_080)).also {
            saveActivities(all + it)
        }
    }

    fun updateActivity(activity: GrowthActivity, name: String, target: Int): GrowthActivity {
        val updated = activity.copy(name = validName(name), weeklyTargetMinutes = target.coerceIn(0, 10_080))
        saveActivities(activities().map { if (it.id == activity.id) updated else it })
        return updated
    }

    fun archiveActivity(activity: GrowthActivity) {
        require(active()?.activityId != activity.id) { "请先结束正在进行的记录" }
        saveActivities(activities().map { if (it.id == activity.id) it.copy(archived = true) else it })
    }

    fun active(): GrowthEffort? = if (!activeFile.exists()) null else runCatching {
        GrowthEffort.fromJson(JSONObject(activeFile.readText()))
    }.getOrNull()

    fun start(activity: GrowthActivity): GrowthEffort {
        require(active() == null) { "已有一项努力正在进行" }
        return GrowthEffort(UUID.randomUUID().toString(), activity.id, activity.name, System.currentTimeMillis()).also {
            write(activeFile, it.toJson().toString(2))
        }
    }

    fun finish(folderUri: String): GrowthEffort? {
        val active = active() ?: return null
        val finished = active.copy(durationMs = (System.currentTimeMillis() - active.startedAt).coerceAtLeast(0))
        saveEfforts(localEfforts().filterNot { it.id == finished.id } + finished)
        if (folderUri.isNotBlank()) export(folderUri, finished)
        activeFile.delete()
        return finished
    }

    fun efforts(folderUri: String): List<GrowthEffort> {
        val merged = localEfforts().associateBy { it.id }.toMutableMap()
        if (folderUri.isNotBlank()) runCatching {
            growthRoot(folderUri, false)?.listFiles().orEmpty().filter { it.isDirectory }.forEach { directory ->
                val record = readRemoteRecord(directory) ?: return@forEach
                // 列表页只需要附件数和名称；正文预览留到用户真正打开一条记录时再读。
                merged[record.id] = record.copy(artifacts = readArtifacts(directory, includePreview = false))
            }
        }
        val result = merged.values.sortedByDescending { it.startedAt }
        saveEfforts(result.map { it.copy(artifacts = emptyList()) })
        return result
    }

    fun weeklyMinutes(activityId: String): Int = localEfforts().filter {
        it.activityId == activityId && thisWeek(it.startedAt)
    }.sumOf { (it.durationMs / 60_000).toInt() }

    fun importArtifact(folderUri: String, effort: GrowthEffort, source: Uri): GrowthArtifact {
        require(folderUri.isNotBlank()) { "请先在设置中选择同步文件夹" }
        val sourceFile = DocumentFile.fromSingleUri(context, source) ?: error("无法读取所选文件")
        val name = sourceFile.name?.takeIf(String::isNotBlank) ?: "学习文件"
        val directory = effortDir(folderUri, effort, true) ?: error("无法创建成长记录目录")
        val target = directory.findFile(name) ?: directory.createFile(sourceFile.type ?: "application/octet-stream", name)
            ?: error("无法创建学习文件")
        context.contentResolver.openInputStream(source)?.use { input ->
            context.contentResolver.openOutputStream(target.uri, "wt")?.use { input.copyTo(it) }
                ?: error("无法写入学习文件")
        } ?: error("无法读取所选文件")
        return readArtifact(target, includePreview = false)
    }

    fun delete(effort: GrowthEffort, folderUri: String) {
        require(validId(effort.id)) { "无效记录编号" }
        if (folderUri.isNotBlank()) effortDir(folderUri, effort, false)?.let {
            check(it.delete()) { "无法删除同步目录中的记录" }
        }
        saveEfforts(localEfforts().filterNot { it.id == effort.id })
        deleteFromLegacyFile(effort.id)
    }

    fun recordPath(effort: GrowthEffort) = "成长/成长记录/${folderName(effort)}/"

    private fun localEfforts(): List<GrowthEffort> {
        val current = readArray(effortsFile, GrowthEffort::fromJson)
        // v2 文件存在就说明迁移已经完成；即使内容是 []，也可能是用户主动删空。
        // 不能再从旧文件迁移，否则删除的记录会在下一次刷新时重新出现。
        if (effortsFile.exists() || !legacySessionsFile.exists()) return current
        val migrated = readArray(legacySessionsFile, GrowthEffort::fromJson)
        saveEfforts(migrated)
        return migrated
    }

    private fun export(folderUri: String, effort: GrowthEffort) {
        val directory = effortDir(folderUri, effort, true) ?: error("无法创建成长记录目录")
        val file = directory.findFile("记录.json") ?: directory.createFile("application/json", "记录.json")
            ?: error("无法创建记录文件")
        context.contentResolver.openOutputStream(file.uri, "wt")?.use {
            it.write(effort.toJson().toString(2).toByteArray(Charsets.UTF_8))
        } ?: error("无法写入记录文件")
    }

    private fun readRemoteRecord(directory: DocumentFile): GrowthEffort? = runCatching {
        val file = directory.findFile("记录.json") ?: return@runCatching null
        FileSnapshotCache.readFile(context, file)?.let { GrowthEffort.fromJson(JSONObject(it)) }
    }.getOrNull()

    private fun readRemoteActivities(folderUri: String): List<GrowthActivity> {
        if (folderUri.isBlank()) return emptyList()
        return runCatching {
            val root = DocumentFile.fromTreeUri(context, Uri.parse(folderUri)) ?: return@runCatching emptyList()
            val file = StorageLayout.readFile(root, "项目.json") ?: return@runCatching emptyList()
            val source = JSONObject(FileSnapshotCache.readFile(context, file).orEmpty()).optJSONArray("projects")
                ?: return@runCatching emptyList()
            (0 until source.length()).mapNotNull { index ->
                source.optJSONObject(index)?.let { GrowthActivity.fromJson(it) }
            }
        }.getOrDefault(emptyList())
    }

    private fun readArtifacts(directory: DocumentFile, includePreview: Boolean) = directory.listFiles()
        .filter { it.isFile && it.name != "记录.json" && !it.name.orEmpty().startsWith(".") }
        .mapNotNull { runCatching { readArtifact(it, includePreview) }.getOrNull() }.sortedBy { it.name.lowercase() }

    private fun readArtifact(file: DocumentFile, includePreview: Boolean): GrowthArtifact {
        val name = file.name ?: "学习文件"
        val mime = file.type ?: "application/octet-stream"
        val textLike = mime.startsWith("text/") || name.endsWith(".md", true) || name.endsWith(".json", true)
        val preview = if (includePreview && textLike) context.contentResolver.openInputStream(file.uri)?.bufferedReader()?.use {
            val buffer = CharArray(20_000)
            val count = it.read(buffer)
            if (count > 0) String(buffer, 0, count) else ""
        }.orEmpty() else ""
        return GrowthArtifact(name, file.uri.toString(), mime, preview)
    }

    private fun growthRoot(folderUri: String, create: Boolean): DocumentFile? {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(folderUri)) ?: error("请先选择同步文件夹")
        return if (create) {
            StorageLayout.writableDirectory(context, root, StorageLayout.GROWTH, "成长记录")
        } else {
            StorageLayout.readDirectory(root, StorageLayout.GROWTH, "成长记录")
        }
    }

    private fun effortDir(folderUri: String, effort: GrowthEffort, create: Boolean): DocumentFile? {
        require(validId(effort.id)) { "无效记录编号" }
        val root = growthRoot(folderUri, create) ?: return null
        findEffortDirectory(root, effort.id)?.let { existing ->
            if (create && existing.name != folderName(effort)) existing.renameTo(folderName(effort))
            return existing
        }
        return if (create) root.createDirectory(folderName(effort)) else null
    }

    private fun findEffortDirectory(parent: DocumentFile, id: String): DocumentFile? {
        parent.findFile(id)?.takeIf { it.isDirectory }?.let { return it }
        return parent.listFiles().firstOrNull { directory ->
            directory.isDirectory && readRemoteRecord(directory)?.id == id
        }
    }

    private fun folderName(effort: GrowthEffort): String {
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd_HHmmss", java.util.Locale.CHINA)
            .format(java.util.Date(effort.startedAt))
        val title = effort.activityName.replace(Regex("[\\\\/:*?\"<>|]"), "")
            .trim().ifBlank { "成长记录" }.take(30)
        return "${stamp}_${title}"
    }

    private fun saveActivities(items: List<GrowthActivity>) =
        write(activitiesFile, JSONArray(items.map(GrowthActivity::toJson)).toString(2))
    private fun saveEfforts(items: List<GrowthEffort>) =
        write(effortsFile, JSONArray(items.sortedBy { it.startedAt }.map(GrowthEffort::toJson)).toString(2))
    private fun deleteFromLegacyFile(id: String) {
        if (!legacySessionsFile.exists()) return
        val source = runCatching { JSONArray(legacySessionsFile.readText()) }.getOrNull() ?: return
        val kept = JSONArray()
        for (index in 0 until source.length()) {
            val item = source.optJSONObject(index) ?: continue
            if (item.optString("id") != id) kept.put(item)
        }
        write(legacySessionsFile, kept.toString(2))
    }
    private fun <T> readArray(file: File, parse: (JSONObject) -> T): List<T> = if (!file.exists()) emptyList() else runCatching {
        val array = JSONArray(file.readText()); (0 until array.length()).map { parse(array.getJSONObject(it)) }
    }.getOrDefault(emptyList())
    private fun write(file: File, value: String) { val atomic = AtomicFile(file); val output = atomic.startWrite(); try {
        output.write(value.toByteArray(Charsets.UTF_8)); atomic.finishWrite(output)
    } catch (e: Exception) { atomic.failWrite(output); throw e } }
    private fun validName(name: String): String { val clean = name.trim(); require(clean.length in 1..20 && clean.none { it == '\n' || it == '\r' }) { "项目名称为 1–20 个字" }; return clean }
    private fun thisWeek(time: Long): Boolean { val start = Calendar.getInstance().apply { firstDayOfWeek = Calendar.MONDAY
        set(Calendar.DAY_OF_WEEK, Calendar.MONDAY); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        return time >= start.timeInMillis }
    companion object { private fun validId(id: String) = id.matches(Regex("[0-9a-fA-F-]{36}")) }
}
