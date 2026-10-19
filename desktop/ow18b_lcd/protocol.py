"""The OW18B's Bluetooth protocol: live packets, device info, keys, commands, offline logs.
See PROTOCOL.md for the format."""
from __future__ import annotations

import datetime as dt
import re
import struct
from dataclasses import dataclass, field

NAME = "BDM"  # what the meter advertises as out of the box
SERVICE = "0000fff0-0000-1000-8000-00805f9b34fb"
COMMAND = "0000fff1-0000-1000-8000-00805f9b34fb"  # 16-byte commands, read back for replies
INFO = "0000fff2-0000-1000-8000-00805f9b34fb"  # device info, read once
KEYS = "0000fff3-0000-1000-8000-00805f9b34fb"  # key presses
NOTIFY = "0000fff4-0000-1000-8000-00805f9b34fb"  # live packets and offline-log dumps

# function code -> (label shown on the LCD, unit)
FUNCTIONS = {
    0: ("DC", "V"), 1: ("AC", "V"), 2: ("DC", "A"), 3: ("AC", "A"),
    4: ("", "Ω"), 5: ("", "F"), 6: ("", "Hz"), 7: ("", "%"),
    8: ("", "°C"), 9: ("", "°F"), 10: ("DIODE", "V"), 11: ("CONT", "Ω"),
    12: ("hFE", ""), 13: ("NCV", ""),
}
FUNCTION_NAMES = {
    0: "DC voltage", 1: "AC voltage", 2: "DC current", 3: "AC current", 4: "Resistance", 5: "Capacitance",
    6: "Frequency", 7: "Duty cycle", 8: "Temperature", 9: "Temperature", 10: "Diode", 11: "Continuity",
    12: "hFE", 13: "NCV",
}
PREFIXES = {0: "p", 1: "n", 2: "µ", 3: "m", 4: "", 5: "k", 6: "M", 7: "G"}
EXPONENTS = {0: -12, 1: -9, 2: -6, 3: -3, 4: 0, 5: 3, 6: 6, 7: 9}
NCV = 13
MODELS = {18: "OW18B", 20: "OW18E", 33: "B33", 35: "B35", 41: "B41T", 21: "CM2100B"}

# Key codes for FFF3, tested on an OW18B (firmware 3.0.4): [key, 01] short press, [key, 00] long.
KEY_SELECT, KEY_RANGE, KEY_HOLD, KEY_HZ = 1, 2, 3, 5
SHORT, LONG = 1, 0

MAX_RECORDS = 10_000
NAME_CHARS = re.compile(r"^[A-Za-z0-9_]{1,15}$")


@dataclass(frozen=True)
class Reading:
    function: int
    scale: int
    decimals: int  # 7 means overload
    raw: int  # signed display counts
    hold: bool = False
    rel: bool = False
    auto: bool = False
    low_battery: bool = False

    @property
    def overload(self) -> bool:
        return self.decimals == 7

    @property
    def mode(self) -> str:
        return FUNCTIONS.get(self.function, (f"F{self.function}", ""))[0]

    @property
    def name(self) -> str:
        """'DC voltage', 'Resistance'..."""
        return FUNCTION_NAMES.get(self.function, f"Function {self.function}")

    @property
    def unit(self) -> str:
        """Unit with its prefix, e.g. 'mV', 'kΩ'."""
        unit = FUNCTIONS.get(self.function, ("", "?"))[1]
        if not unit or self.function in (7, 8, 9):  # %, °C and °F never take a prefix
            return unit
        return PREFIXES.get(self.scale, "") + unit

    @property
    def base_unit(self) -> str:
        return FUNCTIONS.get(self.function, ("", "?"))[1]

    @property
    def digits(self) -> str:
        """What the LCD shows: '-1.234', 'OL', '26', or 'EF' / bars for NCV."""
        if self.overload:
            return "OL"
        if self.function == NCV:
            return "EF" if self.raw == 0 else "-" * min(self.raw, 4)
        if self.decimals == 0:
            return str(self.raw)
        s = f"{abs(self.raw) / 10 ** self.decimals:.{self.decimals}f}"
        return ("-" if self.raw < 0 else "") + s

    @property
    def value(self) -> float | None:
        """The reading in base units (volts, ohms...), or None for overload and NCV."""
        if self.overload or self.function == NCV or self.decimals > 5:
            return None
        exp = 0 if self.function in (7, 8, 9) else EXPONENTS.get(self.scale, 0)
        # Rounded to the display's resolution, so -7.27 mV is -0.00727, not -0.007270000000000001
        return round(self.raw / 10 ** self.decimals * 10.0 ** exp, self.decimals - exp)

    @property
    def flags(self) -> str:
        return " ".join(t for on, t in ((self.hold, "HOLD"), (self.rel, "REL"), (self.auto, "AUTO"),
                                        (self.low_battery, "LOWBAT")) if on)

    @property
    def spoken(self) -> str:
        """The reading as words for text-to-speech."""
        if self.overload:
            return "overload"
        if self.function == NCV:
            return "no field" if self.raw == 0 else f"field level {self.raw}"
        words = {"V": "volts", "A": "amps", "Ω": "ohms", "F": "farads", "Hz": "hertz", "%": "percent",
                 "°C": "degrees", "°F": "degrees Fahrenheit"}
        prefix = {"n": "nano", "µ": "micro", "m": "milli", "k": "kilo", "M": "mega"}
        unit = self.unit
        p = prefix.get(unit[:1], "") if len(unit) > 1 and unit not in ("Hz", "°C", "°F") else ""
        base = words.get(unit[1:] if p else unit, unit)
        text = self.digits.replace("-", "minus ")
        return f"{text} {p}{base}".strip()


def decode(packet: bytes) -> Reading | None:
    """One 6-byte packet -> Reading, or None for the blank frame sent while the dial turns."""
    if len(packet) < 6:
        return None
    word = int.from_bytes(packet[0:2], "little")
    flags = int.from_bytes(packet[2:4], "little")
    raw = int.from_bytes(packet[4:6], "little")
    function, scale, decimals = (word >> 6) & 0x0F, (word >> 3) & 0x07, word & 0x07
    # Turning the dial or pressing Select first sends "0 Ω, no decimals" (20 f1 .. .. 00 00),
    # which no real Ω range shows.
    if function == 4 and scale == 4 and decimals == 0 and raw == 0:
        return None
    value = -(raw & 0x7FFF) if raw & 0x8000 else raw
    return Reading(function, scale, decimals, value,
                   hold=bool(flags & 1), rel=bool(flags & 2), auto=bool(flags & 4), low_battery=bool(flags & 8))


def split(data: bytes) -> list[bytes]:
    """Notifications sometimes carry several packets back to back."""
    return [data[i:i + 6] for i in range(0, len(data) - 5, 6)]


# --- Device info (FFF2) ---

@dataclass(frozen=True)
class DeviceInfo:
    model_code: int
    battery: int | None  # percent
    firmware: str
    recording: bool  # an offline log is being recorded

    @property
    def model(self) -> str:
        return MODELS.get(self.model_code, f"model {self.model_code}")

    @property
    def firmware_number(self) -> int:
        return int(self.firmware.replace(".", "") or 0)


def parse_info(data: bytes) -> DeviceInfo:
    fw = ".".join(str(b) for b in data[2:5]) if len(data) >= 5 else ""
    battery = data[1] if len(data) > 1 and data[1] <= 100 else None
    return DeviceInfo(data[0] if data else 0, battery, fw, len(data) > 5 and data[5] == 1)


# --- Commands (FFF1, 16 bytes each) and keys (FFF3) ---

def _pad(b: bytes) -> bytes:
    return b.ljust(16, b"\0")


def key(code: int, press: int = SHORT) -> bytes:
    return bytes([code, press])


def set_clock(now: dt.datetime) -> bytes:
    return _pad(b"*DATe" + bytes([now.year // 100, now.year % 100, now.month, now.day, now.hour, now.minute, now.second]))


def start_record(interval_s: int, count: int) -> bytes:
    if not 1 <= count <= MAX_RECORDS or interval_s < 1:
        raise ValueError(f"interval must be at least 1 s and count 1-{MAX_RECORDS}")
    return _pad(b"*RECOrd," + struct.pack("<II", interval_s, count))


STOP_RECORD = _pad(b"*STOP")
READ_LENGTH = _pad(b"*READlen?")
READ_LOG = _pad(b"*READ1?")  # firmware 0.1.2 and later
READ_LOG_OLD = _pad(b"*READ?")


def rename(name: str) -> bytes:
    if not NAME_CHARS.match(name):
        raise ValueError("Use 1-15 letters, digits or underscores")
    return _pad(b"@" + name.encode())


# --- Offline log dumps (FFF4, after READ_LOG) ---

MARK = b"\xff" * 20


@dataclass
class OfflineLog:
    start: dt.datetime | None
    interval: int
    readings: list[tuple[float, Reading]] = field(default_factory=list)  # (seconds from start, reading)


def dump_complete(buf: bytes) -> bool:
    i = buf.find(MARK)
    return i >= 0 and buf.find(MARK, i + len(MARK)) > 0


def parse_dump(buf: bytes, dated: bool = True) -> OfflineLog:
    """The bytes received after READ_LOG (live packets around the dump are skipped)."""
    i = buf.find(MARK)
    if i < 0:
        raise ValueError("no log in the data")
    start = i + len(MARK)
    while start < len(buf) and buf[start] == 0xFF:  # a longer leading run
        start += 1
    end = buf.find(MARK, start)
    if end < 0:
        raise ValueError("the log was cut off")
    p = buf[start:end]
    when, interval, offset = None, 1, 4
    if dated:
        cc, yy, mo, dd, hh, mi, ss = p[0:7]
        interval = struct.unpack("<I", p[8:12])[0] or 1
        offset = 16
        try:
            when = dt.datetime(cc * 100 + yy if cc else 2000 + yy, mo, dd, hh, mi, ss)
        except ValueError:
            when = None  # the meter's clock was never set
    log = OfflineLog(when, interval)
    current: tuple[int, int, int] | None = None
    n = 0
    for k in range(offset, len(p) - 1, 2):
        w = int.from_bytes(p[k:k + 2], "little")
        if (w >> 10) == 0b111100:  # a function/range word for the readings after it
            current = ((w >> 6) & 0x0F, (w >> 3) & 0x07, w & 0x07)
            continue
        if current is None:
            continue
        raw = -(w & 0x7FFF) if w & 0x8000 else w
        log.readings.append((n * interval, Reading(*current, raw)))
        n += 1
    return log
