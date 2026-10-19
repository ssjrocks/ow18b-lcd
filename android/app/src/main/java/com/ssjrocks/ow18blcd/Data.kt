package com.ssjrocks.ow18blcd

import java.io.Writer
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** Recordings, statistics, alarms and CSV. Mirrors desktop/ow18b_lcd/data.py. */
object Si {
    val PREFIX = mapOf(-12 to "p", -9 to "n", -6 to "µ", -3 to "m", 0 to "", 3 to "k", 6 to "M", 9 to "G")
    private val PREFIX_VALUE = mapOf("p" to -12, "n" to -9, "u" to -6, "µ" to -6, "m" to -3, "" to 0, "k" to 3, "K" to 3, "M" to 6, "G" to 9)
    private val NO_PREFIX = setOf("%", "°C", "°F", "")

    fun exponent(v: Double): Int =
        if (v == 0.0 || !v.isFinite()) 0 else (floor(log10(abs(v)) / 3) * 3).toInt().coerceIn(-12, 9)

    /** 1.234e-3, "V" -> "1.234 mV". Units without prefixes (%, °C, °F) stay as they are. */
    fun format(v: Double, unit: String, exp: Int? = null, decimals: Int? = null): String {
        if (unit in NO_PREFIX) return "%.${decimals ?: 2}f %s".format(Locale.ROOT, v, unit).trim()
        val e = exp ?: exponent(v)
        val scaled = v / 10.0.pow(e)
        val d = decimals ?: (3 - (if (scaled != 0.0) abs(scaled).toLong().toString().length else 1)).coerceAtLeast(0)
        return "%.${d}f %s%s".format(Locale.ROOT, scaled, PREFIX[e], unit).trim()
    }

    /** "4.5", "450m", "450 mV", "1.2k" -> base-unit value. Throws IllegalArgumentException. */
    fun parse(text: String, unit: String = ""): Double {
        var t = text.trim().replace(",", ".")
        if (unit.isNotEmpty() && t.endsWith(unit)) t = t.dropLast(unit.length).trim()
        val m = Regex("([-+]?\\d*\\.?\\d+(?:[eE][-+]?\\d+)?)\\s*([pnuµmkKMG]?)").matchEntire(t)
            ?: throw IllegalArgumentException("Not a number: $text")
        return m.groupValues[1].toDouble() * 10.0.pow(PREFIX_VALUE.getValue(m.groupValues[2]))
    }
}

class Sample(val time: Long, val reading: Protocol.Reading) // time in ms since the epoch

/** A list of readings over time: a recording made here, or an offline log read from the meter. */
class Series(val title: String, val source: String = "recording") {
    val samples = mutableListOf<Sample>()

    fun add(r: Protocol.Reading, time: Long) {
        samples.add(Sample(time, r))
    }

    val durationMs: Long get() = if (samples.size > 1) samples.last().time - samples.first().time else 0

    /** One row per reading. "value" is in the base unit (V, A, Ω...), so a range change from mV to
     *  V stays one column; "display" and "display_unit" are what the LCD showed. */
    fun writeCsv(w: Writer) {
        val iso = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")
        fun q(s: String) = if (s.any { it == ',' || it == '"' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
        w.write("time,seconds,value,unit,display,display_unit,function,flags\r\n")
        val t0 = samples.firstOrNull()?.time ?: 0
        for (s in samples) {
            val r = s.reading
            val time = LocalDateTime.ofInstant(Instant.ofEpochMilli(s.time), ZoneId.systemDefault()).format(iso)
            val cells = listOf(time, "%.3f".format(Locale.ROOT, (s.time - t0) / 1000.0), r.value?.toString() ?: "",
                r.baseUnit, r.digits, r.unit, r.name, r.flags)
            w.write(cells.joinToString(",") { q(it) } + "\r\n")
        }
    }

    companion object {
        fun fromOffline(log: Protocol.OfflineLog): Series {
            val start = log.start ?: LocalDateTime.now()
            val startMs = start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val whenText = log.start?.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) ?: "unknown time"
            return Series("Offline log, $whenText, every ${log.interval} s", "offline").apply {
                log.readings.forEach { (t, r) -> add(r, startMs + t * 1000) }
            }
        }
    }
}

/** Min, max and average since reset, for one function at a time. */
class Stats {
    var function: Int? = null
        private set
    var unit = ""
        private set
    var count = 0
        private set
    private var total = 0.0
    private var low = Double.POSITIVE_INFINITY
    private var high = Double.NEGATIVE_INFINITY
    private var exp = 0
    private var decimals = 2

    fun reset() {
        count = 0; total = 0.0; low = Double.POSITIVE_INFINITY; high = Double.NEGATIVE_INFINITY
    }

    fun add(r: Protocol.Reading) {
        if (r.function != function) {
            function = r.function
            unit = r.baseUnit
            reset()
        }
        val v = r.value ?: return
        if (r.function !in setOf(7, 8, 9)) exp = Protocol.EXPONENTS[r.scale] ?: 0
        decimals = r.decimals
        count++
        total += v
        low = minOf(low, v)
        high = maxOf(high, v)
    }

    fun describe(): String {
        if (count == 0) return "Min –   Max –   Avg –"
        fun f(v: Double) = Si.format(v, unit, exp, decimals)
        return "Min ${f(low)}   Max ${f(high)}   Avg ${f(total / count)}   ($count)"
    }
}

/** High/low limits for one base unit; trips when a reading goes outside them. */
data class Alarm(val enabled: Boolean = false, val unit: String = "V", val low: Double? = null, val high: Double? = null) {
    fun check(r: Protocol.Reading): String? {
        if (!enabled || r.baseUnit != unit) return null
        val v = r.value ?: return if (r.overload && high != null) "Overload" else null
        if (high != null && v > high) return "${Si.format(v, unit)} is above ${Si.format(high, unit)}"
        if (low != null && v < low) return "${Si.format(v, unit)} is below ${Si.format(low, unit)}"
        return null
    }
}
