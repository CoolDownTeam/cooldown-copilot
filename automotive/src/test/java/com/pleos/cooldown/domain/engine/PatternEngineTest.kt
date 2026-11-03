package com.pleos.cooldown.domain.engine

import com.pleos.cooldown.domain.model.Baseline
import com.pleos.cooldown.testutil.DriveSequence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PatternEngineTest {

    private val learned = Baseline.DEFAULT.copy(totalTrips = 10, totalDrivingMinutes = 300.0)

    private fun engine(baseline: Baseline = learned) =
        PatternEngine(initialBaseline = baseline, scorer = StateScorer(hourOfDay = { 14 }))

    private fun DriveSequence.calmCycle() {
        accelerate(sec = 5.0, ms2 = 1.0)
        cruise(sec = 20.0, kmh = speedKmh)
        accelerate(sec = 3.3, ms2 = -1.5, brake = true)
        cruise(sec = 5.0, kmh = speedKmh)
    }

    private fun DriveSequence.aggressiveCycle() {
        accelerate(sec = 2.0, ms2 = 3.5)
        cruise(sec = 3.0, kmh = speedKmh)
        accelerate(sec = 1.5, ms2 = -4.5, brake = true)
        cruise(sec = 2.0, kmh = speedKmh)
    }

    @Test
    fun `C0-20 publishes a driver state every second`() {
        val engine = engine()
        val timestamps = mutableListOf<Long>()
        val drive = DriveSequence(engine::onSample) { t ->
            engine.tick(t)
            timestamps += engine.state.value.timestampMs
        }

        drive.cruise(sec = 5.0, kmh = 30.0)

        assertEquals(listOf(1_000L, 2_000L, 3_000L, 4_000L, 5_000L), timestamps)
    }

    @Test
    fun `C0-21 calm driving stays below 40`() {
        val engine = engine()
        var maxAggression = 0
        val drive = DriveSequence(engine::onSample) { t ->
            engine.tick(t)
            maxAggression = maxOf(maxAggression, engine.state.value.aggression)
        }

        drive.cruise(sec = 5.0, kmh = 10.0)
        repeat(10) { drive.calmCycle() }

        assertTrue("max aggression $maxAggression", maxAggression < 40)
    }

    @Test
    fun `C0-22 repeated hard accel and braking reaches 60 within 60 seconds`() {
        val engine = engine()
        var firstHighMs: Long? = null
        val drive = DriveSequence(engine::onSample) { t ->
            engine.tick(t)
            if (firstHighMs == null && engine.state.value.aggression >= 60) firstHighMs = t
        }

        drive.cruise(sec = 30.0, kmh = 10.0)
        val aggressiveFromMs = drive.timeMs
        repeat(8) { drive.aggressiveCycle() }

        val reached = firstHighMs ?: throw AssertionError("aggression never reached 60")
        assertTrue("reached after ${reached - aggressiveFromMs} ms", reached - aggressiveFromMs <= 60_000)
    }

    @Test
    fun `finishing a trip updates the baseline and resets the state`() {
        val engine = engine(Baseline.DEFAULT)
        val drive = DriveSequence(engine::onSample) { t -> engine.tick(t) }
        drive.cruise(sec = 5.0, kmh = 10.0)
        repeat(4) { drive.calmCycle() }

        val updated = engine.finishTrip(endMs = drive.timeMs)

        assertEquals(1, updated.totalTrips)
        assertTrue(updated.totalDrivingMinutes > 2.0)
        assertEquals(updated, engine.baseline)
        assertEquals(0, engine.state.value.aggression)
        assertTrue(engine.state.value.learning)
    }

    @Test
    fun `C0-15 reset goes back to the built-in defaults`() {
        val engine = engine()
        assertFalse(engine.state.value.learning)

        engine.reset(nowMs = 0)

        assertEquals(Baseline.DEFAULT, engine.baseline)
        assertTrue(engine.state.value.learning)
    }
}
