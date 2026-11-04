# Tapper

Android app that places draggable virtual buttons on screen and taps them at fixed, editable intervals (like Zap Tap).

Built on an `AccessibilityService`: overlays use `TYPE_ACCESSIBILITY_OVERLAY` and taps are injected with `dispatchGesture` (API 24+). The service reads no screen content.

## Build
Push to GitHub and download the `tapper-debug-apk` artifact from the *Build APK* workflow, or locally with the Android SDK + Gradle 8.7: `gradle :app:assembleDebug`.

## Use
1. Install, open Tapper, enable it in Settings > Accessibility.
2. Floating bar: `+` add button, `▶/■` start/stop, `✕` hide, `⠿` drag the bar.
3. While stopped: drag buttons into place; tap one to edit its interval (ms, min 20) or delete it.
4. While running, buttons are click-through and each taps its own centre at its interval.
Targets persist across restarts.
