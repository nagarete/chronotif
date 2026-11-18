#!/usr/bin/env bash
# Cuts a video from raw/takes (phone), raw/demo (website demo) and its captions, 1080x1920 at
# 30 fps. The cut is cuts/NAME.sh (segments) with cuts/NAME.json (captions):
#   ./edit.sh promo            -> out/chronotif-promo.mp4
#   MUSIC=raw/music/x.mp3 MUSIC_START=12 ./edit.sh features
#
# Phone footage is cropped before anything else: the top 150 px (status bar, whose icons belong to
# the phone's real apps) and 28 px on each side (the HyperOS Sidebar handle). Segment start times
# are chosen to skip moments where a take shows the home screen; keep it that way when re-cutting.
set -euo pipefail
cd "$(dirname "$0")"
cut=${1:?usage: ./edit.sh NAME}
raw=raw; tmp=raw/edit/$cut; out=out; caps=raw/captions/$cut
mkdir -p "$tmp" "$out"
PLAYWRIGHT_CORE=${PLAYWRIGHT_CORE:-/tmp/promo-tools/node_modules/playwright-core} node captions.mjs "cuts/$cut.json" "$caps" >/dev/null

W=1080 H=1920 FPS=30 XF=0.35
PW=672 PH=1440 PX=204 PY=440 R=48   # the phone screen inside the frame

# Background, rounded-corner mask and the phone's bezel.
magick -size ${W}x${H} radial-gradient:'#1b2236'-'#0b0c10' -gravity north -extent ${W}x${H} "$tmp/bg.png"
magick -size ${PW}x${PH} xc:black -fill white -draw "roundrectangle 0,0,$((PW-1)),$((PH-1)),$R,$R" "$tmp/mask.png"
magick -size $((PW+24))x$((PH+24)) xc:none -fill '#2a2d36' -draw "roundrectangle 0,0,$((PW+23)),$((PH+23)),$((R+12)),$((R+12))" \
  -fill '#08090c' -draw "roundrectangle 4,4,$((PW+19)),$((PH+19)),$((R+9)),$((R+9))" "$tmp/bezel.png"

caption_in="format=rgba,fade=t=in:st=0.15:d=0.3:alpha=1"
segments=()

# phone NAME TAKE START DUR CAPTION
phone() {
  local name=$1 take=$2 ss=$3 dur=$4 cap=$5
  ffmpeg -loglevel error -y -ss "$ss" -t "$dur" -i "$raw/takes/$take.mp4" -loop 1 -i "$tmp/bg.png" \
    -loop 1 -i "$tmp/bezel.png" -loop 1 -i "$tmp/mask.png" -loop 1 -i "$caps/$cap.png" -filter_complex "
      [0:v]fps=$FPS,crop=1224:2622:28:150,scale=$PW:$PH:flags=lanczos,format=rgba[s];
      [3:v]format=gray[m]; [s][m]alphamerge[screen];
      [1:v][2:v]overlay=$((PX-12)):$((PY-12))[b]; [b][screen]overlay=$PX:$PY[p];
      [4:v]$caption_in[c]; [p][c]overlay=0:0,format=yuv420p[v]" \
    -map "[v]" -t "$dur" -r $FPS -c:v libx264 -crf 16 -preset slow "$tmp/$name.mp4"
  segments+=("$name:$dur")
}

# card NAME IMAGE DUR
card() {
  ffmpeg -loglevel error -y -loop 1 -t "$3" -i "$caps/$2.png" -vf "fps=$FPS,format=yuv420p" \
    -c:v libx264 -crf 16 -preset slow "$tmp/$1.mp4"
  segments+=("$1:$3")
}

# demo NAME START DUR CAPTION: the website demo, with its view-transition spill below the
# device frame covered by the empty background from a still.
demo() {
  ffmpeg -loglevel error -y -ss "$2" -t "$3" -i "$raw/demo/demo.mp4" -loop 1 -i "$raw/demo/still.png" \
    -loop 1 -i "$caps/$4.png" -filter_complex "
      [1:v]crop=$W:$((H-1560)):0:1560[floor]; [0:v][floor]overlay=0:1560[d];
      [2:v]$caption_in[c]; [d][c]overlay=0:0,format=yuv420p[v]" \
    -map "[v]" -t "$3" -r $FPS -c:v libx264 -crf 16 -preset slow "$tmp/$1.mp4"
  segments+=("$1:$3")
}

source "cuts/$cut.sh"

# Chain with short crossfades.
inputs=() chain="" prev="0:v" offset=0 i=0
for s in "${segments[@]}"; do inputs+=(-i "$tmp/${s%%:*}.mp4"); done
for s in "${segments[@]:1}"; do
  i=$((i + 1)); d=${segments[i-1]#*:}
  offset=$(python3 -c "print(round($offset + $d - $XF, 3))")
  chain+="[$prev][$i:v]xfade=transition=fade:duration=$XF:offset=$offset[x$i];"; prev="x$i"
done
total=$(python3 -c "print(round($offset + ${segments[-1]#*:}, 3))")
final="$out/chronotif-$cut.mp4"
silent="$tmp/silent.mp4"
ffmpeg -loglevel error -y "${inputs[@]}" -filter_complex "${chain%;}" -map "[$prev]" \
  -c:v libx264 -crf 18 -preset slow -pix_fmt yuv420p -movflags +faststart "$silent"
if [[ -n ${MUSIC:-} ]]; then ./mux.sh "$silent" "$MUSIC" "$final" "${MUSIC_START:-0}" >/dev/null; else cp "$silent" "$final"; fi
echo "$final (${total}s)"
