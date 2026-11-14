package com.offline.stt

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

// ---------- Themes ----------
data class Th(
    val name: String, val dark: Boolean, val bg: Brush, val fg: Color, val sub: Color,
    val card: Color, val border: Color, val a: Color, val b: Color, val onA: Color,
    val orb: List<Color>, val dots: Boolean = false,
)

val THEMES = listOf(
    Th("Aurora", true, Brush.verticalGradient(listOf(Color(0xFF03020A), Color(0xFF0E0624), Color(0xFF220C52))),
        Color.White, Color(0xFFA9A3C2), Color(0x1AFFFFFF), Color(0x26FFFFFF), Color(0xFF7C5CFF), Color(0xFFE040FB), Color.White,
        listOf(Color(0xFF3B2BFF), Color(0xFFB026FF), Color(0xFFFF2BD6), Color(0xFF2BA4FF))),
    Th("Pearl", false, Brush.linearGradient(listOf(Color(0xFFF6C4D2), Color(0xFFC3D6F6), Color(0xFFF6F4FA))),
        Color(0xFF1E1B2E), Color(0xFF6B6985), Color(0x99FFFFFF), Color(0x80FFFFFF), Color(0xFF6C7BFF), Color(0xFFB07CFF), Color.White,
        listOf(Color(0xFF8E7BFF), Color(0xFF6EA8FF), Color(0xFFE59BE0), Color(0xFF8FD3FF))),
    Th("Lime", false, Brush.verticalGradient(listOf(Color(0xFFEFF5E8), Color(0xFFD3E6C0))),
        Color(0xFF1B2A1B), Color(0xFF5F7060), Color(0xCCFFFFFF), Color(0x80FFFFFF), Color(0xFF8EE000), Color(0xFF4CD964), Color(0xFF10260F),
        listOf(Color(0xFFD7FF8A), Color(0xFF8EE000), Color(0xFF4CD964), Color(0xFFEFFFC8))),
    Th("Sunset", false, Brush.verticalGradient(listOf(Color(0xFFFFF7F0), Color(0xFFF8D6BC))),
        Color(0xFF2B1A10), Color(0xFF8A6D5A), Color(0xB3FFFFFF), Color(0x80FFFFFF), Color(0xFFFF7A1A), Color(0xFFFFA36B), Color.White,
        listOf(Color(0xFFFF7A1A), Color(0xFFFFB27A), Color(0xFFFFD2B0), Color(0xFFFF9A4D))),
    Th("Mono", false, SolidColor(Color(0xFFEDEDED)),
        Color(0xFF111111), Color(0xFF777777), Color.White, Color(0x14000000), Color(0xFF111111), Color(0xFF3A3A3A), Color.White,
        listOf(Color.Black, Color.DarkGray, Color.Gray, Color.Black), dots = true),
)

fun Modifier.glass(th: Th, radius: Dp = 24.dp): Modifier =
    this.clip(RoundedCornerShape(radius)).background(th.card).border(1.dp, th.border, RoundedCornerShape(radius))

fun mmss(s: Int) = "%02d:%02d".format(s / 60, s % 60)

// ---------- Icons (Material-style paths, no extra dependency) ----------
object Ic {
    private fun build(d: String) = ImageVector.Builder("i", 24.dp, 24.dp, 24f, 24f)
        .addPath(PathParser().parsePathString(d).toNodes(), fill = SolidColor(Color.Black)).build()
    private fun v(d: String): ImageVector = try { build(d) } catch (e: Exception) { build("M4 4h16v16H4z") }

    val Mic by lazy { v("M12 14c1.66 0 2.99-1.34 2.99-3L15 5c0-1.66-1.34-3-3-3S9 3.34 9 5v6c0 1.66 1.34 3 3 3zm5.3-3c0 3-2.54 5.1-5.3 5.1S6.7 14 6.7 11H5c0 3.41 2.72 6.23 6 6.72V21h2v-3.28c3.28-.48 6-3.3 6-6.72h-1.7z") }
    val Stop by lazy { v("M6 6h12v12H6z") }
    val Copy by lazy { v("M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 16H8V7h11v14z") }
    val Share by lazy { v("M18 16.08c-.76 0-1.44.3-1.96.77L8.91 12.7c.05-.23.09-.46.09-.7s-.04-.47-.09-.7l7.05-4.11c.54.5 1.25.81 2.04.81 1.66 0 3-1.34 3-3s-1.34-3-3-3-3 1.34-3 3c0 .24.04.47.09.7L8.04 9.81C7.5 9.31 6.79 9 6 9c-1.66 0-3 1.34-3 3s1.34 3 3 3c.79 0 1.5-.31 2.04-.81l7.12 4.16c-.05.21-.08.43-.08.65 0 1.61 1.31 2.92 2.92 2.92s2.92-1.31 2.92-2.92-1.31-2.92-2.92-2.92z") }
    val Delete by lazy { v("M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z") }
    val List by lazy { v("M3 13h2v-2H3v2zm0 4h2v-2H3v2zm0-8h2V7H3v2zm4 4h14v-2H7v2zm0 4h14v-2H7v2zM7 7v2h14V7H7z") }
    val Tune by lazy { v("M3 17v2h6v-2H3zM3 5v2h10V5H3zm10 16v-2h8v-2h-8v-2h-2v6h2zM7 9v2H3v2h4v2h2V9H7zm14 4v-2H11v2h10zm-6-4h2V7h4V5h-4V3h-2v6z") }
    val File by lazy { v("M14 2H6c-1.1 0-1.99.9-1.99 2L4 20c0 1.1.89 2 1.99 2H18c1.1 0 2-.9 2-2V8l-6-6zm4 18H6V4h7v5h5v11z") }
    val Palette by lazy { v("M12 3c-4.97 0-9 4.03-9 9s4.03 9 9 9c.83 0 1.5-.67 1.5-1.5 0-.39-.15-.74-.39-1.01-.23-.26-.38-.61-.38-.99 0-.83.67-1.5 1.5-1.5H16c2.76 0 5-2.24 5-5 0-4.42-4.03-8-9-8zm-5.5 9c-.83 0-1.5-.67-1.5-1.5S5.67 9 6.5 9 8 9.67 8 10.5 7.33 12 6.5 12zm3-4C8.67 8 8 7.33 8 6.5S8.67 5 9.5 5s1.5.67 1.5 1.5S10.33 8 9.5 8zm5 0c-.83 0-1.5-.67-1.5-1.5S13.67 5 14.5 5s1.5.67 1.5 1.5S15.33 8 14.5 8zm3 4c-.83 0-1.5-.67-1.5-1.5S16.67 9 17.5 9s1.5.67 1.5 1.5-.67 1.5-1.5 1.5z") }
    val Search by lazy { v("M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3S3 5.91 3 9.5 5.91 16 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z") }
    val Save by lazy { v("M17 3H5c-1.11 0-2 .9-2 2v14c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2V7l-4-4zm-5 16c-1.66 0-3-1.34-3-3s1.34-3 3-3 3 1.34 3 3-1.34 3-3 3zm3-10H5V5h10v4z") }
    val Export by lazy { v("M19 12v7H5v-7H3v7c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2v-7h-2zm-6 .67l2.59-2.58L17 11.5l-5 5-5-5 1.41-1.41L11 12.67V3h2z") }
    val Add by lazy { v("M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z") }
    val Download by lazy { v("M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z") }
    val Lock by lazy { v("M18 8h-1V6c0-2.76-2.24-5-5-5S7 3.24 7 6v2H6c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V10c0-1.1-.9-2-2-2zm-6 9c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2zm3.1-9H8.9V6c0-1.71 1.39-3.1 3.1-3.1 1.71 0 3.1 1.39 3.1 3.1v2z") }
    val Check by lazy { v("M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z") }
    val Close by lazy { v("M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z") }
}

// ---------- Orb: glassy animated sphere (dotted halftone on Mono) ----------
@Composable
fun Orb(th: Th, dp: Dp, level: Float, active: Boolean, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "orb")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(14000, easing = LinearEasing)), label = "spin")
    val pulse by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "pulse")
    val lv by animateFloatAsState(level, label = "lv")
    Canvas(modifier.size(dp)) {
        val c = center
        val r = this.size.minDimension / 2f * 0.62f * (1f + lv * 0.12f)
        if (active) for (i in 0..2) {
            val p = (pulse + i / 3f) % 1f
            drawCircle(color = th.a.copy(alpha = (1f - p) * 0.4f), radius = r * (1f + p * 0.6f), center = c, style = Stroke(width = 3f))
        }
        if (th.dots) {
            val step = r / 8f
            var yy = -r
            while (yy <= r) {
                var xx = -r
                while (xx <= r) {
                    val d = hypot(xx, yy) / r
                    if (d < 1f) {
                        val w = 0.85f + 0.15f * sin(spin * 0.0349f + (xx + yy) / r * 3f)
                        val rad = step * 0.5f * (1f - d).pow(0.6f) * w * (1f + lv * 0.5f)
                        drawCircle(th.fg, rad.coerceAtLeast(0.6f), Offset(c.x + xx, c.y + yy))
                    }
                    xx += step
                }
                yy += step
            }
        } else {
            val o = th.orb
            drawCircle(Brush.radialGradient(listOf(o[1].copy(alpha = 0.45f), Color.Transparent), center = c, radius = r * 1.7f), radius = r * 1.7f, center = c)
            rotate(spin, pivot = c) {
                drawCircle(Brush.sweepGradient(listOf(o[0], o[1], o[2], o[3], o[0]), center = c), radius = r, center = c)
            }
            rotate(-spin * 1.6f, pivot = c) {
                drawCircle(Brush.sweepGradient(listOf(o[2].copy(alpha = 0f), o[3].copy(alpha = 0.8f), o[1].copy(alpha = 0f), o[0].copy(alpha = 0.7f), o[2].copy(alpha = 0f)), center = c), radius = r * 0.96f, center = c)
            }
            drawCircle(Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(alpha = if (th.dark) 0.55f else 0.18f)), center = c, radius = r), radius = r, center = c)
            drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.75f), Color.Transparent), center = Offset(c.x - r * 0.35f, c.y - r * 0.4f), radius = r * 0.75f), radius = r, center = c)
            drawCircle(color = Color.White.copy(alpha = 0.35f), radius = r, center = c, style = Stroke(width = 2f))
        }
    }
}

@Composable
fun Wave(th: Th, data: FloatArray, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val n = data.size
        val gap = size.width / n
        for (i in 0 until n) {
            val h = size.height * (0.08f + 0.92f * data[i].coerceIn(0f, 1f))
            val x = gap * i + gap / 2
            drawLine(
                color = lerp(th.a, th.b, i / n.toFloat()).copy(alpha = 0.35f + 0.65f * i / n),
                start = Offset(x, (size.height - h) / 2), end = Offset(x, (size.height + h) / 2),
                strokeWidth = gap * 0.55f, cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
fun IconBtn(icon: ImageVector, th: Th, dp: Dp = 44.dp, enabled: Boolean = true, filled: Boolean = false, onClick: () -> Unit) {
    val m = Modifier.size(dp).clip(CircleShape)
    Box(
        (if (filled) m.background(Brush.linearGradient(listOf(th.a, th.b))) else m.background(th.card).border(1.dp, th.border, CircleShape))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = (if (filled) th.onA else th.fg).copy(alpha = if (enabled) 1f else 0.35f), modifier = Modifier.size(dp * 0.48f)) }
}

@Composable
fun NavPill(th: Th, tab: Int, onTab: (Int) -> Unit) {
    val items = listOf(Ic.Mic to "Dictate", Ic.List to "Library", Ic.Tune to "Settings")
    Row(Modifier.glass(th, 40.dp).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEachIndexed { i, (ic, label) ->
            val sel = i == tab
            Row(
                Modifier.clip(RoundedCornerShape(50))
                    .then(if (sel) Modifier.background(Brush.linearGradient(listOf(th.a, th.b))) else Modifier)
                    .clickable { onTab(i) }.padding(horizontal = if (sel) 18.dp else 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(ic, null, tint = if (sel) th.onA else th.sub, modifier = Modifier.size(22.dp))
                if (sel) { Spacer(Modifier.width(8.dp)); Text(label, color = th.onA, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
            }
        }
    }
}
