package com.pleos.cooldown.domain.engine

import com.pleos.cooldown.domain.model.Baseline
import com.pleos.cooldown.domain.model.DriverState
import com.pleos.cooldown.domain.model.VehicleSignal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * C0 운전 패턴 엔진. 특징 추출 → 점수 → 기준선 기록을 한 주행 단위로 묶는다.
 *
 * 호출하는 쪽(CopilotService)이 10Hz로 [onSample], 1초마다 [tick]을 부른다.
 * 한 코루틴에서만 호출한다.
 */
class PatternEngine(
    initialBaseline: Baseline,
    private val scorer: StateScorer,
    private val extractorFactory: () -> FeatureExtractor = { FeatureExtractor() },
    private val learner: BaselineLearner = BaselineLearner(),
) {
    private val _state = MutableStateFlow(DriverState.IDLE.copy(learning = initialBaseline.learning))

    /** C0-20 */
    val state: StateFlow<DriverState> = _state.asStateFlow()

    var baseline: Baseline = initialBaseline
        private set

    private var extractor = extractorFactory()

    fun onSample(timeMs: Long, signal: VehicleSignal) {
        extractor.add(timeMs, signal)
    }

    /** 표본이 아직 없으면 상태를 바꾸지 않는다 */
    fun tick(nowMs: Long) {
        val features = extractor.snapshot(nowMs) ?: return
        learner.record(features)
        _state.value = scorer.score(features, baseline)
    }

    /** F2·F3가 개입을 시작하거나 끝낼 때 부른다 (C0-12) */
    fun setIntervening(active: Boolean, nowMs: Long) {
        learner.setIntervening(active, nowMs)
    }

    /** 주행이 끝나면 기준선을 갱신해서 돌려준다. 저장은 호출하는 쪽이 한다 (C0-14) */
    fun finishTrip(endMs: Long): Baseline {
        baseline = learner.finishTrip(baseline, endMs)
        extractor = extractorFactory()
        _state.value = DriverState.IDLE.copy(timestampMs = endMs, learning = baseline.learning)
        return baseline
    }

    /** C0-15: 운전 습관 다시 배우기 */
    fun reset(nowMs: Long) {
        baseline = Baseline.DEFAULT
        extractor = extractorFactory()
        _state.value = DriverState.IDLE.copy(timestampMs = nowMs, learning = true)
    }
}
