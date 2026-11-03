package com.pleos.cooldown.domain.model

import java.time.LocalDateTime
import java.time.Duration

/**
 * 주행 데이터
 */
data class TripData(
    val id: Long = 0,
    val startTime: LocalDateTime,
    val endTime: LocalDateTime,
    val finalScore: Int,
    val grade: ScoreGrade,
    val totalDistance: Float = 0f,  // km
    val events: List<DrivingEvent> = emptyList(),
    val violations: Int = 0,
    val positiveActions: Int = 0
) {
    val duration: Duration
        get() = Duration.between(startTime, endTime)

    val durationMinutes: Long
        get() = duration.toMinutes()
}

/**
 * 차량 상태 데이터 (Pleos SDK에서 수신)
 */
data class VehicleState(
    val speed: Float = 0f,           // km/h
    val gearPosition: GearPosition = GearPosition.P,
    val steeringAngle: Float = 0f,   // degrees
    val isEngineOn: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 기어 위치
 */
enum class GearPosition {
    P,  // Park
    R,  // Reverse
    N,  // Neutral
    D,  // Drive
    L,  // Low
    UNKNOWN
}

/**
 * 내비게이션 정보 (Pleos NaviHelper SDK에서 수신)
 */
data class NavigationInfo(
    val currentSpeedLimit: Int? = null,     // km/h
    val isAccidentZone: Boolean = false,
    val distanceToDestination: Float? = null,  // km
    val estimatedArrivalTime: LocalDateTime? = null
)
