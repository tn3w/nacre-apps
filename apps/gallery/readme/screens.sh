restart=1
settle=2
storage=data
screens=(Photos Albums Favorites Trash)

stage() {
    adb shell pm grant "$package" android.permission.READ_MEDIA_IMAGES
    adb shell pm grant "$package" android.permission.READ_MEDIA_VIDEO
    preferences gallery <<EOF
<string name="settings">{"columns":3}</string>
EOF
}
