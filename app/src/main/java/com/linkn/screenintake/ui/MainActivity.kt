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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.linkn.screenintake.ScreenIntakeApp
import com.linkn.screenintake.capture.ScreenIntakeAccessibilityService
import com.linkn.screenintake.capture.PendingCaptureNotifier
import com.linkn.screenintake.meeting.MeetingRecorderService
import com.linkn.screenintake.report.AiReportNotificationWorker
import com.linkn.screenintake.store.HubRoot
import com.linkn.screenintake.store.SyncNotificationWorker
import com.linkn.screenintake.store.LocalDataIndexWorker
import com.linkn.screenintake.ui.theme.ScreenIntakeTheme
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private val folderChosen = mutableStateOf(false)
    private val accessibilityReady = mutableStateOf(false)
    private val overlayPermissionOk = mutableStateOf(false)

    // 每次回到前台都 +1：单纯用来让「待确认」数量这类不在 folderChosen/accessibilityReady
    // 里的状态，也能在你切回这个 App 时被重新读一次。
    private val resumeTick = mutableStateOf(0)
    private val pendingOpenTick = mutableStateOf(0)
    private val meetingOpenTick = mutableStateOf(0)
    private val reportOpenTick = mutableStateOf(0)

    // Android 13+ 的确认通知、AI 报告通知和待办提醒都需要这个权限。
    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 拒绝的话，触发/识别结果的 Toast 提示可能不会显示，但震动反馈不受影响 */ }

    // 提前问，免得第一次长按音量上键拍照时才弹权限对话框，体验上突兀
    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 拒绝的话，长按音量上键会再问一次；一直拒绝就用不了拍照这个入口 */ }

    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            ScreenIntakeApp.instance.settingsStore.folderUri = uri.toString()
            LocalDataIndexWorker.refresh(this)
            folderChosen.value = true
            Toast.makeText(this, "保存位置已设置", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    SyncNotificationWorker.scanNow(this@MainActivity)
                    AiReportNotificationWorker.scanNow(this@MainActivity)
                    delay(30_000)
                }
            }
        }
        if (intent?.action == PendingCaptureNotifier.ACTION_OPEN_PENDING) pendingOpenTick.value += 1
        if (intent?.action == MeetingRecorderService.OPEN_MEETINGS) meetingOpenTick.value += 1
        if (intent?.action == AiReportNotificationWorker.OPEN_REPORTS) reportOpenTick.value += 1
        maybeBindTestHub(intent)
        maybeRequestNotificationPermission()
        maybeRequestCameraPermission()
        folderChosen.value = ScreenIntakeApp.instance.settingsStore.folderUri.isNotBlank()
        accessibilityReady.value = ScreenIntakeAccessibilityService.isRunning()
        overlayPermissionOk.value = Settings.canDrawOverlays(this)

        setContent {
            ScreenIntakeTheme {
                var snapshotReady by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.linkn.screenintake.store.UiSnapshot.load(applicationContext, ScreenIntakeApp.instance.settingsStore.folderUri)
                    }
                    snapshotReady = true
                }
                if (!snapshotReady) {
                    androidx.compose.material3.LinearProgressIndicator()
                    return@ScreenIntakeTheme
                }
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val store = ScreenIntakeApp.instance.settingsStore
                    var showOnboarding by rememberSaveable { mutableStateOf(!store.onboardingDone) }
                    var showSettings by rememberSaveable { mutableStateOf(false) }
                    var currentTab by rememberSaveable { mutableStateOf(BottomTab.WORK) }
                    val folderOk by folderChosen
                    val a11yOk by accessibilityReady
                    val overlayOk by overlayPermissionOk
                    val tick by resumeTick
                    val openPending by pendingOpenTick
                    val openMeeting by meetingOpenTick
                    val openReport by reportOpenTick
                    LaunchedEffect(openMeeting) {
                        if (openMeeting > 0) { showSettings = false; currentTab = BottomTab.WORK }
                    }
                    LaunchedEffect(openPending) {
                        if (openPending > 0) showSettings = false
                    }

                    if (showOnboarding) {
                        OnboardingScreen(
                            onOpenAccessibility = { openAccessibilitySettings() },
                            onOpenOverlaySettings = { openOverlaySettings() },
                            onDone = {
                                store.onboardingDone = true
                                showOnboarding = false
                            }
                        )
                    } else AnimatedContent(
                        targetState = showSettings,
                        modifier = Modifier.fillMaxSize(),
                        transitionSpec = {
                            if (targetState) {
                                (fadeIn(tween(220)) + slideInHorizontally(tween(220)) { it / 10 }) togetherWith
                                    (fadeOut(tween(150)) + slideOutHorizontally(tween(150)) { -it / 16 })
                            } else {
                                (fadeIn(tween(220)) + slideInHorizontally(tween(220)) { -it / 12 }) togetherWith
                                    (fadeOut(tween(150)) + slideOutHorizontally(tween(150)) { it / 18 })
                            }
                        },
                        label = "settings-page-content"
                    ) { settingsVisible ->
                        if (settingsVisible) {
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
                        } else MainScaffold(
                            currentTab = currentTab,
                            onTabSelected = { currentTab = it },
                            onOpenSettings = { showSettings = true },
                            resumeTick = tick,
                            pendingOpenTick = openPending,
                            meetingOpenTick = openMeeting,
                            reportOpenTick = openReport
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == PendingCaptureNotifier.ACTION_OPEN_PENDING) pendingOpenTick.value += 1
        if (intent.action == MeetingRecorderService.OPEN_MEETINGS) meetingOpenTick.value += 1
        if (intent.action == AiReportNotificationWorker.OPEN_REPORTS) reportOpenTick.value += 1
        maybeBindTestHub(intent)
    }

    override fun onResume() {
        super.onResume()
        com.linkn.screenintake.store.StorageLayout.invalidateExternalHandles(
            ScreenIntakeApp.instance.settingsStore.folderUri
        )
        folderChosen.value = ScreenIntakeApp.instance.settingsStore.folderUri.isNotBlank()
        accessibilityReady.value = ScreenIntakeAccessibilityService.isRunning()
        overlayPermissionOk.value = Settings.canDrawOverlays(this)
        resumeTick.value += 1
        // Syncthing 可能在 App 退到后台时刚把报告写进来。回到前台立即扫一次，避免必须
        // 等下一轮 15 分钟后台任务；后台仍由周期任务负责发现新报告并发通知。
        AiReportNotificationWorker.scanNow(this)
        SyncNotificationWorker.scanNow(this)
    }


    private fun maybeBindTestHub(intent: Intent?) {
        if (intent?.action != HubRoot.ACTION_BIND_TEST_HUB) return
        val path = intent.getStringExtra(HubRoot.EXTRA_PATH)?.trim().orEmpty().ifBlank { null }
        val copyFrom = intent.getStringExtra(HubRoot.EXTRA_COPY_FROM)?.trim().orEmpty().ifBlank { null }
        val debug = getSharedPreferences("adb_debug", MODE_PRIVATE)
        HubRoot.bindForTest(this, path, copyFrom).onSuccess { result ->
            val ok = ScreenIntakeApp.instance.settingsStore.setFolderUriCommit(result.uri)
            com.linkn.screenintake.store.SystemStatus.success(this, "读取")
            LocalDataIndexWorker.refresh(this)
            folderChosen.value = true
            resumeTick.value += 1
            val readable = com.linkn.screenintake.store.LedgerReader.folderAccessible(this, result.uri)
            debug.edit()
                .putString("last_bind_path", result.absolutePath)
                .putString("last_bind_uri", result.uri)
                .putString("last_bind_mode", result.mode)
                .putString("last_bind_tried", result.tried.joinToString("|").take(500))
                .putBoolean("last_bind_ok", true)
                .putBoolean("prefs_commit_ok", ok)
                .putBoolean("folder_accessible", readable)
                .putString("last_bind_error", "")
                .putLong("last_bind_at", System.currentTimeMillis())
                .commit()
            Toast.makeText(
                this,
                "测试同步根已绑定\n" + result.absolutePath + "\nmode=" + result.mode + " 可读=" + readable,
                Toast.LENGTH_LONG
            ).show()
        }.onFailure { err ->
            debug.edit()
                .putString("last_bind_path", path ?: copyFrom ?: "")
                .putBoolean("last_bind_ok", false)
                .putString("last_bind_error", err.message ?: "unknown")
                .putLong("last_bind_at", System.currentTimeMillis())
                .commit()
            Toast.makeText(this, "绑定失败: " + (err.message ?: "unknown"), Toast.LENGTH_LONG).show()
        }
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
