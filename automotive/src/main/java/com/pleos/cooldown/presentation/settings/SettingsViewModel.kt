package com.pleos.cooldown.presentation.settings

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class SettingsState(
    val autoStartEnabled: Boolean = false,
    val violationAlertEnabled: Boolean = true,
    val accidentZoneAlertEnabled: Boolean = true
)

class SettingsViewModel : ViewModel() {

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    fun setAutoStart(enabled: Boolean) {
        _state.update { it.copy(autoStartEnabled = enabled) }
        // TODO: Save to preferences
    }

    fun setViolationAlert(enabled: Boolean) {
        _state.update { it.copy(violationAlertEnabled = enabled) }
        // TODO: Save to preferences
    }

    fun setAccidentZoneAlert(enabled: Boolean) {
        _state.update { it.copy(accidentZoneAlertEnabled = enabled) }
        // TODO: Save to preferences
    }
}
