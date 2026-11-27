# Skip Intro for Stremio

Netflix-style **Skip Intro**, **Skip Recap**, **Skip Credits** and **Next Episode** buttons for Stremio. It is built with anime in mind but works for any series or movie that has timestamps.

A regular Stremio addon can't do this, because addons have no access to the player. So `skip-intro.plugin.js` runs inside Stremio's page instead. It works in these places:

- **Normal Stremio desktop (Windows, v5)**, where mpv plays the video. A small watcher adds the plugin to Stremio's page. See below.
- **The official Stremio app for macOS**, through a small launcher app.
- **The official Stremio app for Linux** (Flatpak), through a small helper library that Flatpak loads into it.
- **The Stremio app for Android phones**, through a [Morphe](https://morphe.software) patch (in `android/`). The phone app is native, so it gets its own Java version of the same lookup.
- **[Stremio Enhanced](https://github.com/REVENGE977/stremio-enhanced)**, which has a built-in plugin system. Note that its Chromium player can't play some files, such as HEVC 10-bit MKV.

## Install: normal Stremio (Windows)

### Easy way
Go to **https://osamudazai666.github.io/stremio-skip-intro**, click **Install for Stremio**, paste the copied command into Terminal and click **Yes**. That command is:

```powershell
irm https://osamudazai666.github.io/stremio-skip-intro/install.ps1 | iex
```

It installs to `%LOCALAPPDATA%\StremioSkipIntro`, and the plugin updates itself from this repo.

To uninstall:

```powershell
irm https://osamudazai666.github.io/stremio-skip-intro/uninstall.ps1 | iex
```

### Manual way (from a clone)
1. **One-time, needs admin:** run `launcher\Enable-StremioDebugPort.ps1` as administrator.
   - It adds one per-app WebView2 setting for `stremio-shell-ng.exe` under `HKCU\Software\Policies\Microsoft\Edge\WebView2\AdditionalBrowserArguments`. This opens a debugging port on `127.0.0.1:9334`, reachable only from this PC, while Stremio runs.
   - Stremio still loads `https://web.stremio.com` and plays through mpv, so your sign-in and playback don't change.
2. Run `launcher\Install-Autostart.ps1`. It starts a hidden watcher now and at every Windows sign-in.
   - When Stremio is open, the watcher adds the plugin. When you edit `skip-intro.plugin.js`, it hot-swaps the new version in.
   - Its log is at `%TEMP%\stremio-skip-intro.log`.
3. Open Stremio as usual and restart it once.

**To undo:** run `launcher\Uninstall-Autostart.ps1`, then run `launcher\Disable-StremioDebugPort.ps1` as administrator, then restart Stremio.

### How it talks to mpv
Stremio Web sends commands to the shell as:
```
chrome.webview.postMessage(JSON.stringify({id: 0, type: 6, args: [command, params]}))
```
mpv's state comes back as `mpv-prop-change` events, which carry `time-pos`, `duration` and `pause`.
- **Listening:** the plugin listens to those events.
- **Seeking:** it sends `mpv-set-prop ["time-pos", t]`, the same message Stremio's own seek bar sends.
- **Duration:** if the plugin was added after mpv reported the duration, it reads the total time from Stremio's seek bar instead.

## Install: macOS (official Stremio app)

```bash
curl -fsSL https://osamudazai666.github.io/stremio-skip-intro/install-mac.sh | bash
```

No admin rights are needed. Afterwards, open Stremio with the new **Stremio Skip Intro** app. Putting it in your Dock instead of Stremio works well.

**How it works:**
- Stremio for Mac is a WebKit view with libmpv, and its code signature allows `DYLD_INSERT_LIBRARIES` (`com.apple.security.cs.allow-dyld-environment-variables` and `disable-library-validation`).
- The launcher starts the official `/Applications/Stremio.app` with `macos/SkipIntroInject.dylib`.
- When Stremio creates its WebKit view, the library adds `skip-intro.plugin.js` to the page as a `WKUserScript`. The app bundle is never modified.
- On macOS, Stremio's page talks to mpv through the same `window.chrome.webview` bridge as on Windows, so the plugin needs no changes.

**Tested** on macOS 27.0.1 (Apple Silicon) with Stremio 5.1.31. The plugin loads, the mpv bridge works, Skip Intro seeks mpv, and Next Episode opens the next episode. The library is universal (it includes Intel), but it hasn't been tested on an Intel Mac.

To uninstall:

```bash
curl -fsSL https://osamudazai666.github.io/stremio-skip-intro/uninstall-mac.sh | bash
```

**Development:** build with `macos/build.sh`, which needs the Xcode Command Line Tools. `macos/dev-launch.sh` starts Stremio in developer mode: debug log, Safari Web Inspector, and an eval channel used by `macos/dev-eval.sh`.

## Install: Linux (official Stremio Flatpak)

```bash
curl -fsSL https://osamudazai666.github.io/stremio-skip-intro/install-linux.sh | bash
```

No sudo is needed. You need the official Stremio app from Flathub (`flatpak install flathub com.stremio.Stremio`). Afterwards, open Stremio as usual.

**How it works:**
- Stremio for Linux ([stremio-linux-shell](https://github.com/Stremio/stremio-linux-shell)) is GTK4 + WebKitGTK + libmpv. It adds its IPC bridge to the page with `webkit_user_content_manager_add_script()`.
- `linux/libskipintro.so` replaces that function through `LD_PRELOAD`. When Stremio adds its bridge, the library adds `skip-intro.plugin.js` to the same page. It does nothing in any other process.
- The installer puts the files in Stremio's own Flatpak data folder, `~/.var/app/com.stremio.Stremio/data/stremio-skip-intro`. It then sets `LD_PRELOAD` with a per-user `flatpak override`, so Stremio loads the helper however you open it. The Stremio app isn't modified.
- On Linux, Stremio's page talks to mpv through the same `chrome.webview` bridge as on Windows and macOS, so the plugin needs no changes.
- A systemd user timer fetches plugin updates every 6 hours.

**Tested** with Stremio 1.3.0 (Flatpak) on Ubuntu 26.04 in WSL: the plugin loads, Skip Intro seeks mpv, and Next Episode works. The library is built for x86_64 and aarch64, but the ARM build hasn't been run yet. The older Qt-based Stremio 4 packages aren't supported; use Stremio Enhanced there.

To uninstall:

```bash
curl -fsSL https://osamudazai666.github.io/stremio-skip-intro/uninstall-linux.sh | bash
```

**Development:** `linux/build.sh` builds the library (gcc, plus gcc-aarch64-linux-gnu for ARM). The **Linux helper** GitHub Actions workflow builds it on every change, then tests it against the real Stremio Flatpak on a virtual screen:
- `linux/dev-test.js` checks, from inside the page, that the plugin is loaded, the mpv bridge is hooked, an mpv event arrives, the AniSkip lookup works and the anime map works.
- `install-linux.sh` and `uninstall-linux.sh` run end to end.

The libraries committed in `linux/` are the ones that workflow built, and the installer checks their SHA-256.

## Install: Android (Stremio app)

1. Install [Morphe Manager](https://morphe.software) on the phone.
2. Add this repository as a patch source: open **https://morphe.software/add-source?github=OsamuDazai666/stremio-skip-intro** on the phone, or in Morphe Manager go to **Sources → +** and paste `https://github.com/OsamuDazai666/stremio-skip-intro`.
3. Pick **Stremio**, keep **Skip Intro** selected, and tap **Patch**. Use the Stremio phone app **2.6.1** (APK from [stremio.com/downloads](https://www.stremio.com/downloads)). Newer versions are listed as experimental until they're checked.
4. Morphe installs the patched app in place of yours (Android needs the original uninstalled first). Sign in again afterwards.

**How it works:**
- Stremio's Android player already has a Skip Intro / credits overlay. It's fed by `Player.introOutro` from Stremio's core, and it's shown only to Stremio Premium accounts.
- The patch hooks the player's view model, so AniSkip / TheIntroDB times (looked up the same way as on desktop, including the anime id mapping) fill `introOutro` when Stremio has none.
- Stremio's plain overlay is hidden, and Netflix-style buttons are drawn on top of the player instead:
  - **Skip Intro** (or **Skip Recap**) for the whole intro.
  - **Watch Credits** and **▶ Next Episode** at the credits. With binge watching on, Next Episode counts down for 10 s.
- The buttons run Stremio's own seek and play-next actions.
- Stremio's own Premium timestamps still need Premium: accounts without it only ever get the AniSkip / TheIntroDB times.

**Tested** on a Samsung Galaxy A06 (Android 16) with Stremio 2.6.1: intro skip, credits, and next episode.

To uninstall, uninstall the patched Stremio and install Stremio again from Google Play.

**Development:** `android/` is a Morphe patches project (GPL-3.0, from Morphe's template). The patch is in `android/patches`, and the Java code it adds to Stremio is in `android/extensions`. The **Android patches** GitHub Actions workflow builds the `.mpp` bundle on every push. Pushing a tag `android-vX.Y.Z` publishes a release and points `patches-bundle.json` (read by Morphe Manager) at it.

## Install: Stremio Enhanced

1. Install Stremio Enhanced and sign in.
2. Open **Settings → Plugins → Open plugins folder**.
3. Copy `skip-intro.plugin.js` into that folder.
4. Restart Stremio Enhanced, then turn on **Skip Intro** in the plugin list.

## What you get

| While playing… | You see |
|---|---|
| "Previously on…" | `Skip Recap` |
| Intro / anime OP | `Skip Intro` |
| End credits / anime ED, with nothing after it | `Watch Credits` · `▶ Next Episode` |
| End credits followed by a post-credits scene or preview | `Skip Credits` · `▶ Next Episode` |
| Credits of a movie or the last episode | `Skip Credits` |
| Post-credit preview | `Skip Preview` |

- The buttons sit bottom-right and move up when Stremio's control bar is visible.
- When a button is on screen, **Enter** presses it.
- **Auto-skip** (per segment type, in settings): the button fills up over the countdown, then skips and shows a short "Intro skipped" toast. Pausing or seeking cancels the countdown. If you seek back into a segment you already skipped, you get the button again, not a second auto-skip.

### Settings
In Stremio Enhanced → Settings → Skip Intro. In normal Stremio there's no settings screen yet, so the defaults below apply.
- **Intros / Recaps / Previews:** `button` (default), `auto`, or `off`.
- **Credits:**
  - `next` (default): Watch Credits + Next Episode.
  - `auto`: plays the next episode automatically, unless something follows the credits, in which case it only skips the credits.
  - `button`: a Skip Credits button.
  - `off`.
- **Auto-skip countdown:** instant, 3, 5 or 10 s.
- **Enter shortcut** on/off.
- **AniSkip** on/off and **TheIntroDB** on/off.
- **TheIntroDB API key** (optional).

## Where the timestamps come from

| Source | Used for |
|---|---|
| [AniSkip](https://api.aniskip.com) | Anime openings, endings and recaps (crowd-sourced, keyed by MyAnimeList id) |
| [TheIntroDB](https://theintrodb.org) | Intros, recaps, credits and previews for TV, movies and anime |

For anime, AniSkip wins wherever both sources have data. TheIntroDB fills in the rest.

### Anime episode mapping
Stremio identifies episodes in several ways:
- `tt2560140:3:13` (IMDb season/episode, from Cinemeta)
- `kitsu:42765:1` (Anime Kitsu addon)
- `mal:` / `anilist:` ids

AniSkip needs a **MyAnimeList id + episode**. The plugin downloads the [Fribb/anime-lists](https://github.com/Fribb/anime-lists) mapping once a week (about 1.3 MB compressed, cached) and converts the ids. That covers:
- split seasons, e.g. AoT S3E13 → MAL 38524 ep 1, and JJK S3E13 → MAL 63824 ep 1
- absolute numbering, e.g. One Piece ep 1000 → MAL 21 ep 1000

AniSkip times are matched to your file's length, within ±30 s, so a release of a different length doesn't skip at the wrong moment.

## Development

There's no build step: the plugin is one plain JS file.

`test/harness.html` fakes Stremio's player DOM and core state, so you can test the UI and the live lookups in a normal browser:

```powershell
python -m http.server 8765 --bind 127.0.0.1
```
Then open http://localhost:8765/test/harness.html, pick a test case and press **Load**. Use the "Jump to …" buttons to land in each segment.

Debugging inside Stremio Enhanced (DevTools console):
```js
SkipIntro.status()      // current video id, MAL mapping, segments
SkipIntro.settings      // effective settings
```

## Known limitations
- **Platforms:** Windows, macOS, Linux (Stremio Flatpak), Android phones (Stremio 2.6.1) and Stremio Enhanced. Not Android TV or iOS yet.
- **Stremio updates:** in normal Stremio, the plugin depends on Stremio Web's shell message format. A Stremio update that changes that format would stop skipping from working, but playback itself would not be affected.
- When neither AniSkip nor TheIntroDB has timestamps for an episode, no button appears. Contributing timestamps from inside the player is planned.
- Stremio's CSS class names are hashed, so the plugin finds elements by class *substring* (`player-container`, `overlayHidden`). A big Stremio Web redesign could need selector updates.
