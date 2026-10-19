package com.ssjrocks.ow18blcd

import java.time.LocalDateTime
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * The OW18B's Bluetooth protocol: live packets, device info, keys, commands, offline logs (see
 * PROTOCOL.md). Mirrors desktop/ow18b_lcd/protocol.py.
 */
object Protocol {
    const val NAME = "BDM"
    val SERVICE: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
    val COMMAND: UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb") // 16-byte commands
    val INFO: UUID = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb") // device info, read once
    val KEYS: UUID = UUID.fromString("0000fff3-0000-1000-8000-00805f9b34fb") // key presses
    val NOTIFY: UUID = UUID.fromString("0000fff4-0000-1000-8000-00805f9b34fb") // live packets and log dumps
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** function code -> (label shown on the LCD, unit) */
    val FUNCTIONS = mapOf(
        0 to ("DC" to "V"), 1 to ("AC" to "V"), 2 to ("DC" to "A"), 3 to ("AC" to "A"),
        4 to ("" to "Ω"), 5 to ("" to "F"), 6 to ("" to "Hz"), 7 to ("" to "%"),
        8 to ("" to "°C"), 9 to ("" to "°F"), 10 to ("DIODE" to "V"), 11 to ("CONT" to "Ω"),
        12 to ("hFE" to ""), 13 to ("NCV" to ""),
    )
    val FUNCTION_NAMES = mapOf(
        0 to "DC voltage", 1 to "AC voltage", 2 to "DC current", 3 to "AC current", 4 to "Resistance",
        5 to "Capacitance", 6 to "Frequency", 7 to "Duty cycle", 8 to "Temperature", 9 to "Temperature",
        10 to "Diode", 11 to "Continuity", 12 to "hFE", 13 to "NCV",
    )
    private val PREFIXES = mapOf(0 to "p", 1 to "n", 2 to "µ", 3 to "m", 4 to "", 5 to "k", 6 to "M", 7 to "G")
    val EXPONENTS = mapOf(0 to -12, 1 to -9, 2 to -6, 3 to -3, 4 to 0, 5 to 3, 6 to 6, 7 to 9)
    private val MODELS = mapOf(18 to "OW18B", 20 to "OW18E", 33 to "B33", 35 to "B35", 41 to "B41T", 21 to "CM2100B")
    const val NCV = 13
    private val NO_PREFIX = setOf(7, 8, 9) // %, °C and °F never take a prefix

    // Key codes for FFF3, tested on an OW18B (firmware 3.0.4): [key, 01] short press, [key, 00] long.
    const val KEY_SELECT = 1
    const val KEY_RANGE = 2
    const val KEY_HOLD = 3
    const val KEY_HZ = 5
    const val SHORT = 1
    const val LONG = 0

    const val MAX_RECORDS = 10_000
    val NAME_CHARS = Regex("^[A-Za-z0-9_]{1,15}$")

    data class Reading(
        val function: Int,
        val scale: Int,
        val decimals: Int, // 7 = overload
        val raw: Int, // signed display counts
        val hold: Boolean = false,
        val rel: Boolean = false,
        val auto: Boolean = false,
        val lowBattery: Boolean = false,
        val packet: String = "",
    ) {
        val overload get() = decimals == 7
        val mode: String get() = FUNCTIONS[function]?.first ?: "F$function"
        val name: String get() = FUNCTION_NAMES[function] ?: "Function $function"
        val baseUnit: String get() = FUNCTIONS[function]?.second ?: "?"

        /** Unit with its prefix, e.g. "mV", "kΩ". */
        val unit: String
            get() = baseUnit.let { if (it.isEmpty() || function in NO_PREFIX) it else (PREFIXES[scale] ?: "") + it }

        /** What the LCD shows: "-1.234", "OL", "26", or "EF" / bars for NCV. */
        val digits: String
            get() = when {
                overload -> "OL"
                function == NCV -> if (raw == 0) "EF" else "-".repeat(raw.coerceAtMost(4))
                decimals == 0 -> raw.toString()
                else -> (if (raw < 0) "-" else "") + "%.${decimals}f".format(Locale.ROOT, abs(raw) / 10.0.pow(decimals))
            }

        /** The reading in base units (volts, ohms...) at the display's resolution, or null for OL and NCV. */
        val value: Double?
            get() {
                if (overload || function == NCV || decimals > 5) return null
                val exp = if (function in NO_PREFIX) 0 else EXPONENTS[scale] ?: 0
                val places = decimals - exp
                val v = raw / 10.0.pow(decimals) * 10.0.pow(exp)
                val f = 10.0.pow(places)
                return (v * f).roundToLong() / f
            }

        val flags: String
            get() = listOf(hold to "HOLD", rel to "REL", auto to "AUTO", lowBattery to "LOWBAT")
                .filter { it.first }.joinToString(" ") { it.second }

        /** The reading as words for text-to-speech. */
        val spoken: String
            get() {
                if (overload) return "overload"
                if (function == NCV) return if (raw == 0) "no field" else "field level $raw"
                val words = mapOf("V" to "volts", "A" to "amps", "Ω" to "ohms", "F" to "farads", "Hz" to "hertz",
                    "%" to "percent", "°C" to "degrees", "°F" to "degrees Fahrenheit")
                val prefix = mapOf('n' to "nano", 'µ' to "micro", 'm' to "milli", 'k' to "kilo", 'M' to "mega")
                val u = unit
                val p = if (u.length > 1 && u !in setOf("Hz", "°C", "°F")) prefix[u[0]] ?: "" else ""
                val base = words[if (p.isNotEmpty()) u.substring(1) else u] ?: u
                return "${digits.replace("-", "minus ")} $p$base".trim()
            }
    }

    /** One 6-byte packet -> Reading, or null for the blank frame sent while the dial turns. */
    fun decode(p: ByteArray, offset: Int = 0): Reading? {
        if (p.size - offset < 6) return null
        fun u16(i: Int) = (p[offset + i].toInt() and 0xFF) or ((p[offset + i + 1].toInt() and 0xFF) shl 8)
        val word = u16(0)
        val flags = u16(2)
        val raw = u16(4)
        val function = (word shr 6) and 0x0F
        val scale = (word shr 3) and 0x07
        val decimals = word and 0x07
        // Turning the dial or pressing Select first sends "0 Ω, no decimals" (20 f1 .. .. 00 00),
        // which no real Ω range shows.
        if (function == 4 && scale == 4 && decimals == 0 && raw == 0) return null
        val value = if (raw and 0x8000 != 0) -(raw and 0x7FFF) else raw
        val hex = (0 until 6).joinToString(" ") { "%02x".format(p[offset + it]) }
        return Reading(function, scale, decimals, value, flags and 1 != 0, flags and 2 != 0, flags and 4 != 0, flags and 8 != 0, hex)
    }

    /** Notifications sometimes carry several packets back to back. */
    fun decodeAll(data: ByteArray): List<Reading> =
        (0..data.size - 6 step 6).mapNotNull { decode(data, it) }

    fun hex(s: String): ByteArray = s.split(" ").map { it.toInt(16).toByte() }.toByteArray()

    // --- Device info (FFF2) ---

    data class DeviceInfo(val modelCode: Int, val battery: Int?, val firmware: String, val recording: Boolean) {
        val model: String get() = MODELS[modelCode] ?: "model $modelCode"
        val firmwareNumber: Int get() = firmware.replace(".", "").toIntOrNull() ?: 0
    }

    fun parseInfo(d: ByteArray): DeviceInfo {
        val fw = if (d.size >= 5) (2..4).joinToString(".") { (d[it].toInt() and 0xFF).toString() } else ""
        val battery = if (d.size > 1 && (d[1].toInt() and 0xFF) <= 100) d[1].toInt() and 0xFF else null
        return DeviceInfo(if (d.isNotEmpty()) d[0].toInt() and 0xFF else 0, battery, fw, d.size > 5 && d[5].toInt() == 1)
    }

    // --- Commands (FFF1, 16 bytes each) and keys (FFF3) ---

    private fun pad(b: ByteArray) = b.copyOf(16)
    private fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)
    private fun u32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

    fun key(code: Int, press: Int = SHORT) = byteArrayOf(code.toByte(), press.toByte())

    fun setClock(t: LocalDateTime) = pad(ascii("*DATe") + byteArrayOf(
        (t.year / 100).toByte(), (t.year % 100).toByte(), t.monthValue.toByte(), t.dayOfMonth.toByte(),
        t.hour.toByte(), t.minute.toByte(), t.second.toByte(),
    ))

    fun startRecord(intervalS: Int, count: Int): ByteArray {
        require(count in 1..MAX_RECORDS && intervalS >= 1) { "Interval must be at least 1 s and count 1-$MAX_RECORDS" }
        return pad(ascii("*RECOrd,") + u32(intervalS) + u32(count))
    }

    val STOP_RECORD = pad(ascii("*STOP"))
    val READ_LOG = pad(ascii("*READ1?")) // firmware 0.1.2 and later
    val READ_LOG_OLD = pad(ascii("*READ?"))

    fun rename(name: String): ByteArray {
        require(NAME_CHARS.matches(name)) { "Use 1-15 letters, digits or underscores" }
        return pad(ascii("@$name"))
    }

    // --- Offline log dumps (FFF4, after READ_LOG) ---

    class OfflineLog(
        val start: LocalDateTime?, val interval: Int, val readings: List<Pair<Long, Reading>>,
        val expected: Int = 0, // payload bytes the header announces
        val received: Int = 0, // payload bytes that came
    ) {
        val complete: Boolean get() = received >= expected
    }

    private fun findMark(b: ByteArray, from: Int): Int {
        var run = 0
        for (i in from until b.size) {
            if (b[i] == 0xFF.toByte()) {
                run++
                if (run == 20) return i - 19
            } else run = 0
        }
        return -1
    }

    fun dumpComplete(b: ByteArray): Boolean {
        val i = findMark(b, 0)
        return i >= 0 && findMark(b, i + 20) > 0
    }

    /** The bytes received after READ_LOG (live packets around the dump are skipped). */
    fun parseDump(b: ByteArray, dated: Boolean = true): OfflineLog {
        val i = findMark(b, 0)
        require(i >= 0) { "No log in the data" }
        var start = i + 20
        while (start < b.size && b[start] == 0xFF.toByte()) start++ // a longer leading run
        val end = findMark(b, start)
        require(end >= 0) { "The log was cut off" }
        val p = b.copyOfRange(start, end)
        fun u(k: Int) = p[k].toInt() and 0xFF
        var whenStarted: LocalDateTime? = null
        var interval = 1
        var offset = 4
        fun u32(k: Int) = u(k) or (u(k + 1) shl 8) or (u(k + 2) shl 16) or (u(k + 3) shl 24)
        val expected = if (dated && p.size >= 16) u32(12) else if (!dated && p.size >= 4) u32(0) else 0
        if (dated && p.size >= 16) {
            interval = (u(8) or (u(9) shl 8) or (u(10) shl 16) or (u(11) shl 24)).takeIf { it > 0 } ?: 1
            offset = 16
            whenStarted = runCatching {
                LocalDateTime.of(if (u(0) != 0) u(0) * 100 + u(1) else 2000 + u(1), u(2), u(3), u(4), u(5), u(6))
            }.getOrNull() // the meter's clock was never set
        }
        val out = mutableListOf<Pair<Long, Reading>>()
        var current: Triple<Int, Int, Int>? = null
        var n = 0L
        var k = offset
        while (k + 1 < p.size) {
            val w = u(k) or (u(k + 1) shl 8)
            k += 2
            if ((w shr 10) == 0b111100) { // a function/range word for the readings after it
                current = Triple((w shr 6) and 0x0F, (w shr 3) and 0x07, w and 0x07)
                continue
            }
            val c = current ?: continue
            val raw = if (w and 0x8000 != 0) -(w and 0x7FFF) else w
            out.add(n * interval to Reading(c.first, c.second, c.third, raw))
            n++
        }
        return OfflineLog(whenStarted, interval, out, expected, maxOf(0, p.size - offset))
    }

    /** Packets from a real OW18B going through its dial positions, with seconds to show each, for demo mode. */
    val DEMO = listOf(
        "19 f0 04 00 00 00" to 6, "1a f0 04 00 74 00" to 1, "1a f0 04 00 57 00" to 1, "1a f0 04 00 41 00" to 1,
        "1a f0 04 00 5b 00" to 1, "1a f0 04 00 7c 00" to 1, "1a f0 04 00 36 00" to 1, "1a f0 04 00 d7 82" to 1,
        "1a f0 04 00 30 80" to 1, "1a f0 04 00 d6 00" to 1, "1a f0 04 00 2f 00" to 1, "1a f0 04 00 a0 00" to 1,
        "1a f0 04 00 88 00" to 1, "1a f0 04 00 2c 00" to 1, "1a f0 04 00 29 80" to 1, "1a f0 04 00 cd 00" to 2,
        "1a f0 04 00 c7 00" to 1, "1a f0 04 00 16 00" to 1, "1a f0 04 00 2e 00" to 3,
        "27 f1 04 00 ff 7f" to 2, "2f f1 04 00 ff 7f" to 3, "37 f1 04 00 ff 7f" to 3,
        "4a f1 04 00 00 00" to 4, "a3 f1 04 00 00 00" to 4, "20 f2 00 00 1a 00" to 4,
        "60 f3 00 00 00 00" to 3, "60 f3 00 00 01 00" to 1, "60 f3 00 00 02 00" to 1, "60 f3 00 00 03 00" to 2,
        "60 f3 00 00 01 00" to 1, "60 f3 00 00 00 00" to 2, "9a f0 04 00 08 00" to 3, "91 f0 04 00 01 00" to 3,
        "a2 f0 00 00 00 00" to 3,
    )
    val DEMO_INFO = DeviceInfo(18, 87, "3.0.4", false)
}
