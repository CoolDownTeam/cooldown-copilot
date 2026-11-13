package com.pleos.cooldown.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.pleos.cooldown.di.AppContainer
import com.pleos.cooldown.domain.briefing.BriefingComposer
import com.pleos.cooldown.domain.briefing.HardSectionDetector
import com.pleos.cooldown.domain.cooldown.SeasonBand
import com.pleos.cooldown.domain.model.Baseline
import com.pleos.cooldown.domain.model.DrivingState
import com.pleos.cooldown.domain.report.InterventionRecord
import com.pleos.cooldown.domain.report.TripRecord
import com.pleos.cooldown.domain.report.TripSummarizer
import com.pleos.cooldown.domain.settings.CopilotSettings
import com.pleos.cooldown.presentation.driving.DrivingStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId

/** 정차 중 화면 (SC-01·02·04·05·06). 주행 중 화면(SC-03)은 [CopilotViewModel.driving]이 덮는다 */
sealed interface Screen {
    data object Onboarding : Screen
    data object Home : Screen
    data class Report(val tripId: Long, val from: Screen) : Screen
    data object History : Screen
    data object Settings : Screen
}

data class TripItem(
    val trip: TripRecord,
    val interventions: List<InterventionRecord>,
    val headline: String,
)

data class LearningUi(val learning: Boolean, val trips: Int, val target: Int)

data class BriefingUi(val summary: String)

data class ReportUi(
    val tripId: Long,
    val dateText: String,
    val headline: String,
    val duration: String,
    val helpCount: Int,
    val thirdLabel: String,
    val thirdValue: String,
    val timeline: List<String>,
    /** 개입 효과 한 줄. 쿨다운이 없었으면 null */
    val recoveryText: String?,
    /** 오래된 것부터, 이번 주행이 [highlight] */
    val aggressionTrend: List<Int>,
    val fatigueTrend: List<Int>,
    val highlight: Int,
    val learningText: String?,
)

@OptIn(ExperimentalCoroutinesApi::class)
class CopilotViewModel(private val container: AppContainer) : ViewModel() {

    private val runner = container.copilotRunner
    private val zone: ZoneId = ZoneId.systemDefault()

    val settings: StateFlow<CopilotSettings> = container.settingsStore.settings

    private val _screen = MutableStateFlow<Screen>(if (settings.value.onboardingDone) Screen.Home else Screen.Onboarding)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    /** 기어 기반 주행 상태 또는 AAOS UX 제한 (화면 명세 1절) */
    val driving: StateFlow<Boolean> = combine(
        container.vehicleDataSource.drivingState,
        container.uxRestrictions.requiresDistractionOptimization,
    ) { state, restricted -> state == DrivingState.DRIVING || restricted }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val drivingStatus: StateFlow<DrivingStatus> = combine(
        runner.cooldownPhase, runner.fatiguePhase, runner.freshAirActive, runner.driverState,
    ) { cooldown, fatigue, air, driver -> DrivingStatus.of(cooldown, fatigue, air, driver.learning) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, DrivingStatus.CALM)

    val trips: StateFlow<List<TripItem>> = container.tripRepository.recent(HISTORY_LIMIT)
        .flatMapLatest { list ->
            container.tripRepository.interventionsOf(list.map { it.id }).map { byTrip ->
                list.mapIndexed { i, trip ->
                    val interventions = byTrip[trip.id].orEmpty()
                    TripItem(trip, interventions, TripSummarizer.headline(trip, list.drop(i + 1), interventions))
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val learning: StateFlow<LearningUi?> = runner.baseline.map { b ->
        b?.let { LearningUi(it.learning, it.totalTrips, Baseline.LEARNING_TRIPS) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val comfort = runner.comfortProfile
    val climateAvailable = runner.climateAvailable

    private val _season = MutableStateFlow(SeasonBand.MILD)
    val season: StateFlow<SeasonBand> = _season.asStateFlow()

    val briefing: StateFlow<BriefingUi?> = container.routeSource.route.map { route ->
        route?.let {
            val sections = HardSectionDetector.detect(it).size
            val duration = it.totalDurationSec?.let { s -> "목적지까지 ${BriefingComposer.durationText(s)}" } ?: "경로 안내 중"
            BriefingUi(if (sections == 0) "$duration · 어렵지 않은 길이에요" else "$duration · 어려운 구간 ${sections}곳")
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _report = MutableStateFlow<ReportUi?>(null)
    val report: StateFlow<ReportUi?> = _report.asStateFlow()

    init {
        refreshSeason()
        // 5분 이상 주행이 끝나면 리포트를 띄운다 (명세 7.1). 짧은 주행은 기록에만 남는다
        viewModelScope.launch {
            runner.finishedTrip.collect { finished ->
                if (finished == null) return@collect
                runner.consumeFinishedTrip()
                if (finished.autoShow && settings.value.onboardingDone) open(Screen.Report(finished.tripId, Screen.Home))
            }
        }
    }

    fun refreshSeason() {
        viewModelScope.launch { _season.value = container.currentSeason() }
    }

    fun open(screen: Screen) {
        if (screen is Screen.Report) loadReport(screen.tripId)
        _screen.value = screen
    }

    fun back() {
        _screen.value = when (val s = _screen.value) {
            is Screen.Report -> s.from
            Screen.History, Screen.Settings -> Screen.Home
            Screen.Home, Screen.Onboarding -> s
        }
    }

    fun updateSettings(transform: (CopilotSettings) -> CopilotSettings) = container.settingsStore.update(transform)

    /** 온보딩 2단계 (S1-01, F2-08) */
    fun answerClimateConsent(allow: Boolean) =
        updateSettings { it.copy(cooldownEnabled = allow, fatigueEnabled = allow) }

    fun finishOnboarding() {
        updateSettings { it.copy(onboardingDone = true) }
        _screen.value = Screen.Home
    }

    fun replayBriefing() = runner.replayBriefing()

    fun resetLearning() = runner.resetLearning()

    fun clearHistory() {
        viewModelScope.launch { container.tripRepository.clear() }
    }

    private fun loadReport(tripId: Long) {
        _report.value = null
        viewModelScope.launch {
            val record = container.tripRepository.load(tripId) ?: return@launch
            val all = container.tripRepository.recent(HISTORY_LIMIT).first()
            val older = all.filter { it.startMs < record.trip.startMs }
            val trend = (older.take(TripSummarizer.TREND_TRIPS - 1).reversed() + record.trip)
            val (label, value) = TripSummarizer.thirdStat(record.trip)
            _report.value = ReportUi(
                tripId = tripId,
                dateText = TripSummarizer.dateText(record.trip.startMs, zone),
                headline = TripSummarizer.headline(record.trip, older, record.interventions),
                duration = TripSummarizer.durationText(record.trip.durationMs),
                helpCount = record.interventions.size,
                thirdLabel = label,
                thirdValue = value,
                timeline = TripSummarizer.timeline(record.interventions, zone),
                recoveryText = TripSummarizer.recoveryText(
                    record.interventions,
                    container.tripRepository.interventionsOf(older.map { it.id }).first().values.flatten(),
                ),
                aggressionTrend = trend.map { it.avgAggression },
                fatigueTrend = trend.map { it.avgFatigue },
                highlight = trend.lastIndex,
                learningText = TripSummarizer.learningText(record.trip, Baseline.LEARNING_TRIPS),
            )
        }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = CopilotViewModel(container) as T
    }

    private companion object {
        const val HISTORY_LIMIT = 30
    }
}
