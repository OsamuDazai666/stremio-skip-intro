/**
 * @name Skip Intro
 * @description Netflix-style "Skip Intro", "Skip Recap" and "Next Episode" buttons for series and anime. Timestamps from AniSkip (anime) and TheIntroDB (TV, movies, anime).
 * @version 0.1.0
 * @author stremio-skip-intro
 * @updateUrl https://raw.githubusercontent.com/OsamuDazai666/stremio-skip-intro/main/skip-intro.plugin.js
 */
/* jshint esversion: 11, browser: true, devel: true */
/* global StremioEnhancedAPI */

(function () {
  'use strict';

  if (window.__skipIntroLoaded) return;
  window.__skipIntroLoaded = true;

  // ---------------------------------------------------------------------------
  // Constants
  // ---------------------------------------------------------------------------

  const VERSION = '0.1.0';
  const ANISKIP_API = 'https://api.aniskip.com/v2';
  const TIDB_API = 'https://api.theintrodb.org/v4';
  const ANIME_LIST_URL = 'https://raw.githubusercontent.com/Fribb/anime-lists/master/anime-list-full.json';
  const ANIME_LIST_TTL_MS = 7 * 24 * 3600 * 1000;
  const CACHE_NAME = 'skip-intro-v1';
  const ANISKIP_LENGTH_TOLERANCE_S = 30; // accept AniSkip times recorded on releases up to 30s longer/shorter
  const MIN_SEGMENT_S = 3;
  const TICK_MS = 250;
  const INFO_POLL_MS = 1500;

  // Debug channel: the launcher's proxy (normal Stremio) can collect log lines in a file.
  const DEBUG = !!window.__SKIPINTRO_DEBUG;
  function dbg(...args) {
    if (!DEBUG) return;
    try {
      const line = args.map((a) => (typeof a === 'string' ? a : a instanceof Error ? a.stack || a.message : JSON.stringify(a))).join(' ').slice(0, 4000);
      const mac = window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.skipintro;
      if (mac) mac.postMessage(line); // macOS injector writes these to skipintro.log
      else fetch('/skip-intro/log', { method: 'POST', body: line, keepalive: true }).catch(() => {});
    } catch (_) { /* ignore */ }
  }
  const log = (...args) => { console.log('[SkipIntro]', ...args); dbg(...args); };
  const warn = (...args) => { console.warn('[SkipIntro]', ...args); dbg('WARN', ...args); };

  // ---------------------------------------------------------------------------
  // Settings (Stremio Enhanced settings API, localStorage fallback)
  // ---------------------------------------------------------------------------

  const MODE_OPTIONS = [
    { value: 'button', label: 'Show skip button' },
    { value: 'auto', label: 'Skip automatically (with countdown)' },
    { value: 'off', label: 'Off' },
  ];

  const SETTINGS_SCHEMA = [
    { key: 'si_intro', type: 'select', label: 'Intros / openings', description: 'Shows a "Skip Intro" button while the intro or anime opening plays.', defaultValue: 'button', options: MODE_OPTIONS },
    { key: 'si_recap', type: 'select', label: 'Recaps', description: 'Shows a "Skip Recap" button during "previously on" segments.', defaultValue: 'button', options: MODE_OPTIONS },
    {
      key: 'si_outro', type: 'select', label: 'Credits / endings', description: 'What to show during the end credits or anime ending.', defaultValue: 'next',
      options: [
        { value: 'next', label: 'Show "Watch Credits" and "Next Episode" buttons' },
        { value: 'auto', label: 'Play next episode automatically (with countdown)' },
        { value: 'button', label: 'Show "Skip Credits" button' },
        { value: 'off', label: 'Off' },
      ],
    },
    { key: 'si_preview', type: 'select', label: 'Next-episode previews', description: 'Shows a "Skip Preview" button for post-credit previews.', defaultValue: 'button', options: MODE_OPTIONS },
    {
      key: 'si_countdown', type: 'select', label: 'Auto-skip countdown', description: 'How long the button fills before an automatic skip.', defaultValue: '5',
      options: [{ value: '0', label: 'Instant' }, { value: '3', label: '3 seconds' }, { value: '5', label: '5 seconds' }, { value: '10', label: '10 seconds' }],
    },
    { key: 'si_shortcut', type: 'toggle', label: 'Enter key skips', description: 'Press Enter to press the visible skip button.', defaultValue: true },
    { key: 'si_aniskip', type: 'toggle', label: 'Use AniSkip (anime)', description: 'Crowd-sourced anime opening/ending timestamps.', defaultValue: true },
    { key: 'si_tidb', type: 'toggle', label: 'Use TheIntroDB (TV, movies, anime)', description: 'Community intro, recap, credits and preview timestamps.', defaultValue: true },
    { key: 'si_tidb_key', type: 'input', label: 'TheIntroDB API key (optional)', description: 'Only needed for higher rate limits.', defaultValue: '' },
  ];

  const DEFAULTS = Object.fromEntries(SETTINGS_SCHEMA.map((s) => [s.key, s.defaultValue]));
  const settings = { ...DEFAULTS };

  const seApi = () => (typeof StremioEnhancedAPI !== 'undefined' ? StremioEnhancedAPI : null);
  const asBool = (v) => v === true || v === 'true';

  function setSetting(key, value) {
    if (!(key in DEFAULTS)) return;
    settings[key] = value === undefined || value === null ? DEFAULTS[key] : value;
  }

  async function initSettings() {
    const api = seApi();
    if (api && typeof api.registerSettings === 'function' && !window.__skipIntroSettingsRegistered) {
      window.__skipIntroSettingsRegistered = true;
      try {
        await api.registerSettings(SETTINGS_SCHEMA);
      } catch (e) {
        if (!/regist/i.test(String(e && e.message))) warn('registerSettings failed', e);
      }
    }
    for (const key of Object.keys(DEFAULTS)) {
      try {
        let v;
        if (api && typeof api.getSetting === 'function') v = await api.getSetting(key);
        else {
          const raw = localStorage.getItem('skipintro.' + key);
          v = raw === null ? undefined : JSON.parse(raw);
        }
        if (v !== undefined && v !== null) setSetting(key, v);
      } catch (_) { /* keep default */ }
    }
    if (api) {
      if (typeof api.onSettingChange === 'function') {
        for (const key of Object.keys(DEFAULTS)) {
          try { api.onSettingChange(key, (v) => { setSetting(key, v); onSettingsChanged(); }); } catch (_) { /* ignore */ }
        }
      }
      if (typeof api.onSettingsSaved === 'function') {
        try {
          api.onSettingsSaved((all) => {
            if (all && typeof all === 'object') for (const k of Object.keys(DEFAULTS)) if (k in all) setSetting(k, all[k]);
            onSettingsChanged();
          });
        } catch (_) { /* ignore */ }
      }
    }
  }

  function onSettingsChanged() {
    // Sources may have changed: force a reload of the segments for the current video.
    controller.key = null;
  }

  // ---------------------------------------------------------------------------
  // Page bridge: read Stremio core state from the page's JS world.
  // Functions below are serialised with toString(), so they must not use any
  // variable from the enclosing scope.
  // ---------------------------------------------------------------------------

  // Stremio Web v5.1+ exposes the core as `window.core`; older builds as `window.services.core.transport`.
  async function pageReadPlayer(w) {
    const t = w.core && typeof w.core.getState === 'function' ? w.core : w.services && w.services.core && w.services.core.transport;
    if (!t || typeof t.getState !== 'function') return null;
    const st = await t.getState('player');
    if (!st) return null;
    const meta = st.metaItem && st.metaItem.type === 'Ready' ? st.metaItem.content : null;
    const path = st.selected && st.selected.streamRequest && st.selected.streamRequest.path;
    const videoId = path ? path.id : null;
    let absEpisode = null;
    if (meta && Array.isArray(meta.videos) && videoId) {
      const eps = meta.videos
        .filter((v) => v && typeof v.season === 'number' && v.season > 0 && typeof v.episode === 'number')
        .sort((a, b) => a.season - b.season || a.episode - b.episode);
      const i = eps.findIndex((v) => v.id === videoId);
      if (i >= 0) absEpisode = i + 1;
    }
    const si = st.seriesInfo || null;
    const nv = st.nextVideo;
    const ls = st.libraryItem && st.libraryItem.state;
    const knownDuration = ls && ls.video_id === videoId && ls.duration > 0 ? ls.duration / 1000 : null;
    return {
      videoId: videoId,
      type: (path && path.type) || (meta && meta.type) || null,
      metaId: meta ? meta.id : null,
      name: (meta && meta.name) || st.title || null,
      imdbId: (meta && (meta.imdb_id || (/^tt\d+$/.test(meta.id) ? meta.id : null))) || null,
      tmdbId: (meta && (meta.moviedb_id || meta.tmdb_id)) || null,
      season: si && typeof si.season === 'number' ? si.season : null,
      episode: si && typeof si.episode === 'number' ? si.episode : null,
      absEpisode: absEpisode,
      duration: knownDuration,
      hasNext: !!(nv && nv.deepLinks && nv.deepLinks.player),
    };
  }

  async function pagePlayNext(w) {
    const t = w.core && typeof w.core.getState === 'function' ? w.core : w.services && w.services.core && w.services.core.transport;
    if (!t || typeof t.getState !== 'function') return false;
    const st = await t.getState('player');
    const link = st && st.nextVideo && st.nextVideo.deepLinks && st.nextVideo.deepLinks.player;
    if (!link) return false;
    try { t.dispatch({ action: 'Player', args: { action: 'NextVideo' } }, 'player'); } catch (e) { /* not fatal */ }
    let hash = String(link);
    if (/^stremio:\/\/\//.test(hash)) hash = '#/' + hash.replace(/^stremio:\/\/\//, '');
    else if (hash.charAt(0) !== '#') hash = '#' + (hash.charAt(0) === '/' ? '' : '/') + hash;
    w.location.replace(hash);
    return true;
  }

  function evalInPage(fn) {
    return new Promise((resolve) => {
      const evName = 'skipintro-' + Math.random().toString(36).slice(2);
      const script = document.createElement('script');
      let timer = null;
      const done = (e) => {
        clearTimeout(timer);
        script.remove();
        try { resolve(e && typeof e.detail === 'string' ? JSON.parse(e.detail) : null); } catch (_) { resolve(null); }
      };
      timer = setTimeout(() => { window.removeEventListener(evName, done); script.remove(); resolve(null); }, 4000);
      window.addEventListener(evName, done, { once: true });
      script.textContent =
        '(async()=>{let out=null;try{out=await (' + fn.toString() + ')(window);}catch(e){console.error("[SkipIntro]",e);}' +
        'window.dispatchEvent(new CustomEvent(' + JSON.stringify(evName) + ',{detail:JSON.stringify(out===undefined?null:out)}));})();';
      (document.head || document.documentElement).appendChild(script);
    });
  }

  // Call directly when we share the page's world (Electron), otherwise inject.
  async function inPage(fn) {
    try {
      const sameWorld = (window.core && typeof window.core.getState === 'function') || (window.services && window.services.core && window.services.core.transport);
      if (sameWorld) return await fn(window);
    } catch (e) {
      warn('page call failed', e);
      return null;
    }
    return evalInPage(fn);
  }

  // ---------------------------------------------------------------------------
  // Players. Two kinds, same interface:
  //   - HTML <video> (Stremio Web in a browser, Stremio Enhanced)
  //   - mpv in the normal Stremio desktop app (stremio-shell-ng), reached through
  //     the same chrome.webview channel Stremio Web uses to drive mpv.
  // Interface: time, duration, paused (seconds / bool), key (changes per file),
  //            seek(t), and events 'time' + 'seek' via bind/unbind.
  // ---------------------------------------------------------------------------

  function createHtmlPlayer(video) {
    return {
      kind: 'html',
      el: video,
      get time() { return video.currentTime; },
      get duration() { return video.duration; },
      get paused() { return video.paused; },
      get key() { return video.currentSrc || video.src || ''; },
      seek(t) { video.currentTime = t; },
      bind(onTime, onSeek) {
        video.addEventListener('timeupdate', onTime);
        video.addEventListener('seeking', onSeek);
      },
      unbind(onTime, onSeek) {
        video.removeEventListener('timeupdate', onTime);
        video.removeEventListener('seeking', onSeek);
      },
    };
  }

  const MpvBridge = {
    props: { 'time-pos': null, duration: null, pause: null, path: null },
    listening: false,
    hooked: false,
    template: null, // a real outgoing 'mpv-set-prop' message, reused as the wire format for seeking
    handlers: { time: new Set(), seek: new Set() },
    debugSeen: new Set(),

    available() {
      return !!(window.chrome && window.chrome.webview && typeof window.chrome.webview.addEventListener === 'function');
    },

    /**
     * Stremio Web sends shell commands as
     *   chrome.webview.postMessage(JSON.stringify({ id: 0, type: 6, args: [command, params] }))
     * and receives mpv events as 'message' events on chrome.webview.
     */
    install() {
      if (!this.available()) return;
      const wv = window.chrome.webview;
      if (!this.listening) {
        this.listening = true;
        this.onMessageEvent = (ev) => this.onIncoming(ev && ev.data);
        wv.addEventListener('message', this.onMessageEvent);
      }
      if (!this.hooked) {
        let orig = wv.postMessage;
        // Recover the native function if an earlier build left postMessage broken
        // (the shell keeps its own reference in window.qt.webChannelTransport.send).
        if (typeof orig !== 'function' || orig.__skipIntroWrapper) {
          const qtSend = window.qt && window.qt.webChannelTransport && window.qt.webChannelTransport.send;
          orig = typeof orig === 'function' && orig.__skipIntroOrig ? orig.__skipIntroOrig : typeof qtSend === 'function' ? qtSend : null;
        }
        if (!orig) return;
        this.hooked = true;
        const self = this;
        this.origPost = orig;
        this.rawSend = (msg) => orig.call(wv, msg);
        const wrapper = function (msg) {
          try { self.onOutgoing(msg); } catch (_) { /* never break Stremio */ }
          return orig.apply(wv, arguments);
        };
        wrapper.__skipIntroWrapper = true;
        wrapper.__skipIntroOrig = orig;
        try { wv.postMessage = wrapper; } catch (_) { /* read-only: we can still send with the default format */ }
      }
    },

    uninstall() {
      const wv = this.available() ? window.chrome.webview : null;
      if (wv && this.onMessageEvent) wv.removeEventListener('message', this.onMessageEvent);
      // Put the original function back (never delete: postMessage is an own property of chrome.webview).
      if (wv && this.origPost && wv.postMessage && wv.postMessage.__skipIntroWrapper) {
        try { wv.postMessage = this.origPost; } catch (_) { /* ignore */ }
      }
      this.listening = false;
      this.hooked = false;
      this.handlers.time.clear();
      this.handlers.seek.clear();
    },

    parse(raw) {
      if (typeof raw !== 'string') return raw && typeof raw === 'object' ? raw : null;
      try { return JSON.parse(raw); } catch (_) { return null; }
    },

    onOutgoing(raw) {
      const m = this.parse(raw);
      if (!m || !Array.isArray(m.args)) return;
      if (DEBUG && !this.debugSeen.has('out:' + m.args[0])) {
        this.debugSeen.add('out:' + m.args[0]);
        dbg('mpv out sample', raw);
      }
      if (m.args[0] === 'mpv-set-prop') this.template = m;
    },

    onIncoming(raw) {
      const m = this.parse(raw);
      const args = m && m.args;
      if (!Array.isArray(args)) return;
      if (DEBUG && !this.debugSeen.has('in:' + args[0])) {
        this.debugSeen.add('in:' + args[0]);
        dbg('mpv in sample', typeof raw === 'string' ? raw.slice(0, 600) : raw);
      }
      if (args[0] !== 'mpv-prop-change' || !args[1] || !(args[1].name in this.props)) return;
      const name = args[1].name;
      const data = args[1].data;
      const prev = this.props[name];
      this.props[name] = data;
      if (name === 'path' && data !== prev) {
        this.props['time-pos'] = null;
        this.props.duration = null;
      }
      if (name === 'time-pos' && typeof data === 'number') {
        // A jump of more than a few seconds is a seek (by the user or by us).
        if (typeof prev === 'number' && Math.abs(data - prev) > 3) this.emit('seek');
        this.emit('time');
      }
    },

    emit(type) {
      for (const fn of this.handlers[type]) {
        try { fn(); } catch (e) { warn('handler failed', e); }
      }
    },

    setProp(name, value) {
      // Same shape as Stremio Web's own messages (copied from a real one when we have seen it).
      const base = this.template ? JSON.parse(JSON.stringify(this.template)) : { id: 0, type: 6 };
      base.args = ['mpv-set-prop', [name, value]];
      const msg = JSON.stringify(base);
      if (this.rawSend) this.rawSend(msg);
      else window.chrome.webview.postMessage(msg);
      dbg('mpv set', name, value);
    },

    hasMedia() {
      return !!this.props.path && typeof this.props.duration === 'number' && this.props.duration > 0;
    },
  };

  /** Total time shown at the end of Stremio's seek bar ("00:24:30"), in seconds; NaN if not shown. */
  function readSeekBarDuration() {
    const labels = document.querySelectorAll('[class*="seek-bar"] [class*="label"]');
    const last = labels.length ? labels[labels.length - 1].textContent.trim() : '';
    if (!/^\d+(:\d{1,2}){1,2}$/.test(last)) return NaN;
    const secs = last.split(':').reduce((acc, p) => acc * 60 + Number(p), 0);
    return secs > 0 ? secs : NaN;
  }

  const mpvPlayer = {
    kind: 'mpv',
    // Used when the plugin was injected after mpv reported the duration (Stremio's own record).
    fallbackDuration: null,
    get time() { return typeof MpvBridge.props['time-pos'] === 'number' ? MpvBridge.props['time-pos'] : 0; },
    get duration() {
      if (typeof MpvBridge.props.duration === 'number' && MpvBridge.props.duration > 0) return MpvBridge.props.duration;
      if (this.fallbackDuration > 0) return this.fallbackDuration;
      return readSeekBarDuration();
    },
    get paused() { return MpvBridge.props.pause === true; },
    get key() { return String(MpvBridge.props.path || ''); },
    seek(t) {
      MpvBridge.setProp('time-pos', t);
      MpvBridge.props['time-pos'] = t; // optimistic, mpv confirms shortly
    },
    bind(onTime, onSeek) {
      MpvBridge.handlers.time.add(onTime);
      MpvBridge.handlers.seek.add(onSeek);
    },
    unbind(onTime, onSeek) {
      MpvBridge.handlers.time.delete(onTime);
      MpvBridge.handlers.seek.delete(onSeek);
    },
  };

  // ---------------------------------------------------------------------------
  // HTTP helpers
  // ---------------------------------------------------------------------------

  async function fetchJson(url, init, timeoutMs) {
    const ctrl = typeof AbortController !== 'undefined' ? new AbortController() : null;
    const timer = ctrl ? setTimeout(() => ctrl.abort(), timeoutMs || 10000) : null;
    try {
      const res = await fetch(url, Object.assign({}, init, ctrl ? { signal: ctrl.signal } : {}));
      let json = null;
      if (res.status !== 204) json = await res.json().catch(() => null);
      return { status: res.status, json };
    } catch (e) {
      return { status: 0, json: null, error: e };
    } finally {
      if (timer) clearTimeout(timer);
    }
  }

  // Text cached in the Cache API with a TTL; a stale copy is used if refreshing fails.
  async function getCachedText(url, ttlMs) {
    let cache = null;
    try { cache = await caches.open(CACHE_NAME); } catch (_) { /* Cache API unavailable */ }
    let stale = null;
    if (cache) {
      try {
        const hit = await cache.match(url);
        if (hit) {
          const ts = Number(hit.headers.get('x-si-time')) || 0;
          const text = await hit.text();
          if (Date.now() - ts < ttlMs) return text;
          stale = text;
        }
      } catch (_) { /* ignore */ }
    }
    try {
      const res = await fetch(url);
      if (!res.ok) throw new Error('HTTP ' + res.status);
      const text = await res.text();
      if (cache) {
        try {
          await cache.put(url, new Response(text, { headers: { 'content-type': 'application/json', 'x-si-time': String(Date.now()) } }));
        } catch (_) { /* quota etc. */ }
      }
      return text;
    } catch (e) {
      if (stale) return stale;
      throw e;
    }
  }

  // ---------------------------------------------------------------------------
  // Video id parsing
  // ---------------------------------------------------------------------------

  const toInt = (v) => {
    const n = parseInt(v, 10);
    return Number.isFinite(n) ? n : null;
  };

  /**
   * Stremio video ids look like:
   *   tt2560140:3:13      (Cinemeta series: imdb:season:episode)
   *   tt0816692           (movie)
   *   kitsu:7442:5        (Anime Kitsu addon: kitsu:id:episode)
   *   mal:16498:5 / anilist:16498:5
   *   tmdb:1429:1:5       (TMDB addon)
   */
  function parseVideoId(videoId, info) {
    const p = String(videoId || '').split(':');
    const out = { imdb: null, tmdb: null, kitsu: null, mal: null, anilist: null, season: null, episode: null };
    if (/^tt\d+$/.test(p[0])) {
      out.imdb = p[0];
      out.season = toInt(p[1]);
      out.episode = toInt(p[2]);
    } else if (['kitsu', 'mal', 'anilist'].includes(p[0])) {
      out[p[0]] = toInt(p[1]);
      if (p.length >= 4) { out.season = toInt(p[2]); out.episode = toInt(p[3]); } else { out.episode = toInt(p[2]); }
    } else if (p[0] === 'tmdb') {
      out.tmdb = toInt(p[1]);
      out.season = toInt(p[2]);
      out.episode = toInt(p[3]);
    }
    if (info) {
      if (!out.imdb && !out.kitsu && !out.mal && !out.anilist && info.imdbId) out.imdb = info.imdbId;
      if (!out.tmdb && info.tmdbId && !out.imdb) out.tmdb = toInt(info.tmdbId);
      if (out.episode === null && typeof info.episode === 'number') {
        out.season = info.season;
        out.episode = info.episode;
      }
    }
    out.isSeries = out.episode !== null;
    out.absEpisode = (info && info.absEpisode) || null;
    return out;
  }

  // ---------------------------------------------------------------------------
  // Anime id mapping (Fribb/anime-lists): IMDb/TVDB season+episode, Kitsu,
  // AniList -> MyAnimeList id + episode number used by AniSkip.
  // ---------------------------------------------------------------------------

  function buildAnimeIndex(list) {
    const idx = { byImdb: new Map(), byTvdb: new Map(), byKitsu: new Map(), byMal: new Map(), byAnilist: new Map(), size: 0 };
    const push = (map, key, rec) => {
      if (key === null || key === undefined) return;
      const arr = map.get(key);
      if (arr) arr.push(rec); else map.set(key, [rec]);
    };
    for (const e of list) {
      if (!e || (!e.mal_id && !e.tvdb_id)) continue;
      const rec = {
        mal: e.mal_id || null,
        tvdb: e.tvdb_id || null,
        season: e.season && typeof e.season.tvdb === 'number' ? e.season.tvdb : null,
        offset: e.episode_offset && typeof e.episode_offset.tvdb === 'number' ? e.episode_offset.tvdb : 0,
        movie: e.type === 'MOVIE',
      };
      const imdbs = Array.isArray(e.imdb_id) ? e.imdb_id : e.imdb_id ? [e.imdb_id] : [];
      for (const tt of imdbs) push(idx.byImdb, tt, rec);
      push(idx.byTvdb, rec.tvdb, rec);
      if (e.kitsu_id && !idx.byKitsu.has(e.kitsu_id)) idx.byKitsu.set(e.kitsu_id, rec);
      if (e.mal_id && !idx.byMal.has(e.mal_id)) idx.byMal.set(e.mal_id, rec);
      if (e.anilist_id && !idx.byAnilist.has(e.anilist_id)) idx.byAnilist.set(e.anilist_id, rec);
      idx.size++;
    }
    return idx;
  }

  /**
   * Returns { mal, ep, tvdb, tvdbSeason, tvdbEpisode } or null when the title is not a known anime.
   * `tvdb*` fields are filled when we can translate an anime-native id to TVDB numbering (for TheIntroDB).
   */
  function resolveAnime(idx, q) {
    if (!idx) return null;

    // Anime-native ids: one entry per season/cour, episode numbering already local.
    const native = (q.kitsu && idx.byKitsu.get(q.kitsu)) || (q.mal && idx.byMal.get(q.mal)) || (q.anilist && idx.byAnilist.get(q.anilist));
    if (native || q.mal) {
      const rec = native || { mal: q.mal, tvdb: null, season: null, offset: 0, movie: false };
      const ep = q.episode || 1;
      const out = { mal: rec.mal || q.mal || null, ep, tvdb: null, tvdbSeason: null, tvdbEpisode: null };
      if (rec.tvdb && rec.season !== null && rec.season > 0) {
        out.tvdb = rec.tvdb;
        out.tvdbSeason = rec.season;
        out.tvdbEpisode = ep + rec.offset;
      }
      return out.mal ? out : null;
    }

    if (!q.imdb) return null;
    const direct = idx.byImdb.get(q.imdb) || [];

    if (!q.isSeries) {
      const m = direct.find((r) => r.movie && r.mal) || (direct.length === 1 && direct[0].mal ? direct[0] : null);
      return m ? { mal: m.mal, ep: 1, tvdb: null, tvdbSeason: null, tvdbEpisode: null } : null;
    }

    // Series: gather every entry that belongs to the same TVDB show.
    const pool = new Set(direct.filter((r) => !r.movie));
    for (const r of direct) {
      if (r.movie || !r.tvdb) continue;
      for (const s of idx.byTvdb.get(r.tvdb) || []) if (!s.movie) pool.add(s);
    }
    const entries = [...pool].filter((r) => r.mal);
    if (!entries.length) return null;

    const S = q.season;
    const E = q.episode;
    if (S !== null && S > 0) {
      let best = null;
      for (const r of entries) {
        if (r.season === S && r.offset < E && (!best || r.offset > best.offset)) best = r;
      }
      if (best) return { mal: best.mal, ep: E - best.offset, tvdb: null, tvdbSeason: null, tvdbEpisode: null };
    }

    // Long-running shows with absolute numbering (One Piece, Detective Conan...).
    const absolute = entries.filter((r) => r.season === null);
    if (absolute.length === 1) {
      const ep = q.absEpisode || (S === 1 ? E : null);
      if (ep) return { mal: absolute[0].mal, ep, tvdb: null, tvdbSeason: null, tvdbEpisode: null };
    }
    return null;
  }

  const AnimeMap = {
    promise: null,
    index: null,
    load() {
      if (!this.promise) {
        this.promise = getCachedText(ANIME_LIST_URL, ANIME_LIST_TTL_MS)
          .then((text) => {
            this.index = buildAnimeIndex(JSON.parse(text));
            log('anime map ready:', this.index.size, 'entries');
            return this.index;
          })
          .catch((e) => {
            warn('anime map unavailable', e);
            this.promise = null;
            return null;
          });
      }
      return this.promise;
    },
  };

  // ---------------------------------------------------------------------------
  // Timestamp sources -> segments { type: intro|recap|outro|preview, start, end, source }
  // ---------------------------------------------------------------------------

  const ANISKIP_TYPES = { op: 'intro', 'mixed-op': 'intro', ed: 'outro', 'mixed-ed': 'outro', recap: 'recap' };
  const ANISKIP_RANK = { op: 0, ed: 0, recap: 0, 'mixed-op': 1, 'mixed-ed': 1 };

  async function fetchAniSkip(mal, ep, duration) {
    const types = Object.keys(ANISKIP_TYPES).map((t) => 'types=' + encodeURIComponent(t)).join('&');
    const base = ANISKIP_API + '/skip-times/' + mal + '/' + ep + '?' + types + '&episodeLength=';

    let res = await fetchJson(base + duration.toFixed(3));
    let results = res.json && res.json.found ? res.json.results : null;
    if (!results) {
      // Nothing for this exact length: take any release within tolerance.
      res = await fetchJson(base + '0');
      results = (res.json && res.json.found ? res.json.results : []).filter(
        (r) => !r.episodeLength || Math.abs(r.episodeLength - duration) <= ANISKIP_LENGTH_TOLERANCE_S,
      );
    }
    if (!results || !results.length) return [];

    // Best result per segment type: prefer pure op/ed over mixed, then closest episode length.
    const best = {};
    for (const r of results) {
      const type = ANISKIP_TYPES[r.skipType];
      if (!type || !r.interval) continue;
      const score = ANISKIP_RANK[r.skipType] * 1000 + (r.episodeLength ? Math.abs(r.episodeLength - duration) : 500);
      if (!best[type] || score < best[type].score) best[type] = { score, r };
    }
    return Object.entries(best).map(([type, { r }]) => ({
      type,
      start: r.interval.startTime,
      end: r.interval.endTime,
      source: 'AniSkip',
    }));
  }

  async function fetchTheIntroDB(params, duration) {
    const qs = new URLSearchParams();
    for (const [k, v] of Object.entries(params)) if (v !== null && v !== undefined) qs.set(k, String(v));
    qs.set('duration_ms', String(Math.round(duration * 1000)));
    const headers = { Accept: 'application/json' };
    const key = String(settings.si_tidb_key || '').trim();
    if (key) headers.Authorization = 'Bearer ' + key;
    const res = await fetchJson(TIDB_API + '/media?' + qs.toString(), { headers });
    if (res.status === 429) warn('TheIntroDB rate limit reached', res.json && res.json.code);
    if (res.status !== 200 || !res.json) return [];
    const map = { intro: 'intro', recap: 'recap', credits: 'outro', preview: 'preview' };
    const out = [];
    for (const [field, type] of Object.entries(map)) {
      for (const s of res.json[field] || []) {
        if (!s) continue;
        const start = s.start_ms === null || s.start_ms === undefined ? 0 : s.start_ms / 1000;
        const end = s.end_ms === null || s.end_ms === undefined ? duration : s.end_ms / 1000;
        if (start === 0 && end === 0) continue;
        out.push({ type, start, end, source: 'TheIntroDB' });
      }
    }
    return out;
  }

  /** Pick one segment per type by source priority, clean them up and link outro -> preview. */
  function mergeSegments(lists, duration) {
    const chosen = {};
    for (const list of lists) {
      for (const s of list) {
        if (chosen[s.type]) continue;
        const start = Math.max(0, Math.min(s.start, duration));
        const end = Math.max(0, Math.min(s.end, duration));
        if (end - start < MIN_SEGMENT_S) continue;
        chosen[s.type] = { type: s.type, start, end, source: s.source };
      }
    }
    const segs = Object.values(chosen).sort((a, b) => a.start - b.start);
    for (const s of segs) s.skipTo = s.end;
    // Credits followed directly by a preview: skipping credits also skips the preview.
    const outro = chosen.outro;
    const preview = chosen.preview;
    if (outro && preview && preview.start >= outro.start && preview.start - outro.end <= 2) outro.skipTo = Math.max(outro.end, preview.end);
    for (const s of segs) s.isTail = s.skipTo >= duration - 3;
    return segs;
  }

  async function resolveSegments(info, duration) {
    const q = parseVideoId(info.videoId, info);
    const useAni = asBool(settings.si_aniskip);
    const useTidb = asBool(settings.si_tidb);
    const isAnimeNative = !!(q.kitsu || q.mal || q.anilist);

    // TheIntroDB with ids we already have (in parallel with the anime map download).
    let tidbParams = null;
    if (q.imdb) tidbParams = q.isSeries ? { imdb_id: q.imdb, season: q.season, episode: q.episode } : { imdb_id: q.imdb };
    else if (q.tmdb) tidbParams = q.isSeries ? { tmdb_id: q.tmdb, season: q.season, episode: q.episode } : { tmdb_id: q.tmdb };
    const tidbPromise = useTidb && tidbParams ? fetchTheIntroDB(tidbParams, duration) : Promise.resolve([]);

    let anime = null;
    if (useAni || (useTidb && isAnimeNative)) {
      const idx = await AnimeMap.load();
      anime = resolveAnime(idx, q);
    }

    const aniPromise = useAni && anime && anime.mal && anime.ep ? fetchAniSkip(anime.mal, anime.ep, duration) : Promise.resolve([]);
    let tidbAnimePromise = Promise.resolve([]);
    if (useTidb && !tidbParams && anime && anime.tvdb) {
      tidbAnimePromise = fetchTheIntroDB({ tvdb_id: anime.tvdb, season: anime.tvdbSeason, episode: anime.tvdbEpisode }, duration);
    }

    const [ani, tidb, tidbAnime] = await Promise.all([aniPromise, tidbPromise, tidbAnimePromise]);
    const segments = mergeSegments([ani, tidb, tidbAnime], duration);
    return { segments, ids: q, anime };
  }

  // ---------------------------------------------------------------------------
  // Netflix-style UI
  // ---------------------------------------------------------------------------

  const STYLE = `
#skipintro-root{position:absolute;right:4.5%;bottom:4.5rem;z-index:2147483000;display:flex;gap:.8em;align-items:center;
  pointer-events:none;opacity:0;transform:translateY(10px);transition:opacity .25s ease,transform .25s ease,bottom .2s ease;
  font-family:"Netflix Sans","Helvetica Neue","Segoe UI",Roboto,Ubuntu,Arial,sans-serif;font-size:clamp(14px,1.15vw,22px)}
#skipintro-root.si-fixed{position:fixed}
#skipintro-root.si-visible{opacity:1;transform:none;pointer-events:auto}
#skipintro-root.si-raised{bottom:9rem}
#skipintro-root .si-btn{position:relative;overflow:hidden;display:inline-flex;align-items:center;gap:.55em;margin:0;padding:.75em 1.5em;
  border-radius:4px;font:inherit;font-weight:600;letter-spacing:.01em;line-height:1;cursor:pointer;white-space:nowrap;
  -webkit-font-smoothing:antialiased;box-shadow:0 2px 12px rgba(0,0,0,.35);
  transition:background-color .15s ease,color .15s ease,border-color .15s ease,transform .1s ease}
#skipintro-root .si-btn:active{transform:scale(.96)}
#skipintro-root .si-ghost{color:#fff;background:rgba(42,42,42,.6);border:2px solid rgba(255,255,255,.75)}
#skipintro-root .si-ghost:hover,#skipintro-root .si-ghost:focus-visible{background:#fff;color:#141414;border-color:#fff;outline:none}
#skipintro-root .si-primary{color:#141414;background:#fff;border:2px solid #fff}
#skipintro-root .si-primary:hover,#skipintro-root .si-primary:focus-visible{background:rgba(255,255,255,.82);border-color:rgba(255,255,255,.82);outline:none}
#skipintro-root .si-fill{position:absolute;left:0;top:0;bottom:0;width:0;pointer-events:none}
#skipintro-root .si-ghost .si-fill{background:rgba(255,255,255,.3)}
#skipintro-root .si-ghost:hover .si-fill{background:rgba(0,0,0,.12)}
#skipintro-root .si-primary .si-fill{background:rgba(0,0,0,.16)}
#skipintro-root .si-label,#skipintro-root .si-icon{position:relative;z-index:1}
#skipintro-root .si-icon{width:1.05em;height:1.05em;flex:none}
#skipintro-toast{position:absolute;left:50%;top:12%;z-index:2147483000;transform:translate(-50%,-6px);opacity:0;pointer-events:none;
  padding:.55em 1.1em;border-radius:4px;background:rgba(20,20,20,.78);color:#fff;font:600 clamp(13px,1vw,18px) "Netflix Sans","Helvetica Neue","Segoe UI",Roboto,Arial,sans-serif;
  transition:opacity .25s ease,transform .25s ease}
#skipintro-toast.si-fixed{position:fixed}
#skipintro-toast.si-visible{opacity:1;transform:translate(-50%,0)}
`;

  const ICONS = {
    play: '<svg class="si-icon" viewBox="0 0 24 24" aria-hidden="true"><path fill="currentColor" d="M6 4l15 8-15 8z"/></svg>',
    skip: '<svg class="si-icon" viewBox="0 0 24 24" aria-hidden="true"><path fill="currentColor" d="M4 5l9 7-9 7zM13 5l9 7-9 7z"/></svg>',
  };

  const ui = {
    root: null,
    toastEl: null,
    renderedId: null,
    countdownTimer: null,
    toastTimer: null,
    primary: null,

    ensure(container) {
      if (!document.getElementById('skipintro-style')) {
        const st = document.createElement('style');
        st.id = 'skipintro-style';
        st.textContent = STYLE;
        (document.head || document.documentElement).appendChild(st);
      }
      const host = container || document.body;
      if (!this.root) {
        this.root = document.createElement('div');
        this.root.id = 'skipintro-root';
        this.root.setAttribute('role', 'group');
        this.root.setAttribute('aria-label', 'Skip');
        // Keep clicks from reaching the player (which would toggle pause).
        for (const ev of ['click', 'mousedown', 'mouseup', 'dblclick', 'pointerdown']) this.root.addEventListener(ev, (e) => e.stopPropagation());
      }
      if (!this.toastEl) {
        this.toastEl = document.createElement('div');
        this.toastEl.id = 'skipintro-toast';
      }
      for (const el of [this.root, this.toastEl]) {
        if (el.parentNode !== host) host.appendChild(el);
        el.classList.toggle('si-fixed', !container);
      }
    },

    /** spec: { id, buttons: [{ label, kind: 'ghost'|'primary', icon, countdown, onClick }] } */
    show(spec, container) {
      this.ensure(container);
      if (this.renderedId !== spec.id) {
        this.clearCountdown();
        this.root.textContent = '';
        this.primary = null;
        for (const b of spec.buttons) {
          const btn = document.createElement('button');
          btn.type = 'button';
          btn.className = 'si-btn ' + (b.kind === 'primary' ? 'si-primary' : 'si-ghost');
          btn.innerHTML = '<span class="si-fill"></span>' + (b.icon ? ICONS[b.icon] : '') + '<span class="si-label"></span>';
          btn.querySelector('.si-label').textContent = b.label;
          btn.addEventListener('click', (e) => {
            e.preventDefault();
            this.clearCountdown();
            b.onClick();
          });
          this.root.appendChild(btn);
          if (b.isDefault || !this.primary) this.primary = btn;
          if (b.countdown !== undefined && b.countdown !== null) this.startCountdown(btn, b.countdown, b.onClick);
        }
        this.renderedId = spec.id;
      }
      this.root.classList.add('si-visible');
    },

    startCountdown(btn, seconds, action) {
      const fill = btn.querySelector('.si-fill');
      fill.style.transition = 'none';
      fill.style.width = '0%';
      void fill.offsetWidth; // restart transition
      fill.style.transition = 'width ' + seconds + 's linear';
      fill.style.width = '100%';
      this.countdownTimer = setTimeout(() => {
        this.countdownTimer = null;
        action();
      }, seconds * 1000);
    },

    hasCountdown() {
      return this.countdownTimer !== null;
    },

    clearCountdown() {
      if (this.countdownTimer) clearTimeout(this.countdownTimer);
      this.countdownTimer = null;
      if (this.root) {
        for (const f of this.root.querySelectorAll('.si-fill')) {
          f.style.transition = 'none';
          f.style.width = '0%';
        }
      }
    },

    setRaised(raised) {
      if (this.root) this.root.classList.toggle('si-raised', !!raised);
    },

    isVisible() {
      return !!(this.root && this.root.classList.contains('si-visible') && this.root.isConnected);
    },

    hide() {
      this.clearCountdown();
      this.renderedId = null;
      this.primary = null;
      if (this.root) this.root.classList.remove('si-visible');
    },

    toast(text, container) {
      this.ensure(container);
      this.toastEl.textContent = text;
      this.toastEl.classList.add('si-visible');
      clearTimeout(this.toastTimer);
      this.toastTimer = setTimeout(() => this.toastEl.classList.remove('si-visible'), 2200);
    },
  };

  // ---------------------------------------------------------------------------
  // Controller: follows the player, loads segments, drives the UI.
  // ---------------------------------------------------------------------------

  const LABELS = { intro: 'Skip Intro', recap: 'Skip Recap', outro: 'Skip Credits', preview: 'Skip Preview' };
  const SKIPPED = { intro: 'Intro skipped', recap: 'Recap skipped', outro: 'Credits skipped', preview: 'Preview skipped' };
  const MODE_KEY = { intro: 'si_intro', recap: 'si_recap', outro: 'si_outro', preview: 'si_preview' };

  const controller = {
    video: null, // current player (see createHtmlPlayer / mpvPlayer)
    key: null,
    info: null,
    reqId: 0,
    loading: false,
    segments: [],
    lastResult: null,
    lastInfoPoll: 0,
    infoPolling: false,
    handled: new Set(), // segments already skipped/auto-skipped (no auto-skip again)
    dismissed: new Set(), // "Watch Credits" pressed
    autoCancelled: new Set(), // countdown interrupted (pause / seek)

    findContainer() {
      return document.querySelector('[class*="player-container"]');
    },

    findPlayer() {
      const c = this.findContainer();
      const el = (c && c.querySelector('video')) || document.querySelector('video');
      if (el) return this.video && this.video.el === el ? this.video : createHtmlPlayer(el);
      MpvBridge.install();
      if (MpvBridge.available() && (MpvBridge.props.path || typeof MpvBridge.props['time-pos'] === 'number')) return mpvPlayer;
      return null;
    },

    onPlayerRoute() {
      return /^#\/player\//.test(location.hash) || !!this.findContainer();
    },

    reset() {
      this.reqId++;
      this.key = null;
      this.info = null;
      this.segments = [];
      this.lastResult = null;
      this.loading = false;
      this.handled.clear();
      this.dismissed.clear();
      this.autoCancelled.clear();
      ui.hide();
    },

    attach(player) {
      if (this.video) this.video.unbind(this.onTime, this.onSeek);
      this.video = player;
      if (player) player.bind(this.onTime, this.onSeek);
    },

    onTime: () => controller.update(),
    onSeek: () => {
      // Seeking during a countdown cancels the auto action for that segment.
      if (ui.hasCountdown() && ui.renderedId) {
        controller.autoCancelled.add(ui.renderedId.split('#')[0]);
        ui.hide();
      }
      controller.update();
    },

    tick() {
      const onRoute = this.onPlayerRoute();
      const video = onRoute ? this.findPlayer() : null;
      if (!video) {
        if (this.key || this.video) {
          this.reset();
          this.attach(null);
        }
        if (!onRoute) MpvBridge.install();
        return;
      }
      if (video !== this.video) this.attach(video);
      const now = Date.now();
      if (now - this.lastInfoPoll > INFO_POLL_MS && !this.infoPolling) {
        this.lastInfoPoll = now;
        this.pollInfo();
      }
      this.update();
    },

    async pollInfo() {
      this.infoPolling = true;
      try {
        const info = await inPage(pageReadPlayer);
        if (!info || !info.videoId) return;
        const video = this.video;
        const key = info.videoId + '|' + (video ? video.key : '');
        if (this.info) this.info.hasNext = info.hasNext;
        if (video && video.kind === 'mpv') mpvPlayer.fallbackDuration = info.duration;
        if (key === this.key) return;
        const duration = video ? video.duration : NaN;
        if (!Number.isFinite(duration) || duration <= 0) return; // wait for metadata
        this.reset();
        this.key = key;
        this.info = info;
        this.load(info, duration);
      } finally {
        this.infoPolling = false;
      }
    },

    async load(info, duration) {
      const req = ++this.reqId;
      this.loading = true;
      try {
        const result = await resolveSegments(info, duration);
        if (req !== this.reqId) return;
        this.segments = result.segments;
        this.lastResult = result;
        log('segments for', info.name || info.videoId, info.videoId, result.anime ? '(MAL ' + result.anime.mal + ' ep ' + result.anime.ep + ')' : '', result.segments);
      } catch (e) {
        warn('failed to load segments', e);
      } finally {
        if (req === this.reqId) this.loading = false;
      }
    },

    activeSegment(t) {
      return this.segments.find((s) => t >= s.start - 0.05 && t < s.end - 0.75) || null;
    },

    update() {
      const v = this.video;
      if (!v || !this.segments.length || !Number.isFinite(v.duration)) {
        ui.hide();
        return;
      }
      const container = this.findContainer();
      const seg = this.activeSegment(v.time);
      if (!seg) {
        ui.hide();
        return;
      }
      const id = seg.type + '@' + seg.start.toFixed(1);
      const spec = this.buildSpec(seg, id);
      if (!spec) {
        ui.hide();
        return;
      }
      // Pausing during a countdown cancels the automatic action (button stays).
      if (v.paused && spec.auto) {
        this.autoCancelled.add(id);
        return this.update();
      }
      if (spec.instant) {
        spec.instant();
        return;
      }
      ui.show(spec, container);
      ui.setRaised(container ? !/overlayHidden/.test(container.className) : false);
    },

    buildSpec(seg, id) {
      if (this.dismissed.has(id)) return null;
      const mode = String(settings[MODE_KEY[seg.type]] || 'off');
      if (mode === 'off') return null;

      const countdown = Math.max(0, Number(settings.si_countdown) || 0);
      const canAuto = !this.handled.has(id) && !this.autoCancelled.has(id);
      const hasNext = !!(this.info && this.info.hasNext);

      // End of the episode: Netflix-style "Watch Credits" / "Skip Credits" + "Next Episode".
      const outroMode = String(settings.si_outro || 'off');
      const nextMode = hasNext && (outroMode === 'next' || outroMode === 'auto');
      if (nextMode && (seg.type === 'outro' || (seg.type === 'preview' && seg.isTail))) {
        const auto = outroMode === 'auto' && canAuto;
        const next = () => this.playNext(id);
        if (seg.isTail) {
          // Credits run to the end: the next episode is the natural continuation.
          if (auto && countdown === 0) return { id, instant: next };
          return {
            id: id + (auto ? '#auto' : ''),
            auto,
            buttons: [
              { label: 'Watch Credits', kind: 'ghost', onClick: () => this.dismiss(id) },
              { label: 'Next Episode', kind: 'primary', icon: 'play', isDefault: true, countdown: auto ? countdown : null, onClick: next },
            ],
          };
        }
        // Something follows the credits (post-credits scene, anime preview): auto mode only
        // skips the credits so that scene is not lost; "Next Episode" stays one click away.
        const skipCredits = () => this.skip(seg, id, auto);
        if (auto && countdown === 0) return { id, instant: skipCredits };
        return {
          id: id + (auto ? '#auto' : ''),
          auto,
          buttons: [
            { label: 'Skip Credits', kind: 'ghost', countdown: auto ? countdown : null, onClick: skipCredits },
            { label: 'Next Episode', kind: 'primary', icon: 'play', isDefault: true, onClick: next },
          ],
        };
      }

      const auto = mode === 'auto' && canAuto;
      const skip = () => this.skip(seg, id, auto);
      if (auto && countdown === 0) return { id, instant: () => this.skip(seg, id, true) };
      return {
        id: id + (auto ? '#auto' : ''),
        auto,
        buttons: [{ label: LABELS[seg.type], kind: 'ghost', icon: null, countdown: auto ? countdown : null, onClick: skip }],
      };
    },

    skip(seg, id, wasAuto) {
      const v = this.video;
      if (!v) return;
      this.handled.add(id);
      ui.hide();
      const target = Math.min(seg.skipTo, v.duration - 0.25);
      v.seek(target);
      if (wasAuto) ui.toast(SKIPPED[seg.type], this.findContainer());
      log('skipped', seg.type, 'to', target.toFixed(1), '(' + seg.source + ')');
    },

    dismiss(id) {
      this.dismissed.add(id);
      // Also dismiss the preview that follows the credits.
      for (const s of this.segments) if (s.type === 'preview' && s.isTail) this.dismissed.add(s.type + '@' + s.start.toFixed(1));
      ui.hide();
    },

    async playNext(id) {
      this.handled.add(id);
      ui.hide();
      const ok = await inPage(pagePlayNext);
      if (!ok && this.video) {
        // Fallback: jump to the end and let Stremio handle "ended".
        this.video.seek(Math.max(0, this.video.duration - 0.25));
      }
    },
  };

  // Enter = press the visible button.
  const onKeyDown = (e) => {
    if (e.key !== 'Enter' || !asBool(settings.si_shortcut) || !ui.isVisible() || !ui.primary) return;
    const t = e.target;
    if (t && (t.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(t.tagName))) return;
    e.preventDefault();
    e.stopPropagation();
    ui.primary.click();
  };
  document.addEventListener('keydown', onKeyDown, true);

  const timers = [];

  /** Removes everything this plugin added, so a newer version can be injected without a page reload. */
  function destroy() {
    for (const id of timers) clearInterval(id);
    timers.length = 0;
    document.removeEventListener('keydown', onKeyDown, true);
    controller.reset();
    controller.attach(null);
    MpvBridge.uninstall();
    for (const id of ['skipintro-root', 'skipintro-toast', 'skipintro-style']) {
      const el = document.getElementById(id);
      if (el) el.remove();
    }
    delete window.__skipIntroLoaded;
    delete window.SkipIntro;
    log('destroyed');
  }

  // ---------------------------------------------------------------------------
  // Boot
  // ---------------------------------------------------------------------------

  window.SkipIntro = {
    version: VERSION,
    build: window.__SKIPINTRO_BUILD || null,
    settings,
    controller,
    destroy,
    status: () => ({
      videoId: controller.info && controller.info.videoId,
      name: controller.info && controller.info.name,
      loading: controller.loading,
      player: controller.video && controller.video.kind,
      time: controller.video ? Math.round(controller.video.time) : null,
      mpv: MpvBridge.available() ? { hooked: MpvBridge.hooked, template: !!MpvBridge.template, props: MpvBridge.props } : null,
      anime: controller.lastResult && controller.lastResult.anime,
      segments: controller.segments,
    }),
    // Exposed for debugging and tests.
    _internals: { MpvBridge, parseVideoId, buildAnimeIndex, resolveAnime, mergeSegments, resolveSegments, fetchAniSkip, fetchTheIntroDB, AnimeMap },
  };

  initSettings()
    .catch((e) => warn('settings init failed', e))
    .finally(() => {
      if (!window.__skipIntroLoaded) return; // destroyed before settings finished loading
      timers.push(setInterval(() => {
        try { controller.tick(); } catch (e) { warn('tick failed', e); }
      }, TICK_MS));
      if (DEBUG) {
        timers.push(setInterval(() => {
          if (controller.onPlayerRoute() && window.SkipIntro) dbg('status', window.SkipIntro.status());
        }, 10000));
      }
      // Warm the anime map in the background so the first episode is quick.
      if (asBool(settings.si_aniskip)) setTimeout(() => AnimeMap.load(), 3000);
      log('v' + VERSION + ' loaded');
    });
})();
