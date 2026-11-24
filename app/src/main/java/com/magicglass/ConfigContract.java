package com.magicglass;

import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-level configuration contract. Every parameter exists independently for
 * every slot (L1 / L2 / L3 / six external packages), with an optional
 * "inherit from global" switch on each pair.
 *
 * Design rules (learned the hard way from the upstream module):
 *  - Everything is stored as a String. LSPosed remote preferences rebuild from
 *    a generic Map and may turn an int into a String across processes.
 *  - The preference group is our own "magicglass", not upstream "backgrounds".
 *  - No reflection into any obfuscated upstream helper class (the old "pr").
 *  - One edit writes the value AND bumps cfg.version; hosts re-read onResume.
 *  - A parameter change must rebuild the HnGlassEffect instance: system
 *    material effects snapshot their uniforms at creation time.
 */
public final class ConfigContract {

    public static final String GROUP = "magicglass";
    public static final String KEY_VERSION = "mg.cfg.version";

    /**
     * 0.5.0-p66: ONE source of truth for the opaque colour used whenever the
     * glass overlay has not been composited yet (Activity transition / starting
     * window). Neither pure white (the ROM's theme windowBackground, which the
     * user sees as "白屏转场") nor null (p42's black transition).
     */
    public static final int WINDOW_BASE_COLOR = 0xFFEDEBF2;

    /**
     * Derive the transition colour from the CURRENT background configuration.
     *
     * 0.5.0-p77: the user reported that the very first entry into Settings
     * still shows a visible "白色转变成选择的背景" load effect. p76 only made
     * the decode EARLIER; it could not remove the COLOUR JUMP, because every
     * un-composited frame (SystemUI starting window, decor backdrop, plate)
     * was painted with the fixed near-white WINDOW_BASE_COLOR while the
     * selected wallpaper is dark / coloured. p66's hard boundary still holds:
     * the starting window is drawn by SystemUI BEFORE our process exists, so
     * a runtime hook can only align its COLOUR, never push a bitmap into it.
     *
     * Fix with the lowest possible power cost: compute the background's
     * average colour ONCE in the module process when the user picks the
     * media, persist it as BG_WINDOW_COLOR, and read that single String here.
     * Zero per-frame work, zero extra IPC, zero bitmap in the starting window.
     * The first frame is then the SAME colour family as the wallpaper, so the
     * transition reads as a reveal of detail, not a white-to-image flash.
     */
    public static int windowColorOf(Snapshot cfg) {
        if (cfg == null) return WINDOW_BASE_COLOR;
        String bg = cfg.asString(Param.BG_WINDOW_COLOR);
        if (bg != null && bg.length() > 0) {
            try {
                return 0xFF000000
                        | (android.graphics.Color.parseColor(bg) & 0x00FFFFFF);
            } catch (Throwable ignored) {
            }
        }
        String raw = cfg.asString(Param.BG_TINT_COLOR);
        if (raw == null || raw.length() == 0) return WINDOW_BASE_COLOR;
        try {
            return 0xFF000000
                    | (android.graphics.Color.parseColor(raw) & 0x00FFFFFF);
        } catch (Throwable t) {
            return WINDOW_BASE_COLOR;
        }
    }

    public enum Slot {
        GLOBAL("global"),
        L1("l1"), L2("l2"), L3("l3"),
        EXT_SYSTEMMANAGER("ext.systemmanager"),
        EXT_CONTROLCENTER("ext.controlcenter"),
        EXT_ASSISTANT("ext.assistant"),
        EXT_ID("ext.id"),
        EXT_WALLET("ext.wallet"),
        EXT_PARENTCONTROL("ext.parentcontrol"),
        /** 0.5.0-p105: 桌面和个性化, render-only scope addition. */
        EXT_WALLPAPEREDITOR("ext.wallpapereditor"),
        EXT_OTHER("ext.other");

        public final String id;

        Slot(String id) {
            this.id = id;
        }

        public static Slot forLevel(PageLevel.Level level) {
            if (level == PageLevel.Level.L1) return L1;
            if (level == PageLevel.Level.L2) return L2;
            if (level == PageLevel.Level.L3) return L3;
            return GLOBAL;
        }

        public static Slot forPackage(String pkg) {
            if (pkg == null || Scope.SETTINGS.equals(pkg)) return GLOBAL;
            String id = Scope.externalSlotId(pkg);
            for (Slot s : values()) {
                if (s.id.equals(id)) return s;
            }
            return EXT_OTHER;
        }
    }

    public enum Param {
        // ---- background (per level) ----
        BG_ENABLED("bg.enabled", "true"),
        BG_KIND("bg.kind", "image"),           // image | video | none
        BG_SOURCE("bg.source", ""),            // file name under the module filesDir
        BG_OPACITY("bg.opacity", "100"),       // 0..100
        // 0.5.0-p68: the settings UI now offers ONE filling model -- 自定义.
        // "custom" with zoom=100 / focus=50 is algebraically IDENTICAL to the
        // frozen centerCrop: ScaleMode.matrix() gives s=max(vw/sw,vh/sh) and
        // centres both axes. So switching the default does NOT change a pixel
        // for existing installs, while the two sliders become live immediately.
        BG_SCALE("bg.scale", "custom"),
        // 0.5.0-p67: CUSTOM filling. These deliberately start with "bg." so
        // they enter bgFingerprint() (backdrop re-applies) but NOT
        // glassFingerprint() (the accepted container pool is never rebuilt).
        BG_SCALE_ZOOM("bg.scaleZoom", "100"),      // 50..400, % of centerCrop
        BG_SCALE_FOCUS_X("bg.scaleFocusX", "50"),  // 0..100, left..right
        BG_SCALE_FOCUS_Y("bg.scaleFocusY", "50"),  // 0..100, top..bottom
        BG_BLUR_ENABLED("bg.blurEnabled", "false"),
        BG_BLUR_RADIUS_DP("bg.blurRadiusDp", "20"),
        BG_RANDOM_ENABLED("bg.randomEnabled", "false"),
        BG_RANDOM_POOL("bg.randomPool", ""),   // ';' separated file names
        // 0.3.5-p2: 0 meant "rotate only when the page is re-applied". When
        // the user explicitly enabled 随机背景 that looked broken. Default to
        // a real 5-minute rotation; 0 still disables the timer on purpose.
        BG_RANDOM_INTERVAL_MIN("bg.randomIntervalMin", "5"),
        BG_VIDEO_MUTED("bg.videoMuted", "true"),
        BG_TINT_COLOR("bg.tintColor", ""),          // #AARRGGBB, empty = no tint
        BG_DIM("bg.dim", "0"),                      // 0..100 black dim overlay
        /**
         * 0.5.0-p77: AVERAGE COLOUR of the chosen background media, written
         * ONCE by ConfigStore when the user imports it (#RRGGBB). It is the
         * single source for the opaque colour of every un-composited frame
         * (SystemUI starting window / decor backdrop / plate), so the first
         * frame no longer flashes near-white before the wallpaper appears.
         * Starts with "bg." so a change re-applies the backdrop, but it is
         * NOT part of glassFingerprint() -- the accepted container pool is
         * never rebuilt. Being a NEW key, every slot inherits GLOBAL by
         * default, so no legacy per-page override can make two pages differ.
         * Empty = fall back to BG_TINT_COLOR / WINDOW_BASE_COLOR.
         */
        BG_WINDOW_COLOR("bg.windowColor", ""),


        // ---- glass (per level, all mapped to the Honor SDK) ----
        // Real StyleType Enum.name() values (Settings.apk classes4.dex):
        //   NORMAL / TRANSPARENCY / FROSTED_GLASS_TRANSPARENCY /
        //   LIQUID_GLASS_TRANSPARENT. "follow" MUST inject TRANSPARENCY --
        //   an untouched container stays NORMAL and draws nothing.
        GLASS_ENABLED("glass.enabled", "true"),
        /**
         * 0.5.0-p112: strip the OEM top full-width band's OWN white fill
         * (hn_blur_top_container / content_header_container) so external L2
         * pages look like Settings L2. Child gradient views keep drawing.
         * A glass.* key -> part of glassFingerprint -> toggling it invalidates
         * the pool and re-renders in the SAME process: no restart, instant.
         */
        GLASS_STRIP_TOP_BAND("glass.stripTopBand", "true"),
        /**
         * 0.5.0-p118: unified-card transparent-fill work (design report
         * offline-unified-card-transparency-design-2026-11-18).
         * All three are glass.* keys, so they enter glassFingerprint() and
         * take effect in the SAME process (ConfigStore.writeLive ->
         * RECEIVER_EXPORTED broadcast -> ConfigBus.invalidate -> refresh()
         * -> requestRender()), no restart. Default posture is DIAGNOSE ONLY:
         * colourDiag logs candidates while both strip switches stay OFF
         * until the user visually accepts the result on multiple pages.
         */
        GLASS_COLOR_DIAG("glass.colorDiag", "true"),
        GLASS_UNIFIED_CARDS("glass.unifiedCards", "false"),
        GLASS_UNIFIED_AGGRESSIVE("glass.unifiedAggressive", "false"),
        /**
         * 0.4.0-p5 GLASS ENGINE.
         *   host   = the ROM's own HnTransFrameLayout + StyleType (default)
         *   liquid = self-drawn glass: backdrop bitmap + AGSL refraction
         * "liquid" is the only way to get real transparency control, because
         * the host material's opacity is fixed inside the uikit.
         */
        GLASS_ENGINE("glass.engine", "liquid"),       // host | liquid
        GLASS_MATERIAL("glass.material", "follow"),   // follow | liquid | frosted
        // Real SceneType mode bits: General 32, Immersive 16, BigWidget 256,
        // SmallWidget 512, FollowUIMODE 2048. "follow"/"normal" uses
        // General|BigWidget|FollowUIMODE, "immersive" swaps in Immersive.
        GLASS_SCENE("glass.scene", "follow"),         // follow | immersive | normal
        GLASS_TRANSPARENCY("glass.transparency", "-1"), // -1 = follow system, else 0..1
        GLASS_FOLLOW_SYSTEM("glass.followSystem", "true"),
        GLASS_ADAPT_BACKGROUND("glass.adaptBackground", "true"),
        GLASS_INTERACTIVE("glass.interactive", "false"),
        GLASS_SKIP_RENDER_LAYER("glass.skipRenderLayer", "false"),
        GLASS_GROUP_CARDS("glass.groupCards", "true"),
        GLASS_STANDALONE_CARDS("glass.standaloneCards", "true"),
        // External apps (systemmanager / wallet / parentcontrol / ...) use
        // bespoke compound card Views instead of RecyclerView rows. When no
        // list rows are found, a guarded generic card detector takes over.
        // Kept as a switch so it can be turned off per slot if a ROM build
        // ever produces false positives.
        GLASS_GENERIC_CARDS("glass.genericCards", "true"),
        // 0.3.7 STATIC WHITE CARDS were introduced as an A/B switch
        // (glass.staticCards). The user's 2026-11-12 14:56 verdict:
        //   "常用设置这个没必要存在最终成果是不需要白卡的存在"
        // i.e. a switch implies white cards are an acceptable state. Static
        // card detection + latched stripping are now UNCONDITIONAL and the
        // parameter was REMOVED entirely (0.3.8). There is no way to turn
        // white cards back on from the UI.
        // 0.3.2 FORCED BACKING. Skip the host uikit entirely and draw a plain
        // translucent rounded backing. Default off; turning it on is also the
        // supported way to exercise the failure-degradation path on device.
        // The renderer auto-selects the same path if createLayout() ever fails.
        GLASS_FORCE_BACKING("glass.forceBacking", "false"),
        // ---- geometry (per level) ----
        GEO_RADIUS_DP("geo.radiusDp", "18"),
        GEO_H_INSET_DP("geo.hInsetDp", "14"),
        GEO_V_INSET_DP("geo.vInsetDp", "4"),
        /**
         * 0.3.6 UNIFORM CARD TEMPLATE -- the user's "模板" request.
         *
         * Every GROUP (整块) and STANDALONE (分块) glass card shares ONE pair
         * of left/right edges, derived from the decor's visible width minus
         * geo.hInsetDp. This is the single geometry template applied on ALL
         * levels (L1/L2/L3); it is NOT an L3 special case.
         *
         * Why it is needed (040_view_top_p6.txt, L3 个人热点):
         *   normal rows      SettingsRecyclerView child 0,373-1272,541 ...
         *                    -> glass rect 49..1223 (1174 wide)
         *   help paragraph   LinearLayout 112,1542-1160,2399 (1048 wide)
         *                    -> glass rect 161..1111 ( 950 wide)
         * The OEM itself gives the help row 112 px of extra padding on EACH
         * side, and rects() used to keep every block's OWN left/right. The
         * result was a visibly narrower "white glass card" under the help
         * text, with glyphs sitting outside it. 0.3.5 tried to patch that by
         * stacking a second translucent View on top -- the user rejected the
         * two-layer result ("半透明块 + 白玻璃卡依然存在"). 0.3.6 fixes the
         * glass card ITSELF: the row is aligned to the same template as the
         * 个人热点..已连接设备 block above it, so one glass card covers all
         * the text and no extra layer exists.
         *
         * Only cards already close to full width (ratio >= 0.70) adopt the
         * template; narrow side-by-side tiles of external apps keep their own
         * bounds so they can never be stretched into an overlapping strip.
         * false restores the legacy per-block width (A/B switch).
         */
        GEO_UNIFORM_CARD_WIDTH("geo.uniformCardWidth", "true"),
        GEO_DIVIDER_ENABLED("geo.dividerEnabled", "false"),
        GEO_DIVIDER_COLOR("geo.dividerColor", ""),
        GEO_DIVIDER_ALPHA("geo.dividerAlpha", "20"),
        GEO_DIVIDER_WIDTH_DP("geo.dividerWidthDp", "1"),
        GEO_DIVIDER_INSET_DP("geo.dividerInsetDp", "16"),
        GEO_STROKE_ENABLED("geo.strokeEnabled", "false"),
        // System material parameters (android.graphics.material.atomiceffect.*).
        // Verified real setters on AAK-AN00 / Android 17: GlassParam.setRefraction(F),
        // setDispersion(F), setThickness(F), setHighQualitySdfEnabled(Z);
        // BlurParam.setRadius(F). -1 means "follow the system material".
        GLASS_REFRACTION("glass.refraction", "-1"),
        GLASS_DISPERSION("glass.dispersion", "-1"),
        GLASS_THICKNESS("glass.thickness", "-1"),
        GLASS_HIGH_QUALITY_SDF("glass.highQualitySdf", "true"),
        GLASS_BLUR_RADIUS("glass.blurRadius", "-1"),
        // 0.5.0-p175: 玻璃卡内部填充。fillColor=#RRGGBB 或 #AARRGGBB(空=沿用系统 veil),
        // fillAlpha=0..1 覆盖色自带 alpha(-1=用色自带/系统默认),fillImage=媒体名,
        // fillImageAlpha=0..1,fillImageMaxDp=解码最大边(防OOM),fillImageFit=cover|fit。
        GLASS_FILL_COLOR("glass.fillColor", ""),
        GLASS_FILL_ALPHA("glass.fillAlpha", "-1"),
        GLASS_FILL_IMAGE("glass.fillImage", ""),
        GLASS_FILL_IMAGE_ALPHA("glass.fillImageAlpha", "-1"),
        GLASS_FILL_IMAGE_MAX_DP("glass.fillImageMaxDp", "512"),
        GLASS_FILL_IMAGE_FIT("glass.fillImageFit", "cover"),

        GEO_REFRACTION("geo.refraction", "0"),  // AGSL fallback only

        // ---- element-level precise configuration (0.5.0-p121) ----
        // 元素精确配置总框架：按 ①~⑩ 视觉编号 + 功能域 + 界面 + 维度 精确配置
        // （键形如 el.net.wifi_settings.4.card，见 ElementRegistry.paramKey）。
        //
        // 重要：el.* 键暂不进任何 fingerprint，因为本轮不接渲染 hook；
        // 后续接 hook 时须新增 elfingerprint 或改 prefix 判定。
        // glassFingerprint() 只收 glass./geo. 前缀，bgFingerprint() 只收 bg. 前缀，
        // el.* 均不落入二者，因此改动元素配置不会重建已验收的材质容器池或背景层。
        //
        // 不可配置红线：②（顶栏毛玻璃/渐隐层）与 ⑧（行内图标/箭头/开关）为 keep 元素，
        // 见 ElementRegistry.VisualNo.N2_TOPBAR_KEEP / N8_ICON_KEEP（keep=true，
        // configurable() 返回 false）。任何 el.* 配置都不得触碰这两类元素：
        // 清掉 ② 的背景会丢滚动渐隐，把 ⑧ 的 alpha 置 0 会误伤头像/昵称/开关状态。
        EL_ENABLED("el.enabled", "false"),        // 总开关，默认关（诊断/渐进启用）
        EL_CARD_MODE("el.cardMode", "off"),       // off|tint|frosted，元素卡片模式
        EL_FONT_SCALE("el.fontScale", "100"),     // 60..160 百分比
        EL_FONT_COLOR("el.fontColor", ""),        // #RRGGBB 或空=跟随系统
        EL_BACKDROP_REF("el.backdropRef", ""),    // 元素级背景引用（预留）
        /**
         * 0.5.0-p126: 按 global_id 精确配置的规则串（全局唯一）。
         * 格式 `gid|dim|value;gid|dim|value;...`，例如
         * `SC.NET.WIFI_SETTINGS.CAT.01|fontColor|#FF0000;SC.NET.WIFI_SETTINGS.PREF.03|fontScale|120`。
         * gid 含 '.' 但不含 '|' / ';'；dim 本轮仅 fontColor / fontScale
         * （card / backdrop 预留不实现）；value 为 #RRGGBB 或 60..160 整数字符串。
         * 默认空 = 无规则。el.* 键不进任何 fingerprint（沿用上面 el.* 约定）。
         */
        EL_RULES("el.rules", ""),
        /**
         * 0.5.0-p178: 行内图标替换规则串,按 preference key 定位(主设置页各行
         * 未必都在 ElementRegistry 索引里,但 key 在 PREFBIND 日志里稳定可见)。
         * 格式与 el.rules 相同:`key|icon|mediaName;key|icon|mediaName;...`。
         * mediaName 是 ConfigStore.importMedia 导入的媒体名,经只读 provider
         * ConfigBus.media(name) 读取。默认空=不替换,任何行零开销返回。
         */
        ICON_RULES("icon.rules", ""),

        // ---- frame scheduling (per level) ----
        // 0.3.4. A motion frame starts a short window in which EVERY frame is
        // scanned unconditionally, even when the cheap token happens to repeat.
        // This is what makes fast flings follow the finger: a fling often moves
        // <1 px per frame at 120 Hz, so integer getTop() (or a translationY
        // animation) can legitimately repeat for several frames; 0.3.3 treated
        // those frames as idle and froze the glass mid-gesture.
        //   0   = pure token gate (0.3.3 behaviour, retained for A/B testing)
        //  160  = default, ~10-20 frames of tail cover the fling deceleration
        // The "perf." prefix is DELIBERATE: glassFingerprint()/bgFingerprint()
        // only collect glass./geo./bg. keys, so tuning the scheduler never
        // tears down the host material pool or the backdrop layer.
        PERF_TRACK_TAIL_MS("perf.trackTailMs", "160"),
        /**
         * 0.5.0: default OFF. With the switch off, only the startup signature
         * lines are written; every per-frame / per-event diagnostic string is
         * suppressed BEFORE it is built. This removes logcat + LSPosed DB
         * traffic from the frame path. The settings UI can turn it back on
         * for a diagnostic session without reinstalling the module.
         */
        PERF_DEBUG_LOG("perf.debugLog", "false");

        public final String key;
        public final String def;

        Param(String key, String def) {
            this.key = key;
            this.def = def;
        }
    }

    // ---- per-global_id rules (0.5.0-p126) ----------------------------------
    // EL_RULES stores ONE string: `gid|dim|value;gid|dim|value;...`.
    // Pure String logic lives here so both the hook layer and the settings UI
    // share the exact same format. No regex, no dependency on ElementRegistry.

    /** Parse a rules string into an ordered map keyed by gid+'\0'+dim. */
    private static LinkedHashMap<String, String> parseElRules(String rules) {
        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
        if (rules == null || rules.length() == 0) return out;
        String[] entries = rules.split(";");
        for (String entry : entries) {
            if (entry == null || entry.length() == 0) continue;
            String[] parts = entry.split("\\|", 3);
            if (parts.length < 3) continue;
            String gid = parts[0], dim = parts[1], value = parts[2];
            // Malformed / empty segments are ignored, never crash the caller.
            if (gid.length() == 0 || dim.length() == 0 || value.length() == 0) continue;
            // LinkedHashMap.put on an existing key overwrites the value but
            // keeps the FIRST insertion position -> later rules win, order kept.
            out.put(gid + "\u0000" + dim, value);
        }
        return out;
    }

    /** 0.5.0-p178: UI 列出已配置规则用;返回不可变视图,键为 gkey+'\0'+dim。 */
    public static Map<String, String> parseElRulesPublic(String rules) {
        return java.util.Collections.unmodifiableMap(parseElRules(rules));
    }

    /** @return the value for gid|dim, or "" on miss / empty input. */
    public static String elRuleGet(String rules, String gid, String dim) {
        if (gid == null || gid.length() == 0 || dim == null || dim.length() == 0) return "";
        String v = parseElRules(rules).get(gid + "\u0000" + dim);
        return v == null ? "" : v;
    }

    /**
     * Add or overwrite (or delete) one gid|dim rule and return the new string.
     * An empty/null {@code value} deletes the rule; order of the rest is kept.
     */
    public static String elRuleSet(String rules, String gid, String dim, String value) {
        if (gid == null || gid.length() == 0 || dim == null || dim.length() == 0) {
            return rules == null ? "" : rules;
        }
        LinkedHashMap<String, String> map = parseElRules(rules);
        String key = gid + "\u0000" + dim;
        if (value == null || value.length() == 0) {
            map.remove(key);
        } else {
            map.put(key, value);
        }
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (b.length() > 0) b.append(';');
            // Composite key is gid + NUL + dim; NUL can never appear in either.
            b.append(e.getKey().replace('\u0000', '|')).append('|').append(e.getValue());
        }
        return b.toString();
    }

    /**
     * @return always true. The ② / ⑧ keep hard-gate (顶栏毛玻璃渐隐层、行内图标
     * 箭头开关) is enforced by the HOOK layer via ElementRegistry.configurable /
     * byNo. ConfigContract deliberately has NO dependency on ElementRegistry,
     * so it must not try to re-implement that judgement here.
     */
    public static boolean elRuleConfigurable(String gid, String dim) {
        return true;
    }

    private ConfigContract() {
    }

    public static String key(Slot slot, Param p) {
        return "mg." + slot.id + "." + p.key;
    }

    public static String inheritKey(Slot slot, Param p) {
        return key(slot, p) + ".inherit";
    }

    /**
     * Minimal read abstraction. LSPosed remote preferences are NOT guaranteed
     * to be writable/synchronised from the module process on every framework,
     * so the host can also read the same keys through our own ContentProvider.
     * Both channels are plain Strings; the Source interface hides the origin.
     */
    public interface Source {
        /** @return the stored value, or null when the key is absent. */
        String get(String key);
    }

    public static Source from(final SharedPreferences sp) {
        return new Source() {
            @Override
            public String get(String key) {
                if (sp == null) return null;
                try {
                    Object v = sp.getAll().get(key);
                    return v == null ? null : String.valueOf(v);
                } catch (Throwable t) {
                    try {
                        return sp.getString(key, null);
                    } catch (Throwable ignored) {
                        return null;
                    }
                }
            }
        };
    }

    public static Source from(final Map<String, String> map) {
        return new Source() {
            @Override
            public String get(String key) {
                return map == null ? null : map.get(key);
            }
        };
    }

    /** Read one value without assuming the stored Java type. */
    private static String raw(Source src, String key, String fallback) {
        if (src == null) return fallback;
        String v;
        try {
            v = src.get(key);
        } catch (Throwable t) {
            return fallback;
        }
        return v == null ? fallback : v;
    }

    private static boolean rawBool(Source src, String key, boolean fallback) {
        String v = raw(src, key, null);
        return v == null ? fallback : Boolean.parseBoolean(v.trim());
    }

    /** Resolve one slot into an immutable snapshot. */
    /**
     * 0.5.0-p172: 设置界面新开放的玻璃高级参数——圆角/左右内缩/上下内缩
     * (纯几何)与透明度/模糊/折射/色散/厚度(质感)。
     * 0.5.0-p173: 用户选择 8 项全部统一,这些键现在对所有 slot(含 L1)
     * 强制继承 GLOBAL 值,resolve() 不再对 L1 强制读回 p30 默认。
     */
    public static boolean isUserTunableGlass(Param p) {
        return p == Param.GEO_RADIUS_DP
                || p == Param.GEO_H_INSET_DP
                || p == Param.GEO_V_INSET_DP
                || p == Param.GLASS_TRANSPARENCY
                || p == Param.GLASS_BLUR_RADIUS
                || p == Param.GLASS_REFRACTION
                || p == Param.GLASS_DISPERSION
                || p == Param.GLASS_THICKNESS;
    }

    /** 0.5.0-p175: 内部填充参数,与玻璃参数一样对所有 slot 统一继承 GLOBAL。 */
    public static boolean isGlassFillParam(Param p) {
        return p == Param.GLASS_FILL_COLOR
                || p == Param.GLASS_FILL_ALPHA
                || p == Param.GLASS_FILL_IMAGE
                || p == Param.GLASS_FILL_IMAGE_ALPHA
                || p == Param.GLASS_FILL_IMAGE_MAX_DP
                || p == Param.GLASS_FILL_IMAGE_FIT;
    }

    public static Snapshot resolve(Source src, Slot slot) {
        Map<String, String> out = new HashMap<>();
        Map<String, Boolean> custom = new HashMap<>();
        for (Param p : Param.values()) {
            String global = raw(src, key(Slot.GLOBAL, p), p.def);
            // 0.5.0-p77: BACKGROUND IS GLOBAL-ONLY, structurally.
            // p63-p65 shipped a "作用页面" selector and could write per-slot
            // bg.* values with inherit=false. p66 removed that selector and
            // offered a manual "统一全部页面" button, but never cleared the
            // legacy overrides automatically -- so an external process such
            // as 账号中心 (com.hihonor.id -> slot ext.id) could keep its own
            // tint / dim / opacity / source and render a DIFFERENT backdrop
            // from every Settings L2. The user explicitly asked for one
            // unified background on ALL pages. Enforcing inheritance in the
            // read path (instead of a one-off write) makes it impossible for
            // any stale key to win, needs no device write and no broadcast,
            // and costs one fewer Map lookup per bg parameter.
            // L1 is ACCEPTED/FROZEN at p30: gate the enforcement on
            // slot != L1 so L1 takes the byte-for-byte pre-existing branch.
            // In a healthy install L1's bg.* inherit flags are already true,
            // so this gate changes nothing there; it only guarantees the
            // frozen page can never be affected by a stale override.
            // GLASS parameters keep their per-slot ability everywhere.
            boolean backgroundOnly = p.key.startsWith("bg.") && slot != Slot.L1;
            // 0.5.0-p173: 用户选「8 项全部统一」——3 几何 + 5 质感这批
            // 可调玻璃参数对所有 slot(含 L1)强制继承 GLOBAL,与 bg.* 同构。
            // 即使旧的 per-slot override(inherit=false)残留也不能让某页
            // 读到不同值;不再有 p172 的 L1->p.def 硬门。三渲染文件仍与
            // p30 逐字节相同,只是配置快照不再对 L1 特判。
            boolean glassUnified = isUserTunableGlass(p) || isGlassFillParam(p);
            boolean inherited = slot == Slot.GLOBAL || backgroundOnly
                    || glassUnified || rawBool(src, inheritKey(slot, p), true);
            String value = inherited ? global : raw(src, key(slot, p), global);
            out.put(p.key, value);
            custom.put(p.key, !inherited);
        }
        return new Snapshot(slot, out, custom);
    }

    public static Snapshot resolve(SharedPreferences sp, Slot slot) {
        return resolve(from(sp), slot);
    }

    public static String version(Source src) {
        return raw(src, KEY_VERSION, "0");
    }

    public static String version(SharedPreferences sp) {
        return version(from(sp));
    }

    // ---- writer side (module UI, runs in the module process) ----------------

    public static void put(SharedPreferences.Editor e, Slot slot, Param p, String value) {
        e.putString(key(slot, p), value);
        bump(e);
    }

    public static void setInherit(SharedPreferences.Editor e, Slot slot, Param p, boolean inherit) {
        e.putString(inheritKey(slot, p), String.valueOf(inherit));
        bump(e);
    }

    /** Explicit overwrite, never remove(): upstream learned that a removed key
     *  is not guaranteed to disappear from the remote mirror. */
    public static void writeDefaults(SharedPreferences.Editor e, Slot slot) {
        for (Param p : Param.values()) {
            e.putString(key(slot, p), p.def);
            if (slot != Slot.GLOBAL) {
                e.putString(inheritKey(slot, p), "true");
            }
        }
        bump(e);
    }

    /**
     * 0.5.0: strictly monotonic. System.currentTimeMillis() alone can repeat
     * when two edits land in the same millisecond (a slider emits many of
     * them), which made hosts miss a config change entirely.
     */
    private static long sLastVersion;

    private static synchronized long nextVersion() {
        long now = System.currentTimeMillis();
        if (now <= sLastVersion) now = sLastVersion + 1L;
        sLastVersion = now;
        return now;
    }

    private static void bump(SharedPreferences.Editor e) {
        e.putString(KEY_VERSION, String.valueOf(nextVersion()));
    }

    public static final class Snapshot {
        public final Slot slot;
        private final Map<String, String> values;
        private final Map<String, Boolean> custom;

        private Snapshot(Slot slot, Map<String, String> values, Map<String, Boolean> custom) {
            this.slot = slot;
            this.values = values;
            this.custom = custom;
        }

        public String asString(Param p) {
            return values.get(p.key);
        }

        public boolean asBool(Param p) {
            return Boolean.parseBoolean(values.get(p.key));
        }

        public int asInt(Param p) {
            try {
                return Integer.parseInt(values.get(p.key));
            } catch (Throwable t) {
                try {
                    return Integer.parseInt(p.def);
                } catch (Throwable ignored) {
                    return 0;
                }
            }
        }

        public float asFloat(Param p) {
            try {
                return Float.parseFloat(values.get(p.key));
            } catch (Throwable t) {
                try {
                    return Float.parseFloat(p.def);
                } catch (Throwable ignored) {
                    return 0f;
                }
            }
        }

        /** True when this slot overrides the global value for this parameter. */
        public boolean isCustom(Param p) {
            Boolean b = custom.get(p.key);
            return b != null && b;
        }

        /**
         * Only parameters that change the MATERIAL object / container. A change
         * here forces the container pool to be rebuilt (system glass effects
         * snapshot their uniforms at creation time); a pure geometry change is
         * handled by re-aligning the existing containers instead.
         */
        public String glassFingerprint() {
            // NOTE: slot.id is deliberately NOT part of this fingerprint.
            // resolve() already returns the actual per-slot parameter VALUES,
            // so two levels with identical material settings are genuinely
            // identical. Including the slot id made every L1 -> L2 transition
            // look like a material change, which forced RenderController to
            // tear down the whole container pool; the rebuild then needed 3
            // hysteresis frames during which the page showed stock white
            // cards. That was the "glass disappears / turns white" defect.
            StringBuilder b = new StringBuilder("material");
            for (Param p : Param.values()) {
                // 0.3.6: geo.uniformCardWidth is PURE GEOMETRY -- rects()
                // re-reads it every frame, so toggling it must NOT tear down
                // the host glass pool (that would flash OEM white cards).
                // 0.3.8: glass.staticCards no longer exists (always on).
                if (p == Param.GEO_UNIFORM_CARD_WIDTH) continue;
                if (p.key.startsWith("glass.") || p.key.startsWith("geo.")) {
                    b.append('|').append(p.key).append('=').append(asString(p));
                }
            }
            return b.toString();
        }

        /** Only parameters that change the background layer. */
        public String bgFingerprint() {
            StringBuilder b = new StringBuilder(slot.id);
            for (Param p : Param.values()) {
                if (p.key.startsWith("bg.")) {
                    b.append('|').append(p.key).append('=').append(asString(p));
                }
            }
            return b.toString();
        }

        public String describe() {
            return "slot=" + slot.id
                    + " bg=" + asString(Param.BG_KIND)
                    + " glass=" + asBool(Param.GLASS_ENABLED)
                    + " material=" + asString(Param.GLASS_MATERIAL)
                    + " transparency=" + asString(Param.GLASS_TRANSPARENCY)
                    + " group=" + asBool(Param.GLASS_GROUP_CARDS)
                    + " standalone=" + asBool(Param.GLASS_STANDALONE_CARDS);
        }
    }
}
