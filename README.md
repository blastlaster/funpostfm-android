# FunpostFM for Android

The FunpostFM radio in your pocket: plays in the background and from the lock
screen, shows what's on and who's live, and pings you when someone goes live.

Download the APK from [Releases](../../releases/latest).

## Build

    ./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk

Needs JDK 17 and the Android SDK (platform 35). Releases are built by
`.github/workflows/release.yml` when a `v*` tag is pushed, signed with the
keystore held in the repository's secrets.

## How it works

- `Station.kt` reads the station the same way the On Air desktop app and the
  FunpostFM browser add-on do: the Neocities pointer (`onair/station.json`), the
  studio PC's `/api/status`, and the yesstreaming panel's history when the
  studio is off.
- `PlaybackService.kt` is a Media3 `MediaSessionService` (ExoPlayer) playing
  the Icecast stream, so Android gives it lock-screen, notification, headset
  and car controls.
- `LiveCheckWorker.kt` checks every ~15 minutes while the app is closed (the
  shortest interval Android allows) for the "we're live" alert.
- `Updates.kt` looks at this repository's latest release every few hours while
  the app is open (or from Menu → Updates). A newer version downloads with
  Android's DownloadManager and opens Android's installer; it installs over the
  old one because releases share the same signing key.
