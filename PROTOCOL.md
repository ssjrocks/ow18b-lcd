# OW18B Bluetooth protocol

What the OWON OW18B sends over Bluetooth LE, worked out from a real meter (2026-10-19) and from
earlier notes on OWON's B35T/OW18E family, which share the format.

## Connection

- Advertised name: **`BDM`**. No pairing needed; connect directly.
- Service `0000fff0-0000-1000-8000-00805f9b34fb`, characteristic **`fff4` (notify)**. Turn on
  notifications by writing `01 00` to its descriptor `2902`.
- The meter then sends a 6-byte packet about twice a second (faster after a mode change).
  A notification can hold several packets back to back.
- Only one client at a time: while one app is connected, others (OWON's own app included) can't see it.

## Packet

Three little-endian 16-bit words:

| Bytes | Meaning |
|---|---|
| 0–1 | bits 0–2 decimals (7 = overload, "OL"), bits 3–5 scale, bits 6–9 function. Bits 12–15 are always set (`fxxx`) |
| 2–3 | flags: bit 0 HOLD, bit 1 REL, bit 2 AUTO range, bit 3 low battery |
| 4–5 | displayed value in counts, sign-magnitude: bit 15 = negative, bits 0–14 = magnitude |

The reading is `counts / 10^decimals`, in the unit the function and scale give.

**Scale:** 1 n, 2 µ, 3 m, 4 (none), 5 k, 6 M.

**Function:**

| Code | Mode | Seen on a real meter |
|---|---|---|
| 0 | DC V | yes: `19 f0` (0.0 mV), `1a f0` (0.00 mV range) |
| 1 | AC V | not yet |
| 2 | DC A | yes: `91 f0` µA, `9a f0` mA, `a2 f0` A (A range has no AUTO flag) |
| 3 | AC A | not yet |
| 4 | Ω | yes: `27 f1` OL Ω, `2f f1` OL kΩ, `37 f1` OL MΩ while auto-ranging open |
| 5 | Capacitance (F) | yes: `4a f1` 0.00 nF |
| 6 | Frequency (Hz) | yes: `a3 f1` 0.000 Hz |
| 7 | Duty cycle (%) | not yet |
| 8 | Temperature °C | yes: `20 f2`, 26 °C |
| 9 | Temperature °F | not yet |
| 10 | Diode (V) | not yet |
| 11 | Continuity (Ω) | not yet |
| 12 | hFE | not yet |
| 13 | NCV | yes: `60 f3`, value 0–3 = field strength. The apps show `EF` for 0 and bars otherwise |

**Dial-turn frame:** every turn of the dial first sends `20 f1 xx xx 00 00` ("0 Ω, no decimals"),
which no real Ω range produces. The apps ignore it.

Example: `1a f0 04 00 d7 82` → word `f01a`: function 0 (DC V), scale 3 (m), 2 decimals; flags
`0004` AUTO; value `82d7` → negative, 0x2d7 = 727 → **−7.27 mV**.
