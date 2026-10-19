package com.ssjrocks.ow18blcd

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
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

/** Graphs: the live trend of the last minute, and whole recordings with zoom and pan. Mirrors desktop graph.py. */

/** The last minute of readings, cleared when the meter changes function. */
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

private fun niceStep(span: Double): Double {
    val raw = span / 4
    val mag = 10.0.pow(floor(log10(raw)))
    return listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * mag }.first { it >= raw }
}

private fun niceTimeStep(spanS: Double, ticks: Int): Double {
    for (s in listOf(1, 2, 5, 10, 15, 30, 60, 120, 300, 600, 900, 1800, 3600, 7200, 14400, 28800, 86400)) {
        if (spanS / s <= ticks) return s.toDouble()
    }
    return 86400.0 * ceil(spanS / 86400 / ticks)
}

fun clock(seconds: Double): String {
    val s = abs(seconds).roundToLong()
    val sign = if (seconds < 0) "-" else ""
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "$sign$h:%02d:%02d".format(m, sec) else "$sign$m:%02d".format(sec)
}

private val NO_PREFIX = setOf("%", "°C", "°F", "")

/** Points (x, value or null for a gap) between x0 and x1, autoscaled in y, with optional alarm lines. */
private fun DrawScope.plot(
    m: TextMeasurer, points: List<Pair<Double, Double?>>, unit: String, x0: Double, x1: Double,
    ticks: List<Pair<Double, String>>, empty: String, markers: List<Double>,
) {
    drawRect(LcdStyle.BG_BOTTOM)
    val left = 70.dp.toPx(); val right = 12.dp.toPx(); val top = 10.dp.toPx(); val bottom = 22.dp.toPx()
    val pw = size.width - left - right; val ph = size.height - top - bottom
    if (pw < 20 || ph < 20 || x1 <= x0) return
    val style = TextStyle(fontSize = 11.sp, color = LcdStyle.INK.copy(alpha = 0.8f))
    val grid = LcdStyle.INK.copy(alpha = 0.15f)
    val values = points.filter { it.second != null && it.first in x0..x1 }.map { it.second!! }
    var lo: Double; var hi: Double; val step: Double
    if (values.isNotEmpty()) {
        lo = (values + markers).min(); hi = (values + markers).max()
        if (hi - lo < 1e-15) {
            val pad = (abs(hi) * 0.1).takeIf { it > 0 } ?: 1e-3
            lo -= pad; hi += pad
        }
        step = niceStep(hi - lo)
        lo = floor(lo / step) * step; hi = ceil(hi / step) * step
    } else { lo = -1.0; hi = 1.0; step = 0.5 }

    fun xOf(x: Double) = (left + pw * (x - x0) / (x1 - x0)).toFloat()
    fun yOf(v: Double) = (top + ph * (1 - (v - lo) / (hi - lo))).toFloat()

    val exp = Si.exponent(max(abs(lo), abs(hi)))
    val scaledStep = if (unit in NO_PREFIX) step else step / 10.0.pow(exp)
    val decimals = (0..6).first { abs((scaledStep * 10.0.pow(it)).roundToLong() / 10.0.pow(it) - scaledStep) < scaledStep * 1e-6 }
    var v = lo
    while (v <= hi + step / 2) {
        val y = yOf(v)
        drawLine(grid, Offset(left, y), Offset(left + pw, y), 1f)
        if (values.isNotEmpty()) {
            val text = Si.format(if (abs(v) > step * 1e-6) v else 0.0, unit, exp, decimals)
            val layout = m.measure(text, style)
            drawText(layout, topLeft = Offset(left - 6.dp.toPx() - layout.size.width, y - layout.size.height / 2))
        }
        v += step
    }
    for ((x, label) in ticks) {
        if (x < x0 || x > x1) continue
        val px = xOf(x)
        drawLine(grid, Offset(px, top), Offset(px, top + ph), 1f)
        val layout = m.measure(label, style)
        val lx = (px - layout.size.width / 2).coerceIn(2f, size.width - layout.size.width - 2)
        drawText(layout, topLeft = Offset(lx, size.height - layout.size.height - 3.dp.toPx()))
    }
    if (values.isEmpty()) {
        val layout = m.measure(empty, style.copy(fontSize = 13.sp))
        drawText(layout, topLeft = Offset(left + (pw - layout.size.width) / 2, top + (ph - layout.size.height) / 2))
        return
    }
    clipRect(left, top, left + pw, top + ph) {
        for (mk in markers) {
            drawLine(Color(0xCCB31A1A), Offset(left, yOf(mk)), Offset(left + pw, yOf(mk)), 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 12f)))
        }
        val path = Path()
        var drawing = false
        val dots = mutableListOf<Offset>()
        for ((x, value) in points) {
            if (value == null) { drawing = false; continue }
            if (drawing) path.lineTo(xOf(x), yOf(value)) else { path.moveTo(xOf(x), yOf(value)); dots.add(Offset(xOf(x), yOf(value))) }
            drawing = true
        }
        drawPath(path, LcdStyle.INK, style = Stroke(2.dp.toPx(), join = StrokeJoin.Round))
        if (values.size < 3) dots.forEach { drawCircle(LcdStyle.INK, 2.5.dp.toPx(), it) }
    }
}

@Composable
fun TrendGraph(trend: Trend, markers: List<Double>, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val now by produceState(SystemClock.uptimeMillis()) {
        while (true) { value = SystemClock.uptimeMillis(); delay(250) }
    }
    val version by trend.version
    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") version
        val pts = trend.points.map { (t, v) -> (t - now) / 1000.0 to v }
        val ticks = listOf(60, 45, 30, 15, 0).map { -it.toDouble() to (if (it == 0) "now" else "-${it}s") }
        val empty = if (trend.function != null && trend.unit.isEmpty()) "Nothing to plot in this mode" else "No readings to plot"
        plot(measurer, pts, trend.unit, -60.0, 0.0, ticks, empty, markers)
    }
}

/** A whole recording or offline log, one function at a time. Pinch to zoom, drag to pan, double-tap to reset. */
@Composable
fun SeriesGraph(series: Series?, function: Int?, version: Int, markers: List<Double>, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    var zoom by remember(series) { mutableFloatStateOf(1f) } // visible fraction of the whole
    var start by remember(series) { mutableFloatStateOf(0f) } // left edge, as a fraction
    Canvas(
        modifier
            .pointerInput(series) {
                detectTransformGestures { centroid, pan, gestureZoom, _ ->
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    val at = centroid.x / w
                    val newZoom = (zoom / gestureZoom).coerceIn(0.01f, 1f)
                    var s = start + at * (zoom - newZoom) - pan.x / w * newZoom
                    s = s.coerceIn(0f, 1f - newZoom)
                    zoom = newZoom
                    start = s
                }
            }
            .pointerInput(series) { detectTapGestures(onDoubleTap = { zoom = 1f; start = 0f }) },
    ) {
        @Suppress("UNUSED_EXPRESSION") version
        val samples = series?.samples ?: emptyList()
        val t0 = samples.firstOrNull()?.time ?: 0L
        var unit = ""
        val pts = mutableListOf<Pair<Double, Double?>>()
        for (s in samples) {
            if (s.reading.function == function) {
                pts.add((s.time - t0) / 1000.0 to s.reading.value)
                unit = s.reading.baseUnit
            } else if (pts.isNotEmpty() && pts.last().second != null) {
                pts.add((s.time - t0) / 1000.0 to null) // break the line where another function was measured
            }
        }
        val total = max((series?.durationMs ?: 0) / 1000.0, 1.0)
        val a = total * start
        val b = a + total * zoom
        val step = niceTimeStep(b - a, max(2, ((size.width - 82.dp.toPx()) / 70.dp.toPx()).toInt()))
        val first = ceil(a / step) * step
        val ticks = generateSequence(first) { it + step }.takeWhile { it <= b }.map { it to clock(it) }.toList()
        plot(measurer, pts, unit, a, b, ticks, if (pts.isEmpty()) "Nothing recorded yet" else "Nothing to plot", markers)
    }
}
