#!/usr/bin/env bash
# Builds the Android "QR code setup" payload that installs this app as a kiosk (device owner).
#
#   tools/kiosk-qr.sh <signed-release.apk> <https-direct-download-url> [wifi-ssid] [wifi-password]
#
# Writes tools/out/kiosk-provisioning.json and tools/out/kiosk-qr.html (open it, scan the QR).
# The URL must download the exact APK passed here (same signature), over HTTPS, with no login page.
set -euo pipefail

APK="${1:?signed APK path}"
URL="${2:?https download URL of that APK}"
SSID="${3:-}"
PASS="${4:-}"

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
APKSIGNER="$(ls -d "$SDK"/build-tools/*/apksigner | sort -V | tail -1)"
[[ -x "$APKSIGNER" ]] || { echo "apksigner not found under $SDK/build-tools" >&2; exit 1; }

CERT_HEX="$("$APKSIGNER" verify --print-certs "$APK" | grep -m1 'certificate SHA-256 digest' | awk '{print $NF}')"
[[ -n "$CERT_HEX" ]] || { echo "APK is not signed — sign it with your release key first" >&2; exit 1; }
CHECKSUM="$(echo -n "$CERT_HEX" | xxd -r -p | base64 | tr '+/' '-_' | tr -d '=')"

OUT="$(cd "$(dirname "$0")" && pwd)/out"
mkdir -p "$OUT"

python3 - "$URL" "$CHECKSUM" "$SSID" "$PASS" > "$OUT/kiosk-provisioning.json" <<'PY'
import json, sys
url, checksum, ssid, password = sys.argv[1:5]
p = {
    "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME":
        "com.guardianangel.dronetelementry/com.guardianangel.dronetelementry.kiosk.KioskAdminReceiver",
    "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION": url,
    "android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM": checksum,
    # Keep system apps (WebView/Chrome are needed for the Microsoft sign-in page).
    "android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED": True,
    "android.app.extra.PROVISIONING_SKIP_ENCRYPTION": False,
}
if ssid:
    p["android.app.extra.PROVISIONING_WIFI_SSID"] = ssid
    if password:
        p["android.app.extra.PROVISIONING_WIFI_PASSWORD"] = password
        p["android.app.extra.PROVISIONING_WIFI_SECURITY_TYPE"] = "WPA"
print(json.dumps(p, separators=(",", ":")))
PY

python3 - "$OUT/kiosk-provisioning.json" > "$OUT/kiosk-qr.html" <<'PY'
import json, sys
payload = open(sys.argv[1]).read().strip()
print(f"""<!doctype html><meta charset="utf-8"><title>Kiosk setup QR</title>
<style>body{{font-family:sans-serif;display:grid;place-items:center;min-height:100vh;margin:0;background:#fff}}
#qr{{padding:24px;background:#fff}}p{{max-width:520px;color:#333}}</style>
<div><div id="qr"></div><p>Factory-reset tablet &rarr; tap the welcome screen 6 times &rarr; scan this code.</p></div>
<script src="https://cdnjs.cloudflare.com/ajax/libs/qrcodejs/1.0.0/qrcode.min.js"></script>
<script>new QRCode(document.getElementById("qr"),{{text:{json.dumps(payload)},width:480,height:480,correctLevel:QRCode.CorrectLevel.M}});</script>""")
PY

echo "Signature checksum: $CHECKSUM"
echo "Payload: $OUT/kiosk-provisioning.json"
echo "QR page: $OUT/kiosk-qr.html  (open it in a browser)"
