# Tapper

Android app that places draggable virtual buttons on screen and taps them at fixed, editable intervals (like Zap Tap).

Built on an `AccessibilityService`: overlays use `TYPE_ACCESSIBILITY_OVERLAY` and taps are injected with `dispatchGesture` (API 24+). The service reads no screen content.

## Download
Every push builds the app and updates the **latest** release: `https://github.com/fblah/tapper/releases/tag/latest` (`tapper-debug.apk`).

Tagged versions get their own permanent release: `git tag v1.0.0 && git push origin v1.0.0`.

## Updates
The app checks this repo's releases on launch (and via the *Updates* card) and can download and install a newer build. Android asks you to allow Tapper to install apps the first time. Builds are signed in CI with a private key kept in GitHub secrets, so they install over each other.

## Signing setup (one time)
Create a key and store it as repository secrets (Settings > Secrets and variables > Actions):

```bash
keytool -genkeypair -v -keystore tapper-release.jks -alias tapper -keyalg RSA -keysize 2048 -validity 36500
base64 -w0 tapper-release.jks   # macOS: base64 -i tapper-release.jks
```

| Secret | Value |
| --- | --- |
| `KEYSTORE_BASE64` | the base64 output above |
| `KEYSTORE_PASSWORD` | the keystore password you chose |
| `KEY_ALIAS` | `tapper` |
| `KEY_PASSWORD` | the key password you chose |

Keep a backup of `tapper-release.jks`: if it is lost, installed copies can't be updated (uninstall and reinstall). Without the secrets, CI still builds an artifact but does not publish a release.

## Build
Locally with the Android SDK + Gradle 8.7: `gradle :app:assembleDebug`.

## Use
1. Install, open Tapper, enable it in Settings > Accessibility.
2. Floating bar: `+` add tap button, `⇄` add swipe button (blue start + orange end marker), `▶/■` start/stop, `✕` hide, `⠿` drag the bar.
3. While stopped: drag buttons into place; tap one to edit interval (ms, min 20), number of taps (0 = unlimited), swipe duration, or delete it.
4. While running, buttons are click-through and tap their own centre.

## Features
- Per-button interval and repeat count; global stop timer (minutes).
- Swipe buttons (start, end, duration).
- Sequence mode: buttons fire one after another in number order (interval = wait after that button, repeat = taps in a row), with a cycle count.
- Random jitter on interval (+/- %) and tap position (+/- px).
- Named profiles, plus JSON export/import.
- Visual feedback: tap buttons pulse and draw a ring (or swipe trail) each time they fire.
- Record mode: records your taps/swipes (the stop tap is not recorded) and replays them; long-press replay to set play time (0 = unlimited) and pause between repeats. Recording again replaces it.
- Collapsible floating bar.
- Quick Settings tile to start/stop (add it from the tile editor).

Settings and profiles are on the main screen.
