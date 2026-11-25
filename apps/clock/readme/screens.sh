screens=(Alarm Clock Timer Stopwatch)

alarm() {
    printf '{"id":%s,"hour":%s,"minute":%s,"days":%s,"label":"%s",' "$@"
    printf '"enabled":true,"vibrate":true,"snoozedUntil":0,"skipped":0}'
}

timer() {
    printf '{"id":%s,"duration":%s,"remaining":%s,"endsAt":%s,"label":"%s"}' "$@"
}

stage() {
    local time alarms timers stopwatch
    time=$(elapsed)
    alarms="$(alarm 1 6 30 31 Work),$(alarm 2 8 0 96 Run),$(alarm 3 21 45 0 Read)"
    timers="$(timer 1 540000 513000 0 Pasta),"
    timers+="$(timer 2 240000 240000 $((time + 205000)) Tea)"
    stopwatch='{"accumulated":32000,"startedAt":'$time',"laps":[3110,5730,9150]}'

    preferences clock <<EOF
<string name="zones">Asia/Tokyo,Europe/London,America/New_York</string>
<string name="alarms">[$alarms]</string>
<string name="timers">[$timers]</string>
<string name="stopwatch">$stopwatch</string>
EOF
}
