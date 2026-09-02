package com.example.composeapp.widget

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import androidx.core.content.ContextCompat
import com.example.composeapp.R
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
            // 已结束：名称与地点用次要色（RemoteViews 兼容做法，不依赖 setAlpha）
            val nameColor = if (r.past) R.color.widget_text_secondary else R.color.widget_text_primary
            val locColor = R.color.widget_text_secondary
            views.setTextColor(R.id.item_name, ContextCompat.getColor(context, nameColor))
            views.setTextColor(R.id.item_loc, ContextCompat.getColor(context, locColor))
            return views
        }

        override fun getLoadingView(): RemoteViews? = null

        override fun getItemId(position: Int): Long = rows[position].entryId

        override fun hasStableIds(): Boolean = true
    }
}
