package com.linkn.screenintake.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.linkn.screenintake.ScreenIntakeApp
import com.linkn.screenintake.growth.GrowthActivity
import com.linkn.screenintake.growth.GrowthArtifact
import com.linkn.screenintake.growth.GrowthEffort
import com.linkn.screenintake.growth.GrowthEffortRepository
import com.linkn.screenintake.growth.GrowthNote
import com.linkn.screenintake.growth.GrowthNoteListing
import com.linkn.screenintake.growth.GrowthNoteRepository
import com.linkn.screenintake.meeting.MeetingRecord
import com.linkn.screenintake.store.UiDataCache
import com.linkn.screenintake.report.ReportDomain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun GrowthEffortScreen(resumeTick: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { GrowthEffortRepository(context.applicationContext) }
    val folderUri = ScreenIntakeApp.instance.settingsStore.folderUri
    var activities by remember { mutableStateOf(UiDataCache.growthActivities) }
    var efforts by remember { mutableStateOf(UiDataCache.growthEfforts) }
    var active by remember { mutableStateOf<GrowthEffort?>(null) }
    var clock by remember { mutableStateOf(System.currentTimeMillis()) }
    var error by remember { mutableStateOf("") }
    var addProject by remember { mutableStateOf(false) }
    var editProject by remember { mutableStateOf<GrowthActivity?>(null) }
    var finishConfirm by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<GrowthEffort?>(null) }
    var preview by remember { mutableStateOf<GrowthArtifact?>(null) }
    var importTarget by remember { mutableStateOf<GrowthEffort?>(null) }
    var deleteTarget by remember { mutableStateOf<GrowthEffort?>(null) }
    var selectedSection by rememberSaveable { mutableStateOf(0) }
    val pagerState = rememberSyncedSectionPagerState(selectedSection, 4) { selectedSection = it }
    var notesPath by rememberSaveable(folderUri) { mutableStateOf("") }
    BackHandler(enabled = selectedSection == 2 && notesPath.isNotBlank()) {
        notesPath = notesPath.substringBeforeLast('/', "")
    }

    fun reload() {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    Triple(repo.activities(folderUri), repo.efforts(folderUri), repo.active())
                }
            }.onSuccess { (a, e, running) ->
                activities = a; efforts = e; active = running; clock = System.currentTimeMillis()
                UiDataCache.growthActivities = a; UiDataCache.growthEfforts = e
            }
                .onFailure { if (it !is CancellationException) error = it.message ?: "读取成长记录失败" }
        }
    }

    LaunchedEffect(resumeTick) { reload() }
    LaunchedEffect(active?.startedAt) {
        while (active != null) { clock = System.currentTimeMillis(); delay(1_000) }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = importTarget
        importTarget = null
        if (uri != null && target != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) { repo.importArtifact(folderUri, target, uri) } }
                .onSuccess { reload() }.onFailure { error = it.message ?: "导入学习文件失败" }
        }
    }

    fun start(activity: GrowthActivity) = scope.launch {
        runCatching { withContext(Dispatchers.IO) { repo.start(activity) } }
            .onSuccess { active = it; clock = System.currentTimeMillis() }
            .onFailure { error = it.message ?: "无法开始记录" }
    }

    Column(Modifier.fillMaxSize()) {
        UnifiedSectionTabs(
            labels = listOf("我的项目", "最近记录", "笔记", "建议"),
            selectedIndex = selectedSection,
            onSelected = { selectedSection = it },
            pagerState = pagerState
        )
        if (error.isNotBlank()) Text(error, modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
        SectionPager(pagerState, Modifier.weight(1f).fillMaxWidth()) { page ->
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {

        if (page == 0) {
            active?.let { running -> item {
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("正在进行 · ${running.activityName}", style = MaterialTheme.typography.titleMedium)
                    Text(MeetingRecord.clock((clock - running.startedAt).coerceAtLeast(0)))
                    Button(onClick = { finishConfirm = true }) { Text("结束并保存") }
                } }
            } }

            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { addProject = true }) { Text("＋ 新增项目") }
                }
            }

            items(activities.filterNot { it.archived }, key = { it.id }) { activity ->
                val minutes = efforts.filter { it.activityId == activity.id && isThisWeek(it.startedAt) }
                    .sumOf { (it.durationMs / 60_000).toInt() }
                Card(Modifier.fillMaxWidth().clickable { editProject = activity }) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(activity.name, style = MaterialTheme.typography.titleSmall)
                            Button(onClick = { start(activity) }, enabled = active == null) { Text("开始") }
                        }
                        if (activity.weeklyTargetMinutes > 0) {
                            Text("本周 $minutes / ${activity.weeklyTargetMinutes} 分钟",
                                style = MaterialTheme.typography.bodySmall)
                            LinearProgressIndicator(progress = { (minutes.toFloat() / activity.weeklyTargetMinutes).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth())
                        } else Text("本周 $minutes 分钟", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else if (page == 1) {
            if (efforts.isEmpty()) item {
                Text("还没有记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(efforts.take(30), key = { it.id }) { effort ->
                RecordRowCard(Icons.Filled.School, effort.activityName,
                    "${date(effort.startedAt)} · ${MeetingRecord.clock(effort.durationMs)}",
                    detail = if (effort.artifacts.isEmpty()) "等待学习文件" else "${effort.artifacts.size} 个学习文件",
                    onClick = { detail = effort })
            }
        } else if (page == 2) {
            item { GrowthNotesView(folderUri, notesPath, { notesPath = it }, resumeTick) }
        } else {
            item { DomainAdvicePanel(ReportDomain.GROWTH, resumeTick) }
        }
            }
        }
    }

    if (finishConfirm) AlertDialog(onDismissRequest = { finishConfirm = false },
        title = { Text("结束这次努力？") },
        text = { Text("将保存时长并创建独立记录目录。笔记或 AI 评价以后传入也能自动关联。") },
        confirmButton = { TextButton(onClick = {
            finishConfirm = false
            scope.launch { runCatching { withContext(Dispatchers.IO) { repo.finish(folderUri) } }
                .onSuccess { reload() }.onFailure { error = it.message ?: "保存失败" } }
        }) { Text("结束并保存") } }, dismissButton = { TextButton(onClick = { finishConfirm = false }) { Text("继续") } })

    if (addProject) ProjectDialog(null, onDismiss = { addProject = false }) { name, target ->
        scope.launch { runCatching { withContext(Dispatchers.IO) { repo.addActivity(name, target).also { repo.syncActivities(folderUri) } } }
            .onSuccess { addProject = false; reload() }.onFailure { error = it.message ?: "新增失败" } }
    }
    editProject?.let { activity -> ProjectDialog(activity, onDismiss = { editProject = null },
        onArchive = { scope.launch { runCatching { withContext(Dispatchers.IO) { repo.archiveActivity(activity); repo.syncActivities(folderUri) } }
            .onSuccess { editProject = null; reload() }.onFailure { error = it.message.orEmpty() } } }) { name, target ->
        scope.launch { runCatching { withContext(Dispatchers.IO) { repo.updateActivity(activity, name, target).also { repo.syncActivities(folderUri) } } }
            .onSuccess { editProject = null; reload() }.onFailure { error = it.message ?: "保存失败" } }
    } }

    detail?.let { effort -> EffortDetail(effort, repo.recordPath(effort), onDismiss = { detail = null },
        onCopyPath = {
            val text = "记录ID：${effort.id}\n项目：${effort.activityName}\n时间：${date(effort.startedAt)}\n时长：${MeetingRecord.clock(effort.durationMs)}\n同步目录：${repo.recordPath(effort)}"
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("本次努力", text)); Toast.makeText(context, "记录信息已复制", Toast.LENGTH_SHORT).show()
        }, onImport = { importTarget = effort; picker.launch(arrayOf("text/*", "application/pdf", "application/json")) },
        onOpen = { artifact -> if (artifact.preview.isNotBlank()) preview = artifact else openArtifact(context, artifact) },
        onDelete = { deleteTarget = effort })
    }

    preview?.let { artifact -> Dialog(onDismissRequest = { preview = null }) {
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(artifact.name, style = MaterialTheme.typography.titleMedium)
            Text(artifact.preview)
            TextButton(onClick = { preview = null }) { Text("关闭") }
        } }
    } }

    deleteTarget?.let { effort -> AlertDialog(onDismissRequest = { deleteTarget = null }, title = { Text("删除这次努力？") },
        text = { Text("时长记录和同步目录内的学习文件都会被删除，无法恢复。") },
        confirmButton = { TextButton(onClick = { deleteTarget = null; detail = null; scope.launch {
            runCatching { withContext(Dispatchers.IO) { repo.delete(effort, folderUri) } }.onSuccess { reload() }
                .onFailure { error = it.message ?: "删除失败" }
        } }) { Text("删除") } }, dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } })
    }
}

@Composable
private fun GrowthNotesView(folderUri: String, relativePath: String, onPathChange: (String) -> Unit, resumeTick: Int) {
    val context = LocalContext.current
    val repo = remember { GrowthNoteRepository(context.applicationContext) }
    var listing by remember { mutableStateOf(GrowthNoteListing(emptyList(), emptyList())) }
    var selected by remember { mutableStateOf<GrowthNote?>(null) }
    var readError by remember { mutableStateOf("") }
    var retry by remember { mutableStateOf(0) }
    LaunchedEffect(folderUri, relativePath, resumeTick, retry, com.linkn.screenintake.store.DataChangeSignal.tick.value) {
        runCatching { withContext(Dispatchers.IO) { repo.list(folderUri, relativePath) } }
            .onSuccess { listing = it; readError = "" }
            .onFailure { readError = it.message ?: "读取失败，保留上次内容" }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    if (readError.isNotBlank()) {
        Text(readError, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = { retry++ }) { Text("重试") }
    }
    if (relativePath.isNotBlank()) {
        TextButton(onClick = { onPathChange(relativePath.substringBeforeLast('/', "")) }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
            Text("返回上级", modifier = Modifier.padding(start = 8.dp))
        }
    }
    if (listing.collections.isEmpty() && listing.notes.isEmpty()) {
        Text(if (relativePath.isBlank()) "还没有同步到成长笔记。" else "这个合集里还没有 Markdown 笔记。",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (relativePath.isBlank()) {
                Text("我的笔记", style = MaterialTheme.typography.titleMedium)
            } else {
                Text(relativePath.substringAfterLast('/'), style = MaterialTheme.typography.titleMedium)
            }
            listing.collections.forEach { collection ->
                Card(Modifier.fillMaxWidth().clickable { onPathChange(collection.path) }) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp)) {
                    Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(collection.name, style = MaterialTheme.typography.titleSmall)
                        Text("笔记合集", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary)
                    }
                    }
                }
            }
            listing.notes.forEach { note ->
                Card(Modifier.fillMaxWidth().clickable { selected = note }) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(note.name, style = MaterialTheme.typography.titleSmall)
                        if (note.preview.isNotBlank()) Text(note.preview, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    }
                }
            }
        }
    }
    }
    selected?.let { note ->
        var text by remember(note.uri) { mutableStateOf("") }
        LaunchedEffect(note.uri) { text = runCatching { withContext(Dispatchers.IO) { repo.content(folderUri, note) } }
            .getOrElse { it.message ?: "读取失败，请关闭后重试" }.ifEmpty { "这篇笔记暂时没有正文" } }
        Dialog(onDismissRequest = { selected = null }) {
            Card(Modifier.fillMaxWidth().fillMaxSize(0.86f)) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(note.name, style = MaterialTheme.typography.titleMedium); TextButton(onClick = { selected = null }) { Text("关闭") } } }
                    item { Text(text.ifBlank { "正在读取…" }, style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
    }
}

@Composable private fun ProjectDialog(activity: GrowthActivity?, onDismiss: () -> Unit, onArchive: (() -> Unit)? = null,
    onSave: (String, Int) -> Unit) {
    var name by remember(activity?.id) { mutableStateOf(activity?.name.orEmpty()) }
    var target by remember(activity?.id) { mutableStateOf(activity?.weeklyTargetMinutes?.takeIf { it > 0 }?.toString().orEmpty()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (activity == null) "新增成长项目" else "编辑成长项目") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("项目名称") }, singleLine = true)
            OutlinedTextField(target, { target = it.filter(Char::isDigit) }, label = { Text("每周目标分钟（可不填）") }, singleLine = true)
            if (onArchive != null) TextButton(onClick = onArchive) { Text("归档项目") }
        } }, confirmButton = { TextButton(onClick = { onSave(name, target.toIntOrNull() ?: 0) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable private fun EffortDetail(effort: GrowthEffort, path: String, onDismiss: () -> Unit, onCopyPath: () -> Unit,
    onImport: () -> Unit, onOpen: (GrowthArtifact) -> Unit, onDelete: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) { Card(Modifier.fillMaxWidth()) {
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text(effort.activityName, style = MaterialTheme.typography.titleLarge)
                Text("${date(effort.startedAt)} · ${MeetingRecord.clock(effort.durationMs)}")
                Text("文件关联目录：$path", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onCopyPath) { Text("复制记录信息") }
                    Button(onClick = onImport) { Text("导入文件") }
                }
            }
            item { Text("学习文件", style = MaterialTheme.typography.titleMedium) }
            if (effort.artifacts.isEmpty()) item { Text("还没有文件。同步到上述目录后，重新进入成长页即可显示。") }
            items(effort.artifacts, key = { it.uri }) { artifact ->
                Card(Modifier.fillMaxWidth().clickable { onOpen(artifact) }) { Column(Modifier.padding(10.dp)) {
                    Text(artifact.name)
                    if (artifact.preview.isNotBlank()) Text(artifact.preview.lineSequence().firstOrNull().orEmpty().take(100),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } }
            }
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onDelete) { Text("删除记录") }; TextButton(onClick = onDismiss) { Text("关闭") }
            } }
        }
    } }
}

private fun openArtifact(context: Context, artifact: GrowthArtifact) = runCatching {
    context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(artifact.uri), artifact.mimeType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
}.onFailure { Toast.makeText(context, "手机上没有可打开此文件的应用", Toast.LENGTH_SHORT).show() }

private fun date(time: Long) = SimpleDateFormat("MM月dd日 HH:mm", Locale.CHINA).format(Date(time))
private fun isThisWeek(time: Long): Boolean { val calendar = java.util.Calendar.getInstance().apply {
    firstDayOfWeek = java.util.Calendar.MONDAY; set(java.util.Calendar.DAY_OF_WEEK, java.util.Calendar.MONDAY)
    set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }
    return time >= calendar.timeInMillis }
