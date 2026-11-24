package com.magicglass;

import android.graphics.Matrix;
import android.widget.ImageView;

/**
 * 0.5.0-p67: ONE source of truth for background filling.
 *
 * Three consumers must never disagree about what a mode means:
 *   - BackdropLayer's ImageView (static / random images),
 *   - BackdropLayer's TextureView (looping video),
 *   - the SettingsActivity preview card.
 *
 * Preset modes are mapped straight onto ImageView.ScaleType so the DEFAULT
 * path (centerCrop) keeps exactly the pixels it had at L1 p30 / 0.5.0-p66.
 * Only CUSTOM needs a hand-built Matrix: it adds a zoom multiplier and a
 * crop focus (0..100 on each axis). Video has no ScaleType at all, so ALL
 * video modes go through the SAME matrix() computation.
 *
 * Legacy value "fitXY" is deliberately NOT accepted: it was offered in old
 * UI builds but silently fell through to centerCrop (p65 audit). Making it
 * suddenly stretch would be an unannounced visual jump for existing installs.
 * The new real stretch mode uses the new value "fill" instead.
 */
public final class ScaleMode {

    public static final String CENTER_CROP = "centerCrop";
    public static final String FIT_CENTER = "fitCenter";
    public static final String CENTER_INSIDE = "centerInside";
    public static final String FILL = "fill";
    public static final String CENTER = "center";
    public static final String CUSTOM = "custom";

    public static final String[] VALUES = {
            CENTER_CROP, FIT_CENTER, CENTER_INSIDE, FILL, CENTER, CUSTOM
    };

    public static final String[] LABELS = {
            "居中裁切", "完整适应", "原尺寸居中", "拉伸铺满", "原图居中", "自定义…"
    };

    private static final float ZOOM_MIN = 50f;
    private static final float ZOOM_MAX = 400f;

    private ScaleMode() {
    }

    /** Unknown / legacy values resolve to the frozen default, never a jump. */
    public static String normalize(String raw) {
        if (raw == null) return CENTER_CROP;
        for (String v : VALUES) {
            if (v.equals(raw)) return v;
        }
        return CENTER_CROP;
    }

    public static boolean isCustom(String mode) {
        return CUSTOM.equals(mode);
    }

    /** Used by both the real ImageView and the settings preview ImageView. */
    public static ImageView.ScaleType scaleTypeOf(String mode) {
        if (FIT_CENTER.equals(mode)) return ImageView.ScaleType.FIT_CENTER;
        if (CENTER_INSIDE.equals(mode)) return ImageView.ScaleType.CENTER_INSIDE;
        if (FILL.equals(mode)) return ImageView.ScaleType.FIT_XY;
        if (CENTER.equals(mode)) return ImageView.ScaleType.CENTER;
        if (CUSTOM.equals(mode)) return ImageView.ScaleType.MATRIX;
        return ImageView.ScaleType.CENTER_CROP;
    }

    public static String description(String mode) {
        if (FIT_CENTER.equals(mode))
            return "完整适应：整张图都在画面内、保持比例，两侧或上下会留白。";
        if (CENTER_INSIDE.equals(mode))
            return "原尺寸居中：保持比例且绝不放大，小图会原样居中。";
        if (FILL.equals(mode))
            return "拉伸铺满：强制铺满屏幕，不保持比例，画面会被拉长或压扁。";
        if (CENTER.equals(mode))
            return "原图居中：一个像素对应一个像素，超出屏幕的部分被裁掉。";
        if (CUSTOM.equals(mode))
            return "自定义：以居中裁切为基准再放大，并用两个焦点决定留住哪一块。";
        return "居中裁切：保持比例铺满屏幕，多出来的部分从四周裁掉（默认）。";
    }

    public static int zoomOf(ConfigContract.Snapshot cfg) {
        return cfg == null ? 150 : clamp(cfg.asInt(ConfigContract.Param.BG_SCALE_ZOOM), 50, 400);
    }

    public static int focusXOf(ConfigContract.Snapshot cfg) {
        return cfg == null ? 50 : clamp(cfg.asInt(ConfigContract.Param.BG_SCALE_FOCUS_X), 0, 100);
    }

    public static int focusYOf(ConfigContract.Snapshot cfg) {
        return cfg == null ? 50 : clamp(cfg.asInt(ConfigContract.Param.BG_SCALE_FOCUS_Y), 0, 100);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /**
     * Changing ANY of these must invalidate the baked screen snapshot.
     * p30 taught us the hard way that a cached transform silently survives a
     * config change; the old snapshot() only compared width/height.
     */
    public static String styleKey(String mode, int zoom, int fx, int fy) {
        return mode + "|" + zoom + "|" + fx + "|" + fy;
    }

    /**
     * Matrix mapping the source rectangle (0,0)-(sw,sh) into the view
     * rectangle (0,0)-(vw,vh). Same formula for CUSTOM images, ALL video
     * modes, and the settings preview, so the three never disagree.
     *
     * focus 0 = keep the left/top edge, 50 = centre, 100 = keep the right/bottom
     * edge. It is applied to the (possibly negative) spare space, so it also
     * controls which side gets cropped when the content is larger than the view.
     */
    public static Matrix matrix(String mode, int zoom, int fx, int fy,
                                int sw, int sh, int vw, int vh) {
        if (sw <= 0 || sh <= 0 || vw <= 0 || vh <= 0) return null;
        float sx = (float) vw / (float) sw;
        float sy = (float) vh / (float) sh;
        float ax = fx / 100f;
        float ay = fy / 100f;
        float mx = Math.max(sx, sy);
        float mn = Math.min(sx, sy);
        Matrix m = new Matrix();
        if (FILL.equals(mode)) {
            // Non-uniform: this is the only mode that may distort the image.
            m.setScale(sx, sy);
            return m;
        }
        float s;
        if (FIT_CENTER.equals(mode)) {
            s = mn;
            ax = 0.5f;
            ay = 0.5f;
        } else if (CENTER_INSIDE.equals(mode)) {
            s = Math.min(1f, mn);
            ax = 0.5f;
            ay = 0.5f;
        } else if (CENTER.equals(mode)) {
            s = 1f;
            ax = 0.5f;
            ay = 0.5f;
        } else if (CUSTOM.equals(mode)) {
            float z = zoom / 100f;
            if (z < ZOOM_MIN / 100f) z = ZOOM_MIN / 100f;
            if (z > ZOOM_MAX / 100f) z = ZOOM_MAX / 100f;
            s = mx * z;
        } else {
            // CENTER_CROP (and any unknown value normalised to it)
            s = mx;
            ax = 0.5f;
            ay = 0.5f;
        }
        m.setScale(s, s);
        m.postTranslate((vw - sw * s) * ax, (vh - sh * s) * ay);
        return m;
    }
}
