package com.pleos.cooldown.domain.report

import com.pleos.cooldown.domain.cooldown.InterventionOutcome
import com.pleos.cooldown.domain.fatigue.FatigueAction
import com.pleos.cooldown.domain.fatigue.FatiguePhase
import com.pleos.cooldown.domain.model.DriverState

/**
 * 주행 1회 동안 상태 점수와 개입을 모아 [TripWithInterventions]로 만든다 (F4).
 * 순수 Kotlin. CopilotRunner 스레드에서만 호출한다.
 */
class TripRecorder(private val calmBelow: Int = CALM_BELOW) {

    private var startMs: Long? = null
    private var startOdometerKm: Float? = null
    private var ticks = 0
    private var aggressionSum = 0L
    private var fatigueSum = 0L
    private var maxAggression = 0
    private var maxFatigue = 0
    private var calmSinceMs: Long? = null
    private var longestCalmMs = 0L
    private var routeSeen = false
    private var hardSections = 0
    private var lastFatiguePhase = FatiguePhase.DISABLED

    private val closed = mutableListOf<InterventionRecord>()
    private val open = mutableMapOf<InterventionKind, InterventionRecord>()

    val active: Boolean get() = startMs != null

    fun start(nowMs: Long, odometerKm: Float?) {
        reset()
        startMs = nowMs
        startOdometerKm = odometerKm
    }

    fun onState(state: DriverState) {
        if (!active) return
        ticks++
        aggressionSum += state.aggression
        fatigueSum += state.fatigue
        maxAggression = maxOf(maxAggression, state.aggression)
        maxFatigue = maxOf(maxFatigue, state.fatigue)
        markRecovery(state)
        if (state.aggression < calmBelow) {
            val since = calmSinceMs ?: state.timestampMs.also { calmSinceMs = it }
            longestCalmMs = maxOf(longestCalmMs, state.timestampMs - since)
        } else {
            calmSinceMs = null
        }
    }

    /** 진행 중인 쿨다운에서 공격성이 처음 차분 기준 아래로 내려온 시점을 남긴다 */
    private fun markRecovery(state: DriverState) {
        val cooldown = open[InterventionKind.COOLDOWN] ?: return
        if (cooldown.recoveryMs != null || state.aggression >= calmBelow || state.timestampMs < cooldown.startMs) return
        open[InterventionKind.COOLDOWN] = cooldown.copy(recoveryMs = state.timestampMs - cooldown.startMs)
    }

    fun onRouteSeen() {
        if (active) routeSeen = true
    }

    /** F1 직전 안내를 한 번 했다 = 어려운 구간 하나를 지난다 */
    fun onHardSectionAlert() {
        if (active) hardSections++
    }

    fun onCooldownStarted(atMs: Long) = openRecord(atMs, InterventionKind.COOLDOWN)

    fun onCooldownEnded(atMs: Long, outcome: InterventionOutcome, maxStage: Int) {
        val result = when (outcome) {
            InterventionOutcome.RESTORED, InterventionOutcome.TRIP_ENDED -> InterventionResult.DONE
            InterventionOutcome.COLD_FEEDBACK -> InterventionResult.COLD
            InterventionOutcome.STOPPED -> InterventionResult.STOPPED
            InterventionOutcome.YIELDED_MANUAL -> InterventionResult.YIELDED
            InterventionOutcome.FAILED -> InterventionResult.FAILED
        }
        closeRecord(atMs, InterventionKind.COOLDOWN, result, maxStage)
    }

    /** F2 강도 학습 결과를 가장 최근 쿨다운에 붙인다 (UI-05) */
    fun onComfortLevelChanged(delta: Int) {
        if (delta == 0) return
        open[InterventionKind.COOLDOWN]?.let {
            open[InterventionKind.COOLDOWN] = it.copy(levelChange = it.levelChange + delta)
            return
        }
        val index = closed.indexOfLast { it.kind == InterventionKind.COOLDOWN }
        if (index >= 0) closed[index] = closed[index].copy(levelChange = closed[index].levelChange + delta)
    }

    fun onFatigueHoldStarted(atMs: Long) = openRecord(atMs, InterventionKind.FATIGUE_HOLD_ON)

    fun onFatigueHoldEnded(atMs: Long, outcome: FatigueAction.Outcome) {
        val result = when (outcome) {
            FatigueAction.Outcome.RESTORED, FatigueAction.Outcome.TRIP_ENDED -> InterventionResult.DONE
            FatigueAction.Outcome.RESTED -> InterventionResult.RESTED
            FatigueAction.Outcome.YIELDED -> InterventionResult.YIELDED
            FatigueAction.Outcome.FAILED -> InterventionResult.FAILED
        }
        closeRecord(atMs, InterventionKind.FATIGUE_HOLD_ON, result, maxStage = 1)
    }

    fun onFreshAirStarted(atMs: Long) = openRecord(atMs, InterventionKind.FRESH_AIR)

    fun onFreshAirEnded(atMs: Long, yielded: Boolean) =
        closeRecord(atMs, InterventionKind.FRESH_AIR, if (yielded) InterventionResult.YIELDED else InterventionResult.DONE, maxStage = 1)

    /**
     * 휴게소 제안은 F3 단계 변화로 기록한다: 답을 기다리기 시작 → 제안, 그다음 단계로 수락·거절.
     */
    fun onFatiguePhase(nowMs: Long, phase: FatiguePhase) {
        val previous = lastFatiguePhase
        lastFatiguePhase = phase
        if (!active || phase == previous) return
        if (phase == FatiguePhase.AWAITING_REPLY) openRecord(nowMs, InterventionKind.FATIGUE_SUGGEST)
        if (previous == FatiguePhase.AWAITING_REPLY) {
            val result = if (phase == FatiguePhase.HEADING_TO_REST) InterventionResult.ACCEPTED else InterventionResult.DECLINED
            closeRecord(nowMs, InterventionKind.FATIGUE_SUGGEST, result, maxStage = 1)
        }
    }

    /** 휴게소로 가던 중 정차했다 (F3-09) */
    fun onRested() {
        val index = closed.indexOfLast { it.kind == InterventionKind.FATIGUE_SUGGEST && it.result == InterventionResult.ACCEPTED }
        if (index >= 0) closed[index] = closed[index].copy(result = InterventionResult.RESTED)
    }

    /** 주행이 끝났다. 시작하지 않았으면 null */
    fun finish(nowMs: Long, odometerKm: Float?, learning: Boolean, learnedTrips: Int): TripWithInterventions? {
        val start = startMs ?: return null
        open.values.toList().forEach { record ->
            val result = if (record.kind == InterventionKind.FATIGUE_SUGGEST) InterventionResult.DECLINED else InterventionResult.DONE
            closed += record.copy(endMs = nowMs, result = result)
        }
        open.clear()
        val distance = startOdometerKm?.let { from -> odometerKm?.let { to -> (to - from).coerceAtLeast(0f) } }
        val trip = TripRecord(
            startMs = start,
            endMs = nowMs,
            distanceKm = distance,
            avgAggression = if (ticks == 0) 0 else (aggressionSum / ticks).toInt(),
            maxAggression = maxAggression,
            avgFatigue = if (ticks == 0) 0 else (fatigueSum / ticks).toInt(),
            maxFatigue = maxFatigue,
            learning = learning,
            learnedTrips = learnedTrips,
            hardSections = if (routeSeen) hardSections else null,
            longestCalmMs = longestCalmMs,
        )
        val result = TripWithInterventions(trip, closed.sortedBy { it.startMs })
        reset()
        return result
    }

    private fun openRecord(atMs: Long, kind: InterventionKind) {
        if (!active || kind in open) return
        open[kind] = InterventionRecord(atMs, null, kind, maxStage = 0, result = InterventionResult.DONE)
    }

    private fun closeRecord(atMs: Long, kind: InterventionKind, result: InterventionResult, maxStage: Int) {
        val record = open.remove(kind) ?: return
        closed += record.copy(endMs = atMs, result = result, maxStage = maxStage)
    }

    private fun reset() {
        startMs = null
        startOdometerKm = null
        ticks = 0
        aggressionSum = 0
        fatigueSum = 0
        maxAggression = 0
        maxFatigue = 0
        calmSinceMs = null
        longestCalmMs = 0
        routeSeen = false
        hardSections = 0
        closed.clear()
        open.clear()
    }

    companion object {
        /** F2·F3의 "평온" 기준과 같다 */
        const val CALM_BELOW = 40
    }
}
