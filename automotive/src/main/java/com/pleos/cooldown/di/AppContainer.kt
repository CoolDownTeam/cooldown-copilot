package com.pleos.cooldown.di

import android.content.Context
import com.pleos.cooldown.data.local.BaselineRepository
import com.pleos.cooldown.data.local.CopilotDatabase
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

    // DB는 처음 쓸 때 연다 (앱 시작을 느리게 하지 않게)
    private val database: CopilotDatabase by lazy { CopilotDatabase.create(context) }
    val baselineRepository: BaselineRepository by lazy { BaselineRepository(database.baselineDao()) }
}
