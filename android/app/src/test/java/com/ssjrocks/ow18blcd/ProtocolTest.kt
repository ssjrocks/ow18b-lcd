package com.ssjrocks.ow18blcd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringWriter
import java.time.LocalDateTime

/** Packets and replies captured from a real OW18B (firmware 3.0.4) on 2026-10-19. */
class ProtocolTest {
    private fun r(hex: String) = Protocol.decode(Protocol.hex(hex))!!

    @Test fun liveReadings() {
        val cases = listOf(
            "19 f0 04 00 00 00" to "0.0 mV", "1a f0 04 00 d7 82" to "-7.27 mV", "37 f1 04 00 ff 7f" to "OL MΩ",
            "4a f1 04 00 00 00" to "0.00 nF", "a3 f1 04 00 00 00" to "0.000 Hz", "20 f2 00 00 1a 00" to "26 °C",
            "60 f3 00 00 03 00" to "---", "91 f0 04 00 01 00" to "0.1 µA", "a2 f0 00 00 00 00" to "0.00 A",
        )
        for ((hex, shown) in cases) assertEquals(hex, shown, "${r(hex).digits} ${r(hex).unit}".trim())
        assertNull(Protocol.decode(Protocol.hex("20 f1 04 00 00 00"))) // dial-turn frame
        assertEquals(-0.00727, r("1a f0 04 00 d7 82").value!!, 0.0)
        assertEquals("minus 7.27 millivolts", r("1a f0 04 00 d7 82").spoken)
    }

    @Test fun info() {
        val i = Protocol.parseInfo(Protocol.hex("12 63 03 00 04 00 00 00 00 00 00 00 00 00 00 00"))
        assertEquals(Protocol.DeviceInfo(18, 99, "3.0.4", false), i)
        assertEquals("OW18B", i.model)
    }

    @Test fun commands() {
        fun hex(b: ByteArray) = b.joinToString(" ") { "%02x".format(it) }
        assertEquals("2a 44 41 54 65 14 1a 0a 13 12 03 3b 00 00 00 00", hex(Protocol.setClock(LocalDateTime.of(2026, 10, 19, 18, 3, 59))))
        assertEquals("2a 52 45 43 4f 72 64 2c 01 00 00 00 0a 00 00 00", hex(Protocol.startRecord(1, 10)))
        assertEquals("02 00", hex(Protocol.key(Protocol.KEY_RANGE, Protocol.LONG)))
        assertEquals(16, Protocol.rename("Bench_1").size)
    }

    @Test fun dump() {
        val notes = listOf("19 f0 04 00 00 00", List(20) { "ff" }.joinToString(" "), "14 1a 0a 13 12 03 3b 00 01 00 00 00 16 00 00 00",
            "19 f0 " + List(18) { "00" }.joinToString(" "), "00 00", List(20) { "ff" }.joinToString(" "), "19 f0 04 00 00 00")
        val b = notes.map { Protocol.hex(it) }.reduce { a, c -> a + c }
        assertTrue(Protocol.dumpComplete(b))
        val log = Protocol.parseDump(b)
        assertEquals(LocalDateTime.of(2026, 10, 19, 18, 3, 59), log.start)
        assertEquals(10, log.readings.size)
        assertEquals(9L, log.readings[9].first)
        assertEquals("0.0", log.readings[0].second.digits)
        assertTrue(log.complete)
        // The same dump without its readings (seen once on the real meter): flagged incomplete
        val short = (notes.take(3) + notes.drop(5)).map { Protocol.hex(it) }.reduce { a, c -> a + c }
        assertTrue(!Protocol.parseDump(short).complete)
    }

    @Test fun dataTools() {
        assertEquals(0.45, Si.parse("450m", "V"), 1e-12)
        assertEquals(1200.0, Si.parse("1.2 kΩ", "Ω"), 1e-9)
        val stats = Stats()
        listOf("1a f0 04 00 74 00", "1a f0 04 00 d7 82", "1a f0 04 00 41 00").forEach { stats.add(r(it)) }
        assertTrue(stats.describe(), stats.describe().startsWith("Min -7.27 mV   Max 1.16 mV   Avg -1.82 mV"))
        val alarm = Alarm(true, "V", -5e-3, 1e-3)
        assertEquals("1.16 mV is above 1.00 mV", alarm.check(r("1a f0 04 00 74 00")))
        val s = Series("t").apply { add(r("1a f0 04 00 d7 82"), 0) }
        val w = StringWriter().also { s.writeCsv(it) }.toString().lines()
        assertTrue(w[1], w[1].endsWith(",0.000,-0.00727,V,-7.27,mV,DC voltage,AUTO"))
    }
}
