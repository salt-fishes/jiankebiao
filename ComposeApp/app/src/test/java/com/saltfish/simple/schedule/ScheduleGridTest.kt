package com.saltfish.simple.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 格线网格 v3 纯几何核心的单测（合成探针，无 Android 依赖）。
 * 场景对齐真机回归发现的问题：同格双卡上下铺、稀疏线 PDF、长 rowspan、跨格粘连。
 */
class ScheduleGridTest {

    /** 合成线段图：预置横线位置集合的探针（忽略 x 范围）。 */
    private fun probeOf(vararg lineYs: Float) = ScheduleGrid.SegmentProbe { yFrom, yTo, _, _ ->
        lineYs.filter { it in yFrom..yTo }.minByOrNull { kotlin.math.abs(it - (yFrom + yTo) / 2f) }
    }

    private fun gridOf(
        anchors: List<Pair<Float, Int>> = listOf(100f to 1, 200f to 2, 300f to 3),
        probe: ScheduleGrid.SegmentProbe = probeOf(),
    ): ScheduleGrid.Grid {
        val rows = ScheduleGrid.buildRows(anchors, 50f, 350f, probe, 0f, 600f)
        return ScheduleGrid.Grid(listOf(0f to 300f, 300f to 600f), rows)
    }

    @Test
    fun `锚点定行 - 中点边界与横线吸附`() {
        // 带内两条线（155 外框、160 真边界），取距带中心最近的 → 160
        val rows = ScheduleGrid.buildRows(
            listOf(100f to 1, 220f to 2, 340f to 3),
            tableTop = 40f, tableBottom = 400f,
            probe = probeOf(155f, 160f, 280f), colX0 = 0f, colX1 = 600f,
        )
        assertEquals(160f, rows[0].bottom)
        assertEquals(280f, rows[1].bottom)
        assertEquals(listOf(1, 2, 3), rows.map { it.section })
    }

    @Test
    fun `锚点整流 - LIS 丢弃乱序噪声锚`() {
        val kept = ScheduleGrid.sanitizeAnchors(
            listOf(100f to 1, 110f to 7, 200f to 2, 205f to 1, 300f to 3),
        )
        assertEquals(listOf(1, 2, 3), kept.map { it.second })
        assertEquals(listOf(100f, 200f, 300f), kept.map { it.first })
    }

    @Test
    fun `行列定位 - 中心点命中列与行标签`() {
        val g = gridOf()
        assertEquals(1, g.columnAt(150f))
        assertEquals(2, g.columnAt(450f))
        assertNull(g.columnAt(650f))
        assertEquals(1, g.rowSectionAt(100f))
        assertEquals(3, g.rowSectionAt(320f))
        assertNull(g.rowSectionAt(20f))   // 表头区（首行上方）
    }

    @Test
    fun `扩展 - 文字跨两带的双节卡不越组边界`() {
        val g = gridOf(probe = probeOf(150f, 250f))   // 组间有线：1|2 无、2|3 有
        // 理论力学式双节卡：文字行标签 {1,2} → 1-2，2|3 有线止步
        val span = ScheduleGrid.expandSpan(g.rows, listOf(1, 2), probeOf(150f, 250f), 4f, 296f)
        assertEquals(1 to 2, span)
    }

    @Test
    fun `扩展 - 同格双卡上下铺各自回满整格`() {
        // 5-6 格上下两张卡：上卡核 {5}、下卡核 {6}，5|6 无线、4|5 与 6|7 有线
        val rows = ScheduleGrid.buildRows(
            listOf(100f to 4, 200f to 5, 300f to 6, 400f to 7),
            tableTop = 50f, tableBottom = 450f,
            probe = probeOf(150f, 350f), colX0 = 0f, colX1 = 600f,
        )
        val upper = ScheduleGrid.expandSpan(rows, listOf(5), probeOf(150f, 350f), 4f, 296f)
        val lower = ScheduleGrid.expandSpan(rows, listOf(6), probeOf(150f, 350f), 4f, 296f)
        assertEquals(5 to 6, upper)
        assertEquals(5 to 6, lower)
    }

    @Test
    fun `扩展 - 跨格粘连被有线边界拦住`() {
        // 概率论（1-2 格第二门）文字标签抖成 {2}：1|2 无线回扩到 1，2|3 有线止步
        val g = gridOf(probe = probeOf(250f))
        val span = ScheduleGrid.expandSpan(g.rows, listOf(2), probeOf(250f), 4f, 296f)
        assertEquals(1 to 2, span)
    }

    @Test
    fun `扩展 - 长 rowspan 向两侧无线扩张`() {
        // 劳动实践式：核 {6,7}，5|6、7|8 无线，4|5、8|9 有线 → 5-8
        val rows = ScheduleGrid.buildRows(
            listOf(100f to 4, 200f to 5, 300f to 6, 400f to 7, 500f to 8, 600f to 9),
            tableTop = 50f, tableBottom = 650f,
            probe = probeOf(150f, 550f), colX0 = 0f, colX1 = 600f,
        )
        val span = ScheduleGrid.expandSpan(rows, listOf(6, 7), probeOf(150f, 550f), 4f, 296f)
        assertEquals(5 to 8, span)
    }

    @Test
    fun `扩展 - 空核返回 null`() {
        val g = gridOf()
        assertNull(ScheduleGrid.expandSpan(g.rows, emptyList(), probeOf(), 4f, 296f))
    }

    @Test
    fun `扩展 - 单侧扩张步数钳制`() {
        // 完全无线 + 核 {2}：若无钳制会扩成 1..3，钳制后 1..3 之内最多各走 2 步
        // （3 行表走满也是 1..3，用 6 行表验证钳制生效）
        val rows = ScheduleGrid.buildRows(
            listOf(100f to 1, 200f to 2, 300f to 3, 400f to 4, 500f to 5, 600f to 6),
            tableTop = 50f, tableBottom = 650f,
            probe = probeOf(), colX0 = 0f, colX1 = 600f,
        )
        val span = ScheduleGrid.expandSpan(rows, listOf(3), probeOf(), 4f, 296f)
        assertEquals(1 to 5, span)
    }
}
