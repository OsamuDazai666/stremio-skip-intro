package io.github.osamudazai666.skipintro;

import android.util.Log;

/**
 * Entry points called by the bytecode the "Skip Intro" patch adds to Stremio's player view model.
 *
 * Stremio's player already has a Skip Intro button and a Next Episode popup. They are driven by
 * {@code Player.IntroOutro} from Stremio's core and are shown only to Stremio Premium accounts.
 * These hooks put AniSkip / TheIntroDB timestamps into that field and let the player show them.
 * Stremio's own premium timestamps are never shown to accounts without premium.
 *
 * Every method catches everything: a failure here must never break playback.
 */
@SuppressWarnings("unused")
public final class SkipIntro {
    static final String TAG = "SkipIntro";

    /** Video id of the player state seen last, for example "tt22248376:1:27". */
    private static volatile String currentVideoId;
    /** Whether the account had Stremio Premium the last time the player asked. */
    private static volatile boolean premium;

    private SkipIntro() {
    }

    /** Called at the start of the player view model's state handler with the new core state. */
    public static void onPlayer(Object player) {
        try {
            PlayerInfo info = PlayerInfo.read(player);
            if (info == null) {
                currentVideoId = null;
                return;
            }
            currentVideoId = info.videoId;
            Lookup.request(info);
        } catch (Throwable t) {
            Log.w(TAG, "onPlayer failed", t);
        }
    }

    /**
     * Replaces the core's {@code player.getIntroOutro()} result.
     * Premium accounts keep Stremio's own intro when it has one. Otherwise our timestamps are used
     * when we have them, and the original value is returned unchanged when we do not.
     */
    public static Object introOutro(Object original) {
        try {
            Lookup.Times times = Lookup.get(currentVideoId);
            if (times == null || times.isEmpty()) return original;
            if (premium && original != null && Models.getIntro(original) != null) return original;
            Object ours = Models.newIntroOutro(original, times);
            return ours != null ? ours : original;
        } catch (Throwable t) {
            Log.w(TAG, "introOutro failed", t);
            return original;
        }
    }

    /**
     * Replaces the player's premium check before it shows skip segments.
     * Passes when the account has premium, or when we have our own timestamps for this video.
     */
    public static boolean allow(boolean isPremium) {
        try {
            premium = isPremium;
            if (isPremium) return true;
            Lookup.Times times = Lookup.get(currentVideoId);
            return times != null && !times.isEmpty();
        } catch (Throwable t) {
            Log.w(TAG, "allow failed", t);
            return isPremium;
        }
    }
}
