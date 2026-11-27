package io.github.osamudazai666.skipintro;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** What the lookup needs from a {@code com.stremio.core.models.Player} state. */
final class PlayerInfo {
    /** Stremio video id, for example "tt22248376:1:27", "kitsu:46474:27" or "tt0816692". */
    String videoId;
    /** "series", "movie", "anime"... */
    String type;
    /** Season and episode from the core's seriesInfo, or -1. */
    int season = -1;
    int episode = -1;
    /** 1-based position of this episode among all regular episodes, or -1 (for absolute numbering). */
    int absEpisode = -1;
    /** Length of the file in milliseconds once the core knows it, or 0. */
    long durationMs;

    static PlayerInfo read(Object player) {
        if (player == null) return null;
        Object selected = Models.call(player, "getSelected");
        Object streamRequest = Models.call(selected, "getStreamRequest");
        Object path = Models.call(streamRequest, "getPath");
        String id = Models.string(path, "getId");
        if (id == null || id.isEmpty()) return null;

        PlayerInfo info = new PlayerInfo();
        info.videoId = id;
        info.type = Models.string(path, "getType");

        Object seriesInfo = Models.call(player, "getSeriesInfo");
        if (seriesInfo != null) {
            info.season = (int) Models.number(seriesInfo, "getSeason", -1);
            info.episode = (int) Models.number(seriesInfo, "getEpisode", -1);
        }

        Object libraryItem = Models.call(player, "getLibraryItem");
        Object state = Models.call(libraryItem, "getState");
        if (state != null && id.equals(Models.string(state, "getVideoId"))) {
            info.durationMs = Math.max(0, Models.number(state, "getDuration", 0));
        }

        info.absEpisode = absoluteEpisode(player, id);
        return info;
    }

    private static int absoluteEpisode(Object player, String id) {
        try {
            Object meta = Models.call(Models.call(player, "getMetaItem"), "getReady");
            Object videos = Models.call(meta, "getVideos");
            if (!(videos instanceof List)) return -1;
            List<long[]> eps = new ArrayList<>();
            List<String> ids = new ArrayList<>();
            for (Object v : (List<?>) videos) {
                Object si = Models.call(v, "getSeriesInfo");
                if (si == null) continue;
                long s = Models.number(si, "getSeason", -1);
                long e = Models.number(si, "getEpisode", -1);
                if (s <= 0 || e < 0) continue;
                eps.add(new long[]{s, e, ids.size()});
                ids.add(Models.string(v, "getId"));
            }
            Collections.sort(eps, (a, b) -> a[0] != b[0] ? Long.compare(a[0], b[0]) : Long.compare(a[1], b[1]));
            for (int i = 0; i < eps.size(); i++) {
                if (id.equals(ids.get((int) eps.get(i)[2]))) return i + 1;
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }
}
