package com.pleos.cooldown.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/**
 * 기준선 한 행 (명세 7.3, C0-14). 운전자 프로필마다 앱 데이터가 따로라서 행은 하나만 둔다.
 */
@Entity(tableName = "baseline")
data class BaselineEntity(
    @PrimaryKey val id: Int = SINGLE_ROW_ID,
    val json: String,
    val totalTrips: Int,
    val totalMinutes: Double,
) {
    companion object {
        const val SINGLE_ROW_ID = 0
    }
}

@Dao
interface BaselineDao {
    @Query("SELECT * FROM baseline WHERE id = ${BaselineEntity.SINGLE_ROW_ID}")
    suspend fun get(): BaselineEntity?

    @Upsert
    suspend fun upsert(entity: BaselineEntity)

    @Query("DELETE FROM baseline")
    suspend fun clear()
}
