package com.linkn.screenintake.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.linkn.screenintake.ScreenIntakeApp
import com.linkn.screenintake.capture.ScreenIntakeAccessibilityService
import com.linkn.screenintake.ui.theme.ScreenIntakeTheme

class MainActivity : ComponentActivity() {

    private val folderChosen = mutableStateOf(false)
    private val accessibilityReady = mutableStateOf(false)
    private val overlayPermissionOk = mutableStateOf(false)

    // 每次回到前台都 +1：单纯用来让「待确认」数量这类不在 folderChosen/accessibilityReady
    // 里的状态，也能在你切回这个 App 时被重新读一次。
    private val resumeTick = mutableStateOf(0)

    // 只用来让"后台 Toast 不被 Android 13+ 静默吞掉"，这个 App 不会真的发通知
    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 拒绝的话，触发/识别结果的 Toast 提示可能不会显示，但震动反馈不受影响 */ }

    // 提前问，免得第一次长按音量上键拍照时才弹权限对话框，体验上突兀
    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 拒绝的话，长按音量上键会再问一次；一直拒绝就用不了拍照这个入口 */ }

    // 待办同步日历要读写日历，两个权限一起问；拒绝的话待办.md 照常写，只是不会同步到日历、
    // 收不到日历的原生提醒
    private val calendarPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* 拒绝也不影响待办.md 本身的记录，CalendarSync 里会自己检查权限、没有就跳过 */ }

    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            ScreenIntakeApp.instance.settingsStore.folderUri = uri.toString()
            folderChosen.value = true
            Toast.makeText(this, "保存位置已设置", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        maybeRequestNotificationPermission()
        maybeRequestCameraPermission()
        maybeRequestCalendarPermission()
        folderChosen.value = ScreenIntakeApp.instance.settingsStore.folderUri.isNotBlank()
        accessibilityReady.value = ScreenIntakeAccessibilityService.isRunning()
        overlayPermissionOk.value = Settings.canDrawOverlays(this)

        setContent {
            ScreenIntakeTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val store = ScreenIntakeApp.instance.settingsStore
                    var showOnboarding by remember { mutableStateOf(!store.onboardingDone) }
                    var showSettings by remember { mutableStateOf(false) }
                    var currentTab by remember { mutableStateOf(BottomTab.FINANCE) }
                    val folderOk by folderChosen
                    val a11yOk by accessibilityReady
                    val overlayOk by overlayPermissionOk
                    val tick by resumeTick

                    when {
                        showOnboarding -> OnboardingScreen(
                            onOpenAccessibility = { openAccessibilitySettings() },
                            onOpenOverlaySettings = { openOverlaySettings() },
                            onDone = {
                                store.onboardingDone = true
                                showOnboarding = false
                            }
                        )
                        showSettings -> {
                            // 设置页是叠在主界面上面的一层状态，不是系统返回栈里单独的一页——
                            // 点左上角的返回箭头会走 onBack 正常回到主界面，但系统自带的返回
                            // 手势（屏幕边缘往里滑）默认不知道这个状态，会直接把整个 Activity
                            // 关掉、退出到桌面，感觉像"设置页把 App 关了"。用 BackHandler 把
                            // 系统返回手势也接管到同一个 onBack，两种返回方式表现一致。
                            BackHandler(onBack = { showSettings = false })
                            SettingsScreen(
                                accessibilityReady = a11yOk,
                                overlayPermissionOk = overlayOk,
                                folderChosen = folderOk,
                                onOpenAccessibility = { openAccessibilitySettings() },
                                onOpenOverlaySettings = { openOverlaySettings() },
                                onPickFolder = { folderPicker.launch(null) },
                                onBack = { showSettings = false }
                            )
                        }
                        else -> MainScaffold(
                            currentTab = currentTab,
                            onTabSelected = { currentTab = it },
                            onOpenSettings = { showSettings = true },
                            resumeTick = tick
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        folderChosen.value = ScreenIntakeApp.instance.settingsStore.folderUri.isNotBlank()
        accessibilityReady.value = ScreenIntakeAccessibilityService.isRunning()
        overlayPermissionOk.value = Settings.canDrawOverlays(this)
        resumeTick.value += 1
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun maybeRequestCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun maybeRequestCalendarPermission() {
        val needsRead = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) !=
            PackageManager.PERMISSION_GRANTED
        val needsWrite = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CALENDAR) !=
            PackageManager.PERMISSION_GRANTED
        if (needsRead || needsWrite) {
            calendarPermission.launch(
                arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
            )
        }
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    // 悬浮窗权限比较特殊：不是普通运行时权限，没法用弹窗直接申请，只能跳到这个专门的
    // 设置页让你手动开一次——长按音量上/下键悬浮弹相机/文字输入框，靠的就是这个权限。
    private fun openOverlaySettings() {
        startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        )
    }
}
