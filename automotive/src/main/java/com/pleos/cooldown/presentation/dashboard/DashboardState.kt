package com.pleos.cooldown.presentation.dashboard

import com.pleos.cooldown.domain.model.GearPosition
import com.pleos.cooldown.domain.model.VehicleState

/**
 * 대시보드 UI 상태
 */
data class DashboardState(
    val isMonitoring: Boolean = false,
    val vehicleState: VehicleState = VehicleState(),
    val currentSpeedLimit: Int? = null,
    val isAccidentZone: Boolean = false,
    val autoStartEnabled: Boolean = false
) {
    val canStartDriving: Boolean
        get() = vehicleState.gearPosition != GearPosition.P || !autoStartEnabled

    val shouldShowResult: Boolean
        get() = isMonitoring && vehicleState.gearPosition == GearPosition.P
}
