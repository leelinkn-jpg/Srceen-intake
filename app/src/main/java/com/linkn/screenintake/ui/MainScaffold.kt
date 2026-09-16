package com.linkn.screenintake.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.linkn.screenintake.ScreenIntakeApp
import com.linkn.screenintake.store.DataChangeSignal
import com.linkn.screenintake.store.PendingDraft
import com.linkn.screenintake.store.RecordStore
import com.linkn.screenintake.store.UnconfirmedNote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    resumeTick: Int
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var pendingDrafts by remember { mutableStateOf(listOf<PendingDraft>()) }
    var pendingNotes by remember { mutableStateOf(listOf<UnconfirmedNote>()) }
    var pendingLoading by remember { mutableStateOf(true) }

    // 收件箱是不是打开着、打开的时候要不要预先按某个领域筛好——都是叠加在 Tab 之上的
    // 本地状态，见上面类注释。inboxInitialDomain 只在"点某个领域 Tab 的查看全部"这条
    // 路径里被设置，从顶部图标直接点进来时始终是 null（不筛）。
    var showInbox by remember { mutableStateOf(false) }
    var inboxInitialDomain by remember { mutableStateOf<String?>(null) }

    fun openInbox(domain: String? = null) {
        inboxInitialDomain = domain
        showInbox = true
    }

    suspend fun reloadPending() {
        val folderUri = ScreenIntakeApp.instance.settingsStore.folderUri
        val (d, n) = withContext(Dispatchers.IO) {
            try {
                val store = RecordStore(context)
                store.readPendingDrafts(folderUri) to store.readUnconfirmedNotes(folderUri)
            } catch (e: Exception) {
                emptyList<PendingDraft>() to emptyList<UnconfirmedNote>()
            }
        }
        pendingDrafts = d
        pendingNotes = n
        pendingLoading = false
    }

    // App 回到前台（含冷启动）读一次
    LaunchedEffect(resumeTick) { reloadPending() }
    // 打开收件箱时也读一次，确保 App 还在前台、没触发 resumeTick 的情况下
    // （比如截屏识别出新草稿后直接点开收件箱看）也能看到最新的
    LaunchedEffect(showInbox) {
        if (showInbox) reloadPending()
    }
    // 组合键截屏/长按拍照/长按打字这几条捕获路径本来就不把 App 切到后台，人如果正好停在
    // 收件箱里触发一次捕获，前两个 LaunchedEffect 都不会重新触发——跟着 DataChangeSignal
    // 走，RecordStore 真正写完草稿/删掉草稿那一刻就通知到，角标数字和列表内容才能跟文件
    // 真正同步，不用等切走再切回来。
    val pendingChangeTick = DataChangeSignal.tick.value
    LaunchedEffect(pendingChangeTick) { reloadPending() }

    val pendingCount = pendingDrafts.size + pendingNotes.size

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(titleFor(currentTab, showInbox), fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { openInbox(null) }) {
                        if (pendingCount > 0) {
                            BadgedBox(badge = { Badge { Text(pendingCount.toString()) } }) {
                                Icon(Icons.Default.Inbox, contentDescription = "收件箱（$pendingCount 条待确认）")
                            }
                        } else {
                            Icon(Icons.Default.Inbox, contentDescription = "收件箱")
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                BottomTab.values().forEach { tab ->
                    NavigationBarItem(
                        selected = !showInbox && currentTab == tab,
                        onClick = {
                            showInbox = false
                            onTabSelected(tab)
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (showInbox) {
                InboxScreen(
                    resumeTick = resumeTick,
                    drafts = pendingDrafts,
                    notes = pendingNotes,
                    loading = pendingLoading,
                    onReload = { scope.launch { reloadPending() } },
                    initialDomain = inboxInitialDomain
                )
            } else {
                AnimatedContent(
                    targetState = currentTab,
                    modifier = Modifier.fillMaxSize(),
                    label = "bottom-tab-content"
                ) { tab ->
                    when (tab) {
                        BottomTab.FINANCE -> FinanceScreen(resumeTick)
                        BottomTab.HEALTH -> HealthScreen(resumeTick)
                        BottomTab.WORK -> WorkScreen(resumeTick)
                        BottomTab.HABIT -> HabitScreen(resumeTick)
                    }
                }
            }
        }
    }
}

private fun titleFor(tab: BottomTab, showInbox: Boolean): String {
    if (showInbox) return "收件箱"
    return when (tab) {
        BottomTab.FINANCE -> "财务"
        BottomTab.HEALTH -> "健康"
        BottomTab.WORK -> "工作"
        BottomTab.HABIT -> "习惯"
    }
}
