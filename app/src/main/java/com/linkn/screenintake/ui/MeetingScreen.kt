package com.linkn.screenintake.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.AudioAttributes
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import com.linkn.screenintake.ScreenIntakeApp
import com.linkn.screenintake.meeting.MeetingRecord
import com.linkn.screenintake.meeting.MeetingRecorderService
import com.linkn.screenintake.meeting.MeetingRepository
import com.linkn.screenintake.store.UiDataCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun MeetingScreen(resumeTick: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { MeetingRepository(context.applicationContext) }
    val live by MeetingRecorderService.state.collectAsState()
    val folder = ScreenIntakeApp.instance.settingsStore.folderUri
    var records by remember { mutableStateOf(UiDataCache.meetings) }
    var error by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf<String?>(null) }
    var playingId by remember { mutableStateOf<String?>(null) }
    var playbackLoading by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<MeetingRecord?>(null) }
    var renameTarget by remember { mutableStateOf<MeetingRecord?>(null) }
    var meetingName by remember { mutableStateOf("") }
    val player = remember { MediaPlayer() }

    DisposableEffect(player) { onDispose { runCatching { player.release() } } }
    LaunchedEffect(resumeTick, live.busy, folder) {
        if (!live.busy) {
            runCatching {
                records = withContext(Dispatchers.IO) { repo.recover(); repo.list(folder) }
                    .also { UiDataCache.meetings = it }
            }.onFailure {
                // LaunchedEffect 因重组取消是正常行为，不能把它显示成用户可见错误。
                if (it !is CancellationException) error = it.message ?: "读取会议记录失败"
            }
        }
    }

    fun start() {
        runCatching {
            player.reset(); playingId = null
            MeetingRecorderService.start(context)
        }.onFailure { error = it.message ?: "录音启动失败" }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) start() else error = "需要允许麦克风权限才能录音"
    }
    fun requestStart() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start()
        else permission.launch(Manifest.permission.RECORD_AUDIO)
    }
    fun work(action: String, record: MeetingRecord) {
        runCatching { MeetingRecorderService.work(context, action, record.id) }
            .onFailure { error = it.message ?: "任务启动失败" }
    }
    fun delete(record: MeetingRecord) {
        if (playingId == record.id) { player.reset(); playingId = null }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { repo.delete(record); repo.list(folder) }
            }.onSuccess { records = it; UiDataCache.meetings = it }
                .onFailure { error = it.message ?: "删除会议记录失败" }
        }
    }
    fun rename(record: MeetingRecord) {
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { repo.rename(record, meetingName) } }
                .onSuccess { updated ->
                    records = records.map { if (it.id == updated.id) updated else it }
                    UiDataCache.meetings = records
                }
                .onFailure { error = it.message ?: "修改会议名称失败" }
        }
    }
    fun playback(record: MeetingRecord) {
        if (playingId == record.id) {
            player.reset(); playingId = null; return
        }
        playbackLoading = true
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) { repo.ensureAudio(record) }
                player.reset()
                player.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                player.setDataSource(file.absolutePath)
                player.setOnPreparedListener { playbackLoading = false; playingId = record.id; it.start() }
                player.setOnCompletionListener { playingId = null }
                player.setOnErrorListener { _, _, _ -> playingId = null; playbackLoading = false; error = "录音回放失败"; true }
                player.prepareAsync()
            } catch (e: Exception) { playbackLoading = false; error = e.message ?: "无法回放录音" }
        }
    }

    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = RecordListContentPadding,
        verticalArrangement = Arrangement.spacedBy(RecordListSpacing)) {
        item {
            if (live.busy) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(if (live.recording) MeetingRecord.clock(live.elapsedMs) else live.message,
                            style = MaterialTheme.typography.titleSmall)
                        TextButton(onClick = {
                            context.startService(Intent(context, MeetingRecorderService::class.java)
                                .setAction(if (live.recording) MeetingRecorderService.STOP else MeetingRecorderService.CANCEL))
                        }) { Text(if (live.recording) "结束录音" else "取消保存") }
                    }
                    LinearProgressIndicator(
                        progress = { if (live.recording) live.level else 0.45f },
                        modifier = Modifier.fillMaxWidth())
                }
            } else {
                Button(onClick = { requestStart() }, enabled = !playbackLoading) { Text("开始录音") }
                if (live.message.isNotBlank()) Text(live.message, style = MaterialTheme.typography.bodySmall)
            }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        }
        if (records.isEmpty()) item { Text("还没有会议记录", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(records, key = { it.id }) { record ->
            RecordRowCard(
                icon = CategoryIcons.iconFor("学习"), title = record.title,
                subtitle = "${record.label} · ${MeetingRecord.clock(record.durationMs)}",
                detail = record.error.takeIf { it.isNotBlank() },
                onClick = {
                    renameTarget = record
                    meetingName = record.name.ifBlank { MeetingRecord.defaultName(record.createdAt, record.testOnly) }
                },
                footer = {
                    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        TextButton(
                            onClick = { playback(record) }, enabled = !live.busy && !playbackLoading,
                            modifier = Modifier.heightIn(min = 32.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            Text(if (playingId == record.id) "停止" else "播放")
                        }
                        TextButton(
                            enabled = record.status == "ready" || record.parts.isNotEmpty(), onClick = {
                            scope.launch {
                                runCatching { withContext(Dispatchers.IO) { repo.transcriptText(record) } }
                                    .onSuccess { detail = it }.onFailure { error = it.message.orEmpty() }
                            }
                        }, modifier = Modifier.heightIn(min = 32.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text("文字") }
                        if (!record.synced) TextButton(enabled = !live.busy, onClick = {
                            work(MeetingRecorderService.EXPORT, record)
                        }, modifier = Modifier.heightIn(min = 32.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text("重新同步") }
                        TextButton(enabled = !live.busy, onClick = { deleteTarget = record }, modifier = Modifier.heightIn(min = 32.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text("删除") }
                    }
                    }
                }
            )
        }
    }
    deleteTarget?.let { record ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除这条会议记录？") },
            text = { Text("将同时删除录音、转录文本和同步文件夹中的副本，无法恢复。") },
            confirmButton = { TextButton(onClick = { deleteTarget = null; delete(record) }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } }
        )
    }
    renameTarget?.let { record ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("会议名称") },
            text = { OutlinedTextField(value = meetingName, onValueChange = { meetingName = it }, singleLine = true, label = { Text("名称") }) },
            confirmButton = { TextButton(onClick = { renameTarget = null; rename(record) }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("取消") } }
        )
    }
    detail?.let { text ->
        Dialog(onDismissRequest = { detail = null }) {
            Card(Modifier.fillMaxWidth().heightIn(max = 620.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("会议转录", style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { detail = null }) { Text("关闭") }
                    }
                    SelectionContainer(Modifier.verticalScroll(rememberScrollState())) {
                        Text(text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
