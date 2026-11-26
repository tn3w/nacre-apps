storage=data
screens=(Home Apps Folder Settings)

app() {
    printf '{&quot;id&quot;:%s,&quot;kind&quot;:&quot;App&quot;,&quot;page&quot;:%s,' "$1" "$2"
    printf '&quot;cell&quot;:%s,&quot;target&quot;:&quot;%s&quot;,&quot;serial&quot;:0}' "$3" "$4"
}

nacre() {
    echo "org.nacre.$1/org.nacre.$1.MainActivity"
}

folder() {
    printf '{&quot;id&quot;:%s,&quot;kind&quot;:&quot;Folder&quot;,&quot;page&quot;:0,' "$1"
    printf '&quot;cell&quot;:%s,&quot;target&quot;:&quot;&quot;,&quot;serial&quot;:0,' "$2"
    printf '&quot;name&quot;:&quot;%s&quot;,&quot;children&quot;:[%s]}' "$3" "$4"
}

stage() {
    local items hidden component
    hidden=""
    for component in $(adb shell cmd package query-activities --brief \
        -a android.intent.action.MAIN -c android.intent.category.LAUNCHER |
        tr -d ' \r' | grep / | grep -v -e '^org\.nacre\.' -e '^com\.android\.settings/'); do
        local name=${component#*/}
        [[ $name == .* ]] && name=${component%%/*}$name
        hidden+="<string>${component%%/*}/$name#0</string>"
    done
    items="[$(app 1 -1 0 "$(nacre phone)"),$(app 2 -1 1 "$(nacre messages)"),"
    items+="$(app 3 -1 2 "$(nacre contacts)"),"
    items+="$(app 4 -1 3 com.android.settings/com.android.settings.Settings),"
    items+="$(folder 5 16 Tools "$(app 6 0 0 "$(nacre clock)"),$(app 7 0 0 "$(nacre calculator)")")]"

    preferences launcher <<EOF
<string name="items">$items</string>
<set name="hidden">$hidden</set>
EOF
}

unstage() {
    adb shell am force-stop "$package"
    stage
    adb shell input keyevent KEYCODE_HOME
}
