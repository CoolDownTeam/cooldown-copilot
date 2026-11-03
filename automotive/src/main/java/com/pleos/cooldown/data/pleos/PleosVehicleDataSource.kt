package com.pleos.cooldown.data.pleos

import android.content.Context
import android.util.Log
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.VehicleSpeedListener
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pleos Vehicle SDK 데이터 소스
 * 실제 차량 데이터를 Flow로 제공
 */
class PleosVehicleDataSource(private val context: Context) {

    companion object {
        private const val TAG = "PleosVehicleDataSource"

        // VehicleGear 상수
        const val GEAR_UNKNOWN = 0
        const val GEAR_NEUTRAL = 1
        const val GEAR_REVERSE = 2
        const val GEAR_PARK = 4
        const val GEAR_DRIVE = 8
    }

    private var vehicle: Vehicle? = null

    private val _speedState = MutableStateFlow(0f)
    private val _gearState = MutableStateFlow(GEAR_PARK)

    val speedFlow: Flow<Float> = _speedState.asStateFlow()
    val gearFlow: Flow<Int> = _gearState.asStateFlow()

    private val speedListener = object : VehicleSpeedListener {
        override fun onSpeedUpdated(speed: Float?) {
            _speedState.value = speed ?: 0f
            Log.d(TAG, "Speed updated: ${speed ?: 0f} km/h")
        }

        override fun onFailed(e: Exception) {
            Log.e(TAG, "Speed listener failed", e)
        }
    }

    private val gearListener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) {
            val gearValue = when (gear) {
                VehicleGear.GEAR_PARK -> GEAR_PARK
                VehicleGear.GEAR_REVERSE -> GEAR_REVERSE
                VehicleGear.GEAR_NEUTRAL -> GEAR_NEUTRAL
                VehicleGear.GEAR_DRIVE -> GEAR_DRIVE
                else -> GEAR_UNKNOWN
            }
            _gearState.value = gearValue
            Log.d(TAG, "Gear updated: $gear -> $gearValue")
        }

        override fun onFailed(e: Exception) {
            Log.e(TAG, "Gear listener failed", e)
        }
    }

    fun initialize() {
        try {
            vehicle = Vehicle(context)
            vehicle?.initialize()

            // 기어 리스너 등록
            vehicle?.getGear()?.registerGearSelection(gearListener)

            Log.d(TAG, "Vehicle data source initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize vehicle data source", e)
        }
    }

    fun release() {
        try {
            vehicle?.getGear()?.unregisterGearSelection(gearListener)
            vehicle?.release()
            vehicle = null
            Log.d(TAG, "Vehicle data source released")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release vehicle data source", e)
        }
    }
}
