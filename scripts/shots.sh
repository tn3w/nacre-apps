#!/usr/bin/env bash
set -euo pipefail

tag=$1
package=org.nacre.$tag
readme=apps/$tag/readme
work=$(mktemp -d)

demo() {
    adb shell am broadcast -a com.android.systemui.demo -e command "$@" >/dev/null
}

cleanup() {
    demo exit
    adb shell settings put global sysui_demo_allowed 0
    adb shell cmd uimode night no >/dev/null
    adb shell pm clear "$package" >/dev/null
    if declare -F unstage >/dev/null; then unstage; fi
    rm -rf "$work"
}

elapsed() {
    adb shell cat /proc/uptime | awk '{ printf "%d", $1 * 1000 }'
}

preferences() {
    local file=/data/${storage:-user_de/0}/$package/shared_prefs/$1.xml
    { echo "<map>"; cat; echo "</map>"; } |
        adb shell "run-as $package sh -c 'mkdir -p ${file%/*} && cat > $file'"
}

capture() {
    local parts=()
    adb shell cmd uimode night "$2" >/dev/null
    sleep 1
    for screen in "${screens[@]}"; do
        adb shell am start -W -n "$package/.MainActivity" --es tab "$screen" >/dev/null
        demo clock -e hhmm "$(adb shell date +%H%M | tr -d '\r')"
        sleep 0.8
        adb exec-out screencap -p >"$work/$screen.png"
        magick "$work/$screen.png" -resize 360x -alpha set \
            \( +clone -alpha transparent -fill white \
            -draw "roundrectangle 0,0,%[fx:w-1],%[fx:h-1],28,28" \) \
            -compose DstIn -composite -compose Over -bordercolor none -border 16 \
            "$work/$screen-$1.png"
        parts+=("$work/$screen-$1.png")
    done
    magick "${parts[@]}" -background none +append -bordercolor none -border 16 \
        -strip -define png:color-type=6 "$readme/screens-$1.png"
}

trap cleanup EXIT
source "$readme/screens.sh"

scripts/logo.sh "$tag"

adb shell pm clear "$package" >/dev/null
adb shell pm grant "$package" android.permission.POST_NOTIFICATIONS 2>/dev/null || true
stage

adb shell settings put global sysui_demo_allowed 1
demo enter
demo battery -e level 100 -e plugged false
demo network -e wifi show -e level 4
demo network -e mobile hide
demo notifications -e visible false

capture light no
capture dark yes
