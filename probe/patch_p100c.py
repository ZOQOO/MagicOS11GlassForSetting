import io

p = 'app/src/main/java/com/magicglass/ConfigBus.java'
s = io.open(p, encoding='utf-8').read()

# 1. field
old = "    private static volatile Bitmap sStartingPreview;"
new = """    private static volatile Bitmap sStartingPreview;
    /** 0.5.0-p100: strongly blurred twin, used by the splash only. */
    private static volatile Bitmap sStartingBlur;"""
assert old in s, 'field'
s = s.replace(old, new, 1)

# 2. getter next to startingPreview()
old_g = """    public static Bitmap startingPreview() {
        Bitmap b = sStartingPreview;"""
new_g = """    /**
     * 0.5.0-p100: the blurred twin for the starting window. Falls back to the
     * sharp picture when the blur has not been generated yet, so a fresh
     * install still shows SOMETHING rather than a white frame.
     */
    public static Bitmap startingBlur() {
        Bitmap b = sStartingBlur;
        if (b != null && !b.isRecycled()) return b;
        return startingPreview();
    }

    public static Bitmap startingPreview() {
        Bitmap b = sStartingPreview;"""
assert old_g in s, 'getter'
s = s.replace(old_g, new_g, 1)

# 3. read the blurred twin in the same daemon trip
old_r = """            sStartingPreview = bmp;
            if (bmp != null) sPrefetchDone = true;"""
new_r = """            sStartingPreview = bmp;
            if (bmp != null) sPrefetchDone = true;
            // 0.5.0-p100: the blurred twin, same directory, same framing.
            // Read in the SAME daemon trip so the splash never has to do IPC.
            Bitmap blur = null;
            try {
                InputStream bin = ctx.getContentResolver().openInputStream(
                        media(ConfigStore.WINDOW_PREVIEW_BLUR_NAME));
                if (bin != null) {
                    BitmapFactory.Options bo = new BitmapFactory.Options();
                    bo.inPreferredConfig = Bitmap.Config.ARGB_8888;
                    blur = BitmapFactory.decodeStream(bin, null, bo);
                }
                try {
                    if (bin != null) bin.close();
                } catch (Throwable ignored) {
                }
            } catch (Throwable ignored) {
            }
            sStartingBlur = blur;
            GlassEntry.say("mg-state startingWindowPreview blur="
                    + (blur == null ? "none"
                            : blur.getWidth() + "x" + blur.getHeight()));"""
assert old_r in s, 'read'
s = s.replace(old_r, new_r, 1)

# 4. clear it on invalidation
old_i = "        sStartingPreview = null;"
new_i = "        sStartingPreview = null;\n        sStartingBlur = null;"
assert old_i in s, 'invalidate'
s = s.replace(old_i, new_i, 1)

io.open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('ConfigBus: blur twin wired')
