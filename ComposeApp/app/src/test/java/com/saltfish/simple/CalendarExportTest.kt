package com.saltfish.simple.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class CalendarExportTest {

    private fun entry(
        id: Long = 1,
        name: String = "高数",
        day: Int = 1,
        start: Int = 1,
        end: Int = 2,
        weeks: String = "1,2,3",
        room: String = "A101",
    ) = EntryWithCourse(
        entryId = id, courseId = 100 + id, courseName = name, type = "讲课", credit = "3.0",
        colorIndex = 0, dayOfWeek = day, startSection = start, endSection = end,
        weeksCsv = weeks, campus = "下沙", building = "环宇楼", room = room, teacher = "张三",
    )

    private val sectionTimes = listOf(
        SectionTime(1, LocalTime.of(8, 0), LocalTime.of(8, 45)),
        SectionTime(2, LocalTime.of(8, 55), LocalTime.of(9, 40)),
        SectionTime(3, LocalTime.of(10, 0), LocalTime.of(10, 45)),
    )

    private fun opts(name: String = "我的课表", remind: Int = 10) = CalendarExport.Options(
        timetableId = 7, timetableName = name, totalWeeks = 20, remindMinutesBefore = remind,
    )

    private val now = LocalDateTime.of(2026, 9, 3, 1, 0)

    @Test
    fun `生成事件数量与周次展开一致`() {
        val ics = CalendarExport.buildIcs(
            listOf(entry(weeks = "1,2,17")), sectionTimes, LocalDate.of(2026, 9, 7), opts(), now,
        )
        assertEquals(3, Regex("BEGIN:VEVENT").findAll(ics).count())
        // 第 1/2/17 周的周一分别为 2026-09-07 / 2026-09-14 / 2026-12-28
        assertEquals(
            3,
            Regex("DTSTART;TZID=Asia/Shanghai:20260907T080000").findAll(ics).count() +
                Regex("DTSTART;TZID=Asia/Shanghai:20260914T080000").findAll(ics).count() +
                Regex("DTSTART;TZID=Asia/Shanghai:20261228T080000").findAll(ics).count(),
        )
    }

    @Test
    fun `周次超出总周数被过滤`() {
        val ics = CalendarExport.buildIcs(
            listOf(entry(weeks = "1,25,30")), sectionTimes, LocalDate.of(2026, 9, 7), opts(), now,
        )
        assertEquals(1, Regex("BEGIN:VEVENT").findAll(ics).count())
    }

    @Test
    fun `节次时间映射与结束早于开始的兑底`() {
        val ics = CalendarExport.buildIcs(
            listOf(entry(start = 2, end = 3)), sectionTimes, LocalDate.of(2026, 9, 7), opts(remind = 0), now,
        )
        assertTrue(ics.contains("DTSTART;TZID=Asia/Shanghai:20260907T085500"))
        assertTrue(ics.contains("DTEND;TZID=Asia/Shanghai:20260907T104500"))
        // 跨节连堂共用同一 UID 规则：不同 entryId 不同 UID
        val ics2 = CalendarExport.buildIcs(
            listOf(entry(id = 2, start = 2, end = 3)), sectionTimes, LocalDate.of(2026, 9, 7), opts(), now,
        )
        assertTrue(ics2.contains("UID:jkb-7-2-w1@jiankebiao"))
        assertFalse(ics2.contains("UID:jkb-7-1-w1@jiankebiao"))
    }

    @Test
    fun `连堂课结束时间取最后一节的下课时间`() {
        val ics = CalendarExport.buildIcs(
            listOf(entry(start = 1, end = 2)), sectionTimes, LocalDate.of(2026, EntrySectionFixture.day, 7), opts(remind = 0), now,
        )
        assertTrue(ics.contains("DTEND;TZID=Asia/Shanghai:20260907T094000"))
    }

    @Test
    fun `特殊字符转义`() {
        val e = entry(name = "课程;含,逗号", room = "A101,3楼")
        val ics = CalendarExport.buildIcs(listOf(e), sectionTimes, LocalDate.of(2026, 9, 7), opts(remind = 0), now)
        assertTrue(ics.contains("SUMMARY:课程\\;含\\,逗号★"))
        assertTrue(ics.contains("LOCATION:下沙 环宇楼 A101\\,3楼"))
    }

    @Test
    fun `长行按75字节折行且不截断UTF8字符`() {
        val longName = "非常非常非常非常非常非常非常非常非常非常长的课程名称测试数据超长".repeat(3)
        val e = entry(name = longName)
        val ics = CalendarExport.buildIcs(listOf(e), sectionTimes, LocalDate.of(2026, 9, 7), opts(remind = 0), now)
        for (raw in ics.split("\r\n")) {
            // 续行前缀空格计入后每段 ≤75 字节
            assertTrue(raw.toByteArray(Charsets.UTF_8).size <= 75)
        }
        // 折行后内容可无损还原（去续行空格后包含完整课程名）
        val unfolded = ics.replace("\r\n ", "")
        assertTrue(unfolded.contains(longName + "★"))
    }

    @Test
    fun `提醒告警生成`() {
        val ics = CalendarExport.buildIcs(
            listOf(entry()), sectionTimes, LocalDate.of(2026, 9, 7), opts(remind = 15), now,
        )
        assertTrue(ics.contains("BEGIN:VALARM"))
        assertTrue(ics.contains("TRIGGER:-PT15M"))
    }

    @Test
    fun `周次天数正确映射周一到周日`() {
        val start = LocalDate.of(2026, 9, 7) // 周一
        val ics = CalendarExport.buildIcs(
            listOf(entry(id = 1, day = 1), entry(id = 2, day = 7)),
            sectionTimes, start, opts(remind = 0), now,
        )
        assertTrue(ics.contains("DTSTART;TZID=Asia/Shanghai:20260907T080000"))
        assertTrue(ics.contains("DTSTART;TZID=Asia/Shanghai:20260913T080000"))
    }

    @Test
    fun `semesterStart缺失时不产生事件`() {
        val ics = CalendarExport.buildIcs(listOf(entry()), sectionTimes, null, opts(), now)
        assertFalse(ics.contains("BEGIN:VEVENT"))
    }
}

/** 供测试使用的常量（避免 Kotlin 默认参数语法限制）。 */
private object EntrySectionFixture {
    const val day = 9
}
