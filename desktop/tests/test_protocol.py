"""Packets captured from a real OW18B, one per dial position (2026-10-19)."""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

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


if __name__ == "__main__":
    for name, fn in list(globals().items()):
        if name.startswith("test_"):
            fn()
    print("ok")
