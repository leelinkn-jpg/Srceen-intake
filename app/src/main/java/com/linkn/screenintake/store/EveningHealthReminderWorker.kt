package com.linkn.screenintake.store

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.documentfile.provider.DocumentFile
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.linkn.screenintake.R
import com.linkn.screenintake.ScreenIntakeApp
import com.linkn.screenintake.ui.MainActivity
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * 每天 23:00 检查当天是否已记：屏幕使用（数字健康.csv）、体重（体重.csv）、三餐照片。
 * 缺哪项就提醒哪项；三项都有则安静。准点用 AlarmManager，WorkManager 作补偿。
 */
class EveningHealthReminderWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        runCatching { checkAndNotify(applicationContext) }
        schedule(applicationContext) // 排明天
        return Result.success()
    }

    companion object {
        private const val CHANNEL_ID = "evening_health_reminders"
        private const val PREFS = "evening_health_reminder"
        private const val WORK = "evening-health-reminder"
        private const val NOTIFICATION_ID = 19092300
        private const val HOUR = 23
        private const val MINUTE = 0

        fun schedule(context: Context) {
            val zone = ZoneId.systemDefault()
            var fire = LocalDate.now(zone).atTime(HOUR, MINUTE).atZone(zone)
            if (!fire.toLocalDateTime().isAfter(LocalDateTime.now(zone))) {
                fire = fire.plusDays(1)
            }
            val fireMillis = fire.toInstant().toEpochMilli()
            val alarm = context.getSystemService(AlarmManager::class.java)
            val pi = alarmIntent(context)
            alarm.cancel(pi)
            if (Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms()) {
                runCatching {
                    alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireMillis, pi)
                }.onFailure {
                    SystemStatus.failure(context, "提醒", "晚间健康提醒准点不可用，已用后台补偿")
                }
            } else {
                SystemStatus.failure(context, "提醒", "准点提醒未授权，晚间健康提醒可能延迟")
            }
            val delay = (fireMillis - System.currentTimeMillis()).coerceAtLeast(0L)
            val request = OneTimeWorkRequestBuilder<EveningHealthReminderWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, request)
        }

        fun deliverFromAlarm(context: Context) {
            runCatching { checkAndNotify(context) }
            schedule(context)
        }

        private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            NOTIFICATION_ID,
            Intent(context, TodoAlarmReceiver::class.java).setAction(ACTION_EVENING_HEALTH),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        const val ACTION_EVENING_HEALTH = "com.linkn.screenintake.EVENING_HEALTH_REMINDER"

        private fun checkAndNotify(context: Context) {
            val zone = ZoneId.systemDefault()
            val now = LocalDateTime.now(zone)
            // 只在 23:00 之后到次日凌晨前触发提醒，避免补偿任务白天误报
            if (now.hour < HOUR) return
            val today = LocalDate.now(zone).toString()
            val prefs = context.getSharedPreferences(PREFS, 0)
            if (prefs.getString("last_notified_date", null) == today) return

            val folder = ScreenIntakeApp.instance.settingsStore.folderUri
            if (folder.isBlank()) return

            val missing = missingItems(context, folder, today)
            if (missing.isEmpty()) {
                prefs.edit().putString("last_notified_date", today).apply()
                SystemStatus.success(context, "提醒")
                return
            }

            val manager = NotificationManagerCompat.from(context)
            if (!manager.areNotificationsEnabled()) {
                SystemStatus.failure(context, "提醒", "通知权限未开启，晚间健康提醒未发出")
                return
            }
            ensureChannel(context)
            val text = "今天还没记：" + missing.joinToString("、")
            val open = PendingIntent.getActivity(
                context,
                NOTIFICATION_ID,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            manager.notify(
                NOTIFICATION_ID,
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle("健康记录提醒")
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text + "\n屏幕使用可音量组合键读屏；体重同样；三餐长按音量上拍照。"))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    .build()
            )
            prefs.edit().putString("last_notified_date", today).apply()
            SystemStatus.success(context, "提醒")
        }

        private fun missingItems(context: Context, folderUri: String, today: String): List<String> {
            val root = HubRoot.resolve(context, folderUri) ?: return emptyList()
            val missing = mutableListOf<String>()
            if (!hasCsvDate(context, root, "数字健康.csv", today)) missing += "屏幕使用"
            if (!hasCsvDate(context, root, "体重.csv", today)) missing += "体重"
            if (!hasMealPhoto(context, root, today)) missing += "三餐"
            return missing
        }

        private fun hasCsvDate(context: Context, root: DocumentFile, name: String, today: String): Boolean {
            val file = StorageLayout.readFile(root, name) ?: return false
            val text = FileSnapshotCache.readFile(context, file).orEmpty()
            return text.lineSequence().drop(1).any { line ->
                val raw = line.trim().removePrefix("\uFEFF")
                if (raw.isEmpty()) return@any false
                // 日期可能带引号、后面跟时间
                val cell = raw.split(',').firstOrNull()?.trim()?.trim('"') ?: return@any false
                cell.startsWith(today)
            }
        }

        private fun hasMealPhoto(context: Context, root: DocumentFile, today: String): Boolean {
            val dir = StorageLayout.readDirectory(root, StorageLayout.HEALTH, "日常照片")
                ?.findFile("三餐")
                ?: root.findFile("日常照片")?.findFile("三餐")
                ?: return false
            if (!dir.isDirectory) return false
            return dir.listFiles().any { file ->
                val n = file.name ?: return@any false
                file.isFile && n.startsWith(today)
            }
        }

        private fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "晚间健康记录提醒", NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
        }
    }
}
