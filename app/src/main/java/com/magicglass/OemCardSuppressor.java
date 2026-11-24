package com.magicglass;

import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Bounded, first-frame-correct suppression of OEM containers whose own onDraw()
 * pass paints the white card (0.4.0).
 *
 * WHY IT EXISTS
 * -------------
 * 0.3.9 drove suppression from the strip set, so it only ever saw children of
 * lists(decor). Pages whose painter is a ListView or a card container outside
 * any RecyclerView were never considered, and the Activity's first frames ran
 * before layout (getWidth()==0), so a size guard rejected them. The result was
 * the reported "首帧白一下再消失" / "存在又消失".
 *
 * This class therefore walks the tree ITSELF: bounded (depth 20, 900 nodes),
 * by class name, with no dependency on lists() or the strip set and no size
 * gate on discovery. It is forced on the first pre-draw frame (which runs
 * AFTER layout) and again inside a short early window, then only on idle
 * frames.
 *
 * L1 is exempt: apply(false) restores every flipped container so the OEM's own
 * dividers and highlights stay intact.
 */
public final class OemCardSuppressor {

    /** First pre-draw frames get a couple of extra walks (late inflation). */
    public static final long EARLY_WINDOW_MS = 400L;

    private static final int MAX_DEPTH = 20;
    private static final int MAX_NODES = 900;
    private static final long RESCAN_MS = 600L;
    private static final long FORCE_GAP_MS = 120L;
    /**
     * Deliberately false: the wallet's full-screen self-drawing roots
     * (BlurScrollView / HwScrollView / HwViewPager) must not be ripped out.
     * The code path is kept complete so it can be enabled per page if needed.
     */
    private static final boolean WALLET_BLUR_ROOT = false;

    private final Set<View> candidates = new HashSet<View>();
    private final Set<View> rejected = new HashSet<View>();
    /** Views we actually flipped; needed for a faithful restore. */
    private final Set<View> suppressed = new HashSet<View>();
    /** 0.4.0-p44: original Drawables removed from a discovered container. */
    private final Map<View, Drawable> savedBg = new HashMap<View, Drawable>();
    private final Map<View, Drawable> savedFg = new HashMap<View, Drawable>();
    /** 0.4.0-p46 diagnostic: which classes we accept / reject, and how often. */
    private final Map<String, Integer> candCls = new HashMap<String, Integer>();
    private final Map<String, Integer> rejCls = new HashMap<String, Integer>();
    /** 0.4.0-p48: times a stripped container's Drawable came back (OEM re-bind). */
    private int revivedBg;
    /** p162: 有界诊断行计数（每次 forced pass 重置）。 */
    private int diagLines;
    private boolean dirty = true;
    private long lastDiscoverMs;

    public void invalidate() {
        dirty = true;
        lastDiscoverMs = 0L;
    }

    public int count() {
        return suppressed.size();
    }

    public String describe() {
        return suppressed.size() + "/" + candidates.size() + "/" + rejected.size();
    }

    /**
     * Discrete discovery. A forced pass is a single bounded walk; the idle
     * pass is rate-limited to RESCAN_MS so a settled page costs nothing.
     */
    public void discover(View decor, View overlay, boolean force, boolean idle) {
        discover(decor, overlay, force, idle, false);
    }

    /**
     * 0.4.0-p48: `ignoreGap` walks the tree on EVERY call.
     *
     * The proven entry fix (p45/p47) is "strip the container the moment the
     * walk sees it". Its remaining hole was cadence, not coverage: forced
     * passes are rate-limited to FORCE_GAP_MS (120 ms), so a card inflated
     * 50 ms after the previous pass stayed white for up to ~7 frames -- the
     * "一闪". During the early window the walk is cheap on repeat frames
     * because candidates/rejected memoise every visited ViewGroup, so only
     * NEW nodes pay the class-name work.
     */
    public void discover(View decor, View overlay, boolean force, boolean idle,
                         boolean ignoreGap) {
        if (decor == null) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (!(dirty || force || (idle && now - lastDiscoverMs >= RESCAN_MS))) return;
        long gap = (dirty || force) ? FORCE_GAP_MS : RESCAN_MS;
        if (!ignoreGap && now - lastDiscoverMs < gap) return;
        lastDiscoverMs = now;
        boolean wasDirty = dirty;
        dirty = false;
        // p162: 每次 forced/invalidated pass 重置诊断预算。
        if (force || wasDirty) diagLines = 0;
        walk(decor, overlay, 0, new int[]{MAX_NODES});
        // 0.4.0-p46 diagnostic: only on a forced/invalidated pass (page enter),
        // so this cannot spam.
        if (wasDirty) GlassEntry.say("suppress classes " + classStats());
    }

    private void walk(View v, View overlay, int depth, int[] budget) {
        if (v == null || v == overlay || depth > MAX_DEPTH) return;
        if (budget[0]-- <= 0) return;
        if (isInjected(v)) return;
        if (v instanceof ViewGroup && !candidates.contains(v)
                && !rejected.contains(v)) {
            String cls = v.getClass().getName().toLowerCase();
            // 0.5.0-p161: 应用页(AppAndNotificationDashboardActivity, l2)的
            // dashboard_tile 是 LinearLayout(类名无 card/preference 关键词)，
            // isTarget 按类名判会拒绝 ⇒ 首帧 OEM 白底不被剥(syncRows 前
            // strip=0)，白卡先画出来约 350ms 后才消失。复用 §44/§45 的
            // "发现即剥 background/foreground"机制，按精确 id 放行这个容器。
            // id 此页独有，L1/其它页 no-op。
            String vres = resourceName(v);
            boolean byId = "dashboard_tile".equals(vres);
            // p162 诊断：应用页 LinearLayout 首帧为何被拒。有界每次 forced
            // pass 最多 6 行，只打可疑 LinearLayout，不进帧路径热循环。
            if (cls.contains("linearlayout") && diagLines < 6) {
                diagLines++;
                GlassEntry.say("suppress DIAG cls=" + shortName(cls)
                        + " id=" + (vres.length() == 0 ? "-" : vres)
                        + " byId=" + byId + " target=" + isTarget(cls));
            }
            if ((isTarget(cls) || byId) && !isHardChrome(cls)) {
                candidates.add(v);
                bump(candCls, shortName(cls));
            } else {
                rejected.add(v);
                bump(rejCls, shortName(cls));
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                walk(g.getChildAt(i), overlay, depth + 1, budget);
            }
        }
    }

    /**
     * Cheap and idempotent, called every frame. When disabled (L1) every
     * previously flipped container is restored, so the OEM dividers and
     * highlights come back.
     */
    public void apply(boolean enabled) {
        if (!candidates.isEmpty()) {
            for (View v : candidates) {
                if (enabled) {
                    try {
                        boolean touched = false;
                        if (!v.willNotDraw()) {
                            v.setWillNotDraw(true);
                            touched = true;
                        }
                        // 0.4.0-p44 ENTRY WHITE-CARD FIX.
                        //
                        // setWillNotDraw(true) only skips onDraw(); View.draw()
                        // still paints the background Drawable. A card
                        // container that carries a white fill therefore keeps
                        // drawing it until syncRows() strips the row -- and on
                        // the first frames of a fresh Activity CardScanner has
                        // no rows yet (scan: rects=0 / strip=0). Strip the
                        // Drawable here, the moment the container is
                        // discovered, and keep the original so release() can
                        // still restore the stock page faithfully.
                        // 0.4.0-p48 REVIVED CONTAINER -- the SAME lesson as
                        // p41's restripLatched(), which was only ever applied
                        // to scanned ROWS and never to these containers.
                        //
                        // The guard used to be `!savedBg.containsKey(v)`, i.e.
                        // a container was stripped ONCE. But the ROM re-binds
                        // a fresh Drawable later -- on the Bluetooth page the
                        // "available devices" rows arrive a frame or two after
                        // the card itself, and the bind re-installs the card's
                        // white fill. Because the key already existed, the new
                        // Drawable was NOT stripped, so the stock white card
                        // was painted for 1-2 frames at FULL SCREEN WIDTH
                        // (the card's own background, unlike our 14 dp inset
                        // glass) -- exactly "整个卡片一起闪，左右到屏幕边缘".
                        //
                        // Keep the FIRST original for release(); strip every
                        // revived one, every frame.
                        Drawable bg = v.getBackground();
                        if (bg != null) {
                            if (!savedBg.containsKey(v)) savedBg.put(v, bg);
                            else revivedBg++;
                            v.setBackground(null);
                            touched = true;
                        }
                        Drawable fg = v.getForeground();
                        if (fg != null) {
                            if (!savedFg.containsKey(v)) savedFg.put(v, fg);
                            else revivedBg++;
                            v.setForeground(null);
                            touched = true;
                        }
                        if (touched) suppressed.add(v);
                    } catch (Throwable ignored) {
                    }
                } else if (suppressed.contains(v)) {
                    try {
                        if (v.willNotDraw()) v.setWillNotDraw(false);
                        Drawable bg = savedBg.remove(v);
                        if (bg != null) v.setBackground(bg);
                        Drawable fg = savedFg.remove(v);
                        if (fg != null) v.setForeground(fg);
                    } catch (Throwable ignored) {
                    }
                    suppressed.remove(v);
                }
            }
        }
        if (!enabled) return;
        // Re-assert on the small suppressed set too: the ROM may re-enable
        // onDraw after addItemDecoration().
        for (View v : suppressed) {
            try {
                if (!v.willNotDraw()) v.setWillNotDraw(true);
            } catch (Throwable ignored) {
            }
        }
    }

    /** Restore everything on detach (module switched off / glass disabled). */
    public void release() {
        release(true);
    }

    /**
     * 0.4.0-p37: when the Activity is FINISHING, `restore=false` clears our
     * bookkeeping WITHOUT handing the OEM's white-card onDraw back. During a
     * back transition the outgoing window is still composited, so flipping
     * setWillNotDraw(false) there makes the stock white cards reappear.
     */
    public void release(boolean restore) {
        if (restore) apply(false);
        // 0.4.0-p44: after a finishing release the View tree is going away, so
        // merely drop the references instead of writing Drawables back.
        savedBg.clear();
        savedFg.clear();
        candidates.clear();
        rejected.clear();
        suppressed.clear();
        dirty = true;
        lastDiscoverMs = 0L;
    }

    /** 0.4.0-p44 diagnostic: how many containers had their Drawable removed. */
    public int bgStripped() {
        return savedBg.size();
    }

    /**
     * 0.4.0-p48 diagnostic: how many times a container's background/foreground
     * came BACK after we had already stripped it. A non-zero value on page
     * entry is the signature of the OEM re-bind that caused the Bluetooth
     * flash; it is consumed once per frames window.
     */
    public int takeRevived() {
        int n = revivedBg;
        revivedBg = 0;
        return n;
    }

    private static void bump(Map<String, Integer> m, String k) {
        Integer n = m.get(k);
        m.put(k, n == null ? 1 : n + 1);
    }

    private static String shortName(String cls) {
        int i = cls.lastIndexOf('.');
        return i < 0 ? cls : cls.substring(i + 1);
    }

    private static String top(Map<String, Integer> m) {
        StringBuilder sb = new StringBuilder("[");
        int n = 0;
        for (Map.Entry<String, Integer> e : m.entrySet()) {
            if (n >= 7) break;
            if (n > 0) sb.append(' ');
            sb.append(e.getKey()).append(':').append(e.getValue());
            n++;
        }
        return sb.append(']').toString();
    }

    /** 0.4.0-p46 diagnostic: accepted vs rejected container classes. */
    public String classStats() {
        return "cand=" + top(candCls) + " rej=" + top(rejCls);
    }

    /**
     * p162: 与项目已验证的 CardScanner.resourceName / RenderController.
     * resourceEntry 逐字一致(带 toLowerCase、不额外判 getResources()==null)。
     * p161 我写的版本多了 getResources() 空检查、少了小写，真机 byId 未命中
     * (suppress cand=[] rej=[linearlayout:11])，dashboard_tile 白卡仍延迟。
     */
    private static String resourceName(View v) {
        try {
            int id = v.getId();
            if (id != View.NO_ID) {
                return v.getResources().getResourceEntryName(id).toLowerCase();
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static boolean isTarget(String cls) {
        if (cls.endsWith("recyclerview")
                || cls.contains("listview")
                || cls.contains("abslistview")
                || cls.contains("cardview")
                || cls.contains("cardlayout")
                || cls.contains("listcard")
                || cls.contains("cardstack")
                || cls.contains("cardcontainer")
                || cls.contains("cardroot")
                // 0.4.0-p47: Settings' own preference containers. The p46
                // diagnostic showed Bluetooth's "available devices" rows are
                // `SummaryPreferenceLayout` and were REJECTED, so their white
                // fill survived until syncRows() stripped the row -- exactly
                // the one-frame flash the user still saw after p45. Class name
                // taken from the log: rej=[... summarypreferencelayout:2 ...].
                || cls.contains("summarypreferencelayout")
                || cls.contains("preferencegroup")) {
            return true;
        }
        return WALLET_BLUR_ROOT;
    }

    private static boolean isHardChrome(String cls) {
        String[] bad = {"toolbar", "appbar", "statusbar", "navigationbar",
                "decorview", "gradientblur", "hntransparent", ConfigContract.GROUP,
                "searchview", "searchplate"};
        for (String b : bad) {
            if (cls.contains(b)) return true;
        }
        return WALLET_BLUR_ROOT;
    }

    // 0.5.0: the p50/p51/p58 whole-tree paintability censuses were
    // removed. Their only callers (PAINT / PAINT2 / DRAWWHITE) are
    // retired; the measured properties are still used by the live
    // structural strip in RenderController.stripRowsIn().

    private static boolean isInjected(View v) {        try {
            Object tag = v.getTag();
            if (tag != null && tag.toString().startsWith("magicglass.")) return true;
        } catch (Throwable ignored) {
        }
        return v.getClass().getName().toLowerCase().contains(ConfigContract.GROUP);
    }
}
