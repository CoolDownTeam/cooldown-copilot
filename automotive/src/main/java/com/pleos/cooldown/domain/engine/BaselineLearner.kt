package com.pleos.cooldown.domain.engine

import com.pleos.cooldown.domain.model.Baseline
import com.pleos.cooldown.domain.model.DrivingFeatures
import com.pleos.cooldown.domain.model.FeatureId
import com.pleos.cooldown.domain.model.FeatureStat
import com.pleos.cooldown.domain.model.SpeedBand

/**
 * C0 기준선 학습 (명세 3.2).
 *
 * 주행 중에는 1초마다 특징을 기록하고, 주행이 끝나면 속도대별 평균·분산을
 * 지수이동평균으로 기준선에 반영한다. F2·F3가 개입한 구간과 그 앞 3분은 쓰지 않는다 (C0-12).
 * 기록은 메모리에만 두고 저장하지 않는다 (SAFE-05).
 */
class BaselineLearner(private val config: Config = Config()) {

    data class Config(
        val alpha: Double = 0.2,
        val excludeBeforeInterventionMs: Long = 180_000,
        /** 한 속도대에서 이보다 짧게 달렸으면 그 속도대는 갱신하지 않는다 */
        val minSnapshotsPerBand: Int = 60,
    )

    private val snapshots = ArrayList<DrivingFeatures>()
    private var intervening = false
    private var tripStartMs: Long? = null

    fun record(features: DrivingFeatures) {
        if (tripStartMs == null) tripStartMs = features.timestampMs
        if (!intervening) snapshots.add(features)
    }

    /** C0-12: 개입이 시작되면 그 앞 3분 기록을 지우고, 끝날 때까지 기록하지 않는다 */
    fun setIntervening(active: Boolean, nowMs: Long) {
        if (active && !intervening) {
            val cutoff = nowMs - config.excludeBeforeInterventionMs
            snapshots.removeAll { it.timestampMs >= cutoff }
        }
        intervening = active
    }

    /** 주행을 끝내고 새 기준선을 돌려준다. 기록은 비운다 */
    fun finishTrip(baseline: Baseline, endMs: Long): Baseline {
        val drivingMinutes = tripStartMs?.let { (endMs - it) / 60_000.0 } ?: 0.0
        val updated = baseline.bySpeedBand.mapValues { (band, stats) -> updateBand(band, stats) }
        snapshots.clear()
        intervening = false
        tripStartMs = null
        return baseline.copy(
            bySpeedBand = updated,
            totalTrips = baseline.totalTrips + 1,
            totalDrivingMinutes = baseline.totalDrivingMinutes + drivingMinutes,
        )
    }

    private fun updateBand(band: SpeedBand, stats: Map<FeatureId, FeatureStat>): Map<FeatureId, FeatureStat> {
        val inBand = snapshots.filter { it.speedBand == band }
        if (inBand.size < config.minSnapshotsPerBand) return stats
        return stats.mapValues { (id, stat) ->
            val values = inBand.mapNotNull { it.values[id] }
            if (values.size < config.minSnapshotsPerBand) stat else ewma(stat, values)
        }
    }

    /** C0-11: mean ← (1−α)·mean + α·x, 분산도 같은 방식 (이번 주행 창들의 분산을 반영) */
    private fun ewma(stat: FeatureStat, values: List<Double>): FeatureStat {
        val tripMean = values.average()
        val tripVariance = values.sumOf { (it - tripMean) * (it - tripMean) } / values.size
        val a = config.alpha
        return FeatureStat(
            mean = (1 - a) * stat.mean + a * tripMean,
            variance = (1 - a) * stat.variance + a * tripVariance,
            samples = stat.samples + 1,
        )
    }
}
