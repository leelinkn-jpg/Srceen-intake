package com.linkn.screenintake.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.linkn.screenintake.report.ReportDomain

private enum class WorkTab { MEETING, TODO, NOTE, CAPACITY, OVERDUE, RESERVE, VISITS, PLACEMENTS, ADVICE }

/**
 * 工作 Tab：待办/灵感基本上都是工作相关的，所以不像最初设计那样在四个领域 Tab 里各放一块
 * 摘要卡片，而是整个搬到这里、做成跟财务 Tab 收支/持仓/卡片一样的 FilterChip 切换子视图
 * 样式——待办/灵感直接复用 [TodoListScreen]/[NoteListScreen]，用 domainFilter = "工作"
 * 过滤成只看跟工作相关的那部分（如果某条被 AI 分到了别的领域但其实是工作事项，可以在
 * 编辑弹窗里改一下领域标签）。其它领域（财务/健康/习惯，以及"其他"兜底桶）下的待办/灵感
 * 仍然存在，只是不在各自 Tab 里露出，要看全部靠顶部齿轮旁边的"收件箱"图标。
 */
@Composable
fun WorkScreen(resumeTick: Int, meetingOpenTick: Int = 0) {
    var tab by remember(meetingOpenTick) { mutableStateOf(WorkTab.MEETING) }

    Column(Modifier.fillMaxSize()) {
        WeeklyMeetingPlanCard(resumeTick)
        WorkOverviewCard(resumeTick)
        UnifiedSectionTabs(
            labels = listOf("会议", "待办", "灵感", "厂配容", "逾期", "储备", "走访", "投放", "建议"),
            selectedIndex = tab.ordinal,
            onSelected = { tab = WorkTab.entries[it] }
        )

        Box(modifier = Modifier.weight(1f).fillMaxWidth().sectionSwipes(tab.ordinal, WorkTab.entries.size) { tab = WorkTab.entries[it] }) {
            when (tab) {
                WorkTab.MEETING -> MeetingScreen(resumeTick)
                WorkTab.TODO -> TodoListScreen(resumeTick = resumeTick, domainFilter = "工作")
                WorkTab.NOTE -> NoteListScreen(resumeTick = resumeTick, domainFilter = "工作")
                WorkTab.CAPACITY -> WorkListScreen("capacity", resumeTick)
                WorkTab.OVERDUE -> WorkListScreen("overdue", resumeTick)
                WorkTab.RESERVE -> WorkListScreen("reserve", resumeTick)
                WorkTab.VISITS -> WorkListScreen("visits", resumeTick)
                WorkTab.PLACEMENTS -> WorkListScreen("placements", resumeTick)
                WorkTab.ADVICE -> DomainAdviceScreen(ReportDomain.WORK, resumeTick)
            }
        }
    }
}
