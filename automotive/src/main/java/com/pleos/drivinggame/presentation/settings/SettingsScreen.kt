package com.pleos.drivinggame.presentation.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pleos.drivinggame.presentation.theme.*

@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel()
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
                text = "설정",
                style = MaterialTheme.typography.headlineMedium,
                color = TextPrimary
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        // 설정 항목들
        SettingsSection(title = "주행 시작") {
            SettingsSwitch(
                title = "시동 시 자동 시작",
                description = "차량 시동이 켜지면 자동으로 주행 모니터링을 시작합니다",
                checked = state.autoStartEnabled,
                onCheckedChange = viewModel::setAutoStart
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        SettingsSection(title = "알림") {
            SettingsSwitch(
                title = "위반 알림",
                description = "교통법규 위반 시 알림을 표시합니다",
                checked = state.violationAlertEnabled,
                onCheckedChange = viewModel::setViolationAlert
            )

            Spacer(modifier = Modifier.height(16.dp))

            SettingsSwitch(
                title = "사고다발구역 알림",
                description = "사고다발구역 진입 시 알림을 표시합니다",
                checked = state.accidentZoneAlertEnabled,
                onCheckedChange = viewModel::setAccidentZoneAlert
            )
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = Primary
        )

        Spacer(modifier = Modifier.height(16.dp))

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = SurfaceVariant
        ) {
            Column(
                modifier = Modifier.padding(20.dp)
            ) {
                content()
            }
        }
    }
}

@Composable
private fun SettingsSwitch(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = OnPrimary,
                checkedTrackColor = Primary
            )
        )
    }
}
