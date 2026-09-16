package com.linkn.screenintake.capture

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.linkn.screenintake.R
import com.linkn.screenintake.classify.ClassifyResult

/**
 * 分类结果需要过一遍你的眼睛时，弹这一条通知：平时没有任何通知，只有真的有一条待确认才
 * 出现，点了「确认」或者在「编辑」里提交之后立刻消失——不是那种一直挂着显示"运行中"的
 * 常驻通知。手滑把通知直接划掉也不会丢内容，草稿还在「待确认」文件夹里（见
 * [RecordStore.savePendingDraft]），只是不会再有通知提醒你回去处理。
 */
object PendingCaptureNotifier {

    private const val CHANNEL_ID = "pending_capture"

    const val ACTION_CONFIRM = "com.linkn.screenintake.action.CONFIRM_CAPTURE"
    const val ACTION_EDIT_SUBMIT = "com.linkn.screenintake.action.EDIT_SUBMIT_CAPTURE"
    const val EXTRA_DRAFT_ID = "draft_id"
    const val EXTRA_RESULT_JSON = "result_json"
    const val EXTRA_SCREEN_TEXT = "screen_text"
    const val EXTRA_NOTIFICATION_ID = "notification_id"
    const val REMOTE_INPUT_KEY = "edited_text"

    fun show(context: Context, draftId: String, result: ClassifyResult, screenText: String) {
        ensureChannel(context)
        val notificationId = draftId.hashCode()

        // 待办事项必须有具体提醒时间——通知栏没法弹日期/时间选择器，所以这种情况下
        // 不出「确认」这个一键放行的按钮，只留「编辑」，逼着要么在回复里把时间说清楚
        // （会被当成全新内容重新分类一遍，见 CaptureConfirmActions），要么打开 App
        // 到「待确认」里用编辑弹窗的时间选择器补上，跟 App 内列表（PendingScreen 的
        // DraftCard）的限制保持一致，不能靠通知栏绕过去。
        val needsDueAt = result.isTodo && result.dueAt.isNullOrBlank()

        val remoteInput = RemoteInput.Builder(REMOTE_INPUT_KEY)
            .setLabel(result.editHint())
            .build()
        val editPending = PendingIntent.getBroadcast(
            context,
            notificationId + 1,
            actionIntent(context, ACTION_EDIT_SUBMIT, draftId, result, screenText, notificationId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        val editAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_edit, "编辑", editPending
        ).addRemoteInput(remoteInput).build()

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("待确认 · ${result.typeLabel()}")
            .setContentText(result.displaySummary())
            .setStyle(NotificationCompat.BigTextStyle().bigText(result.displaySummary()))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setOngoing(false)

        if (!needsDueAt) {
            val confirmPending = PendingIntent.getBroadcast(
                context,
                notificationId,
                actionIntent(context, ACTION_CONFIRM, draftId, result, screenText, notificationId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val confirmAction = NotificationCompat.Action.Builder(
                android.R.drawable.checkbox_on_background, "确认", confirmPending
            ).build()
            builder.addAction(confirmAction)
        }
        builder.addAction(editAction)

        NotificationManagerCompat.from(context).notify(notificationId, builder.build())
    }

    fun cancel(context: Context, notificationId: Int) {
        NotificationManagerCompat.from(context).cancel(notificationId)
    }

    private fun actionIntent(
        context: Context,
        action: String,
        draftId: String,
        result: ClassifyResult,
        screenText: String,
        notificationId: Int
    ): Intent = Intent(action, null, context, CaptureConfirmReceiver::class.java).apply {
        putExtra(EXTRA_DRAFT_ID, draftId)
        putExtra(EXTRA_RESULT_JSON, result.toJson())
        putExtra(EXTRA_SCREEN_TEXT, screenText)
        putExtra(EXTRA_NOTIFICATION_ID, notificationId)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID, "待确认记录", NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "读屏 / 拍照识别出的内容，等你点确认或编辑；不用的时候不会出现"
                }
                manager.createNotificationChannel(channel)
            }
        }
    }
}
