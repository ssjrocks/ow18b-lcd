"""Dialogs: meter info, rename, choose a meter, alarm limits, starting an offline log."""
from __future__ import annotations

import datetime as dt
from concurrent.futures import Future
from typing import Any, Callable

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
from gi.repository import Adw, GLib, Gtk  # noqa: E402

from . import protocol  # noqa: E402
from .data import Alarm, parse_si, si  # noqa: E402
from .meter import Meter  # noqa: E402


def when_done(future: Future, ok: Callable[[Any], None], err: Callable[[BaseException], None]) -> None:
    """Calls ok(result) or err(exception) on the GTK main loop."""
    def done(f: Future) -> None:
        e = f.exception()
        GLib.idle_add(lambda: (err(e) if e else ok(f.result())) and False)
    future.add_done_callback(done)


def alert(parent: Gtk.Widget, heading: str, body: str) -> None:
    d = Adw.AlertDialog(heading=heading, body=body)
    d.add_response("ok", "OK")
    d.present(parent)


def duration(seconds: float) -> str:
    seconds = int(seconds)
    d, rem = divmod(seconds, 86400)
    h, rem = divmod(rem, 3600)
    m, s = divmod(rem, 60)
    parts = [f"{d} d" if d else "", f"{h} h" if h else "", f"{m} min" if m else "", f"{s} s" if s and not d else ""]
    return " ".join(p for p in parts if p) or "0 s"


def show_info(parent: Gtk.Widget, meter: Meter) -> None:
    def show(info: protocol.DeviceInfo | None, note: str = "") -> None:
        lines = [f"Name: {meter.name}", f"Address: {meter.address or '–'}"]
        if info:
            lines += [f"Model: {info.model}", f"Firmware: {info.firmware}",
                      f"Battery: {info.battery} %" if info.battery is not None else "Battery: not reported",
                      "Offline log: recording" if info.recording else "Offline log: not recording"]
        if note:
            lines.append(note)
        alert(parent, "Meter", "\n".join(lines))

    when_done(meter.read_info(), show, lambda e: show(meter.info, f"\nCouldn't read the meter: {e}"))


def rename(parent: Gtk.Widget, meter: Meter, toast: Callable[[str], None]) -> None:
    entry = Gtk.Entry(text=meter.name if meter.name != protocol.NAME else "", placeholder_text="e.g. Bench_1",
                      max_length=15, activates_default=True)
    d = Adw.AlertDialog(heading="Rename the meter",
                        body="The name is stored in the meter and is what it advertises over Bluetooth. "
                             "Letters, digits and underscores, up to 15.", extra_child=entry)
    d.add_response("cancel", "Cancel")
    d.add_response("rename", "Rename")
    d.set_response_appearance("rename", Adw.ResponseAppearance.SUGGESTED)
    d.set_default_response("rename")

    def check(*_):
        d.set_response_enabled("rename", bool(protocol.NAME_CHARS.match(entry.get_text())))
    entry.connect("changed", check)
    check()

    def chosen(_d, response):
        if response != "rename":
            return
        name = entry.get_text()
        when_done(meter.rename(name), lambda _: toast(f"Renamed to {name}. The new name shows after the meter restarts Bluetooth."),
                  lambda e: toast(f"Couldn't rename: {e}"))
    d.connect("response", chosen)
    d.present(parent)


def choose_meter(parent: Gtk.Widget, meter: Meter) -> None:
    dialog = Adw.Dialog(title="Choose a meter", content_width=420, content_height=440)
    view = Adw.ToolbarView()
    view.add_top_bar(Adw.HeaderBar())
    box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=12, margin_top=12, margin_bottom=12,
                  margin_start=12, margin_end=12)
    status = Gtk.Label(label="Looking for Bluetooth devices…", wrap=True, xalign=0)
    spinner = Adw.Spinner() if hasattr(Adw, "Spinner") else Gtk.Spinner(spinning=True)
    top = Gtk.Box(spacing=8)
    top.append(spinner)
    top.append(status)
    rescan = Gtk.Button(label="Scan again", halign=Gtk.Align.END, hexpand=True, sensitive=False)
    top.append(rescan)
    listbox = Gtk.ListBox(selection_mode=Gtk.SelectionMode.NONE)
    listbox.add_css_class("boxed-list")
    scroller = Gtk.ScrolledWindow(vexpand=True, child=listbox)
    note = Gtk.Label(label="OW18B meters advertise as “BDM” unless renamed. A meter only shows here when its "
                           "Bluetooth is on and no other app is connected to it.", wrap=True, xalign=0)
    note.add_css_class("dim-label")
    box.append(top)
    box.append(scroller)
    box.append(note)
    view.set_content(box)
    dialog.set_child(view)

    def fill(devices: list[tuple[str, str, int]]) -> None:
        spinner.set_visible(False)
        rescan.set_sensitive(True)
        listbox.remove_all()
        status.set_label(f"{len(devices)} found" if devices else "No devices found")
        for address, name, rssi in devices:
            sub = address + ("   (this meter)" if address == meter.address else "") + (f"   {rssi} dBm" if rssi else "")
            row = Adw.ActionRow(title=GLib.markup_escape_text(name), subtitle=sub, activatable=True)
            row.add_suffix(Gtk.Image(icon_name="go-next-symbolic"))
            row.connect("activated", lambda _r, a=address: (meter.use_address(a), dialog.close()))
            listbox.append(row)

    def scan(*_):
        spinner.set_visible(True)
        rescan.set_sensitive(False)
        status.set_label("Looking for Bluetooth devices…")
        when_done(meter.scan(), fill, lambda e: (fill([]), status.set_label(f"Scan failed: {e}")))
    rescan.connect("clicked", scan)
    scan()
    dialog.present(parent)


def alarm_dialog(parent: Gtk.Widget, alarm: Alarm, unit: str, on_save: Callable[[Alarm], None]) -> None:
    unit = unit or alarm.unit
    group = Adw.PreferencesGroup(description=f"Limits in {unit}, for the function the meter is on now. "
                                             f"You can type prefixes: 450m, 1.2k.")
    enabled = Adw.SwitchRow(title="Alarm on", active=alarm.enabled or alarm.unit != unit)
    low = Adw.EntryRow(title=f"Low limit ({unit})")
    high = Adw.EntryRow(title=f"High limit ({unit})")
    if alarm.unit == unit:
        low.set_text("" if alarm.low is None else si(alarm.low, unit).replace(" ", "").removesuffix(unit))
        high.set_text("" if alarm.high is None else si(alarm.high, unit).replace(" ", "").removesuffix(unit))
    for row in (enabled, low, high):
        group.add(row)
    d = Adw.AlertDialog(heading="Alarm", body="Sounds and shows a warning when a reading goes outside the limits. "
                                              "Leave a limit empty to not check it.", extra_child=group)
    d.add_response("cancel", "Cancel")
    d.add_response("save", "Save")
    d.set_response_appearance("save", Adw.ResponseAppearance.SUGGESTED)

    def parse(row: Adw.EntryRow) -> float | None:
        t = row.get_text().strip()
        return parse_si(t, unit) if t else None

    def check(*_):
        ok = True
        for row in (low, high):
            try:
                parse(row)
                row.remove_css_class("error")
            except ValueError:
                row.add_css_class("error")
                ok = False
        d.set_response_enabled("save", ok)
    low.connect("changed", check)
    high.connect("changed", check)

    def chosen(_d, response):
        if response == "save":
            on_save(Alarm(enabled.get_active(), unit, parse(low), parse(high)))
    d.connect("response", chosen)
    d.present(parent)


def start_offline(parent: Gtk.Widget, meter: Meter, on_started: Callable[[int, int], None],
                  toast: Callable[[str], None]) -> None:
    group = Adw.PreferencesGroup()
    interval = Adw.SpinRow.new_with_range(1, 86400, 1)
    interval.set_title("Interval (seconds)")
    interval.set_value(1)
    count = Adw.SpinRow.new_with_range(1, protocol.MAX_RECORDS, 1)
    count.set_title("Readings")
    count.set_value(600)
    total = Adw.ActionRow(title="Takes")
    for row in (interval, count, total):
        group.add(row)

    def update(*_):
        secs = int(interval.get_value()) * int(count.get_value())
        end = dt.datetime.now() + dt.timedelta(seconds=secs)
        total.set_subtitle(f"{duration(secs)}, until about {end.strftime('%a %H:%M')}")
    interval.connect("notify::value", update)
    count.connect("notify::value", update)
    update()

    d = Adw.AlertDialog(
        heading="Record on the meter",
        body="The meter logs readings into its own memory, up to 10,000, with nothing connected. It replaces the "
             "log already in the meter.\n\nThe meter turns Bluetooth off as it starts. To read the log, turn it back "
             "on with a long press of Hz/Duty △/ᛒ, then choose Read log from meter. Keep the dial where it is.",
        extra_child=group)
    d.add_response("cancel", "Cancel")
    d.add_response("start", "Start recording")
    d.set_response_appearance("start", Adw.ResponseAppearance.SUGGESTED)

    def chosen(_d, response):
        if response != "start":
            return
        i, n = int(interval.get_value()), int(count.get_value())
        when_done(meter.start_recording(i, n), lambda _: on_started(i, n), lambda e: toast(f"Couldn't start: {e}"))
    d.connect("response", chosen)
    d.present(parent)
