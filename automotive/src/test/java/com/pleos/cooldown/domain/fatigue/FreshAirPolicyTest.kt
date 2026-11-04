package com.pleos.cooldown.domain.fatigue

import com.pleos.cooldown.domain.cooldown.HvacSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FreshAirPolicyTest {

    private val policy = FreshAirPolicy()
    private val actions = mutableListOf<FreshAirAction>()
    private var nowMs = 0L
    private var hvac = HvacSnapshot(24f, 3, powerOn = true, recirculation = true, autoRecirculation = false)

    private fun run(seconds: Int, suspended: Boolean = false) {
        repeat(seconds) {
            nowMs += 1_000
            val result = policy.onTick(nowMs, hvac, suspended)
            actions += result
            result.filterIsInstance<FreshAirAction.SetRecirculation>().forEach { hvac = hvac.copy(recirculation = it.on) }
        }
    }

    private fun switches() = actions.filterIsInstance<FreshAirAction.SetRecirculation>().map { it.on }

    @Test
    fun `F3-T9 one driver, 30 minutes of recirculation opens fresh air for 5 minutes`() {
        policy.startTrip(occupantCount = 1)
        run(29 * 60 + 59)
        assertTrue(switches().isEmpty())

        run(1)
        assertEquals(listOf(false), switches())

        run(5 * 60)
        assertEquals(listOf(false, true), switches())
        assertEquals(1, actions.filterIsInstance<FreshAirAction.Ended>().size)
    }

    @Test
    fun `F3-T10 three occupants switch after 15 minutes`() {
        policy.startTrip(occupantCount = 3)
        run(15 * 60)

        assertEquals(listOf(false), switches())
    }

    @Test
    fun `unknown occupancy counts as one driver`() {
        policy.startTrip(occupantCount = null)
        run(20 * 60)

        assertTrue(switches().isEmpty())
    }

    @Test
    fun `the recirculation timer restarts after switching back`() {
        policy.startTrip(occupantCount = 3)
        run(15 * 60)
        run(5 * 60)
        assertEquals(listOf(false, true), switches())

        run(15 * 60 - 1)
        assertEquals(listOf(false, true), switches())

        run(1)
        assertEquals(listOf(false, true, false), switches())
    }

    @Test
    fun `F3-T11 driver switching back to recirculation stops prevention for the trip`() {
        policy.startTrip(occupantCount = 3)
        run(15 * 60)

        actions += policy.onManualRecirculation(nowMs, on = true)
        hvac = hvac.copy(recirculation = true)
        run(60 * 60)

        assertEquals(listOf(false), switches())
        assertEquals(true, actions.filterIsInstance<FreshAirAction.Ended>().single().yielded)
    }

    @Test
    fun `F3-T12 auto recirculation is left to the car`() {
        hvac = hvac.copy(autoRecirculation = true)
        policy.startTrip(occupantCount = 3)
        run(60 * 60)

        assertTrue(actions.isEmpty())
    }

    @Test
    fun `power off or fresh air already does nothing`() {
        policy.startTrip(occupantCount = 3)
        hvac = hvac.copy(powerOn = false)
        run(30 * 60)
        hvac = hvac.copy(powerOn = true, recirculation = false)
        run(30 * 60)

        assertTrue(actions.isEmpty())
    }

    @Test
    fun `hold-on takes over fresh air without switching back`() {
        policy.startTrip(occupantCount = 3)
        run(15 * 60)
        run(60, suspended = true)

        assertEquals(listOf(false), switches())
        assertEquals(false, policy.active)
    }

    @Test
    fun `ending the trip during fresh air switches back`() {
        policy.startTrip(occupantCount = 3)
        run(15 * 60)

        actions += policy.endTrip(nowMs)

        assertEquals(listOf(false, true), switches())
    }
}
