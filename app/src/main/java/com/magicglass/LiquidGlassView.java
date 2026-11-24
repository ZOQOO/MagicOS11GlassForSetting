package com.magicglass;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.content.res.Configuration;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.view.View;

/**
 * Self-drawn liquid glass (0.4.0-p5, engine = "liquid").
 *
 * WHY THIS EXISTS
 * ---------------
 * The host material (HnTransFrameLayout + StyleType LIQUID_GLASS_TRANSPARENT,
 * HighGradeStrategy) is already the clearest the ROM offers, yet it still reads
 * as frosted: the material's own opacity is baked into the uikit, the host
 * never uses this widget in Settings at all, and `support_background_blur` is 0
 * on this device, so no real blur is produced.
 *
 * Reference projects (big-dictator/LiquidTab, kyant0/AndroidLiquidGlass) solve
 * it the other way: draw the glass themselves from the real backdrop with an
 * AGSL refraction shader. They have to capture the backdrop with PixelCopy.
 * We do NOT: the backdrop is our own Bitmap in BackdropLayer, so the input can
 * be copied straight out of it, with zero capture cost and zero frame delay.
 *
 * PIPELINE (per frame, GPU only)
 *   1. record the backdrop region into a RenderNode
 *   2. chain:  refraction(shader, "content")  <-  blur   (LiquidTab's order)
 *   3. draw the node clipped to the rounded rect
 *   4. overlay a tint layer whose alpha IS the "通透度" setting
 *   5. stroke a soft edge highlight
 *
 * Every step is guarded: a device without RuntimeShader falls back to
 * blur-only, and without that to tint-only. It can never crash Settings.
 */
public final class LiquidGlassView extends View {

    private final RenderNode node = new RenderNode("magicglass.liquid");
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /**
     * 0.4.0-p14 AMBIENT SHADOW, copied from LiquidTab:
     *   PANEL_SHADOW_BLUR_DP    = 10
     *   PANEL_SHADOW_ALPHA      = 0.14  (dark)
     *   PANEL_LIGHT_SHADOW_ALPHA= 0.19  (light -- shadows read stronger on light)
     * drawn with dx = dy = 0 (pure ambient halo) and clipOutPath so only the
     * ring OUTSIDE the glass gets the shadow.
     *
     * The halo extends past the card, so the container is inflated by PAD_DP on
     * every side; onDraw translates by that amount and the glass itself keeps
     * the exact card rectangle.
     */
    private static final float SHADOW_BLUR_DP = 10f;
    private static final float SHADOW_ALPHA_DARK = 0.14f;
    private static final float SHADOW_ALPHA_LIGHT = 0.19f;
    /**
     * HOOK: extra room around the card for the ambient halo.
     *
     * Set to 0 on purpose -- the user does not want the container inflated.
     * 12f restores the previous look (halo fully visible), any value in
     * between trades halo spread against card spacing. When it is 0 the
     * shadow pass is skipped entirely, because whatever it drew would be
     * clipped by the view bounds and show up as a hard dark rim.
     */
    public static final float PAD_DP = 0f;
    /**
     * HOOK: strength of the rainbow rim.
     *
     * The shader splits R and B by `d * grad * dispersion`, where
     * `d` peaks at |refractionAmount| (~91 px) and `dispersion` peaks at 1.0
     * in the corners, so the raw visible split is
     *   chromaticAberration * 91 px
     * LiquidTab ships chromaticAberration = 0.42 on PANELS THAT ARE SMALL
     * (navigation bars). Applied to a 1174 x 1250 settings block the same
     * constant splits R/B by ~38 px and the rim reads as a hard rainbow gash.
     * 0.42 (LiquidTab) * 0.18 = 0.076 -> ~7 px split, confined to the outer
     * 12 px of the refraction band by the shader's `rim` term, so it still
     * shimmers while dragging but no longer colours the whole edge.
     * 0.0 disables dispersion completely, 0.35 restores the old value.
     */
    private static final float DISPERSION_SCALE = 0.18f;
    private final Path clip = new Path();
    private final int[] loc = new int[2];

    private final RuntimeShader shader;

    private BackdropLayer backdrop;
    private RenderEffect effect;
    private boolean effectDirty = true;
    /**
     * Size (px) the current effect's `size` uniform was set to.
     *
     * 0.4.0-p17 CRITICAL FIX. The shader decides "is this pixel inside the
     * refraction band?" from `size`/`halfSize`, so `size` MUST equal the live
     * view size. rebuildEffect() used to bail out as soon as `effect != null`,
     * so the very first size was frozen forever. A card whose height changes
     * while scrolling (a block gains/loses a row) then evaluated the SDF with
     * a stale half-size: `-sd` came out wrong, the `-sd >= refractionHeight`
     * early-out never fired, `sd` got clamped to 0 and `d` became the FULL
     * ±refractionAmount over the whole surface. Visually that is a whole-card
     * jump of ~91 px plus a large R/B split -- the user's "整块跳一截" and
     * the big rainbow sides on the bottom card.
     */
    private int effW = -1;
    private int effH = -1;

    /** 0.4.0-p29 per-frame truth, read by RenderController (diagnostic only). */
    public int diagBd = -1;        // 1 = backdrop sampled and drawn, 0 = not
    public int diagUniformPush;    // 1 = uniforms re-pushed this frame
    public int diagEffectRebuild;  // 1 = RenderEffect rebuilt this frame
    public int diagW, diagH;

    private float radius;
    private float blurRadius = 24f;
    private float refractionHeight = 18f;
    private float refractionAmount = 2.2f;
    private float dispersion;
    private float tint = 0.40f;
    // 0.5.0-p175 inner fill: optional solid colour and/or cached image.
    private int fillColor;          // 0 = disabled
    private float fillAlpha;        // 0..1, applied on top of the colour's own alpha
    private Bitmap fillImage;       // from GlassFill cache, frame path = reference read only
    private float fillImageAlpha;   // 0..1
    private boolean fillImageCover = true;
    private final android.graphics.RectF fillDst = new android.graphics.RectF();
    private final android.graphics.Rect fillSrc = new android.graphics.Rect();
    private int edgeColor = 0x66FFFFFF;
    private int edgeW;
    private int edgeH;

    public LiquidGlassView(Context ctx) {
        super(ctx);
        setWillNotDraw(false);
        RuntimeShader s = null;
        try {
            s = new RuntimeShader(LiquidShader.GLASS);
        } catch (Throwable t) {
            // A broken AGSL string must be visible from lspd, not guessed
            // from the picture: a null shader silently degrades the whole
            // engine to "blur only", which looks like "no glass at all".
            if (!sShaderFailLogged) {
                sShaderFailLogged = true;
                GlassEntry.say("liquid shader COMPILE FAILED: " + t);
            }
        }
        shader = s;
        if (s != null && !sShaderOkLogged) {
            sShaderOkLogged = true;
            GlassEntry.say("liquid shader ready");
        }
        fillPaint.setStyle(Paint.Style.FILL);
        edgePaint.setStyle(Paint.Style.STROKE);
        edgePaint.setStrokeWidth(1.5f);
    }

    private static boolean sShaderOkLogged;
    private static boolean sShaderFailLogged;

    /** Which backdrop to sample. Null keeps the tint-only look. */
    public void setBackdrop(BackdropLayer layer) {
        if (backdrop != layer) {
            backdrop = layer;
            effectDirty = true;
        }
    }

    /**
     * Called from MaterialBridge.configure, once per frame.
     * Sizes arrive in DP (the reference project's unit); the view converts to
     * pixels with the real density so the refraction band scales with the
     * screen instead of looking tiny on a 3.5x display.
     */
    public void applyMaterial(float cornerRadius, float blurDp, float refrHeightDp,
                              float refrAmountDp, float disp, float tintAlpha) {
        float d = density();
        float blur = blurDp * d;
        float refrHeight = refrHeightDp * d;
        float refrAmount = refrAmountDp * d;
        if (radius != cornerRadius) {
            radius = cornerRadius;
            effectDirty = true;
        }
        if (blurRadius != blur) {
            blurRadius = blur;
            effectDirty = true;
        }
        if (refractionHeight != refrHeight || refractionAmount != refrAmount
                || dispersion != disp) {
            refractionHeight = refrHeight;
            refractionAmount = refrAmount;
            dispersion = disp;
            effectDirty = true;
        }
        tint = tintAlpha;
    }

    /**
     * 0.5.0-p175: inner fill of the glass card. Called from MaterialBridge
     * every frame with cached values (no decode / no allocation here).
     * color=0 disables the solid fill; bitmap=null disables the image fill.
     */
    public void applyFill(int color, float alpha, Bitmap bitmap,
                          float imageAlpha, boolean cover) {
        if (fillColor != color || fillAlpha != alpha) {
            fillColor = color;
            fillAlpha = alpha;
        }
        if (fillImage != bitmap || fillImageAlpha != imageAlpha
                || fillImageCover != cover) {
            fillImage = bitmap;
            fillImageAlpha = imageAlpha;
            fillImageCover = cover;
        }
    }

    private float density() {
        try {
            float d = getResources().getDisplayMetrics().density;
            return d > 0f ? d : 1f;
        } catch (Throwable t) {
            return 1f;
        }
    }

    public void setEdgeColor(int color) {
        edgeColor = color;
    }

    /**
     * Soft halo outside the glass. Transparent fill + shadow layer + clipOut
     * is the reference project's exact recipe: the path itself paints nothing,
     * only its shadow survives, and the interior is punched out so the glass is
     * not darkened from the inside.
     */
    private void drawAmbientShadow(Canvas canvas, int pad) {
        if (PAD_DP <= 0f) return;   // no room -> the halo would be a hard rim
        try {
            float blur = SHADOW_BLUR_DP * density();
            float a = isDark() ? SHADOW_ALPHA_DARK : SHADOW_ALPHA_LIGHT;
            int alpha = (int) (a * 255f);
            shadowPaint.setColor(0x00000000);
            shadowPaint.setShadowLayer(blur, 0f, 0f, alpha << 24);
            canvas.save();
            canvas.clipOutPath(clip);
            canvas.drawPath(clip, shadowPaint);
            canvas.restore();
        } catch (Throwable ignored) {
        }
    }

    /** Follow the theme the way the reference project does. */
    private boolean isDark() {
        try {
            int mode = getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK;
            return mode == Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int pad = Math.round(PAD_DP * density());
        int w = getWidth() - pad * 2;
        int h = getHeight() - pad * 2;
        diagW = w;
        diagH = h;
        diagBd = 0;
        diagUniformPush = 0;
        diagEffectRebuild = 0;
        if (w <= 0 || h <= 0) return;
        canvas.translate(pad, pad);

        clip.reset();
        clip.addRoundRect(0f, 0f, (float) w, (float) h, radius, radius,
                Path.Direction.CW);

        drawAmbientShadow(canvas, pad);

        canvas.save();
        canvas.clipPath(clip);

        boolean backdropDrawn = false;
        BackdropLayer layer = backdrop;
        if (layer != null) {
            try {
                // Ask for the CURRENT screen position every frame instead of a
                // value pushed in from outside: a stale rect is exactly what
                // made the backdrop look glued to the card.
                getLocationOnScreen(loc);
                node.setPosition(0, 0, w, h);
                Canvas c = node.beginRecording();
                backdropDrawn = layer.drawRegion(c, loc[0] + pad, loc[1] + pad, w, h);
                node.endRecording();
                if (backdropDrawn) {
                    node.setRenderEffect(syncEffect(w, h));
                    diagBd = 1;
                    canvas.drawRenderNode(node);
                }
            } catch (Throwable ignored) {
                backdropDrawn = false;
            }
        }

        // No backdrop bitmap (no image picked yet / video / pure colour):
        // paint an opaque-ish base so the card never becomes invisible and the
        // rows never float bare on the wallpaper. This is the same visual
        // contract as the previous engine, just without refraction.
        // 0.4.0-p20 NO WHITE PLATE WHEN A BACKDROP EXISTS.
        //
        // The plate is only a safety net for "the user picked no image at all",
        // where the card would otherwise be invisible and the rows would float
        // bare on the wallpaper. If a BackdropLayer IS attached, a frame that
        // failed to sample it must show the backdrop through (the OEM white
        // cards are already stripped), never an opaque plate: a 70%-white
        // rectangle flashing for one frame is precisely the "拖动时会出现白块"
        // the user reported.
        if (!backdropDrawn && layer == null) {
            fillPaint.setColor(0xB3FFFFFF);
            canvas.drawRect(0f, 0f, (float) w, (float) h, fillPaint);
        }

        // 0.5.0-p175 INNER FILL. Drawn inside the clip, above the backdrop but
        // below the tint veil / edge highlight, so the glass still reads as a
        // pane. Both branches are no-op by default (no key set -> 0 / null).
        // Frame path: image is a cached Bitmap reference; only the two Rect
        // objects (allocated once in the constructor) are reused here.
        boolean fillDirty = false;
        if (fillImage != null && fillImageAlpha > 0.002f) {
            int iw = fillImage.getWidth();
            int ih = fillImage.getHeight();
            if (iw > 0 && ih > 0) {
                fillDirty = true;
                float scale = fillImageCover
                        ? Math.max((float) w / iw, (float) h / ih)
                        : Math.min((float) w / iw, (float) h / ih);
                float dw = iw * scale;
                float dh = ih * scale;
                fillDst.set((w - dw) * 0.5f, (h - dh) * 0.5f,
                        (w + dw) * 0.5f, (h + dh) * 0.5f);
                int prev = fillPaint.getAlpha();
                fillPaint.setAlpha((int) (Math.min(1f, fillImageAlpha) * 255f));
                canvas.drawBitmap(fillImage, null, fillDst, fillPaint);
                fillPaint.setAlpha(prev);
            }
        }
        if (fillColor != 0 && fillAlpha > 0.002f) {
            fillDirty = true;
            int baseAlpha = android.graphics.Color.alpha(fillColor);
            int a = Math.round(baseAlpha * Math.min(1f, fillAlpha));
            if (a > 0) {
                int rgb = fillColor & 0x00FFFFFF;
                fillPaint.setColor((a << 24) | rgb);
                canvas.drawRect(0f, 0f, (float) w, (float) h, fillPaint);
            }
        }

        // Surface fill. Reference project: 40% white in light mode, 40% of
        // #211F26 in dark mode -- a coloured veil, not pure white, so the glass
        // still belongs to the theme.
        if (tint > 0.002f) {
            int alpha = (int) (Math.min(1f, tint) * 255f);
            int base = isDark() ? 0x00211F26 : 0x00FFFFFF;
            fillPaint.setColor((alpha << 24) | base);
            canvas.drawRect(0f, 0f, (float) w, (float) h, fillPaint);
        }
        canvas.restore();

        // Directional edge highlight: bright on the top-left, fading to almost
        // nothing on the bottom-right. That gradient is what makes the surface
        // read as a physical pane instead of a flat translucent box.
        if (edgeW != w || edgeH != h) {
            edgeW = w;
            edgeH = h;
            try {
                edgePaint.setShader(new LinearGradient(
                        0f, 0f, w * 0.55f, (float) h,
                        0x66FFFFFF, 0x08FFFFFF, Shader.TileMode.CLAMP));
            } catch (Throwable ignored) {
                edgePaint.setShader(null);
                edgePaint.setColor(edgeColor);
            }
        }
        canvas.drawPath(clip, edgePaint);
    }

    /**
     * Keep the shader's `size` uniform equal to the LIVE view size.
     *
     * 0.4.0-p17: this used to be a pure cache (`effect != null -> return`),
     * which froze the size at whatever the container measured the first time
     * it drew.
     *
     * 0.4.0-p30 CORRECT FIX. p17..p29 assumed that re-pushing uniforms onto
     * the RuntimeShader would update an already-created RenderEffect. IT DOES
     * NOT: RenderEffect.createRuntimeShaderEffect() snapshots the shader's
     * uniform buffer at creation, so setFloatUniform() afterwards is invisible
     * to that effect. p29's diagnostics proved it on-device:
     *   uPush=296..428 per 2 s, rebuild=0
     * -- hundreds of "updates" that never reached the GPU. The `size` uniform
     * therefore stayed at the FIRST frame's value forever. Any block whose
     * height differs from that first value gets a wrong halfSize, so its SDF
     * puts interior pixels in the refraction band: the whole INSIDE of that
     * one block looks wrong/flickers, while the CPU-drawn rounded clip and
     * edge stroke stay perfectly still -- exactly the user's "固定一块在闪,
     * 块的边缘不闪".
     *
     * Fix: a size change REBUILDS the effect after pushing the uniforms, so
     * the new uniform buffer is the one Skia snapshots. Unchanged frames keep
     * returning the cached effect (O(1), no allocation).
     */
    private RenderEffect syncEffect(int w, int h) {
        if (effectDirty || effect == null) {
            rebuildEffect(w, h);
            effW = w;
            effH = h;
            diagEffectRebuild = 1;
            return effect;
        }
        if (w != effW || h != effH) {
            rebuildEffect(w, h);   // uniforms inside; effect must be re-snapshotted
            effW = w;
            effH = h;
            diagUniformPush = 1;
            diagEffectRebuild = 1;
        }
        return effect;
    }

    /** All float uniforms, in one place (used by syncEffect + rebuildEffect). */
    private void applyUniforms(int w, int h) {
        if (shader == null) return;
        try {
            shader.setFloatUniform("size", (float) w, (float) h);
            shader.setFloatUniform("offset", 0f, 0f);
            shader.setFloatUniform("cornerRadii", radius, radius, radius, radius);
            shader.setFloatUniform("refractionHeight", refractionHeight);
            shader.setFloatUniform("refractionAmount", refractionAmount);
            shader.setFloatUniform("depthEffect", 1f);
            shader.setFloatUniform("chromaticAberration", dispersion * DISPERSION_SCALE);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Saturation + blur, which depend ONLY on blurRadius (a material setting),
     * never on the view size. Cached so a per-frame size change re-snapshots
     * just the lens instead of churning the whole chain.
     */
    private RenderEffect cachedBlur;
    private int blurCacheKey = Integer.MIN_VALUE;

    private RenderEffect innerBlur() {
        int key = Float.floatToIntBits(blurRadius);
        if (cachedBlur != null && blurCacheKey == key) return cachedBlur;
        RenderEffect inner = null;
        try {
            ColorMatrix cm = new ColorMatrix();
            cm.setSaturation(1.5f);
            inner = RenderEffect.createColorFilterEffect(
                    new ColorMatrixColorFilter(cm));
        } catch (Throwable ignored) {
        }
        RenderEffect blur = inner;
        if (blurRadius > 0.5f) {
            try {
                blur = RenderEffect.createBlurEffect(blurRadius, blurRadius,
                        inner, Shader.TileMode.CLAMP);
            } catch (Throwable ignored) {
                blur = inner;
            }
        }
        cachedBlur = blur;
        blurCacheKey = key;
        return blur;
    }

    /**
     * Effect chain. Order matters and matches LiquidTab:
     *   outer = refraction (samples `content`), inner = blur
     * so the lens distorts an already-blurred backdrop.
     *
     * 0.4.0-p30: applyUniforms() MUST run immediately before
     * createRuntimeShaderEffect(), because that call snapshots the current
     * uniform buffer. Any later setFloatUniform() is invisible to the effect.
     */
    private void rebuildEffect(int w, int h) {
        RenderEffect blur = innerBlur();
        RenderEffect result = blur;
        if (shader != null) {
            applyUniforms(w, h);
            try {
                RenderEffect lens =
                        RenderEffect.createRuntimeShaderEffect(shader, "content");
                result = blur == null ? lens
                        : RenderEffect.createChainEffect(lens, blur);
            } catch (Throwable ignored) {
                result = blur;
            }
        }
        effect = result;
        effectDirty = false;
    }
}
