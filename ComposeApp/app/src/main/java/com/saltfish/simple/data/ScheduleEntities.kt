package com.saltfish.simple.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** 课表（多课表管理的第一实体）：一组课程 + 自己的开学日、总周数与作息时间。 */
@Entity(tableName = "timetables")
data class TimetableEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val startMillis: Long,     // 第 1 周周一 00:00；0 = 待从旧设置回填
    val totalWeeks: Int,
    @ColumnInfo(defaultValue = "0") val sectionsPerDay: Int = 0,        // 0 = 未单独设置（继承全局）
    @ColumnInfo(defaultValue = "") val sectionTimesCsv: String = "",    // 空 = 未单独设置（继承全局）
    val createdAt: Long = System.currentTimeMillis(),
)

/** 课程（课表内按名称唯一）。地点/教师随排课条目存储——同一门课不同天可能不同教室。 */
@Entity(
    tableName = "courses",
    indices = [Index(value = ["timetableId", "name"], unique = true), Index("timetableId")]
)
data class CourseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(defaultValue = "1") val timetableId: Long = 1,  // 所属课表（迁移默认 1）
    val name: String,
    val type: String,          // 讲课/实验/上机/实践/集中实践
    val credit: String,
    val classNo: String,
    val composition: String,
    val colorIndex: Int,       // 0..2，映射 primary/secondary/tertiary-container 色调对
    @ColumnInfo(defaultValue = "0") val hidden: Boolean = false,  // 手动隐藏
    val updatedAt: Long = System.currentTimeMillis(),
)

/** 排课条目：星期几、第几节、哪些周、当天的地点与教师。 */
@Entity(
    tableName = "schedule_entries",
    foreignKeys = [ForeignKey(
        entity = CourseEntity::class,
        parentColumns = ["id"],
        childColumns = ["courseId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("courseId"), Index("dayOfWeek")]
)
data class ScheduleEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val courseId: Long,
    val dayOfWeek: Int,        // 1=星期一 ... 7=星期日
    val startSection: Int?,
    val endSection: Int?,
    val weeksCsv: String,      // 展开的周次，逗号分隔，如 "1,2,3,5"
    val campus: String = "",
    val building: String = "",
    val room: String = "",
    val teacher: String = "",
)

/** 条目 + 课程名聚合视图（UI/小组件直接消费）。 */
data class EntryWithCourse(
    val entryId: Long,
    val courseId: Long,
    val courseName: String,
    val type: String,
    val credit: String,
    val colorIndex: Int,
    val dayOfWeek: Int,
    val startSection: Int?,
    val endSection: Int?,
    val weeksCsv: String,
    val campus: String,
    val building: String,
    val room: String,
    val teacher: String,
) {
    val weeks: Set<Int> get() = weeksCsv.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()

    fun isInWeek(week: Int): Boolean = week in weeks

    /** 是否与 [other] 在节次上有重叠。 */
    fun overlaps(other: EntryWithCourse): Boolean {
        val s1 = startSection ?: 0; val e1 = endSection ?: s1
        val s2 = other.startSection ?: 0; val e2 = other.endSection ?: s2
        return dayOfWeek == other.dayOfWeek && s1 <= e2 && s2 <= e1
    }

    /** "环宇楼A404" 风格的短地点（格子内展示）。 */
    val shortLocation: String get() = room.ifBlank { building.ifBlank { campus } }

    /** 完整地点（详情页展示）。 */
    val fullLocation: String
        get() = listOf(campus, building, room).filter { it.isNotBlank() }.joinToString(" ")

    val sectionRangeLabel: String
        get() = when {
            startSection == null -> ""
            endSection != null && endSection != startSection -> "$startSection-$endSection 节"
            else -> "$startSection 节"
        }
}
