package com.linkn.screenintake.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import com.linkn.screenintake.ScreenIntakeApp
import com.linkn.screenintake.classify.ClassifyResult
import kotlinx.coroutines.launch

/**
 * 处理「待确认」通知上的两个动作：确认原样落盘，或者按编辑框里改过的文字重新落盘。
 * 通知的收起是立刻同步做的，不等后面的文件写入完成——写文件（尤其是账本.csv/待办.md
 * 这种整篇重写的方式）偶尔要几百毫秒，等太久的话系统有时会把「编辑」提交后的通知又还原
 * 成带按钮的原样（看起来像没反应，得再点一次确认才行），所以收通知这一步必须是点了动作
 * 按钮那一刻马上做，落盘再放到后台协程慢慢来。
 *
 * 真正落盘的逻辑挪到了 [CaptureConfirmActions]，跟 App 内「待确认」模块共用——这里只
 * 管通知本身的收起时机、日志、以及 goAsync() 这套 BroadcastReceiver 特有的收尾。
 *
 * 每一步都打了 Log.d，方便用 Logcat 搜 "CaptureConfirmReceiver" 排查——之前这里只有
 * 出异常才会打日志，正常走完一次完全不会留任何痕迹，出问题时没法从日志区分「这个函数
 * 根本没被调用」和「调用了但某一步悄悄没生效」这两种情况。
 */
class CaptureConfirmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "onReceive 收到 action=${intent.action}")

        val draftId = intent.getStringExtra(PendingCaptureNotifier.EXTRA_DRAFT_ID)
        if (draftId == null) {
            Log.w(TAG, "intent 里没有 draftId，直接返回，什么都不做")
            return
        }
        val resultJson = intent.getStringExtra(PendingCaptureNotifier.EXTRA_RESULT_JSON)
        if (resultJson == null) {
            Log.w(TAG, "intent 里没有 resultJson，直接返回，什么都不做")
            return
        }
        val notificationId = intent.getIntExtra(
            PendingCaptureNotifier.EXTRA_NOTIFICATION_ID, draftId.hashCode()
        )
        val original = ClassifyResult.fromJson(resultJson)

        val editedText = if (intent.action == PendingCaptureNotifier.ACTION_EDIT_SUBMIT) {
            RemoteInput.getResultsFromIntent(intent)
                ?.getCharSequence(PendingCaptureNotifier.REMOTE_INPUT_KEY)
                ?.toString()?.trim()?.takeIf { it.isNotBlank() }
        } else {
            null
        }
        Log.d(TAG, "draftId=$draftId notificationId=$notificationId editedText=${editedText ?: "(无编辑)"}")

        val appContext = context.applicationContext

        // 不管点的是「确认」还是「编辑」提交，通知都在这里立刻收掉，不等下面的落盘做完。
        PendingCaptureNotifier.cancel(appContext, notificationId)
        Log.d(TAG, "已调用 cancel(notificationId=$notificationId)（第一次，同步）")

        // SAF 文件写入 / 重新分类都是同步阻塞的，onReceive 本身生命周期很短，用 goAsync()
        // 拿到一个「允许我再多活一会」的许可，把实际工作丢到后台协程做完再 finish()。
        val pendingResult = goAsync()
        ScreenIntakeApp.instance.ioScope.launch {
            try {
                CaptureConfirmActions.confirmOrEdit(appContext, draftId, original, editedText)
                Log.d(TAG, "已落盘/已删除草稿 $draftId，处理完成")
            } catch (e: Exception) {
                Log.e(TAG, "确认/编辑落盘失败", e)
            } finally {
                // 兜底再收一次：万一系统在第一次 cancel() 之后又把通知的 RemoteInput UI
                // 还原回去，这里再收一次——收一个已经不存在的通知 ID 是安全的空操作。
                PendingCaptureNotifier.cancel(appContext, notificationId)
                Log.d(TAG, "已调用 cancel(notificationId=$notificationId)（第二次，兜底）")
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "CaptureConfirmReceiver"
    }
}
