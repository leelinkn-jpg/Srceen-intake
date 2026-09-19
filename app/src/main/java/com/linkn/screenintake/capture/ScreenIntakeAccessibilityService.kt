package com.linkn.screenintake.capture

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Base64
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference

/**
 * 核心链路都在这一个服务里，一共三种触发方式：
 *  - 同时按下音量上 + 音量下键（[COMBO_WINDOW_MS] 窗口内）：判定为组合键，对当前屏幕拍一张
 *    静默截图（见 [performScreenshotCapture]），交给 [CapturePipeline] 分类归档；
 *  - 单独长按音量下键（超过 [LONG_PRESS_THRESHOLD_MS] 且没有组成组合）：在当前界面正上方
 *    悬浮弹出手动文字快速输入框（[FloatingTextCapture]），不切出正在用的 App，给脑子里
 *    一闪而过、屏幕上并没有出现的想法用；
 *  - 单独长按音量上键（同样超过 [LONG_PRESS_THRESHOLD_MS]）：悬浮弹出一个小的拍照取景窗
 *    （[FloatingCameraCapture]），同样不切出正在用的 App，用来记录现实里的东西（比如一张
 *    纸质发票、白板），走视觉模型识别；
 *  - 除了以上三种，单独短按一个键（松开时既没组成组合、也没到长按阈值）：当成普通音量键
 *    使用，松手那一刻直接调整一次音量（含正常的音量条 UI），跟这个 App 出现之前完全一样；
 *  - 换句话说，长按单独一个音量键不会再像原生系统那样连续调音量了——长按被这个 App 整个
 *    接管去做别的事情，只有干净利落的短按松手，才会触发一次音量调整；
 *  - 不要把两个键一起按住超过 3 秒——那是系统自己保留的「切换无障碍服务」手势，跟这个
 *    App 无关，但物理按键是共享的。
 *
 * 不监听系统通知——只在用户主动按组合键那一刻才动作，避免通知和手动触发重复记一笔，
 * 也避免把用户不想记的通知内容也捞进来。
 *
 * 组合键截屏用的是 AccessibilityService 自带的静默截屏接口（[android.accessibilityservice
 * .AccessibilityService.takeScreenshot]，minSdk 已提到 30 专门为了这个接口），跟音量下+
 * 电源键那种系统级截屏是两回事：没有截屏动画、没有咔嚓声、不会弹"已保存到相册"、不会进
 * 相册或任何文件——拿到的只是内存里的一张位图，转换成 JPEG 字节直接发给视觉模型，函数
 * 调用结束后这张图不会再被任何地方持有。截屏这一步万一失败（比较少见：系统短时间内限频、
 * 内部错误），直接记一笔「待确认」失败痕迹，不做退回文字读取的兜底——用户明确要求过
 * 不用加这层，他自己的使用习惯是画面稳定之后才按键触发。
 */
class ScreenIntakeAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private val audioManager: AudioManager by lazy {
        getSystemService(AUDIO_SERVICE) as AudioManager
    }

    private var volumeUpDownAt = 0L
    private var volumeDownDownAt = 0L
    private var volumeUpHeld = false
    private var volumeDownHeld = false
    private var comboFired = false

    // 这次按下有没有已经被识别成「单独长按」触发过专属动作——用来在松开时判断：
    // 如果已经触发过，松开就什么都不用再做；如果没触发过，松开那一刻才是一次普通短按。
    private var volumeUpLongPressFired = false
    private var volumeDownLongPressFired = false

    // 按下的时候排一个「等到长按阈值就触发专属动作」的任务；如果窗口内识别成组合，或者
    // 提前松手了，就把这个还没执行的任务撤销掉。
    private var pendingLongPressUpRunnable: Runnable? = null
    private var pendingLongPressDownRunnable: Runnable? = null

    private var meetingOverlay: android.widget.TextView? = null

    private fun updateMeetingOverlay(text: String?) {
        val manager = getSystemService(android.view.WindowManager::class.java)
        if (text == null) {
            meetingOverlay?.let { runCatching { manager.removeView(it) } }
            meetingOverlay = null
            return
        }
        if (meetingOverlay == null) {
            val label = android.widget.TextView(this).apply {
                textSize = 12f
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundColor(0xDD263238.toInt())
                setPadding(18, 12, 18, 12)
                contentDescription = "结束会议录音"
                setOnClickListener {
                    startService(android.content.Intent(this@ScreenIntakeAccessibilityService,
                        com.linkn.screenintake.meeting.MeetingRecorderService::class.java)
                        .setAction(com.linkn.screenintake.meeting.MeetingRecorderService.STOP))
                }
            }
            val params = android.view.WindowManager.LayoutParams(
                android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                android.graphics.PixelFormat.TRANSLUCENT).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.END
                x = 12; y = (100 * resources.displayMetrics.density).toInt()
            }
            runCatching { manager.addView(label, params); meetingOverlay = label }
        }
        meetingOverlay?.text = text
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instanceRef = WeakReference(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 不处理任何被动事件——读屏只在组合键按下那一刻主动发起，见 performCapture()。
    }

    override fun onInterrupt() {
        // no-op
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val isVolUp = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP
        val isVolDown = event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        if (!isVolUp && !isVolDown) return super.onKeyEvent(event)

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (isVolUp) volumeUpHeld = true else volumeDownHeld = true

                if (event.repeatCount == 0) {
                    if (isVolUp) volumeUpLongPressFired = false else volumeDownLongPressFired = false
                    val now = event.eventTime
                    if (isVolUp) volumeUpDownAt = now else volumeDownDownAt = now

                    val otherHeld = if (isVolUp) volumeDownHeld else volumeUpHeld
                    val otherDownAt = if (isVolUp) volumeDownDownAt else volumeUpDownAt
                    if (otherHeld && otherDownAt != 0L && now - otherDownAt <= COMBO_WINDOW_MS) {
                        // 识别成组合：撤销两个键各自还没执行的「长按」判定，两个键都不生效
                        comboFired = true
                        pendingLongPressUpRunnable?.let { handler.removeCallbacks(it) }
                        pendingLongPressDownRunnable?.let { handler.removeCallbacks(it) }
                        pendingLongPressUpRunnable = null
                        pendingLongPressDownRunnable = null
                        vibrate(COMBO_VIBRATE_PATTERN)
                        handler.post { Toast.makeText(this, "已触发，识别中…", Toast.LENGTH_SHORT).show() }
                        performCapture()
                    } else {
                        // 单独按下：先不生效，等看是短按松手（照常调音量）还是长按到阈值（触发专属动作）
                        scheduleLongPress(isVolUp)
                    }
                }
                // 长按的自动重复事件（repeatCount > 0）什么都不用做，只需要吞掉，
                // 不再像原生那样连续调音量——长按已经被专属动作接管了。
                return true
            }
            KeyEvent.ACTION_UP -> {
                if (isVolUp) volumeUpHeld = false else volumeDownHeld = false

                val longPressFired = if (isVolUp) volumeUpLongPressFired else volumeDownLongPressFired
                if (!comboFired && !longPressFired) {
                    // 既没组成组合、也没长按到阈值：一次干净的短按，松手这一刻正常调一次音量
                    if (isVolUp) pendingLongPressUpRunnable?.let { handler.removeCallbacks(it) }
                    else pendingLongPressDownRunnable?.let { handler.removeCallbacks(it) }
                    if (isVolUp) pendingLongPressUpRunnable = null else pendingLongPressDownRunnable = null
                    applyVolume(isVolUp)
                }
                if (isVolUp) volumeUpLongPressFired = false else volumeDownLongPressFired = false

                if (!volumeUpHeld && !volumeDownHeld) {
                    // 两个键都松开了，重置状态，准备识别下一次按键
                    comboFired = false
                    volumeUpDownAt = 0L
                    volumeDownDownAt = 0L
                }
                return true
            }
        }
        return super.onKeyEvent(event)
    }

    private fun scheduleLongPress(isVolUp: Boolean) {
        val runnable = Runnable {
            val stillHeld = if (isVolUp) volumeUpHeld else volumeDownHeld
            if (stillHeld && !comboFired) {
                if (isVolUp) volumeUpLongPressFired = true else volumeDownLongPressFired = true
                performLongPressAction(isVolUp)
            }
            if (isVolUp) pendingLongPressUpRunnable = null else pendingLongPressDownRunnable = null
        }
        if (isVolUp) pendingLongPressUpRunnable = runnable else pendingLongPressDownRunnable = runnable
        handler.postDelayed(runnable, LONG_PRESS_THRESHOLD_MS)
    }

    /**
     * 单独长按到阈值：音量上键悬浮弹出拍照小工具，音量下键悬浮弹出手动文字输入框——两个
     * 都是 WindowManager 悬浮窗，不是新开 Activity，当前正在用的 App 不会被切走。
     */
    private fun performLongPressAction(isVolUp: Boolean) {
        vibrate(LONG_PRESS_VIBRATE_PATTERN)
        if (isVolUp) {
            FloatingCameraCapture.show(this)
        } else {
            FloatingTextCapture.show(this)
        }
    }

    private fun applyVolume(isVolUp: Boolean) {
        val direction = if (isVolUp) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        audioManager.adjustSuggestedStreamVolume(
            direction,
            AudioManager.USE_DEFAULT_STREAM_TYPE,
            AudioManager.FLAG_SHOW_UI
        )
    }

    private fun performCapture() {
        // 截屏 + 网络请求都放到协程里做，不卡主线程。
        scope.launch { performScreenshotCapture() }
    }

    /** 对当前屏幕拍一张静默截图，发给视觉模型判断；截屏本身失败就直接记一笔失败痕迹。 */
    private suspend fun performScreenshotCapture() {
        val base64 = captureScreenshotBase64()
        val pipeline = CapturePipeline(this@ScreenIntakeAccessibilityService)
        if (base64 == null) {
            pipeline.recordScreenshotFailure()
            return
        }
        pipeline.classifyScreenshotAndRoute(base64)
    }

    /**
     * 静默截屏，转成 JPEG 再转 base64。拿到的 [ScreenshotResult] 里是一个 HardwareBuffer，
     * 按官方样例的方式转换：`Bitmap.wrapHardwareBuffer` 包一层，再 `copy` 成普通的
     * ARGB_8888 位图（硬件位图不能直接拿去压缩成 JPEG），用完立刻回收，不留任何引用。
     * 失败（系统限频 [TakeScreenshotCallback.onFailure] 之类）时返回 null。
     */
    private suspend fun captureScreenshotBase64(): String? = suspendCancellableCoroutine { cont ->
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        try {
                            val hwBuffer = screenshot.hardwareBuffer
                            val bitmap = Bitmap.wrapHardwareBuffer(hwBuffer, screenshot.colorSpace)
                                ?.copy(Bitmap.Config.ARGB_8888, false)
                            hwBuffer.close()
                            if (bitmap == null) {
                                if (cont.isActive) cont.resume(null, onCancellation = null)
                                return
                            }
                            val stream = ByteArrayOutputStream()
                            bitmap.compress(Bitmap.CompressFormat.JPEG, SCREENSHOT_JPEG_QUALITY, stream)
                            bitmap.recycle()
                            val base64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
                            if (cont.isActive) cont.resume(base64, onCancellation = null)
                        } catch (e: Exception) {
                            Log.e(TAG, "截屏结果转换失败", e)
                            if (cont.isActive) cont.resume(null, onCancellation = null)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.w(TAG, "静默截屏失败，错误码 $errorCode")
                        if (cont.isActive) cont.resume(null, onCancellation = null)
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "发起截屏请求失败", e)
            if (cont.isActive) cont.resume(null, onCancellation = null)
        }
    }

    private fun vibrate(pattern: LongArray) {
        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            val mgr = getSystemService(VibratorManager::class.java)
            mgr?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as? Vibrator
        } ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }

    override fun onDestroy() {
        updateMeetingOverlay(null)
        pendingLongPressUpRunnable?.let { handler.removeCallbacks(it) }
        pendingLongPressDownRunnable?.let { handler.removeCallbacks(it) }
        scope.cancel()
        if (instanceRef?.get() === this) {
            instanceRef = null
        }
        super.onDestroy()
    }

    companion object {
        // 两个音量键之间允许的时间差，超过这个值就不算「同时按」
        private const val COMBO_WINDOW_MS = 150L

        // 单独按住一个键多久，才算「长按」触发专属动作（拍照 / 文字输入），而不是普通音量键；
        // 跟 Android 系统自己的长按判定习惯（约 500ms）保持一致，短按用户几乎不会误触发到这里。
        private const val LONG_PRESS_THRESHOLD_MS = 500L

        // 触发反馈用的几种震动节奏，跟"记录成功/失败"的反馈区分开
        private val COMBO_VIBRATE_PATTERN = longArrayOf(0, 25, 60, 25) // 短促两下：读屏触发
        private val LONG_PRESS_VIBRATE_PATTERN = longArrayOf(0, 40) // 单次稍长：长按专属动作触发

        // JPEG 压缩质量：识别效果优先于体积，截图本来也不落盘，不用太省
        private const val SCREENSHOT_JPEG_QUALITY = 85

        private const val TAG = "ScreenIntakeA11yService"

        private var instanceRef: WeakReference<ScreenIntakeAccessibilityService>? = null

        fun isRunning(): Boolean = instanceRef?.get() != null

        /** 可见录音状态与停止按钮，不修改会议的音频焦点或输出设备。 */
        fun showMeetingRecordingStatus(text: String?) {
            val service = instanceRef?.get() ?: return
            service.handler.post { service.updateMeetingOverlay(text) }
        }

        /** 供设置页里「测试整条链路」按钮调用，截的是当前（也就是本 App）屏幕。 */
        fun requestCapture(): Boolean {
            val service = instanceRef?.get() ?: return false
            service.performCapture()
            return true
        }
    }
}
