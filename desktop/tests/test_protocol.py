"""Packets captured from a real OW18B, one per dial position (2026-10-19)."""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import datetime as dt  # noqa: E402

from ow18b_lcd import protocol  # noqa: E402
from ow18b_lcd.protocol import decode, split  # noqa: E402

CASES = [
    ("19 f0 04 00 00 00", "0.0", "mV", "DC", True),
    ("1a f0 04 00 74 00", "1.16", "mV", "DC", True),
    ("1a f0 04 00 d7 82", "-7.27", "mV", "DC", True),
    ("27 f1 04 00 ff 7f", "OL", "Ω", "", True),
    ("2f f1 04 00 ff 7f", "OL", "kΩ", "", True),
    ("37 f1 04 00 ff 7f", "OL", "MΩ", "", True),
    ("4a f1 04 00 00 00", "0.00", "nF", "", True),
    ("a3 f1 04 00 00 00", "0.000", "Hz", "", True),
    ("20 f2 00 00 1a 00", "26", "°C", "", False),
    ("60 f3 00 00 00 00", "EF", "", "NCV", False),
    ("60 f3 00 00 03 00", "---", "", "NCV", False),
    ("9a f0 04 00 08 00", "0.08", "mA", "DC", True),
    ("91 f0 04 00 01 00", "0.1", "µA", "DC", True),
    ("a2 f0 00 00 00 00", "0.00", "A", "DC", False),
]


def test_cases():
    for hexs, digits, unit, mode, auto in CASES:
        r = decode(bytes.fromhex(hexs))
        assert r is not None, hexs
        assert (r.digits, r.unit, r.mode, r.auto) == (digits, unit, mode, auto), (hexs, r)


def test_values():
    assert abs(decode(bytes.fromhex("1a f0 04 00 d7 82")).value - -7.27e-3) < 1e-12
    assert decode(bytes.fromhex("37 f1 04 00 ff 7f")).value is None


def test_dial_blank_frame_skipped():
    assert decode(bytes.fromhex("20 f1 04 00 00 00")) is None
    assert decode(bytes.fromhex("20 f1 00 00 00 00")) is None


def test_split():
    assert len(split(bytes.fromhex("19 f0 04 00 00 00" * 3))) == 3


def test_info():
    # Read from FFF2 on a real OW18B
    info = protocol.parse_info(bytes.fromhex("12 63 03 00 04 00 00 00 00 00 00 00 00 00 00 00"))
    assert (info.model, info.battery, info.firmware, info.recording) == ("OW18B", 99, "3.0.4", False)


def test_commands():
    # The exact bytes the meter accepted on 2026-10-19
    assert protocol.set_clock(dt.datetime(2026, 10, 19, 18, 3, 59)).hex(" ") == "2a 44 41 54 65 14 1a 0a 13 12 03 3b 00 00 00 00"
    assert protocol.start_record(1, 10).hex(" ") == "2a 52 45 43 4f 72 64 2c 01 00 00 00 0a 00 00 00"
    assert protocol.key(protocol.KEY_RANGE, protocol.LONG) == b"\x02\x00"
    assert protocol.rename("Bench_1")[:8] == b"@Bench_1" and len(protocol.rename("Bench_1")) == 16
    for bad in ("", "has space", "x" * 16):
        try:
            protocol.rename(bad)
            raise AssertionError(bad)
        except ValueError:
            pass


def test_dump():
    # Notifications received after *READ1? on a real OW18B: 10 readings of 0.0 mV, 1 s apart
    notes = ["19 f0 04 00 00 00", "ff " * 19 + "ff", "14 1a 0a 13 12 03 3b 00 01 00 00 00 16 00 00 00",
             "19 f0" + " 00" * 18, "00 00", "ff " * 19 + "ff", "19 f0 04 00 00 00"]
    buf = b"".join(bytes.fromhex(n) for n in notes)
    assert protocol.dump_complete(buf)
    log = protocol.parse_dump(buf)
    assert log.start == dt.datetime(2026, 10, 19, 18, 3, 59) and log.interval == 1
    assert len(log.readings) == 10
    assert log.readings[9][0] == 9 and log.readings[0][1].digits == "0.0" and log.readings[0][1].unit == "mV"
    assert log.complete and log.expected == 22
    # The same dump without its readings (seen once on the real meter): flagged incomplete
    short = b"".join(bytes.fromhex(n) for n in notes[:3] + notes[5:])
    assert not protocol.parse_dump(short).complete


def test_spoken():
    assert decode(bytes.fromhex("1a f0 04 00 d7 82")).spoken == "minus 7.27 millivolts"
    assert decode(bytes.fromhex("37 f1 04 00 ff 7f")).spoken == "overload"
    assert decode(bytes.fromhex("20 f2 00 00 1a 00")).spoken == "26 degrees"
    assert decode(bytes.fromhex("a3 f1 04 00 00 00")).spoken == "0.000 hertz"


if __name__ == "__main__":
    for name, fn in list(globals().items()):
        if name.startswith("test_"):
            fn()
    print("ok")
