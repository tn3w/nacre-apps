screens=(Alarm Clock Timer Stopwatch)

alarm() {
    printf '{"id":%s,"hour":%s,"minute":%s,"days":%s,"label":"%s","enabled":true,' "$@"
    printf '"vibrate":true,"snoozedUntil":0,"skipped":0}'
}

stage() {
    local time
    time=$(now)
    preferences clock <<EOF
<string name="zones">Asia/Tokyo,Europe/London,America/New_York</string>
<string name="alarms">[$(alarm 1 6 30 31 Work),$(alarm 2 8 0 96 Run),$(alarm 3 21 45 0 Read)]</string>
<string name="timers">[{"id":1,"duration":540000,"remaining":513000,"endsAt":0,"label":"Pasta"},
{"id":2,"duration":240000,"remaining":240000,"endsAt":$((time + 205000)),"label":"Tea"}]</string>
<string name="stopwatch">{"accumulated":0,"startedAt":$((time - 32000)),"laps":[3110,5730,9150]}</string>
EOF
}
