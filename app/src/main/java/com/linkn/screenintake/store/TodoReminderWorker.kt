package com.linkn.screenintake.store

import android.app.NotificationChannel
import android.app.AlarmManager
import android.net.Uri
import android.os.Build
import org.json.JSONObject
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.linkn.screenintake.R
import com.linkn.screenintake.ui.MainActivity
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** 待办提醒完全由 App 自己调度，不再借用系统日历。WorkManager 会跨重启保留任务。 */
class TodoReminderWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.success()
        if (applicationContext.getSharedPreferences("todo_schedule", 0).contains(id)) {
            return if (deliver(applicationContext, id)) Result.success() else Result.retry()
        }
        // Legacy jobs are replaced by reconciliation at application startup.
        return Result.success()
    }

    companion object {
        private const val CHANNEL_ID = "todo_reminders"
        private const val KEY_ID = "todo_id"
        private const val KEY_TITLE = "todo_title"

        private fun alarmIntent(context: Context, id: String): PendingIntent = PendingIntent.getBroadcast(
            context, 0, Intent(context, TodoAlarmReceiver::class.java)
                .setData(Uri.Builder().scheme("miaojitodo").authority("reminder").appendPath(id).build()),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        @Synchronized
        fun deliver(context: Context, id: String): Boolean {
            val prefs = context.getSharedPreferences("todo_schedule", 0)
            val value = prefs.getString(id, null) ?: return true
            val record = JSONObject(value)
            if (record.optBoolean("delivered") || record.getLong("at") > System.currentTimeMillis() + 1000) return true
            val manager = NotificationManagerCompat.from(context)
            if (!manager.areNotificationsEnabled()) {
                SystemStatus.failure(context, "提醒", "通知权限未开启，待办提醒尚未发出")
                return false
            }
            return runCatching {
                ensureChannel(context)
                check(context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE)
                val open = PendingIntent.getActivity(context, id.hashCode(), Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                manager.notify(id.hashCode(), NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.mipmap.ic_launcher).setContentTitle("待办提醒")
                    .setContentText(record.getString("title"))
                    .setStyle(NotificationCompat.BigTextStyle().bigText(record.getString("title")))
                    .setContentIntent(open).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    .setPriority(NotificationCompat.PRIORITY_HIGH).build())
                prefs.edit().putString(id, record.put("delivered", true).toString()).commit()
                SystemStatus.success(context, "提醒")
                true
            }.getOrElse {
                SystemStatus.failure(context, "提醒", "提醒发送失败，后台将重试")
                false
            }
        }

        fun restore(context: Context) {
            context.getSharedPreferences("todo_schedule", 0).all.forEach { (id, raw) ->
                runCatching {
                    val record = JSONObject(raw as String)
                    if (!record.optBoolean("delivered")) schedule(context, id, record.getString("title"), record.getString("dueAt"))
                }
            }
        }

        fun reconcile(context: Context, todos: List<TodoItem>) {
            val active = todos.filter { !it.done && it.reminderId != null && it.dueAt != null }.associateBy { it.reminderId!! }
            val prefs = context.getSharedPreferences("todo_schedule", 0)
            prefs.all.keys.filter { it !in active }.forEach { cancel(context, it) }
            active.forEach { (id, todo) ->
                val old = prefs.getString(id, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
                val due = runCatching { LocalDateTime.parse(todo.dueAt!!.replace(' ', 'T')).toString() }.getOrNull()
                if (old == null || old.optString("dueAt") != due || old.optString("title") != todo.text) {
                    // Do not flood the user with pre-existing overdue reminders on upgrade.
                    val future = runCatching { LocalDateTime.parse(due).isAfter(LocalDateTime.now()) }.getOrDefault(false)
                    if (old != null || future) schedule(context, id, todo.text, due)
                }
            }
        }

        fun schedule(context: Context, id: String, title: String, dueAt: String?) {
            val manager = WorkManager.getInstance(context)
            val due = dueAt?.let { runCatching { LocalDateTime.parse(it.trim().replace(' ', 'T')) }.getOrNull() }
                ?: run { cancel(context, id); return }
            val dueMillis = due.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val prefs = context.getSharedPreferences("todo_schedule", 0)
            val old = prefs.getString(id, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
            val delivered = old?.optLong("at") == dueMillis && old.optBoolean("delivered")
            prefs.edit().putString(id, JSONObject().put("title", title).put("dueAt", due.toString())
                .put("at", dueMillis).put("delivered", delivered).toString()).commit()
            val alarm = context.getSystemService(AlarmManager::class.java)
            alarm.cancel(alarmIntent(context, id))
            if (delivered) { manager.cancelUniqueWork(id); return }
            if (Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms()) {
                runCatching { alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, dueMillis.coerceAtLeast(System.currentTimeMillis()), alarmIntent(context, id)) }
                    .onFailure { SystemStatus.failure(context, "提醒", "准点提醒不可用，已使用后台提醒") }
            } else SystemStatus.failure(context, "提醒", "准点提醒未授权，后台提醒可能延迟")
            val delay = (dueMillis - System.currentTimeMillis()).coerceAtLeast(0)
            val data = Data.Builder().putString(KEY_ID, id).putString(KEY_TITLE, title).build()
            val request = OneTimeWorkRequestBuilder<TodoReminderWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setInputData(data)
                .build()
            manager.enqueueUniqueWork(id, androidx.work.ExistingWorkPolicy.REPLACE, request)
        }

        fun cancel(context: Context, id: String) {
            context.getSharedPreferences("todo_schedule", 0).edit().remove(id).commit()
            context.getSystemService(AlarmManager::class.java).cancel(alarmIntent(context, id))
            WorkManager.getInstance(context).cancelUniqueWork(id)
            NotificationManagerCompat.from(context).cancel(id.hashCode())
        }

        private fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "待办提醒", NotificationManager.IMPORTANCE_HIGH)
                )
            }
        }
    }
}
