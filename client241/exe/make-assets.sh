#!/bin/bash
# Remake the exe's icon, splash and Windows resource file (needs ImageMagick with DejaVu fonts and
# github.com/akavel/rsrc). The outputs are committed, so a normal build does not need this.
set -euo pipefail
cd "$(dirname "$0")"
convert -size 256x256 xc:none -fill '#1d1a14' -draw "roundrectangle 8,8 248,248 40,40" -fill '#ff981f' \
  -font DejaVu-Sans-Bold -pointsize 170 -gravity center -annotate +0-14 'G' \
  -fill '#ffffff' -pointsize 52 -annotate +0+86 '241' icon256.png
convert icon256.png -define icon:auto-resize=256,64,48,32,16 icon.ico
rm icon256.png
convert -size 420x140 xc:'#1d1a14' -fill '#ff981f' -font DejaVu-Sans-Bold -pointsize 30 -gravity north \
  -annotate +0+28 'GadScape' -fill '#d8d0c0' -font DejaVu-Sans -pointsize 15 -annotate +0+70 'build 241 TEST client' splash.png
rsrc -manifest app.manifest -ico icon.ico -arch amd64 -o rsrc_windows_amd64.syso
