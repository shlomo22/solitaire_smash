# Solitaire Assistant

Personal Android overlay helper for Solitaire Smash (draw-3 Klondike).

## What it does
1. Captures the screen with user-approved MediaProjection in a foreground service
2. Detects the board layout with OpenCV template matching
3. Computes a legal / scored best move under Klondike draw-3 rules
4. Draws a touch-through arrow overlay so you can still play normally

## Requirements
- Android Studio with Android SDK
- Physical Android device (API 26+) recommended
- Solitaire Smash installed on the device
- Screenshots of your device for recognition tuning (place under `app/src/test/resources/screenshots/`)
- Rank/suit templates under `app/src/main/assets/templates/` (see README there)

## Build
Use JDK 17 (a portable Temurin copy can live under `.jdk/`):

```bash
set JAVA_HOME=.\.jdk\jdk-17.0.20+8
gradlew.bat :app:assembleDebug
gradlew.bat :app:testDebugUnitTest
```

Open the project in Android Studio and set the Gradle JDK to 17 if Studio defaults to a newer JBR.

## Screenshots & templates
Device screenshots from Solitaire Smash live in `app/src/test/resources/screenshots/` (`board_a`…`board_d`).

Geometry is calibrated for Smash layout: **foundations left → waste → stock right**, tableau below.

Templates under `app/src/main/assets/templates/` are bundled fallbacks. For best accuracy, use **Template Lab** in the app to capture rank-corner and suit-badge crops from your device (stored under `files/templates/`). See `app/src/main/assets/templates/README.md`.

Match confidence in settings now controls recognizer thresholds directly.

## Device test gates
1. **Capture** — Start the app, grant overlay + projection, confirm capture notification
2. **Template Lab** — Save rank/suit samples from live gameplay (2+ per confused rank, all suits)
3. **Live overlay** — Verify arrow endpoints; tap Cancel on wrong hints
4. **Logs** — `adb exec-out run-as com.personal.solitaireassistant cat files/logs/analysis.log`

## Permissions
- Display over other apps (`SYSTEM_ALERT_WINDOW`)
- MediaProjection consent each session
- Notifications (Android 13+)
