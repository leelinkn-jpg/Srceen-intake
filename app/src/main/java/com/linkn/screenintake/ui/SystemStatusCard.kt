package com.linkn.screenintake.ui

import android.app.NotificationManager
import android.app.AlarmManager
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.linkn.screenintake.BuildConfig
import com.linkn.screenintake.ScreenIntakeApp
import com.linkn.screenintake.store.LocalDataIndexWorker
import com.linkn.screenintake.store.SyncNotificationWorker
import com.linkn.screenintake.report.AiReportNotificationWorker
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

@Composable
internal fun SystemStatusCard() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("system_status", 0) }
    var tick by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    var confirmRecovery by remember { mutableStateOf(false) }
    var recoveryMessage by remember { mutableStateOf("") }
    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> tick++ }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val times = remember(tick) { listOf("读取", "同步检查", "报告检查", "报告通知", "提醒").map { stage ->
        val time = prefs.getLong("$stage.time", 0)
        val date = if (time == 0L) "尚无成功记录" else SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(time))
        Triple(stage, date, prefs.getString("$stage.error", null))
    } }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("系统状态", style = MaterialTheme.typography.titleMedium)
            Text("版本 ${BuildConfig.VERSION_NAME} · ${BuildConfig.BUILD_TYPE}\n构建 ${BuildConfig.BUILD_STAMP}", style = MaterialTheme.typography.bodySmall)
            Text("目录：${android.net.Uri.decode(ScreenIntakeApp.instance.settingsStore.folderUri.substringAfterLast('/'))}", style = MaterialTheme.typography.bodySmall)
            Text("报告任务：" + prefs.getString("report_job", "等待检查").orEmpty())
            times.forEach { (stage, date, error) ->
                Text("$stage：${error ?: date}", color = if (error == null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
            }
            val allowed = context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
            Text("通知权限：${if (allowed) "已开启" else "未开启，无法发送提醒"}")
            val exact = Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
            Text("准点提醒：${if (exact) "可用" else "未授权，可能延迟"}")
            if (!exact && Build.VERSION.SDK_INT >= 31) TextButton(onClick = {
                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    android.net.Uri.parse("package:${context.packageName}")))
            }) { Text("开启准点提醒") }
            val pending = File(context.filesDir, "pending-writes").listFiles()?.count { it.extension == "json" } ?: 0
            if (pending > 0) Text("有 $pending 项保存操作需要核对，原内容和拟保存内容已保留", color = MaterialTheme.colorScheme.error)
            if (pending > 0) TextButton(onClick = { confirmRecovery = true }) { Text("核对未完成保存") }
            if (recoveryMessage.isNotBlank()) Text(recoveryMessage)
            TextButton(onClick = {
                LocalDataIndexWorker.refresh(context)
                SyncNotificationWorker.scanNow(context)
                AiReportNotificationWorker.scanNow(context)
                tick++
            }) { Text("重新检查") }
        }
    }
    if (confirmRecovery) AlertDialog(onDismissRequest = { confirmRecovery = false },
        title = { Text("核对未完成保存") },
        text = { Text("已经完整保存的操作会确认完成；只保存了一部分的操作会尝试恢复到修改前。文件若有其他修改，将保留等待人工核对，不会覆盖。") },
        confirmButton = { TextButton(onClick = {
            confirmRecovery = false
            scope.launch {
                recoveryMessage = withContext(Dispatchers.IO) { com.linkn.screenintake.store.RecordStore(context).reconcilePendingWrites() }
                tick++
            }
        }) { Text("核对并恢复") } }, dismissButton = { TextButton(onClick = { confirmRecovery = false }) { Text("取消") } })
}
