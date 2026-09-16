package com.linkn.screenintake.store

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.linkn.screenintake.classify.ClassifyResult
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.TimeZone

/**
 * 待办事项顺带同步一份到手机系统日历，这样能借用日历自带的提醒通知——待办.md 才是主记录，
 * 这里只是多存一份"能被系统提醒"的副本。没有日历权限、或者手机上找不到能写入的日历时，
 * 安静跳过，不影响 待办.md 正常写入（那才是主流程，日历同步失败绝不能连累它）。
 *
 * 模型能算出具体时间（[ClassifyResult.dueAt]）时就用那个时间点、加一小时的提醒；算不出来
 * 就退回到「今天，全天事件」——具体几点，之后自己在日历 App 里改一下就行，不用我们猜。
 */
object CalendarSync {

    fun trySync(context: Context, result: ClassifyResult) {
        if (!result.isTodo) return
        if (!hasCalendarPermission(context)) return

        try {
            val calendarId = findWritableCalendarId(context) ?: return
            val title = result.summary?.takeIf { it.isNotBlank() } ?: "待办事项"
            val (startMillis, endMillis, allDay) = resolveEventTime(result.dueAt)

            val eventValues = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.TITLE, title)
                val descriptionParts = listOfNotNull(
                    result.who?.let { "对象：$it" },
                    result.source?.let { "来源：$it" },
                    "由「秒记」自动同步，待办.md 里也有一份完整记录"
                )
                put(CalendarContract.Events.DESCRIPTION, descriptionParts.joinToString("\n"))
                put(CalendarContract.Events.DTSTART, startMillis)
                put(CalendarContract.Events.DTEND, endMillis)
                put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }
            val eventUri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, eventValues)
            val eventId = eventUri?.lastPathSegment?.toLongOrNull() ?: return

            val reminderValues = ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, eventId)
                put(CalendarContract.Reminders.MINUTES, 0)
                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            }
            context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, reminderValues)
        } catch (e: Exception) {
            // 日历同步失败静默跳过，待办.md 那边已经写成功了，不用因为这个额外功能弹错误提示
            Log.e("CalendarSync", "同步到日历失败，不影响待办.md 的正常记录", e)
        }
    }

    private fun hasCalendarPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    /** 找一个能写入的日历：优先选 Google 账户的那个，找不到就退回第一个能写的日历。 */
    private fun findWritableCalendarId(context: Context): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.VISIBLE
        )
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI, projection, null, null, null
        )?.use { cursor ->
            var fallbackId: Long? = null
            val idIdx = cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
            val accountTypeIdx = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.ACCOUNT_TYPE)
            val accessIdx = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
            val visibleIdx = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.VISIBLE)
            while (cursor.moveToNext()) {
                if (cursor.getInt(visibleIdx) == 0) continue
                if (cursor.getInt(accessIdx) < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) continue
                val id = cursor.getLong(idIdx)
                if (cursor.getString(accountTypeIdx) == "com.google") return id
                if (fallbackId == null) fallbackId = id
            }
            return fallbackId
        }
        return null
    }

    /** @return Triple(开始时间毫秒, 结束时间毫秒, 是否全天) */
    private fun resolveEventTime(dueAt: String?): Triple<Long, Long, Boolean> {
        if (!dueAt.isNullOrBlank()) {
            try {
                val local = LocalDateTime.parse(dueAt)
                val startMillis = local.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                return Triple(startMillis, startMillis + 60L * 60L * 1000L, false)
            } catch (e: DateTimeParseException) {
                // 模型给的格式不对就忽略，往下走「今天全天」的兜底
            }
        }
        val todayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return Triple(todayStart, todayStart + 24L * 60L * 60L * 1000L, true)
    }
}
