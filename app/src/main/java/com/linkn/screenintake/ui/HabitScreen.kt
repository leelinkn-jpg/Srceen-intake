package com.linkn.screenintake.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.linkn.screenintake.ScreenIntakeApp
import com.linkn.screenintake.meeting.MeetingRecord
import com.linkn.screenintake.classify.Categories
import com.linkn.screenintake.classify.ClassifyResult
import com.linkn.screenintake.store.CardAccount
import com.linkn.screenintake.store.DataChangeSignal
import com.linkn.screenintake.store.HealthMetricRow
import com.linkn.screenintake.store.Holding
import com.linkn.screenintake.store.LedgerReader
import com.linkn.screenintake.store.LedgerRow
import com.linkn.screenintake.store.NoteItem
import com.linkn.screenintake.store.PhotoItem
import com.linkn.screenintake.store.PriceCache
import com.linkn.screenintake.store.QuoteFetcher
import com.linkn.screenintake.store.RecordStore
import com.linkn.screenintake.store.TodoItem
import com.linkn.screenintake.store.TransferRow
import com.linkn.screenintake.store.WeightRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HabitScreen(resumeTick: Int) {
    val context = LocalContext.current
    val repo = remember { com.linkn.screenintake.growth.GrowthRepository(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val settings = ScreenIntakeApp.instance.settingsStore
    var sessions by remember { mutableStateOf(emptyList<com.linkn.screenintake.growth.GrowthSession>()) }
    var active by remember { mutableStateOf<com.linkn.screenintake.growth.GrowthSession?>(null) }
    var tick by remember { mutableStateOf(System.currentTimeMillis()) }
    var choose by remember { mutableStateOf(false) }
    var reflectionDialog by remember { mutableStateOf(false) }
    var settingsDialog by remember { mutableStateOf(false) }
    var showRecords by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<com.linkn.screenintake.growth.GrowthSession?>(null) }
    var input by remember { mutableStateOf("") }
    fun reload() { sessions = repo.sessions(); active = repo.active(); tick = System.currentTimeMillis() }
    LaunchedEffect(resumeTick) { withContext(Dispatchers.IO) { repo.sessions() }; reload() }
    LaunchedEffect(active?.startedAt) { while (active != null) { tick = System.currentTimeMillis(); kotlinx.coroutines.delay(1_000) } }
    fun begin(kind: String, label: String) { scope.launch { withContext(Dispatchers.IO) { repo.start(kind, label) }; reload() } }
    fun finish() { scope.launch { withContext(Dispatchers.IO) { repo.stop() }; reload() } }
    val english = remember(sessions) { repo.weeklyMinutes("英语") }
    val investing = remember(sessions) { repo.weeklyMinutes("价值投资") }
    if (!showRecords) LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            if (active == null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { choose = true }) { Text("开始学习") }
                OutlinedButton(onClick = { begin("冥想", "冥想") }) { Text("开始冥想") }
                OutlinedButton(onClick = { showRecords = true }) { Text("记录") }
            } else Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${active!!.label} · ${MeetingRecord.clock(tick - active!!.startedAt)}", style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = ::finish) { Text("结束") }
            } }
        }
        item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("本周学习", style = MaterialTheme.typography.titleSmall); TextButton(onClick = { settingsDialog = true }) { Text("目标") } }
            Text("英语  $english / ${settings.englishWeeklyMinutes} 分钟")
            LinearProgressIndicator(progress = { (english.toFloat() / settings.englishWeeklyMinutes).coerceAtMost(1f) }, modifier = Modifier.fillMaxWidth())
            Text("价值投资  $investing / ${settings.investingWeeklyMinutes} 分钟")
            LinearProgressIndicator(progress = { (investing.toFloat() / settings.investingWeeklyMinutes).coerceAtMost(1f) }, modifier = Modifier.fillMaxWidth())
        } } }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { TextButton(onClick = { reflectionDialog = true }) { Text("想一想") }; Text("一句话记录你的第一反应", style = MaterialTheme.typography.bodySmall) } }
    } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { TextButton(onClick = { showRecords = false }) { Text("返回") }; Text("全部记录", style = MaterialTheme.typography.titleMedium) } }
        if (sessions.isEmpty()) item { Text("开始一次学习或训练后，会自动出现在这里。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(sessions, key = { it.id }) { s -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(horizontal = 12.dp, vertical = 7.dp)) {
            Text("${java.text.SimpleDateFormat("MM月dd日 HH:mm", java.util.Locale.CHINA).format(java.util.Date(s.startedAt))} · ${s.kind}" +
                if (s.label == s.kind) "" else " · ${s.label}")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (s.durationMs > 0) "${MeetingRecord.clock(s.durationMs)}" else s.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { deleteTarget = s }) { Text("删除") }
            }
        } } }
    }
    deleteTarget?.let { session -> AlertDialog(
        onDismissRequest = { deleteTarget = null }, title = { Text("删除这条记录？") },
        text = { Text("删除后无法恢复。") },
        confirmButton = { TextButton(onClick = { deleteTarget = null; scope.launch { withContext(Dispatchers.IO) { repo.delete(session.id) }; reload() } }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } }
    ) }
    if (choose) AlertDialog(onDismissRequest = { choose = false }, title = { Text("开始学习") }, text = { Column { TextButton(onClick = { choose = false; begin("英语", "英语") }) { Text("英语") }; TextButton(onClick = { choose = false; begin("价值投资", "价值投资") }) { Text("价值投资") } } }, confirmButton = {})
    if (reflectionDialog) AlertDialog(onDismissRequest = { reflectionDialog = false }, title = { Text("想一想") }, text = { OutlinedTextField(value = input, onValueChange = { input = it }, label = { Text("今天发生了什么，第一反应是什么？") }) }, confirmButton = { TextButton(onClick = { scope.launch { runCatching { withContext(Dispatchers.IO) { repo.addReflection(input) } }.onSuccess { input = ""; reflectionDialog = false; reload() } } }) { Text("记录") } }, dismissButton = { TextButton(onClick = { reflectionDialog = false }) { Text("取消") } })
    if (settingsDialog) { var englishTarget by remember { mutableStateOf(settings.englishWeeklyMinutes.toString()) }; var investingTarget by remember { mutableStateOf(settings.investingWeeklyMinutes.toString()) }; AlertDialog(onDismissRequest = { settingsDialog = false }, title = { Text("每周学习目标（分钟）") }, text = { Column { OutlinedTextField(englishTarget, { englishTarget = it }, label = { Text("英语") }); OutlinedTextField(investingTarget, { investingTarget = it }, label = { Text("价值投资") }) } }, confirmButton = { TextButton(onClick = { settings.englishWeeklyMinutes = englishTarget.toIntOrNull() ?: settings.englishWeeklyMinutes; settings.investingWeeklyMinutes = investingTarget.toIntOrNull() ?: settings.investingWeeklyMinutes; settingsDialog = false; tick = System.currentTimeMillis() }) { Text("保存") } }) }
}
