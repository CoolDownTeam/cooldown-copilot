package com.pleos.cooldown.domain.engine

import com.pleos.cooldown.domain.model.Baseline
import com.pleos.cooldown.domain.model.DrivingFeatures
import com.pleos.cooldown.domain.model.FeatureId
import com.pleos.cooldown.domain.model.FeatureStat
import com.pleos.cooldown.domain.model.SpeedBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StateScorerTest {

    private var hour = 14
    private val scorer = StateScorer(hourOfDay = { hour })

    private val defaults = Baseline.DEFAULT.bySpeedBand.getValue(SpeedBand.URBAN)
    private val learned = Baseline.DEFAULT.copy(totalTrips = 10, totalDrivingMinutes = 300.0)

    @Test
    fun `features equal to the baseline mean score zero`() {
        val state = scorer.score(features(defaults.mapValues { it.value.mean }), learned)

        assertEquals(0, state.aggression)
        assertEquals(0, state.fatigue)
    }

    @Test
    fun `score follows 100 x (1 - e^(-sum w z over 2))`() {
        // 급가속만 z = (1.1 − 0.3) / 0.4 = 2 → Σ w·z = 0.6 → 100 × (1 − e^−0.3) = 25.9
        val values = defaults.mapValues { it.value.mean } + (FeatureId.HARD_ACCEL_PER_MIN to 1.1)

        assertEquals(26, scorer.score(features(values), learned).aggression)
    }

    @Test
    fun `smoother than usual does not lower the score below zero`() {
        val values = defaults.mapValues { it.value.mean } + (FeatureId.HARD_ACCEL_PER_MIN to 0.0)

        assertEquals(0, scorer.score(features(values), learned).aggression)
    }

    @Test
    fun `C0-13 tiny std is floored by minStd`() {
        val flat = learned.copy(
            bySpeedBand = learned.bySpeedBand + (SpeedBand.URBAN to defaults + (
                FeatureId.HARD_ACCEL_PER_MIN to FeatureStat(mean = 0.3, variance = 1e-6, samples = 10)
            ))
        )
        // minStd 0.2로 나누면 z = 0.1 / 0.2 = 0.5 → 7점. 하한이 없으면 z = 100 → 100점
        val values = defaults.mapValues { it.value.mean } + (FeatureId.HARD_ACCEL_PER_MIN to 0.4)

        assertEquals(7, scorer.score(features(values), flat).aggression)
    }

    @Test
    fun `C0-23 the same driving scores lower for a habitually rough driver`() {
        val rough = learned.copy(
            bySpeedBand = learned.bySpeedBand + (SpeedBand.URBAN to defaults + mapOf(
                FeatureId.HARD_ACCEL_PER_MIN to FeatureStat(mean = 2.0, variance = 1.0, samples = 10),
                FeatureId.HARD_DECEL_PER_MIN to FeatureStat(mean = 2.0, variance = 1.0, samples = 10),
            ))
        )
        val values = defaults.mapValues { it.value.mean } +
            (FeatureId.HARD_ACCEL_PER_MIN to 2.5) + (FeatureId.HARD_DECEL_PER_MIN to 2.5)

        val calmDriverScore = scorer.score(features(values), learned).aggression
        val roughDriverScore = scorer.score(features(values), rough).aggression

        assertTrue("calm=$calmDriverScore rough=$roughDriverScore", roughDriverScore < calmDriverScore)
        assertTrue(calmDriverScore >= 60)
        assertTrue(roughDriverScore < 40)
    }

    @Test
    fun `C0-10 learning mode compares against the built-in defaults`() {
        val personal = Baseline.DEFAULT.copy(
            bySpeedBand = Baseline.DEFAULT.bySpeedBand + (SpeedBand.URBAN to defaults + (
                FeatureId.HARD_ACCEL_PER_MIN to FeatureStat(mean = 3.0, variance = 1.0, samples = 2)
            )),
            totalTrips = 2,
            totalDrivingMinutes = 40.0,
        )
        val values = defaults.mapValues { it.value.mean } + (FeatureId.HARD_ACCEL_PER_MIN to 3.0)

        val state = scorer.score(features(values), personal)

        assertTrue(state.learning)
        assertEquals(scorer.score(features(values), Baseline.DEFAULT).aggression, state.aggression)
        assertTrue(state.aggression > 0)
    }

    @Test
    fun `missing features do not count`() {
        val state = scorer.score(features(emptyMap()), learned)

        assertEquals(0, state.aggression)
        assertEquals(0, state.fatigue)
    }

    @Test
    fun `fatigue rises with fewer micro corrections, held wheel, long driving and night`() {
        val drowsy = defaults.mapValues { it.value.mean } +
            (FeatureId.STEER_MICRO_CORRECTION_PER_MIN to 0.0) +
            (FeatureId.STEER_HOLD_RATIO to 0.95)
        hour = 2

        val state = scorer.score(features(drowsy, continuousMin = 120), learned)

        assertTrue("fatigue=${state.fatigue}", state.fatigue >= 60)
        assertEquals(0, state.aggression)
    }

    @Test
    fun `fatigue can reach the F3 threshold on the highway`() {
        val highway = Baseline.DEFAULT.bySpeedBand.getValue(SpeedBand.HIGHWAY).mapValues { it.value.mean } +
            (FeatureId.STEER_MICRO_CORRECTION_PER_MIN to 1.0) +
            (FeatureId.STEER_HOLD_RATIO to 0.95)
        hour = 14

        val state = scorer.score(features(highway, continuousMin = 120).copy(speedBand = SpeedBand.HIGHWAY), learned)

        // 미세 보정 z 1.5, 정지 비율 z 1.5, 연속 2시간 → Σ 1.2 → 70점
        assertEquals(70, state.fatigue)
    }

    @Test
    fun `continuous driving alone is capped`() {
        hour = 14
        val values = defaults.mapValues { it.value.mean }

        val twoHours = scorer.score(features(values, continuousMin = 120), learned).fatigue
        val tenHours = scorer.score(features(values, continuousMin = 600), learned).fatigue

        // 피로는 /1: 0.30 × 1.0 → 26점, 상한 1.5 → 0.30 × 1.5 → 36점
        assertEquals(26, twoHours)
        assertEquals(36, tenHours)
    }

    @Test
    fun `night counts from 22 to 6`() {
        val values = defaults.mapValues { it.value.mean }
        hour = 22
        val night = scorer.score(features(values), learned).fatigue
        hour = 6
        val morning = scorer.score(features(values), learned).fatigue

        assertEquals(10, night)
        assertEquals(0, morning)
        assertFalse(scorer.score(features(values), learned).learning)
    }

    private fun features(values: Map<FeatureId, Double>, continuousMin: Long = 0) = DrivingFeatures(
        timestampMs = 0L,
        speedBand = SpeedBand.URBAN,
        windowMs = 180_000,
        accelerationMs2 = 0.0,
        continuousDrivingMs = continuousMin * 60_000,
        values = values,
    )
}
