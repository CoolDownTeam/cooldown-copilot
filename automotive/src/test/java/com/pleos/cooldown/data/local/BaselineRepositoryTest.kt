package com.pleos.cooldown.data.local

import com.pleos.cooldown.domain.model.Baseline
import com.pleos.cooldown.domain.model.FeatureId
import com.pleos.cooldown.domain.model.FeatureStat
import com.pleos.cooldown.domain.model.SpeedBand
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BaselineRepositoryTest {

    private val dao = FakeBaselineDao()
    private val repository = BaselineRepository(dao)

    @Test
    fun `C0-14 loads the built-in defaults when nothing is stored`() = runTest {
        assertEquals(Baseline.DEFAULT, repository.load())
    }

    @Test
    fun `C0-14 round trips a saved baseline`() = runTest {
        val urban = Baseline.DEFAULT.bySpeedBand.getValue(SpeedBand.URBAN) +
            (FeatureId.HARD_ACCEL_PER_MIN to FeatureStat(mean = 1.25, variance = 0.5, samples = 7))
        val baseline = Baseline(
            bySpeedBand = Baseline.DEFAULT.bySpeedBand + (SpeedBand.URBAN to urban),
            totalTrips = 7,
            totalDrivingMinutes = 123.5,
        )

        repository.save(baseline)

        assertEquals(baseline, repository.load())
    }

    @Test
    fun `fills features missing from storage with defaults`() = runTest {
        dao.upsert(BaselineEntity(json = "URBAN|HARD_ACCEL_PER_MIN|1.0|0.25|3", totalTrips = 3, totalMinutes = 30.0))

        val loaded = repository.load()

        assertEquals(FeatureStat(1.0, 0.25, 3), loaded.stat(SpeedBand.URBAN, FeatureId.HARD_ACCEL_PER_MIN))
        assertEquals(
            Baseline.DEFAULT.stat(SpeedBand.HIGHWAY, FeatureId.STEER_RATE_STD),
            loaded.stat(SpeedBand.HIGHWAY, FeatureId.STEER_RATE_STD)
        )
        assertEquals(3, loaded.totalTrips)
    }

    @Test
    fun `C0-15 reset clears storage`() = runTest {
        repository.save(Baseline.DEFAULT.copy(totalTrips = 9))

        repository.reset()

        assertNull(dao.get())
        assertEquals(Baseline.DEFAULT, repository.load())
    }

    @Test
    fun `codec skips broken and unknown lines`() {
        val decoded = BaselineCodec.decode(
            """
            URBAN|HARD_ACCEL_PER_MIN|1.0|0.25|3
            URBAN|NOT_A_FEATURE|1.0|0.25|3
            SPACE|HARD_ACCEL_PER_MIN|1.0|0.25|3
            URBAN|HARD_DECEL_PER_MIN|abc|0.25|3
            garbage
            """.trimIndent()
        )

        assertEquals(mapOf(SpeedBand.URBAN to mapOf(FeatureId.HARD_ACCEL_PER_MIN to FeatureStat(1.0, 0.25, 3))), decoded)
    }

    private class FakeBaselineDao : BaselineDao {
        private var row: BaselineEntity? = null
        override suspend fun get(): BaselineEntity? = row
        override suspend fun upsert(entity: BaselineEntity) {
            row = entity
        }
        override suspend fun clear() {
            row = null
        }
    }
}
