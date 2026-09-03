package com.saltfish.simple.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** 每张课表的课程数（管理页列表展示）。 */
data class TimetableCourseCount(val timetableId: Long, val courseCount: Int)

@Dao
interface ScheduleDao {

    @Query(
        """
        SELECT e.id AS entryId, c.id AS courseId, c.name AS courseName, c.type AS type,
               c.credit AS credit, c.colorIndex AS colorIndex,
               e.dayOfWeek, e.startSection, e.endSection, e.weeksCsv,
               e.campus, e.building, e.room, e.teacher
        FROM schedule_entries e JOIN courses c ON c.id = e.courseId
        WHERE c.hidden = 0 AND c.timetableId = :timetableId
        ORDER BY e.dayOfWeek, e.startSection
        """
    )
    fun observeAllEntries(timetableId: Long): Flow<List<EntryWithCourse>>

    @Query(
        """
        SELECT e.id AS entryId, c.id AS courseId, c.name AS courseName, c.type AS type,
               c.credit AS credit, c.colorIndex AS colorIndex,
               e.dayOfWeek, e.startSection, e.endSection, e.weeksCsv,
               e.campus, e.building, e.room, e.teacher
        FROM schedule_entries e JOIN courses c ON c.id = e.courseId
        WHERE c.hidden = 0 AND c.timetableId = :timetableId
        ORDER BY e.dayOfWeek, e.startSection
        """
    )
    suspend fun getAllEntries(timetableId: Long): List<EntryWithCourse>

    @Query("SELECT * FROM courses WHERE hidden = 0 AND timetableId = :timetableId ORDER BY name")
    fun observeCourses(timetableId: Long): Flow<List<CourseEntity>>

    @Query("SELECT * FROM timetables ORDER BY createdAt, id")
    fun observeTimetables(): Flow<List<TimetableEntity>>

    @Query("SELECT * FROM timetables ORDER BY createdAt, id")
    suspend fun getTimetables(): List<TimetableEntity>

    @Query("SELECT * FROM timetables WHERE id = :id LIMIT 1")
    fun observeTimetable(id: Long): Flow<TimetableEntity?>

    @Query("SELECT * FROM timetables WHERE id = :id LIMIT 1")
    suspend fun getTimetable(id: Long): TimetableEntity?

    @Query("SELECT COUNT(*) FROM timetables")
    suspend fun timetableCount(): Int

    @Query(
        """SELECT c.timetableId AS timetableId, COUNT(*) AS courseCount
           FROM courses c WHERE c.hidden = 0 GROUP BY c.timetableId"""
    )
    fun observeCourseCounts(): Flow<List<TimetableCourseCount>>

    @Query("SELECT * FROM courses WHERE name = :name AND timetableId = :timetableId LIMIT 1")
    suspend fun findCourseByName(name: String, timetableId: Long): CourseEntity?

    @Query("SELECT colorIndex FROM courses WHERE hidden = 0 AND timetableId = :timetableId")
    suspend fun usedColorIndices(timetableId: Long): List<Int>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCourse(course: CourseEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEntries(entries: List<ScheduleEntryEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEntry(entry: ScheduleEntryEntity): Long

    @Query("DELETE FROM courses WHERE timetableId = :timetableId")
    suspend fun clearTimetableCourses(timetableId: Long)

    // ---- 编辑 ----

    @Query("UPDATE courses SET name = :name, updatedAt = :updatedAt WHERE id = :courseId")
    suspend fun updateCourseName(courseId: Long, name: String, updatedAt: Long)

    @Query(
        """UPDATE schedule_entries
           SET teacher = :teacher, campus = :campus, building = :building, room = :room
           WHERE id = :entryId"""
    )
    suspend fun updateEntryInfo(
        entryId: Long, teacher: String, campus: String, building: String, room: String,
    )

    @Query(
        """UPDATE schedule_entries
           SET dayOfWeek = :dayOfWeek, startSection = :startSection, endSection = :endSection
           WHERE id = :entryId"""
    )
    suspend fun updateEntryTime(
        entryId: Long, dayOfWeek: Int, startSection: Int, endSection: Int,
    )

    @Query("DELETE FROM schedule_entries WHERE id = :entryId")
    suspend fun deleteEntry(entryId: Long)

    // ---- 课表管理 ----

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTimetable(timetable: TimetableEntity): Long

    @Update
    suspend fun updateTimetable(timetable: TimetableEntity)

    @Query("DELETE FROM timetables WHERE id = :timetableId")
    suspend fun deleteTimetable(timetableId: Long)

    /** 导入/替换：事务内清空指定课表的课程（条目级联清除）再写入。 */
    @Transaction
    suspend fun replaceTimetable(
        timetableId: Long,
        items: List<Pair<CourseEntity, List<ScheduleEntryEntity>>>,
    ) {
        clearTimetableCourses(timetableId)
        for ((course, entries) in items) {
            val id = insertCourse(course.copy(timetableId = timetableId))
            insertEntries(entries.map { it.copy(courseId = id) })
        }
    }

    /** 复制课表课程：courseId 重映射、周次重置为整学期。 */
    @Transaction
    suspend fun copyCourses(
        sourceTimetableId: Long,
        targetTimetableId: Long,
        colorIndexFor: (String) -> Int,
        totalWeeks: Int,
    ) {
        val oldCourses = getAllCourses(sourceTimetableId)
        for (old in oldCourses) {
            val newId = insertCourse(
                old.copy(
                    id = 0,
                    timetableId = targetTimetableId,
                    colorIndex = colorIndexFor(old.name),
                )
            )
            val fullWeeksCsv = (1..totalWeeks).joinToString(",")
            insertEntries(
                getEntriesOfCourse(old.id).map { it.copy(id = 0, courseId = newId, weeksCsv = fullWeeksCsv) }
            )
        }
    }

    @Query("SELECT * FROM courses WHERE timetableId = :timetableId")
    suspend fun getAllCourses(timetableId: Long): List<CourseEntity>

    @Query("SELECT * FROM schedule_entries WHERE courseId = :courseId")
    suspend fun getEntriesOfCourse(courseId: Long): List<ScheduleEntryEntity>

    /** 整体替换课表（导入覆盖）：事务内清空指定课表再写入；每个课程携带其条目列表。 */
    @Deprecated("改用 replaceTimetable（多课表）", ReplaceWith("replaceTimetable(timetableId, items)"))
    @Transaction
    suspend fun replaceAll(items: List<Pair<CourseEntity, List<ScheduleEntryEntity>>>) {
        replaceTimetable(1L, items)
    }
}
