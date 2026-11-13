package com.pleos.cooldown.data.hvac

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.HVAC
import ai.pleos.playground.vehicle.api.Seat
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.FanDirectionType
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import ai.pleos.playground.vehicle.listener.HvacFanSpeedListener
import ai.pleos.playground.vehicle.listener.HvacPowerStateListener
import ai.pleos.playground.vehicle.listener.HvacRecirculationStateListener
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Pleos Vehicle SDK 공조 구현. 운전석(`ROW_1_LEFT`)이 들어 있는 영역만 다룬다 (F2-05, 좌핸들 기준).
 *
 * 속성마다 영역이 다르다. 에뮬레이터 VHAL 기준으로 설정 온도·시트 통풍은 운전석(0x1),
 * 풍량·풍향은 앞줄(0x5), 전원·내기순환은 전체(0x75)다. 그래서 고정 영역을 쓰지 않고
 * `checkXXXCapabilities` 결과에서 운전석이 들어 있는 영역을 골라 쓴다 (SAFE-04).
 *
 * 권한: 조회 `CAR_CLIMATE`·`CAR_SEATS`, 변경 `CONTROL_CAR_CLIMATE`·`CONTROL_CAR_SEATS` (SDK의 @RequiresPermission).
 * 제어 권한은 Pleos App Market 사전 승인이 필요하다.
 */
class PleosHvacController(
    context: Context,
    private val clock: Clock,
) : HvacController {

    private val appContext = context.applicationContext
    @Volatile private var temperatureArea: SeatArea? = null
    @Volatile private var fanSpeedArea: SeatArea? = null
    @Volatile private var fanDirectionArea: SeatArea? = null
    @Volatile private var powerArea: SeatArea? = null
    @Volatile private var recirculationArea: SeatArea? = null
    @Volatile private var autoRecirculationArea: SeatArea? = null
    @Volatile private var seatVentilationArea: SeatArea? = null
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
    private var recirculation: Boolean? = null
    private var autoRecirculation: Boolean? = null
    private var lastRecoverMs = Long.MIN_VALUE / 2

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

    // SDK는 일반·자동 내기순환 구독에 같은 리스너 타입을 쓴다 (콜백 이름은 onHvacAutoRecirculationStateUpdated)
    private val recirculationListener = object : HvacRecirculationStateListener {
        override fun onHvacAutoRecirculationStateUpdated(value: Boolean?) {
            val v = value ?: return
            recirculation = v
            detector.onRecirculation(clock.nowMs(), v)?.let(_manualChanges::tryEmit)
            publish()
        }
        override fun onFailed(e: Exception) = logFailure("recirculation", e)
    }

    private val autoRecirculationListener = object : HvacRecirculationStateListener {
        override fun onHvacAutoRecirculationStateUpdated(value: Boolean?) {
            autoRecirculation = value ?: return
            publish()
        }
        override fun onFailed(e: Exception) = logFailure("auto recirculation", e)
    }

    override fun start() {
        if (vehicle != null) return
        try {
            val v = Vehicle(appContext)
            v.initialize()
            vehicle = v
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start HVAC", e)
        }
    }

    /**
     * 지원 범위를 하나씩 확인한다. 에뮬레이터에서 여러 확인을 한꺼번에 보내면 일부가 응답하지 않아서
     * 순서대로, 각각 시간 제한을 두고 보낸다. 여기서 못 얻은 것은 [recoverMissing]이 주행 중에 다시 얻는다.
     */
    override suspend fun prepare() {
        val v = vehicle ?: return
        setUpTemperature(v.getHVAC())
        setUpFanSpeed(v.getHVAC())
        val hvac = v.getHVAC()
        val power = retrying<List<SeatArea>>("power") { ok, fail -> hvac.checkHvacPowerCapabilities(ok, fail) }?.driverArea()
        if (power == null) {
            // 전원을 따로 끌 수 없는(또는 확인되지 않는) 차량이면 늘 켜져 있는 것으로 본다
            powerOn = true
            publish()
        } else {
            powerArea = power
            hvac.registerHvacPowerState(power, powerListener)
        }
        setUpRecirculation(hvac)
        retrying<List<SeatArea>>("auto recirculation") { ok, fail -> hvac.checkHvacAutoRecirculationCapabilities(ok, fail) }
            ?.driverArea()?.let { a -> autoRecirculationArea = a; hvac.registerHvacAutoRecirculationState(a, autoRecirculationListener) }
        retrying<Map<SeatArea, List<FanDirectionType>>>("fan direction") { ok, fail -> hvac.checkHvacFanDirectionCapabilities(ok, fail) }
            ?.keys?.driverArea()?.let { a ->
                fanDirectionArea = a
                // 풍향은 변경 리스너가 없어서 여기서 한 번 읽고, 그 뒤로는 refreshFanDirection으로 다시 읽는다
                fanDirection = retrying<FanDirectionType?>("fan direction value") { ok, fail -> hvac.getHvacFanDirection(a, ok, fail) }
                    ?.toDomain()?.also { detector.onFanDirection(clock.nowMs(), it) }
                publish()
            }
        setUpSeatVentilation(v.getSeat())
        readCurrentValues(v)
    }

    private suspend fun setUpTemperature(hvac: HVAC) {
        retrying<Map<SeatArea, AreaBounds<*>>>("temperature") { ok, fail -> hvac.checkHvacTemperatureCapabilities(ok, fail) }
            ?.keys?.driverArea()?.let { a -> temperatureArea = a; hvac.registerHvacTemperature(a, temperatureListener) }
    }

    private suspend fun setUpFanSpeed(hvac: HVAC) {
        retrying<Map<SeatArea, List<*>>>("fan speed") { ok, fail -> hvac.checkHvacFanSpeedCapabilities(ok, fail) }
            ?.keys?.driverArea()?.let { a -> fanSpeedArea = a; hvac.registerHvacFanSpeed(a, fanSpeedListener) }
    }

    private suspend fun setUpRecirculation(hvac: HVAC) {
        retrying<List<SeatArea>>("recirculation") { ok, fail -> hvac.checkHvacRecirculationCapabilities(ok, fail) }
            ?.driverArea()?.let { a -> recirculationArea = a; hvac.registerHvacRecirculationState(a, recirculationListener) }
    }

    private suspend fun setUpSeatVentilation(seat: Seat) {
        retrying<Map<SeatArea, AreaBounds<*>>>("seat ventilation") { ok, fail -> seat.checkSeatVentilationCapabilities(ok, fail) }
            ?.keys?.driverArea()?.let { a -> seatVentilationArea = a; seat.registerHvacSeatVentilation(a, seatVentilationListener) }
    }

    /** 구독만으로는 현재 값이 오지 않아서(에뮬레이터 확인) 한 번씩 읽어 둔다. 이미 아는 값은 건너뛴다 */
    private suspend fun readCurrentValues(v: Vehicle) {
        val hvac = v.getHVAC()
        temperatureArea?.takeIf { temperature == null }?.let { a ->
            retrying<Float?>("temperature value") { ok, fail -> hvac.getHvacTemperature(a, TemperatureUnit.CELSIUS, ok, fail) }
                ?.let { temperature = it; detector.onTemperature(clock.nowMs(), it) }
        }
        fanSpeedArea?.takeIf { fanSpeed == null }?.let { a ->
            retrying<Int?>("fan speed value") { ok, fail -> hvac.getHvacFanSpeed(a, ok, fail) }
                ?.let { fanSpeed = it; detector.onFanSpeed(clock.nowMs(), it) }
        }
        powerArea?.takeIf { powerOn == null }?.let { a ->
            retrying<Boolean?>("power value") { ok, fail -> hvac.getHvacPowerState(a, ok, fail) }?.let { powerOn = it }
        }
        recirculationArea?.takeIf { recirculation == null }?.let { a ->
            retrying<Boolean?>("recirculation value") { ok, fail -> hvac.getHvacRecirculationState(a, ok, fail) }
                ?.let { recirculation = it; detector.onRecirculation(clock.nowMs(), it) }
        }
        autoRecirculationArea?.takeIf { autoRecirculation == null }?.let { a ->
            retrying<Boolean?>("auto recirculation value") { ok, fail -> hvac.getHvacAutoRecirculationState(a, ok, fail) }
                ?.let { autoRecirculation = it }
        }
        seatVentilationArea?.takeIf { seatVentilation == null }?.let { a ->
            retrying<Int?>("seat ventilation value") { ok, fail -> v.getSeat().getHvacSeatVentilationState(a, ok, fail) }
                ?.let { seatVentilation = it; detector.onSeatVentilation(clock.nowMs(), it) }
        }
        publish()
    }

    override suspend fun recoverMissing() {
        val v = vehicle ?: return
        val missing = temperatureArea == null || fanSpeedArea == null || recirculationArea == null ||
            seatVentilationArea == null || temperature == null || fanSpeed == null || powerOn == null ||
            (recirculationArea != null && recirculation == null) || (seatVentilationArea != null && seatVentilation == null)
        val now = clock.nowMs()
        if (!missing || now - lastRecoverMs < RECOVER_INTERVAL_MS) return
        lastRecoverMs = now
        Log.i(TAG, "recovering missing HVAC info")
        val hvac = v.getHVAC()
        if (temperatureArea == null) setUpTemperature(hvac)
        if (fanSpeedArea == null) setUpFanSpeed(hvac)
        if (recirculationArea == null) setUpRecirculation(hvac)
        if (seatVentilationArea == null) setUpSeatVentilation(v.getSeat())
        readCurrentValues(v)
    }

    override fun stop() {
        val v = vehicle ?: return
        try {
            v.getHVAC().apply {
                temperatureArea?.let { unregisterHvacTemperature(it, temperatureListener) }
                fanSpeedArea?.let { unregisterHvacFanSpeed(it, fanSpeedListener) }
                powerArea?.let { unregisterHvacPowerState(it, powerListener) }
                recirculationArea?.let { unregisterHvacRecirculationState(it, recirculationListener) }
                autoRecirculationArea?.let { unregisterHvacAutoRecirculationState(it, autoRecirculationListener) }
            }
            seatVentilationArea?.let { v.getSeat().unregisterHvacSeatVentilation(it, seatVentilationListener) }
            v.release()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop HVAC", e)
        }
        vehicle = null
    }

    override suspend fun refreshFanDirection() {
        val v = vehicle ?: return
        val area = fanDirectionArea ?: return
        val value = request<FanDirectionType?>("fan direction value") { ok, fail -> v.getHVAC().getHvacFanDirection(area, ok, fail) }
            ?.toDomain()
        fanDirection = value
        val change = if (value == null) detector.onFanDirectionUnknown() else detector.onFanDirection(clock.nowMs(), value)
        change?.let(_manualChanges::tryEmit)
        publish()
    }

    override suspend fun limits(): HvacLimits? {
        val v = vehicle ?: return null
        val hvac = v.getHVAC()
        // SDK의 제네릭 타입은 런타임에 지워져서 실제로는 Double이 들어온다 (에뮬레이터에서 ClassCastException 확인).
        // 그래서 숫자는 모두 Number로 받아 변환한다
        val temp = retrying<Map<SeatArea, AreaBounds<*>>>("temperature limits") { ok, fail ->
            hvac.checkHvacTemperatureCapabilities(ok, fail)
        }?.driverValue()
        val fanSpeeds = request<Map<SeatArea, List<*>>>("fan speed limits") { ok, fail ->
            hvac.checkHvacFanSpeedCapabilities(ok, fail)
        }?.driverValue()?.mapNotNull { (it as? Number)?.toInt() }
        val directions = request<Map<SeatArea, List<FanDirectionType>>>("fan direction limits") { ok, fail ->
            hvac.checkHvacFanDirectionCapabilities(ok, fail)
        }?.driverValue().orEmpty()
        val seatMax = request<Map<SeatArea, AreaBounds<*>>>("seat ventilation limits") { ok, fail ->
            v.getSeat().checkSeatVentilationCapabilities(ok, fail)
        }?.driverValue()?.maxValue.asNumber()?.toInt()
        val minTemp = temp?.minValue.asNumber()?.toFloat()
        val maxTemp = temp?.maxValue.asNumber()?.toFloat()
        Log.d(TAG, "limits temp=$minTemp~$maxTemp fan=$fanSpeeds directions=$directions seat=$seatMax")
        if (minTemp == null || maxTemp == null) return null
        return HvacLimits(
            minTempC = minTemp,
            maxTempC = maxTemp,
            // 풍량 단계 목록을 주지 않는 차량도 있어서, 없으면 현재 값보다 올리지 않는다
            maxFanSpeed = fanSpeeds?.maxOrNull() ?: (fanSpeed ?: 1),
            supportsFaceDirection = FanDirectionType.FACE in directions,
            maxSeatVentilation = seatMax?.takeIf { it > 0 },
        )
    }

    override suspend fun apply(target: HvacTarget): Boolean {
        val v = vehicle ?: return false
        val hvac = v.getHVAC()
        detector.onAppSet(clock.nowMs(), target)
        val current = _snapshot.value
        if (current?.temperatureC != target.temperatureC) {
            val area = temperatureArea ?: return false
            if (!set { ok, fail -> hvac.setHvacTemperature(area, target.temperatureC, TemperatureUnit.CELSIUS, { _, _ -> ok() }, fail) }) return false
        }
        if (current?.fanSpeed != target.fanSpeed) {
            val area = fanSpeedArea ?: return false
            if (!set { ok, fail -> hvac.setHvacFanSpeed(area, target.fanSpeed, { _, _ -> ok() }, fail) }) return false
        }
        val direction = target.fanDirection
        val directionArea = fanDirectionArea
        if (direction != null && direction != current?.fanDirection && directionArea != null) {
            if (!set { ok, fail -> hvac.setHvacFanDirection(directionArea, direction.toSdk(), { _, _ -> ok() }, fail) }) return false
            fanDirection = direction
            // 다음에 다시 읽을 때 앱이 바꾼 값을 운전자 조작으로 보지 않게 한다
            detector.onFanDirection(clock.nowMs(), direction)
            publish()
        }
        val seat = target.seatVentilation
        val seatArea = seatVentilationArea
        if (seat != null && seat != current?.seatVentilation && seatArea != null &&
            !set { ok, fail -> v.getSeat().setHvacSeatVentilation(seatArea, seat, { _, _ -> ok() }, fail) }
        ) return false
        return true
    }

    override suspend fun setRecirculation(on: Boolean): Boolean {
        val v = vehicle ?: return false
        val area = recirculationArea ?: return false
        detector.onAppSetRecirculation(clock.nowMs(), on)
        return set { ok, fail -> v.getHVAC().setHvacRecirculation(area, on, { _, _ -> ok() }, fail) }
    }

    /** 착석 감지를 지원하는 좌석마다 물어서 센다. 권한: `CAR_SEATS` */
    override suspend fun occupantCount(): Int? {
        val seat = vehicle?.getSeat() ?: return null
        val areas = request<List<SeatArea>>("occupancy") { ok, fail -> seat.checkSeatOccupancyCapabilities(ok, fail) }
            ?.takeIf { it.isNotEmpty() } ?: return null
        var count = 0
        for (area in areas) {
            val occupied = request<Boolean?>("occupancy value") { ok, fail -> seat.getCurrentSeatOccupancy(area, ok, fail) }
            if (occupied == true) count++
        }
        // 운전자 좌석 감지가 없어도 운전자는 있다
        return count.coerceAtLeast(1)
    }

    private fun publish() {
        val t = temperature ?: return
        val f = fanSpeed ?: return
        val p = powerOn ?: return
        val next = HvacSnapshot(t, f, p, fanDirection, seatVentilation, recirculation, autoRecirculation)
        if (_snapshot.value != next) Log.d(TAG, "snapshot $next")
        _snapshot.update { next }
    }

    /** 운전석 값을 고른다. 키가 정확히 같지 않을 수 있어 좌석 값으로 찾는다 */
    private fun <T> Map<SeatArea, T>.driverValue(): T? =
        entries.firstOrNull { (area, _) -> SeatValue.ROW_1_LEFT in area.values }?.value

    private fun Any?.asNumber(): Number? = this as? Number

    /** 운전석이 들어 있는 영역 (예: 앞줄 0x5, 전체 0x75) */
    private fun Collection<SeatArea>.driverArea(): SeatArea? {
        val area = firstOrNull { SeatValue.ROW_1_LEFT in it.values }
        Log.d(TAG, "capability areas=${map { it.values.toList() }} → driver=${area?.values?.toList()}")
        return area
    }

    /** 콜백 API를 기다린다. 실패하거나 [REQUEST_TIMEOUT_MS] 안에 답이 없으면 null */
    private suspend fun <T> request(
        what: String = "request",
        call: (onComplete: (T) -> Unit, onFailed: (Exception) -> Unit) -> Unit,
    ): T? = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
        suspendCancellableCoroutine<T?> { cont ->
            try {
                call({ if (cont.isActive) cont.resume(it) }, { e ->
                    logFailure(what, e)
                    if (cont.isActive) cont.resume(null)
                })
            } catch (e: Exception) {
                logFailure(what, e)
                if (cont.isActive) cont.resume(null)
            }
        }
    }.also { if (it == null) Log.w(TAG, "HVAC $what: no result") }

    /**
     * 지원 범위 확인·현재 값 읽기. 앱 시작 직후 첫 요청은 SDK 연결이 덜 돼 답이 없을 때가 있어서
     * (에뮬레이터에서 온도 확인·시트 통풍 값이 3초 안에 안 옴) 한 번 더 묻는다.
     */
    private suspend fun <T> retrying(
        what: String,
        call: (onComplete: (T) -> Unit, onFailed: (Exception) -> Unit) -> Unit,
    ): T? = request(what, call) ?: run {
        delay(CAPABILITY_RETRY_DELAY_MS)
        request("$what (retry)", call)
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
        const val REQUEST_TIMEOUT_MS = 3_000L
        const val CAPABILITY_RETRY_DELAY_MS = 1_000L
        /** [recoverMissing]를 다시 시도하는 최소 간격 */
        const val RECOVER_INTERVAL_MS = 10_000L
    }
}
