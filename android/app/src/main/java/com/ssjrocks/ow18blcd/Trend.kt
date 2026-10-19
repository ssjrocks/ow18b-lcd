package com.ssjrocks.ow18blcd

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToLong

/** The last minute of readings, cleared when the meter changes function. Mirrors desktop graph.py. */
class Trend {
    val points = ArrayDeque<Pair<Long, Double?>>()
    var function: Int? = null
        private set
    var unit = ""
        private set
    /** Bumped on every change so the graph redraws. */
    val version = mutableIntStateOf(0)

    fun clear() {
        points.clear()
        version.intValue++
    }

    fun add(r: Protocol.Reading) {
        if (r.function != function) {
            points.clear()
            function = r.function
            unit = r.baseUnit
        }
        val now = SystemClock.uptimeMillis()
        points.addLast(now to r.value)
        while (points.isNotEmpty() && points.first().first < now - WINDOW_MS - 2000) points.removeFirst()
        version.intValue++
    }

    companion object {
        const val WINDOW_MS = 60_000L
    }
}

private val PREFIX = mapOf(-12 to "p", -9 to "n", -6 to "µ", -3 to "m", 0 to "", 3 to "k", 6 to "M", 9 to "G")

private fun exponent(v: Double): Int =
    if (v == 0.0 || !v.isFinite()) 0 else (floor(log10(abs(v)) / 3) * 3).toInt().coerceIn(-12, 9)

private fun niceStep(span: Double): Double {
    val raw = span / 4
    val mag = 10.0.pow(floor(log10(raw)))
    return listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * mag }.first { it >= raw }
}

@Composable
fun TrendGraph(trend: Trend, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val now by produceState(SystemClock.uptimeMillis()) {
        while (true) { value = SystemClock.uptimeMillis(); delay(250) }
    }
    val version by trend.version
    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") version
        drawRect(LcdStyle.BG_BOTTOM)
        val left = 64.dp.toPx(); val right = 12.dp.toPx(); val top = 10.dp.toPx(); val bottom = 22.dp.toPx()
        val pw = size.width - left - right; val ph = size.height - top - bottom
        if (pw < 20 || ph < 20) return@Canvas
        val style = TextStyle(fontSize = 11.sp, color = LcdStyle.INK.copy(alpha = 0.8f))
        val grid = LcdStyle.INK.copy(alpha = 0.15f)

        val values = trend.points.filter { it.second != null && it.first >= now - Trend.WINDOW_MS }.map { it.second!! }
        var lo: Double; var hi: Double; val step: Double
        if (values.isNotEmpty()) {
            lo = values.min(); hi = values.max()
            if (hi - lo < 1e-15) {
                val pad = (abs(hi) * 0.1).takeIf { it > 0 } ?: 1e-3
                lo -= pad; hi += pad
            }
            step = niceStep(hi - lo)
            lo = floor(lo / step) * step; hi = ceil(hi / step) * step
        } else { lo = -1.0; hi = 1.0; step = 0.5 }

        fun xOf(t: Long) = left + pw * (1 - (now - t).toFloat() / Trend.WINDOW_MS)
        fun yOf(v: Double) = (top + ph * (1 - (v - lo) / (hi - lo))).toFloat()

        val exp = exponent(max(abs(lo), abs(hi)))
        val scaledStep = step / 10.0.pow(exp)
        val decimals = (0..6).first { abs((scaledStep * 10.0.pow(it)).roundToLong() / 10.0.pow(it) - scaledStep) < scaledStep * 1e-6 }
        var v = lo
        while (v <= hi + step / 2) {
            val y = yOf(v)
            drawLine(grid, Offset(left, y), Offset(left + pw, y), 1f)
            if (values.isNotEmpty()) {
                val shown = if (abs(v) > step * 1e-6) v else 0.0
                val text = "%.${decimals}f %s%s".format(java.util.Locale.ROOT, shown / 10.0.pow(exp), PREFIX[exp], trend.unit).trim()
                val layout = measurer.measure(text, style)
                drawText(layout, topLeft = Offset(left - 6.dp.toPx() - layout.size.width, y - layout.size.height / 2))
            }
            v += step
        }
        for (sec in listOf(60, 45, 30, 15, 0)) {
            val x = xOf(now - sec * 1000L)
            drawLine(grid, Offset(x, top), Offset(x, top + ph), 1f)
            val layout = measurer.measure(if (sec == 0) "now" else "-${sec}s", style)
            val lx = (x - layout.size.width / 2).coerceAtMost(size.width - layout.size.width - 2)
            drawText(layout, topLeft = Offset(lx, size.height - layout.size.height - 3.dp.toPx()))
        }

        if (values.isEmpty()) {
            val msg = if (trend.function != null && trend.unit.isEmpty()) "Nothing to plot in this mode" else "No readings to plot"
            val layout = measurer.measure(msg, style.copy(fontSize = 13.sp))
            drawText(layout, topLeft = Offset(left + (pw - layout.size.width) / 2, top + (ph - layout.size.height) / 2))
            return@Canvas
        }

        val path = Path()
        var drawing = false
        for ((t, value) in trend.points) {
            if (value == null) { drawing = false; continue }
            if (drawing) path.lineTo(xOf(t), yOf(value)) else path.moveTo(xOf(t), yOf(value))
            drawing = true
        }
        clipRect(left, top, left + pw, top + ph) {
            drawPath(path, LcdStyle.INK, style = Stroke(2.dp.toPx(), join = StrokeJoin.Round))
        }
    }
}
