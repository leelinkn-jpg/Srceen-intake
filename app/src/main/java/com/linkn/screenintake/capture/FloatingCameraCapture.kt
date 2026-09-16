package com.linkn.screenintake.capture

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.linkn.screenintake.ScreenIntakeApp
import kotlinx.coroutines.launch

/**
 * 长按音量上键唤出的拍照界面：同样不开新 Activity，用悬浮窗直接铺满整个屏幕、取景画面
 * 占满全屏，跟正常相机 App 看起来没什么两样，只是它是悬浮在当前 App 上面的悬浮窗，
 * 拍完/取消一收起来，底下的 App 全程没被切走、没有重建。CameraX 的 API 需要一个
 * LifecycleOwner，这里没有 Activity 可用，配一个手动推进状态的 [OverlayLifecycleOwner]：
 * 界面出现时推到 RESUMED，收起来时推到 DESTROYED（CameraX 会在那一刻自动解绑摄像头）。
 *
 * 拍完只做一次很轻量的 AI 判断（"这是吃的还是喝的"外加一句简短描述），不做更深的
 * 现场识别，判断完直接自动存进对应分类文件夹、同步给 Mac，交给以后更强的模型去看
 * 细节（见 [com.linkn.screenintake.capture.CapturePipeline.classifyPhotoAndSave]）。
 */
class FloatingCameraCapture private constructor(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val lifecycleOwner = OverlayLifecycleOwner()
    private var rootView: View? = null
    private var imageCapture: ImageCapture? = null
    private var capturing = false

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    private fun start() {
        val previewView = PreviewView(context)

        // 取景画面直接铺满整个悬浮窗——悬浮窗本身就是全屏大小，效果跟系统相机全屏预览一样。
        val root = FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
        }
        root.addView(previewView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val cancelBtn = ImageButton(context).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            background = GradientDrawable().apply {
                setColor(Color.argb(150, 0, 0, 0))
                shape = GradientDrawable.OVAL
            }
            setColorFilter(Color.WHITE)
            setOnClickListener { dismiss() }
        }
        root.addView(
            cancelBtn,
            FrameLayout.LayoutParams(dp(44), dp(44)).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = dp(16)
                topMargin = dp(40)
            }
        )

        val shutterBtn = ImageButton(context).apply {
            setImageResource(android.R.drawable.ic_menu_camera)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                shape = GradientDrawable.OVAL
            }
            setOnClickListener { takePicture() }
        }
        root.addView(
            shutterBtn,
            FrameLayout.LayoutParams(dp(76), dp(76)).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(48)
            }
        )

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 不抢焦点/键盘：跟底下 App 共存，触摸还是照常只发给悬浮窗自己范围内的控件
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        rootView = root
        windowManager.addView(root, layoutParams)
        lifecycleOwner.start()
        bindCamera(previewView)
    }

    private fun bindCamera(previewView: PreviewView) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                try {
                    val provider = future.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    // 不显式指定 targetRotation 的话，CameraX 拍出来的 JPEG 会按传感器原始
                    // 方向编码，之前拍出来的照片全部逆时针偏了 90 度，就是这个原因——这个
                    // 悬浮窗不是 Activity，没有 Activity 那一套自动跟着屏幕方向重建/回调的
                    // 机制，需要自己在绑相机的时候读一次当前屏幕方向告诉 ImageCapture。
                    // context 这里是无障碍服务的 Service Context，不是 UiContext，不能直接
                    // 调 context.display（Android 会抛 UnsupportedOperationException），
                    // 用 DisplayManager 拿默认屏幕的 rotation 是 Service/Application Context
                    // 下官方认可的取法。
                    val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                    val rotation = displayManager.getDisplay(android.view.Display.DEFAULT_DISPLAY).rotation
                    val capture = ImageCapture.Builder()
                        .setTargetRotation(rotation)
                        .build()
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                    imageCapture = capture
                } catch (e: Exception) {
                    Toast.makeText(context, "相机启动失败：${e.message}", Toast.LENGTH_SHORT).show()
                    dismiss()
                }
            },
            ContextCompat.getMainExecutor(context)
        )
    }

    private fun takePicture() {
        val capture = imageCapture ?: return
        if (capturing) return
        capturing = true
        capture.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val buffer = image.planes[0].buffer
                    val rawBytes = ByteArray(buffer.remaining())
                    buffer.get(rawBytes)
                    image.close()
                    // 有些设备只把方向写进 EXIF 标签、不真的转像素，这里统一摆正成
                    // "像素本身就是正的"，见 [normalizeOrientation]。
                    val bytes = normalizeOrientation(rawBytes)
                    vibrate(context, SUBMIT_PATTERN)
                    ScreenIntakeApp.instance.ioScope.launch {
                        CapturePipeline(ScreenIntakeApp.instance).classifyPhotoAndSave(bytes)
                    }
                    showCapturedFreezeFrame(bytes)
                }

                override fun onError(exception: ImageCaptureException) {
                    capturing = false
                    Toast.makeText(context, "拍照失败：${exception.message}", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    /** 拍完之前是瞬间就收起悬浮窗，缺一个"确实拍到了"的反馈，显得很突兀——现在把刚拍的
     * 这张照片盖在预览画面上停留一小会儿，再收起悬浮窗。停留期间 [capturing] 已经是
     * true，快门不会响应第二次点击，取消按钮虽然还在但半秒钟之后反正也会自动收起，
     * 不用特地处理。 */
    private fun showCapturedFreezeFrame(bytes: ByteArray) {
        // rootView 声明成 View? 是给 dismiss()/removeView 用的松类型，这里需要 addView，
        // 强转回 [show] 里实际创建的那个 FrameLayout——拍照悬浮窗只有这一种根布局，
        // 强转不会失败。
        val root = rootView as? FrameLayout
        if (root == null) {
            dismiss()
            return
        }
        val bitmap = try {
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            null
        }
        if (bitmap != null) {
            val freezeView = android.widget.ImageView(context).apply {
                setImageBitmap(bitmap)
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                // 盖住下面的快门/取消按钮不只是视觉上盖住——顺手把触摸也一起接管，不然
                // 底下的按钮虽然看不见了，手指点在同样位置还是能点到，快门按下去会因为
                // capturing 已经是 true 被直接吞掉，不会有任何提示，跟"点了却什么反应
                // 都没有"一模一样。
                isClickable = true
            }
            root.addView(
                freezeView,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            )
        }
        android.os.Handler(context.mainLooper).postDelayed({ dismiss() }, FREEZE_FRAME_MILLIS)
    }

    /** 有些设备/相机管线拍照时只把方向写进 JPEG 的 EXIF 方向标签里，并不会真的把像素转
     * 过来——大部分看图 App 会自动读这个标签摆正显示，但这个 App 自己画缩略图的地方
     * （[com.linkn.screenintake.store.LedgerReader.loadPhotoThumbnail]）用的是最基础的
     * BitmapFactory，不认这个标签，这里悬浮窗"拍完停留一下"的预览用的也是同一份原始
     * 字节。为了不管在哪个地方看这张照片都是正的，拍完这一下就把 EXIF 方向读出来、
     * 按需要把像素本身转正，之后存的、上传给 AI 判断的、悬浮窗预览的都是同一份已经
     * 摆正的字节，不用依赖任何下游代码再去处理方向。已经是正的（没有旋转标签）就原样
     * 返回，不用白白转一次、也不会把图片重新压缩一遍画质打折。 */
    private fun normalizeOrientation(bytes: ByteArray): ByteArray {
        return try {
            val exif = android.media.ExifInterface(java.io.ByteArrayInputStream(bytes))
            val orientation = exif.getAttributeInt(
                android.media.ExifInterface.TAG_ORIENTATION,
                android.media.ExifInterface.ORIENTATION_NORMAL
            )
            val degrees = when (orientation) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (degrees == 0f) return bytes
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes
            val matrix = android.graphics.Matrix().apply { postRotate(degrees) }
            val rotated = android.graphics.Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
            )
            val output = java.io.ByteArrayOutputStream()
            rotated.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, output)
            output.toByteArray()
        } catch (e: Exception) {
            bytes
        }
    }

    private fun dismiss() {
        rootView?.let { runCatching { windowManager.removeView(it) } }
        rootView = null
        lifecycleOwner.destroy()
    }

    companion object {
        private val SUBMIT_PATTERN = longArrayOf(0, 30)

        // 拍完之后悬浮窗停留多久再收起，见 [showCapturedFreezeFrame]
        private const val FREEZE_FRAME_MILLIS = 500L

        fun show(context: Context) {
            if (!Settings.canDrawOverlays(context)) {
                Toast.makeText(
                    context,
                    "还没开「显示在其他应用上层」权限，没法悬浮弹出相机，先去设置里开一下",
                    Toast.LENGTH_LONG
                ).show()
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return
            }
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED
            ) {
                Toast.makeText(context, "没有相机权限，没法拍照", Toast.LENGTH_SHORT).show()
                return
            }
            FloatingCameraCapture(context).start()
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
}
