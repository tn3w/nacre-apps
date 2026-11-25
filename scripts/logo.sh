#!/usr/bin/env bash
set -euo pipefail

tag=$1
title=${2:-${tag^}}
out=apps/$tag/readme
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

badge() {
    rsvg-convert -w 120 "$work/$1/$tag.svg" -o "$work/$1.png"
    magick "$work/$1.png" \
        \( -background none -fill "$3" -font Noto-Sans-Medium -pointsize 64 \
        "label:$title" \) \
        -background none -gravity center +smush 20 -bordercolor none -border 44x20 \
        \( +clone -alpha transparent -fill "$2" \
        -draw "roundrectangle 0,0,%[fx:w-1],%[fx:h-1],44,44" \) \
        +swap -compose Over -composite -strip "$out/logo-$4.png"
}

mkdir -p "$out"
python3 scripts/sheen.py "icons/$tag.svg" -o "$work" >/dev/null
badge black '#E6E6E3' '#0B0B0C' light
badge white '#1C1C1F' '#FAFAF7' dark
