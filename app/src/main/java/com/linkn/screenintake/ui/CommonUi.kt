package com.linkn.screenintake.ui

import android.graphics.Bitmap
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collectLatest

/**
 * 各 Tab 共用的极简空状态占位。单独拆成一个文件是因为 Kotlin 顶层 `private` 只在
 * 同一个文件内可见——2026-09-16 把原来的 DataScreens.kt 按 Tab 拆开后，这个函数被
 * 财务/待办灵感/健康/习惯好几个文件用到，必须改成 `internal`、挪到独立文件里，不能
 * 继续留在某一个 Tab 自己的文件里当 private。
 */
@Composable
internal fun EmptyHint(text: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Shared visual language for every time-based record: icon, main fact, context, then action. */
// A single rhythm for ledger, health, meeting and growth records.  Keeping the
// list gap separate from card padding prevents each screen from feeling like it
// uses a different density.
internal val RecordListContentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
internal val RecordListSpacing = 4.dp

@Composable
internal fun RecordRowCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    detail: String? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    footer: @Composable (() -> Unit)? = null
) {
    val cardModifier = Modifier.fillMaxWidth().then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick))
    Card(cardModifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) { Icon(icon, contentDescription = null, tint = iconTint) }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    detail?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                trailing?.invoke()
            }
            footer?.invoke()
        }
    }
}

/** 四个一级领域页面共用的二级页签，保证位置、字号和选中反馈完全一致。 */
@Composable
internal fun UnifiedSectionTabs(
    labels: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    pagerState: PagerState? = null
) {
    val indicatorColor = MaterialTheme.colorScheme.primary
    ScrollableTabRow(
        selectedTabIndex = selectedIndex,
        modifier = Modifier.fillMaxWidth(),
        edgePadding = 0.dp,
        // The Canvas occupies the tab-row indicator layer, but only paints a 3dp
        // line at its bottom.  Unlike a fixed tab indicator, its position is
        // interpolated from the pager's live drag offset.
        indicator = { positions ->
            if (positions.isNotEmpty()) {
                val current = (pagerState?.currentPage ?: selectedIndex)
                    .coerceIn(0, positions.lastIndex)
                val offset = pagerState?.currentPageOffsetFraction ?: 0f
                val target = (current + if (offset >= 0f) 1 else -1)
                    .coerceIn(0, positions.lastIndex)
                val progress = kotlin.math.abs(offset).coerceIn(0f, 1f)
                val left = lerp(positions[current].left, positions[target].left, progress)
                val width = lerp(positions[current].width, positions[target].width, progress)
                Canvas(Modifier.fillMaxSize()) {
                    val lineHeight = 3.dp.toPx()
                    drawRect(
                        color = indicatorColor,
                        topLeft = Offset(left.toPx(), size.height - lineHeight),
                        size = Size(width.toPx(), lineHeight)
                    )
                }
            }
        }
    ) {
        labels.forEachIndexed { index, label ->
            Tab(
                selected = selectedIndex == index,
                onClick = { onSelected(index) },
                text = { Text(label, maxLines = 1, style = MaterialTheme.typography.titleSmall) }
            )
        }
    }
}

/**
 * A real pager instead of a "gesture ends -> switch page" shortcut. The page and the
 * tab indicator now travel with the finger; only a settled page changes the caller's state.
 */
@Composable
internal fun rememberSyncedSectionPagerState(
    selectedIndex: Int,
    pageCount: Int,
    onSelected: (Int) -> Unit
): PagerState {
    val state = rememberPagerState(initialPage = selectedIndex, pageCount = { pageCount })
    val latestSelect by rememberUpdatedState(onSelected)
    LaunchedEffect(selectedIndex, pageCount) {
        if (selectedIndex in 0 until pageCount && state.currentPage != selectedIndex) {
            state.animateScrollToPage(selectedIndex)
        }
    }
    LaunchedEffect(state) {
        snapshotFlow { state.settledPage }.collectLatest { page ->
            if (page in 0 until pageCount) latestSelect(page)
        }
    }
    return state
}

@Composable
internal fun SectionPager(
    state: PagerState,
    modifier: Modifier = Modifier,
    content: @Composable (Int) -> Unit
) {
    HorizontalPager(state = state, modifier = modifier, beyondViewportPageCount = 1) { page -> content(page) }
}
