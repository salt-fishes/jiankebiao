package com.saltfish.simple.schedule

import com.saltfish.simple.schedule.ParseRulePack.Companion.IconGrid
import com.saltfish.simple.schedule.ParseRulePack.Companion.Qz
import com.saltfish.simple.schedule.ParseRulePack.Companion.Zfsoft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ScheduleParser 规则包解释验证：单元格文本取自真实 OCR 输出（姓名等已替换为占位），
 * 三种内置包（正方键值式 / 图标行式 / 强智位置式）各覆盖正例与误判防线。
 */
class ScheduleParserTest {

    // ---- 正方键值式（PDF OCR 实测形态：跨行断裂、字段碎片） ----

    private val zfCell = listOf(
        "模拟电子线路★",
        "(1-2节)1-3周,5-15周/校区:下 沙/楼号:环宇楼/场地:环宇 楼A404/教师:张三/教学班 :(2026-2027-1)-XX12345- 67/教学班组成:示例班1;示例班2/选课",
    ).joinToString("\n")

    @Test
    fun `正方键值式完整字段`() {
        val c = ScheduleParser.parseCell(zfCell, Zfsoft).single()
        assertEquals("模拟电子线路", c.name)
        assertEquals("讲课", c.type)
        assertEquals("张三", c.teacher)
        assertEquals("下沙", c.campus)
        assertEquals("环宇楼", c.building)
        assertEquals("环宇楼A404", c.room)
        assertEquals("(2026-2027-1)-XX12345-67", c.classNo)
        assertEquals(listOf(1, 2), c.sections)
        assertEquals((1..3).toList() + (5..15).toList(), c.weeks)
    }

    @Test
    fun `学分碎片不另起课程块而是并入上一块`() {
        // OCR 实测：属性区换行把 "学分:3.0" 断成独立行（曾因行尾 0 无保护变成垃圾课程）
        val text = zfCell + "\n:/学分:3.0"
        val courses = ScheduleParser.parseCell(text, Zfsoft)
        assertEquals(1, courses.size)
        assertEquals("3.0", courses.single().credit)
    }

    @Test
    fun `冒号被OCR读成空格或丢失时仍能取到字段`() {
        // 块内拼接会去掉空格："教师 李四" → "教师李四"（无分隔符）
        val text = "示例物理方法★\n(1-2节)1-3周/校区:下沙/教师 李四/学分:2.0"
        val c = ScheduleParser.parseCell(text, Zfsoft).single()
        assertEquals("李四", c.teacher)
        assertEquals("下沙", c.campus)
        assertEquals("2.0", c.credit)
    }

    @Test
    fun `数值结尾的碎片行不产生幽灵课程`() {
        // 打印时间行被 OCR 成 "J丁F时时问.2020"（无冒号可挡），多位数结尾应整体拒绝
        val courses = ScheduleParser.parseCell("大学物理A2★\n(6-7节)1-3周\nJ丁F时时问.2020", Zfsoft)
        assertEquals(1, courses.size)
        assertEquals("大学物理A2", courses.single().name)
    }

    @Test
    fun `实验标记的 OCR 变体仍可识别`() {
        // ○ → 字母 O / 数字 0（前一位非数字）
        for (tail in listOf("AO", "A0", "A〇")) {
            val c = ScheduleParser.parseCell("物理实验$tail", Zfsoft).single()
            assertEquals("物理实验A", c.name)
            assertEquals("实验", c.type)
        }
    }

    // ---- 强智位置式 ----

    @Test
    fun `强智位置式字段回填`() {
        val text = "大学物理(理论)\n【1-6,10-18周】\n张三\nN6-403"
        val c = ScheduleParser.parseCell(text, Qz).single()
        assertEquals("大学物理", c.name)
        assertEquals("讲课", c.type)
        assertEquals("张三", c.teacher)
        assertEquals("N6-403", c.room)
        assertEquals((1..6).toList() + (10..18).toList(), c.weeks)
    }

    @Test
    fun `强智教师与周次同行`() {
        // OCR 实测：教师名紧邻【周次】（"王五【2-6周】"）
        val c = ScheduleParser.parseCell("中国近现代史纲要(理论)\n王五【2-6周】\nN4-JT02", Qz).single()
        assertEquals("中国近现代史纲要", c.name)
        assertEquals("王五", c.teacher)
        assertEquals("N4-JT02", c.room)
        assertEquals((2..6).toList(), c.weeks)
    }

    @Test
    fun `强智教室取教学班说明前的首个匹配`() {
        // 尾部教学班碎片（示例班2076）不再是教室候选
        val c = ScheduleParser.parseCell(
            "劳动实践(实践)\n赵六【10-13周】\nN4-室外1\n劳动实践示例班2511,机\n制2076",
            Qz,
        ).single()
        assertEquals("N4-室外1", c.room)
        assertEquals("赵六", c.teacher)
    }

    // ---- 图标行式（网页/截图） ----

    @Test
    fun `图标行式前缀路由`() {
        val text = listOf(
            "数学分析★",
            "◎ (1-2节)1-4周,6-11周",
            "📍 新校区 图信楼B305",
            "👤 张三(高等学校教师/讲师)",
            "🏠 (2026-2027-1)-XX12345-67",
        ).joinToString("\n")
        val c = ScheduleParser.parseCell(text, IconGrid).single()
        assertEquals("数学分析", c.name)
        assertEquals("讲课", c.type)
        assertEquals(listOf(1, 2), c.sections)
        assertEquals((1..4).toList() + (6..11).toList(), c.weeks)
        assertEquals("新校区", c.campus)
        assertEquals("图信楼B305", c.room)
        assertEquals("张三", c.teacher)
        assertEquals("(2026-2027-1)-XX12345-67", c.classNo)
    }

    @Test
    fun `网页截图图标被OCR吞掉时按行形状恢复`() {
        // OCR 实测：📍 整个丢失、👤 变 "1"、🏠 丢失、🕐 变 "©"、括号全角半角混排
        val text = listOf(
            "机械工程材料★",
            "©（3-4节)1-5周,7-11周",
            "新校区机电楼A201(新校区)",
            "1钱七(高等学校教师/副教授)",
            "(2026-2027-1)-0Y351035-06",
        ).joinToString("\n")
        val c = ScheduleParser.parseCell(text, IconGrid).single()
        assertEquals("机械工程材料", c.name)
        assertEquals(listOf(3, 4), c.sections)
        assertEquals((1..5).toList() + (7..11).toList(), c.weeks)
        assertEquals("钱七", c.teacher)
        assertEquals("新校区机电楼A201", c.room)
        assertEquals("(2026-2027-1)-0Y351035-06", c.classNo)
    }

    // ---- 包错配防线（用户看到「0 门课程」的典型成因） ----

    @Test
    fun `规则包错配时不产生课程`() {
        // 正方文本用强智包解析：类型标记 (理论) 不存在 → 无课程块 → 0 门课
        assertTrue(ScheduleParser.parseCell(zfCell, Qz).isEmpty())
        // 强智文本用正方包解析：行尾无 ★○●◇ 标记 → 无课程块 → 0 门课
        assertTrue(ScheduleParser.parseCell("大学物理(理论)\n【1-6,10-18周】\n张三\nN6-403", Zfsoft).isEmpty())
    }
}
