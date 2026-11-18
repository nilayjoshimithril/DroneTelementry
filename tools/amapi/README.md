# Kiosk setup with the Android Management API

Production way to put tablets into single-app kiosk mode. Google's **Android Device Policy**
becomes device owner, installs the app from **managed Google Play** and pins it. Nothing is
sideloaded, so Play Protect never blocks it. Updates come from Play, and tablets can be managed
and wiped remotely.

The setup steps are done once. After that, each tablet only needs:
factory reset → tap the welcome screen 6× → scan the QR code.

---

## A. One-time: Google Cloud (≈10 min)

1. Go to <https://console.cloud.google.com> and create a project, e.g. `cga-kiosk`.
   Note the **Project ID**.
2. Open **APIs & Services → Library**, search **Android Management API** and click **Enable**.
3. Open **IAM & Admin → Service Accounts → Create service account**.
   - Name it `amapi`. You can skip the roles step.
   - Open the service account → **Keys → Add key → Create new key → JSON**.
   - Save the downloaded file as `tools/amapi/service-account.json`. It is git-ignored;
     never commit it.

## B. One-time: create your enterprise (≈5 min)

```bash
tools/amapi/run.sh signup-url    # project ID is read from service-account.json
```

1. Open the printed link and sign in with the Google account that should own the
   enterprise. This should be a work account you'll keep. Complete the steps.
2. You land on google.com. Copy the `enterpriseToken=...` value from the address bar.

```bash
tools/amapi/run.sh create-enterprise --token <ENTERPRISE_TOKEN>
```

The command prints your **Organization ID** (e.g. `LC0abc123`). You need it in step C.

## C. One-time: make the app available in managed Google Play

1. Open **Play Console** and select the app.
2. Go to **Setup → Advanced settings → Managed Google Play**.
3. Turn it on and **add your organization**: Organization ID from step B, plus a name.
4. Publish a **production** release.
   - If the app targets only your organization, it stays **private**: only your tablets can
     see it.
   - A private-only app can't be made public later.

   The first private release can take some time to appear in managed Play.

## D. One-time: kiosk policy

```bash
tools/amapi/run.sh policy
```

The policy:
- Installs the app as `KIOSK`.
- Hides the status bar and notifications, and disables Home and Recents.
- Blocks Settings and developer options.
- Keeps the power menu and the lock screen.
- Updates the app automatically.
- Installs Android system updates between 02:00 and 04:00.

Re-run the command after changing `kiosk_policy()` in `amapi.py`. Enrolled tablets pick up
the change automatically.

## E. Each time you need a QR code

```bash
tools/amapi/run.sh enroll --wifi-ssid "Office WiFi" --wifi-password "secret"
open tools/amapi/out/enroll-qr.html
```

- The code is valid for 30 days (`--days` to change) and works for any number of tablets.
- The Wi-Fi arguments are optional. Without them, the tablet asks for Wi-Fi during setup.

## F. On each tablet

1. If the tablet has been used, remove the Google and Samsung accounts. Then go to
   **Settings → General management → Reset → Factory data reset**.
2. On the first welcome screen, **tap the same spot 6 times**.
3. Connect to Wi-Fi if asked, then **scan the QR code**.
4. Setup installs Android Device Policy, enrolls the tablet, installs the app from Play and
   locks into it.

## Managing tablets

```bash
tools/amapi/run.sh devices                              # list enrolled tablets
tools/amapi/run.sh wipe --device enterprises/.../devices/...   # remote factory reset
```

App updates: publish a new release in Play Console. Tablets update themselves.

## Notes

- New Cloud projects can manage up to 500 devices. Above that, request a higher quota in the
  Android Management API console.
- The app's own device-owner path (`adb shell dpm set-device-owner …`) still works for test
  tablets. Don't mix the two methods on one tablet.
