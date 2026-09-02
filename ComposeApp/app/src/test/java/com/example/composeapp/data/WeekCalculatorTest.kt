package com.example.composeapp.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

private fun d(y: Int, m: Int, day: Int): LocalDate = LocalDate.of(y, m, day)

/** 周次计算回归：重点覆盖开学日前的负向周次（截断除法 bug）。 */
class WeekCalculatorTest {

    @Test
    fun `开学当天与开学周`() {
        // 开学 2026-09-10（周四）：归一后周一为 09-07，第 1 周 = 09-07..09-13
        assertEquals(1, WeekCalculator.currentWeek(d(2026, 9, 10), d(2026, 9, 10)))
        assertEquals(1, WeekCalculator.currentWeek(d(2026, 9, 10), d(2026, 9, 13)))
        assertEquals(2, WeekCalculator.currentWeek(d(2026, 9, 10), d(2026, 9, 16)))
        assertEquals(2, WeekCalculator.currentWeek(d(2026, 9, 10), d(2026, 9, 17)))
    }

    @Test
    fun `开学日归一到周一`() {
        // 开学设在周三 09-02：周一 08-31，09-02 是第 1 周
        assertEquals(1, WeekCalculator.currentWeek(d(2026, 9, 2), d(2026, 9, 2)))
        assertEquals(1, WeekCalculator.currentWeek(d(2026, 9, 2), d(2026, 9, 6)))
        assertEquals(2, WeekCalculator.currentWeek(d(2026, 9, 2), d(2026, 9, 7)))
    }

    @Test
    fun `开学前为非正周次（截断除法回归）`() {
        // -5 天：截断除法 -5/7=0 会错得第 1 周，floorDiv 后应为第 0 周
        assertEquals(0, WeekCalculator.currentWeek(d(2026, 9, 10), d(2026, 9, 2)))
        // -12 天：floorDiv = -2 → 第 -1 周
        assertEquals(-1, WeekCalculator.currentWeek(d(2026, 9, 14), d(2026, 9, 2)))
        // -1 天：floorDiv = -1 → 第 0 周
        assertEquals(0, WeekCalculator.currentWeek(d(2026, 9, 10), d(2026, 9, 6)))
        // 开学前一天但在归一后的开学周内（开学设周四时，周一~周三按第 1 周计）
        assertEquals(1, WeekCalculator.currentWeek(d(2026, 9, 10), d(2026, 9, 9)))
    }

    @Test
    fun `开学日未设置`() {
        assertEquals(1, WeekCalculator.currentWeek(null, d(2026, 9, 2)))
    }
}
