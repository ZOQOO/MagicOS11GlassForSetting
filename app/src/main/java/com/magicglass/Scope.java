package com.magicglass;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * One source of truth for the LSPosed scope.
 *
 * This module is standalone: its package name is com.magicglass, NOT the upstream
 * com.ciallo.hyperbackground. Nothing here inherits the MIUI/HyperOS base APK's
 * scope list, preference group, activity names or class names.
 *
 * Every entry must exist in BOTH this set and META-INF/xposed/scope.list, and
 * must be ticked in LSPosed. A missing entry fails silently.
 */
public final class Scope {

    public static final String MODULE = "com.magicglass";
    public static final String SETTINGS = "com.android.settings";
    public static final String SYSTEMUI = "com.android.systemui";

    /** Six external apps that host pages reachable from the Settings home screen. */
    public static final String[] EXTERNAL = {
            "com.hihonor.systemmanager",
            "com.hihonor.controlcenter",
            "com.hihonor.assistant",
            "com.hihonor.id",
            "com.hihonor.wallet",
            "com.hihonor.parentcontrol",
    };

    /**
     * 0.5.0-p105: 桌面和个性化 is hosted by com.hihonor.wallpapereditor,
     * which was NOT in the original scope. It needs the top-bar strip but must
     * NOT join isExternal() -- that would also turn on the SystemUI starting
     * window hooks for a package whose launch behaviour was never surveyed.
     */
    private static final String[] TOPBAR_ONLY = {
            "com.hihonor.wallpapereditor",
    };

    private static final Set<String> RENDER_TARGETS;

    static {
        Set<String> s = new HashSet<>(Arrays.asList(EXTERNAL));
        s.addAll(Arrays.asList(TOPBAR_ONLY));
        s.add(SETTINGS);
        RENDER_TARGETS = Collections.unmodifiableSet(s);
    }

    private Scope() {
    }

    public static boolean isRenderTarget(String pkg) {
        return pkg != null && RENDER_TARGETS.contains(pkg);
    }

    /** True for the six apps whose pages are built from bespoke card Views. */
    public static boolean isExternal(String pkg) {
        if (pkg == null) return false;
        for (String p : EXTERNAL) {
            if (p.equals(pkg)) return true;
        }
        return false;
    }

    /** 0.5.0-p105: render-only scope addition, no starting-window side effects. */
    public static boolean isTopBarOnly(String pkg) {
        if (pkg == null) return false;
        for (String p : TOPBAR_ONLY) {
            if (p.equals(pkg)) return true;
        }
        return false;
    }

    /** Packages allowed to have their white starting window suppressed. */
    public static boolean isStartingWindowTarget(String pkg) {
        return SETTINGS.equals(pkg) || isExternal(pkg);
    }

    public static boolean isModule(String pkg) {
        return MODULE.equals(pkg);
    }

    public static boolean isSystemUi(String pkg) {
        return SYSTEMUI.equals(pkg);
    }

    /** slot id used by ConfigContract for an external package. */
    public static String externalSlotId(String pkg) {
        if (pkg == null) {
            return "ext.unknown";
        }
        int dot = pkg.lastIndexOf('.');
        return "ext." + (dot >= 0 ? pkg.substring(dot + 1) : pkg);
    }

    public static String[] all() {
        String[] out = new String[EXTERNAL.length + TOPBAR_ONLY.length + 3];
        int i = 0;
        out[i++] = SETTINGS;
        for (String p : EXTERNAL) out[i++] = p;
        for (String p : TOPBAR_ONLY) out[i++] = p;
        out[i] = SYSTEMUI;
        out[i + 1] = MODULE;
        return out;
    }
}
