package com.linkn.screenintake.growth

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.UUID

data class GrowthSession(val id: String, val kind: String, val label: String, val startedAt: Long,
    val durationMs: Long = 0, val note: String = "") {
    fun toJson() = JSONObject().put("id", id).put("kind", kind).put("label", label)
        .put("startedAt", startedAt).put("durationMs", durationMs).put("note", note)
    companion object { fun fromJson(j: JSONObject) = GrowthSession(j.getString("id"), j.getString("kind"),
        j.getString("label"), j.getLong("startedAt"), j.optLong("durationMs"), j.optString("note")) }
}

class GrowthRepository(context: Context) {
    private val sessionsFile = File(context.filesDir, "growth-sessions.json")
    private val activeFile = File(context.filesDir, "growth-active.json")
    fun sessions(): List<GrowthSession> = read(sessionsFile).sortedByDescending { it.startedAt }
    fun active(): GrowthSession? = if (activeFile.exists()) runCatching { GrowthSession.fromJson(JSONObject(activeFile.readText())) }.getOrNull() else null
    fun start(kind: String, label: String): GrowthSession {
        val session = GrowthSession(UUID.randomUUID().toString(), kind, label, System.currentTimeMillis())
        write(activeFile, session.toJson().toString()); return session
    }
    fun stop(): GrowthSession? {
        val active = active() ?: return null
        val finished = active.copy(durationMs = (System.currentTimeMillis() - active.startedAt).coerceAtLeast(0))
        write(sessionsFile, JSONArray(sessions().map { it.toJson() }.plus(finished.toJson())).toString())
        activeFile.delete(); return finished
    }
    fun addReflection(note: String) {
        val clean = note.trim(); require(clean.isNotBlank()) { "写一句即可" }
        val item = GrowthSession(UUID.randomUUID().toString(), "深度思考", "想一想", System.currentTimeMillis(), 0, clean)
        write(sessionsFile, JSONArray(sessions().map { it.toJson() }.plus(item.toJson())).toString())
    }
    fun delete(id: String) {
        require(id.matches(Regex("[0-9a-fA-F-]{36}"))) { "无效记录编号" }
        write(sessionsFile, JSONArray(sessions().filterNot { it.id == id }.map { it.toJson() }).toString())
    }
    fun weeklyMinutes(kind: String): Int = sessions().filter { it.kind == kind && thisWeek(it.startedAt) }
        .sumOf { (it.durationMs / 60_000).toInt() }
    private fun read(file: File): List<GrowthSession> = if (!file.exists()) emptyList() else runCatching {
        val a = JSONArray(file.readText()); (0 until a.length()).map { GrowthSession.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())
    private fun write(file: File, value: String) { val a = AtomicFile(file); val out = a.startWrite(); try { out.write(value.toByteArray()); a.finishWrite(out) } catch (e: Exception) { a.failWrite(out); throw e } }
    private fun thisWeek(time: Long): Boolean { val now = Calendar.getInstance(); val start = Calendar.getInstance().apply { firstDayOfWeek = Calendar.MONDAY; timeInMillis = now.timeInMillis; set(Calendar.DAY_OF_WEEK, Calendar.MONDAY); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }; return time >= start.timeInMillis }
}
