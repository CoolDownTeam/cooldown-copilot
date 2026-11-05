package com.pleos.cooldown.data.vehicle

import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * AAOS UX 제한 (화면 명세 1·5절). 주행 중 화면 전환에 쓰고, 기어 기반 주행 상태와 함께 본다.
 * 서비스를 못 쓰면 false로 두고 기어 기반 판단만 쓴다.
 */
class UxRestrictionSource(context: Context) {

    private val appContext = context.applicationContext
    private var car: Car? = null
    private var manager: CarUxRestrictionsManager? = null

    private val _requiresDistractionOptimization = MutableStateFlow(false)
    val requiresDistractionOptimization: StateFlow<Boolean> = _requiresDistractionOptimization.asStateFlow()

    fun start() {
        if (manager != null) return
        runCatching {
            val c = Car.createCar(appContext) ?: return
            car = c
            val m = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager ?: return
            manager = m
            _requiresDistractionOptimization.value = m.currentCarUxRestrictions?.isRequiresDistractionOptimization ?: false
            m.registerListener { restrictions ->
                _requiresDistractionOptimization.value = restrictions.isRequiresDistractionOptimization
            }
        }.onFailure { Log.w(TAG, "UX restrictions unavailable", it) }
    }

    fun stop() {
        runCatching { manager?.unregisterListener() }
        manager = null
        car?.disconnect()
        car = null
    }

    private companion object {
        const val TAG = "UxRestrictionSource"
    }
}
