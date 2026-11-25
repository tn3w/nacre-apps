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
    rm -rf "$work"
}

now() {
    adb shell date +%s%3N | tr -d '\r'
}

preferences() {
    local file=/data/user_de/0/$package/shared_prefs/$1.xml
    { echo "<map>"; cat; echo "</map>"; } |
        adb shell "run-as $package sh -c 'mkdir -p ${file%/*} && cat > $file'"
}

logo() {
    rsvg-convert -w 128 "$work/icons/$1/$tag.svg" | magick - -strip "$readme/logo-$2.png"
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

python3 scripts/sheen.py "icons/$tag.svg" -o "$work/icons" >/dev/null
logo black light
logo white dark

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
