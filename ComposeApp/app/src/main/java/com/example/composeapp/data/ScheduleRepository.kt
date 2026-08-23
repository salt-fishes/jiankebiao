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
        for (c in parsed.courses) {
            byName[c.name] = CourseEntity(
                name = c.name,
                type = c.type,
                credit = c.credit,
                classNo = c.classNo,
                composition = c.composition,
                colorIndex = colorIndexFor(c.name),
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
            courseId = dao.insertCourse(
                CourseEntity(
                    name = n,
                    type = "",
                    credit = "",
                    classNo = "",
                    composition = "",
                    colorIndex = colorIndexFor(n),
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

        /** 课程名 -> 稳定颜色索引（0..9，对应 CourseBlockPalettes 十组派生色）。 */
        fun colorIndexFor(name: String): Int {
            var h = 0
            for (ch in name) h = h * 31 + ch.code
            return ((h % 10) + 10) % 10
        }
    }
}
