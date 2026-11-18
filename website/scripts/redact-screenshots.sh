#!/usr/bin/env bash
# Builds the images the site publishes in public/screens/.
#
# App screenshots are frames from the promo takes in video/raw/takes/ (git-ignored; see
# video/README.md). Those were recorded in demo mode, so they show only made-up notifications
# from the demo apps and need no blurring. Each entry is a take and a time in seconds inside that
# take's safe range: never use a range the README marks as showing the home screen.
#
# Also crops the install-guide screenshots from screenshots-raw/install-guide/. Those are real
# captures, so regions holding account details are blurred.
#
# Requires ffmpeg and ImageMagick 7 (`magick`). Usage: npm run screens
set -euo pipefail
cd "$(dirname "$0")/.."

takes=video/raw/takes
out=public/screens
mkdir -p "$out"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

# name | take | seconds
frames=(
  "panel|panel|18"
  "lock|lock|0.5"
  "panel-earlier|panel|22"
  "lock-chat|lock|8"
  "arrive|arrive|3"
  "check|check|6"
  "preview|preview|2.5"
  "running|running|3"
)

for entry in "${frames[@]}"; do
  IFS='|' read -r name take secs <<<"$entry"
  ffmpeg -v error -ss "$secs" -i "$takes/$take.mp4" -frames:v 1 -y "$tmp/$name.png"
  for width in 480 800; do
    magick "$tmp/$name.png" -strip -resize "${width}x" -quality 82 -define webp:method=6 "$out/$name-$width.webp"
  done
  echo "wrote $out/$name-{480,800}.webp"
done

# Install-guide crops: system settings screens, cropped (crop geometry | optional accent ring
# as x1,y1,x2,y2 in the cropped image | optional regions to blur, same coordinates, for the
# Google account card on Play Store screens).
guide=screenshots-raw/install-guide
crops=(
  "install-denied|Screenshot_2026-10-23-08-59-09-474_com.google.android.permissioncontroller.jpg|1280x1250+0+840|"
  "install-restricted|Screenshot_2026-10-23-08-59-43-236_com.android.settings.jpg|1280x960+0+2130|30,720,1250,900|"
  "install-playprotect-menu|Screenshot_2026-10-23-09-03-52-920_com.android.vending.jpg|1280x1660+0+320|53,1404,1227,1587|55,10,1225,270"
  "install-playprotect-home|Screenshot_2026-10-26-14-18-37-846_com.android.vending.jpg|1280x1180+0+150|1124,18,1276,170|"
  "install-playprotect-settings|Screenshot_2026-10-23-09-04-00-748_com.android.vending.jpg|1280x720+0+140|42,407,1240,636|"
  "install-playprotect-pause|Screenshot_2026-10-26-14-18-42-697_com.android.vending.jpg|1280x1750+0+575|112,1115,1168,1281|"
)

for entry in "${crops[@]}"; do
  IFS='|' read -r name file geometry ring blurs <<<"$entry"
  args=("$guide/$file" -crop "$geometry" +repage)
  for r in $blurs; do
    IFS=',' read -r x1 y1 x2 y2 <<<"$r"
    w=$((x2 - x1)) h=$((y2 - y1))
    args+=(\( +clone -crop "${w}x${h}+${x1}+${y1}" +repage -blur 0x22 \) -geometry "+${x1}+${y1}" -composite)
  done
  if [[ -n $ring ]]; then
    IFS=',' read -r x1 y1 x2 y2 <<<"$ring"
    args+=(-fill none -stroke "#0a84ff" -strokewidth 8 -draw "roundrectangle $x1,$y1 $x2,$y2 56,56")
  fi
  magick "${args[@]}" -strip -resize 640x -quality 82 -define webp:method=6 "$out/$name.webp"
  echo "wrote $out/$name.webp"
done
