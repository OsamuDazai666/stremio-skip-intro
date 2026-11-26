# Stremio Skip Intro / Outro — Plan

Goal: skip openings, endings, recaps and previews in Stremio. Anime gets first-class support, and other series work too.

## Status (2026-11-26)
**Decided:**
- v1 targets **desktop** (Stremio Enhanced plugin) with a **Netflix-style button**.
- It runs standalone (no backend), calling AniSkip and TheIntroDB directly.

**Done (Phase 0 + most of Phase 1):** `skip-intro.plugin.js`
- Netflix-style buttons: Skip Intro / Skip Recap / Skip Credits / Skip Preview, plus Watch Credits + Next Episode.
- Auto-skip with a countdown fill, and the Enter shortcut.
- Anime ID mapping (IMDb season/episode, Kitsu, MAL, AniList → MAL episode) through Fribb/anime-lists, including split seasons and absolute numbering.
- AniSkip and TheIntroDB clients, with priority-based merging.
- Verified against the live APIs and in `test/harness.html`.

**Update (2026-11-27): normal Stremio desktop (Windows v5) supported.**
- Normal Stremio plays through mpv, so it handles files that Enhanced's Chromium player can't (e.g. HEVC 10-bit Frieren releases).
- Setup: a per-app WebView2 policy opens a localhost-only debug port; a watcher in `launcher/` injects and hot-swaps the plugin; the plugin drives mpv through Stremio Web's own `chrome.webview` message format.
- Verified on Frieren S1E27 (Torrentio / Cinemeta): the episode matched MAL 52991 ep 27, the button appeared during the intro, and clicking it seeked mpv from 0:40 to 1:59.
- Also verified in Stremio Enhanced: AoT S1E1 via Kitsu.
- Rejected approach: serving Stremio's page from a localhost proxy via `--webui-url`. Playback in mpv failed with it.

**Next:**
- Seekbar segment markers.
- "Mark intro" contribution UI (needs the Phase 2 backend, or submission to AniSkip/TheIntroDB).
- Check button placement against the real Stremio control bar.

---

## 1. The core constraint (read this first)

A normal Stremio **addon** (built with `stremio-addon-sdk`) **cannot control the player**. Addons can only return catalogs, metadata, streams and subtitles. There is no API to add a button or seek the video. So there are only two ways to skip:

| Approach | How it skips | Works on | UX |
|---|---|---|---|
| **A. Client plugin** (Stremio Enhanced, desktop) | Injected JS reads player state, shows a "Skip Intro" button, sets `video.currentTime` | Windows / macOS / Linux desktop (Stremio Enhanced app or its Chrome extension) | Best: real button, optional auto-skip, seekbar markers |
| **B. Stream-wrapper addon** (server) | Adds a second stream ("🎯 Skip") whose URL is an HLS playlist that leaves out the intro byte range and ends before the credits | Any Stremio client in theory, including Android TV and mobile | Invisible skip, no button. Needs an upstream stream source (debrid). MKV often fails on TV/web players |

**Recommendation:** build a **shared skip-times backend** first, then **(A) the desktop plugin** (Phase 1–2), then **(B) the wrapper addon** for TV/mobile (Phase 3) as a stretch goal. Approach B is the riskiest part technically.

### Prior art (worth knowing, possibly reusing)
- **TheIntroDB Stremio Enhanced plugin** is approach A, using TheIntroDB data. It reads `window.services.core.transport.getState('player')` for the meta ID and season/episode, then calls `api.theintrodb.org/v4/media`.
- **IntroHater** is approach B. It is an Express + MongoDB addon that wraps AIOstreams/debrid streams and serves HLS byte-range manifests. It uses AniSkip, its own DB and chapter detection.
- **Jellyfin Intro Skipper** detects intros with audio fingerprinting (chromaprint) across the episodes of a season.

**What makes ours different:** an **anime-first, multi-source resolver**. It maps IMDb, Kitsu and season/episode correctly to MAL and AniSkip, merges sources by confidence, and covers outros and recaps as well as intros.

---

## 2. Architecture

```
            ┌──────────────────────────────────────────┐
            │          Skip-Times Resolver API         │
            │  GET /v1/skips?id=tt0388629:1:5&dur=1420 │
            │                                          │
            │  ID mapper ──► Sources (priority/merge): │
            │   IMDb/Kitsu    1. Own community DB      │
            │   → MAL/AniList 2. AniSkip (anime)       │
            │   → TMDB/TVDB   3. TheIntroDB (TV/anime) │
            │                 4. File chapter markers  │
            │                 5. Fingerprint (later)   │
            │  Cache (SQLite/Postgres + LRU)           │
            └───────────▲───────────────────▲──────────┘
                        │                   │
   ┌────────────────────┴───┐     ┌─────────┴──────────────────────┐
   │ A. Stremio Enhanced    │     │ B. Stream-wrapper addon        │
   │    plugin (desktop)    │     │    manifest.json / stream/...  │
   │  - Skip button / auto  │     │  - wraps upstream debrid stream│
   │  - seekbar markers     │     │  - HLS byte-range playlist     │
   │  - "mark intro" submit │     │    without the intro/outro     │
   └────────────────────────┘     └────────────────────────────────┘
```

### Unified response format
```json
{
  "id": "tt0388629:1:5",
  "duration": 1420.0,
  "segments": [
    { "type": "intro",   "start": 92.1,   "end": 181.5, "source": "aniskip",    "confidence": 0.9 },
    { "type": "recap",   "start": 0,      "end": 45.0,  "source": "theintrodb", "confidence": 0.7 },
    { "type": "outro",   "start": 1300.2, "end": 1390.0,"source": "aniskip",    "confidence": 0.9 },
    { "type": "preview", "start": 1390.0, "end": 1420.0,"source": "community",  "confidence": 0.6 }
  ]
}
```

---

## 3. Data sources & the anime ID-mapping problem

| Source | Coverage | Keyed by | Notes |
|---|---|---|---|
| **AniSkip** (`api.aniskip.com/v2/skip-times/{malId}/{ep}?types=op&types=ed&types=recap&types=mixed-op&types=mixed-ed&episodeLength=…`) | Anime: huge OP/ED coverage | MAL ID + **absolute** episode number + episode length | Free. Returns 404 when nothing is found and 429 when rate-limited. Times are for one particular release; use `episodeLength` to choose between them |
| **TheIntroDB** (`api.theintrodb.org/v4/media?imdb_id=…&season=&episode=&duration_ms=`) | TV + movies + some anime: intro, recap, credits, preview | IMDb / TMDB / TVDB + season/episode | Anonymous use allowed, optional Bearer key, 429 with `retry_after_seconds`. **Official docs not verified yet** |
| **Own community DB** | Whatever our users submit | Stremio video ID + duration bucket | Filled by the plugin's "mark intro/outro" UI and votes |
| **Chapter markers** | Many fansub/BD-rip MKVs have "Opening"/"Ending" chapters | The file itself | Desktop plugin: can't read chapters easily. Server: `ffprobe -show_chapters` |
| **Audio fingerprinting** (Phase 4) | Anything, automatically | Season-wide comparison | Chromaprint on the first/last ~5 min of 2+ episodes. Heavy, but it fills the gaps |

### ID mapping (the hard, anime-specific part)
Stremio gives us one of the following:
- `tt1234567:S:E` (Cinemeta, which uses **TVDB-style seasons**), or
- `kitsu:12345:E` (Anime Kitsu addon), or `mal:…` / `anilist:…` from some anime addons.

AniSkip needs **MAL ID + episode number within that MAL entry**. Each anime "season" is usually a separate MAL entry, and long shows like One Piece use absolute numbering.

Plan:
1. Load **Fribb/anime-lists** (`anime-list-full.json`: mal ↔ anilist ↔ kitsu ↔ tvdb ↔ imdb ↔ tmdb) and **Anime-Lists/anime-lists** (`anime-list-master.xml`: TVDB season/episode offsets per AniDB entry). Refresh daily.
2. `tt…:S:E` → TVDB season S → find the AniDB/MAL entry whose `defaulttvdbseason` and `episodeoffset` cover episode E → MAL ID + local episode.
3. `kitsu:ID:E` → MAL ID directly, same episode.
4. If the mapping is ambiguous, try the candidates and choose by `episodeLength` match.
5. Unit-test the mapping heavily with known tricky shows: One Piece, AoT, Gintama, JJK, Bleach TYBW, Monogatari.

### Merge rules
- Prefer, in order: user-confirmed community entry with net positive votes → AniSkip (anime) / TheIntroDB (non-anime) → chapters → fingerprint.
- **Duration guard:** if the source's episode length differs from the actual video length by more than ~5 s, shift by the difference (when it looks like a leading/trailing logo) or drop the entry.
- Clamp: intro ≤ 3 min, segments inside `[0, duration]`, no overlaps.

---

## 4. Components & tech stack

- **Language:** TypeScript everywhere (monorepo with `pnpm` workspaces).
- **`packages/core`**: the ID mapper, source clients (AniSkip, TheIntroDB), merge logic and types. Pure, with no runtime dependencies so it can be tested and reused.
- **`apps/api`**: resolver API on Node 20 + Fastify. SQLite (dev) / Postgres (prod) for cache + community submissions, plus an in-memory LRU. Rate limiting and an upstream back-off on 429.
- **`apps/plugin`**: `skip-intro.plugin.js` for Stremio Enhanced. It can run in **standalone mode**, calling AniSkip and TheIntroDB directly with the mapping bundled or fetched, so it works even without our API.
- **`apps/addon`** (Phase 3): `stremio-addon-sdk` manifest + stream handler + HLS manifest route. ffprobe for duration, keyframe and byte offsets.
- **Deploy:** Docker image on Fly.io / Render / a home server. Approach B needs ffprobe, so it can't run on Cloudflare Workers.

---

## 5. Desktop plugin behaviour (Approach A)

1. **Detect playback.** Poll `window.services.core.transport.getState('player')` for `metaItem` + `seriesInfo`. If that fails, parse the `#/player/...` / `#/detail/...` URL. Re-trigger when the `<video>` `currentSrc` changes or on `loadedmetadata`.
2. **Fetch** `/v1/skips?id=…&dur=video.duration` (or call the sources directly in standalone mode). Cache per video.
3. **UI:**
   - "Skip Intro" / "Skip Recap" / "Skip Ending" / "Skip Preview" button that appears while inside a segment (Netflix-style, bottom-right).
   - Settings, via the Stremio Enhanced Settings API: per segment type choose `off | button | auto-skip`, with a 5-second "auto-skipping…" countdown that can be cancelled.
   - **Outro → "Next Episode"**: for the ending, offer "Skip to next episode" by triggering Stremio's own next-video action instead of seeking.
   - Coloured segment markers on the seekbar.
4. **Contribute:** keyboard shortcuts (`[` = mark start, `]` = mark end, then choose the type) and 👍/👎 after a skip. Results are POSTed to our API, and optionally also to AniSkip/TheIntroDB.
5. **Robustness:** select DOM nodes with class substring selectors (`[class*="player-container"]`), because Stremio's classes are hashed, and use a MutationObserver. Never break playback when the API fails.

---

## 6. Stream-wrapper addon (Approach B, for TV/mobile)

- The user configures an upstream source (debrid key or an AIOstreams/Torrentio-style addon URL) on a `/configure` page. The config is encoded into the addon URL.
- `stream/series/{id}.json` → fetch the upstream streams → for each one, also return a "🎯 Skip" variant pointing to `/hls/{config}/{token}.m3u8`.
- The HLS route uses ffprobe to read the container index (MP4 `moov` / MKV Cues), converts intro end and outro start to keyframe byte offsets, and returns a playlist of `#EXT-X-BYTERANGE` segments pointing at the **original debrid URL**. The player fetches the bytes directly, so there is no proxy bandwidth.
- Outro: the playlist ends at the credits start, so Stremio's "next episode" prompt fires sooner.
- **Known risks (spike before committing):**
  - Byte-range HLS over a non-fragmented MP4/MKV is not standard HLS. ExoPlayer (Android TV) and hls.js (web) often reject MKV. Expect **MP4 + desktop/libmpv to work and MKV on TV to fail**, which matches IntroHater's own notes.
  - The fallback for problem clients is an ffmpeg remux to fragmented MP4/TS on the fly. That costs CPU and bandwidth, so it only makes sense on a self-hosted server.
  - Debrid links expire, so manifests need short TTLs and re-resolution.

---

## 7. Roadmap

| Phase | Deliverable | Done when |
|---|---|---|
| **0. Spike (1–2 days)** | Confirm in Stremio Enhanced: player state access, `video.currentTime` seek, AniSkip + TheIntroDB calls, and one IMDb→MAL mapping | A hard-coded "Skip Intro" works on one anime episode |
| **1. Core + plugin MVP** | `packages/core` (mapping + AniSkip + TheIntroDB + merge) and a standalone plugin with buttons for intro/outro | Skips work on 10 test titles (5 anime, 5 western series), and mapping tests pass |
| **2. Backend + community** | `apps/api` with caching, submissions and votes; the plugin's marking UI, auto-skip, seekbar markers and settings | Users can submit a missing intro and see it used on the next play |
| **3. Wrapper addon** | `apps/addon` with configure page + HLS byte-range skip | MP4 streams skip on desktop and Android; documented limits for MKV/TV |
| **4. Auto-detection** | Chromaprint season-fingerprint worker + chapter extraction to backfill the DB | New episodes get intros automatically after the first two are watched or processed |

### Testing
- Unit: ID mapping fixtures (the tricky anime), merge and duration-shift logic, AniSkip/TheIntroDB response parsing (with recorded HTTP fixtures).
- Integration: the API against live sources (nightly, rate-limit friendly).
- Manual matrix: Stremio Enhanced on Windows; the Phase 3 addon on Stremio desktop, Android TV and web.

---

## 8. Open questions for you
1. **Target platform:** is desktop only (Stremio Enhanced) OK for v1, or is Android TV/mobile a must-have? That decides whether Approach B comes early.
2. **Hosting:** run our own backend (community DB, needs a server) or keep the plugin standalone, calling AniSkip/TheIntroDB directly with no server?
3. **Default behaviour:** auto-skip or a button by default?
4. **Build vs. extend:** TheIntroDB's plugin and IntroHater already exist. Build our own anime-first version, or contribute AniSkip/anime mapping to one of them?
