package com.pleos.cooldown.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 앱 로컬 DB. v2에서 F4 Trip·Intervention을 더했다.
 * 출시 전이라 스키마가 바뀌면 지우고 다시 만든다 (기준선도 처음부터 다시 배운다).
 */
@Database(
    entities = [BaselineEntity::class, ComfortProfileEntity::class, TripEntity::class, InterventionEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class CopilotDatabase : RoomDatabase() {

    abstract fun baselineDao(): BaselineDao

    abstract fun comfortProfileDao(): ComfortProfileDao

    abstract fun tripDao(): TripDao

    companion object {
        fun create(context: Context): CopilotDatabase =
            Room.databaseBuilder(context.applicationContext, CopilotDatabase::class.java, "copilot.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
