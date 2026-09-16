package com.linkn.screenintake.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Work
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 底部导航四个：按「AI融入生活」的四块领域分——财务、健康、工作、习惯，取代之前按
 * 内容类型分的 财务/待办/灵感/待确认。待办/灵感打上"[域名]"标签后，实际上基本都集中在
 * 工作这一个领域，所以只在 [WorkScreen] 里完整展示（FilterChip 切子视图，跟财务 Tab
 * 收支/持仓/卡片同样式），不在财务/健康/习惯三个 Tab 里重复放；其它领域下的待办/灵感
 * 仍然存在，靠顶部齿轮旁边的"收件箱"图标（[InboxScreen]）能看到全部。原来的"待确认"
 * 审核队列也不再是一个 Tab，挪到了同一个收件箱图标里（见 [MainScaffold]），因为它本质上
 * 是一个跨领域的、AI 拿不准要人工确认的队列，不属于任何一个具体领域。
 * 没有单独的首页——应用打开直接落在财务这个 Tab，设置（运行环境状态、API Key、保存
 * 文件夹、分类偏好、一键自测、使用说明）都收在右上角齿轮按钮里，不占一个 Tab 位置。
 */
enum class BottomTab(val label: String, val icon: ImageVector) {
    FINANCE("财务", Icons.Default.AccountBalanceWallet),
    HEALTH("健康", Icons.Default.Favorite),
    WORK("工作", Icons.Default.Work),
    HABIT("习惯", Icons.Default.CheckCircle)
}
