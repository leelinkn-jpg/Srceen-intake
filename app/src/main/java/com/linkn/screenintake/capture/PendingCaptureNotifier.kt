package com.linkn.screenintake.capture

import android.app.NotificationChannel
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.linkn.screenintake.R
import com.linkn.screenintake.ScreenIntakeApp
import com.linkn.screenintake.classify.ClassifyResult
import com.linkn.screenintake.classify.ExpensePurpose
import com.linkn.screenintake.ui.MainActivity

/** 通知只负责带用户回到 App 的完整确认卡，草稿始终先落盘。 */
object PendingCaptureNotifier {
    private const val CHANNEL_ID = "pending_capture"
    const val ACTION_CONFIRM = "com.linkn.screenintake.action.CONFIRM_CAPTURE"
    const val ACTION_SUBMIT = "com.linkn.screenintake.action.SUBMIT_CAPTURE"
    const val ACTION_OPEN_PENDING = "com.linkn.screenintake.action.OPEN_PENDING"
    const val REMOTE_INPUT_KEY = "capture_submit_text"
    const val EXTRA_DRAFT_ID = "draft_id"
    const val EXTRA_RESULT_JSON = "result_json"
    const val EXTRA_SCREEN_TEXT = "screen_text"
    const val EXTRA_NOTIFICATION_ID = "notification_id"

    fun show(context: Context, draftId: String, result: ClassifyResult, screenText: String,
             error: String? = null) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        ensureChannel(context)
        val id = draftId.hashCode()
        val recognized = result.displaySummary()
        val open = PendingIntent.getActivity(context, id,
            Intent(context, MainActivity::class.java).setAction(ACTION_OPEN_PENDING)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(if (error == null) "一笔${result.typeLabel()}待确认" else "未保存 · 请检查")
            .setContentText(if (error == null) "点此打开确认卡" else error)
            .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(false).setOngoing(false)
        builder.addAction(android.R.drawable.ic_menu_edit, "查看并确认", open)
        NotificationManagerCompat.from(context).notify(id, builder.build())
    }

    /** 回复提交后更新同一通知，结束系统行内回复状态，保存成功后才取消。 */
    fun showSaving(context: Context, notificationId: Int) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        ensureChannel(context)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground).setContentTitle("正在保存…")
            .setPriority(NotificationCompat.PRIORITY_LOW).setOnlyAlertOnce(true)
            .setOngoing(false)
        NotificationManagerCompat.from(context).notify(notificationId, builder.build())
    }

    fun cancel(context: Context, notificationId: Int) {
        NotificationManagerCompat.from(context).cancel(notificationId)
    }

    private fun actionIntent(context: Context, action: String, draftId: String, result: ClassifyResult,
                             screenText: String, notificationId: Int) =
        Intent(action, null, context, CaptureConfirmReceiver::class.java).apply {
            putExtra(EXTRA_DRAFT_ID, draftId)
            putExtra(EXTRA_RESULT_JSON, result.toJson())
            putExtra(EXTRA_SCREEN_TEXT, screenText)
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "待确认记录",
                NotificationManager.IMPORTANCE_HIGH).apply {
                description = "识屏后确认或修改；没有待处理记录时不出现"
            })
        }
    }
}
