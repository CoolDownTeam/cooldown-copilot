package com.pleos.cooldown.service

import com.pleos.cooldown.data.hvac.FakeHvacController
import com.pleos.cooldown.data.navi.FakeRestAreaSource
import com.pleos.cooldown.data.vehicle.VehicleDataSource
import com.pleos.cooldown.data.voice.VoiceInput
import com.pleos.cooldown.data.voice.VoiceOutput
import com.pleos.cooldown.domain.cooldown.ComfortProfile
import com.pleos.cooldown.domain.cooldown.CooldownPhase
import com.pleos.cooldown.domain.cooldown.CooldownPolicy
import com.pleos.cooldown.domain.cooldown.Feedback
import com.pleos.cooldown.domain.cooldown.SeasonBand
import com.pleos.cooldown.domain.engine.StateScorer
import com.pleos.cooldown.domain.fatigue.FatiguePhase
import com.pleos.cooldown.domain.fatigue.FatiguePolicy
import com.pleos.cooldown.domain.fatigue.RestArea
import com.pleos.cooldown.domain.model.Baseline
import com.pleos.cooldown.domain.model.DrivingState
import com.pleos.cooldown.domain.model.Gear
import com.pleos.cooldown.domain.model.SignalType
import com.pleos.cooldown.domain.model.VehicleSignal
import com.pleos.cooldown.util.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CopilotRunnerTest {

    private val vehicle = FakeVehicle()
    private val hvac = FakeHvacController()
    private val restAreas = FakeRestAreaSource(RestArea("행담도휴게소", 12_000.0), nextTurnM = 5_000.0)
    private val storage = MemoryStorage()
    private val spoken = mutableListOf<String>()
    private var reply: String? = null

    private val voiceOut = object : VoiceOutput {
        override suspend fun speak(text: String) {
            spoken += text
        }
    }
    private val voiceIn = object : VoiceInput {
        override suspend fun listenOnce(timeoutMs: Long): String? {
            delay(2_000)
            return reply
        }
    }

    private fun TestScope.runner(): CopilotRunner = CopilotRunner(
        scope = backgroundScope,
        clock = Clock { testScheduler.currentTime },
        vehicle = vehicle,
        hvac = hvac,
        storage = storage,
        scorer = StateScorer(hourOfDay = { 14 }),
        season = { SeasonBand.SUMMER },
        voiceOut = voiceOut,
        voiceIn = voiceIn,
        restAreas = restAreas,
    )

    /** 급가속·급제동 반복 (8.5초 주기) */
    private fun TestScope.driveAggressively() {
        backgroundScope.launch {
            var speed = 10.0
            while (true) {
                repeat(20) { speed += 3.5 * 0.36; vehicle.emit(speed, brake = false); delay(100) }
                repeat(30) { vehicle.emit(speed, brake = false); delay(100) }
                repeat(15) { speed -= 4.5 * 0.36; vehicle.emit(speed, brake = true); delay(100) }
                repeat(20) { vehicle.emit(speed, brake = false); delay(100) }
            }
        }
    }

    @Test
    fun `aggressive trip cools the cabin and parking restores and saves`() = runTest {
        val runner = runner()
        runner.start()
        vehicle.drivingState.value = DrivingState.DRIVING
        driveAggressively()

        advanceTimeBy(90_000)

        assertEquals(CooldownPhase.STAGE1, runner.cooldownPhase.value)
        assertEquals(23f, hvac.snapshot.value?.temperatureC)
        assertEquals(listOf(CooldownPolicy.MESSAGE_COOLING), spoken)

        vehicle.drivingState.value = DrivingState.PARKED
        advanceTimeBy(61_000)
        runCurrent()

        assertEquals(CooldownPhase.DISABLED, runner.cooldownPhase.value)
        assertEquals(24f, hvac.snapshot.value?.temperatureC)
        assertEquals(11, storage.baseline.totalTrips)
        assertNotNull(storage.comfort.band(SeasonBand.SUMMER).baselineTempC)
        runner.stop()
    }

    @Test
    fun `saying cold after the announcement restores and lowers summer intensity`() = runTest {
        reply = "아 추워"
        val runner = runner()
        runner.start()
        vehicle.drivingState.value = DrivingState.DRIVING
        driveAggressively()

        advanceTimeBy(90_000)

        assertEquals(CooldownPhase.YIELDED, runner.cooldownPhase.value)
        assertEquals(24f, hvac.snapshot.value?.temperatureC)
        assertEquals(listOf(CooldownPolicy.MESSAGE_COOLING, CooldownPolicy.MESSAGE_RESTORED), spoken)

        vehicle.drivingState.value = DrivingState.PARKED
        advanceTimeBy(61_000)
        runCurrent()
        assertEquals(2, storage.comfort.band(SeasonBand.SUMMER).level)
        runner.stop()
    }

    @Test
    fun `Gleo feedback is handled only while intervening`() = runTest {
        val runner = runner()
        runner.start()
        vehicle.drivingState.value = DrivingState.DRIVING
        advanceTimeBy(5_000)

        var idleResult: Boolean? = null
        runner.handleGleoFeedback(Feedback.COLD) { idleResult = it }
        runCurrent()
        assertEquals(false, idleResult)

        driveAggressively()
        advanceTimeBy(90_000)
        var activeResult: Boolean? = null
        runner.handleGleoFeedback(Feedback.COLD) { activeResult = it }
        runCurrent()

        assertEquals(true, activeResult)
        assertEquals(24f, hvac.snapshot.value?.temperatureC)
        assertFalse(CooldownPolicy.MESSAGE_RESTORED in spoken)
        runner.stop()
    }

    @Test
    fun `a short stop does not end the trip`() = runTest {
        val runner = runner()
        runner.start()
        vehicle.drivingState.value = DrivingState.DRIVING
        advanceTimeBy(10_000)

        vehicle.drivingState.value = DrivingState.PARKED
        advanceTimeBy(30_000)
        vehicle.drivingState.value = DrivingState.DRIVING
        advanceTimeBy(60_000)

        assertTrue(runner.cooldownPhase.value != CooldownPhase.DISABLED)
        assertEquals(10, storage.baseline.totalTrips)
        runner.stop()
    }

    /** 고속도로를 핸들 그대로 두고 단조롭게 달린다 (조향 미세 보정 0, 정지 비율 1) */
    private fun TestScope.driveMonotonously() {
        backgroundScope.launch {
            while (true) {
                vehicle.emit(100.0, brake = false)
                delay(100)
            }
        }
    }

    @Test
    fun `F3 long monotonous drive - fresh air, hold on, suggestion, rest tip`() = runTest {
        val runner = runner()
        runner.start()
        vehicle.drivingState.value = DrivingState.DRIVING
        driveMonotonously()

        // ① 예방: 운전자 1명, 내기순환 30분 → 외기 5분
        advanceTimeBy(30 * 60_000L + 2_000)
        assertEquals(false, hvac.snapshot.value?.recirculation)
        advanceTimeBy(5 * 60_000L)
        assertEquals(true, hvac.snapshot.value?.recirculation)
        assertTrue(spoken.isEmpty())

        // ② 감지 → HOLD_ON: 연속 40분이 지나고 피로 60 이상 60초
        advanceTimeBy(7 * 60_000L)
        assertEquals(FatiguePhase.HOLD_ON, runner.fatiguePhase.value)
        assertEquals(23f, hvac.snapshot.value?.temperatureC)
        assertEquals(false, hvac.snapshot.value?.recirculation)

        // ③ 10분 뒤 휴게소 제안 → "응"
        reply = "응 들르자"
        advanceTimeBy(10 * 60_000L + 5_000)
        assertEquals(
            listOf("12km 앞 행담도휴게소에서 잠깐 쉬어 갈까요?", FatiguePolicy.MESSAGE_ACCEPTED),
            spoken
        )
        assertEquals(FatiguePhase.HEADING_TO_REST, runner.fatiguePhase.value)

        // 휴게소에서 P단 → 휴식 요령, 공조 복원
        vehicle.drivingState.value = DrivingState.PARKED
        runCurrent()
        assertEquals(FatiguePolicy.MESSAGE_REST_TIP, spoken.last())
        assertEquals(24f, hvac.snapshot.value?.temperatureC)
        assertEquals(true, hvac.snapshot.value?.recirculation)
        runner.stop()
    }

    @Test
    fun `Gleo rest reply is handled only while a suggestion waits`() = runTest {
        val runner = runner()
        runner.start()
        vehicle.drivingState.value = DrivingState.DRIVING
        advanceTimeBy(5_000)

        var result: Boolean? = null
        runner.handleGleoRestReply(accepted = true) { result = it }
        runCurrent()

        assertEquals(false, result)
        runner.stop()
    }

    private class FakeVehicle : VehicleDataSource {
        override val signal = MutableStateFlow(VehicleSignal.INITIAL.copy(gear = Gear.D))
        override val drivingState = MutableStateFlow(DrivingState.PARKED)
        override val unavailable: StateFlow<Set<SignalType>> = MutableStateFlow(emptySet())
        override fun start() = Unit
        override fun stop() = Unit

        fun emit(speedKmh: Double, brake: Boolean) {
            signal.value = signal.value.copy(speedKmh = speedKmh.toFloat(), brakePressed = brake)
        }
    }

    private class MemoryStorage : CopilotStorage {
        var baseline: Baseline = Baseline.DEFAULT.copy(totalTrips = 10, totalDrivingMinutes = 300.0)
        var comfort: ComfortProfile = ComfortProfile.DEFAULT
        override suspend fun loadBaseline() = baseline
        override suspend fun saveBaseline(baseline: Baseline) {
            this.baseline = baseline
        }
        override suspend fun loadComfort() = comfort
        override suspend fun saveComfort(profile: ComfortProfile) {
            comfort = profile
        }
    }
}
