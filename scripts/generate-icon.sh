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

# qlmanage 会把 SVG 的透明画布渲染成白色不透明底图。只移除与画布边缘
# 连通的白色区域，避免误删图标内部的白色描边和确认标记。
python3 - "$SOURCE" <<'PY'
from collections import deque
from pathlib import Path
import sys

from PIL import Image

path = Path(sys.argv[1])
image = Image.open(path).convert("RGBA")
pixels = image.load()
width, height = image.size
queue = deque()
seen = set()

def is_matte(pixel):
    red, green, blue, alpha = pixel
    return alpha > 0 and red >= 245 and green >= 245 and blue >= 245

for x in range(width):
    queue.append((x, 0))
    queue.append((x, height - 1))
for y in range(height):
    queue.append((0, y))
    queue.append((width - 1, y))

while queue:
    x, y = queue.popleft()
    if (x, y) in seen or not (0 <= x < width and 0 <= y < height):
        continue
    seen.add((x, y))
    if not is_matte(pixels[x, y]):
        continue
    pixels[x, y] = (255, 255, 255, 0)
    queue.extend(((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)))

image.save(path)
PY

for size in 16 32 128 256 512; do
  sips -z "$size" "$size" "$SOURCE" --out "$ICONSET/icon_${size}x${size}.png" >/dev/null
  double=$((size * 2))
  sips -z "$double" "$double" "$SOURCE" --out "$ICONSET/icon_${size}x${size}@2x.png" >/dev/null
done
iconutil -c icns "$ICONSET" -o "$ROOT_DIR/target/icon.icns"
echo "应用图标已生成：$ROOT_DIR/target/icon.icns"
