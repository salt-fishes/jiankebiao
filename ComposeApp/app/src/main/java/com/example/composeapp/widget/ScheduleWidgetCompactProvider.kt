package com.example.composeapp.widget

import com.example.composeapp.R

/**
 * 今日课程小组件（2×2 紧凑变体）：与 3×2 同结构（标题 + 今日课程列表），
 * 使用更小的字号与间距；列表项由 Service 按 EXTRA_COMPACT_ITEMS 切换紧凑布局。
 */
class ScheduleWidgetCompactProvider : ScheduleWidgetProvider() {

    override val layoutRes: Int = R.layout.widget_schedule_compact
    override val compactItems: Boolean = true
}
