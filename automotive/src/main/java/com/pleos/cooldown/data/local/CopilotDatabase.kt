package com.pleos.cooldown.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 앱 로컬 DB. v2에서 F4 Trip·Intervention을 더했고, v3에서 개입 효과(`recoveryMs`)를 더했다.
 * v2 → v3은 열 하나만 늘어서 기록을 지키며 옮긴다. 그 밖의 스키마 변경은 지우고 다시 만든다.
 */
@Database(
    entities = [BaselineEntity::class, ComfortProfileEntity::class, TripEntity::class, InterventionEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class CopilotDatabase : RoomDatabase() {

    abstract fun baselineDao(): BaselineDao

    abstract fun comfortProfileDao(): ComfortProfileDao

    abstract fun tripDao(): TripDao

    companion object {
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE intervention ADD COLUMN recoveryMs INTEGER")
            }
        }

        fun create(context: Context): CopilotDatabase =
            Room.databaseBuilder(context.applicationContext, CopilotDatabase::class.java, "copilot.db")
                .addMigrations(MIGRATION_2_3)
                .fallbackToDestructiveMigration()
                .build()
    }
}
