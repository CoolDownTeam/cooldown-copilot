package com.pleos.drivinggame.presentation.history

import androidx.lifecycle.ViewModel
import com.pleos.drivinggame.domain.model.TripData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class HistoryState(
    val trips: List<TripData> = emptyList(),
    val isLoading: Boolean = false
)

class HistoryViewModel : ViewModel() {

    private val _state = MutableStateFlow(HistoryState())
    val state: StateFlow<HistoryState> = _state.asStateFlow()

    init {
        // TODO: Load trip history
    }
}
