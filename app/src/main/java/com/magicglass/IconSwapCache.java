package com.magicglass;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.net.Uri;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 0.5.0-p178: bounded, process-wide cache for row-icon replacement bitmaps.
 *
 * Unlike GlassFill (one bitmap at a time), icon swap can have many rules
 * active at once, so this is a bounded LRU (MAX_ENTRIES). Each bitmap is
 * decoded through the module's read-only media provider with a hard pixel
 * cap (icons are ~24dp; a generous 128dp ceiling still prevents a huge
 * photo from becoming a 100 MB Bitmap). Decoding happens on media-pick /
 * config change, NEVER in the PREFBIND hot path: the hook only reads the
 * volatile cache via {@link #peek(String)}.
 */
public final class IconSwapCache {

    private static final int MAX_ENTRIES = 24;
    private static final int MAX_EDGE_DP = 128;
    private static final LinkedHashMap<String, Bitmap> CACHE =
            new LinkedHashMap<String, Bitmap>(MAX_ENTRIES, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
                    if (size() > MAX_ENTRIES) {
                        Bitmap b = eldest.getValue();
                        // Do NOT recycle: a View may still hold a Drawable on it.
                        return true;
                    }
                    return false;
                }
            };

    private IconSwapCache() {
    }

    /** Hot-path safe: single synchronized map lookup, no decode / no IPC. */
    public static Bitmap peek(String name) {
        if (name == null || name.length() == 0) return null;
        synchronized (CACHE) {
            Bitmap b = CACHE.get(name);
            return b != null && !b.isRecycled() ? b : null;
        }
    }

    /** Called from the settings UI after a media pick: decode + cache. */
    public static Bitmap load(Context ctx, String name) {
        if (ctx == null || name == null || name.trim().length() == 0) return null;
        Bitmap cached = peek(name);
        if (cached != null) return cached;
        Bitmap decoded = null;
        try {
            Uri uri = ConfigBus.media(name);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            InputStream in = ctx.getContentResolver().openInputStream(uri);
            if (in != null) {
                try {
                    BitmapFactory.decodeStream(in, null, bounds);
                } finally {
                    close(in);
                }
            }
            int w = bounds.outWidth;
            int h = bounds.outHeight;
            if (w <= 0 || h <= 0) return null;
            float density = ctx.getResources().getDisplayMetrics().density;
            int capPx = Math.max(32, Math.round(MAX_EDGE_DP
                    * (density > 0f ? density : 1f)));
            int longest = Math.max(w, h);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = Math.max(1, longest / capPx);
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            InputStream in2 = ctx.getContentResolver().openInputStream(uri);
            if (in2 != null) {
                try {
                    decoded = BitmapFactory.decodeStream(in2, null, opts);
                } finally {
                    close(in2);
                }
            }
            if (decoded != null
                    && Math.max(decoded.getWidth(), decoded.getHeight()) > capPx) {
                float scale = (float) capPx
                        / Math.max(decoded.getWidth(), decoded.getHeight());
                Bitmap small = Bitmap.createScaledBitmap(decoded,
                        Math.max(1, Math.round(decoded.getWidth() * scale)),
                        Math.max(1, Math.round(decoded.getHeight() * scale)), true);
                decoded.recycle();
                decoded = small;
            }
            // 0.5.0-p188: 居中方形裁切 + 圆形遮罩。任意比例的图片塞进方形图标位
            // 会被拉伸变形，先取中心正方形再裁成圆形，与系统行图标的圆形外观一致。
            if (decoded != null) {
                decoded = toRoundIcon(decoded);
            }
        } catch (Throwable t) {
            decoded = null;
        }
        if (decoded != null) {
            synchronized (CACHE) {
                CACHE.put(name, decoded);
            }
        }
        return decoded;
    }

    /** Drop a rule's cached bitmap (e.g. after reset). */
    public static void remove(String name) {
        if (name == null) return;
        synchronized (CACHE) {
            CACHE.remove(name);
        }
    }

    public static void clear() {
        synchronized (CACHE) {
            CACHE.clear();
        }
    }

    /**
     * 0.5.0-p188: 居中方形裁切 + 圆形遮罩。
     * 取原图中心最小正方形，用 BitmapShader + 平移矩阵映射到圆形画布上，
     * 输出 ARGB_8888 的正方形透明底圆形位图。失败时原样返回，绝不抛异常。
     */
    private static Bitmap toRoundIcon(Bitmap src) {
        if (src == null) return null;
        try {
            int w = src.getWidth();
            int h = src.getHeight();
            int size = Math.min(w, h);
            if (size <= 0) return src;
            Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            BitmapShader shader = new BitmapShader(src,
                    Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            // 把源图中心正方形对齐到 (0,0) 起点。
            Matrix m = new Matrix();
            m.setTranslate(-(w - size) / 2f, -(h - size) / 2f);
            shader.setLocalMatrix(m);
            paint.setShader(shader);
            float r = size / 2f;
            canvas.drawCircle(r, r, r, paint);
            if (out != src) src.recycle();
            return out;
        } catch (Throwable t) {
            return src;
        }
    }

    private static void close(InputStream in) {
        try {
            in.close();
        } catch (Throwable ignored) {
        }
    }
}
