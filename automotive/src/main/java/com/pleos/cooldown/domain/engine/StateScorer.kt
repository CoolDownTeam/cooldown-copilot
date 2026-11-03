package com.pleos.cooldown.domain.engine

import com.pleos.cooldown.domain.model.Baseline
import com.pleos.cooldown.domain.model.DriverState
import com.pleos.cooldown.domain.model.DrivingFeatures
import com.pleos.cooldown.domain.model.FeatureId
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * C0 상태 점수 (명세 3.3). 특징이 기준선에서 벗어난 정도(z)를 가중합해 0~100으로 바꾼다.
 *
 * @param hourOfDay 시각(ms) → 0~23시. 야간 가중치에 쓴다
 */
class StateScorer(
    private val hourOfDay: (Long) -> Int,
    private val config: Config = Config(),
) {

    data class Config(
        val aggressionWeights: Map<FeatureId, Double> = mapOf(
            FeatureId.HARD_ACCEL_PER_MIN to 0.30,
            FeatureId.HARD_DECEL_PER_MIN to 0.30,
            FeatureId.ACCEL_BRAKE_FLIP_PER_MIN to 0.25,
            FeatureId.STEER_RATE_STD to 0.15,
        ),
        val microCorrectionDropWeight: Double = 0.35,
        val steerHoldWeight: Double = 0.25,
        val continuousDrivingWeight: Double = 0.30,
        val nightWeight: Double = 0.10,
        val continuousDrivingFullMs: Long = 120 * 60_000L,
        val continuousDrivingCap: Double = 1.5,
        val nightStartHour: Int = 22,
        val nightEndHour: Int = 6,
        val aggressionDivisor: Double = 2.0,
        /**
         * 명세 3.3은 피로에도 /2를 쓰지만, 피로 특징은 비율이라 z가 2 안팎에서 막혀
         * 고속도로·야간·3시간 연속에 가장 졸린 조향이어도 58점에 그친다 (F3 기준 60에 못 미침).
         * 그래서 피로는 /1로 둔다. 평범한 조향 2시간 26점, 졸린 조향 2시간 약 70점
         */
        val fatigueDivisor: Double = 1.0,
    )

    /** 학습 모드면 내장 기본값과 비교한다 (C0-10) */
    fun score(features: DrivingFeatures, baseline: Baseline): DriverState {
        val learning = baseline.learning
        val reference = if (learning) Baseline.DEFAULT else baseline

        val aggressionSum = config.aggressionWeights.entries.sumOf { (id, w) ->
            w * zAbove(features, reference, id)
        }

        // 졸리면 미세 보정이 줄고(아래쪽 편차), 핸들을 오래 가만히 둔다(위쪽 편차)
        val fatigueSum = config.microCorrectionDropWeight * zBelow(features, reference, FeatureId.STEER_MICRO_CORRECTION_PER_MIN) +
            config.steerHoldWeight * zAbove(features, reference, FeatureId.STEER_HOLD_RATIO) +
            config.continuousDrivingWeight * continuousDrivingTerm(features.continuousDrivingMs) +
            config.nightWeight * if (isNight(features.timestampMs)) 1.0 else 0.0

        return DriverState(
            timestampMs = features.timestampMs,
            aggression = toScore(aggressionSum, config.aggressionDivisor),
            fatigue = toScore(fatigueSum, config.fatigueDivisor),
            speedBand = features.speedBand,
            learning = learning,
        )
    }

    private fun zAbove(features: DrivingFeatures, baseline: Baseline, id: FeatureId): Double =
        deviation(features, baseline, id)?.coerceAtLeast(0.0) ?: 0.0

    private fun zBelow(features: DrivingFeatures, baseline: Baseline, id: FeatureId): Double =
        deviation(features, baseline, id)?.let { (-it).coerceAtLeast(0.0) } ?: 0.0

    /** 특징이나 기준선이 없으면 null (점수에 반영하지 않는다) */
    private fun deviation(features: DrivingFeatures, baseline: Baseline, id: FeatureId): Double? {
        val x = features.values[id] ?: return null
        val stat = baseline.stat(features.speedBand, id) ?: return null
        val minStd = Baseline.MIN_STD[id] ?: 0.0
        return (x - stat.mean) / max(stat.std, minStd)
    }

    private fun continuousDrivingTerm(continuousMs: Long): Double =
        (continuousMs.toDouble() / config.continuousDrivingFullMs).coerceAtMost(config.continuousDrivingCap)

    private fun isNight(timeMs: Long): Boolean {
        val hour = hourOfDay(timeMs)
        return hour >= config.nightStartHour || hour < config.nightEndHour
    }

    /** score = 100 × (1 − e^(−Σ w·z / divisor)) */
    private fun toScore(weightedSum: Double, divisor: Double): Int =
        (100 * (1 - exp(-weightedSum / divisor))).roundToInt().coerceIn(0, 100)
}
