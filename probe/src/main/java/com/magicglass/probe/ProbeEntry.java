package com.magicglass.probe;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.Process;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * P0 read-only probe. Install as a separate LSPosed module, enable its scope,
 * then read the "MagicGlassProbe" tag in the LSPosed log.
 *
 * It answers four questions before any rendering code is written:
 *  1. Does the Honor transparency SDK exist in the boot class path, in the
 *     settings app class loader, or nowhere at all?
 *  2. What are the real method signatures on this device/ROM build?
 *  3. Are MIUI's setMi* hidden methods present? (We must NOT rely on them.)
 *  4. What level signals (activity name, fragment stack, view tree) are
 *     actually available for L1/L2/L3 classification?
 *
 * No code here depends on any upstream module, MIUI class, or preference key.
 */
public final class ProbeEntry extends XposedModule {

    private static final String TAG = "MagicGlassProbe";
    private static final int PRIORITY = 4;

    // Verified by offline DEX parsing on HONOR AAK-AN00 / Android 17 (API 37)
    // / 11.0.0.117(SP3C00E117R102P2), 2026-11-11. Three distinct layers:
    //   boot  = /system/framework/framework.jar + framework-magic.jar
    //   host  = classes4.dex shipped inside each scoped APK
    //   devSDK= developer.honor.com aar, NOT present in the ROM at all
    private static final String[] SDK_CLASSES = {
            // --- boot: android.graphics.material engine ---
            "android.graphics.material.Material",
            "android.graphics.material.Material$MaterialType",
            "android.graphics.material.MaterialOpt",
            "android.graphics.material.LiquidGlassMaterial",
            "android.graphics.material.FrostGlassMaterial",
            "android.graphics.material.atomiceffect.GlassParam",
            "android.graphics.material.atomiceffect.BlurParam",
            "android.graphics.material.atomiceffect.ShapeParam",
            "android.graphics.material.atomiceffect.EdgeLightParam",
            "android.graphics.material.atomiceffect.EnvironmentLightParam",
            "android.graphics.material.atomiceffect.StrokeParam",
            "android.graphics.material.atomiceffect.ShadowParam",
            // --- boot: framework-magic.jar Honor extensions ---
            "com.hihonor.android.graphics.material.HnMaterialOpt",
            "com.hihonor.android.vieweffect.HnVisualEffectEx",
            "com.hihonor.android.vieweffect.effects.api.HnVisualEffectFactory",
            // --- host: uikit transparency shipped inside Settings.apk ---
            "com.hihonor.uikit.transparency.widget.HnTransRelativeLayout",
            "com.hihonor.uikit.transparency.widget.HnTransLinearLayout",
            "com.hihonor.uikit.transparency.style.StyleType",
            "com.hihonor.uikit.transparency.style.AbsViewStyle",
            "com.hihonor.uikit.transparency.style.HnBaseGlassStyle",
            "com.hihonor.uikit.transparency.effect.SceneType",
            "com.hihonor.uikit.transparency.effect.ViewEffectType",
            "com.hihonor.uikit.transparency.adapters.HnMaterialAggregator",
            // --- developer SDK aar: expected MISSING unless we embed it ---
            "com.hihonor.uikit.transparency.api.HnVisualEffectLayout",
            "com.hihonor.uikit.transparency.api.HnGlassEffect",
    };

    private static final String[] TARGET_PACKAGES = {
            "com.android.settings",
            "com.hihonor.systemmanager",
            "com.hihonor.controlcenter",
            "com.hihonor.assistant",
            "com.hihonor.id",
            "com.hihonor.wallet",
            "com.hihonor.parentcontrol",
            "com.android.systemui",
            "com.magicglass",
            "com.magicglass.probe",
    };

    private static final String[] HIDDEN_KEYWORDS = {
            "glass", "material", "blur", "transparen", "refract", "backdrop",
            "visualeffect", "setmi", "sethn", "adapt", "liquid", "frost"
    };

    private static final Set<String> TARGETS =
            new HashSet<>(Arrays.asList(TARGET_PACKAGES));
    private static final Set<Integer> SEEN_ACTIVITIES = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean HOST_PROBED = new AtomicBoolean(false);
    private static final List<String> SETTINGS_KEYS = new ArrayList<>();
    private static volatile XposedModule sModule;

    public ProbeEntry() {
        super();
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        sModule = this;
        String pkg = param.getPackageName();
        if (!TARGETS.contains(pkg)) {
            return;
        }
        say("===== probe start pkg=" + pkg + " pid=" + Process.myPid() + " =====");
        probeSdk(View.class.getClassLoader(), "boot");
        probeHiddenApi();
        probeSettingsFields();
        installActivityHooks();
    }

    // ---- 1. Honor transparency SDK ------------------------------------------

    private static void probeSdk(ClassLoader loader, String label) {
        if (loader == null) {
            say("[" + label + "] classloader is null");
            return;
        }
        for (String name : SDK_CLASSES) {
            try {
                Class<?> c = Class.forName(name, false, loader);
                say("[FOUND:" + label + "] " + name
                        + " loader=" + c.getClassLoader()
                        + " super=" + (c.getSuperclass() == null ? "null" : c.getSuperclass().getName()));
                dumpClass(c);
            } catch (Throwable t) {
                say("[MISSING:" + label + "] " + name);
            }
        }
    }

    private static void dumpClass(Class<?> c) {
        try {
            for (Constructor<?> k : c.getDeclaredConstructors()) {
                say("  ctor " + signature(k.getName(), k.getParameterTypes()));
            }
            int printed = 0;
            for (Method m : c.getDeclaredMethods()) {
                if (m.isSynthetic() || m.isBridge() || printed >= 45) {
                    continue;
                }
                say("  method " + Modifier.toString(m.getModifiers()) + " "
                        + m.getReturnType().getSimpleName() + " "
                        + signature(m.getName(), m.getParameterTypes()));
                printed++;
            }
            for (Field f : c.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                Object v = null;
                try {
                    v = f.get(null);
                } catch (Throwable ignored) {
                }
                say("  field " + f.getName() + " = " + v);
            }
            if (c.isEnum()) {
                say("  enum constants = " + Arrays.toString(c.getEnumConstants()));
            }
        } catch (Throwable t) {
            say("  dump failed " + t);
        }
    }

    private static String signature(String name, Class<?>[] types) {
        StringBuilder sb = new StringBuilder(name).append('(');
        for (int i = 0; i < types.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(types[i].getSimpleName());
        }
        return sb.append(')').toString();
    }

    // ---- 2. Hidden View / Window API (MIUI leftovers vs Honor APIs) ----------

    private static void probeHiddenApi() {
        String[] names = {
                "android.view.View", "android.view.ViewGroup", "android.view.Window",
                "android.graphics.RenderNode", "android.graphics.RenderEffect",
                "android.view.WindowManager.LayoutParams",
                "android.graphics.material.MaterialOpt",
                "com.hihonor.android.vieweffect.HnVisualEffectEx"
        };
        for (String name : names) {
            try {
                Class<?> c = Class.forName(name, false, View.class.getClassLoader());
                int n = 0;
                for (Method m : c.getDeclaredMethods()) {
                    String low = m.getName().toLowerCase();
                    boolean hit = false;
                    for (String k : HIDDEN_KEYWORDS) {
                        if (low.contains(k)) {
                            hit = true;
                            break;
                        }
                    }
                    if (hit && n < 40) {
                        say("[HIDDEN] " + name + "." + signature(m.getName(), m.getParameterTypes()));
                        n++;
                    }
                }
                say("[HIDDEN] " + name + " matches=" + n);
            } catch (Throwable t) {
                say("[HIDDEN] " + name + " unavailable");
            }
        }
    }

    // ---- 3. System transparency setting keys --------------------------------

    private static void probeSettingsFields() {
        String[] groups = {
                "android.provider.Settings$Global",
                "android.provider.Settings$System",
                "android.provider.Settings$Secure"
        };
        for (String g : groups) {
            try {
                Class<?> c = Class.forName(g);
                for (Field f : c.getDeclaredFields()) {
                    if (!Modifier.isStatic(f.getModifiers()) || f.getType() != String.class) {
                        continue;
                    }
                    Object v;
                    try {
                        v = f.get(null);
                    } catch (Throwable ignored) {
                        continue;
                    }
                    String s = String.valueOf(v);
                    if (!hasKeyword(s)) {
                        continue;
                    }
                    SETTINGS_KEYS.add(s);
                    say("[KEY] " + g + "." + f.getName() + " = " + s);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static boolean hasKeyword(String s) {
        String low = s.toLowerCase();
        for (String k : HIDDEN_KEYWORDS) {
            if (low.contains(k)) {
                return true;
            }
        }
        return false;
    }

    // ---- 4. Activity / level signals ----------------------------------------

    private void installActivityHooks() {
        try {
            Method onCreate = Instrumentation.class.getDeclaredMethod(
                    "callActivityOnCreate", Activity.class, Bundle.class);
            hook(onCreate).intercept(new ActivityHook());
            say("hooked Instrumentation.callActivityOnCreate");
        } catch (Throwable t) {
            say("hook callActivityOnCreate failed: " + t);
        }
        try {
            Method onResume = Instrumentation.class.getDeclaredMethod(
                    "callActivityOnResume", Activity.class);
            hook(onResume).intercept(new ActivityHook());
            say("hooked Instrumentation.callActivityOnResume");
        } catch (Throwable t) {
            say("hook callActivityOnResume failed: " + t);
        }
    }

    private final class ActivityHook implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            Object result = chain.proceed();
            try {
                Object arg0 = chain.getArg(0);
                if (arg0 instanceof Activity) {
                    probeActivity((Activity) arg0);
                }
            } catch (Throwable t) {
                say("activity probe failed: " + t);
            }
            return result;
        }
    }

    private static void probeActivity(Activity a) {
        int id = System.identityHashCode(a);
        if (!SEEN_ACTIVITIES.add(id)) {
            return;
        }
        if (HOST_PROBED.compareAndSet(false, true)) {
            probeSdk(a.getClass().getClassLoader(), "host");
        }
        say("[PAGE] activity=" + a.getClass().getName()
                + " pkg=" + a.getPackageName() + " task=" + a.getTaskId());
        try {
            Intent i = a.getIntent();
            if (i != null) {
                Bundle ex = i.getExtras();
                say("[PAGE] intent=" + i.getAction() + " extras=" + (ex == null ? "null" : ex.keySet()));
            }
        } catch (Throwable ignored) {
        }
        say("[PAGE] fragmentBackStack=" + fragmentDepth(a));
        dumpSettingsValues(a);
        dumpViewTree(a);
    }

    private static int fragmentDepth(Activity a) {
        for (String getter : new String[]{"getFragmentManager", "getSupportFragmentManager"}) {
            try {
                Method m = a.getClass().getMethod(getter);
                Object fm = m.invoke(a);
                if (fm == null) {
                    continue;
                }
                Method count = fm.getClass().getMethod("getBackStackEntryCount");
                Object v = count.invoke(fm);
                if (v instanceof Integer) {
                    return (Integer) v;
                }
            } catch (Throwable ignored) {
            }
        }
        return -1;
    }

    private static void dumpSettingsValues(Activity a) {
        for (String key : SETTINGS_KEYS) {
            try {
                String v = Settings.Global.getString(a.getContentResolver(), key);
                if (v != null) {
                    say("[VALUE] " + key + " = " + v);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static void dumpViewTree(Activity a) {
        try {
            View decor = a.getWindow().getDecorView();
            Map<String, Integer> counts = new TreeMap<>();
            walk(decor, counts, 0);
            List<Map.Entry<String, Integer>> list = new ArrayList<>(counts.entrySet());
            list.sort(Comparator.comparingInt((Map.Entry<String, Integer> e) -> e.getValue()).reversed());
            int n = 0;
            for (Map.Entry<String, Integer> e : list) {
                if (n >= 35) {
                    break;
                }
                say("[VIEW] " + e.getValue() + " x " + e.getKey());
                n++;
            }
        } catch (Throwable t) {
            say("[VIEW] dump failed: " + t);
        }
    }

    private static void walk(View v, Map<String, Integer> counts, int depth) {
        if (v == null || depth > 25 || counts.size() > 400) {
            return;
        }
        String name = v.getClass().getName();
        counts.put(name, counts.containsKey(name) ? counts.get(name) + 1 : 1);
        try {
            int id = v.getId();
            if (id != View.NO_ID) {
                String res = v.getResources().getResourceEntryName(id);
                counts.put("@id/" + res, 1);
            }
        } catch (Throwable ignored) {
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                walk(g.getChildAt(i), counts, depth + 1);
            }
        }
    }

    private static void say(String msg) {
        try {
            XposedModule m = sModule;
            if (m != null) {
                m.log(PRIORITY, TAG, msg);
            }
        } catch (Throwable ignored) {
        }
    }
}
