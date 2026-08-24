package com.example.composeapp.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.composeapp.MainActivity
import com.example.composeapp.R
import com.example.composeapp.data.AppDatabase
import com.example.composeapp.data.EntryWithCourse
import com.example.composeapp.data.SettingsRepository
import com.example.composeapp.data.TimeUtils
import com.example.composeapp.data.WeekCalculator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

/**
 * 今日课程小组件：标题（第 N 周 · 日期）+ 今日课程列表（RemoteViews 列表）。
 * 遵循「显示周末」开关：隐藏周末时，周六/日显示空态；数据变化后由 AppRefresh 触发刷新，
 * 系统兜底每 30 分钟周期刷新（跨天/跨周）。
 */
/**
 * 小组件单行数据（列表 Factory 与 Provider 头部共用）。
 */
data class WidgetRow(
    val entryId: Long,
    val start: String,
    val end: String,
    val name: String,
    val loc: String,
    val past: Boolean,
)

class ScheduleWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val views = buildViews(appContext)
                for (id in appWidgetIds) appWidgetManager.updateAppWidget(id, views)
            } catch (_: Throwable) {
            } finally {
                pending.finish()
            }
        }
    }

    companion object {

        /** 数据变化后的主动刷新入口。 */
        fun requestUpdate(context: Context) {
            val appContext = context.applicationContext
            val mgr = AppWidgetManager.getInstance(appContext)
            val ids = mgr.getAppWidgetIds(ComponentName(appContext, ScheduleWidgetProvider::class.java))
            if (ids.isEmpty()) return
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val views = buildViews(appContext)
                    for (id in ids) mgr.updateAppWidget(id, views)
                } catch (_: Throwable) {
                }
            }
        }

        /** 加载今日课程行；返回 (当前周, 行列表)。 */
        internal suspend fun loadRows(context: Context): Pair<String, List<WidgetRow>> {
            val settings = SettingsRepository.getInstance(context).current
            val today = LocalDate.now()
            // 遵循「显示周末」开关：隐藏周末则周六/日不展示课程
            if (!settings.showWeekend && today.dayOfWeek.value >= 6) return "0" to emptyList()
            // 开学前 currentWeek <= 0，与课表页一致钳到第 1 周（否则开学前小组件永远为空）
            val week = WeekCalculator.currentWeek(settings.semesterStartDate, today)
                .coerceAtLeast(1)
            val entries = AppDatabase.getInstance(context).scheduleDao().observeAllEntries().first()
                .filter { it.dayOfWeek == today.dayOfWeek.value && week >= 1 && it.isInWeek(week) }
                .sortedBy { it.startSection ?: 99 }
            val nowMinutes = LocalTime.now().let { it.hour * 60 + it.minute }
            val rows = entries.map { e ->
                val span = TimeUtils.sectionMinutes(settings.sectionTimes, e.startSection ?: 1)
                WidgetRow(
                    entryId = e.entryId,
                    start = span?.let { fmt(it.first) } ?: "",
                    end = span?.let { fmt(it.second) } ?: "",
                    name = e.courseName + typeSymbol(e.type),
                    loc = shortLocation(e),
                    past = span != null && nowMinutes > span.second,
                )
            }
            return "$week" to rows
        }

        internal fun typeSymbol(type: String): String = when (type) {
            "讲课" -> "★"
            "实验" -> "○"
            "上机" -> "●"
            "实践" -> "◇"
            "集中实践" -> ":"
            else -> ""
        }

        internal fun shortLocation(e: EntryWithCourse): String =
            e.room.ifBlank { e.building.ifBlank { e.campus } }

        internal fun fmt(minutesOfDay: Int): String =
            "%02d:%02d".format(minutesOfDay / 60, minutesOfDay % 60)

        private suspend fun buildViews(context: Context): RemoteViews {
            val (week, rows) = loadRows(context)
            val views = RemoteViews(context.packageName, R.layout.widget_schedule)

            // 点击整块打开应用
            val pi = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            views.setOnClickPendingIntent(R.id.widget_root, pi)

            val today = LocalDate.now()
            val dayNames = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
            views.setTextViewText(
                R.id.widget_subtitle,
                "${today.monthValue}月${today.dayOfMonth}日 ${dayNames[today.dayOfWeek.value - 1]} · 第 $week 周",
            )
            views.setTextViewText(R.id.widget_count, if (rows.isNotEmpty()) "${rows.size} 节" else "")

            views.setRemoteAdapter(R.id.widget_list, Intent(context, ScheduleWidgetService::class.java))
            views.setEmptyView(R.id.widget_list, R.id.widget_empty)
            return views
        }
    }
}
