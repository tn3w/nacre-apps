restart=1
settle=8
storage=data
screens=(Photo Video Pro Document)

stage() {
    adb shell pm grant "$package" android.permission.CAMERA
    adb shell pm grant "$package" android.permission.RECORD_AUDIO
    preferences camera <<EOF
<string name="settings">{"grid":true,"flash":"Auto"}</string>
EOF
}
