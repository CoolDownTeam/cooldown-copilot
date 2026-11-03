package com.pleos.cooldown.data.vehicle

import com.pleos.cooldown.domain.model.DrivingState
import com.pleos.cooldown.domain.model.Gear
import com.pleos.cooldown.domain.model.SignalType
import com.pleos.cooldown.domain.model.VehicleSignal
import com.pleos.cooldown.util.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 시나리오 한 프레임. CSV 한 줄: `t_ms,speed_kmh,gear,brake,steering_deg`
 */
data class ScenarioFrame(
    val offsetMs: Long,
    val speedKmh: Float,
    val gear: Gear,
    val brakePressed: Boolean,
    val steeringAngleDeg: Float
)

object ScenarioParser {

    /** 빈 줄과 `#` 주석은 건너뛰고, 형식이 틀린 줄은 줄 번호와 함께 예외를 던진다. */
    fun parse(csv: String): List<ScenarioFrame> =
        csv.lineSequence()
            .mapIndexedNotNull { index, raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@mapIndexedNotNull null
                val cols = line.split(",").map { it.trim() }
                require(cols.size == 5) { "line ${index + 1}: expected 5 columns, got ${cols.size}" }
                ScenarioFrame(
                    offsetMs = cols[0].toLongOrNull()
                        ?: throw IllegalArgumentException("line ${index + 1}: bad t_ms '${cols[0]}'"),
                    speedKmh = cols[1].toFloatOrNull()
                        ?: throw IllegalArgumentException("line ${index + 1}: bad speed '${cols[1]}'"),
                    gear = Gear.entries.firstOrNull { it.name == cols[2].uppercase() }
                        ?: throw IllegalArgumentException("line ${index + 1}: bad gear '${cols[2]}'"),
                    brakePressed = cols[3] == "1" || cols[3].equals("true", ignoreCase = true),
                    steeringAngleDeg = cols[4].toFloatOrNull()
                        ?: throw IllegalArgumentException("line ${index + 1}: bad steering '${cols[4]}'")
                )
            }
            .toList()
            .also { frames ->
                require(frames.zipWithNext().all { (a, b) -> b.offsetMs >= a.offsetMs }) {
                    "t_ms must be non-decreasing"
                }
            }
}

/**
 * 녹화한 시나리오를 재생하는 가짜 데이터 소스 (D0-16). 시연과 통합 확인에 쓴다.
 */
class FakeVehicleDataSource(
    private val frames: List<ScenarioFrame>,
    private val scope: CoroutineScope,
    clock: Clock
) : VehicleDataSource {

    private val store = VehicleSignalStore(clock)
    private var job: Job? = null

    override val signal: StateFlow<VehicleSignal> = store.signal
    override val drivingState: StateFlow<DrivingState> = store.drivingState
    override val unavailable: StateFlow<Set<SignalType>> = store.unavailable

    override fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            var previousOffset = 0L
            for (frame in frames) {
                delay(frame.offsetMs - previousOffset)
                previousOffset = frame.offsetMs
                apply(frame)
            }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }

    private fun apply(frame: ScenarioFrame) {
        store.onGear(frame.gear)
        store.onBrake(frame.brakePressed)
        store.onSteering(frame.steeringAngleDeg)
        store.onSpeed(frame.speedKmh)
    }
}
