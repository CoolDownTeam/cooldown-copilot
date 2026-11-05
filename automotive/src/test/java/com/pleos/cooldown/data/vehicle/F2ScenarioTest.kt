package com.pleos.cooldown.data.vehicle

import com.pleos.cooldown.domain.cooldown.ComfortLearner
import com.pleos.cooldown.domain.cooldown.ComfortProfile
import com.pleos.cooldown.domain.cooldown.CooldownAction
import com.pleos.cooldown.domain.cooldown.CooldownPhase
import com.pleos.cooldown.domain.cooldown.CooldownPolicy
import com.pleos.cooldown.domain.cooldown.HvacLimits
import com.pleos.cooldown.domain.cooldown.HvacSnapshot
import com.pleos.cooldown.domain.cooldown.InterventionOutcome
import com.pleos.cooldown.domain.cooldown.SeasonBand
import com.pleos.cooldown.domain.engine.PatternEngine
import com.pleos.cooldown.domain.engine.StateScorer
import com.pleos.cooldown.domain.model.Baseline
import com.pleos.cooldown.domain.model.Gear
import com.pleos.cooldown.domain.model.VehicleSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 시연 시나리오가 F2 전체(개입 → 2단계 → 평온 5분 → 복원 → 잠금)를 P 전에 보여 주는지 확인한다.
 * 공격성은 3분 창이라 거친 운전이 끝나고 약 2분 반 뒤에 40 아래로 내려온다. 차분 구간이 짧으면 복원 전에 주행이 끝난다.
 */
class F2ScenarioTest {

    @Test
    fun `f2_aggressive scenario restores and locks before parking`() {
        val frames = ScenarioParser.parse(File("src/debug/assets/scenarios/f2_aggressive.csv").readText())
        val engine = PatternEngine(Baseline.DEFAULT, StateScorer(hourOfDay = { 14 }))
        val policy = CooldownPolicy()
        policy.startTrip(ComfortLearner.plan(ComfortProfile.DEFAULT, SeasonBand.SUMMER), HvacLimits(16f, 30f, 8, true, 3))
        val hvac = HvacSnapshot(temperatureC = 24f, fanSpeed = 2, powerOn = true, seatVentilation = 0)

        val phases = mutableListOf<CooldownPhase>()
        val ends = mutableListOf<InterventionOutcome>()
        frames.takeWhile { it.gear != Gear.P }.forEachIndexed { i, f ->
            engine.onSample(
                f.offsetMs,
                VehicleSignal.INITIAL.copy(timestampMs = f.offsetMs, speedKmh = f.speedKmh, gear = f.gear, brakePressed = f.brakePressed),
            )
            if (i % 10 == 9) {
                engine.tick(f.offsetMs)
                policy.onTick(f.offsetMs, engine.state.value, hvac)
                    .filterIsInstance<CooldownAction.InterventionEnded>()
                    .forEach { ends += it.outcome }
                if (phases.lastOrNull() != policy.phase) phases += policy.phase
            }
        }

        assertEquals(
            listOf(CooldownPhase.IDLE, CooldownPhase.STAGE1, CooldownPhase.STAGE2, CooldownPhase.RESTORING, CooldownPhase.LOCKED),
            phases,
        )
        assertTrue(ends == listOf(InterventionOutcome.RESTORED))
    }
}
