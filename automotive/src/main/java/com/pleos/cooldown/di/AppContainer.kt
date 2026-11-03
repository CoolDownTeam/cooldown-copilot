package com.pleos.cooldown.di

import android.content.Context
import com.pleos.cooldown.data.vehicle.PleosVehicleDataSource
import com.pleos.cooldown.data.vehicle.VehicleDataSource
import com.pleos.cooldown.util.Clock
import com.pleos.cooldown.util.SystemClock

/**
 * 수동 DI 컨테이너. 실제/가짜 데이터 소스를 바꿔 끼우는 지점이다.
 */
class AppContainer(context: Context) {
    val clock: Clock = SystemClock
    val vehicleDataSource: VehicleDataSource = PleosVehicleDataSource(context, clock)
}
