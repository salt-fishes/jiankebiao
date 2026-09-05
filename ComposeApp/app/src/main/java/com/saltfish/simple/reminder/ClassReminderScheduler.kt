package com.saltfish.simple.reminder

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.saltfish.simple.MainActivity
import com.saltfish.simple.data.AppDatabase
import com.saltfish.simple.data.EntryWithCourse
import com.saltfish.simple.data.SettingsRepository
import com.saltfish.simple.data.TimeUtils
import com.saltfish.simple.data.WeekCalculator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * 课前提醒调度：扫描未来若干天内的下一节课，在其开始前 N 分钟用闹钟触发
 * [ReminderReceiver]；每次触发后由接收器续排下一条，形成链式调度。
 *
 * 权限策略：API 31+ 的 SCHEDULE_EXACT_ALARM 可能未被授予（Android 14 默认拒绝），
 * 此时降级为窗口闹钟（±10 分钟），不引导用户申请额外权限。
 */
object ClassReminderScheduler {

    const val ACTION_FIRE = "com.saltfish.simple.action.FIRE_CLASS_REMINDER"
    const val ACTION_TEST_FIRE = "com.saltfish.simple.action.FIRE_TEST_REMINDER"
    const val CHANNEL_ID = "class_reminder"

    private const val REQUEST_CODE = 2001
    private const val REQUEST_CODE_TEST = 2002
    /** 向前扫描天数上限（覆盖两周课表足够；无候选则不排闹钟）。 */
    private const val HORIZON_DAYS = 28

    data class UpcomingClass(
        val remindAt: LocalDateTime,   // 应提醒时刻（开课前 N 分钟）
        val startAt: LocalDateTime,    // 上课时刻
        val entry: EntryWithCourse,
        val week: Int,
    )

    /** 重排下一条提醒（内部切 IO 线程）。 */
    suspend fun reschedule(context: Context) = withContext(Dispatchers.IO) {
        ensureChannel(context)
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // 先取消旧闹钟再按最新数据/设置重排（同 requestCode 覆盖语义）
        am.cancel(firePendingIntent(context))
        ReminderDiagnostics.recordReschedule(context)
        if (!SettingsRepository.getInstance(context).current.remindEnabled) return@withContext
        findNext(context)?.let { setAlarm(context, it) }
    }

    /**
     * 找到 [from] 之后最近的一节待提醒课程。
     * @param graceSec 容忍已过时刻的秒数（接收器触发时用于匹配刚到的闹钟）。
     */
    suspend fun findNext(
        context: Context,
        from: LocalDateTime = LocalDateTime.now(),
        graceSec: Long = 0,
    ): UpcomingClass? = withContext(Dispatchers.IO) {
        val settings = SettingsRepository.getInstance(context).current
        if (!settings.remindEnabled) return@withContext null
        val entries = AppDatabase.getInstance(context).scheduleDao()
            .getAllEntries(settings.timetableId)
        val lowerBound = from.minusSeconds(graceSec.coerceAtLeast(0))

        var best: UpcomingClass? = null
        var date: LocalDate = from.toLocalDate()
        repeat(HORIZON_DAYS) {
            // 遵循「显示周末」开关：隐藏周末则周末课程不提醒；
            // 开学前（周数 <= 0）不匹配任何课程，开学当天自然从第 1 周开始
            val week = WeekCalculator.currentWeek(settings.semesterStartDate, date)
            val weekendHidden = date.dayOfWeek.value >= 6 && !settings.showWeekend
            if (week >= 1 && !weekendHidden) {
                for (e in entries) {
                    if (e.dayOfWeek != date.dayOfWeek.value || !e.isInWeek(week)) continue
                    val span = TimeUtils.sectionMinutes(settings.sectionTimes, e.startSection ?: continue)
                        ?: continue
                    val start = date.atTime(LocalTime.of(span.first / 60, span.first % 60))
                    val remindAt = start.minusMinutes(settings.remindMinutesBefore.toLong())
                    if (remindAt.isBefore(lowerBound)) continue
                    val cand = UpcomingClass(remindAt, start, e, week)
                    if (best == null || cand.remindAt < best!!.remindAt) best = cand
                }
            }
            date = date.plusDays(1)
        }
        best
    }

    /** 收到闹钟：为刚到点的课程发通知，并为之后的一节续排。 */
    internal fun onFired(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            // 容差 120 秒：窗口闹钟可能略早触发，仍匹配到本次目标课
            val fired = findNext(context, graceSec = 120)
            if (fired != null && Math.abs(java.time.Duration.between(fired.remindAt, LocalDateTime.now()).seconds) <= 300) {
                notifyUpcoming(context, fired, test = false)
                val next = findNext(context, from = fired.startAt.plusSeconds(1))
                if (next == null) reschedule(context) else setAlarm(context, next)
            } else {
                // 数据在排程后发生变化：按最新数据重排
                reschedule(context)
            }
        }
    }

    /**
     * 立即发送一条测试提醒：取下一节即将到来的课按真实文案预览；
     * 无课或学期未开始时用示例文案。用于「我的 → 提醒」自查效果。
     */
    suspend fun fireTest(context: Context) = withContext(Dispatchers.IO) {
        ensureChannel(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return@withContext
        val settings = SettingsRepository.getInstance(context).current
        val entries = AppDatabase.getInstance(context).scheduleDao()
            .getAllEntries(settings.timetableId)
        val now = LocalDateTime.now()
        var best: UpcomingClass? = null
        var date: LocalDate = now.toLocalDate()
        repeat(HORIZON_DAYS) {
            // 开学前（周数 <= 0）不排任何提醒，与小组件行为一致
            val week = WeekCalculator.currentWeek(settings.semesterStartDate, date)
            val weekendHidden = date.dayOfWeek.value >= 6 && !settings.showWeekend
            if (week >= 1 && !weekendHidden) {
                for (e in entries) {
                    if (e.dayOfWeek != date.dayOfWeek.value || !e.isInWeek(week)) continue
                    val span = TimeUtils.sectionMinutes(settings.sectionTimes, e.startSection ?: continue)
                        ?: continue
                    val start = date.atTime(LocalTime.of(span.first / 60, span.first % 60))
                    if (!start.isAfter(now)) continue
                    val cand = UpcomingClass(now, start, e, week)
                    if (best == null || cand.startAt < best!!.startAt) best = cand
                }
            }
            date = date.plusDays(1)
        }
        val up = best
        if (up != null) {
            notifyUpcoming(context, up, test = true)
        } else {
            postNotification(
                context,
                title = "测试提醒：即将上课",
                body = "示例：高等数学 · 今天 08:00 · 未排地点（当前课表无后续课程）",
            )
        }
    }

    private suspend fun setAlarm(context: Context, up: UpcomingClass) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = up.remindAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val pi = firePendingIntent(context)
        val canExact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (canExact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } else {
            // 降级：10 分钟交付窗口，保证基本可用
            am.setWindow(AlarmManager.RTC_WAKEUP, triggerAt, 10 * 60_000L, pi)
        }
    }

    private fun notifyUpcoming(context: Context, up: UpcomingClass, test: Boolean) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannel(nm)
        val span = TimeUtils.sectionMinutes(
            SettingsRepository.getInstance(context).current.sectionTimes,
            up.entry.startSection ?: 1,
        )
        val timeText = span?.let { "今天 ${fmt(it.first)}" } ?: up.entry.sectionRangeLabel
        val body = buildString {
            append(timeText).append(" · ").append(up.entry.shortLocation.ifBlank { "未排地点" })
            if (up.entry.teacher.isNotBlank()) append(" · ").append(up.entry.teacher)
        }
        val title = if (test) "测试提醒：${up.entry.courseName}" else "即将上课：${up.entry.courseName}"
        if (!test) ReminderDiagnostics.recordFired(context)
        postNotification(context, title, body, entryId = up.entry.entryId)
    }

    private fun postNotification(
        context: Context,
        title: String,
        body: String,
        entryId: Long = 0L,
    ) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val pi = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = androidx.core.app.NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_ALARM)
            .build()
        nm.notify(((entryId xor 0x5000L) + body.hashCode()).toInt(), notification)
    }

    private fun firePendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, REQUEST_CODE,
            Intent(context, ReminderReceiver::class.java).setAction(ACTION_FIRE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /**
     * 一分钟测试：真排一个 60 秒后的闹钟（与正式提醒同一交付链路），
     * 用于验证「精确闹钟权限 + 厂商省电策略」是否真的放行——
     * 立即测试只验证通知展示，验证不了闹钟链路。
     */
    suspend fun fireTestInOneMinute(context: Context) = withContext(Dispatchers.IO) {
        ensureChannel(context)
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            context, REQUEST_CODE_TEST,
            Intent(context, ReminderReceiver::class.java).setAction(ACTION_TEST_FIRE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val triggerAt = System.currentTimeMillis() + 60_000L
        val canExact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (canExact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } else {
            am.setWindow(AlarmManager.RTC_WAKEUP, triggerAt, 30_000L, pi)
        }
    }

    /** 一分钟测试闹钟到点：直接发测试通知（不续排、不动正式链）。 */
    internal fun onTestFired(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            postNotification(
                context,
                title = "一分钟测试到达",
                body = "闹钟链路正常。如果这条迟到了很久，说明系统在延迟交付闹钟（省电策略）。",
            )
        }
    }

    internal fun ensureChannel(context: Context) {
        ensureChannel(context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
    }

    private fun ensureChannel(nm: NotificationManager) {
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "上课提醒", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun fmt(minutesOfDay: Int): String =
        "%02d:%02d".format(minutesOfDay / 60, minutesOfDay % 60)
}

/** 数据或设置变化后的统一刷新入口：更新小组件 + 重排提醒。 */
object AppRefresh {
    fun onDataChanged(context: Context) {
        com.saltfish.simple.widget.ScheduleWidgetProvider.requestUpdate(context)
        CoroutineScope(Dispatchers.IO).launch {
            ClassReminderScheduler.reschedule(context)
        }
    }
}
