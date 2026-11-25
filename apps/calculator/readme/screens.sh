storage=data
screens=(Basic Scientific History Converter)

entry() {
    printf '{"expression":"%s","result":"%s"}' "$@"
}

stage() {
    local history
    history="$(entry '1280×12' 15360),$(entry '√2' 1.414213562373095),"
    history+="$(entry '(68−32)×5÷9' 20),$(entry '0.1+0.2' 0.3),$(entry '2^10' 1024)"

    preferences calculator <<EOF
<string name="expression">1280×12+4.5%</string>
<boolean name="degrees" value="true" />
<string name="history">[$history]</string>
<string name="category">Currency</string>
<string name="unit.Currency">BTC</string>
<string name="input">0.05</string>
EOF
}
