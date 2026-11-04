package com.pleos.cooldown.domain.cooldown

import kotlin.math.min

/**
 * 계절 구간 (명세 4.7.1). 주행 시작 때 한 번 정하고 그 주행 동안 바꾸지 않는다.
 */
enum class SeasonBand(val defaultLevel: Int) {
    SUMMER(3), MILD(2), WINTER(1);

    companion object {
        const val SUMMER_FROM_C = 25f
        const val WINTER_BELOW_C = 10f

        /** F2-19: 외기온을 못 읽으면 달(1~12)로 정한다 */
        fun of(outsideTempC: Float?, month: Int): SeasonBand = when {
            outsideTempC != null && outsideTempC >= SUMMER_FROM_C -> SUMMER
            outsideTempC != null && outsideTempC < WINTER_BELOW_C -> WINTER
            outsideTempC != null -> MILD
            month in 6..9 -> SUMMER
            month == 12 || month in 1..2 -> WINTER
            else -> MILD
        }
    }
}

/** 운전자 반응을 학습 신호로 바꾼 것 (F2-20~22) */
enum class ComfortSignal { STRONG_COLD, WEAK_COLD, POSITIVE, STOP }

data class BandComfort(
    val level: Int,
    val baselineTempC: Float?,
    val weakSignals: Int,
    val positiveStreak: Int,
)

/**
 * 운전자 한 명(프로필)의 계절 구간별 강도와 기준 온도.
 */
data class ComfortProfile(val bands: Map<SeasonBand, BandComfort>) {

    fun band(band: SeasonBand): BandComfort = bands[band] ?: defaultFor(band)

    companion object {
        const val MAX_LEVEL = 3

        val DEFAULT = ComfortProfile(SeasonBand.entries.associateWith(::defaultFor))

        private fun defaultFor(band: SeasonBand) =
            BandComfort(level = band.defaultLevel, baselineTempC = null, weakSignals = 0, positiveStreak = 0)
    }
}

/**
 * 계절 구간·강도로 정한 이번 주행의 개입 방식 (4.7.1 강도 단계 표)
 *
 * @param maxDropC D. 원래 값 대비 최대 하강폭
 */
data class CooldownPlan(
    val band: SeasonBand,
    val level: Int,
    val maxDropC: Float,
    val baselineTempC: Float?,
) {
    val stage1DropC: Float get() = min(1f, maxDropC)
    val faceDirection: Boolean get() = band == SeasonBand.WINTER
    val seatVentilation: Boolean get() = band == SeasonBand.SUMMER && level == ComfortProfile.MAX_LEVEL

    /** STAGE2에서 더 할 일이 있는지 */
    val hasStage2: Boolean get() = maxDropC > stage1DropC || seatVentilation
}

/**
 * 계절·운전자별 강도 학습 (명세 4.7.1, F2-20~23). 순수 함수만 둔다.
 */
object ComfortLearner {

    const val BASELINE_ALPHA = 0.2f
    const val WEAK_SIGNALS_PER_STEP = 2
    const val POSITIVE_STREAK_PER_STEP = 3

    /** L0이면 null (그 구간에서는 개입하지 않는다) */
    fun plan(profile: ComfortProfile, band: SeasonBand): CooldownPlan? {
        val comfort = profile.band(band)
        val drop = when (comfort.level) {
            3 -> 2f
            2 -> 1f
            1 -> 0f
            else -> return null
        }
        return CooldownPlan(band = band, level = comfort.level, maxDropC = drop, baselineTempC = comfort.baselineTempC)
    }

    /** F2-23: 주행 시작 때(개입 전) 설정 온도를 기준 온도에 반영한다 */
    fun observeTripStartTemp(profile: ComfortProfile, band: SeasonBand, tempC: Float): ComfortProfile {
        val comfort = profile.band(band)
        val baseline = comfort.baselineTempC?.let { (1 - BASELINE_ALPHA) * it + BASELINE_ALPHA * tempC } ?: tempC
        return profile.with(band, comfort.copy(baselineTempC = baseline))
    }

    fun apply(profile: ComfortProfile, band: SeasonBand, signal: ComfortSignal): ComfortProfile {
        val c = profile.band(band)
        val updated = when (signal) {
            ComfortSignal.STRONG_COLD -> c.copy(level = (c.level - 1).coerceAtLeast(0), weakSignals = 0, positiveStreak = 0)
            ComfortSignal.WEAK_COLD -> {
                val weak = c.weakSignals + 1
                if (weak >= WEAK_SIGNALS_PER_STEP) {
                    c.copy(level = (c.level - 1).coerceAtLeast(0), weakSignals = 0, positiveStreak = 0)
                } else {
                    c.copy(weakSignals = weak, positiveStreak = 0)
                }
            }
            ComfortSignal.POSITIVE -> {
                val streak = c.positiveStreak + 1
                if (streak >= POSITIVE_STREAK_PER_STEP) {
                    c.copy(level = (c.level + 1).coerceAtMost(band.defaultLevel), positiveStreak = 0)
                } else {
                    c.copy(positiveStreak = streak)
                }
            }
            ComfortSignal.STOP -> c
        }
        return profile.with(band, updated)
    }

    private fun ComfortProfile.with(band: SeasonBand, comfort: BandComfort) =
        copy(bands = bands + (band to comfort))
}
