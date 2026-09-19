package com.linkn.screenintake.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.linkn.screenintake.ScreenIntakeApp
import com.linkn.screenintake.store.DataChangeSignal
import com.linkn.screenintake.store.PendingDraft
import com.linkn.screenintake.store.RecordStore
import com.linkn.screenintake.store.UnconfirmedNote
import com.linkn.screenintake.store.UiDataCache
import com.linkn.screenintake.report.AiReport
import com.linkn.screenintake.report.AiReportRepository
import com.linkn.screenintake.report.ReportChangeSignal
import com.linkn.screenintake.work.WorkChangeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class SecondaryPage(val title: String) {
    REPORTS("AI 报告"), PENDING("待确认"), TODOS("全部待办"), NOTES("全部灵感")
}

/**
 * 四个 Tab（财务/健康/工作/习惯，按「AI融入生活」的四块领域分，见 [BottomTab] 头部注释）
 * 共用的外壳：顶部标题随 Tab 变化，右上角两个图标——一个「收件箱」（原来的「待确认」，
 * 现在是跨领域的、有几条没处理时会冒数字小红点的收件箱，见 [InboxScreen]），一个齿轮
 * 设置，不管停在哪个 Tab 都能直接用，没有单独的首页。
 *
 * 「待确认」的草稿/失败记录、以及待办/灵感的领域筛选状态，都在这里统一管理：草稿数据
 * 在这里读取（App 回到前台时读一次、打开收件箱时也读一次），跟角标数字共用同一份，往下
 * 传给 [InboxScreen]——原来角标和内容各自单独去读一遍「待确认」文件夹，数据来源不一致
 * 不说，每次打开都要重新读一遍才有内容，读取慢的时候（比如经第三方网盘同步的目录）就会
 * 有一瞬间看着像没内容，容易被当成坏了。现在只读一次共用，而且读到新数据前继续显示上
 * 一次读到的内容，不会突然清空成空白。
 *
 * showInbox 是本地状态，不是 BottomTab 的第五个值——收件箱本质上跟四个领域 Tab 不是同一
 * 层级的东西（它是"审核队列 + 待办/灵感总入口"，不是某个具体领域），所以用叠加在 Tab
 * 内容上面的一层来表示，而不是让 BottomTab 变成 5 个值；点底部导航任何一个 Tab 都会顺带
 * 把它关掉，回到正常的领域内容。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScaffold(
    currentTab: BottomTab,
    onTabSelected: (BottomTab) -> Unit,
    onOpenSettings: () -> Unit,
    resumeTick: Int,
    pendingOpenTick: Int = 0,
    meetingOpenTick: Int = 0,
    reportOpenTick: Int = 0
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var pendingDrafts by remember { mutableStateOf(UiDataCache.pendingDrafts) }
    var pendingNotes by remember { mutableStateOf(UiDataCache.pendingNotes) }
    var workChanges by remember { mutableStateOf(UiDataCache.workChanges) }
    var pendingLoading by remember { mutableStateOf(UiDataCache.pendingDrafts.isEmpty() && UiDataCache.pendingNotes.isEmpty()) }
    var reports by remember { mutableStateOf(UiDataCache.reports) }

    var secondaryPageName by rememberSaveable { mutableStateOf("") }
    val secondaryPage = SecondaryPage.entries.firstOrNull { it.name == secondaryPageName }
    fun openSecondary(page: SecondaryPage) { secondaryPageName = page.name }
    LaunchedEffect(reportOpenTick) { if (reportOpenTick > 0) openSecondary(SecondaryPage.REPORTS) }
    LaunchedEffect(meetingOpenTick) {
        if (meetingOpenTick > 0) secondaryPageName = ""
    }
    LaunchedEffect(pendingOpenTick) {
        if (pendingOpenTick > 0) openSecondary(SecondaryPage.PENDING)
    }

    suspend fun reloadPending() {
        val folderUri = ScreenIntakeApp.instance.settingsStore.folderUri
        val (d, n, changes) = withContext(Dispatchers.IO) {
            try {
                val store = RecordStore(context)
                Triple(store.readPendingDrafts(folderUri), store.readUnconfirmedNotes(folderUri), WorkChangeRepository(context).pending(folderUri))
            } catch (e: Exception) {
                Triple(emptyList<PendingDraft>(), emptyList<UnconfirmedNote>(), emptyList())
            }
        }
        pendingDrafts = d
        pendingNotes = n
        workChanges = changes
        UiDataCache.pendingDrafts = d
        UiDataCache.pendingNotes = n
        UiDataCache.workChanges = changes
        pendingLoading = false
    }

    suspend fun reloadReports() {
        val folderUri = ScreenIntakeApp.instance.settingsStore.folderUri
        reports = withContext(Dispatchers.IO) { AiReportRepository(context).list(folderUri) }
            .also { UiDataCache.reports = it }
    }

    // App 回到前台（含冷启动）读一次
    LaunchedEffect(resumeTick) { reloadPending() }
    val reportChangeTick = ReportChangeSignal.tick.value
    LaunchedEffect(resumeTick, reportChangeTick) { reloadReports() }
    // 打开收件箱时也读一次，确保 App 还在前台、没触发 resumeTick 的情况下
    // （比如截屏识别出新草稿后直接点开收件箱看）也能看到最新的
    LaunchedEffect(secondaryPage) {
        if (secondaryPage == SecondaryPage.PENDING) reloadPending()
    }
    // 组合键截屏/长按拍照/长按打字这几条捕获路径本来就不把 App 切到后台，人如果正好停在
    // 收件箱里触发一次捕获，前两个 LaunchedEffect 都不会重新触发——跟着 DataChangeSignal
    // 走，RecordStore 真正写完草稿/删掉草稿那一刻就通知到，角标数字和列表内容才能跟文件
    // 真正同步，不用等切走再切回来。
    val pendingChangeTick = DataChangeSignal.tick.value
    LaunchedEffect(pendingChangeTick) { reloadPending() }

    val pendingCount = pendingDrafts.size + pendingNotes.size + workChanges.size
    val secondaryTitle = secondaryPage?.title

    fun closeSecondaryPage() {
        secondaryPageName = ""
    }
    BackHandler(enabled = secondaryTitle != null) { closeSecondaryPage() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        secondaryTitle ?: titleFor(currentTab),
                        fontWeight = if (secondaryTitle == null) FontWeight.Bold else FontWeight.Normal
                    )
                },
                navigationIcon = {
                    if (secondaryTitle != null) {
                        IconButton(onClick = { closeSecondaryPage() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
                actions = {
                    if (secondaryTitle == null) {
                        IconButton(onClick = { openSecondary(SecondaryPage.REPORTS) }, modifier = Modifier.size(48.dp)) {
                            val unread = AiReportRepository(context).unreadCount(reports)
                            if (unread > 0) BadgedBox(badge = { Badge { Text(unread.toString()) } }) {
                                Icon(Icons.Default.Assessment, contentDescription = "AI 报告（$unread 条未读）")
                            } else Icon(Icons.Default.Assessment, contentDescription = "AI 报告")
                        }
                        IconButton(onClick = { openSecondary(SecondaryPage.PENDING) }, modifier = Modifier.size(48.dp)) {
                            if (pendingCount > 0) {
                                BadgedBox(badge = { Badge { Text(pendingCount.toString()) } }) {
                                    Icon(Icons.Default.Inbox, contentDescription = "待确认（$pendingCount 条）")
                                }
                            } else {
                                Icon(Icons.Default.Inbox, contentDescription = "待确认")
                            }
                        }
                        IconButton(onClick = { openSecondary(SecondaryPage.TODOS) }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Default.Checklist, contentDescription = "全部待办", modifier = Modifier.size(24.dp))
                        }
                        IconButton(onClick = { openSecondary(SecondaryPage.NOTES) }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Default.Lightbulb, contentDescription = "全部灵感", modifier = Modifier.size(24.dp))
                        }
                        IconButton(onClick = onOpenSettings, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Default.Settings, contentDescription = "设置", modifier = Modifier.size(24.dp))
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (secondaryTitle == null) {
                NavigationBar {
                    BottomTab.values().forEach { tab ->
                        NavigationBarItem(
                            selected = currentTab == tab,
                            onClick = { onTabSelected(tab) },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            AnimatedContent(
                targetState = secondaryPage,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    // Match Settings exactly: secondary pages enter from the
                    // right, and returning home reverses the same motion.
                    if (targetState != null) {
                        (fadeIn(tween(220)) + slideInHorizontally(tween(220)) { it / 10 }) togetherWith
                            (fadeOut(tween(150)) + slideOutHorizontally(tween(150)) { -it / 16 })
                    } else {
                        (fadeIn(tween(220)) + slideInHorizontally(tween(220)) { -it / 12 }) togetherWith
                            (fadeOut(tween(150)) + slideOutHorizontally(tween(150)) { it / 18 })
                    }
                },
                label = "secondary-page-content"
            ) { page ->
                when (page) {
                    SecondaryPage.REPORTS -> AiReportCenterScreen(resumeTick = resumeTick)
                    SecondaryPage.PENDING -> PendingScreen(
                        drafts = pendingDrafts,
                        notes = pendingNotes,
                        workChanges = workChanges,
                        loading = pendingLoading,
                        onReload = { scope.launch { reloadPending() } }
                    )
                    SecondaryPage.TODOS -> TodoListScreen(resumeTick = resumeTick)
                    SecondaryPage.NOTES -> NoteListScreen(resumeTick = resumeTick)
                    null -> AnimatedContent(
                        targetState = currentTab,
                        modifier = Modifier.fillMaxSize(),
                        transitionSpec = {
                            (fadeIn(animationSpec = tween(180)) + slideInHorizontally(animationSpec = tween(180)) { it / 16 }) togetherWith
                                (fadeOut(animationSpec = tween(120)) + slideOutHorizontally(animationSpec = tween(120)) { -it / 20 })
                        },
                        label = "bottom-tab-content"
                    ) { tab ->
                        when (tab) {
                            BottomTab.FINANCE -> FinanceScreen(resumeTick)
                            BottomTab.HEALTH -> HealthScreen(resumeTick)
                            BottomTab.WORK -> WorkScreen(resumeTick, meetingOpenTick)
                            BottomTab.HABIT -> GrowthEffortScreen(resumeTick)
                        }
                    }
                }
            }
        }
    }
}

private fun titleFor(tab: BottomTab): String {
    return when (tab) {
        BottomTab.FINANCE -> "财务"
        BottomTab.HEALTH -> "健康"
        BottomTab.WORK -> "工作"
        BottomTab.HABIT -> "成长"
    }
}
