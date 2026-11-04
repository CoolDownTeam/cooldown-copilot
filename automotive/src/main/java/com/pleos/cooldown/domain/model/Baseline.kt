package com.pleos.cooldown.domain.model

import kotlin.math.sqrt

/**
 * 특징 하나의 개인 분포 (명세 3.2). 주행이 끝날 때마다 지수이동평균으로 갱신한다.
 *
 * @param samples 이 값에 반영된 주행 수. 0이면 내장 기본값 그대로다
 */
data class FeatureStat(val mean: Double, val variance: Double, val samples: Int) {
    val std: Double get() = sqrt(variance)
}

/**
 * 운전자 한 명의 기준선. 속도대마다 특징별 분포를 가진다.
 */
data class Baseline(
    val bySpeedBand: Map<SpeedBand, Map<FeatureId, FeatureStat>>,
    val totalTrips: Int,
    val totalDrivingMinutes: Double,
) {
    /** C0-10: 주행 5회 미만 또는 누적 60분 미만 */
    val learning: Boolean
        get() = totalTrips < LEARNING_TRIPS || totalDrivingMinutes < LEARNING_MINUTES

    fun stat(band: SpeedBand, id: FeatureId): FeatureStat? = bySpeedBand[band]?.get(id)

    companion object {
        const val LEARNING_TRIPS = 5
        const val LEARNING_MINUTES = 60.0

        /**
         * 학습 모드에서 쓰는 내장 기본값. 일반적인 운전을 가정한 초기값이며
         * 에뮬레이터 모의 주행으로 조정한다 (명세 머리말).
         */
        val DEFAULT: Baseline = Baseline(
            bySpeedBand = mapOf(
                SpeedBand.URBAN to defaults(
                    hardAccel = 0.3 to 0.4, hardDecel = 0.3 to 0.4, flip = 1.0 to 0.8,
                    steerStd = 8.0 to 4.0, micro = 6.0 to 3.0, hold = 0.60 to 0.15,
                ),
                SpeedBand.RURAL to defaults(
                    hardAccel = 0.15 to 0.3, hardDecel = 0.15 to 0.3, flip = 0.4 to 0.5,
                    steerStd = 4.0 to 2.0, micro = 5.0 to 2.5, hold = 0.70 to 0.12,
                ),
                SpeedBand.HIGHWAY to defaults(
                    hardAccel = 0.1 to 0.2, hardDecel = 0.1 to 0.2, flip = 0.2 to 0.3,
                    steerStd = 2.5 to 1.5, micro = 4.0 to 2.0, hold = 0.80 to 0.10,
                ),
            ),
            totalTrips = 0,
            totalDrivingMinutes = 0.0,
        )

        /** C0-13: 표준편차 하한. 너무 작은 표준편차로 점수가 튀는 것을 막는다 */
        val MIN_STD: Map<FeatureId, Double> = mapOf(
            FeatureId.HARD_ACCEL_PER_MIN to 0.2,
            FeatureId.HARD_DECEL_PER_MIN to 0.2,
            FeatureId.ACCEL_BRAKE_FLIP_PER_MIN to 0.3,
            FeatureId.STEER_RATE_STD to 1.0,
            FeatureId.STEER_MICRO_CORRECTION_PER_MIN to 1.0,
            FeatureId.STEER_HOLD_RATIO to 0.05,
        )

        private fun defaults(
            hardAccel: Pair<Double, Double>,
            hardDecel: Pair<Double, Double>,
            flip: Pair<Double, Double>,
            steerStd: Pair<Double, Double>,
            micro: Pair<Double, Double>,
            hold: Pair<Double, Double>,
        ): Map<FeatureId, FeatureStat> = mapOf(
            FeatureId.HARD_ACCEL_PER_MIN to hardAccel.toStat(),
            FeatureId.HARD_DECEL_PER_MIN to hardDecel.toStat(),
            FeatureId.ACCEL_BRAKE_FLIP_PER_MIN to flip.toStat(),
            FeatureId.STEER_RATE_STD to steerStd.toStat(),
            FeatureId.STEER_MICRO_CORRECTION_PER_MIN to micro.toStat(),
            FeatureId.STEER_HOLD_RATIO to hold.toStat(),
        )

        private fun Pair<Double, Double>.toStat() = FeatureStat(mean = first, variance = second * second, samples = 0)
    }
}

/**
 * 1초마다 내보내는 운전자 상태 (명세 3.3)
 */
data class DriverState(
    val timestampMs: Long,
    val aggression: Int,
    val fatigue: Int,
    val speedBand: SpeedBand,
    val learning: Boolean,
    /** C0-08. F3-01 판정에 쓴다 */
    val continuousDrivingMs: Long = 0,
) {
    companion object {
        val IDLE = DriverState(timestampMs = 0L, aggression = 0, fatigue = 0, speedBand = SpeedBand.URBAN, learning = true)
    }
}
