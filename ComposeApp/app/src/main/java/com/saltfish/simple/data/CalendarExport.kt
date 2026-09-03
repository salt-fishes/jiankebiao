package com.saltfish.simple.data

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * 课表导出系统日历（.ics / iCalendar 2.0）。
 *
 * 设计要点：
 * - 逐周展开为独立 VEVENT（而非 RRULE+EXDATE）：各日历应用兼容性最好；
 * - UID 稳定（timetableId+entryId+week）：重复导入同一目标日历时天然幂等去重；
 * - 内置 Asia/Shanghai VTIMEZONE（固定 +08:00，无夏令时）；
 * - 每行按 RFC 5545 折行（≤75 字节，UTF-8 按字节计）；
 * - 纯函数无 Android 依赖，可单元测试。
 */
object CalendarExport {

    private val TS_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    /** 一条导出所需的设置。remindMinutesBefore <= 0 时不生成提醒。 */
    data class Options(
        val timetableId: Long,
        val timetableName: String,
        val totalWeeks: Int,
        val remindMinutesBefore: Int = 0,
    )

    /** 生成完整 iCalendar 文本。entries/sectionTimes/semesterStart 来自当前活动课表。 */
    fun buildIcs(
        entries: List<EntryWithCourse>,
        sectionTimes: List<SectionTime>,
        semesterStart: LocalDate?,
        opts: Options,
        now: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC),
    ): String {
        val sb = StringBuilder()
        sb.append("BEGIN:VCALENDAR\r\n")
        sb.append("VERSION:2.0\r\n")
        sb.append("PRODID:-//jiankebiao//timetable 1.0//CN\r\n")
        sb.append("CALSCALE:GREGORIAN\r\n")
        line(sb, "X-WR-CALNAME", opts.timetableName.ifBlank { "我的课表" })
        appendVtimezone(sb)
        val dtstamp = now.format(TS_FORMAT) + "Z"

        for (e in entries) {
            val startSection = e.startSection ?: 1
            val endSection = e.endSection ?: startSection
            val startT = sectionTimes.firstOrNull { it.section == startSection } ?: continue
            val endT = sectionTimes.firstOrNull { it.section == endSection } ?: startT
            val weeks = e.weeksCsv.split(',')
                .mapNotNull { it.trim().toIntOrNull() }
                .filter { it in 1..opts.totalWeeks }
                .distinct().sorted()
            val start = semesterStart ?: continue

            for (week in weeks) {
                val date = WeekCalculator.mondayOfWeek(start, week)
                    .plusDays((e.dayOfWeek - 1).toLong())
                val uid = "jkb-${opts.timetableId}-${e.entryId}-w$week@jiankebiao"
                appendEvent(sb, uid, dtstamp, date, startT, endT, e, opts)
            }
        }
        sb.append("END:VCALENDAR\r\n")
        return sb.toString()
    }

    private fun appendEvent(
        sb: StringBuilder,
        uid: String,
        dtstamp: String,
        date: LocalDate,
        startT: SectionTime,
        endT: SectionTime,
        e: EntryWithCourse,
        opts: Options,
    ) {
        sb.append("BEGIN:VEVENT\r\n")
        line(sb, "UID", uid)
        line(sb, "DTSTAMP", dtstamp)
        line(sb, "DTSTART;TZID=Asia/Shanghai", date.atTime(startT.start).format(TS_FORMAT))
        // 结束时间兜底：结束节时间早于开始节（脏数据）时用开始时间 +30 分钟
        val endDateTime = if (endT.end > startT.start) date.atTime(endT.end) else date.atTime(startT.start).plusMinutes(30)
        line(sb, "DTEND;TZID=Asia/Shanghai", endDateTime.format(TS_FORMAT))
        line(sb, "SUMMARY", e.courseName + typeMark(e.type))
        if (e.room.isNotBlank() || e.campus.isNotBlank()) {
            line(sb, "LOCATION", listOf(e.campus, e.building, e.room).filter { it.isNotBlank() }.joinToString(" "))
        }
        line(sb, "DESCRIPTION", buildString {
            if (e.teacher.isNotBlank()) append("教师：${e.teacher}")
            if (e.credit.isNotBlank()) append(if (isEmpty()) "" else "；").append("学分：${e.credit}")
        }.ifBlank { null })
        if (opts.remindMinutesBefore > 0) {
            sb.append("BEGIN:VALARM\r\n")
            sb.append("ACTION:DISPLAY\r\n")
            line(sb, "TRIGGER", "-PT${opts.remindMinutesBefore}M")
            line(sb, "DESCRIPTION", "${e.courseName} ${opts.remindMinutesBefore}分钟后上课")
            sb.append("END:VALARM\r\n")
        }
        sb.append("END:VEVENT\r\n")
    }

    /** Asia/Shanghai 固定 +08:00（无夏令时）的最小 VTIMEZONE。 */
    private fun appendVtimezone(sb: StringBuilder) {
        sb.append("BEGIN:VTIMEZONE\r\n")
        sb.append("TZID:Asia/Shanghai\r\n")
        sb.append("BEGIN:STANDARD\r\n")
        sb.append("DTSTART:19700101T000000\r\n")
        sb.append("TZOFFSETFROM:+0800\r\n")
        sb.append("TZOFFSETTO:+0800\r\n")
        sb.append("TZNAME:CST\r\n")
        sb.append("END:STANDARD\r\n")
        sb.append("END:VTIMEZONE\r\n")
    }

    /** 类型符号与课表 UI 一致（★讲课 ○实验 ●上机 ◇实践）。 */
    private fun typeMark(type: String): String = when (type) {
        "讲课" -> "★"
        "实验" -> "○"
        "上机" -> "●"
        "实践" -> "◇"
        else -> ""
    }

    /** 转义并写入一行属性（含 RFC 5545 折行）。value 为 null/空时跳过。 */
    private fun line(sb: StringBuilder, key: String, value: String?) {
        if (value.isNullOrBlank()) return
        foldLine(sb, "$key:${escape(value)}")
    }

    private fun escape(v: String): String = v
        .replace("\\", "\\\\")
        .replace(";", "\\;")
        .replace(",", "\\,")
        .replace("\r\n", "\\n")
        .replace("\n", "\\n")

    /** RFC 5545 折行：单行 ≤75 字节（UTF-8），续行以空格开头。切割点只在完整字符边界。 */
    private fun foldLine(sb: StringBuilder, raw: String) {
        val bytes = raw.toByteArray(Charsets.UTF_8)
        if (bytes.size <= 75) {
            sb.append(raw).append("\r\n")
            return
        }
        var start = 0
        var first = true
        while (start < bytes.size) {
            val budget = if (first) 75 else 74  // 续行行首空格占 1 字节
            // 按完整 UTF-8 字符消耗，直到预算耗尽；切割点永远落在字符边界
            var pos = start
            var cut = start
            while (pos < bytes.size && pos - start < budget) {
                val b = bytes[pos].toInt() and 0xFF
                val charLen = when {
                    b < 0x80 -> 1
                    b < 0xE0 -> 2
                    b < 0xF0 -> 3
                    else -> 4
                }
                if (pos - start + charLen > budget) break
                pos += charLen
                cut = pos
            }
            if (cut == start) cut = minOf(start + 1, bytes.size)  // 防御：保证前进
            if (!first) sb.append(' ')
            sb.append(String(bytes, start, cut - start, Charsets.UTF_8))
            start = cut
            first = false
            if (start < bytes.size) sb.append("\r\n")
        }
        sb.append("\r\n")
    }
}
