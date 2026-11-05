package com.pleos.cooldown.presentation.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.pleos.cooldown.domain.report.InterventionKind
import com.pleos.cooldown.domain.report.TripSummarizer
import com.pleos.cooldown.presentation.TripItem
import com.pleos.cooldown.presentation.components.Icon
import com.pleos.cooldown.presentation.components.LineIcon
import com.pleos.cooldown.presentation.components.PrimaryButton
import com.pleos.cooldown.presentation.components.ScreenPadding
import com.pleos.cooldown.presentation.components.SecondaryButton
import com.pleos.cooldown.presentation.components.TopBar
import com.pleos.cooldown.presentation.components.TouchMin
import com.pleos.cooldown.presentation.theme.CopilotTheme
import java.time.ZoneId

/** SC-05 기록 (화면 명세 6절). 최근 30회, 누르면 리포트 */
@Composable
fun HistoryScreen(trips: List<TripItem>, onOpen: (Long) -> Unit, onClearAll: () -> Unit, onBack: () -> Unit) {
    val c = CopilotTheme.colors
    val zone = ZoneId.systemDefault()
    var confirm by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(ScreenPadding)) {
        TopBar("기록", onBack)
        Spacer(Modifier.height(24.dp))
        if (trips.isEmpty()) {
            Text("아직 기록이 없어요.", style = MaterialTheme.typography.bodyLarge, color = c.textSecondary)
            return
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(trips, key = { it.trip.id }) { item ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = TouchMin + 16.dp)
                        .clickable(role = Role.Button) { onOpen(item.trip.id) }
                        .padding(vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.width(260.dp)) {
                        Text(TripSummarizer.dateText(item.trip.startMs, zone), style = MaterialTheme.typography.titleLarge, color = c.text)
                        Text(TripSummarizer.durationText(item.trip.durationMs), style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                    }
                    Text(item.headline, style = MaterialTheme.typography.titleLarge, color = c.text, modifier = Modifier.weight(1f))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        kindsOf(item).forEach { (icon, color) -> LineIcon(icon, color, size = 28.dp) }
                    }
                    Spacer(Modifier.width(16.dp))
                    LineIcon(Icon.CHEVRON, c.textMuted, size = 28.dp)
                }
                HorizontalDivider(color = c.outline)
            }
            item {
                Spacer(Modifier.height(24.dp))
                SecondaryButton("기록 전체 삭제", { confirm = true }, color = c.textSecondary)
            }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("기록을 모두 지울까요?", style = MaterialTheme.typography.headlineSmall) },
            text = { Text("주행 리포트가 모두 지워져요. 배운 운전 습관은 그대로예요.", style = MaterialTheme.typography.bodyLarge) },
            confirmButton = { PrimaryButton("지우기", { confirm = false; onClearAll() }) },
            dismissButton = { SecondaryButton("취소", { confirm = false }) },
            containerColor = c.surface,
        )
    }
}

@Composable
private fun kindsOf(item: TripItem) = buildList {
    val c = CopilotTheme.colors
    val kinds = item.interventions.map { it.kind }.toSet()
    if (InterventionKind.COOLDOWN in kinds) add(Icon.SNOWFLAKE to c.cool)
    if (InterventionKind.FRESH_AIR in kinds || InterventionKind.FATIGUE_HOLD_ON in kinds) add(Icon.WIND to c.warm)
    if (InterventionKind.FATIGUE_SUGGEST in kinds) add(Icon.COFFEE to c.warm)
}
