package com.pleos.cooldown.domain.fatigue

import com.pleos.cooldown.domain.cooldown.FanDirection
import com.pleos.cooldown.domain.cooldown.interventionDirection
import com.pleos.cooldown.domain.cooldown.HvacLimits
import com.pleos.cooldown.domain.cooldown.HvacSnapshot
import com.pleos.cooldown.domain.cooldown.HvacTarget
import com.pleos.cooldown.domain.cooldown.ManualChange
import com.pleos.cooldown.domain.model.DriverState
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class FatiguePhase { DISABLED, IDLE, HOLD_ON, AWAITING_REPLY, DECLINED, HEADING_TO_REST, LOCKED }

/** 경로 위 다음 휴게소 (내비 TBT, 명세 5.4) */
data class RestArea(val name: String, val distanceM: Double)

sealed interface FatigueAction {
    data class ApplyHvac(val target: HvacTarget) : FatigueAction
    data class SetRecirculation(val on: Boolean) : FatigueAction
    data class Announce(val message: String, val listenForReply: Boolean) : FatigueAction
    data class InterventionStarted(val atMs: Long) : FatigueAction
    data class InterventionEnded(val atMs: Long, val outcome: Outcome) : FatigueAction

    enum class Outcome { RESTORED, RESTED, YIELDED, TRIP_ENDED, FAILED }
}

/**
 * F3 ② 감지 + ③ 휴식 유도 상태 머신 (명세 5.4).
 *
 * 찬 바람(`HOLD_ON`)은 졸음을 해결하는 수단이 아니라 휴게소까지 버티게 하는 보조 수단이다 (Reyner & Horne, 1998).
 * 핵심은 휴게소 제안과, 도착했을 때 효과가 확인된 휴식(커피·짧은 낮잠)을 권하는 것이다.
 */
class FatiguePolicy(private val config: Config = Config()) {

    data class Config(
        val threshold: Int = 60,
        val immediateSuggestFrom: Int = 80,
        val calmBelow: Int = 40,
        val minContinuousDrivingMs: Long = 40 * 60_000L,
        val enterAfterMs: Long = 60_000,
        val suggestAfterHoldMs: Long = 10 * 60_000L,
        val restoreAfterCalmMs: Long = 5 * 60_000L,
        val declineLockMs: Long = 30 * 60_000L,
        val lockMs: Long = 30 * 60_000L,
        val maxSuggestionsPerTrip: Int = 3,
        val deferIfTurnWithinM: Double = 500.0,
        val entranceReminderM: Double = 1_000.0,
        val absoluteMinTempC: Float = 18f,
        val tickMs: Long = 1_000,
    )

    var phase: FatiguePhase = FatiguePhase.DISABLED
        private set

    private var limits: HvacLimits? = null
    private var original: HvacSnapshot? = null
    private var holding = false
    private var climateYielded = false

    private var highSinceMs: Long? = null
    private var veryHighSinceMs: Long? = null
    private var calmSinceMs: Long? = null
    private var holdStartedMs = 0L
    private var lockedUntilMs = 0L
    private var suggestions = 0
    private var acceptedArea: String? = null
    private var reminded = false

    /** 피로 케어가 온도·풍량을 쓰고 있는 동안 true (F3-02: 그동안 F2는 들어오지 않는다) */
    val holdingClimate: Boolean get() = holding && !climateYielded

    fun startTrip(limits: HvacLimits?) {
        this.limits = limits
        phase = FatiguePhase.IDLE
        original = null
        holding = false
        climateYielded = false
        highSinceMs = null
        veryHighSinceMs = null
        calmSinceMs = null
        suggestions = 0
        acceptedArea = null
        reminded = false
    }

    /**
     * @param cooldownActive F2가 개입 중이면 true (F3-02: F2 우선)
     * @param restArea 경로 위 다음 휴게소. 모르면 null (F3-04)
     * @param nextTurnM 다음 회전 안내까지 거리. 모르면 null (F3-05)
     */
    fun onTick(
        nowMs: Long,
        driver: DriverState,
        hvac: HvacSnapshot?,
        cooldownActive: Boolean,
        restArea: RestArea?,
        nextTurnM: Double?,
    ): List<FatigueAction> {
        val fatigue = driver.fatigue
        highSinceMs = if (fatigue >= config.threshold) highSinceMs ?: nowMs else null
        veryHighSinceMs = if (fatigue >= config.immediateSuggestFrom) veryHighSinceMs ?: nowMs else null
        calmSinceMs = if (fatigue < config.calmBelow) calmSinceMs ?: nowMs else null

        return when (phase) {
            FatiguePhase.IDLE -> tickIdle(nowMs, driver, hvac, cooldownActive, restArea, nextTurnM)
            FatiguePhase.HOLD_ON -> tickHolding(nowMs, restArea, nextTurnM, canSuggest = held(nowMs, highSinceMs) &&
                nowMs - holdStartedMs >= config.suggestAfterHoldMs)
            FatiguePhase.DECLINED -> tickHolding(nowMs, restArea, nextTurnM, canSuggest = held(nowMs, highSinceMs) &&
                nowMs >= lockedUntilMs)
            FatiguePhase.HEADING_TO_REST -> tickHeading(nowMs, restArea)
            FatiguePhase.LOCKED -> {
                if (nowMs >= lockedUntilMs) phase = FatiguePhase.IDLE
                emptyList()
            }
            FatiguePhase.AWAITING_REPLY, FatiguePhase.DISABLED -> emptyList()
        }
    }

    /** STT·Gleo 응답. null은 무응답 (F3-03: 거절로 처리) */
    fun onReply(nowMs: Long, accepted: Boolean?): List<FatigueAction> {
        if (phase != FatiguePhase.AWAITING_REPLY) return emptyList()
        if (accepted == true) {
            phase = FatiguePhase.HEADING_TO_REST
            reminded = false
            return listOf(FatigueAction.Announce(MESSAGE_ACCEPTED, listenForReply = false))
        }
        // F3-08: 거절해도 공조는 피로가 내려갈 때까지 유지한다
        phase = FatiguePhase.DECLINED
        lockedUntilMs = nowMs + config.declineLockMs
        acceptedArea = null
        return emptyList()
    }

    /** P단이 됐을 때. 휴게소로 가던 중이었으면 효과가 확인된 휴식을 권한다 (F3-09) */
    fun onParked(nowMs: Long): List<FatigueAction> {
        if (phase != FatiguePhase.HEADING_TO_REST) return emptyList()
        return buildList {
            add(FatigueAction.Announce(MESSAGE_REST_TIP, listenForReply = false))
            addAll(restore(nowMs, FatigueAction.Outcome.RESTED))
            phase = FatiguePhase.LOCKED
            lockedUntilMs = nowMs + config.lockMs
        }
    }

    /** 운전자가 공조를 직접 만지면 공조에서만 손을 뗀다. 휴게소 제안은 계속한다 */
    fun onManualChange(nowMs: Long, change: ManualChange): List<FatigueAction> {
        if (!holdingClimate) return emptyList()
        climateYielded = true
        return listOf(FatigueAction.InterventionEnded(nowMs, FatigueAction.Outcome.YIELDED))
    }

    fun onCommandFailed(nowMs: Long): List<FatigueAction> {
        if (!holdingClimate) return emptyList()
        climateYielded = true
        return listOf(FatigueAction.InterventionEnded(nowMs, FatigueAction.Outcome.FAILED))
    }

    fun endTrip(nowMs: Long): List<FatigueAction> {
        val actions = if (holding) restore(nowMs, FatigueAction.Outcome.TRIP_ENDED) else emptyList()
        phase = FatiguePhase.DISABLED
        return actions
    }

    private fun tickIdle(
        nowMs: Long,
        driver: DriverState,
        hvac: HvacSnapshot?,
        cooldownActive: Boolean,
        restArea: RestArea?,
        nextTurnM: Double?,
    ): List<FatigueAction> {
        // F3-01: 짧은 주행은 오탐을 막으려고 개입하지 않는다. F3-02: F2 우선
        if (driver.continuousDrivingMs < config.minContinuousDrivingMs || cooldownActive) return emptyList()
        if (!held(nowMs, highSinceMs)) return emptyList()
        val actions = mutableListOf<FatigueAction>()
        actions += startHolding(nowMs, hvac)
        // F3-07: 매우 졸리면 찬 바람 단계를 기다리지 않고 바로 제안한다
        if (held(nowMs, veryHighSinceMs)) actions += suggest(nowMs, restArea, nextTurnM)
        return actions
    }

    private fun tickHolding(nowMs: Long, restArea: RestArea?, nextTurnM: Double?, canSuggest: Boolean): List<FatigueAction> {
        if (held(nowMs, calmSinceMs, config.restoreAfterCalmMs)) {
            val actions = restore(nowMs, FatigueAction.Outcome.RESTORED)
            phase = FatiguePhase.LOCKED
            lockedUntilMs = nowMs + config.lockMs
            return actions
        }
        return if (canSuggest) suggest(nowMs, restArea, nextTurnM) else emptyList()
    }

    private fun tickHeading(nowMs: Long, restArea: RestArea?): List<FatigueAction> {
        val target = acceptedArea
        // 수락한 휴게소를 지나쳐 다음 휴게소가 보이면 거절한 것으로 본다. TBT가 잠깐 비는 것은 무시한다
        if (target != null && restArea != null && restArea.name != target) {
            phase = FatiguePhase.DECLINED
            lockedUntilMs = nowMs + config.declineLockMs
            acceptedArea = null
            return emptyList()
        }
        if (!reminded && restArea != null && restArea.distanceM <= config.entranceReminderM) {
            reminded = true
            return listOf(FatigueAction.Announce(MESSAGE_ENTRANCE, listenForReply = false))
        }
        return emptyList()
    }

    /** `HOLD_ON`: 외기 유입, 얼굴 쪽 바람, 설정 온도 −1℃ (F2와 같은 하한·운전자 우선) */
    private fun startHolding(nowMs: Long, hvac: HvacSnapshot?): List<FatigueAction> {
        phase = FatiguePhase.HOLD_ON
        holdStartedMs = nowMs
        val l = limits
        if (hvac == null || !hvac.powerOn || l == null) return emptyList()
        original = hvac
        holding = true
        climateYielded = false
        val target = HvacTarget(
            temperatureC = min(hvac.temperatureC, max(hvac.temperatureC - 1f, maxOf(config.absoluteMinTempC, l.minTempC))),
            fanSpeed = min(hvac.fanSpeed + 1, l.maxFanSpeed),
            fanDirection = interventionDirection(hvac.fanDirection, FanDirection.FACE.takeIf { l.supportsFaceDirection }),
            seatVentilation = hvac.seatVentilation,
        )
        return buildList {
            add(FatigueAction.InterventionStarted(nowMs))
            add(FatigueAction.ApplyHvac(target))
            if (hvac.recirculation == true) add(FatigueAction.SetRecirculation(on = false))
        }
    }

    private fun suggest(nowMs: Long, restArea: RestArea?, nextTurnM: Double?): List<FatigueAction> {
        if (suggestions >= config.maxSuggestionsPerTrip) return emptyList()
        // F3-05: 내비 안내 음성과 겹치지 않게 미룬다
        if (nextTurnM != null && nextTurnM < config.deferIfTurnWithinM) return emptyList()
        suggestions++
        if (restArea == null) {
            // F3-04: 휴게소를 모르면 권하기만 하고 응답은 받지 않는다
            phase = FatiguePhase.DECLINED
            lockedUntilMs = nowMs + config.declineLockMs
            return listOf(FatigueAction.Announce(MESSAGE_SUGGEST_UNKNOWN, listenForReply = false))
        }
        phase = FatiguePhase.AWAITING_REPLY
        acceptedArea = restArea.name
        return listOf(FatigueAction.Announce(suggestMessage(restArea), listenForReply = true))
    }

    private fun restore(nowMs: Long, outcome: FatigueAction.Outcome): List<FatigueAction> {
        val base = original
        val actions = mutableListOf<FatigueAction>()
        if (holding && !climateYielded && base != null) {
            actions += FatigueAction.ApplyHvac(HvacTarget(base.temperatureC, base.fanSpeed, base.fanDirection, base.seatVentilation))
            base.recirculation?.let { actions += FatigueAction.SetRecirculation(it) }
            actions += FatigueAction.InterventionEnded(nowMs, outcome)
        }
        holding = false
        climateYielded = false
        original = null
        return actions
    }

    private fun held(nowMs: Long, sinceMs: Long?, durationMs: Long = config.enterAfterMs): Boolean =
        sinceMs != null && nowMs - sinceMs + config.tickMs >= durationMs

    companion object {
        const val MESSAGE_SUGGEST_UNKNOWN = "다음 휴게소에서 잠깐 쉬어 가세요"
        const val MESSAGE_ACCEPTED = "좋아요, 입구 전에 알려 드릴게요"
        const val MESSAGE_ENTRANCE = "곧 휴게소 입구예요"
        const val MESSAGE_REST_TIP = "커피 한 잔이나 15분 낮잠이 졸음에 가장 잘 들어요"

        /** SAFE-02: 한 문장. 거리는 km 단위로 반올림한다 */
        fun suggestMessage(area: RestArea): String {
            val km = max(1, (area.distanceM / 1_000).roundToInt())
            val place = area.name.ifBlank { "휴게소" }
            return "${km}km 앞 ${place}에서 잠깐 쉬어 갈까요?"
        }
    }
}
