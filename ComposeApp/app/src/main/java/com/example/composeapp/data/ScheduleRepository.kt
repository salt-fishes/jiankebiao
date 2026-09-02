package com.example.composeapp.data

import android.content.Context
import com.example.composeapp.schedule.ParsedSchedule
import kotlinx.coroutines.flow.Flow

/**
 * 课表仓库：负责解析结果的持久化与查询。
 * 颜色索引按课程名稳定哈希分配（0..2），同一课程在列表/网格/小组件中颜色一致。
 */
class ScheduleRepository(private val dao: ScheduleDao) {

    fun observeAllEntries(): Flow<List<EntryWithCourse>> = dao.observeAllEntries()

    fun observeCourses(): Flow<List<CourseEntity>> = dao.observeCourses()

    /** 整体替换课表（导入覆盖）。 */
    suspend fun importSchedule(parsed: ParsedSchedule) {
        val byName = LinkedHashMap<String, CourseEntity>()
        val entriesByCourse = LinkedHashMap<String, MutableList<ScheduleEntryEntity>>()
        // 颜色唯一分配：不同课程名拿到不同色板索引（色板 12 组，超出部分才回用）
        val usedColors = mutableSetOf<Int>()
        for (c in parsed.courses) {
            val colorIndex = freeColorIndex(colorIndexFor(c.name), usedColors)
            usedColors.add(colorIndex)
            byName[c.name] = CourseEntity(
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
                    courseId = 0L, // replaceAll 事务内回填
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
        dao.replaceAll(items)
    }

    /** 清除全部课表数据。 */
    suspend fun clearAll() = dao.replaceAll(emptyList())

    /** 编辑课程名（影响该课全部条目的显示）。 */
    suspend fun renameCourse(courseId: Long, name: String) =
        dao.updateCourseName(courseId, name.trim(), System.currentTimeMillis())

    /** 编辑某节排课的教师与地点。 */
    suspend fun updateEntryInfo(
        entryId: Long, teacher: String, campus: String, building: String, room: String,
    ) = dao.updateEntryInfo(
        entryId,
        teacher.trim(), campus.trim(), building.trim(), room.trim(),
    )

    /** 长按拖拽移动排课：改星期与起止节次（保持时长由调用方计算好）。 */
    suspend fun moveEntry(entryId: Long, dayOfWeek: Int, startSection: Int, endSection: Int) =
        dao.updateEntryTime(entryId, dayOfWeek, startSection, endSection)

    /** 删除单条排课。 */
    suspend fun deleteEntry(entryId: Long) = dao.deleteEntry(entryId)

    /**
     * 新增一条排课（课程名已存在则复用，否则新建课程）。
     * @return 新增条目的 id
     */
    suspend fun addEntry(
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
        var courseId = dao.findCourseByName(n)?.id
        if (courseId == null) {
            // 新课程颜色避开已占用的色板索引
            val used = runCatching { dao.usedColorIndices() }.getOrDefault(emptyList())
            courseId = dao.insertCourse(
                CourseEntity(
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
            val n = com.example.composeapp.ui.theme.CourseBlockPalettes.size
            return ((h % n) + n) % n
        }

        /** 从期望索引起找第一个未被占用的色板索引（课程数 ≤ 色板数时保证互不相同）。 */
        private fun freeColorIndex(desired: Int, used: Set<Int>): Int {
            val n = com.example.composeapp.ui.theme.CourseBlockPalettes.size
            var i = ((desired % n) + n) % n
            while (i in used) i = (i + 1) % n
            return i
        }
    }
}
