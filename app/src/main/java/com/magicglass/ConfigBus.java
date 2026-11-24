package com.magicglass;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Host-side configuration reader with two independent channels.
 *
 *  PRIMARY   LSPosed getRemotePreferences("magicglass"), handed in by
 *             GlassEntry. Zero IPC, updated by the framework itself.
 *  FALLBACK  our own ModuleProvider (content://com.magicglass.provider/config).
 *             Some LSPosed builds only expose remote preferences as a read-only
 *             mirror of a differently-named file, or the module process writes
 *             to its own SharedPreferences without a mirror. The provider
 *             always reflects what the module UI actually wrote.
 *
 * A channel is considered "live" when it carries mg.cfg.version. Both are
 * plain String maps, so ConfigContract.Source hides the difference.
 *
 * This class never writes to the device's system partitions, never changes
 * Settings.Global/System keys, and never issues a reboot command.
 */
public final class ConfigBus {

    public static final String AUTHORITY = "com.magicglass.provider";
    public static final Uri URI_CONFIG = Uri.parse("content://" + AUTHORITY + "/config");
    public static final Uri URI_MEDIA = Uri.parse("content://" + AUTHORITY + "/media");
    public static final String COLUMN_KEY = "key";
    public static final String COLUMN_VALUE = "value";
    public static final String ACTION_CONFIG_CHANGED = "com.magicglass.CONFIG_CHANGED";

    private static volatile SharedPreferences sRemote;
    /**
     * 0.5.0-p76: EVENT-DRIVEN cache. p63 removed a 1.5 s TTL cache because
     * it made a settings edit look like it needed a page restart. A TTL is
     * wrong, but a cache invalidated by the very broadcast the module UI
     * sends on EVERY write is exact: load() is called on attach, onResume
     * and config broadcast, so a settled page / a second Activity no longer
     * pays a cross-process provider query. The broadcast receiver already
     * calls invalidate() before its debounced refresh.
     */
    private static volatile ConfigContract.Source sCached;
    private static volatile int sIpcLoads;
    private static volatile int sCacheHits;
    /** One warm-up per host process (onPackageLoaded can fire again). */
    private static boolean sWarmUpDone;

    /**
     * 0.5.0-p81 FIRST-FRAME PREFETCH (findings §70.4, plan C).
     *
     * p80 read the tiny REAL background picture with a SYNCHRONOUS
     * cross-provider openInputStream()+decode inside BackdropLayer.apply()
     * on the UI thread, and set its "loaded" flag even when that read failed.
     * On a cold start the module provider is not bound yet, so the picture
     * was permanently missing and frame 0 fell back to the flat colour.
     *
     * Both artifacts now come from ONE low-priority daemon started at
     * onPackageLoaded, long before any Activity:
     *   1. a config query (also triggers ModuleProvider.query()'s lazy
     *      derivation of bg.windowColor AND window_preview.png on upgrades);
     *   2. up to PREVIEW_TRIES reads of the tiny PNG through the SAME
     *      read-only media provider.
     * The result is a process-wide Bitmap. Host apply() only does a volatile
     * read; SystemUI's starting-window hooks read the very same Bitmap.
     * Failures are NOT cached: requestFirstFrame() allows a bounded retry.
     */
    private static volatile Bitmap sStartingPreview;
    /** 0.5.0-p100: strongly blurred twin, used by the splash only. */
    private static volatile Bitmap sStartingBlur;
    private static volatile int sPreviewAttempts;
    private static volatile boolean sPrefetchDone;
    private static volatile boolean sPrefetchRunning;
    private static volatile Runnable sPreviewReady;
    private static final int PREVIEW_TRIES = 3;
    /**
     * 0.5.0-p83 / blueprint §3.4 + §4 A. The previous LIFETIME budget
     * (MAX_PREVIEW_ATTEMPTS = 6, only reset by a config change) was a real
     * defect for a LONG-LIVED SystemUI: three immediate retries with no
     * back-off burn the whole budget when the module provider is still cold
     * at boot, and then plan A can NEVER succeed again for the rest of the
     * boot cycle — the starting window silently degrades to skip forever.
     * Replaced by a TIME-WINDOW throttle: at most one bounded attempt per
     * cooldown, unbounded over time, and a successful read still stops
     * everything (sPrefetchDone). No polling, no timer: attempts only happen
     * when something actually asks for the picture.
     */
    private static final long PREVIEW_COOLDOWN_MS = 1200L;
    private static volatile long sLastPreviewAttemptMs;
    /**
     * Bounded SELF-retry inside one request chain. A HOST process only gets
     * ONE attach() to obtain the picture, so a single cold-provider failure
     * must not end the attempt for that page; a long-lived SystemUI must not
     * spin forever either. Reset by every external requestFirstFrame() call.
     */
    private static final int PREVIEW_MAX_SPINS = 3;
    private static volatile int sPreviewSpins;
    /**
     * 0.5.0-p82 / report §4 A: "static cache (SystemUI is long-lived, read
     * once; REFRESH on config change)". Bumped by invalidateStartingPreview().
     * The host reads it in ensureWindowPreview() so BOTH processes drop a
     * stale picture after the user picks a new background.
     */
    private static volatile int sPreviewGeneration;

    private ConfigBus() {
    }

    /** Called by GlassEntry in a hooked process. */
    public static void setRemote(SharedPreferences sp) {
        sRemote = sp;
    }

    /**
     * 0.5.0-p76: no longer a no-op. Clears the event-driven Source cache.
     * Called by RenderController's config broadcast receiver BEFORE the
     * debounced refresh, so a settings edit is still picked up live while
     * idle pages / repeated onResume calls pay zero IPC.
     */
    public static void invalidate() {
        sCached = null;
        // 0.5.0-p82 / report §4 A: the HOST must refresh its static picture
        // too, not only the long-lived SystemUI process. Otherwise a config
        // change makes the starting window show the NEW background while the
        // DecorView still paints the OLD one -- a new colour/picture jump.
        // invalidate() is called by RenderController's RECEIVER_EXPORTED
        // config receiver, so no extra receiver / timer / polling is added.
        invalidateStartingPreview();
    }

    /**
     * 0.5.0-p117: clear ONLY the configuration snapshot cache.
     *
     * Defect (device evidence 2026-11-16 21:22): the user picked a VIDEO
     * background while the host page was in the background. The settings UI
     * did write the new prefs and did send the coalesced CONFIG_CHANGED
     * broadcast, but no host Activity was attached at that moment, so the
     * RECEIVER_EXPORTED receiver (registered in attach(), unregistered in
     * detach()) was gone. sCached is process-wide and had NO TTL, so every
     * later attach() / onLifecycle() kept reading the OLD snapshot forever:
     * page ... bg=image while prefs said bg.kind=video (ver 1794835365614
     * vs the last host read 1794835329762). One manual broadcast to an
     * attached host immediately produced cache=miss + bg=video +
     * LIVEGLASS mode=gpu video=true, which proves the cache was the only
     * stale link.
     *
     * Fix: re-read the provider on every attach and every debounced resume.
     * Unlike invalidate() this MUST NOT bump the starting-window generation
     * (that would disturb the accepted p87/p103 splash path). Cost is one
     * small IPC (~5 ms in the field) per page entry, never per frame.
     */
    public static void invalidateConfigCache() {
        sCached = null;
    }

    /**
     * 0.5.0-p76 FIRST-FRAME LATENCY. The very first cross-process call from
     * a freshly started host process has to bind (and possibly start) the
     * module's ContentProvider; doing that synchronously in
     * RenderController.attach() -> refresh(true) is what makes the wallpaper
     * appear 1-2 s late on first entry. Warm that channel on a low-priority
     * daemon thread as soon as the render hooks are installed (long before
     * any Activity exists). It only reads our own config keys, never writes.
     */
    public static void warmUpAsync(final Context ctx) {
        if (ctx == null) return;
        synchronized (ConfigBus.class) {
            if (sWarmUpDone) return;
            sWarmUpDone = true;
        }
        try {
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    long t0 = SystemClock.uptimeMillis();
                    // p81: the same daemon now also primes the window colour
                    // and the tiny REAL first-frame picture. One config query
                    // instead of two, zero work on the UI thread.
                    prefetchSync(ctx);
                    MgLog.say("config warmup ms="
                            + (SystemClock.uptimeMillis() - t0));
                }
            }, "mg-config-warmup");
            t.setPriority(Thread.MIN_PRIORITY);
            t.setDaemon(true);
            t.start();
        } catch (Throwable t) {
            MgLog.say("config warmup unavailable: " + t);
        }
    }

    /**
     * 0.5.0-p66: SystemUI's starting-window hook runs without a Context, so it
     * cannot query our ContentProvider. LSPosed remote preferences are already
     * installed there by GlassEntry.installStartingWindowHooks(); read the same
     * tint colour the render process uses for its window base, so the transition
     * does not jump from a system white splash to a differently coloured page.
     */
    /**
     * 0.5.0-p79: getRemotePreferences() is a snapshot bound when SystemUI
     * started. A bg.windowColor written later by the module process (p77) or
     * derived lazily by ModuleProvider.query() (p78) may never appear in that
     * snapshot, so the starting-window fallback stayed near-white even when
     * the render process used the correct colour. Prime the colour from the
     * SAME provider the render processes read, once, on a daemon thread —
     * that very query also derives a missing key (p78).
     */
    private static volatile int sWindowColor = ConfigContract.WINDOW_BASE_COLOR;
    private static volatile boolean sWindowColorFromProvider;

    public static void warmWindowColor(final Context ctx) {
        // 0.5.0-p83: stop condition is the PICTURE (sPrefetchDone), not the
        // colour. The old check returned early once the colour was known,
        // so a colour success with a failed picture read never retried the
        // picture -- and plan A needs the picture, not the colour.
        if (ctx == null || sPrefetchDone) return;
        try {
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    // p81: SystemUI needs the SAME tiny picture as the host.
                    // One daemon, one config query, one media read; the
                    // starting-window hooks only do a volatile read.
                    prefetchSync(ctx);
                }
            }, "mg-windowcolour");
            t.setPriority(Thread.MIN_PRIORITY);
            t.setDaemon(true);
            t.start();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 0.5.0-p100: the blurred twin for the starting window. Falls back to the
     * sharp picture when the blur has not been generated yet, so a fresh
     * install still shows the real background instead of a white frame.
     */
    public static Bitmap startingBlur() {
        Bitmap b = sStartingBlur;
        if (b != null && !b.isRecycled()) return b;
        return startingPreview();
    }

    /** Process-wide REAL first-frame picture; null when not ready (p81). */
    public static Bitmap startingPreview() {
        Bitmap b = sStartingPreview;
        return b == null || b.isRecycled() ? null : b;
    }

    /** 0.5.0-p82: monotonic generation of that picture (report §4 A). */
    public static int previewGeneration() {
        return sPreviewGeneration;
    }

    /**
     * 0.5.0-p82 / report §4 A, "REFRESH on config change".
     * SystemUI never restarts, so the process-wide Bitmap AND every
     * one-shot flag from p81 would otherwise pin the OLD background for the
     * whole boot cycle. Drop the reference (do NOT recycle: a live View may
     * still hold it through a BitmapDrawable), reset the bounded-retry
     * budget and the colour source, and bump the generation so the host's
     * ensureWindowPreview() re-reads too. No IPC, no decode, no timer here:
     * the caller re-arms the SAME MIN_PRIORITY daemon via requestFirstFrame().
     */
    public static void invalidateStartingPreview() {
        sStartingPreview = null;
        sStartingBlur = null;
        sPrefetchDone = false;
        sPreviewAttempts = 0;
        sWindowColorFromProvider = false;
        // 0.5.0-p83: clear the throttle so the NEW picture is read at once.
        sLastPreviewAttemptMs = 0;
        sPreviewGeneration++;
        MgLog.say("mg-state startingWindowPreview invalidated gen="
                + sPreviewGeneration);
    }

    /**
     * Body of the single first-frame daemon. Called on a MIN_PRIORITY thread
     * from warmUpAsync() (host) and warmWindowColor() (SystemUI), never from
     * a draw method or from the UI thread.
     */
    private static void prefetchSync(Context ctx) {
        if (ctx == null) return;
        synchronized (ConfigBus.class) {
            if (sPrefetchRunning) return;
            sPrefetchRunning = true;
        }
        // 0.5.0-p82: a config change DURING this read makes its result stale.
        // Never publish it; the finally block re-arms exactly one retry.
        final int gen = sPreviewGeneration;
        // 0.5.0-p83: this read also counts for the throttle, so a caller that
        // went through warmUpAsync()/warmWindowColor() (which bypass
        // requestFirstFrame) cannot bypass the cooldown.
        sLastPreviewAttemptMs = SystemClock.uptimeMillis();
        try {
            // (1) A config read from the module process. ModuleProvider.query()
            // lazily derives bg.windowColor AND window_preview.png for upgraded
            // installs (p78/p80), so the media read below cannot miss them.
            Map<String, String> map = providerMap(ctx);
            if (!map.isEmpty() && !sWindowColorFromProvider) {
                try {
                    int c = ConfigContract.windowColorOf(ConfigContract.resolve(
                            ConfigContract.from(map), ConfigContract.Slot.GLOBAL));
                    sWindowColor = c;
                    sWindowColorFromProvider = true;
                    MgLog.say("mg-state windowColourSource=provider win="
                            + Integer.toHexString(c));
                } catch (Throwable ignored) {
                }
            }
            // (2) The tiny PNG. A cold provider can legitimately fail once;
            // retry a bounded number of times on THIS daemon instead of
            // caching the failure forever (p80's defect).
            Bitmap bmp = null;
            for (int i = 0; i < PREVIEW_TRIES && bmp == null; i++) {
                sPreviewAttempts++;
                if (i > 0) {
                    // Blueprint §3.4: a cold provider needs a moment to bind.
                    // Three back-to-back reads inside one daemon trip all
                    // failed at once and (with the old lifetime budget)
                    // poisoned plan A for the whole boot cycle.
                    try {
                        Thread.sleep(PREVIEW_COOLDOWN_MS);
                    } catch (Throwable ignored) {
                    }
                }
                InputStream in = null;
                try {
                    in = ctx.getContentResolver().openInputStream(
                            media(ConfigStore.WINDOW_PREVIEW_NAME));
                    if (in != null) {
                        BitmapFactory.Options o = new BitmapFactory.Options();
                        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
                        bmp = BitmapFactory.decodeStream(in, null, o);
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (in != null) {
                        try {
                            in.close();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
            if (gen != sPreviewGeneration) {
                // Do NOT recycle: a live View may still hold the old Bitmap.
                // Just discard this stale read; finally re-arms one retry.
                bmp = null;
                MgLog.say("mg-state startingWindowPreview stale=1 gen=" + gen);
            }
            sStartingPreview = bmp;
            if (bmp != null) sPrefetchDone = true;
            // 0.5.0-p100: the blurred twin, same directory, same framing.
            // Read in the SAME daemon trip so the splash never does IPC itself.
            Bitmap blur = null;
            try {
                InputStream bin = ctx.getContentResolver().openInputStream(
                        media(ConfigStore.WINDOW_PREVIEW_BLUR_NAME));
                if (bin != null) {
                    BitmapFactory.Options bo = new BitmapFactory.Options();
                    bo.inPreferredConfig = Bitmap.Config.ARGB_8888;
                    blur = BitmapFactory.decodeStream(bin, null, bo);
                }
                try {
                    if (bin != null) bin.close();
                } catch (Throwable ignored) {
                }
            } catch (Throwable ignored) {
            }
            sStartingBlur = blur;
            String size = bmp == null ? "none"
                    : bmp.getWidth() + "x" + bmp.getHeight();
            MgLog.say("mg-state firstFramePrefetch preview=" + size
                    + " attempts=" + sPreviewAttempts);
            MgLog.say("mg-state startingWindowPreview load=" + size);
            if (bmp != null) postPreviewReady();
        } finally {
            sPrefetchRunning = false;
            // 0.5.0-p83: a failed read re-arms ONCE per spin budget, so the
            // single attach() of a host page still gets its picture, while a
            // long-lived SystemUI stops after a few tries instead of spinning.
            // A config change (stale generation) always gets a fresh spin.
            if (ctx != null && !sPrefetchDone) {
                boolean stale = gen != sPreviewGeneration;
                if (stale) sPreviewSpins = 0;
                if (sPreviewSpins < PREVIEW_MAX_SPINS) {
                    sPreviewSpins++;
                    try {
                        Thread t = new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    Thread.sleep(PREVIEW_COOLDOWN_MS);
                                } catch (Throwable ignored) {
                                }
                                prefetchSync(ctx);
                            }
                        }, "mg-preview-refresh");
                        t.setPriority(Thread.MIN_PRIORITY);
                        t.setDaemon(true);
                        t.start();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }

    private static void postPreviewReady() {
        Runnable r = sPreviewReady;
        if (r == null) return;
        try {
            new Handler(Looper.getMainLooper()).post(r);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Host apply() entry point. Registers a one-shot repaint callback and
     * returns the picture WITHOUT any IPC on the calling (UI) thread. If the
     * warm-up daemon has not run or failed, start a bounded retry daemon.
     */
    public static Bitmap requestFirstFrame(final Context ctx, Runnable onReady) {
        if (onReady != null) sPreviewReady = onReady;
        Bitmap b = startingPreview();
        if (b != null) return b;
        if (ctx == null || sPrefetchDone || sPrefetchRunning) return null;
        // 0.5.0-p83: TIME-WINDOW throttle instead of a lifetime budget. A
        // long-lived SystemUI must be able to retry on a LATER starting
        // window even if the very first attempts failed while the module
        // provider was still cold; it must not hammer the provider either.
        long now = SystemClock.uptimeMillis();
        if (sLastPreviewAttemptMs != 0
                && now - sLastPreviewAttemptMs < PREVIEW_COOLDOWN_MS) {
            return null;
        }
        sLastPreviewAttemptMs = now;
        // A fresh external request restores the self-retry budget: a later
        // starting window / page entry must be able to retry even if an
        // earlier chain exhausted its spins.
        sPreviewSpins = 0;
        try {
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    prefetchSync(ctx);
                }
            }, "mg-firstframe-retry");
            t.setPriority(Thread.MIN_PRIORITY);
            t.setDaemon(true);
            t.start();
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static int windowColor() {
        if (sWindowColorFromProvider) return sWindowColor;
        SharedPreferences sp = sRemote;
        if (sp == null) return ConfigContract.WINDOW_BASE_COLOR;
        try {
            return ConfigContract.windowColorOf(
                    ConfigContract.resolve(ConfigContract.from(sp),
                            ConfigContract.Slot.GLOBAL));
        } catch (Throwable t) {
            return ConfigContract.WINDOW_BASE_COLOR;
        }
    }

    public static ConfigContract.Source load(Context ctx) {
        long t0 = SystemClock.uptimeMillis();
        ConfigContract.Source cached = sCached;
        if (cached != null) {
            sCacheHits++;
            // 0.5.0-p88: `mg-state ` prefix so this line is ALWAYS visible.
            // Without it the startup budget could not be attributed: the probe
            // turned out to cost 0 ms, and the question "did the first attach
            // pay a cold IPC?" had no evidence either way. Log the first hit
            // only -- later hits are inside a frame and would spam the log.
            if (sCacheHits == 1) {
                MgLog.say("mg-state config load cache=hit ipc=0 ms="
                        + (SystemClock.uptimeMillis() - t0));
            }
            return cached;
        }
        // The provider reads the module's OWN SharedPreferences, so it is the
        // freshest source. LSPosed remote preferences may lag by one sync or
        // be a read-only mirror on some builds; prefer the provider whenever
        // it carries a real version key.
        Map<String, String> map = providerMap(ctx);
        sIpcLoads++;
        ConfigContract.Source src;
        if (!map.isEmpty()) {
            ConfigContract.Source provider = ConfigContract.from(map);
            if (!"0".equals(ConfigContract.version(provider))) {
                src = provider;
            } else {
                src = remoteOrDefault(map);
            }
        } else {
            src = remoteOrDefault(map);
        }
        sCached = src;
        MgLog.say("mg-state config load cache=miss ipc=1 ms="
                + (SystemClock.uptimeMillis() - t0)
                + " ipcTotal=" + sIpcLoads
                + " ver=" + ConfigContract.version(src));
        return src;
    }

    private static ConfigContract.Source remoteOrDefault(Map<String, String> map) {
        SharedPreferences remote = sRemote;
        if (remote != null) return ConfigContract.from(remote);
        // No config written yet: resolve() will use every Param's default.
        if (!map.isEmpty()) return ConfigContract.from(map);
        return ConfigContract.from(new HashMap<String, String>());
    }

    private static Map<String, String> providerMap(Context ctx) {
        Map<String, String> out = new HashMap<String, String>();
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(URI_CONFIG, null, null, null, null);
            if (c != null) {
                int ki = c.getColumnIndex(COLUMN_KEY);
                int vi = c.getColumnIndex(COLUMN_VALUE);
                if (ki >= 0 && vi >= 0) {
                    while (c.moveToNext()) {
                        out.put(c.getString(ki), c.getString(vi));
                    }
                }
            }
        } catch (Throwable t) {
            MgLog.say("config provider unavailable: " + t);
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return out;
    }

    /** Build the content Uri for one background image/video in the module's filesDir. */
    public static Uri media(String name) {
        return Uri.withAppendedPath(URI_MEDIA, name);
    }
}
