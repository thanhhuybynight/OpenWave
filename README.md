# OpenWave

Ad-free multi-source Android music hub (Kotlin · Jetpack Compose · Material You 3).  
No Google Play Services. Guest listening for free sources; optional YTM Premium extras later.

## Debug preview APK (GitHub Actions)

Every push to `main` builds a **debug APK** and uploads it as a workflow artifact.

1. Open the repo on GitHub → **Actions** → **Android Debug APK**
2. Open the latest successful run
3. Download artifact **`OpenWave-debug`**
4. Install on device: `adb install -r app-debug.apk`  
   Package id: `com.openwave.music.debug`

Or trigger manually: **Actions → Android Debug APK → Run workflow**.

### What works in the debug build (demo data)

- Home shelves: Quick picks, Charts, Podcasts, Moods
- Search across demo tracks (YTM / SoundCloud / Local labels)
- Play sample audio (public test streams)
- Mini player + full Now Playing (Material You Dynamic Color)
- Sleep timer chips, quality prefs, crossfade flag
- Offline queue enqueue (in-memory)
- Media3 foreground playback (screen off / other apps)

Real YouTube Music / SoundCloud extractors are still Phase 2; demo catalog fills the UI.

## Build locally

```bash
# JDK 17 + Android SDK 35
cp local.properties.example local.properties
# edit sdk.dir=...

chmod +x ./gradlew
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Docs

- [MASTER_PLAN.md](./MASTER_PLAN.md)
- [docs/AGGREGATOR_SPEED.md](./docs/AGGREGATOR_SPEED.md)
- [docs/FEATURES_SIMPMUSIC_PARITY.md](./docs/FEATURES_SIMPMUSIC_PARITY.md)

## License

GPL-3.0 recommended once NewPipeExtractor is linked; currently project scaffold.
