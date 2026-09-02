package com.example.composeapp.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ScheduleXlsParser 解析验证：样本为正方教务同结构导出的班级课表 Excel
 * （app/src/test/resources/zf_class_schedule.xls，内容为虚构数据，结构与真实导出一致：
 * 表头星期行、节次行分组、单元格 "课程/(节)周次/地点/教师/学时/学分" 与换行多课程）。
 */
class ScheduleXlsParserTest {

    private fun parsed(): ParsedSchedule {
        val bytes = javaClass.getResourceAsStream("/zf_class_schedule.xls")?.readBytes()
            ?: error("缺少测试样本 zf_class_schedule.xls")
        val tmp = File.createTempFile("zf_schedule", ".xls")
        tmp.deleteOnExit()
        tmp.writeBytes(bytes)
        return ScheduleXlsParser.parse(tmp)
    }

    @Test
    fun `课程与排课数量`() {
        val p = parsed()
        assertEquals(14, p.courses.size)
        // 26 条原始排课中，6-8 连堂被教务拆成 (6-7)+(8-8) 的 3 组（周二/周三/周五）各合并为 1 条
        assertEquals(23, p.entries.size)
        val names = p.courses.map { it.name }
        assertTrue(
            names.containsAll(
                listOf(
                    "示例语文", "示例数学A", "示例物理", "示例英语", "示例制图",
                    "示例程序设计", "示例数据结构", "示例德育", "示例安全教育",
                )
            )
        )
    }

    @Test
    fun `周次与节次解析`() {
        val p = parsed()
        // 周一 1-2 节 示例语文：(1-2节)1-3周,5-16周
        val e = p.entries.first {
            it.course == "示例语文" && it.dayOfWeek == 1 && it.startSection == 1
        }
        assertEquals(listOf(2), listOf(e.endSection))
        assertEquals((1..3).toList() + (5..16).toList(), e.weeks)
        // 单节条目：示例程序设计 周二 (8-8节) 已与 (6-7节) 合并为 6-8 连堂
        val merged = p.entries.first {
            it.course == "示例程序设计" && it.dayOfWeek == 2 && it.startSection == 6
        }
        assertEquals(8, merged.endSection)
        // 10-12 节大节：示例形势与政策 (10-12节)6周,12周
        val big = p.entries.first { it.course == "示例形势与政策" }
        assertEquals(10, big.startSection)
        assertEquals(12, big.endSection)
        assertEquals(listOf(6, 12), big.weeks)
    }

    @Test
    fun `地点与教师解析`() {
        val p = parsed()
        // "东沙 教学楼A101（智慧教室）" -> campus=东沙, building=教学楼, room=全段
        val e = p.entries.first { it.course == "示例语文" && it.dayOfWeek == 1 }
        assertEquals("东沙", e.campus)
        assertEquals("教学楼", e.building)
        assertEquals("教学楼A101（智慧教室）", e.room)
        assertEquals("张老师", e.teacher)
        // 多教师保留原文
        val t = p.entries.first { it.course == "示例制图" && it.dayOfWeek == 1 }
        assertEquals("周老师,吴老师", t.teacher)
        // 未排地点：不拆楼号
        val none = p.entries.first { it.course == "示例安全教育" }
        assertEquals("未排地点", none.room)
    }

    @Test
    fun `课程级学分与合并`() {
        val p = parsed()
        val writing = p.courses.first { it.name == "示例语文" }
        assertEquals("2.0", writing.credit)
        assertEquals("张老师", writing.teacher)
        // 同名课程（跨天不同教室）应合并为一条
        assertEquals(1, p.courses.count { it.name == "示例制图" })
    }

    @Test
    fun `连堂课相邻条目合并`() {
        val p = parsed()
        // 周二 示例程序设计 6-8 节连堂：(6-7节)+(8-8节) 合并为一条，12 周单次仍独立
        val tue = p.entries.filter { it.course == "示例程序设计" && it.dayOfWeek == 2 }
        assertEquals(2, tue.size)
        val merged = tue.first { it.startSection == 6 }
        assertEquals(8, merged.endSection)
        assertEquals((1..3).toList() + (5..11).toList(), merged.weeks)
        val wk12 = tue.first { it.startSection == 6 && it != merged }
        assertEquals(7, wk12.endSection)
        assertEquals(listOf(12), wk12.weeks)
        // 周三 示例数据结构 同样合并；周四 示例德育 8-9 本就一条不受影响
        val wed = p.entries.filter { it.course == "示例数据结构" && it.dayOfWeek == 3 }
        assertEquals(2, wed.size)
        assertEquals(8, wed.first { it.startSection == 6 }.endSection)
    }

    @Test
    fun `单双周标记`() {
        assertEquals(listOf(1, 3, 5, 7, 9, 11, 13, 15), ScheduleParser.parseWeeks("1-15周(单周)"))
        assertEquals(listOf(2, 4, 6, 8, 10), ScheduleParser.parseWeeks("1-10周(双周)"))
        assertEquals((1..16).toList(), ScheduleParser.parseWeeks("1-16周"))
        assertEquals(listOf(1, 2, 3, 5, 6), ScheduleParser.parseWeeks("1-3周,5-6周"))
    }
}
