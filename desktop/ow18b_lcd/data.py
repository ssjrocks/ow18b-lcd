"""Recordings, statistics, alarms and CSV export. No GTK in here."""
from __future__ import annotations

import csv
import datetime as dt
import math
import re
from dataclasses import dataclass, field
from pathlib import Path

from .protocol import OfflineLog, Reading

PREFIX = {-12: "p", -9: "n", -6: "µ", -3: "m", 0: "", 3: "k", 6: "M", 9: "G"}
PREFIX_VALUE = {"p": -12, "n": -9, "u": -6, "µ": -6, "m": -3, "": 0, "k": 3, "K": 3, "M": 6, "G": 9}


def exponent(value: float) -> int:
    """The SI exponent (multiple of 3) that puts |value| in 1..999."""
    if value == 0 or not math.isfinite(value):
        return 0
    return max(-12, min(9, int(math.floor(math.log10(abs(value)) / 3) * 3)))


def si(value: float, unit: str, exp: int | None = None, decimals: int | None = None) -> str:
    """1.234e-3, 'V' -> '1.234 mV'. Units without prefixes (%, °C, °F) stay as they are."""
    if unit in ("%", "°C", "°F", ""):
        return f"{value:.{2 if decimals is None else decimals}f} {unit}".strip()
    if exp is None:
        exp = exponent(value)
    scaled = value / 10 ** exp
    if decimals is None:
        decimals = max(0, 3 - (len(str(int(abs(scaled)))) if scaled else 1))
    return f"{scaled:.{decimals}f} {PREFIX[exp]}{unit}".strip()


def parse_si(text: str, unit: str = "") -> float:
    """'4.5', '450m', '450 mV', '1.2k' -> base-unit float. Raises ValueError."""
    t = text.strip().replace(",", ".")
    if unit and t.endswith(unit):
        t = t[: -len(unit)].strip()
    m = re.fullmatch(r"([-+]?\d*\.?\d+(?:[eE][-+]?\d+)?)\s*([pnuµmkKMG]?)", t)
    if not m:
        raise ValueError(f"not a number: {text!r}")
    return float(m.group(1)) * 10 ** PREFIX_VALUE[m.group(2)]


@dataclass
class Sample:
    time: float  # Unix time
    reading: Reading


@dataclass
class Series:
    """A list of readings over time: a recording made here, or an offline log read from the meter."""
    title: str
    samples: list[Sample] = field(default_factory=list)
    source: str = "recording"  # or "offline"

    @classmethod
    def from_offline(cls, log: OfflineLog) -> "Series":
        start = (log.start or dt.datetime.now()).timestamp()
        when = log.start.strftime("%Y-%m-%d %H:%M") if log.start else "unknown time"
        s = cls(f"Offline log, {when}, every {log.interval} s", source="offline")
        s.samples = [Sample(start + t, r) for t, r in log.readings]
        return s

    def add(self, r: Reading, when: float) -> None:
        self.samples.append(Sample(when, r))

    @property
    def duration(self) -> float:
        return self.samples[-1].time - self.samples[0].time if len(self.samples) > 1 else 0.0

    def write_csv(self, path: Path) -> None:
        """One row per reading. 'value' is in the base unit (V, A, Ω...), so a range change from
        mV to V stays one column; 'display' and 'display_unit' are what the LCD showed."""
        with open(path, "w", newline="", encoding="utf-8") as f:
            w = csv.writer(f)
            w.writerow(["time", "seconds", "value", "unit", "display", "display_unit", "function", "flags"])
            t0 = self.samples[0].time if self.samples else 0
            for s in self.samples:
                r = s.reading
                value = r.value
                w.writerow([
                    dt.datetime.fromtimestamp(s.time).isoformat(timespec="milliseconds"),
                    f"{s.time - t0:.3f}",
                    "" if value is None else repr(value),
                    r.base_unit, r.digits, r.unit, r.name, r.flags,
                ])


@dataclass
class Stats:
    """Min, max and average since reset, for one function at a time."""
    function: int | None = None
    unit: str = ""
    count: int = 0
    total: float = 0.0
    low: float = math.inf
    high: float = -math.inf
    exp: int = 0  # the LCD's prefix, so the figures read like the meter
    decimals: int = 2

    def reset(self) -> None:
        self.count, self.total, self.low, self.high = 0, 0.0, math.inf, -math.inf

    def add(self, r: Reading) -> None:
        if r.function != self.function:
            self.function, self.unit = r.function, r.base_unit
            self.reset()
        v = r.value
        if v is None:
            return
        if r.function not in (7, 8, 9):
            from .protocol import EXPONENTS
            self.exp = EXPONENTS.get(r.scale, 0)
        self.decimals = r.decimals
        self.count += 1
        self.total += v
        self.low, self.high = min(self.low, v), max(self.high, v)

    @property
    def average(self) -> float | None:
        return self.total / self.count if self.count else None

    def describe(self) -> str:
        if not self.count:
            return "Min –   Max –   Avg –"
        f = lambda v: si(v, self.unit, self.exp, self.decimals)  # noqa: E731
        return f"Min {f(self.low)}   Max {f(self.high)}   Avg {f(self.average)}   ({self.count})"


@dataclass
class Alarm:
    """High/low limits for one base unit; trips when a reading goes outside them."""
    enabled: bool = False
    unit: str = "V"
    low: float | None = None
    high: float | None = None

    def check(self, r: Reading) -> str | None:
        """A message when this reading is out of limits, else None."""
        if not self.enabled or r.base_unit != self.unit:
            return None
        v = r.value
        if v is None:
            return "Overload" if r.overload and self.high is not None else None
        if self.high is not None and v > self.high:
            return f"{si(v, self.unit)} is above {si(self.high, self.unit)}"
        if self.low is not None and v < self.low:
            return f"{si(v, self.unit)} is below {si(self.low, self.unit)}"
        return None

    def to_dict(self) -> dict:
        return {"enabled": self.enabled, "unit": self.unit, "low": self.low, "high": self.high}

    @classmethod
    def from_dict(cls, d: dict) -> "Alarm":
        return cls(bool(d.get("enabled")), d.get("unit", "V"), d.get("low"), d.get("high"))
