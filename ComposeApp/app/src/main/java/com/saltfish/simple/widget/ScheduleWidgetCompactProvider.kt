package com.saltfish.simple.widget

import com.saltfish.simple.R

/**
 * 今日课程小组件（2×2 紧凑变体）：与列表型（2×3/2×4）同结构（标题 + 今日课程列表），
 * 字号与间距收紧；列表项由 Service 按 EXTRA_COMPACT_ITEMS 切换紧凑布局。
 */
class ScheduleWidgetCompactProvider : ScheduleWidgetProvider() {

    override val layoutRes: Int = R.layout.widget_schedule_compact
    override val compactItems: Boolean = true
}
