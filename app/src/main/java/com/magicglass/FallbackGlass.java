package com.magicglass;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.drawable.GradientDrawable;
import android.view.View;

/**
 * FORCED glass backing (0.3.2).
 *
 * User decision (2026-11-12): "万一外进程无法创建玻璃容器，就恢复原生白底
 * ... 这个会不会导致错乱，强制玻璃底怎么样？" -- restoring the OEM white
 * drawables is exactly the wrong fallback. It makes the page flip between
 * glass and opaque white, permanently downgrades the Activity after a single
 * transient failure, and (if restoreRows() and the current scan disagree)
 * leaves a mixture of white cards and bare text on the wallpaper.
 *
 * This class gives the renderer an ALWAYS-CONSTRUCTIBLE backing: a plain View
 * with a rounded translucent GradientDrawable. It has no dependency on the
 * host uikit, no reflection, no GPU render layer, and costs almost nothing.
 * It is deliberately MORE opaque than the host liquid glass (about 80%) so a
 * dense paragraph stays readable even when the system material is missing,
 * while still letting the custom background show through.
 *
 * RenderController latches this mode for the Activity's lifetime (hysteresis),
 * so a flaky ClassLoader can never make the UI oscillate. Only a real
 * glass-parameter change or a detach is allowed to retry the host SDK.
 */
public final class FallbackGlass {

    /** Marks the View so MaterialBridge can skip SDK-only reflection safely. */
    public static final String TAG = "magicglass.fallback";

    /** ~80% opaque. Readable over a photo, still clearly translucent. */
    private static final int FILL_LIGHT = 0xCDF7F8FB;
    private static final int FILL_DARK = 0xC62A2B31;
    private static final int STROKE_LIGHT = 0x33FFFFFF;
    private static final int STROKE_DARK = 0x3AFFFFFF;

    private FallbackGlass() {
    }

    static View createView(Context ctx, ConfigContract.Snapshot cfg, float density) {
        return make(ctx, cfg, density,
                FILL_LIGHT, FILL_DARK, STROKE_LIGHT, STROKE_DARK);
    }

    private static View make(Context ctx, ConfigContract.Snapshot cfg, float density,
                             int fillLight, int fillDark,
                             int strokeLight, int strokeDark) {
        View v = new View(ctx);
        v.setTag(TAG);
        boolean night = isNight(ctx);
        float radius = 18f * density;
        try {
            radius = cfg.asFloat(ConfigContract.Param.GEO_RADIUS_DP) * density;
        } catch (Throwable ignored) {
        }
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(Math.max(0f, radius));
        d.setColor(night ? fillDark : fillLight);
        d.setStroke(Math.max(1, Math.round(density)),
                night ? strokeDark : strokeLight);
        v.setBackground(d);
        return v;
    }

    private static boolean isNight(Context ctx) {
        try {
            Configuration c = ctx.getResources().getConfiguration();
            return (c.uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable t) {
            return false;
        }
    }
}
