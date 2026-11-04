package com.pleos.cooldown.domain.engine

import com.pleos.cooldown.domain.model.DrivingFeatures
import com.pleos.cooldown.domain.model.FeatureId
import com.pleos.cooldown.domain.model.SpeedBand
import com.pleos.cooldown.domain.model.VehicleSignal
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * C0 특징 추출 (명세 3.1). 10Hz로 샘플링한 차량 신호를 받아 최근 3분 창의 특징을 계산한다.
 *
 * 순수 Kotlin이고 스레드 안전하지 않다. 한 코루틴에서만 호출한다.
 */
class FeatureExtractor(private val config: Config = Config()) {

    data class Config(
        val windowMs: Long = 180_000,
        /** T_acc 초기값. 기준선이 쌓이면 개인 상위 5% 값으로 바꾼다 (C0-02) */
        val hardAccelMs2: Double = 2.5,
        /** T_dec 초기값 (C0-03) */
        val hardDecelMs2: Double = 3.0,
        val accelPhaseMs2: Double = 0.5,
        val flipWindowMs: Long = 10_000,
        val smoothingMs: Long = 500,
        val minSteerSpeedKmh: Float = 5f,
        val steerStillDegPerSec: Double = 0.5,
        val microCorrectionMinDeg: Double = 1.0,
        val microCorrectionMaxDeg: Double = 5.0,
        val straightMaxAngleDeg: Double = 15.0,
        val longStopMs: Long = 300_000,
        /** 표본 간격이 이보다 길면 미분하지 않는다 (신호 끊김) */
        val maxGapMs: Long = 1_000,
        /** 경계 근처에서 속도대가 계속 바뀌어 창이 비는 것을 막는다 */
        val bandHysteresisKmh: Float = 3f,
    )

    private class Sample(
        val timeMs: Long,
        val durationMs: Long,
        val steerRate: Double?,
        val straight: Boolean,
    )

    private class Previous(val timeMs: Long, val speedMs: Double, val steerDeg: Double)

    /**
     * 속도대 하나의 최근 3분 창. 속도대가 바뀌어도 지우지 않고, 각 속도대에서 보낸 시간과 사건만 모은다.
     * (에뮬레이터 확인: 60km/h 근처에서 가감속을 반복하면 창을 새로 쓰는 방식은 사건이 계속 지워져 공격성을 낮게 봤다)
     */
    private class BandWindow {
        val samples = ArrayDeque<Sample>()
        val hardAccelEvents = ArrayDeque<Long>()
        val hardDecelEvents = ArrayDeque<Long>()
        val flipEvents = ArrayDeque<Long>()
        val microCorrectionEvents = ArrayDeque<Long>()

        fun prune(cutoffMs: Long) {
            while (samples.firstOrNull()?.let { it.timeMs < cutoffMs } == true) samples.removeFirst()
            listOf(hardAccelEvents, hardDecelEvents, flipEvents, microCorrectionEvents).forEach { events ->
                while (events.firstOrNull()?.let { it < cutoffMs } == true) events.removeFirst()
            }
        }
    }

    private val windows = SpeedBand.entries.associateWith { BandWindow() }
    private val rawAccel = ArrayDeque<Pair<Long, Double>>()

    private var previous: Previous? = null
    private var band: SpeedBand? = null
    private var acceleration = 0.0

    private var inHardAccel = false
    private var inHardDecel = false
    private var inAccelPhase = false
    private var previousBrake = false
    private var lastAccelPhaseMs: Long? = null
    private var lastBrakeMs: Long? = null
    private var steerEpisodeStartDeg: Double? = null

    private var drivingSinceMs: Long? = null
    private var stoppedSinceMs: Long? = null

    fun add(timeMs: Long, signal: VehicleSignal) {
        val prev = previous
        if (prev != null && timeMs <= prev.timeMs) return

        val speedMs = signal.speedKmh / 3.6
        val steerDeg = signal.steeringAngleDeg.toDouble()
        previous = Previous(timeMs, speedMs, steerDeg)
        updateContinuousDriving(timeMs, signal.speedKmh)

        val current = resolveBand(signal.speedKmh)
        band = current
        val window = windows.getValue(current)

        if (prev == null) return
        val dtMs = timeMs - prev.timeMs
        if (dtMs > config.maxGapMs) {
            rawAccel.clear()
            steerEpisodeStartDeg = null
            return
        }
        val dtSec = dtMs / 1000.0

        rawAccel.addLast(timeMs to (speedMs - prev.speedMs) / dtSec)
        while (rawAccel.first().first <= timeMs - config.smoothingMs) rawAccel.removeFirst()
        acceleration = rawAccel.sumOf { it.second } / rawAccel.size

        detectLongitudinalEvents(window, timeMs, signal.brakePressed)

        val steerValid = signal.speedKmh >= config.minSteerSpeedKmh
        val steerRate = (steerDeg - prev.steerDeg) / dtSec
        if (steerValid) trackSteerEpisode(window, timeMs, prev.steerDeg, steerDeg, steerRate) else steerEpisodeStartDeg = null

        window.samples.addLast(
            Sample(
                timeMs = timeMs,
                durationMs = dtMs,
                steerRate = if (steerValid) steerRate else null,
                straight = steerValid && abs(steerDeg) <= config.straightMaxAngleDeg,
            )
        )
        val cutoff = timeMs - config.windowMs
        windows.values.forEach { it.prune(cutoff) }
    }

    /** 표본이 하나도 없으면 null */
    fun snapshot(nowMs: Long): DrivingFeatures? {
        val currentBand = band ?: return null
        val window = windows.getValue(currentBand)
        val samples = window.samples.filter { it.timeMs >= nowMs - config.windowMs }
        // 분모는 최근 3분 중 이 속도대에서 보낸 시간. 짧을 때 사건 하나로 값이 튀지 않도록 최소 1분
        val windowMs = samples.sumOf { it.durationMs }
        val minutes = max(windowMs, MINUTE_MS) / MINUTE_MS.toDouble()

        val values = mutableMapOf(
            FeatureId.HARD_ACCEL_PER_MIN to window.hardAccelEvents.size / minutes,
            FeatureId.HARD_DECEL_PER_MIN to window.hardDecelEvents.size / minutes,
            FeatureId.ACCEL_BRAKE_FLIP_PER_MIN to window.flipEvents.size / minutes,
        )

        val steerSamples = samples.filter { it.steerRate != null }
        if (steerSamples.isNotEmpty()) {
            val steerMinutes = max(steerSamples.sumOf { it.durationMs }, MINUTE_MS) / MINUTE_MS.toDouble()
            values[FeatureId.STEER_MICRO_CORRECTION_PER_MIN] = window.microCorrectionEvents.size / steerMinutes
        }
        if (steerSamples.size >= 2) {
            values[FeatureId.STEER_RATE_STD] = standardDeviation(steerSamples.mapNotNull { it.steerRate })
        }
        val straightSamples = samples.filter { it.straight }
        if (straightSamples.isNotEmpty()) {
            val still = straightSamples.count { abs(it.steerRate ?: 0.0) < config.steerStillDegPerSec }
            values[FeatureId.STEER_HOLD_RATIO] = still.toDouble() / straightSamples.size
        }

        return DrivingFeatures(
            timestampMs = nowMs,
            speedBand = currentBand,
            windowMs = windowMs,
            accelerationMs2 = acceleration,
            continuousDrivingMs = continuousDrivingMs(nowMs),
            values = values,
        )
    }

    private fun detectLongitudinalEvents(window: BandWindow, timeMs: Long, brake: Boolean) {
        val hardAccel = acceleration >= config.hardAccelMs2
        if (hardAccel && !inHardAccel) window.hardAccelEvents.addLast(timeMs)
        inHardAccel = hardAccel

        val hardDecel = brake && -acceleration >= config.hardDecelMs2
        if (hardDecel && !inHardDecel) window.hardDecelEvents.addLast(timeMs)
        inHardDecel = hardDecel

        // C0-04: 가속 구간과 제동이 10초 안에 서로 바뀌면 반전 1회
        val accelPhase = acceleration > config.accelPhaseMs2
        if (accelPhase && !inAccelPhase && withinFlipWindow(lastBrakeMs, timeMs)) window.flipEvents.addLast(timeMs)
        if (brake && !previousBrake && withinFlipWindow(lastAccelPhaseMs, timeMs)) window.flipEvents.addLast(timeMs)
        inAccelPhase = accelPhase
        previousBrake = brake
        if (accelPhase) lastAccelPhaseMs = timeMs
        if (brake) lastBrakeMs = timeMs
    }

    private fun withinFlipWindow(lastMs: Long?, timeMs: Long): Boolean =
        lastMs != null && timeMs - lastMs <= config.flipWindowMs

    /** C0-06: 핸들이 움직이기 시작해서 멈출 때까지를 한 번의 보정으로 보고, 그 크기가 1~5°면 센다 */
    private fun trackSteerEpisode(window: BandWindow, timeMs: Long, fromDeg: Double, toDeg: Double, steerRate: Double) {
        if (abs(steerRate) >= config.steerStillDegPerSec) {
            if (steerEpisodeStartDeg == null) steerEpisodeStartDeg = fromDeg
            return
        }
        val startDeg = steerEpisodeStartDeg ?: return
        steerEpisodeStartDeg = null
        val amplitude = abs(toDeg - startDeg)
        if (amplitude >= config.microCorrectionMinDeg && amplitude <= config.microCorrectionMaxDeg) {
            window.microCorrectionEvents.addLast(timeMs)
        }
    }

    private fun resolveBand(speedKmh: Float): SpeedBand {
        val raw = SpeedBand.of(speedKmh)
        val current = band ?: return raw
        if (raw == current) return current
        val h = config.bandHysteresisKmh
        val clearlyInside = SpeedBand.of(speedKmh - h) == raw && SpeedBand.of(speedKmh + h) == raw
        return if (clearlyInside) raw else current
    }

    /** C0-08: 5분 넘게 정차하면 연속 주행 시간을 0부터 다시 센다 */
    private fun updateContinuousDriving(timeMs: Long, speedKmh: Float) {
        if (speedKmh > 0f) {
            val stoppedSince = stoppedSinceMs
            if (drivingSinceMs == null || (stoppedSince != null && timeMs - stoppedSince > config.longStopMs)) {
                drivingSinceMs = timeMs
            }
            stoppedSinceMs = null
        } else if (stoppedSinceMs == null) {
            stoppedSinceMs = timeMs
        }
    }

    private fun continuousDrivingMs(nowMs: Long): Long {
        val since = drivingSinceMs ?: return 0
        val stoppedSince = stoppedSinceMs
        if (stoppedSince != null && nowMs - stoppedSince > config.longStopMs) return 0
        return nowMs - since
    }

    private fun standardDeviation(values: List<Double>): Double {
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
    }

    private companion object {
        const val MINUTE_MS = 60_000L
    }
}
