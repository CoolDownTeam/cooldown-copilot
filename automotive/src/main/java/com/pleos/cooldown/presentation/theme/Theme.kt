package com.pleos.cooldown.presentation.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 화면 명세 8절: 다크 기본 + 주야간 자동, 강조색은 쿨다운 청록·피로 호박 두 개뿐이고 나머지는 무채색.
 */
@Immutable
data class CopilotColors(
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val outline: Color,
    val text: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val cool: Color,
    val coolSoft: Color,
    val warm: Color,
    val warmSoft: Color,
    val onAccent: Color,
)

private val Night = CopilotColors(
    background = Color(0xFF0D1013),
    surface = Color(0xFF161A1F),
    surfaceRaised = Color(0xFF1E242A),
    outline = Color(0xFF2A3138),
    text = Color(0xFFF1F3F4),
    textSecondary = Color(0xFFA9B1B8),
    textMuted = Color(0xFF6E7780),
    cool = Color(0xFF45D1C8),
    coolSoft = Color(0xFF123230),
    warm = Color(0xFFF0B04A),
    warmSoft = Color(0xFF3A2B12),
    onAccent = Color(0xFF071312),
)

private val Day = CopilotColors(
    background = Color(0xFFF3F5F6),
    surface = Color(0xFFFFFFFF),
    surfaceRaised = Color(0xFFE9EDEF),
    outline = Color(0xFFD5DBDF),
    text = Color(0xFF12171B),
    textSecondary = Color(0xFF4D5760),
    textMuted = Color(0xFF7D868E),
    cool = Color(0xFF0F8F88),
    coolSoft = Color(0xFFD5F0EE),
    warm = Color(0xFFB7740B),
    warmSoft = Color(0xFFF7E7CB),
    onAccent = Color(0xFFFFFFFF),
)

val LocalCopilotColors = staticCompositionLocalOf { Night }

/** 차량 화면 거리 기준: 제목 28sp 이상, 본문 20sp 이상 (화면 명세 8절) */
private val CopilotTypography = Typography(
    displaySmall = TextStyle(fontSize = 40.sp, lineHeight = 50.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = TextStyle(fontSize = 32.sp, lineHeight = 42.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 22.sp, lineHeight = 32.sp),
    bodyMedium = TextStyle(fontSize = 20.sp, lineHeight = 28.sp),
    labelLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium),
)

object CopilotTheme {
    val colors: CopilotColors
        @Composable get() = LocalCopilotColors.current
}

@Composable
fun CooldownTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (dark) Night else Day
    val scheme = if (dark) {
        darkColorScheme(
            primary = c.cool, onPrimary = c.onAccent, background = c.background, onBackground = c.text,
            surface = c.surface, onSurface = c.text, surfaceVariant = c.surfaceRaised, outline = c.outline,
        )
    } else {
        lightColorScheme(
            primary = c.cool, onPrimary = c.onAccent, background = c.background, onBackground = c.text,
            surface = c.surface, onSurface = c.text, surfaceVariant = c.surfaceRaised, outline = c.outline,
        )
    }
    CompositionLocalProvider(LocalCopilotColors provides c) {
        MaterialTheme(colorScheme = scheme, typography = CopilotTypography, content = content)
    }
}
