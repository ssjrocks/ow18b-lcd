"""Statistics, alarms, SI parsing and CSV export."""
import csv
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from ow18b_lcd.data import Alarm, Series, Stats, parse_si, si  # noqa: E402
from ow18b_lcd.protocol import decode  # noqa: E402

MV = [decode(bytes.fromhex(h)) for h in ("1a f0 04 00 74 00", "1a f0 04 00 d7 82", "1a f0 04 00 41 00")]  # 1.16, -7.27, 0.65 mV


def test_si():
    assert si(1.16e-3, "V", -3, 2) == "1.16 mV"
    assert si(26, "°C") == "26.00 °C"
    assert parse_si("450m", "V") == 0.45 and parse_si("1.2 kΩ", "Ω") == 1200 and parse_si("-5", "V") == -5
    for bad in ("abc", "1.2x"):
        try:
            parse_si(bad)
            raise AssertionError(bad)
        except ValueError:
            pass


def test_stats():
    s = Stats()
    for r in MV:
        s.add(r)
    assert s.count == 3 and abs(s.low + 7.27e-3) < 1e-12 and abs(s.high - 1.16e-3) < 1e-12
    assert s.describe().startswith("Min -7.27 mV   Max 1.16 mV   Avg -1.82 mV")
    s.add(decode(bytes.fromhex("20 f2 00 00 1a 00")))  # another function resets
    assert s.count == 1 and s.unit == "°C"


def test_alarm():
    a = Alarm(True, "V", -5e-3, 1e-3)
    assert a.check(MV[0]) == "1.16 mV is above 1.00 mV"
    assert a.check(MV[1]) == "-7.27 mV is below -5.00 mV"
    assert a.check(MV[2]) is None
    assert a.check(decode(bytes.fromhex("20 f2 00 00 1a 00"))) is None  # other unit
    assert Alarm.from_dict(a.to_dict()) == a


def test_csv():
    series = Series("t")
    for i, r in enumerate(MV):
        series.add(r, 1_760_000_000 + i)
    with tempfile.TemporaryDirectory() as d:
        path = Path(d) / "x.csv"
        series.write_csv(path)
        rows = list(csv.DictReader(open(path, encoding="utf-8")))
    assert [r["display"] for r in rows] == ["1.16", "-7.27", "0.65"]
    assert rows[1]["value"] == repr(-7.27e-3) and rows[1]["unit"] == "V" and rows[1]["display_unit"] == "mV"
    assert rows[2]["seconds"] == "2.000" and rows[0]["flags"] == "AUTO"


if __name__ == "__main__":
    for name, fn in list(globals().items()):
        if name.startswith("test_"):
            fn()
    print("ok")
