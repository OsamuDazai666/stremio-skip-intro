package io.github.osamudazai666.skipintro;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Netflix-style Skip Intro / Watch Credits / Next Episode buttons drawn as plain Android views on
 * top of Stremio's player. Stremio's own overlay is hidden by the patch; the buttons call the same
 * actions it would (seek past the intro, play the next episode).
 *
 * Driven by {@link #update}, which the patch calls every time Stremio's player re-renders its
 * skip overlay (on every playback position change). All work happens on the main thread.
 */
final class Overlay {
    private static final long COUNTDOWN_MS = 10000;
    private static final long WATCHDOG_MS = 500;

    private enum Mode { NONE, INTRO, CREDITS }

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static WeakReference<Activity> resumed = new WeakReference<>(null);
    private static boolean callbacksRegistered;

    private static Mode mode = Mode.NONE;
    /** Start of the segment the user dismissed ("Watch Credits"), so it stays hidden. */
    private static long dismissedSegment = -1;
    private static Object skipAction, nextAction;
    private static boolean binge;

    private static FrameLayout root;
    private static LinearLayout row;
    private static Button ghost, primary;
    private static ValueAnimator countdown;
    private static boolean watchdogRunning;

    private Overlay() {
    }

    /**
     * @param timeMs      playback position
     * @param durationMs  file length
     * @param intro       intro range from Stremio (a SkipSegment with from/to), or null
     * @param outro       credits range (from, to or -1), or null
     * @param nextVideo   the next episode, or null
     * @param bingeOn     Stremio's "binge watching" setting
     * @param onSkip      Stremio's action that seeks to the end of the intro
     * @param onNext      Stremio's action that plays the next episode
     */
    static void update(long timeMs, long durationMs, Object intro, Object outro, Object nextVideo,
                       boolean bingeOn, Object onSkip, Object onNext, boolean introIsRecap) {
        registerCallbacks();
        skipAction = onSkip;
        nextAction = onNext;
        binge = bingeOn;

        long[] in = range(intro);
        long[] out = range(outro);
        Mode want = Mode.NONE;
        long segment = -1;
        if (timeMs > 0 && in != null && timeMs >= in[0] && timeMs < in[1] - 500) {
            want = Mode.INTRO;
            segment = in[0];
        } else if (timeMs > 0 && out != null && nextVideo != null && timeMs >= out[0]
            && (durationMs <= 0 || timeMs < durationMs - 1000)) {
            want = Mode.CREDITS;
            segment = out[0];
        }
        currentSegmentStart = segment;
        if (want != Mode.NONE && segment == dismissedSegment) want = Mode.NONE;
        if (want == Mode.NONE && segment != dismissedSegment) dismissedSegment = -1;
        show(want, introIsRecap);
    }

    // -------------------------------------------------------------------------------------------

    private static void show(Mode want, boolean recap) {
        Activity activity = resumed.get();
        if (want == Mode.NONE || activity == null) {
            hide();
            return;
        }
        ensureViews(activity);
        if (want == mode && root.getParent() != null) return;
        mode = want;
        stopCountdown();

        if (want == Mode.INTRO) {
            ghost.setVisibility(View.GONE);
            primary.configure(recap ? "Skip Recap" : "Skip Intro", Button.ICON_SKIP, false);
        } else {
            ghost.configure("Watch Credits", Button.ICON_NONE, false);
            ghost.setVisibility(View.VISIBLE);
            primary.configure("Next Episode", Button.ICON_PLAY, true);
            if (binge) startCountdown();
        }
        if (root.getParent() == null) {
            ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
            decor.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        root.setVisibility(View.VISIBLE);
        row.setAlpha(0f);
        row.setTranslationY(dp(activity, 10));
        row.animate().alpha(1f).translationY(0).setDuration(220).start();
        startWatchdog();
    }

    static void hide() {
        mode = Mode.NONE;
        stopCountdown();
        if (root != null && root.getParent() != null) {
            ((ViewGroup) root.getParent()).removeView(root);
        }
    }

    private static void press(boolean isPrimary) {
        Mode m = mode;
        try {
            if (m == Mode.INTRO) {
                invoke(skipAction);
            } else if (m == Mode.CREDITS) {
                if (isPrimary) invoke(nextAction);
            }
        } catch (Throwable t) {
            Log.w(SkipIntro.TAG, "button action failed", t);
        }
        // Keep this segment hidden: after "Watch Credits", or until the seek/next lands.
        dismissedSegment = currentSegmentStart;
        hide();
    }

    private static long currentSegmentStart = -1;

    private static void invoke(Object kotlinFunction0) throws Exception {
        if (kotlinFunction0 == null) return;
        kotlinFunction0.getClass().getMethod("invoke").invoke(kotlinFunction0);
    }

    // -------------------------------------------------------------------------------------------

    /** Hides the buttons when the player goes away (its video surface is gone) or the app pauses. */
    private static void startWatchdog() {
        if (watchdogRunning) return;
        watchdogRunning = true;
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (root == null || root.getParent() == null) {
                    watchdogRunning = false;
                    return;
                }
                Activity a = resumed.get();
                View decor = a != null ? a.getWindow().getDecorView() : null;
                if (decor == null || !hasVideoSurface(decor)) {
                    hide();
                    watchdogRunning = false;
                    return;
                }
                main.postDelayed(this, WATCHDOG_MS);
            }
        }, WATCHDOG_MS);
    }

    private static boolean hasVideoSurface(View v) {
        if ((v instanceof SurfaceView || v instanceof TextureView) && v.isShown()) return true;
        if (v instanceof ViewGroup && v != root) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) if (hasVideoSurface(g.getChildAt(i))) return true;
        }
        return false;
    }

    private static void startCountdown() {
        stopCountdown();
        countdown = ValueAnimator.ofFloat(0f, 1f);
        countdown.setDuration(COUNTDOWN_MS);
        countdown.addUpdateListener(a -> primary.setFill((float) a.getAnimatedValue()));
        countdown.addListener(new android.animation.AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(android.animation.Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (!cancelled && mode == Mode.CREDITS) press(true);
            }
        });
        countdown.start();
    }

    private static void stopCountdown() {
        if (countdown != null) {
            countdown.cancel();
            countdown = null;
        }
        if (primary != null) primary.setFill(0f);
    }

    // -------------------------------------------------------------------------------------------

    private static void registerCallbacks() {
        if (callbacksRegistered) return;
        Context ctx = App.context();
        if (!(ctx instanceof Application)) return;
        callbacksRegistered = true;
        // The player is already open when we are first called: find the resumed activity now.
        Activity current = currentActivity();
        if (current != null) resumed = new WeakReference<>(current);
        ((Application) ctx).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            public void onActivityResumed(Activity a) {
                resumed = new WeakReference<>(a);
            }

            public void onActivityPaused(Activity a) {
                if (resumed.get() == a) {
                    resumed = new WeakReference<>(null);
                    hide();
                }
            }

            public void onActivityCreated(Activity a, Bundle b) {
            }

            public void onActivityStarted(Activity a) {
            }

            public void onActivityStopped(Activity a) {
            }

            public void onActivitySaveInstanceState(Activity a, Bundle b) {
            }

            public void onActivityDestroyed(Activity a) {
                if (root != null && root.getContext() == a) {
                    hide();
                    root = null;
                }
            }
        });
    }

    @SuppressLint("PrivateApi")
    private static Activity currentActivity() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("currentActivityThread").invoke(null);
            Field activitiesField = at.getDeclaredField("mActivities");
            activitiesField.setAccessible(true);
            Object map = activitiesField.get(thread);
            for (Object record : ((java.util.Map<?, ?>) map).values()) {
                Class<?> rc = record.getClass();
                Field paused = rc.getDeclaredField("paused");
                paused.setAccessible(true);
                if (!paused.getBoolean(record)) {
                    Field af = rc.getDeclaredField("activity");
                    af.setAccessible(true);
                    return (Activity) af.get(record);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Reads Stremio's SkipSegment(from, to): its first two long fields. */
    private static long[] range(Object segment) {
        if (segment == null) return null;
        long[] out = new long[2];
        int n = 0;
        for (Field f : segment.getClass().getDeclaredFields()) {
            if (f.getType() != long.class || Modifier.isStatic(f.getModifiers())) continue;
            try {
                f.setAccessible(true);
                out[n++] = f.getLong(segment);
            } catch (Throwable ignored) {
                return null;
            }
            if (n == 2) break;
        }
        if (n < 2) return null;
        if (out[1] < 0) out[1] = Long.MAX_VALUE;
        return out;
    }

    private static void ensureViews(Activity activity) {
        if (root != null && root.getContext() == activity) return;
        hide();
        root = new FrameLayout(activity);
        root.setClickable(false);
        row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        lp.rightMargin = dp(activity, 28);
        lp.bottomMargin = dp(activity, 96);
        root.addView(row, lp);

        ghost = new Button(activity, false);
        primary = new Button(activity, true);
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gap.rightMargin = dp(activity, 12);
        row.addView(ghost, gap);
        row.addView(primary);
        ghost.setOnClickListener(v -> press(false));
        primary.setOnClickListener(v -> press(true));
    }

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    // -------------------------------------------------------------------------------------------

    /** A Netflix-style button: ghost (dark glass, white outline) or primary (solid white). */
    @SuppressLint("ViewConstructor")
    static final class Button extends View {
        static final int ICON_NONE = 0, ICON_SKIP = 1, ICON_PLAY = 2;

        private final boolean solid;
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final Path path = new Path();
        private String label = "";
        private int iconType;
        private float progress;
        private boolean pressedLook;

        Button(Context c, boolean solid) {
            super(c);
            this.solid = solid;
            setClickable(true);
            setFocusable(true);
            text.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            text.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 15, getResources().getDisplayMetrics()));
            border.setStyle(Paint.Style.STROKE);
            border.setStrokeWidth(dp(c, 2));
            border.setColor(Color.argb(200, 255, 255, 255));
            icon.setStyle(Paint.Style.FILL);
            setElevation(dp(c, 4));
        }

        void configure(String label, int iconType, boolean primaryStyle) {
            this.label = label;
            this.iconType = iconType;
            setContentDescription(label);
            requestLayout();
            invalidate();
        }

        void setFill(float p) {
            progress = p;
            invalidate();
        }

        @Override
        public void setPressed(boolean pressed) {
            super.setPressed(pressed);
            pressedLook = pressed;
            animate().scaleX(pressed ? 0.96f : 1f).scaleY(pressed ? 0.96f : 1f).setDuration(90).start();
            invalidate();
        }

        @Override
        protected void onMeasure(int w, int h) {
            int padH = dp(getContext(), 22), padV = dp(getContext(), 13);
            int iconW = iconType == ICON_NONE ? 0 : dp(getContext(), 17) + dp(getContext(), 9);
            int width = (int) (padH * 2 + iconW + text.measureText(label));
            int height = (int) (padV * 2 + text.getTextSize());
            setMeasuredDimension(width, height);
        }

        @Override
        protected void onDraw(Canvas c) {
            float r = dp(getContext(), 4);
            float inset = border.getStrokeWidth() / 2;
            rect.set(inset, inset, getWidth() - inset, getHeight() - inset);

            int fg;
            if (solid) {
                bg.setColor(pressedLook ? Color.argb(210, 255, 255, 255) : Color.WHITE);
                fg = Color.rgb(20, 20, 20);
                fill.setColor(Color.argb(46, 0, 0, 0));
            } else {
                bg.setColor(pressedLook ? Color.WHITE : Color.argb(150, 42, 42, 42));
                fg = pressedLook ? Color.rgb(20, 20, 20) : Color.WHITE;
                fill.setColor(Color.argb(70, 255, 255, 255));
            }
            c.drawRoundRect(rect, r, r, bg);
            if (progress > 0) {
                c.save();
                c.clipRect(0, 0, getWidth() * progress, getHeight());
                c.drawRoundRect(rect, r, r, fill);
                c.restore();
            }
            if (!solid) c.drawRoundRect(rect, r, r, border);

            text.setColor(fg);
            icon.setColor(fg);
            float x = dp(getContext(), 22);
            float cy = getHeight() / 2f;
            if (iconType != ICON_NONE) {
                float s = dp(getContext(), 17);
                drawIcon(c, x, cy - s / 2, s);
                x += s + dp(getContext(), 9);
            }
            Paint.FontMetrics fm = text.getFontMetrics();
            c.drawText(label, x, cy - (fm.ascent + fm.descent) / 2, text);
        }

        private void drawIcon(Canvas c, float x, float y, float s) {
            path.reset();
            if (iconType == ICON_PLAY) {
                path.moveTo(x + s * 0.15f, y);
                path.lineTo(x + s, y + s / 2);
                path.lineTo(x + s * 0.15f, y + s);
                path.close();
            } else {
                float h = s / 2;
                path.moveTo(x, y);
                path.lineTo(x + h, y + h);
                path.lineTo(x, y + s);
                path.close();
                path.moveTo(x + h, y);
                path.lineTo(x + s, y + h);
                path.lineTo(x + h, y + s);
                path.close();
            }
            c.drawPath(path, icon);
        }
    }
}
