// Smoke test for the Linux helper, run in Stremio's page by libskipintro.so when SKIPINTRO_DEV=1
// (copied next to the plugin as dev-test.js). Reports one "SMOKE {json}" line to skipintro.log.
// Used by the "Linux helper" GitHub Actions workflow; never installed for users.
(function () {
  const report = (obj) => {
    try { window.webkit.messageHandlers.skipintro.postMessage('SMOKE ' + JSON.stringify(obj)); } catch (_) { /* ignore */ }
  };
  const wait = (ms) => new Promise((r) => setTimeout(r, ms));

  async function run() {
    const out = { url: location.href.slice(0, 120) };
    // Give the page and the plugin's first ticks time to start.
    for (let i = 0; i < 40 && !(window.SkipIntro && window.SkipIntro._internals.MpvBridge.hooked); i++) await wait(500);

    const si = window.SkipIntro;
    out.plugin = !!si;
    out.version = si && si.version;
    out.ipcBridge = !!(window.chrome && window.chrome.webview && window.qt && window.qt.webChannelTransport);
    if (!si) return report(out);

    const bridge = si._internals.MpvBridge;
    out.bridgeHooked = !!bridge.hooked;
    out.postMessageWrapped = !!(window.chrome.webview.postMessage && window.chrome.webview.postMessage.__skipIntroWrapper);

    // An mpv event the way Stremio's Linux shell delivers it: __postMessage(<JSON string>).
    try {
      window.__postMessage(JSON.stringify({ id: 1, object: 'transport', type: 1, args: ['mpv-prop-change', { name: 'time-pos', data: 42.5 }] }));
      await wait(100);
      out.mpvEventSeen = bridge.props['time-pos'] === 42.5;
    } catch (e) {
      out.mpvEventError = String(e);
    }

    // Timestamp lookups from inside the Flatpak (network + CORS from WebKitGTK).
    try {
      const segs = await si._internals.fetchAniSkip(52991, 27, 0); // Frieren S1E27
      const intro = segs.find((s) => s.type === 'intro');
      out.aniskipIntro = intro ? [Math.round(intro.start), Math.round(intro.end)] : null;
    } catch (e) {
      out.aniskipError = String(e);
    }
    try {
      const idx = await si._internals.AnimeMap.load();
      const q = si._internals.parseVideoId('tt22248376:1:27');
      const anime = si._internals.resolveAnime(idx, q);
      out.animeMap = anime ? { mal: anime.mal, ep: anime.ep } : null;
    } catch (e) {
      out.animeMapError = String(e);
    }
    report(out);
  }

  run().catch((e) => report({ error: String(e && e.stack || e) }));
})();
