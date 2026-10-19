"""Draws the virtual LCD with Cairo: 4 seven-segment digits, sign, decimal points, annunciators, unit.

Everything is laid out on a 500 x 210 canvas and scaled to fit the widget. The Android app's
Lcd.kt draws the same layout; keep the two in step.
"""
from __future__ import annotations

import math

import cairo

from .protocol import Reading

W, H = 500.0, 210.0
BG_TOP, BG_BOTTOM = (0.74, 0.79, 0.67), (0.64, 0.70, 0.57)
INK = (0.11, 0.15, 0.10)
GHOST = 0.07  # alpha of unlit segments

SEGMENTS = {
    "0": "abcdef", "1": "bc", "2": "abged", "3": "abgcd", "4": "fgbc", "5": "afgcd", "6": "afgedc",
    "7": "abc", "8": "abcdefg", "9": "abcdfg", "-": "g", "O": "abcdef", "L": "fed", "E": "afged",
    "F": "afge", " ": "",
}

DIGIT_W, DIGIT_H, THICK, SKEW, GAP = 60.0, 112.0, 12.0, 0.08, 1.6
DIGITS_X, DIGITS_Y, DIGIT_STEP = 74.0, 58.0, 76.0


def cells(text: str) -> list[tuple[str, bool]]:
    """'-7.27' -> 4 (char, decimal point after it) cells, right-aligned, sign not included."""
    out: list[tuple[str, bool]] = []
    for ch in text:
        if ch == ".":
            if out:
                out[-1] = (out[-1][0], True)
        else:
            out.append((ch, False))
    if text in ("OL", "EF"):
        out.append((" ", False))
    out = out[-4:]
    return [(" ", False)] * (4 - len(out)) + out


def _hseg(x1, x2, y, t):
    return [(x1, y), (x1 + t / 2, y - t / 2), (x2 - t / 2, y - t / 2), (x2, y), (x2 - t / 2, y + t / 2), (x1 + t / 2, y + t / 2)]


def _vseg(x, y1, y2, t):
    return [(x, y1), (x + t / 2, y1 + t / 2), (x + t / 2, y2 - t / 2), (x, y2), (x - t / 2, y2 - t / 2), (x - t / 2, y1 + t / 2)]


def segment_polygons(w=DIGIT_W, h=DIGIT_H, t=THICK, g=GAP) -> dict[str, list[tuple[float, float]]]:
    l, r, top, mid, bot = t / 2, w - t / 2, t / 2, h / 2, h - t / 2
    return {
        "a": _hseg(l + g, r - g, top, t), "g": _hseg(l + g, r - g, mid, t), "d": _hseg(l + g, r - g, bot, t),
        "f": _vseg(l, top + g, mid - g, t), "b": _vseg(r, top + g, mid - g, t),
        "e": _vseg(l, mid + g, bot - g, t), "c": _vseg(r, mid + g, bot - g, t),
    }


POLYS = segment_polygons()


def _poly(ctx, x0, y0, pts, h=DIGIT_H):
    for i, (x, y) in enumerate(pts):
        px, py = x0 + x + (h - y) * SKEW, y0 + y
        (ctx.move_to if i == 0 else ctx.line_to)(px, py)
    ctx.close_path()


def _ink(ctx, lit: bool):
    ctx.set_source_rgba(*INK, 1.0 if lit else GHOST)


def _text(ctx, x, y, s, size, lit, align="left", bold=True):
    ctx.select_font_face("Sans", cairo.FONT_SLANT_NORMAL, cairo.FONT_WEIGHT_BOLD if bold else cairo.FONT_WEIGHT_NORMAL)
    ctx.set_font_size(size)
    ext = ctx.text_extents(s)
    if align == "right":
        x -= ext.x_advance
    elif align == "center":
        x -= ext.x_advance / 2
    _ink(ctx, lit)
    ctx.move_to(x, y)
    ctx.show_text(s)


def _battery(ctx, x, y, lit):
    _ink(ctx, lit)
    ctx.set_line_width(2.5)
    ctx.rectangle(x, y, 30, 14)
    ctx.stroke()
    ctx.rectangle(x + 30, y + 4, 4, 6)
    ctx.fill()
    ctx.rectangle(x + 4, y + 4, 8, 6)
    ctx.fill()


def draw(ctx: cairo.Context, width: float, height: float, reading: Reading | None, connected: bool) -> None:
    scale = min(width / W, height / H)
    ctx.save()
    ctx.translate((width - W * scale) / 2, (height - H * scale) / 2)
    ctx.scale(scale, scale)

    # Glass
    radius = 14
    ctx.new_sub_path()
    ctx.arc(W - radius, radius, radius, -math.pi / 2, 0)
    ctx.arc(W - radius, H - radius, radius, 0, math.pi / 2)
    ctx.arc(radius, H - radius, radius, math.pi / 2, math.pi)
    ctx.arc(radius, radius, radius, math.pi, 3 * math.pi / 2)
    ctx.close_path()
    grad = cairo.LinearGradient(0, 0, 0, H)
    grad.add_color_stop_rgb(0, *BG_TOP)
    grad.add_color_stop_rgb(1, *BG_BOTTOM)
    ctx.set_source(grad)
    ctx.fill_preserve()
    ctx.set_source_rgba(0, 0, 0, 0.35)
    ctx.set_line_width(3)
    ctx.stroke()

    r = reading if connected else None

    # Top annunciators
    _text(ctx, 22, 36, "AUTO", 17, bool(r and r.auto))
    _text(ctx, 88, 36, "HOLD", 17, bool(r and r.hold))
    _text(ctx, 154, 36, "REL", 17, bool(r and r.rel))
    _text(ctx, 400, 36, "BT", 17, connected)
    _battery(ctx, 440, 22, bool(r and r.low_battery))

    # Mode left of the digits (DC / AC)
    mode = r.mode if r else ""
    _text(ctx, 22, 72, "DC", 18, mode == "DC")
    _text(ctx, 22, 94, "AC", 18, mode == "AC")

    # Sign and digits
    text = r.digits if r else "    "
    negative = text.startswith("-") and text.strip("-") != ""
    if negative:
        text = text[1:]
    _ink(ctx, negative)
    _poly(ctx, DIGITS_X - 44, DIGITS_Y, _hseg(4, 34, DIGIT_H / 2, THICK))
    ctx.fill()
    for i, (ch, dp) in enumerate(cells(text)):
        x0 = DIGITS_X + i * DIGIT_STEP
        lit = SEGMENTS.get(ch, "")
        for name, pts in POLYS.items():
            _ink(ctx, name in lit)
            _poly(ctx, x0, DIGITS_Y, pts)
            ctx.fill()
        if i < 3:
            _ink(ctx, dp)
            ctx.rectangle(x0 + DIGIT_W + 4, DIGITS_Y + DIGIT_H - THICK, THICK - 1, THICK - 1)
            ctx.fill()

    # Unit and special modes on the right
    _text(ctx, 488, 100, "DIODE", 15, mode == "DIODE", align="right")
    _text(ctx, 488, 122, "CONT", 15, mode == "CONT", align="right")
    _text(ctx, 488, 78, "NCV", 15, mode == "NCV", align="right")
    _text(ctx, 488, 168, r.unit if r else "", 40, True, align="right")
    if r and mode == "hFE":
        _text(ctx, 488, 168, "hFE", 34, True, align="right")
    ctx.restore()
