package com.pleos.cooldown.domain.fatigue

import com.pleos.cooldown.domain.cooldown.FanDirection
import com.pleos.cooldown.domain.cooldown.HvacLimits
import com.pleos.cooldown.domain.cooldown.HvacSnapshot
import com.pleos.cooldown.domain.cooldown.ManualChange
import com.pleos.cooldown.domain.model.DriverState
import com.pleos.cooldown.domain.model.SpeedBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FatiguePolicyTest {

    private val limits = HvacLimits(minTempC = 16f, maxTempC = 32f, maxFanSpeed = 8, supportsFaceDirection = true, maxSeatVentilation = 3)
    private val policy = FatiguePolicy().apply { startTrip(limits) }
    private val actions = mutableListOf<FatigueAction>()
    private var nowMs = 0L
    private var continuousMin = 50L
    private var hvac = HvacSnapshot(24f, 3, powerOn = true, fanDirection = FanDirection.FLOOR, seatVentilation = 0, recirculation = true)
    private var restArea: RestArea? = RestArea("행담도휴게소", 12_000.0)
    private var nextTurnM: Double? = null
    private var cooldownActive = false

    private fun run(seconds: Int, fatigue: Int) {
        repeat(seconds) {
            nowMs += 1_000
            val driver = DriverState(nowMs, aggression = 0, fatigue = fatigue, speedBand = SpeedBand.HIGHWAY,
                learning = false, continuousDrivingMs = continuousMin * 60_000)
            apply(policy.onTick(nowMs, driver, hvac, cooldownActive, restArea, nextTurnM))
        }
    }

    private fun apply(result: List<FatigueAction>) {
        actions += result
        result.forEach { a ->
            when (a) {
                is FatigueAction.ApplyHvac -> hvac = hvac.copy(temperatureC = a.target.temperatureC,
                    fanSpeed = a.target.fanSpeed, fanDirection = a.target.fanDirection)
                is FatigueAction.SetRecirculation -> hvac = hvac.copy(recirculation = a.on)
                else -> Unit
            }
        }
    }

    private fun announcements() = actions.filterIsInstance<FatigueAction.Announce>()

    @Test
    fun `F3-T1 sixty seconds of fatigue holds on silently`() {
        run(59, fatigue = 65)
        assertEquals(FatiguePhase.IDLE, policy.phase)

        run(1, fatigue = 65)

        assertEquals(FatiguePhase.HOLD_ON, policy.phase)
        assertEquals(23f, hvac.temperatureC)
        assertEquals(4, hvac.fanSpeed)
        assertEquals(FanDirection.FACE, hvac.fanDirection)
        assertEquals(false, hvac.recirculation)
        assertTrue(announcements().isEmpty())
        assertTrue(policy.holdingClimate)
    }

    @Test
    fun `F3-T2 ten more minutes suggests the rest area in one sentence`() {
        run(60, fatigue = 65)
        run(600, fatigue = 65)

        assertEquals(FatiguePhase.AWAITING_REPLY, policy.phase)
        assertEquals(
            listOf(FatigueAction.Announce("12km 앞 행담도휴게소에서 잠깐 쉬어 갈까요?", listenForReply = true)),
            announcements()
        )
    }

    @Test
    fun `F3-T3 accepting reminds near the entrance and keeps the air`() {
        run(660, fatigue = 65)
        apply(policy.onReply(nowMs, accepted = true))
        assertEquals(FatiguePhase.HEADING_TO_REST, policy.phase)

        restArea = RestArea("행담도휴게소", 900.0)
        run(1, fatigue = 65)

        assertEquals(
            listOf(FatiguePolicy.MESSAGE_ACCEPTED, FatiguePolicy.MESSAGE_ENTRANCE),
            announcements().drop(1).map { it.message }
        )
        assertEquals(23f, hvac.temperatureC)
    }

    @Test
    fun `F3-T8 parking at the rest area recommends coffee or a nap and restores`() {
        run(660, fatigue = 65)
        apply(policy.onReply(nowMs, accepted = true))

        apply(policy.onParked(nowMs))

        assertEquals(FatiguePolicy.MESSAGE_REST_TIP, announcements().last().message)
        assertEquals(24f, hvac.temperatureC)
        assertEquals(FanDirection.FLOOR, hvac.fanDirection)
        assertEquals(true, hvac.recirculation)
        assertEquals(FatigueAction.Outcome.RESTED, actions.filterIsInstance<FatigueAction.InterventionEnded>().single().outcome)
        assertEquals(FatiguePhase.LOCKED, policy.phase)
    }

    @Test
    fun `F3-T4 no reply locks suggestions for 30 minutes but keeps the air`() {
        run(660, fatigue = 65)
        apply(policy.onReply(nowMs, accepted = null))

        run(29 * 60, fatigue = 65)
        assertEquals(1, announcements().size)
        assertEquals(23f, hvac.temperatureC)

        run(61, fatigue = 65)
        assertEquals(2, announcements().size)
    }

    @Test
    fun `F3-T5 short trips never trigger`() {
        continuousMin = 20
        run(1_200, fatigue = 90)

        assertEquals(FatiguePhase.IDLE, policy.phase)
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `F3-T6 cooldown has priority`() {
        cooldownActive = true
        run(120, fatigue = 70)

        assertEquals(FatiguePhase.IDLE, policy.phase)
    }

    @Test
    fun `F3-T7 very high fatigue suggests immediately`() {
        run(60, fatigue = 85)

        assertEquals(FatiguePhase.AWAITING_REPLY, policy.phase)
        assertTrue(policy.holdingClimate)
        assertEquals(1, announcements().size)
    }

    @Test
    fun `F3-04 unknown rest area advises without asking`() {
        restArea = null
        run(660, fatigue = 65)

        assertEquals(listOf(FatigueAction.Announce(FatiguePolicy.MESSAGE_SUGGEST_UNKNOWN, listenForReply = false)), announcements())
        assertEquals(FatiguePhase.DECLINED, policy.phase)
    }

    @Test
    fun `F3-05 defers the suggestion near a turn`() {
        nextTurnM = 300.0
        run(700, fatigue = 65)
        assertTrue(announcements().isEmpty())

        nextTurnM = 2_000.0
        run(1, fatigue = 65)
        assertEquals(1, announcements().size)
    }

    @Test
    fun `F3-06 at most three suggestions per trip`() {
        run(660, fatigue = 65)
        repeat(4) {
            apply(policy.onReply(nowMs, accepted = false))
            run(31 * 60, fatigue = 65)
        }

        assertEquals(3, announcements().size)
    }

    @Test
    fun `calm for five minutes restores and locks`() {
        run(60, fatigue = 65)
        run(300, fatigue = 30)

        assertEquals(FatiguePhase.LOCKED, policy.phase)
        assertEquals(24f, hvac.temperatureC)
        assertEquals(true, hvac.recirculation)
        assertFalse(policy.holdingClimate)
    }

    @Test
    fun `passing the accepted rest area counts as declined`() {
        run(660, fatigue = 65)
        apply(policy.onReply(nowMs, accepted = true))

        restArea = null
        run(5, fatigue = 65)
        assertEquals(FatiguePhase.HEADING_TO_REST, policy.phase)

        restArea = RestArea("서산휴게소", 30_000.0)
        run(1, fatigue = 65)
        assertEquals(FatiguePhase.DECLINED, policy.phase)
    }

    @Test
    fun `manual climate change yields the air but keeps the suggestion flow`() {
        run(60, fatigue = 65)
        apply(policy.onManualChange(nowMs, ManualChange.TEMP_UP))
        hvac = hvac.copy(temperatureC = 25f)
        assertFalse(policy.holdingClimate)

        run(600, fatigue = 65)
        assertEquals(1, announcements().size)

        run(300, fatigue = 30)
        assertEquals(25f, hvac.temperatureC)
    }

    @Test
    fun `ending the trip while holding restores`() {
        run(60, fatigue = 65)

        apply(policy.endTrip(nowMs))

        assertEquals(24f, hvac.temperatureC)
        assertEquals(FatigueAction.Outcome.TRIP_ENDED, actions.filterIsInstance<FatigueAction.InterventionEnded>().single().outcome)
    }

    @Test
    fun `F3-03 reply words`() {
        listOf("응", "네 들를게", "그래", "좋아 가자").forEach { assertEquals(it, true, ReplyParser.parse(it)) }
        listOf("아니", "괜찮아", "아니 됐어", "그냥 갈래").forEach { assertEquals(it, false, ReplyParser.parse(it)) }
        listOf(null, "", "음").forEach { assertEquals(it, null, ReplyParser.parse(it)) }
    }

    @Test
    fun `suggest message rounds distance`() {
        assertEquals("1km 앞 휴게소에서 잠깐 쉬어 갈까요?", FatiguePolicy.suggestMessage(RestArea("", 400.0)))
        assertEquals("13km 앞 망향휴게소에서 잠깐 쉬어 갈까요?", FatiguePolicy.suggestMessage(RestArea("망향휴게소", 12_600.0)))
    }

    @Test
    fun `SAFE-03 holding on keeps the driver's defrost`() {
        hvac = hvac.copy(fanDirection = FanDirection.DEFROST)
        run(60, fatigue = 65)

        assertEquals(FatiguePhase.HOLD_ON, policy.phase)
        assertEquals(FanDirection.DEFROST, hvac.fanDirection)
        assertEquals(4, hvac.fanSpeed)
    }

    @Test
    fun `SAFE-03 a fan direction change while holding yields the climate`() {
        run(60, fatigue = 65)

        apply(policy.onManualChange(nowMs, ManualChange.DIRECTION))

        assertFalse(policy.holdingClimate)
        assertEquals(FatigueAction.Outcome.YIELDED, actions.filterIsInstance<FatigueAction.InterventionEnded>().single().outcome)
    }
}
