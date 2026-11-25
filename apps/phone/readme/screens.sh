storage=data
screens=(Favorites Recents Keypad Call)
callee=5550147

provider=content://com.android.contacts

contact() {
    local id
    adb shell content insert --uri "$provider/raw_contacts" \
        --bind account_type:n: --bind account_name:n: --bind "starred:i:$3"
    id=$(adb shell content query --uri "$provider/raw_contacts" --projection _id |
        tail -1 | sed 's/.*_id=//' | tr -d '\r')
    adb shell content insert --uri "$provider/data" --bind "raw_contact_id:i:$id" \
        --bind mimetype:s:vnd.android.cursor.item/name --bind "data1:s:${1// /\\ }"
    adb shell content insert --uri "$provider/data" --bind "raw_contact_id:i:$id" \
        --bind mimetype:s:vnd.android.cursor.item/phone_v2 \
        --bind "data1:s:${2// /\\ }" --bind data2:i:2
}

call() {
    local now
    now=$(adb shell date +%s | tr -d '\r')
    adb shell content insert --uri content://call_log/calls --bind "number:s:$1" \
        --bind "name:s:${2// /\\ }" --bind "type:i:$3" \
        --bind "date:l:$(((now - $4 * 60) * 1000))" --bind "duration:i:$5" \
        --bind new:i:0 --bind is_read:i:1
}

stage() {
    unstage
    adb shell cmd role add-role-holder android.app.role.DIALER "$package"
    contact "Ada Lovelace" "+44 20 7946 0958" 1
    contact "Alan Turing" "+44 161 496 0321" 1
    contact "Grace Hopper" "555-0147" 1
    contact "Katherine Johnson" "+1 757-555-0199" 0
    contact "Marie Curie" "+33 1 23 45 67 89" 1
    contact "Niels Bohr" "+45 32 12 34 56" 0
    contact "Rosalind Franklin" "+44 1223 496021" 0
    call "5550147" "Grace Hopper" 3 12 0
    call "+447946000000" "" 1 45 312
    call "+441614960321" "Alan Turing" 2 130 845
    call "+441614960321" "Alan Turing" 1 190 64
    call "+33123456789" "Marie Curie" 2 1500 1290
    call "+17575550199" "Katherine Johnson" 1 2900 402
    adb shell am start -W -n "$package/.MainActivity" >/dev/null
    adb shell am start -a android.intent.action.CALL -d "tel:$callee" >/dev/null
    sleep 4
    adb shell input keyevent KEYCODE_HOME
}

unstage() {
    adb shell input keyevent KEYCODE_ENDCALL
    adb shell content delete --uri "$provider/raw_contacts?caller_is_syncadapter=true" \
        --where "_id\>0"
    adb shell content delete --uri content://call_log/calls
    adb shell content delete --uri content://com.android.blockednumber/blocked
}
