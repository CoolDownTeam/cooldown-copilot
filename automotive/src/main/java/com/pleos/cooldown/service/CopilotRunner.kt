package com.pleos.cooldown.service

import android.util.Log
import com.pleos.cooldown.data.hvac.HvacController
import com.pleos.cooldown.data.navi.RestAreaSource
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
import com.pleos.cooldown.domain.cooldown.ManualChange
import com.pleos.cooldown.domain.cooldown.SeasonBand
import com.pleos.cooldown.domain.engine.PatternEngine
import com.pleos.cooldown.domain.fatigue.FatigueAction
import com.pleos.cooldown.domain.fatigue.FatiguePhase
import com.pleos.cooldown.domain.fatigue.FatiguePolicy
import com.pleos.cooldown.domain.fatigue.FreshAirAction
import com.pleos.cooldown.domain.fatigue.FreshAirPolicy
import com.pleos.cooldown.domain.fatigue.ReplyParser
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
 * 주행 한 번을 처음부터 끝까지 돌린다: 주행 감지 → C0 엔진 → F2·F3 정책 → 공조·음성 실행 → 학습·저장.
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
    private val restAreas: RestAreaSource,
    private val config: Config = Config(),
) {

    data class Config(
        val sampleIntervalMs: Long = 100,
        val samplesPerTick: Int = 10,
        /** 명세 0.3: P로 돌아온 뒤 60초가 지나면 주행 1회가 끝난다 */
        val tripEndAfterParkedMs: Long = 60_000,
        val listenWindowMs: Long = 8_000,
        /** 휴게소 정보(TBT)를 다시 요청하는 간격 (틱 단위) */
        val restAreaRefreshTicks: Int = 10,
    )

    private val _driverState = MutableStateFlow(DriverState.IDLE)
    val driverState: StateFlow<DriverState> = _driverState.asStateFlow()

    private val _phase = MutableStateFlow(CooldownPhase.DISABLED)
    val cooldownPhase: StateFlow<CooldownPhase> = _phase.asStateFlow()

    private val _fatiguePhase = MutableStateFlow(FatiguePhase.DISABLED)
    val fatiguePhase: StateFlow<FatiguePhase> = _fatiguePhase.asStateFlow()

    private val policy = CooldownPolicy()
    private val fatigue = FatiguePolicy()
    private val freshAir = FreshAirPolicy()
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
        restAreas.start()
        rootJob = scope.launch {
            val e = PatternEngine(storage.loadBaseline(), scorer)
            engine = e
            comfort = storage.loadComfort()
            launch { e.state.collect { _driverState.value = it } }
            launch { hvac.manualChanges.collect(::onManualChange) }
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
        restAreas.stop()
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

    /** Gleo `ANSWER_REST_SUGGESTION` (명세 4.8, F3-03). 제안에 대한 답을 기다리던 중이면 true */
    fun handleGleoRestReply(accepted: Boolean, onResult: (handled: Boolean) -> Unit) {
        scope.launch {
            val waiting = fatigue.phase == FatiguePhase.AWAITING_REPLY
            executeFatigue(fatigue.onReply(clock.nowMs(), accepted))
            onResult(waiting)
        }
    }

    private suspend fun onManualChange(change: ManualChange) {
        val now = clock.nowMs()
        execute(policy.onManualChange(now, change))
        executeFatigue(fatigue.onManualChange(now, change))
        if (change == ManualChange.RECIRCULATION) {
            executeFreshAir(freshAir.onManualRecirculation(now, hvac.snapshot.value?.recirculation ?: true))
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
                // F3-09: 휴게소로 가던 중이면 정차하자마자 휴식 요령을 말한다
                if (tripJob != null) executeFatigue(fatigue.onParked(clock.nowMs()))
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
        val limits = hvac.limits()
        policy.startTrip(ComfortLearner.plan(comfort, band), limits)
        fatigue.startTrip(limits)
        freshAir.startTrip(hvac.occupantCount())
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
                val driver = e.state.value
                // F3-02: 피로 케어가 공조를 쓰는 동안 F2는 들어오지 않는다
                execute(policy.onTick(now, driver, if (fatigue.holdingClimate) null else hvac.snapshot.value))
                executeFatigue(
                    fatigue.onTick(
                        now, driver, hvac.snapshot.value, policy.intervening,
                        restAreas.nextRestArea.value, restAreas.nextTurnDistanceM.value,
                    )
                )
                executeFreshAir(freshAir.onTick(now, hvac.snapshot.value, suspended = fatigue.holdingClimate))
                if (samples % (config.samplesPerTick * config.restAreaRefreshTicks) == 0) restAreas.refresh()
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
        executeFatigue(fatigue.endTrip(now))
        executeFreshAir(freshAir.endTrip(now))
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
                is CooldownAction.InterventionStarted -> Log.i(TAG, "cooldown started")
                // F4 리포트용 기록은 InterventionEntity 작업 때 붙인다
                is CooldownAction.InterventionEnded -> Log.i(TAG, "cooldown ended: ${action.outcome} stage=${action.maxStage}")
                is CooldownAction.Learn -> comfort = ComfortLearner.apply(comfort, band, action.signal)
            }
        }
        publishPhase()
    }

    private suspend fun executeFatigue(actions: List<FatigueAction>) {
        for (action in actions) {
            when (action) {
                is FatigueAction.ApplyHvac -> if (!hvac.apply(action.target)) {
                    executeFatigue(fatigue.onCommandFailed(clock.nowMs()))
                }
                is FatigueAction.SetRecirculation -> if (!hvac.setRecirculation(action.on)) {
                    Log.w(TAG, "recirculation switch failed")
                }
                is FatigueAction.Announce -> scope.launch { announceFatigue(action) }
                is FatigueAction.InterventionStarted -> Log.i(TAG, "fatigue hold-on started")
                is FatigueAction.InterventionEnded -> Log.i(TAG, "fatigue hold-on ended: ${action.outcome}")
            }
        }
        publishPhase()
    }

    private suspend fun executeFreshAir(actions: List<FreshAirAction>) {
        for (action in actions) {
            when (action) {
                is FreshAirAction.SetRecirculation -> if (!hvac.setRecirculation(action.on)) {
                    Log.w(TAG, "fresh air switch failed")
                }
                is FreshAirAction.Started -> Log.i(TAG, "fresh air started")
                is FreshAirAction.Ended -> Log.i(TAG, "fresh air ended yielded=${action.yielded}")
            }
        }
    }

    /** F3-03: 제안 뒤 8초 동안 "응"·"아니"를 듣는다. 무응답도 거절로 넘긴다 */
    private suspend fun announceFatigue(action: FatigueAction.Announce) {
        voiceOut.speak(action.message)
        if (!action.listenForReply) return
        val accepted = ReplyParser.parse(voiceIn.listenOnce(config.listenWindowMs))
        executeFatigue(fatigue.onReply(clock.nowMs(), accepted))
    }

    /** F2-11: 안내 직후 8초 동안 "추워"·"그만"을 듣는다 */
    private suspend fun announce(action: CooldownAction.Announce) {
        voiceOut.speak(action.message)
        if (!action.listenForReply) return
        val feedback = FeedbackParser.parse(voiceIn.listenOnce(config.listenWindowMs)) ?: return
        execute(policy.onFeedback(clock.nowMs(), feedback, fromGleo = false))
    }

    /** C0-12: F2·F3가 공조를 쓰는 동안은 기준선 학습에서 뺀다 */
    private fun publishPhase() {
        _phase.value = policy.phase
        _fatiguePhase.value = fatigue.phase
        engine?.setIntervening(policy.intervening || fatigue.holdingClimate, clock.nowMs())
    }

    private companion object {
        const val TAG = "CopilotRunner"
    }
}
