package com.linkn.screenintake.capture

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.linkn.screenintake.ScreenIntakeApp
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * 长按音量下键唤出的快速文字输入：不再新开一个 Activity、不会把当前 App 切到后台——直接
 * 用 WindowManager 在悬浮窗里画一个输入框，浮在你正在用的那个 App 上面，输完提交，悬浮窗
 * 自己关掉，底下的 App 全程没被切走、没有重建、滚动位置什么的都还在原地。
 *
 * 需要「显示在其他应用上层」这个特殊权限（系统设置里手动开一次，不是普通的运行时权限
 * 弹窗），没开的话会提示一下 + 跳转到对应设置页，不会退回到旧的开 Activity 那种方案。
 *
 * 卡片故意贴在屏幕靠上的位置（而不是正中间）——这样不管键盘多高、横屏还是竖屏，
 * 键盘只会盖住屏幕下半部分，卡片本身永远露在外面，不用去猜键盘弹起来到底有多高。
 * 另外给输入框加了 IME_FLAG_NO_EXTRACT_UI，避免横屏时输入法自己切到那种铺满全屏、
 * 看不见卡片本身的「全屏编辑」模式。
 */
object FloatingTextCapture {

    private val SUBMIT_PATTERN = longArrayOf(0, 30)

    fun show(context: Context) {
        if (!Settings.canDrawOverlays(context)) {
            promptOverlayPermission(context)
            return
        }

        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        var overlayView: View? = null

        fun dismiss() {
            overlayView?.let { runCatching { windowManager.removeView(it) } }
            overlayView = null
        }

        fun submit(text: String) {
            if (text.isNotBlank()) {
                vibrate(context, SUBMIT_PATTERN)
                ScreenIntakeApp.instance.ioScope.launch {
                    CapturePipeline(ScreenIntakeApp.instance).classifyAndRoute(text, skipConfirmation = true)
                }
            }
            dismiss()
        }

        // 半透明背景铺满全屏，点空白处 = 取消，跟点系统对话框外面一样
        val scrim = FrameLayout(context).apply {
            setBackgroundColor(Color.argb(115, 0, 0, 0))
            setOnClickListener { dismiss() }
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(16).toFloat()
            }
            isClickable = true // 吞掉点击，不让它冒泡到 scrim 触发取消
        }

        val title = TextView(context).apply {
            text = "秒记一笔"
            textSize = 17f
            setTextColor(Color.parseColor("#1B1B1B"))
        }

        val input = EditText(context).apply {
            hint = "记点什么，比如「明天下午三点交材料」"
            minLines = 2
            maxLines = 5
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(Color.parseColor("#1B1B1B"))
            setHintTextColor(Color.parseColor("#9E9E9E"))
            // 避免横屏时输入法把自己切到铺满全屏的「全屏编辑」模式，导致看不到卡片
            imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        }

        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val cancelBtn = TextView(context).apply {
            text = "取消"
            textSize = 15f
            setTextColor(Color.parseColor("#757575"))
            setPadding(dp(16), dp(10), dp(16), dp(10))
            isClickable = true
            setOnClickListener { dismiss() }
        }
        val saveBtn = TextView(context).apply {
            text = "保存"
            textSize = 15f
            setTextColor(Color.parseColor("#1B5E4A"))
            setPadding(dp(16), dp(10), dp(16), dp(10))
            isClickable = true
            setOnClickListener { submit(input.text.toString()) }
        }
        buttonRow.addView(cancelBtn)
        buttonRow.addView(saveBtn)

        card.addView(title)
        card.addView(
            input,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12)
                bottomMargin = dp(8)
            }
        )
        card.addView(buttonRow)

        // 卡片宽度封顶，不管横屏还是竖屏都不会被拉得太宽太扁；横屏时屏幕更宽，
        // 这里保证卡片还是舒服的宽度而不是铺满整个横向空间。
        val screenWidthPx = context.resources.displayMetrics.widthPixels
        val cardWidthPx = min(screenWidthPx - dp(56), dp(400))

        val cardParams = FrameLayout.LayoutParams(
            cardWidthPx,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            // 贴在屏幕上方而不是居中：键盘弹出来只会挡住下半屏，卡片固定在上面就永远露在外面，
            // 不用去判断键盘到底有多高、也不受横竖屏切换影响。
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = dp(96)
        }
        scrim.addView(card, cardParams)

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        overlayView = scrim
        windowManager.addView(scrim, layoutParams)

        input.requestFocus()
        input.post {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun promptOverlayPermission(context: Context) {
        Toast.makeText(
            context,
            "还没开「显示在其他应用上层」权限，没法悬浮弹出输入框，先去设置里开一下",
            Toast.LENGTH_LONG
        ).show()
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun vibrate(context: Context, pattern: LongArray) {
        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }
}
