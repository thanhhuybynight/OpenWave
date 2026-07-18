# OpenWave

Ad-free multi-source Android music hub (Kotlin · Jetpack Compose · Material You 3).  
No Google Play Services. Guest listening for free sources; optional YTM Premium extras later.

**Repo:** https://github.com/thanhhuybynight/OpenWave

## Debug preview APK (GitHub Actions)

Every push to `main` builds a **debug APK** and uploads it as a workflow artifact.

1. Open **Actions** → [Android Debug APK](https://github.com/thanhhuybynight/OpenWave/actions/workflows/android-debug.yml)
2. Open the latest successful run
3. Download artifact **`OpenWave-debug`**
4. Install: `adb install -r app-debug.apk`  
   Package id: `com.openwave.music.debug`

Or: **Actions → Android Debug APK → Run workflow**.

### What works (Phase 2–4)

| Area | Status |
|------|--------|
| YouTube search + stream | NewPipeExtractor (+ demo fallback) |
| SoundCloud search + stream | Public API + `client_id` discovery |
| Home / Charts / Moods | Live kiosk + mood search shelves |
| Multi-source search | Progressive fan-out + dedupe |
| Offline download | WorkManager → `filesDir/tracks` |
| Play offline first | Local file before network resolve |
| Queue / shuffle / repeat | Media3 multi-item |
| SponsorBlock | Auto-skip segments |
| Return YouTube Dislike | Shown on Now Playing |
| Scrobble + stats | Room persistence |
| Sleep timer / quality UI | Home chips |
| Background / screen off | Media3 FGS mediaPlayback |
| Android Auto | MediaLibrary tree shell |

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

**GPL-3.0** recommended: NewPipeExtractor is GPL. Do not ship proprietary forks without complying.
