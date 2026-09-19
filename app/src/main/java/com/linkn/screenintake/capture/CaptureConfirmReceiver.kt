package com.linkn.screenintake.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import com.linkn.screenintake.ScreenIntakeApp
import com.linkn.screenintake.classify.ClassifyResult
import com.linkn.screenintake.classify.ExpensePurpose
import kotlinx.coroutines.launch

class CaptureConfirmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(PendingCaptureNotifier.ACTION_CONFIRM, PendingCaptureNotifier.ACTION_SUBMIT)) return
        val draftId = intent.getStringExtra(PendingCaptureNotifier.EXTRA_DRAFT_ID) ?: return
        val original = runCatching {
            ClassifyResult.fromJson(intent.getStringExtra(PendingCaptureNotifier.EXTRA_RESULT_JSON) ?: return)
        }.getOrNull() ?: return
        val id = intent.getIntExtra(PendingCaptureNotifier.EXTRA_NOTIFICATION_ID, draftId.hashCode())
        val screenText = intent.getStringExtra(PendingCaptureNotifier.EXTRA_SCREEN_TEXT).orEmpty()
        val submitted = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(PendingCaptureNotifier.REMOTE_INPUT_KEY)?.toString()?.trim().orEmpty()
        // 空的修改提交不能意外地变成「确认」。兼容升级前通知上的原样摘要候选。
        if (intent.action == PendingCaptureNotifier.ACTION_SUBMIT && submitted.isEmpty()) return
        val editedText = submitted.takeIf {
            intent.action == PendingCaptureNotifier.ACTION_SUBMIT && it != original.displaySummary().trim()
        }
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        ScreenIntakeApp.instance.ioScope.launch {
            try {
                PendingCaptureNotifier.showSaving(appContext, id)
                if (editedText != null && (original.isExpense || original.isIncome)) {
                    val updated = ExpensePurpose.edit(original, editedText,
                        ScreenIntakeApp.instance.settingsStore.expensePurposes)
                    CaptureConfirmActions.confirmOrEdit(appContext, draftId, updated, null)
                } else {
                    CaptureConfirmActions.confirmOrEdit(appContext, draftId, original, editedText)
                }
                PendingCaptureNotifier.cancel(appContext, id)
            } catch (e: Exception) {
                Log.e("CaptureConfirmReceiver", "确认失败，保留草稿", e)
                runCatching {
                    PendingCaptureNotifier.show(appContext, draftId, original, screenText,
                        e.message ?: "保存失败，请到待确认重试")
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
