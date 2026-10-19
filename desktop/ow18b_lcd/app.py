"""OW18B LCD: a GTK4 window showing what an OWON OW18B multimeter reads, live over Bluetooth."""
from __future__ import annotations

import argparse
import logging
import sys

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
from gi.repository import Adw, Gio, GLib, Gtk  # noqa: E402

from . import __version__, graph, lcd, meter, protocol  # noqa: E402

APP_ID = "com.ssjrocks.OW18BLcd"


class Window(Adw.ApplicationWindow):
    def __init__(self, app: Adw.Application, demo: bool):
        super().__init__(application=app, title="OW18B LCD", default_width=560, default_height=440)
        self.reading: protocol.Reading | None = None
        self.connected = False
        self.trend = graph.Trend()
        cfg = meter.load_config()

        self.title = Adw.WindowTitle(title="OW18B LCD", subtitle="Starting…")
        header = Adw.HeaderBar(title_widget=self.title)
        menu = Gio.Menu()
        menu.append("Show trend graph", "win.graph")
        menu.append("Clear graph", "win.clear")
        menu.append("About OW18B LCD", "win.about")
        header.pack_end(Gtk.MenuButton(icon_name="open-menu-symbolic", menu_model=menu, tooltip_text="Menu"))

        self.lcd_area = Gtk.DrawingArea(hexpand=True, vexpand=True, content_height=170)
        self.lcd_area.set_draw_func(lambda _a, ctx, w, h: lcd.draw(ctx, w, h, self.reading, self.connected))
        self.graph_area = Gtk.DrawingArea(hexpand=True, vexpand=True, content_height=150)
        self.graph_area.set_draw_func(lambda _a, ctx, w, h: self.trend.draw(ctx, w, h))
        self.graph_frame = Gtk.Frame(child=self.graph_area)

        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=12,
                      margin_top=12, margin_bottom=12, margin_start=12, margin_end=12)
        box.append(self.lcd_area)
        box.append(self.graph_frame)
        view = Adw.ToolbarView(content=box)
        view.add_top_bar(header)
        self.set_content(view)

        show_graph = cfg.get("graph", True)
        self.graph_frame.set_visible(show_graph)
        graph_action = Gio.SimpleAction.new_stateful("graph", None, GLib.Variant.new_boolean(show_graph))
        graph_action.connect("change-state", self._toggle_graph)
        self.add_action(graph_action)
        for name, fn in (("clear", self._clear), ("about", self._about)):
            action = Gio.SimpleAction.new(name, None)
            action.connect("activate", fn)
            self.add_action(action)

        # Scroll the graph even when readings pause
        GLib.timeout_add(250, self._tick)

        self.meter = meter.Meter(
            on_reading=lambda r: GLib.idle_add(self._on_reading, r),
            on_status=lambda s, c: GLib.idle_add(self._on_status, s, c),
            demo=demo,
        )
        self.meter.start()
        self.connect("close-request", self._on_close)

    def _on_reading(self, r: protocol.Reading) -> bool:
        self.reading = r
        self.trend.add(r)
        self.lcd_area.queue_draw()
        return GLib.SOURCE_REMOVE

    def _on_status(self, status: str, connected: bool) -> bool:
        self.title.set_subtitle(status)
        if connected != self.connected:
            self.connected = connected
            if not connected:
                self.reading = None
            self.lcd_area.queue_draw()
        return GLib.SOURCE_REMOVE

    def _tick(self) -> bool:
        if self.graph_frame.get_visible():
            self.graph_area.queue_draw()
        return GLib.SOURCE_CONTINUE

    def _toggle_graph(self, action: Gio.SimpleAction, value: GLib.Variant) -> None:
        action.set_state(value)
        self.graph_frame.set_visible(value.get_boolean())
        meter.save_config({**meter.load_config(), "graph": value.get_boolean()})

    def _clear(self, *_):
        self.trend.clear()
        self.graph_area.queue_draw()

    def _about(self, *_):
        Adw.AboutDialog(
            application_name="OW18B LCD", version=__version__, developer_name="ssjrocks",
            comments="Live readings from an OWON OW18B multimeter over Bluetooth.",
            website="https://github.com/ssjrocks/ow18b-lcd", license_type=Gtk.License.MIT_X11,
            application_icon=APP_ID,
        ).present(self)

    def _on_close(self, *_):
        self.meter.stop()
        return False


class App(Adw.Application):
    def __init__(self, demo: bool):
        super().__init__(application_id=APP_ID + (".Demo" if demo else ""), flags=Gio.ApplicationFlags.NON_UNIQUE if demo else 0)
        self.demo = demo

    def do_activate(self):
        window = self.props.active_window or Window(self, self.demo)
        window.present()


def main() -> int:
    parser = argparse.ArgumentParser(prog="ow18b-lcd", description=__doc__)
    parser.add_argument("--demo", action="store_true", help="replay recorded readings instead of connecting")
    parser.add_argument("--verbose", action="store_true")
    args, rest = parser.parse_known_args()
    logging.basicConfig(level=logging.INFO if args.verbose else logging.WARNING, format="%(asctime)s %(name)s: %(message)s")
    return App(args.demo).run([sys.argv[0], *rest])
