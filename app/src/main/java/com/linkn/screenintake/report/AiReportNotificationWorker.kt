package com.linkn.screenintake.report

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.linkn.screenintake.R
import com.linkn.screenintake.ScreenIntakeApp
import com.linkn.screenintake.ui.MainActivity
import java.util.concurrent.TimeUnit

class AiReportNotificationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = runCatching {
        val folder = ScreenIntakeApp.instance.settingsStore.folderUri
        if (folder.isBlank()) return@runCatching Result.success()
        val repo = AiReportRepository(applicationContext)
        val all = repo.list(folder)
        com.linkn.screenintake.store.SystemStatus.success(applicationContext, "报告检查")
        val today = java.time.LocalDate.now().toString()
        val todayReport = all.firstOrNull { it.period == ReportPeriod.DAILY && java.time.Instant.ofEpochMilli(it.generatedAt)
            .atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString() == today }
        val jobStatus = if (todayReport != null) "今日报告已到达" else runCatching {
            val root = androidx.documentfile.provider.DocumentFile.fromTreeUri(applicationContext, android.net.Uri.parse(folder))
            val job = root?.findFile("系统")?.findFile("系统状态")?.findFile("分析任务")?.findFile("$today.json")
            val json = job?.let { applicationContext.contentResolver.openInputStream(it.uri)?.bufferedReader()?.use { input -> org.json.JSONObject(input.readText()) } }
            when (json?.optString("status")) {
                "queued" -> "今日报告等待模型执行"
                "running" -> "今日报告生成中"
                "failed", "blocked" -> "今日报告未完成，等待处理或重试"
                "complete" -> "Mini 已完成任务，等待报告文件到达"
                else -> "今日尚无报告或任务状态"
            }
        }.getOrDefault("报告任务状态暂时不可读")
        applicationContext.getSharedPreferences("system_status", 0).edit().putString("report_job", jobStatus).apply()
        all.filter(repo::isRead).forEach(repo::markNotified)
        val fresh = all.filter { !repo.wasNotified(it) && !repo.isRead(it) }
        if (fresh.isEmpty()) return@runCatching Result.success()
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Mini AI 报告", NotificationManager.IMPORTANCE_DEFAULT))
        val canNotify = ContextCompat.checkSelfPermission(
            applicationContext, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (canNotify) {
            fresh.forEach { report ->
                val open = PendingIntent.getActivity(applicationContext, report.id.hashCode(),
                    Intent(applicationContext, MainActivity::class.java).setAction(OPEN_REPORTS)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val prefix = if (report.important) "需要关注 · " else ""
                manager.notify(report.id.hashCode(), NotificationCompat.Builder(applicationContext, CHANNEL)
                    .setSmallIcon(R.drawable.ic_launcher_foreground).setContentTitle(prefix + report.title)
                    .setContentText(report.summary.ifBlank { "Mini 已生成新的${report.period.label}" })
                    .setStyle(NotificationCompat.BigTextStyle().bigText(report.summary)).setContentIntent(open)
                    .setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build())
            }
            // 只有真正发出通知后才记为已通知。此前无权限时也记为已通知，会导致用户后来
            // 开启权限后永远收不到这份报告的提示。
            fresh.forEach(repo::markNotified)
            com.linkn.screenintake.store.SystemStatus.success(applicationContext, "报告通知")
        }
        ReportChangeSignal.bump()
        Result.success()
    }.getOrElse {
        if (it is kotlinx.coroutines.CancellationException) throw it
        com.linkn.screenintake.store.SystemStatus.failure(applicationContext, "报告检查", "报告检查失败，稍后重试")
        Result.retry()
    }

    companion object {
        const val OPEN_REPORTS = "com.linkn.screenintake.report.OPEN"
        private const val CHANNEL = "mini_ai_reports"
        private const val WORK = "scan-mini-ai-reports"
        private const val IMMEDIATE_WORK = "scan-mini-ai-reports-now"
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<AiReportNotificationWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
            scanNow(context)
        }

        fun scanNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                IMMEDIATE_WORK,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<AiReportNotificationWorker>().build()
            )
        }
    }
}
