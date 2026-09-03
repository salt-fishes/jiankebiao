package com.saltfish.simple.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 闹钟触发：发上课提醒通知并续排下一条（链式调度）。 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ClassReminderScheduler.ACTION_FIRE) return
        ClassReminderScheduler.onFired(context)
    }
}
