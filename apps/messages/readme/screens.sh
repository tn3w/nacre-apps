storage=data
screens=(Inbox Chat New Settings)

provider=content://com.android.contacts

contact() {
    local id
    adb shell content insert --uri "$provider/raw_contacts" \
        --bind account_type:n: --bind account_name:n: --bind starred:i:0
    id=$(adb shell content query --uri "$provider/raw_contacts" --projection _id |
        tail -1 | sed 's/.*_id=//' | tr -d '\r')
    adb shell content insert --uri "$provider/data" --bind "raw_contact_id:i:$id" \
        --bind mimetype:s:vnd.android.cursor.item/name --bind "data1:s:${1// /\\ }"
    adb shell content insert --uri "$provider/data" --bind "raw_contact_id:i:$id" \
        --bind mimetype:s:vnd.android.cursor.item/phone_v2 \
        --bind "data1:s:${2// /\\ }" --bind data2:i:2
}

text() {
    local now
    now=$(adb shell date +%s | tr -d '\r')
    adb shell content insert --uri content://sms --bind "address:s:$1" \
        --bind "body:s:${2// /\\ }" --bind "type:i:$3" \
        --bind "date:l:$(((now - $4 * 60) * 1000))" --bind read:i:1 --bind seen:i:1
}

stage() {
    unstage
    adb shell appops set com.android.shell WRITE_SMS allow
    adb shell cmd role add-role-holder android.app.role.SMS "$package"
    for permission in READ_CONTACTS READ_PHONE_STATE READ_PHONE_NUMBERS; do
        adb shell pm grant "$package" "android.permission.$permission"
    done
    contact "Ada Lovelace" "+44 20 7946 0958"
    contact "Alan Turing" "+44 161 496 0321"
    contact "Grace Hopper" "+1 202-555-0147"
    contact "Marie Curie" "+33 1 23 45 67 89"
    text "+33123456789" "Merci! See you at the lab on Monday." 1 2900
    text "+12025550147" "Found the bug. It was a moth, again." 1 1500
    text "+12025550147" "Ha! Tape it into the logbook." 2 1490
    text "+441614960321" "Can machines think? Shall we discuss tonight?" 1 190
    text "+442079460958" "The engine is ready for a first program." 1 40
    text "+442079460958" "Wonderful. Bringing the Bernoulli notes." 2 35
    text "+442079460958" "Perfect, see you at 7." 1 12
    text "+442079460958" "Reacted 👍 to “Perfect, see you at 7.”" 2 10
}

unstage() {
    adb shell appops set com.android.shell WRITE_SMS allow
    adb shell content delete --uri "$provider/raw_contacts?caller_is_syncadapter=true" \
        --where "_id\>0"
    adb shell content delete --uri content://mms-sms/conversations
    adb shell appops set com.android.shell WRITE_SMS default
}
