package com.pleos.cooldown.domain.model

/**
 * 한 시점의 차량 신호 묶음 (D0)
 */
data class VehicleSignal(
    val timestampMs: Long,
    val speedKmh: Float,
    val gear: Gear,
    val brakePressed: Boolean,
    val steeringAngleDeg: Float,
    val odometerKm: Float?
) {
    companion object {
        val INITIAL = VehicleSignal(
            timestampMs = 0L,
            speedKmh = 0f,
            gear = Gear.UNKNOWN,
            brakePressed = false,
            steeringAngleDeg = 0f,
            odometerKm = null
        )
    }
}

enum class Gear { P, R, N, D, UNKNOWN }

enum class DrivingState { PARKED, DRIVING }

/**
 * 개별 신호 종류. 리스너가 실패하면 해당 신호만 사용 불가로 표시한다.
 */
enum class SignalType { SPEED, GEAR, BRAKE, STEERING, ODOMETER }
