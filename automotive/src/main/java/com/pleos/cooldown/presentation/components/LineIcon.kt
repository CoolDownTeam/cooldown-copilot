package com.pleos.cooldown.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** 선 아이콘 한 종류로 통일한다 (화면 명세 8절). 24×24 격자에 그린다 */
enum class Icon { LEAF, SPARKLE, SNOWFLAKE, WIND, COFFEE, HISTORY, SETTINGS, BACK, PLAY, CHEVRON, CLOSE }

@Composable
fun LineIcon(icon: Icon, tint: Color, modifier: Modifier = Modifier, size: Dp = 32.dp) {
    Canvas(modifier.size(size)) {
        val u = this.size.minDimension / 24f
        val stroke = Stroke(width = 1.8f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(x: Float, y: Float) = Offset(x * u, y * u)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(tint, p(x1, y1), p(x2, y2), strokeWidth = stroke.width, cap = StrokeCap.Round)
        fun path(block: Path.() -> Unit) = drawPath(Path().apply(block), tint, style = stroke)

        when (icon) {
            Icon.LEAF -> {
                path {
                    moveTo(5f * u, 19f * u)
                    cubicTo(4f * u, 10f * u, 10f * u, 4f * u, 20f * u, 4f * u)
                    cubicTo(20f * u, 14f * u, 14f * u, 20f * u, 5f * u, 19f * u)
                }
                line(5f, 19f, 13f, 11f)
            }
            Icon.SPARKLE -> {
                path {
                    moveTo(12f * u, 3f * u)
                    quadraticBezierTo(13f * u, 11f * u, 21f * u, 12f * u)
                    quadraticBezierTo(13f * u, 13f * u, 12f * u, 21f * u)
                    quadraticBezierTo(11f * u, 13f * u, 3f * u, 12f * u)
                    quadraticBezierTo(11f * u, 11f * u, 12f * u, 3f * u)
                    close()
                }
            }
            Icon.SNOWFLAKE -> repeat(3) { i ->
                val a = i * PI / 3
                val dx = (9 * cos(a)).toFloat()
                val dy = (9 * sin(a)).toFloat()
                line(12f - dx, 12f - dy, 12f + dx, 12f + dy)
                listOf(1f, -1f).forEach { s ->
                    val ex = 12f + s * dx * 0.7f
                    val ey = 12f + s * dy * 0.7f
                    val b1 = a + s * PI * 0.75
                    val b2 = a - s * PI * 0.75
                    line(ex, ey, ex + (2.6 * cos(b1)).toFloat() * s, ey + (2.6 * sin(b1)).toFloat() * s)
                    line(ex, ey, ex + (2.6 * cos(b2)).toFloat() * s, ey + (2.6 * sin(b2)).toFloat() * s)
                }
            }
            Icon.WIND -> {
                path {
                    moveTo(3f * u, 9f * u); lineTo(15f * u, 9f * u)
                    cubicTo(19f * u, 9f * u, 19f * u, 4f * u, 15.5f * u, 4.5f * u)
                }
                path {
                    moveTo(3f * u, 14f * u); lineTo(18f * u, 14f * u)
                    cubicTo(22f * u, 14f * u, 22f * u, 19.5f * u, 18f * u, 19f * u)
                }
                line(3f, 19f, 11f, 19f)
            }
            Icon.COFFEE -> {
                path {
                    moveTo(4f * u, 9f * u); lineTo(16f * u, 9f * u); lineTo(16f * u, 15f * u)
                    cubicTo(16f * u, 18f * u, 14f * u, 20f * u, 11f * u, 20f * u)
                    lineTo(9f * u, 20f * u)
                    cubicTo(6f * u, 20f * u, 4f * u, 18f * u, 4f * u, 15f * u)
                    close()
                }
                path {
                    moveTo(16f * u, 10.5f * u)
                    cubicTo(20.5f * u, 10.5f * u, 20.5f * u, 15.5f * u, 16f * u, 15.5f * u)
                }
                line(8f, 3.5f, 8f, 6f)
                line(12f, 3.5f, 12f, 6f)
            }
            Icon.HISTORY -> {
                drawCircle(tint, 9f * u, p(12f, 12f), style = stroke)
                line(12f, 7.5f, 12f, 12f)
                line(12f, 12f, 15.5f, 14f)
            }
            Icon.SETTINGS -> {
                drawCircle(tint, 3f * u, p(12f, 12f), style = stroke)
                drawCircle(tint, 7f * u, p(12f, 12f), style = stroke)
                repeat(8) { i ->
                    val a = i * PI / 4
                    line(12f + (7 * cos(a)).toFloat(), 12f + (7 * sin(a)).toFloat(), 12f + (9.5 * cos(a)).toFloat(), 12f + (9.5 * sin(a)).toFloat())
                }
            }
            Icon.BACK -> {
                line(19f, 12f, 5f, 12f)
                path { moveTo(11f * u, 6f * u); lineTo(5f * u, 12f * u); lineTo(11f * u, 18f * u) }
            }
            Icon.PLAY -> path {
                moveTo(8f * u, 5f * u); lineTo(19f * u, 12f * u); lineTo(8f * u, 19f * u); close()
            }
            Icon.CHEVRON -> path { moveTo(9f * u, 5f * u); lineTo(16f * u, 12f * u); lineTo(9f * u, 19f * u) }
            Icon.CLOSE -> {
                line(6f, 6f, 18f, 18f)
                line(18f, 6f, 6f, 18f)
            }
        }
    }
}
