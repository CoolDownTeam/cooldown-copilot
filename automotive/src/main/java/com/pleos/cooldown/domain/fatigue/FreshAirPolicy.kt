package com.pleos.cooldown.domain.fatigue

import com.pleos.cooldown.domain.cooldown.HvacSnapshot

/** F3 예방의 할 일 */
sealed interface FreshAirAction {
    data class SetRecirculation(val on: Boolean) : FreshAirAction
    data class Started(val atMs: Long) : FreshAirAction
    data class Ended(val atMs: Long, val yielded: Boolean) : FreshAirAction
}

/**
 * F3 ① 예방 — 실내 공기 관리 (명세 5.3, F3-P1~P7).
 *
 * 내기순환이 탑승 인원에 비해 오래 이어지면 외기를 잠깐 들였다가 되돌린다. 차량에 CO2 센서 API가 없으므로
 * 농도를 재지 않고 탑승 인원·연속 시간으로 추정한다 (Hudda & Fruin, 2018). 무음이다.
 */
class FreshAirPolicy(private val config: Config = Config()) {

    data class Config(
        val recirculationLimitFewMs: Long = 30 * 60_000L,
        val recirculationLimitManyMs: Long = 15 * 60_000L,
        val manyOccupantsFrom: Int = 3,
        val freshAirDurationMs: Long = 5 * 60_000L,
        val tickMs: Long = 1_000,
    )

    private var occupants = 1
    private var recirculationSinceMs: Long? = null
    private var freshUntilMs: Long? = null
    private var yielded = false

    /** 지금 앱이 외기로 바꿔 둔 상태인지 */
    val active: Boolean get() = freshUntilMs != null

    /**
     * 운전자가 둔 공조 상태. 앱이 외기로 바꿔 둔 동안에는 내기순환이 켜져 있던 것으로 돌려준다
     * (외기 전환은 내기순환일 때만 시작한다). 피로 케어가 이걸 "원래 값"으로 저장해야,
     * 외기 전환 중에 `HOLD_ON`이 시작돼도 끝날 때 내기순환으로 되돌린다.
     */
    fun driverSet(hvac: HvacSnapshot?): HvacSnapshot? = if (active) hvac?.copy(recirculation = true) else hvac

    /** F3-P2: 탑승 인원을 모르면 운전자 1명으로 본다 */
    fun startTrip(occupantCount: Int?) {
        occupants = (occupantCount ?: 1).coerceAtLeast(1)
        recirculationSinceMs = null
        freshUntilMs = null
        yielded = false
    }

    /**
     * @param suspended 피로 케어 `HOLD_ON`이 공조를 쓰는 동안 true. 그동안은 내기순환으로 되돌리지 않는다
     */
    fun onTick(nowMs: Long, hvac: HvacSnapshot?, suspended: Boolean): List<FreshAirAction> {
        val until = freshUntilMs
        if (until != null) {
            if (suspended) {
                // HOLD_ON이 외기를 이어받는다. 끝나면 HOLD_ON이 원래 값으로 되돌린다
                freshUntilMs = null
                recirculationSinceMs = null
                return listOf(FreshAirAction.Ended(nowMs, yielded = false))
            }
            if (nowMs < until) return emptyList()
            freshUntilMs = null
            // 다음 틱부터 내기순환 시간을 다시 센다 (주행 시작 때와 같은 기준)
            recirculationSinceMs = null
            return listOf(FreshAirAction.SetRecirculation(on = true), FreshAirAction.Ended(nowMs, yielded = false))
        }

        // F3-P5·P7: 양보했거나, 전원이 꺼졌거나, 자동 내기순환이면 차량 로직에 맡긴다
        if (yielded || suspended || hvac == null || !hvac.powerOn || hvac.autoRecirculation == true || hvac.recirculation != true) {
            recirculationSinceMs = null
            return emptyList()
        }
        val since = recirculationSinceMs ?: nowMs.also { recirculationSinceMs = it }
        if (nowMs - since + config.tickMs < limitMs()) return emptyList()

        freshUntilMs = nowMs + config.freshAirDurationMs
        recirculationSinceMs = null
        return listOf(FreshAirAction.Started(nowMs), FreshAirAction.SetRecirculation(on = false))
    }

    /** F3-P5: 앱이 외기로 바꾼 뒤 운전자가 다시 내기순환으로 바꾸면 이번 주행 동안 멈춘다 */
    fun onManualRecirculation(nowMs: Long, on: Boolean): List<FreshAirAction> {
        if (freshUntilMs == null || !on) {
            recirculationSinceMs = if (on) nowMs else null
            return emptyList()
        }
        freshUntilMs = null
        yielded = true
        return listOf(FreshAirAction.Ended(nowMs, yielded = true))
    }

    /** 주행이 끝날 때 외기로 바꿔 둔 상태면 원래대로 */
    fun endTrip(nowMs: Long): List<FreshAirAction> {
        if (freshUntilMs == null) return emptyList()
        freshUntilMs = null
        return listOf(FreshAirAction.SetRecirculation(on = true), FreshAirAction.Ended(nowMs, yielded = false))
    }

    private fun limitMs(): Long =
        if (occupants >= config.manyOccupantsFrom) config.recirculationLimitManyMs else config.recirculationLimitFewMs
}
