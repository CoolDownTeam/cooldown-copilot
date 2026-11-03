package com.pleos.cooldown.presentation.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pleos.cooldown.domain.model.ScoreGrade
import com.pleos.cooldown.domain.model.TripData
import com.pleos.cooldown.presentation.theme.*
import java.time.format.DateTimeFormatter

@Composable
fun HistoryScreen(
    onNavigateBack: () -> Unit,
    viewModel: HistoryViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp)
    ) {
        // 헤더
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onNavigateBack,
                modifier = Modifier.size(76.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "뒤로",
                    modifier = Modifier.size(36.dp),
                    tint = TextPrimary
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Text(
                text = "주행 기록",
                style = MaterialTheme.typography.headlineMedium,
                color = TextPrimary
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 히스토리 목록
        if (state.trips.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "아직 주행 기록이 없습니다",
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextDisabled,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(state.trips) { trip ->
                    TripCard(trip = trip)
                }
            }
        }
    }
}

@Composable
private fun TripCard(trip: TripData) {
    val gradeColor = when (trip.grade) {
        ScoreGrade.EXCELLENT -> ScoreExcellent
        ScoreGrade.GOOD -> ScoreGood
        ScoreGrade.AVERAGE -> ScoreAverage
        ScoreGrade.POOR -> ScorePoor
        ScoreGrade.BAD -> ScoreBad
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = SurfaceVariant
    ) {
        Row(
            modifier = Modifier
                .padding(20.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = trip.startTime.format(DateTimeFormatter.ofPattern("MM월 dd일 HH:mm")),
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${trip.durationMinutes}분 주행",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "${trip.finalScore}점",
                    style = MaterialTheme.typography.headlineSmall,
                    color = gradeColor
                )
                Text(
                    text = trip.grade.emoji,
                    style = MaterialTheme.typography.titleMedium,
                    color = gradeColor
                )
            }
        }
    }
}
