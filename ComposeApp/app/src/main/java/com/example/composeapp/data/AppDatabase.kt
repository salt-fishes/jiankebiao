package com.example.composeapp.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [TimetableEntity::class, CourseEntity::class, ScheduleEntryEntity::class],
    version = 5,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun scheduleDao(): ScheduleDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * 3 → 4：引入多课表。
         * - 新建 timetables 表，并插入默认课表 id=1（startMillis=0 哨兵，
         *   首启由 SettingsRepository 从旧偏好回填开学日/周数）
         * - courses 增加 timetableId 列（默认 1，历史数据全部归入默认课表）
         * - 唯一索引 name → (timetableId, name)：同名课程允许跨课表
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `timetables` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `startMillis` INTEGER NOT NULL,
                        `totalWeeks` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL)"""
                )
                db.execSQL(
                    """INSERT INTO `timetables` (`id`, `name`, `startMillis`, `totalWeeks`, `createdAt`)
                        VALUES (1, '我的课表', 0, 17, 0)"""
                )
                db.execSQL("ALTER TABLE `courses` ADD COLUMN `timetableId` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("DROP INDEX IF EXISTS `index_courses_name`")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_courses_timetableId_name` " +
                        "ON `courses` (`timetableId`, `name`)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_courses_timetableId` ON `courses` (`timetableId`)")
            }
        }

        /** 4 → 5：作息时间下沉到每张课表（0/空 = 未单独设置，继承全局偏好）。 */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `timetables` ADD COLUMN `sectionsPerDay` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `timetables` ADD COLUMN `sectionTimesCsv` TEXT NOT NULL DEFAULT ''")
            }
        }

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "composeapp.db"
                )
                    .addMigrations(MIGRATION_3_4, MIGRATION_4_5)
                    .build().also { INSTANCE = it }
            }
    }
}
