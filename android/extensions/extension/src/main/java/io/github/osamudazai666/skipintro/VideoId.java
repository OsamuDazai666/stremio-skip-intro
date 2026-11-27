package io.github.osamudazai666.skipintro;

/**
 * Parsed Stremio video id. Same rules as parseVideoId() in skip-intro.plugin.js:
 * <pre>
 *   tt2560140:3:13   Cinemeta series: imdb:season:episode
 *   tt0816692        movie
 *   kitsu:7442:5     Anime Kitsu addon: kitsu:id:episode (or kitsu:id:season:episode)
 *   mal:16498:5 / anilist:16498:5
 *   tmdb:1429:1:5    TMDB addon
 * </pre>
 */
final class VideoId {
    String imdb;
    int tmdb = -1, kitsu = -1, mal = -1, anilist = -1;
    int season = -1, episode = -1, absEpisode = -1;

    boolean isSeries() {
        return episode >= 0;
    }

    static VideoId parse(PlayerInfo info) {
        VideoId q = new VideoId();
        String[] p = info.videoId.split(":");
        if (p[0].matches("tt\\d+")) {
            q.imdb = p[0];
            q.season = at(p, 1);
            q.episode = at(p, 2);
        } else if (p[0].equals("kitsu") || p[0].equals("mal") || p[0].equals("anilist")) {
            int id = at(p, 1);
            if (p[0].equals("kitsu")) q.kitsu = id;
            else if (p[0].equals("mal")) q.mal = id;
            else q.anilist = id;
            if (p.length >= 4) {
                q.season = at(p, 2);
                q.episode = at(p, 3);
            } else {
                q.episode = at(p, 2);
            }
        } else if (p[0].equals("tmdb")) {
            q.tmdb = at(p, 1);
            q.season = at(p, 2);
            q.episode = at(p, 3);
        }
        if (q.episode < 0 && info.episode >= 0) {
            q.season = info.season;
            q.episode = info.episode;
        }
        q.absEpisode = info.absEpisode;
        return q;
    }

    private static int at(String[] p, int i) {
        if (i >= p.length) return -1;
        try {
            return Integer.parseInt(p[i]);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
