#!/bin/bash
# Remake the exe's icons, splash images and Windows resource files from client241/branding (the GadScape shield of
# the 211 client and the Cinzel Decorative font). Needs ImageMagick and github.com/akavel/rsrc. The outputs are
# committed, so a normal build does not need this.
#   release (exe/):          icon.ico, splash.png, rsrc_windows_amd64.syso
#   test    (exe/test-res/): the same with a red TEST band / "TEST build" line
set -euo pipefail
cd "$(dirname "$0")"
b=../branding
font="$b/CinzelDecorative-Bold.ttf"
mkdir -p test-res
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT

# icons: the shield; test adds a red band with TEST across the bottom
convert "$b/shield-256.png" -define icon:auto-resize=256,64,48,32,16 icon.ico
convert "$b/shield-256.png" \( -size 236x70 xc:'#b3121b' -fill white -font DejaVu-Sans-Bold -pointsize 54 \
  -gravity center -annotate +0+2 'TEST' \) -gravity south -geometry +0+4 -composite "$tmp/test256.png"
convert "$tmp/test256.png" -define icon:auto-resize=256,64,48,32,16 test-res/icon.ico

# splash 420x140: shield on the left, name in Cinzel Decorative gold; the bottom 50 px stay free for the
# progress text and bar that progress.go draws (text at y 96-102, bar at y 118)
splash() { # out subtitle
  convert -size 420x140 xc:'#1d1a14' \
    \( "$b/shield-256.png" -resize 84x84 \) -gravity northwest -geometry +22+6 -composite \
    -font "$font" -fill '#f0c450' -pointsize 40 -gravity northwest -annotate +122+16 'GadScape' \
    -font DejaVu-Sans -fill '#d8d0c0' -pointsize 14 -annotate +126+66 "$2" "$1"
}
splash splash.png 'Old School RuneScape private server'
splash test-res/splash.png 'TEST build (241)'

rsrc -manifest app.manifest -ico icon.ico -arch amd64 -o rsrc_windows_amd64.syso
rsrc -manifest app.manifest -ico test-res/icon.ico -arch amd64 -o test-res/rsrc_windows_amd64.syso
