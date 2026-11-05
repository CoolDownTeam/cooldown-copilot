package com.pleos.cooldown.presentation.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.pleos.cooldown.presentation.components.Icon
import com.pleos.cooldown.presentation.components.LineIcon
import com.pleos.cooldown.presentation.components.PrimaryButton
import com.pleos.cooldown.presentation.components.ScreenPadding
import com.pleos.cooldown.presentation.components.SecondaryButton
import com.pleos.cooldown.presentation.components.StepDots
import com.pleos.cooldown.presentation.theme.CopilotTheme

private data class Step(val icon: Icon, val title: String, val body: String)

private val steps = listOf(
    Step(Icon.SNOWFLAKE, "평소보다 거칠어지면 차가 먼저 식혀 드려요", "당신의 평소 운전을 배워서, 그보다 거칠어질 때만 공조를 살짝 조절해요."),
    Step(Icon.WIND, "공조를 자동으로 조절해도 될까요?", "온도는 최대 2℃, 바람은 한 단계까지만. 직접 만지시면 바로 손을 뗄게요."),
    Step(Icon.SPARKLE, "처음 5번은 배우는 중이에요", "그동안은 아주 거친 운전에만 반응해요. 춥다면 \"추워\" 또는 \"글레오, 너무 추워\"라고 말해 주세요."),
)

/**
 * SC-01 온보딩 3단계 (화면 명세 2절, S1-01). 단계는 화면 회전·주행 전환 뒤에도 이어지게 저장한다.
 */
@Composable
fun OnboardingScreen(onConsent: (allow: Boolean) -> Unit, onFinish: () -> Unit) {
    val c = CopilotTheme.colors
    var index by rememberSaveable { mutableIntStateOf(0) }
    val step = steps[index]

    Box(Modifier.fillMaxSize().padding(ScreenPadding), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 860.dp), horizontalAlignment = Alignment.Start) {
            Box(Modifier.size(96.dp).clip(CircleShape).background(c.coolSoft), contentAlignment = Alignment.Center) {
                LineIcon(step.icon, c.cool, size = 48.dp)
            }
            Spacer(Modifier.height(40.dp))
            Text(step.title, style = MaterialTheme.typography.displaySmall, color = c.text)
            Spacer(Modifier.height(20.dp))
            Text(step.body, style = MaterialTheme.typography.bodyLarge, color = c.textSecondary)
            Spacer(Modifier.height(56.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StepDots(steps.size, index)
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    when (index) {
                        0 -> PrimaryButton("다음", { index = 1 })
                        1 -> {
                            SecondaryButton("나중에", { onConsent(false); index = 2 })
                            PrimaryButton("허용", { onConsent(true); index = 2 })
                        }
                        else -> PrimaryButton("시작하기", onFinish)
                    }
                }
            }
        }
    }
}
