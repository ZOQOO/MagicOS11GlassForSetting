package com.magicglass;

import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Finds card blocks in the host view tree.
 *
 * ALL thresholds come from the 2026-11-11/12 CLEAN on-device probes on HONOR
 * AAK-AN00 / Android 17 (API 37), taken AFTER the old HonorBG module was
 * disabled and zygote was soft-restarted. Nothing is inherited from the
 * MIUI/HyperOS base APK.
 *
 * Measured reality (screen 1272x2800):
 *  L1 HWSettings: HwRecyclerView#dashboard_container -> LinearLayout#dashboard_tile
 *      group gap 63px; tiles reach ANY y after scrolling.
 *  L3 TetherSettings: SettingsRecyclerView#list -> MinHeightLinearLayout
 *      rows 168/227/293px (VARIABLE); group gap 63px.
 *  L2 WifiSettings: the WLAN switch bar is NOT a RecyclerView child. It is
 *      HnListCardLayout{974537e 0,0-1272,168} sitting in
 *      LinearLayout#switch_bar_layout, a SIBLING of #main_content. Before
 *      0.3.7 it was never scanned, never stripped and therefore stayed a
 *      solid white card ON TOP of our decor-level glass overlay.
 *
 * Three detection paths, ALL feeding ONE grouping pass and ONE geometry
 * template (0.3.6):
 *  1. LIST path: direct children of a *RecyclerView (L1/L2/L3).
 *  2. STATIC path (0.3.7): opaque card containers OUTSIDE any RecyclerView
 *     (switch bars, account headers, 荣耀互联 / YOYO header cards, ...).
 *     These are exactly the "白卡" the user reported on nearly every L2 page.
 *     Discovery is a BOUNDED, CACHED, IDLE-ONLY tree walk -- never a per-frame
 *     recursion (the 0.3.4 p8 jank regression). Identities are cached, so a
 *     stripped background can never make the detector forget its own card.
 *  3. GENERIC path (external apps): bespoke compound card Views.
 *
 * Chrome is excluded by class/resource name, never by an absolute Y cut-off.
 * 0.2.6 used MIN_TOP_PX=350f and dropped a genuine tile at [0,343][1272,525].
 */
public final class CardScanner {

    public enum Kind { GROUP, STANDALONE }

    public static final class Block {
        public final RectF rect;              // screen coordinates
        public final Kind kind;
        /** Height of the TALLEST SINGLE row, not the merged block (P2a). */
        public final float maxRowHeight;
        /** Screen rect of that row, used to place the legibility backing. */
        public final RectF tallRowRect;
        /**
         * 0.3.8: laid-out (UNCLIPPED) height of the tallest row. EdgeFade
         * compares rect.height() against this to tell "a card being clipped
         * by an edge" from "a card that is simply short".
         */
        public final float fullH;
        public final List<View> rows = new ArrayList<View>();

        Block(RectF r, Kind k, float maxRowHeight, RectF tallRowRect, float fullH) {
            rect = r;
            kind = k;
            this.maxRowHeight = maxRowHeight;
            this.tallRowRect = tallRowRect;
            this.fullH = fullH;
        }

        public String describe() {
            return kind + " [" + (int) rect.left + "," + (int) rect.top + ","
                    + (int) rect.right + "," + (int) rect.bottom + "] rows=" + rows.size();
        }
    }

    /** A row must be at least this tall, else it is a divider/spacer. */
    private static final float MIN_ROW_H_PX = 80f;
    /** A row must span most of the screen width. */
    private static final float MIN_WIDTH_RATIO = 0.75f;
    /** Measured group gap = 63px. Anything > 30px starts a new block. */
    private static final float GROUP_GAP_PX = 30f;
    /**
     * 0.5.0-p166: 分组滞回带(px)。gap 落在 [GROUP_GAP_PX-HYST, GROUP_GAP_PX+HYST]
     * 内时沿用上一帧同一对相邻 View 的分界决定,避免阈值附近反复跨越导致块数
     * 3↔4 跳、容器隐藏/重现(reappears)。超出带才按新 gap 决策。
     */
    private static final float GROUP_HYST_PX = 18f;
    /** DO NOT filter by absolute screen-Y: see the 0.2.6 / 0.2.7 incident. */
    private static final float MIN_TOP_PX = 0f;
    private static final int MAX_TREE_DEPTH = 24;

    // ---- generic (external app) card detector guards ------------------------
    // Stability first: every one of these limits is a hard bail-out. A ROM
    // update that produces an unexpected tree must degrade to "no glass",
    // never to "glass painted over arbitrary UI".
    private static final int GENERIC_MAX_DEPTH = 18;
    private static final int GENERIC_MAX_NODES = 600;
    private static final int GENERIC_MAX_CARDS = 14;
    private static final float GENERIC_MIN_W_RATIO = 0.18f;   // small twin cards
    private static final float GENERIC_MIN_H_PX = 110f;
    private static final float GENERIC_MAX_AREA_RATIO = 0.58f; // no full-screen
    private static final float GENERIC_MAX_TOTAL_RATIO = 0.90f;
    private static final float GENERIC_MAX_OVERLAP = 0.60f;

    // ---- static (non-RecyclerView) white-card detector, 0.3.7 --------------
    // The user: "白块如果在玻璃层上面可以去掉它". These OEM cards are pinned
    // above our decor-level overlay, so stripping the background is the ONLY
    // way to let the glass show through. Discovery is deliberately discrete:
    //   * hard node budget 600 / depth 20;
    //   * cached View identities (WeakReference), re-read geometry each frame;
    //   * at most once per 400 ms, and NEVER during the tracking tail (scroll);
    //   * forced once on attach / page change via invalidateStatic().
    // The frame path itself stays O(visible rows + cached cards).
    private static final int STATIC_MAX_DEPTH = 20;
    private static final int STATIC_MAX_NODES = 600;
    private static final int STATIC_MAX_CARDS = 14;
    private static final int STATIC_MAX_STRIPS = 40;
    private static final float STATIC_MIN_W_RATIO = 0.18f;
    private static final float STATIC_MIN_H_PX = 70f;
    // p159: 隐私/钱包 ScrollView 内层行收集的有界节点预算。
    private static final int SCROLL_ROW_BUDGET = 200;
    /**
     * 0.3.8: the old binary 32 px cut-off made a scrolling card vanish in
     * one frame. A card is now kept until EdgeFade.MIN_VISIBLE_PX and faded
     * continuously instead (see EdgeFade.java).
     */
    private static final float STATIC_MAX_AREA_RATIO = 0.58f;
    private static final long STATIC_DISCOVER_MS = 600L;
    private static final long STATIC_DISCOVER_EMPTY_MS = 2000L;

    private CardScanner() {
    }

    // ---- compatibility helpers (no cache; used by one-off callers) ----------

    public static List<Block> scan(View decor) {
        return new Scanner().scan(decor);
    }

    public static List<RectF> rects(View decor, ConfigContract.Snapshot cfg,
                                    float density) {
        return new Scanner().rects(decor, decor, cfg, density);
    }

    /** Cheap FNV-1a signature: skip container re-layout when geometry is unchanged. */
    public static long signature(List<RectF> rects) {
        long h = 1469598103934665603L;
        if (rects == null) return h;
        for (RectF r : rects) {
            h = mix(h, Float.floatToRawIntBits(r.left));
            h = mix(h, Float.floatToRawIntBits(r.top));
            h = mix(h, Float.floatToRawIntBits(r.right));
            h = mix(h, Float.floatToRawIntBits(r.bottom));
        }
        return mix(h, rects.size());
    }

    private static long mix(long h, int v) {
        return (h ^ (v & 0xffffffffL)) * 1099511628211L;
    }

    // ---- stateful scanner (one per Activity, driven by pre-draw) ------------

    public static final class Scanner {

        private final List<WeakReference<View>> listCache =
                new ArrayList<WeakReference<View>>();
        /**
         * Generic cards are identified BY HAVING A BACKGROUND DRAWABLE, but
         * syncRows() strips that very drawable. Without a cache the detector
         * would forget its own cards on the next frame: white cards disappear
         * yet no glass containers are ever built. Cache the View identities
         * and only re-read their geometry each frame.
         */
        private final List<WeakReference<View>> genericCache =
                new ArrayList<WeakReference<View>>();
        /**
         * 0.3.7 static OEM cards OUTSIDE a RecyclerView. Identity-cached for
         * exactly the same reason: their background is stripped every frame.
         */
        private final List<WeakReference<View>> staticCache =
                new ArrayList<WeakReference<View>>();
        /** Opaque inner wrappers (inside a card or a list row): strip only. */
        private final List<WeakReference<View>> staticStripCache =
                new ArrayList<WeakReference<View>>();
        private long lastGenericDiscoverMs;
        private long lastStaticDiscoverMs;
        private long lastStaticStructureSig = Long.MIN_VALUE;
        private long staticIntervalMs = STATIC_DISCOVER_MS;
        private boolean staticDirty = true;
        private boolean discoveryAllowed = true;
        // 0.5.0-p166: 分组滞回——记住上一帧"哪对相邻 View 被分界",让块数在
        // 滑动中不因阈值附近抖动而反复合并/拆分(真机 reappears=8~18、块数
        // 3↔4 跳=容器反复隐藏/重现+重录=用户说的摇晃)。WeakHashMap 以行
        // View 为键,随 View 回收自动清理,不进帧热循环之外的新分配。
        private final java.util.WeakHashMap<View, Boolean> prevSplitRows =
                new java.util.WeakHashMap<View, Boolean>();
        /** Parallel to rects(): continuous edge-fade alpha for each card. */
        private List<Float> lastFades = Collections.emptyList();
        /** Parallel to rects(): OEM row Views to fade together with the glass. */
        private List<List<View>> lastContents = Collections.emptyList();
        private int lastStaticCards;
        private int lastStaticStrips;
        private int lastStaticNodes;
        /**
         * 0.5.0-p120: pure-diagnostic SQUARECARD line budget. walkStatic() is
         * static, so the throttle/limit live here as static fields; reset once
         * per discoverStatic() pass (one page). Bounded to 8 lines, never on a
         * per-frame path (discoverStatic itself is a bounded settled-frame walk).
         */
        private static int sSquareCardLines;
        private static long sLastSquareCardLogMs;
        private List<Block> lastBlocks = Collections.emptyList();
        private List<View> lastVisibleRows = Collections.emptyList();
        /**
         * 0.3.6/0.3.7: no paragraph detector, no second View layer, no
         * last-good rect, no kick chain. All glass geometry comes from the
         * single rects() template. The frame path is strictly O(visible rows).
         */

        /** Blocks from the most recent scan, already filtered by per-level switches. */
        public List<Block> blocks() {
            return lastBlocks;
        }

        /**
         * EVERY qualifying row, including rows that did not end up in a block.
         * RenderController strips OEM white backgrounds from this WIDER set:
         * a row must never flash back to an opaque card merely because the
         * grouping pass rejected it on one frame (or the page had zero blocks
         * during a transition). 0.3.7 adds static cards and their wrappers.
         */
        public List<View> visibleRows() {
            return lastVisibleRows;
        }

        /** Force one bounded re-discovery (attach / page change / config). */
        public void invalidateStatic() {
            staticDirty = true;
            lastStaticDiscoverMs = 0L;
        }

        /** Tree walks are allowed ONLY on settled, non-tracking-tail frames. */
        public void setDiscoveryAllowed(boolean allowed) {
            discoveryAllowed = allowed;
        }

        /**
         * 0.4.6: force the list-container cache to be rebuilt.
         *
         * lists() keeps a WeakReference cache that only invalidates when a
         * cached View is detached or hidden. When a page pushes a NEW Activity
         * on top of Settings, the OLD page's HwRecyclerView is still attached
         * (just not visible to the user), so the cache looked perfectly valid
         * and the new page's inner ListView was NEVER collected -- the
         * 应用/荣耀互联 rows stayed white no matter how many times the
         * ListView support itself was patched. Called on attach / lifecycle /
         * slot change, i.e. exactly on a page transition.
         */
        /** Diagnostic: "cards/strips/nodes" from the last discovery. */
        public String staticDescribe() {
            return lastStaticCards + "/" + lastStaticStrips + "/" + lastStaticNodes;
        }

        /** 0.4.0-p32 diagnostic: why each cached static card was dropped. */
        private String lastStaticReject = "";

        public String staticRejectDescribe() {
            return lastStaticReject;
        }

        /** Alpha per glass rect, same order as the list returned by rects(). */
        public List<Float> fades() {
            return lastFades;
        }

        /** OEM content Views per glass rect, same order (never misaligned). */
        public List<List<View>> contents() {
            return lastContents;
        }

        /**
         * ULTRA-CHEAP motion token read in the CURRENT pre-draw frame, BEFORE
         * deciding whether the full scan is needed (0.3.3, hardened 0.3.4).
         *
         * History: 0.3.1 used `moving`, an OUTPUT of the PREVIOUS scan, so the
         * first 64 ms of every fling was stale. 0.3.2 scanned every frame
         * (perfect tracking, +3 ms idle cost). 0.3.3 made the token an INPUT,
         * but hashed only getTop() of the first 4 + last child of each list.
         * On a 120 Hz panel a fast fling regularly moves <1 px per frame, and
         * ROM overscroll / item animations move rows via translationY rather
         * than mTop. Both make an integer getTop() token REPEAT for several
         * consecutive frames while the cards are in fact still moving, so the
         * idle gate froze the glass MID-GESTURE -- the "fast scroll still does
         * not follow the finger" report.
         *
         * 0.3.4 therefore hashes, still with ZERO Rect allocation and no tree
         * walk:
         *   - the list's own scrollY + translationY (whole-list animations);
         *   - getTop() AND float translationY of EVERY visible child (<= 24),
         *     so sub-pixel / RenderNode motion is visible to the token;
         *   - the same pair for cached generic (external-app) cards.
         * 0.3.7 adds cached STATIC cards (pinned switch bars move by
         * translationY when the header collapses), plus alpha so a fading
         * header cannot leave a frozen glass rectangle behind.
         * A tracking-tail window in RenderController is the second safety net.
         */
        private static final int MAX_TOKEN_CHILDREN = 24;
        /** 0.3.9: collapsing-header ancestor chain depth. */
        private static final int MAX_TOKEN_ANCESTORS = 6;

        public long quickToken() {
            long h = 1469598103934665603L;
            for (WeakReference<View> ref : listCache) {
                View v = ref.get();
                if (!(v instanceof ViewGroup)) continue;
                ViewGroup g = (ViewGroup) v;
                h = mix(h, g.getScrollY());
                h = mix(h, g.getTop());
                h = mix(h, Float.floatToRawIntBits(g.getTranslationY()));
                // 0.3.9 L2 MOTION FIX. On L2 pages the OEM uses a collapsing
                // header (HwAppBarLayout top=-103 in dumpsys). The WHOLE
                // content subtree is shifted by offsetTopAndBottom() /
                // translationY while every RecyclerView child keeps the SAME
                // getTop()/scrollY. The 0.3.4 token therefore repeated for
                // the entire gesture, the idle gate classified it as static
                // and the glass froze -- the "和之前 1 级菜单一样的卡顿".
                // Hashing the first 6 ancestors (getTop + scrollY + float
                // translationY) makes container-level motion visible with
                // zero Rect allocation and no tree walk.
                ViewParent p = g.getParent();
                int depth = 0;
                while (p instanceof View && depth++ < MAX_TOKEN_ANCESTORS) {
                    View a = (View) p;
                    h = mix(h, a.getTop());
                    h = mix(h, a.getScrollY());
                    h = mix(h, Float.floatToRawIntBits(a.getTranslationY()));
                    p = a.getParent();
                }
                int n = g.getChildCount();
                h = mix(h, n);
                int limit = n < MAX_TOKEN_CHILDREN ? n : MAX_TOKEN_CHILDREN;
                for (int i = 0; i < limit; i++) {
                    View c = g.getChildAt(i);
                    if (c == null) continue;
                    h = mix(h, c.getTop());
                    h = mix(h, Float.floatToRawIntBits(c.getTranslationY()));
                }
                if (n > limit) {
                    View last = g.getChildAt(n - 1);
                    if (last != null) {
                        h = mix(h, last.getTop());
                        h = mix(h, Float.floatToRawIntBits(last.getTranslationY()));
                    }
                }
            }
            for (WeakReference<View> ref : genericCache) {
                View v = ref.get();
                if (v == null) continue;
                h = mix(h, v.getTop());
                h = mix(h, Float.floatToRawIntBits(v.getTranslationY()));
            }
            for (WeakReference<View> ref : staticCache) {
                View v = ref.get();
                if (v == null) continue;
                h = mix(h, v.getTop());
                h = mix(h, Float.floatToRawIntBits(v.getTranslationY()));
                h = mix(h, Float.floatToRawIntBits(v.getAlpha()));
            }
            return h;
        }

        public List<Block> scan(View decor, ConfigContract.Snapshot cfg) {
            return scan(decor, cfg, false, decor);
        }

        public List<Block> scan(View decor, ConfigContract.Snapshot cfg,
                                boolean allowGeneric, View overlay) {
            List<Block> all = scanAllRows(decor, overlay, allowGeneric);
            // 0.5.0-p149: 最后一道防线——任何块的 rows 落在排除容器子树内
            // 就整块丢弃。static/generic/list 三条路径逐条拦可能漏(尤其
            // 进页面首帧的页面级大块闪现)，这里统一兜住。
            all = dropExcludedBlocks(all);
            lastBlocks = all;
            if (cfg == null || all.isEmpty()) return all;
            boolean group = cfg.asBool(ConfigContract.Param.GLASS_GROUP_CARDS);
            boolean standalone = cfg.asBool(ConfigContract.Param.GLASS_STANDALONE_CARDS);
            if (group && standalone) return all;
            List<Block> out = new ArrayList<Block>();
            for (Block b : all) {
                if (b.kind == Kind.GROUP ? group : standalone) out.add(b);
            }
            lastBlocks = out;
            return out;
        }

        /**
         * 0.5.0-p149: 丢掉任一 row 落在排除容器子树内的整块。与三条发现
         * 路径的入口检查形成互补的统一防线，专治"进入首帧页面级大块闪现"。
         */
        private List<Block> dropExcludedBlocks(List<Block> in) {
            if (in == null || in.isEmpty()) return in;
            List<Block> out = new ArrayList<Block>(in.size());
            for (Block b : in) {
                boolean excluded = false;
                for (View row : b.rows) {
                    if (isUnderExcludedContainer(row)
                            || hasExcludedChild(row)
                            || isNotifyHeaderRow(row)) { excluded = true; break; }
                }
                if (!excluded) out.add(b);
            }
            return out;
        }

        /**
         * 0.5.0-p160: 隐私/钱包 ScrollView 的滚动感知已由 collectLists 收编
         * listCache 解决(quickToken 感知，motion 实测从 1/7 升到 245)，玻璃
         * 行本来由 static/generic 路径发现。p159 曾在此额外生成独立 row，
         * 与 static 发现【双重收编】⇒ scan 出现完全重复块(750+168x1272 两
         * 次)——用户反馈"不太好"。本版撤销行生成，只保留有界的 OEM 背景
         * strip(剥白)，不再产出玻璃 row，消除重复。L1/其它页天然 no-op。
         */
        private static void stripScrollRows(View v, Rect listClip,
                                            List<Row> rows, Set<View> strip,
                                            int depth, int[] budget) {
            if (v == null || depth > MAX_TREE_DEPTH || budget[0]-- <= 0) return;
            if (v.getVisibility() != View.VISIBLE) return;
            String rid = resourceName(v);
            boolean privacyRow = "item_card".equals(rid)
                    || "item_with_summary_card".equals(rid);
            boolean walletRow = "hwlistpattern_parent".equals(rid);
            if (privacyRow || walletRow) {
                strip.add(v);   // OEM 白底照剥
                // 0.5.0-p165: 隐私/钱包卡在 ScrollView 里、不是 RecyclerView,
                // static 发现只收 4 行 ⇒ 红框区 11 行没玻璃(用户截图证实)。
                // 这里为这些行生成玻璃 row。去重交给 step2 static 循环的
                // overlapsRows:重叠>50% 的 static 卡跳过,每行只生成一次,
                // 避免 p159 双重收编的重复块。listClip 让滚动跟手。
                try {
                    Rect r = new Rect();
                    if (v.getGlobalVisibleRect(r) && r.height() > 0
                            && r.width() >= 400
                            && viewHeight(v) >= MIN_ROW_H_PX
                            && r.top >= MIN_TOP_PX
                            && !overlapsRows(rows, new RectF(r))) {
                        rows.add(new Row(r, v, viewHeight(v), listClip));
                    }
                } catch (Throwable ignored) {
                }
                return;  // 行内不再递归,避免内层子 View 重复成块
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                int n = g.getChildCount();
                for (int i = 0; i < n; i++) {
                    stripScrollRows(g.getChildAt(i), listClip, rows, strip,
                            depth + 1, budget);
                }
            }
        }

        public List<Block> scan(View decor) {
            return scanAllRows(decor, decor, false);
        }

        /**
         * ONE grouping pass for ALL three card sources (0.3.7). Static cards
         * and generic cards simply become extra Rows, so the very same
         * gap-based grouping and the very same rects() template apply to
         * 整块/分块 on L1/L2/L3 and in external apps. No special cases.
         */
        private List<Block> scanAllRows(View decor, View overlay,
                                        boolean allowGeneric) {
            lastVisibleRows = Collections.emptyList();
            if (decor == null) return Collections.emptyList();
            Rect decorRect = new Rect();
            decor.getGlobalVisibleRect(decorRect);
            int screenW = decorRect.width();
            if (screenW <= 0) return Collections.emptyList();

            List<Row> rows = new ArrayList<Row>();
            // LinkedHashSet: O(1) dedup AND deterministic iteration order, so
            // rowSignature() cannot flip spuriously between frames.
            Set<View> strip = new LinkedHashSet<View>();
            // 0.5.0-p165: 本帧是否由隐私/钱包 ScrollView 生成过玻璃行;step2
            // static 循环据此去重,避免同一行被 static + scroll 双重收编。
            boolean scrollRowsAdded = false;

            // 0.4.0 FRAME-PATH CONTRACT (restores the 0.3.4 rule).
            // 0.3.8/0.3.9 grew this loop into O(rows x children) every frame
            // (one-level child scan + isChrome() resource lookups), which is
            // the "L2 跟手迟钝掉帧" the user reported. Stripping is LATCHED,
            // so a background removed once can never come back on a later
            // frame; only a freshly rebound row needs re-checking, and that
            // is its OWN getBackground() (saveAndClear's fast path). Deep
            // wrappers are discovered by the DISCRETE walkStatic() and
            // cached in staticStripCache. This loop is now strictly
            // O(visible rows + cached cards), with no recursion and no
            // getResources() lookup in the frame path.
            //
            // (1) Direct children of RecyclerViews (verified L1/L2/L3 path).
            for (View list : lists(decor)) {
                if (!(list instanceof ViewGroup)) continue;
                ViewGroup g = (ViewGroup) list;
                // 0.5.0-p130: 账号安全页(EXT_ID, com.hihonor.id)的四卡列表按
                // resource-id 识别——该 id 账号中心独有，L1/L2/L3 及其它 app
                // 都没有，天然 no-op。
                //   #account_center_list_other (4 张方卡)：用户要求【有玻璃底】，
                //     但卡宽 542/1272=0.43 < MIN_WIDTH_RATIO 0.75 被宽度门拒，
                //     只对这 4 卡放宽宽度门到 0.40（窄门控 + 实测尺寸）。
                //   p129 误删了 #account_center_list (5 行资料区)的玻璃；用户
                //     截图红框指出删错：那 5 行玻璃要保留，要删的是顶部盾牌
                //     大块的白。p130 已撤销该误删，5 行恢复原路径。
                String listRid = resourceName(list);
                boolean squareCardList = "account_center_list_other".equals(listRid);
                // Strip the list container's own Drawable too. Its Canvas
                // self-draw pass is handled separately by
                // OemCardSuppressor (independent of this strip set).
                strip.add(list);
                Rect listClip = null;
                try {
                    Rect lr = new Rect();
                    if (list.getGlobalVisibleRect(lr) && lr.height() > 0) {
                        listClip = lr;
                    }
                } catch (Throwable ignored) {
                }
                // 0.5.0-p165: 隐私/钱包滚动区是 ScrollView，直接子项是整页
                // wrapper(1272x2800)不能当一行；其 item_card 行不在 RecyclerView,
                // static 只收 4 行(红框 11 行没玻璃)。这里有界递归内层行生成
                // 玻璃 row,step2 static 循环用 overlapsRows 去重(重叠>50%跳过),
                // 每行只生成一次,避免 p159 双重收编重复块。listClip 跟手滚动。
                if ("sv_container".equals(listRid)
                        || "traffic_card_wallet_scroll_view".equals(listRid)) {
                    int before = rows.size();
                    stripScrollRows(g, listClip, rows, strip, 0,
                            new int[]{SCROLL_ROW_BUDGET});
                    scrollRowsAdded = rows.size() > before;
                    continue;
                }
                for (int i = 0; i < g.getChildCount(); i++) {
                    View child = g.getChildAt(i);
                    if (child == null || child.getVisibility() != View.VISIBLE) continue;
                    strip.add(child);
                    // 0.5.0-p147: 主题预览页的预览卡与浅灰块走 list 路径进
                    // rows(不是 static/generic)，p146 只排了后两者。这里
                    // 同样排除——strip 照做(剥 OEM 白底)，只是不生成玻璃
                    // row。精确 id+祖先链，L1/其它页 no-op。
                    if (isUnderExcludedContainer(child)) continue;
                    // p155: list row 外层无 id、子树含排除容器(绿洲护眼页
                    // recommend_footer)时同样不生成玻璃；strip 仍照做。
                    if (hasExcludedChild(child)) continue;
                    // p158: 通知和状态栏页(head布局无 id LinearLayout 528..2250,
                    // 高1722)作为 all_package_manager_listview 的直接子项被
                    // 收进 list row，产出覆盖整页的大玻璃，叠在内部各 card_*
                    // 分块之上(用户："玻璃上下覆盖")。该头布局里的三行
                    // card_capsule/status_bar/control_center 本应各自有缝。
                    // 父容器 id 页独有 + 行高超 1000px 双门控，跳过这一整行
                    // 的玻璃(内部子卡片仍各自生成)，strip 照常；L1/其它页 no-op。
                    if (isNotifyHeaderRow(child)) continue;
                    Rect r = new Rect();
                    if (!child.getGlobalVisibleRect(r)) continue;
                    // 0.4.0-p20 CONTINUOUS BLOCK EDGE.
                    //
                    // This used to drop the row as soon as its CLIPPED height
                    // fell below EdgeFade.MIN_VISIBLE_PX (6 px). The block's
                    // rect is the UNION of the rows' clipped rects, so the
                    // moment that row was dropped the union lost 6 px AND the
                    // 63 px inter-row gap above the next row -- a one-frame
                    // 69 px snap of the block's bottom (or top) edge, repeated
                    // for EVERY row that scrolls past. At 168 px per row that
                    // is a visible flicker several times per drag: exactly the
                    // "拖动时...一些闪烁的块" the user reported.
                    //
                    // Keeping the row until it is COMPLETELY invisible makes
                    // the union continuous (its bottom end is clamped by the
                    // list's clip rect and shrinks smoothly). The card still
                    // fades out, because EdgeFade.alpha() returns 0 for any
                    // visible height <= MIN_VISIBLE_PX -- so nothing lingers.
                    if (r.height() <= 0) continue;
                    // LAID-OUT size, not the clipped rect, decides whether
                    // this is a card. A row scrolling out keeps its glass
                    // rect until EdgeFade finishes.
                    float fullH = viewHeight(child);
                    if (fullH < MIN_ROW_H_PX) continue;
                    float fullW = viewWidth(child);
                    // p130: 撤销 p129 的 noGlassRows 误删——5 行资料区的玻璃要保留。
                    // 0.5.0-p129: 账号安全 4 张方卡宽度只有 0.43 屏宽，对该列表
                    // 单独放宽宽度门到 0.40，并加实测尺寸(高 450..560)防止把
                    // 内层 444/443 宽的嵌套子层收进来(它们是子 View 不是直接子项)。
                    float widthGate = squareCardList ? 0.40f : MIN_WIDTH_RATIO;
                    if (fullW < screenW * widthGate) continue;
                    if (squareCardList && (fullH < 450f || fullH > 560f)) continue;
                    if (r.top < MIN_TOP_PX) continue;
                    // isChrome() deliberately NOT called here: a
                    // RecyclerView's direct children are preference rows,
                    // never toolbar chrome, and the resource lookup cost
                    // ~15 getResourceEntryName() calls per frame.
                    rows.add(new Row(r, child, fullH, listClip));
                }
            }

            // (2) Static OEM cards OUTSIDE any RecyclerView, permanently ON.
            //     Discovery supplies GEOMETRY + the deep strip cache;
            //     stripping itself is latched and time-independent.
            maybeDiscoverStatic(decor, overlay);
            int sNull = 0, sDead = 0, sRect = 0, sH = 0, sW = 0, sOk = 0;
            for (WeakReference<View> ref : staticCache) {
                View v = ref.get();
                if (v == null) { sNull++; continue; }
                strip.add(v);       // keep stripping even when GONE / faded
                if (!staticAlive(v)) { sDead++; continue; }
                Rect r = new Rect();
                if (!v.getGlobalVisibleRect(r)) { sRect++; continue; }
                // 0.4.0-p20: same continuous-edge rule as the list path above
                // -- only drop a static card once it is COMPLETELY gone.
                if (r.height() <= 0) { sH++; continue; }
                if (r.width() < screenW * STATIC_MIN_W_RATIO) { sW++; continue; }
                // 0.5.0-p165: 隐私/钱包 ScrollView 已由 step1 生成内层行玻璃,
                // static 发现的那几张卡若与这些行重叠>50% 就跳过,避免同一区域
                // 双重收编(p159 重复块)。其它页 scrollRowsAdded=false,逐字不变。
                if (scrollRowsAdded && overlapsRows(rows, new RectF(r))) continue;
                sOk++;
                rows.add(new Row(r, v, viewHeight(v), null, true));
            }
            lastStaticReject = "null=" + sNull + " dead=" + sDead
                    + " rect=" + sRect + " h=" + sH + " w=" + sW + " ok=" + sOk;
            for (WeakReference<View> ref : staticStripCache) {
                View v = ref.get();
                if (v != null) strip.add(v);
            }

            // (3) External bespoke cards, ADDITIVE.
            if (allowGeneric && overlay != null) {
                for (Block b : genericBlocks(decor, overlay)) {
                    for (View gv : b.rows) {
                        if (gv != null) strip.add(gv);
                    }
                    if (b.rows.isEmpty()) continue;
                    if (overlapsRows(rows, b.rect)) continue;
                    float fh = viewHeight(b.rows.get(0));
                    rows.add(new Row(new Rect((int) b.rect.left, (int) b.rect.top,
                            (int) b.rect.right, (int) b.rect.bottom),
                            b.rows.get(0), fh, null));
                }
            }

            // Expose the ungrouped STRIP set BEFORE the grouping filters run.
            List<View> visible = new ArrayList<View>(strip.size() + rows.size());
            visible.addAll(strip);
            for (Row row : rows) {
                if (!strip.contains(row.view)) visible.add(row.view);
            }
            lastVisibleRows = visible;

            // 0.4.0-p34 HISTORY (hypothesis DISPROVEN, kept only for a
            // consistent sort key). Sorting by the clipped rect.top while
            // grouping by trueTop was suspected of making the WLAN switch bar
            // flip around the first list row. p34 tried sorting by trueTop;
            // the BARJUMP count stayed at 85/125 frames, so the sort order was
            // NEVER the cause. trueTop sorting is harmless and matches the
            // grouping key, so it stays; the real fix is the FAMILY SPLIT in
            // the grouping loop below. L1 has no static cards and keeps its
            // exact accepted order.
            final boolean hasStatic = !staticCache.isEmpty();
            Collections.sort(rows, new Comparator<Row>() {
                @Override
                public int compare(Row a, Row b) {
                    if (hasStatic && a.trueTop != b.trueTop) {
                        return a.trueTop - b.trueTop;
                    }
                    return a.rect.top - b.rect.top;
                }
            });

            List<Block> blocks = new ArrayList<Block>();
            List<Row> current = new ArrayList<Row>();
            // 0.5.0-p166: 本帧新分界集合(写回 prevSplitRows)。键是 row 自己的
            // View,只在"prev→row 这对相邻行"上记录是否分界,与滚动位置无关。
            java.util.WeakHashMap<View, Boolean> nextSplit =
                    new java.util.WeakHashMap<View, Boolean>();
            // 0.4.0-p25: grouping produces the blocks in TOP-DOWN order. The
            // first and last block are the only ones that can be pinned to the
            // viewport edge, so pass that flag down instead of guessing per-row.
            boolean firstGroup = true;
            for (Row row : rows) {
                if (!current.isEmpty()) {
                    Row prev = current.get(current.size() - 1);
                    // 0.5.0-p166: 分组滞回。familyBoundary(static/list 不同族)
                    // 必须无条件分界,不参与滞回;只有纯 gap 分界才做帧间稳定。
                    boolean familyBoundary = prev.isStatic != row.isStatic;
                    float gap = (float) row.trueTop - prev.trueBottom;
                    boolean split;
                    if (familyBoundary) {
                        split = true;
                    } else {
                        Boolean last = prevSplitRows.get(row.view);
                        boolean lastSplit = (last != null && last);
                        if (gap > GROUP_GAP_PX + GROUP_HYST_PX) {
                            split = true;      // 远超阈值:必分
                        } else if (gap < GROUP_GAP_PX - GROUP_HYST_PX) {
                            split = false;     // 远低阈值:必合
                        } else {
                            split = lastSplit; // 滞回带内:沿用上一帧
                        }
                    }
                    nextSplit.put(row.view, split);
                    if (split) {
                        blocks.add(toBlock(current, firstGroup, false));
                        current = new ArrayList<Row>();
                        firstGroup = false;
                    }
                    // 0.4.0-p35 不变量保留:static card 与 list row 是不同族,
                    // familyBoundary 已在上方无条件分界,不受滞回/滚动影响。
                }
                current.add(row);
            }
            if (!current.isEmpty()) {
                blocks.add(toBlock(current, firstGroup, true));
            }
            // 0.5.0-p166: 写回本帧分界表供下一帧滞回(整表替换,旧 View 自动淘汰)。
            prevSplitRows.clear();
            prevSplitRows.putAll(nextSplit);
            return blocks;
        }

        /** A card at least this fraction of the template width is "full width". */
        private static final float UNIFORM_WIDTH_RATIO = 0.70f;

        /**
         * Screen-coordinate blocks -> overlay-local RectFs.
         *
         * 0.3.6 UNIFORM TEMPLATE. ONE pair of left/right edges is computed
         * from the decor's visible width and applied to EVERY GROUP and
         * STANDALONE card that is already close to full width. Vertical edges
         * stay per-block (rows have different heights). This is the "模板"
         * the user asked for: the very same geometry rule on L1/L2/L3 and on
         * both whole blocks and separated blocks -- no L3 special case, no
         * extra View layer, no detector, no per-frame tree walk.
         * 0.3.7 static cards enter this SAME path, so a WLAN switch bar, an
         * account header and a list group all end up with identical edges.
         */
        public List<RectF> rects(View decor, View overlay,
                                 ConfigContract.Snapshot cfg, float density) {
            return rects(decor, overlay, cfg, density, false);
        }

        public List<RectF> rects(View decor, View overlay,
                                 ConfigContract.Snapshot cfg, float density,
                                 boolean allowGeneric) {
            if (decor == null || overlay == null) return Collections.emptyList();
            int[] loc = new int[2];
            try {
                overlay.getLocationOnScreen(loc);
            } catch (Throwable t) {
                return Collections.emptyList();
            }
            float hInset = cfg == null ? 0f
                    : cfg.asFloat(ConfigContract.Param.GEO_H_INSET_DP) * density;
            float vInset = cfg == null ? 0f
                    : cfg.asFloat(ConfigContract.Param.GEO_V_INSET_DP) * density;

            boolean uniform = cfg == null
                    || cfg.asBool(ConfigContract.Param.GEO_UNIFORM_CARD_WIDTH);
            // 0.4.0-p21: the visible screen rect, needed by BOTH the width
            // template and the edge-fade maths below.
            Rect decorRect = new Rect();
            boolean haveDecorRect = false;
            try {
                haveDecorRect = decor.getGlobalVisibleRect(decorRect);
            } catch (Throwable ignored) {
            }
            float tplLeft = Float.NaN, tplRight = Float.NaN;
            if (uniform && haveDecorRect && decorRect.width() > 0) {
                tplLeft = decorRect.left - loc[0] + hInset;
                tplRight = decorRect.right - loc[0] - hInset;
            }

            float fadePx = EdgeFade.FADE_DP * (density > 0f ? density : 1f);
            List<RectF> out = new ArrayList<RectF>();
            List<Float> fades = new ArrayList<Float>();
            List<List<View>> contents = new ArrayList<List<View>>();
            for (Block b : scan(decor, cfg, allowGeneric, overlay)) {
                float left = b.rect.left - loc[0] + hInset;
                float right = b.rect.right - loc[0] - hInset;
                if (uniform && !Float.isNaN(tplLeft)
                        && tplRight - tplLeft > 8f
                        && (right - left) >= (tplRight - tplLeft) * UNIFORM_WIDTH_RATIO) {
                    left = tplLeft;
                    right = tplRight;
                }
                RectF r = new RectF(left,
                        b.rect.top - loc[1] + vInset,
                        right,
                        b.rect.bottom - loc[1] - vInset);
                // 0.4.0-p38 EXTERNAL-PROCESS OFF-SCREEN BLOCKS.
                //
                // The generic path on com.hihonor.systemmanager (battery) can
                // emit blocks that are ENTIRELY outside the visible decor:
                //   backdrop FAIL why=2 rect=49,-550 1174x540 snap=1272x2800
                // (top=-550, h=540 -> bottom=-10). rects() used to accept any
                // rect wider/taller than 8 px, so these became VISIBLE glass
                // containers: every frame sampled a backdrop window that
                // clamps to empty, rebuilt a shader effect for a View that can
                // never be seen, and tripped INNERBLINK / bdFail counters.
                //
                // Only FULLY invisible blocks are dropped; a partially clipped
                // one keeps its continuous outer edge (never a binary edge
                // cull -- see p20). Gated on allowGeneric, which is true ONLY
                // for external packages, so the accepted L1/L2/L3 geometry is
                // byte-for-byte unchanged.
                if (allowGeneric && haveDecorRect) {
                    float vl = decorRect.left - loc[0];
                    float vt = decorRect.top - loc[1];
                    float vr = decorRect.right - loc[0];
                    float vb = decorRect.bottom - loc[1];
                    if (r.right <= vl || r.left >= vr
                            || r.bottom <= vt || r.top >= vb) continue;
                }
                // 0.5.0-p170: 高度硬门 8f→0f。块滚到视口边缘时可见高度在
                // 8px 附近抖动⇒块数 3↔4 交替⇒容器反复 hide/show+重录
                // (reappears=8,真机 REAPPEAR 日志 nr3↔4 证实)。降为 >0 让
                // 部分可见的块继续输出(容器保持 VISIBLE),由已有 EdgeFade
                // (visibleH<=6px⇒alpha=0)保证不露小玻璃条——块数稳定但视觉
                // 无变化。宽度门 >8f 与 p38 完全离屏检查(828行)逐字不变。
                if (r.width() > 8f && r.height() > 0f) {
                    out.add(r);
                    // 0.3.8 ONE reusable edge-fade template for EVERY card
                    // family. b.fullH is the UNCLIPPED height, so a short
                    // standalone card is never dimmed by mistake.
                    //
                    // 0.4.0-p21: the fade must follow how much of the block is
                    // actually ON SCREEN, not b.rect.height() -- since p21 the
                    // outer edges extend past the viewport (that is what makes
                    // them continuous), so the raw height no longer means
                    // "visible".
                    float visH = b.rect.height();
                    if (haveDecorRect) {
                        float visTop = Math.max(b.rect.top, decorRect.top);
                        float visBottom = Math.min(b.rect.bottom, decorRect.bottom);
                        visH = Math.max(0f, visBottom - visTop);
                    }
                    fades.add(EdgeFade.alpha(visH, b.fullH, fadePx));
                    contents.add(b.rows);
                }
            }
            lastFades = fades;
            lastContents = contents;
            return out;
        }

        private List<View> lists(View decor) {
            List<View> out = new ArrayList<View>();
            boolean valid = !listCache.isEmpty();
            Iterator<WeakReference<View>> it = listCache.iterator();
            while (it.hasNext()) {
                View v = it.next().get();
                if (v == null || !v.isAttachedToWindow()
                        || v.getVisibility() != View.VISIBLE) {
                    it.remove();
                    valid = false;
                    continue;
                }
                out.add(v);
            }
            if (!valid) {
                listCache.clear();
                out.clear();
                collectLists(decor, out, 0);
                for (View v : out) listCache.add(new WeakReference<View>(v));
            }
            return out;
        }

        // ---- 0.3.7 static white-card discovery ------------------------------

        /**
         * Discrete, bounded, cached. NEVER a per-frame recursion: the 0.3.4 p8
         * regression came from a full DecorView walk inside the frame path.
         * A forced (attach / page change) discovery is allowed once; all other
         * walks require an idle frame and a 400 ms / 1500 ms throttle.
         */
        private void maybeDiscoverStatic(View decor, View overlay) {
            long now = SystemClock.uptimeMillis();
            long sig = staticStructureSig(decor);
            boolean changed = sig != lastStaticStructureSig;
            boolean stale = !staticCache.isEmpty() && !allStaticAlive();
            if (!staticDirty && !changed && !stale) return;
            boolean forced = staticDirty;
            // A non-forced walk is allowed ONLY on a settled frame.
            if (!forced && !discoveryAllowed) return;
            if (now - lastStaticDiscoverMs < staticIntervalMs) return;
            lastStaticDiscoverMs = now;
            lastStaticStructureSig = sig;
            staticDirty = false;
            discoverStatic(decor, overlay);
            staticIntervalMs = staticCache.isEmpty()
                    ? STATIC_DISCOVER_EMPTY_MS : STATIC_DISCOVER_MS;
        }

        /**
         * 0.4.0: O(1) structure fingerprint. The 0.3.7 version walked 64
         * nodes EVERY frame; on L2 that tree walk plus the one-level child
         * scan plus 15 getResourceEntryName() calls is what made scrolling
         * feel heavy. Page swaps are already signalled by
         * invalidateStatic() from RenderController.onLifecycle()/refresh(),
         * so the frame path only needs decor identity, child count and the
         * cached list identities.
         */
        private long staticStructureSig(View decor) {
            long h = 1469598103934665603L;
            h = mix(h, System.identityHashCode(decor));
            if (decor instanceof ViewGroup) {
                h = mix(h, ((ViewGroup) decor).getChildCount());
            }
            for (View v : lists(decor)) {
                h = mix(h, System.identityHashCode(v));
                // 0.5.0-p127: a list's IDENTITY does not change when the ROM
                // lazily inflates more static cards into it (账号安全: the
                // first forced pass saw only 登录方式; 指纹验证/安全中心/
                // 更改密码 arrived later). Without the child count the sig
                // stayed identical, stale=false (cached card still attached),
                // staticDirty=false => maybeDiscoverStatic early-returned
                // forever, so the late cards only appeared after the user
                // scrolled (which called invalidateStatic). Folding getChildCount()
                // in makes late inflation signal changed=true, and the existing
                // settled-frame + 600ms throttle gate a single bounded re-scan.
                // getChildCount() is O(1) -- no per-child walk on the frame path.
                h = mix(h, (v instanceof ViewGroup)
                        ? ((ViewGroup) v).getChildCount() : 0);
            }
            for (WeakReference<View> ref : staticCache) {
                View v = ref.get();
                h = mix(h, System.identityHashCode(v));
            }
            return h;
        }

        private void discoverStatic(View decor, View overlay) {
            prune(staticCache);
            prune(staticStripCache);
            Rect dr = new Rect();
            if (!decor.getGlobalVisibleRect(dr)) return;
            int sw = dr.width(), sh = dr.height();
            if (sw <= 0 || sh <= 0) return;
            List<View> cards = new ArrayList<View>();
            List<View> strips = new ArrayList<View>();
            int[] budget = {STATIC_MAX_NODES};
            // 0.5.0-p120: fresh SQUARECARD diagnostic budget per discovery pass.
            sSquareCardLines = 0;
            walkStatic(decor, overlay, cards, strips, 0, budget, sw, sh);
            for (View v : cards) {
                if (!cached(staticCache, v) && staticCache.size() < STATIC_MAX_CARDS) {
                    staticCache.add(new WeakReference<View>(v));
                }
            }
            for (View v : strips) {
                if (!cached(staticStripCache, v)
                        && staticStripCache.size() < STATIC_MAX_STRIPS) {
                    staticStripCache.add(new WeakReference<View>(v));
                }
            }
            lastStaticCards = staticCache.size();
            lastStaticStrips = staticStripCache.size();
            lastStaticNodes = STATIC_MAX_NODES - budget[0];
        }

        /**
         * Pre-order, hard node budget. Keeps the OUTERMOST opaque container of
         * each nesting chain (hasAncestorIn), rejects full-screen roots by area
         * (0.58 x screen) and chrome by name. Children of an accepted card are
         * put in the STRIP-ONLY set so a white rounded drawable buried two or
         * three levels deep is still removed -- the frame-path strip helper
         * only looks one level down.
         */
        private static void walkStatic(View v, View overlay,
                                       List<View> cards, List<View> strips,
                                       int depth, int[] budget,
                                       int sw, int sh) {
            if (v == null || v == overlay || depth > STATIC_MAX_DEPTH) return;
            if (budget[0]-- <= 0) return;
            if (isInjectedLayer(v)) return;
            // 0.5.0-p146: 排除容器连同整棵子树不进卡片发现。
            if (isUnderExcludedContainer(v)) return;
            int screenArea = sw * sh;
            try {
                if (v.getVisibility() == View.VISIBLE
                        && v.isAttachedToWindow()
                        && v.getAlpha() > 0.05f
                        && !isStaticChrome(v)
                        && !isPageDecorContainer(v)) {
                    Rect r = new Rect();
                    if (v.getGlobalVisibleRect(r)
                            && r.width() >= sw * STATIC_MIN_W_RATIO
                            && r.height() >= STATIC_MIN_H_PX
                            && (float) r.width() * r.height()
                                    <= screenArea * STATIC_MAX_AREA_RATIO) {
                        boolean hasBg = v.getBackground() != null
                                || v.getForeground() != null;
                        // 0.5.0-p120: the account-security four square cards
                        // carry NO drawable and have no "card" name, so the old
                        // `hasBg || isCardName` gate never even admitted them;
                        // and being inside #account_center_list_other they
                        // would only land in strips (no glass rect). A narrow
                        // STRUCTURAL gate routes just these four into cards.
                        // Every non-matching View keeps the byte-identical path.
                        boolean squareCard = isAccountSquareCard(v);
                        if (squareCard) {
                            if (cards.size() < STATIC_MAX_CARDS
                                    && !hasAncestorIn(cards, v)
                                    && !overlapsCards(cards, r)) {
                                cards.add(v);
                                logSquareCard(v, r);
                            }
                        } else if (hasBg || isCardName(v)) {
                            if (insideRecyclerView(v)) {
                                if (strips.size() < STATIC_MAX_STRIPS) strips.add(v);
                            } else if (cards.size() < STATIC_MAX_CARDS
                                    && !hasAncestorIn(cards, v)
                                    && !overlapsCards(cards, r)) {
                                cards.add(v);
                            } else if (strips.size() < STATIC_MAX_STRIPS) {
                                strips.add(v);
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    walkStatic(g.getChildAt(i), overlay, cards, strips,
                            depth + 1, budget, sw, sh);
                }
            }
        }

        private static boolean isInjectedLayer(View v) {
            try {
                Object tag = v.getTag();
                if (tag != null && tag.toString().startsWith("magicglass.")) return true;
            } catch (Throwable ignored) {
            }
            return v.getClass().getName().toLowerCase().contains("magicglass");
        }

        /**
         * Reject chrome, tiny controls and our own injected layers. "blur" /
         * "header" / "switch" are only SOFT exclusions: Honor names several
         * REAL cards HnBlurCardXxx / xxx_header_card, so a card-named View is
         * never rejected by a soft word. Hard chrome (toolbar/decor/...) is
         * always rejected -- it must keep its own background.
         */
        private static boolean isStaticChrome(View v) {
            String cls = v.getClass().getName().toLowerCase();
            String rn = resourceName(v);
            String[] hard = {"toolbar", "appbar", "statusbar", "navigationbar",
                    "decorview", "gradientblur", "hntransparent", "scrollbar",
                    "magicglass", "textview", "imageview", "button",
                    "checkbox", "edittext", "progress"};
            for (String b : hard) {
                if (cls.contains(b)) return true;
                if (rn.length() > 0 && rn.contains(b)) return true;
            }
            if (cls.contains("card") || (rn.length() > 0 && rn.contains("card"))) {
                return false;
            }
            String[] soft = {"search", "header", "blur", "switch"};
            for (String b : soft) {
                if (cls.contains(b)) return true;
                if (rn.length() > 0 && rn.contains(b)) return true;
            }
            return false;
        }

        private static boolean isCardName(View v) {
            String cls = v.getClass().getName().toLowerCase();
            if (cls.contains("card")) return true;
            String rn = resourceName(v);
            return rn.length() > 0 && rn.contains("card");
        }

        /**
         * 0.5.0-p145: 页面级装饰容器，不是可衬玻璃的卡片。它们 bg 常是透明
         * ColorDrawable(getBackground()!=null 满足旧 hasBg 门)或 fade 层，
         * 被 walkStatic/genericBlocks 误收成整页大玻璃。按精确 id 排除，
         * 这些 id 均为对应页独有，L1/L2/L3 及其它 app 天然 no-op。
         */
        private static boolean isPageDecorContainer(View v) {
            String rn = resourceName(v);
            if (rn.length() == 0) return false;
            return "theme_module_root".equals(rn)
                    || "theme_history_container".equals(rn)
                    || "super_device_settings_incard".equals(rn)
                    || "intro_card_layout".equals(rn)
                    || "intro_card_title".equals(rn)
                    || "top_gradient_bg".equals(rn)
                    // p146: 主题预览卡本身(用户要求不衬玻璃)，含其内浅灰块。
                    || "wallpaper_main_root".equals(rn)
                    || "wallpaper_history_image_container".equals(rn)
                    // p147: 浅灰块的祖先容器(ViewPager 外壳)，不在
                    // wallpaper_main_root 子树内，需单独排除。
                    || "history_viewpager_root".equals(rn)
                    // p155: 荣耀绿洲护眼页(oasis_intro_card, 1132x575)类名含
                    // card 被 walkStatic 收成整页玻璃块；该 id 此页独有，
                    // 排除后 intro_card_layout 子树一并连带(L1/其它页 no-op)。
                    || "oasis_intro_card".equals(rn)
                    // p155: 绿洲护眼页底部"是否在寻找其他设置项"卡，是
                    // RecyclerView 直接子项(无 id 外层)的子 View；玻璃块由
                    // 外层 list row 产出，向下精确检查命中此 id 即不生成玻璃。
                    || "recommend_footer".equals(rn)
                    // p156: 散热风扇页顶部宣传视频卡(1132x647, id 资源表唯一)，
                    // 与 oasis_intro_card 同构——卡片自带不透明图片，背后不该
                    // 再衬玻璃。此 id 该页独有，L1/其它页 no-op。
                    || "thermal_fan_video_play".equals(rn)
                    // p157: 通知和状态栏页(EXT_SYSTEMMANAGER)应用列表行的内层
                    // 左容器(838x241)。整行已由 list row 产出 1230 宽玻璃，
                    // 这层子 View 又经 collectGeneric 生成 838 宽玻璃叠在上面
                    // (日志同 top 双块 2330+241x1230 / 2330+241x838)=重叠。
                    // 排除它只去掉重复子块，整行玻璃与分块保持不变。
                    // 该 id 仅此列表行使用，L1/其它页 no-op。
                    || "notification_package_left_layout".equals(rn);
        }

        /**
         * 0.5.0-p146: 自身或其任一祖先是排除容器 ⇒ 连整棵子树都不进入
         * static/generic 卡片发现(预览卡下方那块浅灰玻璃是其子 View，
         * 只排除容器本身、仍递归子节点的话它会漏进来)。
         */
        private static boolean isUnderExcludedContainer(View v) {
            View cur = v;
            int d = 0;
            while (cur != null && d++ < MAX_TREE_DEPTH) {
                if (isPageDecorContainer(cur)) return true;
                // p156: 声音和触感页顶部"声随心动"横幅卡。card_view 是通用
                // id(资源表 46 次)不能单凭 id 排除；改为 id=card_view 且父链
                // 含页面独有 layout_protrait(1 次)。该卡 y534..1019，其兄弟
                // 声音模式三按钮(y1201+)不在子树内，不误伤。L1/其它页 no-op。
                if ("card_view".equals(resourceName(cur))
                        && hasAncestorResource(cur, "layout_protrait")) return true;
                ViewParent p = cur.getParent();
                cur = (p instanceof View) ? (View) p : null;
            }
            return false;
        }

        /** 有界自下查父链是否含指定 id（p156 声音横幅卡精确门）。 */
        private static boolean hasAncestorResource(View v, String id) {
            ViewParent p = v == null ? null : v.getParent();
            int d = 0;
            while (p instanceof View && d++ < MAX_TREE_DEPTH) {
                if (id.equals(resourceName((View) p))) return true;
                p = ((View) p).getParent();
            }
            return false;
        }

        /**
         * 0.5.0-p155: list row 的外层(ViewGroup)本身无 id，但子树里含排除
         * 容器(如绿洲护眼页 recommend_footer)。isUnderExcludedContainer 只
         * 自下查祖先，够不到这种"外层产出玻璃、内层才是要排除卡"的结构。
         * 有界向下查直接子节点命中即成立；只用于固定的一两层 list row，
         * 有界(MAX_TREE_DEPTH)，L1/其它页无该 id天然 no-op。
         */
        private static boolean hasExcludedChild(View v) {
            if (!(v instanceof ViewGroup)) return false;
            ViewGroup g = (ViewGroup) v;
            int n = g.getChildCount();
            for (int i = 0; i < n; i++) {
                View c = g.getChildAt(i);
                if (c == null) continue;
                if (isPageDecorContainer(c)) return true;
            }
            return false;
        }

        /**
         * 0.5.0-p158: 通知和状态栏页 all_package_manager_listview 的直接子项
         * 里，那个无 id 的头布局容器(laid-out 高 ~1722px)覆盖整个卡片区，
         * 被当成 list row 生成整页大玻璃，叠在内部 card_* 分块之上。用
         * "父容器 id 页独有 + 本行 laid-out 高 > 1000px"双门控跳过它，
         * 内部子卡片仍各自生成玻璃、strip 照常。L1/其它页无该 id no-op。
         */
        private static boolean isNotifyHeaderRow(View child) {
            if (child == null) return false;
            ViewParent p = child.getParent();
            if (!(p instanceof View)
                    || !"all_package_manager_listview".equals(resourceName((View) p))) {
                return false;
            }
            try {
                return viewHeight(child) > 1000f;
            } catch (Throwable t) {
                return false;
            }
        }

        private static boolean insideRecyclerView(View v) {
            ViewParent p = v.getParent();
            int depth = 0;
            while (p instanceof View && depth++ < MAX_TREE_DEPTH) {
                if (p.getClass().getName().endsWith("RecyclerView")) return true;
                p = ((View) p).getParent();
            }
            return false;
        }

        /**
         * 0.5.0-p120: narrow structural gate for the account-security four
         * square cards (p119 device evidence). Requirements, all measured:
         *  - own node is a ViewGroup (the four cards are LinearLayouts);
         *  - own size w in [500,600] and h in [450,560] px — this selects the
         *    542x498 / 541x498 OUTER cards and rejects the 444/443 px nested
         *    children by width;
         *  - a RecyclerView ancestor (bounded depth <= 6) whose resource entry
         *    is exactly "account_center_list_other" — an id unique to the
         *    account-security page (com.hihonor.id.AccountSecurityActivity).
         * Only structure + parent id are used: no slot parameter, no new import,
         * no reliance on background/foreground (the four cards have none).
         */
        private static boolean isAccountSquareCard(View v) {
            if (!(v instanceof ViewGroup)) return false;
            int w = v.getWidth(), h = v.getHeight();
            if (w < 500 || w > 600) return false;
            if (h < 450 || h > 560) return false;
            ViewParent p = v.getParent();
            int depth = 0;
            while (p instanceof View && depth++ < 6) {
                View pv = (View) p;
                if (pv instanceof ViewGroup
                        && pv.getClass().getName().endsWith("RecyclerView")
                        && "account_center_list_other".equals(resourceName(pv))) {
                    return true;
                }
                p = pv.getParent();
            }
            return false;
        }

        /** Bounded, throttled SQUARECARD diagnostic (max 8 lines per pass). */
        private static void logSquareCard(View v, Rect r) {
            if (sSquareCardLines >= 8) return;
            long now = SystemClock.uptimeMillis();
            if (now - sLastSquareCardLogMs < 150L) return;
            sLastSquareCardLogMs = now;
            sSquareCardLines++;
            GlassEntry.say("mg-state SQUARECARD id=" + resourceName(v)
                    + " bounds=" + r.left + "," + r.top + "," + r.right + ","
                    + r.bottom + " added=1");
        }

        private static boolean hasAncestorIn(List<View> cards, View v) {
            ViewParent p = v.getParent();
            int depth = 0;
            while (p instanceof View && depth++ < MAX_TREE_DEPTH) {
                for (View c : cards) {
                    if (c == p) return true;
                }
                p = ((View) p).getParent();
            }
            return false;
        }

        private static boolean overlapsCards(List<View> cards, Rect r) {
            float area = (float) r.width() * r.height();
            for (View c : cards) {
                Rect o = new Rect();
                if (!c.getGlobalVisibleRect(o)) continue;
                float ix = Math.max(0f, Math.min(r.right, o.right)
                        - Math.max(r.left, o.left));
                float iy = Math.max(0f, Math.min(r.bottom, o.bottom)
                        - Math.max(r.top, o.top));
                if (ix * iy > area * 0.55f) return true;
            }
            return false;
        }

        private static boolean overlapsRows(List<Row> rows, RectF r) {
            float area = r.width() * r.height();
            if (area <= 0f) return false;
            for (Row row : rows) {
                float ix = Math.max(0f, Math.min(r.right, row.rect.right)
                        - Math.max(r.left, row.rect.left));
                float iy = Math.max(0f, Math.min(r.bottom, row.rect.bottom)
                        - Math.max(r.top, row.rect.top));
                if (ix * iy > area * 0.50f) return true;
            }
            return false;
        }

        private static boolean staticAlive(View v) {
            try {
                return v.isAttachedToWindow()
                        && v.getVisibility() == View.VISIBLE
                        && v.getAlpha() > 0.05f;
            } catch (Throwable t) {
                return false;
            }
        }

        /**
         * Only DETACHED views are stale. A header that is temporarily GONE or
         * faded to alpha 0 must stay in the cache: syncRows() keeps stripping
         * it, so it can never flash back to an opaque white card when it
         * reappears. (0.3.2 hard-won rule: never restore OEM white cards on a
         * transient frame.)
         */
        private boolean allStaticAlive() {
            if (staticCache.isEmpty()) return false;
            for (WeakReference<View> ref : staticCache) {
                View v = ref.get();
                if (v == null) return false;
                try {
                    if (!v.isAttachedToWindow()) return false;
                } catch (Throwable t) {
                    return false;
                }
            }
            return true;
        }

        private static void prune(List<WeakReference<View>> cache) {
            Iterator<WeakReference<View>> it = cache.iterator();
            while (it.hasNext()) {
                View v = it.next().get();
                if (v == null) {
                    it.remove();
                    continue;
                }
                try {
                    if (!v.isAttachedToWindow()) it.remove();
                } catch (Throwable t) {
                    it.remove();
                }
            }
        }

        private static boolean cached(List<WeakReference<View>> cache, View v) {
            for (WeakReference<View> ref : cache) {
                if (ref.get() == v) return true;
            }
            return false;
        }

        // ---- generic (external app) cards -----------------------------------

        /**
         * Guarded generic detector for bespoke external-app cards.
         * Keeps the OUTERMOST opaque container of each nesting chain, rejects
         * chrome / tiny controls / full-screen roots / heavy overlaps, and
         * bails out entirely if the tree looks nothing like a card page.
         */
        private List<Block> genericBlocks(View decor, View overlay) {
            Rect dr = new Rect();
            if (!decor.getGlobalVisibleRect(dr)) return Collections.emptyList();
            int sw = dr.width(), sh = dr.height();
            if (sw <= 0 || sh <= 0) return Collections.emptyList();
            int screenArea = sw * sh;

            Iterator<WeakReference<View>> it = genericCache.iterator();
            while (it.hasNext()) {
                View v = it.next().get();
                if (v == null || !v.isAttachedToWindow()
                        || v.getVisibility() != View.VISIBLE) it.remove();
            }

            long now = SystemClock.uptimeMillis();
            if (genericCache.isEmpty() || now - lastGenericDiscoverMs > 1500L) {
                lastGenericDiscoverMs = now;
                List<View> found = new ArrayList<View>();
                int[] budget = {GENERIC_MAX_NODES};
                collectGeneric(decor, overlay, found, 0, budget);
                for (View v : found) {
                    if (!genericCached(v) && genericCache.size() < 40) {
                        genericCache.add(new WeakReference<View>(v));
                    }
                }
            }

            List<View> candidates = new ArrayList<View>(genericCache.size());
            for (WeakReference<View> ref : genericCache) {
                View v = ref.get();
                if (v != null && v.isAttachedToWindow()
                        && v.getVisibility() == View.VISIBLE) candidates.add(v);
            }

            List<Block> out = new ArrayList<Block>();
            Set<View> accepted = new HashSet<View>();
            int totalArea = 0;
            for (View v : candidates) {
                if (out.size() >= GENERIC_MAX_CARDS) break;
                if (hasAcceptedAncestor(v, accepted)) continue;
                Rect r = new Rect();
                if (!v.getGlobalVisibleRect(r)) continue;
                // 0.5.0-p144: ViewPager 页内 View 的 getGlobalVisibleRect 会被
                // 横向滑动逐帧裁剪(宽 1272→981、高 1846→560)，当成玻璃几何
                // 就产生 3-4 层块边跳变。对这类 View 改用【完整布局矩形】
                // (getLocationOnScreen + getWidth/getHeight，与 p21 的
                // trueTop/trueBottom 同款 unclipped 思想)：几何不再随滑动
                // 裁剪跳变，跳闪消失；非 ViewPager 页逐字节不变，L1 no-op。
                // 仅在仍与屏幕有交集时接受，避免把完全离屏的页算进来。
                if (isInViewPager(v)) {
                    Rect full = unclippedScreenRect(v);
                    if (full != null && full.left < dr.right && full.right > dr.left
                            && full.top < dr.bottom && full.bottom > dr.top) {
                        // 0.5.0-p145: clamp 到 decor 屏幕内，避免 ViewPager 页
                        // 完整布局宽度(实测 1372)超过屏宽(1272)造出过宽玻璃。
                        full.left = Math.max(full.left, dr.left);
                        full.right = Math.min(full.right, dr.right);
                        full.top = Math.max(full.top, dr.top);
                        full.bottom = Math.min(full.bottom, dr.bottom);
                        r = full;
                    }
                }
                int w = r.width(), h = r.height();
                if (w < sw * GENERIC_MIN_W_RATIO || h < GENERIC_MIN_H_PX) continue;
                int area = w * h;
                if (area > screenArea * GENERIC_MAX_AREA_RATIO) continue;
                if (totalArea + area > screenArea * GENERIC_MAX_TOTAL_RATIO) continue;
                if (overlapsAccepted(out, r, area)) continue;
                totalArea += area;
                accepted.add(v);
                Block b = new Block(new RectF(r), Kind.STANDALONE, h,
                        new RectF(r), viewHeight(v));
                b.rows.add(v);
                out.add(b);
            }
            if (out.isEmpty() || out.size() > GENERIC_MAX_CARDS) {
                return Collections.emptyList();
            }
            return out;
        }

        private boolean genericCached(View v) {
            for (WeakReference<View> ref : genericCache) {
                if (ref.get() == v) return true;
            }
            return false;
        }

        private static boolean overlapsAccepted(List<Block> accepted, Rect r, int area) {
            for (Block b : accepted) {
                float ix = Math.max(0f, Math.min(r.right, b.rect.right)
                        - Math.max(r.left, b.rect.left));
                float iy = Math.max(0f, Math.min(r.bottom, b.rect.bottom)
                        - Math.max(r.top, b.rect.top));
                if (ix * iy > area * GENERIC_MAX_OVERLAP) return true;
            }
            return false;
        }

        private static boolean hasAcceptedAncestor(View v, Set<View> accepted) {
            ViewParent p = v.getParent();
            int depth = 0;
            while (p instanceof View && depth++ < MAX_TREE_DEPTH) {
                if (accepted.contains(p)) return true;
                p = ((View) p).getParent();
            }
            return false;
        }

        private static void collectGeneric(View v, View skip, List<View> out,
                                           int depth, int[] budget) {
            if (v == null || v == skip || depth > GENERIC_MAX_DEPTH) return;
            if (budget[0]-- <= 0) return;
            // 0.5.0-p146: 排除容器连同整棵子树不进 generic 卡片发现。
            if (isUnderExcludedContainer(v)) return;
            try {
                if (v.getVisibility() == View.VISIBLE && isCardLike(v)
                        && (v.getBackground() != null || v.getForeground() != null)) {
                    out.add(v);
                }
            } catch (Throwable ignored) {
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    collectGeneric(g.getChildAt(i), skip, out, depth + 1, budget);
                }
            }
        }

        /** Reject chrome, tiny controls and our own injected layers. */
        private static boolean isCardLike(View v) {
            String cls = v.getClass().getName().toLowerCase();
            String[] bad = {"toolbar", "appbar", "header", "statusbar",
                    "navigationbar", "blur", "search", "decorview",
                    "textview", "imageview", "button", "switch", "checkbox",
                    "edittext", "progress", "scrollbar", "magicglass",
                    "hntransparent", "gradientblur"};
            for (String b : bad) {
                if (cls.contains(b)) return false;
            }
            String rn = resourceName(v);
            for (String b : bad) {
                if (rn.length() > 0 && rn.contains(b)) return false;
            }
            // 0.5.0-p145: 页面级装饰容器不生成 generic 玻璃块——彩色卡的
            // 外层 super_device_settings_incard 与 theme 页大容器此前被误收
            // 成整页大玻璃(圆角卡盖上面、方角从圆角边漏出)。精确 id 独有，
            // L1/L2/L3 及其它 app 天然 no-op。
            if (isPageDecorContainer(v)) return false;
            return true;
        }

        /** p144: true if the View sits inside an android ViewPager (horizontal paging). */
        private static boolean isInViewPager(View v) {
            try {
                ViewParent p = v.getParent();
                int d = 0;
                while (p instanceof View && d < 12) {
                    View pv = (View) p;
                    String cls = pv.getClass().getName().toLowerCase();
                    if (cls.contains("viewpager")) return true;
                    p = pv.getParent();
                    d++;
                }
            } catch (Throwable ignored) {
            }
            return false;
        }

        /** p144: full laid-out screen rect, NOT clipped by parent scroll/pager. */
        private static Rect unclippedScreenRect(View v) {
            try {
                int[] loc = new int[2];
                v.getLocationOnScreen(loc);
                int w = v.getWidth(), h = v.getHeight();
                if (w <= 0 || h <= 0) return null;
                return new Rect(loc[0], loc[1], loc[0] + w, loc[1] + h);
            } catch (Throwable t) {
                return null;
            }
        }
    }

    private static Block toBlock(List<Row> rows, boolean firstBlock, boolean lastBlock) {
        Rect r = new Rect(rows.get(0).rect);
        float maxH = 0f;
        float fullH = 0f;
        RectF tall = null;
        for (Row row : rows) {
            r.union(row.rect);
            float h = row.rect.height();
            if (h > maxH) {
                maxH = h;
                tall = new RectF(row.rect);
            }
            // Sum of UNCLIPPED row heights approximates the whole block's
            // natural height. Using max() would make a 3-row block look
            // "fully visible" while its first two rows are already clipped.
            fullH += row.fullH;
        }
        // 0.4.0-p25 CORRECT OUTER-EDGE PINNING.
        //
        // The glass is only visible inside the list's clip, so the FIRST
        // block's top edge and the LAST block's bottom edge must sit on the
        // clip boundary the whole time that block is still entering/leaving
        // the viewport. Keying this off "is the edge row half-visible" misses
        // the exact frame the edge row scrolls FULLY off and is dropped from
        // the scan, so the block lost a whole row and its edge snapped by a
        // row height -- the bottom-edge flash. Using the block's POSITION
        // (first/last) instead makes the pin continuous: it stays pinned until
        // the block has fully entered and is no longer the outermost block.
        clampOuterEdges(rows, r, firstBlock, lastBlock);
        Block b = new Block(new RectF(r),
                rows.size() > 1 ? Kind.GROUP : Kind.STANDALONE, maxH, tall, fullH);
        for (Row row : rows) b.rows.add(row.view);
        return b;
    }

    /**
     * Pin the outermost block's outer edge to the list's visible clip.
     *
     * The first block's top edge and the last block's bottom edge are pinned
     * to the clip whenever the block's content runs past the clip (its edge
     * row's TRUE position is outside the clip). Interior blocks, and blocks
     * fully inside the viewport, keep their own row edges -- they have no
     * discontinuity because they are never clipped.
     */
    private static void clampOuterEdges(List<Row> rows, Rect r,
                                        boolean firstBlock, boolean lastBlock) {
        if (rows.isEmpty()) return;
        Row first = rows.get(0);
        Row last = rows.get(rows.size() - 1);
        Rect clip = first.clip != null ? first.clip : (last.clip != null ? last.clip : null);
        if (clip == null) {
            // Static / generic cards have no list clip: keep the p21
            // extend-to-unclipped behaviour so a clipped standalone card still
            // tracks its row.
            if (first.rect.height() < first.fullH - 0.5f) {
                r.top = Math.min(r.top, first.trueTop);
            }
            if (last.rect.height() < last.fullH - 0.5f) {
                r.bottom = Math.max(r.bottom, last.trueBottom);
            }
            return;
        }
        if (firstBlock && first.trueTop < clip.top) {
            r.top = clip.top;
        }
        if (lastBlock && last.trueBottom > clip.bottom) {
            r.bottom = clip.bottom;
        }
    }

    /**
     * 0.4.4 LISTVIEW COVERAGE, INNERMOST FIRST.
     *
     * 设置 -> 应用 (ManageApplications) and 荣耀互联 build their lists from
     * com.hihonor.android.widget.ListView, NOT a RecyclerView. 0.3.7..0.4.3
     * only ever walked *RecyclerView children, so every row
     * (android.widget.LinearLayout 1272x227, bg=1) kept its opaque OEM
     * drawable forever -- the "应用设置下面的白卡还在 / 荣耀互联白卡依然在"
     * the user reported four rounds in a row.
     *
     * Naively adding "endsWith(ListView)" is not enough: the page wraps the
     * real list in com.android.settings.widget.SearchListView (1272x2469),
     * which is ALSO a *ListView. Stopping at the first match picked the
     * WRAPPER, whose only child is the inner ListView -- so the rows were
     * never seen and the wrapper itself became one giant glass block.
     * Recursing FIRST and keeping the INNERMOST list fixes both.
     *
     * @return true when this subtree contains a list container.
     */
    private static boolean collectLists(View v, List<View> out, int depth) {
        if (v == null || depth > MAX_TREE_DEPTH) return false;
        // 0.5.0-p133: 流量管理的应用列表是 ListView(不是 RecyclerView)，
        // collectLists 只认 RecyclerView ⇒ 它永不进 listCache ⇒ quickToken
        // 哈希不到它的滚动 ⇒ frameMotion=false ⇒ 下拉时不做跟手扫描 ⇒
        // 玻璃卡顿/延迟出现(stall=95)。只对这个流量管理独有 id 收编 ListView，
        // 使 quickToken 能感知滚动；L1/L2/L3 及其它页无此 id，天然 no-op。
        if ("traffic_apps_list".equals(resourceName(v))
                && v.getClass().getName().endsWith("ListView")
                && v instanceof ViewGroup
                && v.getVisibility() == View.VISIBLE) {
            out.add(v);
            return true;
        }
        // 0.5.0-p143: 通知共享页(SuperNotificationActivity)的应用列表同样是
        // ListView(android.widget.ListView, id=super_all_package_manager_listview)，
        // 不在 listCache ⇒ quickToken 哈希不到下拉滚动 ⇒ frameMotion=false ⇒
        // 下拉时不做跟手扫描 ⇒ 玻璃卡顿/卡成白卡。与 traffic_apps_list 分支
        // 并列，只对这个通知页独有 id 收编 ListView；L1/L2/L3 及其它页无此
        // id，天然 no-op。
        if ("super_all_package_manager_listview".equals(resourceName(v))
                && v.getClass().getName().endsWith("ListView")
                && v instanceof ViewGroup
                && v.getVisibility() == View.VISIBLE) {
            out.add(v);
            return true;
        }
        // 0.5.0-p156: 通知和状态栏页(NotificationManagmentActivity,
        // EXT_SYSTEMMANAGER)下拉后的"应用通知管理"列表是另一个 ListView
        // all_package_manager_listview(与 p143 的 super_ 前缀版不同页)。
        // 不在 listCache ⇒ quickToken 感知不到下拉滚动 ⇒ 玻璃宽/分组混乱。
        // 同 p133/p143 手法按精确 id 收编；真机 rows=23/宽 1230 有界。
        // L1/L2/L3 及其它页无此 id，天然 no-op。
        if ("all_package_manager_listview".equals(resourceName(v))
                && v.getClass().getName().endsWith("ListView")
                && v instanceof ViewGroup
                && v.getVisibility() == View.VISIBLE) {
            out.add(v);
            return true;
        }
        // 0.5.0-p159: 应用页(AppAndNotificationDashboardActivity, l2)红框白卡
        // 列表 external_app_settings_list 是【嵌在 #list 里的 RecyclerView】。
        // collectLists 对外层 #list 命中即 return true，内层这个永不进
        // listCache ⇒ quickToken 感知不到 ⇒ dashboard_tile 白卡不剥也不衬
        // 玻璃(真机该页 scan rects=0)。按精确 id 收编内层；该 id 此页独有，
        // 放在 RecyclerView 通用分支之前，让嵌套也能命中。L1/其它页 no-op。
        if ("external_app_settings_list".equals(resourceName(v))
                && v.getClass().getName().endsWith("RecyclerView")
                && v instanceof ViewGroup
                && v.getVisibility() == View.VISIBLE) {
            out.add(v);
            return true;
        }
        // 0.5.0-p159: 隐私安全(ext.systemmanager)滚动区是 ScrollView，含
        // 8+ item_card 行；钱包(wallet)滚动区 ScrollView 含 cl_card_entry_*
        // 行。两者不进 listCache ⇒ quickToken 感知不到滚动 ⇒ 玻璃时有时无
        // (真机 motion=1/7、stall=13)。按精确 id 收编；内层行由
        // scanAllRows 的专用收集处理，不会让整页 wrapper 成一块。L1 no-op。
        if ("sv_container".equals(resourceName(v))
                || "traffic_card_wallet_scroll_view".equals(resourceName(v))) {
            if (v.getClass().getName().endsWith("ScrollView")
                    && v instanceof ViewGroup
                    && v.getVisibility() == View.VISIBLE) {
                out.add(v);
                return true;
            }
        }
        if (v.getClass().getName().endsWith("RecyclerView")
                && v instanceof ViewGroup
                && v.getVisibility() == View.VISIBLE) {
            Rect r = new Rect();
            // 0.5.0-p130: 账号安全四卡列表在首帧 collectLists 时还没 layout
            // 完(可见高度<=600)，被旧门拒后 listCache 建立就永不重建 ⇒
            // 该列表永久缺席 ⇒ 0.40 宽度门是死代码 ⇒ 下排两卡无玻璃。
            // 对这个账号中心独有的 id 直接收编，不依赖当时的可见高度；
            // L1/L2/L3 及其它 app 无此 id，天然 no-op。
            boolean accountSquareList =
                    "account_center_list_other".equals(resourceName(v));
            if (accountSquareList
                    || (v.getGlobalVisibleRect(r) && r.height() > 600)) {
                out.add(v);
                return true;
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectLists(g.getChildAt(i), out, depth + 1);
            }
        }
        return false;
    }

    /**
     * 0.4.0: isChrome()/matchesBadWord() were REMOVED from the frame path.
     * A RecyclerView's direct children are preference rows, never toolbar
     * chrome; the old check cost ~15 getResourceEntryName() lookups per
     * frame on L2 for no benefit.
     */

    // ---- 0.4.0 layout-size helpers -----------------------------------------

    /** Laid-out size, NOT the clipped getGlobalVisibleRect() size. */
    private static float viewHeight(View v) {
        try {
            return v.getHeight();
        } catch (Throwable t) {
            return 0f;
        }
    }

    private static float viewWidth(View v) {
        try {
            return v.getWidth();
        } catch (Throwable t) {
            return 0f;
        }
    }

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

    private static final class Row {
        final Rect rect;
        final View view;
        /** Laid-out height, used by the reusable EdgeFade template. */
        final float fullH;
        /**
         * 0.4.0-p21: the row's UNCLIPPED on-screen top and bottom. These are
         * stable while the row is merely clipped at an edge, whereas `rect` is
         * the clipped getGlobalVisibleRect(). Grouping by the clipped rect
         * made a group flicker in/out every time a partially-off-screen row
         * crossed the GROUP_GAP_PX threshold -- the new-group flash.
         */
        final int trueTop;
        final int trueBottom;
        /**
         * 0.4.0-p22: the owning list's VISIBLE clip (or null for static/
         * generic cards). When a row scrolls fully out of the viewport it is
         * dropped from the scan, so the block edge must be pinned to this
         * clip -- which moves continuously with the scroll -- instead of
         * snapping to the next surviving row.
         */
        final Rect clip;
        /**
         * 0.4.0-p35 L2 SWITCH-BAR FLASH. TRUE only for a STATIC card (a
         * non-RecyclerView header such as WLAN's switch bar). Static cards
         * live in a DIFFERENT container than list rows, so the vertical gap
         * between them carries no grouping meaning -- L2's collapsing header
         * can push a list row's unclipped trueTop ABOVE the bar's trueBottom,
         * making the gap negative. Grouping must never merge the two families.
         */
        final boolean isStatic;

        Row(Rect r, View v, float fullH, Rect listClip) {
            this(r, v, fullH, listClip, false);
        }

        Row(Rect r, View v, float fullH, Rect listClip, boolean isStatic) {
            rect = r;
            view = v;
            this.fullH = fullH;
            this.isStatic = isStatic;
            int[] p = new int[2];
            int t = r.top;
            int b = r.bottom;
            try {
                v.getLocationOnScreen(p);
                t = p[1];
                b = p[1] + v.getHeight();
            } catch (Throwable ignored) {
            }
            trueTop = t;
            trueBottom = b;
            clip = listClip == null ? null : new Rect(listClip);
        }
    }
}
