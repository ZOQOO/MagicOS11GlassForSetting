package com.magicglass;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Standalone LSPosed module entry (libxposed API 101).
 *
 * Deliberately does NOT inherit from the upstream HyperBackground / MIUI base:
 *  - own package name com.magicglass; own preference group "magicglass";
 *  - own module UI (SettingsActivity) and own ContentProvider, no hooking of
 *    any upstream MainActivity or reflection into its obfuscated "pr" helper;
 *  - zero MIUI setMi* material APIs. The primary renderer is the HOST's own
 *    com.hihonor.uikit.transparency.widget.HnTransFrameLayout, verified to
 *    exist in all nine scoped APKs; framework backdrop is the secondary path
 *    and the two GitHub AGSL projects are only a last-resort fallback.
 *
 * Hook chain follows the verified libxposed pattern: Instrumentation
 * .callActivityOnCreate / .callActivityOnResume. One {@link RenderController}
 * is attached per Activity; it owns the decor overlay, the pre-draw sync, the
 * container pool and the per-level background layer.
 */
public final class GlassEntry extends XposedModule {

    private static final String TAG = "MagicGlass";
    private static final int PRIORITY = 4;

    /**
     * Build fingerprint. LSPosed 2.2.1 keeps a cached copy of a module's dex:
     * a same-signature `adb install -r` + zygote soft restart updates base.apk
     * but lspd may keep executing the OLD bytecode. Every process load and
     * every frame diagnostics line carries this tag, so the log itself proves
     * which build is actually running. MUST be bumped with module.prop.
     */
    public static final String BUILD = "0.5.0-p201";

    private static volatile XposedModule sModule;
    private static volatile MaterialBridge.Availability sAvailability;
    /**
     * 0.5.0-p81: how many starting-window PAINT hooks were really installed.
     * The skip hook only lets the splash exist when BOTH a paint hook and the
     * preview picture are available; otherwise it keeps the p66/p79 skip so
     * no white SystemUI starting window is ever shown (§18.6).
     */
    private static volatile int sPaintHooks;
    /** Kept only to allow a bounded retry of the read-only provider prefetch. */
    private static volatile Context sSystemUiContext;
    /**
     * 0.5.0-p82 / report §3.1 + §4 A (PREFERRED guard point):
     * `HnSplashscreenContentDrawer.createContentView(StartingWindowInfo, ...)`
     * is public and its arg0 IS the StartingWindowInfo, so the package name
     * is resolved here with ZERO guessing. The worker-handler Runnable then
     * calls the two content factories; a short-lived guard package lets the
     * after-hook paint the View even when its own parameter scan fails.
     */
    private static volatile String sGuardPackage;
    private static volatile long sGuardAtMs;
    private static final long GUARD_TTL_MS = 4000L;
    /** 0.5.0-p82 / report §4 A: one config receiver per long-lived SystemUI. */
    private static volatile boolean sConfigReceiverRegistered;
    /** 0.5.0-p82 / report §4 A: "preferred hook point" install count. */
    private static volatile int sGuardHooks;

    /** One-shot diagnostic: proves the interceptor actually ran in this process. */
    private static volatile boolean sFirstFire = true;

    /**
     * 0.5.0-p124 / 自定义 ID 命中诊断：最近一个 Activity 的全类名。
     * ActivityHook 在每次生命周期回调写入；PreferenceBindHooker 用它反查
     * ElementRegistry.screenForActivity() 得到当前 screen。纯诊断，不影响渲染。
     */
    private static volatile String sLastActivityClass;

    /** p124: Preference.onBindViewHolder 诊断 hook 只装一次（Activity classLoader）。 */
    private static volatile boolean sPrefHookInstalled;
    /** p124: 行预算——换页重置 + 每页上限 + 相同 key 去重 + 150ms 节流。 */
    private static final Object sPrefLock = new Object();
    private static final Set<String> sPrefSeenKeys = new HashSet<String>();
    private static volatile String sPrefPageId;
    private static volatile int sPrefPageCount;
    private static volatile long sPrefLastLogMs;
    private static final long PREF_THROTTLE_MS = 150L;
    private static final int PREF_MAX_LINES = 24;
    // 0.5.0-p186: 旧的"抓取当前页行列表"缓存与广播已随图标替换重做一并移除。

    // ---- 0.5.0-p126: 按 global_id 命中后应用字体（el.rules） ----
    /** WeakHashMap: 以 TextView 为弱键，View 回收后自动清理，天然有界。 */
    private static final Map<android.widget.TextView, SavedFont> sElSavedFonts =
            new WeakHashMap<android.widget.TextView, SavedFont>();
    /** 反射缓存 setTextSize(int,float)，用 COMPLEX_UNIT_PX 精确按像素复原。 */
    private static volatile java.lang.reflect.Method sElSetTextSizePx;
    /** ELFONT 日志独立 8 行预算 + 150ms 节流，默认 mg-state 可见。 */
    private static final Object sElFontLock = new Object();
    private static volatile String sElFontPageId;
    private static volatile int sElFontPageCount;
    private static volatile long sElFontLastLogMs;
    private static final long ELFONT_THROTTLE_MS = 150L;
    private static final int ELFONT_MAX_LINES = 8;
    /** android.R.id.title / android.R.id.summary 的真实常量值（避免 R 依赖）。 */
    private static final int ANDROID_ID_TITLE = 0x1020016;
    private static final int ANDROID_ID_SUMMARY = 0x1020010;

    /** 每个 TextView 的字体原值；每次套用新规则前先还原，避免缩放叠加。 */
    private static final class SavedFont {
        final int color;
        final float sizePx;
        SavedFont(int color, float sizePx) {
            this.color = color;
            this.sizePx = sizePx;
        }
    }

    /**
     * 0.5.0-p89: startup stage clock.
     *
     * <p>The p88 report could only attribute time BETWEEN our own log lines,
     * which left the 192 ms "attach → first frame" block completely dark. This
     * clock makes each lifecycle callback print its own offset from module
     * load, so a single cold start says whether the cost is inflate (onCreate),
     * layout (onContentChanged/onAttachedToWindow) or our own first draw.
     */
    private static volatile long sBootMs;
    /** Bounded: eight stage lines per process, then silent. */
    private static volatile int sStageLines;

    /** Milliseconds since the module was loaded in this process, or 0. */
    public static long stageAt() {
        long boot = sBootMs;
        return boot == 0L ? 0L : SystemClock.uptimeMillis() - boot;
    }

    /**
     * One line per lifecycle callback, bounded to eight per process.
     * Called only from startup hooks, never from the per-frame path.
     */
    private static void stage(String what, long costMs) {
        int n = sStageLines;
        if (n >= 8) return;
        sStageLines = n + 1;
        say("mg-state stage " + what + " +" + stageAt() + "ms cost=" + costMs);
    }

    /** Pruned on every Activity event, so finished pages never accumulate. */
    private static final Map<Activity, RenderController> CONTROLLERS =
            new WeakHashMap<Activity, RenderController>();

    public GlassEntry() {
        super();
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        sBootMs = SystemClock.uptimeMillis();
        sModule = this;
        // 0.5.0-p104: hand the (untyped) module instance to the log writer that
        // is also safe in the module APK's own, UNHOOKED process.
        MgLog.bind(this);
        String pkg = param.getPackageName();
        say("mg-build " + BUILD + " loaded in " + pkg + " at=+0ms");
        if (Scope.isRenderTarget(pkg)) {
            try {
                ConfigBus.setRemote(getRemotePreferences(ConfigContract.GROUP));
            } catch (Throwable t) {
                say("remote preferences unavailable (provider fallback active): " + t);
            }
            installRenderHooks();
            say("render hooks installed for " + pkg);
            // 0.5.0-p76: start/bind our ContentProvider NOW, on a daemon
            // thread, long before the first Activity's attach() needs the
            // config. This hides the first-IPC cold start that made the
            // wallpaper appear 1-2 s late on first entry. Reflection avoids
            // a compile-time dependency on hidden ActivityThread.
            try {
                Class<?> at = Class.forName("android.app.ActivityThread");
                Object app = at.getMethod("currentApplication").invoke(null);
                if (app instanceof Context) {
                    ConfigBus.warmUpAsync((Context) app);
                }
            } catch (Throwable t) {
                say("config warmup schedule failed: " + t);
            }
        } else if (Scope.isModule(pkg)) {
            // Nothing to hook: the module owns a normal Activity + Provider.
            say("module process loaded");
        } else if (Scope.isSystemUi(pkg)) {
            installStartingWindowHooks(param.getDefaultClassLoader());
            say("systemui starting-window hooks installed (runtime-only)");
        }
    }

    // ---- settings / external app processes ----------------------------------

    /**
     * Field test on LSPosed 2.2.1 / MagicOS (Android 17) showed that hooking
     * Instrumentation.callActivityOnCreate / callActivityOnResume registers
     * cleanly in all nine scoped processes, but the interceptor NEVER fires:
     * the ROM's HwInstrumentation overrides those callbacks without delegating
     * to the base implementation. Hook the Activity lifecycle itself instead --
     * every Activity must call super.onCreate()/super.onResume(), so these are
     * reachable no matter how the ROM wraps Instrumentation.
     *
     * The Instrumentation hooks are kept as a complementary path.
     */
    private void installRenderHooks() {
        hookLifecycle("onResume");
        hookLifecycle("onCreate", Bundle.class);
        // 0.4.0-p59: some ROM activities never reach the two hooks above.
        // 荣耀互联 (com.hihonor.controlcenter/SuperDeviceSettingsActivity) was
        // NEVER attached -- no "attached to ...SuperDeviceSettingsActivity" line
        // in any log, although the process was hooked (MainSettingsActivity in
        // the same process attaches fine). That page therefore kept its 10 OEM
        // white cards. onAttachedToWindow is called by the Window for EVERY
        // window that becomes visible, and onContentChanged right after
        // setContentView; both are far less likely to be inlined or bypassed
        // by an OEM Instrumentation subclass.
        hookLifecycle("onAttachedToWindow");
        hookLifecycle("onContentChanged");
        installWindowHook();
        hookInstrumentation("callActivityOnCreate", Activity.class, Bundle.class);
        hookInstrumentation("callActivityOnResume", Activity.class);
        // 0.5.0-p88: the warm-up in onPackageLoaded has NEVER run in this
        // process. It asks ActivityThread.currentApplication(), which is null
        // while LSPosed is still loading modules (§76 measured exactly the same
        // failure in SystemUI, where it silently disabled the whole prefetch).
        // So every attach() paid a COLD provider query on the main thread,
        // right where the first frame is being prepared. Capture the
        // Application when it really exists and warm up there instead.
        hookConfigWarm();
    }

    /**
     * 0.5.0-p88: BEFORE-hook on {@code Instrumentation.callApplicationOnCreate}
     * (arg0 IS the Application). Runtime-only; a reflective failure simply
     * leaves the old lazy path in place, so nothing can regress.
     */
    private boolean hookConfigWarm() {
        try {
            Class<?> c = Class.forName("android.app.Instrumentation");
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals("callApplicationOnCreate")) continue;
                if (m.getParameterTypes().length != 1) continue;
                try {
                    hook(m).intercept(new ConfigWarmHooker());
                    try {
                        deoptimize(m);
                    } catch (Throwable ignored) {
                    }
                    say("hooked Instrumentation.callApplicationOnCreate (config warm)");
                    return true;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            say("config warm hook unavailable: " + t);
        }
        return false;
    }

    /**
     * 0.5.0-p88: warm the read-only config provider on a daemon thread before
     * any Activity attaches, so the first attach() is a cache hit.
     */
    public static final class ConfigWarmHooker implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            try {
                Object app = chain.getArg(0);
                if (app instanceof Context) {
                    Context ctx = (Context) app;
                    ConfigBus.warmUpAsync(ctx);
                    ConfigBus.warmWindowColor(ctx);
                    say("mg-state config warm ctx=app");
                }
            } catch (Throwable ignored) {
            }
            return chain.proceed();
        }
    }

    private void hookLifecycle(String name, Class<?>... args) {
        try {
            Method m = Activity.class.getDeclaredMethod(name, args);
            hook(m).intercept(new ActivityHook());
            // Defeat ART inlining of Activity's base implementation into its
            // thousands of subclasses; harmless when the framework refuses.
            try {
                deoptimize(m);
            } catch (Throwable ignored) {
            }
            say("hooked Activity." + name);
        } catch (Throwable t) {
            say("hook Activity." + name + " failed: " + t);
        }
    }

    /**
     * 0.4.0-p60 WINDOW-LEVEL ATTACH.
     *
     * <p>荣耀互联 (com.hihonor.controlcenter/SuperDeviceSettingsActivity) never
     * reaches ANY of the four Activity hooks -- no "DIAG lifecycle" line for
     * that class in any log, while another Activity in the same process
     * attaches fine. Its onCreate/onResume/onAttachedToWindow are almost
     * certainly AOT-inlined past our hooks, so the page got NO glass and kept
     * its 10 stock white cards.
     *
     * <p>ViewRootImpl.setView() is called by the framework (not by the app), so
     * it cannot be inlined away. It runs once per window, right after the
     * decor is created, which is exactly when we need to attach.
     *
     * <p>Guard: only windows whose decor IS the Activity's own decor (dialogs
     * and other sub-windows are skipped), so we never paint glass into a
     * dialog.
     */
    private void installWindowHook() {
        try {
            Class<?> c = Class.forName("android.view.ViewRootImpl", false,
                    Activity.class.getClassLoader());
            Method m = findMethod(c, "setView", 3);
            if (m == null) {
                say("hook ViewRootImpl.setView unavailable");
                return;
            }
            hook(m).intercept(new WindowSetViewHooker());
            say("hooked ViewRootImpl.setView");
        } catch (Throwable t) {
            say("hook ViewRootImpl.setView failed: " + t);
        }
    }

    /** Public + static: the framework resolves the callback from this class. */
    public static final class WindowSetViewHooker implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            Object result = chain.proceed();
            try {
                Object arg0 = chain.getArg(0);
                if (arg0 instanceof View) {
                    Activity a = activityOf((View) arg0);
                    if (a != null) onActivityReady(a);
                }
            } catch (Throwable ignored) {
            }
            return result;
        }
    }

    /**
     * Resolve the Activity that owns `v`, but ONLY when `v` is that Activity's
     * own decor view -- a dialog's decor has its own window, so it is skipped.
     */
    private static Activity activityOf(View v) {
        try {
            Context c = v.getContext();
            int guard = 0;
            while (c instanceof ContextWrapper && !(c instanceof Activity) && guard++ < 6) {
                c = ((ContextWrapper) c).getBaseContext();
            }
            if (!(c instanceof Activity)) return null;
            Activity a = (Activity) c;
            if (a.getWindow() == null || a.getWindow().getDecorView() != v) return null;
            return a;
        } catch (Throwable t) {
            return null;
        }
    }

    private void hookInstrumentation(String name, Class<?>... args) {
        try {
            Method m = Instrumentation.class.getDeclaredMethod(name, args);
            hook(m).intercept(new ActivityHook());
            say("hooked Instrumentation." + name);
        } catch (Throwable t) {
            say("hook Instrumentation." + name + " failed: " + t);
        }
    }

    /** Public + static: the framework resolves the callback from the Hooker class. */
    public static final class ActivityHook implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            if (sFirstFire) {
                synchronized (GlassEntry.class) {
                    if (sFirstFire) {
                        sFirstFire = false;
                        say("hook fired: " + String.valueOf(chain.getExecutable()));
                    }
                }
            }
            Object result = chain.proceed();
            try {
                Activity a = activityFrom(chain);
                if (a != null) {
                    // 0.5.0-p124: 记录最近页面，供 PREFBIND 反查 screen（纯诊断）。
                    sLastActivityClass = a.getClass().getName();
                    long t0 = SystemClock.uptimeMillis();
                    onActivityReady(a);
                    // 0.5.0-p89: `cost=` is what OUR hook body took, `+Nms` is
                    // where it sits on the startup timeline.
                    stage(String.valueOf(chain.getExecutable().getName())
                                    + "@" + a.getClass().getSimpleName(),
                            SystemClock.uptimeMillis() - t0);
                }
            } catch (Throwable t) {
                say("apply failed: " + t);
            }
            return result;
        }
    }

    /** Activity lifecycle hooks carry the Activity as `this`; Instrumentation
     *  hooks carry it as argument 0. Support both. */
    private static Activity activityFrom(XposedInterface.Chain chain) {
        Object thiz = chain.getThisObject();
        if (thiz instanceof Activity) return (Activity) thiz;
        try {
            Object arg0 = chain.getArg(0);
            if (arg0 instanceof Activity) return (Activity) arg0;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void onActivityReady(Activity a) {
        String pkg = a.getPackageName();
        if (!Scope.isRenderTarget(pkg)) return;

        // 0.5.0-p124: 自定义 ID 命中诊断 hook。只在 Preference 宿主页
        // (com.android.settings) 装一次；onPackageLoaded 阶段看不到 Settings
        // APK 里的 app-private androidx.preference 类，而 Activity 的
        // classLoader 可以。installPreferenceHook 内部全 try-catch + 防重。
        if (!sPrefHookInstalled && "com.android.settings".equals(pkg)) {
            installPreferenceHook(a.getClass().getClassLoader());
        }
        // 0.5.0-p186: L1 图标替换 hook——挂 TextView.setText，设置设置页行的
        // 标题文字后，再按行标题替换同行 android.R.id.icon 的图标。
        // 反编译证实 L1 由 DashboardSummary 的 dashboard_tile 渲染，走
        // g.U→g.S，不经过 Preference.onBindViewHolder，故必须挂 setText。
        try {
            IconSwap.install(a.getClass().getClassLoader(), sModule);
        } catch (Throwable ignored) {
        }

        // Probe against the HOST class loader. The boot loader alone cannot see
        // app-private uikit classes, so this runs on the first real Activity.
        if (sAvailability == null) {
            sAvailability = MaterialBridge.probe(a.getClass().getClassLoader());
            say("SDK " + sAvailability.describe());
            say("StyleType constants: " + MaterialBridge.describeStyleTypes());
            say("SceneType constants: " + MaterialBridge.describeSceneTypes());
        }

        RenderController c = controllerFor(a);
        c.onLifecycle();
    }

    // ---- 0.5.0-p124: 自定义 ID 命中诊断（hook 半边，纯日志、零像素改动） ----

    /**
     * Hook {@code androidx.preference.Preference.onBindViewHolder(holder)}：
     * {@code this} 就是 Preference（getKey() 直接拿 key），arg0 是 holder
     * （反射取 itemView 得到这一行的 View）。再结合当前 Activity 反查的 screen，
     * 调用 ElementRegistry.globalIdFor/dimsFor 把 key → global_id 的命中结果
     * 打成一行 mg-state PREFBIND。若 gid 非空即证明自定义 ID 能命中真实屏幕块；
     * 若全空说明 key 对不上，需换桥。
     *
     * <p>在 {@link #onActivityReady(Activity)} 里用 Activity 的 classLoader 注册：
     * onPackageLoaded 阶段看不到 Settings APK 里的 app-private 类。反射 + try-catch
     * 全程包裹，失败只留一条 say 日志，绝不崩溃、绝不改渲染行为。</p>
     */
    private static void installPreferenceHook(ClassLoader loader) {
        if (sPrefHookInstalled) return;
        try {
            XposedModule module = sModule;
            if (module == null) {
                say("PREFBIND hook unavailable: module not bound");
                return;
            }
            if (loader == null) {
                say("PREFBIND hook unavailable: null classloader");
                return;
            }
            Class<?> pref = Class.forName("androidx.preference.Preference", false, loader);
            Method target = null;
            for (Method m : pref.getDeclaredMethods()) {
                if ("onBindViewHolder".equals(m.getName())
                        && m.getParameterTypes().length == 1) {
                    target = m;
                    break;
                }
            }
            if (target == null) {
                say("PREFBIND hook unavailable: no onBindViewHolder(holder) on "
                        + pref.getName());
                return;
            }
            module.hook(target).intercept(new PreferenceBindHooker());
            sPrefHookInstalled = true;
            say("PREFBIND hook installed: " + pref.getName() + "." + target.getName());
        } catch (Throwable t) {
            say("PREFBIND hook unavailable: " + t);
        }
    }

    /** Public + static: the framework resolves the callback from the Hooker class. */
    public static final class PreferenceBindHooker implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            Object result = chain.proceed();
            try {
                Object pref = chain.getThisObject();
                Object holder = chain.getArg(0);
                if (pref == null || holder == null) return result;

                // Preference.getKey() —— public API，反射调用避免编译期依赖。
                String key = null;
                try {
                    Object k = pref.getClass().getMethod("getKey").invoke(pref);
                    if (k instanceof String) key = (String) k;
                } catch (Throwable ignored) {
                }

                // PreferenceViewHolder.itemView（继承自 RecyclerView.ViewHolder）。
                Object itemView = field(holder, "itemView");
                if (!(itemView instanceof View)) return result;
                View v = (View) itemView;

                String act = sLastActivityClass;
                String screen = ElementRegistry.screenForActivity(act);
                String gid = "";
                String dims = "";
                int amb = 0;
                if (key != null && key.length() > 0) {
                    // 0.5.0-p125: screen is NOT required. Device logs (§113)
                    // showed HWSettings hosts the WLAN Fragment, so
                    // Activity->screen reverse lookup returns null. Preference
                    // keys are globally resolvable; keep the strict screen+key
                    // path when screen IS known, otherwise fall back to key-only.
                    if (screen != null) {
                        try {
                            String g = ElementRegistry.globalIdFor(screen, key);
                            if (g != null && g.length() > 0) gid = g;
                        } catch (Throwable ignored) {
                        }
                    }
                    if (gid.length() == 0) {
                        try {
                            String g = ElementRegistry.globalIdByKey(key);
                            if (g != null) gid = g;
                            dims = ElementRegistry.dimsByKey(key);
                            amb = ElementRegistry.keyAmbiguity(key);
                        } catch (Throwable ignored) {
                        }
                    } else {
                        try {
                            String d = ElementRegistry.dimsFor(screen, key);
                            if (d != null) dims = d;
                        } catch (Throwable ignored) {
                        }
                    }
                }
                // 0.5.0-p126: 命中能力产生实际效果——gid 非空且 dims 含 font 时
                // 按 el.rules 改字体。keep 硬门（②顶栏毛玻璃渐隐层 / ⑧行内图标
                // 箭头开关）的 dims 是 "keep"，不含 "font"，故天然被排除；这里
                // 不按 ①~⑩ 视觉编号猜测（gid 末段 .CAT.01/.PREF.03 是类型缩写
                // +序号，无法反推视觉编号），只信已命中的 dims。
                if (gid.length() > 0 && dims != null && dims.contains("font")) {
                    applyElFont(v, gid, readElRules(v));
                }
                // 0.5.0-p186: 图标替换已整体移出 PREFBIND。反编译证实 L1 主屏
                // 由 DashboardSummary + dashboard_tile 渲染,不走
                // Preference.onBindViewHolder,旧的 applyIconSwap 在此永不触发。
                // 新实现见 IconSwap.applyOnDrawable（hook ImageView.setImageDrawable）。
                logPrefBind(act, screen, key, gid, dims, amb, v);
            } catch (Throwable ignored) {
            }
            return result;
        }
    }

    /**
     * PREFBIND 有界输出：换页重置、每页最多 24 行、相同 key 去重、150ms 节流。
     * 任一字段取不到都打 "-" / 空，绝不因日志本身抛异常或刷屏。
     */
    private static void logPrefBind(String act, String screen, String key,
                                    String gid, String dims, int amb, View v) {
        long now = SystemClock.uptimeMillis();
        String page = act == null ? "?" : act;
        synchronized (sPrefLock) {
            if (!page.equals(sPrefPageId)) {
                sPrefPageId = page;
                sPrefPageCount = 0;
                sPrefSeenKeys.clear();
            }
            if (now - sPrefLastLogMs < PREF_THROTTLE_MS) return;
            if (sPrefPageCount >= PREF_MAX_LINES) return;
            if (key != null && key.length() > 0 && !sPrefSeenKeys.add(key)) return;
            sPrefLastLogMs = now;
            sPrefPageCount++;
        }

        String viewCls = "-";
        String idName = "-";
        String bounds = "-";
        try {
            viewCls = v.getClass().getSimpleName();
        } catch (Throwable ignored) {
        }
        try {
            int id = v.getId();
            if (id != View.NO_ID && v.getResources() != null) {
                idName = v.getResources().getResourceEntryName(id);
            }
        } catch (Throwable ignored) {
            idName = "?";
        }
        try {
            int[] loc = new int[2];
            v.getLocationOnScreen(loc);
            bounds = "[" + loc[0] + "," + loc[1] + ","
                    + (loc[0] + v.getWidth()) + ","
                    + (loc[1] + v.getHeight()) + "]";
        } catch (Throwable ignored) {
        }

        say("mg-state PREFBIND screen=" + (screen == null ? "-" : screen)
                + " key=" + (key == null ? "-" : key)
                + " gid=" + (gid == null ? "" : gid)
                + " dims=" + (dims == null ? "" : dims)
                + " amb=" + amb
                + " view=" + viewCls
                + " id=" + idName
                + " bounds=" + bounds);
    }

    // ---- 0.5.0-p126: 按 global_id 应用字体（只在 onBind 回调，非每帧） ----

    /**
     * 从 itemView 自身的 Context（属于当前绑定的宿主进程）读全局 el.rules。
     * 拿不到 Context / 读取失败一律返回 ""，调用方据此走“无规则”分支。
     */
    private static String readElRules(View v) {
        try {
            Context c = v.getContext();
            if (c == null) return "";
            ConfigContract.Source src = ConfigBus.load(c);
            if (src == null) return "";
            String rules = src.get(ConfigContract.key(
                    ConfigContract.Slot.GLOBAL, ConfigContract.Param.EL_RULES));
            return rules == null ? "" : rules;
        } catch (Throwable ignored) {
            return "";
        }
    }

    // ---- 0.5.0-p186: 图标替换已迁至 IconSwap（hook TextView.setText） ----
    // 旧实现（applyIconSwap / reapplyIconsOnScreen / findIconView / PREFBIND
    // 按键或标题匹配）在 p186 整体删除：反编译证实 L1 主屏由 DashboardSummary
    // 的 dashboard_tile 渲染，不经过 Preference.onBindViewHolder，旧 hook 在
    // L1 永不触发。新的正确落点是 Text 设置后的行内图标替换，见 IconSwap。

    /** L1 硬门：主设置页（com.android.settings.HWSettings / Settings）。 */
    static boolean isL1Now() {
        return isL1Activity(sLastActivityClass);
    }

    /** L1 硬门：主设置页（com.android.settings.HWSettings / Settings）不碰。 */
    private static boolean isL1Activity(String act) {
        if (act == null) return false;
        return "com.android.settings.HWSettings".equals(act)
                || "com.android.settings.Settings".equals(act);
    }

    /**
     * 有界遍历 itemView 找 title/summary 两个 TextView：深度 ≤4、最多 16 个
     * 节点、只认 android.R.id.title(0x1020016)/summary(0x1020010)。绝不进
     * frame path——这是 Preference.onBindViewHolder 回调，非每帧渲染。
     */
    private static void collectFontTargets(View v, int depth,
                                           android.widget.TextView[] out,
                                           int[] visited) {
        if (v == null || depth > 4 || visited[0] >= 16) return;
        if (out[0] != null && out[1] != null) return;
        visited[0]++;
        try {
            if (v instanceof android.widget.TextView) {
                int id = v.getId();
                if (id == ANDROID_ID_TITLE && out[0] == null) {
                    out[0] = (android.widget.TextView) v;
                    return; // TextView 不再向下走
                }
                if (id == ANDROID_ID_SUMMARY && out[1] == null) {
                    out[1] = (android.widget.TextView) v;
                    return;
                }
            }
            if (v instanceof android.view.ViewGroup) {
                android.view.ViewGroup g = (android.view.ViewGroup) v;
                int n = g.getChildCount();
                for (int i = 0; i < n && visited[0] < 16; i++) {
                    collectFontTargets(g.getChildAt(i), depth + 1, out, visited);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * setTextSize(int unit, float size) 用 COMPLEX_UNIT_PX(=0) 精确按像素设置；
     * getTextSize() 返回的也是像素，单参 setTextSize(float) 却是 SP，直接用会
     * 把像素当 SP 导致字号放大数倍。反射调用避免依赖 android.util.TypedValue
     * 与 compile_check 的 stub 重载；任何失败都静默不改字号。
     */
    private static void setElTextSizePx(android.widget.TextView tv, float px) {
        try {
            java.lang.reflect.Method m = sElSetTextSizePx;
            if (m == null) {
                m = android.widget.TextView.class
                        .getMethod("setTextSize", int.class, float.class);
                sElSetTextSizePx = m;
            }
            m.invoke(tv, Integer.valueOf(0), Float.valueOf(px));
        } catch (Throwable ignored) {
        }
    }

    /** 还原并移除保存的原值；规则清空/删除或 L1 时走这里。 */
    private static void restoreFonts(android.widget.TextView[] targets) {
        for (int i = 0; i < targets.length; i++) {
            android.widget.TextView tv = targets[i];
            if (tv == null) continue;
            try {
                synchronized (sElSavedFonts) {
                    SavedFont sf = sElSavedFonts.remove(tv);
                    if (sf == null) continue;
                    tv.setTextColor(sf.color);
                    setElTextSizePx(tv, sf.sizePx);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 按 gid 从 rules 取 fontColor / fontScale 并应用到 itemView 的
     * title/summary。两者都空 -> 还原原值后零开销返回；配置清空/规则删除即
     * 靠此分支恢复。全 try-catch，绝不影响原 PREFBIND 日志与 hook 行为。
     */
    private static void applyElFont(View itemView, String gid, String rules) {
        if (itemView == null || gid == null || gid.length() == 0) return;
        try {
            // L1 主设置页跳过（同时还原，防止复用 View 残留样式）。
            if (isL1Activity(sLastActivityClass)) {
                android.widget.TextView[] t = new android.widget.TextView[2];
                collectFontTargets(itemView, 0, t, new int[1]);
                restoreFonts(t);
                return;
            }

            String colorRaw = ConfigContract.elRuleGet(rules, gid, "fontColor");
            String scaleRaw = ConfigContract.elRuleGet(rules, gid, "fontScale");
            boolean hasColor = false, hasScale = false;
            int argb = 0;
            float factor = 1f;
            if (colorRaw != null && colorRaw.length() > 0) {
                try {
                    argb = android.graphics.Color.parseColor(colorRaw);
                    hasColor = true;
                } catch (Throwable ignored) {
                }
            }
            if (scaleRaw != null && scaleRaw.length() > 0) {
                try {
                    int pct = Integer.parseInt(scaleRaw.trim());
                    if (pct >= 60 && pct <= 160) {
                        factor = pct / 100f;
                        hasScale = true;
                    }
                } catch (Throwable ignored) {
                }
            }

            android.widget.TextView[] targets = new android.widget.TextView[2];
            int[] visited = new int[1];
            collectFontTargets(itemView, 0, targets, visited);

            // 无规则（含配置清空/规则删除）-> 还原，之后不再做事。
            if (!hasColor && !hasScale) {
                restoreFonts(targets);
                return;
            }

            int applied = 0;
            for (int i = 0; i < targets.length; i++) {
                android.widget.TextView tv = targets[i];
                if (tv == null) continue;
                try {
                    SavedFont sf;
                    synchronized (sElSavedFonts) {
                        sf = sElSavedFonts.get(tv);
                        if (sf == null) {
                            sf = new SavedFont(tv.getCurrentTextColor(),
                                    tv.getTextSize());
                            sElSavedFonts.put(tv, sf);
                        }
                    }
                    // 先还原基线，反复绑定/改规则都不会叠加缩放。
                    tv.setTextColor(sf.color);
                    setElTextSizePx(tv, sf.sizePx);
                    if (hasColor) tv.setTextColor(argb);
                    if (hasScale) setElTextSizePx(tv, sf.sizePx * factor);
                    applied++;
                } catch (Throwable ignored) {
                }
            }
            logElFont(gid, applied, colorRaw, scaleRaw);
        } catch (Throwable ignored) {
        }
    }

    /** ELFONT 有界日志：换页重置、每页 8 行、150ms 节流，默认可见。 */
    private static void logElFont(String gid, int targets,
                                  String color, String scale) {
        long now = SystemClock.uptimeMillis();
        String page = sLastActivityClass == null ? "?" : sLastActivityClass;
        synchronized (sElFontLock) {
            if (!page.equals(sElFontPageId)) {
                sElFontPageId = page;
                sElFontPageCount = 0;
            }
            if (sElFontPageCount >= ELFONT_MAX_LINES) return;
            if (now - sElFontLastLogMs < ELFONT_THROTTLE_MS) return;
            sElFontLastLogMs = now;
            sElFontPageCount++;
        }
        say("mg-state ELFONT gid=" + gid
                + " targets=" + targets
                + " color=" + (color == null || color.length() == 0 ? "-" : color)
                + " scale=" + (scale == null || scale.length() == 0 ? "-" : scale));
    }

    private static synchronized RenderController controllerFor(Activity a) {
        Iterator<Map.Entry<Activity, RenderController>> it =
                CONTROLLERS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Activity, RenderController> e = it.next();
            Activity key = e.getKey();
            boolean dead = key == null || key.isFinishing() || key.isDestroyed()
                    || !e.getValue().isAttached();
            if (dead) {
                try {
                    e.getValue().detach();
                } catch (Throwable ignored) {
                }
                it.remove();
            }
        }
        RenderController c = CONTROLLERS.get(a);
        if (c == null) {
            c = new RenderController(a);
            CONTROLLERS.put(a, c);
        }
        return c;
    }

    // ---- systemui process ---------------------------------------------------

    /**
     * P0 -- WHITE TRANSITION FIRST FRAME (enabled 2026-11-12 with user OK).
     *
     * Reproduced on device: repro_L2_frame0.png is an ENTIRELY WHITE screen
     * (only the status bar) on the first frame of an L1 -> L2 transition. That
     * is the ROM's splash / starting window, not our renderer. findings 14.6,
     * 17.8. Offline DEX audit (scope_audit.py) located the implementation in
     * com.android.systemui.apk, which is already one of our 9 scopes, so no
     * system_server / "android" scope is required.
     *
     * SAFETY CONTRACT (user requirement: do not touch original system files):
     *  - Pure in-memory hooking through libxposed. NO /system write, NO APK
     *    modification, NO file write anywhere on the device.
     *  - All ROM classes are resolved reflectively at runtime from the host
     *    ClassLoader; a missing/changed class simply disables that hook.
     *  - MANDATORY package guard: only com.android.settings + the six external
     *    apps are affected. If the package cannot be resolved we call proceed()
     *    and leave the ROM behaviour untouched.
     *  - Every reflective step is wrapped; a failure can never break systemui.
     *
     * Primary: skip StartingWindowController.addStartingWindow for scoped
     * packages (removes the white splash entirely). Fallback: neutralise
     * HnSplashscreenWindowCreator.estimateTaskBackgroundColor when the primary
     * class is absent. ShellTaskOrganizer is deliberately NOT hooked.
     */
    private void installStartingWindowHooks(ClassLoader loader) {
        if (loader == null) {
            say("starting-window: no host class loader, disabled");
            return;
        }
        try {
            ConfigBus.setRemote(getRemotePreferences(ConfigContract.GROUP));
        } catch (Throwable t) {
            say("starting-window: remote prefs unavailable: " + t);
        }
        // 0.5.0-p79: remote preferences are a SystemUI-start-time snapshot.
        // Prime the fallback colour through the module provider instead, so
        // even when the skip hook misses, the splash is the wallpaper's
        // average colour rather than the ROM's near-white windowBackground.
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app instanceof Context) {
                sSystemUiContext = (Context) app;
                ConfigBus.warmWindowColor((Context) app);
                // 0.5.0-p82 / report §4 A: "SystemUI is long-lived, read
                // ONCE; refresh on config change". Without this receiver a
                // user picking a NEW background keeps seeing the OLD picture
                // in every later starting window for the whole boot cycle.
                registerConfigRefresh((Context) app);
            }
        } catch (Throwable ignored) {
        }
        // 0.5.0-p82 / report §3.1 + §4 A: the PREFERRED guard point. This is
        // a pure BEFORE hook that only resolves the package name and records
        // it; it NEVER changes arguments, never replaces the result, and
        // always proceeds. Runtime-only, reflective failure => proceed().
        sGuardHooks = hookContentViewGuard(loader);
        say("mg-state startingWindow guardHooks=" + sGuardHooks);
        // 0.5.0-p86 ROOT CAUSE of "plan A never worked": at onPackageLoaded
        // SystemUI has NO Application yet, so ActivityThread.currentApplication()
        // returns null, warmWindowColor() returned without doing anything, and
        // the prefetch daemon NEVER ran in SystemUI (measured: 0 prefetch lines
        // from SystemUI vs 64 from settings across all logs). startingPreview()
        // was therefore always null, so the skip hook could only ever skip.
        // Capture the Context when Instrumentation creates the Application,
        // then warm the picture there.
        boolean appHook = hookAppCreate(loader);
        say("mg-state startingWindow appHook=" + appHook
                + " ctx=" + (sSystemUiContext != null));
        // 0.5.0-p81 plan A: paint the SystemUI starting window with the REAL
        // background picture instead of merely skipping / colour-aligning it.
        // Runtime-only, package-name guarded, reflective failure => proceed().
        sPaintHooks = hookStartingWindowPaint(loader);
        say("mg-state startingWindow paintHooks=" + sPaintHooks);
        // 0.5.0-p94 (plan A): cross-fade the splash out instead of hard-cutting
        // to the first frame. Without this the picture splash reads as "a boot
        // image" (p86 feedback) even though the pixels are the right ones.
        // 0.5.0-p98: the removal hook is deliberately NOT installed. p94/p95
        // deferred `proceed()` for 200 ms and the window was never removed.
        // The ROM's own removal path is used instead; the code stays below as
        // the reference for a future attempt that hooks removeWindowInner.
        boolean fade = hookStartingWindowRemove(loader);
        say("mg-state startingWindow fadeHook=" + fade + " mode=reveal");
        boolean skip = hookAddStartingWindow(loader);
        boolean color = hookBackgroundColor(loader);
        say("starting-window suppression: skip=" + skip + " colorFallback=" + color
                + " | runtime-only, guarded by package name");
    }

    /**
     * 0.5.0-p82 / report §4 A, step "static cache (SystemUI long-lived, read
     * once; REFRESH on config change)". ConfigStore.notifyChanged() sends an
     * explicit Intent to every scoped package, SystemUI included, so a
     * RECEIVER_EXPORTED dynamic receiver (module process is a different UID)
     * is required -- the exact p63 live-config rule. On receipt we invalidate
     * the process-wide preview and re-arm the SAME bounded MIN_PRIORITY
     * daemon; no polling, no timer, no UI-thread IPC, no device file write.
     */
    private static void registerConfigRefresh(final Context ctx) {
        if (ctx == null || sConfigReceiverRegistered) return;
        try {
            BroadcastReceiver r = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                    try {
                        ConfigBus.invalidateStartingPreview();
                        say("mg-state startingWindowPreview refresh=config");
                        // A config change means a NEW background: re-read the
                        // config (lazy derivation) and the NEW tiny PNG.
                        ConfigBus.requestFirstFrame(ctx, null);
                    } catch (Throwable t) {
                        say("mg-state startingWindowPreview refresh failed: " + t);
                    }
                }
            };
            try {
                ctx.registerReceiver(r,
                        new IntentFilter(ConfigBus.ACTION_CONFIG_CHANGED),
                        Context.RECEIVER_EXPORTED);
            } catch (Throwable t) {
                try {
                    ctx.registerReceiver(r,
                            new IntentFilter(ConfigBus.ACTION_CONFIG_CHANGED));
                } catch (Throwable ignored) {
                }
            }
            sConfigReceiverRegistered = true;
            say("mg-state startingWindowPreview refreshReceiver=registered");
        } catch (Throwable t) {
            say("mg-state startingWindowPreview refreshReceiver failed: " + t);
        }
    }

    /**
     * 0.5.0-p79: §16.4's offline DEX audit found THREE starting-window entry
     * points inside com.android.systemui.apk (classes3.dex), and §18.6 proved
     * on-device that skipping them makes frame 0 show real content + glass
     * instead of the 35 KB pure-white frame. p66 hooked only the FIRST method
     * named addStartingWindow on ONE class; if the ROM dispatches through a
     * different overload / class (or that class moved dex), the skip misses
     * and §53.3 says the result is exactly the white splash.
     *
     * Hook every matching overload on all three classes. The package-name
     * guard inside StartingWindowSkipHooker is unchanged, so no other app is
     * affected. Every reflective step stays wrapped: a miss can only leave
     * the ROM untouched.
     */
    /**
     * 0.5.0-p86: the ONLY reliable moment SystemUI exposes a Context.
     * `ActivityThread.currentApplication()` is null at onPackageLoaded, which
     * silently disabled every starting-window prefetch (and therefore plan A)
     * since p79. Instrumentation.callApplicationOnCreate(Application) is
     * public, called for every process, and its arg0 IS the Application.
     */
    private boolean hookAppCreate(ClassLoader loader) {
        try {
            Class<?> c = Class.forName("android.app.Instrumentation", false, loader);
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals("callApplicationOnCreate")) continue;
                if (m.getParameterTypes().length != 1) continue;
                try {
                    hook(m).intercept(new StartingWindowAppHooker());
                    try {
                        deoptimize(m);
                    } catch (Throwable ignored) {
                    }
                    say("hooked Instrumentation.callApplicationOnCreate");
                    return true;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            say("starting-window app hook unavailable: " + t);
        }
        return false;
    }

    /** 0.5.0-p94: splash fade-out duration (plan A). */
    private static final long FADE_MS = 200L;
    /** Hard release so a dropped animation callback can never pin the splash. */
    private static final long FADE_BACKSTOP_MS = 500L;

    /**
     * 0.5.0-p94 (plan A): hook the ONE method through which every starting
     * window disappears, and fade the window out before letting the removal
     * happen. `StartingSurfaceDrawer.removeStartingWindow(
     * StartingWindowRemovalInfo)` is public (DEX line 217) and is called for
     * both the splash and the snapshot path.
     */
    private boolean hookStartingWindowRemove(ClassLoader loader) {
        try {
            Class<?> c = Class.forName(
                    "com.android.wm.shell.startingsurface.StartingSurfaceDrawer",
                    false, loader);
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals("removeStartingWindow")) continue;
                if (m.getParameterTypes().length != 1) continue;
                if (m.getReturnType() != void.class) continue;
                try {
                    hook(m).intercept(new StartingWindowRemoveHooker());
                    try {
                        deoptimize(m);
                    } catch (Throwable ignored) {
                    }
                    return true;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            say("starting-window remove hook unavailable: " + t);
        }
        return false;
    }

    /**
     * 0.5.0-p94 (plan A): BEFORE-hook on removeStartingWindow.
     *
     * <p>Fades the splash to alpha 0 over {@link #FADE_MS} and only then runs
     * the original method, so the user sees their own wallpaper dissolve into
     * the identical first frame instead of two hard cuts.
     *
     * <p>Safety: the release runnable is guarded by an AtomicBoolean and is
     * ALSO posted as a backstop, so a cancelled animation can never leave the
     * splash pinned on top of the UI. If anything throws, the original method
     * runs immediately (no fade, but never a hang).
     */
    public static final class StartingWindowRemoveHooker
            implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            try {
                Object info = chain.getArg(0);
                if (info != null) {
                    // 0.5.0-p99: do NOT defer proceed() any more (p94/p95 left
                    // the window on screen forever). Instead switch the ROM to
                    // its OWN reveal-animation branch:
                    //   SplashWindowRecord.removeIfPossible():
                    //     z (removeImmediately) == true  -> removeWindowInner()
                    //                                       which is setVisibility(8)
                    //                                       + removeView = HARD CUT
                    //     playRevealAnimation == true    -> applyExitAnimation()
                    // Only take the animation branch when the geometry it needs
                    // is present, otherwise the ROM would NPE inside
                    // applyExitAnimation and the window would never go away.
                    Object frame = field(info, "mainFrame");
                    Object leash = field(info, "windowAnimationLeash");
                    boolean reveal = false;
                    if (frame != null) {
                        reveal = setBool(info, "playRevealAnimation", true);
                        if (reveal) {
                            setBool(info, "removeImmediately", false);
                        }
                    }
                    say("mg-state startingWindowPreview reveal=" + (reveal ? 1 : 0)
                            + " leash=" + (leash != null)
                            + " frame=" + (frame != null));
                }
            } catch (Throwable t) {
                say("starting-window reveal skipped: " + t);
            }
            // Always proceed immediately -- the ROM's transition owns removal.
            return chain.proceed();
        }
    }

    private static View startingRootView(XposedInterface.Chain chain) {
        Object drawer = chain.getThisObject();
        if (drawer == null) {
            say("mg-state fade dbg this=null");
            return null;
        }
        Object info = chain.getArg(0);
        Object id = field(info, "taskId");
        if (!(id instanceof Integer)) {
            say("mg-state fade dbg taskId=" + id);
            return null;
        }
        Object manager = field(drawer, "mWindowRecords");
        if (manager == null) {
            say("mg-state fade dbg no mWindowRecords");
            return null;
        }
        Object array = field(manager, "mStartingWindowRecords");
        if (array == null) {
            say("mg-state fade dbg no mStartingWindowRecords");
            return null;
        }
        try {
            Object record = array.getClass()
                    .getMethod("get", int.class)
                    .invoke(array, id);
            if (record == null) {
                say("mg-state fade dbg no record task=" + id);
                return null;
            }
            Object v = field(record, "mRootView");
            say("mg-state fade dbg record=" + record.getClass().getSimpleName()
                    + " view=" + (v == null ? "null" : v.getClass().getName()));
            return v instanceof View ? (View) v : null;
        } catch (Throwable t) {
            say("mg-state fade dbg get failed: " + t);
            return null;
        }
    }

    private boolean hookAddStartingWindow(ClassLoader loader) {
        int hooked = 0;        hooked += hookSkips(loader, "com.android.wm.shell.ShellTaskOrganizer",
                "addStartingWindow");
        hooked += hookSkips(loader,
                "com.android.wm.shell.startingsurface.StartingWindowController",
                "addStartingWindow");
        hooked += hookSkips(loader,
                "com.android.wm.shell.startingsurface.HnSplashscreenWindowCreator",
                "addSplashScreenStartingWindow", "addStartingWindow");
        say("mg-state startingWindow skipHooks=" + hooked);
        return hooked > 0;
    }

    private int hookSkips(ClassLoader loader, String className,
                          String... names) {
        int n = 0;
        try {
            Class<?> c = Class.forName(className, false, loader);
            for (Method m : c.getDeclaredMethods()) {
                boolean match = false;
                for (String name : names) {
                    if (m.getName().equals(name)
                            && m.getParameterTypes().length >= 1) {
                        match = true;
                        break;
                    }
                }
                if (!match) continue;
                // 0.5.0-p82 / report §4 B: "confirm the return type of
                // StartingWindowController.addStartingWindow before hooking;
                // skipValue() returning 0/false for a NON-void method may
                // change the ROM's decision". Decompiled classes3.dex proves
                // all THREE entries are void (ShellTaskOrganizer.java:764,
                // StartingWindowController.java:505,
                // HnSplashscreenWindowCreator.java:115). Installing the skip
                // hook on void methods ONLY makes that fact a hard invariant
                // rather than an assumption, and logs any other overload.
                if (m.getReturnType() != void.class) {
                    say("mg-state startingWindow skipReturn="
                            + m.getReturnType().getName() + " method="
                            + c.getSimpleName() + "." + m.getName()
                            + " hook=skipped");
                    continue;
                }
                try {
                    hook(m).intercept(new StartingWindowSkipHooker());
                    try {
                        deoptimize(m);
                    } catch (Throwable ignored) {
                    }
                    n++;
                    say("hooked " + c.getSimpleName() + "." + m.getName());
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            say("hook " + className + " unavailable: " + t);
        }
        return n;
    }

    private boolean hookBackgroundColor(ClassLoader loader) {
        try {
            Class<?> c = Class.forName(
                    "com.android.wm.shell.startingsurface.HnSplashscreenWindowCreator",
                    false, loader);
            Method m = findMethod(c, "estimateTaskBackgroundColor", 1);
            if (m == null || m.getReturnType() != int.class) return false;
            hook(m).intercept(new StartingWindowColorHooker());
            say("hooked " + c.getSimpleName() + "." + m.getName());
            return true;
        } catch (Throwable t) {
            say("hook estimateTaskBackgroundColor unavailable: " + t);
            return false;
        }
    }

    /**
     * 0.5.0-p81 PLAN A -- SystemUI starting window gets a REAL picture.
     *
     * Decompiled evidence (probe/probe-report/sysui_dec/, classes3.dex):
     *   SplashscreenContentDrawer.makeSplashScreenContentView(Context,
     *       StartingWindowInfo, int, Consumer<Runnable>) -> SplashScreenView
     *   SplashscreenContentDrawer.makeSimpleSplashScreenContentView(Context,
     *       StartingWindowInfo, int) -> SplashScreenView
     *   HnSplashscreenWindowCreator.addSplashScreenStartingWindow(...) creates
     *       a plain FrameLayout and hands it to
     *       WindowManagerGlobal.addView(frameLayout, params, ...).
     * Both are ordinary Views owned by SystemUI. An after-hook that replaces
     * their background is pure memory, no /system or device file write.
     * The content View covers the theme windowBackground; the root hook also
     * covers the FrameLayout in case the white comes from the window itself.
     * Class names / signatures come from the DEX, never from a guess; every
     * reflective step is wrapped and a miss leaves the ROM untouched.
     */
    private int hookStartingWindowPaint(ClassLoader loader) {
        int n = 0;
        n += hookPaintMethods(loader,
                "com.android.wm.shell.startingsurface.SplashscreenContentDrawer");
        n += hookPaintMethods(loader,
                "com.android.wm.shell.startingsurface.HnSplashscreenContentDrawer");
        n += hookRootAddView(loader);
        return n;
    }

    /**
     * 0.5.0-p82 / report §3.1 + §4 A PREFERRED landing point.
     * Decompiled classes3.dex (probe/probe-report/sysui_dec/):
     *   HnSplashscreenContentDrawer.java line 136
     *   public void createContentView(StartingWindowInfo info, int type,
     *       Context ctx, Consumer<SplashScreenView> c1,
     *       Consumer<Runnable> c2, SynchronousQueue<MetaData> q)
     * arg0 is the StartingWindowInfo, so targetActivityInfo / taskInfo
     * .topActivityInfo resolve WITHOUT scanning several parameters. It only
     * posts a Runnable to mSplashscreenWorkerHandler; the actual View is made
     * later by makeSplashScreenContentView(), which plan A already after-hooks.
     * Class names and the method name come from the DEX, never a guess; the
     * whole install is reflected and every failure leaves the ROM untouched.
     */
    private int hookContentViewGuard(ClassLoader loader) {
        int n = 0;
        n += hookGuardMethods(loader,
                "com.android.wm.shell.startingsurface.HnSplashscreenContentDrawer");
        n += hookGuardMethods(loader,
                "com.android.wm.shell.startingsurface.SplashscreenContentDrawer");
        return n;
    }

    private int hookGuardMethods(ClassLoader loader, String className) {
        int n = 0;
        try {
            Class<?> c = Class.forName(className, false, loader);
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals("createContentView")
                        || m.getParameterTypes().length < 1) continue;
                try {
                    hook(m).intercept(new StartingWindowGuardHooker());
                    try {
                        deoptimize(m);
                    } catch (Throwable ignored) {
                    }
                    n++;
                    say("hooked " + c.getSimpleName() + ".createContentView (guard)");
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            say("starting-window content guard unavailable: " + className + " " + t);
        }
        return n;
    }

    /** Short-lived, whitelist-only package resolved by the preferred guard. */
    private static String guardPackage() {
        String p = sGuardPackage;
        if (p == null || !Scope.isStartingWindowTarget(p)) return null;
        if (SystemClock.uptimeMillis() - sGuardAtMs > GUARD_TTL_MS) return null;
        return p;
    }

    /**
     * BEFORE hook: record + negative log + bounded prefetch ONLY.
     * Report §4 A: "on failure always chain.proceed()". This hooker never
     * returns a substitute value, never changes an argument and never
     * swallows the real method, so a reflective miss is a pure no-op.
     */
    public static final class StartingWindowGuardHooker implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            try {
                String pkg = startingPackage(chain.getArg(0));
                boolean target = Scope.isStartingWindowTarget(pkg);
                seen(chain, pkg, target ? "guard" : "nontarget");
                if (target) {
                    sGuardPackage = pkg;
                    sGuardAtMs = SystemClock.uptimeMillis();
                    if (ConfigBus.startingPreview() == null) {
                        ConfigBus.requestFirstFrame(sSystemUiContext, null);
                    }
                } else {
                    sGuardPackage = null;
                }
            } catch (Throwable t) {
                say("mg-state startingWindow guard failed (proceeding): " + t);
            }
            return chain.proceed();
        }
    }

    private int hookPaintMethods(ClassLoader loader, String className) {
        int n = 0;
        try {
            Class<?> c = Class.forName(className, false, loader);
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals("makeSplashScreenContentView")
                        && !m.getName().equals("makeSimpleSplashScreenContentView")) {
                    continue;
                }
                if (m.getParameterTypes().length < 2
                        || m.getReturnType() == void.class
                        || m.getReturnType().isPrimitive()) continue;
                try {
                    hook(m).intercept(new StartingWindowPaintHooker());
                    try {
                        deoptimize(m);
                    } catch (Throwable ignored) {
                    }
                    n++;
                    say("hooked " + c.getSimpleName() + "." + m.getName()
                            + " (starting-window paint)");
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            say("starting-window paint hooks unavailable: " + className + " " + t);
        }
        return n;
    }

    private int hookRootAddView(ClassLoader loader) {
        int n = 0;
        try {
            Class<?> c = Class.forName("android.view.WindowManagerGlobal",
                    false, loader);
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals("addView")
                        || m.getParameterTypes().length < 2) continue;
                try {
                    hook(m).intercept(new StartingWindowRootHooker());
                    try {
                        deoptimize(m);
                    } catch (Throwable ignored) {
                    }
                    n++;
                    say("hooked WindowManagerGlobal.addView (root paint)");
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            say("starting-window root hook unavailable: " + t);
        }
        return n;
    }

    /** StartingWindowInfo may be arg0 or arg1 depending on the overload. */
    private static String paintPackage(XposedInterface.Chain chain) {
        for (int i = 0; i < 3; i++) {
            try {
                String p = startingPackage(chain.getArg(i));
                if (p != null) return p;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Method findMethod(Class<?> c, String name, int argCount) {
        try {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name)
                        && m.getParameterTypes().length >= argCount) return m;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Reflective field lookup that also walks superclasses. */
    /**
     * 0.5.0-p99: write a boolean field of a framework object (used to switch
     * the starting-window removal into the ROM's OWN reveal-animation branch).
     * Returns true only when the field was really written.
     */
    private static boolean setBool(Object target, String name, boolean value) {
        if (target == null) return false;
        Class<?> c = target.getClass();
        while (c != null && c != Object.class) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.setBoolean(target, value);
                return true;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Throwable t) {
                return false;
            }
        }
        return false;
    }

    private static Object field(Object target, String name) {
        if (target == null) return null;
        Class<?> c = target.getClass();
        while (c != null && c != Object.class) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    /** Resolve the launch package from StartingWindowInfo / TaskInfo / Intent. */
    static String startingPackage(Object info) {
        String p = packageOfActivityInfo(field(info, "targetActivityInfo"));
        if (p != null) return p;
        Object task = field(info, "taskInfo");
        if (task == null) task = info;
        p = packageOfActivityInfo(field(task, "topActivityInfo"));
        if (p != null) return p;
        p = packageOfIntent(field(task, "baseIntent"));
        if (p != null) return p;
        p = packageOfActivityInfo(field(task, "baseActivity"));
        if (p != null) return p;
        return packageOfIntent(field(info, "baseIntent"));
    }

    private static String packageOfActivityInfo(Object ai) {
        Object p = field(ai, "packageName");
        return p instanceof String && ((String) p).length() > 0 ? (String) p : null;
    }

    private static String packageOfIntent(Object intent) {
        if (intent == null) return null;
        try {
            Object p = intent.getClass().getMethod("getPackage").invoke(intent);
            if (p instanceof String && ((String) p).length() > 0) return (String) p;
        } catch (Throwable ignored) {
        }
        try {
            Object comp = intent.getClass().getMethod("getComponent").invoke(intent);
            if (comp != null) {
                Object p = comp.getClass().getMethod("getPackageName").invoke(comp);
                if (p instanceof String && ((String) p).length() > 0) return (String) p;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Correct zero-value for a skipped method's return type. */
    private static Object skipValue(XposedInterface.Chain chain) {
        try {
            if (chain.getExecutable() instanceof Method) {
                Class<?> r = ((Method) chain.getExecutable()).getReturnType();
                if (r == boolean.class) return Boolean.FALSE;
                if (r == int.class) return Integer.valueOf(0);
                if (r == long.class) return Long.valueOf(0L);
                if (r == float.class) return Float.valueOf(0f);
                if (r == double.class) return Double.valueOf(0d);
                if (r == short.class) return Short.valueOf((short) 0);
                if (r == byte.class) return Byte.valueOf((byte) 0);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 0.5.0-p87 — USER DECISION (2026-11-16): NO STARTING WINDOW AT ALL.
     *
     * p86 painted the starting window with the chosen background picture.
     * Measured on device: that frame occupies the screen from +217 ms to
     * +1.72 s with NO interface content, i.e. the user sees a full-screen
     * photo and only then the Settings UI — reported as "a boot image frame".
     *
     * The requirement is now: no intermediate frame; the Settings window
     * itself is the first thing that appears. So every target package is
     * ALWAYS skipped, regardless of whether a preview picture exists.
     * Consequence, accepted by the user: the previous screen stays visible
     * during the app's own start-up instead of showing a fake frame.
     *
     * The A/C machinery (preview prefetch, config refresh, paint hooks) is
     * kept intact and still feeds the HOST first frame, so the window appears
     * already showing the chosen background + glass; only the SystemUI
     * starting window is suppressed.
     */
    public static final class StartingWindowSkipHooker implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            try {
                String pkg = startingPackage(chain.getArg(0));
                boolean target = Scope.isStartingWindowTarget(pkg);
                // 0.5.0-p96: BACK TO UNCONDITIONAL SKIP.
                //
                // p94/p95 tried plan A (paint the picture, then fade the splash
                // out over 200 ms by deferring removeStartingWindow). The paint
                // half worked (`mode=paint`, `applied=root/content`) and the
                // fade callback fired (`fade=200`), but the deferred
                // `chain.proceed()` did NOT remove the window: measured with
                // `dumpsys window windows` ->
                //   isVisible=true isOnScreen=true mDrawState=HAS_DRAWN
                //   mWindowRemovalAllowed=false
                // and SurfaceFlinger kept GROWING the layer list
                // (VRI-Splash Screen #35831, #35856, #35857 ...). A splash the
                // user cannot dismiss is worse than no splash at all, so the
                // window must not exist. Do NOT re-enable the picture splash
                // until the removal is proven to complete (a hook on the real
                // removal path, not a deferred proceed()).
                // 0.5.0-p98 (plan A, stage 2): let the window exist ONLY when
                // we can paint the real background on it. No removal hook is
                // installed, so the ROM removes it through its own native path
                // -- that is the point: p94/p95's deferred proceed() left the
                // window on screen forever (isVisible=true,
                // mWindowRemovalAllowed=false, layers accumulated).
                boolean paint = target && sPaintHooks > 0
                        && ConfigBus.startingPreview() != null;
                seen(chain, pkg, target ? (paint ? "paint" : "skip") : "nontarget");
                if (target && !paint) {
                    return skipValue(chain);
                }
            } catch (Throwable t) {
                say("starting-window guard failed (proceeding): " + t);
            }
            return chain.proceed();
        }
    }

    /**
     * 0.5.0-p86: capture SystemUI's Application and warm the first-frame
     * picture from it. Runs once per process, on the main thread, at the very
     * start of the app — long before any starting window is requested.
     */
    public static final class StartingWindowAppHooker implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            Object result = chain.proceed();
            try {
                Object app = chain.getArg(0);
                if (app instanceof Context) {
                    Context ctx = (Context) app;
                    sSystemUiContext = ctx;
                    ConfigBus.warmWindowColor(ctx);
                    registerConfigRefresh(ctx);
                    say("mg-state startingWindow ctx=captured");
                }
            } catch (Throwable t) {
                say("starting-window ctx capture failed: " + t);
            }
            return result;
        }
    }

    /** Always visible (`mg-state `), one short line per starting window. */
    private static void seen(XposedInterface.Chain chain, String pkg, String mode) {
        try {
            String method = "?";
            if (chain.getExecutable() != null) {
                method = chain.getExecutable().getName();
            }
            say("mg-state startingWindow seen method=" + method
                    + " pkg=" + (pkg == null ? "none" : pkg)
                    + " mode=" + mode);
        } catch (Throwable ignored) {
        }
    }

    /** AFTER-hook on the ROM's SplashScreenView factory (content View). */
    /** 0.5.0-p103: GPU blur radius (px) applied to the splash only. */
    private static final float SPLASH_BLUR_RADIUS = 64f;

    /**
     * 0.5.0-p103: blur the starting-window View on the GPU.
     *
     * <p>Deliberately NOT a second pre-blurred PNG: that attempt (p100) never
     * produced a file on device. This keeps the SAME bitmap and the SAME
     * bounds, so the splash cannot drift from the first frame it dissolves
     * into -- it only loses detail, which is exactly what turns a "photo stuck
     * on screen" into a focus pull.
     */
    private static void applySplashBlur(View v, String pkg) {
        try {
            v.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(
                    SPLASH_BLUR_RADIUS, SPLASH_BLUR_RADIUS,
                    android.graphics.Shader.TileMode.CLAMP));
            say("mg-state startingWindowPreview blur=gpu r=" + SPLASH_BLUR_RADIUS
                    + " pkg=" + pkg);
        } catch (Throwable t) {
            say("mg-state startingWindowPreview blur=off why=" + t);
        }
    }

    public static final class StartingWindowPaintHooker implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            Object result = chain.proceed();
            try {
                String pkg = paintPackage(chain);
                // 0.5.0-p82 / report §3.1: the two factory methods do NOT all
                // carry StartingWindowInfo in the same slot, so p81 scanned
                // the first three args. The preferred createContentView guard
                // resolves it exactly; use it whenever that scan failed.
                if (pkg == null) pkg = guardPackage();
                if (!Scope.isStartingWindowTarget(pkg) || !(result instanceof View)) {
                    return result;
                }
                // 0.5.0-p103: show the SHARP preview and blur it on the GPU with
                // a RenderEffect. A second PNG was tried first (downscale 1/14 +
                // bilinear upscale in the module process) and never landed on
                // device, while this needs no extra file and no extra bitmap:
                // the blur happens at composite time. The real first frame is
                // sharp, so the hand-over reads as a focus pull.
                Bitmap b = ConfigBus.startingPreview();
                if (b == null || b.isRecycled()) return result;
                View v = (View) result;
                try {
                    v.setBackground(new BitmapDrawable(v.getResources(), b));
                    v.setAlpha(1f);
                    v.invalidate();
                } catch (Throwable ignored) {
                }
                applySplashBlur(v, pkg);
                say("mg-state startingWindowPreview applied=" + pkg);
                say("mg-state startingWindowPreview applied=content pkg=" + pkg
                        + " view=" + v.getClass().getName()
                        + " size=" + v.getWidth() + "x" + v.getHeight()
                        + " img=" + b.getWidth() + "x" + b.getHeight());
                return result;
            } catch (Throwable t) {
                say("mg-state startingWindowPreview content failed: " + t);
            }
            return result;
        }
    }

    /** BEFORE-hook on WindowManagerGlobal.addView (root FrameLayout). */
    public static final class StartingWindowRootHooker implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            try {
                Object viewObj = chain.getArg(0);
                Object p = field(chain.getArg(1), "packageName");
                String pkg = p instanceof String ? (String) p : null;
                // 0.5.0-p82: LayoutParams.packageName is the DEX-proven
                // source (createLayoutParameters line 73); the preferred
                // createContentView guard is a bounded fallback only.
                if (pkg == null) pkg = guardPackage();
                if (Scope.isStartingWindowTarget(pkg) && viewObj instanceof View) {
                    Bitmap b = ConfigBus.startingPreview();
                    if (b != null && !b.isRecycled()) {
                        View rv = (View) viewObj;
                        try {
                            rv.setBackground(new BitmapDrawable(rv.getResources(), b));
                        } catch (Throwable ignored) {
                        }
                        applySplashBlur(rv, pkg);
                        say("mg-state startingWindowPreview applied=root pkg=" + pkg
                                + " size=" + rv.getWidth() + "x" + rv.getHeight()
                                + " img=" + b.getWidth() + "x" + b.getHeight());
                    }
                }
            } catch (Throwable ignored) {
            }
            // 0.5.0-p92: the post-proceed "did the ROM overwrite our
            // background" review is gone together with the picture path.
            // skip is the only live form, so there is nothing to re-apply.
            return chain.proceed();
        }
    }

    /** Fallback: transparent splash colour instead of the ROM's solid white. */
    public static final class StartingWindowColorHooker implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            try {
                Object task = chain.getArg(0);
                String pkg = packageOfActivityInfo(field(task, "topActivityInfo"));
                if (pkg == null) pkg = packageOfIntent(field(task, "baseIntent"));
                if (Scope.isStartingWindowTarget(pkg)) {
                    // 0.5.0-p66: TRANSPARENT lets the ROM fall back to its
                    // white theme windowBackground. Return the SAME opaque
                    // colour BackdropLayer uses for every un-composited frame,
                    // so the transition runs on the current background colour
                    // instead of a white splash.
                    int c = ConfigBus.windowColor();
                    say("starting-window colour=" + Integer.toHexString(c)
                            + " for " + pkg);
                    return Integer.valueOf(c);
                }
            } catch (Throwable ignored) {
            }
            return chain.proceed();
        }
    }

    /**
     * 0.5.0 POWER/DIAGNOSTIC GATE.
     * Per-frame log strings used to be built and written to BOTH the LSPosed
     * log database and logcat. With the switch off (default) every diagnostic
     * line is dropped. The process-start signature is always kept so
     * `grep -c "mg-build <ver>"` still proves which dex lspd actually loaded,
     * and the two one-shot hook-state lines are kept for basic triage.
     *
     * 0.5.0-p104: the flag and the writer MOVED to {@link MgLog}, because this
     * class extends a libxposed class that does not exist in the module APK's
     * own process. Six device crashes proved that any touch of GlassEntry from
     * ModuleProvider.query() is a linkage error the catch blocks cannot stop.
     * Hooked processes are unaffected: GlassEntry binds MgLog at load.
     */
    static void setVerbose(boolean on) {
        MgLog.setVerbose(on);
    }

    static boolean verbose() {
        return MgLog.verbose();
    }

    /** Package-visible so RenderController / helpers can log through LSPosed. */
    static void say(String msg) {
        MgLog.say(msg);
    }
}
