# RuiAlert

Android disaster-information app: EEW （緊急地震速報）, special weather warnings,
linear precipitation band alerts, tsunami alerts for your current coastal area,
a prefecture-coloured earthquake map, and an embedded realtime earthquake viewer.

## Features

- EEW monitoring (Wolfx) for the prefecture you are currently in (GPS-based)
- Special warnings （特別警報） and linear precipitation band （線状降水帯） alerts (JMA)
- Tsunami alerts （大津波警報 / 津波警報 / 津波注意報） for your prefecture's coasts (P2PQuake)
- Chime sounds via notification channels: pinpon for EEW forecasts,
  alert chime on the alarm stream for critical notifications
- Notification history with per-entry delete and lock
- Embedded realtime earthquake viewer (by kotoho7) in a WebView tab;
  the viewer URL can be changed in the app (long-press the viewer tab)

## Updates

The app checks `version.json` in this repository on startup.
When a newer `versionCode` is published there, it downloads the APK from
GitHub Releases and asks to install it.

## Build notes

Built with a manual Kotlin/aapt2 pipeline (no Gradle) — see `build-manual.sh`.
Binary assets not tracked here (kept locally): `manual-deps/` (AAR/JAR
dependencies), `res/raw/*.mp3` chimes, launcher PNGs, the debug keystore.
