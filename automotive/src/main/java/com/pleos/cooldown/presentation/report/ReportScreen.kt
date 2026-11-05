package com.pleos.cooldown.presentation.report

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.pleos.cooldown.presentation.ReportUi
import com.pleos.cooldown.presentation.components.CardLabel
import com.pleos.cooldown.presentation.components.CopilotCard
import com.pleos.cooldown.presentation.components.PrimaryButton
import com.pleos.cooldown.presentation.components.ScreenPadding
import com.pleos.cooldown.presentation.components.TopBar
import com.pleos.cooldown.presentation.components.TwoPane
import com.pleos.cooldown.presentation.theme.CopilotTheme

/**
 * SC-04 주행 리포트 (화면 명세 4절). 왼쪽 결과, 오른쪽 추세, 오른쪽 아래 닫기.
 */
@Composable
fun ReportScreen(ui: ReportUi?, onClose: () -> Unit) {
    val c = CopilotTheme.colors
    Column(Modifier.fillMaxSize().padding(ScreenPadding)) {
        TopBar(ui?.dateText ?: "주행 리포트", onBack = onClose)
        Spacer(Modifier.height(28.dp))
        if (ui == null) {
            Text("불러오는 중이에요", style = MaterialTheme.typography.bodyLarge, color = c.textSecondary)
            return
        }
        TwoPane(
            primaryWeight = 1.4f,
            primary = {
                Text(ui.headline, style = MaterialTheme.typography.displaySmall, color = c.text)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Stat("주행 시간", ui.duration, Modifier.weight(1f))
                    Stat("도와드린 횟수", "${ui.helpCount}번", Modifier.weight(1f))
                    Stat(ui.thirdLabel, ui.thirdValue, Modifier.weight(1f))
                }
                CopilotCard(Modifier.fillMaxWidth()) {
                    CardLabel("이번 주행에서")
                    if (ui.timeline.isEmpty()) {
                        Text("도와드릴 일 없이 평온했어요", style = MaterialTheme.typography.bodyLarge, color = c.text)
                    }
                    ui.timeline.forEach { line ->
                        Row(verticalAlignment = Alignment.Top) {
                            Box(Modifier.padding(top = 12.dp).size(8.dp).clip(CircleShape).background(c.cool))
                            Spacer(Modifier.size(16.dp))
                            Text(line, style = MaterialTheme.typography.bodyLarge, color = c.text)
                        }
                    }
                }
            },
            secondary = {
                CopilotCard(Modifier.fillMaxWidth()) {
                    CardLabel("최근 주행")
                    TrendBars("거친 정도", ui.aggressionTrend, ui.highlight, c.cool)
                    Spacer(Modifier.height(8.dp))
                    TrendBars("피로", ui.fatigueTrend, ui.highlight, c.warm)
                }
                ui.learningText?.let {
                    Text(it, style = MaterialTheme.typography.bodyLarge, color = c.textSecondary)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    PrimaryButton("닫기", onClose)
                }
            },
        )
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    val c = CopilotTheme.colors
    CopilotCard(modifier) {
        CardLabel(label)
        Text(value, style = MaterialTheme.typography.headlineSmall, color = c.text)
    }
}

/** 축·숫자 없는 막대. 이번 주행만 강조색 (화면 명세 4절) */
@Composable
private fun TrendBars(label: String, values: List<Int>, highlight: Int, accent: Color) {
    val c = CopilotTheme.colors
    Text(label, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
    Row(
        Modifier.fillMaxWidth().height(96.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        values.forEachIndexed { i, v ->
            val fraction = (v.coerceIn(0, 100) / 100f).coerceAtLeast(0.06f)
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(fraction)
                    .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                    .background(if (i == highlight) accent else c.surfaceRaised)
            )
        }
        repeat((7 - values.size).coerceAtLeast(0)) { Spacer(Modifier.weight(1f)) }
    }
}
