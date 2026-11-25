storage=data
screens=(Contacts Favorites Details Manage)

provider=content://com.android.contacts

field() {
    adb shell content insert --uri "$provider/data" --bind "raw_contact_id:i:$1" \
        --bind "mimetype:s:vnd.android.cursor.item/$2" --bind "data1:s:${3// /\\ }" \
        --bind "data2:s:${4// /\\ }" --bind "data3:s:${5:-}" >/dev/null
}

contact() {
    local id
    adb shell content insert --uri "$provider/raw_contacts" \
        --bind account_type:n: --bind account_name:n: --bind "starred:i:$4"
    id=$(adb shell content query --uri "$provider/raw_contacts" --projection _id |
        tail -1 | sed 's/.*_id=//' | tr -d '\r')
    field "$id" name "$1" "${1% *}" "${1##* }"
    field "$id" phone_v2 "$2" 2
    field "$id" email_v2 "$3" 1
    if [ -n "${5:-}" ]; then field "$id" postal-address_v2 "$5" 1; fi
    if [ -n "${6:-}" ]; then field "$id" contact_event "$6" 3; fi
    if [ -n "${7:-}" ]; then field "$id" organization "$7" 1; fi
}

stage() {
    unstage
    adb shell pm grant "$package" android.permission.READ_CONTACTS
    adb shell pm grant "$package" android.permission.WRITE_CONTACTS
    contact "Ada Lovelace" "+44 20 7946 0958" ada@example.org 1 \
        "12 St James Square, London" 1815-12-10 "Analytical Engines"
    contact "Alan Turing" "+44 161 496 0321" alan@example.org 1
    contact "Grace Hopper" "+1 202-555-0147" grace@example.org 1
    contact "Katherine Johnson" "+1 757-555-0199" katherine@example.org 0
    contact "Linus Pauling" "+1 503-555-0110" linus@example.org 0
    contact "Marie Curie" "+33 1 23 45 67 89" marie@example.org 1
    contact "Niels Bohr" "+45 32 12 34 56" niels@example.org 0
    contact "Rosalind Franklin" "+44 1223 496021" rosalind@example.org 0
}

unstage() {
    adb shell content delete --uri "$provider/raw_contacts?caller_is_syncadapter=true" \
        --where "_id\>0"
}
