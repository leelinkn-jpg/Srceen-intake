package com.linkn.screenintake.store

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
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
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 监看“由 Mini / Syncthing 后台带回手机”的结果文件。这里故意不扫描照片、录音、账本、
 * 待办和灵感：它们大多是手机刚刚主动写出的数据，操作现场已经有浮层反馈，再发通知栏会
 * 重复打扰。报告由 AiReportNotificationWorker 单独负责；这里只管 WHOOP/健康数据、领域
 * 分析结果和会议转录。
 */
class SyncNotificationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = runCatching {
        val folder = ScreenIntakeApp.instance.settingsStore.folderUri
        if (folder.isBlank()) return@runCatching Result.success()
        // Syncthing 写入后旧 DocumentFile 句柄可能已对应不到当前文件，先清掉句柄缓存。
        StorageLayout.invalidateExternalHandles(folder)
        val root = DocumentFile.fromTreeUri(applicationContext, Uri.parse(folder))
            ?: return@runCatching Result.success()
        val current = linkedMapOf<String, String>()
        listOf("财务", "健康", "工作", "成长").forEach { domain ->
            root.findFile(domain)?.listFiles()?.filter {
                it.isFile && (it.name?.endsWith(".csv", true) == true || it.name?.endsWith(".md", true) == true)
            }?.forEach { file -> current["原始记录/$domain/${file.name}"] = signature(file) }
        }
        collectHealth(root.findFile("健康"), current)
        collectHealthPending(root.findFile("健康")?.findFile("酒精待确认"), "酒精确认", current)
        collectHealthPending(root.findFile("健康")?.findFile("成长使用待确认"), "成长使用确认", current)
        collectMeetingTranscripts(root.findFile("工作")?.findFile("会议记录"), current)
        collectPendingPlans(root.findFile("工作")?.findFile("待确认计划"), current)
        collectPendingChanges(root.findFile("工作")?.findFile("待确认变更"), current)
        collectGrowthNotes(root.findFile("成长")?.findFile("笔记"), current)
        listOf("财务", "健康", "工作", "成长").forEach { domain ->
            collectState(root.findFile(domain)?.findFile("状态"), "$domain/状态", current)
        }

        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previousText = prefs.getString(KEY_SNAPSHOT, null)
        val currentText = JSONObject(current as Map<*, *>).toString()
        // 首次安装只建立基线，不能把手机里原有的几十个文件都当成“刚同步”。
        if (previousText == null) {
            prefs.edit().putString(KEY_SNAPSHOT, currentText).apply()
            return@runCatching Result.success()
        }
        val previous = JSONObject(previousText)
        SystemStatus.success(applicationContext, "同步检查")
        val changed = current.keys.filter { key -> previous.optString(key) != current[key] }
        val removed = previous.keys().asSequence().any { it !in current }
        if (changed.isEmpty() && !removed) return@runCatching Result.success()

        // 同步完成后在后台更新结构化本地镜像；通知与页面都不需要再重新扫整棵目录。
        LocalDataIndexWorker.refresh(applicationContext)
        // 外部同步进来的文件不会经过 RecordStore，必须主动通知当前页面重读。
        // 否则用户停留在“成长-笔记”页时，会出现文件已到手机但列表仍为空的假象。
        DataChangeSignal.bump()

        val messages = buildList {
            if (changed.any { it.startsWith("健康数据/") }) add("WHOOP／健康数据已更新")
            if (changed.any { it.startsWith("酒精确认/") }) add("发现饮酒记录，待你确认")
            if (changed.any { it.startsWith("成长使用确认/") }) add("发现成长类 App 使用，待确认关联")
            if (changed.any { it.startsWith("成长笔记/") }) add("成长笔记已同步")
            if (changed.any { it.startsWith("会议转录/") }) add("新的会议转录已完成")
            if (changed.any { it.startsWith("周会计划/") }) add("本周周会时间待确认")
            if (changed.any { it.startsWith("工作变更/") }) add("有新的工作变更待确认")
            listOf("财务", "健康", "工作", "成长").forEach { domain ->
                if (changed.any { it.startsWith("$domain/状态/") }) add("$domain 分析结果已更新")
            }
        }.distinct()
        // 没有通知权限时不推进快照；用户以后开启权限仍能收到这一次真正的新数据。
        if (messages.isNotEmpty() && !notify(messages)) return@runCatching Result.success()
        prefs.edit().putString(KEY_SNAPSHOT, currentText).apply()
        Result.success()
    }.getOrElse {
        if (it is kotlinx.coroutines.CancellationException) throw it
        SystemStatus.failure(applicationContext, "同步检查", "同步检查失败，请检查目录权限")
        Result.retry()
    }

    private fun collectHealth(dir: DocumentFile?, out: MutableMap<String, String>) {
        dir?.listFiles()?.filter { it.isFile && it.name?.endsWith(".csv", true) == true }
            ?.forEach { out["健康数据/${it.name}"] = signature(it) }
    }

    private fun collectHealthPending(dir: DocumentFile?, prefix: String, out: MutableMap<String, String>) {
        dir?.listFiles()?.filter { it.isFile && it.name?.endsWith(".json", true) == true }?.forEach { file ->
            val pending = runCatching {
                applicationContext.contentResolver.openInputStream(file.uri)?.bufferedReader()?.use {
                    JSONObject(it.readText()).optString("status", "pending") == "pending"
                } ?: false
            }.getOrDefault(false)
            if (pending) out["$prefix/${file.name}"] = signature(file)
        }
    }

    private fun collectMeetingTranscripts(dir: DocumentFile?, out: MutableMap<String, String>) {
        dir?.listFiles()?.filter(DocumentFile::isDirectory)?.forEach { meeting ->
            meeting.findFile("转录.md")?.takeIf { it.isFile }?.let {
                out["会议转录/${meeting.name}/转录.md"] = signature(it)
            }
        }
    }

    private fun collectPendingPlans(dir: DocumentFile?, out: MutableMap<String, String>) {
        dir?.listFiles()?.filter { it.isFile && it.name?.endsWith(".json", true) == true }?.forEach { file ->
            val pending = runCatching {
                applicationContext.contentResolver.openInputStream(file.uri)?.bufferedReader()?.use {
                    JSONObject(it.readText()).optString("status") == "pending"
                } ?: false
            }.getOrDefault(false)
            if (pending) out["周会计划/${file.name}"] = signature(file)
        }
    }

    private fun collectPendingChanges(dir: DocumentFile?, out: MutableMap<String, String>) {
        dir?.listFiles()?.filter { it.isFile && it.name?.endsWith(".json", true) == true }?.forEach { file ->
            val pending = runCatching {
                applicationContext.contentResolver.openInputStream(file.uri)?.bufferedReader()?.use {
                    JSONObject(it.readText()).optString("status") == "pending"
                } ?: false
            }.getOrDefault(false)
            if (pending) out["工作变更/${file.name}"] = signature(file)
        }
    }

    /** 笔记允许按主题继续分文件夹；只记录 Markdown 的签名，不读取正文。 */
    private fun collectGrowthNotes(dir: DocumentFile?, out: MutableMap<String, String>, prefix: String = "") {
        dir?.listFiles()?.forEach { file ->
            val name = file.name.orEmpty()
            when {
                file.isDirectory -> collectGrowthNotes(file, out, "$prefix$name/")
                file.isFile && name.endsWith(".md", true) -> out["成长笔记/$prefix$name"] = signature(file)
            }
        }
    }

    private fun collectState(dir: DocumentFile?, prefix: String, out: MutableMap<String, String>) {
        dir?.listFiles()?.forEach { file ->
            if (file.isDirectory) collectState(file, "$prefix/${file.name}", out)
            else if (file.name?.endsWith(".json", true) == true && file.name?.startsWith(".") != true) {
                val completed = runCatching {
                    applicationContext.contentResolver.openInputStream(file.uri)?.bufferedReader()?.use {
                        JSONObject(it.readText()).optString("status") == "complete"
                    } ?: false
                }.getOrDefault(false)
                if (completed) out["$prefix/${file.name}"] = signature(file)
            }
        }
    }

    private fun signature(file: DocumentFile) = "${file.lastModified()}:${file.length()}"

    private fun notify(lines: List<String>): Boolean {
        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) return false
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "数据同步", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(applicationContext, 7201,
            Intent(applicationContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = lines.joinToString("；")
        manager.notify(NOTIFICATION_ID, NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(if (lines.size == 1) lines.first() else "秒记数据已同步")
            .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(false)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build())
        return true
    }

    companion object {
        private const val PREFS = "sync_notifications"
        private const val KEY_SNAPSHOT = "snapshot"
        private const val CHANNEL = "data_sync_updates"
        private const val PERIODIC = "scan-synced-data"
        private const val NOW = "scan-synced-data-now"
        private const val NOTIFICATION_ID = 7200

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC, ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<SyncNotificationWorker>(15, TimeUnit.MINUTES).build())
            scanNow(context)
        }

        fun scanNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                NOW, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<SyncNotificationWorker>().build())
        }
    }
}
