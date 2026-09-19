package com.linkn.screenintake.meeting

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Calendar

data class MeetingRecord(
    val id: String,
    val createdAt: Long,
    val folderUri: String,
    val status: String = "recording",
    val durationMs: Long = 0,
    val silencedMs: Long = 0,
    val audibleMs: Long = 0,
    val inputDevice: String = "",
    val parts: List<String> = emptyList(),
    val error: String = "",
    val synced: Boolean = false,
    val testOnly: Boolean = false,
    val name: String = ""
) {
    val title: String get() = SimpleDateFormat("MM月dd日 HH:mm", Locale.CHINA).format(Date(createdAt)) + " · " +
        name.ifBlank { defaultName(createdAt, testOnly) }
    val label: String get() = when (status) {
        "recording" -> "录音中"
        "transcribing" -> "转录中（已完成 ${parts.size} 段）"
        "pending_local" -> "等待 Mac mini 转录"
        "ready" -> "已转录"
        "interrupted" -> "录音被中断，请试听"
        "failed" -> "待重试"
        else -> "待转录"
    }

    fun toJson(): String = JSONObject().apply {
        put("id", id); put("createdAt", createdAt); put("folderUri", folderUri)
        put("status", status); put("durationMs", durationMs); put("silencedMs", silencedMs)
        put("audibleMs", audibleMs); put("inputDevice", inputDevice)
        put("parts", JSONArray(parts)); put("error", error); put("synced", synced); put("testOnly", testOnly); put("name", name)
    }.toString(2)

    fun markdown(): String = buildString {
        append("# $title\n\n")
        append("- 开始时间：${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(createdAt))}\n")
        append("- 时长：${clock(durationMs)}\n- 状态：$label\n- 音频输入：$inputDevice\n")
        append("- 录音文件：[录音.wav](录音.wav)\n")
        if (silencedMs > 0) append("- 注意：约 ${silencedMs / 1000} 秒被系统静音，转录无法恢复未录到的内容。\n")
        append("\n> 麦克风收音，未保证覆盖双方发言。转录按音频片段排列，时间为片段起点，不是逐句时间戳。\n\n")
        if (parts.isEmpty()) append("尚无转录文本。\n")
        parts.forEachIndexed { index, text ->
            append("## ${clock(index * WavAudio.CHUNK_MS)}\n\n$text\n\n")
        }
    }

    companion object {
        /** 周一 08:35–09:05 默认周会；其余工作日 08:10–08:35 默认部门晨会。 */
        fun defaultName(timeMillis: Long, testOnly: Boolean = false): String {
            if (testOnly) return "收音测试"
            val c = Calendar.getInstance().apply { timeInMillis = timeMillis }
            val day = c.get(Calendar.DAY_OF_WEEK)
            val minutes = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
            if (day == Calendar.MONDAY && minutes in (8 * 60 + 35)..(9 * 60 + 5)) return "周会"
            if (day in Calendar.MONDAY..Calendar.FRIDAY && minutes in (8 * 60 + 10)..(8 * 60 + 35)) return "部门晨会"
            return "会议记录"
        }

        fun clock(ms: Long): String = "%02d:%02d:%02d".format(Locale.ROOT, ms / 3600000, ms / 60000 % 60, ms / 1000 % 60)
        fun fromJson(text: String): MeetingRecord {
            val j = JSONObject(text)
            val parts = j.optJSONArray("parts") ?: JSONArray()
            return MeetingRecord(j.getString("id"), j.getLong("createdAt"), j.optString("folderUri"),
                j.optString("status", "recorded"), j.optLong("durationMs"), j.optLong("silencedMs"),
                j.optLong("audibleMs"), j.optString("inputDevice"),
                (0 until parts.length()).map { parts.getString(it) }, j.optString("error"),
                j.optBoolean("synced"), j.optBoolean("testOnly"), j.optString("name"))
        }
    }
}
