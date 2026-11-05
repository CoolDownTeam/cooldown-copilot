package com.pleos.cooldown.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pleos.cooldown.presentation.theme.CopilotTheme

/** 최소 터치 영역 (화면 명세 8절) */
val TouchMin = 76.dp
val ScreenPadding = 40.dp
private val CardShape = RoundedCornerShape(24.dp)
private val ButtonShape = RoundedCornerShape(20.dp)

@Composable
fun CopilotCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = CopilotTheme.colors
    Column(
        modifier
            .clip(CardShape)
            .background(c.surface)
            .border(1.dp, c.outline, CardShape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/** 카드 위쪽 작은 제목 */
@Composable
fun CardLabel(text: String, color: Color = CopilotTheme.colors.textSecondary) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color, fontWeight = FontWeight.Medium)
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = CopilotTheme.colors
    Box(
        modifier
            .defaultMinSize(minWidth = 200.dp, minHeight = TouchMin)
            .clip(ButtonShape)
            .background(c.cool)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 36.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = c.onAccent, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = CopilotTheme.colors.text) {
    val c = CopilotTheme.colors
    Box(
        modifier
            .defaultMinSize(minWidth = 200.dp, minHeight = TouchMin)
            .clip(ButtonShape)
            .border(1.5.dp, c.outline, ButtonShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 36.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = color)
    }
}

@Composable
fun IconButton(icon: Icon, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = CopilotTheme.colors
    Box(
        modifier
            .size(TouchMin)
            .clip(CircleShape)
            .background(c.surface)
            .border(1.dp, c.outline, CircleShape)
            .clickable(role = Role.Button, onClickLabel = contentDescription, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        LineIcon(icon, c.text)
    }
}

/** 화면 위쪽 막대: 뒤로 + 제목 + 오른쪽 버튼들 */
@Composable
fun TopBar(title: String, onBack: (() -> Unit)?, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            IconButton(Icon.BACK, "뒤로", onBack)
            Spacer(Modifier.width(24.dp))
        }
        Text(title, style = MaterialTheme.typography.headlineMedium, color = CopilotTheme.colors.text, modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), content = actions)
    }
}

@Composable
fun StepDots(count: Int, current: Int) {
    val c = CopilotTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(count) { i ->
            Box(
                Modifier
                    .size(if (i == current) 14.dp else 12.dp)
                    .clip(CircleShape)
                    .background(if (i == current) c.cool else c.outline)
            )
        }
    }
}

/** 설정 스위치 한 줄. 줄 전체가 터치 영역이다 */
@Composable
fun SwitchRow(title: String, description: String?, checked: Boolean, onChange: (Boolean) -> Unit, accent: Color = CopilotTheme.colors.cool) {
    val c = CopilotTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = TouchMin)
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Switch) { onChange(!checked) }
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = c.text)
            if (description != null) Text(description, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
        }
        Spacer(Modifier.width(24.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedTrackColor = accent, checkedThumbColor = c.onAccent),
        )
    }
}

/** 상태 점과 글자: "● 켜짐" */
@Composable
fun StatusText(on: Boolean, onText: String = "켜짐", offText: String = "꺼짐", accent: Color = CopilotTheme.colors.cool) {
    val c = CopilotTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (on) accent else c.textMuted))
        Text(if (on) onText else offText, style = MaterialTheme.typography.titleLarge, color = if (on) c.text else c.textSecondary)
    }
}

@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier, color: Color = CopilotTheme.colors.cool) {
    val c = CopilotTheme.colors
    Box(modifier.fillMaxWidth().height(10.dp).clip(CircleShape).background(c.surfaceRaised)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(10.dp).clip(CircleShape).background(color))
    }
}

/** 이 너비보다 넓으면 두 열, 좁으면 한 열로 쌓는다 (Pleos 분할 화면의 앱 패널은 약 840dp) */
val TwoPaneMinWidth = 1000.dp

/**
 * 화면 명세의 "왼쪽·오른쪽" 배치. 창이 좁으면 위아래로 쌓고 전체를 스크롤한다.
 */
@Composable
fun TwoPane(
    modifier: Modifier = Modifier,
    primaryWeight: Float = 1.5f,
    primary: @Composable ColumnScope.() -> Unit,
    secondary: @Composable ColumnScope.() -> Unit,
) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxWidth()) {
        val spacing = Arrangement.spacedBy(24.dp)
        if (maxWidth >= TwoPaneMinWidth) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = spacing) {
                Column(
                    Modifier.weight(primaryWeight).verticalScroll(rememberScrollState()),
                    verticalArrangement = spacing,
                    content = primary,
                )
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = spacing, content = secondary)
            }
        } else {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = spacing) {
                primary()
                secondary()
            }
        }
    }
}
