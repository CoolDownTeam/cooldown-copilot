package com.pleos.cooldown.domain.model

/**
 * 속도대 (명세 0.3). 기준선과 특징 창은 속도대별로 따로 둔다.
 */
enum class SpeedBand {
    URBAN, RURAL, HIGHWAY;

    companion object {
        fun of(speedKmh: Float): SpeedBand = when {
            speedKmh < 60f -> URBAN
            speedKmh < 90f -> RURAL
            else -> HIGHWAY
        }
    }
}

/**
 * 기준선과 비교하는 특징 (C0-02~07). 연속 주행 시간(C0-08)은 기준선 비교 대상이 아니라서 따로 둔다.
 */
enum class FeatureId {
    HARD_ACCEL_PER_MIN,
    HARD_DECEL_PER_MIN,
    ACCEL_BRAKE_FLIP_PER_MIN,
    STEER_RATE_STD,
    STEER_MICRO_CORRECTION_PER_MIN,
    STEER_HOLD_RATIO,
}

/**
 * 1초마다 내보내는 최근 3분 창의 특징.
 *
 * @param values 계산할 표본이 없는 특징은 빠진다 (예: 5 km/h 미만에서만 달린 창의 조향 특징)
 */
data class DrivingFeatures(
    val timestampMs: Long,
    val speedBand: SpeedBand,
    val windowMs: Long,
    val accelerationMs2: Double,
    val continuousDrivingMs: Long,
    val values: Map<FeatureId, Double>,
)
