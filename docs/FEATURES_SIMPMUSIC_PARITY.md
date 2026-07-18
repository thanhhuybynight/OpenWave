# SimpMusic-parity feature matrix (OpenWave)

Goal: multi-source hub with **optional** YTM account extras (Premium quality, sync), without making login mandatory for core listening.

## Auth model

| Mode | Login | What you get |
|------|-------|----------------|
| **Guest (default)** | None | Search free sources, stream YTM/SC, offline cache, sleep timer, local scrobble, SponsorBlock, RYD |
| **YTM linked (optional)** | Cookie / OAuth-style session | Library sync, followed artists, playlists push/pull, Premium itags (up to ~256 kbps) when account has Premium |
| **AI** | User API key (OpenAI/Gemini) | Suggestions only; keys stay on device |

---

## Feature matrix

| # | Feature | Needs login? | Phase | Module | Status in scaffold |
|---|---------|--------------|-------|--------|--------------------|
| 1 | HQ ≤256 kbps (YTM Premium) | YTM Premium session | 2B | `features/quality` | Contract + `StreamQuality` enum |
| 2 | Home / Charts / Podcast / Moods & Genre | No (browse endpoints) | 2A | `features/browse` | Interface + Home UI sections |
| 3 | Search everything on YouTube | No | 2A | catalog + YTM client | Existing progressive search |
| 4 | Play stats + custom playlists + YTM sync | Stats/playlists local no; sync yes | 3 | `features/library` | Local playlist + scrobble |
| 5 | Spotify Canvas | Spotify token or public asset | 4 | `features/canvas` | Stub |
| 6 | 1080p video + subtitles | No for public YT | 3 | `features/video` | Stub player surface |
| 7 | AI song suggestions | User API key | 4 | `features/ai` | Stub |
| 8 | Playlist customize + YTM sync | Sync yes | 3 | `features/library` | Local CRUD first |
| 9 | Followed artist notifications | YTM session | 4 | `features/notify` | Stub + WorkManager hook |
| 10 | Cache + offline playback | No | 2C | `features/offline` | Download manager skeleton |
| 11 | Crossfade DJ-style | No | 3 | `features/audiofx` | Controller + gapless prep |
| 12 | Local scrobble (Last.fm-like) | No (Last.fm API optional later) | 2C | `features/scrobble` | **Implemented (Room-ready memory)** |
| 13 | SponsorBlock | No | 2C | `features/sponsorblock` | Client stub + skip hooks |
| 14 | Return YouTube Dislike | No | 2C | `features/ryd` | Client stub |
| 15 | Sleep timer | No | 1 | `features/sleeptimer` | **Implemented** |
| 16 | Android Auto online | No (browse via MediaLibrary) | 3 | `features/auto` | Service stub |

---

## Priority for “speed + convenience” product

1. **P0** YTM browse + search + stream (guest)  
2. **P0** Offline cache, sleep timer, local scrobble  
3. **P1** SponsorBlock + RYD, quality ladder, crossfade  
4. **P1** Local playlists + stats UI  
5. **P2** Optional YTM login → sync + Premium itags  
6. **P2** Android Auto MediaLibraryService  
7. **P3** Video 1080p, Canvas, AI, artist push  

---

## Quality ladder (256 kbps)

```
Guest / free itags     → typically 48–128 kbps audio-only
YTM Premium session    → request higher itags (e.g. 251/141 family where available)
User preference        → StreamQuality.AUTO | HIGH | MAX
```

Never claim Premium quality without a valid session; fall back silently to best free format.

---

## Crossfade (Apple Music–style)

- Overlap end of A with start of B by `crossfadeMs` (default 8–12 s)  
- Media3: playlist + `ConcatenatingMediaSource` / gapless + volume ramps via `AudioProcessor` or dual-player blend  
- MVP: single player, start next item early with volume fade (simpler, good enough)

---

## Android Auto

- Extend `MediaLibraryService` (Media3) with browsable tree: Home, Charts, Library, Search  
- Online content = same `FastMusicCatalog` resolve path  
- No GMS Cast required for Auto media browse
