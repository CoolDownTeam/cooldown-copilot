package com.pleos.cooldown.data.vehicle

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import com.pleos.cooldown.domain.model.DrivingState
import com.pleos.cooldown.domain.model.Gear
import com.pleos.cooldown.domain.model.SignalType
import com.pleos.cooldown.domain.model.VehicleSignal
import com.pleos.cooldown.util.Clock
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs

/**
 * 실제 차량 데이터 소스 (D0).
 * 차속은 AAOS 표준 [CarPropertyManager]로 받는다 (Vehicle SDK 2.0.3에는 속도 구독 API가 없음).
 * 기어·브레이크·조향·주행 거리는 Pleos Vehicle SDK로 받는다.
 */
class PleosVehicleDataSource(
    context: Context,
    clock: Clock
) : VehicleDataSource {

    companion object {
        private const val TAG = "PleosVehicleDataSource"
        const val CAR_SPEED_PERMISSION = "android.car.permission.CAR_SPEED"
    }

    private val appContext = context.applicationContext
    private val store = VehicleSignalStore(clock)

    override val signal: StateFlow<VehicleSignal> = store.signal
    override val drivingState: StateFlow<DrivingState> = store.drivingState
    override val unavailable: StateFlow<Set<SignalType>> = store.unavailable

    private var vehicle: Vehicle? = null
    private var car: Car? = null
    private var propertyManager: CarPropertyManager? = null

    private val gearListener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) {
            store.onGear(gear?.let(::mapGear))
        }

        override fun onFailed(e: Exception) = fail(SignalType.GEAR, e)
    }

    private val brakeListener = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(pressed: Boolean?) {
            store.onBrake(pressed)
        }

        override fun onFailed(e: Exception) = fail(SignalType.BRAKE, e)
    }

    private val steeringListener = object : SteeringWheelAngleListener {
        override fun onSteeringWheelAngleUpdated(angle: Float?) {
            store.onSteering(angle)
        }

        override fun onFailed(e: Exception) = fail(SignalType.STEERING, e)
    }

    private val distanceListener = object : DistanceDrivenListener {
        override fun onDistanceDrivenUpdated(distance: Float?) {
            store.onOdometer(distance)
        }

        override fun onFailed(e: Exception) = fail(SignalType.ODOMETER, e)
    }

    private val speedCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val metersPerSecond = value.value as? Float ?: return
            // 후진 시 음수로 들어오므로 절댓값을 쓴다
            store.onSpeed(abs(metersPerSecond) * 3.6f)
        }

        override fun onErrorEvent(propertyId: Int, areaId: Int) {
            Log.e(TAG, "Speed property error: $propertyId / $areaId")
            store.markUnavailable(SignalType.SPEED)
        }
    }

    override fun start() {
        if (vehicle == null) startVehicleSdk()
        if (propertyManager == null) startCarSpeed()
    }

    override fun stop() {
        try {
            vehicle?.let { v ->
                v.getGear().unregisterGearSelection(gearListener)
                v.getBrake().unregisterBrakePedalStatus(brakeListener)
                v.getSteeringWheel().unregisterSteeringWheelAngle(steeringListener)
                v.getOdometer().unregisterDistanceDriven(distanceListener)
                v.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release Vehicle SDK", e)
        }
        vehicle = null

        try {
            propertyManager?.unregisterCallback(speedCallback)
            car?.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release Car service", e)
        }
        propertyManager = null
        car = null
    }

    private fun startVehicleSdk() {
        try {
            val v = Vehicle(appContext)
            v.initialize()
            v.getGear().registerGearSelection(gearListener)
            v.getBrake().registerBrakePedalStatus(brakeListener)
            v.getSteeringWheel().registerSteeringWheelAngle(steeringListener)
            v.getOdometer().registerDistanceDriven(distanceListener)
            vehicle = v
            Log.d(TAG, "Vehicle SDK listeners registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Vehicle SDK", e)
            listOf(SignalType.GEAR, SignalType.BRAKE, SignalType.STEERING, SignalType.ODOMETER)
                .forEach(store::markUnavailable)
        }
    }

    private fun startCarSpeed() {
        // CAR_SPEED는 런타임 권한이다. 허용 전에는 건너뛰고, 허용 후 start()를 다시 호출한다.
        if (appContext.checkSelfPermission(CAR_SPEED_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            store.markUnavailable(SignalType.SPEED)
            return
        }
        try {
            val connected = car ?: Car.createCar(appContext)
            car = connected
            val manager = connected?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
            if (manager == null) {
                store.markUnavailable(SignalType.SPEED)
                return
            }
            @Suppress("DEPRECATION")
            val registered = manager.registerCallback(
                speedCallback,
                VehiclePropertyIds.PERF_VEHICLE_SPEED,
                CarPropertyManager.SENSOR_RATE_FAST
            )
            if (registered) {
                propertyManager = manager
                Log.d(TAG, "Speed callback registered")
            } else {
                store.markUnavailable(SignalType.SPEED)
            }
        } catch (e: Exception) {
            fail(SignalType.SPEED, e)
        }
    }

    private fun fail(type: SignalType, e: Exception) {
        Log.e(TAG, "$type listener failed", e)
        store.markUnavailable(type)
    }

    private fun mapGear(gear: VehicleGear): Gear = when (gear) {
        VehicleGear.GEAR_PARK -> Gear.P
        VehicleGear.GEAR_REVERSE -> Gear.R
        VehicleGear.GEAR_NEUTRAL -> Gear.N
        VehicleGear.GEAR_UNKNOWN -> Gear.UNKNOWN
        // GEAR_DRIVE와 수동 기어(GEAR_1~9)는 모두 전진으로 본다
        else -> Gear.D
    }
}
