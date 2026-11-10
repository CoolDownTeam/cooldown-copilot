package com.pleos.cooldown.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import com.pleos.cooldown.domain.report.InterventionKind
import com.pleos.cooldown.domain.report.InterventionRecord
import com.pleos.cooldown.domain.report.InterventionResult
import com.pleos.cooldown.domain.report.TripRecord
import com.pleos.cooldown.domain.report.TripWithInterventions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 주행 1회 요약 (명세 7.3) */
@Entity(tableName = "trip")
data class TripEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startMs: Long,
    val endMs: Long,
    val distanceKm: Float?,
    val avgAggression: Int,
    val maxAggression: Int,
    val avgFatigue: Int,
    val maxFatigue: Int,
    val learning: Boolean,
    val learnedTrips: Int,
    val hardSections: Int?,
    val longestCalmMs: Long,
)

@Entity(
    tableName = "intervention",
    foreignKeys = [ForeignKey(entity = TripEntity::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("tripId")],
)
data class InterventionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val startMs: Long,
    val endMs: Long?,
    /** COOLDOWN / FRESH_AIR / FATIGUE_HOLD_ON / FATIGUE_SUGGEST */
    val kind: String,
    val maxStage: Int,
    /** DONE / COLD / STOPPED / YIELDED / FAILED / ACCEPTED / DECLINED / RESTED */
    val outcome: String,
    val levelChange: Int,
    /** v3: 쿨다운 뒤 차분해지기까지 걸린 시간 */
    val recoveryMs: Long? = null,
)

@Dao
interface TripDao {
    @Insert
    suspend fun insertTrip(trip: TripEntity): Long

    @Insert
    suspend fun insertInterventions(list: List<InterventionEntity>)

    @Transaction
    suspend fun insert(trip: TripEntity, interventions: List<InterventionEntity>): Long {
        val id = insertTrip(trip)
        insertInterventions(interventions.map { it.copy(tripId = id) })
        return id
    }

    @Query("SELECT * FROM trip ORDER BY startMs DESC LIMIT :limit")
    fun recentTrips(limit: Int): Flow<List<TripEntity>>

    @Query("SELECT * FROM trip WHERE id = :id")
    suspend fun trip(id: Long): TripEntity?

    @Query("SELECT * FROM intervention WHERE tripId = :tripId ORDER BY startMs")
    suspend fun interventions(tripId: Long): List<InterventionEntity>

    @Query("SELECT * FROM intervention WHERE tripId IN (:tripIds) ORDER BY startMs")
    fun interventionsOf(tripIds: List<Long>): Flow<List<InterventionEntity>>

    @Query("DELETE FROM trip")
    suspend fun clear()
}

/** F4 주행 기록 저장소. "기록 전체 삭제"는 [clear] (S1-03) */
class TripRepository(private val dao: TripDao) {

    suspend fun save(record: TripWithInterventions): Long =
        dao.insert(record.trip.toEntity(), record.interventions.map { it.toEntity(tripId = 0) })

    fun recent(limit: Int): Flow<List<TripRecord>> = dao.recentTrips(limit).map { list -> list.map { it.toRecord() } }

    fun interventionsOf(tripIds: List<Long>): Flow<Map<Long, List<InterventionRecord>>> =
        dao.interventionsOf(tripIds).map { list -> list.groupBy({ it.tripId }, { it.toRecord() }) }

    suspend fun load(id: Long): TripWithInterventions? {
        val trip = dao.trip(id) ?: return null
        return TripWithInterventions(trip.toRecord(), dao.interventions(id).map { it.toRecord() })
    }

    suspend fun clear() = dao.clear()
}

private fun TripRecord.toEntity() = TripEntity(
    startMs = startMs, endMs = endMs, distanceKm = distanceKm,
    avgAggression = avgAggression, maxAggression = maxAggression,
    avgFatigue = avgFatigue, maxFatigue = maxFatigue,
    learning = learning, learnedTrips = learnedTrips,
    hardSections = hardSections, longestCalmMs = longestCalmMs,
)

private fun TripEntity.toRecord() = TripRecord(
    id = id, startMs = startMs, endMs = endMs, distanceKm = distanceKm,
    avgAggression = avgAggression, maxAggression = maxAggression,
    avgFatigue = avgFatigue, maxFatigue = maxFatigue,
    learning = learning, learnedTrips = learnedTrips,
    hardSections = hardSections, longestCalmMs = longestCalmMs,
)

private fun InterventionRecord.toEntity(tripId: Long) = InterventionEntity(
    tripId = tripId, startMs = startMs, endMs = endMs, kind = kind.name,
    maxStage = maxStage, outcome = result.name, levelChange = levelChange, recoveryMs = recoveryMs,
)

private fun InterventionEntity.toRecord() = InterventionRecord(
    startMs = startMs, endMs = endMs,
    kind = InterventionKind.entries.firstOrNull { it.name == kind } ?: InterventionKind.COOLDOWN,
    maxStage = maxStage,
    result = InterventionResult.entries.firstOrNull { it.name == outcome } ?: InterventionResult.DONE,
    levelChange = levelChange,
    recoveryMs = recoveryMs,
)
