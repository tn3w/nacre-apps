storage=data
screens=(Home Apps Folder Settings)

json() {
    printf '{&quot;id&quot;:%s,&quot;kind&quot;:&quot;%s&quot;,&quot;page&quot;:%s,' "$1" "$2" "$3"
    printf '&quot;cell&quot;:%s,&quot;target&quot;:&quot;%s&quot;,&quot;serial&quot;:0' "$4" "$5"
}

app() {
    printf '%s}' "$(json "$1" App "$2" "$3" "$4")"
}

nacre() {
    echo "org.nacre.$1/org.nacre.$1.MainActivity"
}

folder() {
    printf '%s,&quot;name&quot;:&quot;%s&quot;,' "$(json "$1" Folder 0 "$2" "")" "$3"
    printf '&quot;children&quot;:[%s]}' "$4"
}

glance() {
    printf '%s,&quot;width&quot;:4,&quot;height&quot;:1}' "$(json 8 Glance 0 0 "")"
}

stage() {
    local items
    items="[$(glance),$(app 1 -1 0 "$(nacre phone)"),$(app 2 -1 1 "$(nacre messages)"),"
    items+="$(app 3 -1 2 "$(nacre contacts)"),"
    items+="$(app 4 -1 3 com.android.settings/com.android.settings.Settings),"
    items+="$(folder 5 16 Tools "$(app 6 0 0 "$(nacre clock)"),$(app 7 0 0 "$(nacre calculator)")")]"

    preferences launcher <<EOF
<string name="items">$items</string>
EOF
    adb shell am start -W -n "$package/.MainActivity" >/dev/null
    sleep 15
}

unstage() {
    adb shell am force-stop "$package"
    stage
    adb shell input keyevent KEYCODE_HOME
}
