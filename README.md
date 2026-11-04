# Tapper

Android app that places draggable virtual buttons on screen and taps them at fixed, editable intervals (like Zap Tap).

Built on an `AccessibilityService`: overlays use `TYPE_ACCESSIBILITY_OVERLAY` and taps are injected with `dispatchGesture` (API 24+). The service reads no screen content.

## Download
Every push builds the app and updates the **latest** release: `https://github.com/fblah/tapper/releases/tag/latest` (`tapper-debug.apk`).

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
- Quick Settings tile to start/stop (add it from the tile editor).

Settings and profiles are on the main screen.
