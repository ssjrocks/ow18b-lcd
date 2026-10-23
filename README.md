# OW18B LCD

A virtual LCD and remote control for the **OWON OW18B** Bluetooth multimeter, for the Linux desktop
and Android. It uses everything the meter offers over Bluetooth:

- **Live LCD:** seven-segment digits with sign, decimal point and overload (`OL`), and the AUTO, HOLD,
  REL, DC/AC, NCV, diode, continuity, low-battery, Bluetooth and REC annunciators.
- **Remote keys:** Select, Range (hold: back to auto range), Hold (hold: backlight and torch) and
  Hz/Duty △ (Hz/duty on AC, relative on DC). The rotary dial still has to be turned by hand.
- **Meter info:** model, firmware and battery level, with a warning when the battery is low.
- **Recording:** log readings to CSV (saved automatically), with a table and a graph you can zoom and
  pan. Values are kept in base units, so a range change from mV to V stays one column.
- **Offline log:** the meter records up to 10,000 readings into its own memory at an interval you set,
  with nothing connected; read it back later as a graph, a table and CSV. The app sets the meter's
  clock first, so the log has the right times.
- **Min / max / average** since reset, and an **alarm** with high and low limits (sound, notification,
  the LCD turns red, the limits show on the graph).
- **Speak readings** aloud every few seconds, for when your eyes are on the probes.
- **Rename the meter** (the name is stored in it) and **choose between meters**; connect and disconnect.
- Trend graph of the last minute; finds the meter by itself and reconnects; demo mode with readings
  recorded from a real meter.

The Bluetooth protocol is in [PROTOCOL.md](PROTOCOL.md); research on the meter and OWON's own apps is in
[docs/RESEARCH.md](docs/RESEARCH.md).

**About offline logs:** the meter turns its Bluetooth off when an offline log starts. To read the log,
turn Bluetooth back on by holding Hz/Duty △/ᛒ for about 2 s, then choose *Read log*. Starting a new
log replaces the one in the meter. While Bluetooth is on (and nothing is connected) the meter turns it
off by itself after 10 minutes.

## Desktop (Linux, GTK 4)

Needs Python 3, GTK 4 and libadwaita with their Python bindings, and BlueZ. On Ubuntu 24.04 or later:

```bash
sudo apt install python3-gi gir1.2-gtk-4.0 gir1.2-adw-1
```

Then install for your user. This puts a venv with [bleak](https://github.com/hbldh/bleak) in
`~/.local/share/ow18b-lcd`, the `ow18b-lcd` command in `~/.local/bin`, and a launcher in the app grid:

```bash
desktop/install.sh
```

Run `ow18b-lcd` (or `ow18b-lcd --demo`). Keyboard: S Select, R Range (Shift+R auto), H Hold (Shift+H
light), Z Hz/Duty, Ctrl+R record, Ctrl+I meter info. Recordings and offline logs are saved to
`~/Documents/OW18B LCD/`. Speaking readings uses speech-dispatcher (`spd-say`). To keep the window
above others on GNOME, right-click its title bar and choose **Always on Top**. Remove it with
`desktop/install.sh --uninstall`.

To run from the checkout instead: `cd desktop && python3 -m ow18b_lcd` (with bleak installed).

## Android

Download `ow18b-lcd.apk` from the [latest release](https://github.com/ssjrocks/ow18b-lcd/releases/latest)
and install it. Android 8 or later with Bluetooth LE. The app asks for the Nearby devices permission
(Android 12+) or location (Android 8–11, which Android requires for Bluetooth scans; the app never
uses your location).

Press and hold Range or Hold ☀ for their long-press functions. Recordings keep running with the screen
off or another app in front (a notification shows the count); otherwise the app lets go of the meter
while it's in the background, so the desktop app can connect. Recordings and offline logs are saved in
the app's folder and can be shared or saved anywhere with *Share* and *Save as*. Turn the phone
sideways for the meter and the graphs side by side. The app asks to show notifications for
recordings and alarms.

**Updates:** the app checks this repository's latest release at most every 12 hours and offers to
install a newer version. Downloads are checked against the SHA-256 in the release, and Android only
installs them over the app when they're signed with the same key.

**Crash and problem reports** go to the author's server so bugs can be fixed. A report
holds the app version, the device model and Android version, a random per-install id, memory figures,
the app's own log lines and Android's log lines from this app's process, the stack trace for crashes,
and the meter status (connected or not, model, firmware and battery, the last packet, how many readings
are being recorded). "Report a problem" adds the note you type.
Nothing else is sent, and your location is never sent. IP addresses are used only for rate limiting
and aren't stored.

### Building

```bash
cd android
./gradlew assembleDebug testDebugUnitTest   # app/build/outputs/apk/debug/app-debug.apk
```

Desktop tests: `python3 desktop/tests/test_protocol.py && python3 desktop/tests/test_data.py`.

Release builds are signed with the key named in `android/keystore.properties` (not in the repo); without
one they fall back to the debug key. `scripts/release.sh NOTES.md` publishes a signed release for the
updater.

## Licence

MIT
