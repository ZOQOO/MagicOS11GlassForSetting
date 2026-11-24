package com.magicglass;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.util.DisplayMetrics;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * WRITER side of the configuration channel; runs only in the module process
 * (SettingsActivity). The host processes never write here.
 *
 * All values are Strings (ConfigContract rule). Every edit bumps
 * mg.cfg.version and sends an explicit broadcast to each scoped package, so a
 * hooked Settings page refreshes without leaving the page or rebooting.
 *
 * Background media is imported into the module's OWN filesDir (normal app
 * data, never a system partition) and exposed to host processes through
 * ModuleProvider.openAssetFile(). Host processes have no permission to read
 * /data/data/com.magicglass directly, so a content:// Uri is mandatory.
 */
public final class ConfigStore {

    private ConfigStore() {
    }

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(ConfigContract.GROUP, Context.MODE_PRIVATE);
    }

    /** Idempotent: writes defaults once so the host has a real version key. */
    public static void ensureDefaults(Context c) {
        SharedPreferences sp = prefs(c);
        if (!"0".equals(ConfigContract.version(ConfigContract.from(sp)))) return;
        SharedPreferences.Editor e = sp.edit();
        for (ConfigContract.Slot slot : ConfigContract.Slot.values()) {
            ConfigContract.writeDefaults(e, slot);
        }
        e.apply();
    }

    public static Map<String, String> all(Context c) {
        Map<String, String> out = new HashMap<String, String>();
        try {
            for (Map.Entry<String, ?> e : prefs(c).getAll().entrySet()) {
                out.put(e.getKey(), e.getValue() == null
                        ? "" : String.valueOf(e.getValue()));
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    public static void write(Context c, ConfigContract.Slot slot,
                             ConfigContract.Param p, String value, boolean inherit) {
        write(c, slot, p, value, inherit, true);
    }

    /**
     * 0.5.0: `notify=false` lets the settings UI coalesce a slider drag into
     * ONE cross-process broadcast instead of nine per progress event. The
     * value is still committed immediately (apply()), so a later lifecycle
     * refresh always sees it.
     */
    public static void write(Context c, ConfigContract.Slot slot,
                             ConfigContract.Param p, String value, boolean inherit,
                             boolean notify) {
        SharedPreferences.Editor e = prefs(c).edit();
        ConfigContract.put(e, slot, p, value);
        if (slot != ConfigContract.Slot.GLOBAL) {
            ConfigContract.setInherit(e, slot, p, inherit);
        }
        e.apply();
        if (notify) notifyChanged(c);
    }

    /**
     * 0.5.0-p126: 按 global_id 增删/覆盖一条元素级规则到 EL_RULES（全局规则串）。
     * 复用现有 write(...) -> notifyChanged 广播链，改完同进程即时生效，不另造机制。
     * el.rules 是全局规则，调用方传 GLOBAL slot。value 为 null/空表示删除该规则。
     */
    public static void writeElRule(Context c, ConfigContract.Slot slot,
                                   String gid, String dim, String value) {
        Map<String, String> all = all(c);
        String key = ConfigContract.key(slot, ConfigContract.Param.EL_RULES);
        String current = all.get(key);
        if (current == null) current = ConfigContract.Param.EL_RULES.def;
        String updated = ConfigContract.elRuleSet(current, gid, dim, value);
        write(c, slot, ConfigContract.Param.EL_RULES, updated, true);
    }

    /** Restore all parameters of one slot to "inherit from global". */
    public static void resetSlot(Context c, ConfigContract.Slot slot) {
        SharedPreferences.Editor e = prefs(c).edit();
        ConfigContract.writeDefaults(e, slot);
        e.apply();
        notifyChanged(c);
    }

    /**
     * 0.5.0-p66: the settings UI no longer offers a page selector -- every
     * edit writes GLOBAL. Old per-page overrides written by earlier builds
     * would still win in ConfigContract.resolve(), so this one action makes
     * "所有页面统一" actually true for existing installs as well.
     * Only the module's own preferences are touched.
     */
    public static void clearOverrides(Context c) {
        SharedPreferences.Editor e = prefs(c).edit();
        for (ConfigContract.Slot slot : ConfigContract.Slot.values()) {
            if (slot == ConfigContract.Slot.GLOBAL) continue;
            ConfigContract.writeDefaults(e, slot);
        }
        e.apply();
        notifyChanged(c);
    }

    /**
     * 0.5.0-p68: parameters that were exposed by the p63..p67 settings UI and
     * can therefore hold a USER value that overrides the LiquidTab reference
     * recipe. The render path was never changed -- MaterialBridge.java,
     * LiquidGlassView.java and LiquidShader.java are byte-identical to the
     * L1-ACCEPTED-p30 baseline -- but MaterialBridge.param()/tintOf() read
     * the config first and only fall back to the LiquidTab constants when the
     * value is -1. Dragging any one of these sliders once was enough to
     * replace refraction=10dp / thickness=-26dp / dispersion=0.42 /
     * blur=2f / tint=0.40 with a scaled user number.
     *
     * GLASS_ENABLED is deliberately NOT in this list: it is the on/off switch,
     * not a recipe parameter, and the user explicitly asked for it to stay
     * live-togglable. The engine/material/scene knobs were never rendered by
     * any UI and are left alone.
     *
     * Idempotent: only keys that actually differ from p30 defaults are
     * rewritten, and non-GLOBAL slots are put back on "inherit" so a leftover
     * per-page override cannot win in ConfigContract.resolve().
     */
    private static final ConfigContract.Param[] FROZEN_GLASS = {
            // 0.5.0-p172: 以下 8 个键(3 几何 + 5 质感)已解锁为用户可调,
            // 从 FROZEN 列表移除——否则设置界面写入后会被 restoreGlassDefaults
            // 强制写回默认值。0.5.0-p173: 用户选 8 项全部统一,ConfigContract.
            // resolve() 现在让这批键对所有 slot(含 L1)强制继承 GLOBAL,
            // p172 的 slot==L1 硬门已撤除。GEO_UNIFORM_CARD_WIDTH 等其余键继续冻结。
            ConfigContract.Param.GEO_UNIFORM_CARD_WIDTH,
            ConfigContract.Param.GLASS_GROUP_CARDS,
            ConfigContract.Param.GLASS_STANDALONE_CARDS,
            ConfigContract.Param.GLASS_GENERIC_CARDS,
            ConfigContract.Param.GLASS_FORCE_BACKING,
            ConfigContract.Param.PERF_TRACK_TAIL_MS,
    };

    /** @return true when at least one key had to be restored. */
    public static boolean restoreGlassDefaults(Context c) {
        Map<String, String> all = all(c);
        SharedPreferences.Editor e = prefs(c).edit();
        boolean changed = false;
        for (ConfigContract.Slot slot : ConfigContract.Slot.values()) {
            for (ConfigContract.Param p : FROZEN_GLASS) {
                String k = ConfigContract.key(slot, p);
                String cur = all.get(k);
                if (cur == null || !p.def.equals(cur)) {
                    ConfigContract.put(e, slot, p, p.def);
                    changed = true;
                }
                if (slot != ConfigContract.Slot.GLOBAL) {
                    String ik = ConfigContract.inheritKey(slot, p);
                    String iv = all.get(ik);
                    if (iv != null && !"true".equals(iv)) {
                        e.putString(ik, "true");
                        changed = true;
                    }
                }
            }
        }
        if (changed) {
            e.apply();
            notifyChanged(c);
        }
        return changed;
    }

    /**
     * 0.5.0-p68: 背景 is 自定义 only. Existing installs may still store a
     * p67 preset (centerCrop/fitCenter/...), in which case the sliders would
     * look alive but change nothing. Force the single mode.
     *
     * 0.5.0-p69 CHANGE -- "一个上下挪动位置，一个左右挪动位置":
     * p68 ALSO wrote BG_SCALE_FOCUS_X = "50" on EVERY SettingsActivity entry.
     * That made a 左右位置 slider a DEAD control: whatever the user chose was
     * silently reset the next time the settings page opened. The horizontal
     * focus is now a genuine user value and this method NEVER writes a literal
     * value for it (nor for zoom / vertical focus).
     *
     * Only one migration is kept: p63..p65 could write a per-page horizontal
     * focus with inherit=false. Since p66 the UI is global-only, such a stale
     * override would win in ConfigContract.resolve() and hide the slider's
     * effect. Clearing just that inherit flag makes GLOBAL the single source
     * of truth without ever freezing the value. Idempotent.
     */
    public static boolean ensureCustomFilling(Context c) {
        Map<String, String> all = all(c);
        SharedPreferences.Editor e = prefs(c).edit();
        boolean changed = false;
        for (ConfigContract.Slot slot : ConfigContract.Slot.values()) {
            String ks = ConfigContract.key(slot, ConfigContract.Param.BG_SCALE);
            if (!ScaleMode.CUSTOM.equals(all.get(ks))) {
                ConfigContract.put(e, slot, ConfigContract.Param.BG_SCALE,
                        ScaleMode.CUSTOM);
                changed = true;
            }
            if (slot != ConfigContract.Slot.GLOBAL) {
                String ik = ConfigContract.inheritKey(slot,
                        ConfigContract.Param.BG_SCALE_FOCUS_X);
                if ("false".equals(all.get(ik))) {
                    ConfigContract.setInherit(e, slot,
                            ConfigContract.Param.BG_SCALE_FOCUS_X, true);
                    changed = true;
                }
            }
        }
        if (changed) {
            e.apply();
            notifyChanged(c);
        }
        return changed;
    }

    /**
     * Explicit broadcast to each scoped process. A fresh Intent per package
     * avoids mutating a shared object while the loop is running. The receivers
     * in those processes are registered as RECEIVER_EXPORTED (0.5.0): a
     * NOT_EXPORTED receiver rejects broadcasts sent by ANOTHER application,
     * which is precisely what made config edits appear to need a restart.
     */
    public static void notifyChanged(Context c) {
        for (String pkg : Scope.all()) {
            try {
                Intent i = new Intent(ConfigBus.ACTION_CONFIG_CHANGED);
                i.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                i.setPackage(pkg);
                c.sendBroadcast(i);
            } catch (Throwable ignored) {
            }
        }
    }

    // ---- background media ---------------------------------------------------

    public static File mediaDir(Context c) {
        File d = new File(c.getFilesDir(), "bg");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** Resolve a media name with strict path-traversal protection. */
    public static File mediaFile(Context c, String name) {
        if (name == null || name.length() == 0) return null;
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0
                || name.contains("..")) return null;
        File dir = mediaDir(c);
        File f = new File(dir, name);
        try {
            if (!f.getCanonicalPath().startsWith(dir.getCanonicalPath() + File.separator)) {
                return null;
            }
        } catch (Throwable t) {
            return null;
        }
        return f;
    }

    /**
     * 0.5.0-p189: 把裁剪好的位图存成 PNG 到 media 目录，返回安全文件名。
     * 用于交互裁剪（拖动/缩放）后的成品；绘制端直接读该文件，不再二次变换。
     */
    public static String importBitmap(Context c, android.graphics.Bitmap bmp, String kind) {
        if (c == null || bmp == null) return null;
        FileOutputStream out = null;
        try {
            String base = "bg_" + (kind == null ? "icon" : kind) + "_"
                    + System.currentTimeMillis();
            File f = new File(mediaDir(c), base + ".png");
            out = new FileOutputStream(f);
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
            out.flush();
            return f.getName();
        } catch (Throwable t) {
            return null;
        } finally {
            close(out);
        }
    }

    /** Import a SAF Uri into our filesDir; returns the safe file name. */
    public static String importMedia(Context c, Uri src, String kind) {        InputStream in = null;
        OutputStream out = null;
        try {
            String base = "bg_" + (kind == null ? "media" : kind) + "_"
                    + System.currentTimeMillis();
            String ext = extensionOf(c, src);
            File f = new File(mediaDir(c), base + ext);
            in = c.getContentResolver().openInputStream(src);
            if (in == null) return null;
            out = new FileOutputStream(f);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
            return f.getName();
        } catch (Throwable t) {
            return null;
        } finally {
            close(in);
            close(out);
        }
    }

    // ---- p77: one-shot average colour of the chosen background -------------

    /**
     * 0.5.0-p77. The user's first-entry complaint is NOT (only) latency: it is
     * the visible COLOUR JUMP from the fixed near-white WINDOW_BASE_COLOR to
     * the chosen wallpaper. p66 proved the starting window is drawn by
     * SystemUI BEFORE our process exists, so a runtime hook can only align
     * its colour, never push a bitmap. Therefore we derive ONE average colour
     * from the media HERE, in the module process, at the moment the user picks
     * it, and persist it as BG_WINDOW_COLOR. Every later consumer
     * (SystemUI starting-window hook, host window backdrop, opaque plate) just
     * parses that String: zero per-frame work, zero extra IPC, zero bitmap in
     * the frame path. This is a derived config value, NOT the p68 preview UI.
     *
     * Power: one 16 px decode (image) or one decoder frame (video), ONCE per
     * picked / upgraded background -- thousands of times cheaper than any
     * per-frame sampling. A pure-black first video frame is lifted slightly so
     * the transition window is never mistaken for the p42 black screen.
     */
    /**
     * 0.5.0-p80: a small, REAL picture of the chosen background, stored next
     * to the media files. The FIRST window frame reads it through the already
     * existing content://.../media/<name> channel, so no new IPC is added.
     * §45.3 recorded "backdrop FAIL why=1 ... snap=0x0" on entry: before the
     * View tree is laid out there is no screen snapshot, so the window could
     * only paint a flat colour and then jumped to the wallpaper.
     */
    public static final String WINDOW_PREVIEW_NAME = "window_preview.png";
    /** 0.5.0-p100: strongly blurred twin of the preview, shown by the splash. */
    public static final String WINDOW_PREVIEW_BLUR_NAME = "window_preview_blur.png";
    /** 0.5.0-p84: sidecar naming the source that produced the preview. */
    private static final String WINDOW_PREVIEW_SRC_NAME = "window_preview.src";
    private static final int WINDOW_PREVIEW_MAX_EDGE = 320;

    /**
     * 0.5.0-p84: HOW THE PREVIEW IS BUILT CHANGED, and this is the defect the
     * user reported as "still feels like it is loading".
     *
     * p80..p83 wrote `window_preview.png` from `frame` -- the ~16-32 px bitmap
     * that exists ONLY to compute the average colour. With WINDOW_PREVIEW_MAX_
     * EDGE = 320 the scale factor was clamped to 1 (never upscale), so the
     * file really was 33x33 px: measured on device as 2051 bytes. Stretched
     * over 1272x2800 that is a featureless blur, so frame 0 looked like a
     * loading placeholder instead of the chosen background.
     *
     * The preview is now decoded at screen scale and composited with the SAME
     * ScaleMode.matrix() the settled backdrop uses, at HALF the screen size.
     * Frame 0 is therefore a faithful copy of what the user picked -- same
     * crop, same zoom, same focus -- and the hand-over to the live backdrop
     * is invisible.
     */
    public static String deriveWindowColor(Context c, File f, String kind) {
        if (f == null || !f.isFile()) return "";
        Bitmap frame = null;
        Bitmap one = null;
        MediaMetadataRetriever mmr = null;
        InputStream in = null;
        try {
            if ("video".equals(kind)) {
                mmr = new MediaMetadataRetriever();
                mmr.setDataSource(f.getAbsolutePath());
                frame = mmr.getFrameAtTime(0);
            } else {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                in = new FileInputStream(f);
                BitmapFactory.decodeStream(in, null, bounds);
                close(in);
                in = null;
                int max = Math.max(bounds.outWidth, bounds.outHeight);
                int sample = 1;
                while (max / (sample * 2) > 16 && sample < 1024) sample *= 2;
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = sample;
                opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
                in = new FileInputStream(f);
                frame = BitmapFactory.decodeStream(in, null, opts);
            }
            if (frame == null || frame.isRecycled()) return "";
            // 0.5.0-p84: a real, screen-shaped preview (see the javadoc above).
            // `frame` is the video's first frame (full size) or the tiny
            // average-colour decode (image); the helper re-decodes images at
            // screen scale, so it never reuses a 33 px bitmap.
            try {
                writeWindowPreview(c, f, kind, frame);
            } catch (Throwable ignored) {
            }
            one = Bitmap.createScaledBitmap(frame, 1, 1, true);
            int rgb = one.getPixel(0, 0) & 0x00FFFFFF;
            int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
            // Lift a genuinely black frame so the un-composited window stays
            // a neutral dark tone instead of the p42 "black screenshot".
            if (r + g + b < 0x30) {
                r = Math.max(r, 0x18);
                g = Math.max(g, 0x18);
                b = Math.max(b, 0x1A);
            }
            return String.format("#%02X%02X%02X", r, g, b);
        } catch (Throwable t) {
            MgLog.say("window colour derive failed: " + t);
            return "";
        } finally {
            close(in);
            if (mmr != null) {
                try {
                    mmr.release();
                } catch (Throwable ignored) {
                }
            }
            if (one != null && one != frame) {
                try {
                    one.recycle();
                } catch (Throwable ignored) {
                }
            }
            if (frame != null) {
                try {
                    frame.recycle();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * 0.5.0-p84: build `window_preview.png` = the chosen background AS IT WILL
     * LOOK on this screen, at half resolution.
     *
     * Why half resolution and not "<= 320 px": the preview is stretched 1:1 to
     * the window on frame 0, so its pixel size IS the visible sharpness. A
     * 636x1400 copy of a 1272x2800 screen is indistinguishable at a glance and
     * keeps the PNG around a few hundred KB.
     *
     * Why composite here instead of storing the raw picture: the backdrop
     * applies ScaleMode.matrix() (crop / zoom / focus). If frame 0 ignored
     * that, the splash would show a differently cropped picture and visibly
     * snap on hand-over -- the p74/p75 stretch defect in another form. Using
     * the SAME matrix keeps the two pixel-consistent.
     */
    private static void writeWindowPreview(Context c, File f, String kind,
                                           Bitmap tinyOrVideoFrame) {
        if (c == null || f == null || tinyOrVideoFrame == null) return;
        Bitmap src = null;
        Bitmap out = null;
        // 0.5.0-p94 (plan A): the starting window now shows this picture BEFORE
        // the real first frame and then cross-fades into it, so the two must
        // match pixel for pixel. Screen/2 (p86) made the splash visibly softer
        // than the content it dissolved into -- the "sharpness jump".
        DisplayMetrics dm0 = c.getResources().getDisplayMetrics();
        int vw = dm0.widthPixels > 0 ? dm0.widthPixels : 1080;
        int vh = dm0.heightPixels > 0 ? dm0.heightPixels : 1920;
        try {
            if ("video".equals(kind)) {
                // mmr.getFrameAtTime(0) is already full size.
                src = tinyOrVideoFrame;
            } else {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                InputStream in0 = new FileInputStream(f);
                try {
                    BitmapFactory.decodeStream(in0, null, bounds);
                } finally {
                    close(in0);
                }
                int max = Math.max(bounds.outWidth, bounds.outHeight);
                if (max <= 0) return;
                int target = Math.max(vw, vh);
                int sample = 1;
                while (max / (sample * 2) >= target && sample < 1024) sample *= 2;
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = sample;
                o.inPreferredConfig = Bitmap.Config.ARGB_8888;
                InputStream in1 = new FileInputStream(f);
                try {
                    src = BitmapFactory.decodeStream(in1, null, o);
                } finally {
                    close(in1);
                }
                if (src == null) return;
            }
            int tw = Math.max(1, vw);
            int th = Math.max(1, vh);
            Map<String, String> all = all(c);
            String mode = valueOf(all, ConfigContract.Param.BG_SCALE,
                    ScaleMode.CENTER_CROP);
            int zoom = intOf(all, ConfigContract.Param.BG_SCALE_ZOOM, 100);
            int fx = intOf(all, ConfigContract.Param.BG_SCALE_FOCUS_X, 50);
            int fy = intOf(all, ConfigContract.Param.BG_SCALE_FOCUS_Y, 50);
            out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(out);
            Matrix m = ScaleMode.matrix(mode, zoom, fx, fy,
                    src.getWidth(), src.getHeight(), tw, th);
            if (m != null) cv.concat(m);
            cv.drawBitmap(src, 0f, 0f, null);
            File png = new File(f.getParentFile(), WINDOW_PREVIEW_NAME);
            FileOutputStream os = new FileOutputStream(png);
            try {
                out.compress(Bitmap.CompressFormat.PNG, 90, os);
            } finally {
                close(os);
            }
            writePreviewSource(f.getParentFile(), f.getName(), mode, zoom, fx, fy);
            MgLog.say("mg-state windowPreview wrote=" + tw + "x" + th
                    + " src=" + f.getName() + " mode=" + mode + " zoom=" + zoom
                    + " focus=" + fx + "," + fy);
            // 0.5.0-p100: a strongly blurred twin derived from the SAME `out`,
            // so the framing is identical by construction. The starting window
            // shows this one and the real first frame is sharp, so the hand-over
            // reads as a focus pull rather than a picture swap. Downscale to
            // 1/14 and bilinear upscale = a smooth, cheap, very strong blur
            // (no RenderEffect: a software Canvas ignores it).
            try {
                int bw = Math.max(1, tw / 14);
                int bh = Math.max(1, th / 14);
                Bitmap tiny = Bitmap.createScaledBitmap(out, bw, bh, true);
                Bitmap blurred = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
                Canvas bc = new Canvas(blurred);
                Paint bp = new Paint(Paint.FILTER_BITMAP_FLAG);
                bc.drawBitmap(tiny, null, new Rect(0, 0, tw, th), bp);
                try {
                    tiny.recycle();
                } catch (Throwable ignored) {
                }
                File bpng = new File(f.getParentFile(), WINDOW_PREVIEW_BLUR_NAME);
                FileOutputStream bos = new FileOutputStream(bpng);
                try {
                    blurred.compress(Bitmap.CompressFormat.PNG, 90, bos);
                } finally {
                    close(bos);
                }
                try {
                    blurred.recycle();
                } catch (Throwable ignored) {
                }
                MgLog.say("mg-state windowPreviewBlur wrote=" + tw + "x" + th
                        + " from=" + bw + "x" + bh);
            } catch (Throwable t) {
                MgLog.say("mg-state windowPreviewBlur failed: " + t);
            }
        } catch (Throwable t) {
            MgLog.say("mg-state windowPreview write failed: " + t);
        } finally {
            if (out != null) {
                try {
                    out.recycle();
                } catch (Throwable ignored) {
                }
            }
            if (src != null && src != tinyOrVideoFrame) {
                try {
                    src.recycle();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** Half the screen's long edge, so the preview is sharp but small. */
    private static int previewLongEdge(Context c) {
        int e = WINDOW_PREVIEW_MAX_EDGE;
        try {
            DisplayMetrics dm = c.getResources().getDisplayMetrics();
            e = Math.max(dm.widthPixels, dm.heightPixels) / 2;
            if (e < 320) e = 320;
            if (e > 1600) e = 1600;
        } catch (Throwable ignored) {
        }
        return e;
    }

    private static String valueOf(Map<String, String> all,
                                  ConfigContract.Param p, String def) {
        String v = all.get(ConfigContract.key(ConfigContract.Slot.GLOBAL, p));
        return v == null || v.length() == 0 ? def : v;
    }

    private static int intOf(Map<String, String> all,
                             ConfigContract.Param p, int def) {
        try {
            return Integer.parseInt(valueOf(all, p, String.valueOf(def)));
        } catch (Throwable t) {
            return def;
        }
    }

    /**
     * 0.5.0-p84: sidecar recording WHICH source produced the preview. p80..p83
     * only checked "colour key exists AND preview file exists", so switching
     * image <-> video kept a preview of the OLD background forever.
     */
    private static void writePreviewSource(File dir, String sourceName) {
        writePreviewSource(dir, sourceName, null, 100, 50, 50);
    }

    /**
     * 0.5.0-p100: the sidecar now records the WHOLE render signature, not only
     * the file name. Measured defect: the user moved the background with the
     * "上下位置 / 左右位置 / 拉伸大小" sliders (bg.scaleFocusX=5, scaleFocusY=0),
     * the source file did not change, so the preview was never rebuilt and the
     * starting window kept showing the OLD framing -- "大小位置不一样".
     */
    private static void writePreviewSource(File dir, String sourceName,
                                           String mode, int zoom, int fx, int fy) {
        writePreviewSource(dir, sourceName + "|" + (mode == null ? "-" : mode)
                + "|" + zoom + "|" + fx + "|" + fy, false);
    }

    /**
     * 0.5.0-p104: `cap` marks a CAPTURED base map -- one produced by the host
     * from what it actually draws (see SplashBase), not by re-composing the
     * source with ScaleMode.matrix(). A captured map is correct by
     * construction, so the "must be at least 90% of the screen" rule (which
     * only exists to catch a re-composed picture that came out too small)
     * does not apply to it.
     */
    private static void writePreviewSource(File dir, String signature, boolean cap) {
        FileOutputStream os = null;
        try {
            os = new FileOutputStream(new File(dir, WINDOW_PREVIEW_SRC_NAME));
            os.write((cap ? signature + CAP_SUFFIX : signature).getBytes("UTF-8"));
        } catch (Throwable ignored) {
        } finally {
            close(os);
        }
    }

    /**
     * 0.5.0-p104: the full render signature the LIVE backdrop is drawn with.
     * ModuleProvider compares it against a captured base map before accepting
     * one, so a capture taken for the previous background can never win.
     */
    public static String currentSignature(Context c) {
        if (c == null) return null;
        Map<String, String> all = all(c);
        String name = valueOf(all, ConfigContract.Param.BG_SOURCE, null);
        if (name == null) return null;
        name = name.trim();
        if (name.length() == 0) return null;
        String mode = valueOf(all, ConfigContract.Param.BG_SCALE,
                ScaleMode.CENTER_CROP);
        return name + "|" + mode
                + "|" + intOf(all, ConfigContract.Param.BG_SCALE_ZOOM, 100)
                + "|" + intOf(all, ConfigContract.Param.BG_SCALE_FOCUS_X, 50)
                + "|" + intOf(all, ConfigContract.Param.BG_SCALE_FOCUS_Y, 50);
    }

    /** Temp file the host streams a captured base map into (write-mode FD). */
    public static final String SPLASH_BASE_TMP = "splash_base.tmp";
    /** Sidecar suffix marking a captured base map. */
    public static final String CAP_SUFFIX = "|cap";

    /**
     * 0.5.0-p104: promote a base map the HOST captured (already streamed into
     * SPLASH_BASE_TMP through ModuleProvider.openFile) to window_preview.png.
     *
     * The capture is the settled backdrop exactly as it is composited on
     * screen -- reading it back is the only way the splash can share the live
     * framing without recomputing any geometry. Everything is validated
     * against the CURRENT signature so a stale capture is dropped instead of
     * being shown once at the next launch.
     */
    public static boolean installSplashBase(Context c, String signature) {
        if (c == null || signature == null || signature.length() == 0) return false;
        File dir = mediaDir(c);
        File tmp = new File(dir, SPLASH_BASE_TMP);
        if (!tmp.isFile() || tmp.length() == 0) return false;
        String current = currentSignature(c);
        if (current == null || !current.equals(signature)) {
            tmp.delete();
            return false;
        }
        File png = new File(dir, WINDOW_PREVIEW_NAME);
        if (png.isFile() && !png.delete()) return false;
        if (!tmp.renameTo(png)) return false;
        writePreviewSource(dir, signature, true);
        return true;
    }

    private static String readPreviewSource(File dir) {
        FileInputStream in = null;
        try {
            File f = new File(dir, WINDOW_PREVIEW_SRC_NAME);
            if (!f.isFile()) return null;
            byte[] buf = new byte[(int) Math.min(512, f.length())];
            in = new FileInputStream(f);
            int n = in.read(buf);
            return n <= 0 ? null : new String(buf, 0, n, "UTF-8").trim();
        } catch (Throwable t) {
            return null;
        } finally {
            close(in);
        }
    }

    /**
     * Write the derived colour into GLOBAL. force=true on a fresh pick;
     * force=false is an idempotent migration for installs upgraded from
     * p76 (which had no BG_WINDOW_COLOR key). Returns true when a key was
     * actually written, so callers never broadcast on a no-op.
     */
    public static boolean writeWindowColor(Context c, File f, String kind,
                                           boolean force, boolean notify) {
        Map<String, String> all = all(c);
        String k = ConfigContract.key(ConfigContract.Slot.GLOBAL,
                ConfigContract.Param.BG_WINDOW_COLOR);
        if (!force && all.get(k) != null && all.get(k).length() > 0) return false;
        String colour = deriveWindowColor(c, f, kind);
        if (colour.length() == 0) return false;
        if (colour.equals(all.get(k))) return false;
        SharedPreferences.Editor e = prefs(c).edit();
        ConfigContract.put(e, ConfigContract.Slot.GLOBAL,
                ConfigContract.Param.BG_WINDOW_COLOR, colour);
        e.apply();
        // On a fresh pick the settings UI already debounces all of its
        // writes into ONE broadcast (ui.postDelayed(push, 60)); sending a
        // second one here would be redundant cross-process work.
        if (notify) notifyChanged(c);
        MgLog.say("window colour sample=" + colour + " kind=" + kind);
        return true;
    }

    /** 0.5.0-p80: the first-frame picture lives next to the media files. */
    public static boolean hasWindowPreview(Context c) {
        // 0.5.0-p100: this overload used to pass null, which SKIPPED the
        // signature check entirely -- so ModuleProvider.query() (the only
        // caller that matters at startup) never noticed that the user had
        // moved the background with the position/size sliders, and the preview
        // kept the OLD framing ("大小位置不一样"). Resolve the source name here
        // and let the strict overload decide.
        Map<String, String> all = all(c);
        String name = valueOf(all, ConfigContract.Param.BG_SOURCE, null);
        return hasWindowPreview(c, name);
    }

    /**
     * 0.5.0-p84: a preview is only usable when it is (a) present, (b) big
     * enough to be a picture rather than a blur, and (c) made from the
     * CURRENT background source. p80..p83 only tested existence, so a 33x33
     * legacy thumbnail — or a preview of the previous background — was
     * accepted forever and frame 0 stayed a blur.
     */
    public static boolean hasWindowPreview(Context c, String sourceName) {
        File p = new File(mediaDir(c), WINDOW_PREVIEW_NAME);
        if (!p.isFile() || p.length() == 0) return false;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try {
            BitmapFactory.decodeFile(p.getAbsolutePath(), o);
        } catch (Throwable ignored) {
        }
        if (o.outWidth < 200 || o.outHeight < 200) return false;
        // 0.5.0-p104: a CAPTURED base map (sidecar ends with "|cap") is the
        // settled backdrop read back from the host, so it is correct by
        // construction no matter what resolution the capture used; it is
        // exempt from the size rule below. Everything else is a re-composed
        // picture and must be full-screen-ish.
        String made = readPreviewSource(p.getParentFile());
        boolean cap = made != null && made.endsWith(CAP_SUFFIX);
        if (!cap) {
            // 0.5.0-p94 (plan A): a HALF-screen preview (p86 produced 636x1400
            // on a 1272-wide screen) is no longer acceptable -- the splash now
            // cross-fades into a full-screen first frame, so an undersized
            // picture would still show the "soft then sharp" jump this plan
            // exists to remove. Anything below 90% of the screen width is
            // regenerated once.
            DisplayMetrics dm = c.getResources().getDisplayMetrics();
            int need = (int) (dm.widthPixels * 0.9f);
            if (need > 0 && o.outWidth < need) return false;
        }
        // 0.5.0-p103: the blurred twin is no longer required -- the splash blurs
        // the view on the GPU instead. Requiring the file here would rebuild a
        // 3 MB PNG on every single launch for nothing.
        if (sourceName != null) {
            String made5 = cap
                    ? made.substring(0, made.length() - CAP_SUFFIX.length())
                    : made;
            Map<String, String> all = all(c);
            String mode = valueOf(all, ConfigContract.Param.BG_SCALE,
                    ScaleMode.CENTER_CROP);
            int zoom = intOf(all, ConfigContract.Param.BG_SCALE_ZOOM, 100);
            int fx = intOf(all, ConfigContract.Param.BG_SCALE_FOCUS_X, 50);
            int fy = intOf(all, ConfigContract.Param.BG_SCALE_FOCUS_Y, 50);
            String want = sourceName + "|" + mode + "|" + zoom + "|" + fx + "|" + fy;
            // p100: a legacy sidecar (file name only) never matches a signature,
            // so an upgraded install rebuilds once. That is intended.
            if (made5 != null && !made5.equals(want)) return false;
        }
        return true;
    }

    /**
     * Idempotent: derive once for the CURRENT background on upgrade/open.
     * 0.5.0-p80: an install upgraded from p77/p78/p79 ALREADY has the colour
     * key but NO window_preview.png. If we only checked the colour key, those
     * users would never get the real first-frame picture. deriveWindowColor()
     * writes both, so re-run it once when the preview file is missing.
     */
    public static boolean ensureWindowColor(Context c) {
        Map<String, String> all = all(c);
        String name = all.get(ConfigContract.key(ConfigContract.Slot.GLOBAL,
                ConfigContract.Param.BG_SOURCE));
        String g = ConfigContract.key(ConfigContract.Slot.GLOBAL,
                ConfigContract.Param.BG_WINDOW_COLOR);
        boolean haveColour = all.get(g) != null && all.get(g).length() > 0;
        // p84: the preview must belong to the CURRENT source and be a real
        // picture (>= 200 px). A 33 px thumbnail or a preview of the previous
        // background now counts as MISSING, so it is regenerated.
        boolean havePreview = name != null && name.length() > 0
                ? hasWindowPreview(c, name) : hasWindowPreview(c, null);
        if (haveColour && havePreview) return false;
        if (name == null || name.length() == 0) return false;
        String kind = all.get(ConfigContract.key(ConfigContract.Slot.GLOBAL,
                ConfigContract.Param.BG_KIND));
        if (kind == null) kind = "image";
        File f = mediaFile(c, name);
        if (!haveColour) return writeWindowColor(c, f, kind, false, true);
        // Colour already stored: deriveWindowColor() still (re)writes the
        // preview picture. No config write and no broadcast are needed.
        deriveWindowColor(c, f, kind);
        MgLog.say("mg-state windowColour derived=preview-only");
        return true;
    }

    /** Called by the settings UI right after a successful media import. */
    public static void onMediaPicked(Context c, String name, String kind) {
        writeWindowColor(c, mediaFile(c, name), kind, true, false);
    }

    private static String extensionOf(Context c, Uri src) {
        try {
            String type = c.getContentResolver().getType(src);
            if (type != null) {
                if (type.startsWith("video/")) return ".mp4";
                if (type.contains("png")) return ".png";
                if (type.contains("webp")) return ".webp";
            }
        } catch (Throwable ignored) {
        }
        return ".jpg";
    }

    private static void close(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Throwable ignored) {
        }
    }

    /** Human-readable state, shown by the module UI's diagnostics button. */
    public static String diagnostics(Context c) {
        Map<String, String> all = all(c);
        StringBuilder b = new StringBuilder();
        b.append("version=").append(ConfigContract.version(ConfigContract.from(prefs(c))))
                .append('\n');
        for (ConfigContract.Slot s : ConfigContract.Slot.values()) {
            b.append('\n').append('[').append(s.id).append("] ")
                    .append(ConfigContract.resolve(prefs(c), s).describe()).append('\n');
        }
        File[] files = mediaDir(c).listFiles();
        b.append("\nmedia files: ").append(files == null ? 0 : files.length).append('\n');
        b.append("keys: ").append(all.size()).append('\n');
        return b.toString();
    }
}
