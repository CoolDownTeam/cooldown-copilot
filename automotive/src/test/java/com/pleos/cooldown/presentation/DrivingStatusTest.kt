package com.pleos.cooldown.presentation

import com.pleos.cooldown.domain.cooldown.CooldownPhase
import com.pleos.cooldown.domain.fatigue.FatiguePhase
import com.pleos.cooldown.presentation.driving.DrivingStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class DrivingStatusTest {

    @Test
    fun `status priority follows screen spec 5`() {
        assertEquals(DrivingStatus.CALM, DrivingStatus.of(CooldownPhase.IDLE, FatiguePhase.IDLE, freshAir = false, learning = false))
        assertEquals(DrivingStatus.LEARNING, DrivingStatus.of(CooldownPhase.IDLE, FatiguePhase.IDLE, freshAir = false, learning = true))
        assertEquals(DrivingStatus.COOLING, DrivingStatus.of(CooldownPhase.STAGE2, FatiguePhase.IDLE, freshAir = true, learning = true))
        assertEquals(DrivingStatus.COOLING, DrivingStatus.of(CooldownPhase.RESTORING, FatiguePhase.IDLE, freshAir = false, learning = false))
        assertEquals(DrivingStatus.FRESH_AIR, DrivingStatus.of(CooldownPhase.LOCKED, FatiguePhase.IDLE, freshAir = true, learning = false))
        assertEquals(DrivingStatus.FRESH_AIR, DrivingStatus.of(CooldownPhase.IDLE, FatiguePhase.HOLD_ON, freshAir = false, learning = false))
        assertEquals(DrivingStatus.REST, DrivingStatus.of(CooldownPhase.STAGE1, FatiguePhase.HEADING_TO_REST, freshAir = false, learning = false))
    }
}
