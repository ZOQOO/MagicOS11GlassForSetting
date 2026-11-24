package com.magicglass;

import java.lang.reflect.Method;

/**
 * 0.5.0-p104: logging that is SAFE IN THE MODULE'S OWN APP PROCESS.
 *
 * Measured defect (device crash buffer, six crashes in eleven seconds while a
 * cold start queried the provider):
 *
 *   FATAL EXCEPTION: binder:14393_2
 *   Process: com.magicglass, PID: 14393
 *   java.lang.NoClassDefFoundError: Failed resolution of:
 *       Lio/github/libxposed/api/XposedModule;
 *     at com.magicglass.ConfigStore.writeWindowPreview(ConfigStore.java:501)
 *     at com.magicglass.ConfigStore.deriveWindowColor(ConfigStore.java:383)
 *     at com.magicglass.ConfigStore.ensureWindowColor(ConfigStore.java:745)
 *     at com.magicglass.ModuleProvider.query(ModuleProvider.java:68)
 *
 * Root cause is a LINKAGE error, not an exception in the usual sense:
 * {@link GlassEntry} extends {@code io.github.libxposed.api.XposedModule}, a
 * class that only exists inside a hooked process. The module APK's own
 * process is NOT hooked, so the very first reference to ANY member of
 * GlassEntry (even a static `say()`) fails to resolve -- and because the
 * failure happens while the caller is linking/resolving that reference, the
 * `catch (Throwable)` blocks around it do not reliably intercept it. The
 * provider process therefore died mid-query, which is why the p100 blurred
 * PNG "never appeared" (the crash sits between the sharp PNG write and the
 * blur write).
 *
 * This class has NO superclass or field whose type lives outside the module
 * APK. The framework logger is only ever touched through an untyped
 * {@code Object} + reflection, so an unhooked process simply logs to logcat
 * and never resolves a missing class.
 *
 * Every file that also runs in the module app process (ConfigStore,
 * ConfigBus, ModuleProvider, SplashBase) must call THIS instead of
 * GlassEntry.say().
 */
public final class MgLog {

    private static final String TAG = "MagicGlass";
    /** Same priority GlassEntry used (LSPosed "info"). */
    private static final int PRIORITY = 4;

    /** The XposedModule instance, kept untyped on purpose. */
    private static volatile Object sModule;
    private static volatile Method sLog;
    /** Mirrors GlassEntry's perf.debugLog switch. */
    private static volatile boolean sVerbose;

    private MgLog() {
    }

    /** Called from GlassEntry.onPackageLoaded() in a HOOKED process only. */
    static void bind(Object module) {
        if (module == null) return;
        sModule = module;
        Method found = null;
        try {
            for (Method m : module.getClass().getMethods()) {
                if (!"log".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 3 && p[0] == int.class
                        && p[1] == String.class && p[2] == String.class) {
                    found = m;
                    break;
                }
            }
        } catch (Throwable ignored) {
        }
        sLog = found;
    }

    static void setVerbose(boolean on) {
        sVerbose = on;
    }

    static boolean verbose() {
        return sVerbose;
    }

    /**
     * True for the handful of one-shot lines emitted at process start.
     *
     * 0.5.0-p78: `mg-state ` lines are ALWAYS visible, even when
     * perf.debugLog is off (the default). After an install the single most
     * common failure is lspd still running the CACHED dex, so an empty log
     * could not distinguish "old dex" from "fix did nothing". These lines are
     * emitted only on process start, a slot/force change or a throttled keep
     * decision: never per frame.
     */
    private static boolean startupLine(String msg) {
        return msg.startsWith("mg-build ")
                || msg.startsWith("mg-state ")
                || msg.startsWith("render hooks installed")
                || msg.startsWith("SDK ")
                || msg.startsWith("module process loaded")
                || msg.startsWith("starting-window suppression:");
    }

    /** Package-visible so RenderController / helpers can log through LSPosed. */
    static void say(String msg) {
        if (msg == null) return;
        if (!sVerbose && !startupLine(msg)) return;
        Object module = sModule;
        Method log = sLog;
        if (module != null && log != null) {
            try {
                log.invoke(module, PRIORITY, TAG, msg);
            } catch (Throwable ignored) {
            }
        }
        // LSPosed 2.2.1 stores module logs in its own database and does NOT
        // mirror them into logcat. Duplicate into logcat so `adb logcat -d`
        // can verify the render path without opening the manager UI.
        try {
            android.util.Log.i(TAG, msg);
        } catch (Throwable ignored) {
        }
    }
}
