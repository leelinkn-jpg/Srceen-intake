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
import com.linkn.screenintake.classify.ExpensePurpose
import com.linkn.screenintake.store.NoteItem
import com.linkn.screenintake.store.PhotoItem
import com.linkn.screenintake.store.PriceCache
import com.linkn.screenintake.store.QuoteFetcher
import com.linkn.screenintake.store.RecordStore
import com.linkn.screenintake.store.TodoItem
import com.linkn.screenintake.store.TransferRow
import com.linkn.screenintake.store.UiDataCache
import com.linkn.screenintake.store.WeightRow
import com.linkn.screenintake.report.ReportDomain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 财务 Tab 的内容：收支流水、股票持仓、卡片余额三个子视图，顶上一张常驻的「总览」卡片
 * （不管切到哪个子视图都看得到本月收支和当前总资产）。卡片管理（以前在设置页里）也在
 * 这里，跟它管的钱放在同一个 Tab 下更符合直觉。数据来自 [LedgerReader] 现读现解析保存
 * 文件夹里的几个文件；点一下某一条就能弹出编辑框改内容或者删掉，改完/删完立刻整篇重写
 * 对应的文件、重新读一遍刷新列表。顶部标题栏和设置入口统一由外层的 [MainScaffold] 提供，
 * 这里只负责内容区域。（原来财务/待办/灵感/健康/工作/习惯几个 Tab 都写在同一个
 * DataScreens.kt 里，2026-09-16 按 Tab 拆成了多个文件，都还在同一个 ui 包下，互相引用
 * 不用加 import。）
 */
private enum class FinanceTab { MONEY, HOLDINGS, CARDS, ADVICE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FinanceScreen(resumeTick: Int) {
    val context = LocalContext.current
    val folderUri = ScreenIntakeApp.instance.settingsStore.folderUri
    var rows by remember { mutableStateOf(UiDataCache.ledger) }
    var editingRow by remember { mutableStateOf<LedgerRow?>(null) }
    var holdings by remember { mutableStateOf(UiDataCache.holdings) }
    var editingHolding by remember { mutableStateOf<Holding?>(null) }
    var cards by remember { mutableStateOf(UiDataCache.cards) }
    var editingCard by remember { mutableStateOf<CardAccount?>(null) }
    // 转账记录.csv 只读展示用——转账不像收支/持仓那样有编辑弹窗，账户/金额认错了走
    // 「待确认」里删掉重来（见 PendingScreen 的提示），这里纯粹是给「卡片」页一个
    // 「最近转了什么」的可视化留痕，不提供改/删。
    var transfers by remember { mutableStateOf(UiDataCache.transfers) }
    // 卡片页「转账」按钮用：点了某张卡的转账按钮，就把这张卡记成"转出方"、弹出转账表单
    // 选转入方+金额；跟 editingCard 分开管理，因为转账表单跟"编辑卡片"表单是两个不同的
    // 弹窗，不能共用同一个 state（点转账的时候要关掉编辑弹窗，不是改成转账弹窗的内容）。
    var transferFromCard by remember { mutableStateOf<CardAccount?>(null) }
    var tab by remember { mutableStateOf(FinanceTab.MONEY) }
    // 股票现价现取现算（见 QuoteFetcher 头部注释：手机同步文件夹是"仅发送"，价格这种
    // 会变的数字没法指望从外面同步进来）。价格本身存在 PriceCache 这个 App 内单例里
    // （不是这里的 remember 状态）——放 remember 里的话，切一下 Tab 再切回来，这个
    // Composable 被销毁重建，取到的价格就清零了，市值会瞬间跳回按成本估算的数字，
    // 跟"没手动刷新就不该变"的预期对不上；放单例里只要 App 进程不重启就会一直保留
    // 上次刷新的结果。放在 FinanceScreen 这一层读、不放进持仓子视图内部，是为了让
    // 「总览」卡片算总资产时能用上同一份已经取到的价格，不用两边各刷各的。
    val prices = PriceCache.prices
    var refreshingPrices by remember { mutableStateOf(false) }
    // 保存文件夹这个 SAF 授权偶尔会失效（最常见是把 App 整个卸载重装了一遍）——一旦
    // 失效，下面几个 read* 会因为拿不到文件夹/文件统一兜底返回空列表，几个列表全部
    // 变空，看着就像记账数据全没了，其实文件都还在 Mac 那边，只是手机这边暂时看不到。
    // 这里单独探测一下，跟"确实还没记过东西"的空列表区分开，提示明确该怎么办。
    var folderAccessible by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    fun keyOf(h: Holding) = "${h.market}:${h.code}"

    fun refreshPrices() {
        if (holdings.isEmpty() || refreshingPrices) return
        refreshingPrices = true
        scope.launch(Dispatchers.IO) {
            val result = holdings.associate { h -> keyOf(h) to QuoteFetcher.fetchPrice(h.market, h.code) }
            PriceCache.update(result)
            refreshingPrices = false
        }
    }

    suspend fun reload() = coroutineScope {
        val accessibleResult = async(Dispatchers.IO) { LedgerReader.folderAccessible(context, folderUri) }
        val holdingsResult = async(Dispatchers.IO) { LedgerReader.readHoldings(context, folderUri) }
        val cardsResult = async(Dispatchers.IO) { LedgerReader.readCards(context, folderUri) }
        val transfersResult = async(Dispatchers.IO) { LedgerReader.readTransfers(context, folderUri) }
        val rowsResult = async(Dispatchers.IO) {
            // 账本.csv 的「日期」只精确到天（少数带具体时间），同一天记的好几笔互相之间是
            // 「平局」，光按日期倒序排（sortedWith 是稳定排序）平局部分会保持原来在文件里
            // 的顺序，也就是先记的在前、后记的在后。但文件本身是按记录时间从旧到新往后
            // 追加的，这样一来今天最新记的一笔反而会被排到「今天」这一组的最下面、可能
            // 已经滚到屏幕外了，看着就像"记了但是不显示"。index 是这一行在文件里的原始
            // 位置（越靠后越新），日期相同时再按 index 倒序排一次，让同一天里最新记的
            // 那笔始终排在最前面。
            LedgerReader.readLedger(context, folderUri)
                .sortedWith(compareByDescending<LedgerRow> { it.date }.thenByDescending { it.index })
        }
        folderAccessible = accessibleResult.await()
        holdings = holdingsResult.await().also { UiDataCache.holdings = it }
        cards = cardsResult.await().also { UiDataCache.cards = it }
        transfers = transfersResult.await().also { UiDataCache.transfers = it }
        rows = rowsResult.await().also { UiDataCache.ledger = it }
    }

    // 只在首次进入时读一遍是不够的：截屏/通知确认这些捕获路径经常不会把 App 带到前台，
    // 如果一直停在这个 Tab 没切走、也没离开过 App，看到的会是很久以前读到的旧数据——
    // 哪怕文件早就更新了。resumeTick 在 App 每次回到前台时 +1（MainActivity.onResume），
    // 这里跟着它重新读一遍，保证"回来看一眼"总能看到最新记录。但组合键截屏/长按拍照/
    // 长按打字这几条捕获路径本来就是设计成不把 App 切到后台的，人如果正好停在这个 Tab
    // 上截屏记账，resumeTick 根本不会变——所以还要跟着 DataChangeSignal 走，
    // RecordStore 真正写完文件那一刻就会通知到，不用等回到前台或者切 Tab。
    val financeChangeTick = DataChangeSignal.forDomain("财务").value
    LaunchedEffect(resumeTick, financeChangeTick) { reload() }

    val monthPrefix = remember {
        java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.CHINA).format(java.util.Date())
    }
    val monthTotals = remember(rows, monthPrefix) {
        rows.asSequence().filter { it.date.startsWith(monthPrefix) }
            .fold(0.0 to 0.0) { totals, row ->
                if (row.type == "支出") totals.copy(first = totals.first + row.amount)
                else if (row.type == "收入") totals.copy(second = totals.second + row.amount)
                else totals
            }
    }
    val monthExpense = monthTotals.first
    val monthIncome = monthTotals.second
    val monthBalance = monthIncome - monthExpense
    // 现金净额：储蓄卡是「有多少」直接加，信用卡记的是「欠款」所以要减掉；持仓市值
    // 优先用已经刷新到的实时价，没刷新过的（prices 里没有这个 key）就先按成本价估算，
    // 跟持仓子视图里的兜底逻辑一致，避免刚打开 App、还没点"刷新最新价格"时总资产显示 0。
    val cashNet = cards.sumOf { c -> if (c.type == "信用") -c.balance else c.balance }
    val holdingsValue = holdings.sumOf { h -> (prices[keyOf(h)] ?: h.avgCost) * h.shares }
    val totalAssets = cashNet + holdingsValue

    Column(Modifier.fillMaxSize()) {
        if (!folderAccessible) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "看不到保存文件夹了",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        "数据没有丢，还在 Mac 那边的同步文件夹里——只是手机这边暂时没有访问" +
                            "权限了（常见于把 App 整个卸载重装过一遍）。去「设置」里重新选一下" +
                            "同一个文件夹就能恢复。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

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
                Row(modifier = Modifier.fillMaxWidth()) {
                    FinanceSummaryItem(
                        modifier = Modifier.weight(1f),
                        label = "本月支出",
                        amount = monthExpense,
                        color = MaterialTheme.colorScheme.error
                    )
                    FinanceSummaryItem(
                        modifier = Modifier.weight(1f),
                        label = "本月收入",
                        amount = monthIncome,
                        color = MaterialTheme.colorScheme.primary
                    )
                    FinanceSummaryItem(
                        modifier = Modifier.weight(1f),
                        label = "本月结余",
                        amount = monthBalance,
                        color = if (monthBalance >= 0) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                }
                Column {
                    Text(
                        "总资产（现金净额 + 持仓市值）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        "¥%.2f".format(totalAssets),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                if (cards.isEmpty() && holdings.isEmpty()) {
                    Text(
                        "还没建卡片、也没记过持仓，总资产暂时只算了个 ¥0.00——去下面「卡片」" +
                            "「持仓」页建一下就有数了。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        UnifiedSectionTabs(
            labels = listOf("收支", "持仓", "卡片", "建议"),
            selectedIndex = tab.ordinal,
            onSelected = { tab = FinanceTab.entries[it] }
        )

        Box(modifier = Modifier.weight(1f).fillMaxWidth().sectionSwipes(tab.ordinal, FinanceTab.entries.size) { tab = FinanceTab.entries[it] }) {
            when (tab) {
                FinanceTab.HOLDINGS -> HoldingsView(
                    holdings = holdings,
                    prices = prices,
                    refreshing = refreshingPrices,
                    onRefresh = { refreshPrices() },
                    onEditHolding = { editingHolding = it }
                )
                FinanceTab.CARDS -> CardsView(
                    cards = cards,
                    transfers = transfers,
                    onAdd = { editingCard = CardAccount(name = "", type = "储蓄", balance = 0.0, index = -1) },
                    onEditCard = { editingCard = it }
                )
                FinanceTab.ADVICE -> DomainAdviceScreen(ReportDomain.FINANCE, resumeTick)
                FinanceTab.MONEY -> if (rows.isEmpty()) {
                    EmptyHint(if (folderAccessible) "还没有记录" else "暂时读不到记录")
                } else {
                    val totals = remember(rows) {
                        rows.fold(0.0 to 0.0) { sum, row ->
                            if (row.type == "支出") sum.copy(first = sum.first + row.amount)
                            else if (row.type == "收入") sum.copy(second = sum.second + row.amount)
                            else sum
                        }
                    }
                    val expenseTotal = totals.first
                    val incomeTotal = totals.second
                    val balance = incomeTotal - expenseTotal

                    Column(Modifier.fillMaxSize()) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                            ) {
                                FinanceSummaryItem(
                                    modifier = Modifier.weight(1f),
                                    label = "累计支出",
                                    amount = expenseTotal,
                                    color = MaterialTheme.colorScheme.error
                                )
                                FinanceSummaryItem(
                                    modifier = Modifier.weight(1f),
                                    label = "累计收入",
                                    amount = incomeTotal,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                FinanceSummaryItem(
                                    modifier = Modifier.weight(1f),
                                    label = "累计结余",
                                    amount = balance,
                                    color = if (balance >= 0) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    }
                                )
                            }
                        }
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(rows, key = { it.index }) { row ->
                                val isExpense = row.type == "支出"
                                val accent = if (isExpense) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { editingRow = row },
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(CategoryIcons.iconFor(row.category), contentDescription = null, tint = accent)
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f)) {
                                            val detail = ExpensePurpose.withoutTag(row.note).ifBlank { row.category }
                                            Text(
                                                detail,
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            val purpose = ExpensePurpose.fromNote(row.note)
                                            val caption = listOfNotNull(purpose, row.category, row.date).joinToString(" · ")
                                            Text(
                                                caption,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Text(
                                            (if (isExpense) "-¥%.2f" else "+¥%.2f").format(row.amount),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = accent
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    editingRow?.let { row ->
        FinanceEditDialog(
            row = row,
            cards = cards,
            onDismiss = { editingRow = null },
            onSave = { updated ->
                editingRow = null
                scope.launch(Dispatchers.IO) {
                    RecordStore(context).updateLedgerRow(folderUri, row, updated)
                    reload()
                }
            },
            onDelete = {
                editingRow = null
                scope.launch(Dispatchers.IO) {
                    RecordStore(context).deleteLedgerRow(folderUri, row)
                    reload()
                }
            }
        )
    }

    editingHolding?.let { holding ->
        HoldingEditDialog(
            holding = holding,
            onDismiss = { editingHolding = null },
            onSave = { updated ->
                editingHolding = null
                scope.launch(Dispatchers.IO) {
                    RecordStore(context).updateHolding(folderUri, holding, updated)
                    reload()
                }
            },
            onDelete = {
                editingHolding = null
                scope.launch(Dispatchers.IO) {
                    RecordStore(context).deleteHolding(folderUri, holding)
                    reload()
                }
            }
        )
    }

    editingCard?.let { card ->
        val isNew = card.index < 0
        CardEditDialog(
            card = card,
            isNew = isNew,
            onDismiss = { editingCard = null },
            onSave = { updated ->
                editingCard = null
                scope.launch(Dispatchers.IO) {
                    if (isNew) {
                        RecordStore(context).addCard(folderUri, updated)
                    } else {
                        RecordStore(context).updateCard(folderUri, card, updated)
                    }
                    reload()
                }
            },
            onDelete = {
                editingCard = null
                scope.launch(Dispatchers.IO) {
                    RecordStore(context).deleteCard(folderUri, card)
                    reload()
                }
            },
            onTransfer = {
                editingCard = null
                transferFromCard = card
            }
        )
    }

    transferFromCard?.let { from ->
        TransferDialog(
            cards = cards,
            fromCard = from,
            onDismiss = { transferFromCard = null },
            onConfirm = { toCard, amount, note ->
                transferFromCard = null
                scope.launch(Dispatchers.IO) {
                    RecordStore(context).recordManualTransfer(folderUri, from.name, toCard.name, amount, note)
                    reload()
                }
            }
        )
    }
}

@Composable
private fun FinanceSummaryItem(
    modifier: Modifier = Modifier,
    label: String,
    amount: Double,
    color: androidx.compose.ui.graphics.Color
) {
    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "¥%.2f".format(amount),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

/** 编辑一笔支出/收入：类型、分类、金额、备注、日期都能改，也能直接删掉这一笔。
 * 分类下拉框只给固定的那份列表选（[Categories]），跟识别时模型能选的范围一致，
 * 不会出现手打一个奇怪分类、图标对不上的情况。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FinanceEditDialog(
    row: LedgerRow,
    cards: List<CardAccount>,
    onDismiss: () -> Unit,
    onSave: (LedgerRow) -> Unit,
    onDelete: () -> Unit
) {
    val isExpense = row.type != "收入"
    var category by remember { mutableStateOf(row.category) }
    var amountText by remember { mutableStateOf(if (row.amount == 0.0) "" else row.amount.toString()) }
    var note by remember { mutableStateOf(ExpensePurpose.withoutTag(row.note)) }
    var purpose by remember { mutableStateOf(ExpensePurpose.fromNote(row.note)) }
    var cardName by remember { mutableStateOf(ExpensePurpose.cardFromNote(row.note)) }
    var date by remember { mutableStateOf(row.date) }
    var categoryMenuExpanded by remember { mutableStateOf(false) }

    val categories = if (isExpense) ScreenIntakeApp.instance.settingsStore.expenseCategories else Categories.INCOME

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
                Text("编辑记录", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                Text(if (isExpense) "消费" else "收入", style = MaterialTheme.typography.labelLarge)

                ExposedDropdownMenuBox(
                    expanded = categoryMenuExpanded,
                    onExpandedChange = { categoryMenuExpanded = it }
                ) {
                    OutlinedTextField(
                        value = category,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("分类") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryMenuExpanded) },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = categoryMenuExpanded,
                        onDismissRequest = { categoryMenuExpanded = false }
                    ) {
                        categories.forEach { c ->
                            DropdownMenuItem(
                                text = { Text(c) },
                                onClick = {
                                    category = c
                                    categoryMenuExpanded = false
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("金额") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )

                if (isExpense) ExpensePurposePicker(purpose) { purpose = it }
                ExpenseCardPicker(cardName, cards) { cardName = it }

                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注") },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = date,
                    onValueChange = { date = it },
                    label = { Text("日期（yyyy-MM-dd 或 yyyy-MM-dd HH:mm）") },
                    modifier = Modifier.fillMaxWidth()
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
                        Button(onClick = {
                            onSave(
                                row.copy(
                                    type = row.type,
                                    category = category,
                                    amount = amountText.toDoubleOrNull() ?: row.amount,
                                    note = ExpensePurpose.withNote(note, if (isExpense) purpose else null, cardName),
                                    date = date.ifBlank { row.date }
                                )
                            )
                        }) { Text("保存") }
                    }
                }
            }
        }
    }
}

/**
 * 持仓子视图：只做展示 + 现取实时价格，不在这里改结构化数据（买卖靠截图/打字记一笔交易，
 * 见 RecordStore.route 的 "trade" 分支自动滚动更新）——点一条持仓弹出的编辑框是给对账用的
 * 兜底修正入口，不是日常操作路径。价格状态由 [FinanceScreen] 持有并传进来，这样「总览」
 * 卡片算总资产时能用上同一份已经取到的价格。
 */
@Composable
private fun HoldingsView(
    holdings: List<Holding>,
    prices: Map<String, Double?>,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onEditHolding: (Holding) -> Unit
) {
    fun keyOf(h: Holding) = "${h.market}:${h.code}"

    if (holdings.isEmpty()) {
        EmptyHint("还没有持仓，买卖一笔股票（截图或者打字描述）就会出现在这里")
        return
    }

    val totalCost = holdings.sumOf { it.shares * it.avgCost }
    val totalValue = holdings.sumOf { h -> (prices[keyOf(h)] ?: h.avgCost) * h.shares }
    val totalPnl = totalValue - totalCost

    Column(Modifier.fillMaxSize()) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    FinanceSummaryItem(
                        modifier = Modifier.weight(1f),
                        label = "成本",
                        amount = totalCost,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FinanceSummaryItem(
                        modifier = Modifier.weight(1f),
                        label = "市值",
                        amount = totalValue,
                        color = MaterialTheme.colorScheme.primary
                    )
                    FinanceSummaryItem(
                        modifier = Modifier.weight(1f),
                        label = "盈亏",
                        amount = totalPnl,
                        color = if (totalPnl >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                }
                OutlinedButton(
                    onClick = onRefresh,
                    enabled = !refreshing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (refreshing) "刷新中…" else "刷新最新价格")
                }
                Text(
                    "价格来自免费的公开行情接口，仅供参考，可能有延迟或偶尔取不到；取不到的" +
                        "标的会先按成本价估算市值，不代表真实盈亏。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(holdings) { h ->
                val price = prices[keyOf(h)]
                val marketValue = (price ?: h.avgCost) * h.shares
                val pnl = marketValue - h.shares * h.avgCost
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onEditHolding(h) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${h.name}（${h.code}）",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "${marketLabel(h.market)} · ${ClassifyResult.fmtNum(h.shares)}股 · 成本¥%.2f".format(h.avgCost),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                if (price != null) "¥%.2f".format(marketValue) else "取价失败",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            if (price != null) {
                                Text(
                                    (if (pnl >= 0) "+¥%.2f" else "¥%.2f").format(pnl),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (pnl >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun marketLabel(market: String): String = when (market) {
    "US" -> "美股"
    "HK" -> "港股"
    else -> "A股"
}

/** 持仓对账用的兜底编辑入口——正常应该靠记一笔买卖交易让持仓自动滚动更新，这里主要是
 * 数据对不上时手动纠正，或者删掉一条不想再跟踪的持仓。 */
@Composable
private fun HoldingEditDialog(
    holding: Holding,
    onDismiss: () -> Unit,
    onSave: (Holding) -> Unit,
    onDelete: () -> Unit
) {
    var name by remember { mutableStateOf(holding.name) }
    var code by remember { mutableStateOf(holding.code) }
    var market by remember { mutableStateOf(holding.market) }
    var sharesText by remember { mutableStateOf(ClassifyResult.fmtNum(holding.shares)) }
    var costText by remember { mutableStateOf(holding.avgCost.toString()) }

    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("编辑持仓", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "一般不需要手动改——正常应该通过记一笔买卖交易让持仓自动更新，这里主要是" +
                        "对账发现数字不对时用来手动纠正。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("A" to "A股", "US" to "美股", "HK" to "港股").forEach { (m, label) ->
                        FilterChip(selected = market == m, onClick = { market = m }, label = { Text(label) })
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("代码") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = sharesText,
                    onValueChange = { sharesText = it },
                    label = { Text("股数") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = costText,
                    onValueChange = { costText = it },
                    label = { Text("平均成本") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
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
                        Button(onClick = {
                            onSave(
                                holding.copy(
                                    name = name.ifBlank { holding.name },
                                    code = code.ifBlank { holding.code },
                                    market = market,
                                    shares = sharesText.toDoubleOrNull() ?: holding.shares,
                                    avgCost = costText.toDoubleOrNull() ?: holding.avgCost
                                )
                            )
                        }) { Text("保存") }
                    }
                }
            }
        }
    }
}

/**
 * 卡片子视图：储蓄卡/信用卡列表，点一条弹出编辑框改名字/类型/余额或者删掉，「添加一张卡」
 * 建一张新的——跟持仓一样，改动立刻整篇重写 卡片.csv 并重新读一遍，不需要单独的
 * "保存"按钮（以前这部分在设置页里，需要改完点最下面的保存才生效，挪过来的时候顺便
 * 统一成跟这个 Tab 其它地方一样"点了就存"的操作习惯）。
 */
@Composable
private fun CardsView(
    cards: List<CardAccount>,
    transfers: List<TransferRow>,
    onAdd: () -> Unit,
    onEditCard: (CardAccount) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(
                "把储蓄卡/信用卡建在这里，以后截图记账时如果能看出用的哪张卡，会自动加减对应" +
                    "的余额。信用卡记的是「欠款余额」——消费增加欠款，还款/退款减少欠款；对不上" +
                    "任何一张卡的交易不受影响，正常记账。信用卡还款、卡与卡之间互转这种自己账户" +
                    "内部挪钱，会自动识别成「转账」，不计入收支，只改这两张卡各自的余额，见下面" +
                    "「最近转账」。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (cards.isEmpty()) {
            item { EmptyHint("还没有卡片，点下面「添加一张卡」建一张") }
        }
        items(cards) { card ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onEditCard(card) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.CreditCard, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            card.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            if (card.type == "信用") "信用卡 · 欠款余额" else "储蓄卡 · 当前余额",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        "¥%.2f".format(card.balance),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (card.type == "信用") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
        item {
            OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("添加一张卡")
            }
        }
        if (transfers.isNotEmpty()) {
            item {
                Text(
                    "最近转账",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            // 文件是按记录时间从旧到新往后追加的，index 越大越新，倒序显示最新的在最上面；
            // 只读留痕，不提供点击编辑/删除（见函数头部注释）。
            items(transfers.sortedByDescending { it.index }.take(20)) { t ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.SwapHoriz, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${t.fromCard} → ${t.toCard}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            val caption = if (t.note.isNotBlank()) "${t.date} · ${t.note}" else t.date
                            Text(
                                caption,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            "¥%.2f".format(t.amount),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/** 新增/编辑一张卡：名字、储蓄/信用类型、余额；card.index < 0 表示还没存过（新增），
 * 这种情况下不显示"删除"按钮（没什么可删的）。余额输入框用本地缓存的文本状态，不直接
 * 从 card.balance 反算字符串，避免打"12."这种中间态被立刻纠正成"12.0"、光标跟着跳——
 * 跟 FinanceEditDialog 里金额输入框用的是同一个套路。 */
@Composable
private fun CardEditDialog(
    card: CardAccount,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (CardAccount) -> Unit,
    onDelete: () -> Unit,
    onTransfer: () -> Unit
) {
    var name by remember { mutableStateOf(card.name) }
    var type by remember { mutableStateOf(card.type.ifBlank { "储蓄" }) }
    var balanceText by remember { mutableStateOf(if (card.balance == 0.0) "" else card.balance.toString()) }

    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    if (isNew) "添加卡片" else "编辑卡片",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("卡名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = type == "储蓄", onClick = { type = "储蓄" }, label = { Text("储蓄卡") })
                    FilterChip(selected = type == "信用", onClick = { type = "信用" }, label = { Text("信用卡") })
                }
                OutlinedTextField(
                    value = balanceText,
                    onValueChange = { balanceText = it },
                    label = { Text(if (type == "信用") "当前欠款" else "当前余额") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    if (!isNew) {
                        // "转账"用的是这张卡已经保存在文件里的名字/状态（不是这个表单里
                        // 还没保存的临时编辑内容）——转账是独立于改名字/改余额之外的另一个
                        // 动作，避免"改了名字但没点保存，又点了转账"这种状态不一致。
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = onTransfer) { Text("转账") }
                            TextButton(onClick = onDelete) {
                                Text("删除", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    } else {
                        Spacer(Modifier)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onDismiss) { Text("取消") }
                        Button(onClick = {
                            onSave(
                                card.copy(
                                    name = name.ifBlank { "新卡片" },
                                    type = type,
                                    balance = balanceText.toDoubleOrNull() ?: card.balance
                                )
                            )
                        }) { Text(if (isNew) "添加" else "保存") }
                    }
                }
            }
        }
    }
}

/** 卡片页"转账"按钮弹出的表单：转出方固定成点了按钮的那张卡（fromCard，显示为纯文本，
 * 不能改——想换一张转出卡就去点那张卡的转账按钮），转入方从其余卡片里下拉选，金额手打。
 * 没有第二张卡可选时（只建过一张卡）直接提示先去添加，不显示表单其余部分——转账天然
 * 需要两张卡，少一张就没法进行。保存后走 [RecordStore.recordManualTransfer]，直接生效，
 * 不经过「待确认」（这是他自己在表单里明确选的，不是模型猜的，参考 CardEditDialog 新增/
 * 编辑卡片同样点了就直接生效的信任级别）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransferDialog(
    cards: List<CardAccount>,
    fromCard: CardAccount,
    onDismiss: () -> Unit,
    onConfirm: (toCard: CardAccount, amount: Double, note: String) -> Unit
) {
    val otherCards = cards.filter { it.index != fromCard.index }
    var toCard by remember { mutableStateOf(otherCards.firstOrNull()) }
    var toMenuExpanded by remember { mutableStateOf(false) }
    var amountText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val amount = amountText.toDoubleOrNull()
    val canSubmit = toCard != null && amount != null && amount > 0.0

    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("转账", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (otherCards.isEmpty()) {
                    Text(
                        "还没有第二张卡——转账需要两张卡，先去「添加一张卡」建一张再来转账。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) { Text("知道了") }
                    }
                } else {
                    OutlinedTextField(
                        value = fromCard.name,
                        onValueChange = {},
                        readOnly = true,
                        enabled = false,
                        label = { Text("转出") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    ExposedDropdownMenuBox(
                        expanded = toMenuExpanded,
                        onExpandedChange = { toMenuExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = toCard?.name.orEmpty(),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("转入") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = toMenuExpanded) },
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = toMenuExpanded,
                            onDismissRequest = { toMenuExpanded = false }
                        ) {
                            otherCards.forEach { c ->
                                DropdownMenuItem(
                                    text = { Text(c.name) },
                                    onClick = {
                                        toCard = c
                                        toMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { amountText = it },
                        label = { Text("金额") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text("备注（可选）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) { Text("取消") }
                        Button(
                            onClick = { toCard?.let { onConfirm(it, amount ?: 0.0, note.trim()) } },
                            enabled = canSubmit
                        ) { Text("确认转账") }
                    }
                }
            }
        }
    }
}
