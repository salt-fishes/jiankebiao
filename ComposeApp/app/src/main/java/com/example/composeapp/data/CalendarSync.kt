package com.example.composeapp.data

import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.provider.CalendarContract
import java.time.LocalDate
import java.time.ZoneId

/**
 * 课表 → 系统日历直接写入（CalendarProvider）。
 *
 * 华为等国产日历 App 普遍没有 .ics 导入入口，因此同步不走文件，直接把事件
 * 写进系统日历数据库：用户打开日历 App 即见课程。
 *
 * 策略要点：
 * - 只写专属「简课表」日历（找不到就创建 ACCOUNT_TYPE_LOCAL 本地账户日历），
 *   绝不碰用户其他日历——重复同步前按 CALENDAR_ID 清空旧事件，保证幂等且不误删；
 * - 事件展开逻辑与 CalendarExport.buildIcs 同源：逐周成独立事件（兼容性最好）；
 * - 需 READ/WRITE_CALENDAR 运行时权限（调用方负责申请）。
 */
object CalendarSync {

    private const val ACCOUNT_NAME = "jiankebiao.local"
    private const val CAL_DISPLAY_NAME = "简课表"
    private const val TZ_SH = "Asia/Shanghai"
    private val ZONE = ZoneId.of(TZ_SH)

    /** 一条待写入的系统日历事件。 */
    data class EventSpec(
        val startMillis: Long,
        val endMillis: Long,
        val title: String,
        val location: String?,
        val description: String?,
    )

    /** 与 .ics 导出同源的逐周展开；remindMinutes 由写入方统一作为提醒规则。 */
    fun buildEventSpecs(
        entries: List<EntryWithCourse>,
        sectionTimes: List<SectionTime>,
        semesterStart: LocalDate,
        totalWeeks: Int,
    ): List<EventSpec> {
        val specs = mutableListOf<EventSpec>()
        for (e in entries) {
            val startSection = e.startSection ?: 1
            val endSection = e.endSection ?: startSection
            val startT = sectionTimes.firstOrNull { it.section == startSection } ?: continue
            val endT = sectionTimes.firstOrNull { it.section == endSection } ?: startT
            val weeks = e.weeksCsv.split(',')
                .mapNotNull { it.trim().toIntOrNull() }
                .filter { it in 1..totalWeeks }
                .distinct().sorted()
            val title = e.courseName + when (e.type) {
                "讲课" -> "★"; "实验" -> "○"; "上机" -> "●"; "实践" -> "◇"; else -> ""
            }
            val location = listOf(e.campus, e.building, e.room)
                .filter { it.isNotBlank() }.joinToString(" ").ifBlank { null }
            val description = buildString {
                if (e.teacher.isNotBlank()) append("教师：${e.teacher}")
                if (e.credit.isNotBlank()) append(if (isEmpty()) "" else "；").append("学分：${e.credit}")
            }.ifBlank { null }
            for (week in weeks) {
                val date = WeekCalculator.mondayOfWeek(semesterStart, week)
                    .plusDays((e.dayOfWeek - 1).toLong())
                val start = date.atTime(startT.start).atZone(ZONE)
                // 结束节时间早于开始节（脏数据）时用开始时间 +30 分钟，与 .ics 一致
                val endDateTime =
                    if (endT.end > startT.start) date.atTime(endT.end) else date.atTime(startT.start).plusMinutes(30)
                specs += EventSpec(
                    startMillis = start.toInstant().toEpochMilli(),
                    endMillis = endDateTime.atZone(ZONE).toInstant().toEpochMilli(),
                    title = title,
                    location = location,
                    description = description,
                )
            }
        }
        return specs.sortedBy { it.startMillis }
    }

    /**
     * 写入系统日历：先清空「简课表」日历旧事件再批量插入，返回写入条数。
     * 失败抛出携带用户可读信息的异常（调用方展示 Snackbar）。
     */
    fun sync(cr: ContentResolver, specs: List<EventSpec>, remindMinutesBefore: Int): Int {
        val calId = resolveOrCreateCalendar(cr)
        // 幂等：只清本应用专属日历，重复同步不产生重复课程，也不影响用户其他日历
        cr.delete(
            CalendarContract.Events.CONTENT_URI,
            "${CalendarContract.Events.CALENDAR_ID}=?",
            arrayOf(calId.toString()),
        )
        if (specs.isEmpty()) return 0
        val ops = ArrayList<ContentProviderOperation>(specs.size * 2)
        for (s in specs) {
            ops += ContentProviderOperation
                .newInsert(CalendarContract.Events.CONTENT_URI)
                .withValues(ContentValues().apply {
                    put(CalendarContract.Events.CALENDAR_ID, calId)
                    put(CalendarContract.Events.TITLE, s.title)
                    put(CalendarContract.Events.EVENT_TIMEZONE, TZ_SH)
                    put(CalendarContract.Events.DTSTART, s.startMillis)
                    put(CalendarContract.Events.DTEND, s.endMillis)
                    s.location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
                    s.description?.let { put(CalendarContract.Events.DESCRIPTION, it) }
                    put(CalendarContract.Events.AVAILABILITY, CalendarContract.Events.AVAILABILITY_BUSY)
                })
                .build()
            if (remindMinutesBefore > 0) {
                ops += ContentProviderOperation
                    .newInsert(CalendarContract.Reminders.CONTENT_URI)
                    .withValues(ContentValues().apply {
                        put(CalendarContract.Reminders.MINUTES, remindMinutesBefore)
                        put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                    })
                    // 回引刚插入事件的 EVENT_ID（ops 末尾即该事件操作）
                    .withValueBackReference(CalendarContract.Reminders.EVENT_ID, ops.size - 1)
                    .build()
            }
        }
        cr.applyBatch(CalendarContract.AUTHORITY, ops)
        return specs.size
    }

    /**
     * 找到（或创建）应用专属的「简课表」日历。只认名字命中的日历，
     * 避免写入用户私人日历后清空操作误删他人事件。
     */
    private fun resolveOrCreateCalendar(cr: ContentResolver): Long {
        cr.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            ),
            null, null, null,
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
            val nameCol = c.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
            while (c.moveToNext()) {
                if (c.getString(nameCol) == CAL_DISPLAY_NAME) return c.getLong(idCol)
            }
        }
        return createLocalCalendar(cr)
    }

    /**
     * 清空本应用写入系统日历的全部课程事件（「简课表」日历），返回移除条数。
     * 只动本应用的专属日历，绝不触碰用户其他日历；日历本身保留，可再次同步。
     */
    fun clearSyncedEvents(cr: ContentResolver): Int {
        var calId = -1L
        cr.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID),
            "${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME}=? AND ${CalendarContract.Calendars.ACCOUNT_NAME}=?",
            arrayOf(CAL_DISPLAY_NAME, ACCOUNT_NAME),
            null,
        )?.use { c ->
            if (c.moveToFirst()) calId = c.getLong(0)
        }
        if (calId < 0) return 0  // 从未同步过：无可清理
        return cr.delete(
            CalendarContract.Events.CONTENT_URI,
            "${CalendarContract.Events.CALENDAR_ID}=?",
            arrayOf(calId.toString()),
        )
    }

    /** 创建 ACCOUNT_TYPE_LOCAL 本地账户日历（无需设备账号，华为/小米等主流 ROM 支持）。 */
    private fun createLocalCalendar(cr: ContentResolver): Long = try {
        val uri: Uri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            .build()
        ContentUris.parseId(
            cr.insert(
                uri,
                ContentValues().apply {
                    put(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
                    put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
                    put(CalendarContract.Calendars.NAME, CAL_DISPLAY_NAME)
                    put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CAL_DISPLAY_NAME)
                    put(CalendarContract.Calendars.CALENDAR_COLOR, 0xFF585992.toInt())
                    put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
                    put(CalendarContract.Calendars.OWNER_ACCOUNT, ACCOUNT_NAME)
                    put(CalendarContract.Calendars.SYNC_EVENTS, 1)
                    put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, TZ_SH)
                },
            ) ?: throw IllegalStateException("系统拒绝了日历创建"),
        )
    } catch (e: Exception) {
        throw IllegalStateException("无法创建「简课表」日历（系统日历不可用），可改用 .ics 导出", e)
    }
}
