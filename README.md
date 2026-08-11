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

Templates under `app/src/main/assets/templates/` were cropped from those shots. Replace them with sharper device captures for better rank/suit matching. OpenCV template matching runs on-device; JVM unit tests use color heuristics (teal backs / white faces) because OpenCV natives are unavailable under Robolectric.

## Device test gates
1. **Capture** — Start the app, grant overlay + projection, enable debug frames, confirm `cache/frames/latest.png`
2. **Detection** — Compare live diagnostics against `board_*.png` fixtures; retune `BoardGeometryProfile` if your phone resolution differs
3. **Live overlay** — Start over Solitaire Smash and verify arrow endpoints / touch-through

## Permissions
- Display over other apps (`SYSTEM_ALERT_WINDOW`)
- MediaProjection consent each session
- Notifications (Android 13+)
