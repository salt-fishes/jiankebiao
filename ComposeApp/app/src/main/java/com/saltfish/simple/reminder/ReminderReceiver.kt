package com.saltfish.simple.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 闹钟触发：发上课提醒通知并续排下一条（链式调度）；测试闹钟走独立动作。 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            ClassReminderScheduler.ACTION_FIRE -> ClassReminderScheduler.onFired(context)
            ClassReminderScheduler.ACTION_TEST_FIRE ->
                ClassReminderScheduler.onTestFired(context)
        }
    }
}
