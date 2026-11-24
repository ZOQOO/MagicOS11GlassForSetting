import io

p = 'app/src/main/java/com/magicglass/ConfigStore.java'
s = io.open(p, encoding='utf-8').read()

# ---- 1. sidecar now records the FULL render signature --------------------
old = '''    private static void writePreviewSource(File dir, String sourceName) {
        FileOutputStream os = null;
        try {
            os = new FileOutputStream(new File(dir, WINDOW_PREVIEW_SRC_NAME));
            os.write(sourceName.getBytes("UTF-8"));'''
new = '''    private static void writePreviewSource(File dir, String sourceName) {
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
        FileOutputStream os = null;
        try {
            os = new FileOutputStream(new File(dir, WINDOW_PREVIEW_SRC_NAME));
            String sig = sourceName + "|" + (mode == null ? "-" : mode)
                    + "|" + zoom + "|" + fx + "|" + fy;
            os.write(sig.getBytes("UTF-8"));'''
assert old in s, 'writePreviewSource header'
s = s.replace(old, new, 1)

# ---- 2. pass the current scale parameters when writing -------------------
old = "            writePreviewSource(f.getParentFile(), f.getName());"
new = "            writePreviewSource(f.getParentFile(), f.getName(), mode, zoom, fx, fy);"
assert old in s, 'writePreviewSource call'
s = s.replace(old, new, 1)

# ---- 3. hasWindowPreview compares the whole signature --------------------
old = '''        if (o.outWidth < 200 || o.outHeight < 200) return false;'''
new = '''        if (o.outWidth < 200 || o.outHeight < 200) return false;'''
# (unchanged marker; the real edit is below)

old_sig = '''        if (sourceName != null) {
            String made = readPreviewSource(p.getParentFile());
            if (made != null && !made.equals(sourceName)) return false;
        }
        return true;'''
new_sig = '''        if (sourceName != null) {
            String made = readPreviewSource(p.getParentFile());
            Map<String, String> all = all(c);
            String mode = valueOf(all, ConfigContract.Param.BG_SCALE,
                    ScaleMode.CENTER_CROP);
            int zoom = intOf(all, ConfigContract.Param.BG_SCALE_ZOOM, 100);
            int fx = intOf(all, ConfigContract.Param.BG_SCALE_FOCUS_X, 50);
            int fy = intOf(all, ConfigContract.Param.BG_SCALE_FOCUS_Y, 50);
            String want = sourceName + "|" + mode + "|" + zoom + "|" + fx + "|" + fy;
            // p100: a legacy sidecar (file name only) never matches a signature,
            // so an upgraded install rebuilds once. That is intended.
            if (made != null && !made.equals(want)) return false;
        }
        return true;'''
assert old_sig in s, 'hasWindowPreview signature compare'
s = s.replace(old_sig, new_sig, 1)

io.open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('ConfigStore: signature-aware preview + sidecar')
