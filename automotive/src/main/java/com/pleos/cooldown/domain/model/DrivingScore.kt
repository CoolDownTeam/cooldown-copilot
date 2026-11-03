package com.pleos.cooldown.domain.model

import java.time.LocalDateTime

/**
 * 운전 점수 데이터
 */
data class DrivingScore(
    val currentScore: Int = 100,
    val totalScore: Int = 100,
    val events: List<DrivingEvent> = emptyList(),
    val startTime: LocalDateTime = LocalDateTime.now(),
    val endTime: LocalDateTime? = null
) {
    val grade: ScoreGrade
        get() = when {
            currentScore >= 90 -> ScoreGrade.EXCELLENT
            currentScore >= 70 -> ScoreGrade.GOOD
            currentScore >= 50 -> ScoreGrade.AVERAGE
            currentScore >= 30 -> ScoreGrade.POOR
            else -> ScoreGrade.BAD
        }

    val isGameOver: Boolean
        get() = currentScore <= 0
}

/**
 * 운전 이벤트 기록
 */
data class DrivingEvent(
    val violation: TrafficViolation,
    val timestamp: LocalDateTime = LocalDateTime.now(),
    val speedAtEvent: Float? = null,
    val locationDescription: String? = null
)

/**
 * 점수 등급
 */
enum class ScoreGrade(val label: String, val emoji: String) {
    EXCELLENT("우수", "S"),
    GOOD("양호", "A"),
    AVERAGE("보통", "B"),
    POOR("주의", "C"),
    BAD("위험", "D")
}
