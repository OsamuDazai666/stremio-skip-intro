# macOS handoff (2026-11-27)

This is the brief for continuing Skip Intro development on a Mac. Read `README.md` and `PLAN.md` first.

## Where things stand (Windows, done and verified)
- **`skip-intro.plugin.js`:** a single-file plugin.
  - Shows Netflix-style Skip Intro / Recap / Credits / Preview and Next Episode buttons.
  - **Data:** AniSkip (anime, MAL id + episode) and TheIntroDB (TV, movies, anime).
  - **Episode matching:** IMDb, Kitsu and MAL ids are converted to the right MyAnimeList episode using Fribb/anime-lists. This handles split seasons and absolute numbering.
  - **Players it drives:**
    - an HTML `<video>` element (Stremio Enhanced / web)
    - **mpv in the Stremio desktop shell**, through Stremio Web's own bridge:
      - outgoing: `chrome.webview.postMessage(JSON.stringify({id:0, type:6, args:[cmd, params]}))`
      - incoming: `message` events on `chrome.webview` carrying `{type:1, args:["mpv-prop-change", {name, data}]}` for `time-pos`, `duration`, `pause` and `path`
      - seeking: `mpv-set-prop ["time-pos", seconds]`
  - **Stremio core:** `window.core.getState('player')` on Stremio Web 5.1+ (older builds: `window.services.core.transport`).
  - **Late injection:** if the plugin was added after mpv reported the duration, it reads the total time from the seek bar's last `[class*="label"]`.
  - **`destroy()`:** removes everything the plugin added, so a newer version can be swapped in.
- **Windows delivery:**
  - `launcher/` holds a watcher that injects the plugin into normal Stremio over CDP.
  - CDP is reachable because of a per-app WebView2 policy that opens `127.0.0.1:9334`.
  - `docs/install.ps1` is the one-line installer. The site is `docs/index.html` on GitHub Pages.
- **Verified on Frieren S1E27:** the episode matched MAL 52991 ep 27, the button appeared, and clicking it seeked mpv from 0:40 to 1:59.

## Lessons (don't repeat these)
- **Never change where Stremio loads its page from.** Serving the web UI from a localhost proxy via `--webui-url` broke mpv playback ("Stream is not supported").
- **Never `delete chrome.webview.postMessage`.** It's an own property: deleting it removed the function and silently broke all of Stremio's mpv commands. Always restore the original.
  - The shell keeps a native copy in `window.qt.webChannelTransport.send` (on Windows).
  - The plugin's wrapper is tagged `__skipIntroWrapper` / `__skipIntroOrig`.
- **Test against the real app before claiming it works.** Use `SkipIntro.status()` in the console.

## macOS: the plan
- **The official Stremio 5 for Mac won't work:** it uses wry/WKWebView, which has no CDP, so the plugin can't be injected.
- **Target [REVENGE977/stremio-shell-ng-macos](https://github.com/REVENGE977/stremio-shell-ng-macos) instead.** It's a community Stremio 5 shell with:
  - libmpv playback (plays HEVC/MKV)
  - Stremio Enhanced plugin and theme support
  - `window.qt.webChannelTransport` and `window.chrome.webview` shims on top of wry's `window.ipc`
  - builds for Intel and Apple Silicon

### Steps
1. **Install and inspect:**
   - Install its latest release and confirm it plays an episode.
   - Find its plugins folder: look in `src/stremio_app/enhanced/`, the README, or the app's settings.
   - Find how to open the Web Inspector or dev tools.
2. **Read its bridge shim (`stremiover.js`, `src/stremio_app/ipc.rs`) and confirm:**
   - whether `chrome.webview.postMessage` is writable, and which property holds the original
   - whether incoming mpv events reach `chrome.webview.addEventListener('message', …)` as JSON strings in the same `{type:1, args:[...]}` shape
   - whether `window.core.getState('player')` exists
   - **If anything differs:** adapt `MpvBridge` in the plugin, keeping it working on Windows. Note especially the native-function recovery, which on Windows reads `qt.webChannelTransport.send`. Re-test on Windows afterwards via `test/harness.html` and the real app.
3. **Test for real:** copy `skip-intro.plugin.js` into the plugins folder, enable it, and play an anime episode.
   - `SkipIntro.status()` should show `player: "mpv"`, a MAL mapping, and segments.
   - The Skip Intro button should seek. Next Episode should go to the next episode.
4. **Mac delivery:**
   - **`docs/install.sh`:** a one-line installer, `curl -fsSL https://osamudazai666.github.io/stremio-skip-intro/install.sh | bash`.
     - It checks the shell app is installed, or points to its releases.
     - It copies the plugin into the plugins folder.
     - It may add an updater (a launchd agent, or a check on run) that pulls `skip-intro.plugin.js` from `main`.
     - **Ask the user before** adding a launchd agent or anything persistent.
   - **`docs/uninstall.sh`.**
   - **Website (`docs/index.html`):** add a Windows / macOS switch next to the install command. Keep the design (theater blue `#0E1A2B`, subtitle yellow `#FFE14A`, Trebuchet subtitles). The design rules are in `design/specs/2026-11-27-landing-redesign-design.md`.
   - **Android:** link [tikkamasalla/stremio-morphe-patches](https://github.com/tikkamasalla/stremio-morphe-patches) in the FAQ. It patches the official APK to add intro skip on phones and Android TV, so we don't build our own.
5. **Ship:**
   - Commit with the user's GitHub no-reply identity: `108152127+OsamuDazai666@users.noreply.github.com`.
   - Push to `main`, then check that the live site and `install.sh` URL respond.

## Repo
- **GitHub:** https://github.com/OsamuDazai666/stremio-skip-intro (public, MIT).
- **Pages:** serves `docs/`. Live at https://osamudazai666.github.io/stremio-skip-intro/.
