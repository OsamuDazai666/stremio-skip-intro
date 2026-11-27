package io.github.osamudazai666.skipintro;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fetches skip times for a video from AniSkip and TheIntroDB, in the background, once per video.
 * Same sources and merge rules as resolveSegments() in skip-intro.plugin.js.
 */
final class Lookup {
    private static final String ANISKIP_API = "https://api.aniskip.com/v2";
    private static final String TIDB_API = "https://api.theintrodb.org/v4";
    private static final double ANISKIP_LENGTH_TOLERANCE_S = 30;
    private static final double MIN_SEGMENT_S = 3;
    /** Wait this long for the core to report the file length before looking up without it. */
    private static final long WAIT_FOR_DURATION_MS = 8000;
    private static final long RETRY_AFTER_ERROR_MS = 60000;

    /** Times in milliseconds; -1 when absent. */
    static final class Times {
        long introStartMs = -1, introEndMs = -1, outroStartMs = -1, durationMs;
        String source = "";

        boolean isEmpty() {
            return introStartMs < 0 && outroStartMs < 0;
        }

        @Override
        public String toString() {
            return "intro " + introStartMs + "-" + introEndMs + " outro " + outroStartMs + " (" + source + ")";
        }
    }

    private static final class Segment {
        final String type;
        final double start, end;
        final String source;

        Segment(String type, double start, double end, String source) {
            this.type = type;
            this.start = start;
            this.end = end;
            this.source = source;
        }
    }

    private static final Times EMPTY = new Times();
    private static final Map<String, Times> results = new ConcurrentHashMap<>();
    private static final Map<String, Long> pending = new ConcurrentHashMap<>();
    private static final Map<String, Long> firstSeen = new ConcurrentHashMap<>();
    private static final Map<String, Long> failed = new ConcurrentHashMap<>();
    private static final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "SkipIntro-lookup");
        t.setDaemon(true);
        return t;
    });

    private Lookup() {
    }

    static Times get(String videoId) {
        if (videoId == null) return null;
        Times t = results.get(videoId);
        return t == EMPTY ? null : t;
    }

    /** Starts a lookup for this video if none was made yet. Cheap; called for every player state. */
    static void request(PlayerInfo info) {
        final String key = info.videoId;
        if (results.containsKey(key) || pending.containsKey(key)) return;
        long now = System.currentTimeMillis();
        Long failedAt = failed.get(key);
        if (failedAt != null && now - failedAt < RETRY_AFTER_ERROR_MS) return;

        // The core reports the file length a moment after playback starts; AniSkip uses it to
        // pick times recorded on a release of the same length.
        Long seen = firstSeen.get(key);
        if (seen == null) {
            firstSeen.put(key, now);
            seen = now;
        }
        if (info.durationMs <= 0 && now - seen < WAIT_FOR_DURATION_MS) return;

        pending.put(key, now);
        final PlayerInfo snapshot = info;
        executor.execute(() -> {
            try {
                Times t = resolve(snapshot);
                results.put(key, t.isEmpty() ? EMPTY : t);
                failed.remove(key);
                Log.i(SkipIntro.TAG, key + " -> " + (t.isEmpty() ? "no timestamps" : t.toString()));
            } catch (Throwable e) {
                failed.put(key, System.currentTimeMillis());
                Log.w(SkipIntro.TAG, "lookup failed for " + key, e);
            } finally {
                pending.remove(key);
            }
        });
    }

    private static Times resolve(PlayerInfo info) throws Exception {
        VideoId q = VideoId.parse(info);
        double duration = info.durationMs / 1000.0;

        Map<String, Object> tidbParams = null;
        if (q.imdb != null) {
            tidbParams = new HashMap<>();
            tidbParams.put("imdb_id", q.imdb);
        } else if (q.tmdb > 0) {
            tidbParams = new HashMap<>();
            tidbParams.put("tmdb_id", q.tmdb);
        }
        if (tidbParams != null && q.isSeries()) {
            tidbParams.put("season", q.season);
            tidbParams.put("episode", q.episode);
        }

        List<Segment> tidb = new ArrayList<>();
        if (tidbParams != null) tidb = fetchTheIntroDB(tidbParams, duration);

        List<Segment> ani = new ArrayList<>();
        List<Segment> tidbAnime = new ArrayList<>();
        AnimeMap map = AnimeMap.get();
        AnimeMap.Match anime = map != null ? map.resolve(q) : null;
        if (anime == null && q.mal > 0) {
            anime = new AnimeMap.Match();
            anime.mal = q.mal;
            anime.ep = q.episode > 0 ? q.episode : 1;
        }
        if (anime != null && anime.mal > 0 && anime.ep > 0) {
            ani = fetchAniSkip(anime.mal, anime.ep, duration);
        }
        if (tidbParams == null && anime != null && anime.tvdb > 0) {
            Map<String, Object> p = new HashMap<>();
            p.put("tvdb_id", anime.tvdb);
            p.put("season", anime.tvdbSeason);
            p.put("episode", anime.tvdbEpisode);
            tidbAnime = fetchTheIntroDB(p, duration);
        }

        List<List<Segment>> lists = new ArrayList<>();
        lists.add(ani);
        lists.add(tidb);
        lists.add(tidbAnime);
        return toTimes(merge(lists, duration), info.durationMs);
    }

    private static List<Segment> fetchAniSkip(int mal, int ep, double duration) throws Exception {
        String base = ANISKIP_API + "/skip-times/" + mal + "/" + ep
            + "?types=op&types=ed&types=mixed-op&types=mixed-ed&types=recap&episodeLength=";
        JSONArray results = null;
        if (duration > 0) {
            JSONObject json = json(base + String.format(Locale.ROOT, "%.3f", duration), true);
            if (json != null && json.optBoolean("found")) results = json.optJSONArray("results");
        }
        boolean filterLength = false;
        if (results == null || results.length() == 0) {
            JSONObject json = json(base + "0", true);
            results = json != null && json.optBoolean("found") ? json.optJSONArray("results") : null;
            filterLength = duration > 0;
        }
        List<Segment> out = new ArrayList<>();
        if (results == null) return out;

        // Best result per type: plain op/ed before mixed, then the closest episode length.
        Map<String, JSONObject> best = new HashMap<>();
        Map<String, Double> bestScore = new HashMap<>();
        for (int i = 0; i < results.length(); i++) {
            JSONObject r = results.optJSONObject(i);
            if (r == null) continue;
            String skipType = r.optString("skipType");
            String type = aniSkipType(skipType);
            JSONObject interval = r.optJSONObject("interval");
            if (type == null || interval == null) continue;
            double len = r.optDouble("episodeLength", 0);
            if (filterLength && len > 0 && Math.abs(len - duration) > ANISKIP_LENGTH_TOLERANCE_S) continue;
            double score = (skipType.startsWith("mixed") ? 1000 : 0) + (len > 0 && duration > 0 ? Math.abs(len - duration) : 500);
            Double prev = bestScore.get(type);
            if (prev == null || score < prev) {
                bestScore.put(type, score);
                best.put(type, r);
            }
        }
        for (Map.Entry<String, JSONObject> e : best.entrySet()) {
            JSONObject interval = e.getValue().getJSONObject("interval");
            out.add(new Segment(e.getKey(), interval.getDouble("startTime"), interval.getDouble("endTime"), "AniSkip"));
        }
        return out;
    }

    private static String aniSkipType(String skipType) {
        switch (skipType) {
            case "op":
            case "mixed-op":
                return "intro";
            case "ed":
            case "mixed-ed":
                return "outro";
            case "recap":
                return "recap";
            default:
                return null;
        }
    }

    private static List<Segment> fetchTheIntroDB(Map<String, Object> params, double duration) throws Exception {
        StringBuilder url = new StringBuilder(TIDB_API).append("/media?");
        boolean first = true;
        for (Map.Entry<String, Object> e : params.entrySet()) {
            if (!first) url.append('&');
            first = false;
            url.append(e.getKey()).append('=').append(URLEncoder.encode(String.valueOf(e.getValue()), "UTF-8"));
        }
        if (duration > 0) url.append("&duration_ms=").append(Math.round(duration * 1000));

        List<Segment> out = new ArrayList<>();
        JSONObject json = json(url.toString(), false);
        if (json == null) return out;
        String[][] fields = {{"intro", "intro"}, {"recap", "recap"}, {"credits", "outro"}, {"preview", "preview"}};
        for (String[] f : fields) {
            JSONArray arr = json.optJSONArray(f[0]);
            if (arr == null) continue;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject s = arr.optJSONObject(i);
                if (s == null) continue;
                double start = s.isNull("start_ms") ? 0 : s.optDouble("start_ms", 0) / 1000.0;
                double end;
                if (s.isNull("end_ms") || !s.has("end_ms")) {
                    // Open-ended credits run to the end of the file. Without a length, keep the start only.
                    end = duration > 0 ? duration : start + 60;
                } else {
                    end = s.optDouble("end_ms", 0) / 1000.0;
                }
                if (start == 0 && end == 0) continue;
                out.add(new Segment(f[1], start, end, "TheIntroDB"));
            }
        }
        return out;
    }

    /** GETs JSON. AniSkip answers 404 when it has nothing, which is not an error. */
    private static JSONObject json(String url, boolean notFoundIsEmpty) throws Exception {
        Http.Response res = Http.get(url, 10000);
        if (res.status == 200) return new JSONObject(res.body);
        if (res.status == 404 || res.status == 204) return null;
        if (res.status == 429) Log.w(SkipIntro.TAG, "rate limited by " + url);
        if (notFoundIsEmpty && res.status < 500) return null;
        throw new java.io.IOException("HTTP " + res.status + " for " + url);
    }

    /** One segment per type, first source wins; cleaned up like mergeSegments() in the plugin. */
    private static Map<String, Segment> merge(List<List<Segment>> lists, double duration) {
        Map<String, Segment> chosen = new HashMap<>();
        for (List<Segment> list : lists) {
            for (Segment s : list) {
                if (chosen.containsKey(s.type)) continue;
                double start = Math.max(0, s.start);
                double end = Math.max(0, s.end);
                if (duration > 0) {
                    start = Math.min(start, duration);
                    end = Math.min(end, duration);
                }
                if (end - start < MIN_SEGMENT_S) continue;
                chosen.put(s.type, new Segment(s.type, start, end, s.source));
            }
        }
        return chosen;
    }

    /**
     * Stremio's player has one intro range and one credits start. A recap right before the opening
     * becomes part of the intro, so one press skips both. Without an opening, the recap is the intro.
     */
    private static Times toTimes(Map<String, Segment> segs, long durationMs) {
        Times t = new Times();
        t.durationMs = durationMs;
        Segment intro = segs.get("intro");
        Segment recap = segs.get("recap");
        Segment outro = segs.get("outro");
        StringBuilder source = new StringBuilder();

        double start = -1, end = -1;
        if (intro != null) {
            start = intro.start;
            end = intro.end;
            source.append(intro.source);
            if (recap != null && recap.end <= intro.start + 1 && intro.start - recap.end <= 5) start = recap.start;
        } else if (recap != null) {
            start = recap.start;
            end = recap.end;
            source.append(recap.source);
        }
        if (start >= 0) {
            t.introStartMs = Math.round(start * 1000);
            t.introEndMs = Math.round(end * 1000);
        }
        if (outro != null) {
            t.outroStartMs = Math.round(outro.start * 1000);
            if (source.length() > 0 && !source.toString().equals(outro.source)) source.append(" + ");
            if (!source.toString().equals(outro.source)) source.append(outro.source);
        }
        t.source = source.toString();
        return t;
    }
}
