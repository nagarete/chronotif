#!/usr/bin/env bash
# Adds a music track to a silent render: trimmed to the video, faded, levelled to -14 LUFS.
# Usage: ./mux.sh VIDEO MUSIC OUT [START_SECONDS]
set -euo pipefail
video=$1 music=$2 out=$3 start=${4:-0}
dur=$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$video")
fade=$(python3 -c "print(max(0, $dur - 2.5))")
ffmpeg -loglevel error -y -i "$video" -ss "$start" -i "$music" -filter_complex \
  "[1:a]atrim=0:$dur,asetpts=PTS-STARTPTS,afade=t=in:d=0.4,afade=t=out:st=$fade:d=2.5,loudnorm=I=-14:TP=-1.5:LRA=11[a]" \
  -map 0:v -map "[a]" -c:v copy -c:a aac -b:a 192k -ar 48000 -shortest -movflags +faststart "$out"
echo "$out"
