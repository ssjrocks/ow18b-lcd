# OW18B LCD

A virtual LCD for the **OWON OW18B** Bluetooth multimeter, for the Linux desktop and Android. It shows
what the meter's own screen shows, live, with a scrolling graph of the last minute.

- Seven-segment digits with sign, decimal point and overload (`OL`), plus the AUTO, HOLD, REL, DC/AC,
  NCV, diode, continuity and low-battery annunciators.
- Trend graph of the last 60 seconds, with SI-prefixed axis labels. It keeps going across auto-range
  changes (mV → V) and clears when you turn the dial to another function.
- Finds the meter by itself and reconnects when you switch it off and on again.
- Demo mode that replays readings recorded from a real meter, for trying it without one.

The packet format is in [PROTOCOL.md](PROTOCOL.md).

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

Run `ow18b-lcd` (or `ow18b-lcd --demo`). The menu toggles the graph. To keep the window above others
on GNOME, right-click its title bar and choose **Always on Top**. Remove it with
`desktop/install.sh --uninstall`.

To run from the checkout instead: `cd desktop && python3 -m ow18b_lcd` (with bleak installed).

## Android

Download `ow18b-lcd.apk` from the [latest release](https://github.com/ssjrocks/ow18b-lcd/releases/latest)
and install it. Android 8 or later with Bluetooth LE. The app asks for the Nearby devices permission
(Android 12+) or location (Android 8–11, which Android requires for Bluetooth scans; the app never
uses your location).

The menu has the trend graph, keep-screen-on (on by default while connected), demo mode, update
check and problem reports. Turn the phone sideways for the LCD and graph side by side. The app lets
go of the meter while it's in the background, so the meter's own app or the desktop app can connect.

**Updates:** the app checks this repository's latest release at most every 12 hours and offers to
install a newer version. Downloads are checked against the SHA-256 in the release, and Android only
installs them over the app when they're signed with the same key.

**Crash and problem reports** go to the author's server (ssjrocks.com) so bugs can be fixed. A report
holds the app version, the device model and Android version, a random per-install id, memory figures,
the app's own log lines and Android's log lines from this app's process, the stack trace for crashes,
and the meter status (connected or not, the last packet). "Report a problem" adds the note you type.
Nothing else is sent, and your location is never sent. IP addresses are used only for rate limiting
and aren't stored.

### Building

```bash
cd android
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
```

Release builds are signed with the key named in `android/keystore.properties` (not in the repo); without
one they fall back to the debug key. `scripts/release.sh NOTES.md` publishes a signed release for the
updater.

## Licence

MIT
