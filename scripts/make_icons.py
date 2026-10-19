#!/usr/bin/env python3
"""Draws the app icon (an OW18B seen from the front) once and writes it as:

  desktop/data/com.ssjrocks.OW18BLcd.svg                         128 x 128 desktop icon
  android/app/src/main/res/drawable/ic_launcher_foreground.xml   adaptive icon foreground (108 dp)
  android/app/src/main/res/drawable/ic_launcher_monochrome.xml   themed-icon silhouette

Run it after changing the shapes: python3 scripts/make_icons.py
"""
from __future__ import annotations

import math
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

BLUE, BLUE_DARK, BLUE_LIGHT = "#2F74E0", "#1D55B5", "#5B95EC"
FACE, KEY, KEY_TEXT = "#1E2023", "#3A3E44", "#9AA1AA"
LCD_BOTTOM, INK = "#BCC6AE", "#1C2618"
DIAL, DIAL_RING, ORANGE, RED = "#0E0F11", "#34373C", "#F29A1F", "#C8372D"


def rrect(x, y, w, h, r):
    """Rounded rectangle as an SVG/VectorDrawable path."""
    r = min(r, w / 2, h / 2)
    return (f"M{x + r:.2f},{y:.2f}h{w - 2 * r:.2f}a{r:.2f},{r:.2f} 0 0 1 {r:.2f},{r:.2f}"
            f"v{h - 2 * r:.2f}a{r:.2f},{r:.2f} 0 0 1 {-r:.2f},{r:.2f}h{-(w - 2 * r):.2f}"
            f"a{r:.2f},{r:.2f} 0 0 1 {-r:.2f},{-r:.2f}v{-(h - 2 * r):.2f}a{r:.2f},{r:.2f} 0 0 1 {r:.2f},{-r:.2f}z")


def circle(cx, cy, r):
    return f"M{cx - r:.2f},{cy:.2f}a{r:.2f},{r:.2f} 0 1 0 {2 * r:.2f},0a{r:.2f},{r:.2f} 0 1 0 {-2 * r:.2f},0z"


def arc(cx, cy, r, a0, a1, width):
    """A thick arc from angle a0 to a1 (degrees, 0 = right, counter-clockwise) as a filled path."""
    def pt(rad, a):
        t = math.radians(a)
        return cx + rad * math.cos(t), cy - rad * math.sin(t)
    ro, ri = r + width / 2, r - width / 2
    (x0, y0), (x1, y1) = pt(ro, a0), pt(ro, a1)
    (x2, y2), (x3, y3) = pt(ri, a1), pt(ri, a0)
    large = 1 if abs(a1 - a0) > 180 else 0
    return (f"M{x0:.2f},{y0:.2f}A{ro:.2f},{ro:.2f} 0 {large} 0 {x1:.2f},{y1:.2f}"
            f"L{x2:.2f},{y2:.2f}A{ri:.2f},{ri:.2f} 0 {large} 1 {x3:.2f},{y3:.2f}z")


SEGS = {"0": "abcdef", "1": "bc", "2": "abged", "3": "abgcd", "4": "fgbc", "5": "afgcd", "6": "afgedc",
        "7": "abc", "8": "abcdefg", "9": "abcdfg"}


def digit(x, y, w, h, t, ch):
    """Seven-segment digit as rectangles."""
    m = h / 2
    rects = {  # segments overlap at the corners: solid digits read better at icon size
        "a": (x, y, w, t), "g": (x, y + m - t / 2, w, t), "d": (x, y + h - t, w, t),
        "f": (x, y, t, m + t / 2), "b": (x + w - t, y, t, m + t / 2),
        "e": (x, y + m - t / 2, t, m + t / 2), "c": (x + w - t, y + m - t / 2, t, m + t / 2),
    }
    return "".join(rrect(*rects[s], t * 0.25) for s in SEGS[ch])


def shapes():
    """(path, fill) pairs on a 128 x 128 canvas, back to front."""
    out = []
    # Rubber holster with grip waist and highlight
    out.append((rrect(24, 3, 80, 122, 20), BLUE_DARK))
    out.append((rrect(26, 4, 76, 119, 18), BLUE))
    # Corner bumpers, a shade lighter, like the real boot
    for x, y in ((26, 4), (80, 4), (26, 101), (80, 101)):
        out.append((rrect(x, y, 22, 22, 11), BLUE_LIGHT))
    out.append((rrect(28, 8, 72, 112, 14), BLUE))
    # Face
    out.append((rrect(33, 11, 62, 106, 9), FACE))
    # OWON logo mark and model plate
    out.append((circle(39.5, 16.5, 2), ORANGE))
    out.append((rrect(43, 15.3, 14, 2.4, 1.2), KEY_TEXT))
    # LCD
    out.append((rrect(37.5, 20, 53, 31, 3), LCD_BOTTOM))
    out.append((rrect(40.5, 23, 8, 2.4, 1), INK))  # AUTO
    for i, ch in enumerate("3425"):
        out.append((digit(45 + i * 10.6, 27, 8, 20, 2.4, ch), INK))
    out.append((rrect(53.8, 44.6, 2.4, 2.4, 0.6), INK))  # decimal point after the first digit
    # Keys: Select, TRUE RMS badge, light/H; Range, Hz/Duty
    out.append((rrect(37.5, 55, 13, 6, 2), KEY))
    out.append((rrect(56, 54.5, 16, 8, 2), KEY))
    out.append((rrect(57.5, 56.5, 13, 1.6, 0.8), KEY_TEXT))
    out.append((rrect(57.5, 59.3, 13, 1.6, 0.8), KEY_TEXT))
    out.append((rrect(77.5, 55, 13, 6, 2), KEY))
    out.append((rrect(37.5, 64, 13, 6, 2), KEY))
    out.append((rrect(77.5, 64, 13, 6, 2), KEY))
    # Dial: orange auto-power-off arc, ring, knob, pointer
    out.append((arc(64, 87, 18.5, 35, 145, 1.8), ORANGE))
    out.append((circle(64, 87, 15), DIAL_RING))
    out.append((circle(64, 87, 13), DIAL))
    out.append((rrect(61.6, 72.5, 4.8, 13.5, 2.4), "#4A4E55"))
    # Jacks: 20A, µA mA, COM, VΩHz
    for x, ring in ((42, ORANGE), (54.7, ORANGE), (73.3, KEY), (86, RED)):
        out.append((circle(x, 109, 4.2), ring))
        out.append((circle(x, 109, 2.6), "#050505"))
    return out


def svg() -> str:
    body = "\n".join(f'  <path fill="{fill}" d="{d}"/>' for d, fill in shapes())
    return f'<svg xmlns="http://www.w3.org/2000/svg" width="128" height="128" viewBox="0 0 128 128">\n{body}\n</svg>\n'


def vector(paths: list[tuple[str, str]], scale: float, dx: float, dy: float) -> str:
    items = "\n".join(f'        <path android:fillColor="{fill}" android:pathData="{d}"/>' for d, fill in paths)
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by scripts/make_icons.py -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
    <group android:scaleX="{scale}" android:scaleY="{scale}" android:translateX="{dx}" android:translateY="{dy}">
{items}
    </group>
</vector>
'''


def main() -> None:
    (ROOT / "desktop/data/com.ssjrocks.OW18BLcd.svg").write_text(svg())
    # The adaptive icon's safe zone is the central 66 dp circle: fit the 122-unit-tall meter in ~64 dp.
    scale = 0.53
    dx = 54 - 64 * scale
    dy = 54 - 64 * scale
    res = ROOT / "android/app/src/main/res/drawable"
    (res / "ic_launcher_foreground.xml").write_text(vector(shapes(), scale, dx, dy))
    # Monochrome: the holster with the LCD, keys, dial and jacks cut out.
    cutouts = [d for d, fill in shapes() if fill in (LCD_BOTTOM, KEY, DIAL_RING)]
    silhouette = [(rrect(26, 4, 76, 119, 18) + "".join(cutouts), "#FFFFFF")]
    mono = vector(silhouette, scale, dx, dy).replace('android:fillColor="#FFFFFF"', 'android:fillColor="#FFFFFF" android:fillType="evenOdd"')
    (res / "ic_launcher_monochrome.xml").write_text(mono)
    print("wrote icons")


if __name__ == "__main__":
    main()
