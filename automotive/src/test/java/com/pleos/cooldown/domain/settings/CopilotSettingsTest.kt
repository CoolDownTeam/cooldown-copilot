package com.pleos.cooldown.domain.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CopilotSettingsTest {

    @Test
    fun `S1-01 before onboarding nothing intervenes or speaks`() {
        val active = CopilotSettings.DEFAULT.active

        assertFalse(active.cooldownEnabled)
        assertFalse(active.fatigueEnabled)
        assertFalse(active.briefingEnabled)
        assertFalse(active.voiceEnabled)
    }

    @Test
    fun `after onboarding the driver's choices apply as they are`() {
        val chosen = CopilotSettings(cooldownEnabled = true, fatigueEnabled = false, onboardingDone = true)

        assertEquals(chosen, chosen.active)
    }
}
