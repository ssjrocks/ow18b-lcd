package com.ssjrocks.ow18blcd

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import kotlin.math.min

/**
 * The virtual LCD: 4 seven-segment digits, sign, decimal points, annunciators and unit, laid out on a
 * 500 x 210 canvas and scaled to fit. Mirrors desktop/ow18b_lcd/lcd.py; keep the two in step.
 */
object LcdStyle {
    const val W = 500f
    const val H = 210f
    val BG_TOP = Color(0.74f, 0.79f, 0.67f)
    val BG_BOTTOM = Color(0.64f, 0.70f, 0.57f)
    val INK = Color(0.11f, 0.15f, 0.10f)
    // The glass turns red while an alarm is on
    val ALARM_TOP = Color(0.86f, 0.66f, 0.60f)
    val ALARM_BOTTOM = Color(0.78f, 0.52f, 0.46f)
    const val GHOST = 0.07f
}

private val SEGMENTS = mapOf(
    '0' to "abcdef", '1' to "bc", '2' to "abged", '3' to "abgcd", '4' to "fgbc", '5' to "afgcd", '6' to "afgedc",
    '7' to "abc", '8' to "abcdefg", '9' to "abcdfg", '-' to "g", 'O' to "abcdef", 'L' to "fed", 'E' to "afged",
    'F' to "afge", ' ' to "",
)

private const val DIGIT_W = 60f
private const val DIGIT_H = 112f
private const val THICK = 12f
private const val SKEW = 0.08f
private const val GAP = 1.6f
private const val DIGITS_X = 74f
private const val DIGITS_Y = 58f
private const val DIGIT_STEP = 76f

private typealias Pts = List<Pair<Float, Float>>

private fun hseg(x1: Float, x2: Float, y: Float, t: Float): Pts = listOf(
    x1 to y, x1 + t / 2 to y - t / 2, x2 - t / 2 to y - t / 2, x2 to y, x2 - t / 2 to y + t / 2, x1 + t / 2 to y + t / 2,
)

private fun vseg(x: Float, y1: Float, y2: Float, t: Float): Pts = listOf(
    x to y1, x + t / 2 to y1 + t / 2, x + t / 2 to y2 - t / 2, x to y2, x - t / 2 to y2 - t / 2, x - t / 2 to y1 + t / 2,
)

private val POLYS: Map<Char, Pts> = run {
    val t = THICK; val g = GAP
    val l = t / 2; val r = DIGIT_W - t / 2; val top = t / 2; val mid = DIGIT_H / 2; val bot = DIGIT_H - t / 2
    mapOf(
        'a' to hseg(l + g, r - g, top, t), 'g' to hseg(l + g, r - g, mid, t), 'd' to hseg(l + g, r - g, bot, t),
        'f' to vseg(l, top + g, mid - g, t), 'b' to vseg(r, top + g, mid - g, t),
        'e' to vseg(l, mid + g, bot - g, t), 'c' to vseg(r, mid + g, bot - g, t),
    )
}

/** "-7.27" without its sign -> 4 (char, decimal point after it) cells, right-aligned. */
internal fun cells(text: String): List<Pair<Char, Boolean>> {
    val out = mutableListOf<Pair<Char, Boolean>>()
    for (ch in text) {
        if (ch == '.') {
            if (out.isNotEmpty()) out[out.lastIndex] = out.last().first to true
        } else out.add(ch to false)
    }
    if (text == "OL" || text == "EF") out.add(' ' to false)
    val last = out.takeLast(4)
    return List(4 - last.size) { ' ' to false } + last
}

private fun DrawScope.poly(x0: Float, y0: Float, pts: Pts, lit: Boolean) {
    val path = Path()
    pts.forEachIndexed { i, (x, y) ->
        val px = x0 + x + (DIGIT_H - y) * SKEW
        if (i == 0) path.moveTo(px, y0 + y) else path.lineTo(px, y0 + y)
    }
    path.close()
    drawPath(path, ink(lit))
}

private fun ink(lit: Boolean) = LcdStyle.INK.copy(alpha = if (lit) 1f else LcdStyle.GHOST)

private enum class Align { LEFT, RIGHT }

private fun DrawScope.label(m: TextMeasurer, x: Float, y: Float, s: String, size: Float, lit: Boolean, align: Align = Align.LEFT) {
    if (s.isEmpty()) return
    val layout = m.measure(s, TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = size.toSp()))
    val left = if (align == Align.RIGHT) x - layout.size.width else x
    drawText(layout, ink(lit), Offset(left, y - layout.firstBaseline))
}

@Composable
fun Lcd(reading: Protocol.Reading?, connected: Boolean, modifier: Modifier = Modifier, alarm: Boolean = false, recording: Boolean = false) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier.aspectRatio(LcdStyle.W / LcdStyle.H)) {
        val s = min(size.width / LcdStyle.W, size.height / LcdStyle.H)
        translate((size.width - LcdStyle.W * s) / 2, (size.height - LcdStyle.H * s) / 2) {
            scale(s, s, pivot = Offset.Zero) { drawLcd(measurer, if (connected) reading else null, connected, alarm, recording) }
        }
    }
}

private fun DrawScope.drawLcd(m: TextMeasurer, r: Protocol.Reading?, connected: Boolean, alarm: Boolean, recording: Boolean) {
    val w = LcdStyle.W; val h = LcdStyle.H
    val glass = if (alarm) listOf(LcdStyle.ALARM_TOP, LcdStyle.ALARM_BOTTOM) else listOf(LcdStyle.BG_TOP, LcdStyle.BG_BOTTOM)
    drawRoundRect(Brush.verticalGradient(glass, 0f, h), Offset.Zero, Size(w, h), CornerRadius(14f))
    drawRoundRect(Color.Black.copy(alpha = 0.35f), Offset.Zero, Size(w, h), CornerRadius(14f), style = Stroke(3f))

    label(m, 22f, 36f, "AUTO", 17f, r?.auto == true)
    label(m, 88f, 36f, "HOLD", 17f, r?.hold == true)
    label(m, 154f, 36f, "REL", 17f, r?.rel == true)
    label(m, 330f, 36f, "REC", 17f, recording)
    label(m, 400f, 36f, "BT", 17f, connected)
    // Battery
    val bat = r?.lowBattery == true
    drawRect(ink(bat), Offset(440f, 22f), Size(30f, 14f), style = Stroke(2.5f))
    drawRect(ink(bat), Offset(470f, 26f), Size(4f, 6f))
    drawRect(ink(bat), Offset(444f, 26f), Size(8f, 6f))

    val mode = r?.mode ?: ""
    label(m, 22f, 72f, "DC", 18f, mode == "DC")
    label(m, 22f, 94f, "AC", 18f, mode == "AC")

    var text = r?.digits ?: "    "
    val negative = text.startsWith("-") && text.trim('-').isNotEmpty()
    if (negative) text = text.drop(1)
    poly(DIGITS_X - 44f, DIGITS_Y, hseg(4f, 34f, DIGIT_H / 2, THICK), negative)
    cells(text).forEachIndexed { i, (ch, dp) ->
        val x0 = DIGITS_X + i * DIGIT_STEP
        val lit = SEGMENTS[ch] ?: ""
        POLYS.forEach { (name, pts) -> poly(x0, DIGITS_Y, pts, name in lit) }
        if (i < 3) drawRect(ink(dp), Offset(x0 + DIGIT_W + 4f, DIGITS_Y + DIGIT_H - THICK), Size(THICK - 1, THICK - 1))
    }

    label(m, 488f, 100f, "DIODE", 15f, mode == "DIODE", Align.RIGHT)
    label(m, 488f, 122f, "CONT", 15f, mode == "CONT", Align.RIGHT)
    label(m, 488f, 78f, "NCV", 15f, mode == "NCV", Align.RIGHT)
    label(m, 488f, 168f, if (mode == "hFE") "hFE" else r?.unit ?: "", 40f, true, Align.RIGHT)
}
