package com.linkn.screenintake.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.font.FontWeight
import com.linkn.screenintake.ScreenIntakeApp
import com.linkn.screenintake.report.AiReport
import com.linkn.screenintake.report.AiReportRepository
import com.linkn.screenintake.report.ReportChangeSignal
import com.linkn.screenintake.report.ReportDomain
import com.linkn.screenintake.report.ReportPeriod
import com.linkn.screenintake.store.UiDataCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LatestAiReportCard(domain: ReportDomain, resumeTick: Int, onOpenCenter: (() -> Unit)? = null) {
    val context = LocalContext.current
    val repo = remember { AiReportRepository(context.applicationContext) }
    val folder = ScreenIntakeApp.instance.settingsStore.folderUri
    var report by remember(domain) { mutableStateOf(UiDataCache.reports.firstOrNull { r -> r.sections.any { it.domain == domain } }) }
    var detail by remember { mutableStateOf<AiReport?>(null) }
    val change = ReportChangeSignal.tick.value
    LaunchedEffect(resumeTick, change, domain) {
        report = withContext(Dispatchers.IO) { repo.latest(folder)?.takeIf { report -> report.sections.any { it.domain == domain } } }
    }
    report?.let { item ->
        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clickable { detail = item },
            colors = CardDefaults.cardColors(containerColor = if (item.important) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Mini · ${item.period.label}", style = MaterialTheme.typography.labelMedium)
                    if (!repo.isRead(item)) Text("新", color = MaterialTheme.colorScheme.primary)
                }
                Text(item.title, style = MaterialTheme.typography.titleSmall)
                if (item.summary.isNotBlank()) Text(item.summary, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                if (onOpenCenter != null) TextButton(onClick = onOpenCenter) { Text("全部报告") }
            }
        }
    }
    detail?.let { ReportDetailDialog(it, repo, folder) { detail = null } }
}

@Composable
fun AiReportCenterScreen(resumeTick: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { AiReportRepository(context.applicationContext) }
    val folder = ScreenIntakeApp.instance.settingsStore.folderUri
    var reports by remember { mutableStateOf(UiDataCache.reports) }
    var period by remember { mutableStateOf<ReportPeriod?>(null) }
    var detail by remember { mutableStateOf<AiReport?>(null) }
    val change = ReportChangeSignal.tick.value
    fun reload() { scope.launch { reports = withContext(Dispatchers.IO) { repo.list(folder) }.also { UiDataCache.reports = it } } }
    LaunchedEffect(resumeTick, change) { reload() }
    val shown = reports.filter { period == null || it.period == period }
    val tabLabels = listOf("全部") + ReportPeriod.entries.map { it.label }
    val selectedIndex = period?.let { ReportPeriod.entries.indexOf(it) + 1 } ?: 0
    fun selectPeriod(index: Int) {
        period = if (index == 0) null else ReportPeriod.entries[index - 1]
    }

    Column(Modifier.fillMaxSize()) {
        UnifiedSectionTabs(tabLabels, selectedIndex, ::selectPeriod)
        Box(
            Modifier
                .fillMaxSize()
                .sectionSwipes(selectedIndex, tabLabels.size, ::selectPeriod)
        ) {
            if (shown.isEmpty()) {
                EmptyHint("还没有 Mini 回传的${period?.label ?: "报告"}")
            } else {
                LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(shown, key = { "${it.id}:${it.revision}" }) { item ->
                        Card(Modifier.fillMaxWidth().clickable { detail = item; repo.markRead(item); ReportChangeSignal.bump() }) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("综合 · ${item.period.label}", style = MaterialTheme.typography.labelMedium)
                                    if (!repo.isRead(item)) Text("未读", color = MaterialTheme.colorScheme.primary)
                                }
                                Text(item.title, style = MaterialTheme.typography.titleSmall)
                                if (item.summary.isNotBlank()) Text(item.summary, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                                Text(formatTime(item.generatedAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
    detail?.let { ReportDetailDialog(it, repo, folder) { detail = null; reload() } }
}

@Composable
fun DomainAdviceScreen(domain: ReportDomain, resumeTick: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { AiReportRepository(context.applicationContext) }
    val folder = ScreenIntakeApp.instance.settingsStore.folderUri
    var reports by remember { mutableStateOf(UiDataCache.reports) }
    var error by remember { mutableStateOf("") }
    val change = ReportChangeSignal.tick.value
    fun reload() { scope.launch { reports = withContext(Dispatchers.IO) { repo.list(folder) }.also { UiDataCache.reports = it } } }
    LaunchedEffect(resumeTick, change) { reload() }
    val entries = reports.mapNotNull { report -> report.sections.firstOrNull { it.domain == domain }?.let { report to it } }
    val feedback = rememberReportFeedback(repo, folder, reports, change)
    if (entries.isEmpty()) {
        Text("暂无${domain.label}建议", modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        entries.forEachIndexed { index, (report, section) ->
            item(key = "section:${report.id}:${report.revision}") {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor =
                    if (section.severity == "urgent" || section.severity == "attention") MaterialTheme.colorScheme.tertiaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(if (index == 0) "最新${domain.label}建议 · ${report.period.label}" else report.title,
                            style = MaterialTheme.typography.labelMedium)
                        Text(section.title, style = MaterialTheme.typography.titleMedium)
                        if (section.summary.isNotBlank()) Text(section.summary)
                        if (section.content.isNotBlank()) Text(section.content, style = MaterialTheme.typography.bodyMedium)
                        Text("数据截至：${report.dataCutoff.ifBlank { report.periodEnd }}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            val actions = report.actions.filter { action -> actionDomain(action.domain) == domain }
            items(actions, key = { "${report.id}:${it.id}" }) { action ->
                val status = feedback[report.id to action.id]
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(action.title, style = MaterialTheme.typography.titleSmall)
                    if (action.reason.isNotBlank()) Text(action.reason, style = MaterialTheme.typography.bodySmall)
                    if (status != null) Text(if (status == "accepted") "已写入待办" else "已忽略",
                        color = MaterialTheme.colorScheme.primary)
                    else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { scope.launch { runCatching { withContext(Dispatchers.IO) { repo.respond(folder, report, action, true) } }
                            .onSuccess { reload() }.onFailure { error = it.message ?: "写入失败" } } }) { Text("确认写入待办") }
                        TextButton(onClick = { scope.launch { runCatching { withContext(Dispatchers.IO) { repo.respond(folder, report, action, false) } }
                            .onSuccess { reload() }.onFailure { error = it.message ?: "保存失败" } } }) { Text("忽略") }
                    }
                } }
            }
        }
        if (error.isNotBlank()) item { Text(error, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
fun DomainAdvicePanel(domain: ReportDomain, resumeTick: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { AiReportRepository(context.applicationContext) }
    val folder = ScreenIntakeApp.instance.settingsStore.folderUri
    var report by remember { mutableStateOf<AiReport?>(null) }
    var refresh by remember { mutableStateOf(0) }
    val change = ReportChangeSignal.tick.value
    LaunchedEffect(resumeTick, change, refresh) { report = withContext(Dispatchers.IO) { repo.latest(folder) } }
    var error by remember { mutableStateOf("") }
    val feedback = rememberReportFeedback(repo, folder, listOfNotNull(report), change)
    val current = report
    val section = current?.sections?.firstOrNull { it.domain == domain }
    if (current == null || section == null) {
        Text("暂无${domain.label}建议", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("最新${domain.label}建议 · ${current.period.label}", style = MaterialTheme.typography.labelMedium)
            Text(section.title, style = MaterialTheme.typography.titleMedium)
            if (section.summary.isNotBlank()) Text(section.summary)
            if (section.content.isNotBlank()) Text(section.content)
        } }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        current.actions.filter { actionDomain(it.domain) == domain }.forEach { action ->
            val status = feedback[current.id to action.id]
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(action.title, style = MaterialTheme.typography.titleSmall)
                if (action.reason.isNotBlank()) Text(action.reason, style = MaterialTheme.typography.bodySmall)
                if (status != null) Text(if (status == "accepted") "已写入待办" else "已忽略")
                else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { scope.launch { runCatching { withContext(Dispatchers.IO) { repo.respond(folder, current, action, true) } }
                        .onSuccess { refresh++; error = "" }.onFailure { error = it.message ?: "保存失败，请重试" } } }) { Text("确认写入待办") }
                    TextButton(onClick = { scope.launch { runCatching { withContext(Dispatchers.IO) { repo.respond(folder, current, action, false) } }
                        .onSuccess { refresh++; error = "" }.onFailure { error = it.message ?: "保存失败，请重试" } } }) { Text("忽略") }
                }
            } }
        }
    }
}

@Composable
private fun rememberReportFeedback(repo: AiReportRepository, folder: String, reports: List<AiReport>, change: Long): Map<Pair<String, String>, String?> {
    var feedback by remember(folder) { mutableStateOf<Map<Pair<String, String>, String?>>(emptyMap()) }
    LaunchedEffect(folder, reports, change) {
        feedback = withContext(Dispatchers.IO) {
            reports.flatMap { report -> report.actions.map { action ->
                (report.id to action.id) to repo.feedback(folder, report.id, action.id)
            } }.toMap()
        }
    }
    return feedback
}

@Composable
private fun ReportDetailDialog(report: AiReport, repo: AiReportRepository, folder: String, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf("") }
    var actionFeedback by remember(report.id, folder) { mutableStateOf<Map<String, String?>>(emptyMap()) }
    LaunchedEffect(report.id, folder) {
        actionFeedback = withContext(Dispatchers.IO) { report.actions.associate { action -> action.id to repo.feedback(folder, report.id, action.id) } }
    }
    repo.markRead(report)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            Modifier.fillMaxWidth().fillMaxHeight(0.94f).padding(horizontal = 12.dp, vertical = 8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("综合 · ${report.period.label}", style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary)
                            Text(report.title, style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.SemiBold)
                            Text("数据截至 ${report.dataCutoff.ifBlank { report.periodEnd }}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = onDismiss) { Text("关闭") }
                    }
                }
                if (report.summary.isNotBlank()) item {
                    Card(colors = CardDefaults.cardColors(containerColor =
                        if (report.important) MaterialTheme.colorScheme.tertiaryContainer
                        else MaterialTheme.colorScheme.primaryContainer)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("今日摘要", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(report.summary, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (report.sections.isNotEmpty()) item {
                    Text("分领域建议", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                items(report.sections, key = { it.domain.wire }) { section ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor =
                            if (section.severity == "urgent" || section.severity == "attention")
                                MaterialTheme.colorScheme.tertiaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(section.domain.label, style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary)
                            Text(section.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            if (section.summary.isNotBlank()) Text(section.summary, style = MaterialTheme.typography.bodyMedium)
                            if (section.content.isNotBlank()) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                Text(section.content, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                if (report.actions.isNotEmpty()) item {
                    Text("建议动作", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                items(report.actions, key = { it.id }) { action ->
                    val status = actionFeedback[action.id]
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(action.title, style = MaterialTheme.typography.titleSmall)
                        if (action.reason.isNotBlank()) Text(action.reason, style = MaterialTheme.typography.bodySmall)
                        if (status != null) Text(if (status == "accepted") "已写入待办" else "已忽略", color = MaterialTheme.colorScheme.primary)
                        else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { scope.launch { runCatching { withContext(Dispatchers.IO) { repo.respond(folder, report, action, true) } }
                                .onSuccess { actionFeedback = actionFeedback + (action.id to "accepted") }.onFailure { error = it.message ?: "写入失败" } } }) { Text("写入待办") }
                            TextButton(onClick = { scope.launch { runCatching { withContext(Dispatchers.IO) { repo.respond(folder, report, action, false) } }
                                .onSuccess { actionFeedback = actionFeedback + (action.id to "rejected") }.onFailure { error = it.message ?: "保存失败" } } }) { Text("忽略") }
                        }
                    } }
                }
                if (error.isNotBlank()) item { Text(error, color = MaterialTheme.colorScheme.error) }
                item { TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("关闭报告") } }
            }
        }
    }
}

private fun formatTime(value: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(value))
private fun actionDomain(value: String) = when (value.lowercase()) {
    "finance", "财务" -> ReportDomain.FINANCE; "health", "健康" -> ReportDomain.HEALTH
    "growth", "成长", "习惯" -> ReportDomain.GROWTH; else -> ReportDomain.WORK
}
