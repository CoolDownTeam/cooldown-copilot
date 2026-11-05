package com.pleos.cooldown.presentation.driving

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pleos.cooldown.presentation.components.Icon
import com.pleos.cooldown.presentation.components.LineIcon
import com.pleos.cooldown.presentation.theme.CopilotTheme

/**
 * SC-03 주행 중 화면 (화면 명세 5절). 아이콘 하나와 문장 한 줄. 버튼·숫자·스크롤이 없고 터치를 받지 않는다 (SAFE-01).
 * 상태가 바뀔 때만 0.3초 페이드한다.
 */
@Composable
fun DrivingScreen(status: DrivingStatus) {
    val c = CopilotTheme.colors
    Crossfade(targetState = status, animationSpec = tween(300), label = "driving-status") { s ->
        val (icon, accent, soft) = when (s) {
            DrivingStatus.CALM -> Triple(Icon.LEAF, c.textSecondary, c.surface)
            DrivingStatus.LEARNING -> Triple(Icon.SPARKLE, c.textSecondary, c.surface)
            DrivingStatus.COOLING -> Triple(Icon.SNOWFLAKE, c.cool, c.coolSoft)
            DrivingStatus.FRESH_AIR -> Triple(Icon.WIND, c.warm, c.warmSoft)
            DrivingStatus.REST -> Triple(Icon.COFFEE, c.warm, c.warmSoft)
        }
        Box(Modifier.fillMaxSize().background(c.background), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(36.dp)) {
                Box(Modifier.size(168.dp).clip(CircleShape).background(soft), contentAlignment = Alignment.Center) {
                    LineIcon(icon, accent, size = 80.dp)
                }
                Text(
                    s.text,
                    style = MaterialTheme.typography.displaySmall,
                    color = c.text,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}
