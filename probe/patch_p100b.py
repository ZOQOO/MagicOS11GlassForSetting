import io

p = 'app/src/main/java/com/magicglass/ConfigStore.java'
s = io.open(p, encoding='utf-8').read()

# imports
old = "import android.graphics.Canvas;\nimport android.graphics.Matrix;"
new = "import android.graphics.Canvas;\nimport android.graphics.Matrix;\nimport android.graphics.Paint;\nimport android.graphics.Rect;"
assert old in s
s = s.replace(old, new, 1)

# constant
old_c = '''    public static final String WINDOW_PREVIEW_NAME = "window_preview.png";'''
new_c = '''    public static final String WINDOW_PREVIEW_NAME = "window_preview.png";
    /** 0.5.0-p100: strongly blurred twin of the preview, shown by the splash. */
    public static final String WINDOW_PREVIEW_BLUR_NAME = "window_preview_blur.png";'''
assert old_c in s
s = s.replace(old_c, new_c, 1)

# generate the blurred twin right after the sharp preview is written
old_w = '''            writePreviewSource(f.getParentFile(), f.getName(), mode, zoom, fx, fy);
            GlassEntry.say("mg-state windowPreview wrote=" + tw + "x" + th
                    + " src=" + f.getName() + " mode=" + mode);'''
new_w = '''            writePreviewSource(f.getParentFile(), f.getName(), mode, zoom, fx, fy);
            GlassEntry.say("mg-state windowPreview wrote=" + tw + "x" + th
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
                GlassEntry.say("mg-state windowPreviewBlur wrote=" + tw + "x" + th
                        + " from=" + bw + "x" + bh);
            } catch (Throwable t) {
                GlassEntry.say("windowPreviewBlur failed: " + t);
            }'''
assert old_w in s
s = s.replace(old_w, new_w, 1)

io.open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('blur twin generation added')
