package com.linkn.screenintake.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.linkn.screenintake.ScreenIntakeApp
import com.linkn.screenintake.work.WeeklyMeetingPlan
import com.linkn.screenintake.work.WeeklyMeetingPlanRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun WeeklyMeetingPlanCard(resumeTick: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val folder = ScreenIntakeApp.instance.settingsStore.folderUri
    val repo = remember { WeeklyMeetingPlanRepository(context.applicationContext) }
    var plan by remember { mutableStateOf<WeeklyMeetingPlan?>(null) }
    var time by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    fun reload() = scope.launch {
        plan = withContext(Dispatchers.IO) { repo.pending(folder).firstOrNull() }
        time = plan?.suggestedAt.orEmpty()
    }
    LaunchedEffect(resumeTick, folder) { reload() }
    val current = plan ?: return
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("本周周会时间待确认", style = MaterialTheme.typography.titleMedium)
            if (current.reason.isNotBlank()) Text(current.reason, style = MaterialTheme.typography.bodySmall)
            if (current.evidence.isNotBlank()) Text("依据：${current.evidence}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(value = time, onValueChange = { time = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text("周会时间") }, placeholder = { Text("例如 2026-10-08 08:45") }, singleLine = true)
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { scope.launch { runCatching { withContext(Dispatchers.IO) { repo.respond(folder, current, null, true) } }
                    .onSuccess { plan = null }.onFailure { error = it.message ?: "保存失败" } } }) { Text("本周不召开") }
                TextButton(onClick = { scope.launch { runCatching { withContext(Dispatchers.IO) { repo.respond(folder, current, time.trim(), false) } }
                    .onSuccess { plan = null }.onFailure { error = it.message ?: "保存失败" } } }) { Text("确认时间") }
            }
        }
    }
}
