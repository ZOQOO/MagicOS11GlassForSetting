package com.magicglass;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;

/**
 * 0.5.0-p104: the splash base map is CAPTURED, not re-composed.
 *
 * <p>History: p84..p103 built `window_preview.png` by decoding the source and
 * re-applying {@link ScaleMode#matrix} at screen size. That is only correct if
 * every input to the live render is reproduced exactly, and the user kept
 * reporting "大小位置不一样" for a VIDEO background -- where the live layer is a
 * TextureView whose effective transform comes from the decoder
 * (`getTransform()`), not from a matrix we can recompute from a
 * MediaMetadataRetriever frame.
 *
 * <p>This class takes the user's suggestion literally: use the size/position of
 * the FINISHED settings background as the splash base map. The host asks its
 * own backdrop layer for exactly the bitmap it paints --
 * <ul>
 *   <li>image: {@code BackdropLayer.snapshot()}, the screen-sized snapshot with
 *       the ImageView's own transform already baked in (p20),</li>
 *   <li>video: the same one-shot {@code TextureView.getBitmap()} + live
 *       {@code getTransform()} capture that p75 proved correct for the back
 *       transition,</li>
 * </ul>
 * and streams it to the module process, which stores it as
 * `window_preview.png`. The splash therefore cannot disagree about framing:
 * it is a copy of the real thing.
 *
 * <p>One shot per (source, mode, zoom, focusX, focusY) per process, off the UI
 * thread, after the background has settled. No per-frame work, no allocation
 * in the draw path.
 */
public final class SplashBase {

    /** Write-only landing pad implemented by ModuleProvider.openFile(). */
    public static final Uri URI = Uri.parse(
            "content://" + ConfigBus.AUTHORITY + "/splashbase");

    /** Long edge of the stored base map: sharp enough behind a 64 px blur. */
    private static final int MAX_EDGE = 720;
    /** Never store something smaller than this: the splash must fill a screen. */
    private static final int MIN_EDGE = 200;

    private SplashBase() {
    }

    /** Same five fields the preview sidecar records. */
    public static String signature(String source, String mode, int zoom,
                                   int fx, int fy) {
        if (source == null || source.length() == 0) return null;
        return source.trim() + "|" + (mode == null ? "-" : mode)
                + "|" + zoom + "|" + fx + "|" + fy;
    }

    /**
     * Encode and hand a captured backdrop to the module process.
     *
     * @param src the live bitmap; NOT recycled here (the glass may still hold it)
     */
    public static void commitAsync(final Context ctx, final Bitmap src,
                                   final String signature) {
        if (ctx == null || src == null || src.isRecycled()
                || signature == null) return;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                commit(ctx, src, signature);
            }
        }, "mg-splashbase");
        t.setPriority(Thread.MIN_PRIORITY);
        t.setDaemon(true);
        try {
            t.start();
        } catch (Throwable t2) {
            MgLog.say("mg-state splashBase skip why=" + t2);
        }
    }

    private static void commit(Context ctx, Bitmap src, String signature) {
        Bitmap small = null;
        OutputStream os = null;
        try {
            int w = src.getWidth();
            int h = src.getHeight();
            if (w <= 0 || h <= 0) return;
            int longEdge = Math.max(w, h);
            float k = longEdge > MAX_EDGE ? (float) MAX_EDGE / longEdge : 1f;
            int tw = Math.max(1, Math.round(w * k));
            int th = Math.max(1, Math.round(h * k));
            if (Math.max(tw, th) < MIN_EDGE) {
                MgLog.say("mg-state splashBase skip why=small "
                        + tw + "x" + th);
                return;
            }
            small = Bitmap.createScaledBitmap(src, tw, th, true);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(262144);
            if (!small.compress(Bitmap.CompressFormat.PNG, 90, bytes)) {
                MgLog.say("mg-state splashBase skip why=encode");
                return;
            }
            byte[] png = bytes.toByteArray();
            os = ctx.getContentResolver().openOutputStream(URI, "w");
            if (os == null) {
                MgLog.say("mg-state splashBase skip why=no stream");
                return;
            }
            os.write(png);
            os.flush();
            os.close();
            os = null;
            Bundle extras = new Bundle();
            extras.putInt("w", tw);
            extras.putInt("h", th);
            Bundle r = ctx.getContentResolver().call(URI, "commit", signature,
                    extras);
            int ok = r == null ? 0 : r.getInt("ok", 0);
            MgLog.say("mg-state splashBase cap=" + tw + "x" + th
                    + " bytes=" + png.length + " ok=" + ok
                    + " sig=" + signature);
        } catch (Throwable t) {
            // Never fatal: the re-composed preview from ConfigStore stays in
            // place as the fallback, so the splash still shows the background.
            MgLog.say("mg-state splashBase skip why=" + t);
        } finally {
            try {
                if (os != null) os.close();
            } catch (Throwable ignored) {
            }
            if (small != null && small != src && !small.isRecycled()) {
                try {
                    small.recycle();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
