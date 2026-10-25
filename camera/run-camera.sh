#!/bin/sh
set -eu
# MTX_PATH is restricted by the configured path regex. Never log stream keys.
case "$MTX_PATH" in live/cam_*) ;; *) exit 1 ;; esac
CAMERA_FILE="${MTX_PATH#live/}"
exec ffmpeg -hide_banner -loglevel fatal -nostdin -threads 2 \
  -fflags nobuffer -flags low_delay -rtsp_transport tcp \
  -i "rtsp://127.0.0.1:8554/$MTX_PATH" -an \
  -vf 'fps=3,scale=min(1920\,iw):-2' -threads 2 -q:v 2 \
  -f image2 -update 1 -atomic_writing 1 "/frames/$CAMERA_FILE.jpg"
