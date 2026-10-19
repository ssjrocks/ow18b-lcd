"""OW18B LCD: a GTK4 window showing what an OWON OW18B multimeter reads, live over Bluetooth."""
from __future__ import annotations

import argparse
import datetime as dt
import logging
import shutil
import signal
import subprocess
import sys
import time
from pathlib import Path

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
from gi.repository import Adw, Gio, GLib, Gtk  # noqa: E402

from . import __version__, dialogs, graph, lcd, meter, protocol  # noqa: E402
from .data import Alarm, Series, Stats  # noqa: E402
from .table import ReadingsTable  # noqa: E402

APP_ID = "com.ssjrocks.OW18BLcd"
SPEAK_EVERY = 5.0  # seconds between spoken readings
log = logging.getLogger(__name__)


def save_dir() -> Path:
    docs = GLib.get_user_special_dir(GLib.UserDirectory.DIRECTORY_DOCUMENTS) or str(Path.home())
    return Path(docs) / "OW18B LCD"


class SeriesPage(Gtk.Box):
    """Graph (scroll to zoom, drag to pan, double-click to reset) and table of one Series."""

    def __init__(self, toolbar: Gtk.Widget, empty: str):
        super().__init__(orientation=Gtk.Orientation.VERTICAL, spacing=6)
        self.plot = graph.SeriesPlot()
        self.markers = (None, None)
        self.title = Gtk.Label(label=empty, xalign=0, hexpand=True, ellipsize=3)
        self.title.add_css_class("heading")
        self.functions = Gtk.DropDown(visible=False, tooltip_text="Function to graph")
        self.functions.connect("notify::selected", self._function_chosen)
        bar = Gtk.Box(spacing=6)
        bar.append(self.title)
        bar.append(self.functions)
        bar.append(toolbar)
        self.area = Gtk.DrawingArea(hexpand=True, vexpand=True, content_height=150)
        self.area.set_draw_func(lambda _a, ctx, w, h: self.plot.draw(ctx, w, h, self.markers))
        scroll = Gtk.EventControllerScroll(flags=Gtk.EventControllerScrollFlags.VERTICAL)
        scroll.connect("scroll", self._scroll)
        self.area.add_controller(scroll)
        self._pointer_x = 0.5
        motion = Gtk.EventControllerMotion()
        motion.connect("motion", lambda _c, x, _y: setattr(self, "_pointer_x", x))
        self.area.add_controller(motion)
        drag = Gtk.GestureDrag()
        drag.connect("drag-begin", lambda *_: setattr(self, "_drag_last", 0.0))
        drag.connect("drag-update", self._drag)
        self.area.add_controller(drag)
        click = Gtk.GestureClick()
        click.connect("pressed", lambda _g, n, *_: n == 2 and (self.plot.reset(), self.area.queue_draw()))
        self.area.add_controller(click)
        frame = Gtk.Frame(child=self.area)
        frame.set_tooltip_text("Scroll to zoom, drag to pan, double-click to see it all")
        self.table = ReadingsTable()
        self.paned = Gtk.Paned(orientation=Gtk.Orientation.HORIZONTAL, start_child=frame, end_child=self.table,
                               resize_start_child=True, resize_end_child=False, shrink_end_child=True, vexpand=True)
        self.append(bar)
        self.append(self.paned)

    def fit(self, width: int) -> None:
        """Table beside the graph in a wide window, below it in a narrow one."""
        wide = width >= 820
        orientation = Gtk.Orientation.HORIZONTAL if wide else Gtk.Orientation.VERTICAL
        if self.paned.get_orientation() != orientation:
            self.paned.set_orientation(orientation)
            self.table.set_min_content_width(330 if wide else -1)
            self.table.set_min_content_height(-1 if wide else 140)

    def _plot_width(self) -> float:
        return max(1.0, self.area.get_width() - 82)

    def _scroll(self, _c, _dx, dy) -> bool:
        at = min(1.0, max(0.0, (self._pointer_x - 70) / self._plot_width()))
        self.plot.zoom(1.25 if dy > 0 else 0.8, at)
        self.area.queue_draw()
        return True

    def _drag(self, _g, dx, _dy) -> None:
        self.plot.pan(-(dx - self._drag_last) / self._plot_width())
        self._drag_last = dx
        self.area.queue_draw()

    def _function_chosen(self, *_):
        fns = self.plot.functions()
        i = self.functions.get_selected()
        if 0 <= i < len(fns):
            self.plot.function = fns[i]
            self.area.queue_draw()

    def show(self, series: Series | None) -> None:
        self.plot.set_series(series)
        self.title.set_label(series.title if series else "")
        self.table.set_samples(series.samples if series else [])
        self._refresh_functions()
        self.area.queue_draw()

    def appended(self, series: Series) -> None:
        """A reading was added while recording: the graph follows the meter's function."""
        self.table.append(series.samples[-1])
        fn = series.samples[-1].reading.function
        if fn != self.plot.function:
            self.plot.function = fn
            self._refresh_functions()
        self.area.queue_draw()

    def _refresh_functions(self) -> None:
        fns = self.plot.functions()
        names = [protocol.FUNCTION_NAMES.get(f, str(f)) + (f" ({protocol.FUNCTIONS.get(f, ('', ''))[0]})" if protocol.FUNCTIONS.get(f, ('', ''))[0] else "") for f in fns]
        self.functions.set_model(Gtk.StringList.new(names))
        self.functions.set_visible(len(fns) > 1)
        if fns:
            self.functions.set_selected(fns.index(self.plot.function) if self.plot.function in fns else len(fns) - 1)


class Window(Adw.ApplicationWindow):
    def __init__(self, app: Adw.Application, demo: bool):
        super().__init__(application=app, title="OW18B LCD", default_width=900, default_height=640)
        self.reading: protocol.Reading | None = None
        self.connected = False
        self.info: protocol.DeviceInfo | None = None
        self.trend = graph.Trend()
        self.stats = Stats()
        cfg = meter.load_config()
        self.alarm = Alarm.from_dict(cfg.get("alarm", {}))
        self.alarm_message: str | None = None
        self.alarm_sounded = 0.0
        self.speak = bool(cfg.get("speak", False))
        self.spoken_at = 0.0
        self.speaker: subprocess.Popen | None = None
        self.recording: Series | None = None
        self.last_recording: Series | None = None
        self.offline: Series | None = None
        self._long_pressed = False

        # Header
        self.title = Adw.WindowTitle(title="OW18B LCD", subtitle="Starting…")
        header = Adw.HeaderBar(title_widget=self.title)
        self.link = Gtk.Button(icon_name="bluetooth-active-symbolic", tooltip_text="Disconnect from the meter")
        self.link.connect("clicked", self._toggle_link)
        header.pack_start(self.link)
        self.record = Gtk.ToggleButton(icon_name="media-record-symbolic", tooltip_text="Record readings to a file")
        self.record.connect("toggled", self._toggle_recording)
        menu = Gio.Menu()
        meter_menu = Gio.Menu()
        meter_menu.append("Choose meter…", "win.choose")
        meter_menu.append("Meter info…", "win.info")
        meter_menu.append("Rename meter…", "win.rename")
        menu.append_section(None, meter_menu)
        tools = Gio.Menu()
        tools.append("Alarm…", "win.alarm")
        tools.append("Speak readings", "win.speak")
        tools.append("Reset min/max", "win.reset-stats")
        tools.append("Open saved files", "win.open-folder")
        menu.append_section(None, tools)
        about = Gio.Menu()
        about.append("Keyboard shortcuts", "win.shortcuts")
        about.append("About OW18B LCD", "win.about")
        menu.append_section(None, about)
        header.pack_end(Gtk.MenuButton(icon_name="open-menu-symbolic", menu_model=menu, tooltip_text="Menu"))
        header.pack_end(self.record)

        # Banners: offline log state, low battery, alarm
        self.offline_banner = Adw.Banner()
        self.offline_banner.connect("button-clicked", self._offline_banner_clicked)
        self.battery_banner = Adw.Banner()
        self.alarm_banner = Adw.Banner(button_label="Turn alarm off")
        self.alarm_banner.connect("button-clicked", lambda *_: self._save_alarm(Alarm(False, self.alarm.unit, self.alarm.low, self.alarm.high)))

        # LCD, keys, statistics
        self.lcd_area = Gtk.DrawingArea(hexpand=True, content_height=180)
        self.lcd_area.set_draw_func(self._draw_lcd)
        keys = Gtk.Box(spacing=6, homogeneous=True, halign=Gtk.Align.CENTER)
        self.key_buttons = []
        for label, code, tip, long_tip in (
            ("Select", protocol.KEY_SELECT, "Select: switch between the functions of this dial position (S)", None),
            ("Range", protocol.KEY_RANGE, "Range: step through ranges (R)", "hold for auto range (Shift+R)"),
            ("Hold ☀", protocol.KEY_HOLD, "Hold the reading (H)", "hold for the backlight and torch (Shift+H)"),
            ("Hz/Duty △", protocol.KEY_HZ, "Hz/Duty on AC, relative (△) on DC (Z)", None),
        ):
            b = Gtk.Button(label=label, tooltip_text=tip + (f"; {long_tip}" if long_tip else ""), width_request=110)
            b.connect("clicked", self._key_clicked, code)
            if long_tip:
                lp = Gtk.GestureLongPress()
                lp.connect("pressed", self._key_long, code)
                b.add_controller(lp)
            keys.append(b)
            self.key_buttons.append(b)
        self.stats_label = Gtk.Label(label=self.stats.describe(), xalign=0.5, selectable=True)
        self.stats_label.add_css_class("monospace")
        reset = Gtk.Button(icon_name="edit-clear-symbolic", tooltip_text="Reset min/max", valign=Gtk.Align.CENTER)
        reset.add_css_class("flat")
        reset.connect("clicked", lambda *_: self._reset_stats())
        stats_row = Gtk.Box(spacing=6, halign=Gtk.Align.CENTER)
        stats_row.append(self.stats_label)
        stats_row.append(reset)

        # Pages: live graph, recording, offline log
        self.stack = Adw.ViewStack(vexpand=True)
        self.trend_area = Gtk.DrawingArea(hexpand=True, vexpand=True, content_height=150)
        self.trend_area.set_draw_func(lambda _a, ctx, w, h: self.trend.draw(ctx, w, h, self._markers()))
        self.stack.add_titled_with_icon(Gtk.Frame(child=self.trend_area), "live", "Live", "utilities-system-monitor-symbolic")

        rec_tools = Gtk.Box(spacing=6)
        self.rec_save = Gtk.Button(label="Save as…", sensitive=False)
        self.rec_save.connect("clicked", lambda *_: self._save_as(self.recording or self.last_recording))
        rec_tools.append(self.rec_save)
        self.rec_page = SeriesPage(rec_tools, "Press ⏺ to record readings")
        self.stack.add_titled_with_icon(self.rec_page, "recording", "Recording", "media-record-symbolic")

        off_tools = Gtk.Box(spacing=6)
        self.off_start = Gtk.Button(label="Record on meter…")
        self.off_start.connect("clicked", lambda *_: self._start_offline())
        self.off_read = Gtk.Button(label="Read log from meter")
        self.off_read.connect("clicked", lambda *_: self._read_offline())
        self.off_save = Gtk.Button(label="Save as…", sensitive=False)
        self.off_save.connect("clicked", lambda *_: self._save_as(self.offline))
        for b in (self.off_start, self.off_read, self.off_save):
            off_tools.append(b)
        self.off_page = SeriesPage(off_tools, "The meter can log up to 10,000 readings on its own")
        self.stack.add_titled_with_icon(self.off_page, "offline", "Offline log", "document-save-symbolic")
        switcher = Adw.ViewSwitcher(stack=self.stack, policy=Adw.ViewSwitcherPolicy.WIDE, halign=Gtk.Align.CENTER)

        content = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=10,
                          margin_top=10, margin_bottom=12, margin_start=12, margin_end=12)
        for w in (self.lcd_area, keys, stats_row, switcher, self.stack):
            content.append(w)
        self.toasts = Adw.ToastOverlay(child=content)
        view = Adw.ToolbarView(content=self.toasts)
        view.add_top_bar(header)
        for b in (self.offline_banner, self.battery_banner, self.alarm_banner):
            view.add_top_bar(b)
        self.set_content(view)

        self._add_actions(cfg)
        GLib.timeout_add(250, self._tick)
        self.meter = meter.Meter(
            on_reading=lambda r: GLib.idle_add(self._on_reading, r),
            on_status=lambda s, c: GLib.idle_add(self._on_status, s, c),
            on_info=lambda i: GLib.idle_add(self._on_info, i),
            demo=demo,
        )
        self.meter.start()
        self._update_offline_banner()
        self.connect("close-request", self._on_close)

    # --- actions and shortcuts ---

    def _add_actions(self, cfg: dict) -> None:
        def act(name, fn, accels=()):
            a = Gio.SimpleAction.new(name, None)
            a.connect("activate", lambda *_: fn())
            self.add_action(a)
            if accels:
                self.get_application().set_accels_for_action(f"win.{name}", list(accels))
        act("choose", lambda: dialogs.choose_meter(self, self.meter))
        act("info", lambda: dialogs.show_info(self, self.meter), ["<Ctrl>i"])
        act("rename", lambda: dialogs.rename(self, self.meter, self.toast))
        act("alarm", lambda: dialogs.alarm_dialog(self, self.alarm, self.reading.base_unit if self.reading else "", self._save_alarm))
        act("reset-stats", self._reset_stats)
        act("open-folder", self._open_folder)
        act("about", self._about)
        act("shortcuts", self._shortcuts)
        act("key-select", lambda: self._press(protocol.KEY_SELECT), ["s"])
        act("key-range", lambda: self._press(protocol.KEY_RANGE), ["r"])
        act("key-auto", lambda: self._press(protocol.KEY_RANGE, protocol.LONG), ["<Shift>r"])
        act("key-hold", lambda: self._press(protocol.KEY_HOLD), ["h"])
        act("key-light", lambda: self._press(protocol.KEY_HOLD, protocol.LONG), ["<Shift>h"])
        act("key-hz", lambda: self._press(protocol.KEY_HZ), ["z"])
        act("toggle-record", lambda: self.record.set_active(not self.record.get_active()), ["<Ctrl>r"])
        speak = Gio.SimpleAction.new_stateful("speak", None, GLib.Variant.new_boolean(self.speak))
        speak.connect("change-state", self._toggle_speak)
        self.add_action(speak)

    def toast(self, text: str, button: str | None = None, action: str | None = None) -> None:
        t = Adw.Toast(title=GLib.markup_escape_text(text), timeout=5)
        if button and action:
            t.set_button_label(button)
            t.set_action_name(action)
        self.toasts.add_toast(t)

    # --- meter events ---

    def _on_reading(self, r: protocol.Reading) -> bool:
        self.reading = r
        self.trend.add(r)
        self.stats.add(r)
        self.stats_label.set_label(self.stats.describe())
        if self.recording is not None:
            self.recording.add(r, time.time())
            self.rec_page.appended(self.recording)
        self._check_alarm(r)
        self._maybe_speak(r)
        self._update_battery_banner()
        self.lcd_area.queue_draw()
        return GLib.SOURCE_REMOVE

    def _on_status(self, status: str, connected: bool) -> bool:
        self.title.set_subtitle(status)
        if connected != self.connected:
            self.connected = connected
            if not connected:
                self.reading = None
            self.lcd_area.queue_draw()
        for b in self.key_buttons:
            b.set_sensitive(connected)
        self.off_start.set_sensitive(connected)
        self.off_read.set_sensitive(connected)
        paused = self.meter.paused if hasattr(self, "meter") else False
        self.link.set_icon_name("bluetooth-disabled-symbolic" if paused else "bluetooth-active-symbolic")
        self.link.set_tooltip_text("Connect to the meter" if paused else "Disconnect from the meter")
        self._update_offline_banner()
        return GLib.SOURCE_REMOVE

    def _on_info(self, info: protocol.DeviceInfo) -> bool:
        self.info = info
        if info.recording and not meter.load_config().get("recording"):
            # Started elsewhere (the meter's own app): remember it so the log is offered once it's done
            meter.update_config(recording={"started": dt.datetime.now().isoformat(timespec="seconds"), "interval": 1, "count": 1})
        self._update_battery_banner()
        self._update_offline_banner()
        return GLib.SOURCE_REMOVE

    # --- keys ---

    def _press(self, code: int, press: int = protocol.SHORT) -> None:
        if not self.connected:
            return
        dialogs.when_done(self.meter.press(code, press), lambda _: None, lambda e: self.toast(f"Key not sent: {e}"))

    def _key_clicked(self, _b, code: int) -> None:
        if self._long_pressed:
            self._long_pressed = False
            return
        self._press(code)

    def _key_long(self, _g, _x, _y, code: int) -> None:
        self._long_pressed = True
        self._press(code, protocol.LONG)

    # --- statistics, alarm, speech ---

    def _reset_stats(self) -> None:
        self.stats.reset()
        self.stats_label.set_label(self.stats.describe())

    def _markers(self):
        if self.alarm.enabled and self.reading and self.reading.base_unit == self.alarm.unit:
            return self.alarm.low, self.alarm.high
        return None, None

    def _save_alarm(self, alarm: Alarm) -> None:
        self.alarm = alarm
        meter.update_config(alarm=alarm.to_dict())
        if not alarm.enabled:
            self.alarm_message = None
            self.alarm_banner.set_revealed(False)
        self.trend_area.queue_draw()

    def _check_alarm(self, r: protocol.Reading) -> None:
        msg = self.alarm.check(r)
        if msg and not self.alarm_message:
            now = time.monotonic()
            if now - self.alarm_sounded > 3:
                self.alarm_sounded = now
                self._sound()
                if self.speak:
                    self._say("Alarm. " + msg.replace("µ", "micro"))
                if not self.is_active():
                    n = Gio.Notification.new("OW18B LCD alarm")
                    n.set_body(msg)
                    n.set_priority(Gio.NotificationPriority.URGENT)
                    self.get_application().send_notification("alarm", n)
        self.alarm_message = msg
        if msg:
            self.alarm_banner.set_title(f"Alarm: {msg}")
        self.alarm_banner.set_revealed(bool(msg))

    def _sound(self) -> None:
        if shutil.which("canberra-gtk-play"):
            subprocess.Popen(["canberra-gtk-play", "-i", "alarm-clock-elapsed", "-d", "OW18B LCD alarm"],
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        else:
            self.get_display().beep()

    def _toggle_speak(self, action: Gio.SimpleAction, value: GLib.Variant) -> None:
        action.set_state(value)
        self.speak = value.get_boolean()
        meter.update_config(speak=self.speak)
        if self.speak and not shutil.which("spd-say"):
            self.toast("Speaking needs speech-dispatcher (spd-say): sudo apt install speech-dispatcher")

    def _maybe_speak(self, r: protocol.Reading) -> None:
        now = time.monotonic()
        if self.speak and now - self.spoken_at >= SPEAK_EVERY:
            self.spoken_at = now
            self._say(r.spoken)

    def _say(self, text: str) -> None:
        if not shutil.which("spd-say") or (self.speaker and self.speaker.poll() is None):
            return
        self.speaker = subprocess.Popen(["spd-say", "-N", "OW18B LCD", "--", text],
                                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    # --- banners ---

    def _update_battery_banner(self) -> None:
        low_flag = bool(self.reading and self.reading.low_battery)
        pct = self.info.battery if self.info else None
        low = low_flag or (pct is not None and pct <= 20)
        if low:
            self.battery_banner.set_title("The meter's battery is low" + (f" ({pct} %)" if pct is not None else "") +
                                          ": readings and offline logs may be wrong. Fit a new 9 V battery.")
        self.battery_banner.set_revealed(low)

    def _update_offline_banner(self) -> None:
        pending = meter.load_config().get("recording")
        b = self.offline_banner
        if self.connected and self.info and self.info.recording:
            b.set_title("The meter is recording an offline log. Stop it to read it now, or disconnect to let it carry on.")
            b.set_button_label("Stop recording")
            b.set_revealed(True)
        elif pending and not self.connected:
            started = dt.datetime.fromisoformat(pending["started"])
            end = started + dt.timedelta(seconds=pending["interval"] * pending["count"])
            when = "is due to finish" if end > dt.datetime.now() else "finished"
            b.set_title(f"Offline log started {started.strftime('%a %H:%M')}, {when} {end.strftime('%a %H:%M')}. "
                        "Turn the meter's Bluetooth on (hold Hz/Duty △/ᛒ) to read it.")
            b.set_button_label(None)
            b.set_revealed(True)
        elif pending and self.connected:
            b.set_title("The offline log on the meter is ready to read.")
            b.set_button_label("Read log")
            b.set_revealed(True)
        else:
            b.set_revealed(False)

    def _offline_banner_clicked(self, *_):
        if self.info and self.info.recording:
            dialogs.when_done(self.meter.stop_recording(), lambda _: self.toast("Recording stopped. You can read the log now."),
                              lambda e: self.toast(f"Couldn't stop: {e}"))
        else:
            self._read_offline()

    # --- recording on this computer ---

    def _toggle_recording(self, button: Gtk.ToggleButton) -> None:
        if button.get_active():
            self.recording = Series(f"Recording, {dt.datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
            self.rec_page.show(self.recording)
            self.rec_save.set_sensitive(True)
            self.stack.set_visible_child_name("recording")
            button.set_tooltip_text("Stop recording")
        else:
            series, self.recording = self.recording, None
            button.set_tooltip_text("Record readings to a file")
            if series and series.samples:
                self.last_recording = series
                path = self._autosave(series, "recording", dt.datetime.fromtimestamp(series.samples[0].time))
                if path:
                    self.toast(f"{len(series.samples)} readings saved to {path.name}", "Open folder", "win.open-folder")

    def _autosave(self, series: Series, kind: str, when: dt.datetime) -> Path | None:
        try:
            folder = save_dir()
            folder.mkdir(parents=True, exist_ok=True)
            path = folder / f"{kind}-{when.strftime('%Y%m%d-%H%M%S')}.csv"
            series.write_csv(path)
            return path
        except OSError as e:
            self.toast(f"Couldn't save: {e}")
            return None

    def _save_as(self, series: Series | None) -> None:
        if not series or not series.samples:
            return
        d = Gtk.FileDialog(title="Save readings as CSV", initial_name=f"{series.source}-{dt.datetime.fromtimestamp(series.samples[0].time).strftime('%Y%m%d-%H%M%S')}.csv")

        def done(dialog, result):
            try:
                f = dialog.save_finish(result)
            except GLib.Error:
                return  # cancelled
            try:
                series.write_csv(Path(f.get_path()))
                self.toast(f"Saved {Path(f.get_path()).name}")
            except OSError as e:
                self.toast(f"Couldn't save: {e}")
        d.save(self, None, done)

    def _open_folder(self) -> None:
        folder = save_dir()
        folder.mkdir(parents=True, exist_ok=True)
        Gtk.FileLauncher(file=Gio.File.new_for_path(str(folder))).launch(self, None, None)

    # --- offline log on the meter ---

    def _start_offline(self) -> None:
        def started(interval: int, count: int) -> None:
            self.toast("Recording on the meter. Its Bluetooth is now off.")
            self._update_offline_banner()
        dialogs.start_offline(self, self.meter, started, self.toast)

    def _read_offline(self) -> None:
        self.stack.set_visible_child_name("offline")
        self.off_read.set_sensitive(False)
        self.off_page.title.set_label("Reading the log from the meter…")

        def progress(n: int) -> None:
            GLib.idle_add(lambda: self.off_page.title.set_label(f"Reading the log from the meter… {n} bytes") and False)

        def ok(lg: protocol.OfflineLog) -> None:
            self.off_read.set_sensitive(self.connected)
            self.offline = Series.from_offline(lg)
            self.off_page.show(self.offline)
            self.off_save.set_sensitive(bool(self.offline.samples))
            meter.update_config(recording=None)
            self._update_offline_banner()
            if self.offline.samples:
                path = self._autosave(self.offline, "offline", lg.start or dt.datetime.now())
                self.toast(f"{len(self.offline.samples)} readings read" + (f", saved to {path.name}" if path else ""),
                           "Open folder", "win.open-folder")
            else:
                self.toast("The meter's log is empty")

        def err(e: BaseException) -> None:
            self.off_read.set_sensitive(self.connected)
            self.off_page.title.set_label(f"Couldn't read the log: {e}")
        dialogs.when_done(self.meter.read_log(progress), ok, err)

    # --- misc ---

    def _toggle_link(self, *_):
        self.meter.set_paused(not self.meter.paused)
        GLib.timeout_add(300, lambda: self._on_status(self.title.get_subtitle(), self.connected) and False)

    def _draw_lcd(self, _a, ctx, w, h) -> None:
        lcd.draw(ctx, w, h, self.reading, self.connected, alarm=bool(self.alarm_message), recording=self.recording is not None)

    def _tick(self) -> bool:
        width = self.get_width()
        self.rec_page.fit(width)
        self.off_page.fit(width)
        if self.stack.get_visible_child_name() == "live":
            self.trend_area.queue_draw()
        return GLib.SOURCE_CONTINUE

    def _shortcuts(self) -> None:
        dialogs.alert(self, "Keyboard shortcuts",
                      "S  Select\nR  Range   (Shift+R  auto range)\nH  Hold   (Shift+H  backlight and torch)\n"
                      "Z  Hz/Duty △\nCtrl+R  start/stop recording\nCtrl+I  meter info\n\n"
                      "Buttons: press and hold Range or Hold ☀ for their long-press function.")

    def _about(self, *_):
        Adw.AboutDialog(
            application_name="OW18B LCD", version=__version__, developer_name="ssjrocks",
            comments="Live readings, remote keys, recording and offline logs for the OWON OW18B multimeter.",
            website="https://github.com/ssjrocks/ow18b-lcd", license_type=Gtk.License.MIT_X11,
            application_icon=APP_ID,
        ).present(self)

    def finish(self) -> None:
        """Saves a running recording and lets go of the meter."""
        if self.recording is not None:
            self.record.set_active(False)
        self.meter.stop()

    def _on_close(self, *_):
        self.finish()
        return False


class App(Adw.Application):
    def __init__(self, demo: bool):
        super().__init__(application_id=APP_ID + (".Demo" if demo else ""), flags=Gio.ApplicationFlags.NON_UNIQUE if demo else 0)
        self.demo = demo

    def do_activate(self):
        window = self.props.active_window or Window(self, self.demo)
        window.present()

    def do_startup(self):
        Adw.Application.do_startup(self)
        # Ctrl+C / kill: quit through do_shutdown so the meter is let go. Otherwise BlueZ keeps the
        # connection and the meter stays invisible to other apps until it times out.
        for sig in (signal.SIGINT, signal.SIGTERM, signal.SIGHUP):
            GLib.unix_signal_add(GLib.PRIORITY_DEFAULT, sig, self._quit)

    def _quit(self) -> bool:
        self.quit()
        return GLib.SOURCE_REMOVE

    def do_shutdown(self):
        for window in self.get_windows():
            if isinstance(window, Window):
                window.finish()
        Adw.Application.do_shutdown(self)


def main() -> int:
    parser = argparse.ArgumentParser(prog="ow18b-lcd", description=__doc__)
    parser.add_argument("--demo", action="store_true", help="replay recorded readings instead of connecting")
    parser.add_argument("--verbose", action="store_true")
    args, rest = parser.parse_known_args()
    logging.basicConfig(level=logging.INFO if args.verbose else logging.WARNING, format="%(asctime)s %(name)s: %(message)s")
    return App(args.demo).run([sys.argv[0], *rest])
