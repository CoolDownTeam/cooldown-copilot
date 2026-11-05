package com.pleos.cooldown.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pleos.cooldown.domain.cooldown.ComfortProfile
import com.pleos.cooldown.domain.cooldown.SeasonBand
import com.pleos.cooldown.domain.report.ComfortDescriber
import com.pleos.cooldown.domain.settings.CopilotSettings
import com.pleos.cooldown.presentation.components.CardLabel
import com.pleos.cooldown.presentation.components.CopilotCard
import com.pleos.cooldown.presentation.components.PrimaryButton
import com.pleos.cooldown.presentation.components.ScreenPadding
import com.pleos.cooldown.presentation.components.SecondaryButton
import com.pleos.cooldown.presentation.components.SwitchRow
import com.pleos.cooldown.presentation.components.TopBar
import com.pleos.cooldown.presentation.components.TwoPane
import com.pleos.cooldown.presentation.theme.CopilotTheme

/**
 * SC-06 설정 (화면 명세 7절, S1-02·03). 왼쪽 기능 켜기·끄기, 오른쪽 배운 내 취향(읽기 전용, F2-07).
 */
@Composable
fun SettingsScreen(
    settings: CopilotSettings,
    comfort: ComfortProfile,
    season: SeasonBand,
    onChange: ((CopilotSettings) -> CopilotSettings) -> Unit,
    onRelearn: () -> Unit,
    onBack: () -> Unit,
) {
    val c = CopilotTheme.colors
    var confirm by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(ScreenPadding)) {
        TopBar("설정", onBack)
        Spacer(Modifier.height(28.dp))
        TwoPane(
            primaryWeight = 1f,
            primary = {
                CopilotCard(Modifier.fillMaxWidth()) {
                    CardLabel("기능")
                    SwitchRow("쿨다운 캐빈", "거칠어지면 공조를 살짝 시원하게", settings.cooldownEnabled, { on -> onChange { it.copy(cooldownEnabled = on) } })
                    SwitchRow("피로 케어", "환기하고, 졸리면 쉬어 갈 곳을 권해요", settings.fatigueEnabled, { on -> onChange { it.copy(fatigueEnabled = on) } }, accent = c.warm)
                    SwitchRow("코스 브리핑", "출발 전에 어려운 구간을 알려 드려요", settings.briefingEnabled, { on -> onChange { it.copy(briefingEnabled = on) } })
                    SwitchRow("음성 안내", "끄면 공조만 조절하고 말하지 않아요", settings.voiceEnabled, { on -> onChange { it.copy(voiceEnabled = on) } })
                }
            },
            secondary = {
                CopilotCard(Modifier.fillMaxWidth()) {
                    CardLabel("배운 내 취향")
                    SeasonBand.entries.forEach { band ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                ComfortDescriber.seasonName(band) + if (band == season) " · 지금" else "",
                                style = MaterialTheme.typography.titleLarge,
                                color = if (band == season) c.cool else c.text,
                                modifier = Modifier.weight(0.8f),
                            )
                            Text(ComfortDescriber.intensity(comfort, band), style = MaterialTheme.typography.bodyLarge, color = c.textSecondary, modifier = Modifier.weight(1.4f))
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        ComfortDescriber.baselineTemp(comfort, season) ?: "평소 두시는 온도는 아직 배우는 중이에요",
                        style = MaterialTheme.typography.bodyLarge,
                        color = c.text,
                    )
                    Text("춥다고 하시면 덜 차갑게, 편하게 지나가면 그대로 둬요.", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                }
                SecondaryButton("운전 습관 다시 배우기", { confirm = true })
                Text("데이터는 차 밖으로 나가지 않아요.", style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
            },
        )
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("처음부터 다시 배울까요?", style = MaterialTheme.typography.headlineSmall) },
            text = { Text("평소 운전, 계절별 강도, 평소 온도를 지워요. 주행 기록은 남아요.", style = MaterialTheme.typography.bodyLarge) },
            confirmButton = { PrimaryButton("다시 배우기", { confirm = false; onRelearn() }) },
            dismissButton = { SecondaryButton("취소", { confirm = false }) },
            containerColor = c.surface,
        )
    }
}
