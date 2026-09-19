package com.linkn.screenintake.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.linkn.screenintake.ScreenIntakeApp
import com.linkn.screenintake.work.WorkCustomer
import com.linkn.screenintake.work.WorkDataRepository
import com.linkn.screenintake.work.WorkEvent
import com.linkn.screenintake.work.WorkProject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun WorkOverviewCard(resumeTick: Int) {
    val context = LocalContext.current; val folder = ScreenIntakeApp.instance.settingsStore.folderUri
    val repo = remember { WorkDataRepository(context.applicationContext) }; var data by remember { mutableStateOf<com.linkn.screenintake.work.WorkOverview?>(null) }
    LaunchedEffect(resumeTick, folder) { data = withContext(Dispatchers.IO) { repo.overview(folder) } }
    val overview = data ?: return
    if (overview.metrics.isEmpty()) return
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("本月经营总览", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            overview.metrics.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth()) {
                    pair.forEach { (name, value) ->
                        Column(Modifier.weight(1f)) {
                            Text(name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text(value, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
            }
            if (overview.cutoff.isNotBlank()) Text("数据截止：${overview.cutoff}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
internal fun WorkListScreen(kind: String, resumeTick: Int) {
    val context = LocalContext.current; val folder = ScreenIntakeApp.instance.settingsStore.folderUri
    val repo = remember { WorkDataRepository(context.applicationContext) }
    var projects by remember { mutableStateOf(emptyList<WorkProject>()) }; var customers by remember { mutableStateOf(emptyList<WorkCustomer>()) }; var events by remember { mutableStateOf(emptyList<WorkEvent>()) }
    LaunchedEffect(kind, resumeTick, folder) { withContext(Dispatchers.IO) { when (kind) {
        "reserve" -> projects = repo.projects(folder); "capacity" -> customers = repo.capacityCustomers(folder); "overdue" -> customers = repo.overdueCustomers(folder)
        "visits" -> events = repo.visits(folder); else -> events = repo.placements(folder)
    } } }
    val empty = projects.isEmpty() && customers.isEmpty() && events.isEmpty()
    if (empty) return EmptyHint("等待 Mini 同步${if (kind == "reserve") "储备项目" else "工作数据"}")
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(projects) { x -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) { Text(x.name, style = MaterialTheme.typography.titleMedium); Text("${x.manager} · ${x.amount} · 预计 ${x.expected}"); if (x.progress.isNotBlank()) Text(x.progress, color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
        items(customers) { x -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) { Text(x.name, style = MaterialTheme.typography.titleMedium); Text("${x.owner} · ${x.type}"); if (x.feedback.isNotBlank()) Text(x.feedback); if (x.lastVisit.isNotBlank()) Text("最近记录：${x.lastVisit}", style = MaterialTheme.typography.bodySmall) } } }
        items(events) { x -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) { Text(x.target, style = MaterialTheme.typography.titleMedium); Text("${x.date} · 人员：${x.owner.ifBlank { "未标注" }} · ${x.type}"); if (x.detail.isNotBlank()) Text(x.detail); if (x.match.isNotBlank()) Text("匹配：${x.match}", style = MaterialTheme.typography.bodySmall) } } }
    }
}
