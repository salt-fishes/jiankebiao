package com.example.composeapp.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduleDao {

    @Query(
        """
        SELECT e.id AS entryId, c.id AS courseId, c.name AS courseName, c.type AS type,
               c.credit AS credit, c.colorIndex AS colorIndex,
               e.dayOfWeek, e.startSection, e.endSection, e.weeksCsv,
               e.campus, e.building, e.room, e.teacher
        FROM schedule_entries e JOIN courses c ON c.id = e.courseId
        WHERE c.hidden = 0
        ORDER BY e.dayOfWeek, e.startSection
        """
    )
    fun observeAllEntries(): Flow<List<EntryWithCourse>>

    @Query("SELECT * FROM courses WHERE hidden = 0 ORDER BY name")
    fun observeCourses(): Flow<List<CourseEntity>>

    @Query("SELECT * FROM courses WHERE name = :name LIMIT 1")
    suspend fun findCourseByName(name: String): CourseEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCourse(course: CourseEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEntries(entries: List<ScheduleEntryEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEntry(entry: ScheduleEntryEntity): Long

    @Query("DELETE FROM courses")
    suspend fun clearCourses()

    @Query("DELETE FROM schedule_entries")
    suspend fun clearEntries()

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

    @Query("DELETE FROM schedule_entries WHERE id = :entryId")
    suspend fun deleteEntry(entryId: Long)

    /** 整体替换课表（导入覆盖）：事务内清空再写入；每个课程携带其条目列表。 */
    @Transaction
    suspend fun replaceAll(items: List<Pair<CourseEntity, List<ScheduleEntryEntity>>>) {
        clearEntries()
        clearCourses()
        for ((course, entries) in items) {
            val id = insertCourse(course)
            insertEntries(entries.map { it.copy(courseId = id) })
        }
    }
}
