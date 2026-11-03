package com.pleos.cooldown.presentation.result

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pleos.cooldown.presentation.components.AutomotiveButton
import com.pleos.cooldown.presentation.components.ScoreDisplay
import com.pleos.cooldown.presentation.theme.*

@Composable
fun ResultScreen(
    onNavigateBack: () -> Unit,
    onNavigateToHistory: () -> Unit,
    viewModel: ResultViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp)
    ) {
        // 왼쪽: 최종 점수
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "주행 완료!",
                style = MaterialTheme.typography.headlineLarge,
                color = TextPrimary
            )

            Spacer(modifier = Modifier.height(32.dp))

            ScoreDisplay(
                score = state.finalScore,
                grade = state.grade
            )

            Spacer(modifier = Modifier.height(24.dp))

            // 주행 통계
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem(label = "주행 시간", value = "${state.durationMinutes}분")
                StatItem(label = "위반", value = "${state.violationCount}건")
                StatItem(label = "모범", value = "${state.positiveCount}건")
            }

            Spacer(modifier = Modifier.height(32.dp))

            AutomotiveButton(
                onClick = onNavigateBack,
                text = "홈으로",
                icon = Icons.Default.Home
            )
        }

        // 오른쪽: 이벤트 상세
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(start = 24.dp)
        ) {
            Text(
                text = "주행 이벤트",
                style = MaterialTheme.typography.titleMedium,
                color = TextSecondary
            )

            Spacer(modifier = Modifier.height(16.dp))

            // F4 주행 리포트(11/9)에서 개입 기록으로 바뀐다
            Text(
                text = "기록된 이벤트가 없어요",
                style = MaterialTheme.typography.bodyLarge,
                color = TextDisabled
            )
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            color = TextPrimary
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
    }
}
