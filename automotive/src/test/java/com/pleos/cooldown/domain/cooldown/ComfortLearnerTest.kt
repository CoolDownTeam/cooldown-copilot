package com.pleos.cooldown.domain.cooldown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComfortLearnerTest {

    @Test
    fun `F2-19 season from outside temperature`() {
        assertEquals(SeasonBand.SUMMER, SeasonBand.of(25f, month = 1))
        assertEquals(SeasonBand.MILD, SeasonBand.of(24.9f, month = 7))
        assertEquals(SeasonBand.MILD, SeasonBand.of(10f, month = 7))
        assertEquals(SeasonBand.WINTER, SeasonBand.of(9.9f, month = 7))
    }

    @Test
    fun `F2-T18 season from month when outside temperature is unknown`() {
        assertEquals(SeasonBand.WINTER, SeasonBand.of(null, month = 1))
        assertEquals(SeasonBand.WINTER, SeasonBand.of(null, month = 12))
        assertEquals(SeasonBand.SUMMER, SeasonBand.of(null, month = 6))
        assertEquals(SeasonBand.SUMMER, SeasonBand.of(null, month = 9))
        assertEquals(SeasonBand.MILD, SeasonBand.of(null, month = 4))
        assertEquals(SeasonBand.MILD, SeasonBand.of(null, month = 11))
    }

    @Test
    fun `default levels and plans per season`() {
        val summer = ComfortLearner.plan(ComfortProfile.DEFAULT, SeasonBand.SUMMER) ?: error("no plan")
        val mild = ComfortLearner.plan(ComfortProfile.DEFAULT, SeasonBand.MILD) ?: error("no plan")
        val winter = ComfortLearner.plan(ComfortProfile.DEFAULT, SeasonBand.WINTER) ?: error("no plan")

        assertEquals(2f, summer.maxDropC)
        assertTrue(summer.seatVentilation)
        assertTrue(summer.hasStage2)
        assertEquals(1f, mild.maxDropC)
        assertFalse(mild.hasStage2)
        assertEquals(0f, winter.maxDropC)
        assertTrue(winter.faceDirection)
        assertFalse(winter.seatVentilation)
    }

    @Test
    fun `F2-T11 strong cold lowers only that season`() {
        val after = ComfortLearner.apply(ComfortProfile.DEFAULT, SeasonBand.SUMMER, ComfortSignal.STRONG_COLD)

        assertEquals(2, after.band(SeasonBand.SUMMER).level)
        assertEquals(2, after.band(SeasonBand.MILD).level)
        assertEquals(1, after.band(SeasonBand.WINTER).level)
    }

    @Test
    fun `level never goes below L0 and L0 has no plan`() {
        var profile = ComfortProfile.DEFAULT
        repeat(3) { profile = ComfortLearner.apply(profile, SeasonBand.WINTER, ComfortSignal.STRONG_COLD) }

        assertEquals(0, profile.band(SeasonBand.WINTER).level)
        assertNull(ComfortLearner.plan(profile, SeasonBand.WINTER))
    }

    @Test
    fun `F2-T16 two weak signals lower one level`() {
        val once = ComfortLearner.apply(ComfortProfile.DEFAULT, SeasonBand.SUMMER, ComfortSignal.WEAK_COLD)
        assertEquals(3, once.band(SeasonBand.SUMMER).level)

        val twice = ComfortLearner.apply(once, SeasonBand.SUMMER, ComfortSignal.WEAK_COLD)
        assertEquals(2, twice.band(SeasonBand.SUMMER).level)
        assertEquals(0, twice.band(SeasonBand.SUMMER).weakSignals)
    }

    @Test
    fun `F2-T17 three positives raise one level but not above the default`() {
        var profile = ComfortLearner.apply(ComfortProfile.DEFAULT, SeasonBand.SUMMER, ComfortSignal.STRONG_COLD)
        repeat(3) { profile = ComfortLearner.apply(profile, SeasonBand.SUMMER, ComfortSignal.POSITIVE) }
        assertEquals(3, profile.band(SeasonBand.SUMMER).level)

        repeat(3) { profile = ComfortLearner.apply(profile, SeasonBand.SUMMER, ComfortSignal.POSITIVE) }
        assertEquals(3, profile.band(SeasonBand.SUMMER).level)
    }

    @Test
    fun `a cold signal resets the positive streak`() {
        var profile = ComfortLearner.apply(ComfortProfile.DEFAULT, SeasonBand.SUMMER, ComfortSignal.STRONG_COLD)
        repeat(2) { profile = ComfortLearner.apply(profile, SeasonBand.SUMMER, ComfortSignal.POSITIVE) }
        profile = ComfortLearner.apply(profile, SeasonBand.SUMMER, ComfortSignal.WEAK_COLD)
        profile = ComfortLearner.apply(profile, SeasonBand.SUMMER, ComfortSignal.POSITIVE)

        assertEquals(2, profile.band(SeasonBand.SUMMER).level)
        assertEquals(1, profile.band(SeasonBand.SUMMER).positiveStreak)
    }

    @Test
    fun `stop is recorded without changing the level`() {
        val after = ComfortLearner.apply(ComfortProfile.DEFAULT, SeasonBand.SUMMER, ComfortSignal.STOP)

        assertEquals(ComfortProfile.DEFAULT, after)
    }

    @Test
    fun `F2-T19 baseline temperature EWMA`() {
        val first = ComfortLearner.observeTripStartTemp(ComfortProfile.DEFAULT, SeasonBand.SUMMER, 24f)
        assertEquals(24f, first.band(SeasonBand.SUMMER).baselineTempC ?: error("null"), 1e-4f)

        val second = ComfortLearner.observeTripStartTemp(first, SeasonBand.SUMMER, 26f)
        assertEquals(24.4f, second.band(SeasonBand.SUMMER).baselineTempC ?: error("null"), 1e-4f)
        assertNull(second.band(SeasonBand.WINTER).baselineTempC)
    }
}
