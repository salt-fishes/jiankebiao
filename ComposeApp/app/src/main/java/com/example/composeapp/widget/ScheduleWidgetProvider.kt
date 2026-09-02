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
import com.example.composeapp.data.TimetableEntity
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

open class ScheduleWidgetProvider : AppWidgetProvider() {

    /** 小组件布局（2×2 紧凑变体覆盖）。 */
    protected open val layoutRes: Int = R.layout.widget_schedule

    /** 列表项是否用紧凑布局（随 RemoteAdapter intent 传给 Service）。 */
    protected open val compactItems: Boolean = false

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val pending = goAsync()
        val appContext = context.applicationContext
        val layout = layoutRes
        val compact = compactItems
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 每个实例按自己的绑定课表独立渲染
                for (id in appWidgetIds) {
                    val views = buildViews(
                        appContext, layout, compact, resolveTimetableId(appContext, id),
                    )
                    appWidgetManager.updateAppWidget(id, views)
                }
            } catch (_: Throwable) {
            } finally {
                pending.finish()
            }
        }
    }

    companion object {

        /** 实例绑定的课表：未设置(0)时跟随当前活动课表。 */
        private fun resolveTimetableId(context: Context, widgetId: Int): Long =
            getWidgetBinding(context, widgetId) ?: resolveActiveTimetableId(context)

        /** 绑定 id（>0），未绑定返回 null。供 Service 工厂兜底使用。 */
        internal fun getWidgetBinding(context: Context, widgetId: Int): Long? =
            SettingsRepository.getInstance(context).getWidgetTimetableId(widgetId)
                .takeIf { it > 0L }

        internal fun resolveActiveTimetableId(context: Context): Long =
            SettingsRepository.getInstance(context).activeTimetableId

        /** 数据变化后的主动刷新入口（更新 3×2 与 2×2 的所有实例，并剪除失效绑定）。 */
        fun requestUpdate(context: Context) {
            val appContext = context.applicationContext
            val mgr = AppWidgetManager.getInstance(appContext)
            val settingsRepo = SettingsRepository.getInstance(appContext)
            val targets = listOf(
                Triple(
                    ComponentName(appContext, ScheduleWidgetProvider::class.java),
                    R.layout.widget_schedule, false,
                ),
                Triple(
                    ComponentName(appContext, ScheduleWidgetCompactProvider::class.java),
                    R.layout.widget_schedule_compact, true,
                ),
            )
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val validIds = mutableSetOf<Int>()
                    for ((cn, layout, compact) in targets) {
                        val ids = mgr.getAppWidgetIds(cn)
                        validIds.addAll(ids.toList())
                        for (id in ids) {
                            val views = buildViews(
                                appContext, layout, compact,
                                resolveTimetableId(appContext, id),
                            )
                            mgr.updateAppWidget(id, views)
                        }
                    }
                    settingsRepo.pruneWidgetBindings(validIds)
                } catch (_: Throwable) {
                }
            }
        }

        /** 加载指定课表的今日课程行；返回 (课表名, 行列表)。 */
        internal suspend fun loadRows(
            context: Context,
            timetableId: Long,
        ): Pair<String, List<WidgetRow>> {
            val settings = SettingsRepository.getInstance(context).current
            val dao = AppDatabase.getInstance(context).scheduleDao()
            // 绑定的课表（可能非活动课表）：开学日/周数/作息/名称都取自它
            val timetable = dao.getTimetable(timetableId)
                ?: TimetableEntity(
                    name = settings.timetableName.ifBlank { "我的课表" },
                    startMillis = settings.semesterStart,
                    totalWeeks = settings.totalWeeks,
                )
            val sectionTimes = SettingsRepository.sectionTimesFor(
                timetable.sectionTimesCsv, timetable.sectionsPerDay, settings.sectionTimes,
            )
            val today = LocalDate.now()
            val startDate = if (timetable.startMillis == 0L) null
            else java.time.Instant.ofEpochMilli(timetable.startMillis)
                .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            val rawWeek = WeekCalculator.currentWeek(startDate, today)
            // 开学前（周数 <= 0）不展示任何课程，避免提前泄露开学后的安排
            if (rawWeek < 1) return "未开学" to emptyList()
            // 遵循「显示周末」开关：隐藏周末则周六/日不展示课程
            if (!settings.showWeekend && today.dayOfWeek.value >= 6) {
                return "第 $rawWeek 周" to emptyList()
            }
            val entries = dao.getAllEntries(timetableId)
                .filter { it.dayOfWeek == today.dayOfWeek.value && it.isInWeek(rawWeek) }
                .sortedBy { it.startSection ?: 99 }
            val nowMinutes = LocalTime.now().let { it.hour * 60 + it.minute }
            val rows = entries.map { e ->
                // 开始时间取起始节次、结束时间取结束节次：连堂课（如 6-8 节）显示最后一节的下课时间
                val startSpan = TimeUtils.sectionMinutes(sectionTimes, e.startSection ?: 1)
                val endSpan = TimeUtils.sectionMinutes(
                    sectionTimes,
                    e.endSection ?: e.startSection ?: 1,
                )
                WidgetRow(
                    entryId = e.entryId,
                    start = startSpan?.let { fmt(it.first) } ?: "",
                    end = endSpan?.let { fmt(it.second) } ?: "",
                    name = e.courseName + typeSymbol(e.type),
                    loc = shortLocation(e),
                    past = endSpan != null && nowMinutes > endSpan.second,
                )
            }
            return "第 $rawWeek 周" to rows
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

        private suspend fun buildViews(
            context: Context,
            layoutRes: Int,
            compactItems: Boolean,
            timetableId: Long,
        ): RemoteViews {
            val (week, rows) = loadRows(context, timetableId)
            val timetableName = AppDatabase.getInstance(context).scheduleDao()
                .getTimetable(timetableId)?.name ?: "课表"
            val views = RemoteViews(context.packageName, layoutRes)

            // 点击整块打开应用
            val pi = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            views.setOnClickPendingIntent(R.id.widget_root, pi)
            // 标题显示所绑定课表的名称
            views.setTextViewText(R.id.widget_title, timetableName)

            val today = LocalDate.now()
            val dayNames = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
            views.setTextViewText(
                R.id.widget_subtitle,
                "${today.monthValue}月${today.dayOfMonth}日 ${dayNames[today.dayOfWeek.value - 1]} · $week",
            )
            views.setTextViewText(R.id.widget_count, if (rows.isNotEmpty()) "${rows.size} 节" else "")

            views.setRemoteAdapter(
                R.id.widget_list,
                Intent(context, ScheduleWidgetService::class.java)
                    .putExtra(ScheduleWidgetService.EXTRA_COMPACT_ITEMS, compactItems)
                    .putExtra(ScheduleWidgetService.EXTRA_TIMETABLE_ID, timetableId),
            )
            views.setEmptyView(R.id.widget_list, R.id.widget_empty)
            return views
        }
    }
}
