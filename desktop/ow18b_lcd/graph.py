"""Scrolling trend graph of the last minute of readings, drawn with Cairo."""
from __future__ import annotations

import math
import time
from collections import deque

import cairo

from . import lcd
from .protocol import Reading

WINDOW = 60.0  # seconds shown
PREFIX = {-12: "p", -9: "n", -6: "µ", -3: "m", 0: "", 3: "k", 6: "M", 9: "G"}


def exponent(value: float) -> int:
    """The SI exponent (multiple of 3) that puts |value| in 1..999."""
    if value == 0 or not math.isfinite(value):
        return 0
    return max(-12, min(9, int(math.floor(math.log10(abs(value)) / 3) * 3)))


def si(value: float, unit: str, exp: int, decimals: int) -> str:
    """1.234e-3, 'V', -3, 2 -> '1.23 mV'."""
    return f"{value / 10 ** exp:.{decimals}f} {PREFIX[exp]}{unit}".strip()


def nice_step(span: float) -> float:
    raw = span / 4
    mag = 10 ** math.floor(math.log10(raw))
    return next(m * mag for m in (1, 2, 2.5, 5, 10) if m * mag >= raw)


class Trend:
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

    def draw(self, ctx: cairo.Context, width: float, height: float) -> None:
        now = time.monotonic()
        ctx.set_source_rgb(*lcd.BG_BOTTOM)
        ctx.rectangle(0, 0, width, height)
        ctx.fill()
        left, right, top, bottom = 64.0, 12.0, 10.0, 22.0
        pw, ph = width - left - right, height - top - bottom
        if pw < 20 or ph < 20:
            return
        ctx.select_font_face("Sans", cairo.FONT_SLANT_NORMAL, cairo.FONT_WEIGHT_NORMAL)
        ctx.set_font_size(11)

        values = [v for t, v in self.points if v is not None and t >= now - WINDOW]
        if values:
            lo, hi = min(values), max(values)
            if hi - lo < 1e-15:
                pad = abs(hi) * 0.1 or 1e-3
                lo, hi = lo - pad, hi + pad
            step = nice_step(hi - lo)
            lo, hi = math.floor(lo / step) * step, math.ceil(hi / step) * step
        else:
            lo, hi, step = -1.0, 1.0, 0.5

        def x_of(t: float) -> float:
            return left + pw * (1 - (now - t) / WINDOW)

        def y_of(v: float) -> float:
            return top + ph * (1 - (v - lo) / (hi - lo))

        # Grid and labels, all with the same prefix and decimals
        exp = exponent(max(abs(lo), abs(hi)))
        scaled_step = step / 10 ** exp
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
                label = si(v if abs(v) > step * 1e-6 else 0.0, self.unit, exp, decimals)
                ext = ctx.text_extents(label)
                ctx.set_source_rgba(*lcd.INK, 0.8)
                ctx.move_to(left - 6 - ext.x_advance, y + 4)
                ctx.show_text(label)
            v += step
        for sec in (60, 45, 30, 15, 0):
            x = x_of(now - sec)
            ctx.set_source_rgba(*lcd.INK, 0.15)
            ctx.move_to(x, top)
            ctx.line_to(x, top + ph)
            ctx.stroke()
            label = "now" if sec == 0 else f"-{sec}s"
            ext = ctx.text_extents(label)
            ctx.set_source_rgba(*lcd.INK, 0.8)
            ctx.move_to(min(x - ext.x_advance / 2, width - ext.x_advance - 2), height - 6)
            ctx.show_text(label)

        if not values:
            ctx.set_font_size(13)
            msg = "No readings to plot" if self.function is None or self.unit else "Nothing to plot in this mode"
            ext = ctx.text_extents(msg)
            ctx.set_source_rgba(*lcd.INK, 0.6)
            ctx.move_to(left + (pw - ext.x_advance) / 2, top + ph / 2)
            ctx.show_text(msg)
            return

        # The line, broken at overloads
        ctx.save()
        ctx.rectangle(left, top, pw, ph)
        ctx.clip()
        ctx.set_source_rgb(*lcd.INK)
        ctx.set_line_width(2)
        ctx.set_line_join(cairo.LINE_JOIN_ROUND)
        drawing = False
        for t, v in self.points:
            if v is None:
                drawing = False
                continue
            (ctx.line_to if drawing else ctx.move_to)(x_of(t), y_of(v))
            drawing = True
        ctx.stroke()
        ctx.restore()
