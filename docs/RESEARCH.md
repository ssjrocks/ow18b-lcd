# OW18B and OWON's apps: what they can do

Research notes, 2026-10-19, for filling out OW18B LCD's features. Sources are listed at the end.
Protocol details are in [PROTOCOL.md](../PROTOCOL.md).

## The meter

OWON OW18B: 6000-count (5999) true-RMS handheld meter with a BLE 4.0 module, in a bright blue rubber
boot with a black face. Same body as the OW18A (no Bluetooth); the OW18E is the 20000-count sibling.

- **Panel:** large LCD; keys **Select**, **☀/H** (short: HOLD, long: backlight + flashlight),
  **Range** (short: manual range step, long: back to auto), **Hz/Duty △/ᛒ** (Hz/duty on AC, REL △,
  long: Bluetooth on); a TRUE RMS badge between the top keys; rotary dial; jacks 20A, µA mA, COM, VΩHz.
- **Functions:** DC/AC V (to 1000 V DC / 750 V AC), mV, DC/AC A (µA, mA, 20 A), Ω (to 60 MΩ),
  continuity, diode, capacitance (to 60 mF), Hz (to 10 MHz) and duty, temperature °C/°F with a K-type
  probe, NCV, hFE on some units. About 3 readings a second.
- **Other:** flashlight on top, tilt stand, 9 V battery, CAT III 1000 V / CAT IV 600 V.
- **Bluetooth:** advertises as `BDM`; one central at a time; switches itself off after 10 minutes
  idle (two beeps); while Bluetooth is on, auto power-off (30 min) is suspended.
- **Offline logging ("built-in offline recording"):** the meter logs up to **10,000 readings** into its
  own memory at a set interval while no phone is connected, and the app reads them back later. One
  recording is kept; a new one overwrites it.

## OWON's apps

| | Multimeter BLE (Android/iOS, 2015-2020) | iMeter (Android/iOS, 2022-2025) | multimeterBLE PC (Windows 7/8, CC2540 dongle) | iMeter for Windows 10/11 (2025) |
|---|---|---|---|---|
| Live reading | yes, landscape only | yes | yes | yes (not analysed) |
| Several meters at once | 2 (single / double view) | device list, "ALL" page | up to 3 | presumably as iMeter |
| Graph | yes | yes, zoom and pan, several meters | trend graph | |
| Table of readings | yes | yes | yes, auto-scroll | |
| Online recording, save/load | yes | save, load, share (CSV) | save CSV, clear | |
| Offline recording | yes (interval + count) | yes (interval + count, default 1 s × 100) | yes | |
| Read back offline log | as CSV | as CSV | as CSV | |
| Stop a running offline log | | offered on reconnect | | |
| Remote keys, short/long | yes | Select, Range, Hold/Light, △/Hz/Duty | yes | |
| Rename the meter | yes (stored in the meter) | yes | | |
| Alarm on readings ("abnormal data warning") | | yes | | |
| Voice read-out | yes ("voice out") | | | |
| "Unify units" (mV/V into one column) | | | yes | |
| Battery, firmware shown | | firmware | battery logged | |

Common complaints in reviews: landscape-only and two-meter-by-default screens (old app), sampling
settings re-entered every time, no auto-reconnect to a known meter, files easy to delete by mistake,
the manual says little about online vs offline logging. Windows needs OWON's own USB dongle for the
older PC software.

## What the Bluetooth link offers

From OWON's own app and PC software (as read by the dmm-tools project), checked against community
captures. ✓ = we already use it.

| Capability | How | Status |
|---|---|---|
| Live readings, flags (HOLD, REL, AUTO, low battery) | FFF4 notifications | ✓ |
| Model, firmware, battery %, "recording" flag | read FFF2 once after connecting | to add |
| Remote keys, short and long press | write `[key, 01]` (short) / `[key, 00]` (long) to FFF3: 1 Select, 2 Range, 3 Hold/Light, 5 △/Hz/Duty (one community project uses 4 on the OW18B: to test) | to add |
| Rename the meter (advertised name) | `@` + name (≤ 15 of `A-Z a-z 0-9 _`), 16 bytes, to FFF1 | to add |
| Set the meter's clock | `*DATe` + CC YY MM DD hh mm ss, 16 bytes, to FFF1 | to add (before recording) |
| Start offline recording | `*RECOrd,` + interval (s, u32 LE) + count (u32 LE, ≤ 10,000); the meter disconnects ~2 s later | to add |
| Stop offline recording | `*STOP` | to add |
| Size of the stored log | `*READlen?`, then read FFF1 | to add |
| Read the stored log | `*READ1?` (firmware ≥ 0.1.2; `*READ?` before); the dump arrives on FFF4 between two runs of 20 `FF` bytes: start time, interval, length, then range words and readings | to add |
| Authentication challenge | OWON's app checks the meter is genuine; the meter streams without it | not needed |

## Gap list for OW18B LCD

Already done: live LCD, annunciators, 60 s trend, auto-reconnect, demo mode, Android updates and
reports.

Worth adding, roughly in order of value:

1. **Remote keys** on both apps: Select, Range, Hold, Hz/Duty/REL, each with a long press
   (auto range, backlight/flashlight). Needs a hardware check of the Hz key code (4 or 5).
2. **Meter info:** model, firmware, battery %, shown in an info dialog; warn when the battery is low.
3. **Recording to a file on the computer/phone** (online log): start/stop, CSV export with time,
   value, unit, mode and flags; a readings table; a longer, zoomable graph than the 60 s trend.
4. **Offline recording:** set interval and count, start (with the clock set first), see that the meter
   is recording when it reconnects, stop, read back to CSV and graph it.
5. **Statistics:** min / max / average since reset (the meter itself has no MAX/MIN key).
6. **Alarms:** high/low limits with a sound or notification, like iMeter's "abnormal data warning".
7. **Rename** the meter, and pick between several meters (both OWON apps handle two or three).
8. **Voice read-out** of the reading (the old OWON app's "voice out"), useful when your eyes are on
   the probes.
9. **Unit handling** in logs: keep base units so a mV → V range change stays one series (we already
   do this in the graph).

## Sources

- [antoinecellerier/dmm-tools, OWON protocol reference](https://github.com/antoinecellerier/dmm-tools/blob/main/docs/research/owon/reverse-engineered-protocol.md):
  a clean-room reading of OWON's iMeter 1.2.4 Android app and pcMultimeter 1.4.5 PC source, with
  community captures (§7 commands, §8 offline records, §14 cross-checks); [issue #40](https://github.com/antoinecellerier/dmm-tools/issues/40)
- OWON OW18 series user manual on [ManualsLib](https://www.manualslib.com/manual/1813001/Owon-Ow18-Series.html)
  (Android app p.23-27, offline record p.27, PC software p.36-47)
- App Store: [OWON Multimeter BLE](https://apps.apple.com/app/id1049176636),
  [OWON iMeter](https://apps.apple.com/app/id1607020484) (descriptions and reviews)
- [Elektor review of the OW18E](https://elektormagazine.com/news/review-owon-ow18e-bluetooth-multimeter/23351)
- Product pages: [Elektor OW18B](https://www.elektor.com/products/owon-ow18b-true-rms-multimeter) (photos),
  [TME](https://www.tme.eu/html/EN/digital-multimeter-owon/ramka_28508_EN_pelny.html),
  [eleshop.eu](https://eleshop.eu/owon-ow18b-mv.html), [eleshop.eu USB dongle](https://eleshop.eu/owon-usb-bluetooth-dongle.html)
- OW18B community projects: [JayTee42/ow18b](https://github.com/JayTee42/ow18b),
  [MartMet/OW18B](https://github.com/MartMet/OW18B) (Web Bluetooth, keys)
