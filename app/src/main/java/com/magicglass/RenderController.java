package com.magicglass;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;

/**
 * Per-Activity render controller: keeps the glass layer glued to the real cards.
 *
 * Frame-pipeline contract (rewritten for 0.3.0 after the 2026-11-12 device
 * session, findings.txt sections 17 and 18):
 *
 *  1. WHITE BACKGROUND STRIPPING IS DECOUPLED FROM GLASS BLOCKS.
 *     The OEM white drawable is stripped from EVERY qualifying row (and its
 *     ancestors / inner wrappers), not only from rows that formed a block.
 *     A transition frame has zero blocks but MUST NOT show white cards.
 *
 *  2. AN EMPTY FRAME NEVER RESTORES THE OEM BACKGROUNDS. It only hides the
 *     glass containers, so stale glass rectangles cannot linger on screen.
 *     The old 30-frame "hold last good frame" kept containers at OLD positions
 *     for ~1 s during a fling -- that was the reported "residue".
 *
 *  3. THE CONTAINER POOL IS REUSED AND ALIGNED IMMEDIATELY. Surplus containers
 *     become INVISIBLE, deficit containers are created on the spot. No 3-frame
 *     hysteresis on the count (it made rects=3 / containers=0 for 96 ms), and
 *     no removeView() churn (it rebuilt the GPU material every time).
 *
 *  4. MOTION-GATED, ZERO-LAG SCHEDULING + TRACKING TAIL (0.3.4). The 0.3.1
 *     `moving ? 0 : 64ms` rule was a feedback loop: `moving` came from the
 *     PREVIOUS scan, so the first 64 ms of every fling ran with frozen glass.
 *     0.3.2 scanned every frame (perfect tracking, +3 ms idle cost). 0.3.3
 *     switched to a CURRENT-frame INPUT token (raw getTop(), back to 16 ms)
 *     but still froze during FAST flings: at 120 Hz a fling moves <1 px per
 *     frame and overscroll/item animations move rows via translationY, so the
 *     integer token legitimately repeated. 0.3.4 hardens the token (float
 *     translationY, scrollY, every child) AND opens a short tracking tail
 *     (default 160 ms, per-slot perf.trackTailMs) in which EVERY frame is
 *     scanned unconditionally. Zero detection lag, no mid-gesture freeze,
 *     and the 16 ms idle gate returns once the page truly settles (0.3.5).
 *
 *  5. Pure vertical motion uses View.offsetTopAndBottom() -- a RenderNode-level
 *     translation with no measure/layout/invalidate chain -- instead of
 *     layout() on every container on every frame.
 *
 *  6. FORCED GLASS BACKING. If the host SDK cannot create a container, the
 *     pool switches to a self-drawn translucent rounded backing and stays
 *     latched. Restoring OEM white cards is FORBIDDEN: it produced a visible
 *     glass <-> white flip and could leave text on the bare wallpaper.
 *
 *  7. 0.3.6 UNIFORM CARD TEMPLATE. There is ONE layer and ONE pool. Every
 *     GROUP (整块) and STANDALONE (分块) glass card on EVERY level shares a
 *     single left/right edge pair derived from the decor width in
 *     CardScanner.rects(); vertical edges stay per-block. This fixes the L3
 *     help paragraph at its SOURCE: the OEM gives that row 112 px of extra
 *     side padding, so its glass card used to be 950 px wide while the block
 *     above was 1174 px. No second View layer, no paragraph detector, no
 *     kick chain, no latch, no TTL, no tree walk -- all of the 0.3.4/0.3.5
 *     P2a machinery is GONE. The user explicitly rejected the two-layer
 *     result ("半透明块 + 白玻璃卡依然存在") and asked for one template on
 *     all whole and separated glass cards. Frame cost is unchanged O(rects).
 *
 *  8. 0.3.7 STATIC WHITE CARDS. The user reported solid white cards ON TOP of
 *     the glass on nearly every L2 page (WLAN / Bluetooth switch bar, 账号中心
 *     header, 荣耀互联, 荣耀AI&YOYO). dumpsys proves the WLAN one is
 *     HnListCardLayout{974537e 0,0-1272,168} inside #switch_bar_layout, a
 *     SIBLING of the RecyclerView, so it was neither scanned nor stripped.
 *     All such pinned cards now enter the SAME row/grouping/template pipeline
 *     and are stripped by the existing syncRows() -- still ONE layer, ONE pool.
 *     The detector is a bounded (600 node), identity-cached, idle-only
 *     discovery; the frame path stays O(visible rows + cached cards). A clipped
 *     or faded header keeps its background stripped but loses its glass rect,
 *     which fixes "往上滑选项卡消失时显示不正常" without a white flash.
 *
 *  9. 0.3.8 LATCHED STRIPPING + REUSABLE EDGE-FADE TEMPLATE. The user's
 *     verdict: "最终成果是不需要白卡的存在" -- the A/B switch is GONE and
 *     detection is unconditional. Two real defects were fixed:
 *       (a) syncRows() used to RESTORE OEM white drawables for any View that
 *           dropped out of the row set. On every scroll frame that produced
 *           "闪烁然后消失，滑动的时候又出现". Backgrounds are now stripped
 *           once and NEVER restored until detach()/glass-off; the strip set
 *           is widened to EVERY visible list child + its one-level wrappers,
 *           so a recycled row is handled in the SAME frame.
 *       (b) MIN_ROW_H_PX / STATIC_MIN_VISIBLE_H_PX were BINARY cut-offs: a
 *           card vanished in one frame with no transition ("凭空消失").
 *           EdgeFade.java is ONE reusable template that derives a continuous
 *           alpha from the CURRENT frame's visible height vs. its laid-out
 *           height, and applies it to BOTH the glass container and the OEM
 *           row Views. L1/L2/L3, whole blocks, separated blocks, static
 *           headers and external cards all share it. No Handler, no delayed
 *           task, no animation object, no tree recursion in the frame path.
 *
 * 10. 0.3.9 THREE FIXES FROM THE USER'S 15:20 REPORT.
 *     (a) WHITE CARDS STILL VISIBLE on 蓝牙 / 荣耀互联 / 荣耀AI /
 *         通知和状态栏 / 应用 / 荣耀钱包. dumpsys View Hierarchy flags
 *         (045/046/048_*.txt) show the real painter: a card-sized container
 *         with the "D" (self-drawing) bit, e.g.
 *           SettingsRecyclerView{7995a99 VFED..... 0,0-1272,2077 #app:id/list}
 *           ExpandRecyclerView{... VFED..... 0,0-1272,1117 #app:id/rv_my_cards}
 *         RecyclerView.onDraw() runs ItemDecoration.onDraw(), which paints
 *         rounded opaque white cards on the Canvas. There is NO Drawable,
 *         so saveAndClear() cannot reach them. suppressSelfDraw() calls
 *         setWillNotDraw(true) on those containers: only the View's OWN
 *         onDraw is skipped, dispatchDraw() still renders every text/icon
 *         child and ItemDecoration.getItemOffsets() still applies at layout,
 *         so glass geometry is untouched. Guarded by class name, size,
 *         hard-chrome and our own overlay; full-screen ScrollView/ViewPager
 *         roots are deliberately left alone (finance-app safety rule).
 *         Idempotent per frame, restored on detach.
 *     (b) FADE BAND 36 -> 60 dp (210 px @ density 3.5).
 *     (c) L2 JANK. Two causes: the 0.3.4 quickToken() never hashed the
 *         COLLAPSING HEADER ancestor chain (HwAppBarLayout top=-103), so on
 *         L2 the whole content subtree moved by translationY while every
 *         RecyclerView child kept the same getTop(); the token repeated for
 *         the whole gesture and the glass froze. Now the first 6 ancestors
 *         contribute getTop/getScrollY/translationY. Also removed the
 *         0.3.8 double-invalidate (EdgeFade.begin() restored and re-faded
 *         the same View in one frame), quantised setAlpha to 0.04, added a
 *         saveAndClear() fast path and throttled the 32-level ancestor walk.
 *
 * 11. 0.4.0 OFFLINE PASS (phone disconnected). Three reported regressions:
 *     (a) 蓝牙 OK but 荣耀互联 / 应用 still white, and 荣耀AI /
 *         通知和状态栏 / 荣耀钱包 showed a white flash on FIRST entry that
 *         disappeared after a scroll. Root cause: 0.3.9's suppression was
 *         driven from the strip set (only lists(decor) children) and ran in
 *         Activity.onCreate BEFORE layout, so its size guard rejected
 *         not-yet-measured containers. OemCardSuppressor is now an
 *         INDEPENDENT bounded tree walk forced on the FIRST pre-draw frame
 *         (onPreDraw runs AFTER layout) and again within 400 ms; it finds
 *         ListView / HwCardView / any RecyclerView regardless of lists().
 *     (b) L1 lost its dividers and glass highlights: 0.3.9 suppressed
 *         L1's HwRecyclerView too. apply() now takes enabled=false for
 *         ConfigContract.Slot.L1 and restores every container, so the OEM
 *         ItemDecoration dividers/highlights come back on the home page.
 *     (c) L2 still laggy. The 0.3.8 frame path had drifted away from the
 *         0.3.4 "strictly O(visible rows)" rule: one-level child scans,
 *         isChrome() getResourceEntryName() lookups (~15/frame), a 64-node
 *         structure walk every frame, and fadeContent() invalidating up to
 *         10 OEM text rows per frame. All removed/quantised. Stripping is
 *         latched, so only each row's OWN getBackground() needs rechecking;
 *         deep wrappers come from the DISCRETE walkStatic() cache.
 *     Fade band stays 60 dp ("渐隐合适了"). 常用设置 intentionally untouched
 *     ("最后一起改").
 *
 * Every GPU / reflection path is wrapped. A failure degrades to a safe
 * backing instead of crashing the highly privileged Settings process.
 */
public final class RenderController {

    private static final String TAG_OVERLAY = "magicglass.overlay";
    private static final String TAG_GLASS_HOST = "magicglass.glassHost";
    private static final String TAG_GLASS_LAYER = "magicglass.glassLayer";
    /**
     * Safety net used only when the per-slot perf.trackTailMs value cannot be
     * read. The configured default is 160 ms (ConfigContract).
     */
    private static final long TRACK_TAIL_FALLBACK_MS = 160L;
    /** Upper bound so a bad config value cannot pin the page at 100% scan. */
    private static final long TRACK_TAIL_MAX_MS = 1000L;
    /**
     * 0.3.4 MOTION-GATED SCHEDULING + TRACKING TAIL.
     *
     * 0.3.1 used `moving ? 0 : 64ms`, where `moving` was an OUTPUT of the
     * PREVIOUS scan: the first 64 ms of every fling ran with frozen glass.
     * 0.3.2 removed the throttle entirely -- perfectly glued to the finger,
     * but idle frames paid ~3 ms of main-thread scanning (50th 17 -> 20 ms).
     * 0.3.3 made the decision from a CURRENT-frame INPUT (quickToken(), raw
     * getTop()) -- back to 16 ms median -- yet STILL dropped frames during a
     * FAST fling: at 120 Hz a fling moves <1 px per frame, and overscroll /
     * item animations move rows via translationY, so an integer getTop()
     * token legitimately repeats for several consecutive frames. Those frames
     * were classified as idle and the glass froze mid-gesture.
     *
     * 0.3.4 fixes it with TWO independent safeguards:
     *   1) quickToken() also hashes float translationY + the list's scrollY,
     *      and walks every visible child instead of the first 4 + last;
     *   2) a detected motion frame opens a short TRACKING TAIL (default
     *      160 ms, per-slot perf.trackTailMs). Inside the tail EVERY frame is
     *      scanned unconditionally, so a repeated token can never freeze the
     *      glass. Every further motion frame extends the tail; once the page
     *      truly settles, the 16 ms idle gate takes over again (0.3.5).
     * This is NOT the 0.3.1 feedback loop: the gate never reads a previous
     * scan's OUTPUT, and the first frame of a gesture still has zero lag.
     */
    /**
     * 0.3.5: 64 ms was too coarse. In a SLOW drag the cheap token can repeat
     * for several frames; once the 160 ms tracking tail expired, the gate
     * dropped back to a 64 ms scan interval and the glass visibly stuttered
     * ("慢慢拖动时掉帧感变明显"). 16 ms ~ one frame, so the gate can no longer
     * produce a two-state stutter. A truly static page produces almost no
     * pre-draw traversals anyway, so this costs little on idle.
     */
    private static final long SCAN_INTERVAL_IDLE_MS = 16L;
    private static final long CONFIG_DEBOUNCE_MS = 90L;
    /**
     * 0.3.5: the p7/p8 kick chain is gone. Its delays were
     * {120, 320, 700, 1400, 2600} ms -- precisely the "滑完停手静置 2-3 秒"
     * window in which the user felt dropped frames. Worse, a slow drag kept
     * the tracking tail short, so kicks ran their 600-node DecorView walk
     * repeatedly DURING the gesture. The backing now needs no discovery, so
     * there is nothing to kick.
     */
    /** Same-signature lifecycle hooks fire several times per Activity start. */
    private static final long LIFECYCLE_DEBOUNCE_MS = 180L;
    private static final int MAX_ANCESTOR_DEPTH = 32;
    /** Hard ceiling on pooled containers; protects against runaway detectors. */
    private static final int MAX_POOL_SIZE = 20;
    /** 0.3.8: bound the latched background bookkeeping (no white restore). */
    private static final int MAX_SAVED_ROWS = 260;

    // ---- 0.5.0-p118 unified-card four-step gate ----------------------------
    /** Step 0: a card fill must span at least this fraction of the decor width. */
    private static final float UNIFIED_MIN_WIDTH_RATIO = 0.50f;
    /** Step 0: minimum measured height in px (small controls are excluded). */
    private static final int UNIFIED_MIN_HEIGHT_PX = 48;
    /** Step 2: per-channel RGB spread allowed across the 3x3 samples. */
    private static final int UNIFIED_COLOR_TOL = 12;
    /** Step 2: "near white" floor, identical to the accepted p56/p64 criterion. */
    private static final int UNIFIED_NEAR_WHITE = 0xE0;
    /** Diagnostic cap: at most this many COLORCAND lines per bounded walk. */
    private static final int COLORCAND_MAX_LINES = 60;

    private final Activity activity;
    private final CardScanner.Scanner scanner = new CardScanner.Scanner();
    private final List<View> containers = new ArrayList<View>();
    private final Map<View, Saved> savedRows = new HashMap<View, Saved>();
    /**
     * 0.3.8: ACCUMULATING, never cleared during the Activity. Backgrounds are
     * latched off; recomputing/dropping ancestors only risks letting a white
     * drawable back in.
     */
    private final Set<View> ancestorViews = new HashSet<View>();
    /** Reusable edge-fade template: glass container + OEM text, one curve. */
    private final EdgeFade edgeFade = new EdgeFade();
    /** 0.4.0: independent, bounded, first-frame-correct self-draw suppression. */
    private final OemCardSuppressor suppressor = new OemCardSuppressor();
    private long attachTimeMs;
    private boolean firstPreDraw = true;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private ViewGroup decor;
    private FrameLayout overlay;
    private FrameLayout glassHost;
    /** 0.3.6: ONE layer. Every card is a host glass container from one pool. */
    private FrameLayout glassLayer;
    private BackdropLayer backdrop;
    private ViewTreeObserver.OnPreDrawListener preDraw;
    /** 0.4.0-p49: post-layout / pre-draw strip hook (see attach()). */
    private ViewTreeObserver.OnGlobalLayoutListener globalLayout;
    private ViewTreeObserver layoutTree;
    /** 0.4.0-p50: last-moment probe/strip at draw start. */
    private ViewTreeObserver.OnDrawListener drawProbe;
    /** 0.4.0-p49 diagnostic: containers stripped by the layout-time hook. */
    private int diagLayoutStrips;
    /** 0.4.0-p55: rows latched before the first draw. */
    private int diagRowStrips;
    /** 0.5.0-p64: header-chrome backgrounds latched (event counter). */
    private int diagChromeStrips;
    /** 0.5.0-p66: external-page OEM child cards hidden (see stripExtWhiteChild). */
    private int diagExtStrips;
    private long lastExtLogMs;
    /** 0.5.0-p78: throttle for the always-visible fade-keep state line. */
    private long lastAccountFadeLogMs;
    /** 0.5.0-p79: throttle for the always-visible successful-strip line. */
    private long lastAccountStripLogMs;
    /** 0.5.0-p80: throttle for p64 §6's Drawable-candidate line. */
    private long lastAccountCandLogMs;
    /** 0.5.0-p105: external L2 top-bar probe (bounded, never on the frame path). */
    private long lastTopBarLogMs;
    private int topBarLogLines;
    private int diagTopBarStrips;
    /** Reused location buffer: the bounded walk must not allocate. */
    private final int[] topBarLoc = new int[2];
    /** 0.5.0-p117: content-area diagnostic probe (EXT pages, log only). */
    private int diagContentNodes;
    private long lastContentLogMs;
    /** 0.5.0-p119: the content probe has its OWN budget. In p117 the
     *  top-band probe consumed the shared 60-line budget with 104 identical
     *  rows, so the content band (the three white blocks + four cards) was
     *  never measured. Log-only change: no strip predicate is touched. */
    private int contentLogLines;
    private int contentNodeLines;
    private int contentRectLines;
    private java.util.HashSet<String> contentSeen;
    /** 0.5.0-p118: COLORCAND pure-diagnostic line budget + throttle. */
    private long lastColorCandLogMs;
    private int colorCandLogLines;
    /** 0.5.0-p118: UNIFIED gate event counters for the objective state line. */
    private int diagUnifiedStrips;
    private int diagUnifiedPlaceholders;
    private int diagUnifiedRevived;
    private long lastUnifiedLogMs;
    /** 0.5.0-p118: reused 3x3 sample buffers — the gate allocates nothing per node. */
    private final int[] unifiedBgPx = new int[9];
    private final int[] unifiedFgPx = new int[9];
    private final boolean[] unifiedBgOk = new boolean[1];
    private final boolean[] unifiedFgOk = new boolean[1];
    /** 0.5.0-p107: ONE structural rule for every external L2. */
    private int diagTopContainers;
    private long lastTopContainerLogMs;
    private int topContainerLogLines;
    /** Late top-container re-check after the 400 ms early strip window. */
    private long lastTopContainerScanMs;
    /** 0.5.0-p130: 账号安全顶部盾牌白底图(hv_ripple_view_bg)被隐藏的实例。
     *  ImageView 的 src 图片内容不体现在 background(Drawable)，bg 探针测不到；
     *  这是截图红框里那层白半透明的真源。detach 时恢复 VISIBLE。 */
    private View accountShieldBgHidden;
    /** 0.5.0-p148: 桌面个性化主题预览卡下方浅白块(无 id ImageView/View，
     *  白来自 src/自绘、bg 探针测不到，同 p130 盾牌白底)被隐藏的实例列表。
     *  ViewPager 每页各一块，故用 List；detach 非 finishing 时恢复。 */
    private final List<View> wallpaperThumbHidden = new ArrayList<View>();
    /** 0.5.0-p148: 白块补扫节流(延迟 inflate 的新 View，同 p141 教训)。 */
    private long lastThumbWalkMs;
    /** 0.5.0-p152: 过渡态白卡(1~2.4s才出现)每帧检查节流。 */
    private long lastTransientWhiteMs;
    /** 0.5.0-p135 (诊断): 荣耀互联 card_item 白源探测有界预算。 */
    // p164: 应用页/电池主页列表行首帧白卡 layout-time strip 诊断预算。
    private int entryStripLogLines;
    private long lastEntryStripLogMs;
    private int cardItemLogLines;
    private long lastCardItemLogMs;
    /** 0.5.0-p143: 通知设置行 NOTIFSTRIP 日志预算(每页 12 行 / 150ms)。 */
    private int notifyStripLogLines;
    private long lastNotifyStripLogMs;
    // p156: 通知页首帧白卡早窗口补扫节流(与晚扫 onGlobalLayout 互补)。
    private long lastNotifyStripWalkMs;
    /** 0.5.0-p140: 早窗口剥 card_item 的节流(每 150ms 一次有界 walk)。 */
    private long lastCardStripWalkMs;
    // p162: 延迟 inflate 白卡的 150ms 补扫节流(应用页/电池详情页)。
    private long lastLateWhiteWalkMs;
    // p163: LATEBLK 诊断有界行计数(每次 detach 重置)。
    private int lateDiagLines;
    /** Tracks the glass master switch so a toggle takes an explicit live path. */
    private boolean glassOn = true;
    private ViewTreeObserver observedTree;
    /** Every tree we ever registered on; removed together at detach(). */
    private final List<ViewTreeObserver> registeredTrees = new ArrayList<ViewTreeObserver>();
    private BroadcastReceiver receiver;

    private boolean attached;
    private boolean errorLogged;
    private boolean genericEnabled;
    private boolean moving;
    /**
     * 0.3.2: FORCED translucent backing instead of restoring OEM white cards.
     * Once set it stays latched for this Activity's lifetime (hysteresis), so
     * a transient construction failure can never make the page flip between
     * glass and white. Only a real material-parameter change or detach resets.
     */
    private boolean fallbackMode;
    private String version = "";
    private String glassFp = "";
    private String bgFp = "";
    private long lastRowsLogMs;
    private long lastAncestorMs;
    private long lastScanLogMs;
    private long lastLifecycleMs;
    /** 0.4.0-p36 pure diagnostic: last suppression switch state. */
    private boolean diagLastSuppress = true;
    /** 0.4.0-p48: throttle for the REVIVED diagnostic line. */
    // 0.3.2 frame diagnostics: proves the glass is aligned every single frame.
    private long diagWindowMs;
    private long diagPrevFrameMs;
    private int diagFrames;
    private int diagScans;
    private int diagMotion;
    private int diagAligns;
    private int diagMaxGapMs;
    /** Frames scanned only because the tracking tail was active. */
    private int diagTail;
    /**
     * Frames whose token did NOT change but whose geometry DID -- exactly the
     * frames 0.3.3 would have frozen. This is the on-device proof of the fix.
     */
    private int diagStall;
    /** 0.4.0-p26: containers that came back from INVISIBLE (stale-list risk). */
    private int diagReappears;
    private boolean frameMotion;
    private long trackUntilMs;
    private ConfigContract.Slot slot;
    private ConfigContract.Snapshot cfg;
    private long lastQuickToken = Long.MIN_VALUE;
    private long lastFullScanMs;
    private boolean pendingFullScan;
    private long lastGeoSig = Long.MIN_VALUE;
    private long lastRowSig = Long.MIN_VALUE;
    private Object glassEffect;

    private final Runnable configRefresh = new Runnable() {
        @Override
        public void run() {
            // 0.5.0-p186: 图标替换即时生效——配置广播后 ConfigBus 已 invalidate,
            // 这里(非渲染帧)重扫当前页 title,按最新 icon.rules 换/还原图标。
            try {
                IconSwap.refresh(decor);
            } catch (Throwable ignored) {
            }
            refresh(false);
        }
    };

    public RenderController(Activity activity) {
        this.activity = activity;
    }

    /** Used by GlassEntry to prune controllers of finished Activities. */
    public boolean isAttached() {
        return attached;
    }

    // ---- lifecycle ----------------------------------------------------------

    public void attach() {
        if (attached) return;
        try {
            View d = activity.getWindow().getDecorView();
            if (!(d instanceof ViewGroup)) return;
            decor = (ViewGroup) d;
            overlay = ensureOverlay(decor);
            glassHost = ensureGlassHost(overlay);
            // 0.3.6: a SINGLE layer, rebuilt on every attach for a
            // deterministic z-order even if an older overlay was reused.
            glassHost.removeAllViews();
            glassLayer = newLayer(TAG_GLASS_LAYER);
            glassHost.addView(glassLayer, fullLp());
            backdrop = new BackdropLayer(activity, overlay);
            genericEnabled = Scope.isExternal(activity.getPackageName())
                    || Scope.isTopBarOnly(activity.getPackageName());

            preDraw = new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    return RenderController.this.onPreDraw();
                }
            };
            registerObserver(decor.getViewTreeObserver());
            // 0.4.0-p49 LAYOUT-TIME STRIP (the Bluetooth "整个卡片一起闪").
            //
            // onPreDraw fires BEFORE measure/layout. A RecyclerView binds its
            // children during layout, so the rows of the "available devices"
            // card are created and drawn inside the SAME traversal -- our next
            // preDraw only sees them one frame LATE, which is exactly the
            // 1-frame full-width white flash the user sees on entry.
            // OnGlobalLayout is dispatched AFTER layout and BEFORE draw in the
            // same traversal, so stripping here removes the OEM background in
            // the very frame the rows appear.
            globalLayout = new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    stripAtLayoutTime();
                    // 0.5.0-p142: 荣耀互联白卡【首帧不显示】的正解(复用 p44/p45
                    // 已验证的 layout-time strip 机制)。OnGlobalLayout 在 layout
                    // 之后、draw 之前触发——在这里剥 card_item 的背景，白卡那一
                    // 帧根本不会画出来，而不是画出来后再靠 onDraw/补扫去剥。
                    // 不限 400ms 早窗口：ROM 延迟重建的新 View 会触发新 layout，
                    // 每次 layout 都补剥。stripCardItemWhite 靠 Saved latch 幂等。
                    if (slot == ConfigContract.Slot.EXT_CONTROLCENTER
                            && decor != null) {
                        try {
                            probeCardItemWalk(decor, 0, new int[]{400});
                        } catch (Throwable ignored) {
                        }
                    }
                    // 0.5.0-p143: 通知共享页(EXT_SYSTEMMANAGER)设置行白卡的
                    // layout-time strip。同 p142 正解:OnGlobalLayout 在 layout
                    // 后 draw 前触发,在这里剥 main_switcher/watch_switcher 的
                    // OEM 白背景,白卡那一帧根本不画出来。独立有界 walk(depth20/
                    // budget300),stripNotifySwitchWhite 靠 Saved latch 幂等。
                    // EXT_CONTROLCENTER 的 card_item 路径逐字保留,互不干扰。
                    if ((slot == ConfigContract.Slot.EXT_SYSTEMMANAGER
                            || slot == ConfigContract.Slot.EXT_ASSISTANT)
                            && decor != null) {
                        try {
                            probeNotifySwitchWalk(decor, 0, new int[]{300});
                        } catch (Throwable ignored) {
                        }
                    }
                    // 0.5.0-p164: 应用页/电池主页列表行首帧白卡正解——同 p142/p143
                    // 在此(layout 后 draw 前)剥行背景,白卡那一帧不画。不限 400ms
                    // 早窗口:每次 layout 都补剥(延迟 inflate 的行使下次 layout 再剥),
                    // stripEntryRowWhite 靠 Saved latch 幂等。独立有界 walk(depth24/
                    // budget400),slot+Activity+id+父链四重硬门,L1/其它页 no-op。
                    if (decor != null) {
                        try {
                            probeEntryRowWalk(decor, 0, new int[]{400});
                        } catch (Throwable ignored) {
                        }
                    }
                    // 0.5.0-p148: 桌面个性化主题预览卡下方浅白块(src/自绘白,
                    // bg 探针测不到)——layout-time 首帧隐藏,避免"进入先显示
                    // 一块白然后消失"。同 p130/p142 正解,幂等+detach 恢复。
                    if (slot == ConfigContract.Slot.EXT_WALLPAPEREDITOR
                            && decor != null) {
                        try {
                            probeWallpaperThumbWalk(decor, 0, new int[]{300});
                        } catch (Throwable ignored) {
                        }
                        // 0.5.0-p150: 顶栏渐变白层也在 draw 前剥——解决
                        // "点进去转场中间帧白卡出现、进菜单后消失"。
                        try {
                            probeTopGradientWalk(decor, 0, new int[]{200});
                        } catch (Throwable ignored) {
                        }
                        // 0.5.0-p151: 滑动卡图片区白占位——剥 ImageView 的
                        // bg/fg(不动 src)，首帧 draw 前执行，诊断日志带 src 类型。
                        try {
                            probeWallpaperImageWalk(decor, 0, new int[]{300});
                        } catch (Throwable ignored) {
                        }
                    }
                }
            };
            layoutTree = decor.getViewTreeObserver();
            try {
                layoutTree.addOnGlobalLayoutListener(globalLayout);
            } catch (Throwable ignored) {
            }
            // 0.4.0-p50: LAST moment before pixels. OnDrawListener is
            // dispatched by ViewRootImpl immediately before the view tree is
            // drawn, so anything still carrying an OEM white Drawable at this
            // point WOULD be painted this frame. Count it (naming the class),
            // then strip it, then count again: post=0 proves the frame is
            // clean, post>0 names what we cannot strip.
            drawProbe = new ViewTreeObserver.OnDrawListener() {
                @Override
                public void onDraw() {
                    stripRowsBeforeDraw();
                }
            };
            try {
                layoutTree.addOnDrawListener(drawProbe);
            } catch (Throwable ignored) {
            }
            receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    ConfigBus.invalidate();
                    handler.removeCallbacks(configRefresh);
                    handler.postDelayed(configRefresh, CONFIG_DEBOUNCE_MS);
                }
            };
            // 0.5.0 LIVE CONFIG FIX. The sender is the MODULE process, i.e. a
            // different UID. On Android 13+ a RECEIVER_NOT_EXPORTED dynamic
            // receiver rejects broadcasts from other apps, so every config
            // edit was silently dropped and only a later restart picked it up.
            // The receiver only triggers a re-read of our own preferences --
            // it accepts no data from the intent -- so exporting it is safe.
            try {
                activity.registerReceiver(receiver,
                        new IntentFilter(ConfigBus.ACTION_CONFIG_CHANGED),
                        Context.RECEIVER_EXPORTED);
            } catch (Throwable t) {
                try {
                    activity.registerReceiver(receiver,
                            new IntentFilter(ConfigBus.ACTION_CONFIG_CHANGED));
                } catch (Throwable ignored) {
                }
            }
            attached = true;
            attachTimeMs = SystemClock.uptimeMillis();
            firstPreDraw = true;
            // 0.4.0: onCreate runs BEFORE the content tree is laid out. A
            // discovery here misses the white-card containers (and their
            // size guard rejects them), which is exactly why 0.3.9 showed
            // "首次进入白卡闪一下后消失". The real discovery is forced on
            // the FIRST pre-draw frame, which runs AFTER layout.
            scanner.invalidateStatic();
            suppressor.invalidate();
            log("attached to " + activity.getClass().getName()
                    + " generic=" + genericEnabled);
            // 0.5.0-p117: a config edit made while NO host page was attached
            // leaves the process-wide snapshot stale forever. Re-read here.
            ConfigBus.invalidateConfigCache();
            refresh(true);
        } catch (Throwable t) {
            log("attach failed: " + t);
            detach();
        }
    }

    /**
     * Called from onCreate/onResume hooks. Four hooks are installed, so one
     * Activity start can reach here several times; debounce and NEVER force a
     * pool rebuild -- that was the "glass disappears on L2" defect.
     */
    public void onLifecycle() {
        // 0.5.0: the p36/p58/p60 lifecycle census (whole-tree walk + two
        // long log lines per resume) is gone. Keep the FUNCTIONAL re-strip,
        // which is the accepted p41 white-card fix.
        restripLatched();
        if (!attached) {
            attach();
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - lastLifecycleMs < LIFECYCLE_DEBOUNCE_MS) return;
        lastLifecycleMs = now;
        // 0.4.0: a fragment/page swap replaces the content subtree.
        scanner.invalidateStatic();
        suppressor.invalidate();
        // 0.5.0-p117: "switch background while the page is in background"
        // must take effect the moment the user comes back, without a restart.
        ConfigBus.invalidateConfigCache();
        refresh(false);
        pendingFullScan = true;
    }

    public void detach() {
        // 0.4.0-p36 PURE DIAGNOSTIC. detach() calls restoreRows(), which puts
        // the OEM white Drawables BACK. If this fires while the previous
        // level's View tree is still on screen, that is the white-card flash.
        log("DIAG detach act=" + activity.getClass().getSimpleName()
                + " saved=" + savedRows.size()
                + " suppress=" + suppressor.describe()
                + " slot=" + slot);
        attached = false;
        fallbackMode = false;
        diagWindowMs = 0L;
        diagPrevFrameMs = 0L;
        // 0.5.0-p140: 换页后首次进早窗口立即剥 card_item，不受上页节流影响。
        lastCardStripWalkMs = 0L;
        lastLateWhiteWalkMs = 0L;
        lateDiagLines = 0;
        // 0.5.0-p148: 换页后首次进早窗口立即扫白块。
        lastThumbWalkMs = 0L;
        // 0.5.0-p152: 换页后首帧立即检查过渡态白卡。
        lastTransientWhiteMs = 0L;
        // 0.5.0-p156: 换页后通知页首帧立即剥白卡，不受上页节流影响。
        lastNotifyStripWalkMs = 0L;
        diagFrames = 0;
        diagScans = 0;
        diagMotion = 0;
        diagAligns = 0;
        diagMaxGapMs = 0;
        diagTail = 0;
        diagStall = 0;
        diagReappears = 0;
        diagChromeStrips = 0;
        diagExtStrips = 0;
        glassOn = true;
        contentLogLines = 0;
        contentNodeLines = 0;
        contentRectLines = 0;
        if (contentSeen != null) contentSeen.clear();
        slotPrimed = false;
        lastRectCount = -1;
        reappearDiagLines = 0;
        for (int i = 0; i < slotUsed.length; i++) slotUsed[i] = false;
        frameMotion = false;
        trackUntilMs = 0L;
        lastQuickToken = Long.MIN_VALUE;
        lastFullScanMs = 0L;
        pendingFullScan = false;
        try {
            for (ViewTreeObserver t : registeredTrees) {
                if (t != null && t.isAlive() && preDraw != null) {
                    t.removeOnPreDrawListener(preDraw);
                }
            }
        } catch (Throwable ignored) {
        }
        // 0.4.0-p49: the layout hook must not survive detach() -- it would
        // keep walking a dead tree and re-strip on a finishing page.
        try {
            if (layoutTree != null && layoutTree.isAlive() && globalLayout != null) {
                layoutTree.removeOnGlobalLayoutListener(globalLayout);
            }
        } catch (Throwable ignored) {
        }
        try {
            if (layoutTree != null && layoutTree.isAlive() && drawProbe != null) {
                layoutTree.removeOnDrawListener(drawProbe);
            }
        } catch (Throwable ignored) {
        }
        layoutTree = null;
        globalLayout = null;
        drawProbe = null;
        registeredTrees.clear();
        try {
            if (receiver != null) activity.unregisterReceiver(receiver);
        } catch (Throwable ignored) {
        }
        try {
            handler.removeCallbacks(configRefresh);
        } catch (Throwable ignored) {
        }
        // 0.4.0-p39: compute `finishing` BEFORE backdrop.release(). p37 only
        // stopped restoreRows()/suppressor.release(), but backdrop.release()
        // ran unconditionally above and called restoreWindowBackground(),
        // putting the OEM opaque DecorView Drawable back. That is the real
        // "返回前一级白卡重新出现" the user still saw after p37.
        boolean finishing = activity.isFinishing() || isDestroyed(activity);
        try {
            if (backdrop != null) backdrop.release(!finishing);
        } catch (Throwable ignored) {
        }
        log("DIAG backdrop release restoreOem=" + (!finishing)
                + " finishing=" + finishing);
        // 0.4.0-p37 RETURN-TO-PREVIOUS WHITE-CARD FIX.
        //
        // p36 diagnostic proved the exact event on every back navigation:
        //   DIAG detach act=AdvancedWifiSettingsActivity saved=12 ...
        //   DIAG restoreRows reason=detach saved=12 slot=L2
        //   DIAG lifecycle act=WifiSettingsActivity attached=true
        // detach() used to put EVERY stripped OEM white Drawable back onto
        // live Views. The outgoing Activity's window is still composited
        // during the back transition, so those stock white cards became
        // visible again -- "返回前一级菜单时会出现白卡重新出现".
        //
        // Restoring only makes sense when OUR MODULE stops being active on a
        // page that stays alive (glass switched off / material unavailable).
        // When the Activity itself is finishing, its View tree is about to be
        // torn down: restoring anything is pointless and harmful. Drop the
        // references instead, and let suppressor.release(false) clear its
        // state WITHOUT flipping setWillNotDraw(false).
        if (finishing) {
            log("DIAG detach finishing=true -> skip OEM restore saved="
                    + savedRows.size());
            savedRows.clear();
            ancestorViews.clear();
            // 0.5.0-p132: 返回时 Activity 正在结束，旧窗口仍在过场合成中。
            // 此时若把 p130 隐藏的盾牌白底设回 VISIBLE，白底会在返回动画里
            // 重现半秒——与 p37 的"返回白卡闪"同构。只丢引用，不恢复；
            // 非 finishing（模块关闭/玻璃不可用、页面仍活着）才恢复。
            accountShieldBgHidden = null;
            // 0.5.0-p148: 返回时 Activity 结束，主题预览浅白块也不恢复
            // (同 p132 两向时机)，只丢引用。
            wallpaperThumbHidden.clear();
        } else {
            restoreRows("detach");
            // 0.5.0-p130: 页面仍活着时恢复被隐藏的顶部盾牌白底图，
            // 避免 View 树复用时残留隐藏状态。
            restoreAccountShieldBg();
            // 0.5.0-p148: 页面仍活着时恢复被隐藏的主题预览浅白块。
            restoreWallpaperThumbWhite();
        }
        // 0.4.0: release BOTH the edge-fade template (restores OEM alphas)
        // and the self-draw suppressor (restores onDraw / dividers).
        edgeFade.release();
        suppressor.release(!finishing);
        try {
            if (glassLayer != null) glassLayer.removeAllViews();
        } catch (Throwable ignored) {
        }
        try {
            if (glassHost != null) glassHost.removeAllViews();
        } catch (Throwable ignored) {
        }
        containers.clear();
        ancestorViews.clear();
        try {
            if (overlay != null && decor != null) decor.removeView(overlay);
        } catch (Throwable ignored) {
        }
        observedTree = null;
        preDraw = null;
        receiver = null;
        overlay = null;
        glassHost = null;
        glassLayer = null;
        decor = null;
        backdrop = null;
    }

    /**
     * A window rebuild replaces the DecorView's ViewTreeObserver. Registering
     * on the new tree is mandatory, but REMOVING a listener from the tree that
     * is currently dispatching onPreDraw() silently kills the callback chain --
     * 0.3.0-p1 showed exactly that in external apps: one "reattached" line and
     * then no controller logs at all. So we only ever ADD here and remove all
     * registered trees together in detach(), which never runs mid-dispatch.
 * A duplicate registration on an old live tree is harmless: the second
 * callback in the same frame reads an unchanged quickToken() and is skipped
 * by the idle gate.
     */
    /**
     * 0.4.0-p49: strip OEM white containers at LAYOUT time.
     *
     * <p>Why this hook exists: onPreDraw is dispatched before measure/layout,
     * but a RecyclerView creates its children DURING layout. On the Bluetooth
     * page the "available devices" rows therefore appear and are drawn inside
     * one traversal -- the next preDraw discovers them a frame later, which is
     * the 1-frame full-width white card the user sees on entry. OnGlobalLayout
     * runs after layout and before draw in the SAME traversal, so the strip
     * lands before the frame is painted.
     *
     * <p>Cost control: only inside the 400 ms entry window, only on pages we
     * suppress (never L1), and the walk is memoised by candidates/rejected so
     * repeat passes only pay for NEW nodes.
     */
    /**
     * 0.4.0-p55 ROW-LEVEL ENTRY STRIP -- class-name independent.
     *
     * <p>syncRows() already strips every list row's background, but it can only
     * run once CardScanner has rects, and the first frames of a fresh Activity
     * have rects=0. The Bluetooth row measured AT PAINT TIME is
     * `ConstraintLayout 1272x182` at y=2216 carrying an opaque near-white
     * ColorDrawable #fff2f3f6 at full screen width -- exactly the
     * "one row, edge to edge" flash the user described. A whitelist can never
     * name every ROM row class, so strip by STRUCTURE instead: direct children
     * of a scrolling list, which is precisely what syncRows() does.
     */
    /**
     * 0.4.0-p55: "opaque near-white" as a MEASURED property, not a class name.
     * #fff2f3f6 (the stock settings card colour) qualifies; a coloured accent
     * or a transparent pattern never does.
     */
    private static boolean isOpaqueNearWhite(Drawable d) {
        if (!(d instanceof ColorDrawable)) return false;
        try {
            int c = ((ColorDrawable) d).getColor();
            if ((c >>> 24) != 0xFF) return false;
            int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
            return r >= 0xE0 && g >= 0xE0 && b >= 0xE0;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 0.5.0-p64: solid opaque fill of the L2/L3 HEADER BAR.
     *
     * p55's pre-draw probe measured `HwToolbar #fff2f3f6` as CONSTANT (unlike
     * the 1-2 frame `ConstraintLayout` row that p56 removed). root-cause-map
     * §9 names it "the status/toolbar's own independent background". It was
     * invisible while the whole OEM page was the same #fff2f3f6; once the OEM
     * white fill is replaced by our glass / window colour it becomes the
     * visible full-width rectangle above the "WLAN" / "蓝牙" title.
     *
     * A near-white ColorDrawable always qualifies (the measured ROM colour).
     * Any other Drawable qualifies only when it reports PixelFormat.OPAQUE --
     * i.e. it really paints an opaque bar. Translucent gradient masks used by
     * collapsing headers report TRANSLUCENT and are deliberately left alone.
     */
    private static boolean isOpaqueSolidFill(Drawable d) {
        if (d == null) return false;
        // When we CAN measure the colour, insist on near-white exactly as
        // p56 did: an opaque dark/coloured toolbar (dark mode, accent) is
        // deliberately NOT touched.
        if (d instanceof ColorDrawable) return isOpaqueNearWhite(d);
        try {
            // Other Drawable types expose no colour getter. Only strip when
            // they actually report an opaque fill; translucent gradient
            // masks on collapsing headers report TRANSLUCENT and survive.
            // android.graphics.PixelFormat.OPAQUE == -1
            return d.getOpacity() == -1;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Fixed framework chrome structure, NOT a whitelist of card rows. */
    private static boolean isChromeClass(String cls) {
        return cls.contains("toolbar") || cls.contains("appbar");
    }

    /**
     * 0.5.0-p79: p55's measured structural rule — an OEM view touches both
     * screen edges, while our glass containers are inset 14 dp per side
     * (49 px at 3.5 density, i.e. ~92% of the screen). Ratio-based, so it
     * needs NO absolute screen-Y threshold (a documented project red line).
     */
    private boolean isFullWidthOem(View v) {
        if (v == null || decor == null) return false;
        int dw = decor.getWidth();
        int vw = v.getWidth();
        return dw > 0 && vw > 0 && vw >= dw * 0.94f;
    }

    /**
     * 0.5.0-p80: p64 report §6 wrote this exact falsifiable candidate and
     * left it for a later build — "若 bg=StateListDrawable/op=-3 且白条仍在，
     * 说明 getOpacity() 保守返回 TRANSLUCENT，下一版需要按 Drawable 实测
     * 颜色再扩展". 账号中心 is a separate app whose header card uses such a
     * Drawable (StateList / Gradient / Ripple): getOpacity() reports -3 even
     * though it paints an opaque near-white rectangle, so every earlier
     * isOpaqueSolidFill() gate silently skipped it.
     *
     * Paint the Drawable into a 1x1 Bitmap and MEASURE the pixel: only
     * alpha=0xFF with all RGB >= 0xE0 qualifies (p56's near-white rule, so
     * dark / coloured accents are still never stripped). Called ONLY from
     * stripRowsIn()'s bounded first-frame walk — never from the per-frame
     * syncRows() hot path, preserving the zero-allocation frame rule.
     */
    private static boolean measuredOpaqueWhite(Drawable d) {
        if (d == null) return false;
        Bitmap b = null;
        try {
            b = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            d.draw(new Canvas(b));
            int px = b.getPixel(0, 0);
            if ((px >>> 24) != 0xFF) return false;
            int r = (px >> 16) & 0xFF, g = (px >> 8) & 0xFF, bl = px & 0xFF;
            return r >= 0xE0 && g >= 0xE0 && bl >= 0xE0;
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (b != null) {
                try {
                    b.recycle();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String drawableDesc(Drawable d) {
        if (d == null) return "null";
        String n = d.getClass().getSimpleName();
        if (d instanceof ColorDrawable) {
            return n + "#" + Integer.toHexString(((ColorDrawable) d).getColor());
        }
        return n + "/op=" + d.getOpacity();
    }

    // ---- 0.5.0-p118 unified-card four-step gate ----------------------------
    // Design: offline-unified-card-transparency-design-2026-11-18 §五/§六/§七.
    // All of this runs ONLY from stripRowsIn()'s bounded first-frame walk (the
    // 400 ms early window / layout hook), NEVER from syncRows()' per-frame path.
    // L1 is a hard no-op (checked by the caller's gate AND here).

    /** Small controls are never card fills — matched by class name, not id. */
    private static boolean unifiedSmallControl(String cls) {
        String[] small = {"button", "switch", "imageview", "checkbox", "textview",
                "edittext", "spinner", "seekbar", "radiobutton", "togglebutton",
                "compoundbutton"};
        for (int i = 0; i < small.length; i++) {
            if (cls.contains(small[i])) return true;
        }
        return false;
    }

    /**
     * Family words are logged but NEVER decide life-or-death (p115: a container
     * whose class contains "gradient" measured as an opaque #fff2f3f6 white
     * fill and still had to be stripped). Kept here purely for the COLORCAND
     * diagnostic so a device capture can confirm the fade is untouched.
     */
    private static boolean unifiedFadeFamilyWord(String low) {
        return low.contains("gradient") || low.contains("fade")
                || low.contains("top_gradient") || low.contains("appbarblur")
                || low.contains("hnblurappbar") || low.contains("collapsing");
    }

    /**
     * Drawable.getAlpha() does not exist on every API / test stub — read it
     * reflectively and default to fully opaque when unavailable.
     */
    private static int unifiedDrawableAlpha(Drawable d) {
        if (d == null) return 255;
        try {
            java.lang.reflect.Method m = d.getClass().getMethod("getAlpha");
            Object v = m.invoke(d);
            if (v instanceof Integer) return ((Integer) v).intValue();
        } catch (Throwable ignored) {
        }
        return 255;
    }

    /** Reflective setBounds() — absent from some API/test stubs, present on device. */
    private static void unifiedSetBounds(Drawable d, int l, int t, int r, int b) {
        try {
            java.lang.reflect.Method m = d.getClass().getMethod("setBounds",
                    int.class, int.class, int.class, int.class);
            m.invoke(d, Integer.valueOf(l), Integer.valueOf(t),
                    Integer.valueOf(r), Integer.valueOf(b));
        } catch (Throwable ignored) {
        }
    }

    /**
     * Step 2 sampling: paint a Drawable into a 3x3 bitmap and return the nine
     * ARGB pixels. Bounds are saved and restored so a diagnostic probe leaves
     * the Drawable byte-for-byte as it found it (zero-pixel-change promise).
     */
    private static boolean unifiedSample3x3(Drawable d, int[] out) {
        if (d == null || out == null || out.length < 9) return false;
        Bitmap b = null;
        android.graphics.Rect orig = null;
        int l = 0, t = 0, r = 0, bo = 0;
        try {
            orig = d.getBounds();
            if (orig != null) {
                l = orig.left; t = orig.top; r = orig.right; bo = orig.bottom;
            }
            b = Bitmap.createBitmap(3, 3, Bitmap.Config.ARGB_8888);
            unifiedSetBounds(d, 0, 0, 3, 3);
            d.draw(new Canvas(b));
            for (int y = 0; y < 3; y++) {
                for (int x = 0; x < 3; x++) out[y * 3 + x] = b.getPixel(x, y);
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        } finally {
            unifiedSetBounds(d, l, t, r, bo);
            if (b != null) {
                try { b.recycle(); } catch (Throwable ignored) {}
            }
        }
    }

    /** True when any sampled point is not fully opaque -> a fading layer. */
    private static boolean unifiedAlphaGradient(int[] px) {
        for (int i = 0; i < 9; i++) {
            if (((px[i] >>> 24) & 0xFF) != 0xFF) return true;
        }
        return false;
    }

    /** Compact alpha distribution for COLORCAND, e.g. "ff:9" or "ff:6,80:3". */
    private static String unifiedAlphaDist(int[] px) {
        StringBuilder sb = new StringBuilder(24);
        int[] keys = new int[9];
        int[] counts = new int[9];
        int kinds = 0;
        for (int i = 0; i < 9; i++) {
            int a = (px[i] >>> 24) & 0xFF;
            int found = -1;
            for (int k = 0; k < kinds; k++) if (keys[k] == a) { found = k; break; }
            if (found < 0) { found = kinds; keys[found] = a; counts[found] = 1; kinds++; }
            else counts[found]++;
        }
        for (int k = 0; k < kinds; k++) {
            if (k > 0) sb.append(',');
            sb.append(Integer.toHexString(keys[k])).append(':').append(counts[k]);
        }
        return sb.toString();
    }

    /**
     * Step 2 colour verdict. Requires every point alpha==0xFF and all channels
     * within UNIFIED_COLOR_TOL of each other (multi-point uniformity — the 1x1
     * sample in measuredOpaqueWhite() cannot see a gradient). Tier 1 additionally
     * requires near-white (>=0xE0); tier 2 (aggressive) drops near-white but
     * keeps uniformity.
     */
    private static boolean unifiedUniformOpaque(int[] px, boolean nearWhite) {
        int r0 = -1, g0 = -1, b0 = -1;
        for (int i = 0; i < 9; i++) {
            int p = px[i];
            if (((p >>> 24) & 0xFF) != 0xFF) return false;
            int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
            if (i == 0) { r0 = r; g0 = g; b0 = b; }
            else {
                if (Math.abs(r - r0) > UNIFIED_COLOR_TOL) return false;
                if (Math.abs(g - g0) > UNIFIED_COLOR_TOL) return false;
                if (Math.abs(b - b0) > UNIFIED_COLOR_TOL) return false;
            }
            if (nearWhite && (r < UNIFIED_NEAR_WHITE || g < UNIFIED_NEAR_WHITE
                    || b < UNIFIED_NEAR_WHITE)) return false;
        }
        return true;
    }

    /** Step 0 structural gate. Id is logged only; it never decides a hit. */
    private boolean unifiedStructGate(View v, String cls, String rid) {
        if (v == null || v == decor || v == overlay || v == glassHost
                || v == glassLayer) return false;
        int w = v.getWidth(), h = v.getHeight();
        int dw = decor == null ? 0 : decor.getWidth();
        if (dw <= 0 || w <= 0 || h <= 0) return false;
        if (w < dw * UNIFIED_MIN_WIDTH_RATIO) return false;
        if (h < UNIFIED_MIN_HEIGHT_PX) return false;
        // Full-screen OEM root is never a card (finance-app safety rule).
        if (decor != null && h >= decor.getHeight() * 9 / 10) return false;
        if (unifiedSmallControl(cls)) return false;
        // p110: avatar/nickname subtree is CONTENT, never a card fill. This is
        // the one minimal structural keep; every other id is diagnostic only.
        if (rid != null && rid.toLowerCase().contains("avatar")) return false;
        return true;
    }

    /**
     * Step 1 fade exclusion — MEASURED transparency first, any real hit keeps
     * the layer regardless of colour. getOpacity() is recorded but NOT decisive
     * on its own: p80 proved StateList/Gradient report TRANSLUCENT while
     * painting an opaque white fill. getAlpha() (view + drawable) and the 3x3
     * alpha gradient ARE decisive because they are measured, not conservative
     * type metadata. Role split (p111): we only replace THIS view's own fills;
     * children keep drawing, so a fade child survives.
     */
    private boolean unifiedIsFade(View v, Drawable bg, Drawable fg,
            boolean[] bgSampled, boolean[] fgSampled, int[] bgPx, int[] fgPx) {
        boolean fade = false;
        try {
            if (v.getAlpha() < 1f) fade = true;
        } catch (Throwable ignored) {
        }
        if (bg != null && unifiedDrawableAlpha(bg) < 255) fade = true;
        if (fg != null && unifiedDrawableAlpha(fg) < 255) fade = true;
        // Sample once; the SAME pixels feed Step 2 so the gate pays for one
        // bounded 3x3 probe per drawable, never per frame.
        bgSampled[0] = bg != null && unifiedSample3x3(bg, bgPx);
        fgSampled[0] = fg != null && unifiedSample3x3(fg, fgPx);
        if (bgSampled[0] && unifiedAlphaGradient(bgPx)) fade = true;
        if (fgSampled[0] && unifiedAlphaGradient(fgPx)) fade = true;
        return fade;
    }

    /**
     * Step 3: transparent placeholder, NOT null and NOT hidden. Keeping the
     * Drawable non-null preserves CardScanner's hasBg/collectGeneric path, so
     * glass geometry cannot lose the candidate. ColorDrawable(0) has alpha 0,
     * so isOpaqueSolidFill() rejects it and it is never re-stripped as a card.
     * No setVisibility(GONE), no setAlpha(0), no setWillNotDraw on a whole root.
     */
    private void replaceCardFillWithTransparent(View v, Drawable bg, Drawable fg) {
        Saved s = savedRows.get(v);
        if (s == null) {
            s = new Saved();
            savedRows.put(v, s);
        }
        if (s.background == null) s.background = bg;
        if (s.foreground == null) s.foreground = fg;
        if (s.placeholder == null) s.placeholder = new ColorDrawable(0);
        boolean changed = false;
        if (bg != null) {
            try { v.setBackground(s.placeholder); changed = true; } catch (Throwable ignored) {}
        }
        if (fg != null) {
            try { v.setForeground(s.placeholder); changed = true; } catch (Throwable ignored) {}
        }
        if (changed) diagUnifiedPlaceholders++;
    }

    /** True when the current fills are still OUR placeholder (or empty). */
    private static boolean unifiedIsOurPlaceholder(View v, Saved s) {
        try {
            Drawable bg = v.getBackground();
            Drawable fg = v.getForeground();
            boolean bgOk = s.background == null
                    ? (bg == null || bg == s.placeholder) : (bg == s.placeholder);
            boolean fgOk = s.foreground == null
                    ? (fg == null || fg == s.placeholder) : (fg == s.placeholder);
            return bgOk && fgOk;
        } catch (Throwable t) {
            return true; // identity check threw: stay conservative, never clear ours
        }
    }

    /**
     * OEM re-bound a fresh Drawable over our placeholder. Re-apply the SAME
     * placeholder instance (zero allocation, frame-path safe); the original is
     * kept from the first save, exactly as design §六.3 requires.
     */
    private boolean unifiedReapplyPlaceholder(View v, Saved s) {
        boolean changed = false;
        try {
            Drawable bg = v.getBackground();
            if (bg != null && bg != s.placeholder) {
                v.setBackground(s.placeholder);
                changed = true;
            }
        } catch (Throwable ignored) {
        }
        try {
            Drawable fg = v.getForeground();
            if (fg != null && fg != s.placeholder) {
                v.setForeground(s.placeholder);
                changed = true;
            }
        } catch (Throwable ignored) {
        }
        return changed;
    }

    /**
     * COLORCAND pure diagnostic — logs every measured candidate, zero pixel
     * change. Bounded to COLORCAND_MAX_LINES lines and throttled; called only
     * from the bounded stripRowsIn() walk, never from syncRows().
     */
    private void logColorCandidate(View v, String rid, String cls,
            Drawable bg, Drawable fg, boolean fade,
            boolean bgSampled, boolean fgSampled, int[] bgPx, int[] fgPx,
            boolean strip1, boolean strip2) {
        if (colorCandLogLines >= COLORCAND_MAX_LINES) return;
        long now = SystemClock.uptimeMillis();
        boolean interesting = fade || strip1 || strip2;
        if (now - lastColorCandLogMs < 150L && !interesting) return;
        lastColorCandLogMs = now;
        colorCandLogLines++;
        String parent = "-";
        try {
            if (v.getParent() != null) parent = v.getParent().getClass().getSimpleName();
        } catch (Throwable ignored) {
        }
        String opBg = bg == null ? "-" : String.valueOf(bg.getOpacity());
        String opFg = fg == null ? "-" : String.valueOf(fg.getOpacity());
        String aBg = bgSampled ? unifiedAlphaDist(bgPx) : "-";
        String aFg = fgSampled ? unifiedAlphaDist(fgPx) : "-";
        boolean family = unifiedFadeFamilyWord(cls + "|" + (rid == null ? "" : rid));
        GlassEntry.say("mg-state COLORCAND n=" + colorCandLogLines
                + " id=" + rid
                + " cls=" + v.getClass().getSimpleName()
                + " " + v.getWidth() + "x" + v.getHeight()
                + " bg=" + drawableDesc(bg) + " fg=" + drawableDesc(fg)
                + " op=" + opBg + "/" + opFg
                + " alpha=" + aBg + "|" + aFg
                + " fade=" + (fade ? 1 : 0) + " family=" + (family ? 1 : 0)
                + " strip1=" + (strip1 ? 1 : 0) + " strip2=" + (strip2 ? 1 : 0)
                + " parent=" + parent + " slot=" + (slot == null ? "-" : slot.id));
    }

    /**
     * The four-step gate entry point. Called at the TOP of stripRowsIn()'s
     * bounded traversal. Hard gate: L1 is a no-op; both switches are read live.
     * COLORCAND (default ON) only logs; GLASS_UNIFIED_CARDS (default OFF) strips.
     */
    private void unifiedCardSweep(View v) {
        if (v == null || slot == ConfigContract.Slot.L1 || cfg == null) return;
        boolean diagOn = cfg.asBool(ConfigContract.Param.GLASS_COLOR_DIAG);
        boolean stripOn = cfg.asBool(ConfigContract.Param.GLASS_UNIFIED_CARDS);
        if (!diagOn && !stripOn) return;
        try {
            String cls = v.getClass().getName().toLowerCase();
            String rid = resourceEntry(v);
            // Step 0 — structural gate; id is for the log, not the verdict.
            if (!unifiedStructGate(v, cls, rid)) return;

            Drawable bg = null, fg = null;
            try { bg = v.getBackground(); } catch (Throwable ignored) {}
            try { fg = v.getForeground(); } catch (Throwable ignored) {}
            // A latched row may be null now (legacy saveAndClear) or carry our
            // own placeholder — neither is a fresh OEM fill to judge.
            Saved prior = savedRows.get(v);
            if (prior != null && prior.placeholder != null) return;
            if (bg == null && fg == null) return;

            // Reused buffers: no allocation on this walk (zero-allocation rule).
            int[] bgPx = unifiedBgPx, fgPx = unifiedFgPx;
            boolean[] bgOk = unifiedBgOk, fgOk = unifiedFgOk;
            // Step 1 — fade exclusion BEFORE any colour decision.
            boolean fade = unifiedIsFade(v, bg, fg, bgOk, fgOk, bgPx, fgPx);

            // Step 2 — multi-point colour verdict on the SAME sampled pixels.
            boolean aggressive = cfg.asBool(ConfigContract.Param.GLASS_UNIFIED_AGGRESSIVE);
            boolean bgS1 = false, fgS1 = false, bgS2 = false, fgS2 = false;
            if (!fade) {
                if (bgOk[0]) {
                    bgS1 = unifiedUniformOpaque(bgPx, true);
                    bgS2 = bgS1 || unifiedUniformOpaque(bgPx, false);
                }
                if (fgOk[0]) {
                    fgS1 = unifiedUniformOpaque(fgPx, true);
                    fgS2 = fgS1 || unifiedUniformOpaque(fgPx, false);
                }
            }
            boolean strip1 = bgS1 || fgS1;
            boolean strip2 = bgS2 || fgS2;

            // COLORCAND diagnostic — zero pixels touched, always available.
            if (diagOn) {
                logColorCandidate(v, rid, cls, bg, fg, fade,
                        bgOk[0], fgOk[0], bgPx, fgPx, strip1, strip2);
            }
            // Step 3 — only when the (live) strip switch is ON.
            if (stripOn && !fade && (aggressive ? strip2 : strip1)) {
                int before = savedRows.size();
                replaceCardFillWithTransparent(v, bg, fg);
                if (savedRows.size() > before) {
                    diagUnifiedStrips++;
                    emitUnifiedIfDue();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** Throttled objective state line: mg-state UNIFIED ... (design §九). */
    private void emitUnifiedIfDue() {
        long now = SystemClock.uptimeMillis();
        if (now - lastUnifiedLogMs < 2000L) return;
        lastUnifiedLogMs = now;
        int rects = -1;
        try {
            if (decor != null && cfg != null) {
                float dens = decor.getResources().getDisplayMetrics().density;
                java.util.List<RectF> rs = CardScanner.rects(decor, cfg, dens);
                rects = rs == null ? 0 : rs.size();
            }
        } catch (Throwable ignored) {
        }
        GlassEntry.say("mg-state UNIFIED slot=" + (slot == null ? "-" : slot.id)
                + " strip=" + diagUnifiedStrips
                + " placeholder=" + diagUnifiedPlaceholders
                + " revived=" + diagUnifiedRevived
                + " rects=" + rects);
    }

    /**
     * 0.5.0-p105: the OTHER L2 pages the user listed as still having a white
     * title bar are all EXTERNAL processes (survey
     * `l2_topbar_survey_p105.txt`). p64's gate is `slot == L2 || L3` plus a
     * class-name chrome test, and it deliberately excluded every ext.* slot
     * except EXT_ID. On those pages the very same ROM bar is a plain
     * android.view.ViewGroup whose "toolbar" lives in the RESOURCE ID, so
     * isChromeClass() cannot match either. Both gates are bypassed here.
     *
     * L1 and Settings L2/L3 are never true here: genericEnabled is false in
     * Settings, and L1/L2/L3/GLOBAL are excluded explicitly.
     */
    private boolean extTopBarGate() {
        return genericEnabled && slot != null
                && slot != ConfigContract.Slot.L1
                && slot != ConfigContract.Slot.L2
                && slot != ConfigContract.Slot.L3
                && slot != ConfigContract.Slot.GLOBAL;
    }

    /** Resource entry name of a View's id, for the device log only. */
    private static String resourceEntry(View v) {
        try {
            int id = v.getId();
            return id == View.NO_ID ? "" : v.getResources().getResourceEntryName(id);
        } catch (Throwable ignored) {
            return "";
        }
    }

    /**
     * Structural CANDIDATE only -- the decision to strip is made by a MEASURED
     * Drawable property below, never by a name. p55's full-width rule (our
     * glass is inset 14dp each side) plus a ratio-based top band keeps the
     * bounded walk away from full-screen roots, and the gradient/fade ids are
     * excluded so the layer the user wants to KEEP is never a candidate.
     * Ratios, not a binary screen-Y threshold.
     */
    private boolean extTopBarCandidate(View v, String cls, String rid) {
        if (v == null || v == decor || v == overlay || v == glassHost) return false;
        if (!isFullWidthOem(v)) return false;
        int dh = decor == null ? 0 : decor.getHeight();
        int h = v.getHeight();
        if (dh <= 0 || h <= 0 || h > dh / 3) return false;
        try {
            v.getLocationOnScreen(topBarLoc);
        } catch (Throwable t) {
            return false;
        }
        if (topBarLoc[1] > dh / 4) return false;
        String low = (cls + "|" + rid).toLowerCase();
        if (low.contains("gradient") || low.contains("fade")
                || low.contains("top_gradient_bg")
                || low.contains("blur_wallpaper")) return false;
        return true;
    }

    /**
     * 0.5.0-p105: measure the object, then decide.
     *
     * p79 established the rule the user restated ("keep the fading layer"):
     * a fade overlay is TRANSLUCENT, an opaque near-white bar is not. Class
     * names containing "blur" / "transparent" are NOT keep words (p77 was
     * retracted for exactly that). This probe therefore strips ONLY when the
     * background / foreground MEASURES as an opaque near-white fill, using the
     * same p56 near-white test plus p80's 1x1 pixel test for StateList /
     * Gradient drawables that conservatively report op=-3.
     *
     * The whole result is printed on the default-visible `mg-state ` channel:
     * a candidate that is really a fade shows solid=0 measured=0 strip=0 and
     * is left alone. One log slice settles the classification on device.
     */
    private void probeExtTopBar(View v, String cls, String rid) {
        try {
            Drawable bg = v.getBackground();
            Drawable fg = v.getForeground();
            boolean solid = isOpaqueSolidFill(bg) || isOpaqueSolidFill(fg);
            boolean measured = !solid
                    && (measuredOpaqueWhite(bg) || measuredOpaqueWhite(fg));
            boolean opaque = solid || measured;
            boolean fade = !opaque && isFadeWhiteLayer(v);
            boolean stripped = false;
            if (opaque) {
                int before = savedRows.size();
                saveAndClear(v);
                if (savedRows.size() > before) {
                    diagTopBarStrips++;
                    stripped = true;
                }
            }
            long now = SystemClock.uptimeMillis();
            if (topBarLogLines < 14
                    && (now - lastTopBarLogMs > 400L || stripped)) {
                lastTopBarLogMs = now;
                topBarLogLines++;
                GlassEntry.say("mg-state TOPBAR slot=" + slot.id
                        + " cls=" + v.getClass().getSimpleName()
                        + " rid=" + rid
                        + " " + v.getWidth() + "x" + v.getHeight()
                        + " top=" + topBarLoc[1]
                        + " bg=" + drawableDesc(bg)
                        + " fg=" + drawableDesc(fg)
                        + " solid=" + (solid ? 1 : 0)
                        + " measured=" + (measured ? 1 : 0)
                        + " fade=" + (fade ? 1 : 0)
                        + " strip=" + (stripped ? 1 : 0)
                        + " total=" + diagTopBarStrips);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 0.5.0-p66 荣耀互联 10 白块 -- HARD GATE.
     *
     * findings 49: the page is com.hihonor.controlcenter / MainSettingsActivity,
     * slot=ext.controlcenter, generic=true. Its tree uses HwScrollView (NOT a
     * RecyclerView), so p56's structural row strip never matched. genericBlocks()
     * keeps only the OUTERMOST opaque container, and syncRows() strips that set
     * plus ANCESTORS -- never descendants. The overlay is installed at
     * decor.addView(..., 0), i.e. BELOW all OEM content, so the un-stripped
     * child cards paint pure white (255,255,255) over the lower half of the
     * glass. That is the measured 49.3 pixel truth, not a shader failure.
     *
     * Only this one slot is affected. L1 (p30), L2/L3 (p35/p64) and the other
     * five external apps take the byte-for-byte identical old path.
     */
    private boolean extWhiteGate() {
        return genericEnabled && slot == ConfigContract.Slot.EXT_CONTROLCENTER;
    }

    /**
     * 0.5.0-p77: 账号中心 is the EXTERNAL process com.hihonor.id, so its
     * slot is EXT_ID (findings §2130 real-device log "slot=ext.id edges=
     * [49..1223 49..1223] 2 of 3 white cards found"; §999 "com.hihonor.id
     * ... HOST_UIKIT"). The user calls it an "L2" page, but PageLevel.slotFor()
     * maps every non-Settings package straight to forPackage(), so none of
     * the L2/L3-only gates (p35, p64) ever applied to it. That is why its
     * top rectangular white card survived every earlier white-card fix.
     *
     * Only this ONE slot is added. L1 (p30 frozen), Settings L2/L3 and the
     * other five external apps keep the byte-for-byte identical old path.
     */
    private boolean accountIdGate() {
        return genericEnabled && slot == ConfigContract.Slot.EXT_ID;
    }

    /**
     * 0.5.0-p79: the "上面那个渐隐白色效果" the user explicitly wants to
     * KEEP must be identified by its MEASURED property, not by a class-name
     * keep word.
     *
     * p77 kept EVERY class whose name contained "blur". But p64's §24.3
     * already recorded the opposite: "Soft words (search/header/blur/switch)
     * do NOT reject a card-named View, so HnBlurCardXxx is still detected" —
     * i.e. a blur-named View CAN be the opaque white card. keepBackground()
     * also keeps every "blur"/"appbar" class (p64 §4). On 账号中心 those two
     * keep words preserved exactly the rectangle the user asked to remove,
     * which is why p77/p78 changed nothing visually.
     *
     * A fading overlay is TRANSLUCENT by construction; an OPAQUE solid fill
     * can never be one. Measured property wins over the name. AppbarBlurBg
     * (044_L2_whitecard_top.txt line 1498) and collapsing-header gradient
     * masks survive; a near-white ColorDrawable card does not.
     */
    private static boolean isFadeWhiteLayer(View v) {
        String n = v.getClass().getName().toLowerCase();
        try {
            Drawable bg = v.getBackground();
            Drawable fg = v.getForeground();
            // DECISIVE: an opaque solid fill is a WHITE CARD, never a fade.
            if (isOpaqueSolidFill(bg) || isOpaqueSolidFill(fg)) return false;
            boolean translucent = (bg != null && bg.getOpacity() != -1)
                    || (fg != null && fg.getOpacity() != -1);
            // Only the actual appbar blur background keeps its name-based
            // protection; a generic "blur" card name no longer does.
            boolean structural = n.contains("appbarblurbg")
                    || n.contains("gradient") || n.contains("fade");
            return translucent || structural;
        } catch (Throwable ignored) {
            return n.contains("appbarblurbg");
        }
    }

    /** Measured-property strip, same predicate p56/p64 proved on this ROM. */
    private void stripExtWhiteChild(View v) {
        boolean account = accountIdGate();
        if ((!extWhiteGate() && !account) || v == null || v == decor
                || v == overlay || v == glassHost) return;
        // 0.5.0-p79: on 账号中心 the class-name whitelist is the bug, not a
        // protection. keepBackground() keeps "blur"/"appbar" (p64 §4), so it
        // shielded the opaque account header card. Use the MEASURED fade
        // guard instead: translucent overlays survive, opaque cards do not.
        // All other slots keep the byte-for-byte identical p66/p77 path.
        if (account) {
            if (isFadeWhiteLayer(v)) return;
        } else if (keepBackground(v)) return;
        try {
            Drawable bg = v.getBackground();
            Drawable fg = v.getForeground();
            if (!isOpaqueSolidFill(bg) && !isOpaqueSolidFill(fg)) return;
            int before = savedRows.size();
            saveAndClear(v);
            if (savedRows.size() <= before) return;
            diagExtStrips++;
            long now = SystemClock.uptimeMillis();
            if (GlassEntry.verbose() && now - lastExtLogMs > 250L) {
                lastExtLogMs = now;
                log((account ? "ACCOUNTWHITE " : "EXTWHITE ")
                        + v.getClass().getSimpleName()
                        + " " + v.getWidth() + "x" + v.getHeight()
                        + " bg=" + drawableDesc(bg)
                        + " strip=1 slot=" + slot.id);
            }
        } catch (Throwable ignored) {
        }
    }

    /** One level below the discovered generic cards, every frame. */
    private void stripExtCardChildren(List<View> stripAll) {
        // 0.5.0-p79: p77 widened stripExtWhiteChild() for 账号中心 but left
        // THIS caller gated on controlcenter only, so the per-frame
        // syncRows() path never reached the account page at all.
        if ((!extWhiteGate() && !accountIdGate()) || stripAll == null) return;
        for (View v : stripAll) {
            if (!(v instanceof ViewGroup)) continue;
            ViewGroup g = (ViewGroup) v;
            int n;
            try {
                n = g.getChildCount();
            } catch (Throwable t) {
                continue;
            }
            if (n > 12) n = 12;
            for (int i = 0; i < n; i++) {
                stripExtWhiteChild(g.getChildAt(i));
            }
        }
    }

    /**
     * 0.5.0-p107: ONE structural rule for ALL external L2 pages.
     *
     * The user's goal is "所有 L2 统一样式". p106 listed per-page ids and
     * therefore only ever touched 桌面和个性化. The device survey shows the
     * seven external pages share ONE structure with different names:
     *
     *   hn_blur_top_container / hn_blur_top_view / hnblur /
     *   hnblurappbar_background_layout / content_header_container /
     *   blur_wallpaper_bg
     *   = y 0..135, width == screen width, height 196..766
     *   plus a sibling gradient layer (top_gradient_bg / *gradient*).
     *
     * So candidate selection is STRUCTURAL (position + size + ratio), not an
     * id whitelist. The upper height bound is screen/3 (933 px here) so the
     * 766 px app-bar container on 隐私和安全 is included while the 2800 px
     * hn_blur_pattern full-screen root is still excluded. Ratios, not a
     * binary screen-Y threshold.
     *
     * The fading layer is a HARD KEEP by its measured role (gradient / fade /
     * collapsing). Everything else that matches gets its OWN drawing
     * suppressed: saveAndClear() drops the opaque background/foreground and
     * setWillNotDraw(true) stops the container's own onDraw, WITHOUT hiding
     * any child View -- that is what leaves the fading layer visible.
     */
    private static boolean isFadeTopLayer(String cls, String rid) {
        // DEVICE EVIDENCE 15:40: hn_blur_top_container's class is
        // HnGradientBlurTopContainer -- the fading layer. Its RESOURCE ID has
        // no "gradient", so an id-only rule suppressed it (TOPCONTAINER
        // kind=base strip=1). The measured CLASS name is part of the object;
        // keep the fade by either name, on every external page.
        String s = ((cls == null ? "" : cls) + "|"
                + (rid == null ? "" : rid)).toLowerCase();
        return s.contains("gradient") || s.contains("fade")
                || s.contains("top_gradient") || s.contains("appbarblur")
                || s.contains("hnblurappbar") || s.contains("collapsing");
        // 0.5.0-p108: 044 line 1498 measured the fading container as
        // com.hihonor.android.widget.appbar.transparency.AppbarBlurBg with
        // id hnblurappbar_background_layout (TRANSLUCENT). Widening the
        // account-centre width gate below lets it into the candidate set, so
        // its CLASS and id must be hard-keep words before that happens.
    }

    private void logTopContainer(View v, String rid, String kind, boolean strip) {
        long now = SystemClock.uptimeMillis();
        if (topContainerLogLines >= 24) return;
        if (now - lastTopContainerLogMs < 400L && !strip) return;
        lastTopContainerLogMs = now;
        topContainerLogLines++;
        GlassEntry.say("mg-state TOPCONTAINER slot=" + slot.id
                + " id=" + rid
                + " cls=" + v.getClass().getSimpleName()
                + " " + v.getWidth() + "x" + v.getHeight()
                + " ratio=" + widthPct(v)
                + " top=" + topBarLoc[1]
                + " bg=" + drawableDesc(v.getBackground())
                + " kind=" + kind
                + " strip=" + (strip ? 1 : 0)
                + " total=" + diagTopContainers);
    }

    /** Suppress the container's OWN drawing only; children keep drawing. */
    private void suppressTopContainer(View v, String rid) {
        try {
            Saved s = savedRows.get(v);
            if (s == null) {
                s = new Saved();
                savedRows.put(v, s);
            }
            s.willNotDraw = true;
            saveAndClear(v);               // latched; restoreRows() puts it back
            v.setWillNotDraw(true);        // no self-drawn white bottom
            diagTopContainers++;
            logTopContainer(v, rid, "base", true);
        } catch (Throwable ignored) {
        }
    }

    // 0.5.0-p128: bounded HALFWHITE diagnostic budget (24 lines/page, 150 ms).
    private int halfWhiteLogLines;
    private long lastHalfWhiteLogMs;

    /**
     * 0.5.0-p128: suppress the OWN onDraw white fill of the five account-security
     * rows (hwlistpattern_parent / HiHonorProportionTextLayout, findings §106.2
     * & §125.5). Those rows are bg=null yet paint white in onDraw; the glass is
     * UNDER the OEM tree, so a glass rect cannot cover them. setWillNotDraw skips
     * only the row's own draw pass while dispatchDraw still renders its text/icon
     * children, so geometry is untouched. Reuses the Saved latch + restoreRows()
     * restore path, exactly like suppressTopContainer().
     *
     * HARD GATE: EXT_ID only + exact id + measured class/size. L1 (and every
     * other slot) has neither this id nor EXT_ID, so this is a no-op there.
     */
    private void suppressAccountHalfWhite(View v) {
        if (!accountIdGate() || v == null || !(v instanceof ViewGroup)) return;
        try {
            String rid = resourceEntry(v);
            if (!"hwlistpattern_parent".equals(rid)) return;
            String cls = v.getClass().getName().toLowerCase();
            if (!cls.contains("proportiontextlayout")) return;
            int w = v.getWidth(), h = v.getHeight();
            if (decor == null || decor.getWidth() <= 0) return;
            if (w <= 0 || w <= 0.9f * decor.getWidth()) return;
            if (h < 140 || h > 260) return;   // measured rows are 168 / 239 px
            Saved s = savedRows.get(v);
            if (s == null) {
                s = new Saved();
                savedRows.put(v, s);
            }
            s.willNotDraw = true;
            saveAndClear(v);
            v.setWillNotDraw(true);
            logHalfWhite(v, rid, w, h);
        } catch (Throwable ignored) {
        }
    }

    private void logHalfWhite(View v, String rid, int w, int h) {
        long now = SystemClock.uptimeMillis();
        if (halfWhiteLogLines >= 24 || now - lastHalfWhiteLogMs < 150L) return;
        lastHalfWhiteLogMs = now;
        halfWhiteLogLines++;
        GlassEntry.say("mg-state HALFWHITE slot=" + slot.id + " id=" + rid
                + " cls=" + v.getClass().getSimpleName()
                + " w=" + w + " h=" + h + " willNotDraw=1");
    }

    /**
     * 0.5.0-p130: 隐藏账号安全顶部盾牌白底图。真机实测(bg 探针全部 null、
     * 用户验证拉动盾牌白底不动)证明：红框那层浅白是 hv_ripple_view_bg
     * (HwImageView 1272x1176, top=0) 的 src 图片内容，不是 background，
     * 故剥背景/自绘都无效，只能让它不可见。盾牌 ripple_view(Lottie)在
     * 另一条 view 分支，隐藏本图不影响盾牌动画。
     * 只 EXT_ID + 精确 id + 首次记录；detach 恢复 VISIBLE。L1/其它页无此 id。
     */
    private void hideAccountShieldBg(View v) {
        if (!"hv_ripple_view_bg".equals(resourceEntry(v))) return;
        if (accountShieldBgHidden == v) return;          // 已隐藏，幂等
        if (accountShieldBgHidden != null) return;       // 本页已记录别的实例
        try {
            accountShieldBgHidden = v;
            v.setVisibility(View.GONE);
            GlassEntry.say("mg-state SHIELDBG slot=" + slot.id
                    + " id=hv_ripple_view_bg hidden=1 cls="
                    + v.getClass().getSimpleName());
        } catch (Throwable ignored) {
            accountShieldBgHidden = null;
        }
    }

    /**
     * 0.5.0-p135 (纯诊断): 打印荣耀互联 card_item 的白源属性——背景 Drawable
     * 类型/颜色/opacity、measured near-white、willNotDraw、父链。用于一次采
     * 确定白来自可剥背景还是自绘 onDraw；不改变任何判据/几何/剥除。
     * 有界 12 行/页 + 150ms，仅 ext.controlcenter + 精确 id。
     */
    private void probeCardItemWhite(View v) {
        if (!"card_item".equals(resourceEntry(v))) return;
        long now = SystemClock.uptimeMillis();
        if (cardItemLogLines >= 12 || now - lastCardItemLogMs < 150L) return;
        lastCardItemLogMs = now;
        cardItemLogLines++;
        try {
            Drawable bg = v.getBackground(), fg = v.getForeground();
            String parent = v.getParent() == null ? "-"
                    : v.getParent().getClass().getSimpleName();
            GlassEntry.say("mg-state CARDITEM slot=" + slot.id
                    + " id=card_item " + v.getWidth() + "x" + v.getHeight()
                    + " bg=" + drawableDesc(bg) + " fg=" + drawableDesc(fg)
                    + " nearWhite=" + (measuredOpaqueWhite(bg) || measuredOpaqueWhite(fg) ? 1 : 0)
                    + " willNotDraw=" + (v.willNotDraw() ? 1 : 0)
                    + " parent=" + parent);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 0.5.0-p137 (纯诊断): card_item 深度=21，晚扫 probeTopContainerIn
     * (depth<=12) 到不了、早窗口 stripRowsIn 的 600 节点预算易被前置节点
     * 耗尽。用一个独立的有界 walk(depth<=24, budget=400) 专找 card_item，
     * 不受其它 walk 的竞争。纯日志，不改任何判据/几何/剥除。
     */
    private void probeCardItemWalk(View v, int depth, int[] budget) {
        if (v == null || depth > 24 || budget[0]-- <= 0) return;
        if (v == overlay || v == glassHost) return;
        probeCardItemWhite(v);
        // 0.5.0-p138: 真修——剥掉 card_item 的不透明白背景，露出下面的玻璃。
        // 走 saveAndClear 的 Saved latch，restoreRows 在 detach 恢复、OEM
        // re-bind 由 restripLatched 每帧重申。文字/图标是子 View，不被剥。
        if (slot == ConfigContract.Slot.EXT_CONTROLCENTER) stripCardItemWhite(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            int n = g.getChildCount();
            for (int i = 0; i < n; i++) {
                probeCardItemWalk(g.getChildAt(i), depth + 1, budget);
            }
        }
    }

    /**
     * 0.5.0-p138: 剥掉荣耀互联 card_item 的不透明白背景。用实测判据
     * (isOpaqueSolidFill/measuredOpaqueWhite)而非类名白名单——渐隐层/透明层
     * 自动放过。透明白/无背景直接跳过，零副作用。EXT_CONTROLLED 硬门，
     * L1/其它页无此 id 天然 no-op。
     */
    private void stripCardItemWhite(View v) {
        if (!"card_item".equals(resourceEntry(v))) return;
        // 幂等：已剥过(含 placeholder)就不再重判，避免把透明占位当新白源。
        Saved prior = savedRows.get(v);
        if (prior != null && (prior.background != null || prior.foreground != null
                || prior.placeholder != null)) return;
        try {
            Drawable bg = v.getBackground(), fg = v.getForeground();
            // 0.5.0-p139: 真机 CARDITEM 实测 bg=LayerDrawable/op=-3、
            // nearWhite=0——通用近白判据测不到(LayerDrawable 内部层画白)。
            // 但 id=card_item 精确门控 + EXT_CONTROLLED slot 已是足够强的
            // 结构证据：这些卡白来自各自 bg/fg drawable，直接剥(同 p45 手法)，
            // 圆角/描边随白卡一起去掉，正好换成下面的玻璃；文字图标是子
            // View 不受影响。bg/fg 都空才跳过。
            if (bg == null && fg == null) return;
            Saved s = prior;
            if (s == null) {
                s = new Saved();
                savedRows.put(v, s);
            }
            s.willNotDraw = true;
            saveAndClear(v);
            v.setWillNotDraw(true);
            // 同时打父容器 SuperDeviceCard 的背景，若剥 card_item 后仍白，
            // 这条日志直接指出白是否在父层，避免再等一轮诊断。
            String parentBg = "-";
            try {
                if (v.getParent() instanceof View) {
                    parentBg = drawableDesc(((View) v.getParent()).getBackground());
                }
            } catch (Throwable ignored) {
            }
            GlassEntry.say("mg-state CARDSTRIP slot=" + slot.id
                    + " id=card_item " + v.getWidth() + "x" + v.getHeight()
                    + " bg=" + drawableDesc(bg) + " parentBg=" + parentBg
                    + " stripped=1");
        } catch (Throwable ignored) {
        }
    }

    /**
     * 0.5.0-p143: 剥掉通知共享页(SuperNotificationActivity / EXT_SYSTEMMANAGER)
     * 设置行的 OEM 不透明白背景(main_switcher / watch_switcher),露出下面已存在
     * 的玻璃矩形。白卡是 background Drawable 时 setWillNotDraw 拦不住、onDraw
     * 补扫会晚一帧,故走 layout-time strip(OnGlobalLayout 在 layout 后 draw 前),
     * 白卡那一帧根本不画出来。复用 p142 的 Saved latch + saveAndClear + 幂等。
     * 门控:slot==EXT_SYSTEMMANAGER + 精确 id + 实测 opaque/near-white;
     * 渐隐/透明一律放过。L1/其它 slot 天然 no-op。
     */
    /**
     * 0.5.0-p164: 应用页(AppAndNotificationDashboardActivity, l2)与电池主页
     * (HwPowerManagerActivity, ext.systemmanager)列表行首帧白卡——p161/p162
     * 把 dashboard_tile 加进 suppressor 候选、补扫 discovery，都走帧路径
     * (600ms 节流 + 400ms 早窗口)，冷启动/每次进入时行晚 inflate 后白卡
     * 那一帧已画出来。按 §8 首行 p142/p143 已验证正解：layout-time strip,
     * OnGlobalLayout(layout 后 draw 前)在这里剥背景，白卡那一帧根本不画。
     * 精确 Activity + slot + 行 id + 父链门控，Saved latch 幂等，L1/其它页
     * no-op。带 ENTRYSTRIP 诊断(bg/parentBg)，剥错时日志直接指出白在哪层。
     */
    private void stripEntryRowWhite(View v) {
        if (slot == null || slot == ConfigContract.Slot.L1) return;
        String act = activity == null ? "" : activity.getClass().getSimpleName();
        String rid = resourceEntry(v);
        boolean appPage = ConfigContract.Slot.L2 == slot
                && "AppAndNotificationDashboardActivity".equals(act)
                && "dashboard_tile".equals(rid)
                && hasAncestorResource(v, "external_app_settings_list", 8);
        // p165: 电池主页在 com.hihonor.systemmanager 进程,activity 字段不等于
        // HwPowerManagerActivity(真机 ENTRYSTRIP 0 条证实 p164 的 Activity 门控
        // 在 EXT 进程失效)。改用 slot + 行 id + 父链三个可靠门控,删掉 Activity 名。
        boolean battPage = ConfigContract.Slot.EXT_SYSTEMMANAGER == slot
                && "linearLayout_software".equals(rid)
                && (hasAncestorResource(v, "recycler_view", 8)
                    || hasAncestorResource(v, "fl_app_list", 8));
        if (!appPage && !battPage) return;
        // 幂等：已剥过就不再重判，避免把透明占位当新白源。
        Saved prior = savedRows.get(v);
        if (prior != null && (prior.background != null || prior.foreground != null
                || prior.placeholder != null)) return;
        try {
            Drawable bg = v.getBackground(), fg = v.getForeground();
            // 精确门控已足够强；有背景就剥(同 p142/p143)，bg/fg 都空跳过。
            if (bg == null && fg == null) return;
            if (isFadeTopLayer(v.getClass().getSimpleName(), rid)) return;
            Saved s = prior;
            if (s == null) {
                s = new Saved();
                savedRows.put(v, s);
            }
            s.willNotDraw = true;
            saveAndClear(v);
            v.setWillNotDraw(true);
            String parentBg = "-";
            try {
                if (v.getParent() instanceof View) {
                    parentBg = drawableDesc(((View) v.getParent()).getBackground());
                }
            } catch (Throwable ignored) {
            }
            long now = SystemClock.uptimeMillis();
            if (entryStripLogLines < 12 && now - lastEntryStripLogMs >= 150L) {
                lastEntryStripLogMs = now;
                entryStripLogLines++;
                GlassEntry.say("mg-state ENTRYSTRIP slot=" + slot.id
                        + " act=" + act + " id=" + rid
                        + " cls=" + v.getClass().getSimpleName()
                        + " bg=" + drawableDesc(bg) + " parentBg=" + parentBg
                        + " stripped=1");
            }
        } catch (Throwable ignored) {
        }
    }

    /** p164: 有界祖先 resource-id 检查(discovery/layout-time 路径,非帧热循环)。 */
    private static boolean hasAncestorResource(View v, String id, int maxDepth) {
        try {
            View cur = v.getParent() instanceof View ? (View) v.getParent() : null;
            int d = 0;
            while (cur != null && d++ < maxDepth) {
                if (id.equals(resourceEntry(cur))) return true;
                cur = cur.getParent() instanceof View ? (View) cur.getParent() : null;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** p164: 有界 walk,在 OnGlobalLayout(layout 后 draw 前)剥这两页的行白卡。 */
    private void probeEntryRowWalk(View v, int depth, int[] budget) {
        if (v == null || depth > 24 || budget[0]-- <= 0) return;
        if (v == overlay || v == glassHost) return;
        stripEntryRowWhite(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            int n = g.getChildCount();
            for (int i = 0; i < n; i++) {
                probeEntryRowWalk(g.getChildAt(i), depth + 1, budget);
            }
        }
    }

    private void stripNotifySwitchWhite(View v) {
        boolean notify = slot == ConfigContract.Slot.EXT_SYSTEMMANAGER;
        // 0.5.0-p144: 荣耀AI&YOYO 四项方卡(item 573x381, 在 RecyclerView#
        // switch_check_gridView 网格)首帧白卡——与 card_item 同构，复用同一
        // layout-time strip。id=item 通用，故额外要求直接父容器是
        // switch_check_gridView，避免误剥其它 item。
        boolean yoyo = slot == ConfigContract.Slot.EXT_ASSISTANT;
        if (!notify && !yoyo) return;
        String rid = resourceEntry(v);
        // 0.5.0-p156: 通知和状态栏页(NotificationManagmentActivity)点进某个
        // 应用后的通知设置页(NotificationAllChannelSettingsActivity, 同为
        // EXT_SYSTEMMANAGER)首行"允许通知"prefs_notification_main_switch
        // 首帧白卡闪一下后消失——同 p143 layout-time strip 场景。该 id 是
        // 该页独有(资源表预检)，加入同一幂等 strip；NOTIFSTRIP 诊断合并。
        // 0.5.0-p159: 电池详情(DetailOfSoftConsumptionActivity, 同
        // EXT_SYSTEMMANAGER)点进去白卡闪一下后消失——这几个内容行是电池
        // 详情页独有的卡片 id，首帧 OEM 白底先画、剥除晚一步。复用同一
        // layout-time strip(OnGlobalLayout + 早窗口两路径)，BATTSTRIP 诊断。
        boolean batt = notify
                && ("app_battery_bar_chart_layout".equals(rid)
                    || "cpu_time_layout".equals(rid)
                    || "cpu_time_back_layout".equals(rid)
                    || "app_control_layout".equals(rid)
                    || "selected_time_layout".equals(rid));
        if (notify && !"main_switcher".equals(rid) && !"watch_switcher".equals(rid)
                && !"prefs_notification_main_switch".equals(rid) && !batt) return;
        if (yoyo && !"item".equals(rid)) return;
        if (yoyo) {
            try {
                if (!(v.getParent() instanceof View)
                        || !"switch_check_gridView".equals(
                                resourceEntry((View) v.getParent()))) return;
            } catch (Throwable t) {
                return;
            }
        }
        // 幂等:已剥过(含 placeholder)就不再重判,避免把透明占位当新白源。
        Saved prior = savedRows.get(v);
        if (prior != null && (prior.background != null || prior.foreground != null
                || prior.placeholder != null)) return;
        try {
            Drawable bg = v.getBackground(), fg = v.getForeground();
            // 0.5.0-p143: 不靠近白判据——§135 已证 LayerDrawable/StateListDrawable
            // 测不出近白(main_switcher/watch_switcher 常是这种层叠背景)。
            // 精确 slot(EXT_SYSTEMMANAGER) + 精确 id(main/watch_switcher) 已是
            // 强结构证据：有背景就剥(同荣耀互联 card_item 手法)，只用 isFadeTopLayer
            // 放过渐隐层。圆角随白卡去掉换成下面已有玻璃，文字/开关是子 View 不动。
            if (bg == null && fg == null) return;
            if (isFadeTopLayer(v.getClass().getSimpleName(), rid)) return;
            Saved s = prior;
            if (s == null) {
                s = new Saved();
                savedRows.put(v, s);
            }
            s.willNotDraw = true;
            saveAndClear(v);
            v.setWillNotDraw(true);
            // 有界日志:每页 12 行 / 150ms,复用 card_item 的预算形状。
            long now = SystemClock.uptimeMillis();
            if (notifyStripLogLines < 12 && now - lastNotifyStripLogMs >= 150L) {
                lastNotifyStripLogMs = now;
                notifyStripLogLines++;
                GlassEntry.say("mg-state " + (yoyo ? "YOYOSTRIP"
                        : (batt ? "BATTSTRIP" : "NOTIFSTRIP"))
                        + " slot=" + slot.id
                        + " id=" + rid + " cls=" + v.getClass().getSimpleName()
                        + " bg=" + drawableDesc(bg) + " stripped=1");
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 0.5.0-p143: 有界 walk(depth<=20, budget=300)专找通知设置行 id,
     * 在 OnGlobalLayout(layout 后 draw 前)剥白,首帧不显示白卡。
     * 与 p142 的 probeCardItemWalk 独立,不触碰 card_item / EXT_CONTROLCENTER 路径。
     */
    private void probeNotifySwitchWalk(View v, int depth, int[] budget) {
        if (v == null || depth > 20 || budget[0]-- <= 0) return;
        if (v == overlay || v == glassHost) return;
        if (slot == ConfigContract.Slot.EXT_SYSTEMMANAGER
                || slot == ConfigContract.Slot.EXT_ASSISTANT) stripNotifySwitchWhite(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            int n = g.getChildCount();
            for (int i = 0; i < n; i++) {
                probeNotifySwitchWalk(g.getChildAt(i), depth + 1, budget);
            }
        }
    }

    /** detach 时恢复被 p130 隐藏的顶部盾牌白底图。 */
    private void restoreAccountShieldBg() {
        if (accountShieldBgHidden == null) return;
        try {
            if (accountShieldBgHidden.getVisibility() == View.GONE) {
                accountShieldBgHidden.setVisibility(View.VISIBLE);
            }
        } catch (Throwable ignored) {
        }
        accountShieldBgHidden = null;
    }

    /**
     * 0.5.0-p148: 隐藏桌面个性化主题预览卡下方的浅白块。真机 view tree:
     * 无 id FrameLayout[335,1759][937,1948] 内含无 id ImageView + View，
     * 白来自 src 图片/自绘(bg 探针测不到，同 p130 hv_ripple_view_bg)。
     * 精确门控: slot=EXT_WALLPAPEREDITOR + 无 id + 尺寸≈602x189 +
     * 父链含 history_viewpager_root；只对 ImageView 与它的空 View 兄弟
     * 设 GONE，FrameLayout 容器本身保留(几何不变)。幂等，List 记录。
     */
    private void hideWallpaperThumbWhite(View v) {
        if (slot != ConfigContract.Slot.EXT_WALLPAPEREDITOR) return;
        if (v == null || v.getId() != View.NO_ID) return;
        try {
            int w = v.getWidth(), h = v.getHeight();
            if (w < 560 || w > 640 || h < 170 || h > 210) return;
            // 父链须含 history_viewpager_root(预览卡外壳)，避免误伤别的
            // 同尺寸无 id View。
            boolean inHistory = false;
            ViewParent p = v.getParent();
            int d = 0;
            while (p instanceof View && d++ < 10) {
                if ("history_viewpager_root".equals(resourceEntry((View) p))) {
                    inHistory = true;
                    break;
                }
                p = ((View) p).getParent();
            }
            if (!inHistory) return;
            String cls = v.getClass().getSimpleName();
            // 0.5.0-p149: p148 只隐藏了子节点(cls=View)，灰块其实是外层
            // FrameLayout 容器自身的底色(跟着 ViewPager 滑动缩放)。把它
            // 也纳入——隐藏容器连带子 ImageView/View，一次去净。仍是
            // 精确 id(无)+尺寸+父链门控，L1/其它页 no-op。
            if (!"ImageView".equals(cls) && !"View".equals(cls)
                    && !"FrameLayout".equals(cls)) return;
            if (v.getVisibility() == View.GONE) return;   // 已隐藏，幂等
            wallpaperThumbHidden.add(v);
            v.setVisibility(View.GONE);
            GlassEntry.say("mg-state THUMBHIDE slot=" + slot.id
                    + " cls=" + cls + " " + w + "x" + h + " hidden=1");
        } catch (Throwable ignored) {
        }
    }

    /** 0.5.0-p148: 有界 walk 找主题预览卡下方的浅白块，layout-time 首帧隐藏。 */
    private void probeWallpaperThumbWalk(View v, int depth, int[] budget) {
        if (v == null || v == overlay || v == glassHost
                || depth > 20 || budget[0]-- <= 0) return;
        if (slot == ConfigContract.Slot.EXT_WALLPAPEREDITOR) hideWallpaperThumbWhite(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            int n = g.getChildCount();
            for (int i = 0; i < n; i++) {
                probeWallpaperThumbWalk(g.getChildAt(i), depth + 1, budget);
            }
        }
    }

    /**
     * 0.5.0-p151: 滑动卡图片区 ImageView#wallpaper_history_image [321,450]
     * [951,1836] 在壁纸图加载前显示白占位。剥它的 background/foreground
     * (saveAndClear 只动 bg/fg，绝不动 src —— 真实图片加载后仍会显示)，
     * Saved latch 幂等可恢复。日志同时打 bg/fg/src 类型：若剥 bg 后仍白，
     * 说明白来自 src 占位，则下一轮据日志改清 src，不再猜。
     * slot=EXT_WALLPAPEREDITOR + 精确 id 硬门，L1/其它页 no-op。
     */
    private void stripWallpaperImageBg(View v) {
        if (slot != ConfigContract.Slot.EXT_WALLPAPEREDITOR) return;
        if (!"wallpaper_history_image".equals(resourceEntry(v))) return;
        try {
            Drawable bg = v.getBackground(), fg = v.getForeground();
            // 幂等：已剥过(占位)就不再重判。
            Saved prior = savedRows.get(v);
            if (prior != null && (prior.background != null
                    || prior.foreground != null || prior.placeholder != null)) return;
            if (bg == null && fg == null) return;
            saveAndClear(v);
            // 诊断：bg/fg/src 三类型，一次换码定白源。
            String src = "-";
            try {
                if (v instanceof android.widget.ImageView) {
                    // 反射取 src，避免依赖ImageView.getDrawable()(stub 无此方法)。
                    java.lang.reflect.Method gm = v.getClass()
                            .getMethod("getDrawable");
                    Object d = gm.invoke(v);
                    src = d == null ? "null" : d.getClass().getSimpleName();
                }
            } catch (Throwable ignored) {
            }
            GlassEntry.say("mg-state WPIMGBG slot=" + slot.id
                    + " bg=" + drawableDesc(bg) + " fg=" + drawableDesc(fg)
                    + " src=" + src + " stripped=1");
        } catch (Throwable ignored) {
        }
    }

    /** 0.5.0-p151: 有界 walk 找壁纸图片 ImageView 剥其 bg。 */
    private void probeWallpaperImageWalk(View v, int depth, int[] budget) {
        if (v == null || v == overlay || v == glassHost
                || depth > 16 || budget[0]-- <= 0) return;
        if (slot == ConfigContract.Slot.EXT_WALLPAPEREDITOR)
            stripWallpaperImageBg(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            int n = g.getChildCount();
            for (int i = 0; i < n; i++) {
                probeWallpaperImageWalk(g.getChildAt(i), depth + 1, budget);
            }
        }
    }

    /**
     * 0.5.0-p153: 录屏原始像素定因——那块"白卡"其实是【浅蓝 RGB(168,204,240)】
     * 圆角占位，精确范围 x=[66..194] y=[169..296]，正是返回按钮 ImageButton
     * [63,135][196,331] 的图标加载态。p152 判据两处错：几何门(w150..450)把
     * 133 宽的按钮排除、近白判据测不出浅蓝 ⇒ TRANSWHITE=0。
     * 修正：按返回按钮精确几何命中 + 有 bg/fg 就剥(不去测颜色) + 日志打全
     * bg/fg/src 三类型。若剥 bg 后浅蓝仍在则是 src 占位，下轮据此反射清 src。
     * slot=EXT_WALLPAPEREDITOR 硬门 + 几何门，L1/其它页 no-op。
     */
    private void probeTransientWhite(View v, int depth, int[] budget) {
        if (v == null || v == overlay || v == glassHost
                || depth > 16 || budget[0]-- <= 0) return;
        if (slot == ConfigContract.Slot.EXT_WALLPAPEREDITOR) {
            try {
                int[] loc = new int[2];
                v.getLocationOnScreen(loc);
                int w = v.getWidth(), h = v.getHeight();
                // p154: 录屏原始像素全屏扫描铁证——真正的"玻璃卡"是左半边
                // 渐变块 x=[0..539] y=[450..638](540x188，左白右透明渐变)，
                // 不是返回按钮(p153 误判)。p153 日志已证返回按钮 bg=null/
                // src=HnIconVectorDrawableUtils(图标)，不该碰它。
                // 新几何门命中这块渐变：x≈0, top430..490, w400..700, h150..250。
                boolean backBtn = loc[0] >= 45 && loc[0] <= 85
                        && loc[1] >= 115 && loc[1] <= 165
                        && w >= 110 && w <= 175 && h >= 170 && h <= 230;
                boolean leftGrad = loc[0] <= 40 && loc[1] >= 420 && loc[1] <= 500
                        && w >= 400 && w <= 720 && h >= 140 && h <= 260;
                // 只处理左渐变块；返回按钮已证是图标，不再碰。
                if (leftGrad) {
                    Drawable bg = v.getBackground(), fg = v.getForeground();
                    Saved prior = savedRows.get(v);
                    boolean stripped = prior != null && (prior.background != null
                            || prior.foreground != null || prior.placeholder != null);
                    // 诊断：类型全打（GradDrawable 渐变测不出近白，故不看颜色）。
                    String src = "-";
                    try {
                        java.lang.reflect.Method gm = v.getClass().getMethod("getDrawable");
                        Object dr = gm.invoke(v);
                        src = dr == null ? "null" : dr.getClass().getSimpleName();
                    } catch (Throwable ignored) {
                    }
                    if (!stripped && (bg != null || fg != null)) {
                        saveAndClear(v);
                        GlassEntry.say("mg-state LEFTGRAD slot=" + slot.id
                                + " cls=" + v.getClass().getSimpleName()
                                + " id=" + resourceEntry(v)
                                + " " + w + "x" + h
                                + " bg=" + drawableDesc(bg) + " fg=" + drawableDesc(fg)
                                + " src=" + src + " stripped=1");
                    } else if (!stripped) {
                        GlassEntry.say("mg-state LEFTGRAD slot=" + slot.id
                                + " cls=" + v.getClass().getSimpleName()
                                + " id=" + resourceEntry(v)
                                + " " + w + "x" + h
                                + " bg=null fg=null src=" + src + " srcOnly=1");
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            int n = g.getChildCount();
            for (int i = 0; i < n; i++) {
                probeTransientWhite(g.getChildAt(i), depth + 1, budget);
            }
        }
    }

    /** 每帧有界检查过渡态白卡(150ms 节流，覆盖 1~2.4s 出现窗口)。 */
    private void checkTransientWhite() {
        if (slot != ConfigContract.Slot.EXT_WALLPAPEREDITOR || decor == null) return;
        long now = SystemClock.uptimeMillis();
        if (lastTransientWhiteMs != 0L && now - lastTransientWhiteMs < 150L) return;
        lastTransientWhiteMs = now;
        try {
            probeTransientWhite(decor, 0, new int[]{300});
        } catch (Throwable ignored) {
        }
    }

    /**
     * 0.5.0-p150: layout-time 首帧剥顶栏渐变白层 top_gradient_bg。此前它只在
     * probeTopContainerIn(600ms 节流晚扫)里剥，转场中间帧/冷启动首帧还没剥
     * 就画出来了(用户录屏抓到白卡出现再消失)。这里在 onGlobalLayout(draw 前)
     * 有界找精确 id 直接 suppressTopContainer，Saved latch 幂等；晚扫路径
     * 保留作兜底。slot=EXT_WALLPAPEREDITOR 硬门，L1/其它页 no-op。
     */
    private void probeTopGradientWalk(View v, int depth, int[] budget) {
        if (v == null || v == overlay || v == glassHost
                || depth > 16 || budget[0]-- <= 0) return;
        if (slot == ConfigContract.Slot.EXT_WALLPAPEREDITOR
                && "top_gradient_bg".equals(resourceEntry(v))) {
            if (stripTopBandNow()) suppressTopContainer(v, "top_gradient_bg");
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            int n = g.getChildCount();
            for (int i = 0; i < n; i++) {
                probeTopGradientWalk(g.getChildAt(i), depth + 1, budget);
            }
        }
    }

    /** 0.5.0-p148: detach 恢复被隐藏的浅白块(返回方向 finishing 不恢复)。 */
    private void restoreWallpaperThumbWhite() {
        for (View v : wallpaperThumbHidden) {
            try {
                if (v != null && v.getVisibility() == View.GONE) {
                    v.setVisibility(View.VISIBLE);
                }
            } catch (Throwable ignored) {
            }
        }
        wallpaperThumbHidden.clear();
    }

    /**
     * 0.5.0-p109: restore the accepted 0.94 width predicate for EVERY slot.
     *
     * p108's EXT_ID 0.90 relaxation was based on a misread of findings
     * §2130 `edges=[49..1223]`. The 19:25 device view-tree dumps
     * (account_center_viewtree_static/swiped_2026-11-16.txt) settle it:
     * 49..1223 is the MODULE'S OWN glass rect. The no-id View
     * [49,387][1223,625] moves to [49,97][1223,335] together with
     * avatar_card (-290 px), while hn_blur_top_container [0,0][1272,394]
     * and hn_id_toolbar [0,135][1272,331] never move. Block #3 is
     * com.hihonor.id:id/avatar_card, width 1272 (100% of the screen) --
     * the original 0.94 rule already covers it. No behaviour change for
     * the other seven external L2s, the 18 fixed Settings L2s or frozen L1.
     */
    private boolean extTopWidthOk(View v) {
        return isFullWidthOem(v);
    }

    /**
     * 0.5.0-p111: the TOP full-width band = 移动网络's content_header_container
     * / 账号中心's hn_blur_top_container. Both are [0,0][1272,394] FrameLayouts.
     * The account one was hard-kept as a fade because its CLASS name contains
     * "gradient"; that kept its OWN white fill too. This predicate separates
     * the CONTAINER (strip own drawing) from its gradient CHILD (keep). It is
     * y=0 + full width + height <= screen/3, and NEVER matches avatar_card or
     * a gradient/fade child.
     */
    /**
     * 0.5.0-p112: read the LIVE switch. cfg is re-resolved by refresh() on
     * every CONFIG_CHANGED broadcast; this key is in glassFingerprint(), so
     * toggling it in Settings invalidates the pool and re-renders in THIS
     * process in the same frame -- no restart, no dex reload.
     */
    private boolean stripTopBandNow() {
        return cfg == null
                || cfg.asBool(ConfigContract.Param.GLASS_STRIP_TOP_BAND);
    }

    /**
     * 0.5.0-p114: ONE structural rule for the top FULL-WIDTH band across the
     * external L2 pages. Device survey 2026-11-16 (external_l2_topband_survey*
     * .txt) shows the same band under different ids:
     *   hn_blur_top_container          FrameLayout [0,0][1272,394..590]
     *   hnblur                         FrameLayout [0,0][1272,394]
     *   hnblurappbar_background_layout LinearLayout [0,135][1272,766]
     *   content_header_container       FrameLayout [0,0][1272,394] (Settings L2)
     * The FADE children are DIFFERENT Views and must survive:
     *   blur_wallpaper_bg / top_gradient_bg / hn_blur_top_view
     * So: id names the CONTAINER family only; pure gradient/fade/avatar/
     * toolbar ids are excluded. Height bound screen/2 includes the 766 px
     * appbar container, still far below the 2800 px full-screen OEM root.
     */
    private static boolean isTopHeaderBand(String rid, int top, int dh, int h) {
        if (rid == null || dh <= 0 || h <= 0) return false;
        if (top > dh / 16) return false;
        if (h > dh / 2) return false;
        String r = rid.toLowerCase();
        if (r.contains("gradient") || r.contains("fade")
                || r.contains("avatar") || r.contains("toolbar")
                || r.contains("blur_wallpaper_bg")
                || r.contains("appbar")) return false;
        return r.equals("hn_blur_top_container")
                || r.equals("content_header_container")
                || r.equals("hnblur")
                // 0.5.0-p115: 荣耀钱包's top full-width band is named
                // hn_blur_top_view (NOT *_container) and its class is a plain
                // FrameLayout. Device log 20:31 showed 钱包 stripping only
                // the top=135 title-bar children while this y=0 band was
                // never a TOPCONTAINER candidate. Same role as 账号中心's
                // hn_blur_top_container, so admit the exact id (no wildcard).
                || r.equals("hn_blur_top_view")
                || r.contains("blur_top_container")
                || r.contains("header_container");
    }

    /** Integer percent, so the device log settles the width question. */
    private int widthPct(View v) {
        if (v == null || decor == null || decor.getWidth() <= 0) return 0;
        return v.getWidth() * 100 / decor.getWidth();
    }

    private void probeExtTopContainer(View v, String rid) {
        if (v == null || v == decor || v == overlay || v == glassHost) return;
        int dh = decor == null ? 0 : decor.getHeight();
        int h = v.getHeight();
        try {
            v.getLocationOnScreen(topBarLoc);
        } catch (Throwable t) {
            return;
        }
        // 0.5.0-p111: avatar_card is the USER INFO card (avatar + nickname).
        // p109/p110 stripped it and removed the user's portrait/nickname -- WRONG.
        // The user named the TOP full-width band instead (the counterpart of
        // 移动网络's content_header_container), so NEVER touch avatar_card.
        if ("avatar_card".equals(rid)) {
            return;
        }
        if (dh <= 0 || h <= 0 || !extTopWidthOk(v)) return;
        if (topBarLoc[1] > dh / 16) {
            // 0.5.0-p108: an inset card below the top band is block #4..#6
            // territory. The user said those are already gone and must NOT
            // be touched, so record it and return WITHOUT suppressing.
            if (slot == ConfigContract.Slot.EXT_ID) {
                logTopContainer(v, rid, "below", false);
            }
            return;
        }
        if (h > dh / 3) {                     // full-screen OEM root
            // 0.5.0-p115: 荣耀钱包's full-screen root id=hn_blur_pattern is a
            // REAL near-white fill on device (bg=ColorDrawable#fff2f3f6),
            // unlike 账号中心's bg=null root. p107 made ALL full roots
            // evidence-only, so 钱包's white base was never stripped and
            // showed through the translucent title bar. Strip ONLY the
            // MEASURED opaque near-white background (saveAndClear latches it
            // for restoreRows); do NOT setWillNotDraw the full root, which
            // must keep drawing its content children. Any full root that is
            // bg=null / translucent / dark stays evidence-only (no-op).
            Drawable fullBg = v.getBackground();
            boolean fullWhite = isOpaqueSolidFill(fullBg)
                    || measuredOpaqueWhite(fullBg);
            if (fullWhite && stripTopBandNow()) {
                int before = savedRows.size();
                saveAndClear(v);
                boolean did = savedRows.size() > before || fullBg != null;
                if (did) {
                    diagTopContainers++;
                    logTopContainer(v, rid, "fullstrip", true);
                    return;
                }
            }
            if (rid.toLowerCase().contains("blur")
                    || rid.toLowerCase().contains("pattern")) {
                logTopContainer(v, rid, "full", false);
            }
            return;
        }
        // 0.5.0-p111: the TOP FULL-WIDTH band is the counterpart of 移动网络's
        // content_header_container. 账号中心's hn_blur_top_container was
        // classified HnGradientBlurTopContainer and HARD-KEPT by isFadeTopLayer
        // (TOPCONTAINER never logged it), so its OWN white fill was never
        // stripped -- while 移动网络's header was stripped by p64 CHROME.
        // Strip the container's OWN drawing only (bg/fg + onDraw); child
        // gradient views keep drawing, so the fade the user wants to KEEP
        // survives. This is the literal "make it like 移动网络" fix.
        if (isTopHeaderBand(rid, topBarLoc[1], dh, h)) {
            // 0.5.0-p112: LIVE switch. OFF => log it as kept, no pixel change.
            if (stripTopBandNow()) {
                suppressTopContainer(v, rid);
            } else {
                logTopContainer(v, rid, "fade", false);
            }
            return;
        }
        // 0.5.0-p147: 顶栏"半透明白大卡"——top_gradient_bg 是 0..560 满宽
        // 渐变白层，名字含 gradient/top_gradient 被 isFadeTopLayer 硬保留，
        // 与兄弟层 hn_blur_top_container(已剥) 同区域同角色。用户要求去掉
        // 这块白：按【精确 id】开例外走 suppressTopContainer(同 p64/p111
        // 顶栏白条手法，saveAndClear+willNotDraw+Saved 可恢复)。id 为这类
        // 页面独有，L1/L2/L3 及其它页无此 id 天然 no-op；其余渐隐层仍保留。
        if ("top_gradient_bg".equals(rid)) {
            if (stripTopBandNow()) suppressTopContainer(v, rid);
            return;
        }
        // The fading layer is NEVER suppressed.
        if (isFadeTopLayer(v.getClass().getSimpleName(), rid)) {
            logTopContainer(v, rid, "fade", false);
            return;
        }
        suppressTopContainer(v, rid);
    }

    /**
     * 0.5.0-p117: DIAGNOSTIC ONLY -- logs, never strips. Covers the CONTENT
     * band (y > top strip) of com.hihonor.id sub-pages, which the p105-p115
     * TOPCONTAINER probe never measured. Bounded 120 nodes / depth 12,
     * invoked from the same 600 ms late walk, never from a frame path.
     * Threshold w>=300 & h in [100, dh/2] deliberately includes the two
     * 542 px square cards that p116 (w>=0.30*dh) would have missed.
     */
    private void probeContentArea(View v, int depth, int[] budget) {
        if (v == null || depth > 12 || budget[0]-- <= 0) return;
        if (v == overlay || v == glassHost) return; // skip OUR own layers
        try {
            int dh = decor == null ? 0 : decor.getHeight();
            int w = v.getWidth(), h = v.getHeight();
            if (w >= 300 && h >= 100 && h <= dh / 2) {
                v.getLocationOnScreen(topBarLoc);
                String rid = resourceEntry(v);
                Drawable bg = v.getBackground(), fg = v.getForeground();
                boolean solid = isOpaqueSolidFill(bg) || isOpaqueSolidFill(fg);
                boolean measured = measuredOpaqueWhite(bg) || measuredOpaqueWhite(fg);
                String parent = v.getParent() == null ? "-"
                        : v.getParent().getClass().getSimpleName();
                long now = SystemClock.uptimeMillis();
                // 0.5.0-p119: independent budget + per-id dedupe. The top
                // band can no longer starve the content measurement.
                String logKey = rid + "|" + topBarLoc[1] + "|" + w + "x" + h;
                if (contentSeen == null) {
                    contentSeen = new java.util.HashSet<String>();
                }
                boolean fresh = contentSeen.add(logKey);
                if (contentNodeLines < 60 && fresh) {
                    lastContentLogMs = now;
                    contentNodeLines++;
                    diagContentNodes++;
                    GlassEntry.say("mg-state CONTENTNODE slot=" + slot.id
                            + " id=" + rid
                            + " cls=" + v.getClass().getSimpleName()
                            + " " + w + "x" + h
                            + " top=" + topBarLoc[1]
                            + " bg=" + drawableDesc(bg)
                            + " fg=" + drawableDesc(fg)
                            + " solid=" + (solid ? 1 : 0)
                            + " measured=" + (measured ? 1 : 0)
                            + " parent=" + parent
                            + " depth=" + depth);
                }
            }
        } catch (Throwable ignored) {
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            int n;
            try { n = Math.min(g.getChildCount(), 20); } catch (Throwable t) { return; }
            for (int i = 0; i < n; i++) probeContentArea(g.getChildAt(i), depth + 1, budget);
        }
    }

    /** 0.5.0-p117: are the four square cards inside CardScanner.rects()? */
    private void probeContentRects() {
        try {
            if (decor == null || cfg == null) return;
            float dens = decor.getResources().getDisplayMetrics().density;
            java.util.List<RectF> rs = CardScanner.rects(decor, cfg, dens);
            StringBuilder sb = new StringBuilder();
            int total = rs == null ? 0 : rs.size();
            int n = Math.min(total, 14);
            for (int i = 0; i < n; i++) {
                RectF r = rs.get(i);
                sb.append((int) r.left).append(',').append((int) r.top)
                        .append(',').append((int) r.right).append(',')
                        .append((int) r.bottom).append(';');
            }
            GlassEntry.say("mg-state CONTENTRECTS slot=" + slot.id
                    + " n=" + total + " [" + sb + "]");
        } catch (Throwable t) {
            GlassEntry.say("mg-state CONTENTRECTS failed=" + t);
        }
    }

    private void latchListRowsNow() {
        // 0.5.0-p118: each bounded first-frame walk gets a fresh COLORCAND
        // line budget (capped at 60 lines/walk, throttled at 150 ms/line).
        colorCandLogLines = 0;
        // 0.5.0-p128: per-page HALFWHITE budget, same reset point as COLORCAND.
        halfWhiteLogLines = 0;
        // 0.5.0-p135: per-page CARDITEM diagnostic budget.
        cardItemLogLines = 0;
        // 0.5.0-p164: 应用页/电池主页行首帧白卡 strip 诊断预算。
        entryStripLogLines = 0;
        // 0.5.0-p140: 白卡剥除必须在【首帧早窗口】就发生，不能等 400ms 后的
        // 晚扫(p139 白卡先显示 0.4s 才消失)。latchListRowsNow 在早窗口每次
        // draw 都调，这里用 150ms 节流跑一次独立有界 walk(depth24/400节点)
        // 专剥 card_item(深度21)；stripCardItemWhite 靠 Saved latch 幂等。
        if (slot == ConfigContract.Slot.EXT_CONTROLCENTER && decor != null) {
            long now = SystemClock.uptimeMillis();
            if (lastCardStripWalkMs == 0L
                    || now - lastCardStripWalkMs >= 150L) {
                lastCardStripWalkMs = now;
                try {
                    probeCardItemWalk(decor, 0, new int[]{400});
                } catch (Throwable ignored) {
                }
            }
        }
        // 0.5.0-p148: 桌面个性化浅白块的早窗口补扫——layout 回调之后才
        // inflate 的新 View(p141 教训)在首帧漏隐藏，每次 draw 都有界扫一次
        // (150ms 节流)，幂等(已 GONE 的跳过)。覆盖"进入先闪一下"。
        if (slot == ConfigContract.Slot.EXT_WALLPAPEREDITOR && decor != null) {
            long now = SystemClock.uptimeMillis();
            if (lastThumbWalkMs == 0L || now - lastThumbWalkMs >= 150L) {
                lastThumbWalkMs = now;
                try {
                    probeWallpaperThumbWalk(decor, 0, new int[]{300});
                } catch (Throwable ignored) {
                }
                // 0.5.0-p150: 顶栏渐变白层也在早窗口剥(转场首帧兜底)。
                try {
                    probeTopGradientWalk(decor, 0, new int[]{200});
                } catch (Throwable ignored) {
                }
                // 0.5.0-p151: 滑动卡图片区白占位 bg 早窗口剥(幂等)。
                try {
                    probeWallpaperImageWalk(decor, 0, new int[]{300});
                } catch (Throwable ignored) {
                }
            }
        }
        // 0.5.0-p156: 通知页首帧白卡早窗口补扫——onGlobalLayout 的
        // probeNotifySwitchWalk 在部分首帧晚于白卡绘制(同 p131/p140 教训)，
        // 这里每次早窗口 draw 有界扫一次(150ms 节流)，幂等(savedRows latch)；
        // 覆盖"点进应用通知设置页白卡闪一下"。EXT_SYSTEMMANAGER 硬门，L1 no-op。
        if (slot == ConfigContract.Slot.EXT_SYSTEMMANAGER && decor != null) {
            long now = SystemClock.uptimeMillis();
            if (lastNotifyStripWalkMs == 0L
                    || now - lastNotifyStripWalkMs >= 150L) {
                lastNotifyStripWalkMs = now;
                try {
                    probeNotifySwitchWalk(decor, 0, new int[]{300});
                } catch (Throwable ignored) {
                }
            }
        }
        try {
            stripRowsIn(decor, 0, new int[]{600});
        } catch (Throwable ignored) {
        }
    }

    private void stripRowsIn(View v, int depth, int[] budget) {
        if (v == null || depth > 24 || budget[0]-- <= 0) return;
        if (v == overlay || v == glassHost) return;
        // 0.5.0-p118: four-step unified-card gate at the TOP of the bounded
        // walk. L1 / switch gating is inside unifiedCardSweep(); COLORCAND is
        // log-only, GLASS_UNIFIED_CARDS is the strip switch. This is the
        // bounded first-frame path, NEVER the per-frame syncRows() path.
        unifiedCardSweep(v);
        // 0.5.0-p131: 账号安全顶部盾牌白底必须在【首帧】就隐藏。p130 的调用
        // 挂在 probeTopContainerIn（400 ms 后的 600 ms 节流分支），早窗口
        // 前 400 ms 完全没碰白底 ⇒ 进页面闪半秒。stripRowsIn 在早窗口每次
        // draw 都跑（depth<=24 覆盖 hv_ripple_view_bg 的 depth=4），幂等方法
        // 重复调用直接 return；L1 及其它页无该 id，天然 no-op。
        if (slot == ConfigContract.Slot.EXT_ID) hideAccountShieldBg(v);
        // 0.5.0-p66: the bounded entry walk already visits every node once.
        // Reuse it for external-page child cards instead of adding a tree walk.
        stripExtWhiteChild(v);
        // 0.5.0-p128: 标题下方半屏白(账号安全 rl_account_center_list 的 5 行
        // hwlistpattern_parent / HiHonorProportionTextLayout)来自行容器自己的
        // onDraw 白底(实测全部 bg=null，§106.2)。在既有首帧有界 walk 里按
        // EXT_ID + id + class + 实测尺寸窄门控 suppress，dispatchDraw 仍画文字
        // 图标、几何不变；L1 无这些 id 且非 EXT_ID，天然 no-op。
        suppressAccountHalfWhite(v);
        // 0.5.0-p135 (纯诊断): 采荣耀互联 card_item 白源属性。
        // card_item 这个 id 是荣耀互联独有，无需 slot 门控（早窗口 slot
        // 可能尚未稳定到 EXT_CONTROLCENTER，p135 因此 0 行）。
        probeCardItemWhite(v);
        try {
            String cls = v.getClass().getName().toLowerCase();
            // 0.5.0-p64 HEADER BAR. Three reasons this survived every earlier
            // white-card fix: keepBackground() protects "toolbar"/"appbar";
            // stripRowsIn() returns at the RecyclerView while the header is
            // its SIBLING; OemCardSuppressor.isTarget() has no chrome entry.
            // Reuse the SAME bounded walk p56 already pays for -- no second
            // tree traversal on the frame path. saveAndClear() latches the
            // ORIGINAL Drawable, so restripLatched() handles OEM re-binds
            // every frame and restoreRows() restores it on detach.
            // HARD GATE: Settings L2/L3 + 账号中心 (EXT_ID) ONLY.
            // 0.5.0-p77: the user's "账号中心L2级菜单最上面有矩形白卡" is
            // the SAME HwToolbar #fff2f3f6 bar p64 removed on WLAN/蓝牙,
            // but 账号中心 lives in com.hihonor.id -> slot EXT_ID, an
            // external process that p64's L2/L3 gate deliberately excluded.
            // Extending the gate by exactly ONE slot reuses the already
            // verified structural + measured-property strip; no new tree
            // walk, no new predicate, no per-frame allocation.
            // L1 (accepted p30), the other five external apps and
            // EXT_CONTROLCENTER take the byte-for-byte identical old path.
            boolean accountChrome = accountIdGate();
            // 0.5.0-p105: other L2 pages live in external processes and never
            // reach the L2/L3-only branch above. Keep that branch untouched.
            boolean extTop = extTopBarGate();
            // 0.5.0-p79: 账号中心 is a SEPARATE app (com.hihonor.id). Its top
            // rectangle is an opaque account / header CARD, not the Settings
            // HwToolbar that p64 measured. p77 widened only the slot and kept
            // isChromeClass(), so nothing ever matched on that page.
            // Reuse p55's structural rule instead of a class whitelist: an
            // OEM view touches both screen edges (our glass is inset 14dp on
            // each side), AND the fill must MEASURE as an opaque solid.
            // 0.5.0-p80: p64 located the header by STRUCTURE alone
            // (chrome class / full screen width) and decided with a MEASURED
            // property. Keeping the measured test here would repeat it and,
            // worse, skip a StateListDrawable bar before the p64 §6 fallback
            // could ever run. Structure first, measurement inside the branch.
            boolean accountTopCard = accountChrome && isFullWidthOem(v);
            if (v != decor && v instanceof ViewGroup
                    && (slot == ConfigContract.Slot.L2
                        || slot == ConfigContract.Slot.L3
                        || accountChrome)
                    && (isChromeClass(cls) || accountTopCard)) {
                // 0.5.0-p77: on 账号中心 keep the fading white gradient
                // (AppbarBlurBg / collapsing-header mask) explicitly. The
                // plain isOpaqueSolidFill() already lets a TRANSLUCENT
                // gradient through; this makes the user's "不要删除渐隐
                // 白色效果" a hard, assertable rule rather than a side
                // effect of Drawable.getOpacity().
                if (!accountChrome || !isFadeWhiteLayer(v)) {
                    Drawable chromeBg = v.getBackground();
                    boolean opaque = isOpaqueSolidFill(chromeBg);
                    boolean measured = false;
                    // 0.5.0-p80: implement p64 report §6's OWN candidate —
                    // a StateList/Gradient Drawable reports op=-3 yet paints
                    // opaque near-white. Only 账号中心 uses the pixel test;
                    // Settings L2/L3 keep p64's byte-identical predicate.
                    if (!opaque && accountChrome) {
                        measured = measuredOpaqueWhite(chromeBg);
                        opaque = measured;
                    }
                    // p64 §6 asked the log to expose the Drawable type AND
                    // its opacity so one capture can settle the candidate.
                    if (accountChrome) {
                        long now = SystemClock.uptimeMillis();
                        if (now - lastAccountCandLogMs > 2000L) {
                            lastAccountCandLogMs = now;
                            GlassEntry.say("mg-state ACCOUNTCAND cls="
                                    + v.getClass().getSimpleName()
                                    + " " + v.getWidth() + "x" + v.getHeight()
                                    + " bg=" + drawableDesc(chromeBg)
                                    + " opSolid=" + isOpaqueSolidFill(chromeBg)
                                    + " measured=" + measured
                                    + " opaque=" + opaque
                                    + " slot=" + slot.id);
                        }
                    }
                    if (opaque) {
                        int before = savedRows.size();
                        saveAndClear(v);
                        if (savedRows.size() > before) {
                            diagChromeStrips++;
                            if (GlassEntry.verbose()) {
                                log((accountChrome ? "ACCOUNTCHROME " : "CHROME ")
                                        + v.getClass().getSimpleName()
                                        + " " + v.getWidth() + "x" + v.getHeight()
                                        + " bg=" + drawableDesc(chromeBg)
                                        + " strip=1 slot=" + slot.id);
                            }
                            // 0.5.0-p79: default-visible proof that the
                            // account card was actually stripped (p77/p78
                            // produced no observable evidence at all).
                            if (accountChrome) {
                                long now = SystemClock.uptimeMillis();
                                if (now - lastAccountStripLogMs > 2000L) {
                                    lastAccountStripLogMs = now;
                                    GlassEntry.say("mg-state ACCOUNTSTRIP cls="
                                            + v.getClass().getSimpleName()
                                            + " " + v.getWidth() + "x" + v.getHeight()
                                            + " bg=" + drawableDesc(chromeBg)
                                            + " measured=" + measured
                                            + " slot=" + slot.id);
                                }
                            }
                        }
                    }
                } else {
                    // 0.5.0-p78: if the 账号中心 white card is STILL visible
                    // with the new dex, the prime suspect is that its Drawable
                    // was classified as a fade layer and explicitly kept.
                    // Make that decision observable WITHOUT enabling
                    // perf.debugLog; throttled so it can never flood a frame.
                    long now = SystemClock.uptimeMillis();
                    if (now - lastAccountFadeLogMs > 2000L) {
                        lastAccountFadeLogMs = now;
                        GlassEntry.say("mg-state ACCOUNTFADE keep="
                                + v.getClass().getSimpleName()
                                + " bg=" + drawableDesc(v.getBackground())
                                + " slot=" + slot.id);
                    }
                }
            }
            // 0.5.0-p105: measured strip for the OTHER L2 title bars. Runs on
            // the SAME bounded first-layout walk, never on a frame path.
            // One resource lookup per node: the bounded walk already pays for
            // it, so both probes share the result.
            String rid = extTop ? resourceEntry(v) : "";
            if (extTop && v instanceof ViewGroup
                    && extTopBarCandidate(v, cls, rid)) {
                probeExtTopBar(v, cls, rid);
            }
            // 0.5.0-p107: ONE structural rule for all seven external L2s.
            // Accepts plain View too (gradient layers are not ViewGroups).
            if (extTop) {
                probeExtTopContainer(v, rid);
            }
            if (v instanceof ViewGroup && cls.endsWith("recyclerview")) {
                ViewGroup g = (ViewGroup) v;
                int n = g.getChildCount();
                if (n > 60) n = 60;
                for (int i = 0; i < n; i++) {
                    View row = g.getChildAt(i);
                    if (row == null || row.getVisibility() != View.VISIBLE) continue;
                    int before = savedRows.size();
                    saveAndClear(row);
                    if (savedRows.size() > before) diagRowStrips++;
                    // The item view may wrap the real card one level down
                    // (FrameLayout > ConstraintLayout ...). Strip a nested
                    // descendant ONLY when its background measures as opaque
                    // near-white, so coloured accents are never touched.
                    if (row instanceof ViewGroup) {
                        ViewGroup rg = (ViewGroup) row;
                        int rn = rg.getChildCount();
                        if (rn > 12) rn = 12;
                        for (int k = 0; k < rn; k++) {
                            View kid = rg.getChildAt(k);
                            if (kid == null || kid.getVisibility() != View.VISIBLE) continue;
                            try {
                                if (isOpaqueNearWhite(kid.getBackground())) {
                                    int b2 = savedRows.size();
                                    saveAndClear(kid);
                                    if (savedRows.size() > b2) diagRowStrips++;
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
                return;
            }
        } catch (Throwable ignored) {
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            int n = 0;
            try {
                n = g.getChildCount();
            } catch (Throwable ignored) {
            }
            for (int i = 0; i < n; i++) stripRowsIn(g.getChildAt(i), depth + 1, budget);
        }
    }

    private void stripAtLayoutTime() {
        if (!attached || decor == null || overlay == null) return;
        if (slot == null || slot == ConfigContract.Slot.L1) return;
        long now = SystemClock.uptimeMillis();
        if (now - attachTimeMs > OemCardSuppressor.EARLY_WINDOW_MS) return;
        // 0.5.0: the discovery/apply/strip itself is the accepted p48/p56
        // fix and stays. Only the LAYOUTSTRIP log + counters were removed.
        try {
            suppressor.discover(decor, overlay, true, false, true);
            suppressor.apply(true);
            latchListRowsNow();
            suppressor.takeRevived();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 0.4.0-p57: strip entry rows at the LAST moment before pixels.
     *
     * <p>OnDrawListener is dispatched by ViewRootImpl right before the tree is
     * drawn, so a row whose white background survived layout is fixed here with
     * nothing left in between. The measurement that drove this (DRAWWHITE /
     * countUnstripped) has been retired from the frame path; the strip itself
     * is what p56 was accepted for.
     */
    private void stripRowsBeforeDraw() {
        if (!attached || decor == null || overlay == null) return;
        if (slot == null || slot == ConfigContract.Slot.L1) return;
        long now = SystemClock.uptimeMillis();
        if (now - attachTimeMs <= OemCardSuppressor.EARLY_WINDOW_MS) {
            latchListRowsNow();
            return;
        }
        // 0.5.0-p107 MEASURED GAP: the accepted p48/p56 strip only runs inside
        // a 400 ms early window, but an external L2 can lay out much later
        // (device log: wallpapereditor onResume at +3572 ms, wallet onCreate
        // at +164 ms with its top container built afterwards). Without this
        // re-check the unified top-container rule never executes on those
        // pages. Throttled to 600 ms + a 200-node bounded walk, so it is not a
        // per-frame full-tree scan; the p48/p56 path itself is unchanged.
        // 0.5.0-p141: 荣耀互联 card_item 会被 ROM 延迟重新创建成【全新 View】
        // (日志: 首剥后 1~1.3s 又一批 CARDSTRIP = 新对象不在 savedRows)。
        // 旧 latch 管不到新 View，只能靠补扫；若跟顶栏共用 600ms 节流，新卡
        // 最慢要等 600ms，表现为"要点进去拉动刷新才消失"。为 ext.controlcenter
        // 单独用 150ms 节流补扫 card_item(幂等，Saved latch 保护已剥的不动)。
        if (slot == ConfigContract.Slot.EXT_CONTROLCENTER && decor != null
                && now - lastCardStripWalkMs >= 150L) {
            lastCardStripWalkMs = now;
            try {
                probeCardItemWalk(decor, 0, new int[]{400});
            } catch (Throwable ignored) {
            }
        }
        if (extTopBarGate() && now - lastTopContainerScanMs > 600L) {
            lastTopContainerScanMs = now;
            probeTopContainerTree();
            // 0.5.0-p117: content band of 账号中心 sub-pages, log only.
            if (slot == ConfigContract.Slot.EXT_ID && decor != null) {
                probeContentArea(decor, 0, new int[]{120});
                if (contentRectLines < 8) {
                    contentRectLines++;
                    probeContentRects();
                }
            }
        }
    }

    /** Bounded late walk: top-band containers only, no row stripping. */
    private void probeTopContainerTree() {
        if (decor == null) return;
        probeTopContainerIn(decor, 0, new int[]{200});
        // 0.5.0-p137 (纯诊断): 独立 walk 专找深层的 card_item(深度21)。
        probeCardItemWalk(decor, 0, new int[]{400});
    }

    private void probeTopContainerIn(View v, int depth, int[] budget) {
        if (v == null || depth > 12 || budget[0]-- <= 0) return;
        if (v == overlay || v == glassHost) return;
        try {
            probeExtTopContainer(v, resourceEntry(v));
            // 0.5.0-p128: the 600 ms bounded late walk also reaches the
            // account-security rows, so a late-inflated row still gets its
            // own-draw white suppressed (the 400 ms early window is the
            // primary path; this is the catch-up path). Idempotent via Saved.
            suppressAccountHalfWhite(v);
            // 0.5.0-p130: 隐藏顶部盾牌白底图 hv_ripple_view_bg。它是 HwImageView，
            // 白是其 src 图片内容(bg=null，bg 探针测不到；用户验证拉动盾牌时白底
            // 不动，证明是独立层)。EXT_ID 硬门、L1/其它页无此 id 天然 no-op。
            if (slot == ConfigContract.Slot.EXT_ID) hideAccountShieldBg(v);
            // 0.5.0-p135 (纯诊断): 晚扫兜底——早窗口 slot 未稳时也能采到 card_item。
            probeCardItemWhite(v);
        } catch (Throwable ignored) {
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            int n;
            try {
                n = Math.min(g.getChildCount(), 24);
            } catch (Throwable t) {
                return;
            }
            for (int i = 0; i < n; i++) {
                probeTopContainerIn(g.getChildAt(i), depth + 1, budget);
            }
        }
    }


    private void registerObserver(ViewTreeObserver tree) {
        if (tree == null || preDraw == null || !tree.isAlive()) return;
        for (ViewTreeObserver t : registeredTrees) {
            if (t == tree) return;
        }
        try {
            tree.addOnPreDrawListener(preDraw);
            registeredTrees.add(tree);
            observedTree = tree;
            if (registeredTrees.size() > 1) {
                log("view tree observer reattached (trees=" + registeredTrees.size() + ")");
            }
        } catch (Throwable ignored) {
        }
    }

    private void ensureObserver() {
        if (decor == null || preDraw == null) return;
        try {
            registerObserver(decor.getViewTreeObserver());
        } catch (Throwable ignored) {
        }
    }

    // ---- config / frame -----------------------------------------------------

    private void refresh(boolean force) {
        if (!attached) return;
        if (activity.isFinishing() || isDestroyed(activity)) {
            detach();
            return;
        }
        try {
            ConfigContract.Source src = ConfigBus.load(activity);
            PageLevel.Result level = PageLevel.detect(activity);
            ConfigContract.Slot newSlot = level.slotFor(activity.getPackageName());
            ConfigContract.Snapshot newCfg = ConfigContract.resolve(src, newSlot);
            String newVersion = ConfigContract.version(src);

            boolean slotChanged = slot != newSlot;
            boolean versionChanged = !newVersion.equals(version);
            boolean glassChanged = !newCfg.glassFingerprint().equals(glassFp);
            boolean bgChanged = !newCfg.bgFingerprint().equals(bgFp);

            slot = newSlot;
            cfg = newCfg;
            version = newVersion;
            glassFp = cfg.glassFingerprint();
            bgFp = cfg.bgFingerprint();
            // 0.5.0-p78: DEFAULT-VISIBLE state line. After an install the
            // most common failure is lspd still executing the CACHED dex,
            // while every p77 criterion was hidden behind perf.debugLog's
            // default-off gate. One line on force / slot change now proves:
            // which BUILD is running, which slot this page resolved to,
            // whether the average window colour key was ever derived, and
            // whether the 账号中心 gate is reachable. Never in a frame path.
            if (slotChanged || force) {
                GlassEntry.say("mg-state build=" + GlassEntry.BUILD
                        + " slot=" + slot.id
                        + " win=" + Integer.toHexString(
                                ConfigContract.windowColorOf(cfg))
                        + " winKey=" + cfg.asString(
                                ConfigContract.Param.BG_WINDOW_COLOR)
                        + " kind=" + cfg.asString(ConfigContract.Param.BG_KIND)
                        + " accountGate=" + accountIdGate()
                        + " generic=" + genericEnabled);
            }
            // 0.5.0-p77 objective check for "账号中心背景对比其他L2有变化":
            // after the read-path unification, the ext.id line and the l2
            // line MUST show identical win/kind/src/tint/dim/opacity. Only
            // emitted on a slot/force change and only when diagnostics are
            // on, so it never adds string work to the frame path.
            if (GlassEntry.verbose() && (slotChanged || force)) {
                log("BGUNIFY slot=" + slot.id
                        + " win=" + Integer.toHexString(
                                ConfigContract.windowColorOf(cfg))
                        + " kind=" + cfg.asString(ConfigContract.Param.BG_KIND)
                        + " src=" + cfg.asString(ConfigContract.Param.BG_SOURCE)
                        + " tint=" + cfg.asString(ConfigContract.Param.BG_TINT_COLOR)
                        + " dim=" + cfg.asString(ConfigContract.Param.BG_DIM)
                        + " opacity=" + cfg.asString(ConfigContract.Param.BG_OPACITY));
            }
            // 0.5.0-p66: make the master switch an EXPLICIT live transition.
            // glass.enabled is part of glassFingerprint(), so the pool would
            // rebuild anyway, but the OFF branch returns early below and the
            // suppressor/scanner caches must be invalidated in both directions
            // for the very next frame to be correct. No page restart needed.
            boolean glassOnNow = cfg.asBool(ConfigContract.Param.GLASS_ENABLED);
            if (glassOnNow != glassOn) {
                glassOn = glassOnNow;
                scanner.invalidateStatic();
                suppressor.invalidate();
                requestRender();
                log("GLASS toggle=" + (glassOnNow ? "on" : "off")
                        + " slot=" + slot + " live=1");
            }
            // 0.5.0: one switch controls every diagnostic line in the module.
            GlassEntry.setVerbose(cfg.asBool(ConfigContract.Param.PERF_DEBUG_LOG));

            if (force || slotChanged || versionChanged || bgChanged) {
                if (backdrop != null) backdrop.apply(slot, cfg);
            }
            if (slotChanged || versionChanged) {
                scanner.invalidateStatic();
                suppressor.invalidate();
            }

            if (!cfg.asBool(ConfigContract.Param.GLASS_ENABLED)) {
                clearGlass();
                restoreRows("glass_disabled");
                return;
            }
            if (MaterialBridge.mode() == MaterialBridge.Mode.NONE) {
                log("material SDK absent -> background layer only");
                clearGlass();
                restoreRows("mode_none");
                return;
            }
            // ONLY a real material-parameter change rebuilds the pool.
            // `force` used to be part of this condition: every onResume tore
            // down all containers and the 3-frame hysteresis then showed the
            // stock white cards. glassFingerprint() no longer includes the
            // slot id, so L1 -> L2 is not a material change any more.
            if (glassChanged) {
                clearGlass();
                // A real material change is the ONLY event allowed to retry
                // the host SDK. Transient failures never toggle back and forth.
                if (fallbackMode) log("material changed -> retry host glass once");
                fallbackMode = false;
                glassEffect = MaterialBridge.createGlass(cfg);
                lastGeoSig = Long.MIN_VALUE;
                lastRowSig = Long.MIN_VALUE;
            }
            if (versionChanged || slotChanged) {
                log("page " + level.describe() + " | " + cfg.describe()
                        + " | mode=" + MaterialBridge.mode()
                        + " | generic=" + genericEnabled);
            }
            pendingFullScan = true;
            scanAndBind();
            // 0.5.0 LIVE CONFIG: a static page has no pending traversal, so a
            // config broadcast would otherwise leave the OLD pixels on screen
            // until the next scroll or onResume. One explicit invalidate
            // produces exactly one draw traversal (and one onPreDraw scan).
            // 0.5.0-p76 POWER: a repeated onLifecycle() refresh with NO
            // change (four lifecycle hooks can fire for one Activity start)
            // used to invalidate four layers and force an extra full draw
            // traversal. A config broadcast still enters this branch because
            // versionChanged is true, so live settings remain instant.
            if (force || slotChanged || versionChanged || glassChanged
                    || bgChanged) {
                requestRender();
            }
            // 0.3.5: no kick chain. With the backing derived from the glass
            // rects, a single scan is enough -- a static page needs no
            // delayed rediscovery and therefore causes no post-gesture jank.
        } catch (Throwable t) {
            logOnce("refresh failed: " + t);
        }
    }

    /** Repaint every layer we own; a no-op when the page is not attached. */
    private void requestRender() {
        try {
            if (glassLayer != null) glassLayer.invalidate();
        } catch (Throwable ignored) {
        }
        try {
            if (glassHost != null) glassHost.invalidate();
        } catch (Throwable ignored) {
        }
        try {
            if (overlay != null) overlay.invalidate();
        } catch (Throwable ignored) {
        }
        try {
            if (decor != null) decor.invalidate();
        } catch (Throwable ignored) {
        }
    }

    private boolean onPreDraw() {
        if (!attached) return true;
        if (activity.isFinishing() || isDestroyed(activity)) {
            detach();
            return true;
        }
        ensureObserver();
        long now = SystemClock.uptimeMillis();
        // 0.4.0: force discovery on the first real (post-layout) frame.
        if (firstPreDraw) {
            firstPreDraw = false;
            scanner.invalidateStatic();
            suppressor.invalidate();
        }
        // 0.5.0: the whole 2 s diagnostic window is gated. When the switch
        // is off there is no string building, no logcat write, and no LSPosed
        // DB write on any frame. Turning it on in Settings restores the exact
        // same per-frame counters used by the p30/p35/p57 regressions.
        if (GlassEntry.verbose()) {
            if (diagWindowMs == 0L) diagWindowMs = now;
            diagFrames++;
            if (diagPrevFrameMs != 0L) {
                int gap = (int) (now - diagPrevFrameMs);
                if (gap > diagMaxGapMs) diagMaxGapMs = gap;
            }
            diagPrevFrameMs = now;
            if (now - diagWindowMs > 2000L) {
                log("frames: total=" + diagFrames + " scanned=" + diagScans
                        + " motion=" + diagMotion + " aligned=" + diagAligns
                        + " tail=" + diagTail + " stall=" + diagStall
                        + " reappears=" + diagReappears
                        + " lStrip=" + diagLayoutStrips + " rStrip=" + diagRowStrips
                        + " cStrip=" + diagChromeStrips
                        + " eStrip=" + diagExtStrips
                        + " maxGapMs=" + diagMaxGapMs + " fallback=" + fallbackMode
                        + " build=" + GlassEntry.BUILD);
                diagWindowMs = now;
                diagFrames = 0;
                diagScans = 0;
                diagMotion = 0;
                diagAligns = 0;
                diagTail = 0;
                diagStall = 0;
                diagReappears = 0;
                diagLayoutStrips = 0;
                diagRowStrips = 0;
                diagChromeStrips = 0;
                diagExtStrips = 0;
                diagMaxGapMs = 0;
            }
        }
        // CURRENT-frame motion detection: an INPUT, never the previous scan's
        // OUTPUT. It runs BEFORE any decision, so a fling's first frame is
        // never lost. 0.3.4 token also covers float translationY + scrollY.
        long token = scanner.quickToken();
        boolean motion = (lastQuickToken == Long.MIN_VALUE) || (token != lastQuickToken);
        lastQuickToken = token;
        frameMotion = motion;
        if (motion) {
            diagMotion++;
            long tail = cfg == null ? TRACK_TAIL_FALLBACK_MS
                    : cfg.asInt(ConfigContract.Param.PERF_TRACK_TAIL_MS);
            if (tail < 0L) tail = 0L;
            if (tail > TRACK_TAIL_MAX_MS) tail = TRACK_TAIL_MAX_MS;
            trackUntilMs = now + tail;
        }
        boolean inTrack = now < trackUntilMs;
        if (inTrack && !motion) diagTail++;
        // Inside the tail every frame is scanned unconditionally. A repeated
        // token (sub-pixel fling / translationY animation) can therefore never
        // freeze the glass mid-gesture. Outside the tail, a genuinely idle
        // page still skips ~80% of full scans.
        if (!motion && !inTrack && !pendingFullScan
                && now - lastFullScanMs < SCAN_INTERVAL_IDLE_MS) {
            return true;   // genuinely idle frame: no scan at all
        }
        pendingFullScan = false;
        lastFullScanMs = now;
        diagScans++;
        try {
            scanAndBind();
        } catch (Throwable t) {
            logOnce("pre-draw failed: " + t);
        }
        return true;
    }

    private void scanAndBind() {
        if (cfg == null || glassHost == null || glassLayer == null
                || decor == null) return;
        // 0.3.8: restore LAST frame's OEM content alphas before applying this
        // frame's edge-fade. No stale half-transparent recycled row can survive.
        edgeFade.begin();
        float density = decor.getResources().getDisplayMetrics().density;
        // Explicit "force glass backing" switch: same path as an automatic
        // construction failure, so the degradation can be tested on demand.
        if (cfg.asBool(ConfigContract.Param.GLASS_FORCE_BACKING) && !fallbackMode) {
            switchToFallback();
        }
        boolean allowGeneric = genericEnabled
                && cfg.asBool(ConfigContract.Param.GLASS_GENERIC_CARDS);
        // 0.3.7: bounded static-card discovery is DISCRETE. It may only run
        // on a settled frame -- never inside the tracking tail (scrolling),
        // which is exactly what made 0.3.4-p8 janky. Forced attach/page-change
        // discovery is the one exception and is still a single 600-node walk.
        long frameNow = SystemClock.uptimeMillis();
        boolean idleFrame = frameNow >= trackUntilMs && !frameMotion;
        scanner.setDiscoveryAllowed(idleFrame);
        // 0.4.0: self-draw suppression is INDEPENDENT of the strip set.
        // Forced during the first 400 ms (post-layout first frame included),
        // then discrete on idle frames. L1 is exempt so its OEM dividers /
        // glass highlights stay intact.
        boolean early = frameNow - attachTimeMs < OemCardSuppressor.EARLY_WINDOW_MS;
        // 0.5.0-p162: 应用页/电池详情页的 OEM 卡片在 400ms 后才 inflate
        // (真机 strip=0→1.4s 后才 16)，错过 early burst 只能等 600ms idle
        // discovery ⇒ 白卡延迟消失。对这两页用 150ms 节流的补扫 forced
        // discovery 到 1500ms，覆盖延迟 inflate。精确类名门控，L1/其它页
        // 逐字不变(early 仍 400ms)。
        long sinceAttach = frameNow - attachTimeMs;
        String actSimple = activity == null ? "" : activity.getClass().getSimpleName();
        boolean lateWhitePage = slot != ConfigContract.Slot.L1
                && ("AppAndNotificationDashboardActivity".equals(actSimple)
                    || "DetailOfSoftConsumptionActivity".equals(actSimple)
                    // p163: 电池主页(HwPowerManagerActivity, page level=L1 但
                    // slot=ext.systemmanager)才是用户说的"点进去白卡延迟消失"
                    // 那一页——昨天 p162 只写了详情页、漏了主页。真机主页
                    // 进入 strip=0→2 秒后 strip=8，远超 400ms 早窗口。
                    || "HwPowerManagerActivity".equals(actSimple)
                    // p162: 隐私/钱包页的 ScrollView 行延迟 inflate，同样
                    // 错过 400ms 早窗口 ⇒ 玻璃时有时无。统一补扫。
                    || "PrivacySecDashboardActivity".equals(actSimple)
                    || "WalletActivity".equals(actSimple));
        boolean lateBurst = false;
        // p163: 钱包(外进程冷启动)玻璃出现更慢，单独延长到 3000ms；其余
        // 三页保持 1500ms。都仍是有界，且只在 lateWhitePage 门控内。
        boolean walletPage = "WalletActivity".equals(actSimple);
        long lateWindow = walletPage ? 3000L : 1500L;
        if (lateWhitePage && sinceAttach < lateWindow
                && (lastLateWhiteWalkMs == 0L
                    || frameNow - lastLateWhiteWalkMs >= 150L)) {
            lastLateWhiteWalkMs = frameNow;
            lateBurst = true;
            // 同时让 static 卡片发现忽略 600/2000ms 节流，覆盖延迟
            // inflate 的行(隐私 item_card / 钱包 cl_card_entry_*)。
            scanner.invalidateStatic();
            // p163 有界诊断：每页最多 20 行，打补扫时的帧号与当时块数，
            // 一次换码即可看出玻璃是第几帧出现、补扫是否命中。
            if (lateDiagLines < 20) {
                lateDiagLines++;
                GlassEntry.say("mg-state LATEBLK slot=" + slot.id
                        + " act=" + actSimple + " +" + sinceAttach + "ms"
                        + " n=" + lateDiagLines + " blocks=" + scanner.blocks().size());
            }
        }
        if (lateBurst) early = true;
        // 0.4.2: PageLevel can still report L1 on the very first frame of a
        // fresh Activity (the task stack has not settled yet), which made a
        // fresh L2 skip suppression for its first frames -- the "闪一下".
        // The Settings HOME page is one concrete class; everything else
        // (L2 / L3 / external apps) must suppress.
        boolean suppressOn = (slot == null || slot != ConfigContract.Slot.L1);
        // 0.4.0-p48: the entry fix was always "strip a container the moment
        // the walk sees it" (p45 / p47). Its hole was CADENCE, not coverage:
        // forced passes are 120 ms apart, so a card inflated 50 ms after the
        // previous pass stayed stock-white for ~7 frames -- the Bluetooth
        // "整个卡片一起闪". Walk every frame during the 400 ms entry window
        // instead. L1 stays exempt so its OEM dividers are untouched, and the
        // walk is cheap on repeat frames because every visited ViewGroup is
        // memoised in candidates/rejected.
        boolean burst = early && suppressOn;
        suppressor.discover(decor, overlay, early || pendingFullScan, idleFrame,
                burst);
        if (GlassEntry.verbose() && suppressOn != diagLastSuppress) {
            diagLastSuppress = suppressOn;
            log("DIAG suppress enabled=" + suppressOn + " slot=" + slot
                    + " state=" + suppressor.describe()
                    + " act=" + activity.getClass().getSimpleName());
        }
        suppressor.apply(suppressOn);
        // The actual re-strip happened inside discover()/apply(). Clearing the
        // counter is all that remains; the REVIVED log line was diagnostic.
        suppressor.takeRevived();
        // 0.5.0-p152: 每帧检查过渡态白卡——它在进页面 1~2.4s 才出现，
        // 早窗口(400ms)抓不到；150ms 节流有界扫，命中几何+近白就剥+日志。
        checkTransientWhite();
        List<RectF> rects = scanner.rects(decor, overlay, cfg, density, allowGeneric);
        List<CardScanner.Block> blocks = scanner.blocks();
        List<View> visibleRows = scanner.visibleRows();
        List<Float> fades = scanner.fades();
        List<List<View>> contents = scanner.contents();
        // 0.5.0: probeJump()/probeContainers() are pure p17/p27 diagnostics
        // (20-slot arrays touched on EVERY frame). Retired from the frame
        // path; the accepted p30/p28 geometry fixes do not depend on them.
        // 0.3.6: ONE geometry template, no second layer and no detector.

        // (1) LATCHED wide stripping (0.3.8). ancestorSeeds are ONLY the rect
        //     rows, so the 32-level ancestor walk stays O(rect rows) even
        //     though the strip set is now much wider.
        List<View> seeds = new ArrayList<View>();
        for (CardScanner.Block b : blocks) seeds.addAll(b.rows);
        syncRows(visibleRows, seeds);

        long sig = CardScanner.signature(rects);
        moving = (sig != lastGeoSig);
        // 0.3.4 on-device proof: geometry changed while the cheap token did
        // NOT. With the 0.3.3 gate this frame would have been skipped and the
        // glass would have stayed at the previous position -- the visible
        // "fast scroll does not follow the finger" defect.
        if (moving && !frameMotion) diagStall++;
        if (moving && GlassEntry.verbose()) {
            long now = SystemClock.uptimeMillis();
            if (now - lastScanLogMs > 2000L) {
                lastScanLogMs = now;
                log("scan: rects=" + rects.size() + " blocks=" + blocks.size()
                        + " containers=" + containers.size()
                        + " rows=" + visibleRows.size()
                        + " static=" + scanner.staticDescribe()
                        + " generic=" + allowGeneric
                        + " blocks=" + describeBlocks(blocks));
                logTemplate(rects);
            }
        }

        if (MaterialBridge.mode() == MaterialBridge.Mode.BOOT_BACKDROP) {
            RenderEffect blur = buildBlur(cfg);
            if (blur != null) {
                for (CardScanner.Block b : blocks) {
                    for (View row : b.rows) MaterialBridge.applyBackdrop(row, blur);
                }
            }
            hideAllContainers();
            lastGeoSig = sig;
            return;
        }

        // (2) Empty frame: hide glass (no stale rectangles), keep the stripped
        //     backgrounds so the page does not turn white for a frame.
        if (rects.isEmpty()) {
            hideAllContainers();
            lastGeoSig = sig;
            return;
        }

        // (3) Pool aligns IMMEDIATELY. Views are reused, never removed.
        // 0.3.2 FORCED GLASS BACKING: a host-SDK construction failure must
        // NEVER put the OEM white drawables back. That path caused a visible
        // glass <-> white flip (错乱) and let one transient failure permanently
        // downgrade the whole Activity. Instead swap the entire pool to an
        // always-available self-drawn translucent backing, which also removes
        // the "text sitting directly on the wallpaper" breakage.
        if (!ensurePool(rects.size(), density)) {
            if (!fallbackMode) {
                switchToFallback();
                if (!ensurePool(rects.size(), density)) {
                    hideAllContainers();
                    return;
                }
            } else {
                // A plain View cannot fail to construct; defensive only.
                hideAllContainers();
                return;
            }
        }

        // (4) Bind rects to container SLOTS BY IDENTITY, not by array index.
        int radiusPx = Math.round(
                cfg.asFloat(ConfigContract.Param.GEO_RADIUS_DP) * density);
        int nc = containers.size();
        int nr = Math.min(rects.size(), nc);
        int[] rectSlot = new int[nr];
        boolean[] taken = new boolean[nc];
        for (int i = 0; i < nr; i++) rectSlot[i] = -1;

        // Pass 1: keep a container on the block it drew last frame.
        // Compare against the slot's LAST top; a scroll moves all tops by the
        // same small amount, so the nearest match is the same block.
        // p167: 若距离全超 SLOT_MATCH_PX(折叠头收缩致整列 top 集体跳变),
        // 退而用"上一帧 top 排序序号"匹配——块数以 nr 与上一帧存活槽位数
        // 相等为前提,序号身份不受整体平移影响,避免容器 hide/show 重录。
        if (slotPrimed) {
            // p169: 用上一帧实际块数作 rank 门控,不用本帧未 hide 的槽数——
            // 后者在 hide 发生后变小会让 rank 自我恶化失效。块数真变化时
            // (lastRectCount!=nr)才退回距离匹配。
            boolean useRank = (nr == lastRectCount);
            for (int i = 0; i < nr; i++) {
                int bestS = -1;
                // p167: 块数未变时【优先按序号身份】匹配。折叠头收缩/快速
                // 滚动会让整列 top 集体变化,纯距离匹配会"成功但错配"
                // (如块 top=847 错配到旧 top=655 的槽,间距192<260),槽位
                // 随滚动抖动⇒容器 hide/show 重录(reappears)。序号身份在
                // 滚动中天然稳定,优先用它;块数变化(useRank=false)才走距离。
                if (useRank) {
                    for (int s = 0; s < nc; s++) {
                        if (taken[s] || !slotUsed[s]) continue;
                        if (slotRank[s] == i) { bestS = s; break; }
                    }
                }
                if (bestS < 0) {
                    float best = SLOT_MATCH_PX;
                    float top = rects.get(i).top;
                    for (int s = 0; s < nc; s++) {
                        if (taken[s] || !slotUsed[s]) continue;
                        float d = Math.abs(slotTop[s] - top);
                        if (d < best) {
                            best = d;
                            bestS = s;
                        }
                    }
                }
                if (bestS >= 0) {
                    rectSlot[i] = bestS;
                    taken[bestS] = true;
                }
            }
        }
        // Pass 2: leftover rects (genuinely new blocks) take any free slot.
        for (int i = 0; i < nr; i++) {
            if (rectSlot[i] >= 0) continue;
            for (int s = 0; s < nc; s++) {
                if (!taken[s]) {
                    rectSlot[i] = s;
                    taken[s] = true;
                    break;
                }
            }
        }

        float[] curFade = new float[nc];
        for (int s = 0; s < nc; s++) {
            View c = containers.get(s);
            int ri = -1;
            for (int i = 0; i < nr; i++) {
                if (rectSlot[i] == s) {
                    ri = i;
                    break;
                }
            }
            if (ri < 0) {
                // No block for this slot this frame.
                edgeFade.applyGlass(c, 1f);
                if (c.getVisibility() != View.INVISIBLE) {
                    c.setVisibility(View.INVISIBLE);
                }
                slotUsed[s] = false;
                curFade[s] = 1f;
                continue;
            }
            boolean reappeared = c.getVisibility() != View.VISIBLE;
            if (reappeared) c.setVisibility(View.VISIBLE);
            MaterialBridge.configure(c, rects.get(ri), radiusPx, cfg);
            if (c instanceof LiquidGlassView) {
                ((LiquidGlassView) c).setBackdrop(backdrop);
                // p26: a hidden view kept its last display list; force a
                // re-record on the frame it comes back.
                if (reappeared) {
                    c.invalidate();
                    diagReappears++;
                    // p167 诊断：reappear 时打槽位/块几何/上一帧该槽几何,
                    // 一次换码定因是槽位重排还是块高度剧变导致容器 hide/show。
                    if (reappearDiagLines < 12) {
                        reappearDiagLines++;
                        RectF rr = rects.get(ri);
                        GlassEntry.say("mg-state REAPPEAR slot=" + s
                                + " rect=" + ri + " top=" + (int) rr.top
                                + " h=" + (int) rr.height()
                                + " lastTop=" + (int) slotTop[s]
                                + " lastRank=" + slotRank[s]
                                + " nr=" + nr + " n=" + reappearDiagLines);
                    }
                }
            }
            float fade = ri < fades.size() ? fades.get(ri) : 1f;
            curFade[s] = fade;
            edgeFade.applyGlass(c, fade);
            if (fade < 0.999f && ri < contents.size()
                    && contents.get(ri) != null) {
                List<View> blockRows = contents.get(ri);
                int n = blockRows.size() < 2 ? blockRows.size() : 2;
                for (int k = 0; k < n; k++) {
                    edgeFade.fadeContent(blockRows.get(k), fade);
                }
            }
            slotUsed[s] = true;
            slotTop[s] = rects.get(ri).top;
            slotRank[s] = ri;   // p167: 本帧块序号,供下帧 rank 回退匹配
        }
        slotPrimed = true;
        lastRectCount = nr;   // p169: 供下帧 rank 回退门控
        if (moving) diagAligns++;
        lastGeoSig = sig;
    }

    /**
     * 0.4.0-p27 DIAGNOSTIC ONLY -- no rendering behaviour is changed here.
     *
     * Screen-recording forensics proved unusable: H.264 ringing plus the
     * selection bias of "cells that changed" produced fake 80-level dark
     * flashes that are absent from the actual decoded frames (whole-frame
     * means stayed within 219..224). So instead of guessing from pixels, log
     * the renderer's OWN state machine, frame by frame, from the data that
     * actually drives the draw:
     *   visibility, fade (what we asked for), alpha (what the View has),
     *   top, height, and whether the container just came back from INVISIBLE.
     *
     * A visible "flash" MUST correspond to one of these crossing a threshold
     * between two consecutive frames. If none does, the flash is not in our
     * overlay at all and the next place to look is the OEM layer.
     *
     * Also counts 1-2 px jitter objectively: a top that goes A -> B -> A with
     * |B-A| <= 2 within 3 consecutive frames is a pixel-level oscillation,
     * which is the only reliable way to answer "1-2px 抖动还在不在" without
     * asking the user to eyeball it.
     *
     * @param slotFade fade actually applied to each SLOT (p28 stable binding
     *                 means fades[rectIndex] is no longer fades[slotIndex]).
     */
    /**
     * 0.4.0-p28 STABLE BLOCK IDENTITY (the actual L1 flash root cause).
     *
     * The pool used to bind container[i] to rects[i] by ARRAY INDEX. But
     * rects() is an ordered list that GROWS AND SHRINKS while scrolling: the
     * moment a group leaves the top (or a new group peeks in at the bottom),
     * every later index shifts by one, so a container that was drawing block
     * A is suddenly told to draw block B -- a 600..1000 px jump, a completely
     * different fade (e.g. 15% -> 100%), all in ONE frame. That is the flash.
     * p27's BLINK log proved it on-device:
     *   #2 vis 1>1 top 1088>2059  (visible the whole time, moved 971 px)
     *   #0 fade 15>100 h 10>882
     *
     * Fix: remember which top each slot drew last frame, and match this
     * frame's rects to the closest free slot. A whole-list scroll moves every
     * top by the same small delta, so each container keeps following its OWN
     * block; only a genuinely new/removed block takes or frees a slot.
     */
    private static final float SLOT_MATCH_PX = 260f;
    private final float[] slotTop = new float[MAX_POOL_SIZE];
    private final boolean[] slotUsed = new boolean[MAX_POOL_SIZE];
    // p167: 上一帧每个槽位对应的"top 排序序号"。折叠头收缩会让整列块 top
    // 集体跳变(关于手机页 367→847 变化 480>260px),超 SLOT_MATCH_PX 致槽位
    // 错配→容器 hide/show→reappears。块数不变时按序号身份复用槽位,不受整体
    // 平移影响。detach 时随 slotUsed 一起失效。
    private final int[] slotRank = new int[MAX_POOL_SIZE];
    // p169: 上一帧"实际块数",用于 rank 回退门控。p167 误用 liveSlots(本帧
    // 尚未 hide 的槽数),一旦某帧有槽被 hide 致 liveSlots<nr,下帧 rank 就
    // 失效退回距离匹配⇒整列换位时全超阈值⇒pass2 重排⇒容器 hide/show 循环。
    // 真机证据 REAPPEAR slot=0 rect=0 top294 lastTop2743 lastRank3 nr4。
    private int lastRectCount = -1;
    /** p167: 每页 REAPPEAR 诊断预算(<=12 行),detach 重置。 */
    private int reappearDiagLines;
    private boolean slotPrimed;

    // 0.5.0: the p17/p27 frame probes were retired. The accepted
    // geometry / identity fixes live in CardScanner and the pool
    // binder above; these methods only built diagnostic strings.

    /** 0.4.0-p61: block rectangles, so "which card is covered" is measurable. */
    private static String describeBlocks(List<CardScanner.Block> blocks) {
        if (blocks == null || blocks.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        int n = blocks.size() > 12 ? 12 : blocks.size();
        for (int i = 0; i < n; i++) {
            CardScanner.Block b = blocks.get(i);
            if (i > 0) sb.append(',');
            sb.append((int) b.rect.top).append('+').append((int) b.rect.height())
                    .append('x').append((int) b.rect.width())
                    .append("/r").append(b.rows.size());
        }
        if (blocks.size() > n) sb.append(",...");
        return sb.append(']').toString();
    }

    // 0.5.0: the p17/p27 frame probes were retired. The accepted
    // geometry / identity fixes live in CardScanner and the pool
    // binder above; these methods only built diagnostic strings.

    /**
     * Latch into forced-backing mode for this Activity. Hysteresis is the
     * whole point: no oscillation between host glass and fallback on a flaky
     * ClassLoader, and no white-card flash at any point in the transition.
     */
    private void switchToFallback() {
        fallbackMode = true;
        clearGlass();
        log("host glass unavailable -> forced translucent backing (latched)");
    }

    /** Grow (never shrink) the reusable pool. Returns false on construction failure. */
    private boolean ensurePool(int target, float density) {
        if (target > MAX_POOL_SIZE) target = MAX_POOL_SIZE;
        if (containers.size() >= target) return true;
        if (!fallbackMode && glassEffect == null) {
            glassEffect = MaterialBridge.createGlass(cfg);
        }
        for (int i = containers.size(); i < target; i++) {
            View layout;
            try {
                layout = fallbackMode
                        ? FallbackGlass.createView(activity, cfg, density)
                        : MaterialBridge.createLayout(activity, cfg, glassEffect);
            } catch (Throwable t) {
                layout = null;
            }
            if (layout == null) {
                logOnce("container construction failed (mode="
                        + MaterialBridge.mode() + " fallback=" + fallbackMode + ")");
                return false;
            }
            glassLayer.addView(layout, new FrameLayout.LayoutParams(1, 1));
            containers.add(layout);
        }
        return true;
    }

    private void hideAllContainers() {
        for (View c : containers) {
            edgeFade.applyGlass(c, 1f);
            if (c.getVisibility() != View.INVISIBLE) c.setVisibility(View.INVISIBLE);
        }
    }

    /**
     * 0.3.6 template diagnostic. There is NO second layer any more: every
     * glass card is produced by the single rects() template. This log simply
     * exposes each card's left/right so the "统一卡片宽度" rule can be
     * verified on device (all full-width cards must report the same L/R).
     * Throttled to once per 2 s and only called from the moving-frame scan
     * log -- zero cost on idle frames.
     */
    private void logTemplate(List<RectF> rects) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < rects.size() && i < 10; i++) {
            RectF r = rects.get(i);
            if (i > 0) b.append(' ');
            b.append((int) r.left).append("..").append((int) r.right);
        }
        log("template: uniform="
                + cfg.asBool(ConfigContract.Param.GEO_UNIFORM_CARD_WIDTH)
                + " slot=" + (slot == null ? "null" : slot.id)
                + " edges=[" + b + "]");
    }

    private static RenderEffect buildBlur(ConfigContract.Snapshot cfg) {
        try {
            float r = cfg.asFloat(ConfigContract.Param.BG_BLUR_RADIUS_DP);
            if (!cfg.asBool(ConfigContract.Param.BG_BLUR_ENABLED) || r <= 0f) return null;
            return RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP);
        } catch (Throwable t) {
            return null;
        }
    }

    // ---- row background bookkeeping ----------------------------------------

    /**
     * 0.3.8 LATCHED STRIPPING. The user: "最终成果是不需要白卡的存在".
     *
     * The 0.3.7 defect was the RESTORE loop at the bottom of the old method:
     * whenever the row set changed (i.e. on EVERY scroll frame) it put the OEM
     * white drawable back on any View that had dropped out, then stripped it
     * again next frame. That produced exactly "有的会闪烁然后消失，滑动的时候
     * 又出现". Backgrounds are now stripped once and NEVER restored during the
     * Activity lifetime; only detach() / glass-off call restoreRows().
     *
     * `stripAll` comes from CardScanner and already contains every visible
     * list child plus its one-level background-bearing children, so a freshly
     * rebound or newly scrolled-in white card is handled in the SAME frame.
     * `ancestorSeeds` are only the rect rows: the 32-level ancestor walk is
     * O(rect rows), not O(strip set), and ancestorViews ACCUMULATES so a page
     * swap can never let an opaque parent back in.
     */
    private void syncRows(List<View> stripAll, List<View> ancestorSeeds) {
        // 0.4.0-p40: catch OEM re-binds on back navigation FIRST, before the
        // early return and before any draw. A revived white card therefore
        // never reaches a frame even when this scan produced no rows.
        restripLatched();
        if (stripAll.isEmpty() && !savedRows.isEmpty()) return;

        long sig = rowSignature(stripAll);
        if (sig != lastRowSig) {
            lastRowSig = sig;
            // 0.3.9: ancestorViews ACCUMULATES (latch), so the 32-level walk
            // only has to find NEW parents on a page swap -- not re-walk the
            // same chain on every scroll frame. 120 ms is well below the
            // perception threshold and cuts the L2 frame cost noticeably.
            long now = SystemClock.uptimeMillis();
            if (ancestorViews.isEmpty() || now - lastAncestorMs > 120L) {
                lastAncestorMs = now;
                for (View row : ancestorSeeds) {
                    if (row == null) continue;
                    ViewParent p = row.getParent();
                    int depth = 0;
                    while (p instanceof View && depth++ < MAX_ANCESTOR_DEPTH) {
                        View anc = (View) p;
                        if (anc == overlay || anc == glassHost || anc == decor) break;
                        if (!keepBackground(anc)) ancestorViews.add(anc);
                        p = anc.getParent();
                    }
                }
            }
        }
        for (View v : stripAll) {
            if (v != null) saveAndClear(v);
        }
        // 0.5.0-p66: external bespoke cards are discovered as OUTERMOST
        // containers, so stripAll never contains their white child cards.
        // Check one level of children on every frame (bounded, O(rows*12)).
        stripExtCardChildren(stripAll);
        for (View a : ancestorViews) {
            saveAndClear(a);
        }
        // NO restore here. Latch until detach().
        if (savedRows.size() > MAX_SAVED_ROWS) pruneSaved();
        long now = SystemClock.uptimeMillis();
        if (GlassEntry.verbose() && now - lastRowsLogMs > 2000L) {
            lastRowsLogMs = now;
            log("backgrounds cleared: strip=" + stripAll.size()
                    + " tracked=" + savedRows.size()
                    + " ancestors=" + ancestorViews.size()
                    + " suppress=" + suppressor.describe()
                    + " latched=true");
        }
    }

    /** Bound memory without ever restoring a white drawable. */
    private void pruneSaved() {
        Iterator<Map.Entry<View, Saved>> it = savedRows.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<View, Saved> e = it.next();
            View v = e.getKey();
            boolean detached;
            try {
                detached = !v.isAttachedToWindow();
            } catch (Throwable t) {
                detached = true;
            }
            if (detached) it.remove();
        }
    }

    private static long rowSignature(List<View> rows) {
        long h = 1469598103934665603L;
        if (rows == null) return h;
        for (View v : rows) {
            h = (h ^ System.identityHashCode(v)) * 1099511628211L;
        }
        return h ^ rows.size();
    }

    /**
     * Idempotent. RecyclerView rebinds rows and may call setBackground() again
     * after every scroll, so re-clear on each scan while keeping the ORIGINAL
     * Drawable for a faithful restore on detach.
     */
    private void saveAndClear(View v) {
        Drawable bg = null, fg = null;
        try {
            bg = v.getBackground();
        } catch (Throwable ignored) {
        }
        try {
            fg = v.getForeground();
        } catch (Throwable ignored) {
        }
        // 0.3.9 fast path: a latched (already stripped) View costs exactly
        // two getters and NO HashMap lookup on every scroll frame.
        if (bg == null && fg == null) return;
        Saved s = savedRows.get(v);
        // 0.5.0-p118: never let the generic strip path clear our transparent
        // placeholder. A non-null ColorDrawable(0) is IDENTICAL in shape to an
        // OEM fill here, so without this guard syncRows() would null it out on
        // the next frame and the glass candidate would flicker. Revived fills
        // are re-latched to the SAME placeholder instance.
        if (s != null && s.placeholder != null) {
            if (!unifiedIsOurPlaceholder(v, s)) unifiedReapplyPlaceholder(v, s);
            return;
        }
        if (s == null) {
            s = new Saved();
            s.background = bg;
            s.foreground = fg;
            savedRows.put(v, s);
        }
        try {
            if (bg != null) v.setBackground(null);
        } catch (Throwable ignored) {
        }
        try {
            if (fg != null) v.setForeground(null);
        } catch (Throwable ignored) {
        }
    }

    /** Never strip the ROM's own chrome / backdrop layers. */
    private static boolean keepBackground(View v) {
        String n = v.getClass().getName().toLowerCase();
        // HnTransparentPattern deliberately NOT kept: on this ROM it owns the
        // opaque window background that hides the decor-level overlay.
        String[] keep = {"toolbar", "appbar", "blur", "statusbar",
                "navigationbar", "decorview"};
        for (String k : keep) {
            if (n.contains(k)) return true;
        }
        return false;
    }

    /**
     * 0.4.0-p39 DIRECT white-card truth probe. p37's judge only proved that
     * WE did not actively put the OEM Drawables back; it never proved the
     * white cards were actually gone. Count latched Views whose background /
     * foreground came back (OEM rebind) and whether the DecorView regained an
     * opaque background. This is the judge that should have been used first.
     */
    /**
     * 0.4.0-p40. WHITECHECK on p39 proved the remaining leak: on back
     * navigation the OEM rebinds rows and calls setBackground() again on Views
     * we ALREADY latched (revived up to 7). syncRows() only clears this frame's
     * stripAll + cached ancestors, so a revived View that is not in that set
     * keeps its white card until (maybe) a later frame. Re-sweep the whole
     * latched set: O(latched rows), throttled to 120 ms, and forced once on
     * every lifecycle event so the first post-return frame is already clean.
     * saveAndClear() preserves the ORIGINAL Saved Drawable, so a later real
     * restore (glass off) is still faithful.
     */
    /**
     * 0.4.0-p41 accepted fix, kept verbatim: NO execution throttle, because
     * the OEM can re-apply a white Drawable between sweeps. 0.5.0 removes only
     * the SECOND full pass and the WHITESWEEP log line -- pure diagnostics
     * that duplicated the same walk every frame for no rendering benefit.
     */
    private void restripLatched() {
        try {
            for (Map.Entry<View, Saved> e : savedRows.entrySet()) {
                View v = e.getKey();
                if (v == null || !v.isAttachedToWindow()) continue;
                Saved s = e.getValue();
                if (s != null && s.placeholder != null) {
                    // 0.5.0-p118 unified-card placeholder row. IDENTITY check:
                    // the non-null Drawable we installed is NOT an OEM re-bind,
                    // so never clear it (that made glass candidates flicker on
                    // every frame). Only a genuinely new instance is revived.
                    if (!unifiedIsOurPlaceholder(v, s)) {
                        if (unifiedReapplyPlaceholder(v, s)) {
                            diagUnifiedRevived++;
                            emitUnifiedIfDue();
                        }
                    }
                } else if (v.getBackground() != null || v.getForeground() != null) {
                    // Pre-existing saveAndClear row: unchanged behaviour.
                    saveAndClear(v);
                }
                // 0.5.0-p107: keep the container's own onDraw suppressed if
                // the OEM re-binds the View, with no extra tree walk.
                if (s != null && s.willNotDraw) {
                    try {
                        v.setWillNotDraw(true);
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void restoreRows(String reason) {
        // 0.4.0-p36 PURE DIAGNOSTIC. A non-zero `saved` here means OEM white
        // Drawables were physically put back onto live Views.
        log("DIAG restoreRows reason=" + reason
                + " saved=" + savedRows.size() + " slot=" + slot);
        for (Map.Entry<View, Saved> e : savedRows.entrySet()) {
            restore(e.getKey(), e.getValue());
        }
        savedRows.clear();
        ancestorViews.clear();
        // 0.4.0: give the OEM its onDraw pass back on a real detach / glass
        // off, so the stock page is intact when our module is not active.
        suppressor.apply(false);
        lastRowSig = Long.MIN_VALUE;
    }

    private static void restore(View v, Saved s) {
        // 0.5.0-p118: s.background/foreground are ALWAYS the original OEM
        // Drawables captured before any strip — the transparent placeholder
        // lives only in s.placeholder and is never written here, so detach /
        // glass-off hands the ROM its exact originals back, never our placeholder.
        try {
            v.setBackground(s.background);
        } catch (Throwable ignored) {
        }
        try {
            v.setForeground(s.foreground);
        } catch (Throwable ignored) {
        }
        // 0.5.0-p107: give the ROM container its own draw pass back.
        // ViewGroup defaults to willNotDraw=true, so false simply restores
        // the OEM's own onDraw; a no-op draw when it had none.
        if (s.willNotDraw) {
            try {
                v.setWillNotDraw(false);
            } catch (Throwable ignored) {
            }
        }
    }

    private void clearGlass() {
        // Only the CHILD layer is emptied. glassHost itself keeps its layer
        // so the z-order stays fixed across a rebuild.
        try {
            if (glassLayer != null) glassLayer.removeAllViews();
        } catch (Throwable ignored) {
        }
        containers.clear();
        glassEffect = null;
        lastGeoSig = Long.MIN_VALUE;
        lastRowSig = Long.MIN_VALUE;
    }

    // ---- overlay plumbing ---------------------------------------------------

    private static FrameLayout ensureOverlay(ViewGroup decor) {
        View existing = decor.findViewWithTag(TAG_OVERLAY);
        if (existing instanceof FrameLayout) return (FrameLayout) existing;
        FrameLayout f = new FrameLayout(decor.getContext());
        f.setTag(TAG_OVERLAY);
        f.setClipChildren(false);
        decor.addView(f, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        return f;
    }

    private static FrameLayout ensureGlassHost(FrameLayout overlay) {
        View existing = overlay.findViewWithTag(TAG_GLASS_HOST);
        if (existing instanceof FrameLayout) return (FrameLayout) existing;
        FrameLayout f = new FrameLayout(overlay.getContext());
        f.setTag(TAG_GLASS_HOST);
        f.setClipChildren(false);
        // BackdropLayer owns indices 0..2; the glass host sits above them.
        overlay.addView(f, fullLp());
        return f;
    }

    private FrameLayout newLayer(String tag) {
        FrameLayout f = new FrameLayout(overlay.getContext());
        f.setTag(tag);
        f.setClipChildren(false);
        return f;
    }

    private static FrameLayout.LayoutParams fullLp() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private static boolean isDestroyed(Activity a) {
        try {
            return a.isDestroyed();
        } catch (Throwable t) {
            return false;
        }
    }

    private void logOnce(String msg) {
        if (errorLogged) return;
        errorLogged = true;
        log(msg);
    }

    private static void log(String msg) {
        GlassEntry.say("controller: " + msg);
    }

    private static final class Saved {
        Drawable background;
        Drawable foreground;
        /** 0.5.0-p107: setWillNotDraw(true) was applied to this View. */
        boolean willNotDraw;
        /**
         * 0.5.0-p118: the transparent placeholder instance we installed in place
         * of the original background/foreground. Non-null iff this row was
         * converted by the unified-card gate. restripLatched() uses IDENTITY
         * against this field so it never mistakes its own placeholder for an
         * OEM re-bind; restore() uses background/foreground (the originals),
         * never handing the placeholder back to the ROM.
         */
        Drawable placeholder;
    }
}
