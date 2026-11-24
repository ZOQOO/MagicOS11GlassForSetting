package com.magicglass;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.Map;

/**
 * Read-only bridge from the module process to the nine hooked host processes.
 *
 *   content://com.magicglass.provider/config       -> key/value Cursor
 *   content://com.magicglass.provider/media/<name> -> AssetFileDescriptor
 *
 * Why a provider instead of a world-readable SharedPreferences file:
 * MODE_WORLD_READABLE was removed years ago, and a hooked system app cannot
 * read /data/data/com.magicglass. A content Uri with openAssetFile() is the
 * supported cross-process mechanism and needs no storage permission.
 *
 * exported=true is required (the callers are other apps/processes). Nothing is
 * writable: insert/update/delete are inert, and media names are validated by
 * ConfigStore.mediaFile() before a file descriptor is ever opened.
 */
public final class ModuleProvider extends ContentProvider {

    private static final int CONFIG = 1;
    private static final int MEDIA = 2;
    /** 0.5.0-p104: write-only landing pad for a host-captured splash base map. */
    private static final int SPLASH = 3;
    private static final UriMatcher MATCHER = new UriMatcher(UriMatcher.NO_MATCH);

    static {
        MATCHER.addURI(ConfigBus.AUTHORITY, "config", CONFIG);
        MATCHER.addURI(ConfigBus.AUTHORITY, "media/*", MEDIA);
        MATCHER.addURI(ConfigBus.AUTHORITY, "splashbase", SPLASH);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        if (MATCHER.match(uri) != CONFIG || getContext() == null) return null;
        // 0.5.0-p78: the p77 average-colour key was derived ONLY from
        // SettingsActivity.onCreate() / onMediaPicked(). A user who installs
        // an upgrade and goes straight into Settings (or whose install simply
        // never re-opens the module UI) has NO bg.windowColor key, so
        // windowColorOf() falls back to the fixed near-white and the very
        // first frame still flashes white -> wallpaper. Derive it HERE, in
        // the module process, on the FIRST config read by ANY host process.
        // This also covers ConfigBus.warmUpAsync()'s early query, i.e. BEFORE
        // the first Activity is attached. Idempotent: once the key exists the
        // check is one Map lookup and no media file is touched again.
        Map<String, String> all = ConfigStore.all(getContext());
        String k = ConfigContract.key(ConfigContract.Slot.GLOBAL,
                ConfigContract.Param.BG_WINDOW_COLOR);
        // 0.5.0-p80: upgraded installs already have bg.windowColor but not
        // window_preview.png. Both artefacts come from the SAME one-shot
        // deriveWindowColor() call, so treat either one as missing.
        if (all.get(k) == null || all.get(k).length() == 0
                || !ConfigStore.hasWindowPreview(getContext())) {
            ConfigStore.ensureWindowColor(getContext());
            // Re-read so THIS query (not only the next broadcast-driven one)
            // already carries the colour. apply() on the first frame then sees
            // it with zero extra IPC and zero extra frame-path work.
            all = ConfigStore.all(getContext());
            String derived = all.get(k);
            MgLog.say("mg-state windowColour derived="
                    + (derived == null ? "none" : derived)
                    + " source="
                    + (all.get(ConfigContract.key(ConfigContract.Slot.GLOBAL,
                            ConfigContract.Param.BG_SOURCE)) == null
                            ? "none" : "set"));
        }
        MatrixCursor c = new MatrixCursor(
                new String[]{ConfigBus.COLUMN_KEY, ConfigBus.COLUMN_VALUE});
        for (Map.Entry<String, String> e : all.entrySet()) {
            c.addRow(new Object[]{e.getKey(), e.getValue()});
        }
        return c;
    }

    @Override
    public android.content.res.AssetFileDescriptor openAssetFile(Uri uri, String mode)
            throws FileNotFoundException {
        if (MATCHER.match(uri) != MEDIA || getContext() == null) {
            return super.openAssetFile(uri, mode);
        }
        if (mode != null && !mode.startsWith("r")) {
            throw new FileNotFoundException("read-only provider");
        }
        File f = ConfigStore.mediaFile(getContext(), uri.getLastPathSegment());
        if (f == null || !f.isFile()) {
            throw new FileNotFoundException("no such background media: " + uri);
        }
        ParcelFileDescriptor pfd = ParcelFileDescriptor.open(
                f, ParcelFileDescriptor.MODE_READ_ONLY);
        return new android.content.res.AssetFileDescriptor(pfd, 0L, f.length());
    }

    @Override
    public String getType(Uri uri) {
        int m = MATCHER.match(uri);
        if (m == CONFIG) return "vnd.android.cursor.dir/vnd.com.magicglass.config";
        if (m == MEDIA) return "application/octet-stream";
        if (m == SPLASH) return "image/png";
        return null;
    }

    /**
     * 0.5.0-p104: the ONLY writable entry point, and it can only ever open
     * `bg/splash_base.tmp`.
     *
     * Why a write-mode FD instead of passing the picture through a Bundle:
     * a full-screen ARGB bitmap does not fit in a binder transaction, and the
     * host (a hooked com.android.settings process) cannot write into the
     * module's private directory itself. A ParcelFileDescriptor opened by the
     * provider process carries the provider's own credentials, so the host can
     * stream a PNG in without any storage permission.
     *
     * The name is fixed -- no caller-supplied path, no traversal, no other
     * file in the module's data directory is reachable.
     */
    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode)
            throws FileNotFoundException {
        if (MATCHER.match(uri) != SPLASH || getContext() == null) {
            throw new FileNotFoundException("not writable: " + uri);
        }
        if (mode == null || !mode.startsWith("w")) {
            throw new FileNotFoundException("write-only: " + uri);
        }
        File f = new File(ConfigStore.mediaDir(getContext()),
                ConfigStore.SPLASH_BASE_TMP);
        return ParcelFileDescriptor.open(f,
                ParcelFileDescriptor.MODE_WRITE_ONLY
                        | ParcelFileDescriptor.MODE_CREATE
                        | ParcelFileDescriptor.MODE_TRUNCATE);
    }

    /**
     * 0.5.0-p104: `commit` promotes the streamed base map to window_preview.png.
     *
     * arg is the render signature the host captured FOR. It is compared with
     * the signature in the preferences, so a capture of a background the user
     * has already changed is discarded instead of being shown at the next
     * launch. On success the config-change broadcast is sent from HERE (the
     * module process), which is what makes the long-lived SystemUI drop its
     * cached picture -- no restart, no polling.
     */
    @Override
    public android.os.Bundle call(String method, String arg,
                                  android.os.Bundle extras) {
        if (getContext() == null || !"commit".equals(method)) return null;
        android.content.Context c = getContext();
        boolean ok = ConfigStore.installSplashBase(c, arg);
        if (ok) {
            int w = extras == null ? 0 : extras.getInt("w", 0);
            int h = extras == null ? 0 : extras.getInt("h", 0);
            MgLog.say("mg-state splashBase commit=ok " + w + "x" + h
                    + " sig=" + arg);
            ConfigStore.notifyChanged(c);
        } else {
            MgLog.say("mg-state splashBase commit=no sig=" + arg);
        }
        android.os.Bundle out = new android.os.Bundle();
        out.putInt("ok", ok ? 1 : 0);
        return out;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
                      String[] selectionArgs) {
        return 0;
    }
}
