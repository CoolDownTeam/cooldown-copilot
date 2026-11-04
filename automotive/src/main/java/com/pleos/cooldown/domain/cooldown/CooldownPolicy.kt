package com.pleos.cooldown.domain.cooldown

import com.pleos.cooldown.domain.model.DriverState
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

enum class CooldownPhase { DISABLED, IDLE, STAGE1, STAGE2, RESTORING, LOCKED, YIELDED }

enum class InterventionOutcome { RESTORED, YIELDED_MANUAL, COLD_FEEDBACK, STOPPED, TRIP_ENDED, FAILED }

/** 운전자의 음성 반응 (STT 응답창 또는 Gleo) */
enum class Feedback { COLD, STOP }

/** 정책이 내놓는 할 일. 실제 실행(공조·음성·기록)은 호출하는 쪽이 한다 */
sealed interface CooldownAction {
    data class ApplyHvac(val target: HvacTarget) : CooldownAction
    data class Announce(val message: String, val listenForReply: Boolean) : CooldownAction
    data class InterventionStarted(val atMs: Long) : CooldownAction
    data class InterventionEnded(val atMs: Long, val outcome: InterventionOutcome, val maxStage: Int) : CooldownAction
    data class Learn(val signal: ComfortSignal) : CooldownAction
}

/**
 * F2 쿨다운 캐빈 상태 머신 (명세 4.2). 순수 Kotlin이고 시간은 인자로 받는다.
 * 한 코루틴에서만 호출한다.
 */
class CooldownPolicy(private val config: Config = Config()) {

    data class Config(
        val personalThreshold: Int = 60,
        val learningThreshold: Int = 75,
        val calmBelow: Int = 40,
        val enterAfterMs: Long = 30_000,
        val stage2AfterMs: Long = 120_000,
        val restoreAfterCalmMs: Long = 300_000,
        val restoreDurationMs: Long = 60_000,
        val lockMs: Long = 900_000,
        val absoluteMinTempC: Float = 18f,
        val restoreTempStepC: Float = 0.5f,
        /** onTick 간격. 조건을 처음 만족한 틱도 1초 유지한 것으로 센다 */
        val tickMs: Long = 1_000,
    )

    var phase: CooldownPhase = CooldownPhase.DISABLED
        private set

    private var plan: CooldownPlan? = null
    private var limits: HvacLimits? = null
    private var original: HvacSnapshot? = null
    private var current: HvacTarget? = null
    private var maxStage = 0

    private var highSinceMs: Long? = null
    private var calmSinceMs: Long? = null
    private var stageEnteredMs = 0L
    private var lockedAtMs = 0L
    private var restoreSteps: List<Pair<Long, HvacTarget>> = emptyList()

    val intervening: Boolean
        get() = phase == CooldownPhase.STAGE1 || phase == CooldownPhase.STAGE2 || phase == CooldownPhase.RESTORING

    /** 주행 시작. [plan]이 null(L0)이거나 [limits]를 모르면 이번 주행은 개입하지 않는다 */
    fun startTrip(plan: CooldownPlan?, limits: HvacLimits?) {
        this.plan = plan
        this.limits = limits
        phase = if (plan != null && limits != null) CooldownPhase.IDLE else CooldownPhase.DISABLED
        resetIntervention()
    }

    fun onTick(nowMs: Long, driver: DriverState, hvac: HvacSnapshot?): List<CooldownAction> {
        val threshold = if (driver.learning) config.learningThreshold else config.personalThreshold
        return when (phase) {
            CooldownPhase.IDLE -> tickIdle(nowMs, driver.aggression, threshold, hvac)
            CooldownPhase.STAGE1, CooldownPhase.STAGE2 -> tickIntervening(nowMs, driver.aggression, threshold)
            CooldownPhase.RESTORING -> tickRestoring(nowMs)
            CooldownPhase.LOCKED -> {
                if (nowMs - lockedAtMs >= config.lockMs) phase = CooldownPhase.IDLE
                emptyList()
            }
            CooldownPhase.DISABLED, CooldownPhase.YIELDED -> emptyList()
        }
    }

    /** F2-04·F2-10: 개입 중 운전자 조작 → 복원하지 않고 양보 */
    fun onManualChange(nowMs: Long, change: ManualChange): List<CooldownAction> {
        // 내·외기는 F2가 다루지 않는다 (F3-P6)
        if (!intervening || change == ManualChange.RECIRCULATION) return emptyList()
        val signal = when (change) {
            ManualChange.TEMP_UP -> ComfortSignal.STRONG_COLD
            ManualChange.FAN_DOWN, ManualChange.DIRECTION, ManualChange.SEAT_VENTILATION -> ComfortSignal.WEAK_COLD
            ManualChange.TEMP_DOWN, ManualChange.FAN_UP, ManualChange.RECIRCULATION -> null
        }
        return buildList {
            add(endIntervention(nowMs, InterventionOutcome.YIELDED_MANUAL))
            signal?.let { add(CooldownAction.Learn(it)) }
            phase = CooldownPhase.YIELDED
        }
    }

    /**
     * F2-11·F2-12: "추워"·"그만" → 즉시 원래 값으로 복원하고 양보.
     * 개입 중이 아니면 아무것도 하지 않고 빈 목록을 돌려준다 (F2-09, F2-T10).
     *
     * @param fromGleo Gleo 경로면 Gleo가 응답을 말하므로 앱은 말하지 않는다 (F2-16)
     */
    fun onFeedback(nowMs: Long, feedback: Feedback, fromGleo: Boolean): List<CooldownAction> {
        if (!intervening) return emptyList()
        val target = original?.toTarget() ?: return emptyList()
        val outcome = if (feedback == Feedback.COLD) InterventionOutcome.COLD_FEEDBACK else InterventionOutcome.STOPPED
        val signal = if (feedback == Feedback.COLD) ComfortSignal.STRONG_COLD else ComfortSignal.STOP
        return buildList {
            add(CooldownAction.ApplyHvac(target))
            add(endIntervention(nowMs, outcome))
            add(CooldownAction.Learn(signal))
            if (!fromGleo) add(CooldownAction.Announce(MESSAGE_RESTORED, listenForReply = false))
            phase = CooldownPhase.YIELDED
        }
    }

    /** F2-06: 공조 호출이 실패하면 양보하고 다시 시도하지 않는다 */
    fun onCommandFailed(nowMs: Long): List<CooldownAction> {
        if (!intervening) return emptyList()
        val ended = endIntervention(nowMs, InterventionOutcome.FAILED)
        phase = CooldownPhase.YIELDED
        return listOf(ended)
    }

    /** 주행 종료: 개입 중이었다면 즉시 복원 */
    fun endTrip(nowMs: Long): List<CooldownAction> {
        val actions = if (intervening) {
            listOfNotNull(
                original?.toTarget()?.let { CooldownAction.ApplyHvac(it) },
                endIntervention(nowMs, InterventionOutcome.TRIP_ENDED),
            )
        } else {
            emptyList()
        }
        phase = CooldownPhase.DISABLED
        plan = null
        return actions
    }

    private fun tickIdle(nowMs: Long, score: Int, threshold: Int, hvac: HvacSnapshot?): List<CooldownAction> {
        // F2-03: 공조 전원이 꺼져 있으면 개입하지 않는다
        if (hvac == null || !hvac.powerOn || score < threshold) {
            highSinceMs = null
            return emptyList()
        }
        val since = highSinceMs ?: nowMs.also { highSinceMs = it }
        if (heldFor(nowMs, since) < config.enterAfterMs) return emptyList()
        return enterStage1(nowMs, hvac)
    }

    private fun tickIntervening(nowMs: Long, score: Int, threshold: Int): List<CooldownAction> {
        calmSinceMs = if (score < config.calmBelow) calmSinceMs ?: nowMs else null
        val calmSince = calmSinceMs
        if (calmSince != null && heldFor(nowMs, calmSince) >= config.restoreAfterCalmMs) return startRestoring(nowMs)

        val p = plan ?: return emptyList()
        if (phase == CooldownPhase.STAGE1 && p.hasStage2 &&
            nowMs - stageEnteredMs >= config.stage2AfterMs && score >= threshold
        ) {
            return enterStage2(nowMs)
        }
        return emptyList()
    }

    private fun enterStage1(nowMs: Long, hvac: HvacSnapshot): List<CooldownAction> {
        val p = plan ?: return emptyList()
        val l = limits ?: return emptyList()
        original = hvac
        val target = HvacTarget(
            temperatureC = targetTemp(hvac.temperatureC, p.stage1DropC, p, l),
            fanSpeed = min(hvac.fanSpeed + 1, l.maxFanSpeed),
            fanDirection = if (p.faceDirection && l.supportsFaceDirection) FanDirection.FACE else hvac.fanDirection,
            seatVentilation = hvac.seatVentilation,
        )
        phase = CooldownPhase.STAGE1
        stageEnteredMs = nowMs
        maxStage = 1
        calmSinceMs = null
        current = target
        return listOf(
            CooldownAction.InterventionStarted(nowMs),
            CooldownAction.ApplyHvac(target),
            CooldownAction.Announce(MESSAGE_COOLING, listenForReply = true),
        )
    }

    private fun enterStage2(nowMs: Long): List<CooldownAction> {
        val p = plan ?: return emptyList()
        val l = limits ?: return emptyList()
        val base = original ?: return emptyList()
        val now = current ?: return emptyList()
        val seat = if (p.seatVentilation && l.maxSeatVentilation != null && base.seatVentilation == 0) {
            SEAT_VENTILATION_ON
        } else {
            now.seatVentilation
        }
        val target = now.copy(temperatureC = targetTemp(base.temperatureC, p.maxDropC, p, l), seatVentilation = seat)
        phase = CooldownPhase.STAGE2
        stageEnteredMs = nowMs
        maxStage = 2
        current = target
        return listOf(CooldownAction.ApplyHvac(target))
    }

    /** RESTORING: 1분 동안 온도 0.5℃, 풍량 1단씩 원래 값으로. 풍향·통풍시트는 마지막에 */
    private fun startRestoring(nowMs: Long): List<CooldownAction> {
        val from = current ?: return emptyList()
        val to = original?.toTarget() ?: return emptyList()
        val tempSteps = ceil(abs(to.temperatureC - from.temperatureC) / config.restoreTempStepC).toInt()
        val fanSteps = abs(to.fanSpeed - from.fanSpeed)
        val n = max(1, max(tempSteps, fanSteps))
        val interval = config.restoreDurationMs / n
        restoreSteps = (1..n).map { i ->
            val temp = approach(from.temperatureC, to.temperatureC, config.restoreTempStepC * i)
            val fan = approach(from.fanSpeed, to.fanSpeed, i)
            val target = if (i == n) to else from.copy(temperatureC = temp, fanSpeed = fan)
            nowMs + interval * i to target
        }
        phase = CooldownPhase.RESTORING
        calmSinceMs = null
        return emptyList()
    }

    private fun tickRestoring(nowMs: Long): List<CooldownAction> {
        val due = restoreSteps.takeWhile { it.first <= nowMs }
        if (due.isEmpty()) return emptyList()
        restoreSteps = restoreSteps.drop(due.size)
        val target = due.last().second
        current = target
        val actions = mutableListOf<CooldownAction>(CooldownAction.ApplyHvac(target))
        if (restoreSteps.isEmpty()) {
            actions += endIntervention(nowMs, InterventionOutcome.RESTORED)
            actions += CooldownAction.Learn(ComfortSignal.POSITIVE)
            phase = CooldownPhase.LOCKED
            lockedAtMs = nowMs
        }
        return actions
    }

    /** F2-01: max(원래 − D, 기준 온도 − D, 18℃, 차량 최솟값). 원래 값보다 올리지는 않는다 */
    private fun targetTemp(originalC: Float, dropC: Float, plan: CooldownPlan, limits: HvacLimits): Float {
        val floor = maxOf(
            plan.baselineTempC?.let { it - plan.maxDropC } ?: Float.NEGATIVE_INFINITY,
            config.absoluteMinTempC,
            limits.minTempC,
        )
        return min(originalC, max(originalC - dropC, floor))
    }

    private fun endIntervention(nowMs: Long, outcome: InterventionOutcome): CooldownAction.InterventionEnded {
        val ended = CooldownAction.InterventionEnded(nowMs, outcome, maxStage)
        resetIntervention()
        return ended
    }

    private fun resetIntervention() {
        original = null
        current = null
        maxStage = 0
        highSinceMs = null
        calmSinceMs = null
        restoreSteps = emptyList()
    }

    private fun heldFor(nowMs: Long, sinceMs: Long): Long = nowMs - sinceMs + config.tickMs

    private fun HvacSnapshot.toTarget() = HvacTarget(temperatureC, fanSpeed, fanDirection, seatVentilation)

    private fun approach(from: Float, to: Float, step: Float): Float =
        if (to > from) min(to, from + step) else max(to, from - step)

    private fun approach(from: Int, to: Int, step: Int): Int =
        if (to > from) min(to, from + step) else max(to, from - step)

    companion object {
        const val MESSAGE_COOLING = "조금 시원하게 해 드릴게요"
        const val MESSAGE_RESTORED = "원래대로 돌려 드렸어요"
        const val SEAT_VENTILATION_ON = 1
    }
}
