package io.github.osamudazai666.skipintro;

import android.content.Context;
import android.util.JsonReader;
import android.util.JsonToken;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Anime id mapping from Fribb/anime-lists: IMDb/TVDB season + episode, Kitsu, MyAnimeList and
 * AniList ids -> MyAnimeList id + episode number, which is what AniSkip needs.
 * Same rules as resolveAnime() in skip-intro.plugin.js.
 */
final class AnimeMap {
    private static final String URL = "https://raw.githubusercontent.com/Fribb/anime-lists/master/anime-list-mini.json";
    private static final long MAX_AGE_MS = 7L * 24 * 3600 * 1000;

    static final class Rec {
        int mal;
        int tvdb;
        int season = -1; // TVDB season, -1 when the entry has none (absolute numbering)
        int offset;      // TVDB episode offset
        boolean movie;
    }

    static final class Match {
        int mal;
        int ep;
        int tvdb = -1;
        int tvdbSeason = -1;
        int tvdbEpisode = -1;
    }

    private final Map<String, List<Rec>> byImdb = new HashMap<>();
    private final Map<Integer, List<Rec>> byTvdb = new HashMap<>();
    private final Map<Integer, Rec> byKitsu = new HashMap<>();
    private final Map<Integer, Rec> byMal = new HashMap<>();
    private final Map<Integer, Rec> byAnilist = new HashMap<>();
    private int size;

    private static AnimeMap instance;
    private static long failedAt;

    /** Loads (downloading at most once a week) and returns the map, or null when unavailable. Call off the main thread. */
    static synchronized AnimeMap get() {
        if (instance != null) return instance;
        if (failedAt != 0 && System.currentTimeMillis() - failedAt < 5 * 60 * 1000) return null;
        try {
            Context ctx = App.context();
            File dir = new File(ctx != null ? ctx.getCacheDir() : new File("/data/local/tmp"), "skipintro");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File file = new File(dir, "anime-list-mini.json");
            boolean fresh = file.isFile() && System.currentTimeMillis() - file.lastModified() < MAX_AGE_MS;
            if (!fresh) {
                try {
                    Http.download(URL, file, 60000);
                } catch (Exception e) {
                    if (!file.isFile()) throw e;
                    Log.w(SkipIntro.TAG, "anime list refresh failed, using the old copy", e);
                }
            }
            AnimeMap map = new AnimeMap();
            map.parse(file);
            Log.i(SkipIntro.TAG, "anime map ready: " + map.size + " entries");
            instance = map;
            return map;
        } catch (Throwable t) {
            failedAt = System.currentTimeMillis();
            Log.w(SkipIntro.TAG, "anime map unavailable", t);
            return null;
        }
    }

    private void parse(File file) throws Exception {
        try (JsonReader r = new JsonReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            r.beginArray();
            while (r.hasNext()) readEntry(r);
            r.endArray();
        }
    }

    private void readEntry(JsonReader r) throws Exception {
        Rec rec = new Rec();
        List<String> imdbs = new ArrayList<>(1);
        int kitsu = 0, anilist = 0;
        r.beginObject();
        while (r.hasNext()) {
            String name = r.nextName();
            if (r.peek() == JsonToken.NULL) {
                r.skipValue();
                continue;
            }
            switch (name) {
                case "type":
                    rec.movie = "MOVIE".equals(r.nextString());
                    break;
                case "mal_id":
                    rec.mal = readInt(r);
                    break;
                case "tvdb_id":
                    rec.tvdb = readInt(r);
                    break;
                case "kitsu_id":
                    kitsu = readInt(r);
                    break;
                case "anilist_id":
                    anilist = readInt(r);
                    break;
                case "imdb_id":
                    if (r.peek() == JsonToken.BEGIN_ARRAY) {
                        r.beginArray();
                        while (r.hasNext()) {
                            if (r.peek() == JsonToken.STRING) imdbs.add(r.nextString());
                            else r.skipValue();
                        }
                        r.endArray();
                    } else if (r.peek() == JsonToken.STRING) {
                        imdbs.add(r.nextString());
                    } else {
                        r.skipValue();
                    }
                    break;
                case "season":
                    rec.season = readTvdb(r, -1);
                    break;
                case "episode_offset":
                    rec.offset = readTvdb(r, 0);
                    break;
                default:
                    r.skipValue();
            }
        }
        r.endObject();

        if (rec.mal == 0 && rec.tvdb == 0) return;
        for (String tt : imdbs) push(byImdb, tt, rec);
        if (rec.tvdb != 0) push(byTvdb, rec.tvdb, rec);
        if (kitsu != 0 && !byKitsu.containsKey(kitsu)) byKitsu.put(kitsu, rec);
        if (rec.mal != 0 && !byMal.containsKey(rec.mal)) byMal.put(rec.mal, rec);
        if (anilist != 0 && !byAnilist.containsKey(anilist)) byAnilist.put(anilist, rec);
        size++;
    }

    /** Reads {"tvdb": n, ...} and returns n, or the fallback. */
    private static int readTvdb(JsonReader r, int fallback) throws Exception {
        if (r.peek() != JsonToken.BEGIN_OBJECT) {
            r.skipValue();
            return fallback;
        }
        int value = fallback;
        r.beginObject();
        while (r.hasNext()) {
            String name = r.nextName();
            if ("tvdb".equals(name) && r.peek() == JsonToken.NUMBER) value = r.nextInt();
            else r.skipValue();
        }
        r.endObject();
        return value;
    }

    private static int readInt(JsonReader r) throws Exception {
        if (r.peek() == JsonToken.NUMBER) {
            try {
                return r.nextInt();
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        if (r.peek() == JsonToken.STRING) {
            try {
                return Integer.parseInt(r.nextString());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        r.skipValue();
        return 0;
    }

    private static <K> void push(Map<K, List<Rec>> map, K key, Rec rec) {
        List<Rec> list = map.get(key);
        if (list == null) {
            list = new ArrayList<>(1);
            map.put(key, list);
        }
        list.add(rec);
    }

    /** Returns the MAL id + episode (and TVDB numbering when known), or null when this is not a known anime. */
    Match resolve(VideoId q) {
        Rec nativeRec = null;
        if (q.kitsu > 0) nativeRec = byKitsu.get(q.kitsu);
        if (nativeRec == null && q.mal > 0) nativeRec = byMal.get(q.mal);
        if (nativeRec == null && q.anilist > 0) nativeRec = byAnilist.get(q.anilist);
        if (nativeRec != null || q.mal > 0) {
            Match m = new Match();
            m.ep = q.episode > 0 ? q.episode : 1;
            m.mal = nativeRec != null && nativeRec.mal > 0 ? nativeRec.mal : q.mal;
            if (nativeRec != null && nativeRec.tvdb > 0 && nativeRec.season > 0) {
                m.tvdb = nativeRec.tvdb;
                m.tvdbSeason = nativeRec.season;
                m.tvdbEpisode = m.ep + nativeRec.offset;
            }
            return m.mal > 0 ? m : null;
        }

        if (q.imdb == null) return null;
        List<Rec> direct = byImdb.get(q.imdb);
        if (direct == null) return null;

        if (!q.isSeries()) {
            Rec found = null;
            for (Rec r : direct) {
                if (r.movie && r.mal > 0) {
                    found = r;
                    break;
                }
            }
            if (found == null && direct.size() == 1 && direct.get(0).mal > 0) found = direct.get(0);
            if (found == null) return null;
            Match m = new Match();
            m.mal = found.mal;
            m.ep = 1;
            return m;
        }

        // Series: every entry of the same TVDB show.
        Set<Rec> pool = new LinkedHashSet<>();
        for (Rec r : direct) if (!r.movie) pool.add(r);
        for (Rec r : direct) {
            if (r.movie || r.tvdb == 0) continue;
            List<Rec> sameShow = byTvdb.get(r.tvdb);
            if (sameShow != null) for (Rec s : sameShow) if (!s.movie) pool.add(s);
        }
        List<Rec> entries = new ArrayList<>();
        for (Rec r : pool) if (r.mal > 0) entries.add(r);
        if (entries.isEmpty()) return null;

        int s = q.season, e = q.episode;
        if (s > 0) {
            Rec best = null;
            for (Rec r : entries) {
                if (r.season == s && r.offset < e && (best == null || r.offset > best.offset)) best = r;
            }
            if (best != null) {
                Match m = new Match();
                m.mal = best.mal;
                m.ep = e - best.offset;
                return m;
            }
        }

        // Long-running shows with absolute numbering (One Piece, Detective Conan...).
        Rec absolute = null;
        int count = 0;
        for (Rec r : entries) {
            if (r.season == -1) {
                absolute = r;
                count++;
            }
        }
        if (count == 1) {
            int ep = q.absEpisode > 0 ? q.absEpisode : (s == 1 ? e : -1);
            if (ep > 0) {
                Match m = new Match();
                m.mal = absolute.mal;
                m.ep = ep;
                return m;
            }
        }
        return null;
    }
}
