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
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Hiking
import androidx.compose.material.icons.filled.Pool
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.SportsTennis
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
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.linkn.screenintake.store.DigitalHealthRow
import com.linkn.screenintake.store.ExerciseRow
import com.linkn.screenintake.store.HealthMetricRow
import com.linkn.screenintake.store.Holding
import com.linkn.screenintake.store.LedgerReader
import com.linkn.screenintake.store.LedgerRow
import com.linkn.screenintake.store.NoteItem
import com.linkn.screenintake.store.PhotoItem
import com.linkn.screenintake.store.MealNote
import com.linkn.screenintake.store.PriceCache
import com.linkn.screenintake.store.QuoteFetcher
import com.linkn.screenintake.store.RecordStore
import com.linkn.screenintake.store.TodoItem
import com.linkn.screenintake.store.TransferRow
import com.linkn.screenintake.store.WeightRow
import com.linkn.screenintake.store.UiDataCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.linkn.screenintake.report.ReportDomain
import com.linkn.screenintake.health.AlcoholCandidate
import com.linkn.screenintake.health.AlcoholRecord
import com.linkn.screenintake.health.GrowthUsageCandidate
import com.linkn.screenintake.health.HealthDataRepository
import com.linkn.screenintake.health.NutritionSummary

/**
 * 健康 Tab：跟财务 Tab 同一套样式——顶上一张常驻的「总览」卡片（体重/恢复评分/压力值
 * 这些最新数字，不管切到哪个子视图都看得到），下面用切换按钮分 饮食/饮料/体重 三块。
 * 饮食、饮料是长按拍照记下来的照片列表（各自一个文件夹，AI 只做"吃的还是喝的"这一步
 * 轻量分类，不弹确认，见 [com.linkn.screenintake.capture.CapturePipeline.classifyPhotoAndSave]
 * 头部注释）；体重那块是体重趋势图 + 最近体重记录，跟"身体状态"
 * 归一类。习惯 Tab 目前还没有专门的追踪数据文件，先维持占位，见 [HabitScreen]。
 */
@Composable
fun HealthScreen(resumeTick: Int) {
    val context = LocalContext.current
    val folderUri = ScreenIntakeApp.instance.settingsStore.folderUri
    var weights by remember { mutableStateOf(UiDataCache.weights) }
    var metrics by remember { mutableStateOf(UiDataCache.healthMetrics) }
    var digitalHealth by remember { mutableStateOf(UiDataCache.digitalHealth) }
    var exercises by remember { mutableStateOf(UiDataCache.exercises) }
    var nutrition by remember { mutableStateOf<NutritionSummary?>(null) }
    var editingWeight by remember { mutableStateOf<WeightRow?>(null) }
    // Keep every primary domain consistent: entering it always starts at the
    // first secondary page rather than restoring a special-case detail page.
    var tab by remember { mutableStateOf(HealthTab.MEAL) }

    suspend fun reload() = coroutineScope {
        val weightsResult = async(Dispatchers.IO) {
            LedgerReader.readWeights(context, folderUri).sortedBy { it.date }
        }
        val metricsResult = async(Dispatchers.IO) {
            LedgerReader.readHealthMetrics(context, folderUri).sortedBy { it.date }
        }
        val digitalHealthResult = async(Dispatchers.IO) {
            LedgerReader.readDigitalHealth(context, folderUri).sortedBy { it.date }
        }
        val nutritionResult = async(Dispatchers.IO) { HealthDataRepository(context.applicationContext).nutrition(folderUri) }
        val exerciseResult = async(Dispatchers.IO) { LedgerReader.readExercises(context, folderUri) }
        weights = weightsResult.await().also { UiDataCache.weights = it }
        metrics = metricsResult.await().also { UiDataCache.healthMetrics = it }
        digitalHealth = digitalHealthResult.await().also { UiDataCache.digitalHealth = it }
        nutrition = nutritionResult.await()
        exercises = exerciseResult.await().also { UiDataCache.exercises = it }
    }

    val changeTick = DataChangeSignal.forDomain("健康").value
    LaunchedEffect(resumeTick, changeTick) { reload() }

    val latestWeight = weights.lastOrNull()
    val pagerState = rememberSyncedSectionPagerState(tab.ordinal, HealthTab.entries.size) { tab = HealthTab.entries[it] }
    // 恢复评分/睡眠不一定每天都有（脚本抓不到就留空，或者 Whoop 还没同步过来），从最后
    // 往前找第一条真的有值的，不直接拿最后一行——不然万一最后一天缺了这几项，总览卡片
    // 就会显示"--"，看着像坏了。
    val latestRecovery = metrics.lastOrNull { it.recoveryScore != null }
    val latestSleep = metrics.lastOrNull { it.sleepHours != null }
    val calorieBalance = nutrition?.balanceCalories?.let { if (it > 0) "+${it}kcal" else "${it}kcal" } ?: "--"

    Column(Modifier.fillMaxSize()) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "总览",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                // 四个主要指标排成 2x2：睡眠、饮食情况、恢复情况、体重。饮食情况（卡路里
                // 缺口、口味这些）目前先占位显示"--"——这块要等长按拍照存下来的三餐/饮料
                // 照片被 Mac 那边的模型分析完、写回一个同步文件之后才有数据，见跟他讨论过
                // 的方案；且 Whoop 恢复情况/睡眠这两项现在实际上也读不到，因为手机这边
                // Syncthing 文件夹设成了"仅发送"——这个模式下 Mac 写的 健康.csv 传是传
                // 过来了，但手机本地会直接忽略、不落盘（Syncthing 官方文档原话：send only
                // 模式下"来自其它设备的改动都会被忽略，虽然还是会收到，文件夹会因此显示
                // 「不同步」，但不会真的应用这些改动"），得把手机这边的文件夹类型从"仅发送"
                // 改成"发送并接收"，Mac 写的文件才能真正同步到手机上。
                Row(modifier = Modifier.fillMaxWidth()) {
                    HealthSummaryItem(
                        modifier = Modifier.weight(1f),
                        label = "睡眠",
                        value = latestSleep?.sleepHours?.let { "%.1fh".format(it) } ?: "--"
                    )
                    HealthSummaryItem(
                        modifier = Modifier.weight(1f),
                        label = "热量差额",
                        value = calorieBalance
                    )
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    HealthSummaryItem(
                        modifier = Modifier.weight(1f),
                        label = "恢复情况",
                        value = latestRecovery?.recoveryScore?.let { "%.0f".format(it) } ?: "--"
                    )
                    HealthSummaryItem(
                        modifier = Modifier.weight(1f),
                        label = "体重",
                        value = latestWeight?.let { "%.1fkg".format(it.weightKg) } ?: "--"
                    )
                }
                nutrition?.summary?.takeIf { it.isNotBlank() }?.let { summary ->
                    Text("昨日饮食：$summary", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                if (weights.isEmpty() && metrics.isEmpty()) {
                    Text(
                        "还没有体重或 Whoop 数据——称完体重截个屏，或者把 Whoop 同步脚本跑起来、" +
                            "手机这边的同步文件夹改成「发送并接收」之后，这里就会有数据了；" +
                            "「饮食情况」还在等三餐/饮料照片的分析功能做完。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        UnifiedSectionTabs(
            labels = listOf("饮食", "饮料", "酒精", "体重", "运动", "数字健康", "建议"),
            selectedIndex = tab.ordinal,
            onSelected = { tab = HealthTab.entries[it] },
            pagerState = pagerState
        )

        SectionPager(pagerState, Modifier.weight(1f).fillMaxWidth()) { page ->
            when (HealthTab.entries[page]) {
                HealthTab.MEAL -> PhotoGalleryView(category = "meal", folderUri = folderUri)
                HealthTab.DRINK -> PhotoGalleryView(category = "drink", folderUri = folderUri)
                HealthTab.ALCOHOL -> AlcoholView(folderUri)
                HealthTab.WEIGHT -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TrendCard(
                        title = "体重趋势",
                        points = weights.takeLast(30).map { it.date to it.weightKg },
                        valueFormat = { "%.1fkg".format(it) },
                        emptyHint = "还没有体重记录，或者记录还不够两条——称完体重截个屏，AI 确认后就会出现在这里。",
                        chartHeight = 64.dp,
                        compact = true
                    )
                    // 恢复评分趋势图原来也放在这里，2026-09-16 他反馈"体重"这个子视图下面
                    // 出现恢复评分趋势不太对，应该属于总览——总览卡片里已经有"恢复情况"这个
                    // 数字了（见 HealthScreen 顶部），这里去掉图表，不重复展示，metrics 这个
                    // 状态变量继续留着给总览卡片用。

                    if (weights.isNotEmpty()) {
                        Text("最近体重记录", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        weights.takeLast(10).reversed().forEach { row ->
                            RecordRowCard(Icons.Filled.Favorite, "%.1f kg".format(row.weightKg), row.date,
                                detail = row.note.takeIf { it.isNotBlank() }, onClick = { editingWeight = row })
                        }
                    }
                }
                HealthTab.EXERCISE -> ExerciseView(exercises)
                HealthTab.DIGITAL -> DigitalHealthView(digitalHealth, folderUri)
                HealthTab.ADVICE -> DomainAdviceScreen(ReportDomain.HEALTH, resumeTick)
            }
        }
    }
    editingWeight?.let { row ->
        WeightEditDialog(
            row = row,
            onDismiss = { editingWeight = null },
            onSave = { date, kg, note ->
                editingWeight = null
                kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                    runCatching { RecordStore(context).updateWeight(folderUri, row, row.copy(date = date, weightKg = kg, note = note)) }.onFailure {
                        kotlinx.coroutines.withContext(Dispatchers.Main) { android.widget.Toast.makeText(context, it.message ?: "保存失败", android.widget.Toast.LENGTH_LONG).show() }
                    }
                }
            },
            onDelete = {
                editingWeight = null
                kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                    runCatching { RecordStore(context).deleteWeight(folderUri, row) }.onFailure {
                        kotlinx.coroutines.withContext(Dispatchers.Main) { android.widget.Toast.makeText(context, it.message ?: "删除失败", android.widget.Toast.LENGTH_LONG).show() }
                    }
                }
            }
        )
    }
}

private enum class HealthTab { MEAL, DRINK, ALCOHOL, WEIGHT, EXERCISE, DIGITAL, ADVICE }

@Composable
private fun ExerciseView(rows: List<ExerciseRow>) {
    if (rows.isEmpty()) return EmptyHint("还没有 WHOOP 运动记录")
    LazyColumn(Modifier.fillMaxSize(), contentPadding = RecordListContentPadding, verticalArrangement = Arrangement.spacedBy(RecordListSpacing)) {
        item {
            val recent = rows.take(30)
            val totalCalories = recent.sumOf { it.calories ?: 0.0 }
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("近期运动总览", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text("${recent.size} 次运动 · 消耗 ${totalCalories.toInt()} 千卡", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        items(rows, key = { "${it.startedAt}-${it.type}" }) { row ->
            val info = buildList {
                row.strain?.let { add("Strain %.1f".format(it)) }
                row.calories?.let { add("%d 千卡".format(it.toInt())) }
                row.averageHeartRate?.let { add("平均心率 %d".format(it.toInt())) }
                row.maxHeartRate?.let { add("最高 %d".format(it.toInt())) }
                row.distanceMeters?.takeIf { it > 0 }?.let { add("%.1f km".format(it / 1000)) }
            }
            RecordRowCard(exerciseTypeIcon(row.type), exerciseTypeLabel(row.type),
                "${localExerciseTime(row.startedAt)} · ${exerciseDuration(row.startedAt, row.endedAt)}",
                detail = info.joinToString(" · "))
        }
    }
}

private fun exerciseTypeLabel(raw: String) = when (raw.lowercase()) {
    "walking" -> "步行"
    "running" -> "跑步"
    "cycling" -> "骑行"
    "activity" -> "活动训练"
    else -> raw.ifBlank { "运动" }
}

/** WHOOP uses English activity codes; map the common ones to a recognisable record icon. */
private fun exerciseTypeIcon(raw: String): ImageVector {
    val type = raw.lowercase()
    return when {
        type.contains("run") -> Icons.Filled.DirectionsRun
        type.contains("walk") || type.contains("hiking") -> if (type.contains("hiking")) Icons.Filled.Hiking else Icons.Filled.DirectionsWalk
        type.contains("cycl") || type.contains("bike") -> Icons.Filled.DirectionsBike
        type.contains("swim") -> Icons.Filled.Pool
        type.contains("yoga") || type.contains("pilates") || type.contains("meditat") -> Icons.Filled.SelfImprovement
        type.contains("soccer") || type.contains("football") || type.contains("basketball") || type.contains("volleyball") -> Icons.Filled.SportsSoccer
        type.contains("tennis") || type.contains("badminton") || type.contains("squash") -> Icons.Filled.SportsTennis
        else -> Icons.Filled.FitnessCenter
    }
}

private fun localExerciseTime(value: String) = runCatching {
    java.time.Instant.parse(value).atZone(java.time.ZoneId.of("Asia/Shanghai")).format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))
}.getOrDefault(value.take(16))

private fun exerciseDuration(start: String, end: String) = runCatching {
    val minutes = java.time.Duration.between(java.time.Instant.parse(start), java.time.Instant.parse(end)).toMinutes().coerceAtLeast(0)
    "${minutes / 60}小时${minutes % 60}分"
}.getOrDefault("--")

@Composable
private fun DigitalHealthView(rows: List<DigitalHealthRow>, folderUri: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { HealthDataRepository(context.applicationContext) }
    var candidates by remember { mutableStateOf(emptyList<GrowthUsageCandidate>()) }
    fun reloadCandidates() = scope.launch { candidates = withContext(Dispatchers.IO) { repo.growthUsageCandidates(folderUri) } }
    LaunchedEffect(folderUri, DataChangeSignal.tick.value) { reloadCandidates() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = RecordListContentPadding, verticalArrangement = Arrangement.spacedBy(RecordListSpacing)) {
        item {
            DigitalTrendCard(rows.takeLast(30))
        }
        if (candidates.isNotEmpty()) {
            item { Text("待确认计入成长", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) }
            items(candidates, key = { it.id }) { candidate ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${candidate.date} · ${candidate.appName} ${candidate.minutes} 分钟", style = MaterialTheme.typography.titleSmall)
                        Text("建议关联：${candidate.suggestedActivity.ifBlank { "成长项目" }}", style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { scope.launch(Dispatchers.IO) { repo.respondGrowthUsage(folderUri, candidate.id, false); reloadCandidates() } }) { Text("不计入") }
                            Button(onClick = { scope.launch(Dispatchers.IO) { repo.respondGrowthUsage(folderUri, candidate.id, true); reloadCandidates() } }) { Text("确认关联") }
                        }
                    }
                }
            }
        }
        if (rows.isEmpty()) item { Text("还没有数字健康记录。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(rows.reversed(), key = { it.date }) { row ->
            val feeds = row.apps.filter { it.feed }.sumOf { it.minutes }
            val growth = row.apps.filter { it.growth }.sumOf { it.minutes }
            RecordRowCard(Icons.Filled.Devices, row.date,
                "总时长 ${formatMinutes(row.totalMinutes)} · 信息流 ${formatMinutes(feeds)} · 成长 ${formatMinutes(growth)}")
        }
    }
}

@Composable
private fun DigitalTrendCard(rows: List<DigitalHealthRow>) {
    val total = rows.map { it.totalMinutes.toDouble() }
    val feeds = rows.map { it.apps.filter { app -> app.feed }.sumOf { it.minutes }.toDouble() }
    val growth = rows.map { it.apps.filter { app -> app.growth }.sumOf { it.minutes }.toDouble() }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("数字健康趋势", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (rows.size < 2) Text("至少同步两天屏幕使用时间后显示趋势。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else {
                MultiTrendChart(listOf(total, feeds, growth), Modifier.fillMaxWidth().height(96.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("总时长", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Text("信息流", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    Text("成长", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                }
            }
        }
    }
}

private fun formatMinutes(minutes: Int) = "${minutes / 60}小时${minutes % 60}分"

@Composable
private fun AlcoholView(folderUri: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { HealthDataRepository(context.applicationContext) }
    var records by remember { mutableStateOf(emptyList<AlcoholRecord>()) }
    var candidates by remember { mutableStateOf(emptyList<AlcoholCandidate>()) }
    var editing by remember { mutableStateOf<AlcoholCandidate?>(null) }
    var manual by remember { mutableStateOf(false) }
    fun reload() = scope.launch { withContext(Dispatchers.IO) { repo.alcoholRecords(folderUri) to repo.alcoholCandidates(folderUri) }.also { (r, c) -> records = r; candidates = c } }
    LaunchedEffect(folderUri, DataChangeSignal.tick.value) { reload() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("饮酒记录", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = { manual = true }) { Text("＋ 新增") }
        } }
        if (candidates.isNotEmpty()) item { Text("待确认饮酒", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) }
        items(candidates, key = { it.id }) { candidate -> Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(candidate.summary.ifBlank { "识别到可能饮酒" }, style = MaterialTheme.typography.titleSmall)
                Text("请补充酒类、饮用量与开始/结束时间后确认。", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { scope.launch(Dispatchers.IO) { repo.rejectAlcoholCandidate(folderUri, candidate.id); reload() } }) { Text("不是饮酒") }
                    Button(onClick = { editing = candidate }) { Text("确认并补充") }
                }
            }
        } }
        if (records.isEmpty() && candidates.isEmpty()) item { Text("还没有饮酒记录。WHOOP 习惯或拍照识别到饮酒后，会在这里请你确认；也可手动添加。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(records, key = { it.id }) { record -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(record.drinks, style = MaterialTheme.typography.titleSmall)
            Text("${record.startedAt.ifBlank { record.date }}${record.endedAt.takeIf { it.isNotBlank() }?.let { " ～ $it" }.orEmpty()}", style = MaterialTheme.typography.bodySmall)
            Text(record.source, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } } }
    }
    if (manual || editing != null) AlcoholEditDialog(editing, onDismiss = { manual = false; editing = null }, onSave = { drinks, start, end ->
        scope.launch(Dispatchers.IO) { if (editing == null) repo.addAlcohol(folderUri, drinks, start, end) else repo.confirmAlcoholCandidate(folderUri, editing!!, drinks, start, end); reload() }
        manual = false; editing = null
    })
}

@Composable
private fun AlcoholEditDialog(candidate: AlcoholCandidate?, onDismiss: () -> Unit, onSave: (String, String, String) -> Unit) {
    var drinks by remember(candidate?.id) { mutableStateOf(candidate?.summary.orEmpty()) }
    var start by remember(candidate?.id) { mutableStateOf(candidate?.suggestedAt.orEmpty()) }
    var end by remember(candidate?.id) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (candidate == null) "新增饮酒记录" else "确认饮酒记录") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("可同时填写多种酒，例如：啤酒 500ml；白酒 100ml。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(value = drinks, onValueChange = { drinks = it }, label = { Text("酒类与饮用量") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = start, onValueChange = { start = it }, label = { Text("开始时间（yyyy-MM-dd HH:mm）") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = end, onValueChange = { end = it }, label = { Text("结束时间（可留空）") }, modifier = Modifier.fillMaxWidth())
        } },
        confirmButton = { TextButton(onClick = { onSave(drinks, start, end) }, enabled = drinks.isNotBlank()) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun WeightEditDialog(row: WeightRow, onDismiss: () -> Unit, onSave: (String, Double, String) -> Unit, onDelete: () -> Unit) {
    var date by remember(row.index) { mutableStateOf(row.date) }
    var weight by remember(row.index) { mutableStateOf(row.weightKg.toString()) }
    var note by remember(row.index) { mutableStateOf(row.note) }
    val validWeight = weight.toDoubleOrNull()?.let { it in 20.0..300.0 } == true
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑体重记录") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = date, onValueChange = { date = it }, label = { Text("日期时间") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = weight, onValueChange = { weight = it }, label = { Text("体重（kg）") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("备注") }, modifier = Modifier.fillMaxWidth())
        } },
        confirmButton = { Button(onClick = { weight.toDoubleOrNull()?.let { onSave(date, it, note) } }, enabled = validWeight) { Text("保存") } },
        dismissButton = { Row { TextButton(onClick = onDelete) { Text("删除", color = MaterialTheme.colorScheme.error) }; TextButton(onClick = onDismiss) { Text("取消") } } }
    )
}

/**
 * 饮食/饮料列表——每一行一张缩略图 + AI 给的一句话描述 + 拍摄时间，右边两个按钮：
 * 「改归类」（AI 把吃的喝的分错了，直接挪到另一个文件夹）、「删除」（拍糊了/拍错了）。
 * 长按拍照那条路径判断完直接自动归档、不走「待确认」，这两个按钮就是补救分错类的
 * 唯一入口，见 [com.linkn.screenintake.capture.CapturePipeline.classifyPhotoAndSave]。
 */
@Composable
private fun PhotoGalleryView(category: String, folderUri: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var photos by remember(category) { mutableStateOf(UiDataCache.photos[category].orEmpty()) }
    var mealNotes by remember(category) { mutableStateOf(if (category == "meal") UiDataCache.mealNotes else emptyList()) }

    suspend fun reload() {
        photos = withContext(Dispatchers.IO) { LedgerReader.readPhotos(context, folderUri, category) }
            .also { UiDataCache.photos[category] = it }
        mealNotes = if (category == "meal") withContext(Dispatchers.IO) {
            LedgerReader.readMealNotes(context, folderUri)
        }.also { UiDataCache.mealNotes = it } else emptyList()
    }

    val changeTick = DataChangeSignal.tick.value
    LaunchedEffect(category, folderUri, changeTick) { reload() }

    if (photos.isEmpty() && mealNotes.isEmpty()) {
        EmptyHint(
            if (category == "drink") {
                "还没有饮料照片——长按音量上键拍一张喝的，AI 判断完会自动出现在这里"
            } else {
                "还没有饮食照片——长按音量上键拍一张吃的，AI 判断完会自动出现在这里"
            }
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(mealNotes, key = { "meal-note-${it.index}" }) { note ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(note.text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text(note.date, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        items(photos, key = { it.fileName }) { photo ->
            PhotoRow(
                photo = photo,
                folderUri = folderUri,
                onMove = {
                    scope.launch(Dispatchers.IO) {
                        val target = if (photo.category == "drink") "meal" else "drink"
                        RecordStore(context).movePhoto(folderUri, photo.fileName, photo.category, target)
                        reload()
                    }
                },
                onDelete = {
                    scope.launch(Dispatchers.IO) {
                        RecordStore(context).deletePhoto(folderUri, photo.fileName, photo.category)
                        reload()
                    }
                }
            )
        }
    }
}

@Composable
private fun PhotoRow(
    photo: PhotoItem,
    folderUri: String,
    onMove: () -> Unit,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PhotoThumbnail(folderUri = folderUri, category = photo.category, fileName = photo.fileName)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    photo.caption ?: "（没识别出描述）",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    photoTimeLabel(photo.timestampMillis),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onMove) {
                Icon(
                    Icons.Default.SwapHoriz,
                    contentDescription = if (photo.category == "drink") "改成饮食" else "改成饮料"
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "删除")
            }
        }
    }
}

/** 缩略图：先只读边界算缩放倍数、再按缩放后的尺寸解码，避免相机原图（好几 MB）整张塞进
 * 内存——项目里没有引入 Coil/Glide 这类图片加载库，解码逻辑在 [LedgerReader.loadPhotoThumbnail]
 * 里手写。加载中或者加载失败（比如文件被移走了一瞬间）就先显示一块空底色，不崩不留白洞。 */
@Composable
private fun PhotoThumbnail(folderUri: String, category: String, fileName: String) {
    val context = LocalContext.current
    var bitmap by remember(fileName) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(fileName, category) {
        bitmap = withContext(Dispatchers.IO) {
            LedgerReader.loadPhotoThumbnail(context, folderUri, category, fileName)
        }
    }
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        bitmap?.let { bmp ->
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
    }
}

private val photoTimeFmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)

private fun photoTimeLabel(millis: Long): String = photoTimeFmt.format(java.util.Date(millis))

/** 健康 Tab「总览」卡片里的一小格——跟财务 Tab 的 [FinanceSummaryItem] 是同一个视觉位置，
 * 但体重/恢复评分这些不是"钱"，不能套"¥%.2f"那个格式，所以单独做一个只接格式化好的
 * 字符串的版本，格式化逻辑交给调用方自己决定（kg、纯数字、百分比都能塞）。 */
@Composable
private fun HealthSummaryItem(modifier: Modifier = Modifier, label: String, value: String) {
    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

/**
 * 健康 Tab 里"体重趋势""恢复评分趋势"这类简单折线图共用的卡片：标题 + 折线图 +
 * 首尾日期 + 最低/最高值。点数少于 2 个画不出线，退化成显示 [emptyHint]。
 * points 是 (日期, 数值) 的列表，按时间正序传进来；valueFormat 决定数值怎么格式化显示
 * （kg、纯分数……）。
 */
@Composable
private fun TrendCard(
    title: String,
    points: List<Pair<String, Double>>,
    valueFormat: (Double) -> String,
    emptyHint: String,
    chartHeight: androidx.compose.ui.unit.Dp = 140.dp,
    compact: Boolean = false
) {
    Card {
        Column(Modifier.padding(if (compact) 12.dp else 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(if (compact) 4.dp else 8.dp))
            if (points.size < 2) {
                Text(
                    emptyHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                SimpleTrendChart(
                    values = points.map { it.second },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(chartHeight)
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        points.first().first,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        points.last().first,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!compact) Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("最低 " + valueFormat(points.minOf { it.second }), style = MaterialTheme.typography.labelSmall)
                    Text("最高 " + valueFormat(points.maxOf { it.second }), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

/** 三条归一化折线共用坐标，只表达各自随时间的升降。 */
@Composable
private fun MultiTrendChart(series: List<List<Double>>, modifier: Modifier = Modifier) {
    val colors = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.tertiary)
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier = modifier) {
        val padding = 8.dp.toPx(); val usableH = size.height - padding * 2
        drawLine(gridColor, Offset(0f, size.height - padding), Offset(size.width, size.height - padding), 1.dp.toPx())
        series.forEachIndexed { seriesIndex, values ->
            if (values.size < 2) return@forEachIndexed
            val min = values.min(); val max = values.max(); val range = (max - min).takeIf { it >= 0.01 } ?: 1.0
            val step = size.width / (values.size - 1)
            val points = values.mapIndexed { index, value -> Offset(index * step, padding + usableH * (1f - ((value - min) / range).toFloat().coerceIn(0f, 1f))) }
            points.zipWithNext().forEach { (a, b) -> drawLine(colors[seriesIndex], a, b, 2.dp.toPx(), cap = StrokeCap.Round) }
        }
    }
}

/** 最简单的折线图：等距横坐标（不按真实日期间隔画，简单起见每个点占一样的宽度），
 * 纵坐标按数值在最小-最大之间的比例线性映射。只是给"最近趋势往上走还是往下走"一个
 * 直观感觉，不是精确的数据可视化工具，所以没做坐标轴刻度、没做点击查看具体值这些。 */
@Composable
private fun SimpleTrendChart(values: List<Double>, modifier: Modifier = Modifier) {
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier = modifier) {
        val minV = values.min()
        val maxV = values.max()
        val range = (maxV - minV).let { if (it < 0.01) 1.0 else it }
        val paddingV = 8.dp.toPx()
        val usableH = size.height - paddingV * 2
        val stepX = if (values.size > 1) size.width / (values.size - 1) else 0f

        fun yFor(v: Double): Float {
            val t = ((v - minV) / range).toFloat().coerceIn(0f, 1f)
            return paddingV + usableH * (1f - t)
        }

        drawLine(
            color = gridColor,
            start = Offset(0f, size.height - paddingV),
            end = Offset(size.width, size.height - paddingV),
            strokeWidth = 1.dp.toPx()
        )

        val points = values.mapIndexed { index, v -> Offset(index * stepX, yFor(v)) }
        for (i in 0 until points.size - 1) {
            drawLine(
                color = lineColor,
                start = points[i],
                end = points[i + 1],
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
        points.forEach { p -> drawCircle(color = lineColor, radius = 4.dp.toPx(), center = p) }
    }
}
