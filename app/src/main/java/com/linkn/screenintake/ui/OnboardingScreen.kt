package com.linkn.screenintake.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun OnboardingScreen(
    onOpenAccessibility: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onDone: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "欢迎使用「秒记」",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                "三步开启，随手记录",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Text(
                "三种方式随手记：同时按一下音量上+下键，静默截一张当前屏幕（没有截屏动画和提示音，" +
                    "不会保存到相册或任何文件），发给通义千问判断是一笔支出、一笔收入、一件待办、" +
                    "一条灵感还是不值得记的内容，先弹通知等你确认或修改一下，不会直接静默落盘，" +
                    "识别不了的会连原始内容一起存进「待确认」，不会丢；长按音量下键，在当前界面" +
                    "悬浮弹出一个文字输入框，手动打的字直接落盘，不用再确认一遍；长按音量上键，" +
                    "悬浮弹出一个全屏的拍照取景窗，拍完只用 AI 判断一下这是吃的还是喝的、配一句" +
                    "简单描述，自动归到「健康」tab 的饮食/饮料列表，不做热量成分这类更深识别，也" +
                    "不会弹通知等你确认——分错类了直接去列表里手动挪一下。三种都不会把你正在用的" +
                    "App 切走，拍完/写完悬浮窗自己收起来，原来的界面还在原地。待办事项由 App" +
                    " 自己按设定时间提醒，不会写入手机或 Google 日历。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(16.dp)
            )
        }

        StepCard(
            number = 1,
            title = "开启无障碍权限",
            description = "这个权限用来监听音量键，并在触发那一刻对当前屏幕拍一张静默截图——没有" +
                "截屏动画、没有咔嚓声、不会弹\"已保存到相册\"，也不进相册或任何文件，状态栏也不会" +
                "出现录屏图标，不需要常驻通知，截图只在内存里转一下就直接发给模型，发完即弃。",
            buttonLabel = "打开无障碍设置",
            onButtonClick = onOpenAccessibility
        )

        StepCard(
            number = 2,
            title = "开启悬浮窗权限",
            description = "长按音量上/下键弹出的相机小工具、文字输入框，都是悬浮在当前 App 上面画出来的，" +
                "需要「显示在其他应用上层」这个权限——系统设置里手动开一次就行，不开的话这两个" +
                "长按手势会提示你去开，但不影响组合键读屏那个最基本的功能。",
            buttonLabel = "打开悬浮窗设置",
            onButtonClick = onOpenOverlaySettings
        )

        StepCard(
            number = 3,
            title = "填 API Key、选保存文件夹",
            description = "进入应用后点右上角设置。文件夹必须选 Syncthing 同步的「秒记中枢」（桌面那个带财务/健康/工作/成长子目录的），不要选旧的平铺「秒记」。"
        )

        StepCard(
            number = 4,
            title = "相机和通知权限",
            description = "进入应用时会申请相机和通知权限，分别用于拍照记录、待确认通知和待办提醒。"
        )

        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text("我已了解，进入应用")
        }
    }
}

@Composable
private fun StepCard(
    number: Int,
    title: String,
    description: String,
    buttonLabel: String? = null,
    onButtonClick: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        number.toString(),
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (buttonLabel != null && onButtonClick != null) {
                OutlinedButton(onClick = onButtonClick, modifier = Modifier.fillMaxWidth()) {
                    Text(buttonLabel)
                }
            }
        }
    }
}
