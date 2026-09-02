package com.example.composeapp.data

import android.content.Context
import com.example.composeapp.schedule.ParsedSchedule
import com.example.composeapp.ui.theme.CourseBlockPalettes
import kotlinx.coroutines.flow.Flow

/**
 * 课表仓库：多课表的持久化与查询。所有数据操作以 timetableId 作用域，
 * 活动课表 id 由 SettingsRepository 提供（唯一事实源）。
 */
class ScheduleRepository(private val dao: ScheduleDao) {

    // ---- 查询（按课表作用域） ----

    fun observeAllEntries(timetableId: Long): Flow<List<EntryWithCourse>> =
        dao.observeAllEntries(timetableId)

    fun observeCourses(timetableId: Long): Flow<List<CourseEntity>> =
        dao.observeCourses(timetableId)

    fun observeTimetables(): Flow<List<TimetableEntity>> = dao.observeTimetables()

    fun observeCourseCounts(): Flow<List<TimetableCourseCount>> = dao.observeCourseCounts()

    suspend fun getTimetable(id: Long): TimetableEntity? = dao.getTimetable(id)

    /** 指定课表的全部条目（课表卡片缩略图等一次性渲染用）。 */
    suspend fun entriesOf(timetableId: Long): List<EntryWithCourse> = dao.getAllEntries(timetableId)

    // ---- 课表管理 ----

    /** 新建课表，返回 id。 */
    suspend fun createTimetable(name: String, startMillis: Long, totalWeeks: Int): Long =
        dao.insertTimetable(
            TimetableEntity(
                name = name.trim().ifBlank { "未命名课表" },
                startMillis = startMillis,
                totalWeeks = totalWeeks.coerceIn(8, 30),
            )
        )

    suspend fun updateTimetable(timetable: TimetableEntity) = dao.updateTimetable(timetable)

    /**
     * 删除课表（课程与排课级联）。仅允许删除非活动课表（调用方校验）。
     * @throws IllegalArgumentException 若是最后一张课表（保证应用内至少一张）
     */
    suspend fun deleteTimetable(timetableId: Long, activeTimetableId: Long) {
        if (timetableId == activeTimetableId) throw IllegalArgumentException("不能删除使用中的课表")
        if (dao.timetableCount() <= 1) throw IllegalArgumentException("至少保留一张课表")
        dao.deleteTimetable(timetableId)
    }

    /**
     * 以现有课表为模板复制：课程与排课结构原样复制，周次重置为整学期。
     * @return 新课表 id
     */
    suspend fun copyTimetable(
        source: TimetableEntity,
        newName: String,
        newStartMillis: Long,
        newTotalWeeks: Int,
    ): Long {
        val newId = dao.insertTimetable(
            TimetableEntity(
                name = newName.trim().ifBlank { "${source.name} 副本" },
                startMillis = newStartMillis,
                totalWeeks = newTotalWeeks.coerceIn(8, 30),
                // 作息跟随源课表
                sectionsPerDay = source.sectionsPerDay,
                sectionTimesCsv = source.sectionTimesCsv,
            )
        )
        val weeks = newTotalWeeks.coerceIn(8, 30)
        dao.copyCourses(source.id, newId, { ScheduleRepository.colorIndexFor(it) }, weeks)
        return newId
    }

    // ---- 导入 / 清除 ----

    /** 导入/覆盖指定课表（整表替换，条目级联）。 */
    suspend fun importSchedule(parsed: ParsedSchedule, timetableId: Long) {
        val byName = LinkedHashMap<String, CourseEntity>()
        val entriesByCourse = LinkedHashMap<String, MutableList<ScheduleEntryEntity>>()
        // 颜色唯一分配：不同课程名拿到不同色板索引（色板 12 组，超出部分才回用）
        val usedColors = mutableSetOf<Int>()
        for (c in parsed.courses) {
            val colorIndex = freeColorIndex(colorIndexFor(c.name), usedColors)
            usedColors.add(colorIndex)
            byName[c.name] = CourseEntity(
                timetableId = timetableId,
                name = c.name,
                type = c.type,
                credit = c.credit,
                classNo = c.classNo,
                composition = c.composition,
                colorIndex = colorIndex,
            )
            entriesByCourse[c.name] = mutableListOf()
        }
        val teacherByName = parsed.courses.associate { it.name to it.teacher }
        for (e in parsed.entries) {
            entriesByCourse.getOrPut(e.course) { mutableListOf() }.add(
                ScheduleEntryEntity(
                    courseId = 0L, // replaceTimetable 事务内回填
                    dayOfWeek = e.dayOfWeek,
                    startSection = e.startSection,
                    endSection = e.endSection,
                    weeksCsv = e.weeks.joinToString(","),
                    campus = e.campus,
                    building = e.building,
                    room = e.room,
                    // 条目级教师缺失时回退课程级（同一门课教师通常相同）
                    teacher = e.teacher.ifBlank { teacherByName[e.course] ?: "" },
                )
            )
        }
        val items = byName.map { (name, course) -> course to (entriesByCourse[name] ?: emptyList()) }
        dao.replaceTimetable(timetableId, items)
    }

    /** 清除指定课表（课程与排课级联）。 */
    suspend fun clearTimetable(timetableId: Long) = dao.clearTimetableCourses(timetableId)

    // ---- 排课编辑 ----

    suspend fun renameCourse(courseId: Long, name: String) =
        dao.updateCourseName(courseId, name.trim(), System.currentTimeMillis())

    suspend fun updateEntryInfo(
        entryId: Long, teacher: String, campus: String, building: String, room: String,
    ) = dao.updateEntryInfo(
        entryId,
        teacher.trim(), campus.trim(), building.trim(), room.trim(),
    )

    /** 长按拖拽移动排课：改星期与起止节次（保持时长由调用方计算好）。 */
    suspend fun moveEntry(entryId: Long, dayOfWeek: Int, startSection: Int, endSection: Int) =
        dao.updateEntryTime(entryId, dayOfWeek, startSection, endSection)

    suspend fun deleteEntry(entryId: Long) = dao.deleteEntry(entryId)

    /**
     * 新增一条排课（课表内课程名已存在则复用，否则新建课程）。
     * @return 新增条目的 id
     */
    suspend fun addEntry(
        timetableId: Long,
        name: String,
        teacher: String,
        campus: String,
        building: String,
        room: String,
        dayOfWeek: Int,
        startSection: Int?,
        endSection: Int?,
        weeks: List<Int>,
    ): Long {
        val n = name.trim()
        if (n.isEmpty()) throw IllegalArgumentException("课程名不能为空")
        var courseId = dao.findCourseByName(n, timetableId)?.id
        if (courseId == null) {
            // 新课程颜色避开已占用的色板索引
            val used = runCatching { dao.usedColorIndices(timetableId) }.getOrDefault(emptyList())
            courseId = dao.insertCourse(
                CourseEntity(
                    timetableId = timetableId,
                    name = n,
                    type = "",
                    credit = "",
                    classNo = "",
                    composition = "",
                    colorIndex = freeColorIndex(colorIndexFor(n), used.toSet()),
                )
            )
        }
        return dao.insertEntry(
            ScheduleEntryEntity(
                courseId = courseId,
                dayOfWeek = dayOfWeek,
                startSection = startSection,
                endSection = endSection,
                weeksCsv = weeks.joinToString(","),
                campus = campus.trim(),
                building = building.trim(),
                room = room.trim(),
                teacher = teacher.trim(),
            )
        )
    }

    companion object {
        @Volatile private var INSTANCE: ScheduleRepository? = null

        fun getInstance(context: Context): ScheduleRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: ScheduleRepository(
                    AppDatabase.getInstance(context).scheduleDao()
                ).also { INSTANCE = it }
            }

        /** 课程名 -> 稳定颜色索引（0..色板数-1，对应 CourseBlockPalettes 十二组派生色）。 */
        fun colorIndexFor(name: String): Int {
            var h = 0
            for (ch in name) h = h * 31 + ch.code
            val n = CourseBlockPalettes.size
            return ((h % n) + n) % n
        }

        /** 从期望索引起找第一个未被占用的色板索引（课程数 ≤ 色板数时保证互不相同）。 */
        private fun freeColorIndex(desired: Int, used: Set<Int>): Int {
            val n = CourseBlockPalettes.size
            var i = ((desired % n) + n) % n
            while (i in used) i = (i + 1) % n
            return i
        }
    }
}
