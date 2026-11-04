---
name: whitehat-device
description: Set up whitehat traffic inspection for Facebook, Instagram, Messenger or Threads on the researcher's own Android device or emulator, or iOS simulator, so the app's traffic shows up in their intercepting proxy (Burp or similar). Covers the checks before, the proxy and CA steps around, and the verification after the whitehat_configure tool. Use when the user wants to proxy, intercept or inspect a Meta mobile app's traffic, or turn that off again. Requires the whitehat-device MCP server and idb or adb.
---

# Whitehat Device

Meta's apps pin their certificates, so a proxy sees nothing until the app's whitehat settings
are on. `whitehat_configure` turns them on or off on a device the researcher controls and
restarts the app. Getting traffic into the proxy takes three things, and the tool does only the
middle one fully:

1. **Route** the device's traffic through the proxy. Never done by the tool.
2. **Enable** the app's whitehat settings. `whitehat_configure`.
3. **Trust** the proxy CA. Done by the tool on iOS when given `ca_certificate_path`; on Android
   the researcher installs it.

Work only on the researcher's own device and test accounts. Ask before changing anything that
reaches beyond the device, such as the host machine's proxy settings.

## What works where

| Target | Requirement | Notes |
|---|---|---|
| Android emulator, store build | A **Google APIs** system image (not **Google Play**) and `adb root` | Install the app with `adb install-multiple` from its APK splits. |
| Android rooted phone | `su` that lets the shell become root (Magisk, KernelSU…) | The first run triggers a root prompt for **Shell** on the phone; the call waits until it is granted or refused. |
| Android, debuggable build | Nothing else | Reached through `run-as`. |
| iOS simulator | A **simulator build** of the app and `idb` | App Store builds do not run on simulators and simulator builds are not publicly distributed, so this usually means one provided for a private engagement. |
| Jailbroken iPhone | Not supported by the tool | See the manual outline in the package README. |

The tool chooses `adb root`, then `su`, then `run-as` on its own. Do not run `adb root` on the
user's behalf on a physical phone; it fails on production builds and that is expected.

## Before calling the tool

**Android**

- `adb devices` lists exactly one device, or pass `device_id` (the serial).
- `adb shell pm path <package>` shows the app is installed. Packages: `com.facebook.katana`,
  `com.facebook.orca`, `com.instagram.android`, `com.instagram.barcelona`.
- The app has been launched at least once. Facebook and Messenger refuse with
  `has no prefs_db` otherwise.

**iOS**

- `idb list-targets` shows a booted simulator. Pass its UDID as `device_id` when there is more
  than one.
- `idb list-apps` shows the app: `com.facebook.Facebook`, `com.facebook.Messenger`,
  `com.burbn.instagram` or `com.burbn.barcelona`.
- The app is launched and logged in.
- Instagram and Threads need `account_id`, the logged-in Instagram user id. Read it from the
  session directory names, `<id>.data`:

  ```
  idb file ls --group group.com.burbn.instagram/mobileconfig                       # Instagram
  idb file ls --group group.com.burbn.instagram/Documents/mobileconfig             # Instagram, older layout
  idb file ls --group group.com.burbn.instagram/barcelona/Documents/mobileconfig   # Threads
  ```

## Routing and the CA

**Android**

- Route: `adb shell settings put global http_proxy <host>:<port>`. From an emulator the host
  machine is `10.0.2.2`; or run `adb reverse tcp:8080 tcp:8080` and use `127.0.0.1:8080`.
  Clear it afterwards with `adb shell settings put global http_proxy :0`.
- CA: the app trusts the **user** certificate store once the tool has run. Android 11 and later
  only install CAs through Settings, so push the DER export and ask the user to install it:
  `adb push burp.der /sdcard/Download/`, then Settings → Security → Encryption & credentials →
  Install a certificate → CA certificate. Menu names vary by vendor.

**iOS simulator**

- Route: the simulator uses the host Mac's network settings, so the proxy is set on the Mac
  (System Settings → Network → the active interface → Proxies, HTTP and HTTPS). This affects the
  whole machine; ask first.
- CA: pass `ca_certificate_path`, the proxy CA as PEM or DER (in Burp: Proxy settings → Import /
  export CA certificate → Certificate in DER format). The tool writes it to the app's
  `Library/Application Support/ProxyCACert.der`, which Facebook and Threads require, and adds it
  to the simulator keychain with `xcrun simctl keychain`, which WebViews and the other apps use.
  Both happen while the app is stopped, because the app reads the root once at launch.

## Calling the tool

```json
{"platform": "android", "app": "facebook", "enabled": true}
{"platform": "ios", "app": "threads", "enabled": true, "account_id": "1234567890", "ca_certificate_path": "/Users/me/burp.der"}
{"platform": "ios", "app": "facebook", "enabled": false}
```

- `duration_seconds` limits inspection on Android and on Threads iOS. Default 24 hours, at most
  7 days. When it lapses, run the tool again.
- `app_id` targets a differently named build, for example an internal or renamed package.
- Once the tool reaches the device, the app restarts even if a step fails. Argument errors such as a missing `account_id` or a bad `ca_certificate_path` are reported before the app is touched.

## Checking it worked

Use the app (open the feed, a profile) and look in the proxy history for the app's API hosts,
such as `graph.facebook.com` or `i.instagram.com`.

| You see | Likely cause |
|---|---|
| Nothing from the app | Routing is not set, or the emulator cannot reach the proxy address. |
| TLS handshake failures from the app in the proxy's event log | The CA is not trusted: Android user store missing, or on iOS `ca_certificate_path` not passed. |
| WebView pages fail but API calls work | iOS simulator keychain missing the CA, or Android user store missing it. |
| It worked, then stopped | `duration_seconds` lapsed. Run the tool again. |

## Turning it off

Call the tool with `enabled: false`. It removes only the overrides it added and resets the
pinning settings. It does not remove the proxy CA (it is ignored while inspection is off) or the
proxy routing. Clear routing as above. On iOS, `xcrun simctl keychain <udid> reset` removes
every root added to that simulator.

## Errors

| Message contains | Do |
|---|---|
| `requires account_id` | Find the id as above and call again. |
| `No MobileConfig session directory for account` | The app is not logged in to that account on this simulator. Log in, then call again. |
| `has no prefs_db; launch it once` | Launch the app once, then call again. |
| `not debuggable … run adb root first, allow root for the shell` | No root access. On an emulator, `adb root` (Google APIs image). On a rooted phone, grant Shell root in the su manager. Otherwise a debuggable build is needed. |
| `is not a CA certificate` / `Cannot read a PEM or DER certificate` | The path is not the proxy's CA export. Re-export it. |
| `keychain was left alone because idb targets a remote companion` | Run the `xcrun simctl keychain` command on the Mac running the simulator. |
| `Settings were applied, but relaunching the app failed` | The change is in place. Launch the app by hand. |
| `MobileConfig cleanup did not complete` | Inspection is already off. Calling disable again retries the cleanup. |
| `adbd cannot run as root in production builds` | Expected on a physical phone. Use `su`, as above. |
