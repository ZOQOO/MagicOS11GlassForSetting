package com.magicglass;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import java.io.InputStream;

/**
 * 0.5.0-p175: glass-card INNER fill image loader.
 *
 * The fill image is a static decoration, NOT the translucent backdrop that the
 * glass refracts. It is decoded ONCE per (media name, max edge) through the
 * existing read-only media provider and cached process-wide, so the render
 * frame path only ever reads a volatile Bitmap reference -- no IPC, no decode,
 * no allocation. A config change (new name / new max edge) swaps the cache.
 *
 * Size limits (the user's explicit requirement):
 *  - maxEdgeDp is clamped to [64, 1024], converted to px, and enforced with a
 *    two-pass inSampleSize decode, so a 6000x4000 photo never becomes a
 *    96 MB Bitmap.
 *  - configStore.importMedia() already caps the imported file; this is the
 *    second, independent guard on the decode side.
 */
public final class GlassFill {

    private static volatile Bitmap sBitmap;
    private static volatile String sKey = "";
    private static volatile int sWidth;
    private static volatile int sHeight;

    private static final int MIN_EDGE_DP = 64;
    private static final int MAX_EDGE_DP = 1024;

    private GlassFill() {
    }

    /** @return cached (or freshly decoded) fill bitmap, null when disabled/failed. */
    public static Bitmap get(Context ctx, String name, int maxEdgeDp) {
        if (ctx == null || name == null || name.trim().length() == 0) {
            sKey = "";
            sBitmap = null;
            return null;
        }
        int cap = Math.max(MIN_EDGE_DP, Math.min(MAX_EDGE_DP, maxEdgeDp));
        String key = name + '|' + cap;
        if (key.equals(sKey) && sBitmap != null) return sBitmap;

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
            if (w > 0 && h > 0) {
                float density = ctx.getResources().getDisplayMetrics().density;
                int capPx = Math.max(64, Math.round(cap * (density > 0f ? density : 1f)));
                int longest = Math.max(w, h);
                int sample = Math.max(1, longest / capPx);
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = sample;
                opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
                InputStream in2 = ctx.getContentResolver().openInputStream(uri);
                if (in2 != null) {
                    try {
                        decoded = BitmapFactory.decodeStream(in2, null, opts);
                    } finally {
                        close(in2);
                    }
                }
                if (decoded != null && Math.max(decoded.getWidth(), decoded.getHeight()) > capPx) {
                    // inSampleSize is power-of-two; a final exact scale guarantees the cap.
                    float scale = (float) capPx / Math.max(decoded.getWidth(), decoded.getHeight());
                    Bitmap small = Bitmap.createScaledBitmap(decoded,
                            Math.max(1, Math.round(decoded.getWidth() * scale)),
                            Math.max(1, Math.round(decoded.getHeight() * scale)), true);
                    decoded.recycle();
                    decoded = small;
                }
            }
        } catch (Throwable t) {
            decoded = null;
        }

        synchronized (GlassFill.class) {
            sKey = decoded == null ? "" : key;
            sBitmap = decoded;
            sWidth = decoded == null ? 0 : decoded.getWidth();
            sHeight = decoded == null ? 0 : decoded.getHeight();
        }
        return decoded;
    }

    public static int width() {
        return sWidth;
    }

    public static int height() {
        return sHeight;
    }

    private static void close(InputStream in) {
        try {
            in.close();
        } catch (Throwable ignored) {
        }
    }
}
