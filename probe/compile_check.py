#!/usr/bin/env python3
"""Offline compile check for the MagicGlass module sources.

The build machine has no Android SDK / Gradle, so `javac` cannot see android.*
or io.github.libxposed.api.*. This script generates a minimal API surface (only
the members the module actually calls) and compiles every app Java source
against it. It catches real compiler errors (undefined variables, wrong method
signatures, missing overrides) that a pure syntax parser would miss.

It does NOT replace a real Gradle build: the stubs intentionally declare only
a subset of each Android class. Run after every source change:

    python probe/compile_check.py

Files are generated under probe/tools/ and never participate in the Gradle
build (Gradle only compiles src/main/java).
"""

import os
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APP_SRC = os.path.join(ROOT, "app", "src", "main", "java")
PROBE_SRC = os.path.join(ROOT, "probe", "src", "main", "java")
TOOLS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "tools")
STUBS = os.path.join(TOOLS, "stubs")
OUT = os.path.join(TOOLS, "out")

# fqcn -> class declaration + body (nested types are declared in the body).
CLASSES = {
    "android.R": """
public final class R {
  public static final class string {
    public static final int ok = 0;
    public static final int cancel = 0;
  }
  public static final class id {
    public static final int icon = 0x01020006;
    public static final int title = 0x01020146;
    public static final int summary = 0x01020150;
  }
  public static final class layout {
    public static final int simple_spinner_item = 0;
    public static final int simple_spinner_dropdown_item = 0;
  }
}""",
    "android.app.Instrumentation": """
public class Instrumentation {
  public void callActivityOnCreate(android.app.Activity a, android.os.Bundle b) {}
  public void callActivityOnResume(android.app.Activity a) {}
}""",
    "android.app.Activity": """
public class Activity extends android.content.Context {
  public static final int RESULT_OK = -1;
  public static final int RESULT_CANCELED = 0;
  public void setResult(int r) {}
  public void setResult(int r, android.content.Intent i) {}
  public void finish() {}
  protected void onCreate(android.os.Bundle b) {}
  protected void onResume() {}
  protected void onDestroy() {}
  protected void onActivityResult(int req, int res, android.content.Intent data) {}
  public void setContentView(android.view.View v) {}
  public void startActivityForResult(android.content.Intent i, int req) {}
  public void startActivity(android.content.Intent i) {}
  public android.content.pm.PackageManager getPackageManager() { return null; }
  public boolean isFinishing() { return false; }
  public boolean isDestroyed() { return false; }
  public android.view.Window getWindow() { return null; }
  public android.content.Intent getIntent() { return null; }
  public int getTaskId() { return 0; }
}""",
    "android.app.AlertDialog": """
public class AlertDialog {
  public static final class Builder {
    public Builder(android.content.Context c) {}
    public Builder setTitle(CharSequence s) { return this; }
    public Builder setMessage(CharSequence s) { return this; }
    public Builder setPositiveButton(int id, Object l) { return this; }
    public Builder setNegativeButton(int id, Object l) { return this; }
    public Builder setItems(CharSequence[] items, Object l) { return this; }
    public Builder setView(android.view.View v) { return this; }
    public AlertDialog show() { return null; }
  }
}""",
    "android.content.DialogInterface": """
public interface DialogInterface {
  void onClick(DialogInterface d, int which);
  interface OnClickListener { void onClick(DialogInterface d, int which); }
}""",
    "android.content.Context": """
public class Context {
  public static final int MODE_PRIVATE = 0;
  public static final int RECEIVER_NOT_EXPORTED = 4;
  public static final int RECEIVER_EXPORTED = 2;
  public android.content.SharedPreferences getSharedPreferences(String n, int mode) { return null; }
  public android.content.ContentResolver getContentResolver() { return null; }
  public android.content.res.Resources getResources() { return null; }
  public java.io.File getFilesDir() { return null; }
  public String getPackageName() { return null; }
  public void sendBroadcast(android.content.Intent i) {}
  public void registerReceiver(android.content.BroadcastReceiver r,
      android.content.IntentFilter f) {}
  public void registerReceiver(android.content.BroadcastReceiver r,
      android.content.IntentFilter f, int flags) {}
  public void unregisterReceiver(android.content.BroadcastReceiver r) {}
}""",
    "android.content.SharedPreferences": """
public interface SharedPreferences {
  java.util.Map<String, ?> getAll();
  String getString(String k, String def);
  Editor edit();
  public interface Editor {
    Editor putString(String k, String v);
    void apply();
  }
}""",
    "android.content.pm.PackageManager": """
public class PackageManager { public android.content.Intent getLaunchIntentForPackage(String p) { return null; } }
""",
    "android.content.ComponentName": """
public class ComponentName { public ComponentName(String p, String c) {} }
""",
    "android.view.MotionEvent": """
public class MotionEvent { public static final int ACTION_DOWN=0, ACTION_UP=1, ACTION_MOVE=2, ACTION_CANCEL=3, ACTION_POINTER_DOWN=5; public int getActionMasked(){return 0;} public float getX(){return 0f;} public float getY(){return 0f;} public int getPointerCount(){return 1;} }
""",
    "android.view.ScaleGestureDetector": """
public class ScaleGestureDetector { public interface OnScaleGestureListener { boolean onScale(ScaleGestureDetector d); } public static class SimpleOnScaleGestureListener implements OnScaleGestureListener { public boolean onScale(ScaleGestureDetector d){return false;} } public ScaleGestureDetector(android.content.Context c, OnScaleGestureListener l){} public boolean onTouchEvent(android.view.MotionEvent e){return false;} public float getScaleFactor(){return 1f;} public float getFocusX(){return 0f;} public float getFocusY(){return 0f;} public boolean isInProgress(){return false;} }
""",
    "android.graphics.BitmapShader": """
public class BitmapShader extends android.graphics.Shader { public BitmapShader(android.graphics.Bitmap b, TileMode tx, TileMode ty){} }
""",
    "android.provider.MediaStore": """
public class MediaStore { public static final class Images { public static final class Media { public static final android.net.Uri EXTERNAL_CONTENT_URI=null; public static android.net.Uri insertImage(android.content.ContentResolver c, android.graphics.Bitmap b, String t, String d){return null;} } } }
""",
    "android.content.Intent": """
public class Intent {
  public static final String ACTION_VIEW = "view";
  public static final String ACTION_OPEN_DOCUMENT = "open";
  public static final String CATEGORY_OPENABLE = "openable";
  public static final int FLAG_GRANT_READ_URI_PERMISSION = 1;
  public static final int FLAG_GRANT_PERSISTABLE_URI_PERMISSION = 2;
  public static final int FLAG_ACTIVITY_CLEAR_TOP = 0x04000000;
  public static final int FLAG_ACTIVITY_NEW_TASK = 0x10000000;
  public static final int FLAG_INCLUDE_STOPPED_PACKAGES = 4;
  public Intent() {}
  public Intent(String action) {}
  public Intent(String action, android.net.Uri u) {}
  public Intent(android.content.Context c, Class<?> k) {}
  public Intent putExtra(String n, android.net.Uri v) { return this; }
  public Intent putExtra(String n, String v) { return this; }
  public Intent putExtra(String n, boolean v) { return this; }
  public Intent putExtra(String k, String[] v) { return this; }
  public String[] getStringArrayExtra(String k) { return null; }
  public Intent setComponent(android.content.ComponentName c) { return this; }
  public Intent setPackage(String p) { return this; }
  public Intent addFlags(int f) { return this; }
  public Intent addCategory(String c) { return this; }
  public Intent setType(String t) { return this; }
  public Intent setData(android.net.Uri u) { return this; }
  public android.net.Uri getData() { return null; }
  public String getStringExtra(String k) { return null; }
  public String getAction() { return null; }
  public android.os.Bundle getExtras() { return null; }
  public static Intent createChooser(Intent i, CharSequence title) { return i; }
}""",
    "android.content.IntentFilter": """
public class IntentFilter {
  public IntentFilter(String action) {}
}""",
    "android.content.BroadcastReceiver": """
public abstract class BroadcastReceiver {
  public abstract void onReceive(android.content.Context c, android.content.Intent i);
}""",
    "android.content.ContentValues": "public class ContentValues {}",
    "android.content.UriMatcher": """
public class UriMatcher {
  public static final int NO_MATCH = -1;
  public UriMatcher(int code) {}
  public void addURI(String authority, String path, int code) {}
  public int match(android.net.Uri u) { return NO_MATCH; }
}""",
    "android.content.ContentResolver": """
public class ContentResolver {
  public android.database.Cursor query(android.net.Uri u, String[] p, String s,
      String[] a, String o) { return null; }
  public java.io.InputStream openInputStream(android.net.Uri u)
      throws java.io.FileNotFoundException { return null; }
  public java.io.OutputStream openOutputStream(android.net.Uri u, String mode)
      throws java.io.FileNotFoundException { return null; }
  public android.os.Bundle call(android.net.Uri u, String method, String arg,
      android.os.Bundle extras) { return null; }
  public String getType(android.net.Uri u) { return null; }
  public void takePersistableUriPermission(android.net.Uri u, int flags) {}
}""",
    "android.content.ContentProvider": """
public abstract class ContentProvider {
  public abstract boolean onCreate();
  public abstract android.database.Cursor query(android.net.Uri u, String[] p,
      String s, String[] a, String o);
  public abstract String getType(android.net.Uri u);
  public abstract android.net.Uri insert(android.net.Uri u,
      android.content.ContentValues v);
  public abstract int delete(android.net.Uri u, String s, String[] a);
  public abstract int update(android.net.Uri u, android.content.ContentValues v,
      String s, String[] a);
  public android.content.res.AssetFileDescriptor openAssetFile(
      android.net.Uri u, String mode) throws java.io.FileNotFoundException {
    return null;
  }
  public android.os.ParcelFileDescriptor openFile(android.net.Uri u, String mode)
      throws java.io.FileNotFoundException { return null; }
  public android.os.Bundle call(String method, String arg,
      android.os.Bundle extras) { return null; }
  public android.content.Context getContext() { return null; }
}""",
    "android.content.res.AssetFileDescriptor": """
public class AssetFileDescriptor {
  public AssetFileDescriptor(android.os.ParcelFileDescriptor p, long start,
      long length) {}
}""",
    "android.content.res.Resources": """
public class Resources {
  public int getIdentifier(String n, String d, String p) { return 0; }
  public android.util.DisplayMetrics getDisplayMetrics() { return null; }
  public String getResourceEntryName(int id) { return ""; }
  public android.content.res.Configuration getConfiguration() { return null; }
}""",
    "android.content.res.Configuration": """
public class Configuration {
  public int uiMode;
  public static final int UI_MODE_NIGHT_MASK = 48;
  public static final int UI_MODE_NIGHT_YES = 32;
}""",
    "android.util.DisplayMetrics": """
public class DisplayMetrics {
  public float density = 1f;
  public int widthPixels = 1080;
  public int heightPixels = 1920;
}""",
    "android.util.AttributeSet": "public interface AttributeSet {}",
    "android.util.Log": """
public class Log {
  public static int i(String tag, String msg) { return 0; }
  public static int w(String tag, String msg) { return 0; }
  public static int e(String tag, String msg) { return 0; }
}""",
    "android.database.Cursor": """
public interface Cursor {
  int getColumnIndex(String name);
  boolean moveToNext();
  String getString(int index);
  void close();
}""",
    "android.database.MatrixCursor": """
public class MatrixCursor implements android.database.Cursor {
  public MatrixCursor(String[] columns) {}
  public void addRow(Object[] values) {}
  public int getColumnIndex(String name) { return 0; }
  public boolean moveToNext() { return false; }
  public String getString(int index) { return null; }
  public void close() {}
}""",
    "android.graphics.Rect": """
public class Rect {
  public int left, top, right, bottom;
  public Rect() {}
  public Rect(Rect r) {}
  public Rect(int l, int t, int rr, int b) { left = l; top = t; right = rr; bottom = b; }
  public void set(int l, int t, int r, int b) { left=l; top=t; right=r; bottom=b; }
  public boolean isEmpty() { return left >= right || top >= bottom; }
  public boolean equals(Object o) { return this == o; }
  public int width() { return right - left; }
  public int height() { return bottom - top; }
  public void union(Rect r) {}
}""",
    "android.graphics.RectF": """
public class RectF {
  public float left, top, right, bottom;
  public RectF() {}
  public RectF(RectF r) {}
  public RectF(android.graphics.Rect r) {}
  public RectF(float l, float t, float r, float b) {}
  public void set(float l, float t, float r, float b) {}
  public float width() { return right - left; }
  public float height() { return bottom - top; }
}
""",
    "android.graphics.Color": """
public class Color {
  public static final int BLACK = 0, WHITE = 1, LTGRAY = 2, GRAY = 3, CYAN = 4;
  public static int parseColor(String s) { return 0; }
  public static int alpha(int c) { return (c >>> 24) & 0xff; }
  public static int red(int c) { return (c >> 16) & 0xff; }
  public static int green(int c) { return (c >> 8) & 0xff; }
  public static int blue(int c) { return c & 0xff; }
  public static int rgb(int r, int g, int b) { return argb(255, r, g, b); }
  public static int argb(int a, int r, int g, int b) {
    return (a << 24) | (r << 16) | (g << 8) | b;
  }
}""",
    "android.graphics.Bitmap": """
public class Bitmap {
  public static final class Config { public static final Config ARGB_8888 = new Config(); }
  public boolean isRecycled() { return false; }
  public int getWidth() { return 1; }
  public int getHeight() { return 1; }
  public static Bitmap createBitmap(int w, int h, Config c) { return new Bitmap(); }
  public static Bitmap createScaledBitmap(Bitmap src, int w, int h, boolean filter) { return new Bitmap(); }
  public int getPixel(int x, int y) { return 0; }
  public boolean compress(CompressFormat f, int q, java.io.OutputStream out) { return true; }
  public enum CompressFormat { PNG, JPEG, WEBP }
  public void recycle() {}
}""",
    "android.graphics.BitmapFactory": """
public class BitmapFactory {
  public static android.graphics.Bitmap decodeResource(android.content.res.Resources r, int id) { return null; }
  public static final class Options {
    public boolean inJustDecodeBounds;
    public int inSampleSize;
    public Bitmap.Config inPreferredConfig;
    public int outWidth, outHeight;
  }
  public static android.graphics.Bitmap decodeStream(java.io.InputStream in,
      android.graphics.Rect pad, Options opts) { return null; }
  public static android.graphics.Bitmap decodeFile(String path, Options opts) {
    return null; }
}""",
    "android.graphics.RenderEffect": """
public class RenderEffect {
  public static RenderEffect createBlurEffect(float x, float y,
      android.graphics.Shader.TileMode mode) { return null; }
  public static RenderEffect createColorFilterEffect(
      android.graphics.ColorFilter f) { return null; }
  public static RenderEffect createBlurEffect(float x, float y,
      RenderEffect input, android.graphics.Shader.TileMode mode) { return null; }
  public static RenderEffect createRuntimeShaderEffect(
      android.graphics.RuntimeShader s, String name) { return null; }
  public static RenderEffect createChainEffect(RenderEffect outer,
      RenderEffect inner) { return null; }
}""",
    "android.graphics.RuntimeShader": """
public class RuntimeShader {
  public RuntimeShader(String agsl) {}
  public void setFloatUniform(String name, float a) {}
  public void setFloatUniform(String name, float a, float b) {}
  public void setFloatUniform(String name, float a, float b, float c) {}
  public void setFloatUniform(String name, float a, float b, float c, float d) {}
}""",
    "android.graphics.RenderNode": """
public class RenderNode {
  public RenderNode(String name) {}
  public boolean setPosition(int l, int t, int r, int b) { return true; }
  public boolean hasDisplayList() { return true; }
  public android.graphics.Canvas beginRecording() { return null; }
  public void endRecording() {}
  public void setRenderEffect(android.graphics.RenderEffect e) {}
  public void discardDisplayList() {}
}""",
    "android.graphics.Paint": """
public class Paint {
  public int getAlpha() { return 255; }
  public static final int ANTI_ALIAS_FLAG = 1;
  public static final int FILTER_BITMAP_FLAG = 2;
  public enum Style { FILL, STROKE, FILL_AND_STROKE }
  public Paint() {}
  public Paint(int flags) {}
  public void setStyle(Style s) {}
  public Style getStyle() { return Style.FILL; }
  public void setStrokeWidth(float w) {}
  public void setColor(int c) {}
  public int getColor() { return 0; }
  public void setAlpha(int a) {}
  public void setShader(android.graphics.Shader s) {}
  public void setShadowLayer(float r, float dx, float dy, int color) {}
  public void setAntiAlias(boolean b) {}
  public void setStrokeCap(Object cap) {}
  public void setMaskFilter(Object f) {}
}""",
    "android.graphics.Path": """
public class Path {
  public enum Direction { CW, CCW }
  public Path() {}
  public void reset() {}
  public void addRoundRect(float l, float t, float r, float b, float rx, float ry,
      Direction d) {}
  public void addRoundRect(android.graphics.RectF r, float rx, float ry, Direction d) {}
  public void addCircle(float x, float y, float r, Direction d) {}
  public void moveTo(float x, float y) {}
  public void lineTo(float x, float y) {}
  public void close() {}
}""",
    "android.graphics.Matrix": """
public class Matrix {
  public void setTranslate(float dx, float dy) {}
  public void setScale(float sx, float sy) {}
  public void postTranslate(float dx, float dy) {}
  public void reset() {}
}""",
    "android.graphics.ColorFilter": "public abstract class ColorFilter {}",
    "android.graphics.ColorMatrix": """
public class ColorMatrix {
  public ColorMatrix() {}
  public void setSaturation(float s) {}
}""",
    "android.graphics.ColorMatrixColorFilter": """
public class ColorMatrixColorFilter extends android.graphics.ColorFilter {
  public ColorMatrixColorFilter(android.graphics.ColorMatrix m) {}
}""",
    "android.graphics.LinearGradient": """
public class LinearGradient extends android.graphics.Shader {
  public LinearGradient(float x0, float y0, float x1, float y1,
      int c0, int c1, android.graphics.Shader.TileMode mode) {}
  public LinearGradient(float x0, float y0, float x1, float y1,
      int[] colors, float[] positions, android.graphics.Shader.TileMode mode) {}
}""",
    "android.graphics.ColorFilter": "public abstract class ColorFilter {}",
    "android.graphics.ColorMatrix": """
public class ColorMatrix {
  public ColorMatrix() {}
  public void setSaturation(float s) {}
}""",
    "android.graphics.ColorMatrixColorFilter": """
public class ColorMatrixColorFilter extends android.graphics.ColorFilter {
  public ColorMatrixColorFilter(android.graphics.ColorMatrix m) {}
}""",
    "android.graphics.LinearGradient": """
public class LinearGradient extends android.graphics.Shader {
  public LinearGradient(float x0, float y0, float x1, float y1,
      int c0, int c1, android.graphics.Shader.TileMode mode) {}
  public LinearGradient(float x0, float y0, float x1, float y1,
      int[] colors, float[] positions, android.graphics.Shader.TileMode mode) {}
}""",
    "android.graphics.Shader": """
public class Shader {
  public void setLocalMatrix(android.graphics.Matrix m) {}
  public enum TileMode { CLAMP, REPEAT, MIRROR }
}""",
    "android.graphics.Canvas": """
public class Canvas {
  public void drawBitmap(android.graphics.Bitmap b, android.graphics.Rect src, android.graphics.RectF dst, android.graphics.Paint p) {}
  public Canvas() {}
  public Canvas(android.graphics.Bitmap b) {}
  public int save() { return 0; }
  public void restore() {}
  public void restoreToCount(int n) {}
  public void translate(float x, float y) {}
  public void scale(float x, float y) {}
  public void rotate(float deg) {}
  public void concat(android.graphics.Matrix m) {}
  public boolean clipPath(android.graphics.Path p) { return true; }
  public boolean clipRect(float l, float t, float r, float b) { return true; }
  public boolean clipOutPath(android.graphics.Path p) { return true; }
  public void drawColor(int c) {}
  public void drawBitmap(android.graphics.Bitmap b, float x, float y,
      android.graphics.Paint p) {}
  public void drawBitmap(android.graphics.Bitmap b, android.graphics.Rect src,
      android.graphics.Rect dst, android.graphics.Paint p) {}
  public void drawRect(float l, float t, float r, float b,
      android.graphics.Paint p) {}
  public void drawRoundRect(float l, float t, float r, float b, float rx,
      float ry, android.graphics.Paint p) {}
  public void drawPath(android.graphics.Path path, android.graphics.Paint p) {}
  public void drawRenderNode(android.graphics.RenderNode n) {}
  public void drawCircle(float x, float y, float r, android.graphics.Paint p) {}
  public void drawLine(float x0, float y0, float x1, float y1,
      android.graphics.Paint p) {}
}""",
    "android.graphics.SurfaceTexture": "public class SurfaceTexture {}",
    "android.graphics.drawable.Drawable": "public abstract class Drawable { public void draw(android.graphics.Canvas c) {} public void setAlpha(int a) {} public void setColorFilter(android.graphics.ColorFilter cf) {} public final android.graphics.Rect getBounds() { return new android.graphics.Rect(); } public int getOpacity() { return 0; } }",
    "android.graphics.drawable.GradientDrawable": """
public class GradientDrawable extends android.graphics.drawable.Drawable {
  public void setCornerRadius(float r) {}
  public void setColor(int c) {}
  public void setStroke(int w, int c) {}
}""",
    "android.media.MediaPlayer": """
public class MediaPlayer {
  public interface OnPreparedListener { void onPrepared(MediaPlayer mp); }
  public void setDataSource(android.content.Context c, android.net.Uri u)
      throws java.io.IOException {}
  public void setSurface(android.view.Surface s) {}
  public void setLooping(boolean b) {}
  public void setVolume(float l, float r) {}
  public void setOnPreparedListener(OnPreparedListener l) {}
  public void prepareAsync() {}
  public void start() {}
  public void stop() {}
  public void reset() {}
  public void release() {}
  public boolean isPlaying() { return false; }
  public int getVideoWidth() { return 0; }
  public int getVideoHeight() { return 0; }
}""",
    "android.media.MediaMetadataRetriever": """
public class MediaMetadataRetriever {
  public MediaMetadataRetriever() {}
  public void setDataSource(String path) {}
  public android.graphics.Bitmap getFrameAtTime(long timeUs) { return null; }
  public void release() {}
}""",
    "android.net.Uri": """
public class Uri {
  public static final class Builder {
    public Builder scheme(String s) { return this; }
    public Builder authority(String s) { return this; }
    public Builder appendPath(String s) { return this; }
    public Uri build() { return null; }
  }
  public static Uri parse(String s) { return null; }
  public static Uri withAppendedPath(Uri base, String path) { return null; }
  public String getLastPathSegment() { return null; }
}""",
    "android.os.Bundle": """
public class Bundle {
  public boolean containsKey(String k) { return false; }
  public java.util.Set<String> keySet() { return null; }
  public void putInt(String k, int v) {}
  public int getInt(String k, int def) { return def; }
  public void putString(String k, String v) {}
  public String getString(String k) { return null; }
}""",
    "android.os.Process": """
public class Process { public static int myPid() { return 0; } }""",
    "android.provider.Settings": """
public class Settings {
  public static final class Global {
    public static String getString(android.content.ContentResolver r, String k) {
      return null;
    }
  }
}""",
    "android.os.Handler": """
public class Handler {
  public Handler(android.os.Looper l) {}
  public boolean post(Runnable r) { return true; }
  public boolean postDelayed(Runnable r, long ms) { return true; }
  public void removeCallbacks(Runnable r) {}
}""",
    "android.os.Looper": """
public class Looper { public static Looper getMainLooper() { return null; } }""",
    "android.os.SystemClock": """
public class SystemClock { public static long uptimeMillis() { return 0L; } }""",
    "android.os.ParcelFileDescriptor": """
public class ParcelFileDescriptor {
  public static final int MODE_READ_ONLY = 0;
  public static final int MODE_WRITE_ONLY = 1;
  public static final int MODE_READ_WRITE = 2;
  public static final int MODE_CREATE = 8;
  public static final int MODE_TRUNCATE = 4;
  public static ParcelFileDescriptor open(java.io.File f, int mode)
      throws java.io.FileNotFoundException { return null; }
}""",
    "android.text.InputType": """
public class InputType { public static final int TYPE_CLASS_TEXT = 1; }""",
    "android.view.Gravity": """
public class Gravity {
  public static final int CENTER_HORIZONTAL = 1;
  public static final int BOTTOM = 80;
  public static final int CENTER = 17;
  public static final int CENTER_VERTICAL = 16;
}""",
    "android.view.Surface": """
public class Surface { public Surface(android.graphics.SurfaceTexture st) {} }""",
    "android.graphics.drawable.ColorDrawable": """
public class ColorDrawable extends android.graphics.drawable.Drawable {
  public ColorDrawable() {}
  public ColorDrawable(int color) {}
  public int getColor() { return 0; }
}""",
    "android.graphics.drawable.BitmapDrawable": """
public class BitmapDrawable extends android.graphics.drawable.Drawable {
  public BitmapDrawable(android.content.res.Resources r, android.graphics.Bitmap b) {}
  public BitmapDrawable(android.graphics.Bitmap b) {}
}""",
    "android.content.ContextWrapper": """
public class ContextWrapper extends android.content.Context {
  public android.content.Context getBaseContext() { return null; }
}""",
    "android.view.View": """
public class View {
  public static final int VISIBLE = 0, INVISIBLE = 4, GONE = 8;
  public static final int NO_ID = -1;
  public interface OnClickListener { void onClick(View v); }
  public interface OnLayoutChangeListener {
    void onLayoutChange(View v, int l, int t, int r, int b,
        int ol, int ot, int or, int ob);
  }
  public View(android.content.Context c) {}
  public int getVisibility() { return VISIBLE; }
  public void setVisibility(int v) {}
  public void draw(android.graphics.Canvas c) {}
  public boolean getGlobalVisibleRect(android.graphics.Rect r) { return true; }
  public void getLocationOnScreen(int[] out) {}
  public boolean isAttachedToWindow() { return true; }
  public boolean willNotDraw() { return true; }
  public void setMinimumWidth(int w) {}
  public void setMinimumHeight(int h) {}
  public void setWillNotDraw(boolean b) {}
  public float getAlpha() { return 1f; }
  public int getId() { return NO_ID; }
  public android.graphics.drawable.Drawable getBackground() { return null; }
  public void setBackground(android.graphics.drawable.Drawable d) {}
  public android.graphics.drawable.Drawable getForeground() { return null; }
  public void setForeground(android.graphics.drawable.Drawable d) {}
  public android.content.res.Resources getResources() { return null; }
  public android.view.ViewPropertyAnimator animate() { return null; }
  public boolean isAttachedToWindow2() { return true; }
  public android.view.ViewGroup.LayoutParams getLayoutParams() { return null; }
  public void setLayoutParams(android.view.ViewGroup.LayoutParams p) {}
  public int getLeft() { return 0; }
  public int getTop() { return 0; }
  public int getScrollY() { return 0; }
  public float getTranslationY() { return 0f; }
  public float getTranslationX() { return 0f; }
  public int getWidth() { return 0; }
  public int getHeight() { return 0; }
  public void layout(int l, int t, int r, int b) {}
  /** Animation-grade RenderNode translation; no measure/layout cascade. */
  public void offsetTopAndBottom(int offset) {}
  public android.content.Context getContext() { return null; }
  public android.view.ViewParent getParent() { return null; }
  public Object getTag() { return null; }
  public void setTag(Object tag) {}
  public View findViewWithTag(Object tag) { return null; }
  public android.view.ViewTreeObserver getViewTreeObserver() { return null; }
  public void invalidate() {}
  protected void onDraw(android.graphics.Canvas c) {}
  public boolean post(Runnable r) { return true; }
  public void setBackgroundColor(int c) {}
  public void setAlpha(float a) {}
  public void setRenderEffect(android.graphics.RenderEffect e) {}
  public void setOnClickListener(OnClickListener l) {}
  public void addOnLayoutChangeListener(OnLayoutChangeListener l) {}
  public void setPadding(int l, int t, int r, int b) {}
}""",
    "android.view.ViewParent": "public interface ViewParent {}",
    "android.view.ViewParent": """
public interface ViewParent {
  public void requestDisallowInterceptTouchEvent(boolean d);
}
""",
    "android.view.ViewGroup": """
public class ViewGroup extends android.view.View {
  public static class LayoutParams {
    public static final int MATCH_PARENT = -1, WRAP_CONTENT = -2;
    public int rightMargin, bottomMargin;
  public int width, height, leftMargin, topMargin;
    public LayoutParams(int w, int h) {}
    public LayoutParams(LayoutParams p) {}
    public void setMargins(int l, int t, int r, int b) {}
  }
  public ViewGroup(android.content.Context c) { super(c); }
  public int getChildCount() { return 0; }
  public int indexOfChild(android.view.View v) { return -1; }
  public void bringChildToFront(android.view.View v) {}
  public View getChildAt(int i) { return null; }
  public void addView(View v) {}
  public void addView(View v, int index) {}
  public void addView(View v, LayoutParams p) {}
  public void addView(View v, int index, LayoutParams p) {}
  public void removeView(View v) {}
  public void removeViewAt(int i) {}
  public void removeAllViews() {}
  public void setClipChildren(boolean b) {}
}""",
    "android.view.ViewTreeObserver": """
public class ViewTreeObserver {
  public interface OnPreDrawListener { boolean onPreDraw(); }
  public interface OnGlobalLayoutListener { void onGlobalLayout(); }
  public interface OnDrawListener { void onDraw(); }
  public void addOnPreDrawListener(OnPreDrawListener l) {}
  public void removeOnPreDrawListener(OnPreDrawListener l) {}
  public void addOnGlobalLayoutListener(OnGlobalLayoutListener l) {}
  public void addOnDrawListener(OnDrawListener l) {}
  public void removeOnDrawListener(OnDrawListener l) {}
  public void removeOnGlobalLayoutListener(OnGlobalLayoutListener l) {}
  public boolean isAlive() { return true; }
}""",
    "android.view.animation.Interpolator": """
public interface Interpolator { }
""",
    "android.view.animation.DecelerateInterpolator": """
public class DecelerateInterpolator implements android.view.animation.Interpolator {
  public DecelerateInterpolator() {}
  public DecelerateInterpolator(float f) {}
}
""",
    "android.view.ViewPropertyAnimator": """
public class ViewPropertyAnimator {
  public ViewPropertyAnimator alpha(float f) { return this; }
  public ViewPropertyAnimator setDuration(long d) { return this; }
  public ViewPropertyAnimator setStartDelay(long d) { return this; }
  public ViewPropertyAnimator setInterpolator(
      android.view.animation.Interpolator i) { return this; }
  public ViewPropertyAnimator withEndAction(Runnable r) { return this; }
  public void start() {}
  public void cancel() {}
}
""",
    "android.util.SparseArray": """
public class SparseArray {
  public Object get(int key) { return null; }
  public void put(int key, Object v) {}
  public int size() { return 0; }
  public int keyAt(int i) { return 0; }
}
""",
    "android.view.WindowManager": """
public class WindowManager {
  public static class LayoutParams {
    public int format;
    public int flags;
    public static final int FLAG_NOT_TOUCHABLE = 16;
    public static final int FLAG_NOT_FOCUSABLE = 8;
    public String packageName;
  }
}
""",
    "android.graphics.PixelFormat": """
public class PixelFormat {
  public static final int TRANSLUCENT = -3;
  public static final int TRANSPARENT = -1;
  public static final int OPAQUE = -1;
}
""",
    "android.view.WindowManager": """
public class WindowManager {
  public static class LayoutParams {
    public int format;
    public int flags;
    public static final int FLAG_NOT_TOUCHABLE = 16;
    public static final int FLAG_NOT_FOCUSABLE = 8;
    public String packageName;
  }
}
""",
    "android.graphics.PixelFormat": """
public class PixelFormat {
  public static final int TRANSLUCENT = -3;
  public static final int TRANSPARENT = -1;
  public static final int OPAQUE = -1;
}
""",
    "android.view.Window": """
public class Window { public android.view.View getDecorView() { return null; } }""",
    "android.view.TextureView": """
public class TextureView extends android.view.View {
  public interface SurfaceTextureListener {
    void onSurfaceTextureAvailable(android.graphics.SurfaceTexture st, int w, int h);
    void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture st, int w, int h);
    boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture st);
    void onSurfaceTextureUpdated(android.graphics.SurfaceTexture st);
  }
  public TextureView(android.content.Context c) { super(c); }
  public void setSurfaceTextureListener(SurfaceTextureListener l) {}
  public void setOpaque(boolean b) {}
  public void setTransform(android.graphics.Matrix m) {}
  public android.graphics.Matrix getTransform(android.graphics.Matrix m) {
    return m == null ? new android.graphics.Matrix() : m;
  }
  public android.graphics.Bitmap getBitmap(int w, int h) { return null; }
  public boolean isAvailable() { return false; }
}""",
    "android.widget.FrameLayout": """
public class FrameLayout extends android.view.ViewGroup {
  public static class LayoutParams extends android.view.ViewGroup.LayoutParams {
    public int gravity;
    public LayoutParams(int w, int h) { super(w, h); }
    public LayoutParams(android.view.ViewGroup.LayoutParams p) { super(p); }
  }
  public FrameLayout(android.content.Context c) { super(c); }
}""",
    "android.widget.LinearLayout": """
public class LinearLayout extends android.view.ViewGroup {
  public static final int VERTICAL = 1, HORIZONTAL = 0;
  public static class LayoutParams extends android.view.ViewGroup.LayoutParams {
    public float weight;
    public LayoutParams(int w, int h) { super(w, h); }
    public LayoutParams(int w, int h, float weight) { super(w, h); }
  }
  public LinearLayout(android.content.Context c) { super(c); }
  public void setOrientation(int o) {}
  public void setGravity(int g) {}
  public void setWeightSum(float w) {}
}""",
    "android.widget.ImageView": """

public class ImageView extends android.view.View {
  public enum ScaleType { CENTER_CROP, FIT_CENTER, CENTER_INSIDE, FIT_XY, CENTER, MATRIX }
  public ImageView(android.content.Context c) { super(c); }
  public void setScaleType(ScaleType s) {}
  public void setImageBitmap(android.graphics.Bitmap b) {}
  public void setImageResource(int r) {}
  public void setAdjustViewBounds(boolean b) {}
  public void setImageDrawable(android.graphics.drawable.Drawable d) {}
  public android.graphics.drawable.Drawable getDrawable() { return null; }
  public void setImageMatrix(android.graphics.Matrix m) {}
  public android.graphics.Matrix getImageMatrix() {
    return new android.graphics.Matrix(); }
}""",
    "android.widget.ProgressBar": """
public class ProgressBar extends android.view.View {
  public ProgressBar(android.content.Context c) { super(c); }
  public void setIndeterminate(boolean b) {}
  public boolean isIndeterminate() { return true; }
}""",
    "android.widget.TextView": """
public class TextView extends android.view.View {
  public TextView(android.content.Context c) { super(c); }
  public void setText(CharSequence s) {}
  public void setTextSize(float s) {}
  public float getTextSize() { return 14f; }        // real Android API; stub for p126 font rules
  public void setTextColor(int c) {}
  public int getCurrentTextColor() { return 0xff000000; } // real Android API; stub for p126
  public void setHint(CharSequence s) {}
  public void setHintTextColor(int c) {}
  public void setInputType(int t) {}
  public CharSequence getText() { return ""; }
  public void setGravity(int g) {}
  public void addTextChangedListener(android.text.TextWatcher w) {}
}""",
    "android.text.TextWatcher": """
public interface TextWatcher {
  void beforeTextChanged(CharSequence s, int start, int count, int after);
  void onTextChanged(CharSequence s, int start, int before, int count);
  void afterTextChanged(android.text.Editable s);
}""",
    "android.text.Editable": "public interface Editable extends CharSequence {}",
    "android.widget.EditText": """
public class EditText extends android.widget.TextView {
  public EditText(android.content.Context c) { super(c); }
}""",
    "android.widget.Button": """
public class Button extends android.widget.TextView {
  public Button(android.content.Context c) { super(c); }
  public void setAllCaps(boolean b) {}
}""",
    "android.widget.CheckBox": """
public class CheckBox extends android.widget.TextView {
  public CheckBox(android.content.Context c) { super(c); }
  public void setChecked(boolean b) {}
  public boolean isChecked() { return false; }
}""",
    "android.widget.AdapterView": """
public class AdapterView<T> extends android.view.ViewGroup {
  public interface OnItemSelectedListener {
    void onItemSelected(AdapterView<?> parent, android.view.View view,
        int position, long id);
    void onNothingSelected(AdapterView<?> parent);
  }
  public AdapterView(android.content.Context c) { super(c); }
}""",
    "android.widget.Spinner": """
public class Spinner extends android.widget.AdapterView<Object> {
  public Spinner(android.content.Context c) { super(c); }
  public void setAdapter(android.widget.ArrayAdapter<String> a) {}
  public void setSelection(int i) {}
  public int getSelectedItemPosition() { return 0; }
  public void setOnItemSelectedListener(
      android.widget.AdapterView.OnItemSelectedListener l) {}
}""",
    "android.widget.ArrayAdapter": """
public class ArrayAdapter<T> {
  public ArrayAdapter(android.content.Context c, int res, java.util.List<T> items) {}
  public ArrayAdapter(android.content.Context c, int res, T[] items) {}
  public void setDropDownViewResource(int res) {}
}""",
    "android.widget.SeekBar": """
public class SeekBar extends android.view.View {
  public interface OnSeekBarChangeListener {
    void onProgressChanged(SeekBar bar, int progress, boolean fromUser);
    void onStartTrackingTouch(SeekBar bar);
    void onStopTrackingTouch(SeekBar bar);
  }
  public SeekBar(android.content.Context c) { super(c); }
  public void setMax(int m) {}
  public int getMax() { return 100; }
  public void setProgress(int p) {}
  public int getProgress() { return 0; }
  public void setOnSeekBarChangeListener(OnSeekBarChangeListener l) {}
}""",
    "android.widget.ScrollView": """
public class ScrollView extends android.widget.FrameLayout {
  public ScrollView(android.content.Context c) { super(c); }
}""",
    "android.widget.HorizontalScrollView": """
public class HorizontalScrollView extends android.widget.FrameLayout {
  public HorizontalScrollView(android.content.Context c) { super(c); }
}""",
    "android.widget.Toast": """
public class Toast {
  public static final int LENGTH_SHORT = 0;
  public static Toast makeText(android.content.Context c, CharSequence s,
      int duration) { return null; }
  public void setGravity(int g, int x, int y) {}
  public void show() {}
}""",
    "io.github.libxposed.api.XposedInterface": """
public interface XposedInterface {
  public interface Chain {
    Object proceed();
    Object getArg(int i);
    Object getThisObject();
    java.lang.reflect.Executable getExecutable();
  }
  public interface Hooker { Object intercept(Chain chain) throws Throwable; }
}""",
    "io.github.libxposed.api.XposedModuleInterface": """
public interface XposedModuleInterface {
  public interface PackageLoadedParam {
    String getPackageName();
    ClassLoader getDefaultClassLoader();
  }
  void onPackageLoaded(PackageLoadedParam param);
}""",
    "io.github.libxposed.api.XposedModule": """
public abstract class XposedModule implements
    io.github.libxposed.api.XposedModuleInterface {
  public static final class HookBuilder {
    public Object intercept(io.github.libxposed.api.XposedInterface.Hooker h) {
      return null;
    }
  }
  public XposedModule() {}
  public android.content.SharedPreferences getRemotePreferences(String group) {
    return null;
  }
  public void log(int priority, String tag, String msg) {}
  public boolean deoptimize(java.lang.reflect.Executable e) { return false; }
  public HookBuilder hook(java.lang.reflect.Method m) { return new HookBuilder(); }
}""",
}


def generate():
    if os.path.isdir(STUBS):
        shutil.rmtree(STUBS)
    os.makedirs(STUBS, exist_ok=True)
    paths = []
    for fqcn, body in CLASSES.items():
        pkg, name = fqcn.rsplit(".", 1)
        d = os.path.join(STUBS, pkg.replace(".", os.sep))
        os.makedirs(d, exist_ok=True)
        p = os.path.join(d, name + ".java")
        with open(p, "w", encoding="utf-8") as f:
            f.write("package %s;\n%s\n" % (pkg, body.strip()))
        paths.append(p)
    return paths


def main():
    if not os.path.isdir(APP_SRC):
        print("missing app sources:", APP_SRC)
        return 2
    if os.path.isdir(OUT):
        shutil.rmtree(OUT)
    os.makedirs(OUT, exist_ok=True)
    stub_paths = generate()
    srcs = []
    for tree in (APP_SRC, PROBE_SRC):
        for base, _dirs, files in os.walk(tree):
            for fn in files:
                if fn.endswith(".java"):
                    srcs.append(os.path.join(base, fn))
    cmd = ["javac", "-nowarn", "-J-Duser.language=en", "-J-Duser.country=US",
           "-encoding", "UTF-8", "-d", OUT] + stub_paths + sorted(srcs)
    print("compiling %d sources against %d stubs" % (len(srcs), len(stub_paths)))
    r = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8",
                       errors="replace")
    out = (r.stdout or "") + (r.stderr or "")
    print(out.strip() or "(javac produced no output)")
    if r.returncode == 0:
        print("COMPILE_OK: %d class files" % sum(
            1 for _b, _d, fs in os.walk(OUT) for f in fs if f.endswith(".class")))
        # Keep the workspace clean; generated stubs are not deliverables.
        shutil.rmtree(OUT, ignore_errors=True)
        shutil.rmtree(STUBS, ignore_errors=True)
    else:
        print("COMPILE_FAILED: exit=%d (stubs kept under probe/tools/)" % r.returncode)
    return r.returncode


if __name__ == "__main__":
    sys.exit(main())
