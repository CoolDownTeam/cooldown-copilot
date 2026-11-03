package com.pleos.cooldown.presentation.result

import androidx.lifecycle.ViewModel
import com.pleos.cooldown.domain.model.ScoreGrade
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ResultState(
    val finalScore: Int = 100,
    val grade: ScoreGrade = ScoreGrade.EXCELLENT,
    val durationMinutes: Long = 0,
    val violationCount: Int = 0,
    val positiveCount: Int = 0
)

class ResultViewModel : ViewModel() {

    private val _state = MutableStateFlow(ResultState())
    val state: StateFlow<ResultState> = _state.asStateFlow()

    init {
        // TODO: Load last trip data from repository
    }
}
