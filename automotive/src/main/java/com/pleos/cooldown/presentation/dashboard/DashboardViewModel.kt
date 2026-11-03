package com.pleos.cooldown.presentation.dashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pleos.cooldown.CooldownApplication
import com.pleos.cooldown.domain.model.DrivingEvent
import com.pleos.cooldown.domain.model.DrivingScore
import com.pleos.cooldown.domain.model.Gear
import com.pleos.cooldown.domain.model.GearPosition
import com.pleos.cooldown.domain.model.TrafficViolation
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDateTime

class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    // 앱 전체에서 공유하는 데이터 소스. 수명은 앱이 관리한다 (이후 CopilotService로 이동)
    private val vehicleDataSource = (application as CooldownApplication).container.vehicleDataSource

    private val _state = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _state.asStateFlow()

    private var monitoringJob: Job? = null
    private var speedMonitorJob: Job? = null
    private var gearMonitorJob: Job? = null
    private var safetyBonusJob: Job? = null

    private var lastSpeedingTime: Long = 0
    private var consecutiveSafeMinutes: Int = 0

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

                // 모니터링 중일 때만 위반 체크
                if (_state.value.isMonitoring) {
                    checkSpeedViolation(signal.speedKmh)
                }
            }
        }
    }

    fun startMonitoring() {
        if (_state.value.isMonitoring) return

        viewModelScope.launch {
            _state.update { state ->
                state.copy(
                    isMonitoring = true,
                    drivingScore = DrivingScore(
                        currentScore = 100,
                        totalScore = 100,
                        events = emptyList(),
                        startTime = LocalDateTime.now()
                    ),
                    recentEvents = emptyList()
                )
            }

            // 안전운전 보너스 체크 (1분마다)
            safetyBonusJob = launch {
                while (true) {
                    delay(60_000) // 1분
                    if (_state.value.isMonitoring) {
                        addSafetyBonus()
                    }
                }
            }
        }
    }

    fun stopMonitoring() {
        monitoringJob?.cancel()
        safetyBonusJob?.cancel()

        _state.update { state ->
            state.copy(
                isMonitoring = false,
                drivingScore = state.drivingScore.copy(
                    endTime = LocalDateTime.now()
                )
            )
        }
    }

    private fun checkSpeedViolation(speed: Float) {
        val currentTime = System.currentTimeMillis()

        // 5초 쿨다운 (연속 감점 방지)
        if (currentTime - lastSpeedingTime < 5000) return

        val violation = when {
            speed >= 180 -> TrafficViolation.SPEEDING_80_PLUS
            speed >= 140 -> TrafficViolation.SPEEDING_60_TO_80
            speed >= 120 -> TrafficViolation.SPEEDING_40_TO_60
            speed >= 100 -> TrafficViolation.SPEEDING_20_TO_40
            else -> null
        }

        violation?.let {
            lastSpeedingTime = currentTime
            consecutiveSafeMinutes = 0
            addViolation(it, speed)
        }
    }

    private fun addViolation(violation: TrafficViolation, speed: Float? = null) {
        val event = DrivingEvent(
            violation = violation,
            timestamp = LocalDateTime.now(),
            speedAtEvent = speed
        )

        _state.update { state ->
            val newScore = (state.drivingScore.currentScore + violation.score).coerceIn(0, 100)
            val newEvents = state.drivingScore.events + event

            state.copy(
                drivingScore = state.drivingScore.copy(
                    currentScore = newScore,
                    events = newEvents
                ),
                recentEvents = (listOf(event) + state.recentEvents).take(5)
            )
        }
    }

    private fun addSafetyBonus() {
        consecutiveSafeMinutes++

        val bonus = TrafficViolation.SAFE_DRIVING_PER_MINUTE
        val event = DrivingEvent(
            violation = bonus,
            timestamp = LocalDateTime.now()
        )

        _state.update { state ->
            val newScore = (state.drivingScore.currentScore + bonus.score).coerceIn(0, 100)
            val newEvents = state.drivingScore.events + event

            state.copy(
                drivingScore = state.drivingScore.copy(
                    currentScore = newScore,
                    events = newEvents
                ),
                recentEvents = (listOf(event) + state.recentEvents).take(5)
            )
        }
    }

    // 수동 위반 추가 (테스트용)
    fun simulateViolation(violation: TrafficViolation) {
        if (_state.value.isMonitoring) {
            addViolation(violation)
        }
    }

    override fun onCleared() {
        super.onCleared()
        monitoringJob?.cancel()
        speedMonitorJob?.cancel()
        gearMonitorJob?.cancel()
        safetyBonusJob?.cancel()
    }
}
