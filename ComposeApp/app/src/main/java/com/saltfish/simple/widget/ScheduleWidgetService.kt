package com.saltfish.simple.widget

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.saltfish.simple.R
import kotlinx.coroutines.runBlocking

/**
 * 小组件列表数据源：在后台线程查询今日课程并渲染行视图。
 * 已结束课程用次要色弱化显示。
 */
class ScheduleWidgetService : RemoteViewsService() {

    companion object {
        const val EXTRA_COMPACT_ITEMS = "compact_items"
        const val EXTRA_TIMETABLE_ID = "timetable_id"   // 0 = 跟随活动课表
    }

    override fun onGetViewFactory(intent: Intent?): RemoteViewsFactory =
        Factory(
            applicationContext,
            intent?.getBooleanExtra(EXTRA_COMPACT_ITEMS, false) ?: false,
            intent?.getLongExtra(EXTRA_TIMETABLE_ID, 0L) ?: 0L,
        )

    private class Factory(
        private val context: Context,
        private val compact: Boolean,
        private val timetableId: Long,
    ) : RemoteViewsFactory {

        private var rows: List<WidgetRow> = emptyList()

        override fun onCreate() {}

        override fun onDataSetChanged() {
            rows = runBlocking {
                val tid = if (timetableId > 0) timetableId
                else ScheduleWidgetProvider.resolveActiveTimetableId(context)
                runCatching { ScheduleWidgetProvider.loadRows(context, tid).second }.getOrDefault(emptyList())
            }
        }

        override fun onDestroy() {}

        override fun getCount(): Int = rows.size

        override fun getViewTypeCount(): Int = 1

        override fun getViewAt(position: Int): RemoteViews? {
            if (position < 0 || position >= rows.size) return null
            val r = rows[position]
            val layout = if (compact) R.layout.widget_schedule_compact_item else R.layout.widget_schedule_item
            val views = RemoteViews(context.packageName, layout)
            views.setTextViewText(R.id.item_time_start, r.start)
            views.setTextViewText(R.id.item_time_end, r.end)
            views.setTextViewText(R.id.item_name, r.name)
            views.setTextViewText(R.id.item_loc, r.loc)
            // 配色与容器同源（WidgetTheme）；已结束：名称与地点用次要色（不依赖 setAlpha）
            val theme = WidgetTheme.resolve(context)
            views.setTextColor(R.id.item_time_start, theme.accent)
            views.setTextColor(R.id.item_time_end, theme.textSecondary)
            views.setTextColor(R.id.item_name, if (r.past) theme.textSecondary else theme.textPrimary)
            views.setTextColor(R.id.item_loc, theme.textSecondary)
            return views
        }

        override fun getLoadingView(): RemoteViews? = null

        override fun getItemId(position: Int): Long = rows[position].entryId

        override fun hasStableIds(): Boolean = true
    }
}
