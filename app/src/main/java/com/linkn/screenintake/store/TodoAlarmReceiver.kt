package com.linkn.screenintake.store

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class TodoAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when {
            intent.action == EveningHealthReminderWorker.ACTION_EVENING_HEALTH -> {
                EveningHealthReminderWorker.deliverFromAlarm(context)
            }
            intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED ||
                intent.action == "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED" -> {
                TodoReminderWorker.restore(context)
                EveningHealthReminderWorker.schedule(context)
            }
            else -> intent.data?.lastPathSegment?.let { TodoReminderWorker.deliver(context, it) }
        }
    }
}
