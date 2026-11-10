package com.pleos.cooldown.domain.cooldown

import kotlin.math.abs

enum class FanDirection { FACE, FLOOR, DEFROST }

/**
 * 개입 중 풍향. 운전자가 앞유리 김서림 제거(DEFROST)를 켜 두었거나 지금 풍향을 알 수 없으면
 * 시야가 우선이라 바꾸지 않는다 (SAFE-03). [wanted]가 null이면 지금 풍향을 그대로 둔다.
 */
fun interventionDirection(current: FanDirection?, wanted: FanDirection?): FanDirection? =
    if (current == null || current == FanDirection.DEFROST) current else wanted ?: current

/** 운전석 공조의 현재 값. 차량이 지원하지 않는 항목은 null */
data class HvacSnapshot(
    val temperatureC: Float,
    val fanSpeed: Int,
    val powerOn: Boolean,
    val fanDirection: FanDirection? = null,
    val seatVentilation: Int? = null,
    /** 내기순환 켜짐. 지원하지 않으면 null */
    val recirculation: Boolean? = null,
    /** 자동 내기순환 켜짐 (F3-P7). 지원하지 않으면 null */
    val autoRecirculation: Boolean? = null,
)

/** 앱이 맞추려는 값. null 항목은 건드리지 않는다 */
data class HvacTarget(
    val temperatureC: Float,
    val fanSpeed: Int,
    val fanDirection: FanDirection? = null,
    val seatVentilation: Int? = null,
)

/** SAFE-04: 차량이 지원하는 범위 (`checkXXXCapabilities`) */
data class HvacLimits(
    val minTempC: Float,
    val maxTempC: Float,
    val maxFanSpeed: Int,
    val supportsFaceDirection: Boolean,
    val maxSeatVentilation: Int?,
)

/** 운전자가 직접 바꾼 공조 (F2-04, F2-10) */
enum class ManualChange { TEMP_UP, TEMP_DOWN, FAN_UP, FAN_DOWN, DIRECTION, SEAT_VENTILATION, RECIRCULATION }

/**
 * F2-04: 공조 값 변경 콜백이 앱의 변경인지 운전자의 조작인지 가린다.
 * 앱이 값을 바꾼 직후 [echoWindowMs] 동안 그 값으로 들어오는 콜백은 앱 자신의 변경으로 본다.
 */
class ManualChangeDetector(private val echoWindowMs: Long = 2_000) {

    private var expected: HvacTarget? = null
    private var expectedAtMs = Long.MIN_VALUE
    private var lastTemp: Float? = null
    private var lastFan: Int? = null
    private var lastDirection: FanDirection? = null
    private var lastSeat: Int? = null
    private var lastRecirculation: Boolean? = null
    private var expectedRecirculation: Boolean? = null
    private var expectedRecirculationAtMs = Long.MIN_VALUE

    fun onAppSetRecirculation(nowMs: Long, on: Boolean) {
        expectedRecirculation = on
        expectedRecirculationAtMs = nowMs
    }

    fun onRecirculation(nowMs: Long, on: Boolean): ManualChange? {
        val previous = lastRecirculation
        lastRecirculation = on
        if (previous == null || on == previous) return null
        if (nowMs - expectedRecirculationAtMs <= echoWindowMs && expectedRecirculation == on) return null
        return ManualChange.RECIRCULATION
    }

    fun onAppSet(nowMs: Long, target: HvacTarget) {
        expected = target
        expectedAtMs = nowMs
    }

    fun onTemperature(nowMs: Long, valueC: Float): ManualChange? {
        val previous = lastTemp
        lastTemp = valueC
        if (previous == null || abs(valueC - previous) < TEMP_EPSILON) return null
        if (isEcho(nowMs) { abs(it.temperatureC - valueC) < TEMP_EPSILON }) return null
        return if (valueC > previous) ManualChange.TEMP_UP else ManualChange.TEMP_DOWN
    }

    fun onFanSpeed(nowMs: Long, value: Int): ManualChange? {
        val previous = lastFan
        lastFan = value
        if (previous == null || value == previous) return null
        if (isEcho(nowMs) { it.fanSpeed == value }) return null
        return if (value > previous) ManualChange.FAN_UP else ManualChange.FAN_DOWN
    }

    fun onFanDirection(nowMs: Long, value: FanDirection): ManualChange? {
        val previous = lastDirection
        lastDirection = value
        if (previous == null || value == previous) return null
        if (isEcho(nowMs) { it.fanDirection == value }) return null
        return ManualChange.DIRECTION
    }

    /**
     * 풍향을 읽지 못했다. SDK가 모르는 값(예: VHAL의 DEFROST 4, 김서림+바닥 6)이면 결과가 오지 않는다.
     * 알던 풍향이 갑자기 읽히지 않으면 운전자가 바꾼 것으로 보고 양보한다 (안전한 쪽).
     */
    fun onFanDirectionUnknown(): ManualChange? {
        val previous = lastDirection
        lastDirection = null
        return if (previous != null) ManualChange.DIRECTION else null
    }

    fun onSeatVentilation(nowMs: Long, value: Int): ManualChange? {
        val previous = lastSeat
        lastSeat = value
        if (previous == null || value == previous) return null
        if (isEcho(nowMs) { it.seatVentilation == value }) return null
        return ManualChange.SEAT_VENTILATION
    }

    private inline fun isEcho(nowMs: Long, matches: (HvacTarget) -> Boolean): Boolean {
        val target = expected ?: return false
        return nowMs - expectedAtMs <= echoWindowMs && matches(target)
    }

    private companion object {
        const val TEMP_EPSILON = 0.05f
    }
}
