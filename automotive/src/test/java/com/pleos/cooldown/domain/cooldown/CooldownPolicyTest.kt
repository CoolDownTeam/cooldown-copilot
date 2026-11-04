package com.pleos.cooldown.domain.cooldown

import com.pleos.cooldown.domain.model.DriverState
import com.pleos.cooldown.domain.model.SpeedBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CooldownPolicyTest {

    private val limits = HvacLimits(
        minTempC = 16f, maxTempC = 32f, maxFanSpeed = 8, supportsFaceDirection = true, maxSeatVentilation = 3,
    )
    private val policy = CooldownPolicy()
    private val actions = mutableListOf<CooldownAction>()
    private var nowMs = 0L
    private var hvac = HvacSnapshot(temperatureC = 24f, fanSpeed = 3, powerOn = true, fanDirection = FanDirection.FLOOR, seatVentilation = 0)

    private fun start(band: SeasonBand = SeasonBand.SUMMER, level: Int = 3, baselineC: Float? = 24f) {
        val profile = ComfortProfile(
            ComfortProfile.DEFAULT.bands + (band to BandComfort(level, baselineC, weakSignals = 0, positiveStreak = 0))
        )
        policy.startTrip(ComfortLearner.plan(profile, band), limits)
    }

    /** 1초마다 점수를 넣고, 적용된 공조 값을 [hvac]에 반영한다 */
    private fun run(seconds: Int, aggression: Int, learning: Boolean = false) {
        repeat(seconds) {
            nowMs += 1_000
            val result = policy.onTick(nowMs, driver(aggression, learning), hvac)
            apply(result)
        }
    }

    private fun apply(result: List<CooldownAction>) {
        actions += result
        result.filterIsInstance<CooldownAction.ApplyHvac>().forEach { (t) ->
            hvac = hvac.copy(
                temperatureC = t.temperatureC, fanSpeed = t.fanSpeed,
                fanDirection = t.fanDirection, seatVentilation = t.seatVentilation,
            )
        }
    }

    private fun driver(aggression: Int, learning: Boolean) =
        DriverState(timestampMs = nowMs, aggression = aggression, fatigue = 0, speedBand = SpeedBand.URBAN, learning = learning)

    private inline fun <reified T : CooldownAction> actionsOf(): List<T> = actions.filterIsInstance<T>()

    @Test
    fun `F2-T1 30 seconds of high aggression enters stage 1`() {
        start()
        run(29, aggression = 70)
        assertEquals(CooldownPhase.IDLE, policy.phase)

        run(1, aggression = 70)

        assertEquals(CooldownPhase.STAGE1, policy.phase)
        assertEquals(23f, hvac.temperatureC)
        assertEquals(4, hvac.fanSpeed)
        assertEquals(1, actionsOf<CooldownAction.InterventionStarted>().size)
        assertEquals(
            listOf(CooldownAction.Announce(CooldownPolicy.MESSAGE_COOLING, listenForReply = true)),
            actionsOf<CooldownAction.Announce>()
        )
    }

    @Test
    fun `a dip below threshold restarts the 30 second count`() {
        start()
        run(20, aggression = 70)
        run(1, aggression = 50)
        run(20, aggression = 70)

        assertEquals(CooldownPhase.IDLE, policy.phase)
    }

    @Test
    fun `learning mode raises the threshold to 75`() {
        start()
        run(40, aggression = 70, learning = true)
        assertEquals(CooldownPhase.IDLE, policy.phase)

        run(30, aggression = 80, learning = true)
        assertEquals(CooldownPhase.STAGE1, policy.phase)
    }

    @Test
    fun `F2-T2 two more minutes of high aggression enters stage 2 with seat ventilation`() {
        start()
        run(30, aggression = 70)
        run(120, aggression = 70)

        assertEquals(CooldownPhase.STAGE2, policy.phase)
        assertEquals(22f, hvac.temperatureC)
        assertEquals(4, hvac.fanSpeed)
        assertEquals(CooldownPolicy.SEAT_VENTILATION_ON, hvac.seatVentilation)
    }

    @Test
    fun `F2-T3 five calm minutes restore over one minute then lock`() {
        start()
        run(150, aggression = 70)
        run(300, aggression = 30)
        assertEquals(CooldownPhase.RESTORING, policy.phase)
        assertEquals(22f, hvac.temperatureC)

        run(30, aggression = 30)
        assertTrue("half way ${hvac.temperatureC}", hvac.temperatureC > 22f && hvac.temperatureC < 24f)

        run(30, aggression = 30)
        assertEquals(CooldownPhase.LOCKED, policy.phase)
        assertEquals(HvacSnapshot(24f, 3, true, FanDirection.FLOOR, 0), hvac)
        val ended = actionsOf<CooldownAction.InterventionEnded>().single()
        assertEquals(InterventionOutcome.RESTORED, ended.outcome)
        assertEquals(2, ended.maxStage)
        assertEquals(listOf(CooldownAction.Learn(ComfortSignal.POSITIVE)), actionsOf<CooldownAction.Learn>())
    }

    @Test
    fun `F2-T4 no intervention during the 15 minute lock`() {
        start()
        run(30, aggression = 70)
        run(360, aggression = 30)
        assertEquals(CooldownPhase.LOCKED, policy.phase)

        run(600, aggression = 70)
        assertEquals(CooldownPhase.LOCKED, policy.phase)

        // 잠금 15분이 끝난 뒤 다시 30초 유지해야 들어간다
        run(299, aggression = 70)
        assertEquals(CooldownPhase.LOCKED, policy.phase)
        run(31, aggression = 70)
        assertEquals(CooldownPhase.STAGE1, policy.phase)
    }

    @Test
    fun `F2-T5 manual temperature change yields without restoring`() {
        start()
        run(30, aggression = 70)

        apply(policy.onManualChange(nowMs, ManualChange.TEMP_UP))
        hvac = hvac.copy(temperatureC = 25f)
        run(600, aggression = 90)

        assertEquals(CooldownPhase.YIELDED, policy.phase)
        assertEquals(25f, hvac.temperatureC)
        assertEquals(InterventionOutcome.YIELDED_MANUAL, actionsOf<CooldownAction.InterventionEnded>().single().outcome)
        assertEquals(listOf(CooldownAction.Learn(ComfortSignal.STRONG_COLD)), actionsOf<CooldownAction.Learn>())
    }

    @Test
    fun `F2-10 lowering the fan is a weak signal, raising it is none`() {
        start()
        run(30, aggression = 70)
        apply(policy.onManualChange(nowMs, ManualChange.FAN_DOWN))
        assertEquals(listOf(CooldownAction.Learn(ComfortSignal.WEAK_COLD)), actionsOf<CooldownAction.Learn>())

        actions.clear()
        start()
        run(30, aggression = 70)
        apply(policy.onManualChange(nowMs, ManualChange.FAN_UP))
        assertTrue(actionsOf<CooldownAction.Learn>().isEmpty())
        assertEquals(CooldownPhase.YIELDED, policy.phase)
    }

    @Test
    fun `F2-T6 no intervention when climate power is off`() {
        start()
        hvac = hvac.copy(powerOn = false)
        run(120, aggression = 90)

        assertEquals(CooldownPhase.IDLE, policy.phase)
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `F2-T7 floor is baseline minus D`() {
        start(baselineC = 22f)
        hvac = hvac.copy(temperatureC = 21f)
        run(150, aggression = 70)

        assertEquals(CooldownPhase.STAGE2, policy.phase)
        assertEquals(20f, hvac.temperatureC)
    }

    @Test
    fun `never raises the temperature even if the floor is above the original`() {
        start(baselineC = 26f)
        hvac = hvac.copy(temperatureC = 22f)
        run(150, aggression = 70)

        assertEquals(22f, hvac.temperatureC)
    }

    @Test
    fun `absolute floor of 18 degrees`() {
        start(baselineC = null)
        hvac = hvac.copy(temperatureC = 19f)
        run(150, aggression = 70)

        assertEquals(18f, hvac.temperatureC)
    }

    @Test
    fun `F2-T8 cold by voice restores immediately and announces`() {
        start()
        run(30, aggression = 70)

        apply(policy.onFeedback(nowMs, Feedback.COLD, fromGleo = false))

        assertEquals(CooldownPhase.YIELDED, policy.phase)
        assertEquals(HvacSnapshot(24f, 3, true, FanDirection.FLOOR, 0), hvac)
        assertEquals(InterventionOutcome.COLD_FEEDBACK, actionsOf<CooldownAction.InterventionEnded>().single().outcome)
        assertEquals(listOf(CooldownAction.Learn(ComfortSignal.STRONG_COLD)), actionsOf<CooldownAction.Learn>())
        assertEquals(CooldownPolicy.MESSAGE_RESTORED, actionsOf<CooldownAction.Announce>().last().message)
    }

    @Test
    fun `F2-T9 cold from Gleo restores without the app speaking`() {
        start()
        run(150, aggression = 70)

        apply(policy.onFeedback(nowMs, Feedback.COLD, fromGleo = true))

        assertEquals(24f, hvac.temperatureC)
        assertEquals(1, actionsOf<CooldownAction.Announce>().size)
    }

    @Test
    fun `stop by voice restores and records without lowering intensity`() {
        start()
        run(30, aggression = 70)

        apply(policy.onFeedback(nowMs, Feedback.STOP, fromGleo = false))

        assertEquals(InterventionOutcome.STOPPED, actionsOf<CooldownAction.InterventionEnded>().single().outcome)
        assertEquals(listOf(CooldownAction.Learn(ComfortSignal.STOP)), actionsOf<CooldownAction.Learn>())
    }

    @Test
    fun `F2-T10 feedback when not intervening does nothing`() {
        start()
        run(10, aggression = 30)

        assertTrue(policy.onFeedback(nowMs, Feedback.COLD, fromGleo = true).isEmpty())
        assertEquals(CooldownPhase.IDLE, policy.phase)
    }

    @Test
    fun `F2-T12 mild L2 stops at minus 1 without stage 2`() {
        start(band = SeasonBand.MILD, level = 2)
        run(300, aggression = 70)

        assertEquals(CooldownPhase.STAGE1, policy.phase)
        assertEquals(23f, hvac.temperatureC)
        assertEquals(4, hvac.fanSpeed)
    }

    @Test
    fun `F2-T14 winter keeps temperature and turns air to the face`() {
        start(band = SeasonBand.WINTER, level = 1)
        run(30, aggression = 70)

        assertEquals(24f, hvac.temperatureC)
        assertEquals(4, hvac.fanSpeed)
        assertEquals(FanDirection.FACE, hvac.fanDirection)

        run(300, aggression = 30)
        run(60, aggression = 30)
        assertEquals(FanDirection.FLOOR, hvac.fanDirection)
        assertEquals(3, hvac.fanSpeed)
    }

    @Test
    fun `L0 never intervenes`() {
        start(band = SeasonBand.WINTER, level = 0)
        run(120, aggression = 95)

        assertEquals(CooldownPhase.DISABLED, policy.phase)
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `F2-06 command failure yields without retry`() {
        start()
        run(30, aggression = 70)

        apply(policy.onCommandFailed(nowMs))
        run(300, aggression = 90)

        assertEquals(CooldownPhase.YIELDED, policy.phase)
        assertEquals(InterventionOutcome.FAILED, actionsOf<CooldownAction.InterventionEnded>().single().outcome)
    }

    @Test
    fun `ending the trip while intervening restores immediately`() {
        start()
        run(150, aggression = 70)

        apply(policy.endTrip(nowMs))

        assertEquals(CooldownPhase.DISABLED, policy.phase)
        assertEquals(24f, hvac.temperatureC)
        assertEquals(InterventionOutcome.TRIP_ENDED, actionsOf<CooldownAction.InterventionEnded>().single().outcome)
    }

    @Test
    fun `unknown hvac limits disable the trip`() {
        policy.startTrip(ComfortLearner.plan(ComfortProfile.DEFAULT, SeasonBand.SUMMER), limits = null)
        run(60, aggression = 95)

        assertEquals(CooldownPhase.DISABLED, policy.phase)
    }

    @Test
    fun `F2-04 detector ignores app echoes within 2 seconds`() {
        val detector = ManualChangeDetector()
        assertNull(detector.onTemperature(0, 24f))

        detector.onAppSet(1_000, HvacTarget(23f, 4))
        assertNull(detector.onTemperature(1_500, 23f))
        assertNull(detector.onFanSpeed(1_500, 3))
        assertNull(detector.onFanSpeed(1_600, 4))

        assertEquals(ManualChange.TEMP_UP, detector.onTemperature(5_000, 25f))
        assertEquals(ManualChange.FAN_DOWN, detector.onFanSpeed(5_000, 2))
        assertNull(detector.onTemperature(6_000, 25f))
    }

    @Test
    fun `F2-04 a matching value after the echo window counts as manual`() {
        val detector = ManualChangeDetector()
        detector.onTemperature(0, 24f)
        detector.onAppSet(1_000, HvacTarget(23f, 4))

        assertEquals(ManualChange.TEMP_DOWN, detector.onTemperature(4_000, 23f))
    }
}
