package com.pleos.cooldown.data.hvac

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.HVAC
import ai.pleos.playground.vehicle.api.Seat
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.FanDirectionType
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import ai.pleos.playground.vehicle.listener.HvacFanSpeedListener
import ai.pleos.playground.vehicle.listener.HvacPowerStateListener
import ai.pleos.playground.vehicle.listener.HvacSeatVentilationListener
import ai.pleos.playground.vehicle.listener.HvacTemperatureListener
import android.content.Context
import android.util.Log
import com.pleos.cooldown.domain.cooldown.FanDirection
import com.pleos.cooldown.domain.cooldown.HvacLimits
import com.pleos.cooldown.domain.cooldown.HvacSnapshot
import com.pleos.cooldown.domain.cooldown.HvacTarget
import com.pleos.cooldown.domain.cooldown.ManualChange
import com.pleos.cooldown.domain.cooldown.ManualChangeDetector
import com.pleos.cooldown.util.Clock
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Pleos Vehicle SDK 공조 구현. 운전석(`ROW_1_LEFT`)만 다룬다 (F2-05, 좌핸들 기준).
 *
 * 권한: 조회 `CAR_CLIMATE`·`CAR_SEATS`, 변경 `CONTROL_CAR_CLIMATE`·`CONTROL_CAR_SEATS` (SDK의 @RequiresPermission).
 * 제어 권한은 Pleos App Market 사전 승인이 필요하다.
 */
class PleosHvacController(
    context: Context,
    private val clock: Clock,
) : HvacController {

    private val appContext = context.applicationContext
    private val driverSeat = SeatArea(SeatValue.ROW_1_LEFT)
    private val detector = ManualChangeDetector()

    private val _snapshot = MutableStateFlow<HvacSnapshot?>(null)
    override val snapshot: StateFlow<HvacSnapshot?> = _snapshot.asStateFlow()

    private val _manualChanges = MutableSharedFlow<ManualChange>(extraBufferCapacity = 8)
    override val manualChanges: SharedFlow<ManualChange> = _manualChanges.asSharedFlow()

    private var vehicle: Vehicle? = null

    // 값이 다 모이기 전에는 snapshot을 내보내지 않는다
    private var temperature: Float? = null
    private var fanSpeed: Int? = null
    private var powerOn: Boolean? = null
    private var fanDirection: FanDirection? = null
    private var seatVentilation: Int? = null

    private val temperatureListener = object : HvacTemperatureListener {
        override fun onHvacTemperatureUpdated(value: Float?) {
            val v = value ?: return
            temperature = v
            detector.onTemperature(clock.nowMs(), v)?.let(_manualChanges::tryEmit)
            publish()
        }
        override fun onFailed(e: Exception) = logFailure("temperature", e)
    }

    private val fanSpeedListener = object : HvacFanSpeedListener {
        override fun onHvacFanSpeedUpdated(value: Int?) {
            val v = value ?: return
            fanSpeed = v
            detector.onFanSpeed(clock.nowMs(), v)?.let(_manualChanges::tryEmit)
            publish()
        }
        override fun onFailed(e: Exception) = logFailure("fan speed", e)
    }

    private val powerListener = object : HvacPowerStateListener {
        override fun onHvacPowerStateUpdated(value: Boolean?) {
            powerOn = value ?: return
            publish()
        }
        override fun onFailed(e: Exception) = logFailure("power", e)
    }

    private val seatVentilationListener = object : HvacSeatVentilationListener {
        override fun onHvacSeatVentilationUpdated(value: Int?) {
            val v = value ?: return
            seatVentilation = v
            detector.onSeatVentilation(clock.nowMs(), v)?.let(_manualChanges::tryEmit)
            publish()
        }
        override fun onFailed(e: Exception) = logFailure("seat ventilation", e)
    }

    override fun start() {
        if (vehicle != null) return
        try {
            val v = Vehicle(appContext)
            v.initialize()
            v.getHVAC().apply {
                registerHvacTemperature(driverSeat, temperatureListener)
                registerHvacFanSpeed(driverSeat, fanSpeedListener)
                registerHvacPowerState(driverSeat, powerListener)
                // 풍향은 변경 리스너가 없어서 시작할 때 한 번 읽는다
                getHvacFanDirection(driverSeat, { type ->
                    fanDirection = type?.toDomain()
                    publish()
                }, { e -> logFailure("fan direction", e) })
            }
            v.getSeat().registerHvacSeatVentilation(driverSeat, seatVentilationListener)
            vehicle = v
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start HVAC", e)
        }
    }

    override fun stop() {
        val v = vehicle ?: return
        try {
            v.getHVAC().apply {
                unregisterHvacTemperature(driverSeat, temperatureListener)
                unregisterHvacFanSpeed(driverSeat, fanSpeedListener)
                unregisterHvacPowerState(driverSeat, powerListener)
            }
            v.getSeat().unregisterHvacSeatVentilation(driverSeat, seatVentilationListener)
            v.release()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop HVAC", e)
        }
        vehicle = null
    }

    override suspend fun limits(): HvacLimits? {
        val v = vehicle ?: return null
        val hvac = v.getHVAC()
        val temp = request<Map<SeatArea, ai.pleos.playground.vehicle.api.model.capability.AreaBounds<Float>>> { ok, fail ->
            hvac.checkHvacTemperatureCapabilities(ok, fail)
        }?.driverValue() ?: return null
        val fanMax = request<Map<SeatArea, List<Int>>> { ok, fail ->
            hvac.checkHvacFanSpeedCapabilities(ok, fail)
        }?.driverValue()?.maxOrNull() ?: return null
        val directions = request<Map<SeatArea, List<FanDirectionType>>> { ok, fail ->
            hvac.checkHvacFanDirectionCapabilities(ok, fail)
        }?.driverValue().orEmpty()
        val seatMax = request<Map<SeatArea, ai.pleos.playground.vehicle.api.model.capability.AreaBounds<Int>>> { ok, fail ->
            v.getSeat().checkSeatVentilationCapabilities(ok, fail)
        }?.driverValue()?.maxValue
        return HvacLimits(
            minTempC = temp.minValue,
            maxTempC = temp.maxValue,
            maxFanSpeed = fanMax,
            supportsFaceDirection = FanDirectionType.FACE in directions,
            maxSeatVentilation = seatMax?.takeIf { it > 0 },
        )
    }

    override suspend fun apply(target: HvacTarget): Boolean {
        val v = vehicle ?: return false
        val hvac = v.getHVAC()
        detector.onAppSet(clock.nowMs(), target)
        val current = _snapshot.value
        if (current?.temperatureC != target.temperatureC &&
            !set { ok, fail -> hvac.setHvacTemperature(driverSeat, target.temperatureC, TemperatureUnit.CELSIUS, { _, _ -> ok() }, fail) }
        ) return false
        if (current?.fanSpeed != target.fanSpeed &&
            !set { ok, fail -> hvac.setHvacFanSpeed(driverSeat, target.fanSpeed, { _, _ -> ok() }, fail) }
        ) return false
        val direction = target.fanDirection
        if (direction != null && direction != current?.fanDirection) {
            if (!set { ok, fail -> hvac.setHvacFanDirection(driverSeat, direction.toSdk(), { _, _ -> ok() }, fail) }) return false
            fanDirection = direction
            publish()
        }
        val seat = target.seatVentilation
        if (seat != null && seat != current?.seatVentilation &&
            !set { ok, fail -> v.getSeat().setHvacSeatVentilation(driverSeat, seat, { _, _ -> ok() }, fail) }
        ) return false
        return true
    }

    private fun publish() {
        val t = temperature ?: return
        val f = fanSpeed ?: return
        val p = powerOn ?: return
        _snapshot.update { HvacSnapshot(t, f, p, fanDirection, seatVentilation) }
    }

    /** 운전석 값을 고른다. 키가 정확히 같지 않을 수 있어 좌석 값으로 찾는다 */
    private fun <T> Map<SeatArea, T>.driverValue(): T? =
        entries.firstOrNull { (area, _) -> SeatValue.ROW_1_LEFT in area.values }?.value

    private suspend fun <T> request(call: (onComplete: (T) -> Unit, onFailed: (Exception) -> Unit) -> Unit): T? =
        suspendCancellableCoroutine { cont ->
            try {
                call({ if (cont.isActive) cont.resume(it) }, { e ->
                    logFailure("capability", e)
                    if (cont.isActive) cont.resume(null)
                })
            } catch (e: Exception) {
                logFailure("capability", e)
                if (cont.isActive) cont.resume(null)
            }
        }

    private suspend fun set(call: (onDone: () -> Unit, onFailed: (Exception) -> Unit) -> Unit): Boolean =
        suspendCancellableCoroutine { cont ->
            try {
                call({ if (cont.isActive) cont.resume(true) }, { e ->
                    logFailure("set", e)
                    if (cont.isActive) cont.resume(false)
                })
            } catch (e: Exception) {
                logFailure("set", e)
                if (cont.isActive) cont.resume(false)
            }
        }

    private fun logFailure(what: String, e: Exception) {
        Log.w(TAG, "HVAC $what failed", e)
    }

    private fun FanDirectionType.toDomain(): FanDirection = when (this) {
        FanDirectionType.FACE -> FanDirection.FACE
        FanDirectionType.FLOOR -> FanDirection.FLOOR
        FanDirectionType.DEFROST -> FanDirection.DEFROST
    }

    private fun FanDirection.toSdk(): FanDirectionType = when (this) {
        FanDirection.FACE -> FanDirectionType.FACE
        FanDirection.FLOOR -> FanDirectionType.FLOOR
        FanDirection.DEFROST -> FanDirectionType.DEFROST
    }

    private companion object {
        const val TAG = "PleosHvac"
    }
}
