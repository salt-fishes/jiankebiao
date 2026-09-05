package com.saltfish.simple.reminder

import android.app.NotificationManager
import android.content.Context
import android.os.Build

/**
 * 提醒链路诊断快照：全部取自本机状态查询 + 两个本地持久化时间戳，
 * 不涉及网络。用途：「课程提醒」页的「提醒运行证据」面板——本地 app 无处报障，
 * 用户可据此自查「提醒为什么不响」。
 */
object ReminderDiagnostics {

    private const val PREFS = "reminder_diagnostics"
    private const val KEY_LAST_RESCHEDULE = "last_reschedule_at"
    private const val KEY_LAST_FIRED = "last_fired_at"

    /** 诊断面板一行数据。 */
    data class Row(val label: String, val value: String, val ok: Boolean? = null)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 数据或设置变化触发重排后调用。 */
    fun recordReschedule(context: Context) {
        prefs(context).edit()
            .putLong(KEY_LAST_RESCHEDULE, System.currentTimeMillis())
            .apply()
    }

    /** 真实提醒通知发出后调用（测试通知不计入）。 */
    fun recordFired(context: Context) {
        prefs(context).edit()
            .putLong(KEY_LAST_FIRED, System.currentTimeMillis())
            .apply()
    }

    private fun fmtTime(millis: Long): String =
        if (millis <= 0L) "尚未记录"
        else java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.CHINA)
            .format(java.util.Date(millis))

    /** 通知总开关（用户是否在系统层面关掉了本应用的通知）。 */
    fun notificationsEnabled(context: Context): Boolean =
        androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** 渠道状态描述（开关 + 是否有声）。 */
    fun channelSummary(context: Context): String {
        if (Build.VERSION.SDK_INT < 26) return "已创建"
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = nm.getNotificationChannel(ClassReminderScheduler.CHANNEL_ID)
            ?: return "未创建"
        val sound = if (ch.sound != null) "有声音" else "无声"
        return when (ch.importance) {
            NotificationManager.IMPORTANCE_NONE -> "已被系统关闭"
            else -> "已开启 · $sound"
        }
    }

    /** 闹钟交付方式：精确 / 近似（±10 分钟窗口）。 */
    fun alarmMode(context: Context): String {
        if (Build.VERSION.SDK_INT < 31) return "精确"
        val am = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        return if (am.canScheduleExactAlarms()) "精确" else "近似（±10 分钟）"
    }

    fun alarmExact(context: Context): Boolean = alarmMode(context) == "精确"

    /** 组装「提醒运行证据」面板行。nextLine 为「下一节」预告文案（调用方异步算好传入）。 */
    fun snapshot(context: Context, nextLine: String): List<Row> = buildList {
        val notifOk = notificationsEnabled(context)
        add(Row("通知总开关", if (notifOk) "已开启" else "已关闭", notifOk))
        val channel = channelSummary(context)
        add(Row("课程提醒渠道", channel, "已关闭" !in channel && "未创建" !in channel))
        val exact = alarmExact(context)
        add(Row("闹钟方式", alarmMode(context), exact))
        add(
            Row(
                "最近重排",
                fmtTime(prefs(context).getLong(KEY_LAST_RESCHEDULE, 0L)),
            )
        )
        add(
            Row(
                "最近触发",
                fmtTime(prefs(context).getLong(KEY_LAST_FIRED, 0L)),
            )
        )
        add(Row("下一节预告", nextLine))
    }
}
