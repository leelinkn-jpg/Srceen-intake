package com.linkn.screenintake.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.linkn.screenintake.ScreenIntakeApp
import com.linkn.screenintake.capture.ScreenIntakeAccessibilityService
import com.linkn.screenintake.classify.ExpensePurpose

/**
 * 设置页现在是首页拆掉之后唯一的「杂物间」：运行环境状态（无障碍服务/悬浮窗权限/保存
 * 文件夹/API Key 四项打没打勾）、实际的编辑表单（API Key、文件夹、分类偏好）、
 * 一键自测按钮、使用说明——都在这一页，从任意 Tab 右上角的齿轮点进来。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    accessibilityReady: Boolean,
    overlayPermissionOk: Boolean,
    folderChosen: Boolean,
    onOpenAccessibility: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onPickFolder: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val store = ScreenIntakeApp.instance.settingsStore

    var apiKey by remember { mutableStateOf(store.apiKey) }
    var rules by remember { mutableStateOf(store.classifyRules) }
    val apiKeySet = apiKey.isNotBlank()

    fun persist() {
        store.apiKey = apiKey
        store.classifyRules = rules
    }

    BackHandler {
        persist()
        onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = {
                        persist()
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SystemStatusCard()
            val readyCount = listOf(accessibilityReady, overlayPermissionOk, folderChosen, apiKeySet).count { it }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "运行环境（$readyCount/4）",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "四项都打勾之后，随手一按就能记录",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            StatusRow(Icons.Default.Accessibility, "无障碍服务", "音量组合键触发 + 静默截屏", accessibilityReady) {
                OutlinedButton(onClick = onOpenAccessibility, modifier = Modifier.fillMaxWidth()) {
                    Text(if (accessibilityReady) "已开启，点这里可管理" else "去无障碍设置里开启")
                }
            }
            StatusRow(Icons.Default.PictureInPicture, "悬浮窗权限", "长按音量键弹相机/文字输入", overlayPermissionOk) {
                OutlinedButton(onClick = onOpenOverlaySettings, modifier = Modifier.fillMaxWidth()) {
                    Text(if (overlayPermissionOk) "已开启，点这里可管理" else "去设置里开启")
                }
            }
            StatusRow(Icons.Default.Folder, "秒记同步文件夹", "自动按财务、健康、工作、成长归档", folderChosen) {
                OutlinedButton(onClick = onPickFolder, modifier = Modifier.fillMaxWidth()) {
                    Text(if (folderChosen) "已选择，点这里更换" else "选一个会同步到电脑的文件夹")
                }
            }
            StatusRow(Icons.Default.Key, "API Key", "用来调用模型判断分类", apiKeySet) {
                Text(
                    if (apiKeySet) "已填写，下面可以改" else "还没填，下面填一个",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SectionCard(icon = Icons.Default.Key, title = "通义千问 API Key") {
                Text(
                    "阿里云百炼",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("sk-...") },
                    placeholder = { Text("去 bailian.console.aliyun.com 申请") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )
                Text(
                    "加密保存在本机（EncryptedSharedPreferences，AES-256），只用来调用" +
                        "阿里云百炼的通义千问接口，充值支持支付宝/微信支付。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            ExpensePurposeSettings()
            ExpenseCategorySettings()

            SectionCard(icon = Icons.Default.Rule, title = "分类偏好") {
                Text(
                    "用大白话写你的规则，每次判断都会带给模型参考。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = rules,
                    onValueChange = { rules = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    placeholder = { Text("比如：跟客户吃饭算人情，不算餐饮") }
                )
            }

            Button(
                onClick = {
                    persist()
                    Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("保存")
            }

            val version = remember(context) {
                runCatching {
                    val info = context.packageManager.getPackageInfo(context.packageName, 0)
                    "版本 ${info.versionName}（构建 ${info.longVersionCode}）"
                }.getOrDefault("版本信息不可用")
            }
            Text(version, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            OutlinedButton(
                onClick = {
                    if (!accessibilityReady) {
                        Toast.makeText(context, "先在上面开启无障碍服务", Toast.LENGTH_SHORT).show()
                    } else {
                        ScreenIntakeAccessibilityService.requestCapture()
                        Toast.makeText(context, "已触发，正在截屏识别", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("测试整条链路（截的是本页面）")
            }

            UsageTips()
        }
    }
}

@Composable
private fun ExpensePurposeSettings() {
    val store = ScreenIntakeApp.instance.settingsStore
    var purposes by remember { mutableStateOf(store.expensePurposes) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var name by remember { mutableStateOf("") }

    fun save(values: List<String>) {
        store.expensePurposes = values
        purposes = store.expensePurposes
    }

    SectionCard(icon = Icons.Default.Rule, title = "消费用途") {
        Text("咖啡归餐饮，用途只标记因公或为谁花钱。普通消费可以不选。",
            style = MaterialTheme.typography.bodySmall)
        purposes.forEachIndexed { index, purpose ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(purpose, modifier = Modifier.weight(1f))
                TextButton(onClick = { name = purpose; editingIndex = index }) { Text("编辑") }
                TextButton(onClick = { save(purposes.filterIndexed { i, _ -> i != index }) }) { Text("删除") }
            }
        }
        OutlinedButton(onClick = { name = ""; editingIndex = -1 }) { Text("添加用途") }
        Text("修改立即保存，只影响以后可选的用途，不改历史记录。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    editingIndex?.let { index ->
        val trimmed = name.trim()
        val duplicate = purposes.filterIndexed { i, _ -> i != index }.contains(trimmed)
        val valid = ExpensePurpose.valid(trimmed) && !duplicate
        AlertDialog(
            onDismissRequest = { editingIndex = null },
            title = { Text(if (index < 0) "添加消费用途" else "编辑消费用途") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text("用途名称") }, placeholder = { Text("例如：因公、给老婆") },
                    isError = name.isNotEmpty() && !valid,
                    supportingText = { Text(if (duplicate) "这个用途已存在" else "1–20 个字，不含【】") })
            },
            confirmButton = {
                TextButton(enabled = valid, onClick = {
                    save(if (index < 0) purposes + trimmed else purposes.mapIndexed { i, value ->
                        if (i == index) trimmed else value
                    })
                    editingIndex = null
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editingIndex = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun ExpenseCategorySettings() {
    val store = ScreenIntakeApp.instance.settingsStore
    var categories by remember { mutableStateOf(store.expenseCategories) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var name by remember { mutableStateOf("") }

    fun save(values: List<String>) {
        store.expenseCategories = values
        categories = store.expenseCategories
    }

    SectionCard(icon = Icons.Default.Rule, title = "消费类别") {
        Text("类别回答“买了什么”，例如餐饮、交通；用途回答“为什么/为谁花”。两套选项可分别维护。",
            style = MaterialTheme.typography.bodySmall)
        categories.forEachIndexed { index, category ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(category, modifier = Modifier.weight(1f))
                TextButton(onClick = { name = category; editingIndex = index }) { Text("编辑") }
                TextButton(onClick = { save(categories.filterIndexed { i, _ -> i != index }) }, enabled = categories.size > 1) { Text("删除") }
            }
        }
        OutlinedButton(onClick = { name = ""; editingIndex = -1 }) { Text("添加类别") }
        Text("修改立即保存，只影响以后可选的类别，不改历史记录。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    editingIndex?.let { index ->
        val trimmed = name.trim()
        val duplicate = categories.filterIndexed { i, _ -> i != index }.contains(trimmed)
        val valid = ExpensePurpose.valid(trimmed) && !duplicate
        AlertDialog(
            onDismissRequest = { editingIndex = null },
            title = { Text(if (index < 0) "添加消费类别" else "编辑消费类别") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
                label = { Text("类别名称") }, placeholder = { Text("例如：餐饮、交通") },
                isError = name.isNotEmpty() && !valid,
                supportingText = { Text(if (duplicate) "这个类别已存在" else "1–20 个字，不含【】") }) },
            confirmButton = { TextButton(enabled = valid, onClick = {
                save(if (index < 0) categories + trimmed else categories.mapIndexed { i, value -> if (i == index) trimmed else value })
                editingIndex = null
            }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { editingIndex = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun SectionCard(
    icon: ImageVector,
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            content()
        }
    }
}

@Composable
private fun StatusRow(
    icon: ImageVector,
    label: String,
    caption: String,
    ok: Boolean,
    action: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        caption,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                StatusBadge(ok)
            }
            action()
        }
    }
}

@Composable
private fun StatusBadge(ok: Boolean) {
    val bg = if (ok) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
    val fg = if (ok) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
    Card(colors = CardDefaults.cardColors(containerColor = bg)) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (ok) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.width(14.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(if (ok) "已就绪" else "待开启", style = MaterialTheme.typography.labelSmall, color = fg)
        }
    }
}

@Composable
private fun UsageTips() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("使用方式", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            val tips = listOf(
                "上面几项都打勾之后，任意界面同时按一下音量上+音量下键即可静默截一张屏发去识别。",
                "两键差不多同时按下就行，不用刻意按很久；不会弹音量条，也不会真的改变音量。",
                "触发的一瞬间会先震两短下、弹一个「已触发，正在截屏识别」的提示，让你知道按对了。",
                "长按音量下键（不跟上键组合）会在当前界面悬浮弹出一个文字输入框，随手记点什么，写完提交，悬浮窗自己收起来，不会把你正在用的 App 切走。",
                "长按音量上键会在当前界面悬浮弹出一个全屏的拍照取景窗，拍完只用 AI 判断一下这是吃的还是喝的、配一句简单描述，自动存进同步文件夹「健康/日常照片」下的三餐/饮料子目录，「健康」tab 里能看到，不做热量成分这类更深识别，同样不会切走当前 App。",
                "这两个悬浮小工具需要「显示在其他应用上层」权限，没开的话长按会提示你去开一次，不影响组合键截屏这个最基本的功能。",
                "短按任意一个音量键，还是跟这个 App 出现之前完全一样，正常调节音量。",
                "截屏识别完不会直接落盘，会弹一条通知等你确认或编辑——通知平时不出现，点确认或者编辑提交之后立刻消失，不会常驻；手动打的字除外，直接写，不用再确认一遍；长按拍照的照片也直接存，不弹通知，但 AI 判断的吃的/喝的分错了可以去「健康」tab 对应列表里手动改归类。",
                "通知被手滑划掉也不会丢：草稿还在同步文件夹的「系统/待确认」子目录里，几个 Tab 上方也会提示还有几条没处理。",
                "识别不了的（网络错误、截屏失败等）也会把原因存进「待确认」，不会静默丢失。",
                "支出和收入都会自动分类（比如餐饮、交通、工资），分类只在固定的列表里选，「财务」这个 Tab 里能直接看到整体收支和每一笔明细。",
                "待办分为工作和生活两类，必须设置具体提醒时间；提醒由秒记 App 自己发送，不会写入手机或 Google 日历。"
            )
            tips.forEach { tip ->
                Row {
                    Text(
                        "•",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        tip,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
