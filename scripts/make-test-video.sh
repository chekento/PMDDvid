#!/usr/bin/env bash
set -euo pipefail
# Asymmetric corners, VFR timestamps, audio and 90-degree metadata expose common
# rotation/mirror/frame-drop regressions without needing a private user video.
mkdir -p app/src/androidTest/assets
ffmpeg -hide_banner -loglevel error -y \
  -f lavfi -i 'color=c=black:s=320x240:r=8:d=1' \
  -f lavfi -i 'sine=frequency=440:sample_rate=48000:duration=1.1' \
  -vf "drawbox=x=0:y=0:w=160:h=120:color=red:t=fill,drawbox=x=160:y=0:w=160:h=120:color=lime:t=fill,drawbox=x=0:y=120:w=160:h=120:color=blue:t=fill,drawbox=x=160:y=120:w=160:h=120:color=yellow:t=fill,settb=1/1000000,setpts=N*125000+floor(N/3)*20000" \
  -fps_mode vfr -enc_time_base 1:1000000 -c:v libx264 -pix_fmt yuv420p -bf 0 -crf 16 -c:a aac -b:a 96k -video_track_timescale 1000000 /tmp/pmddvid-fixture.mp4
ffmpeg -hide_banner -loglevel error -y -i /tmp/pmddvid-fixture.mp4 -c copy -metadata:s:v:0 rotate=90 app/src/androidTest/assets/rotation-vfr.mp4
