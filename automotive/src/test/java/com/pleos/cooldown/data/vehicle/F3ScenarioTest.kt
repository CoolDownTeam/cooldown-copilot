package com.pleos.cooldown.data.vehicle

import com.pleos.cooldown.data.navi.ScenarioRouteParser
import com.pleos.cooldown.data.navi.nextRestArea
import com.pleos.cooldown.data.navi.nextTurnDistanceM
import com.pleos.cooldown.domain.briefing.BriefingComposer
import com.pleos.cooldown.domain.briefing.HardSection
import com.pleos.cooldown.domain.briefing.HardSectionDetector
import com.pleos.cooldown.domain.cooldown.ComfortLearner
import com.pleos.cooldown.domain.cooldown.ComfortProfile
import com.pleos.cooldown.domain.cooldown.CooldownPhase
import com.pleos.cooldown.domain.cooldown.CooldownPolicy
import com.pleos.cooldown.domain.cooldown.HvacLimits
import com.pleos.cooldown.domain.cooldown.HvacSnapshot
import com.pleos.cooldown.domain.cooldown.SeasonBand
import com.pleos.cooldown.domain.engine.PatternEngine
import com.pleos.cooldown.domain.engine.StateScorer
import com.pleos.cooldown.domain.fatigue.FatigueAction
import com.pleos.cooldown.domain.fatigue.FatiguePhase
import com.pleos.cooldown.domain.fatigue.FatiguePolicy
import com.pleos.cooldown.domain.model.Baseline
import com.pleos.cooldown.domain.model.Gear
import com.pleos.cooldown.domain.model.VehicleSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 시연 시나리오 f3_highway가 P 전에 F1 브리핑·직전 안내와 F3 버티기 → 휴게소 제안 → 입구 안내를 모두 보여 주는지 확인한다.
 */
class F3ScenarioTest {

    private val frames = ScenarioParser.parse(File("src/debug/assets/scenarios/f3_highway.csv").readText())
    private val route = ScenarioRouteParser.parse(File("src/debug/assets/scenarios/f3_highway.route.csv").readText())

    @Test
    fun `route briefing names the branches and the congestion`() {
        val start = route.snapshotAt(0.0)
        val sections = HardSectionDetector.detect(start)
        assertEquals(2, sections.size)
        assertTrue(sections[0] is HardSection.ConsecutiveBranches)
        assertTrue(sections[1] is HardSection.CongestionEntry)
        val text = BriefingComposer.briefing(start, sections)
        assertTrue(text, text.startsWith("오늘 길은 1시간 30분이에요."))
        assertTrue(BriefingComposer.estimatedMs(text) <= BriefingComposer.MAX_BRIEFING_MS)
    }

    @Test
    fun `f3_highway holds on, suggests the rest area and reminds before the entrance`() {
        val engine = PatternEngine(Baseline.DEFAULT, StateScorer(hourOfDay = { 14 }))
        val fatigue = FatiguePolicy()
        val cooldown = CooldownPolicy()
        val limits = HvacLimits(16f, 30f, 8, true, 3)
        fatigue.startTrip(limits)
        cooldown.startTrip(ComfortLearner.plan(ComfortProfile.DEFAULT, SeasonBand.SUMMER), limits)
        val hvac = HvacSnapshot(temperatureC = 24f, fanSpeed = 2, powerOn = true, seatVentilation = 0, recirculation = true)

        val phases = mutableListOf<FatiguePhase>()
        val spoken = mutableListOf<String>()
        var traveledM = 0.0
        var samples = 0
        val driving = frames.dropWhile { it.gear == Gear.P }.takeWhile { it.gear != Gear.P }
        val start = driving.first().offsetMs
        // 재생은 100ms마다 최신 프레임을 읽는다 (CopilotRunner와 같다)
        for ((i, f) in driving.withIndex()) {
            val nextMs = driving.getOrNull(i + 1)?.offsetMs ?: (f.offsetMs + 100)
            var t = f.offsetMs
            while (t < nextMs) {
                val now = t - start
                engine.onSample(now, VehicleSignal.INITIAL.copy(timestampMs = now, speedKmh = f.speedKmh, gear = f.gear))
                traveledM += f.speedKmh / 3.6 * 0.1
                if (++samples % 10 == 0) {
                    engine.tick(now)
                    val driver = engine.state.value
                    cooldown.onTick(now, driver, hvac)
                    val snapshot = route.snapshotAt(traveledM)
                    val actions = fatigue.onTick(now, driver, hvac, false, snapshot.nextRestArea(), snapshot.nextTurnDistanceM())
                    actions.filterIsInstance<FatigueAction.Announce>().forEach { a ->
                        spoken += a.message
                        // 시연에서는 Gleo로 "응"을 보낸다
                        if (a.listenForReply) fatigue.onReply(now, true).filterIsInstance<FatigueAction.Announce>().forEach { spoken += it.message }
                    }
                    if (phases.lastOrNull() != fatigue.phase) phases += fatigue.phase
                    assertEquals("F2 must stay idle on a calm highway", CooldownPhase.IDLE, cooldown.phase)
                }
                t += 100
            }
        }

        assertEquals(
            listOf(FatiguePhase.IDLE, FatiguePhase.HOLD_ON, FatiguePhase.HEADING_TO_REST),
            phases.filter { it != FatiguePhase.AWAITING_REPLY },
        )
        assertEquals(3, spoken.size)
        // 시연 영상에서 너무 먼 휴게소로 들리지 않게 10~13km 앞에서 제안한다
        val km = spoken[0].substringBefore("km").toInt()
        assertTrue(spoken[0], km in 10..13 && spoken[0].endsWith("km 앞 행담도휴게소에서 잠깐 쉬어 갈까요?"))
        assertEquals(listOf(FatiguePolicy.MESSAGE_ACCEPTED, FatiguePolicy.MESSAGE_ENTRANCE), spoken.drop(1))
    }
}
