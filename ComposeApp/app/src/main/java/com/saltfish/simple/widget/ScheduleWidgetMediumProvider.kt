package com.saltfish.simple.widget

import com.saltfish.simple.R

/**
 * 今日课程小组件（2×3 变体）：与 2×4 共用同一列表布局与数据源，
 * 仅注册为独立组件（尺寸由 appwidget-provider XML 决定），列表随高度少显几行。
 */
class ScheduleWidgetMediumProvider : ScheduleWidgetProvider() {

    override val layoutRes: Int = R.layout.widget_schedule
    override val compactItems: Boolean = false
}
