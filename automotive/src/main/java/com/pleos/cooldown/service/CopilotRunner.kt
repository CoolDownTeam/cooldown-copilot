package com.pleos.cooldown.service

import android.util.Log
import com.pleos.cooldown.data.hvac.HvacController
import com.pleos.cooldown.data.navi.RouteSource
import com.pleos.cooldown.data.navi.nextRestArea
import com.pleos.cooldown.data.navi.nextTurnDistanceM
import com.pleos.cooldown.data.vehicle.VehicleDataSource
import com.pleos.cooldown.data.voice.VoiceInput
import com.pleos.cooldown.data.voice.VoiceOutput
import com.pleos.cooldown.domain.briefing.BriefingAction
import com.pleos.cooldown.domain.briefing.BriefingComposer
import com.pleos.cooldown.domain.briefing.HardSectionDetector
import com.pleos.cooldown.domain.briefing.BriefingPolicy
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
import com.pleos.cooldown.domain.report.TripRecorder
import com.pleos.cooldown.domain.report.TripSummarizer
import com.pleos.cooldown.domain.report.TripWithInterventions
import com.pleos.cooldown.domain.settings.CopilotSettings
import com.pleos.cooldown.util.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 기준선·강도·주행 기록 저장소 (C0-14, F2-14, F4) */
interface CopilotStorage {
    suspend fun loadBaseline(): Baseline
    suspend fun saveBaseline(baseline: Baseline)
    suspend fun loadComfort(): ComfortProfile
    suspend fun saveComfort(profile: ComfortProfile)
    /** 저장한 주행의 id */
    suspend fun saveTrip(record: TripWithInterventions): Long
    /** "운전 습관 다시 배우기": 기준선·강도·기준 온도를 지운다 (S1-03) */
    suspend fun resetLearning()
}

/** 방금 끝난 주행. [autoShow]면 화면이 리포트를 띄운다 (명세 7.1) */
data class FinishedTrip(val tripId: Long, val autoShow: Boolean)

/**
 * 주행 한 번을 처음부터 끝까지 돌린다: 주행 감지 → C0 엔진 → F1·F2·F3 정책 → 공조·음성 실행 → 학습·저장.
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
    private val routes: RouteSource,
    private val settings: () -> CopilotSettings = { CopilotSettings.DEFAULT },
    private val config: Config = Config(),
) {

    data class Config(
        val sampleIntervalMs: Long = 100,
        val samplesPerTick: Int = 10,
        /** 명세 0.3: P로 돌아온 뒤 60초가 지나면 주행 1회가 끝난다 */
        val tripEndAfterParkedMs: Long = 60_000,
        val listenWindowMs: Long = 8_000,
        /** 경로·휴게소 정보(TBT)를 다시 요청하는 간격 (틱 단위) */
        val restAreaRefreshTicks: Int = 10,
        /** 풍향을 다시 읽는 간격 (틱). 풍향은 변경 리스너가 없다 */
        val fanDirectionRefreshTicks: Int = 2,
    )

    private val _driverState = MutableStateFlow(DriverState.IDLE)
    val driverState: StateFlow<DriverState> = _driverState.asStateFlow()

    private val _phase = MutableStateFlow(CooldownPhase.DISABLED)
    val cooldownPhase: StateFlow<CooldownPhase> = _phase.asStateFlow()

    private val _fatiguePhase = MutableStateFlow(FatiguePhase.DISABLED)
    val fatiguePhase: StateFlow<FatiguePhase> = _fatiguePhase.asStateFlow()

    private val _finishedTrip = MutableStateFlow<FinishedTrip?>(null)
    val finishedTrip: StateFlow<FinishedTrip?> = _finishedTrip.asStateFlow()

    private val _baseline = MutableStateFlow<Baseline?>(null)
    /** 화면의 학습 진행 표시용. 불러오기 전에는 null */
    val baseline: StateFlow<Baseline?> = _baseline.asStateFlow()

    private val _comfort = MutableStateFlow(ComfortProfile.DEFAULT)
    /** 설정 화면 "배운 내 취향" 표시용 (F2-07) */
    val comfortProfile: StateFlow<ComfortProfile> = _comfort.asStateFlow()

    private val _freshAir = MutableStateFlow(false)
    /** F3 ① 바깥 공기로 환기하는 중 (SC-03 "환기하는 중") */
    val freshAirActive: StateFlow<Boolean> = _freshAir.asStateFlow()

    private val _climateAvailable = MutableStateFlow<Boolean?>(null)
    /** 공조 제어를 쓸 수 있는지. 권한이 없거나 지원하지 않으면 false, 확인 전이면 null */
    val climateAvailable: StateFlow<Boolean?> = _climateAvailable.asStateFlow()

    private val policy = CooldownPolicy()
    private val fatigue = FatiguePolicy()
    private val freshAir = FreshAirPolicy()
    private val briefing = BriefingPolicy()
    private var briefingJob: Job? = null
    private var engine: PatternEngine? = null
    private var engineStateJob: Job? = null
    private val recorder = TripRecorder()
    private var tripSettings = CopilotSettings.DEFAULT
    private var tripLearning = true
    private var comfort = ComfortProfile.DEFAULT
        set(value) {
            field = value
            _comfort.value = value
        }
    private var band = SeasonBand.MILD

    private var rootJob: Job? = null
    private var tripJob: Job? = null
    private var fanRefreshJob: Job? = null
    private var tripEndJob: Job? = null

    fun start() {
        if (rootJob != null) return
        vehicle.start()
        hvac.start()
        routes.start()
        rootJob = scope.launch {
            hvac.prepare()
            _climateAvailable.value = hvac.limits() != null
            useEngine(storage.loadBaseline())
            comfort = storage.loadComfort()
            launch { hvac.manualChanges.collect(::onManualChange) }
            // F1: 경로가 오거나 바뀌면 어려운 구간을 다시 계산하고, 정차 중이면 브리핑한다
            launch {
                routes.route.collect { route ->
                    if (route != null) recorder.onRouteSeen()
                    val actions = briefing.onRoute(route, vehicle.drivingState.value)
                    if (settings().briefingEnabled) executeBriefing(actions)
                }
            }
            vehicle.drivingState.collect(::onDrivingState)
        }
    }

    private fun useEngine(baseline: Baseline) {
        val e = PatternEngine(baseline, scorer)
        engine = e
        _baseline.value = baseline
        engineStateJob?.cancel()
        engineStateJob = scope.launch { e.state.collect { _driverState.value = it } }
    }

    /** "운전 습관 다시 배우기" (S1-03). 설정은 정차 중에만 열리므로 주행 중이면 무시한다 */
    fun resetLearning(onDone: (done: Boolean) -> Unit = {}) {
        scope.launch {
            if (tripJob != null) {
                onDone(false)
                return@launch
            }
            storage.resetLearning()
            useEngine(Baseline.DEFAULT)
            comfort = ComfortProfile.DEFAULT
            onDone(true)
        }
    }

    /** 홈 "코스 브리핑 · 듣기" (SC-02). 정차 중에만 말한다 */
    fun replayBriefing() {
        scope.launch {
            val route = routes.route.value ?: return@launch
            if (vehicle.drivingState.value != DrivingState.PARKED || !settings().voiceEnabled) return@launch
            briefingJob?.cancel()
            briefingJob = scope.launch {
                voiceOut.speak(BriefingComposer.briefing(route, HardSectionDetector.detect(route)))
            }
        }
    }

    /** 화면이 리포트를 띄웠으면 다시 띄우지 않게 지운다 */
    fun consumeFinishedTrip() {
        _finishedTrip.value = null
    }

    fun stop() {
        engineStateJob?.cancel()
        engineStateJob = null
        rootJob?.cancel()
        tripJob?.cancel()
        tripEndJob?.cancel()
        rootJob = null
        tripJob = null
        tripEndJob = null
        routes.stop()
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
        executeBriefing(briefing.onDrivingState(state, routes.route.value))
        when (state) {
            DrivingState.DRIVING -> {
                tripEndJob?.cancel()
                tripEndJob = null
                if (tripJob == null) startTrip()
            }
            DrivingState.PARKED -> {
                // F3-09: 휴게소로 가던 중이면 정차하자마자 휴식 요령을 말한다
                if (tripJob != null) {
                    if (fatigue.phase == FatiguePhase.HEADING_TO_REST) recorder.onRested()
                    executeFatigue(fatigue.onParked(clock.nowMs()))
                }
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
        tripSettings = settings()
        tripLearning = _baseline.value?.learning ?: true
        recorder.start(clock.nowMs(), vehicle.signal.value.odometerKm)
        if (routes.route.value != null) recorder.onRouteSeen()
        band = season()
        // F2-23: 개입 전 설정 온도를 개인 기준 온도에 반영
        hvac.snapshot.value?.let { comfort = ComfortLearner.observeTripStartTemp(comfort, band, it.temperatureC) }
        val limits = hvac.limits()
        // S1-02: 꺼 둔 기능은 이번 주행 동안 DISABLED로 둔다
        policy.startTrip(ComfortLearner.plan(comfort, band).takeIf { tripSettings.cooldownEnabled }, limits)
        fatigue.startTrip(limits.takeIf { tripSettings.fatigueEnabled })
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
                // 따로 띄워서, SDK가 답하지 않아도(최대 3초) 샘플링이 멈추지 않게 한다
                if ((samples / config.samplesPerTick) % config.fanDirectionRefreshTicks == 0 && fanRefreshJob?.isActive != true) {
                    fanRefreshJob = scope.launch { hvac.refreshFanDirection() }
                }
                val driver = e.state.value
                recorder.onState(driver)
                val route = routes.route.value
                // F3-02: 피로 케어가 공조를 쓰는 동안 F2는 들어오지 않는다
                execute(policy.onTick(now, driver, if (fatigue.holdingClimate) null else hvac.snapshot.value))
                executeFatigue(
                    fatigue.onTick(
                        now, driver, hvac.snapshot.value, policy.intervening,
                        route?.nextRestArea(), route?.nextTurnDistanceM(),
                    )
                )
                if (tripSettings.fatigueEnabled) {
                    executeFreshAir(freshAir.onTick(now, hvac.snapshot.value, suspended = fatigue.holdingClimate))
                }
                if (tripSettings.briefingEnabled) executeBriefing(briefing.onTick(route, DrivingState.DRIVING))
                if (samples % (config.samplesPerTick * config.restAreaRefreshTicks) == 0) routes.refresh()
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
        val learned = engine?.finishTrip(now)
        if (learned != null) {
            storage.saveBaseline(learned)
            _baseline.value = learned
        }
        storage.saveComfort(comfort)
        recorder.finish(now, vehicle.signal.value.odometerKm, tripLearning, learned?.totalTrips ?: 0)?.let { record ->
            val id = storage.saveTrip(record)
            _finishedTrip.value = FinishedTrip(id, TripSummarizer.shouldAutoShow(record.trip))
        }
        Log.i(TAG, "trip ended")
    }

    private suspend fun execute(actions: List<CooldownAction>) {
        for (action in actions) {
            when (action) {
                is CooldownAction.ApplyHvac -> if (!hvac.apply(action.target)) {
                    execute(policy.onCommandFailed(clock.nowMs()))
                }
                is CooldownAction.Announce -> scope.launch { announce(action) }
                is CooldownAction.InterventionStarted -> recorder.onCooldownStarted(action.atMs)
                is CooldownAction.InterventionEnded -> {
                    Log.i(TAG, "cooldown ended: ${action.outcome} stage=${action.maxStage}")
                    recorder.onCooldownEnded(action.atMs, action.outcome, action.maxStage)
                }
                is CooldownAction.Learn -> {
                    val before = comfort.band(band).level
                    comfort = ComfortLearner.apply(comfort, band, action.signal)
                    recorder.onComfortLevelChanged(comfort.band(band).level - before)
                }
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
                is FatigueAction.InterventionStarted -> recorder.onFatigueHoldStarted(action.atMs)
                is FatigueAction.InterventionEnded -> {
                    Log.i(TAG, "fatigue hold-on ended: ${action.outcome}")
                    recorder.onFatigueHoldEnded(action.atMs, action.outcome)
                }
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
                is FreshAirAction.Started -> {
                    _freshAir.value = true
                    recorder.onFreshAirStarted(action.atMs)
                }
                is FreshAirAction.Ended -> {
                    _freshAir.value = false
                    recorder.onFreshAirEnded(action.atMs, action.yielded)
                }
            }
        }
    }

    private fun executeBriefing(actions: List<BriefingAction>) {
        for (action in actions) {
            when (action) {
                is BriefingAction.SpeakBriefing -> {
                    briefingJob?.cancel()
                    briefingJob = scope.launch {
                        if (settings().voiceEnabled) voiceOut.speak(action.text)
                        briefing.onBriefingFinished()
                    }
                }
                is BriefingAction.SpeakAlert -> {
                    recorder.onHardSectionAlert()
                    if (settings().voiceEnabled) scope.launch { voiceOut.speak(action.text) }
                }
                BriefingAction.StopSpeaking -> {
                    briefingJob?.cancel()
                    briefingJob = null
                    voiceOut.stop()
                }
            }
        }
    }

    /** F3-03: 제안 뒤 8초 동안 "응"·"아니"를 듣는다. 무응답도 거절로 넘긴다 */
    private suspend fun announceFatigue(action: FatigueAction.Announce) {
        if (!settings().voiceEnabled) {
            // 음성을 껐으면 묻지 않은 것이니 답이 없는 것으로 넘긴다
            if (action.listenForReply) executeFatigue(fatigue.onReply(clock.nowMs(), null))
            return
        }
        voiceOut.speak(action.message)
        if (!action.listenForReply) return
        val accepted = ReplyParser.parse(voiceIn.listenOnce(config.listenWindowMs))
        executeFatigue(fatigue.onReply(clock.nowMs(), accepted))
    }

    /** F2-11: 안내 직후 8초 동안 "추워"·"그만"을 듣는다 */
    private suspend fun announce(action: CooldownAction.Announce) {
        if (!settings().voiceEnabled) return
        voiceOut.speak(action.message)
        if (!action.listenForReply) return
        val feedback = FeedbackParser.parse(voiceIn.listenOnce(config.listenWindowMs)) ?: return
        execute(policy.onFeedback(clock.nowMs(), feedback, fromGleo = false))
    }

    /** C0-12: F2·F3가 공조를 쓰는 동안은 기준선 학습에서 뺀다 */
    private fun publishPhase() {
        _phase.value = policy.phase
        _fatiguePhase.value = fatigue.phase
        recorder.onFatiguePhase(clock.nowMs(), fatigue.phase)
        engine?.setIntervening(policy.intervening || fatigue.holdingClimate, clock.nowMs())
    }

    private companion object {
        const val TAG = "CopilotRunner"
    }
}
