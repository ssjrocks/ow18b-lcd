#!/usr/bin/env bash
# Installs OW18B LCD for the current user: a venv in ~/.local/share/ow18b-lcd (system GTK + bleak),
# the `ow18b-lcd` command in ~/.local/bin, and a launcher with icon.
# Needs: python3-gi, gir1.2-gtk-4.0, gir1.2-adw-1 (Ubuntu 24.04+ / Fedora 40+ have them).
# Re-run after updating the code. Uninstall: ./install.sh --uninstall
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
share="$HOME/.local/share/ow18b-lcd"
bin="$HOME/.local/bin/ow18b-lcd"
desktop="$HOME/.local/share/applications/com.ssjrocks.OW18BLcd.desktop"
icon="$HOME/.local/share/icons/hicolor/scalable/apps/com.ssjrocks.OW18BLcd.svg"

if [[ "${1:-}" == "--uninstall" ]]; then
  rm -rf "$share" && rm -f "$bin" "$desktop" "$icon"
  echo "removed OW18B LCD"; exit 0
fi

/usr/bin/python3 -c 'import gi; gi.require_version("Gtk", "4.0"); gi.require_version("Adw", "1")' 2>/dev/null \
  || { echo "GTK 4 / libadwaita Python bindings missing: sudo apt install python3-gi gir1.2-gtk-4.0 gir1.2-adw-1" >&2; exit 1; }

mkdir -p "$share" "$(dirname "$bin")" "$(dirname "$desktop")" "$(dirname "$icon")"
if command -v uv >/dev/null; then
  [[ -x "$share/venv/bin/python" ]] || uv venv -q -p /usr/bin/python3 --system-site-packages "$share/venv"
  uv pip install -q -p "$share/venv" bleak
else
  [[ -x "$share/venv/bin/python" ]] || /usr/bin/python3 -m venv --system-site-packages "$share/venv"
  "$share/venv/bin/pip" install -q bleak
fi
rm -rf "$share/ow18b_lcd" && cp -r "$here/ow18b_lcd" "$share/"
find "$share/ow18b_lcd" -name __pycache__ -prune -exec rm -rf {} +

cat > "$bin" <<EOF
#!/bin/sh
cd "$share" && exec "$share/venv/bin/python" -m ow18b_lcd "\$@"
EOF
chmod +x "$bin"
cp "$here/data/com.ssjrocks.OW18BLcd.svg" "$icon"
cat > "$desktop" <<EOF
[Desktop Entry]
Type=Application
Name=OW18B LCD
Comment=Live readings from an OWON OW18B multimeter over Bluetooth
Exec=$bin
Icon=com.ssjrocks.OW18BLcd
Categories=Utility;Electronics;
StartupWMClass=com.ssjrocks.OW18BLcd
Keywords=multimeter;owon;bluetooth;lcd;
EOF
update-desktop-database -q "$(dirname "$desktop")" 2>/dev/null || true
gtk-update-icon-cache -q -t "$HOME/.local/share/icons/hicolor" 2>/dev/null || true
echo "installed: run 'ow18b-lcd' or open OW18B LCD from the app grid (--demo replays recorded readings)"
