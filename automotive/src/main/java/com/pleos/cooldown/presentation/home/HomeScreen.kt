package com.pleos.cooldown.presentation.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pleos.cooldown.domain.cooldown.ComfortProfile
import com.pleos.cooldown.domain.cooldown.SeasonBand
import com.pleos.cooldown.domain.report.ComfortDescriber
import com.pleos.cooldown.domain.report.TripSummarizer
import com.pleos.cooldown.domain.settings.CopilotSettings
import com.pleos.cooldown.presentation.BriefingUi
import com.pleos.cooldown.presentation.LearningUi
import com.pleos.cooldown.presentation.TripItem
import com.pleos.cooldown.presentation.components.CardLabel
import com.pleos.cooldown.presentation.components.CopilotCard
import com.pleos.cooldown.presentation.components.Icon
import com.pleos.cooldown.presentation.components.IconButton
import com.pleos.cooldown.presentation.components.LineIcon
import com.pleos.cooldown.presentation.components.ProgressBar
import com.pleos.cooldown.presentation.components.ScreenPadding
import com.pleos.cooldown.presentation.components.SecondaryButton
import com.pleos.cooldown.presentation.components.StatusText
import com.pleos.cooldown.presentation.components.TwoPane
import com.pleos.cooldown.presentation.theme.CopilotTheme
import java.time.ZoneId

/** 학습 완료 후 이 기간이 지나면 학습 카드를 숨긴다 (화면 명세 3절) */
private const val LEARNING_CARD_KEEP_MS = 7L * 24 * 60 * 60_000

data class HomeUi(
    val settings: CopilotSettings,
    val learning: LearningUi?,
    val lastTrip: TripItem?,
    val learnedSinceMs: Long?,
    val nowMs: Long,
    val briefing: BriefingUi?,
    val comfort: ComfortProfile,
    val season: SeasonBand,
    val climateAvailable: Boolean?,
)

/**
 * SC-02 홈 (화면 명세 3절). 왼쪽 정보, 오른쪽 상태. 오른쪽 카드는 상태만 보이고 누르면 설정으로 간다 (UI-06).
 */
@Composable
fun HomeScreen(
    ui: HomeUi,
    onOpenTrip: (Long) -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
    onListenBriefing: () -> Unit,
) {
    val c = CopilotTheme.colors
    Column(Modifier.fillMaxSize().padding(ScreenPadding)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("쿨다운 코파일럿", style = MaterialTheme.typography.headlineMedium, color = c.text, modifier = Modifier.weight(1f))
            IconButton(Icon.HISTORY, "기록", onHistory)
            Spacer(Modifier.width(16.dp))
            IconButton(Icon.SETTINGS, "설정", onSettings)
        }
        Spacer(Modifier.height(32.dp))
        TwoPane(
            primaryWeight = 1.6f,
            primary = {
                LearningCard(ui)
                LastTripCard(ui.lastTrip, onOpenTrip)
                ui.briefing?.let { BriefingCard(it, onListenBriefing, ui.settings.briefingEnabled && ui.settings.voiceEnabled) }
            },
            secondary = {
                CooldownCard(ui, onSettings)
                CopilotCard(Modifier.fillMaxWidth(), onClick = onSettings) {
                    CardLabel("피로 케어")
                    StatusText(ui.settings.fatigueEnabled, accent = c.warm)
                }
                CopilotCard(Modifier.fillMaxWidth(), onClick = onSettings) {
                    CardLabel("음성 안내")
                    StatusText(ui.settings.voiceEnabled)
                }
            },
        )
    }
}

@Composable
private fun LearningCard(ui: HomeUi) {
    val c = CopilotTheme.colors
    val learning = ui.learning ?: return
    if (!learning.learning) {
        val since = ui.learnedSinceMs
        if (since != null && ui.nowMs - since > LEARNING_CARD_KEEP_MS) return
        CopilotCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LineIcon(Icon.SPARKLE, c.cool)
                Text("당신의 운전을 알고 있어요", style = MaterialTheme.typography.headlineSmall, color = c.text)
            }
        }
        return
    }
    val trips = learning.trips.coerceAtMost(learning.target)
    CopilotCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LineIcon(Icon.SPARKLE, c.cool)
            Text("배우는 중이에요 · $trips/${learning.target}회", style = MaterialTheme.typography.headlineSmall, color = c.text)
        }
        Spacer(Modifier.height(6.dp))
        ProgressBar(trips.toFloat() / learning.target)
        Text("평소 운전을 다 배우면 그때부터 당신 기준으로 도와드려요.", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
    }
}

@Composable
private fun LastTripCard(item: TripItem?, onOpenTrip: (Long) -> Unit) {
    val c = CopilotTheme.colors
    if (item == null) {
        CopilotCard(Modifier.fillMaxWidth()) {
            CardLabel("지난 주행")
            Text("아직 기록이 없어요. 출발하면 알아서 시작해요.", style = MaterialTheme.typography.bodyLarge, color = c.textSecondary)
        }
        return
    }
    val zone = ZoneId.systemDefault()
    CopilotCard(Modifier.fillMaxWidth(), onClick = { onOpenTrip(item.trip.id) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CardLabel(
                "지난 주행 · ${TripSummarizer.dateText(item.trip.startMs, zone)} · ${TripSummarizer.durationText(item.trip.durationMs)}",
            )
            Spacer(Modifier.weight(1f))
            LineIcon(Icon.CHEVRON, c.textMuted, size = 28.dp)
        }
        Text(item.headline, style = MaterialTheme.typography.headlineSmall, color = c.text)
        Text(TripSummarizer.interventionSummary(item.interventions), style = MaterialTheme.typography.bodyLarge, color = c.textSecondary)
    }
}

@Composable
private fun BriefingCard(briefing: BriefingUi, onListen: () -> Unit, canListen: Boolean) {
    val c = CopilotTheme.colors
    CopilotCard(Modifier.fillMaxWidth()) {
        CardLabel("코스 브리핑")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(briefing.summary, style = MaterialTheme.typography.titleLarge, color = c.text, modifier = Modifier.weight(1f))
            if (canListen) {
                Spacer(Modifier.width(16.dp))
                SecondaryButton("듣기", onListen)
            }
        }
    }
}

@Composable
private fun CooldownCard(ui: HomeUi, onSettings: () -> Unit) {
    val c = CopilotTheme.colors
    CopilotCard(Modifier.fillMaxWidth(), onClick = onSettings) {
        CardLabel("쿨다운 캐빈")
        when {
            ui.climateAvailable == false -> {
                StatusText(false, offText = "차량 권한이 필요해요")
                Text("공조를 조절할 수 없어서 쉬고 있어요.", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
            }
            else -> {
                StatusText(ui.settings.cooldownEnabled)
                if (ui.settings.cooldownEnabled) {
                    Text(ComfortDescriber.currentMode(ui.comfort, ui.season), style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                }
            }
        }
    }
}
