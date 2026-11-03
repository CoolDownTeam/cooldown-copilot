package com.pleos.cooldown.data.hvac

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/**
 * 외기온 (F2-19). Pleos HVAC SDK에는 없어서 AAOS `ENV_OUTSIDE_TEMPERATURE`를 읽는다.
 * 권한이 없거나 차량이 지원하지 않으면 null을 돌려주고, 호출하는 쪽이 달로 계절을 정한다.
 */
class OutsideTemperatureSource(context: Context) {

    private val appContext = context.applicationContext

    fun readCelsius(): Float? {
        if (appContext.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) return null
        var car: Car? = null
        return try {
            car = Car.createCar(appContext)
            val manager = car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager ?: return null
            manager.getFloatProperty(VehiclePropertyIds.ENV_OUTSIDE_TEMPERATURE, GLOBAL_AREA)
        } catch (e: Exception) {
            Log.w(TAG, "Outside temperature unavailable", e)
            null
        } finally {
            car?.disconnect()
        }
    }

    companion object {
        const val PERMISSION = "android.car.permission.CAR_EXTERIOR_ENVIRONMENT"
        private const val GLOBAL_AREA = 0
        private const val TAG = "OutsideTemp"
    }
}
