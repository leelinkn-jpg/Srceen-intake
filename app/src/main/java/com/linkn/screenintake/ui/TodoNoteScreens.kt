package com.linkn.screenintake.ui

import android.graphics.Bitmap
import android.widget.Toast
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
import com.linkn.screenintake.store.UiDataCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 待办/灵感的读取展示与编辑弹窗——被工作 Tab（[WorkScreen]，domainFilter = "工作"）和
 * 顶部收件箱（[com.linkn.screenintake.ui.InboxScreen] 的全部待办/全部灵感子视图）共用，
 * 所以单独拆成一个文件而不是归进某一个领域 Tab 里。数据来自 [LedgerReader]，改完/删完
 * 直接整篇重写对应文件、重新读一遍刷新列表。
 */
@Composable
fun TodoListScreen(resumeTick: Int, domainFilter: String? = null) {
    val context = LocalContext.current
    val folderUri = ScreenIntakeApp.instance.settingsStore.folderUri
    var todos by remember { mutableStateOf(UiDataCache.todos) }
    var editingTodo by remember { mutableStateOf<TodoItem?>(null) }
    // 勾选之后不直接删掉——挪到「已办」这个子列表里，既让「待办」列表不被做完的事情占地方，
    // 又不会真的丢掉记录（跟这个 App 其它地方"不静默丢内容"的原则一致），取消勾选还能挪回来。
    var selectedBucket by remember(domainFilter) { mutableStateOf(if (domainFilter == null) "工作" else "待办") }
    val scope = rememberCoroutineScope()
    var operationError by remember { mutableStateOf("") }

    suspend fun reload() {
        todos = withContext(Dispatchers.IO) { LedgerReader.readTodos(context, folderUri) }
            .also { UiDataCache.todos = it }
    }

    val todoChangeTick = DataChangeSignal.forDomain("工作").value
    LaunchedEffect(resumeTick, todoChangeTick) { reload() }

    fun perform(action: suspend () -> Unit) {
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { action() } }
                .onSuccess { reload() }
                .onFailure { error ->
                    operationError = error.message ?: "操作失败，原记录未修改"
                    Toast.makeText(context, operationError, Toast.LENGTH_LONG).show()
                }
        }
    }

    val scoped = if (domainFilter == null) todos else todos.filter { it.domain == domainFilter }
    fun visibleFor(bucket: String) = if (domainFilter == null) when (bucket) {
            "工作" -> scoped.filter { !it.done && it.domain == "工作" }
            "其他" -> scoped.filter { !it.done && it.domain != "工作" }
            else -> scoped.filter { it.done }
        } else if (bucket == "已办") scoped.filter { it.done } else scoped.filter { !it.done }

    val buckets = if (domainFilter == null) listOf("工作", "其他", "已办") else listOf("待办", "已办")
    val selectedBucketIndex = buckets.indexOf(selectedBucket).coerceAtLeast(0)
    val visible = visibleFor(selectedBucket)
    val pagerState = rememberSyncedSectionPagerState(selectedBucketIndex, buckets.size) { selectedBucket = buckets[it] }
    @Composable fun TodoRows(rows: List<TodoItem>, bucket: String) {
        if (rows.isEmpty()) {
            EmptyHint(if (bucket == "已办") "还没有已办的事项" else "这里还没有待办")
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(rows, key = { it.index }) { todo ->
                    Card(Modifier.fillMaxWidth().clickable { editingTodo = todo }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (todo.done) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                                contentDescription = if (todo.done) "点一下标为未完成" else "点一下标为已完成",
                                tint = if (todo.done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.clickable { perform { RecordStore(context).updateTodo(folderUri, todo, !todo.done, todo.text, todo.domain, todo.dueAt) } })
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(todo.text, style = MaterialTheme.typography.bodyMedium, textDecoration = if (todo.done) TextDecoration.LineThrough else null)
                                Text(listOf(todo.domain, todo.dueAt?.replace('T', ' ') ?: "未设置提醒").joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        if (operationError.isNotBlank()) Text(operationError, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.error)
        UnifiedSectionTabs(
            labels = buckets.map { bucket ->
                val count = when (bucket) {
                    "工作" -> scoped.count { !it.done && it.domain == "工作" }
                    "其他" -> scoped.count { !it.done && it.domain != "工作" }
                    "已办" -> scoped.count { it.done }
                    else -> scoped.count { !it.done }
                }
                "$bucket（$count）"
            },
            selectedIndex = selectedBucketIndex,
            onSelected = { selectedBucket = buckets[it] },
            pagerState = if (domainFilter == null) pagerState else null
        )

        if (domainFilter == null) {
            SectionPager(pagerState, Modifier.weight(1f).fillMaxWidth()) { page -> TodoRows(visibleFor(buckets[page]), buckets[page]) }
        } else {
            Box(Modifier.weight(1f).fillMaxWidth()) { TodoRows(visible, selectedBucket) }
        }
    }

    editingTodo?.let { todo ->
        TodoEditDialog(
            todo = todo,
            onDismiss = { editingTodo = null },
            onSave = { newText, newDone, newDomain, dueAt ->
                editingTodo = null
                perform { RecordStore(context).updateTodo(folderUri, todo, newDone, newText, newDomain, dueAt) }
            },
            onDelete = {
                editingTodo = null
                perform { RecordStore(context).deleteTodo(folderUri, todo) }
            }
        )
    }
}

@Composable
private fun TodoEditDialog(
    todo: TodoItem,
    onDismiss: () -> Unit,
    onSave: (text: String, done: Boolean, domain: String, dueAt: String) -> Unit,
    onDelete: () -> Unit
) {
    var text by remember { mutableStateOf(todo.text) }
    var done by remember { mutableStateOf(todo.done) }
    var domain by remember { mutableStateOf(if (todo.domain == "工作") "工作" else "其他") }
    var dueAt by remember { mutableStateOf(todo.dueAt.orEmpty()) }
    val context = LocalContext.current

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("编辑待办", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("内容") },
                    modifier = Modifier.fillMaxWidth()
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = done, onCheckedChange = { done = it })
                    Spacer(Modifier.width(8.dp))
                    Text(if (done) "已完成" else "未完成")
                }

                Text("类型", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("工作", "其他").forEach { d ->
                        FilterChip(selected = domain == d, onClick = { domain = d }, label = { Text(d) })
                    }
                }
                OutlinedTextField(
                    value = dueAt.replace('T', ' '), onValueChange = {}, readOnly = true,
                    label = { Text("提醒时间") }, modifier = Modifier.fillMaxWidth(),
                    trailingIcon = { TextButton(onClick = {
                        val old = runCatching { java.time.LocalDateTime.parse(dueAt) }.getOrNull()
                            ?: java.time.LocalDateTime.now().plusDays(1).withHour(9).withMinute(0)
                        android.app.DatePickerDialog(context, { _, y, m, d ->
                            android.app.TimePickerDialog(context, { _, h, minute ->
                                dueAt = java.time.LocalDateTime.of(y, m + 1, d, h, minute).toString()
                            }, old.hour, old.minute, true).show()
                        }, old.year, old.monthValue - 1, old.dayOfMonth).show()
                    }) { Text(if (dueAt.isBlank()) "选择" else "修改") } }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = onDelete) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onDismiss) { Text("取消") }
                        Button(onClick = { onSave(text.trim(), done, domain, dueAt) }, enabled = text.isNotBlank() && dueAt.isNotBlank()) { Text("保存") }
                    }
                }
            }
        }
    }
}

@Composable
fun NoteListScreen(resumeTick: Int, domainFilter: String? = null) {
    val context = LocalContext.current
    val folderUri = ScreenIntakeApp.instance.settingsStore.folderUri
    var notes by remember { mutableStateOf(UiDataCache.notes) }
    var editingNote by remember { mutableStateOf<NoteItem?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun reload() {
        notes = withContext(Dispatchers.IO) { LedgerReader.readNotes(context, folderUri) }
            .also { UiDataCache.notes = it }
    }

    val noteChangeTick = DataChangeSignal.forDomain("工作").value
    LaunchedEffect(resumeTick, noteChangeTick) { reload() }

    var selectedDomain by remember(domainFilter) { mutableStateOf(if (domainFilter == null) "工作" else domainFilter) }
    fun notesFor(domain: String) = notes.filter { if (domain == "工作") it.domain == "工作" else it.domain != "工作" }
    @Composable fun NoteRows(rows: List<NoteItem>, label: String) {
        if (rows.isEmpty()) EmptyHint("还没有${label}灵感")
        else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(rows, key = { it.index }) { note ->
                Card(Modifier.fillMaxWidth().clickable { editingNote = note }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(note.heading, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (note.content.isNotBlank()) { Spacer(Modifier.height(4.dp)); Text(note.content, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
        }
    }
    val domains = listOf("工作", "其他")
    val pagerState = rememberSyncedSectionPagerState(if (selectedDomain == "工作") 0 else 1, 2) { selectedDomain = domains[it] }

    Column(Modifier.fillMaxSize()) {
        if (domainFilter == null) {
            UnifiedSectionTabs(
                labels = domains.map { value ->
                    val count = if (value == "工作") {
                        notes.count { it.domain == "工作" }
                    } else {
                        notes.count { it.domain != "工作" }
                    }
                    "$value（$count）"
                },
                selectedIndex = if (selectedDomain == "工作") 0 else 1,
                onSelected = { selectedDomain = domains[it] },
                pagerState = pagerState
            )
        } else {
            NoteRows(notes.filter { it.domain == domainFilter }, domainFilter.orEmpty())
            return@Column
        }
        SectionPager(pagerState, Modifier.weight(1f).fillMaxWidth()) { page -> NoteRows(notesFor(domains[page]), domains[page]) }
    }

    editingNote?.let { note ->
        NoteEditDialog(
            note = note,
            onDismiss = { editingNote = null },
            onSave = { newContent, newDomain ->
                editingNote = null
                scope.launch(Dispatchers.IO) {
                    RecordStore(context).updateNote(folderUri, note, newContent, newDomain)
                    reload()
                }
            },
            onDelete = {
                editingNote = null
                scope.launch(Dispatchers.IO) {
                    RecordStore(context).deleteNote(folderUri, note)
                    reload()
                }
            }
        )
    }
}

@Composable
private fun NoteEditDialog(
    note: NoteItem,
    onDismiss: () -> Unit,
    onSave: (content: String, domain: String) -> Unit,
    onDelete: () -> Unit
) {
    var content by remember { mutableStateOf(note.content) }
    var domain by remember { mutableStateOf(if (note.domain == "工作") "工作" else "其他") }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("编辑灵感", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "记录时间：${note.heading}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("内容") },
                    minLines = 3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp)
                )

                Text("归到哪个领域", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("工作", "其他").forEach { d ->
                        FilterChip(selected = domain == d, onClick = { domain = d }, label = { Text(d) })
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = onDelete) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onDismiss) { Text("取消") }
                        Button(onClick = { onSave(content, domain) }) { Text("保存") }
                    }
                }
            }
        }
    }
}
