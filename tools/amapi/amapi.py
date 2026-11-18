#!/usr/bin/env python3
"""
Kiosk setup through Google's Android Management API (AMAPI).

Google's "Android Device Policy" becomes device owner, installs the app from managed Google
Play and pins it (single-app kiosk). Nothing is sideloaded, so Play Protect never blocks it.

Commands (run in order the first time; see README.md):
  signup-url         Link to create your enterprise (opens a Google sign-up page)
  create-enterprise  Finish sign-up with the enterpriseToken from the callback URL
  policy             Create/update the kiosk policy for the app
  enroll             Make an enrollment QR code (factory-reset tablet, tap 6x, scan)
  devices            List enrolled tablets
  wipe               Factory-reset a tablet remotely and remove it

State (project, enterprise) is kept in state.json next to this file.
"""
import argparse
import json
import pathlib
import sys

from google.auth.transport.requests import AuthorizedSession
from google.oauth2 import service_account

API = "https://androidmanagement.googleapis.com/v1"
SCOPES = ["https://www.googleapis.com/auth/androidmanagement"]
HERE = pathlib.Path(__file__).resolve().parent
STATE = HERE / "state.json"
OUT = HERE / "out"
DEFAULT_PACKAGE = "com.guardianangel.dronetelementry"
POLICY_ID = "kiosk"


def load_state():
    return json.loads(STATE.read_text()) if STATE.exists() else {}


def save_state(state):
    STATE.write_text(json.dumps(state, indent=2))


def session(key_path):
    creds = service_account.Credentials.from_service_account_file(key_path, scopes=SCOPES)
    return AuthorizedSession(creds)


def call(s, method, url, **kw):
    r = s.request(method, url, **kw)
    if r.status_code >= 400:
        sys.exit(f"API error {r.status_code}: {r.text}")
    return r.json() if r.text else {}


def need(state, key, hint):
    if key not in state:
        sys.exit(f"Missing '{key}' — run `{hint}` first.")
    return state[key]


def kiosk_policy(package):
    return {
        "applications": [{
            "packageName": package,
            # Installs from managed Google Play, launches in lock task, acts as the home screen.
            "installType": "KIOSK",
            "defaultPermissionPolicy": "GRANT",
            "autoUpdateMode": "AUTO_UPDATE_HIGH_PRIORITY",
        }],
        "kioskCustomization": {
            "powerButtonActions": "POWER_BUTTON_AVAILABLE",
            "systemErrorWarnings": "ERROR_AND_WARNINGS_MUTED",
            "systemNavigation": "NAVIGATION_DISABLED",
            "statusBar": "NOTIFICATIONS_AND_SYSTEM_INFO_DISABLED",
            "deviceSettings": "SETTINGS_ACCESS_BLOCKED",
        },
        # OS updates overnight (02:00–04:00 device time) instead of interrupting use.
        "systemUpdate": {"type": "WINDOWED", "startMinutes": 120, "endMinutes": 240},
        "advancedSecurityOverrides": {"developerSettings": "DEVELOPER_SETTINGS_DISABLED"},
    }


def qr_html(payload):
    return f"""<!doctype html><meta charset="utf-8"><title>Kiosk enrollment QR</title>
<style>body{{font-family:sans-serif;display:grid;place-items:center;min-height:100vh;margin:0;background:#fff}}
#qr{{padding:24px;background:#fff}}p{{max-width:520px;color:#333}}</style>
<div><div id="qr"></div><p>Factory-reset tablet &rarr; tap the welcome screen 6 times &rarr; scan this code.</p></div>
<script src="https://cdnjs.cloudflare.com/ajax/libs/qrcodejs/1.0.0/qrcode.min.js"></script>
<script>new QRCode(document.getElementById("qr"),{{text:{json.dumps(payload)},width:520,height:520,correctLevel:QRCode.CorrectLevel.M}});</script>"""


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--key", default=str(HERE / "service-account.json"),
                    help="service-account JSON key (default: tools/amapi/service-account.json)")
    sub = ap.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("signup-url")
    p.add_argument("--project", help="Google Cloud project ID (default: the key file's project_id)")
    p.add_argument("--callback", default="https://www.google.com",
                   help="page to land on after sign-up; copy enterpriseToken from its URL")

    p = sub.add_parser("create-enterprise")
    p.add_argument("--token", required=True, help="enterpriseToken from the callback URL")
    p.add_argument("--name", default="Campus Guardian Angel", help="display name")

    p = sub.add_parser("policy")
    p.add_argument("--package", default=DEFAULT_PACKAGE)

    p = sub.add_parser("enroll")
    p.add_argument("--days", type=int, default=30, help="how long the QR code stays valid")
    p.add_argument("--wifi-ssid")
    p.add_argument("--wifi-password")

    sub.add_parser("devices")

    p = sub.add_parser("wipe")
    p.add_argument("--device", required=True, help="device name from `devices` (enterprises/.../devices/...)")

    a = ap.parse_args()
    state = load_state()
    s = session(a.key)

    if a.cmd == "signup-url":
        a.project = a.project or json.loads(pathlib.Path(a.key).read_text())["project_id"]
        r = call(s, "POST", f"{API}/signupUrls", params={"projectId": a.project, "callbackUrl": a.callback})
        state.update(project=a.project, signupUrlName=r["name"])
        save_state(state)
        print("Open this link, sign in with the Google account that will own the enterprise,")
        print("and finish the steps. You land on the callback page; copy the value of")
        print("`enterpriseToken=` from its address bar.\n")
        print(r["url"])

    elif a.cmd == "create-enterprise":
        r = call(s, "POST", f"{API}/enterprises", json={"enterpriseDisplayName": a.name}, params={
            "projectId": need(state, "project", "signup-url"),
            "signupUrlName": need(state, "signupUrlName", "signup-url"),
            "enterpriseToken": a.token,
        })
        state["enterprise"] = r["name"]
        save_state(state)
        print(f"Enterprise created: {r['name']}")
        print(f"Organization ID for Play Console (managed Google Play): {r['name'].split('/')[-1]}")

    elif a.cmd == "policy":
        ent = need(state, "enterprise", "create-enterprise")
        r = call(s, "PATCH", f"{API}/{ent}/policies/{POLICY_ID}", json=kiosk_policy(a.package))
        print(f"Policy saved: {r['name']} (version {r.get('version')}) for {a.package}")

    elif a.cmd == "enroll":
        ent = need(state, "enterprise", "create-enterprise")
        r = call(s, "POST", f"{API}/{ent}/enrollmentTokens", json={
            "policyName": f"{ent}/policies/{POLICY_ID}",
            "duration": f"{a.days * 86400}s",
            "allowPersonalUsage": "PERSONAL_USAGE_DISALLOWED",
        })
        payload = json.loads(r["qrCode"])
        if a.wifi_ssid:
            payload["android.app.extra.PROVISIONING_WIFI_SSID"] = a.wifi_ssid
            if a.wifi_password:
                payload["android.app.extra.PROVISIONING_WIFI_PASSWORD"] = a.wifi_password
                payload["android.app.extra.PROVISIONING_WIFI_SECURITY_TYPE"] = "WPA"
        OUT.mkdir(exist_ok=True)
        html = OUT / "enroll-qr.html"
        html.write_text(qr_html(json.dumps(payload, separators=(",", ":"))))
        print(f"Enrollment QR (valid {a.days} days, expires {r.get('expirationTimestamp')}):")
        print(html)

    elif a.cmd == "devices":
        ent = need(state, "enterprise", "create-enterprise")
        r = call(s, "GET", f"{API}/{ent}/devices")
        devices = r.get("devices", [])
        if not devices:
            print("No devices enrolled yet.")
        for d in devices:
            hw = d.get("hardwareInfo", {})
            print(f"{d['name']}  {hw.get('brand', '')} {hw.get('model', '')}  "
                  f"state={d.get('state')}  last seen={d.get('lastStatusReportTime', '-')}")

    elif a.cmd == "wipe":
        call(s, "DELETE", f"{API}/{a.device}")
        print(f"Wipe sent to {a.device}. The tablet factory-resets the next time it is online.")


if __name__ == "__main__":
    main()
