package com.magicglass;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Classifies a Settings page into L1 / L2 / L3 so every level can own an
 * independent background AND an independent glass parameter set.
 *
 * REWRITTEN after the 2026-11-11 clean-device probe (old HonorBG module
 * disabled + zygote soft-restarted, user manually navigated to
 * 移动网络 -> 个人热点 -> 更多共享设置).
 *
 * The previous implementation used FRAGMENT BACK-STACK DEPTH. That is WRONG on
 * MagicOS. `dumpsys activity top` shows each SubSettings Activity hosts exactly
 * ONE fragment with mBackStackNesting=0 (DashboardSummary on L1,
 * NetShareTetherSettings on L3), so the depth is always 0 and EVERY page would
 * be misclassified as L1.
 *
 * The real navigation chain is an ACTIVITY STACK inside one task (task #996):
 *   Hist #0  com.android.settings/.HWSettings                     rootOfTask=true -> L1
 *   Hist #1  com.android.settings/.Settings$WirelessSettingsActivity              -> L2
 *   Hist #2  com.android.settings/com.hihonor.settingslib.SubSettings            -> L3
 *   Hist #3  com.android.settings/.Settings$TetherSettingsActivity               -> L3+
 *
 * Therefore the primary signal is the index of this Activity inside its own
 * task, tracked in-process: onCreate/onResume pushes (deduplicated), and every
 * pass prunes finished/destroyed entries. No hidden API, no dumpsys at runtime,
 * no MIUI class names. ROM-specific names only appear as FALLBACK rules.
 *
 * Signals, in priority order:
 *   1. task stack index (0 -> L1, 1 -> L2, >=2 -> L3)
 *   2. activity class-name rules (verified names, configurable)
 *   3. AOSP intent extras (:settings:show_fragment etc.)
 *   4. top blur container in the view tree (measured: L3 has
 *      HnGradientBlurTopContainer / HnGradientBlurBottomContainer; L1 does not)
 *   5. hosted fragment class name (DashboardSummary -> L1)
 */
public final class PageLevel {

    public enum Level { L1, L2, L3, UNKNOWN }

    /** Verified on AAK-AN00. Add more entries here after probing other pages. */
    private static final String[] L1_RULES = {
            "com.android.settings.HWSettings",
            "com.android.settings.Settings"
    };
    private static final String[] DEEP_RULES = {
            "com.hihonor.settingslib.SubSettings",
            "com.android.settings.SubSettings",
            ".Settings$"
    };
    private static final String[] FRAGMENT_L1_HINTS = {
            "DashboardSummary", "SettingsHomepage"
    };
    private static final List<String> FRAGMENT_KEYS = Arrays.asList(
            ":settings:show_fragment",
            ":settings:fragment",
            ":settings:fragment_args_key",
            "settings:fragment_args_key"
    );
    private static final String[] TOP_BLUR_HINTS = {
            "HnGradientBlurTopContainer", "HnGradientBlurBottomContainer",
            "HnBlurTopPattern", "HnBlurAppBar"
    };

    /** taskId -> ordered Activity stack, oldest first. */
    private static final Map<Integer, List<WeakReference<Activity>>> TASKS =
            new HashMap<Integer, List<WeakReference<Activity>>>();

    private PageLevel() {
    }

    /** Call from the Activity onCreate/onResume hook. Deduplicated. */
    public static void onActivityCreated(Activity a) {
        if (a == null) return;
        try {
            int task = Math.max(0, a.getTaskId());
            List<WeakReference<Activity>> q = TASKS.get(task);
            if (q == null) {
                q = new ArrayList<WeakReference<Activity>>();
                TASKS.put(task, q);
            }
            prune(q);
            for (WeakReference<Activity> ref : q) {
                if (ref.get() == a) return;   // onCreate AND onResume both fire
            }
            q.add(new WeakReference<Activity>(a));
        } catch (Throwable ignored) {
        }
    }

    private static void prune(List<WeakReference<Activity>> q) {
        Iterator<WeakReference<Activity>> it = q.iterator();
        while (it.hasNext()) {
            Activity a = it.next().get();
            if (a == null || a.isFinishing() || isDestroyed(a)) it.remove();
        }
    }

    private static boolean isDestroyed(Activity a) {
        try {
            return a.isDestroyed();
        } catch (Throwable t) {
            return false;
        }
    }

    private static int taskIndexOf(Activity a) {
        try {
            List<WeakReference<Activity>> q = TASKS.get(Math.max(0, a.getTaskId()));
            if (q == null) return -1;
            for (int i = 0; i < q.size(); i++) {
                if (q.get(i).get() == a) return i;
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    public static final class Result {
        public final Level level;
        public final int confidence;
        /** Index of this Activity in its task stack, -1 when unavailable. */
        public final int taskIndex;
        public final String activityName;
        public final List<String> signals;

        Result(Level level, int confidence, int taskIndex, String activityName,
               List<String> signals) {
            this.level = level;
            this.confidence = confidence;
            this.taskIndex = taskIndex;
            this.activityName = activityName;
            this.signals = signals;
        }

        public ConfigContract.Slot slotFor(String pkg) {
            if (pkg != null && !Scope.SETTINGS.equals(pkg)) {
                return ConfigContract.Slot.forPackage(pkg);
            }
            return ConfigContract.Slot.forLevel(level);
        }

        public String describe() {
            return "level=" + level + " conf=" + confidence
                    + " taskIndex=" + taskIndex + " act=" + activityName
                    + " signals=" + signals;
        }
    }

    public static Result detect(Activity a) {
        List<String> signals = new ArrayList<String>();
        String name = a == null ? "null" : a.getClass().getName();
        if (a == null) return new Result(Level.UNKNOWN, 0, -1, name, signals);

        onActivityCreated(a);
        int index = taskIndexOf(a);

        // --- primary signal: real Activity stack -----------------------------
        if (index >= 0) {
            signals.add("taskIndex=" + index);
        } else {
            signals.add("taskStackUnavailable");
        }

        // --- fallback / corroborating signals --------------------------------
        boolean looksL1 = false;
        for (String rule : L1_RULES) {
            if (name.equals(rule)) {
                looksL1 = true;
                signals.add("rule:L1=" + rule);
                break;
            }
        }
        boolean looksDeep = false;
        for (String rule : DEEP_RULES) {
            if (name.contains(rule)) {
                looksDeep = true;
                signals.add("rule:DEEP=" + rule);
                break;
            }
        }

        Bundle extras = intentExtras(a);
        boolean hasFragmentExtra = false;
        if (extras != null) {
            for (String k : FRAGMENT_KEYS) {
                if (extras.containsKey(k)) {
                    hasFragmentExtra = true;
                    signals.add("extra:" + k);
                    break;
                }
            }
        }

        String fragment = hostedFragment(a);
        // androidx.lifecycle.ReportFragment is attached to EVERY Activity and
        // carries no page-depth information. The first real-device run logged
        // fragment=androidx.lifecycle.ReportFragment and demoted the verified
        // L1 homepage (HWSettings) to L2 with "L1 overridden by page chrome".
        if (fragment != null
                && (fragment.startsWith("androidx.")
                        || fragment.contains("ReportFragment"))) {
            fragment = null;
        }
        boolean fragmentSaysL1 = false;
        if (fragment != null) {
            signals.add("fragment=" + fragment);
            for (String hint : FRAGMENT_L1_HINTS) {
                if (fragment.contains(hint)) {
                    fragmentSaysL1 = true;
                    break;
                }
            }
        }

        boolean topBlur = hasTopBlurContainer(a);
        if (topBlur) signals.add("topBlurContainer");

        // --- decision --------------------------------------------------------
        Level level;
        int confidence;
        if (index >= 0) {
            level = index == 0 ? Level.L1 : (index == 1 ? Level.L2 : Level.L3);
            confidence = 90;
            // A verified L1 rule overrides even a restored/odd stack.
            if (looksL1 && !looksDeep) {
                level = Level.L1;
                confidence = 95;
            }
            // The verified L1 homepage ALSO hosts HnGradientBlurTopContainer,
            // so chrome evidence must never demote a confirmed L1 activity.
            // Chrome is only used to promote an otherwise-ambiguous index-0
            // page (e.g. Settings$WifiSettingsActivity) to L2.
            //
            // 0.4.0-p44 ENTRY WHITE-CARD FIX. The first frame of a fresh
            // Activity reports taskIndex=0 because the task stack has not
            // settled, so Settings$BluetoothSettingsActivity started as L1.
            // That made scanAndBind() call suppressor.apply(false) for those
            // frames, handing the OEM self-drawn white cards back -- the
            // "刚进入蓝牙 L2 时可用设备那里白卡残留" the user reported.
            // A name containing ".Settings$" (or any verified DEEP rule) is
            // ALWAYS a sub page, never the homepage: classify it immediately
            // instead of waiting for topBlurContainer to appear.
            if (level == Level.L1 && !looksL1) {
                if (looksDeep) {
                    // A "Settings$XxxActivity" is ONE level-2 page even though
                    // it also hosts topBlurContainer / the fragment extra
                    // (Bluetooth does). Treating those as level 3 would pick
                    // the L3 background for an L2 page -- the three levels
                    // have independent wallpapers. Only the generic
                    // SubSettings host can be level 3.
                    level = name.contains(".Settings$")
                            ? Level.L2
                            : ((topBlur || hasFragmentExtra) ? Level.L3 : Level.L2);
                    confidence = 80;
                    signals.add("L1 overridden by deep rule");
                } else if (topBlur || (fragment != null && !fragmentSaysL1)) {
                    level = Level.L2;
                    confidence = 70;
                    signals.add("L1 overridden by page chrome");
                }
            }
        } else if (looksL1 || fragmentSaysL1) {
            level = Level.L1;
            confidence = 70;
        } else if (looksDeep) {
            level = topBlur || hasFragmentExtra ? Level.L3 : Level.L2;
            confidence = 55;
        } else {
            level = Level.UNKNOWN;
            confidence = 20;
        }
        return new Result(level, confidence, index, name, signals);
    }

    private static Bundle intentExtras(Activity a) {
        try {
            Intent i = a.getIntent();
            return i == null ? null : i.getExtras();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Optional, best-effort: reads the hosted fragment class name. */
    private static String hostedFragment(Activity a) {
        for (String getter : new String[]{"getFragmentManager", "getSupportFragmentManager"}) {
            try {
                Method m = a.getClass().getMethod(getter);
                Object fm = m.invoke(a);
                if (fm == null) continue;
                Method g = fm.getClass().getMethod("getFragments");
                Object list = g.invoke(fm);
                if (list instanceof List) {
                    List<?> l = (List<?>) list;
                    for (Object f : l) {
                        if (f != null) return f.getClass().getName();
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static boolean hasTopBlurContainer(Activity a) {
        try {
            View decor = a.getWindow().getDecorView();
            return contains(decor, 0);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean contains(View v, int depth) {
        if (v == null || depth > 18) return false;
        String n = v.getClass().getName();
        for (String hint : TOP_BLUR_HINTS) {
            if (n.contains(hint)) return true;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (contains(g.getChildAt(i), depth + 1)) return true;
            }
        }
        return false;
    }
}
