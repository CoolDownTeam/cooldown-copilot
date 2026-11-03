package com.pleos.cooldown.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 앱 로컬 DB. Trip·Intervention 엔티티는 F4 작업 때 추가한다.
 */
@Database(entities = [BaselineEntity::class, ComfortProfileEntity::class], version = 1, exportSchema = false)
abstract class CopilotDatabase : RoomDatabase() {

    abstract fun baselineDao(): BaselineDao

    abstract fun comfortProfileDao(): ComfortProfileDao

    companion object {
        fun create(context: Context): CopilotDatabase =
            Room.databaseBuilder(context.applicationContext, CopilotDatabase::class.java, "copilot.db").build()
    }
}
