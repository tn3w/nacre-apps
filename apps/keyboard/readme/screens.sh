settle=2
screens=(Languages Typing Words Try)

stage() {
    adb shell settings put secure show_ime_with_hard_keyboard 1
    adb shell ime set "$package/.KeyboardService" >/dev/null
    preferences settings <<EOF
<string name="languages">en,de</string>
<boolean name="NumberRow" value="false" />
EOF
    storage=data preferences history <<EOF
<string name="words">{"Nacre":3,"GrapheneOS":4,"selfhosted":2,"Zürich":2}</string>
<string name="pairs">{"see you":3,"see you soon":1}</string>
EOF
}

unstage() {
    adb shell settings put secure show_ime_with_hard_keyboard 0
}
