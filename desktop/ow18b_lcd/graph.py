"""Graphs drawn with Cairo: the live trend of the last minute, and whole recordings with zoom and pan."""
from __future__ import annotations

import math
import time
from collections import deque
from typing import Iterable

import cairo

from . import lcd
from .data import Series, exponent, si
from .protocol import Reading

WINDOW = 60.0  # seconds in the live trend


def nice_step(span: float) -> float:
    raw = span / 4
    mag = 10 ** math.floor(math.log10(raw))
    return next(m * mag for m in (1, 2, 2.5, 5, 10) if m * mag >= raw)


def nice_time_step(span: float, ticks: int = 6) -> float:
    for s in (1, 2, 5, 10, 15, 30, 60, 120, 300, 600, 900, 1800, 3600, 7200, 14400, 28800, 86400):
        if span / s <= ticks:
            return s
    return 86400 * math.ceil(span / 86400 / ticks)


def clock(seconds: float) -> str:
    seconds = int(round(seconds))
    h, rem = divmod(abs(seconds), 3600)
    m, s = divmod(rem, 60)
    sign = "-" if seconds < 0 else ""
    return f"{sign}{h}:{m:02}:{s:02}" if h else f"{sign}{m}:{s:02}"


def draw_plot(ctx: cairo.Context, width: float, height: float, points: Iterable[tuple[float, float | None]],
              unit: str, x0: float, x1: float, x_ticks: list[tuple[float, str]], empty: str,
              markers: tuple[float | None, float | None] = (None, None)) -> None:
    """Points (x, value or None for a gap) between x0 and x1, autoscaled in y; optional alarm lines."""
    ctx.set_source_rgb(*lcd.BG_BOTTOM)
    ctx.rectangle(0, 0, width, height)
    ctx.fill()
    left, right, top, bottom = 70.0, 12.0, 10.0, 22.0
    pw, ph = width - left - right, height - top - bottom
    if pw < 20 or ph < 20 or x1 <= x0:
        return
    ctx.select_font_face("Sans", cairo.FONT_SLANT_NORMAL, cairo.FONT_WEIGHT_NORMAL)
    ctx.set_font_size(11)
    pts = list(points)
    values = [v for x, v in pts if v is not None and x0 <= x <= x1]
    lines = [m for m in markers if m is not None]
    if values:
        lo, hi = min(values + lines), max(values + lines)
        if hi - lo < 1e-15:
            pad = abs(hi) * 0.1 or 1e-3
            lo, hi = lo - pad, hi + pad
        step = nice_step(hi - lo)
        lo, hi = math.floor(lo / step) * step, math.ceil(hi / step) * step
    else:
        lo, hi, step = -1.0, 1.0, 0.5

    def x_of(x: float) -> float:
        return left + pw * (x - x0) / (x1 - x0)

    def y_of(v: float) -> float:
        return top + ph * (1 - (v - lo) / (hi - lo))

    # Value grid, all labels with one prefix and the same decimals
    exp = exponent(max(abs(lo), abs(hi)))
    scaled_step = step / 10 ** exp if unit not in ("%", "°C", "°F", "") else step
    decimals = next(d for d in range(7) if abs(round(scaled_step, d) - scaled_step) < scaled_step * 1e-6)
    ctx.set_line_width(1)
    v = lo
    while v <= hi + step / 2:
        y = y_of(v)
        ctx.set_source_rgba(*lcd.INK, 0.15)
        ctx.move_to(left, y)
        ctx.line_to(left + pw, y)
        ctx.stroke()
        if values:
            label = si(v if abs(v) > step * 1e-6 else 0.0, unit, exp, decimals)
            ext = ctx.text_extents(label)
            ctx.set_source_rgba(*lcd.INK, 0.8)
            ctx.move_to(left - 6 - ext.x_advance, y + 4)
            ctx.show_text(label)
        v += step
    for x, label in x_ticks:
        if not x0 <= x <= x1:
            continue
        px = x_of(x)
        ctx.set_source_rgba(*lcd.INK, 0.15)
        ctx.move_to(px, top)
        ctx.line_to(px, top + ph)
        ctx.stroke()
        ext = ctx.text_extents(label)
        ctx.set_source_rgba(*lcd.INK, 0.8)
        ctx.move_to(max(2, min(px - ext.x_advance / 2, width - ext.x_advance - 2)), height - 6)
        ctx.show_text(label)

    if not values:
        ctx.set_font_size(13)
        ext = ctx.text_extents(empty)
        ctx.set_source_rgba(*lcd.INK, 0.6)
        ctx.move_to(left + (pw - ext.x_advance) / 2, top + ph / 2)
        ctx.show_text(empty)
        return

    ctx.save()
    ctx.rectangle(left, top, pw, ph)
    ctx.clip()
    # Alarm limits as dashed lines
    ctx.set_dash([6, 4])
    ctx.set_source_rgba(0.7, 0.1, 0.1, 0.8)
    for m in lines:
        ctx.move_to(left, y_of(m))
        ctx.line_to(left + pw, y_of(m))
        ctx.stroke()
    ctx.set_dash([])
    # The line, broken at overloads
    ctx.set_source_rgb(*lcd.INK)
    ctx.set_line_width(2)
    ctx.set_line_join(cairo.LINE_JOIN_ROUND)
    drawing = False
    single = []
    for x, v in pts:
        if v is None:
            drawing = False
            continue
        (ctx.line_to if drawing else ctx.move_to)(x_of(x), y_of(v))
        if not drawing:
            single.append((x_of(x), y_of(v)))
        drawing = True
    ctx.stroke()
    if len(values) < 3:  # too few for a line: show dots
        for px, py in single:
            ctx.arc(px, py, 2.5, 0, 2 * math.pi)
            ctx.fill()
    ctx.restore()


class Trend:
    """Live: the last minute, cleared when the meter changes function."""

    def __init__(self) -> None:
        self.points: deque[tuple[float, float | None]] = deque()
        self.function: int | None = None
        self.unit = ""

    def clear(self) -> None:
        self.points.clear()

    def add(self, r: Reading) -> None:
        if r.function != self.function:
            self.clear()
            self.function, self.unit = r.function, r.base_unit
        now = time.monotonic()
        self.points.append((now, r.value))
        while self.points and self.points[0][0] < now - WINDOW - 2:
            self.points.popleft()

    def draw(self, ctx: cairo.Context, width: float, height: float, markers=(None, None)) -> None:
        now = time.monotonic()
        ticks = [(now - s, "now" if s == 0 else f"-{s}s") for s in (60, 45, 30, 15, 0)]
        empty = "Nothing to plot in this mode" if self.function is not None and not self.unit else "No readings to plot"
        draw_plot(ctx, width, height, self.points, self.unit, now - WINDOW, now, ticks, empty, markers)


class SeriesPlot:
    """A whole recording or offline log, one function at a time, with zoom (scroll) and pan (drag)."""

    def __init__(self) -> None:
        self.series: Series | None = None
        self.function: int | None = None  # which function to plot, when the log holds several
        self.view: tuple[float, float] | None = None  # visible span in seconds from the start

    def set_series(self, series: Series | None) -> None:
        self.series = series
        self.view = None
        fns = self.functions()
        self.function = fns[-1] if fns else None

    def functions(self) -> list[int]:
        seen: list[int] = []
        for s in self.series.samples if self.series else []:
            if s.reading.function not in seen:
                seen.append(s.reading.function)
        return seen

    def _span(self) -> tuple[float, float]:
        if not self.series or not self.series.samples:
            return 0.0, 1.0
        return 0.0, max(self.series.duration, 1.0)

    def visible(self) -> tuple[float, float]:
        return self.view or self._span()

    def zoom(self, factor: float, at: float) -> None:
        """factor < 1 zooms in; at = 0..1 across the plot."""
        a, b = self.visible()
        full = self._span()
        c = a + (b - a) * at
        w = max(min((b - a) * factor, full[1] - full[0]), 2.0)
        a, b = c - w * at, c + w * (1 - at)
        self.view = self._clamp(a, b)

    def pan(self, fraction: float) -> None:
        a, b = self.visible()
        d = (b - a) * fraction
        self.view = self._clamp(a + d, b + d)

    def _clamp(self, a: float, b: float) -> tuple[float, float] | None:
        lo, hi = self._span()
        w = b - a
        if w >= hi - lo:
            return None
        a = max(lo, min(a, hi - w))
        return a, a + w

    def reset(self) -> None:
        self.view = None

    def draw(self, ctx: cairo.Context, width: float, height: float, markers=(None, None)) -> None:
        a, b = self.visible()
        s = self.series
        unit = ""
        pts: list[tuple[float, float | None]] = []
        if s and s.samples:
            t0 = s.samples[0].time
            for smp in s.samples:
                if smp.reading.function == self.function:
                    pts.append((smp.time - t0, smp.reading.value))
                    unit = smp.reading.base_unit
                elif pts and pts[-1][1] is not None:
                    pts.append((smp.time - t0, None))  # break the line where another function was measured
        step = nice_time_step(b - a, max(2, int((width - 82) / 70)))
        first = math.ceil(a / step) * step
        ticks = [(first + i * step, clock(first + i * step)) for i in range(int((b - first) / step) + 1)]
        draw_plot(ctx, width, height, pts, unit, a, b, ticks, "Nothing recorded yet" if not pts else "Nothing to plot", markers)


