"""A table of readings (Gtk.ColumnView) for a recording or an offline log."""
from __future__ import annotations

import datetime as dt

import gi

gi.require_version("Gtk", "4.0")
from gi.repository import Gio, GObject, Gtk  # noqa: E402

from .data import Sample  # noqa: E402


class Row(GObject.Object):
    def __init__(self, cells: tuple[str, ...]):
        super().__init__()
        self.cells = cells


COLUMNS = ("Time", "Reading", "Function", "Flags")


def cells(s: Sample) -> tuple[str, ...]:
    r = s.reading
    return (dt.datetime.fromtimestamp(s.time).strftime("%H:%M:%S"), f"{r.digits} {r.unit}".strip(), r.name, r.flags)


class ReadingsTable(Gtk.ScrolledWindow):
    def __init__(self) -> None:
        super().__init__(hexpand=False, vexpand=True, min_content_width=330)
        self.store = Gio.ListStore(item_type=Row)
        self.view = Gtk.ColumnView(model=Gtk.NoSelection(model=self.store), show_row_separators=True)
        self.view.add_css_class("data-table")
        for i, title in enumerate(COLUMNS):
            factory = Gtk.SignalListItemFactory()
            factory.connect("setup", lambda _f, item: item.set_child(Gtk.Label(xalign=0)))
            factory.connect("bind", lambda _f, item, i=i: item.get_child().set_label(item.get_item().cells[i]))
            col = Gtk.ColumnViewColumn(title=title, factory=factory, expand=i == 2)
            self.view.append_column(col)
        self.set_child(self.view)
        self.follow = True  # keep the newest row in view while recording

    def set_samples(self, samples: list[Sample]) -> None:
        self.store.splice(0, self.store.get_n_items(), [Row(cells(s)) for s in samples])

    def append(self, s: Sample) -> None:
        self.store.append(Row(cells(s)))
        if self.follow:
            adj = self.get_vadjustment()
            if adj.get_value() >= adj.get_upper() - adj.get_page_size() - 40:
                self.view.scroll_to(self.store.get_n_items() - 1, None, Gtk.ListScrollFlags.NONE, None)

    def clear(self) -> None:
        self.store.remove_all()
