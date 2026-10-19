"""Decoding the OW18B's Bluetooth packets. See PROTOCOL.md for the format."""
from __future__ import annotations

from dataclasses import dataclass

NAME = "BDM"  # what the meter advertises as
SERVICE = "0000fff0-0000-1000-8000-00805f9b34fb"
NOTIFY = "0000fff4-0000-1000-8000-00805f9b34fb"

# function code -> (label shown on the LCD, unit)
FUNCTIONS = {
    0: ("DC", "V"), 1: ("AC", "V"), 2: ("DC", "A"), 3: ("AC", "A"),
    4: ("", "Ω"), 5: ("", "F"), 6: ("", "Hz"), 7: ("", "%"),
    8: ("", "°C"), 9: ("", "°F"), 10: ("DIODE", "V"), 11: ("CONT", "Ω"),
    12: ("hFE", ""), 13: ("NCV", ""),
}
PREFIXES = {1: "n", 2: "µ", 3: "m", 4: "", 5: "k", 6: "M"}
EXPONENTS = {1: -9, 2: -6, 3: -3, 4: 0, 5: 3, 6: 6}
NCV = 13


@dataclass(frozen=True)
class Reading:
    function: int
    scale: int
    decimals: int  # 7 means overload
    raw: int  # signed display counts
    hold: bool
    rel: bool
    auto: bool
    low_battery: bool

    @property
    def overload(self) -> bool:
        return self.decimals == 7

    @property
    def mode(self) -> str:
        return FUNCTIONS.get(self.function, (f"F{self.function}", ""))[0]

    @property
    def unit(self) -> str:
        """Unit with its prefix, e.g. 'mV', 'kΩ'."""
        unit = FUNCTIONS.get(self.function, ("", "?"))[1]
        return PREFIXES.get(self.scale, "") + unit if unit else ""

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
        if self.overload or self.function == NCV:
            return None
        return self.raw / 10 ** self.decimals * 10.0 ** EXPONENTS.get(self.scale, 0)

    def same_quantity(self, other: Reading | None) -> bool:
        return other is not None and other.function == self.function


def decode(packet: bytes) -> Reading | None:
    """One 6-byte packet -> Reading, or None for the blank frame sent while the dial turns."""
    if len(packet) < 6:
        return None
    word = int.from_bytes(packet[0:2], "little")
    flags = int.from_bytes(packet[2:4], "little")
    raw = int.from_bytes(packet[4:6], "little")
    function, scale, decimals = (word >> 6) & 0x0F, (word >> 3) & 0x07, word & 0x07
    # Turning the dial sends "0 Ω, no decimals" (20 f1 .. .. 00 00), which no real Ω range shows.
    if function == 4 and scale == 4 and decimals == 0 and raw == 0:
        return None
    value = -(raw & 0x7FFF) if raw & 0x8000 else raw
    return Reading(function, scale, decimals, value,
                   hold=bool(flags & 1), rel=bool(flags & 2), auto=bool(flags & 4), low_battery=bool(flags & 8))


def split(data: bytes) -> list[bytes]:
    """Notifications sometimes carry several packets back to back."""
    return [data[i:i + 6] for i in range(0, len(data) - 5, 6)]
