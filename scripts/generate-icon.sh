#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SVG="$ROOT_DIR/desktop/build/icon.svg"
WORK="$ROOT_DIR/target/icon-work"
ICONSET="$WORK/DMConnect.iconset"
PREVIEW="$WORK/preview"

rm -rf "$WORK"
mkdir -p "$ICONSET" "$PREVIEW"
qlmanage -t -s 1024 -o "$PREVIEW" "$SVG" >/dev/null 2>&1
SOURCE="$(find "$PREVIEW" -type f -name '*.png' -print -quit)"
if [[ -z "$SOURCE" ]]; then
  echo "无法从 SVG 生成应用图标" >&2
  exit 1
fi

for size in 16 32 128 256 512; do
  sips -z "$size" "$size" "$SOURCE" --out "$ICONSET/icon_${size}x${size}.png" >/dev/null
  double=$((size * 2))
  sips -z "$double" "$double" "$SOURCE" --out "$ICONSET/icon_${size}x${size}@2x.png" >/dev/null
done
iconutil -c icns "$ICONSET" -o "$ROOT_DIR/target/icon.icns"
echo "应用图标已生成：$ROOT_DIR/target/icon.icns"
