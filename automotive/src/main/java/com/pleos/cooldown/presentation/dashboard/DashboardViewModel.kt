package com.pleos.cooldown.presentation.dashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pleos.cooldown.CooldownApplication
import com.pleos.cooldown.domain.model.Gear
import com.pleos.cooldown.domain.model.GearPosition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    // 앱 전체에서 공유하는 데이터 소스. 수명은 앱이 관리한다 (이후 CopilotService로 이동)
    private val vehicleDataSource = (application as CooldownApplication).container.vehicleDataSource

    private val _state = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _state.asStateFlow()

    init {
        vehicleDataSource.start()
        observeVehicleData()
    }

    private fun observeVehicleData() {
        viewModelScope.launch {
            vehicleDataSource.signal.collect { signal ->
                val gearPosition = when (signal.gear) {
                    Gear.P -> GearPosition.P
                    Gear.R -> GearPosition.R
                    Gear.N -> GearPosition.N
                    Gear.D -> GearPosition.D
                    Gear.UNKNOWN -> GearPosition.UNKNOWN
                }

                _state.update { state ->
                    state.copy(
                        vehicleState = state.vehicleState.copy(
                            speed = signal.speedKmh,
                            gearPosition = gearPosition,
                            steeringAngle = signal.steeringAngleDeg,
                            isEngineOn = gearPosition != GearPosition.P,
                            timestamp = signal.timestampMs
                        )
                    )
                }
            }
        }
    }

    // 기어 자동 감지로 바뀌기 전까지의 임시 시작·종료. C0·F2는 CopilotService에서 돈다 (명세 1.1)
    fun startMonitoring() {
        _state.update { it.copy(isMonitoring = true) }
    }

    fun stopMonitoring() {
        _state.update { it.copy(isMonitoring = false) }
    }
}
