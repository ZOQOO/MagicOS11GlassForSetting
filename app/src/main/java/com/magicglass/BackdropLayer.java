package com.magicglass;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.drawable.Drawable;
import android.graphics.Shader;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.SystemClock;
import android.os.Looper;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-level editable background layer (decision 4: L1/L2/L3 and each external
 * package own an independent background, random image pool and video).
 *
 * Layer order inside the decor overlay, bottom to top:
 *   0 ImageView        static / random image
 *   1 TextureView      looping video (TextureView, NOT SurfaceView: a SurfaceView
 *                      punches a hole through the window and breaks the
 *                      RenderEffect blur used by the glass containers)
 *   2 View             tint + dim overlay
 *   3 FrameLayout      glass host (owned by RenderController)
 *
 * Media is read from the module's own ContentProvider because a hooked system
 * process cannot read /data/data/com.magicglass. Every decode is sampled to at
 * most ~1600x3000 so a 4K wallpaper cannot OOM the Settings process.
 *
 * All GPU work (RenderEffect, MediaPlayer, TextureView) is guarded. Any failure
 * simply leaves the ROM's native HnTransparentPattern background intact.
 */
public final class BackdropLayer {

    private final Context ctx;
    private final FrameLayout overlay;
    private final ImageView image;
    private final TextureView video;
    private final View tint;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private MediaPlayer player;
    private SurfaceTexture surface;
    private Bitmap current;
    /**
     * 0.4.0-p43. An OPAQUE window base colour, derived from the current
     * backdrop image. The old code set the DecorView background to NULL (and
     * skipped the whole step when the ROM had none). During an Activity
     * transition / starting window / paused window there are frames where our
     * overlay has not been composited yet; with a NULL decor background those
     * frames render solid BLACK ("返回时截图变黑"). Keeping an opaque colour
     * that matches the wallpaper makes every such frame neutral, while still
     * never restoring the OEM white card background.
     */
    private int windowBgColor = ConfigContract.WINDOW_BASE_COLOR;
    /**
     * 0.5.0-p66: identity of OUR window backdrop Drawable. It paints the
     * CURRENT wallpaper (plus tint/dim) instead of one flat colour, so every
     * un-composited transition frame is the user's own background.
     */
    private WindowBackdropDrawable windowBgDrawable;
    /** ARGB of the tint+dim composition baked into the window backdrop; 0 = none. */
    private int windowTintARGB;
    /**
     * 0.4.0-p9: a FIXED, screen-sized snapshot of the backdrop.
     *
     * The first implementation cropped the source bitmap by the card's current
     * screen rect on every frame. When the card moved, so did the sampled
     * region -- the perceived result was "the background slides along with the
     * glass and then jumps", the opposite of looking THROUGH glass.
     *
     * Sampling a fixed snapshot instead means the image stays pinned to the
     * screen and a moving card simply reveals a different part of it.
     */
    private Bitmap screenBmp;
    /** Reused: drawRegion runs once per card per frame. */
    private final Rect srcRect = new Rect();
    private final Rect dstRect = new Rect();
    private List<String> pool = new ArrayList<String>();
    private int poolIndex;
    /** 0.5.0: identity of the media currently decoded / playing. A config
     *  refresh that does NOT change the file must never decode the bitmap or
     *  restart the MediaPlayer again. */
    private String imageName;
    private String videoName;
    /**
     * 0.5.0-p76: PROCESS-LEVEL decoded-image cache. Every Activity builds a
     * NEW BackdropLayer, so L1 -> L2 -> L3 used to repeat the two
     * cross-process openInputStream() calls and a full-size JPEG decode on
     * every single page. One Bitmap, keyed by media name, is now shared by
     * all Activities in this host process: zero IPC, zero decode, zero large
     * allocation after the first page.
     *
     * A cached Bitmap must NEVER be recycled by an Activity's recycle():
     * another Activity may still be drawing it. It is only replaced (and
     * then left to GC) when the user picks a different background, so at
     * most two large bitmaps are briefly alive.
     */
    private static volatile String sImageCacheName;
    private static volatile Bitmap sImageCache;
    private static volatile int sDecodeMisses;
    private static volatile int sDecodeHits;
    /** True when `current` is the shared cached Bitmap (do NOT recycle). */
    private boolean currentFromCache;
    /**
     * 0.5.0-p80: a process-wide, tiny copy of the REAL chosen background.
     * Loaded once from the module's media provider during apply(), i.e.
     * BEFORE the first draw traversal. It exists so frame 0 can show the
     * wallpaper itself instead of a flat colour (§45.3: snap=0x0 on entry).
     */
    private static volatile Bitmap sWindowPreview;
    private static volatile boolean sWindowPreviewLoaded;
    /**
     * 0.5.0-p82 / report §4 A: generation of the process-wide preview.
     * A config change invalidates ConfigBus's cached Bitmap; without this
     * latch the HOST would keep painting the OLD background in frame 0 after
     * the user picks a new one, while SystemUI already shows the new one.
     */
    private static volatile int sWindowPreviewGen = -1;
    /** 0.5.0-p81: one default-visible line proving WHERE frame 0 came from. */
    private static volatile boolean sFirstFrameLogged;
    /**
     * 0.5.0-p81: the p80 read ran on the UI thread inside apply() and cached
     * its own failure forever. ConfigBus now prefetches on a daemon; this
     * callback repaints the window as soon as the picture arrives.
     */
    private final Runnable previewReady = new Runnable() {
        @Override
        public void run() {
            Bitmap p = ConfigBus.startingPreview();
            if (p == null) return;
            sWindowPreview = p;
            sWindowPreviewLoaded = true;
            refreshWindowBg();
        }
    };
    /**
     * 0.5.0-p67: filling state shared by the image, the video and the window
     * snapshot. styleKey is the ONLY thing snapshot() compares besides size --
     * the old cache survived a ScaleType change, so the glass kept refracting
     * the PREVIOUS filling for the rest of the Activity.
     */
    private String styleKey = "";
    private String snapshotStyleKey = "";
    /**
     * 0.5.0-p77: scalar state of the CURRENTLY baked opaque plate. Lets
     * ensurePlateSnapshot() decide "unchanged" without allocating a key
     * String on every draw frame. plateBakedKey is the EXACT String object
     * we stored in snapshotStyleKey when baking; an image snapshot or
     * recycle() assigns a DIFFERENT object (or ""), so the reference check
     * alone detects that screenBmp is no longer our plate -- no need to
     * touch snapshot()/recycle(), which stay byte-identical to p76 (L1
     * frozen). All three comparisons are scalar / reference, zero alloc.
     */
    private int plateBakedColor;
    private String plateBakedStyle;
    private String plateBakedKey;
    private String scaleMode = ScaleMode.CENTER_CROP;
    private int scaleZoom = 150;
    private int scaleFocusX = 50;
    private int scaleFocusY = 50;
    private int videoWidth;
    private int videoHeight;
    /**
     * 0.5.0-p73 -- VIDEO background: GPU-side LIVE backdrop.
     *
     * History: p69-p71 tried to sample a video frame into a CPU Bitmap
     * (opaque plate + throttled TextureView.getBitmap() + in-place bake +
     * half-resolution scaled sampling). Three attempts failed the user's
     * visual acceptance: on a CPU bitmap the frame CONTENT can never be
     * both real-time and identity-stable, so a moving video reads as an
     * offset ("拖动时错位"). p72 reverted the whole pipeline to p68
     * (video page has NO glass) by user order.
     *
     * This build opens a NEW chain, taken from the two upstream sources the
     * user pointed at (local clones: analysis/AndroidLiquidGlass @ kmp
     * 65ab177 / tag 2.0.1, and analysis/LiquidTab):
     *
     *   AndroidLiquidGlass (Kyant0, Apache-2.0)
     *     backdrops/LayerBackdropModifier.kt:
     *         ContentDrawScope.draw() { drawContent(); recordLayer(layer) {...} }
     *       -> the SAME frame's DRAW COMMANDS are recorded into a GPU layer,
     *          no pixel copy. DrawBackdropNode.draw() then recordLayer()s the
     *          backdrop into its OWN layer with a coordinate offset, applies
     *          the RenderEffect (blur + RuntimeShader) to that layer and
     *          drawLayer()s it. The library has NO video branch at all:
     *          re-recording every frame is inherently dynamic-content safe.
     *     backdrops/LayerBackdrop.kt:
     *         translate(-offset.x, -offset.y); drawLayer(graphicsLayer)
     *       -> the backdrop is PINNED to screen coordinates while the glass
     *          moves: exactly this project's p20 fixed-snapshot invariant,
     *          but in GPU display-list space instead of a CPU bitmap.
     *
     *   LiquidTab (GPL-3.0, LSPosed pure-View, same runtime class as us)
     *     hook/GlassHostLayout.kt:
     *       viewRenderNode() -> reflection on View.mRenderNode, REUSING the
     *         display list Android already produced in its normal traversal
     *         ("without synchronously redrawing ... into a software bitmap");
     *       liveBackdropNode -> beginRecording() + drawRenderNode(childNode)
     *         + endRecording() every visible frame, keeping the SAME node
     *         identity and the SAME (expensive) RenderEffect objects.
     *       Comments (the decisive facts):
     *         "Douyin's animated image/feed cards can legitimately change
     *          their RenderNode content every frame while keeping the same
     *          node identity."
     *         "Weibo video textures can submit new compositor frames without
     *          changing any View/RenderNode identity or geometry ... Mark the
     *          lightweight wrapper display list fresh every visible frame so
     *          the blur/refraction chain samples the current texture; keep
     *          the expensive effect objects intact."
     *
     * Concretely here: drawRegion() hard-gates VIDEO into
     * drawLiveVideoRegion(), which records the video View's RenderNode into
     * the glass container's OWN RenderNode (LiquidGlassView.onDraw() calls
     * node.beginRecording() EVERY frame -- the wrapper is therefore always
     * fresh, at zero extra cost). The shader's `content` uniform is then
     * the CURRENT video frame, sampled at the CURRENT frame's screen
     * position. No readback, no throttle, no allocation, no recycle, no
     * extra traversal, no temporal lag.
     *
     * The IMAGE path (videoMode == false) is byte-for-byte the frozen
     * L1 p30 / p67 code. Non-video no-CPU-bitmap states keep p69's opaque
     * plate and p70's identity-stability contract.
     *
     * Boundary: independent compositor surfaces (SurfaceView / full-screen
     * video in some apps) cannot be sampled by a parent RenderNode --
     * LiquidTab falls back to PixelCopy there. OUR video is a TextureView
     * OWNED BY THIS MODULE inside our own overlay, not a host page's
     * WebView, so re-recording its layer does not repaint a host page and
     * the GPU path applies. A public-API video.draw(canvas) fallback and an
     * objective LIVEGLASS log make this falsifiable on a real device.
     */
    /** Plate is a property of "no CPU bitmap", independent of BG_ENABLED and
     *  of the page slot. Always on until release(): even with the background
     *  switched off the glass containers can still be drawn. */
    private boolean plateActive = true;
    /** True while the CONFIGURED background is a video -> drawRegion() uses
     *  the GPU live layer (p73). Reset on leaving video / release. */
    private boolean videoMode;
    /**
     * 0.5.0-p73: STABLE IDENTITY of the video View's RenderNode. Resolved
     * once by reflection (LiquidTab viewRenderNode pattern) and then reused
     * for the whole Activity. Its DISPLAY LIST CONTENT is refreshed by
     * Android's normal draw traversal on every video frame; we never
     * replace or re-record this object ourselves.
     */
    private RenderNode liveVideoNode;
    private java.lang.reflect.Field videoNodeField;
    private boolean videoNodeFieldTried;
    /** Reused per-frame screen location of the video View (O(1)). */
    private final int[] videoLoc = new int[2];
    /** Objectively verifiable counters for the device log (no visual check). */
    private static volatile int sLiveNodeDraw;
    private static volatile int sLiveDrawFallback;
    private static volatile int sLiveFail;
    private static long sLastLiveLogMs;
    /** Full-screen plate ALLOCATIONS. Must stay FLAT during a drag. */
    private static volatile int sVideoAlloc;
    private static long sLastVideoLogMs;

    /**
     * 0.5.0-p74 -- OUTGOING transition backdrop for a VIDEO background.
     *
     * The IMAGE era (p43/p66) uses WindowBackdropDrawable + the p20 fixed
     * screen snapshot drawRegion(0,0,w,h), so the back-transition window
     * shows the SAME wallpaper as the settled page. A video has NO CPU
     * snapshot (p73 deliberately uses a GPU RenderNode instead), and
     * release(false) stops the player and sets the TextureView GONE, so the
     * live GPU layer disappears exactly when the transition needs it. The
     * window then falls back to a flat colour -- the missing transition
     * backdrop the user reported.
     *
     * This is NOT the p69-p71 CPU-sampling dead end (findings §58/§61):
     * those builds tried to feed a CONTINUOUS real-time video stream into
     * the glass shader, where content-freshness and object identity cannot
     * coexist. Here we freeze ONE frame, ONCE, at the moment the window is
     * about to be destroyed, for a STATIC transition picture. No temporal
     * lag, no identity churn, no per-frame cost.
     *
     * Lowest power by construction:
     *  - exactly ONE TextureView.getBitmap() per back transition, taken
     *    BEFORE stopVideo()/GONE while the SurfaceTexture still exists;
     *  - longest edge <= TRANSITION_MAX_EDGE (320 px), i.e. ~0.7 MB for a
     *    9:16 frame instead of a ~14 MB full-screen ARGB_8888 bitmap;
     * 0.5.0-p75: the transition picture must be the SAME geometry as the
     * video that was on screen at that instant. p74 re-derived a matrix with
     * ScaleMode.matrix() using (shrunken-frame size -> window bounds), while
     * applyVideoTransform() uses (intrinsic video size -> video View size).
     * Those two differ whenever the window bounds differ from the View, and
     * -- decisively -- right after picking a new video videoWidth/Height are
     * still 0 (set only in onPrepared()), so applyVideoTransform() returns
     * early while the re-derived matrix is still computed from the View size:
     * the frozen picture is stretched and cannot match the live backdrop.
     *
     * Fix: do NOT reconstruct any geometry. Ask the TextureView for the
     * transform that is ACTUALLY in effect (getTransform(null), the remedy
     * recorded in p69_report.txt §3 after verifying AOSP + real-device
     * reports that getBitmap() ignores setTransform()). Capture a frame at
     * the VIDEO VIEW's aspect ratio, then replay that SAME matrix while
     * baking into a small Bitmap that is a scaled copy of the on-screen
     * picture. WindowBackdropDrawable later only magnifies it 1:1; no
     * matrix is computed at draw time.
     *
     * Still one readback per back transition, still <=320 px, still no
     * blur effect chain / shader / timer / per-frame allocation.
     *  - every subsequent transition frame is one drawBitmap of that same
     *    static Bitmap: zero readback, zero allocation, zero recycle.
     */
    private Bitmap transitionFrame;
    private final Paint transitionPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect transitionDst = new Rect();
    private static final int TRANSITION_MAX_EDGE = 320;
    /** 0.5.0-p104: splash base map geometry (see scheduleSplashBase). */
    private static final int SPLASH_BASE_MAX_EDGE = 900;
    private static final long SPLASH_BASE_DELAY_MS = 900L;
    /** Last style signature whose settled backdrop was captured. */
    private String splashBaseSig;
    private int splashBaseTries;
    /** Whether the last video capture found a live transform (log only). */
    private boolean lastCaptureLive;
    /** Objectively verifiable ONE-SHOT counters for the device log. */
    private static volatile int sTransitionOk;
    private static volatile int sTransitionFail;

    private ConfigContract.Snapshot cfg;
    private ConfigContract.Slot slot;
    private boolean released;
    /** OEM window background saved at runtime, never written to disk. */
    private Drawable originalDecorBg;
    private boolean decorBgCleared;

    private final Runnable rotate = new Runnable() {
        @Override
        public void run() {
            if (released || cfg == null || pool.isEmpty()) return;
            poolIndex = (poolIndex + 1) % pool.size();
            showImage(pool.get(poolIndex));
            long minutes = Math.max(1L, cfg.asInt(
                    ConfigContract.Param.BG_RANDOM_INTERVAL_MIN));
            handler.postDelayed(this, minutes * 60L * 1000L);
        }
    };

    public BackdropLayer(Context ctx, FrameLayout overlay) {
        this.ctx = ctx;
        this.overlay = overlay;
        image = new ImageView(ctx);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setVisibility(View.GONE);
        // A MATRIX-based custom filling is expressed in PIXELS, so a rotation
        // or a size change must rebuild it. Preset ScaleTypes do this inside
        // ImageView themselves and are untouched by this listener.
        image.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b,
                                       int ol, int ot, int or, int ob) {
                if (r - l != or - ol || b - t != ob - ot) {
                    reapplyCustomMatrix(true);
                }
            }
        });
        video = new TextureView(ctx);
        video.setVisibility(View.GONE);
        video.setOpaque(false);
        tint = new View(ctx);
        tint.setVisibility(View.GONE);

        // Insert below the glass host, keeping the documented bottom-up order.
        overlay.addView(image, 0, fullScreen());
        overlay.addView(video, 1, fullScreen());
        overlay.addView(tint, 2, fullScreen());

        video.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                surface = st;
                startVideo();
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
                applyVideoTransform();
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                surface = null;
                stopVideo();
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture st) {
                // 0.5.0-p72: EMPTY again (p68). A video background has no
                // glass sampling at all, so there is nothing to observe here.
            }
        });
    }

    /** Re-apply for a (possibly different) level. Called on every config change. */
    /**
     * 0.4.0-p7: the picked image/video name, falling back to the GLOBAL slot.
     *
     * A per-level slot often stores an EMPTY string for bg.source (it inherits
     * by default), and the old code used that empty value directly -- so the
     * image the user actually picked at global level was never loaded and the
     * self-drawn glass had no backdrop. Empty now means "use global".
     */
    private String sourceName() {
        // 0.5.0: ConfigContract.resolve() already folded "inherit from global"
        // into the snapshot. Reading ConfigStore.prefs() here was wrong anyway
        // -- in a HOST process it opens the host's own (empty) preference file,
        // not the module's -- and added a disk read to every apply().
        String n = cfg == null ? "" : cfg.asString(ConfigContract.Param.BG_SOURCE);
        return n == null ? "" : n.trim();
    }


    /**
     * 0.4.0-p5: draw the part of the backdrop that sits under `screen` into the
     * canvas at (0,0,w,h). This is what makes the self-drawn glass possible
     * WITHOUT PixelCopy: the bitmap is already ours.
     *
     * The mapping reproduces ImageView's centerCrop exactly:
     *   scale = max(viewW/bmpW, viewH/bmpH), centred, then the screen offset.
     *
     * @return false when there is no usable Bitmap (video / pure colour / not
     *         laid out yet). The caller then falls back to a tint-only look.
     */
    /**
     * Screen-sized snapshot with the ImageView's own transform baked in.
     * Rebuilt lazily whenever the source bitmap or the view size changes.
     */
    private Bitmap snapshot() {
        Bitmap src = current;
        if (src == null || src.isRecycled()) {
            // 0.4.0-p16: never drop to null while a gesture is running. A single
            // frame with no bitmap made the glass fall back to the opaque plate,
            // which the eye reads as the backdrop "jumping".
            //
            // 0.5.0-p73: a VIDEO background never reaches this method --
            // drawRegion() hard-gates it into the GPU live layer. The
            // !videoMode guard stays as a second line of defence so a
            // caller that invokes snapshot() directly can never bake a
            // CPU plate for a video (the p69-p71 dead end). Every
            // NON-video state without a CPU bitmap (none / BG_ENABLED=false
            // / failed decode) keeps the p69 opaque plate, so the glass
            // cannot vanish there. The image branch below stays
            // byte-for-byte identical to the frozen L1 p30 / p67 code.
            if (plateActive && !videoMode) ensurePlateSnapshot();
            return screenBmp;
        }
        int w, h;
        try {
            w = image.getWidth();
            h = image.getHeight();
        } catch (Throwable t) {
            return screenBmp;
        }
        if (w <= 0 || h <= 0) return screenBmp;
        Bitmap cached = screenBmp;
        // 0.5.0-p67: a cached snapshot baked the OLD image matrix. Comparing
        // only width/height meant switching centreCrop -> fitCenter (or moving
        // a custom zoom) left the glass refracting the previous filling.
        if (cached != null && !cached.isRecycled()
                && cached.getWidth() == w && cached.getHeight() == h
                && styleKey.equals(snapshotStyleKey)) {
            return cached;
        }
        try {
            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            // Reuse whatever scaleType the ImageView is using, so the glass
            // refracts exactly the pixels the user sees behind it.
            Matrix m = image.getImageMatrix();
            if (m != null) c.concat(m);
            c.drawBitmap(src, 0f, 0f, null);
            screenBmp = out;
            snapshotStyleKey = styleKey;
            return out;
        } catch (Throwable t) {
            return cached;
        }
    }

    // ---- 0.5.0-p69: backdrop snapshot for states with no CPU bitmap --------

    /**
     * HARD GUARANTEE (NON-VIDEO states only -- see the p72 header note).
     * Build an OPAQUE, screen-sized Bitmap whenever the normal image-backed
     * snapshot is unavailable. drawRegion() then always succeeds, so
     * LiquidGlassView.onDraw() always reaches setRenderEffect()+drawRenderNode()
     * and the liquid-glass shader always runs. No GPU readback, no per-frame
     * allocation: the plate is reused until its key changes.
     *
     * The plate is deliberately a COPY of the same window base colour used by
     * WindowBackdropDrawable (p43/p66), so a failed/blank state can never
     * produce a black or transparent glass input.
     */
    private void ensurePlateSnapshot() {
        if (released) return;
        int w = image.getWidth();
        int h = image.getHeight();
        if (w <= 0 || h <= 0) {
            w = video.getWidth();
            h = video.getHeight();
        }
        if (w <= 0 || h <= 0) {
            w = overlay.getWidth();
            h = overlay.getHeight();
        }
        if (w <= 0 || h <= 0) return;
        Bitmap cached = screenBmp;
        // 0.5.0-p77 POWER: plateKey() built a NEW String (two concatenations
        // + Integer.toHexString) on EVERY frame here, even though the method
        // claimed "no allocation". The plate depends on exactly three scalar
        // inputs (size, window colour, filling style). Compare them without
        // allocating: the style is compared by reference with the object we
        // baked against, and only a REAL change reaches plateKey() below.
        // This removes ~1 String + 1 StringBuilder per draw frame in every
        // non-video no-bitmap state (none / BG off / failed decode).
        if (cached != null && !cached.isRecycled()
                && cached.getWidth() == w && cached.getHeight() == h
                && windowBgColor == plateBakedColor
                && styleKey == plateBakedStyle
                && snapshotStyleKey == plateBakedKey) {
            return;                     // TRUE O(1) hot path, ZERO allocation
        }
        // 0.5.0-p70: ALLOCATION HAPPENS ONLY HERE (size / colour / style
        // change), NEVER as a side effect of a video frame, and never in the
        // middle of a drag. The plate is a FIXED object afterwards.
        try {
            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            c.drawColor(windowBgColor);
            Bitmap old = screenBmp;
            screenBmp = out;
            // Keep snapshotStyleKey consistent with the p67 image branch:
            // snapshot() compares styleKey to it, so a plate must never be
            // mistaken for a previously baked image snapshot. plateKey() is
            // now called ONLY here (on a real change), not per frame.
            String key = plateKey();
            snapshotStyleKey = key;
            plateBakedKey = key;
            plateBakedColor = windowBgColor;
            plateBakedStyle = styleKey;
            sVideoAlloc++;
            if (old != null && old != current && !old.isRecycled()) {
                try {
                    old.recycle();
                } catch (Throwable ignored) {
                }
            }
            // A freshly allocated plate shows the CURRENT window base colour;
            // there is no video frame to bake any more (p72).
            notePlateLog();
        } catch (Throwable t) {
            GlassEntry.say("backdrop plate failed: " + t);
        }
    }

    /**
     * 0.5.0-p70: ONLY size / colour / filling-style changes create a new
     * plate. Frame counters are deliberately absent -- a plate change must
     * never be a per-frame event.
     */
    private String plateKey() {
        return "plate|" + styleKey + "|" + Integer.toHexString(windowBgColor);
    }

    /** Throttled, objectively readable device-log signal (no visual check). */
    private void notePlateLog() {
        long now = SystemClock.uptimeMillis();
        if (now - sLastVideoLogMs < 2000L) return;
        sLastVideoLogMs = now;
        // 0.5.0-p72 objective criterion (no visual check): with a VIDEO
        // background the log must show `FAIL why=1` (drawRegion false ->
        // tint-only glass, the p68 revert) and `alloc` must stay FLAT; with
        // an image background no new FAIL appears and `alloc` also stays
        // flat during a drag.
        GlassEntry.say("backdrop VIDGLASS p72 videoGlass=off"
                + " video=" + videoMode + " alloc=" + sVideoAlloc
                + " snap=" + (screenBmp == null ? 0 : screenBmp.getWidth())
                + "x" + (screenBmp == null ? 0 : screenBmp.getHeight())
                + " fail=" + sFailTotal);
    }

    /**
     * Draw the part of the FIXED snapshot that sits behind (left, top, w, h),
     * which are SCREEN coordinates. The image stays pinned; only the window
     * moves -- so the card slides over a stationary backdrop.
     *
     * 0.4.0-p20 EDGE CLAMP. A card that is partially scrolled off the top has a
     * NEGATIVE `top`, and one that runs past the bottom has `top + h` beyond
     * the snapshot. The old code passed that raw window straight to
     * drawBitmap(scaled), and for a window that starts outside the bitmap
     * Skia returns nothing at all -- so the whole card rendered as the opaque
     * white plate. That is the "拖动时会出现白块" the user reported, and it
     * happened exactly on the card being eaten by the top edge.
     * Clamp the source window to the snapshot and shift the destination by the
     * same amount, so the surviving part stays pixel-aligned.
     */
    /** 0.4.0-p29 diagnostic counters, read by RenderController's frames log. */
    private static volatile int sFailTotal;
    private static long sLastFailLogMs;
    private static final String[] FAIL_WHY = {"", "", ""};

    public static int diagFailCount() {
        return sFailTotal;
    }

    private static void noteFail(int code, int left, int top, int w, int h,
                                 int sw, int sh) {
        sFailTotal++;
        long now = SystemClock.uptimeMillis();
        if (now - sLastFailLogMs > 200L) {
            sLastFailLogMs = now;
            GlassEntry.say("backdrop FAIL why=" + code + " rect=" + left + ","
                    + top + " " + w + "x" + h + " snap=" + sw + "x" + sh
                    + " total=" + sFailTotal);
        }
    }

    /**
     * 0.5.0-p73: record the CURRENT video frame into the glass container's
     * OWN RenderNode. `c` is the RecordingCanvas obtained from
     * LiquidGlassView.onDraw()'s node.beginRecording(); (left, top) is that
     * container's CURRENT screen position (getLocationOnScreen + pad).
     *
     * The opaque base is painted first so a missing/blank texture can never
     * produce a black glass input (p42/p43 contract). Then the canvas is
     * translated from container-local into SCREEN coordinates, so the video
     * layer stays pinned to the screen while the card slides -- p20's
     * "image stays pinned; only the window moves", now in GPU space.
     *
     * Every call re-records the wrapper display list (the caller already
     * does beginRecording()/endRecording() per frame), which is exactly
     * LiquidTab's forceCompositorFrame requirement for video textures.
     * The RenderNode identity and the RenderEffect objects are NOT replaced.
     */
    private boolean drawLiveVideoRegion(Canvas c, int left, int top,
                                        int w, int h) {
        if (released || w <= 0 || h <= 0) return false;
        // BG_ENABLED=false / a stopped video keeps the p72/p68 tint-only
        // look instead of refracting a stale or empty layer.
        if (video.getVisibility() != View.VISIBLE) return false;
        // 0.5.0-p84: a VISIBLE TextureView whose SurfaceTexture is not ready
        // yet draws NOTHING, and returning true told the caller "the backdrop
        // is drawn" — so frame 0 kept the flat windowColour and the picture
        // only appeared later (the "loading" feel). Report failure instead,
        // which lets the real first-frame picture paint until the surface is
        // live; the hand-over is then invisible.
        try {
            if (!video.isAvailable()) return false;
        } catch (Throwable ignored) {
        }
        try {
            c.drawColor(windowBgColor);
            RenderNode rn = liveVideoRenderNode();
            video.getLocationOnScreen(videoLoc);
            c.save();
            c.translate(videoLoc[0] - left, videoLoc[1] - top);
            if (rn != null && rn.hasDisplayList()) {
                // Reuse the display list Android already produced for this
                // TextureView. Its drawTextureLayer() references the LIVE
                // SurfaceTexture, so replaying it samples the current frame.
                c.drawRenderNode(rn);
                sLiveNodeDraw++;
            } else {
                // Public-API fallback when mRenderNode is unavailable.
                // TextureView.draw() records the same texture layer into
                // whatever hardware canvas it is given.
                video.draw(c);
                sLiveDrawFallback++;
            }
            c.restore();
            noteLiveLog();
            return true;
        } catch (Throwable t) {
            sLiveFail++;
            GlassEntry.say("backdrop LIVEGLASS fail=" + sLiveFail
                    + " why=" + t);
            return false;
        }
    }

    /**
     * LiquidTab viewRenderNode() pattern: reflect View.mRenderNode once and
     * keep the SAME object. Never call updateDisplayListIfDirty() -- that
     * would actively re-record a second display list for the host View and
     * is the documented cause of alternating empty scenes on some renderers.
     */
    private RenderNode liveVideoRenderNode() {
        if (liveVideoNode != null) return liveVideoNode;
        if (!videoNodeFieldTried) {
            videoNodeFieldTried = true;
            try {
                videoNodeField = View.class.getDeclaredField("mRenderNode");
                videoNodeField.setAccessible(true);
            } catch (Throwable t) {
                videoNodeField = null;
                GlassEntry.say("backdrop LIVEGLASS mRenderNode absent: " + t);
            }
        }
        if (videoNodeField == null) return null;
        try {
            Object o = videoNodeField.get(video);
            if (o instanceof RenderNode) liveVideoNode = (RenderNode) o;
        } catch (Throwable ignored) {
        }
        return liveVideoNode;
    }

    /** Throttled, objectively readable device-log signal (no visual check). */
    private void noteLiveLog() {
        long now = SystemClock.uptimeMillis();
        if (now - sLastLiveLogMs < 2000L) return;
        sLastLiveLogMs = now;
        // p73 criterion: on a VIDEO background `src=node` and `node` grows
        // with every frame / drag, `fail=0`, and NO `FAIL why=1` appears.
        // `src=draw` means reflection failed and the public-API fallback is
        // in use. A frozen `node` count on a playing video means the glass
        // containers are not being redrawn (next candidate: a repaint hook).
        GlassEntry.say("backdrop LIVEGLASS mode=gpu video=" + videoMode
                + " src=" + (liveVideoNode != null ? "node" : "draw")
                + " node=" + sLiveNodeDraw
                + " draw=" + sLiveDrawFallback
                + " fail=" + sLiveFail
                + " total=" + sFailTotal);
    }

    /**
     * 0.5.0-p74/p75: freeze ONE small video frame for the outgoing
     * transition. Called ONLY from release(false), BEFORE stopVideo() /
     * setVisibility(GONE) so the SurfaceTexture is still alive. Never called
     * from a draw method (AOSP forbids getBitmap() there) and never from
     * onSurfaceTextureUpdated.
     *
     * TextureView.getBitmap(w,h) returns the RAW SurfaceTexture content and
     * IGNORES setTransform() (p69 verified AOSP javadoc + real-device report;
     * the recorded remedy is to re-apply getTransform(null) afterwards).
     * p75 uses exactly that: the ACTUAL live transform, replayed while
     * baking, so the snapshot is a scaled copy of what was on screen --
     * including any custom stretch / focus applied in settings, and even
     * when videoWidth/Height are not known yet (fresh video, prepareAsync).
     */
    private void captureTransitionFrame() {
        if (surface == null || video.getVisibility() != View.VISIBLE) return;
        try {
            Bitmap out = captureVideoFrame(TRANSITION_MAX_EDGE);
            if (out == null) throw new IllegalStateException("null frame");
            Bitmap old = transitionFrame;
            transitionFrame = out;
            if (old != null && !old.isRecycled()) {
                try {
                    old.recycle();
                } catch (Throwable ignored) {
                }
            }
            sTransitionOk++;
            // `snap=once` + a counter that must NOT grow during the
            // transition animation is the objective "lowest power" check.
            // view/snap/live let the device log verify the geometry.
            GlassEntry.say("backdrop TRANSITION snap=once view="
                    + video.getWidth() + "x" + video.getHeight()
                    + " snap=" + out.getWidth() + "x" + out.getHeight()
                    + " live=" + (lastCaptureLive ? 1 : 0)
                    + " ok=" + sTransitionOk + " fail=" + sTransitionFail);
        } catch (Throwable t) {
            sTransitionFail++;
            // Failure keeps p43's opaque window base colour: never black,
            // never the OEM white card background.
            GlassEntry.say("backdrop TRANSITION fail=" + sTransitionFail
                    + " why=" + t);
        }
    }

    /**
     * 0.5.0-p104: the SAME capture p75 proved correct, but parameterised so the
     * splash base map can ask for a bigger one. Returns a NEW bitmap with the
     * live transform baked in; the caller owns it.
     *
     * getBitmap() returns the raw SurfaceTexture content and ignores
     * setTransform(), so the ACTUAL transform from getTransform(null) is
     * replayed -- never a recomputed ScaleMode.matrix(). That is exactly why
     * this is the right source for "the splash must look like the settled
     * background": no geometry is derived twice.
     */
    private Bitmap captureVideoFrame(int maxEdge) {
        if (surface == null || video.getVisibility() != View.VISIBLE) return null;
        int viewW = video.getWidth();
        int viewH = video.getHeight();
        if (viewW <= 0 || viewH <= 0) return null;
        float k = Math.min(1f, (float) maxEdge / Math.max(viewW, viewH));
        int tw = Math.max(1, Math.round(viewW * k));
        int th = Math.max(1, Math.round(viewH * k));
        // Aspect ratio of the VIDEO VIEW, not the intrinsic video size:
        // getBitmap() stretches the SurfaceTexture content into exactly this
        // bitmap, so no pre-distortion is introduced here.
        Bitmap raw = video.getBitmap(tw, th);
        if (raw == null) return null;
        Matrix live = null;
        try {
            live = video.getTransform(null);
        } catch (Throwable ignored) {
        }
        lastCaptureLive = live != null;
        Bitmap out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(windowBgColor);
        // raw pixels -> View coordinates -> LIVE transform -> small bitmap
        // coordinates. Canvas transforms compose as A * B * C * p, so the draw
        // call below is exactly:
        //   scale(tw/viewW, th/viewH) * live * scale(viewW/tw, viewH/th)
        c.scale((float) tw / viewW, (float) th / viewH);
        if (live != null) c.concat(live);
        c.scale((float) viewW / tw, (float) viewH / th);
        c.drawBitmap(raw, 0f, 0f, transitionPaint);
        if (!raw.isRecycled()) raw.recycle();
        return out;
    }

    /**
     * 0.5.0-p104: hand the SETTLED backdrop to the module process so the next
     * starting window shows exactly this framing (user's proposal: use the
     * size/position of the finished background as the splash base map).
     *
     * One shot per style signature per process, on a delayed main-thread turn
     * so it runs after layout / after the first video frame exists. It is NOT
     * on any draw path and it never touches the per-frame code.
     */
    private void scheduleSplashBase() {
        if (released || cfg == null) return;
        String name = sourceName();
        if (name.length() == 0) return;
        String sig = SplashBase.signature(name, scaleMode, scaleZoom,
                scaleFocusX, scaleFocusY);
        if (sig == null || sig.equals(splashBaseSig)) return;
        splashBaseSig = sig;                    // once per signature
        splashBaseTries = 0;
        handler.removeCallbacks(captureBase);
        handler.postDelayed(captureBase, SPLASH_BASE_DELAY_MS);
    }

    private final Runnable captureBase = new Runnable() {
        @Override
        public void run() {
            if (released || cfg == null) return;
            try {
                Bitmap b = videoMode ? captureVideoFrame(SPLASH_BASE_MAX_EDGE)
                        : snapshot();
                if (b == null || b.isRecycled()) {
                    // The page may not be laid out yet, or a fresh video has no
                    // SurfaceTexture frame. Bounded retry, then give up and keep
                    // the re-composed preview as the fallback.
                    if (splashBaseTries++ < 3) {
                        handler.postDelayed(this, SPLASH_BASE_DELAY_MS);
                    } else {
                        GlassEntry.say("mg-state splashBase skip why=noframe");
                    }
                    return;
                }
                GlassEntry.say("mg-state splashBase grab=" + b.getWidth() + "x"
                        + b.getHeight() + " src=" + (videoMode ? "video" : "image"));
                // The bitmap is owned by the backdrop (snapshot() returns the
                // LIVE snapshot; the video capture is ours). commitAsync never
                // recycles it and only reads it on a worker thread.
                SplashBase.commitAsync(ctx, b, splashBaseSig);
            } catch (Throwable t) {
                GlassEntry.say("mg-state splashBase skip why=" + t);
            }
        }
    };

    /** Real detach (not a finishing transition): free the one-shot frame. */
    private void releaseTransitionFrame() {
        Bitmap t = transitionFrame;
        transitionFrame = null;
        if (t != null && !t.isRecycled()) {
            try {
                t.recycle();
            } catch (Throwable ignored) {
            }
        }
    }

    public boolean drawRegion(Canvas c, int left, int top, int w, int h) {
        // 0.5.0-p73 HARD GATE. A video background NEVER touches the CPU
        // snapshot path (the p69-p71 dead end). Every other state -- image,
        // L1, none, BG off, failed decode -- runs the EXACT frozen p20/p67
        // branch below, line for line. LiquidGlassView is untouched.
        if (videoMode && !released) {
            return drawLiveVideoRegion(c, left, top, w, h);
        }
        Bitmap snap = snapshot();
        if (snap == null || w <= 0 || h <= 0) {
            noteFail(1, left, top, w, h, snap == null ? 0 : snap.getWidth(),
                    snap == null ? 0 : snap.getHeight());
            return false;
        }
        try {
            int sw = snap.getWidth();
            int sh = snap.getHeight();
            int x0 = Math.max(0, left);
            int y0 = Math.max(0, top);
            int x1 = Math.min(sw, left + w);
            int y1 = Math.min(sh, top + h);
            if (x1 <= x0 || y1 <= y0) {
                noteFail(2, left, top, w, h, sw, sh);
                return false;
            }
            srcRect.set(x0, y0, x1, y1);
            dstRect.set(x0 - left, y0 - top, x1 - left, y1 - top);
            c.drawBitmap(snap, srcRect, dstRect, null);
            return true;
        } catch (Throwable t) {
            noteFail(3, left, top, w, h, snap.getWidth(), snap.getHeight());
            return false;
        }
    }

    public void apply(ConfigContract.Slot slot, ConfigContract.Snapshot cfg) {
        if (released) return;
        this.slot = slot;
        this.cfg = cfg;
        handler.removeCallbacks(rotate);
        pool = parsePool(cfg.asString(ConfigContract.Param.BG_RANDOM_POOL));

        // p43/p66: install OUR opaque window backdrop in EVERY config state,
        // BEFORE the media switch is evaluated. Some scoped apps (notably
        // com.hihonor.wallet) put an opaque white windowBackground on the
        // DecorView itself; syncRows() stops at the decor and keepBackground()
        // whitelists decorview, so that layer would otherwise hide the overlay
        // AND paint the whole transition white. release(true) still restores
        // the exact ROM Drawable when the module genuinely stops.
        clearWindowBackground();
        // 0.5.0-p80: have the real background picture ready before the first
        // frame is drawn. One small provider read per host process; the
        // window backdrop only uses it when drawRegion() has no snapshot.
        ensureWindowPreview();
        if (!cfg.asBool(ConfigContract.Param.BG_ENABLED)) {
            // BG_ENABLED controls the MEDIA layers only. The old hide() ->
            // hide(true) restored the ROM's white decor Drawable, which is
            // exactly the "白屏转场" on back/enter.
            hideMedia();
            return;
        }
        // 0.5.0-p67: filling is a property of the BACKGROUND, not of the
        // image only. Apply it BEFORE the media branch so video and image
        // share one mode and one custom zoom / focus.
        applyImageStyle(cfg);
        applyTint(cfg);
        String kind = cfg.asString(ConfigContract.Param.BG_KIND);
        if ("video".equals(kind)) {
            image.setVisibility(View.GONE);
            image.setImageBitmap(null);
            // 0.5.0-p73: recycle() still clears current + screenBmp -- the
            // GPU live layer needs NO CPU bitmap at all. drawRegion() now
            // samples the video TextureView's RenderNode directly, so the
            // glass refracts the CURRENT frame. Only the window base colour
            // is kept in sync for transition frames.
            videoMode = true;
            windowBgColor = ConfigContract.windowColorOf(cfg);
            recycle();
            showVideo(sourceName());
            applyVideoTransform();
            scheduleSplashBase();
            return;
        }
        // Leaving video mode: no captured frame exists any more (p72); just
        // drop the flag. The next image/none branch rebuilds its own state.
        videoMode = false;
        stopVideo();
        video.setVisibility(View.GONE);
        if ("none".equals(kind)) {
            image.setVisibility(View.GONE);
            image.setImageBitmap(null);
            recycle();
            // 0.5.0-p70: bake the plate here, not lazily from the next
            // onDraw(). Keeps the draw frame path allocation-free.
            ensurePlateSnapshot();
            return;
        }

        String name = sourceName();
        boolean random = cfg.asBool(ConfigContract.Param.BG_RANDOM_ENABLED)
                && !pool.isEmpty();
        if (random) {
            poolIndex = Math.abs((int) (System.currentTimeMillis() / 1000L)) % pool.size();
            name = pool.get(poolIndex);
        }
        showImage(name);
        scheduleSplashBase();
        if (random) {
            long minutes = cfg.asInt(ConfigContract.Param.BG_RANDOM_INTERVAL_MIN);
            if (minutes > 0) {
                handler.postDelayed(rotate, minutes * 60L * 1000L);
            }
        }
    }

    // ---- image --------------------------------------------------------------

    private void showImage(String name) {
        if (released || name == null || name.length() == 0) {
            image.setVisibility(View.GONE);
            return;
        }
        // 0.5.0 LIVE BACKGROUND: refresh() re-applies the whole config on
        // every edit. Without this guard a slider drag would re-decode a
        // multi-megapixel JPEG on each step. When the file is unchanged only
        // the (cheap) style is re-applied by applyImageStyle().
        if (name.equals(imageName) && current != null && !current.isRecycled()) {
            image.setVisibility(View.VISIBLE);
            applyWindowBaseColor();
            return;
        }
        Bitmap bmp = decode(name);
        if (bmp == null) {
            // Do not leave a stale Bitmap attached to a GONE ImageView: the
            // previous wallpaper would keep its memory until the next good
            // decode, and a failed random-pool rotation would show nothing
            // while still holding a full-resolution Bitmap.
            image.setImageBitmap(null);
            image.setVisibility(View.GONE);
            recycle();
            return;
        }
        recycle();
        current = bmp;
        // recycle() clears the flag; restore it for the new (possibly
        // process-cached) Bitmap so this Activity never recycles a shared one.
        currentFromCache = (bmp == sImageCache && bmp != null);
        imageName = name;
        image.setImageBitmap(bmp);
        // A newly decoded file can have different intrinsic dimensions; the
        // custom matrix is pixel-based and must be recomputed for it.
        reapplyCustomMatrix(false);
        image.setVisibility(View.VISIBLE);
        applyWindowBaseColor();
    }

    private Bitmap decode(String name) {
        // 0.5.0-p76: reuse the process-level decoded Bitmap. This is the
        // main first-frame cost after the provider has been warmed up:
        // two cross-process stream opens + a multi-megapixel decode.
        long t0 = SystemClock.uptimeMillis();
        Bitmap hit = sImageCache;
        String hitName = sImageCacheName;
        if (hit != null && !hit.isRecycled() && name.equals(hitName)) {
            sDecodeHits++;
            currentFromCache = true;
            GlassEntry.say("backdrop decode cache=hit ms="
                    + (SystemClock.uptimeMillis() - t0)
                    + " hits=" + sDecodeHits
                    + " size=" + hit.getWidth() + "x" + hit.getHeight());
            return hit;
        }
        currentFromCache = false;
        // Two-pass decode: read bounds first, then choose inSampleSize.
        try {
            Uri uri = ConfigBus.media(name);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            InputStream in = ctx.getContentResolver().openInputStream(uri);
            if (in == null) return null;
            try {
                BitmapFactory.decodeStream(in, null, bounds);
            } finally {
                close(in);
            }
            int sample = 1;
            while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 3000) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            InputStream in2 = ctx.getContentResolver().openInputStream(uri);
            if (in2 == null) return null;
            Bitmap bmp;
            try {
                bmp = BitmapFactory.decodeStream(in2, null, opts);
            } finally {
                close(in2);
            }
            if (bmp != null) {
                sImageCacheName = name;
                sImageCache = bmp;
                sDecodeMisses++;
                GlassEntry.say("backdrop decode cache=miss ms="
                        + (SystemClock.uptimeMillis() - t0)
                        + " sample=" + sample
                        + " size=" + bmp.getWidth() + "x" + bmp.getHeight()
                        + " misses=" + sDecodeMisses);
            }
            return bmp;
        } catch (Throwable t) {
            GlassEntry.say("backdrop decode failed: " + t);
            return null;
        }
    }

    private void applyImageStyle(ConfigContract.Snapshot cfg) {
        String mode = ScaleMode.normalize(cfg.asString(ConfigContract.Param.BG_SCALE));
        int zoom = ScaleMode.zoomOf(cfg);
        int fx = ScaleMode.focusXOf(cfg);
        int fy = ScaleMode.focusYOf(cfg);
        String key = ScaleMode.styleKey(mode, zoom, fx, fy);
        boolean styleChanged = !key.equals(styleKey);
        styleKey = key;
        scaleMode = mode;
        scaleZoom = zoom;
        scaleFocusX = fx;
        scaleFocusY = fy;

        if (ScaleMode.isCustom(mode)) {
            image.setScaleType(ImageView.ScaleType.MATRIX);
            reapplyCustomMatrix(false);
        } else {
            // Preset modes keep the ORIGINAL ImageView path. In particular the
            // default centerCrop is exactly the pixels L1 p30 accepted.
            image.setScaleType(ScaleMode.scaleTypeOf(mode));
        }
        int opacity = Math.max(0, Math.min(100,
                cfg.asInt(ConfigContract.Param.BG_OPACITY)));
        image.setAlpha(opacity / 100f);
        try {
            if (cfg.asBool(ConfigContract.Param.BG_BLUR_ENABLED)) {
                float r = Math.max(1f,
                        cfg.asFloat(ConfigContract.Param.BG_BLUR_RADIUS_DP));
                image.setRenderEffect(RenderEffect.createBlurEffect(
                        r, r, Shader.TileMode.CLAMP));
            } else {
                image.setRenderEffect(null);
            }
        } catch (Throwable t) {
            GlassEntry.say("backdrop blur unsupported: " + t);
        }
        if (styleChanged) {
            dropSnapshot();
            refreshWindowBg();
            applyVideoTransform();
            GlassEntry.say("backdrop fill mode=" + mode + " zoom=" + zoom
                    + " focus=" + fx + "," + fy + " video=" + videoWidth
                    + "x" + videoHeight);
        }
    }

    /** Custom image filling and the settings preview use this SAME matrix. */
    private void reapplyCustomMatrix(boolean invalidate) {
        if (!ScaleMode.isCustom(scaleMode) || current == null || current.isRecycled()) {
            return;
        }
        Matrix m = ScaleMode.matrix(scaleMode, scaleZoom, scaleFocusX, scaleFocusY,
                current.getWidth(), current.getHeight(),
                image.getWidth(), image.getHeight());
        if (m == null) return;
        image.setImageMatrix(m);
        if (invalidate) {
            dropSnapshot();
            refreshWindowBg();
        }
    }

    /** TextureView has no ScaleType: EVERY video mode is expressed as one Matrix. */
    private void applyVideoTransform() {
        int vw = video.getWidth(), vh = video.getHeight();
        if (vw <= 0 || vh <= 0 || videoWidth <= 0 || videoHeight <= 0) return;
        Matrix m = ScaleMode.matrix(scaleMode, scaleZoom, scaleFocusX, scaleFocusY,
                videoWidth, videoHeight, vw, vh);
        if (m == null) return;
        try {
            video.setTransform(m);
        } catch (Throwable t) {
            GlassEntry.say("backdrop video transform unsupported: " + t);
        }
    }

    /** Discard the baked screen snapshot without touching the source bitmap. */
    private void dropSnapshot() {
        Bitmap s = screenBmp;
        screenBmp = null;
        snapshotStyleKey = "";
        if (s != null && s != current && !s.isRecycled()) {
            try {
                s.recycle();
            } catch (Throwable ignored) {
            }
        }
    }

    // ---- video --------------------------------------------------------------

    private void showVideo(String name) {
        if (name == null || name.length() == 0) {
            video.setVisibility(View.GONE);
            return;
        }
        video.setVisibility(View.VISIBLE);
        if (surface != null) startVideo();
    }

    private void startVideo() {
        if (released || cfg == null || surface == null) return;
        String name = sourceName();
        if (name == null || name.length() == 0
                || !"video".equals(cfg.asString(ConfigContract.Param.BG_KIND))) return;
        float vol = cfg.asBool(ConfigContract.Param.BG_VIDEO_MUTED) ? 0f : 1f;
        // 0.5.0: a config refresh must not tear down and re-prepare a running
        // video decoder. Only a NEW file (or a real stop) restarts it.
        if (player != null && name.equals(videoName)) {
            try {
                player.setVolume(vol, vol);
            } catch (Throwable ignored) {
            }
            return;
        }
        stopVideo();
        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setDataSource(ctx, ConfigBus.media(name));
            mp.setSurface(new Surface(surface));
            mp.setLooping(true);
            mp.setVolume(vol, vol);
            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer mp) {
                    try {
                        // Video has NO intrinsic ScaleType: the real frame
                        // size only becomes known here. Apply the very same
                        // filling matrix the image path uses.
                        videoWidth = mp.getVideoWidth();
                        videoHeight = mp.getVideoHeight();
                        applyVideoTransform();
                        if (!released) mp.start();
                    } catch (Throwable ignored) {
                    }
                }
            });
            mp.prepareAsync();
            player = mp;
            videoName = name;
        } catch (Throwable t) {
            GlassEntry.say("backdrop video failed: " + t);
        }
    }

    private void stopVideo() {
        MediaPlayer mp = player;
        player = null;
        videoName = null;
        videoWidth = 0;
        videoHeight = 0;
        if (mp == null) return;
        try {
            if (mp.isPlaying()) mp.stop();
        } catch (Throwable ignored) {
        }
        try {
            mp.reset();
        } catch (Throwable ignored) {
        }
        try {
            mp.release();
        } catch (Throwable ignored) {
        }
    }

    // ---- tint / lifecycle ---------------------------------------------------

    /**
     * 0.5.0-p81 (plan C, findings §70.4): the tiny REAL background picture
     * is prefetched by ConfigBus on a MIN_PRIORITY daemon at onPackageLoaded.
     * apply() only performs a volatile read -- NO IPC and NO decode on the UI
     * thread. Failure is NOT cached: requestFirstFrame() allows a bounded
     * retry, and postPreviewReady() repaints this window when the daemon
     * finishes. The p80 defect (loaded=true on an empty stream / exception)
     * was the reason a cold start kept the flat colour forever.
     */
    private void ensureWindowPreview() {
        // 0.5.0-p82 / report §4 A "refresh on config change": SystemUI is
        // long-lived and refreshes its static picture; the host must follow.
        // Only a volatile int read + a volatile boolean write, no IPC, no
        // decode, no allocation, and NOT on a per-frame path (called by
        // apply() only). Never recycle: a live View may hold the old Bitmap.
        int gen = ConfigBus.previewGeneration();
        if (sWindowPreviewGen != gen) {
            sWindowPreviewGen = gen;
            sWindowPreview = null;
            sWindowPreviewLoaded = false;
            GlassEntry.say("mg-state windowPreview refresh gen=" + gen);
        }
        if (sWindowPreviewLoaded) return;
        Bitmap p = ConfigBus.requestFirstFrame(ctx, previewReady);
        if (p == null) return;              // retry on the next apply()
        sWindowPreview = p;
        sWindowPreviewLoaded = true;        // success only
        GlassEntry.say("mg-state windowPreview load="
                + p.getWidth() + "x" + p.getHeight());
        refreshWindowBg();                  // repaint immediately
    }

    /**
     * Runtime-only: detach the OEM opaque window drawable from the DecorView.
     * This changes a View property in memory. It does NOT touch /system, any
     * APK, or any file on the device. The original Drawable is kept so hide()
     * and release() restore the exact ROM appearance.
     */
    private void clearWindowBackground() {
        if (decorBgCleared || !(ctx instanceof Activity)) return;
        try {
            View d = ((Activity) ctx).getWindow().getDecorView();
            if (d == null) return;
            Drawable bg = d.getBackground();
            // Do not overwrite the saved OEM Drawable with our own when a
            // media-only hide/re-apply cycle calls this method again.
            if (bg != windowBgDrawable) originalDecorBg = bg;
            // NEVER leave the window with a null background (p42 black) or
            // with the OEM white theme background (white transition).
            if (windowBgDrawable == null) {
                windowBgDrawable = new WindowBackdropDrawable();
            }
            d.setBackground(windowBgDrawable);
            decorBgCleared = true;
        } catch (Throwable ignored) {
            originalDecorBg = null;
        }
    }

    /** Re-attach / repaint the window backdrop after ANY backdrop change. */
    private void refreshWindowBg() {
        if (!(ctx instanceof Activity)) return;
        try {
            View d = ((Activity) ctx).getWindow().getDecorView();
            if (d == null) return;
            if (windowBgDrawable != null && d.getBackground() != windowBgDrawable) {
                d.setBackground(windowBgDrawable);
            }
            d.invalidate();
        } catch (Throwable ignored) {
        }
    }

    /** Diagnostic: is the current DecorView background ours, OEM's, or absent? */
    public String windowBgKind() {
        try {
            if (!(ctx instanceof Activity)) return "na";
            View d = ((Activity) ctx).getWindow().getDecorView();
            Drawable bg = d == null ? null : d.getBackground();
            if (bg == null) return "null";
            return bg == windowBgDrawable ? "ours" : "oem";
        } catch (Throwable ignored) {
            return "na";
        }
    }

    /**
     * Stub-safe base colour. The local javac stubs expose neither
     * Bitmap.getPixel nor ColorDrawable, so derive the colour from the
     * configured tint (Color.parseColor is already used by applyTint) and
     * otherwise keep the neutral default. This colour is visible ONLY in
     * transition frames where our overlay has not been composited yet.
     */
    private void applyWindowBaseColor() {
        if (cfg == null) return;
        windowBgColor = ConfigContract.windowColorOf(cfg);
        refreshWindowBg();
        GlassEntry.say("window base colour="
                + Integer.toHexString(windowBgColor));
    }

    private void restoreWindowBackground() {
        if (!decorBgCleared) return;
        decorBgCleared = false;
        try {
            if (ctx instanceof Activity) {
                View d = ((Activity) ctx).getWindow().getDecorView();
                if (d != null) d.setBackground(originalDecorBg);
            }
        } catch (Throwable ignored) {
        }
        originalDecorBg = null;
    }

    /**
     * 0.3.5-p2. Tint and dim used to share ONE View whose alpha was set to
     * dim/100 -- with the default bg.dim=0 a user supplied #AARRGGBB tint was
     * multiplied by 0 and became completely invisible. Compose the two layers
     * properly instead: source-over alpha of (tint RGB at its own alpha) and
     * (black at dim%), then apply the resulting single color to the View.
     * No extra layer is added, so the overlay z-order is unchanged.
     */
    private void applyTint(ConfigContract.Snapshot cfg) {
        String raw = cfg.asString(ConfigContract.Param.BG_TINT_COLOR);
        int tintColor = Color.BLACK;
        boolean hasTint = false;
        if (raw != null && raw.length() > 0) {
            try {
                tintColor = Color.parseColor(raw);
                hasTint = true;
            } catch (Throwable ignored) {
            }
        }
        int dim = Math.max(0, Math.min(100, cfg.asInt(ConfigContract.Param.BG_DIM)));
        float aDim = dim / 100f;
        float aTint = hasTint ? Color.alpha(tintColor) / 255f : 0f;
        // Two translucent layers over the wallpaper: tint (colored) then dim
        // (black). Combined coverage = aTint + aDim * (1 - aTint).
        float alpha = aTint + aDim * (1f - aTint);
        if (alpha <= 0f) {
            windowTintARGB = 0;
            tint.setVisibility(View.GONE);
            refreshWindowBg();
            return;
        }
        // The black dim contributes no RGB, so the resulting color is the
        // tint RGB scaled by its share of the combined coverage.
        float share = aTint / alpha;
        int r = hasTint ? Math.round(Color.red(tintColor) * share) : 0;
        int g = hasTint ? Math.round(Color.green(tintColor) * share) : 0;
        int b = hasTint ? Math.round(Color.blue(tintColor) * share) : 0;
        tint.setBackgroundColor(Color.argb(255, r, g, b));
        tint.setAlpha(alpha);
        tint.setVisibility(View.VISIBLE);
        // Same composition on the WINDOW backdrop, so transition frames do
        // not flash an undimmed wallpaper before the tint layer composites.
        windowTintARGB = Color.argb(Math.round(alpha * 255f), r, g, b);
        refreshWindowBg();
    }

    /** Media layers off, decor background still ours (no white transition). */
    private void hideMedia() {
        handler.removeCallbacks(rotate);
        stopVideo();
        video.setVisibility(View.GONE);
        tint.setVisibility(View.GONE);
        image.setVisibility(View.GONE);
        image.setImageBitmap(null);
        windowTintARGB = 0;
        // 0.5.0-p72: with a VIDEO configured, BG off keeps the p68 look --
        // no glass sampling. Every other kind keeps the p69 opaque plate so
        // BG off does not kill the glass. videoMode mirrors the CONFIGURED
        // kind here (apply() returns early before the media switch), so a
        // video -> image switch while BG is off cannot get stuck.
        videoMode = cfg != null
                && "video".equals(cfg.asString(ConfigContract.Param.BG_KIND));
        recycle();
        if (plateActive && !videoMode) ensurePlateSnapshot();
        refreshWindowBg();
    }

    private final class WindowBackdropDrawable extends Drawable {
        @Override
        public void draw(Canvas c) {
            Rect b = getBounds();
            int w = b.width(), h = b.height();
            // Opaque base first: never null (p42 black), never OEM white.
            c.drawColor(windowBgColor);
            if (w > 0 && h > 0) {
                // 0.5.0-p75: transitionFrame is ALREADY a scaled copy of
                // the on-screen video picture (live transform baked in at
                // capture time). Magnify it 1:1 -- NO matrix is recomputed
                // here, so the window can never disagree with the backdrop
                // the user was looking at (p74's stretch/offset defect).
                // Every other state (image / L1 / none / BG off) keeps the
                // p20/p66 fixed-snapshot path, byte-for-byte unchanged.
                Bitmap t = transitionFrame;
                boolean snapshot;
                Bitmap previewUsed = null;
                if (t != null && !t.isRecycled()) {
                    transitionDst.set(0, 0, w, h);
                    c.drawBitmap(t, null, transitionDst, transitionPaint);
                    snapshot = false;
                } else {
                    snapshot = drawRegion(c, 0, 0, w, h);
                    if (!snapshot) {
                        // 0.5.0-p80: FIRST FRAME. §45.3 measured
                        // "backdrop FAIL why=1 ... snap=0x0" on entry: the View
                        // tree is not laid out yet, so there is NO screen snapshot
                        // and the accepted p20 path cannot draw. Previously the
                        // window kept only drawColor(windowBgColor) — the visible
                        // white -> wallpaper jump. Paint the REAL chosen
                        // background picture (p81: daemon-prefetched) instead.
                        // Runs ONLY when the accepted p20/p66 path fails, so
                        // p74/p75 transitions and the settled image/L1 path
                        // stay unchanged.
                        // 0.5.0-p84: RACE FIX, measured on device. The
                        // prefetch daemon can finish BETWEEN apply() and the
                        // first draw, so the host cache (sWindowPreview) is
                        // still null even though the process-wide picture is
                        // ready -- runs alternated between src=preview and
                        // src=color, i.e. sometimes a flat colour flashed
                        // before the background. Read the process-wide bitmap
                        // directly as a fallback: one volatile read, no IPC,
                        // no allocation, only while the cache is empty.
                        Bitmap p = sWindowPreview;
                        if (p == null || p.isRecycled()) {
                            p = ConfigBus.startingPreview();
                        }
                        if (p != null && !p.isRecycled()) {
                            transitionDst.set(0, 0, w, h);
                            c.drawBitmap(p, null, transitionDst, transitionPaint);
                            previewUsed = p;
                        }
                    }
                }
                // 0.5.0-p81: ONE default-visible line measuring WHERE the
                // first frame really came from. p81..p83 suppressed it on L1
                // (the frozen page) — which is exactly the page the user
                // enters first, so the report had no evidence for the case it
                // was written for. It is a ONE-SHOT line per process, not a
                // per-frame path, and carries slot= instead.
                if (!sFirstFrameLogged) {
                    sFirstFrameLogged = true;
                    // 0.5.0-p88: report the bitmap that was ACTUALLY drawn.
                    // The old line read sWindowPreview (the host-side cache),
                    // which can still be empty on frame 0 while the
                    // process-wide picture supplied by ConfigBus is already in
                    // use -- the measured "src=preview preview=none"
                    // contradiction. Diagnostics must describe the object.
                    Bitmap used = previewUsed != null ? previewUsed : sWindowPreview;
                    GlassEntry.say("mg-state firstFrame src="
                            + (t != null && !t.isRecycled() ? "transition"
                                    : snapshot ? "snapshot"
                                    : previewUsed != null ? "preview" : "color")
                            + " slot=" + slot + " preview="
                            + (used == null || used.isRecycled() ? "none"
                                    : used.getWidth() + "x" + used.getHeight())
                            // 0.5.0-p89: where the first frame sits on the
                            // startup timeline, same clock as `stage` lines.
                            + " at=+" + GlassEntry.stageAt() + "ms");
                }
            }
            if (windowTintARGB != 0) c.drawColor(windowTintARGB);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
        }

        @Override
        public int getOpacity() {
            return -1; // PixelFormat.OPAQUE
        }
    }

    private void hide() {
        hide(true);
    }

    /**
     * 0.4.0-p39. restoreOem=false releases every resource but LEAVES the
     * DecorView background cleared. When the Activity is FINISHING, the
     * outgoing window is still composited during the back transition, so
     * putting the OEM opaque window Drawable back makes the stock white cards
     * reappear for the whole transition. There is no point restoring a View
     * that is about to be destroyed -- just drop our reference to it.
     */
    private void hide(boolean restoreOem) {
        if (restoreOem) {
            restoreWindowBackground();
        } else {
            decorBgCleared = false;
            originalDecorBg = null;
        }
        handler.removeCallbacks(rotate);
        stopVideo();
        video.setVisibility(View.GONE);
        tint.setVisibility(View.GONE);
        image.setVisibility(View.GONE);
        image.setImageBitmap(null);
        recycle();
    }

    public void release() {
        release(true);
    }

    public void release(boolean restoreOem) {
        // 0.5.0-p74: remember the configured kind BEFORE videoMode is
        // cleared below. Only a VIDEO background needs a one-shot frame.
        boolean wasVideo = videoMode;
        released = true;
        // 0.5.0-p104: a pending base-map capture must not outlive the window.
        handler.removeCallbacks(captureBase);
        // 0.5.0-p72: stop plate baking on a real detach. `ensurePlateSnapshot()`
        // also checks `released`, so no Bitmap can be allocated after the
        // window is gone. The BAKED plate (screenBmp) is deliberately kept
        // for the finishing window: WindowBackdropDrawable.draw() still calls
        // drawRegion(), so the outgoing transition shows the last backdrop
        // instead of p42's black rectangle.
        plateActive = false;
        videoMode = false;
        // 0.5.0-p73: drop the reflected field / node back-reference. The
        // video View itself is still removed by hide() below; its
        // RenderNode belongs to that View and must not be retained.
        liveVideoNode = null;
        videoNodeField = null;
        videoNodeFieldTried = false;
        if (restoreOem) {
            hide(true);
            releaseTransitionFrame();
        } else {
            // 0.5.0-p74 VIDEO TRANSITION BACKDROP. Freeze ONE small frame
            // BEFORE stopVideo() / GONE: after those calls neither the CPU
            // snapshot nor the GPU live layer exists, and p43's flat window
            // colour is all the outgoing transition would show.
            if (wasVideo) captureTransitionFrame();
            // 0.4.0-p42 BACK-TRANSITION BLACK SCREEN.
            //
            // hide() blanks the ImageView (GONE + setImageBitmap(null) +
            // recycle()) and release() removes it from the overlay. Since p39
            // deliberately does NOT put the OEM opaque decor Drawable back
            // when the Activity is finishing, the outgoing window was left
            // with NO backdrop layer and a NULL decor background. The system
            // transition snapshot then composited as a solid BLACK rectangle
            // with a strip of status/toolbar white on top -- exactly what the
            // user reported for L3 -> L2 -> L1.
            //
            // Keep image + tint VISIBLE (with their bitmap/tint intact) until
            // the window is destroyed, so the transition snapshot shows the
            // wallpaper instead of black, and never shows the OEM white cards.
            // Only the video surface and the rotation timer must stop.
            decorBgCleared = false;
            originalDecorBg = null;
            handler.removeCallbacks(rotate);
            stopVideo();
            video.setVisibility(View.GONE);
        }
        try {
            video.setSurfaceTextureListener(null);
        } catch (Throwable ignored) {
        }
        if (restoreOem && overlay != null) {
            try {
                overlay.removeView(image);
                overlay.removeView(video);
                overlay.removeView(tint);
            } catch (Throwable ignored) {
            }
        }
        try {
            GlassEntry.say("backdrop release restoreOem=" + restoreOem
                    + " imageVis=" + image.getVisibility()
                    + " imageKept=" + (image.getVisibility() == View.VISIBLE)
                    + " decorKind=" + windowBgKind()
                    + " bgColor=" + Integer.toHexString(windowBgColor));
        } catch (Throwable ignored) {
        }
    }

    private void recycle() {
        Bitmap b = current;
        Bitmap s = screenBmp;
        current = null;
        screenBmp = null;
        snapshotStyleKey = "";
        imageName = null;
        // 0.5.0: the baked screen-sized snapshot used to be dereferenced but
        // never recycled. Every wallpaper change leaked one full-screen
        // ARGB_8888 bitmap (several MB) for the lifetime of the process.
        if (s != null && s != b && !s.isRecycled()) {
            try {
                s.recycle();
            } catch (Throwable ignored) {
            }
        }
        // 0.5.0-p76: `current` may be the process-level cached Bitmap still
        // in use by another Activity. Never recycle it here; only the screen
        // snapshot (per-Activity) is safe to free.
        if (b != null && !currentFromCache && !b.isRecycled()) {
            try {
                b.recycle();
            } catch (Throwable ignored) {
            }
        }
        currentFromCache = false;
    }

    private static List<String> parsePool(String raw) {
        List<String> out = new ArrayList<String>();
        if (raw == null) return out;
        for (String s : raw.split(";")) {
            String t = s.trim();
            if (t.length() > 0) out.add(t);
        }
        return out;
    }

    private static FrameLayout.LayoutParams fullScreen() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private static void close(InputStream in) {
        try {
            in.close();
        } catch (Throwable ignored) {
        }
    }
}
