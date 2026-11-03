package com.pleos.cooldown.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import com.pleos.cooldown.domain.cooldown.BandComfort
import com.pleos.cooldown.domain.cooldown.ComfortProfile
import com.pleos.cooldown.domain.cooldown.SeasonBand

/** 계절 구간당 한 행 (명세 7.3, F2-14) */
@Entity(tableName = "comfort_profile")
data class ComfortProfileEntity(
    @PrimaryKey val band: String,
    val level: Int,
    val baselineTempC: Float?,
    val weakSignals: Int,
    val positiveStreak: Int,
)

@Dao
interface ComfortProfileDao {
    @Query("SELECT * FROM comfort_profile")
    suspend fun getAll(): List<ComfortProfileEntity>

    @Upsert
    suspend fun upsertAll(entities: List<ComfortProfileEntity>)

    @Query("DELETE FROM comfort_profile")
    suspend fun clear()
}

class ComfortProfileRepository(private val dao: ComfortProfileDao) {

    suspend fun load(): ComfortProfile {
        val stored = dao.getAll().mapNotNull { e ->
            val band = SeasonBand.entries.firstOrNull { it.name == e.band } ?: return@mapNotNull null
            band to BandComfort(
                level = e.level.coerceIn(0, band.defaultLevel),
                baselineTempC = e.baselineTempC,
                weakSignals = e.weakSignals,
                positiveStreak = e.positiveStreak,
            )
        }.toMap()
        return ComfortProfile(ComfortProfile.DEFAULT.bands + stored)
    }

    suspend fun save(profile: ComfortProfile) {
        dao.upsertAll(
            profile.bands.map { (band, c) ->
                ComfortProfileEntity(band.name, c.level, c.baselineTempC, c.weakSignals, c.positiveStreak)
            }
        )
    }

    /** "운전 습관 다시 배우기" (S1-03) */
    suspend fun reset() {
        dao.clear()
    }
}
