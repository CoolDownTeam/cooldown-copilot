package com.pleos.cooldown.data.vehicle

import com.pleos.cooldown.domain.model.Gear
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScenarioParserTest {

    @Test
    fun `D0-16 parses frames and skips comments and blank lines`() {
        val csv = """
            # t_ms,speed_kmh,gear,brake,steering_deg
            0,0,P,0,0

            1000,12.5,D,0,-2
            2000,10,d,true,1.5
        """.trimIndent()

        val frames = ScenarioParser.parse(csv)

        assertEquals(3, frames.size)
        assertEquals(ScenarioFrame(1000, 12.5f, Gear.D, false, -2f), frames[1])
        assertEquals(Gear.D, frames[2].gear)
        assertTrue(frames[2].brakePressed)
        assertFalse(frames[0].brakePressed)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects wrong column count`() {
        ScenarioParser.parse("0,0,P,0")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects unknown gear`() {
        ScenarioParser.parse("0,0,X,0,0")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects decreasing timestamps`() {
        ScenarioParser.parse("1000,0,P,0,0\n500,0,P,0,0")
    }
}
