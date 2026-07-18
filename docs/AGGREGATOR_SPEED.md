# OpenWave as a multi-app hub — speed first

## Product goal

One app that **replaces opening OuterTune + Spotube + SoundCloud + … separately**:

1. **One search bar** → query every free source in parallel  
2. **One tap** → stream without login / without ads UI  
3. **Always-on playback** → screen off, other apps, lock-screen controls  

Not: re-implement Spotify/Apple DRM.  
Yes: unify the **same free backends** those FOSS clients already use (YTM InnerTube, SoundCloud, metadata→match).

---

## Latency budget (targets)

| Stage | Target | Technique |
|-------|--------|-----------|
| Keystroke → first result paint | **≤ 400 ms** | Progressive `Flow<SearchBatch>`, 280 ms debounce |
| Full multi-source search | **≤ 2.5 s** | Per-source timeout; late sources dropped |
| Tap → first audio | **≤ 1.5 s** cold / **≤ 300 ms** cache hit | Stream cache + resolve race + short Exo buffer |
| Background / screen off | Instant continue | Media3 FGS + MediaSession |

---

## Architecture

```
┌─ Search bar ─────────────────────────────────────────┐
│  debounce 280ms                                       │
│       ↓                                               │
│  FastMusicCatalog.searchProgressive()                 │
│       ├─ async YTM      (timeout 2.5s) ──┐            │
│       ├─ async SoundCloud …………… ────────┤ merge      │
│       ├─ async Spotify-meta (optional) ──┤ + dedupe   │
│       └─ async Local / Demo ─────────────┘            │
│       emit SearchBatch after EACH source finishes     │
└───────────────────────────────────────────────────────┘
                         │
              user taps UnifiedTrack
                         ↓
┌─ resolveStreamFast() ────────────────────────────────┐
│  1. StreamUrlCache hit? → play                       │
│  2. Race getStream() on primary + alternates         │
│  3. First success wins; cancel siblings              │
│  4. Put cache (TTL ~25 min / remote expiry)          │
└───────────────────────────────────────────────────────┘
                         ↓
┌─ PlaybackService (Media3) ───────────────────────────┐
│  Low bufferForPlayback (750ms) → hear sound sooner   │
│  FGS mediaPlayback + notification                     │
│  Audio focus, noisy handling, wake lock network       │
│  Survives task removed while playing                  │
└───────────────────────────────────────────────────────┘
```

### Prefetch

When a search row is composed (visible), call `prefetchStream(track)` so the next tap is often a cache hit.

### Dedupe

Same song from YTM + SC → one `UnifiedTrack` with `alternates`. Play races free alternates if primary fails.

---

## Convenience (“mở lên là nghe”)

| Feature | How |
|---------|-----|
| Resume last track | Persist queue + position in DataStore/Room (Phase 4) |
| Cold start service | Bind MediaController in Application / first Activity |
| Home “Continue” | Snapshot last `PlayerSnapshot` |
| Instant demo | `DemoSourceClient` until extractors ready |

---

## Multi-situation playback

| Situation | Mechanism |
|-----------|-----------|
| Screen off | `WAKE_MODE_NETWORK` + media FGS |
| Another app in front | MediaSession continues; audio focus ducking |
| Headphone unplug | `setHandleAudioBecomingNoisy(true)` |
| Lock screen / BT / headset buttons | Media3 MediaSession |
| Swipe app away | `onTaskRemoved` keeps service if still playing |
| Android 14+ | `foregroundServiceType="mediaPlayback"` |

Optional later: Android Auto (MediaLibraryService), widgets.

---

## Source matrix for the hub

| Source | Search | Stream no-login | Role in hub |
|--------|--------|-----------------|-------------|
| YouTube Music | ✓ InnerTube | ✓ | **Primary** (catalog breadth) |
| SoundCloud | ✓ public API | ✓ | Alt / indie |
| Spotify | metadata / public | ✗ DRM | Discovery UI only → match free |
| Apple Music | metadata only | ✗ FairPlay | Same as Spotify or skip |
| Local files | MediaStore | ✓ | Offline always works |

---

## Implementation map (repo)

| File | Role |
|------|------|
| `core/domain/AggregatorModels.kt` | `UnifiedTrack`, `SearchBatch`, `FastMusicCatalog` |
| `data/repository/FastMusicCatalogImpl.kt` | Parallel search, race stream, rank/dedupe |
| `data/cache/StreamUrlCache.kt` | RAM cache for stream URLs |
| `core/player/PlaybackService.kt` | Fast load control + FGS continuity |
| `presentation/ViewModels.kt` | Progressive search + play/prefetch |

---

## Next engineering steps (ordered by user-perceived speed)

1. **YTM InnerTube search + stream** (biggest win for “real music”)  
2. SoundCloud client  
3. Room: recent searches + last session restore  
4. Prefetch next queue item while current plays  
5. Optional Spotify metadata plugin → match YTM  
6. OkHttp connection pool + HTTP/2 already default  

---

## Honest constraint

“Gộp mọi app stream” trong FOSS = gộp **các nguồn free/anonymous** mà các app đó dùng, **không** gộp stream DRM Spotify/Apple.  
Với YTM + SoundCloud + local (+ metadata match), trải nghiệm hub vẫn đạt: **một ô tìm, một chạm, nghe nền, không ads UI, không bắt login.**
