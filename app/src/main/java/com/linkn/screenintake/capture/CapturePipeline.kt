package com.linkn.screenintake.capture

import android.content.Context
import android.os.Build
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.widget.Toast
import com.linkn.screenintake.ScreenIntakeApp
import com.linkn.screenintake.classify.ClassifyResult
import com.linkn.screenintake.classify.QwenClassifier
import com.linkn.screenintake.settings.SecureSettingsStore
import com.linkn.screenintake.store.LedgerReader
import com.linkn.screenintake.store.RecordStore
import kotlinx.coroutines.launch

class CapturePipeline(private val context: Context) {

    private fun onIo(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            ScreenIntakeApp.instance.ioScope.launch { block() }
        } else {
            block()
        }
    }

    fun classifyAndRoute(screenText: String, skipConfirmation: Boolean = false) {
        onIo { classifyAndRouteNow(screenText, skipConfirmation) }
    }

    private fun classifyAndRouteNow(screenText: String, skipConfirmation: Boolean) {
        val settings = ScreenIntakeApp.instance.settingsStore
        if (!ensureReady(settings)) return
        if (screenText.isBlank()) {
            toast("没读到屏幕上的文字，换个界面再试")
            vibrate(FAIL_PATTERN)
            saveFailureTrace(
                settings,
                "",
                "捕获于 ${System.currentTimeMillis()}，读到的屏幕文字是空的（无障碍节点里没有可读文字）"
            )
            return
        }
        toast("已上传云端模型，识别中…")
        try {
            val classifier = QwenClassifier(settings.apiKey, settings.classifyRules, currentCardNames(settings))
            val results = classifier.classify(screenText)
            finishWithResults(results, screenText, settings, skipConfirmation)
        } catch (e: Exception) {
            Log.e(TAG, "分类或写入失败", e)
            handleClassifyFailure(settings, screenText, e)
        }
    }

    fun classifyPhotoAndSave(imageBytes: ByteArray) {
        onIo { classifyPhotoAndSaveNow(imageBytes) }
    }

    private fun classifyPhotoAndSaveNow(imageBytes: ByteArray) {
        val settings = ScreenIntakeApp.instance.settingsStore
        if (!ensureReady(settings)) return
        val base64 = android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP)
        toast("已上传云端模型，识别中…")
        var category = "meal"
        var caption: String? = null
        var classifyFailed = false
        var failureReason = ""
        try {
            val classifier = QwenClassifier(settings.apiKey, settings.classifyRules, currentCardNames(settings))
            val result = classifier.classifyMealOrDrink(base64)
            category = result.category
            caption = result.summary
        } catch (e: Exception) {
            Log.e(TAG, "拍照分类失败", e)
            classifyFailed = true
            failureReason = e.message ?: "未知错误"
        }
        try {
            val fileName = RecordStore(context).savePhoto(settings.folderUri, imageBytes, category, caption)
            if (classifyFailed) {
                saveFailureTrace(
                    settings,
                    "",
                    "长按拍照的 AI 分类失败（$failureReason），照片已按饮食存进 日常照片/三餐/$fileName"
                )
                toast("拍照已保存，不过没识别出是吃的还是喝的，先归到了「饮食」")
            } else {
                val label = if (category == "drink") "饮料" else "饮食"
                toast("已记一张$label：${caption ?: fileName}")
            }
            vibrate(OK_PATTERN)
        } catch (e: Exception) {
            Log.e(TAG, "保存照片失败", e)
            toast("保存照片失败：${e.message}")
            vibrate(FAIL_PATTERN)
        }
    }

    fun classifyScreenshotAndRoute(imageBase64: String) {
        onIo { classifyScreenshotAndRouteNow(imageBase64) }
    }

    private fun classifyScreenshotAndRouteNow(imageBase64: String) {
        val settings = ScreenIntakeApp.instance.settingsStore
        if (!ensureReady(settings)) return
        toast("已上传云端模型，识别中…")
        try {
            val classifier = QwenClassifier(settings.apiKey, settings.classifyRules, currentCardNames(settings))
            val results = classifier.classifyScreenshot(imageBase64)
            finishWithResults(results, "（截屏识别，没有屏幕文字，截图没有保留）", settings, skipConfirmation = false)
        } catch (e: Exception) {
            Log.e(TAG, "截屏分类失败", e)
            handleClassifyFailure(settings, "（截屏识别失败，截图没有保留）", e)
        }
    }

    fun recordScreenshotFailure() {
        val settings = ScreenIntakeApp.instance.settingsStore
        if (!ensureReady(settings)) return
        toast("截屏没成功，换个时机再试一次")
        vibrate(FAIL_PATTERN)
        saveFailureTrace(settings, "", "静默截屏没有拿到图片")
    }

    private fun currentCardNames(settings: SecureSettingsStore): List<String> = try {
        LedgerReader.readCards(context, settings.folderUri).map { it.name }
    } catch (e: Exception) {
        emptyList()
    }

    private fun ensureReady(settings: SecureSettingsStore): Boolean {
        if (!settings.isReady()) {
            toast("还没配好：请先在设置里填 API Key、选择保存文件夹")
            vibrate(FAIL_PATTERN)
            return false
        }
        return true
    }

    private fun finishWithResults(
        results: List<ClassifyResult>,
        sourceText: String,
        settings: SecureSettingsStore,
        skipConfirmation: Boolean
    ) {
        val store = RecordStore(context)
        val fresh = results.filter { result ->
            val signature = result.signature()
            signature == null || !RecentCaptureDedup.checkAndRemember(signature)
        }
        if (fresh.isEmpty()) {
            toast("跟刚刚重复，没有再记")
            vibrate(IGNORE_PATTERN)
            return
        }
        if (skipConfirmation) {
            val needsReview = fresh.filter { (it.isTodo && it.dueAt.isNullOrBlank()) || it.isTrade || it.isHolding || it.isTransfer }
            val autoRoute = fresh.filterNot { it in needsReview }
            val messages = autoRoute.map { store.route(settings.folderUri, it) }.toMutableList()
            if (needsReview.isNotEmpty()) {
                val baseId = System.currentTimeMillis()
                needsReview.forEachIndexed { index, result ->
                    val draftId = "${baseId}_review_$index"
                    store.savePendingDraft(settings.folderUri, draftId, result, sourceText)
                    PendingCaptureNotifier.show(context, draftId, result, sourceText)
                }
                messages.add("有内容需要确认，已放进「待确认」")
            }
            toast(messages.joinToString("\n"))
            vibrate(if (fresh.any { it.isIgnore }) IGNORE_PATTERN else OK_PATTERN)
        } else {
            val baseId = System.currentTimeMillis()
            fresh.forEachIndexed { index, result ->
                val draftId = "${baseId}_$index"
                store.savePendingDraft(settings.folderUri, draftId, result, sourceText)
                PendingCaptureNotifier.show(context, draftId, result, sourceText)
            }
            toast(
                if (fresh.size == 1) "识别完成，1 条内容已放进「待确认」"
                else "识别完成，${fresh.size} 条内容已放进「待确认」"
            )
            vibrate(OK_PATTERN)
        }
    }

    private fun handleClassifyFailure(settings: SecureSettingsStore, sourceText: String, e: Exception) {
        saveFailureTrace(settings, sourceText, "捕获于 ${System.currentTimeMillis()}，失败原因：${e.message}")
        vibrate(FAIL_PATTERN)
    }

    private fun saveFailureTrace(settings: SecureSettingsStore, sourceText: String, reason: String) {
        try {
            RecordStore(context).saveUnconfirmed(settings.folderUri, sourceText, reason)
            toast("识别没成功，已把内容存进「待确认」文件夹")
        } catch (inner: Exception) {
            Log.e(TAG, "连兜底保存都失败了", inner)
            toast("捕获失败：$reason")
        }
    }

    private fun toast(message: String) {
        android.os.Handler(context.mainLooper).post {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun vibrate(pattern: LongArray) {
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

    companion object {
        private const val TAG = "CapturePipeline"
        private val OK_PATTERN = longArrayOf(0, 60)
        private val IGNORE_PATTERN = longArrayOf(0, 40, 80, 40)
        private val FAIL_PATTERN = longArrayOf(0, 200)
    }
}
