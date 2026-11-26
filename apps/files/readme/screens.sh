restart=1
settle=2
storage=data
screens=(Browse Recent Starred Trash)
seeded=(Documents/Notes Documents/Reports Documents/budget.csv Download/manual.txt)

stage() {
    adb shell appops set "$package" MANAGE_EXTERNAL_STORAGE allow
    adb shell 'cd /sdcard && mkdir -p Documents/Notes Documents/Reports .Trash &&
        echo "Groceries" > Documents/Notes/list.txt &&
        echo "Ideas" > Documents/Notes/ideas.md &&
        echo "Q3" > Documents/Reports/q3.txt &&
        echo "month,amount" > Documents/budget.csv &&
        echo "Manual" > Download/manual.txt &&
        echo "old" > .Trash/draft.txt'
    local files=(Documents/Notes/list.txt Documents/Notes/ideas.md
        Documents/Reports/q3.txt Documents/budget.csv Download/manual.txt)
    for path in "${files[@]}"; do
        adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE \
            -d "file:///sdcard/$path" >/dev/null
    done
    local root=/storage/emulated/0
    preferences files <<EOF
<string name="starred">["$root/Documents/Notes","$root/Documents/budget.csv"]</string>
<string name="trash">[{"path":"$root/.Trash/draft.txt","original":"$root/Documents/draft.txt","deleted":$(date +%s)000}]</string>
EOF
}

unstage() {
    for path in "${seeded[@]}"; do adb shell rm -rf "/sdcard/$path"; done
    adb shell rm -rf /sdcard/.Trash
}
