package com.pleos.cooldown.data.vehicle

import com.pleos.cooldown.domain.model.DrivingState
import com.pleos.cooldown.domain.model.Gear
import com.pleos.cooldown.domain.model.SignalType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VehicleSignalStoreTest {

    private var now = 1_000L
    private lateinit var store: VehicleSignalStore

    @Before
    fun setUp() {
        store = VehicleSignalStore { now }
    }

    @Test
    fun `D0-10 merges individual signals into one snapshot`() {
        store.onGear(Gear.D)
        store.onSpeed(42f)
        store.onBrake(true)
        now = 2_000L
        store.onSteering(-3.5f)

        val signal = store.signal.value
        assertEquals(Gear.D, signal.gear)
        assertEquals(42f, signal.speedKmh)
        assertTrue(signal.brakePressed)
        assertEquals(-3.5f, signal.steeringAngleDeg)
        assertEquals(2_000L, signal.timestampMs)
    }

    @Test
    fun `D0-11 null values keep previous value`() {
        store.onSpeed(30f)
        store.onSpeed(null)
        store.onBrake(true)
        store.onBrake(null)

        assertEquals(30f, store.signal.value.speedKmh)
        assertTrue(store.signal.value.brakePressed)
    }

    @Test
    fun `D0-12 failed signal is marked unavailable and recovers on next value`() {
        store.markUnavailable(SignalType.STEERING)
        assertTrue(SignalType.STEERING in store.unavailable.value)

        store.onSteering(1f)
        assertFalse(SignalType.STEERING in store.unavailable.value)
    }

    @Test
    fun `negative speed is clamped to zero`() {
        store.onSpeed(-5f)
        assertEquals(0f, store.signal.value.speedKmh)
    }

    @Test
    fun `D0-13 parked only when gear is P and speed is zero`() {
        store.onGear(Gear.P)
        store.onSpeed(0f)
        assertEquals(DrivingState.PARKED, store.drivingState.value)

        store.onGear(Gear.D)
        assertEquals(DrivingState.DRIVING, store.drivingState.value)

        store.onGear(Gear.P)
        store.onSpeed(3f)
        assertEquals(DrivingState.DRIVING, store.drivingState.value)

        store.onSpeed(0f)
        assertEquals(DrivingState.PARKED, store.drivingState.value)
    }

    @Test
    fun `D0-13 unknown gear falls back to speed`() {
        store.onSpeed(0f)
        assertEquals(DrivingState.PARKED, store.drivingState.value)

        store.onSpeed(10f)
        assertEquals(DrivingState.DRIVING, store.drivingState.value)
    }
}
