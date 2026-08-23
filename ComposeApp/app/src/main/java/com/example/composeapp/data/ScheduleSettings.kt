package com.example.composeapp.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 单节课起止时间。 */
data class SectionTime(
    val section: Int,
    val start: LocalTime,
    val end: LocalTime,
)

/** 用户设置（课表显示与学期信息）。 */
data class ScheduleSettings(
    val semesterStart: Long,          // 第一周周一 00:00 的 epoch 毫秒
    val sectionsPerDay: Int,          // 一日节数（作息表按此截取/补全）
    val totalWeeks: Int,              // 学期总周数（默认 17）
    val showWeekend: Boolean,         // 显示周末列（默认开）
    val showNonCurrentWeek: Boolean,  // 显示非本周课程（默认关，淡化展示）
    val dynamicColor: Boolean,        // 动态取色（API 31+，默认关）
    val darkMode: String,             // system / light / dark
    val sectionTimes: List<SectionTime>,
) {
    val semesterStartDate: LocalDate?
        get() = if (semesterStart == 0L) null
        else java.time.Instant.ofEpochMilli(semesterStart)
            .atZone(ZoneId.systemDefault()).toLocalDate()
}

/**
 * 设置仓库：SharedPreferences 存储，StateFlow 暴露（避免引入 DataStore 新依赖）。
 */
class SettingsRepository private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("schedule_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    val settings: Flow<ScheduleSettings> = _settings

    val current: ScheduleSettings get() = _settings.value

    /** 监听偏好变化，保证开关即时生效（无需退出重进）。
     *  注意：OnSharedPreferenceChangeListener 是弱引用，必须持有强引用。 */
    private val listener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            _settings.value = read()
        }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun setSemesterStart(date: LocalDate) {
        val millis = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        prefs.edit().putLong(KEY_SEMESTER_START, millis).apply()
    }

    /** 设置学期总周数（8..30）。 */
    fun setTotalWeeks(n: Int) {
        prefs.edit().putInt(KEY_TOTAL_WEEKS, n.coerceIn(8, 30)).apply()
    }

    fun setShowWeekend(value: Boolean) = prefs.edit().putBoolean(KEY_SHOW_WEEKEND, value).apply()

    fun setShowNonCurrentWeek(value: Boolean) =
        prefs.edit().putBoolean(KEY_SHOW_NON_CURRENT, value).apply()

    fun setDynamicColor(value: Boolean) = prefs.edit().putBoolean(KEY_DYNAMIC_COLOR, value).apply()

    fun setDarkMode(mode: String) = prefs.edit().putString(KEY_DARK_MODE, mode).apply()

    /** 调整一日节数：作息表随之截取/补全默认时间。 */
    fun setSectionsPerDay(n: Int) {
        val count = n.coerceIn(4, 16)
        prefs.edit().putInt(KEY_SECTIONS_PER_DAY, count).apply()
    }

    /** 覆盖整张节次时间表。 */
    fun setSectionTimes(times: List<SectionTime>) {
        prefs.edit().putString(KEY_SECTION_TIMES, encodeSections(times)).apply()
    }

    private fun read(): ScheduleSettings {
        val n = prefs.getInt(KEY_SECTIONS_PER_DAY, 12).coerceIn(4, 16)
        // 作息表按一日节数补全（缺的用默认值）或截断
        val base = decodeSections(
            prefs.getString(KEY_SECTION_TIMES, null) ?: encodeSections(DEFAULT_SECTION_TIMES)
        )
        val times = (1..n).map { s ->
            base.firstOrNull { it.section == s }
                ?: DEFAULT_SECTION_TIMES.firstOrNull { it.section == s }
                ?: SectionTime(s, LocalTime.of(8, 0), LocalTime.of(8, 45))
        }
        return ScheduleSettings(
            semesterStart = prefs.getLong(KEY_SEMESTER_START, DEFAULT_SEMESTER_START_MILLIS),
            sectionsPerDay = n,
            totalWeeks = prefs.getInt(KEY_TOTAL_WEEKS, DEFAULT_TOTAL_WEEKS).coerceIn(8, 30),
            showWeekend = prefs.getBoolean(KEY_SHOW_WEEKEND, true),
            showNonCurrentWeek = prefs.getBoolean(KEY_SHOW_NON_CURRENT, false),
            dynamicColor = prefs.getBoolean(KEY_DYNAMIC_COLOR, false),
            darkMode = prefs.getString(KEY_DARK_MODE, "system") ?: "system",
            sectionTimes = times,
        )
    }

    companion object {
        private const val KEY_SECTIONS_PER_DAY = "sections_per_day"
        private const val KEY_TOTAL_WEEKS = "total_weeks"
        private const val KEY_SEMESTER_START = "semester_start"
        private const val KEY_SHOW_WEEKEND = "show_weekend"
        private const val KEY_SHOW_NON_CURRENT = "show_non_current_week"
        private const val KEY_DYNAMIC_COLOR = "dynamic_color"
        private const val KEY_DARK_MODE = "dark_mode"
        private const val KEY_SECTION_TIMES = "section_times"

        /** 默认开学日：2026-08-31（周一），可在设置中修改。 */
        val DEFAULT_SEMESTER_START_LOCAL: LocalDate = LocalDate.of(2026, 8, 31)
        val DEFAULT_SEMESTER_START_MILLIS: Long =
            DEFAULT_SEMESTER_START_LOCAL.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

        /** 默认学期总周数。 */
        const val DEFAULT_TOTAL_WEEKS = 17

        val DEFAULT_SECTION_TIMES: List<SectionTime> = listOf(
            SectionTime(1, LocalTime.of(8, 0), LocalTime.of(8, 45)),
            SectionTime(2, LocalTime.of(8, 50), LocalTime.of(9, 35)),
            SectionTime(3, LocalTime.of(9, 55), LocalTime.of(10, 40)),
            SectionTime(4, LocalTime.of(10, 45), LocalTime.of(11, 30)),
            SectionTime(5, LocalTime.of(11, 35), LocalTime.of(12, 20)),
            SectionTime(6, LocalTime.of(13, 30), LocalTime.of(14, 15)),
            SectionTime(7, LocalTime.of(14, 20), LocalTime.of(15, 5)),
            SectionTime(8, LocalTime.of(15, 15), LocalTime.of(16, 0)),
            SectionTime(9, LocalTime.of(16, 5), LocalTime.of(16, 50)),
            SectionTime(10, LocalTime.of(18, 0), LocalTime.of(18, 45)),
            SectionTime(11, LocalTime.of(18, 50), LocalTime.of(19, 35)),
            SectionTime(12, LocalTime.of(19, 40), LocalTime.of(20, 25)),
        )

        private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        fun encodeSections(times: List<SectionTime>): String =
            times.joinToString(";") { "${it.section},${it.start.format(TIME_FMT)},${it.end.format(TIME_FMT)}" }

        fun decodeSections(text: String): List<SectionTime> =
            text.split(";").mapNotNull { part ->
                val f = part.trim().split(",")
                if (f.size != 3) return@mapNotNull null
                val section = f[0].toIntOrNull() ?: return@mapNotNull null
                // 格式为 "HH:mm"，不能用 toIntOrNull（曾因此把整张表解析成空列表）
                val start = runCatching { LocalTime.parse(f[1].trim()) }.getOrNull()
                    ?: return@mapNotNull null
                val end = runCatching { LocalTime.parse(f[2].trim()) }.getOrNull()
                    ?: return@mapNotNull null
                SectionTime(section, start, end)
            }.sortedBy { it.section }

        @Volatile private var INSTANCE: SettingsRepository? = null

        fun getInstance(context: Context): SettingsRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: SettingsRepository(context.applicationContext).also { INSTANCE = it }
            }
    }
}

/** 周次计算工具。 */
object WeekCalculator {

    /** 当前是第几周（第一周周一 = 第 1 周）；早于开学返回 <=0。 */
    fun currentWeek(semesterStart: LocalDate?, today: LocalDate = LocalDate.now()): Int {
        if (semesterStart == null) return 1
        // 归一到周一，容忍开学日设置非周一
        val startMonday = semesterStart.with(DayOfWeek.MONDAY)
        val days = java.time.temporal.ChronoUnit.DAYS.between(startMonday, today)
        return (days / 7).toInt() + 1
    }

    /** 某周的周一日期。 */
    fun mondayOfWeek(semesterStart: LocalDate, week: Int): LocalDate {
        val startMonday = semesterStart.with(DayOfWeek.MONDAY)
        return startMonday.plusWeeks((week - 1).toLong())
    }
}

/** 时间显示与节次换算工具。 */
object TimeUtils {

    private val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun hm(t: LocalTime): String = t.format(HM)

    /** 压缩有序周列表为区间文案："1-3,5-15"。 */
    fun compressInts(list: Collection<Int>): String {
        if (list.isEmpty()) return ""
        val sorted = list.toSortedSet()
        val parts = mutableListOf<String>()
        var start = sorted.first(); var prev = start
        for (v in sorted.drop(1)) {
            if (v == prev + 1) { prev = v; continue }
            parts.add(if (start == prev) "$start" else "$start-$prev")
            start = v; prev = v
        }
        parts.add(if (start == prev) "$start" else "$start-$prev")
        return parts.joinToString(",")
    }

    /** 节次的起止分钟（当天 00:00 起），找不到返回 null。 */
    fun sectionMinutes(times: List<SectionTime>, section: Int): Pair<Int, Int>? =
        times.firstOrNull { it.section == section }?.let {
            it.start.hour * 60 + it.start.minute to it.end.hour * 60 + it.end.minute
        }
}
