package com.pleos.drivinggame.presentation.dashboard

import com.pleos.drivinggame.domain.model.DrivingEvent
import com.pleos.drivinggame.domain.model.DrivingScore
import com.pleos.drivinggame.domain.model.GearPosition
import com.pleos.drivinggame.domain.model.VehicleState

/**
 * 대시보드 UI 상태
 */
data class DashboardState(
    val isMonitoring: Boolean = false,
    val drivingScore: DrivingScore = DrivingScore(),
    val vehicleState: VehicleState = VehicleState(),
    val recentEvents: List<DrivingEvent> = emptyList(),
    val currentSpeedLimit: Int? = null,
    val isAccidentZone: Boolean = false,
    val autoStartEnabled: Boolean = false
) {
    val canStartDriving: Boolean
        get() = vehicleState.gearPosition != GearPosition.P || !autoStartEnabled

    val shouldShowResult: Boolean
        get() = isMonitoring && vehicleState.gearPosition == GearPosition.P
}
