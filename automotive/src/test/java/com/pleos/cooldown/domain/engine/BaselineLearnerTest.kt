package com.pleos.cooldown.domain.engine

import com.pleos.cooldown.domain.model.Baseline
import com.pleos.cooldown.domain.model.DrivingFeatures
import com.pleos.cooldown.domain.model.FeatureId
import com.pleos.cooldown.domain.model.SpeedBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BaselineLearnerTest {

    private val learner = BaselineLearner()
    private val id = FeatureId.HARD_ACCEL_PER_MIN

    @Test
    fun `C0-10 learning mode until 5 trips and 60 minutes`() {
        assertTrue(Baseline.DEFAULT.learning)
        assertTrue(Baseline.DEFAULT.copy(totalTrips = 5, totalDrivingMinutes = 59.0).learning)
        assertTrue(Baseline.DEFAULT.copy(totalTrips = 4, totalDrivingMinutes = 300.0).learning)
        assertFalse(Baseline.DEFAULT.copy(totalTrips = 5, totalDrivingMinutes = 60.0).learning)
    }

    @Test
    fun `C0-11 updates mean and variance with alpha 0_2`() {
        val before = Baseline.DEFAULT.stat(SpeedBand.URBAN, id) ?: error("missing default")
        // 이번 주행 창들: 절반은 1.0, 절반은 3.0 → 평균 2.0, 분산 1.0
        recordSeconds(count = 120, startMs = 0) { i -> if (i % 2 == 0) 1.0 else 3.0 }

        val after = learner.finishTrip(Baseline.DEFAULT, endMs = 120_000).stat(SpeedBand.URBAN, id) ?: error("missing")

        assertEquals(0.8 * before.mean + 0.2 * 2.0, after.mean, 1e-9)
        assertEquals(0.8 * before.variance + 0.2 * 1.0, after.variance, 1e-9)
        assertEquals(1, after.samples)
    }

    @Test
    fun `counts trips and driving minutes`() {
        recordSeconds(count = 120, startMs = 1_000) { 0.3 }
        val after = learner.finishTrip(Baseline.DEFAULT, endMs = 1_000 + 30 * 60_000)

        assertEquals(1, after.totalTrips)
        assertEquals(30.0, after.totalDrivingMinutes, 1e-9)
    }

    @Test
    fun `C0-12 excludes the intervention and the 3 minutes before it`() {
        // 0~5분: 평소(0.3), 5~8분: 거칠어짐(5.0), 8분에 개입 시작, 10분에 끝, 10~15분: 평소
        recordSeconds(count = 300, startMs = 0) { 0.3 }
        recordSeconds(count = 180, startMs = 300_000) { 5.0 }
        learner.setIntervening(true, nowMs = 480_000)
        recordSeconds(count = 120, startMs = 480_000) { 9.0 }
        learner.setIntervening(false, nowMs = 600_000)
        recordSeconds(count = 300, startMs = 600_000) { 0.3 }

        val before = Baseline.DEFAULT.stat(SpeedBand.URBAN, id) ?: error("missing")
        val after = learner.finishTrip(Baseline.DEFAULT, endMs = 900_000).stat(SpeedBand.URBAN, id) ?: error("missing")

        assertEquals(0.8 * before.mean + 0.2 * 0.3, after.mean, 1e-9)
    }

    @Test
    fun `skips a speed band with less than a minute of data`() {
        recordSeconds(count = 30, startMs = 0) { 5.0 }

        val after = learner.finishTrip(Baseline.DEFAULT, endMs = 30_000)

        assertEquals(Baseline.DEFAULT.bySpeedBand, after.bySpeedBand)
        assertEquals(1, after.totalTrips)
    }

    @Test
    fun `only the driven speed band changes`() {
        recordSeconds(count = 120, startMs = 0) { 2.0 }

        val after = learner.finishTrip(Baseline.DEFAULT, endMs = 120_000)

        assertEquals(Baseline.DEFAULT.bySpeedBand[SpeedBand.HIGHWAY], after.bySpeedBand[SpeedBand.HIGHWAY])
        assertEquals(Baseline.DEFAULT.bySpeedBand[SpeedBand.RURAL], after.bySpeedBand[SpeedBand.RURAL])
    }

    private fun recordSeconds(count: Int, startMs: Long, value: (Int) -> Double) {
        repeat(count) { i ->
            learner.record(
                DrivingFeatures(
                    timestampMs = startMs + i * 1_000L,
                    speedBand = SpeedBand.URBAN,
                    windowMs = 180_000,
                    accelerationMs2 = 0.0,
                    continuousDrivingMs = 0,
                    values = mapOf(id to value(i)),
                )
            )
        }
    }
}
