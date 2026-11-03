package com.pleos.cooldown.service

import android.util.Log
import com.pleos.cooldown.data.hvac.HvacController
import com.pleos.cooldown.data.vehicle.VehicleDataSource
import com.pleos.cooldown.data.voice.VoiceInput
import com.pleos.cooldown.data.voice.VoiceOutput
import com.pleos.cooldown.domain.cooldown.ComfortLearner
import com.pleos.cooldown.domain.cooldown.ComfortProfile
import com.pleos.cooldown.domain.cooldown.CooldownAction
import com.pleos.cooldown.domain.cooldown.CooldownPhase
import com.pleos.cooldown.domain.cooldown.CooldownPolicy
import com.pleos.cooldown.domain.cooldown.Feedback
import com.pleos.cooldown.domain.cooldown.FeedbackParser
import com.pleos.cooldown.domain.cooldown.SeasonBand
import com.pleos.cooldown.domain.engine.PatternEngine
import com.pleos.cooldown.domain.engine.StateScorer
import com.pleos.cooldown.domain.model.Baseline
import com.pleos.cooldown.domain.model.DriverState
import com.pleos.cooldown.domain.model.DrivingState
import com.pleos.cooldown.util.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 기준선·강도 저장소 (C0-14, F2-14) */
interface CopilotStorage {
    suspend fun loadBaseline(): Baseline
    suspend fun saveBaseline(baseline: Baseline)
    suspend fun loadComfort(): ComfortProfile
    suspend fun saveComfort(profile: ComfortProfile)
}

/**
 * 주행 한 번을 처음부터 끝까지 돌린다: 주행 감지 → C0 엔진 → F2 정책 → 공조·음성 실행 → 학습·저장.
 *
 * [scope]는 스레드 하나로 제한된 디스패처여야 한다. 모든 상태를 그 스레드에서만 바꾼다.
 */
class CopilotRunner(
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val vehicle: VehicleDataSource,
    private val hvac: HvacController,
    private val storage: CopilotStorage,
    private val scorer: StateScorer,
    private val season: () -> SeasonBand,
    private val voiceOut: VoiceOutput,
    private val voiceIn: VoiceInput,
    private val config: Config = Config(),
) {

    data class Config(
        val sampleIntervalMs: Long = 100,
        val samplesPerTick: Int = 10,
        /** 명세 0.3: P로 돌아온 뒤 60초가 지나면 주행 1회가 끝난다 */
        val tripEndAfterParkedMs: Long = 60_000,
        val listenWindowMs: Long = 8_000,
    )

    private val _driverState = MutableStateFlow(DriverState.IDLE)
    val driverState: StateFlow<DriverState> = _driverState.asStateFlow()

    private val _phase = MutableStateFlow(CooldownPhase.DISABLED)
    val cooldownPhase: StateFlow<CooldownPhase> = _phase.asStateFlow()

    private val policy = CooldownPolicy()
    private var engine: PatternEngine? = null
    private var comfort = ComfortProfile.DEFAULT
    private var band = SeasonBand.MILD

    private var rootJob: Job? = null
    private var tripJob: Job? = null
    private var tripEndJob: Job? = null

    fun start() {
        if (rootJob != null) return
        vehicle.start()
        hvac.start()
        rootJob = scope.launch {
            val e = PatternEngine(storage.loadBaseline(), scorer)
            engine = e
            comfort = storage.loadComfort()
            launch { e.state.collect { _driverState.value = it } }
            launch { hvac.manualChanges.collect { execute(policy.onManualChange(clock.nowMs(), it)) } }
            vehicle.drivingState.collect(::onDrivingState)
        }
    }

    fun stop() {
        rootJob?.cancel()
        tripJob?.cancel()
        tripEndJob?.cancel()
        rootJob = null
        tripJob = null
        tripEndJob = null
        hvac.stop()
        vehicle.stop()
    }

    /**
     * Gleo `ADJUST_CABIN_COMFORT` (F2-15·16). 개입 중이라 처리했으면 true.
     * [onResult]는 runner 스레드에서 불린다.
     */
    fun handleGleoFeedback(feedback: Feedback, onResult: (handled: Boolean) -> Unit) {
        scope.launch {
            val actions = policy.onFeedback(clock.nowMs(), feedback, fromGleo = true)
            execute(actions)
            onResult(actions.isNotEmpty())
        }
    }

    private suspend fun onDrivingState(state: DrivingState) {
        when (state) {
            DrivingState.DRIVING -> {
                tripEndJob?.cancel()
                tripEndJob = null
                if (tripJob == null) startTrip()
            }
            DrivingState.PARKED -> {
                if (tripJob != null && tripEndJob == null) {
                    tripEndJob = scope.launch {
                        delay(config.tripEndAfterParkedMs)
                        endTrip()
                    }
                }
            }
        }
    }

    private suspend fun startTrip() {
        band = season()
        // F2-23: 개입 전 설정 온도를 개인 기준 온도에 반영
        hvac.snapshot.value?.let { comfort = ComfortLearner.observeTripStartTemp(comfort, band, it.temperatureC) }
        policy.startTrip(ComfortLearner.plan(comfort, band), hvac.limits())
        publishPhase()
        Log.i(TAG, "trip started: band=$band phase=${policy.phase}")
        tripJob = scope.launch { sampleLoop() }
    }

    private suspend fun sampleLoop() {
        val e = engine ?: return
        var samples = 0
        while (scope.isActive) {
            val now = clock.nowMs()
            e.onSample(now, vehicle.signal.value)
            if (++samples % config.samplesPerTick == 0) {
                e.tick(now)
                execute(policy.onTick(now, e.state.value, hvac.snapshot.value))
            }
            delay(config.sampleIntervalMs)
        }
    }

    private suspend fun endTrip() {
        val now = clock.nowMs()
        tripJob?.cancel()
        tripJob = null
        tripEndJob = null
        execute(policy.endTrip(now))
        engine?.let { storage.saveBaseline(it.finishTrip(now)) }
        storage.saveComfort(comfort)
        Log.i(TAG, "trip ended")
    }

    private suspend fun execute(actions: List<CooldownAction>) {
        for (action in actions) {
            when (action) {
                is CooldownAction.ApplyHvac -> if (!hvac.apply(action.target)) {
                    execute(policy.onCommandFailed(clock.nowMs()))
                }
                is CooldownAction.Announce -> scope.launch { announce(action) }
                is CooldownAction.InterventionStarted -> engine?.setIntervening(true, action.atMs)
                is CooldownAction.InterventionEnded -> {
                    engine?.setIntervening(false, action.atMs)
                    // F4 리포트용 기록은 InterventionEntity 작업 때 붙인다
                    Log.i(TAG, "intervention ended: ${action.outcome} stage=${action.maxStage}")
                }
                is CooldownAction.Learn -> comfort = ComfortLearner.apply(comfort, band, action.signal)
            }
        }
        publishPhase()
    }

    /** F2-11: 안내 직후 8초 동안 "추워"·"그만"을 듣는다 */
    private suspend fun announce(action: CooldownAction.Announce) {
        voiceOut.speak(action.message)
        if (!action.listenForReply) return
        val feedback = FeedbackParser.parse(voiceIn.listenOnce(config.listenWindowMs)) ?: return
        execute(policy.onFeedback(clock.nowMs(), feedback, fromGleo = false))
    }

    private fun publishPhase() {
        _phase.value = policy.phase
    }

    private companion object {
        const val TAG = "CopilotRunner"
    }
}
