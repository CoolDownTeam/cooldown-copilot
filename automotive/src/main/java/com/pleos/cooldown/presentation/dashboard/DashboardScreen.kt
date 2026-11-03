package com.pleos.cooldown.presentation.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pleos.cooldown.presentation.components.AutomotiveButton
import com.pleos.cooldown.presentation.theme.*

@Composable
fun DashboardScreen(
    onNavigateToResult: () -> Unit,
    onNavigateToHistory: () -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: DashboardViewModel = viewModel(
        factory = ViewModelProvider.AndroidViewModelFactory.getInstance(
            LocalContext.current.applicationContext as android.app.Application
        )
    )
) {
    val state by viewModel.state.collectAsState()

    // 주차 시 결과 화면으로 이동
    if (state.shouldShowResult) {
        viewModel.stopMonitoring()
        onNavigateToResult()
    }

    DashboardContent(
        state = state,
        onStartDriving = viewModel::startMonitoring,
        onStopDriving = viewModel::stopMonitoring,
        onNavigateToHistory = onNavigateToHistory,
        onNavigateToSettings = onNavigateToSettings
    )
}

@Composable
private fun DashboardContent(
    state: DashboardState,
    onStartDriving: () -> Unit,
    onStopDriving: () -> Unit,
    onNavigateToHistory: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp)
    ) {
        // 왼쪽: 속도 & 주행 시작/종료
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // 현재 속도 & 제한속도
            if (state.isMonitoring) {
                SpeedInfo(
                    currentSpeed = state.vehicleState.speed,
                    speedLimit = state.currentSpeedLimit,
                    isAccidentZone = state.isAccidentZone
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // 시작/정지 버튼
            if (state.isMonitoring) {
                AutomotiveButton(
                    onClick = onStopDriving,
                    text = "주행 종료",
                    icon = Icons.Default.Close,
                    containerColor = ViolationMajor
                )
            } else {
                AutomotiveButton(
                    onClick = onStartDriving,
                    text = "주행 시작",
                    icon = Icons.Default.PlayArrow,
                    containerColor = Primary
                )
            }
        }

        // 오른쪽: 메뉴
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(start = 24.dp)
        ) {
            // 상단 메뉴 버튼들
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(
                    onClick = onNavigateToHistory,
                    modifier = Modifier.size(76.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DateRange,
                        contentDescription = "히스토리",
                        modifier = Modifier.size(36.dp),
                        tint = TextSecondary
                    )
                }
                IconButton(
                    onClick = onNavigateToSettings,
                    modifier = Modifier.size(76.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "설정",
                        modifier = Modifier.size(36.dp),
                        tint = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 새 화면(docs/screen-spec.md)으로 교체될 때까지 비워 둔다
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (state.isMonitoring) "주행 중" else "주행을 시작하세요",
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextDisabled,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun SpeedInfo(
    currentSpeed: Float,
    speedLimit: Int?,
    isAccidentZone: Boolean
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 현재 속도
        Text(
            text = "${currentSpeed.toInt()} km/h",
            style = MaterialTheme.typography.headlineMedium,
            color = when {
                speedLimit != null && currentSpeed > speedLimit -> ViolationMajor
                isAccidentZone -> ViolationWarning
                else -> TextPrimary
            }
        )

        // 제한속도
        if (speedLimit != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = SurfaceVariant
            ) {
                Text(
                    text = "제한속도 $speedLimit km/h",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }

        // 사고다발구역 경고
        if (isAccidentZone) {
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = ViolationWarning.copy(alpha = 0.2f)
            ) {
                Text(
                    text = "사고다발구역",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ViolationWarning,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    }
}

