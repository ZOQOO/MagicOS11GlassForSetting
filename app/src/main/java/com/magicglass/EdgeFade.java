package com.magicglass;

import android.view.View;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Reusable EDGE-FADE TEMPLATE (0.3.8).
 *
 * User report, verbatim: "往上滑动，例如WLAN的L2级菜单下面可以滑动的整块往
 * 上滑文字和背景消失没有动画效果只是凭空消失看着不太好，这个可以设置成一个
 * 模板，有这种情况的时候可以复用。"
 *
 * Root cause: every card rect was gated by a BINARY height threshold
 * (MIN_ROW_H_PX = 80, STATIC_MIN_VISIBLE_H_PX = 32). The moment a scrolling
 * card's clipped height fell below that number the whole glass container was
 * dropped in ONE frame. The OEM text was clipped just as abruptly by its
 * parent. There was no transition at all -- "凭空消失".
 *
 * This class is ONE template used by EVERY card family on EVERY level:
 *   GROUP / STANDALONE list blocks, STATIC (non-RecyclerView) header cards,
 *   and external generic cards. Nothing is page-specific.
 *
 * Two channels are faded together so text and background disappear as one:
 *   1) the glass container in our overlay (setAlpha on the RenderNode);
 *   2) the OEM row Views themselves (so the glyphs soften instead of being
 *      hard-clipped by the RecyclerView).
 *
 * Safety rules learned from earlier incidents:
 *   - NEVER fade a card that is merely SHORT. Only a card whose visible
 *     height is genuinely SMALLER than its laid-out height (i.e. clipped by
 *     an edge) enters the fade band. A 100 px standalone card stays opaque.
 *   - NEVER leave a half-transparent OEM View behind. RecyclerView reuses
 *     row objects; begin() restores EVERY previously faded View to its exact
 *     original alpha before any new fade is applied, and release() does the
 *     same on detach.
 *   - No animation object, no Handler, no Choreographer callback, no delayed
 *     task (the 0.3.4-p8 kick-chain jank regression). The alpha is derived
 *     purely from the CURRENT frame's geometry, so it is frame-exact and
 *     cannot desynchronise from the scroll.
 */
public final class EdgeFade {

    /**
     * Width of the fade band, in dp. Raised 36 -> 60 dp at the user's
     * request ("渐隐带有点短"): 60 dp x 3.5 = 210 px on AAK-AN00, which is
     * a little longer than one full 168 px preference row.
     */
    public static final float FADE_DP = 60f;
    /** Below this visible height the card is hidden outright (no 1 px sliver). */
    public static final float MIN_VISIBLE_PX = 6f;
    /**
     * 0.3.9: quantise setAlpha() so a scroll does not invalidate the same
     * View on EVERY frame. 0.04 is below the visible step at 120 Hz.
     * 0.4.0: GLASS containers are cheap (one RenderNode); OEM content rows
     * are NOT -- setAlpha() there invalidates a whole row of text/icons.
     * With a 210 px band a 120 Hz fling changed alpha by ~0.05/frame, so
     * ALPHA_STEP=0.04 still re-invalidated the OEM rows EVERY frame, which
     * was a major part of the L2 "迟钝掉帧". Content is now quantised at
     * 0.12 (roughly one repaint every 5-6 frames) while glass stays smooth.
     */
    private static final float ALPHA_STEP = 0.04f;
    private static final float CONTENT_ALPHA_STEP = 0.12f;

    /** original alpha of every OEM View we touched, keyed by identity. */
    private final Map<View, Float> touched = new HashMap<View, Float>();
    /** last alpha actually written to that View. */
    private final Map<View, Float> applied = new HashMap<View, Float>();
    /** Views that still need to be faded in the CURRENT frame. */
    private final Set<View> active = new HashSet<View>();

    /**
     * Continuous alpha for one card.
     *
     * @param visibleH clipped height from getGlobalVisibleRect()
     * @param fullH    laid-out height (View.getHeight()), NOT clipped
     * @param fadePx   FADE_DP * density
     */
    public static float alpha(float visibleH, float fullH, float fadePx) {
        if (fadePx <= 1f) return 1f;
        if (visibleH <= MIN_VISIBLE_PX) return 0f;
        // Short by nature, not clipped -> fully opaque.
        if (fullH > 0f && visibleH >= fullH - 2f) return 1f;
        // Still far from the edge -> fully opaque.
        if (visibleH >= fadePx) return 1f;
        float a = (visibleH - MIN_VISIBLE_PX) / (fadePx - MIN_VISIBLE_PX);
        if (a < 0f) return 0f;
        if (a > 1f) return 1f;
        return a;
    }

    /**
     * Finalise the PREVIOUS frame and open the new one. Call ONCE per frame.
     *
     * 0.3.8 restored EVERY touched View here and then immediately re-faded
     * it in the same frame -- two invalidate() calls per View per frame, a
     * real contributor to the L2 scroll jank. 0.3.9 only restores Views that
     * are NO LONGER part of the fade; still-active Views keep their alpha and
     * are updated in place by fadeContent()/applyGlass().
     */
    public void begin() {
        if (!touched.isEmpty()) {
            Iterator<Map.Entry<View, Float>> it = touched.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<View, Float> e = it.next();
                if (active.contains(e.getKey())) continue;
                View v = e.getKey();
                try {
                    if (v.getAlpha() != e.getValue()) v.setAlpha(e.getValue());
                } catch (Throwable ignored) {
                }
                applied.remove(v);
                it.remove();
            }
        }
        active.clear();
    }

    /**
     * Apply the template's alpha to an OEM content View (and remember its
     * original value). Views already at a non-1 alpha are left alone: the ROM
     * may be running its own animation and we must not fight it.
     */
    public void fadeContent(View v, float alpha) {
        if (v == null || alpha >= 0.999f) return;
        try {
            Float orig = touched.get(v);
            if (orig == null) {
                float cur = v.getAlpha();
                if (cur < 0.999f) return;   // OEM animation in progress
                orig = cur;
                touched.put(v, orig);
            }
            active.add(v);
            setQuantised(v, orig * alpha, CONTENT_ALPHA_STEP);
        } catch (Throwable ignored) {
        }
    }

    /** Same continuous template on a glass container in our overlay. */
    public void applyGlass(View c, float alpha) {
        if (c == null) return;
        try {
            float target = alpha < 0f ? 0f : (alpha > 1f ? 1f : alpha);
            Float last = applied.get(c);
            if (last == null || Math.abs(last - target) > ALPHA_STEP) {
                if (c.getAlpha() != target) c.setAlpha(target);
                applied.put(c, target);
            }
        } catch (Throwable ignored) {
        }
    }

    private void setQuantised(View v, float target, float step) {
        Float last = applied.get(v);
        if (last == null || Math.abs(last - target) > step) {
            if (v.getAlpha() != target) v.setAlpha(target);
            applied.put(v, target);
        }
    }

    /** Restore ALL OEM Views on detach. */
    public void release() {
        active.clear();
        begin();
        applied.clear();
    }

    public EdgeFade() {
    }
}
