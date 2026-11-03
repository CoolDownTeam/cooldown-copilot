package com.pleos.cooldown.domain.engine

import com.pleos.cooldown.domain.model.DrivingFeatures
import com.pleos.cooldown.domain.model.FeatureId
import com.pleos.cooldown.domain.model.SpeedBand
import com.pleos.cooldown.testutil.DriveSequence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FeatureExtractorTest {

    private lateinit var drive: Drive

    @Before
    fun setUp() {
        drive = Drive(FeatureExtractor())
    }

    @Test
    fun `C0-01 a single noisy speed sample is smoothed out`() {
        drive.cruise(sec = 5.0, kmh = 40.0)
        drive.spike(deltaKmh = 1.0)
        drive.cruise(sec = 2.0, kmh = 40.0)

        assertEquals(0.0, drive.snapshot().value(FeatureId.HARD_ACCEL_PER_MIN), 1e-9)
    }

    @Test
    fun `C0-02 counts one hard acceleration per episode`() {
        drive.cruise(sec = 5.0, kmh = 10.0)
        drive.accelerate(sec = 2.0, ms2 = 3.0)
        assertEquals(1.0, drive.snapshot().value(FeatureId.HARD_ACCEL_PER_MIN), 1e-9)

        drive.accelerate(sec = 2.0, ms2 = -1.0)
        drive.accelerate(sec = 2.0, ms2 = 3.0)
        assertEquals(2.0, drive.snapshot().value(FeatureId.HARD_ACCEL_PER_MIN), 1e-9)
    }

    @Test
    fun `C0-03 hard deceleration needs the brake pedal`() {
        drive.cruise(sec = 5.0, kmh = 50.0)
        drive.accelerate(sec = 1.5, ms2 = -4.0)
        assertEquals(0.0, drive.snapshot().value(FeatureId.HARD_DECEL_PER_MIN), 1e-9)

        drive.cruise(sec = 3.0, kmh = drive.speedKmh)
        drive.accelerate(sec = 1.5, ms2 = -4.0, brake = true)
        assertEquals(1.0, drive.snapshot().value(FeatureId.HARD_DECEL_PER_MIN), 1e-9)
    }

    @Test
    fun `C0-04 accel and brake within 10 seconds is one flip`() {
        drive.cruise(sec = 5.0, kmh = 30.0)
        drive.accelerate(sec = 2.0, ms2 = 1.0)
        drive.cruise(sec = 3.0, kmh = drive.speedKmh)
        drive.accelerate(sec = 1.0, ms2 = -1.0, brake = true)

        assertEquals(1.0, drive.snapshot().value(FeatureId.ACCEL_BRAKE_FLIP_PER_MIN), 1e-9)
    }

    @Test
    fun `C0-04 accel and brake more than 10 seconds apart is not a flip`() {
        drive.cruise(sec = 5.0, kmh = 30.0)
        drive.accelerate(sec = 2.0, ms2 = 1.0)
        drive.cruise(sec = 15.0, kmh = drive.speedKmh)
        drive.accelerate(sec = 1.0, ms2 = -1.0, brake = true)

        assertEquals(0.0, drive.snapshot().value(FeatureId.ACCEL_BRAKE_FLIP_PER_MIN), 1e-9)
    }

    @Test
    fun `C0-05 to 07 micro corrections and steering stats`() {
        drive.cruise(sec = 5.0, kmh = 40.0)
        repeat(5) {
            drive.steerBy(deg = 3.0, sec = 1.0)
            drive.cruise(sec = 2.0, kmh = 40.0)
            drive.steerBy(deg = -3.0, sec = 1.0)
            drive.cruise(sec = 2.0, kmh = 40.0)
        }
        // 30° 큰 회전은 미세 보정이 아니다
        drive.steerBy(deg = 30.0, sec = 2.0)
        drive.cruise(sec = 2.0, kmh = 40.0)

        val features = drive.snapshot()
        assertEquals(10.0, features.value(FeatureId.STEER_MICRO_CORRECTION_PER_MIN), 1e-9)
        assertTrue(features.value(FeatureId.STEER_RATE_STD) > 0.0)
        val holdRatio = features.value(FeatureId.STEER_HOLD_RATIO)
        assertTrue("hold ratio $holdRatio", holdRatio > 0.5 && holdRatio < 1.0)
    }

    @Test
    fun `C0-07 holding the wheel still gives full hold ratio and zero std`() {
        drive.cruise(sec = 30.0, kmh = 40.0)

        val features = drive.snapshot()
        assertEquals(1.0, features.value(FeatureId.STEER_HOLD_RATIO), 1e-9)
        assertEquals(0.0, features.value(FeatureId.STEER_RATE_STD), 1e-9)
    }

    @Test
    fun `steering features are skipped below 5 kmh`() {
        drive.cruise(sec = 5.0, kmh = 3.0)
        drive.steerBy(deg = 3.0, sec = 1.0)
        drive.cruise(sec = 5.0, kmh = 3.0)

        val values = drive.snapshot().values
        assertFalse(values.containsKey(FeatureId.STEER_RATE_STD))
        assertFalse(values.containsKey(FeatureId.STEER_MICRO_CORRECTION_PER_MIN))
        assertFalse(values.containsKey(FeatureId.STEER_HOLD_RATIO))
    }

    @Test
    fun `C0-08 short stops keep counting, long stops reset`() {
        drive.cruise(sec = 600.0, kmh = 50.0)
        drive.cruise(sec = 180.0, kmh = 0.0)
        drive.cruise(sec = 60.0, kmh = 50.0)
        assertEquals(14.0, drive.snapshot().continuousDrivingMs / 60_000.0, 0.01)

        drive.cruise(sec = 360.0, kmh = 0.0)
        assertEquals(0L, drive.snapshot().continuousDrivingMs)

        drive.cruise(sec = 60.0, kmh = 50.0)
        assertEquals(1.0, drive.snapshot().continuousDrivingMs / 60_000.0, 0.01)
    }

    @Test
    fun `changing speed band starts a new window`() {
        drive.cruise(sec = 5.0, kmh = 10.0)
        drive.accelerate(sec = 2.0, ms2 = 3.0)
        assertEquals(1.0, drive.snapshot().value(FeatureId.HARD_ACCEL_PER_MIN), 1e-9)

        drive.gap(ms = 2_000)
        drive.cruise(sec = 5.0, kmh = 100.0)

        val features = drive.snapshot()
        assertEquals(SpeedBand.HIGHWAY, features.speedBand)
        assertEquals(0.0, features.value(FeatureId.HARD_ACCEL_PER_MIN), 1e-9)
    }

    @Test
    fun `speed band does not flip around a boundary`() {
        drive.cruise(sec = 5.0, kmh = 30.0)
        drive.accelerate(sec = 2.0, ms2 = 3.0)
        drive.accelerate(sec = 10.0, ms2 = 0.3)
        drive.cruise(sec = 5.0, kmh = drive.speedKmh)

        val nearBoundary = drive.snapshot()
        assertEquals(SpeedBand.URBAN, nearBoundary.speedBand)
        assertEquals(1.0, nearBoundary.value(FeatureId.HARD_ACCEL_PER_MIN), 1e-9)

        drive.accelerate(sec = 2.0, ms2 = 0.3)
        drive.cruise(sec = 2.0, kmh = drive.speedKmh)
        assertEquals(SpeedBand.RURAL, drive.snapshot().speedBand)
    }

    @Test
    fun `events older than the 3 minute window are dropped`() {
        drive.cruise(sec = 5.0, kmh = 10.0)
        drive.accelerate(sec = 2.0, ms2 = 3.0)
        drive.cruise(sec = 240.0, kmh = drive.speedKmh)

        assertEquals(0.0, drive.snapshot().value(FeatureId.HARD_ACCEL_PER_MIN), 1e-9)
    }

    @Test
    fun `aggressive sequence scores higher than calm sequence`() {
        val calm = Drive(FeatureExtractor()).apply {
            cruise(sec = 5.0, kmh = 10.0)
            repeat(6) {
                accelerate(sec = 5.0, ms2 = 1.0)
                cruise(sec = 10.0, kmh = speedKmh)
                accelerate(sec = 3.3, ms2 = -1.5, brake = true)
                cruise(sec = 5.0, kmh = speedKmh)
            }
        }.snapshot()
        val aggressive = Drive(FeatureExtractor()).apply {
            cruise(sec = 5.0, kmh = 10.0)
            repeat(6) {
                accelerate(sec = 2.0, ms2 = 3.5)
                cruise(sec = 3.0, kmh = speedKmh)
                accelerate(sec = 1.5, ms2 = -4.5, brake = true)
                cruise(sec = 2.0, kmh = speedKmh)
            }
        }.snapshot()

        assertEquals(0.0, calm.value(FeatureId.HARD_ACCEL_PER_MIN), 1e-9)
        assertEquals(0.0, calm.value(FeatureId.HARD_DECEL_PER_MIN), 1e-9)
        assertTrue(aggressive.value(FeatureId.HARD_ACCEL_PER_MIN) >= 5.0)
        assertTrue(aggressive.value(FeatureId.HARD_DECEL_PER_MIN) >= 5.0)
        assertTrue(
            aggressive.value(FeatureId.ACCEL_BRAKE_FLIP_PER_MIN) > calm.value(FeatureId.ACCEL_BRAKE_FLIP_PER_MIN)
        )
    }

    @Test
    fun `no samples gives no snapshot`() {
        assertEquals(null, FeatureExtractor().snapshot(0L))
    }

    private fun DrivingFeatures.value(id: FeatureId): Double =
        values[id] ?: throw AssertionError("missing feature $id in $values")

    /** 녹화 시퀀스 + 추출기 */
    private class Drive(private val extractor: FeatureExtractor) {
        private val sequence = DriveSequence(onSample = extractor::add)
        val speedKmh: Double get() = sequence.speedKmh

        fun cruise(sec: Double, kmh: Double) = sequence.cruise(sec, kmh)
        fun accelerate(sec: Double, ms2: Double, brake: Boolean = false) = sequence.accelerate(sec, ms2, brake)
        fun spike(deltaKmh: Double) = sequence.spike(deltaKmh)
        fun steerBy(deg: Double, sec: Double) = sequence.steerBy(deg, sec)
        fun gap(ms: Long) = sequence.gap(ms)

        fun snapshot(): DrivingFeatures =
            extractor.snapshot(sequence.timeMs) ?: throw AssertionError("no snapshot")
    }
}
