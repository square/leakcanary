#!/usr/bin/env bash
# Rasterises shark-explorer-icon.svg into the icons the build needs:
#
#   shark-explorer-icon.icns              the macOS .dmg, via iconutil, so macOS only
#   shark-explorer-icon.ico               the Windows .msi
#   ../src/main/resources/                the window icon at runtime, and the Linux .deb
#     shark-explorer-icon.png
#
# Needs librsvg (brew install librsvg) for rsvg-convert, and python3 to pack the .ico, which has no
# command line tool on macOS.
set -euo pipefail

cd "$(dirname "$0")"
svg=shark-explorer-icon.svg
runtime_png=../src/main/resources/shark-explorer-icon.png

render() { rsvg-convert -w "$1" -h "$1" "$svg" -o "$2"; }

# The runtime icon is a single PNG, so it has to be big enough for a Retina dock.
mkdir -p "$(dirname "$runtime_png")"
render 512 "$runtime_png"

iconset=$(mktemp -d)/shark-explorer-icon.iconset
mkdir -p "$iconset"
for size in 16 32 128 256 512; do
  render "$size" "$iconset/icon_${size}x${size}.png"
  render $((size * 2)) "$iconset/icon_${size}x${size}@2x.png"
done
iconutil --convert icns "$iconset" --output shark-explorer-icon.icns

ico=$(mktemp -d)
for size in 16 24 32 48 64 128 256; do
  render "$size" "$ico/$size.png"
done
python3 - "$ico" shark-explorer-icon.ico <<'PY'
"""Packs PNGs into an .ico. Windows reads PNG entries directly, so nothing is re-encoded."""
import struct, sys, os

directory, out = sys.argv[1], sys.argv[2]
pngs = [(size, open(os.path.join(directory, f"{size}.png"), "rb").read())
        for size in sorted(int(name[:-4]) for name in os.listdir(directory))]
header = struct.pack("<HHH", 0, 1, len(pngs))
offset = len(header) + 16 * len(pngs)
entries, images = b"", b""
for size, png in pngs:
    # 256 is written as 0, which is how the format says "not 1..255".
    entries += struct.pack("<BBBBHHII", size % 256, size % 256, 0, 0, 1, 32, len(png), offset)
    images += png
    offset += len(png)
open(out, "wb").write(header + entries + images)
PY

echo "wrote shark-explorer-icon.icns, shark-explorer-icon.ico and $runtime_png"
