package com.linkn.screenintake.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.linkn.screenintake.ScreenIntakeApp
import com.linkn.screenintake.capture.CaptureConfirmActions
import com.linkn.screenintake.capture.PendingCaptureNotifier
import com.linkn.screenintake.classify.Categories
import com.linkn.screenintake.classify.ClassifyResult
import com.linkn.screenintake.store.CardAccount
import com.linkn.screenintake.store.PendingDraft
import com.linkn.screenintake.store.LedgerReader
import com.linkn.screenintake.store.RecordStore
import com.linkn.screenintake.store.UnconfirmedNote
import com.linkn.screenintake.work.WorkChange
import com.linkn.screenintake.work.WorkChangeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.widget.Toast

/**
 * 底部导航「待确认」那个 Tab 的内容——以前只在首页/顶部横幅露个数字，点了也没
 * 地方去，现在是真正能操作的地方：读屏/拍照弹出来还没点确认或编辑就被划掉的通知，草稿
 * 都还在这里，可以直接确认、编辑内容后再确认、或者整条丢弃，不用非得等下次再触发一次
 * 通知。跟财务/待办/灵感一样是个普通 Tab 内容，没有自己的 Scaffold/顶栏——外层
 * [MainScaffold] 统一提供。
 *
 * 数据（drafts/notes/loading）不在这里自己读，由 [MainScaffold] 统一读取后传进来，跟角标
 * 数字共用同一份——避免每次切进这个 Tab 都要重新读一遍「待确认」文件夹才有内容，读取慢的
 * 时候会有一瞬间看着像没内容。这里只在增删改后通过 [onReload] 通知外层重新读一遍。
 *
 * 分两段：上面是真正「待确认」的草稿（有结构化数据，能操作），下面是纯粹的失败排查记录
 * （分类失败/读屏读到空文字这些，没法「确认」成什么，只能看/删）。旧版本存的草稿（还没
 * 加机读 JSON 之前）没法解析出结构化数据，会退化成「只能看/删」的样子，不会导致崩溃。
 */
@Composable
fun PendingScreen(
    drafts: List<PendingDraft>,
    notes: List<UnconfirmedNote>,
    workChanges: List<WorkChange> = emptyList(),
    loading: Boolean,
    onReload: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val folderUri = ScreenIntakeApp.instance.settingsStore.folderUri

    var editingDraft by remember { mutableStateOf<PendingDraft?>(null) }
    var detailNote by remember { mutableStateOf<UnconfirmedNote?>(null) }

    fun act(block: suspend () -> Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                block()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, e.message ?: "保存失败，请稍后重试", Toast.LENGTH_LONG).show()
                }
            } finally {
                withContext(Dispatchers.Main) { onReload() }
            }
        }
    }

    if (drafts.isEmpty() && notes.isEmpty() && workChanges.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (loading) {
                CircularProgressIndicator()
            } else {
                Text("没有待确认的内容", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (drafts.isNotEmpty()) {
                item {
                    Text("待确认（可调整后落盘）", style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(bottom = 4.dp))
                }
            }
            items(drafts, key = { it.draftId }) { draft ->
                DraftCard(
                    draft = draft,
                    onConfirm = {
                        val result = draft.result ?: return@DraftCard
                        act {
                            CaptureConfirmActions.confirmOrEdit(context, draft.draftId, result, null)
                            PendingCaptureNotifier.cancel(context, draft.draftId.hashCode())
                        }
                    },
                    onEdit = { editingDraft = draft },
                    onDelete = {
                        act {
                            RecordStore(context).deletePendingDraft(folderUri, draft.draftId)
                            PendingCaptureNotifier.cancel(context, draft.draftId.hashCode())
                        }
                    }
                )
            }
            if (workChanges.isNotEmpty()) {
                item { Text("工作变更", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(workChanges, key = { it.id }) { change ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(change.title, style = MaterialTheme.typography.titleMedium)
                        if (change.before.isNotBlank()) Text("原记录：${change.before}", style = MaterialTheme.typography.bodySmall)
                        if (change.after.isNotBlank()) Text("建议变为：${change.after}")
                        if (change.evidence.isNotBlank()) Text("依据：${change.evidence}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { act { WorkChangeRepository(context).respond(folderUri, change, false) } }) { Text("不采纳") }
                            Button(onClick = { act { WorkChangeRepository(context).respond(folderUri, change, true) } }) { Text("确认") }
                        }
                    }
                }
            }
            if (notes.isNotEmpty()) {
                item {
                    Text(
                        "识别失败的记录",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (notes.isNotEmpty()) {
                item {
                    Text("识别失败日志（一般可删，不是正式账单）", style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                }
            }
            items(notes, key = { it.fileName }) { note ->
                NoteCard(
                    note = note,
                    onClick = { detailNote = note },
                    onDelete = {
                        act { RecordStore(context).deleteUnconfirmedNote(folderUri, note.fileName) }
                    }
                )
            }
        }
    }

    // onEdit 只在 DraftCard 显示了「编辑」按钮时触发，而那个按钮只在 result != null 时才会
    // 出现，所以这里的 draft.result 理论上不会是 null；万一是（理论上不会发生），干脆不弹
    // 对话框，避免在组合过程中直接写状态。
    editingDraft?.result?.let { result ->
        val draft = editingDraft!!
        EditDraftDialog(
            result = result,
            onDismiss = { editingDraft = null },
            onSubmit = { updated ->
                editingDraft = null
                act {
                    CaptureConfirmActions.confirmOrEdit(context, draft.draftId, updated, null, updated.dueAt)
                    PendingCaptureNotifier.cancel(context, draft.draftId.hashCode())
                }
            }
        )
    }

    detailNote?.let { note ->
        NoteDetailDialog(note = note, onDismiss = { detailNote = null })
    }
}

@Composable
private fun DraftCard(
    draft: PendingDraft,
    onConfirm: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val result = draft.result
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (result != null) {
                Text(result.typeLabel(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Text(result.displaySummary(), style = MaterialTheme.typography.bodyLarge)
                if (result.isExpense || result.isIncome) {
                    Text(
                        "支付卡片：${result.card?.takeIf { it.isNotBlank() } ?: "未识别"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (result.card.isNullOrBlank()) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            } else {
                Text("旧版草稿（缺少结构化数据，只能看/删）", style = MaterialTheme.typography.labelLarge)
            }
            if (draft.screenText.isNotBlank()) {
                Text(
                    draft.screenText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            // 待办事项必须有具体提醒时间，不能靠「确认」原样放行一条没有时间的待办——
            // 模型没抽出 dueAt 的话，这里就不出「确认」按钮，只留「编辑」，逼着走时间
            // 选择器把时间补上（见 EditDraftDialog）。其余类型（支出/收入/灵感/忽略）
            // 没有这条限制，正常显示两个按钮。
            val missingDueTime = result != null && result.isTodo && result.dueAt.isNullOrBlank()
            if (missingDueTime) {
                Text(
                    "缺具体提醒时间，点「编辑」补上才能确认",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            // 股票交易确认后会直接计入持仓的滚动计算，不像消费/收入记错了还能在财务列表里
            // 直接改——这里提示一下，代码/股数/价格不对的话，删掉重来比编辑更保险。
            if (result != null && result.isTrade) {
                Text(
                    "确认后会计入持仓；不对请点「调整」改代码/股数/价格",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            // 持仓设置确认后会直接覆盖这只股票当前的股数/成本（不是累加）——同样提示一下，
            // 数字不对就删掉重来，比编辑更保险。
            if (result != null && result.isHolding) {
                Text(
                    "确认后会覆盖持仓；不对请点「调整」改代码/股数/成本",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            // 转账确认后会同时改动转出/转入两张卡各自的余额——同样提示一下，账户或金额
            // 认错了就删掉重来，比编辑更保险（编辑框只能加备注，改不了转出/转入的账户）。
            if (result != null && result.isTransfer) {
                Text(
                    "确认后会改两张卡余额；账户或金额不对请点「调整」",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onDelete,
                    modifier = Modifier.weight(1f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("删除") }
                if (result != null) {
                    OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f)) { Text("调整") }
                    if (!missingDueTime) {
                        Button(onClick = onConfirm, modifier = Modifier.weight(1f)) { Text("确认") }
                    }
                }
            }
        }
    }
}

@Composable
private fun NoteCard(note: UnconfirmedNote, onClick: () -> Unit, onDelete: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                note.content,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 6
            )
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDelete) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun EditDraftDialog(
    result: ClassifyResult,
    onDismiss: () -> Unit,
    onSubmit: (ClassifyResult) -> Unit
) {
    val softTypes = linkedMapOf(
        "todo" to "待办", "note" to "灵感", "expense" to "支出",
        "income" to "收入", "meal_note" to "饮食"
    )
    val specialtyTypes = linkedMapOf(
        "transfer" to "转账", "trade" to "股票交易", "holding" to "持仓设置",
        "weight" to "体重", "digital_health" to "屏幕使用"
    )
    val isSpecialty = result.type in specialtyTypes
    var selectedType by remember {
        mutableStateOf(
            when {
                result.type in specialtyTypes -> result.type
                result.type in softTypes -> result.type
                else -> "note"
            }
        )
    }
    var content by remember { mutableStateOf(result.summary ?: result.merchant.orEmpty()) }
    var category by remember { mutableStateOf(result.category.orEmpty()) }
    var amount by remember { mutableStateOf(result.amount?.toString().orEmpty()) }
    var todoKind by remember { mutableStateOf(if (result.domain == "工作") "工作" else "其他") }
    var noteDomain by remember {
        mutableStateOf(result.normalizedDomain().let { if (it == "习惯") "成长" else it })
    }
    var purpose by remember { mutableStateOf(result.purpose) }
    var tradeSide by remember { mutableStateOf(result.tradeSide ?: "buy") }
    var market by remember { mutableStateOf(result.market ?: "A") }
    var stockCode by remember { mutableStateOf(result.stockCode.orEmpty()) }
    var stockName by remember { mutableStateOf(result.stockName.orEmpty()) }
    var shares by remember { mutableStateOf(result.shares?.toString().orEmpty()) }
    var price by remember { mutableStateOf(result.price?.toString().orEmpty()) }
    var weightKg by remember { mutableStateOf(result.amount?.toString().orEmpty()) }
    var screenMinutes by remember { mutableStateOf(result.amount?.toInt()?.toString().orEmpty()) }
    val context = LocalContext.current
    val folderUri = ScreenIntakeApp.instance.settingsStore.folderUri
    val cards by produceState(initialValue = emptyList<CardAccount>(), folderUri) {
        value = withContext(Dispatchers.IO) { LedgerReader.readCards(context, folderUri) }
    }
    fun matchingCardName(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val digits = raw.filter(Char::isDigit)
        val compact = raw.lowercase().filter(Char::isLetterOrDigit)
        return cards.firstOrNull { account ->
            val accountDigits = account.name.filter(Char::isDigit)
            val accountCompact = account.name.lowercase().filter(Char::isLetterOrDigit)
            (digits.length >= 4 && accountDigits.takeLast(4) == digits.takeLast(4)) ||
                (compact.isNotBlank() && (accountCompact.contains(compact) || compact.contains(accountCompact)))
        }?.name
    }
    var card by remember(result.card) { mutableStateOf<String?>(null) }
    var fromCard by remember(result.fromCard) { mutableStateOf<String?>(null) }
    var toCard by remember(result.toCard) { mutableStateOf<String?>(null) }
    LaunchedEffect(cards, result.card, result.fromCard, result.toCard) {
        if (card == null) card = matchingCardName(result.card)
        if (fromCard == null) fromCard = matchingCardName(result.fromCard) ?: result.fromCard
        if (toCard == null) toCard = matchingCardName(result.toCard) ?: result.toCard
    }

    var pickedDueAt by remember {
        mutableStateOf(
            result.dueAt?.let {
                try { java.time.LocalDateTime.parse(it) } catch (e: Exception) { null }
            }
        )
    }
    val dueAtDisplay = pickedDueAt?.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

    fun launchDateTimePicker() {
        val base = pickedDueAt ?: java.time.LocalDateTime.now().plusDays(1).withHour(9).withMinute(0)
        android.app.DatePickerDialog(
            context,
            { _, year, month, dayOfMonth ->
                android.app.TimePickerDialog(
                    context,
                    { _, hour, minute ->
                        pickedDueAt = java.time.LocalDateTime.of(year, month + 1, dayOfMonth, hour, minute)
                    },
                    base.hour,
                    base.minute,
                    true
                ).show()
            },
            base.year,
            base.monthValue - 1,
            base.dayOfMonth
        ).show()
    }

    val moneyValid = amount.toDoubleOrNull()?.let { it > 0 } == true && category.isNotBlank()
    val canSubmit = when (selectedType) {
        "todo" -> content.isNotBlank() && pickedDueAt != null
        "expense", "income" -> moneyValid
        "transfer" -> amount.toDoubleOrNull()?.let { it > 0 } == true &&
            !fromCard.isNullOrBlank() && !toCard.isNullOrBlank() && fromCard != toCard
        "trade" -> stockCode.isNotBlank() && shares.toDoubleOrNull()?.let { it > 0 } == true &&
            price.toDoubleOrNull()?.let { it > 0 } == true
        "holding" -> stockCode.isNotBlank() && shares.toDoubleOrNull()?.let { it >= 0 } == true
        "weight" -> weightKg.toDoubleOrNull()?.let { it > 0 } == true
        "digital_health" -> screenMinutes.toIntOrNull()?.let { it >= 0 } == true
        else -> content.isNotBlank()
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("确认记录", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (isSpecialty) {
                    Text(
                        "类型：${specialtyTypes[result.type] ?: result.type}（确认后会影响账户/持仓/健康数据，请改对关键字段）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text("记录类型", style = MaterialTheme.typography.labelLarge)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        softTypes.forEach { (value, label) ->
                            FilterChip(selected = selectedType == value, onClick = {
                                selectedType = value
                            }, label = { Text(label) })
                        }
                    }
                }

                when (selectedType) {
                    "expense", "income" -> {
                        OutlinedTextField(
                            value = amount,
                            onValueChange = { amount = it.filter { ch -> ch.isDigit() || ch == '.' } },
                            label = { Text("金额") }, prefix = { Text("¥") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth(), singleLine = true
                        )
                        if (selectedType == "expense") {
                            ExpenseCategoryPicker(category) { category = it }
                            ExpensePurposePicker(purpose) { purpose = it }
                        } else {
                            OutlinedTextField(category, { category = it }, label = { Text("收入分类") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        }
                        ExpenseCardPicker(card, cards) { card = it }
                    }
                    "transfer" -> {
                        Text("转出账户", style = MaterialTheme.typography.labelLarge)
                        ExpenseCardPicker(fromCard, cards) { fromCard = it }
                        Text("转入账户", style = MaterialTheme.typography.labelLarge)
                        ExpenseCardPicker(toCard, cards) { toCard = it }
                        OutlinedTextField(
                            value = amount,
                            onValueChange = { amount = it.filter { ch -> ch.isDigit() || ch == '.' } },
                            label = { Text("转账金额") }, prefix = { Text("¥") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth(), singleLine = true
                        )
                        if (!fromCard.isNullOrBlank() && fromCard == toCard) {
                            Text("转出和转入不能是同一张卡", color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    "trade" -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("buy" to "买入", "sell" to "卖出").forEach { (v, label) ->
                                FilterChip(selected = tradeSide == v, onClick = { tradeSide = v }, label = { Text(label) })
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("A" to "A股", "US" to "美股", "HK" to "港股").forEach { (v, label) ->
                                FilterChip(selected = market == v, onClick = { market = v }, label = { Text(label) })
                            }
                        }
                        OutlinedTextField(stockCode, { stockCode = it }, label = { Text("证券代码") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true)
                        OutlinedTextField(stockName, { stockName = it }, label = { Text("证券名称") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true)
                        OutlinedTextField(shares, { shares = it.filter { ch -> ch.isDigit() || ch == '.' } },
                            label = { Text("股数") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth(), singleLine = true)
                        OutlinedTextField(price, { price = it.filter { ch -> ch.isDigit() || ch == '.' } },
                            label = { Text("每股价格") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth(), singleLine = true)
                    }
                    "holding" -> {
                        OutlinedTextField(stockCode, { stockCode = it }, label = { Text("证券代码") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true)
                        OutlinedTextField(stockName, { stockName = it }, label = { Text("证券名称") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true)
                        OutlinedTextField(shares, { shares = it.filter { ch -> ch.isDigit() || ch == '.' } },
                            label = { Text("当前股数") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth(), singleLine = true)
                        OutlinedTextField(price, { price = it.filter { ch -> ch.isDigit() || ch == '.' } },
                            label = { Text("成本价（可空）") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth(), singleLine = true)
                    }
                    "weight" -> {
                        OutlinedTextField(weightKg, { weightKg = it.filter { ch -> ch.isDigit() || ch == '.' } },
                            label = { Text("体重 (kg)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth(), singleLine = true)
                    }
                    "digital_health" -> {
                        OutlinedTextField(screenMinutes, { screenMinutes = it.filter { ch -> ch.isDigit() } },
                            label = { Text("屏幕使用总分钟") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(), singleLine = true)
                    }
                    "todo" -> {
                        Text("待办类型", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("工作", "其他").forEach { value ->
                                FilterChip(selected = todoKind == value, onClick = { todoKind = value }, label = { Text(value) })
                            }
                        }
                        OutlinedButton(onClick = { launchDateTimePicker() }, modifier = Modifier.fillMaxWidth()) {
                            Text(dueAtDisplay?.let { "提醒时间：$it（点击修改）" } ?: "选择提醒日期和时间")
                        }
                        if (pickedDueAt == null) {
                            Text("请选择提醒时间", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    "note" -> {
                        Text("灵感分类", style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf("工作", "其他").forEach { value ->
                                FilterChip(selected = noteDomain == value, onClick = { noteDomain = value }, label = { Text(value) })
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = content, onValueChange = { content = it },
                    label = { Text(when (selectedType) {
                        "expense", "income", "transfer" -> "说明"
                        "weight", "digital_health" -> "备注"
                        else -> "具体内容"
                    }) },
                    minLines = 2, modifier = Modifier.fillMaxWidth()
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Button(
                        onClick = {
                            val dueAtIso = pickedDueAt?.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                            val domain = when (selectedType) {
                                "todo" -> todoKind
                                "note" -> if (noteDomain == "成长") "习惯" else noteDomain
                                else -> result.domain
                            }
                            onSubmit(result.copy(
                                type = selectedType,
                                amount = when (selectedType) {
                                    "expense", "income", "transfer" -> amount.toDoubleOrNull()
                                    "weight" -> weightKg.toDoubleOrNull()
                                    "digital_health" -> screenMinutes.toIntOrNull()?.toDouble()
                                    else -> result.amount
                                },
                                category = if (selectedType == "expense" || selectedType == "income") category.trim() else result.category,
                                summary = content.trim().ifBlank { result.summary },
                                domain = domain,
                                dueAt = if (selectedType == "todo") dueAtIso else null,
                                whenText = if (selectedType == "todo") dueAtDisplay else null,
                                purpose = if (selectedType == "expense") purpose else null,
                                card = if (selectedType == "expense" || selectedType == "income") card else result.card,
                                fromCard = if (selectedType == "transfer") fromCard else result.fromCard,
                                toCard = if (selectedType == "transfer") toCard else result.toCard,
                                tradeSide = if (selectedType == "trade") tradeSide else result.tradeSide,
                                market = if (selectedType == "trade" || selectedType == "holding") market else result.market,
                                stockCode = if (selectedType == "trade" || selectedType == "holding") stockCode.trim() else result.stockCode,
                                stockName = if (selectedType == "trade" || selectedType == "holding") stockName.trim() else result.stockName,
                                shares = if (selectedType == "trade" || selectedType == "holding") shares.toDoubleOrNull() else result.shares,
                                price = if (selectedType == "trade" || selectedType == "holding") price.toDoubleOrNull() else result.price
                            ))
                        },
                        enabled = canSubmit
                    ) { Text("提交") }
                }
            }
        }
    }
}

@Composable
private fun DraftDetailDialog(draft: PendingDraft, onDismiss: () -> Unit) {
    val result = draft.result
    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (result != null) {
                    Text(result.typeLabel(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(result.displaySummary(), style = MaterialTheme.typography.bodyLarge)
                } else {
                    Text("旧版草稿（缺少结构化数据）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                if (draft.screenText.isNotBlank()) {
                    Text("原始内容", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(draft.screenText, style = MaterialTheme.typography.bodyMedium)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
            }
        }
    }
}

@Composable
private fun NoteDetailDialog(note: UnconfirmedNote, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
          ) {
                Text("识别失败的记录", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(note.content, style = MaterialTheme.typography.bodyMedium)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
            }
        }
    }
}

private enum class InboxSubTab { PENDING, TODO, NOTE }

/**
 * 顶部齿轮按钮旁边那个带角标的图标打开的「收件箱」，取代了原来单独占一个 Tab 位置的
 * 「待确认」——现在整个底部导航按财务/健康/工作/习惯这四个领域分（见 [BottomTab]），
 * 待确认这个 AI 拿不准要人工确认的队列本质上是跨领域的，硬塞进某一个领域 Tab 里，一条
 * 被 AI 分错领域的草稿就会变得找不到，所以刻意保持全局、不按领域拆分（这是跟用户明确
 * 讨论过的决定）。同一个入口下面另外顺带装了「全部待办」「全部灵感」两个子视图——待办/
 * 灵感现在基本都集中在工作领域，直接完整展示在 [WorkScreen] 里；这里的全部待办/全部灵感
 * 子视图是给其它领域（或分类分错的）零星条目留的一个全局出口。
 *
 * initialDomain 非空时（从某个领域 Tab 的「查看全部」点进来），默认落在「全部待办」子
 * 视图并且预先按这个领域筛好；不传（从顶部图标直接点进来）则默认落在「待确认」，也不
 * 做任何领域筛选——两种入口场景下第一眼看到的东西都符合点进来之前的预期。
 */
@Composable
fun InboxScreen(
    resumeTick: Int,
    drafts: List<PendingDraft>,
    notes: List<UnconfirmedNote>,
    loading: Boolean,
    onReload: () -> Unit,
    initialDomain: String? = null,
    pendingOpenTick: Int = 0
) {
    var subTab by remember(initialDomain, pendingOpenTick) {
        mutableStateOf(if (initialDomain != null) InboxSubTab.TODO else InboxSubTab.PENDING)
    }
    var domainFilter by remember(initialDomain, pendingOpenTick) { mutableStateOf(initialDomain) }
    val pagerState = rememberSyncedSectionPagerState(subTab.ordinal, InboxSubTab.entries.size) { subTab = InboxSubTab.entries[it] }

    Column(modifier = Modifier.fillMaxSize()) {
        UnifiedSectionTabs(
            labels = listOf("待确认（${drafts.size + notes.size}）", "全部待办", "全部灵感"),
            selectedIndex = subTab.ordinal,
            onSelected = { subTab = InboxSubTab.entries[it] },
            pagerState = pagerState
        )

        SectionPager(pagerState, Modifier.weight(1f).fillMaxWidth()) { page ->
            when (InboxSubTab.entries[page]) {
                InboxSubTab.PENDING -> PendingScreen(
                    drafts = drafts,
                    notes = notes,
                    loading = loading,
                    onReload = onReload
                )
                InboxSubTab.TODO -> TodoListScreen(resumeTick = resumeTick, domainFilter = null)
                InboxSubTab.NOTE -> Column(Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(selected = domainFilter == null, onClick = { domainFilter = null }, label = { Text("全部") })
                        ClassifyResult.DOMAINS.forEach { d ->
                            FilterChip(selected = domainFilter == d, onClick = { domainFilter = d }, label = { Text(d) })
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    NoteListScreen(resumeTick = resumeTick, domainFilter = domainFilter)
                }
            }
        }
    }
}
