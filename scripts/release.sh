#!/usr/bin/env bash
# Publishes the release APK as a GitHub release, with ow18b-lcd.json (version code, version
# name, SHA-256) for the in-app updater.
#
# Usage: scripts/release.sh [--no-build] NOTES.md
#   --no-build   publish the APK already in app/build/outputs/apk/release
# Bump versionCode/versionName in android/app/build.gradle.kts (and desktop/ow18b_lcd/__init__.py) first. Builds run under `heavy`
# when it exists (see ~/.claude/CLAUDE.md on the build PC).
set -euo pipefail
build=1
if [[ "${1:-}" == "--no-build" ]]; then build=0; shift; fi
notes="${1:?usage: scripts/release.sh [--no-build] NOTES.md}"
[[ -f "$notes" ]] || { echo "no notes file: $notes" >&2; exit 2; }
notes="$(realpath "$notes")"
cd "$(dirname "$0")/../android"

if [[ $build == 1 ]]; then
  if command -v heavy >/dev/null; then heavy -m 8G -- ./gradlew --console=plain assembleRelease
  else ./gradlew --console=plain assembleRelease; fi
fi

out=app/build/outputs/apk/release
read -r code name < <(python3 -c "import json,sys; e=json.load(open(sys.argv[1]))['elements'][0]; print(e['versionCode'], e['versionName'])" "$out/output-metadata.json")

# Refuse an APK signed with the debug key: phones would reject it as an update.
sdk=$(sed -n 's/^sdk.dir=//p' local.properties)
apksigner=$(ls -d "$sdk"/build-tools/*/apksigner | sort -V | tail -1)
"$apksigner" verify --print-certs "$out/app-release.apk" | grep -q "CN=OW18B LCD" \
  || { echo "app-release.apk isn't signed with the OW18B LCD release key" >&2; exit 1; }

if git rev-parse -q --verify "refs/tags/v$name" >/dev/null; then
  echo "tag v$name already exists" >&2; exit 1
fi
if [[ -n "$(git status --porcelain)" ]]; then
  echo "commit your changes first" >&2; exit 1
fi

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
cp "$out/app-release.apk" "$tmp/ow18b-lcd.apk"
sha=$(sha256sum "$tmp/ow18b-lcd.apk" | cut -d' ' -f1)
printf '{"versionCode": %s, "versionName": "%s", "sha256": "%s"}\n' "$code" "$name" "$sha" > "$tmp/ow18b-lcd.json"

git tag -a "v$name" -m "OW18B LCD $name"
git push origin main "v$name"
gh release create "v$name" "$tmp/ow18b-lcd.apk" "$tmp/ow18b-lcd.json" --title "OW18B LCD $name" --notes-file "$notes" --verify-tag
echo "released OW18B LCD $name (versionCode $code, sha256 $sha)"
