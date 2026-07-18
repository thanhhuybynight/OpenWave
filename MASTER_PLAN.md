# OpenWave — Master Plan

Open-source, Kotlin-first multi-source music client for Android.  
**No Google Play Services. No mandatory login for free sources. Material You 3 + Dynamic Color.**

> **Legal boundary (non-negotiable for this repo)**  
> Circumventing DRM (Spotify Widevine/encrypted blobs, Apple FairPlay) or scraping paid catalogs to stream without a license is copyright infringement and often violates anti-circumvention law (e.g. DMCA §1201).  
> OpenWave **does not** implement SpotX-style premium unlocks, FairPlay cracks, or private Spotify stream decryption.  
> Viable free paths: **YouTube Music (InnerTube / NewPipeExtractor)**, **SoundCloud public streams**, **local files**, and **metadata-only** adapters that redirect play to a free source.  
> Spotify / Apple Music full anonymous streaming is **out of scope** unless you add an official, licensed integration (user OAuth + official SDK).

---

## Product pivot: multi-app hub (speed first)

OpenWave is not “another YTM client only”. It is a **single place** to search every free source at once, tap once, and keep playing with the screen off.

Priorities (in order):

1. **Fast** — progressive search, stream URL cache, resolve race, short Exo buffer  
2. **Convenient** — open → search/continue → play; no login for free sources  
3. **Everywhere** — Media3 foreground media session (background / lock screen / other apps)

See [docs/AGGREGATOR_SPEED.md](./docs/AGGREGATOR_SPEED.md).

---

## 1. Tech stack

| Layer | Choice | Why |
|--------|--------|-----|
| Language | Kotlin 2.0 | 100% Kotlin constraint |
| UI | Jetpack Compose + Material 3 | Material You, Dynamic Color |
| Architecture | MVI-ish MVVM (StateFlow) + clean domain | Testable, Compose-friendly |
| DI | Hilt | Standard Android DI |
| Player | Media3 ExoPlayer + MediaSessionService | Official, GMS-free, notification/Bluetooth OK |
| Net | OkHttp + Retrofit/Moshi | Extractor HTTP clients |
| Images | Coil | Compose-first |
| Local | Room + DataStore | Library, history, settings |
| Extractors | NewPipeExtractor (YT/YTM), custom SC client | FOSS patterns; no GMS |

### Per-source strategy (honest)

| Source | Search / metadata | Anonymous stream? | Approach |
|--------|-------------------|-------------------|----------|
| **YouTube Music** | InnerTube / NewPipeExtractor | Yes (fragile) | Player endpoint → adaptive **audio-only** format; rotate client name / visitor data when Google changes protocol. Same family as InnerTune, OuterTune, RiMusic. |
| **SoundCloud** | Public JSON + rotated `client_id` | Yes (ToS gray) | Resolve progressive or HLS URL; Media3 HLS module for m3u8. |
| **Spotify** | Optional Web API (user OAuth) or third-party metadata | **No free DRM stream** | **Metadata + match** to YTM/SoundCloud (Spotube pattern), or official SDK for licensed users. Not reverse-engineered decryption. |
| **Apple Music** | Official MusicKit (requires auth) | **No** | FairPlay is DRM. Metadata-only or official Apple path only. |

### Architecture sketch

```
UI (Compose) → ViewModel (StateFlow) → MusicCatalog
                                          ├─ YouTubeMusicSourceClient
                                          ├─ SoundCloudSourceClient
                                          ├─ DemoSourceClient
                                          └─ (future) SpotifyMetadataClient → match → free stream
PlayerController → MediaController → PlaybackService (ExoPlayer + MediaSession)
```

### Android player policy checklist

- `foregroundServiceType="mediaPlayback"` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK`
- Notification channel + Media3 session notification
- Audio focus + `setHandleAudioBecomingNoisy(true)`
- User-initiated playback only
- No background scrape loops that look like botnet traffic

---

## 2. Master plan (phases)

### Phase 1 — Core architecture & player (Media3) ✅ scaffolded

- [x] Gradle modules / app shell (single `:app` for MVP)
- [x] Domain models + `MusicSourceClient` / `MusicCatalog`
- [x] `PlaybackService` (MediaSessionService + ExoPlayer)
- [x] `PlayerController` + Hilt wiring
- [x] Demo track path end-to-end
- [ ] Queue, shuffle, repeat, MediaItem playlist
- [ ] Android Auto / Bluetooth metadata polish
- [ ] Unit tests for catalog policy gates

### Phase 2 — Data sources (extractors)

1. **YouTube Music**
   - Add NewPipeExtractor (or Kotlin InnerTube port)
   - Search → browse → `getStream` (audio-only)
   - Cache stream URLs with expiry; refresh on 403
2. **SoundCloud**
   - `client_id` discovery / cache
   - Search + progressive/HLS resolve
3. **Spotify metadata (optional)**
   - Search via public metadata mirror **or** official Web API with user token
   - Fuzzy match title+artist against YTM/SC; never decrypt Spotify CDN
4. **Apple Music**
   - Keep **blocked** for anonymous stream; optional MusicKit later with user auth
5. **Resilience**
   - Per-source circuit breaker, timeout, partial search results
   - User-agent / client version config (update without app store if self-hosted config)

### Phase 3 — UI/UX Material You 3 + taste

- [x] Dynamic Color theme + teal fallback (not AI purple)
- [x] Now Playing + Mini Player (rounded artwork, spring play/pause)
- [x] Home / Search / Library nav shell
- [ ] Shared-element artwork expand (mini → full)
- [ ] Palette-from-artwork optional accent (coil + palette, still respect Dynamic Color)
- [ ] Empty / error / offline skeletons
- [ ] Lyrics pane (if free source provides)
- [ ] A11y: large targets, TalkBack labels, contrast on Dynamic Color edge cases

**Taste dials (product UI):** variance 5 · motion 5 · density 4  
**Shape lock:** cards 28dp, chips 12dp, transport FAB circle  
**Motion:** spring play/pause only; honor reduced motion via system animator duration scale

### Phase 4 — State & optimization

- Queue persistence (Room)
- Offline cache of progressive streams (user opt-in; respect source ToS)
- Battery: pause on long idle, constrain extractors on metered network
- ProGuard rules for extractors
- F-Droid friendly: no proprietary blobs, reproducible builds
- CI: unit + Compose screenshot smoke

---

## 3. Repo layout

```
OpenWave/
├── MASTER_PLAN.md
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
└── app/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        └── java/com/openwave/music/
            ├── OpenWaveApp.kt
            ├── MainActivity.kt
            ├── core/
            │   ├── domain/          # models + source contracts
            │   ├── player/          # Media3 service + controller
            │   └── di/              # Hilt
            ├── data/
            │   ├── repository/      # CompositeMusicCatalog
            │   └── source/          # YTM / SC / Demo clients
            ├── presentation/        # ViewModels
            └── ui/
                ├── theme/           # Material You + Dynamic Color
                ├── player/          # NowPlaying + MiniPlayer
                ├── home/
                ├── search/
                └── navigation/
```

---

## 4. Build

```bash
cd OpenWave
# Requires Android SDK + JDK 17
./gradlew :app:assembleDebug
```

Gradle wrapper JAR is not vendored in this scaffold; generate with a local Android Studio / `gradle wrapper` once SDK is available.

---

## 5. What this scaffold intentionally does **not** include

- SpotX / premium unlock patches  
- FairPlay or Widevine license bypass  
- Hardcoded private API secrets for Spotify/Apple CDN  
- Google Play Services / Cast / Play Integrity  

Those are either illegal, ToS-hostile, or incompatible with a clean FOSS + no-GMS product. The architecture leaves **honest** extension points for free extractors and optional licensed SDKs.

## SimpMusic-parity

See [docs/FEATURES_SIMPMUSIC_PARITY.md](./docs/FEATURES_SIMPMUSIC_PARITY.md).

Scaffold under `features/`: sleep timer (done), local scrobble, offline queue, quality selector (256k requires optional Premium session), browse shelves stub, SponsorBlock + RYD HTTP clients, crossfade settings, Android Auto MediaLibraryService, stubs for Canvas/Video/AI/artist notify.
