package com.magicglass;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reflection bridge to the REAL MagicOS transparency stack.
 *
 * IMPORTANT - this file was rewritten after the 2026-11-11 on-device probe on
 * HONOR AAK-AN00 / Android 17 (API 37) / 11.0.0.117(SP3C00E117R102P2).
 * The earlier version targeted the classes documented at
 * developer.honor.com (com.hihonor.uikit.transparency.api.HnVisualEffectLayout,
 * HnGlassEffect, HnMaterialType, ...). DEX parsing of framework.jar,
 * framework-magic.jar and all nine scoped APKs proved that NONE of those
 * classes exists in the ROM. They only exist inside the downloadable SDK aar,
 * which a normal LSPosed module does not get from the host process.
 *
 * FINAL CHOICE, made after the 2026-11-11 CLEAN on-device probe (old module
 * disabled + zygote soft-restarted):
 *
 *  HOST_UIKIT is now the PRIMARY path. Settings.apk ships the COMPLETE
 *  com.hihonor.uikit.transparency.* implementation (128 classes in classes4.dex):
 *  HnTransFrameLayout / HnTransLinearLayout / HnTransRelativeLayout,
 *  style.StyleType, effect.SceneType / ShapeType / ViewEffectType,
 *  adapters.HnMaterialAggregator, and the material repositories
 *  data.repo.highgrade.liquid.largewidget.LiquidLargeOnHighGeneral etc.
 *  Those repositories already implement liquid/frosted x high/mid/low grade x
 *  large/small widget x general/immersive, and HnTransFrameLayout overrides
 *  onConfigurationChanged() -- so the container follows the system
 *  transparency slider, material switch and dark mode BY ITSELF. That is the
 *  most literal reading of "system automatic", needs no hidden-API exemption,
 *  and is present in ALL NINE scoped APKs (verified by DEX scan).
 *  Verified signatures (dex_api.py, classes4.dex):
 *    <init>(Context), <init>(Context,AttributeSet), <init>(Context,AttributeSet,int),
 *    <init>(Context,AttributeSet,int,int)
 *    setViewStyleType(StyleType)
 *    setViewStyleWithScenes(StyleType,int)
 *    setViewStyleWithScenes(StyleType,int,int,Bundle)
 *    setCornerRadius(float), setCornerRadiusWithOutlineProvider(float[])
 *    setBlurType(int), setStatusBarAppearance(int)
 *    getViewStyle(), widgetSizeType(), onConfigurationChanged(Configuration)
 *  DEX DISASSEMBLY (2026-11-11, probe/dex_deep.py) corrected two earlier
 *  assumptions that would otherwise have made the whole P1 path a no-op:
 *
 *   - Only the enum FIELD names are obfuscated (a,b,c,d / a..o). The String
 *     passed to Enum.<init> is NOT: StyleType has exactly four constants
 *       "NORMAL"(mode 0), "TRANSPARENCY"(mode 1),
 *       "FROSTED_GLASS_TRANSPARENCY"(mode 2), "LIQUID_GLASS_TRANSPARENT"(mode 4)
 *     and SceneType has "General"(32), "BigWidget"(256), "SmallWidget"(512),
 *     "FollowUIMODE"(2048), "Immersive"(16), ... whose `mode` is a BIT MASK.
 *     Resolve constants by Enum.name(); never index values() blindly.
 *
 *   - `new HnTransFrameLayout(Context)` passes a NULL AttributeSet. The
 *     delegate's <init> then skips obtainStyledAttributes() entirely, leaving
 *     style = NORMAL and sceneMask = 0. Lwo/f.e(StyleType) returns false for
 *     NORMAL, so HnTransEffectDelegate.N() returns before building any
 *     AbsViewStyle. onConfigurationChanged() forwards only to a non-null
 *     style. In other words: an untouched container draws NO GLASS and does
 *     NOT follow the system. "follow" therefore has to INJECT the
 *     TRANSPARENCY style, not leave the container alone.
 *
 *  Empty containers are fine: dispatchDraw() unconditionally calls
 *  delegate.i(canvas), and the constructor calls setWillNotDraw(false).
 *  They do need a non-zero width/height (delegate.j()/t() gate on it).
 *
 *  BOOT_BACKDROP (SECONDARY, for scopes that lack the uikit or as a fallback)
 *    framework.jar      android.view.View.setBackdropRenderEffect(RenderEffect)
 *                       android.graphics.RenderNode.setBackdropRenderEffect(RenderEffect)
 *                       android.graphics.material.Material / LiquidGlassMaterial /
 *                       FrostGlassMaterial / MaterialOpt / atomiceffect.*
 *    framework-magic.jar com.hihonor.android.vieweffect.HnVisualEffectEx
 *                       .setViewBackdropRenderEffect(View, RenderEffect)
 *                       com.hihonor.android.graphics.material.HnMaterialOpt
 *    The system already implements the refraction shader. GlassParam exposes
 *    setRefraction(F), setDispersion(F), setThickness(F), setDepth(F),
 *    setHighQualitySdfEnabled(Z); so the Backdrop/LiquidTab AGSL is only a
 *    fallback, not the main renderer. This is what "system automatic" means:
 *    Configuration carries transparent_mode / transparent_degree /
 *    transparent_texture, set by the system transparency slider.
 *
 *  SCOPE AUDIT (2026-11-12, probe/scope_audit.py, class_def level):
 *  all 8 offline scope APKs define HnTransFrameLayout; 6 of them also define
 *  the plain StyleType/SceneType classes. systemmanager and wallet ship a
 *  differently obfuscated uikit build where StyleType is Lqe8; / Ls29; and
 *  SceneType is likewise renamed -- but Enum.name() is STILL plaintext in
 *  <clinit>. Hard-coding CLS_STYLE_TYPE therefore used to force those two
 *  scopes into BOOT_BACKDROP for no good reason. resolveEnums() now discovers
 *  the enum classes from the container's own method parameter types, so all
 *  8 scopes can use HOST_UIKIT. A scope with NO resolvable style enum still
 *  falls back to BOOT_BACKDROP (a container without a style is a no-op).
 *
 *  DEVELOPER_SDK (optional, only if we embed the official aar in the module)
 *    com.hihonor.uikit.transparency.api.HnVisualEffectLayout / HnGlassEffect
 *
 * There are ZERO references to MIUI's setMiGlass / setMiView* APIs. Those came
 * from the upstream HyperOS base APK and must never be used on MagicOS.
 *
 * Remaining on-device work: verify the dynamically resolved enums on the
 * systemmanager / wallet processes (their page View trees may not match the
 * Settings scanner), measure frame rate with a video backdrop, and confirm
 * dark-mode / transparency-slider live changes. The BOOT_BACKDROP path
 * (hidden-API exemption + RenderEffect chain + GlassParam numeric ranges)
 * stays a fallback only.
 */
public final class MaterialBridge {

    public enum Mode { NONE, HOST_UIKIT, APP_CONTAINER, BOOT_BACKDROP, DEVELOPER_SDK }

    // Boot classpath - verified present.
    public static final String CLS_RENDER_EFFECT = "android.graphics.RenderEffect";
    public static final String CLS_MATERIAL = "android.graphics.material.Material";
    public static final String CLS_LIQUID = "android.graphics.material.LiquidGlassMaterial";
    public static final String CLS_FROST = "android.graphics.material.FrostGlassMaterial";
    public static final String CLS_MATERIAL_OPT = "android.graphics.material.MaterialOpt";
    public static final String CLS_GLASS_PARAM = "android.graphics.material.atomiceffect.GlassParam";
    public static final String CLS_BLUR_PARAM = "android.graphics.material.atomiceffect.BlurParam";
    public static final String CLS_SHAPE_PARAM = "android.graphics.material.atomiceffect.ShapeParam";
    public static final String CLS_HN_VISUAL_EFFECT_EX =
            "com.hihonor.android.vieweffect.HnVisualEffectEx";
    public static final String CLS_HN_MATERIAL_OPT =
            "com.hihonor.android.graphics.material.HnMaterialOpt";

    // App-private uikit containers (host ClassLoader). Verified present in ALL
    // nine scoped APKs; HnTransFrameLayout is the chosen primary container.
    public static final String CLS_LAYOUT_FRAME =
            "com.hihonor.uikit.transparency.widget.HnTransFrameLayout";
    public static final String CLS_LAYOUT =
            "com.hihonor.uikit.transparency.widget.HnTransRelativeLayout";
    public static final String CLS_LAYOUT_LINEAR =
            "com.hihonor.uikit.transparency.widget.HnTransLinearLayout";
    public static final String CLS_STYLE_TYPE =
            "com.hihonor.uikit.transparency.style.StyleType";
    public static final String CLS_SCENE_TYPE =
            "com.hihonor.uikit.transparency.effect.SceneType";
    public static final String CLS_AGGREGATOR =
            "com.hihonor.uikit.transparency.adapters.HnMaterialAggregator";

    // Official SDK aar - expected MISSING in the ROM.
    public static final String CLS_SDK_LAYOUT =
            "com.hihonor.uikit.transparency.api.HnVisualEffectLayout";
    public static final String CLS_SDK_EFFECT =
            "com.hihonor.uikit.transparency.api.HnVisualEffect";
    public static final String CLS_SDK_GLASS =
            "com.hihonor.uikit.transparency.api.HnGlassEffect";

    private static final String[] BOOT_CLASSES = {
            CLS_RENDER_EFFECT, CLS_MATERIAL, CLS_MATERIAL_OPT, CLS_LIQUID, CLS_FROST,
            CLS_GLASS_PARAM, CLS_BLUR_PARAM, CLS_SHAPE_PARAM,
            CLS_HN_VISUAL_EFFECT_EX, CLS_HN_MATERIAL_OPT
    };
    private static final String[] APP_CLASSES = {
            CLS_LAYOUT_FRAME, CLS_LAYOUT, CLS_LAYOUT_LINEAR,
            CLS_STYLE_TYPE, CLS_SCENE_TYPE, CLS_AGGREGATOR
    };
    private static final String[] SDK_CLASSES = {
            CLS_SDK_LAYOUT, CLS_SDK_EFFECT, CLS_SDK_GLASS
    };

    private static final Map<String, Method> METHOD_CACHE = new HashMap<>();
    private static volatile ClassLoader sLoader;
    private static volatile Mode sMode = Mode.NONE;
    // Resolved once per process. Some scope APKs (systemmanager, wallet) ship a
    // DIFFERENTLY OBFUSCATED uikit build: StyleType is Lqe8; / Ls29; rather than
    // com.hihonor.uikit.transparency.style.StyleType. Enum.name() is still the
    // plaintext NORMAL / TRANSPARENCY / ... in every build, so discovering the
    // class from setViewStyleType()'s parameter type is safe and lets those
    // scopes use HOST_UIKIT instead of degrading to BOOT_BACKDROP.
    private static volatile Class<?> sStyleType;
    /** One-shot log so the chosen material can be verified from lspd. */
    private static volatile boolean sStyleLogged;
    /** One-shot log of the resolved container style + strategy tier. */
    private static volatile boolean sContainerLogged;
    private static volatile Class<?> sSceneType;
    private static volatile boolean sEnumsResolved;

    private MaterialBridge() {
    }

    public static final class Availability {
        public boolean usable;
        public Mode mode = Mode.NONE;
        public final List<String> found = new ArrayList<>();
        public final List<String> missing = new ArrayList<>();
        public final List<String> signatures = new ArrayList<>();
        public final List<String> backdropMethods = new ArrayList<>();
        /** Actual resolved class names (possibly obfuscated), for diagnostics. */
        public String styleTypeClass = "?";
        public String sceneTypeClass = "?";

        Availability() {
            usable = false;
        }

        public String describe() {
            return "usable=" + usable + " mode=" + mode
                    + " found=" + found.size() + " missing=" + missing.size()
                    + " style=" + styleTypeClass + " scene=" + sceneTypeClass
                    + " backdrop=" + backdropMethods;
        }
    }

    /** Call once per process with the HOST class loader (from an Activity). */
    public static Availability probe(ClassLoader loader) {
        sLoader = loader;
        Availability a = new Availability();
        METHOD_CACHE.clear();
        sStyleType = null;
        sSceneType = null;
        sEnumsResolved = false;

        boolean boot = scan(BOOT_CLASSES, a);
        boolean app = scan(APP_CLASSES, a);
        boolean sdk = scan(SDK_CLASSES, a);

        // The decisive capability is a way to feed backdrop pixels to a card.
        Class<?> re = load(CLS_RENDER_EFFECT);
        Method direct = re == null ? null
                : findMethod(View.class, "setBackdropRenderEffect", re);
        if (direct != null) {
            a.backdropMethods.add("View.setBackdropRenderEffect(RenderEffect)");
        }
        Class<?> ex = load(CLS_HN_VISUAL_EFFECT_EX);
        Method viaEx = (ex == null || re == null) ? null
                : findMethod(ex, "setViewBackdropRenderEffect", View.class, re);
        if (viaEx != null) {
            a.backdropMethods.add("HnVisualEffectEx.setViewBackdropRenderEffect(View,RenderEffect)");
        }

        // FINAL CHOICE: the host's own uikit container wins. It follows the
        // system transparency slider/material switch by itself, needs no
        // hidden-API exemption, and exists in all nine scopes. The StyleType
        // class NAME is obfuscated in two scopes, so resolve it by signature
        // discovery rather than by a hard-coded name (see resolveEnums()).
        boolean frame = load(CLS_LAYOUT_FRAME) != null
                || load(CLS_LAYOUT) != null || load(CLS_LAYOUT_LINEAR) != null;
        resolveEnums();
        boolean style = sStyleType != null;
        a.styleTypeClass = sStyleType == null ? "?"
                : sStyleType.getName();
        a.sceneTypeClass = sSceneType == null ? "?"
                : sSceneType.getName();
        if (frame && style) {
            a.mode = Mode.HOST_UIKIT;
        } else if (sdk && load(CLS_SDK_LAYOUT) != null && load(CLS_SDK_GLASS) != null) {
            a.mode = Mode.DEVELOPER_SDK;
        } else if (!a.backdropMethods.isEmpty() && boot) {
            // A container with no resolvable StyleType can never pass
            // Lwo/f.e(StyleType), so no AbsViewStyle is built and the container
            // would be a transparent no-op overlay. Prefer the framework
            // backdrop path for such a scope.
            a.mode = Mode.BOOT_BACKDROP;
        } else if (frame || app) {
            a.mode = Mode.APP_CONTAINER;
        } else {
            a.mode = Mode.NONE;
        }
        a.usable = a.mode != Mode.NONE;
        sMode = a.mode;
        return a;
    }

    private static boolean scan(String[] names, Availability a) {
        int hits = 0;
        for (String name : names) {
            try {
                Class<?> c = Class.forName(name, false, sLoader);
                hits++;
                a.found.add(name);
                for (Method m : c.getDeclaredMethods()) {
                    if (!m.isSynthetic() && !m.isBridge()) {
                        a.signatures.add(c.getSimpleName() + "." + m.getName()
                                + Arrays.toString(m.getParameterTypes()));
                    }
                }
            } catch (Throwable t) {
                a.missing.add(name);
            }
        }
        return hits > 0;
    }

    public static boolean available() {
        return sLoader != null && sMode != Mode.NONE;
    }

    public static Mode mode() {
        return sMode;
    }

    /**
     * Build the per-level system material. On BOOT_BACKDROP this returns a
     * LiquidGlassMaterial / FrostGlassMaterial configured with the snapshot's
     * refraction, dispersion, thickness and blur values. -1 keeps the system
     * default so global transparency still drives the look.
     */
    public static Object createGlass(ConfigContract.Snapshot cfg) {
        if (sMode == Mode.DEVELOPER_SDK) return createDeveloperGlass(cfg);
        if (sMode != Mode.BOOT_BACKDROP && sMode != Mode.APP_CONTAINER) return null;

        String material = cfg.asString(ConfigContract.Param.GLASS_MATERIAL);
        boolean frosted = "frosted".equals(material);
        Class<?> cls = load(frosted ? CLS_FROST : CLS_LIQUID);
        Object mat = newInstance(cls);
        if (mat == null) return null;

        if (!frosted) {
            Class<?> gpClass = load(CLS_GLASS_PARAM);
            Object gp = newInstance(gpClass);
            if (gp != null) {
                setFloatIfConfigured(gp, gpClass, "setRefraction",
                        cfg.asFloat(ConfigContract.Param.GLASS_REFRACTION));
                setFloatIfConfigured(gp, gpClass, "setDispersion",
                        cfg.asFloat(ConfigContract.Param.GLASS_DISPERSION));
                setFloatIfConfigured(gp, gpClass, "setThickness",
                        cfg.asFloat(ConfigContract.Param.GLASS_THICKNESS));
                call(gp, gpClass, "setHighQualitySdfEnabled", boolean.class,
                        cfg.asBool(ConfigContract.Param.GLASS_HIGH_QUALITY_SDF));
                call(mat, cls, "setGlassParam", gpClass, gp);
            }
        }

        Class<?> bpClass = load(CLS_BLUR_PARAM);
        Object bp = newInstance(bpClass);
        if (bp != null) {
            float radius = cfg.asFloat(ConfigContract.Param.GLASS_BLUR_RADIUS);
            if (radius >= 0f) {
                call(bp, bpClass, "setRadius", float.class, radius);
            }
            call(mat, cls, "setBlurParam", bpClass, bp);
        }
        return mat;
    }

    private static Object createDeveloperGlass(ConfigContract.Snapshot cfg) {
        Class<?> glass = load(CLS_SDK_GLASS);
        if (glass == null) return null;
        try {
            Object effect = glass.getDeclaredConstructor().newInstance();
            Class<?> base = load(CLS_SDK_EFFECT);
            if (base != null) {
                call(effect, base, "setFollowSystemSettings", boolean.class,
                        cfg.asBool(ConfigContract.Param.GLASS_FOLLOW_SYSTEM));
                call(effect, base, "setAdaptToBackground", boolean.class,
                        cfg.asBool(ConfigContract.Param.GLASS_ADAPT_BACKGROUND));
            }
            call(effect, glass, "setTransparencyLevel", float.class,
                    cfg.asFloat(ConfigContract.Param.GLASS_TRANSPARENCY));
            call(effect, glass, "setInteractive", boolean.class,
                    cfg.asBool(ConfigContract.Param.GLASS_INTERACTIVE));
            return effect;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Feed the system backdrop into one card's render effect. This is the
     * replacement for the Compose GraphicsLayer pixel pipeline in Backdrop and
     * for LiquidTab's manual RenderNode capture: MagicOS does it in the
     * framework. No reparenting of RecyclerView children is required.
     * P1 builds {@code renderEffect} (blur + optional AGSL refraction chain).
     */
    public static boolean applyBackdrop(View card, Object renderEffect) {
        if (card == null || renderEffect == null) return false;
        Class<?> re = load(CLS_RENDER_EFFECT);
        if (re == null) return false;

        Method direct = findMethod(card.getClass(), "setBackdropRenderEffect", re);
        if (direct != null) {
            try {
                direct.setAccessible(true);
                direct.invoke(card, renderEffect);
                return true;
            } catch (Throwable ignored) {
            }
        }

        Class<?> ex = load(CLS_HN_VISUAL_EFFECT_EX);
        if (ex == null) return false;
        Method m = findMethod(ex, "setViewBackdropRenderEffect", View.class, re);
        if (m == null) return false;
        try {
            m.setAccessible(true);
            Object target = Modifier.isStatic(m.getModifiers()) ? null : newInstance(ex);
            if (!Modifier.isStatic(m.getModifiers()) && target == null) return false;
            m.invoke(target, card, renderEffect);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Create one transparent container for one card block. Returns null in
     * BOOT_BACKDROP mode on purpose: that path decorates the existing card View
     * in place and needs no overlay container at all.
     */
    public static View createLayout(Context ctx, ConfigContract.Snapshot cfg,
                                    Object glassEffect) {
        if (sMode == Mode.BOOT_BACKDROP || sMode == Mode.NONE) return null;
        // 0.4.0-p5: self-drawn liquid glass. It does not need the host uikit at
        // all -- the backdrop bitmap is ours -- so it also works in scopes
        // whose uikit build is too obfuscated to resolve StyleType.
        if (isSelfEngine(cfg)) {
            try {
                return new LiquidGlassView(ctx);
            } catch (Throwable ignored) {
            }
        }
        String name = sMode == Mode.DEVELOPER_SDK ? CLS_SDK_LAYOUT : CLS_LAYOUT_FRAME;
        Class<?> cls = load(name);
        if (cls == null && CLS_LAYOUT_FRAME.equals(name)) cls = load(CLS_LAYOUT);
        if (cls == null || !View.class.isAssignableFrom(cls)) return null;
        try {
            View layout;
            try {
                Constructor<?> c = cls.getConstructor(Context.class);
                layout = (View) c.newInstance(ctx);
            } catch (Throwable t) {
                Constructor<?> c = cls.getConstructor(Context.class,
                        android.util.AttributeSet.class);
                layout = (View) c.newInstance(ctx, null);
            }
            if (sMode == Mode.DEVELOPER_SDK) {
                call(layout, cls, "setVisualEffect", load(CLS_SDK_EFFECT), glassEffect);
                View placeholder = new View(ctx);
                placeholder.setBackgroundColor(0);
                ((ViewGroup) layout).addView(placeholder,
                        new FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT));
            } else {
                // HOST_UIKIT / APP_CONTAINER. Order matters:
                //  1) the radius once (setCornerRadius -> delegate.J(F,false)
                //     also writes the delegate's draw flag, so never repeat it
                //     from the per-frame alignment path);
                //  2) the style LAST -- this builds the AbsViewStyle, which is
                //     what dispatchDraw() needs and what makes
                //     onConfigurationChanged() follow the system material,
                //     transparency slider and dark mode.
                call(layout, cls, "setCornerRadius", float.class, radiusPx(ctx, cfg));
                boolean styled = applyHostStyle(layout, cls, cfg);
                if (!styled || !sContainerLogged) {
                    sContainerLogged = true;
                    GlassEntry.say("container: styled=" + styled
                            + " " + describeViewStyle(layout));
                }
                if (!styled) {
                    // APP_CONTAINER scopes without StyleType: still a valid
                    // FrameLayout, just without the system material. The
                    // BOOT_BACKDROP fallback in RenderController can cover it.
                    call(layout, cls, "setBlurType", int.class, 0);
                }
                // No child views on purpose: dispatchDraw() is unconditional,
                // and delegate.j()/t() only require width>0 && height>0.
                layout.invalidate();
            }
            return layout;
        } catch (Throwable t) {
            return null;
        }
    }

    // Semantic Enum.name() values, read verbatim from StyleType.<clinit> in
    // Settings.apk classes4.dex. The DEX field names are a/b/c/d but the
    // String passed to Enum.<init> is preserved.
    private static final String STYLE_NORMAL = "NORMAL";
    private static final String STYLE_FOLLOW = "TRANSPARENCY";
    private static final String STYLE_LIQUID = "LIQUID_GLASS_TRANSPARENT";
    private static final String STYLE_FROSTED = "FROSTED_GLASS_TRANSPARENCY";

    // SceneType.mode is a bit mask. The numeric values are read from the enum
    // at runtime (enumMode()), these are only the NAMES of the bits we want.
    private static final String[] SCENE_GENERAL = {
            "General", "BigWidget", "FollowUIMODE"};
    private static final String[] SCENE_IMMERSIVE = {
            "Immersive", "BigWidget", "FollowUIMODE"};

    /** Dump StyleType constants as "NAME(mode=N, ordinal=I)" for the log. */
    public static List<String> describeStyleTypes() {
        return describeEnum(styleConstants());
    }

    /** Dump SceneType constants (the bit mask sources) for the log. */
    public static List<String> describeSceneTypes() {
        return describeEnum(sceneConstants());
    }

    /**
     * Resolve StyleType / SceneType for THIS host ClassLoader.
     *
     * Six of the eight offline scope APKs keep the plain class names. Two of
     * them obfuscate the enum CLASS (systemmanager = Lqe8;, wallet = Ls29;)
     * but preserve Enum.name() -- verified in <clinit>: NORMAL, TRANSPARENCY,
     * FROSTED_GLASS_TRANSPARENCY, LIQUID_GLASS_TRANSPARENT. Discovering the
     * class from the host container's own method parameter types is therefore
     * reliable and keeps those scopes on the native HOST_UIKIT path instead
     * of the BOOT_BACKDROP downgrade.
     */
    private static void resolveEnums() {
        if (sEnumsResolved) return;
        sEnumsResolved = true;
        Class<?> style = load(CLS_STYLE_TYPE);
        Class<?> scene = load(CLS_SCENE_TYPE);
        if (style == null || scene == null) {
            String[] hosts = {CLS_LAYOUT_FRAME, CLS_LAYOUT,
                    CLS_LAYOUT_LINEAR, CLS_AGGREGATOR};
            for (String h : hosts) {
                Class<?> c = load(h);
                if (c == null) continue;
                Method[] methods;
                try {
                    methods = c.getDeclaredMethods();
                } catch (Throwable t) {
                    continue;
                }
                for (Method m : methods) {
                    Class<?>[] params;
                    try {
                        params = m.getParameterTypes();
                    } catch (Throwable t) {
                        continue;
                    }
                    for (Class<?> p : params) {
                        if (p == null || !p.isEnum()) continue;
                        Object[] vals = p.getEnumConstants();
                        if (vals == null || vals.length == 0) continue;
                        boolean transparency = false, normal = false, sceneBit = false;
                        for (Object v : vals) {
                            String n = enumName(v);
                            if (STYLE_FOLLOW.equals(n)) transparency = true;
                            else if (STYLE_NORMAL.equals(n)) normal = true;
                            else if ("General".equals(n) || "BigWidget".equals(n)
                                    || "Immersive".equals(n)) sceneBit = true;
                        }
                        if (style == null && transparency && normal) style = p;
                        if (scene == null && sceneBit && !transparency) scene = p;
                    }
                }
            }
        }
        sStyleType = style;
        sSceneType = scene;
    }

    private static Object[] styleConstants() {
        resolveEnums();
        return sStyleType == null ? null : sStyleType.getEnumConstants();
    }

    private static Object[] sceneConstants() {
        resolveEnums();
        return sSceneType == null ? null : sSceneType.getEnumConstants();
    }

    private static List<String> describeEnum(Object[] vals) {
        List<String> out = new ArrayList<String>();
        if (vals == null) return out;
        for (Object v : vals) {
            int ordinal = -1;
            try {
                ordinal = ((Enum<?>) v).ordinal();
            } catch (Throwable ignored) {
            }
            out.add(enumName(v) + "(mode=" + enumMode(v)
                    + ", ordinal=" + ordinal + ")");
        }
        return out;
    }

    /** Enum.name(), never toString(): a subclass may override toString(). */
    private static String enumName(Object v) {
        if (v == null) return "";
        try {
            return ((Enum<?>) v).name();
        } catch (Throwable t) {
            return String.valueOf(v);
        }
    }

    /** StyleType and SceneType both expose a public/private int "mode" field. */
    private static int enumMode(Object v) {
        if (v == null) return 0;
        try {
            java.lang.reflect.Field f = v.getClass().getDeclaredField("mode");
            f.setAccessible(true);
            return f.getInt(v);
        } catch (Throwable t) {
            try {
                return ((Enum<?>) v).ordinal();
            } catch (Throwable ignored) {
                return 0;
            }
        }
    }

    private static Object findEnum(Object[] vals, String name) {
        if (vals == null) return null;
        for (Object v : vals) {
            if (name.equals(enumName(v))) return v;
        }
        return null;
    }

    /**
     * SceneType.mode is a BIT MASK, so falling back to ordinal() would inject
     * a wrong mask. Only accept a real power-of-two int field; when the field
     * name is obfuscated (a/b/c) scan the int fields for one. -1 means
     * "no scene mask available" and the caller will use setViewStyleType().
     */
    private static int enumModeStrict(Object v) {
        if (v == null) return -1;
        try {
            java.lang.reflect.Field f = v.getClass().getDeclaredField("mode");
            f.setAccessible(true);
            int value = f.getInt(v);
            return value > 0 && (value & (value - 1)) == 0 ? value : -1;
        } catch (Throwable ignored) {
        }
        try {
            for (java.lang.reflect.Field f : v.getClass().getDeclaredFields()) {
                if (f.getType() != int.class || f.isSynthetic()) continue;
                f.setAccessible(true);
                int value = f.getInt(v);
                if (value > 0 && (value & (value - 1)) == 0) return value;
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    /**
     * Pick the StyleType for one level. "follow" must map to TRANSPARENCY:
     * leaving the container untouched means NORMAL, which Lwo/f.e() rejects,
     * so no AbsViewStyle is built, nothing is drawn and the system slider is
     * ignored. Fall back to ANY non-NORMAL constant if the ROM ever changes
     * the names.
     */
    private static Object resolveStyle(String wanted) {
        // "follow" MUST mean "whatever the system is showing", not a fixed
        // constant. It used to map to TRANSPARENCY, which is neither the
        // frosted nor the liquid material -- on this ROM that reads as the
        // frosted look, even though Settings.System.transparency_material
        // was already 2 (liquid glass). The host owns the authority:
        //
        //   StyleType.c():  int m = Settings.System.transparency_material
        //                   1 -> FROSTED_GLASS_TRANSPARENCY
        //                   2 -> LIQUID_GLASS_TRANSPARENT
        //                   else -> FROSTED
        //
        // So ask the host instead of guessing, and the glass becomes literally
        // identical to the system's own material (including when the user
        // switches 通透/磨砂 in Settings).
        if (!"liquid".equals(wanted) && !"frosted".equals(wanted)) {
            Object host = hostStyle();
            if (host != null) return host;
        }
        String name = STYLE_FOLLOW;
        if ("liquid".equals(wanted)) name = STYLE_LIQUID;
        else if ("frosted".equals(wanted)) name = STYLE_FROSTED;
        Object style = findEnum(styleConstants(), name);
        if (style != null) return style;
        style = findEnum(styleConstants(), STYLE_FOLLOW);
        if (style != null) return style;
        Object[] vals = styleConstants();
        if (vals != null) {
            for (Object v : vals) {
                if (!STYLE_NORMAL.equals(enumName(v))) return v;
            }
        }
        return null;
    }

    /**
     * Ask the host for its own current StyleType (StyleType.c()). Returns null
     * when this uikit build does not expose it (the differently obfuscated
     * builds in systemmanager / wallet), in which case the caller falls back to
     * the fixed constants.
     */
    /**
     * Report what the container ACTUALLY resolved to. StyleType alone is not
     * proof: strategy/b.b() picks HighGrade / MidGrade / LowGrade from the
     * device tier, and a lower tier may render differently no matter which
     * StyleType we pass. This line settles it from the log alone.
     */
    private static String describeViewStyle(View layout) {
        try {
            java.lang.reflect.Method m = layout.getClass().getMethod("getViewStyle");
            m.setAccessible(true);
            Object style = m.invoke(layout);
            if (style == null) return "style=null";
            String type = "?";
            try {
                java.lang.reflect.Method g = style.getClass().getMethod("getStyleType");
                g.setAccessible(true);
                type = enumName(g.invoke(style));
            } catch (Throwable ignored) {
            }
            String strategy = "?";
            try {
                java.lang.reflect.Method g = style.getClass().getMethod("getEffectStrategy");
                g.setAccessible(true);
                Object st = g.invoke(style);
                strategy = st == null ? "null" : st.getClass().getSimpleName();
            } catch (Throwable ignored) {
            }
            return "style=" + type + " strategy=" + strategy;
        } catch (Throwable t) {
            return "style=? (" + t.getClass().getSimpleName() + ")";
        }
    }


    private static Object hostStyle() {
        Class<?> c = sStyleType;
        if (c == null) return null;
        try {
            java.lang.reflect.Method m = c.getDeclaredMethod("c");
            m.setAccessible(true);
            Object v = m.invoke(null);
            if (v != null && !STYLE_NORMAL.equals(enumName(v))) return v;
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** -1 when SceneType is unavailable (caller then omits the scene mask). */
    private static int sceneMask(String wanted) {
        String[] names = "immersive".equals(wanted) ? SCENE_IMMERSIVE : SCENE_GENERAL;
        int mask = 0;
        int found = 0;
        for (String n : names) {
            Object v = findEnum(sceneConstants(), n);
            if (v != null) {
                int bit = enumModeStrict(v);
                if (bit > 0) {
                    mask |= bit;
                    found++;
                }
            }
        }
        return found == 0 ? -1 : mask;
    }

    /**
     * Inject the style into a freshly created host container. This is what
     * turns a no-op NORMAL view into a system-driven glass view.
     * Tries the 4-arg scene overload first (so a large grouped card gets the
     * BigWidget material table), then the 3-arg overload, then the plain
     * setViewStyleType. Returns true when one of them was actually invoked.
     */
    private static boolean applyHostStyle(View layout, Class<?> cls,
                                          ConfigContract.Snapshot cfg) {
        resolveEnums();
        Class<?> styleCls = sStyleType;
        if (styleCls == null) return false;
        String material = cfg == null ? "follow"
                : cfg.asString(ConfigContract.Param.GLASS_MATERIAL);
        String scene = cfg == null ? "follow"
                : cfg.asString(ConfigContract.Param.GLASS_SCENE);
        Object style = resolveStyle(material);
        if (style == null) return false;
        if (!sStyleLogged) {
            sStyleLogged = true;
            GlassEntry.say("glass style: chosen=" + enumName(style)
                    + " wanted=" + material
                    + " hostFollow=" + (hostStyle() != null));
        }
        int mask = sceneMask(scene);

        if (mask >= 0 && invokeAny(layout, cls, "setViewStyleWithScenes",
                new Class<?>[]{styleCls, int.class, int.class,
                        android.os.Bundle.class},
                style, mask, 0, null)) {
            return true;
        }
        if (invokeAny(layout, cls, "setViewStyleWithScenes",
                new Class<?>[]{styleCls, int.class},
                style, mask >= 0 ? mask : 0)) {
            return true;
        }
        return invokeAny(layout, cls, "setViewStyleType",
                new Class<?>[]{styleCls}, style);
    }

    /**
     * 0.4.0-p4 TRANSPARENCY, ACTUALLY WIRED.
     *
     * The host material's own opacity is fixed by StyleType + the device
     * strategy, and we already proved we get the clearest one
     * (LIQUID_GLASS_TRANSPARENT / HighGradeStrategy). The remaining lever we
     * own is the container itself: it has NO child views, so its alpha is
     * literally "how much glass is painted". Lowering it lets the background
     * show through instead of a grey veil -- which is what the user means by
     * 通透 rather than 磨砂.
     *
     *   glass.transparency = -1  -> 0.80  (default: clearly clearer than 1.0)
     *                       =  0  -> 0.45  (very see-through)
     *                       = 100 -> 1.00  (full material, least clear)
     *
     * Setting alpha in pre-draw does not re-layout anything, and the 0.02
     * threshold means a settled frame performs a single getAlpha().
     */
    private static void applyTransparency(View layout, ConfigContract.Snapshot cfg) {
        float t = cfg == null ? -1f
                : cfg.asFloat(ConfigContract.Param.GLASS_TRANSPARENCY);
        float target = t < 0f ? 0.80f : (0.45f + 0.55f * (t / 100f));
        if (target > 1f) target = 1f;
        if (target < 0.30f) target = 0.30f;
        try {
            if (Math.abs(layout.getAlpha() - target) > 0.02f) {
                layout.setAlpha(target);
            }
        } catch (Throwable ignored) {
        }
    }

    /** engine = "liquid" switches to the self-drawn glass. */
    static boolean isSelfEngine(ConfigContract.Snapshot cfg) {
        return cfg != null
                && "liquid".equals(cfg.asString(ConfigContract.Param.GLASS_ENGINE));
    }

    /**
     * -1 means "use the engine default". Anything >= 0 is the user's slider
     * value (0..100), multiplied into the engine's own unit.
     */
    private static float param(ConfigContract.Snapshot cfg, ConfigContract.Param p,
                               float fallback, float scale) {
        float v = cfg == null ? -1f : cfg.asFloat(p);
        return v < 0f ? fallback : v * scale;
    }

    /** Tint alpha: the "通透度" slider. 0 = clearest, 100 = most opaque. */
    private static float tintOf(ConfigContract.Snapshot cfg) {
        float v = cfg == null ? -1f : cfg.asFloat(ConfigContract.Param.GLASS_TRANSPARENCY);
        // 0.4.0-p12: EXACTLY the reference project's ratio.
        //
        // LiquidTab: surfaceContainerColor() = Color.argb(lightAlpha*255,
        // 255,255,255) -- i.e. the SAME 0.40 that also drives the edge
        // highlight. My previous 0.05 read that constant as edge-only and made
        // the glass a bare window ("太透").
        if (v < 0f) return 0.40f;
        return 0.10f + (v / 100f) * 0.60f;
    }

    // ---- 0.5.0-p175 inner fill helpers ------------------------------------
    /** #RRGGBB / #AARRGGBB -> ARGB int. 0 = not set (no-op on L1 baseline). */
    static int fillColorOf(ConfigContract.Snapshot cfg) {
        if (cfg == null) return 0;
        String s = cfg.asString(ConfigContract.Param.GLASS_FILL_COLOR);
        if (s == null) return 0;
        s = s.trim();
        if (s.length() == 0) return 0;
        try {
            if (s.charAt(0) != '#') s = "#" + s;
            long v = android.graphics.Color.parseColor(s);
            if (s.length() == 7) v |= 0xFF000000L;  // #RRGGBB -> opaque
            return (int) v;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** -1 = 1f (use the colour's own alpha); else 0..1 slider value. */
    static float fillAlphaOf(ConfigContract.Snapshot cfg) {
        return alphaParam(cfg, ConfigContract.Param.GLASS_FILL_ALPHA);
    }

    static float fillImageAlphaOf(ConfigContract.Snapshot cfg) {
        return alphaParam(cfg, ConfigContract.Param.GLASS_FILL_IMAGE_ALPHA);
    }

    private static float alphaParam(ConfigContract.Snapshot cfg, ConfigContract.Param p) {
        float v = cfg == null ? -1f : cfg.asFloat(p);
        if (v < 0f) return 1f;
        return Math.max(0f, Math.min(1f, v));
    }

    static boolean fillFitOf(ConfigContract.Snapshot cfg) {
        if (cfg == null) return true;
        String s = cfg.asString(ConfigContract.Param.GLASS_FILL_IMAGE_FIT);
        return s == null || !"fit".equalsIgnoreCase(s.trim());
    }

    private static Bitmap fillBitmapOf(Context ctx, ConfigContract.Snapshot cfg) {
        if (ctx == null || cfg == null) return null;
        String name = cfg.asString(ConfigContract.Param.GLASS_FILL_IMAGE);
        int maxDp = 512;
        try {
            maxDp = Math.round(cfg.asFloat(ConfigContract.Param.GLASS_FILL_IMAGE_MAX_DP));
        } catch (Throwable ignored) {
        }
        return GlassFill.get(ctx, name, maxDp);
    }

    private static float radiusPx(Context ctx, ConfigContract.Snapshot cfg) {
        float dp = cfg == null ? 18f
                : cfg.asFloat(ConfigContract.Param.GEO_RADIUS_DP);
        if (dp < 0f) dp = 18f;
        float density = 1f;
        try {
            density = ctx.getResources().getDisplayMetrics().density;
        } catch (Throwable ignored) {
        }
        return dp * (density > 0f ? density : 1f);
    }

    /** Align one container with its card block and refresh per-level geometry. */
    public static void configure(View layout, RectF bound, int radiusPx,
                                 ConfigContract.Snapshot cfg) {
        if (layout == null) return;
        if (layout instanceof LiquidGlassView) {
            LiquidGlassView g = (LiquidGlassView) layout;
            // 0.4.0-p13: EXACT reference-project scale, in dp (the view
            // converts to px with density).
            //
            // LiquidTab sets refractionHeight = 10dp * density and
            // refractionAmount = -26dp * density. The NEGATIVE amount is what
            // bulges the glass outward -- my 2.2 was 40x too small and had the
            // wrong sign, so the surface had blur but no visible refraction.
            g.applyMaterial(
                    radiusPx,
                    param(cfg, ConfigContract.Param.GLASS_BLUR_RADIUS, 2f, 0.16f),
                    param(cfg, ConfigContract.Param.GLASS_REFRACTION, 10f, 0.40f),
                    param(cfg, ConfigContract.Param.GLASS_THICKNESS, -26f, -0.60f),
                    param(cfg, ConfigContract.Param.GLASS_DISPERSION, 0.42f, 0.01f),
                    tintOf(cfg));
            // 0.5.0-p175: inner fill (colour + optional image). Values are
            // cached / resolved here once per frame; the view draw path only
            // reads references. All default keys are empty/-1 => no-op.
            g.applyFill(fillColorOf(cfg), fillAlphaOf(cfg),
                    fillBitmapOf(layout.getContext(), cfg),
                    fillImageAlphaOf(cfg), fillFitOf(cfg));
        } else {
            // MUST run before the geometry fast-path below, otherwise a frame
            // with unchanged geometry would never update the transparency.
            applyTransparency(layout, cfg);
        }
        Class<?> cls = layout.getClass();
        int left = Math.round(bound.left);
        int top = Math.round(bound.top);
        int width = Math.max(1, Math.round(bound.width()));
        int height = Math.max(1, Math.round(bound.height()));
        // Float source values, kept in step with the ints above, so the
        // sub-pixel deadband compares the TRUE position and not a rounded one.
        float rawLeft = bound.left;
        float rawTop = bound.top;
        float rawWidth = Math.max(1f, bound.width());
        float rawHeight = Math.max(1f, bound.height());
        // 0.4.0-p14: the self-drawn glass carries an ambient halo that extends
        // past the card, so its view must be inflated or the shadow gets
        // clipped by the view's own bounds. onDraw() translates back by the
        // same amount, so the glass rectangle itself is unchanged.
        // PAD_DP is 0 today (the user does not want the container inflated);
        // the block stays so a non-zero value needs no other edit.
        if (layout instanceof LiquidGlassView) {
            float d = 1f;
            try {
                d = layout.getResources().getDisplayMetrics().density;
            } catch (Throwable ignored) {
            }
            int pad = Math.round(LiquidGlassView.PAD_DP * (d > 0f ? d : 1f));
            left -= pad;
            top -= pad;
            width += pad * 2;
            height += pad * 2;
            rawLeft -= pad;
            rawTop -= pad;
            rawWidth += pad * 2f;
            rawHeight += pad * 2f;
        }
        // SDK-only reflection lives in its OWN try: a forced-backing View has
        // no setRadius/setBound, and an exception there must never abort the
        // geometry alignment that follows.
        if (sMode == Mode.DEVELOPER_SDK) {
            try {
                call(layout, cls, "setRadius", int.class, radiusPx);
                call(layout, cls, "setBound", RectF.class, new RectF(bound));
                call(layout, cls, "setSkipRenderLayer", boolean.class,
                        cfg.asBool(ConfigContract.Param.GLASS_SKIP_RENDER_LAYER));
            } catch (Throwable ignored) {
            }
        }
        // HOST_UIKIT / APP_CONTAINER: radius is applied ONCE in createLayout().
        // Re-applying it here would reset the delegate's draw flag on every
        // geometry change and suppress the glass. A radius change rebuilds the
        // pool anyway (glassFingerprint() covers geo.*).
        try {
            ViewGroup.LayoutParams lp = layout.getLayoutParams();
            if (!(lp instanceof FrameLayout.LayoutParams)) return;
            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) lp;
            int curLeft = layout.getLeft();
            int curTop = layout.getTop();
            int curW = layout.getWidth();
            int curH = layout.getHeight();
            // 0.4.0-p19 SUB-PIXEL DEADBAND.
            //
            // A slow drag moves a row by 0.4-0.6 px per frame, but
            // View.layout() takes ints: Math.round(137.4)=137 and
            // Math.round(137.6)=138 ALTERNATE on consecutive frames while the
            // real motion is sub-pixel. That is the "1-2 px 抖动" the user
            // sees. Keeping the previous integer until the raw position is
            // more than 0.6 px away turns the alternation into one clean 1 px
            // step every few frames -- visually indistinguishable from smooth,
            // and it cannot lag by more than one pixel.
            left = snapAxis(rawLeft, left, curLeft);
            top = snapAxis(rawTop, top, curTop);
            width = snapAxis(rawWidth, width, curW);
            height = snapAxis(rawHeight, height, curH);
            // Full fast path: nothing changed, skip all layout / invalidate work.
            if (p.width == width && p.height == height
                    && p.leftMargin == left && p.topMargin == top
                    && curLeft == left && curTop == top
                    && curW == width && curH == height) {
                return;
            }
            p.width = width;
            p.height = height;
            p.leftMargin = left;
            p.topMargin = top;
            // NEVER call setLayoutParams() from a pre-draw callback: it
            // schedules requestLayout(), whose result is only applied in the
            // NEXT frame's layout pass. onPreDraw runs AFTER layout, so
            // LayoutParams-only alignment makes the glass trail the list by
            // one full frame -- the visible "smear" while flinging.
            //
            // 0.3.2: a scroll frame changes ONLY `top`. layout() would force
            // setFrame() + invalidate() on every container every frame.
            // offsetTopAndBottom() is the animation-grade API: it shifts the
            // RenderNode directly with no measure/layout cascade, which is
            // both cheaper and frame-exact.
            // 0.4.0-p10: the self-drawn glass must NOT take the
            // offsetTopAndBottom() fast path.
            //
            // That call shifts the View's retained display list -- which
            // includes the RenderNode we recorded -- WITHOUT re-running
            // onDraw(). For the host material that is exactly right (the
            // material does not depend on where it is), but our glass samples
            // a FIXED screen snapshot by absolute position, so translating the
            // display list makes the backdrop travel with the card and only
            // jump when a full redraw finally happens.
            //
            // layout() + invalidate() forces onDraw every frame, so the
            // sampling window follows the card while the snapshot stays put.
            boolean selfDrawn = layout instanceof LiquidGlassView;
            boolean pureVertical = !selfDrawn
                    && (curW == width && curH == height
                    && curLeft == left && curW > 0 && curH > 0 && curTop != top);
            if (pureVertical) {
                layout.offsetTopAndBottom(top - curTop);
            } else {
                layout.layout(left, top, left + width, top + height);
                if (selfDrawn) layout.invalidate();
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 0.4.0-p19: sub-pixel deadband for one axis.
     *
     * `rounded` is Math.round(raw). When the raw value sits within 0.6 px of
     * the integer the view already has, KEEP that integer: otherwise a slow
     * drag (0.4 px/frame) makes round() flip between n and n+1 on alternating
     * frames, which the eye reads as a 1-2 px shimmer of the whole glass card.
     * Worst-case positional error is 0.6 px, i.e. invisible.
     */
    private static int snapAxis(float raw, int rounded, int cur) {
        if (rounded == cur) return cur;
        if (Math.abs(raw - cur) < 0.6f) return cur;
        return rounded;
    }

    /** Clear the stock white card backgrounds so the glass layer is visible. */
    public static int clearCardBackgrounds(View root) {
        return strip(root, 0);
    }

    private static int strip(View v, int depth) {
        if (v == null || depth > 22) return 0;
        int n = 0;
        try {
            if (v.getBackground() != null) {
                v.setBackground(null);
                n++;
            }
            v.setForeground(null);
        } catch (Throwable ignored) {
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                n += strip(g.getChildAt(i), depth + 1);
            }
        }
        return n;
    }

    // ---- reflection helpers -------------------------------------------------

    private static Class<?> load(String name) {
        if (sLoader == null || name == null) return null;
        try {
            return Class.forName(name, false, sLoader);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object newInstance(Class<?> cls) {
        if (cls == null) return null;
        try {
            Constructor<?> c = cls.getDeclaredConstructor();
            c.setAccessible(true);
            return c.newInstance();
        } catch (Throwable t) {
            return null;
        }
    }

    private static void setFloatIfConfigured(Object target, Class<?> cls,
                                             String name, float value) {
        if (value < 0f) return;   // -1 = follow system
        call(target, cls, name, float.class, value);
    }

    private static Method findMethod(Class<?> cls, String name, Class<?>... params) {
        Class<?> cur = cls;
        while (cur != null) {
            try {
                return cur.getDeclaredMethod(name, params);
            } catch (NoSuchMethodException e) {
                cur = cur.getSuperclass();
            }
        }
        return null;
    }

    private static Object call(Object target, Class<?> cls, String name,
                               Class<?> paramType, Object value) {
        if (target == null || cls == null || paramType == null) return null;
        String key = cls.getName() + "#" + name + "#" + paramType.getName();
        try {
            Method m = METHOD_CACHE.get(key);
            if (m == null) {
                m = findMethod(cls, name, paramType);
                if (m == null) return null;
                m.setAccessible(true);
                METHOD_CACHE.put(key, m);
            }
            return m.invoke(target, value);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Multi-argument void-returning reflection. Unlike call() this returns a
     * boolean, because Method.invoke() returns null for a void method and
     * "null" cannot distinguish success from a swallowed exception.
     */
    private static boolean invokeAny(Object target, Class<?> cls, String name,
                                     Class<?>[] types, Object... args) {
        if (target == null || cls == null || types == null) return false;
        try {
            Method m = findMethod(cls, name, types);
            if (m == null) return false;
            m.setAccessible(true);
            m.invoke(target, args);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
