package com.ssjrocks.ow18blcd

import java.util.UUID
import kotlin.math.abs
import kotlin.math.pow

/** The OW18B's Bluetooth packets (see PROTOCOL.md). Mirrors desktop/ow18b_lcd/protocol.py. */
object Protocol {
    const val NAME = "BDM"
    val SERVICE: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
    val NOTIFY: UUID = UUID.fromString("0000fff4-0000-1000-8000-00805f9b34fb")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** function code -> (label shown on the LCD, unit) */
    val FUNCTIONS = mapOf(
        0 to ("DC" to "V"), 1 to ("AC" to "V"), 2 to ("DC" to "A"), 3 to ("AC" to "A"),
        4 to ("" to "Ω"), 5 to ("" to "F"), 6 to ("" to "Hz"), 7 to ("" to "%"),
        8 to ("" to "°C"), 9 to ("" to "°F"), 10 to ("DIODE" to "V"), 11 to ("CONT" to "Ω"),
        12 to ("hFE" to ""), 13 to ("NCV" to ""),
    )
    private val PREFIXES = mapOf(1 to "n", 2 to "µ", 3 to "m", 4 to "", 5 to "k", 6 to "M")
    private val EXPONENTS = mapOf(1 to -9, 2 to -6, 3 to -3, 4 to 0, 5 to 3, 6 to 6)
    const val NCV = 13

    data class Reading(
        val function: Int,
        val scale: Int,
        val decimals: Int, // 7 = overload
        val raw: Int, // signed display counts
        val hold: Boolean,
        val rel: Boolean,
        val auto: Boolean,
        val lowBattery: Boolean,
        val packet: String,
    ) {
        val overload get() = decimals == 7
        val mode: String get() = FUNCTIONS[function]?.first ?: "F$function"
        val baseUnit: String get() = FUNCTIONS[function]?.second ?: "?"
        /** Unit with its prefix, e.g. "mV", "kΩ". */
        val unit: String get() = baseUnit.let { if (it.isEmpty()) "" else (PREFIXES[scale] ?: "") + it }

        /** What the LCD shows: "-1.234", "OL", "26", or "EF" / bars for NCV. */
        val digits: String
            get() = when {
                overload -> "OL"
                function == NCV -> if (raw == 0) "EF" else "-".repeat(raw.coerceAtMost(4))
                decimals == 0 -> raw.toString()
                else -> (if (raw < 0) "-" else "") + "%.${decimals}f".format(java.util.Locale.ROOT, abs(raw) / 10.0.pow(decimals))
            }

        /** The reading in base units (volts, ohms...), or null for overload and NCV. */
        val value: Double?
            get() = if (overload || function == NCV) null
            else raw / 10.0.pow(decimals) * 10.0.pow(EXPONENTS[scale] ?: 0)
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
        // Turning the dial sends "0 Ω, no decimals" (20 f1 .. .. 00 00), which no real Ω range shows.
        if (function == 4 && scale == 4 && decimals == 0 && raw == 0) return null
        val value = if (raw and 0x8000 != 0) -(raw and 0x7FFF) else raw
        val hex = (0 until 6).joinToString(" ") { "%02x".format(p[offset + it]) }
        return Reading(function, scale, decimals, value, flags and 1 != 0, flags and 2 != 0, flags and 4 != 0, flags and 8 != 0, hex)
    }

    /** Notifications sometimes carry several packets back to back. */
    fun decodeAll(data: ByteArray): List<Reading> =
        (0..data.size - 6 step 6).mapNotNull { decode(data, it) }

    fun hex(s: String): ByteArray = s.split(" ").map { it.toInt(16).toByte() }.toByteArray()

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
}
