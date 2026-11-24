package com.magicglass;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Map;

/**
 * MagicGlass settings UI — iOS "Grouped Inset List" visual language.
 *
 * Pure Java: no XML layout, no res/ directory, so the standalone aapt2/d8
 * toolchain keeps working. Every visual element is a GradientDrawable + code
 * layout. The whole write chain (writeLive / writeNow / stored / push /
 * notifyChanged / ensureDefaults / restoreGlassDefaults / ensureCustomFilling
 * / ensureWindowColor) is byte-for-byte unchanged from the accepted baseline.
 *
 * New in this revision: a read-only "界面元素 · 视觉编辑" page that draws a
 * phone screen wireframe (①~⑩ at their real positions) with pure Java Canvas.
 * Tapping a region highlights it and explains in plain words what it is and
 * how it is adjusted. It writes no config value and performs no runtime hook.
 */
public final class SettingsActivity extends Activity {

    private static final int REQ_MEDIA = 1001;
    private static final int REQ_FILL_IMAGE = 1002;  // 0.5.0-p175 填充图片
    private static final int REQ_ICON_SWAP = 1003;   // 0.5.0-p178 行内图标替换
    private static final int REQ_ICON_CROP = 1004;   // 0.5.0-p189 选图后的交互裁剪
    private static final int MODE_IMAGE = 0;
    private static final int MODE_VIDEO = 1;
    private static final int MODE_POOL = 2;

    // iOS system colors. No theme attribute lookup.
    private static final int C_PAGE = 0xFFF2F2F7;        // systemGroupedBackground
    private static final int C_CARD = 0xFFFFFFFF;        // secondarySystemGroupedBackground
    private static final int C_TEXT = 0xFF1C1C1E;        // label
    private static final int C_SUB = 0xFF6E6E73;         // secondaryLabel
    private static final int C_MUTED = 0xFF8E8E93;       // tertiaryLabel
    // 0.5.0-p192: 强调色改为实例字段，随主题即时重设（名字不变，引用点不动）。
    private int C_ACCENT = 0xFF007AFF;      // systemBlue
    private int C_ACCENT_SOFT = 0xFFE9F2FF; // systemBlue tint
    private static final int C_LINE = 0xFFE5E5EA;        // separator / opaque stroke
    private int C_TRACK = 0xFFE9E9EB;       // UISegmentedControl track
    private static final int C_CHIP = 0xFFEFEFF4;        // neutral chip
    private static final int C_WHITE = 0xFFFFFFFF;
    private int C_GREEN = 0xFF34C759;       // UISwitch on
    private static final int C_SHADOW = 0x1A000000;      // soft thumb shadow
    // 0.5.0-p174: One UI 风格——强调色主卡 / 页签 / 分组卡（p192 起随主题）。
    private int C_HERO = 0xFF534AB7;        // One UI accent hero
    private int C_HERO_LIGHT = 0xFFCECBF6;  // hero 上的次要文字
    private int C_TAB_ON = 0xFF534AB7;      // 选中页签
    private static final int C_TAB_BG = 0xFFEFEFF4;      // 页签轨道背景

    // 0.5.0-p192: 六套主题（方案1 磁贴工作台）。index 1=系统蓝为默认，
    // 保持与旧版外观逐字节一致；其余五色取材料/系统常见强调色。
    private static final String[] THEME_NAMES = {
            "表达紫", "系统蓝", "玻璃青", "薄荷绿", "暖阳橙", "高级灰"};
    private static final int[] THEME_ACCENT = {
            0xFF6750A4, 0xFF007AFF, 0xFF00838F, 0xFF1D9E75, 0xFFD85A30, 0xFF3C3C43};
    private static final int[] THEME_SOFT = {
            0xFFEADDFF, 0xFFE9F2FF, 0xFFE0F4F6, 0xFFE1F5EE, 0xFFFAECE7, 0xFFEFEFF4};
    private static final int[] THEME_HERO = {
            0xFF6750A4, 0xFF534AB7, 0xFF00695C, 0xFF0F6E56, 0xFF993C1D, 0xFF2C2C2A};
    private static final int[] THEME_HERO_LIGHT = {
            0xFFD0BCFF, 0xFFCECBF6, 0xFFB2DFDB, 0xFF9FE1CB, 0xFFF5C4B3, 0xFFB4B2A9};
    private static final int[] THEME_GREEN = {
            0xFF6750A4, 0xFF34C759, 0xFF00897B, 0xFF1D9E75, 0xFFBA7517, 0xFF5F5E5A};
    private static final int[] THEME_TRACK = {
            0xFFE7E0EC, 0xFFE9E9EB, 0xFFE0E0E0, 0xFFE1F5EE, 0xFFFAEEDA, 0xFFEFEFF4};
    private static final String KEY_UI_THEME = "ui.theme";
    private int themeIndex = 1;
    // 示意图是静态内部类，不能直接引用实例字段；这里留一份镜像供其取色。
    private static int sWireAccent = 0xFF007AFF;
    private static int sWireAccentSoft = 0xFFE9F2FF;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private LinearLayout body;
    private ConfigContract.Slot slot = ConfigContract.Slot.GLOBAL;
    private int mediaMode = MODE_IMAGE;
    private int activeTab = 0;  // 0=基本 1=高级 (p174 One UI 页签)
    private int expandedSection = 0;  // p190 B1: 0=背景 1=玻璃 2=图标, -1=全收起
    private String iconKey = ""; // p178 图标替换:上次填写的 preference key
    private String iconSearch = ""; // p181 内置目录搜索词
    private boolean iconListExpanded; // p187 是否展开全部 23 行
    // 0.5.0-p186: 图标替换 UI 只用 23 行静态清单，抓取相关字段已移除。
    private LinearLayout iconRowsHost;
    private boolean building;
    private boolean pending;

    // Read-only element-browser state: visualDomain now holds the raw name of
    // the selected real-device L1 (with its parenthesised suffix), visualScreen
    // the selected L2 screen key, and visualSelected the lit wireframe region.
    private String visualDomain = "";
    private String visualScreen = "wifi_settings";
    private int visualSelected = 4;

    private final Runnable push = new Runnable() {
        @Override
        public void run() {
            pending = false;
            ConfigStore.notifyChanged(SettingsActivity.this);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 0.5.0-p192: 主题在构建任何 View 之前应用，保证首屏即目标配色。
        applyTheme();
        ConfigStore.ensureDefaults(this);
        // 0.5.0-p68: put the LiquidTab recipe back BEFORE building any row,
        // so the diagnostics text and every control read the restored value.
        ConfigStore.restoreGlassDefaults(this);
        ConfigStore.ensureCustomFilling(this);
        // 0.5.0-p77: idempotent one-shot migration. Installs upgraded from
        // p76 have NO bg.windowColor key, so their starting window would keep
        // flashing the fixed near-white base. Deriving it once here (only when
        // the key is absent) removes the "白色转变成选择的背景" jump without
        // requiring the user to re-pick the wallpaper. Zero cost on later
        // opens: ensureWindowColor() returns before touching any file.
        ConfigStore.ensureWindowColor(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_PAGE);
        root.setPadding(dp(16), dp(10), dp(16), dp(12));

        // 0.5.0-p193: 删除 p174 的第二套标题（图标方块 + MagicGlass + 状态胶囊）。
        // 自 p190 起 headerBar() 已在 rebuild() 里渲染标题 + 作者 + 状态点，
        // 两套叠加导致"两层标题"。标题只保留 headerBar() 一处。
        // 模块状态卡在 body 的「模块状态」分组里展示 BUILD 与生效范围。

        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(body, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        bodyLp.setMargins(0, dp(16), 0, 0);
        root.addView(scroll, bodyLp);

        setContentView(root);
        rebuild();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(push);
        // A last edit made just before leaving the UI must still reach the
        // host processes. No broadcast at all when nothing was changed.
        if (pending) ConfigStore.notifyChanged(this);
        super.onDestroy();
    }

    // 0.5.0-p186: 旧的"抓取当前页行列表"接收器/请求/超时已随图标替换重做移除。

    // ---- screen construction ------------------------------------------------

    /**
     * 0.5.0-p174: One UI 风格——顶部强调色主卡 + 基本/高级页签。
     * 所有 p171~p173 的功能卡都保留：基本页放背景/显示诊断/位置,
     * 高级页放 8 项玻璃参数 + 只读状态。页签状态存在字段里,rebuild 时恢复。
     */
    private void rebuild() {
        building = true;
        body.removeAllViews();
        // 0.5.0-p192: 方案1 磁贴工作台——主开关磁贴 + 状态 + 外观磁贴 + 关于
        // + 主题切换 + 诊断日志(最底)。主题字段已在 applyTheme 里设好。
        body.addView(headerBar());
        body.addView(groupLabel("快速开关"));
        body.addView(quickSwitchTiles());
        body.addView(groupLabel("模块状态"));
        body.addView(statusGroup());
        body.addView(groupLabel("外观"));
        body.addView(appearanceGroup());
        // 展开的那一组把原有控件卡片接在分组卡下面（功能全部保留）。
        if (expandedSection == 0) {
            body.addView(backgroundCard());
            body.addView(displayCard());
        } else if (expandedSection == 1) {
            body.addView(glassAdvancedCard());
        } else if (expandedSection == 2) {
            body.addView(iconSwapCard());
        }
        body.addView(groupLabel("关于"));
        body.addView(aboutGroup());
        body.addView(groupLabel("赞助"));
        body.addView(sponsorGroup());
        body.addView(groupLabel("主题配色"));
        body.addView(themeGroup());
        body.addView(groupLabel("诊断"));
        body.addView(diagnosticGroup());
        body.addView(footerNote());
        building = false;
    }

    /** 0.5.0-p192: 两个主开关并排磁贴——液态玻璃 / 自定义背景。 */
    private View quickSwitchTiles() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(4), dp(2), dp(4), 0);

        LinearLayout glass = switchTile("液态玻璃效果", "总开关 · 即改即生效",
                ConfigContract.Param.GLASS_ENABLED,
                roundRect(C_HERO, 9, 0, 0));
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        gl.rightMargin = dp(5);
        row.addView(glass, gl);

        LinearLayout bg = switchTile("自定义背景", "关闭恢复窗口底色",
                ConfigContract.Param.BG_ENABLED,
                roundRect(0xFF7FA88C, 9, 0, 0));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        bl.leftMargin = dp(5);
        row.addView(bg, bl);
        return row;
    }

    /**
     * 0.5.0-p192: 单块磁贴——顶部色块示意 + 标题 + 副标题 + 开关。
     * 开关沿用 switchRow 的绘制与写入链路，行为完全一致。
     */
    private LinearLayout switchTile(String title, String sub,
                                    ConfigContract.Param p, android.graphics.drawable.Drawable badge) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setBackground(roundRect(C_CARD, 10, 0, 0));
        tile.setPadding(dp(10), dp(10), dp(10), dp(6));

        View mark = new View(this);
        mark.setBackground(badge);
        tile.addView(mark, new LinearLayout.LayoutParams(dp(28), dp(5)));
        LinearLayout.LayoutParams tm = (LinearLayout.LayoutParams) mark.getLayoutParams();
        tm.bottomMargin = dp(8);

        tile.addView(text(title, 12.5f, C_TEXT, 0));
        tile.addView(text(sub, 10f, C_MUTED, dp(2)));
        tile.addView(switchRow("", "", p));
        return tile;
    }

    /**
     * 0.5.0-p197: 唯一赞赏渠道——内置作者提供的赞赏码图片（drawable/sponsor_qr）。
     * 移除了自定义上传、外链输入框与所有相关入口，做成国内主流方案：
     * 一张固定的赞赏码，用户用微信扫一扫即可。
     */
    private View sponsorGroup() {
        LinearLayout c = groupCard();
        android.widget.ImageView qr = new android.widget.ImageView(this);
        qr.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        qr.setBackgroundColor(C_WHITE);
        qr.setAdjustViewBounds(true);
        int rid = getResources().getIdentifier("sponsor_qr", "drawable", getPackageName());
        if (rid != 0) {
            qr.setImageResource(rid);
        }
        c.addView(qr, wrap());

        TextView hint = text("为肥鱼肥龙大小姐献上美好世界的祝福", 12f, C_TEXT, dp(8));
        hint.setGravity(Gravity.CENTER_HORIZONTAL);
        c.addView(hint, wrap());

        // 0.5.0-p198: 点击跳转——赞赏码是微信小程序码（圆形放射状，
        // 非标准 QR，图内无网址），无法反推出直达支付页的链接。
        // 改为：点一下把码图存进相册 + 拉起微信扫一扫，用户在扫一扫
        // 里点「相册」选刚存的图即可，全程一台手机。
        TextView jump = text("点击跳转 →", 13f, C_ACCENT, dp(8));
        jump.setGravity(Gravity.CENTER_HORIZONTAL);
        c.addView(jump, wrap());
        TextView sub = text("自动保存赞赏码并直达微信扫一扫 · 点相册选它",
                11f, C_MUTED, dp(3));
        sub.setGravity(Gravity.CENTER_HORIZONTAL);
        c.addView(sub, wrap());
        final int finalRid = rid;
        c.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                jumpToSponsor(finalRid);
            }
        });
        return c;
    }

    /** 0.5.0-p198: 存码图到相册并拉起微信扫一扫（单机闭环）。 */
    private void jumpToSponsor(int rid) {
        boolean saved = false;
        try {
            if (rid != 0) {
                android.graphics.Bitmap bmp =
                        android.graphics.BitmapFactory.decodeResource(getResources(), rid);
                if (bmp != null) {
                    android.provider.MediaStore.Images.Media.insertImage(
                            getContentResolver(), bmp, "MagicGlass赞赏码", "赞助作者");
                    saved = true;
                }
            }
        } catch (Throwable t) {
            saved = false;
        }
        // 0.5.0-p201: 放弃废弃的 weixin://dl/scan（微信 8.0.79 一律提示
        // "当前页面无法访问"）。改用显式 ComponentName 启动 LauncherUI
        // 并带 extra=LauncherUI.From.Scaner.Shortcut，实测直达 BaseScanUI
        // （扫一扫）；失败再退回普通主界面。
        boolean launched = false;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setComponent(new android.content.ComponentName(
                    "com.tencent.mm", "com.tencent.mm.ui.LauncherUI"));
            i.putExtra("LauncherUI.From.Scaner.Shortcut", true);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            launched = true;
        } catch (Throwable t) {
            launched = false;
        }
        if (!launched) {
            try {
                Intent i = getPackageManager()
                        .getLaunchIntentForPackage("com.tencent.mm");
                if (i != null) {
                    startActivity(i);
                    launched = true;
                }
            } catch (Throwable t) {
                launched = false;
            }
        }
        if (saved && launched) {
            toast("赞赏码已存入相册 · 扫一扫里点相册选它");
        } else if (saved) {
            toast("赞赏码已存入相册 · 请在扫一扫点相册选它");
        } else {
            toast("请在微信扫一扫中长按识别本页赞赏码");
        }
    }

    /** 0.5.0-p192: 主题切换——六个色块，点选即存本地并重绘整个模块 UI。 */
    private View themeGroup() {
        LinearLayout c = groupCard();
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(11), dp(9), dp(11), dp(6));
        head.addView(text("界面配色", 12.5f, C_TEXT, 0), new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(text(THEME_NAMES[themeIndex] + " · 当前", 11f, C_ACCENT, 0));
        c.addView(head);

        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(dp(11), dp(2), dp(11), dp(10));
        for (int i = 0; i < THEME_NAMES.length; i++) {
            final int idx = i;
            View swatch = new View(this);
            boolean on = i == themeIndex;
            swatch.setBackground(roundRect(THEME_ACCENT[i], 99, on ? 2 : 0,
                    on ? C_TEXT : 0x00000000));
            swatch.setPadding(0, 0, 0, 0);
            swatch.setMinimumWidth(dp(30));
            swatch.setMinimumHeight(dp(30));
            swatch.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    applyThemeIndex(idx);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(30), dp(30));
            if (i > 0) lp.leftMargin = dp(9);
            chips.addView(swatch, lp);
        }
        c.addView(chips);
        return c;
    }

    /** 0.5.0-p192: 写本地主题并即时重绘；纯模块 UI 状态，不广播、不重启。 */
    private void applyThemeIndex(int idx) {
        if (idx < 0 || idx >= THEME_NAMES.length) return;
        try {
            ConfigStore.prefs(this).edit().putString(KEY_UI_THEME,
                    String.valueOf(idx)).apply();
        } catch (Throwable ignored) {
        }
        applyTheme();
        rebuild();
    }

    /** 0.5.0-p192: 诊断日志单独一组，沉到界面最底。 */
    private View diagnosticGroup() {
        LinearLayout c = groupCard();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(11), dp(3), dp(11), dp(3));
        row.addView(switchRow("诊断日志", "逐帧统计，默认关闭以省电",
                ConfigContract.Param.PERF_DEBUG_LOG), wrap());
        c.addView(row);
        return c;
    }

    /** 0.5.0-p192: 读取本地主题并重设强调色字段；只影响模块 UI，不写宿主配置。 */
    private void applyTheme() {
        try {
            String raw = ConfigStore.prefs(this).getString(KEY_UI_THEME, "1");
            int i = Integer.parseInt(raw.trim());
            if (i < 0 || i >= THEME_NAMES.length) i = 1;
            themeIndex = i;
        } catch (Throwable t) {
            themeIndex = 1;
        }
        C_ACCENT = THEME_ACCENT[themeIndex];
        C_ACCENT_SOFT = THEME_SOFT[themeIndex];
        C_HERO = THEME_HERO[themeIndex];
        C_HERO_LIGHT = THEME_HERO_LIGHT[themeIndex];
        C_GREEN = THEME_GREEN[themeIndex];
        C_TRACK = THEME_TRACK[themeIndex];
        C_TAB_ON = THEME_ACCENT[themeIndex];
        sWireAccent = C_ACCENT;
        sWireAccentSoft = C_ACCENT_SOFT;
    }

    /** 标题栏：模块名 + 作者 + 运行状态点。 */
    private View headerBar() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(2), dp(10), dp(2), 0);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(android.view.Gravity.BOTTOM);

        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        // 0.5.0-p194: 标题与项目名对齐（p176 起模块名=MagicOS11GlassForSetting，
        // 但界面标题一直是旧名 MagicGlass）。长名降一号字避免溢出。
        names.addView(text("MagicOS11GlassForSetting", 17f, C_TEXT, 0), wrap());
        names.addView(text("ZOQOO · 基于 LSPosed", 11f, C_MUTED, dp(1)), wrap());
        top.addView(names, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        boolean glassOn = storedBool(ConfigContract.Param.GLASS_ENABLED);
        LinearLayout dot = new LinearLayout(this);
        dot.setOrientation(LinearLayout.HORIZONTAL);
        dot.setGravity(android.view.Gravity.CENTER_VERTICAL);
        View d = new View(this);
        d.setBackground(roundRect(glassOn ? C_GREEN : 0xFFC7C7CC, 99, 0, 0));
        dot.addView(d, new LinearLayout.LayoutParams(dp(7), dp(7)));
        TextView st = text(glassOn ? "运行中" : "已关闭", 11f, C_MUTED, 0);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        stLp.leftMargin = dp(5);
        dot.addView(st, stLp);
        top.addView(dot, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(top, wrap());
        return row;
    }

    /** 分组卡内的开关行：补左右内边距，复用原 switchRow。 */
    private View switchRowInset(String title, String sub, ConfigContract.Param p) {
        LinearLayout wrap2 = new LinearLayout(this);
        wrap2.setOrientation(LinearLayout.VERTICAL);
        wrap2.setPadding(dp(11), 0, dp(11), 0);
        wrap2.addView(switchRow(title, sub, p));
        return wrap2;
    }

    /** 分组小标题（灰字，位于白卡之上）。 */
    private View groupLabel(String s) {
        TextView t = text(s, 11f, C_MUTED, 0);
        t.setPadding(dp(4), dp(13), 0, dp(5));
        return t;
    }

    /** 无内边距的白卡容器，行自己带 padding。 */
    private LinearLayout groupCard() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(roundRect(C_CARD, 10, 0, 0));
        return c;
    }

    private View divider(int leftDp) {
        View v = new View(this);
        v.setBackgroundColor(0xFFE5E5EA);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        lp.leftMargin = dp(leftDp);
        v.setLayoutParams(lp);
        return v;
    }

    /** 模块状态分组：运行状态 / 生效范围。 */
    private View statusGroup() {
        LinearLayout c = groupCard();
        c.addView(statusRow(true, "运行中", GlassEntry.BUILD));
        c.addView(divider(27));
        c.addView(statusRow(false, "生效范围",
                storedBool(ConfigContract.Param.GLASS_ENABLED) ? "全部页面" : "已关闭"));
        return c;
    }

    private View statusRow(boolean on, String label, String value) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(android.view.Gravity.CENTER_VERTICAL);
        r.setPadding(dp(11), dp(9), dp(11), dp(9));
        View dot = new View(this);
        dot.setBackground(roundRect(on ? C_GREEN : 0xFFC7C7CC, 99, 0, 0));
        r.addView(dot, new LinearLayout.LayoutParams(dp(7), dp(7)));
        TextView t = text(label, 12.5f, C_TEXT, 0);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tLp.leftMargin = dp(9);
        r.addView(t, tLp);
        r.addView(text(value, 11f, C_MUTED, 0), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return r;
    }

    /** 外观分组：三行带 38px 缩略图，点行就地展开。 */
    private View appearanceGroup() {
        LinearLayout c = groupCard();
        // 0.5.0-p192: 三个总开关已迁到顶部磁贴(2个)与最底诊断组(1个)，
        // 这里只保留三行外观入口；展开的控件仍在下方，功能不变。
        String bg = bgKindLabel();
        float blur = storedFloat(ConfigContract.Param.GLASS_BLUR_RADIUS, -1f);
        String glass = storedBool(ConfigContract.Param.GLASS_ENABLED)
                ? "圆角 " + Math.round(storedFloat(
                        ConfigContract.Param.GEO_RADIUS_DP, 18f))
                + " · 模糊 " + (blur < 0 ? "跟随系统" : String.valueOf(Math.round(blur)))
                : "已关闭";
        int replaced = countIconRules();
        c.addView(appearanceRow(0, "背景", bg, thumbBg()));
        c.addView(divider(59));
        c.addView(appearanceRow(1, "玻璃容器", glass, thumbGlass()));
        c.addView(divider(59));
        c.addView(appearanceRow(2, "行内图标",
                "L1 主设置页 " + L1IconRows.TITLES.length + " 行 · 已替换 "
                        + replaced + " 项", thumbIcons()));
        return c;
    }

    private View appearanceRow(final int section, String title, String summary,
                               View thumb) {
        boolean open = expandedSection == section;
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(android.view.Gravity.CENTER_VERTICAL);
        r.setPadding(dp(11), dp(7), dp(11), dp(7));
        r.setBackground(roundRect(open ? 0xFFF7F7FA : 0x00000000, 8, 0, 0));

        r.addView(thumb, new LinearLayout.LayoutParams(dp(38), dp(38)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(text(title, 13f, C_TEXT, 0), wrap());
        col.addView(text(summary, 10.5f, C_MUTED, dp(1)), wrap());
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        cLp.leftMargin = dp(10);
        r.addView(col, cLp);

        r.addView(text(open ? "⌄" : "›", 13f, 0xFFC7C7CC, 0));
        r.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                expandedSection = expandedSection == section ? -1 : section;
                rebuild();
            }
        });
        return r;
    }

    /** 背景缩略：浅蓝底 + 深绿色块示意壁纸。 */
    private View thumbBg() {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setBackground(roundRect(0xFFBFD9F2, 7, 0, 0));
        t.setGravity(android.view.Gravity.BOTTOM);
        View hill = new View(this);
        hill.setBackground(roundRect(0xFF7FA88C, 4, 0, 0));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(14));
        lp.setMargins(dp(4), 0, dp(4), dp(4));
        t.addView(hill, lp);
        return t;
    }

    /** 玻璃缩略：淡紫底 + 内层半透明白块示意毛玻璃。 */
    private View thumbGlass() {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setBackground(roundRect(0xFFE7E4F7, 7, 0, 0));
        t.setPadding(dp(5), dp(5), dp(5), dp(5));
        View pane = new View(this);
        pane.setBackground(roundRect(0x99FFFFFF, 4, 1, 0x66FFFFFF));
        t.addView(pane, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT));
        return t;
    }

    /** 图标缩略：三个圆点，绿=已换，灰=未换。 */
    private View thumbIcons() {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.HORIZONTAL);
        t.setGravity(android.view.Gravity.CENTER);
        t.setBackground(roundRect(C_CHIP, 7, 0, 0));
        int replaced = countIconRules();
        for (int i = 0; i < 3; i++) {
            View dot = new View(this);
            dot.setBackground(roundRect(i < replaced ? C_GREEN : 0xFFC7C7CC, 99, 0, 0));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(9), dp(9));
            if (i > 0) lp.leftMargin = dp(3);
            t.addView(dot, lp);
        }
        return t;
    }

    /** 关于分组：作者 + 版本。 */
    private View aboutGroup() {
        LinearLayout c = groupCard();
        c.addView(aboutRow("作者", "ZOQOO"));
        c.addView(divider(11));
        c.addView(aboutRow("版本", GlassEntry.BUILD));
        return c;
    }

    private View aboutRow(String label, String value) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(android.view.Gravity.CENTER_VERTICAL);
        r.setPadding(dp(11), dp(10), dp(11), dp(10));
        r.addView(text(label, 12.5f, C_TEXT, 0), new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(text(value, 11f, C_MUTED, 0));
        return r;
    }

    /** 已配置的图标规则条数（用于摘要与缩略点）。 */
    private int countIconRules() {
        try {
            Map<String, String> parsed =
                    ConfigContract.parseElRulesPublic(iconRules());
            int n = 0;
            for (Map.Entry<String, String> e : parsed.entrySet()) {
                String[] parts = e.getKey().split("\u0000");
                if (parts.length > 1 && "icon".equals(parts[1])) n++;
            }
            return n;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** One UI 强调色主卡:当前配置/是否生效 + 背景与作用域两格。 */
    private View heroCard() {
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setBackground(roundRect(C_HERO, 16, 0, 0));
        hero.setPadding(dp(16), dp(14), dp(16), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(18), 0, 0);
        hero.setLayoutParams(lp);

        hero.addView(text("当前配置", 11f, C_HERO_LIGHT, 0));
        boolean glassOn = storedBool(ConfigContract.Param.GLASS_ENABLED);
        hero.addView(text(glassOn ? "已生效" : "玻璃已关闭", 20f, C_WHITE, dp(4)), wrap());

        LinearLayout stats = new LinearLayout(this);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        stats.setPadding(0, dp(12), 0, 0);
        stats.addView(heroStat("背景", bgKindLabel()));
        stats.addView(heroStat("玻璃参数", glassOn ? "全部页面" : "已关闭"));
        hero.addView(stats, wrap());
        return hero;
    }

    private View heroStat(String label, String value) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(roundRect(0x26FFFFFF, 10, 0, 0));
        box.setPadding(dp(10), dp(8), dp(10), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(4), 0, dp(4), 0);
        box.setLayoutParams(lp);
        box.addView(text(label, 11f, C_HERO_LIGHT, 0), wrap());
        box.addView(text(value, 13f, C_WHITE, dp(2)), wrap());
        return box;
    }

    /** One UI 双层页签:基本 / 高级。 */
    private View tabRow() {
        LinearLayout track = new LinearLayout(this);
        track.setOrientation(LinearLayout.HORIZONTAL);
        track.setBackground(roundRect(C_TAB_BG, 999, 0, 0));
        track.setPadding(dp(3), dp(3), dp(3), dp(3));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(14), 0, 0);
        track.setLayoutParams(lp);

        final TextView basic = tabCell("基本", activeTab == 0);
        final TextView adv = tabCell("高级", activeTab == 1);
        basic.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (activeTab == 0) return;
                activeTab = 0; rebuild();
            }
        });
        adv.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (activeTab == 1) return;
                activeTab = 1; rebuild();
            }
        });
        LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        track.addView(basic, cellLp);
        track.addView(adv, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return track;
    }

    private TextView tabCell(String label, boolean on) {
        TextView t = text(label, 12f, on ? C_WHITE : C_SUB, 0);
        t.setGravity(android.view.Gravity.CENTER);
        t.setPadding(0, dp(9), 0, dp(9));
        t.setBackground(roundRect(on ? C_TAB_ON : 0x00000000, 999, 0, 0));
        return t;
    }

    /**
     * 基本页的「显示与诊断」卡:玻璃总开关 + 诊断日志 + 统一页面/诊断按钮。
     * 从 p171 moreCard() 拆出,功能完全不变,只是不再折叠。
     */
    /**
     * 0.5.0-p178: 行内图标替换卡。按 preference key 定位设置页某一行的图标,
     * 换一张导入的图片。key 可在「诊断日志」打开后进目标页看 PREFBIND key= 得到。
     * 规则存 icon.rules(与 el.rules 同格式),改完走 ConfigStore 广播即时生效。
     */
    private View iconSwapCard() {
        LinearLayout card = card();
        card.addView(sectionHeader("行内图标替换"));
        card.addView(text("L1 主设置页的 23 行，点行选图即换；无需抓取、无需记 key。",
                11f, C_MUTED, dp(4)));

        final EditText searchInput = inputRow(card, "搜索设置项", iconSearch,
                "例：WLAN / 蓝牙 / wifi / bluetooth");
        searchInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable s) {
                iconSearch = s.toString();
                renderKeyDirectory();
            }
        });

        // 0.5.0-p187: 不再用内层 ScrollView（嵌在外层整页 ScrollView 里会抢
        // 触摸、拖不动）。改为默认只显示 5 行 + 「展开全部 23 行」，展开后由
        // 外层整页滚动承载，全程只有一个滚动容器，拖动必然跟手。
        iconRowsHost = new LinearLayout(this);
        iconRowsHost.setOrientation(LinearLayout.VERTICAL);
        card.addView(iconRowsHost, wrap());
        renderKeyDirectory();

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.addView(capsule("全部重置", C_CHIP, C_TEXT,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        String rules = iconRules();
                        Map<String, String> parsed =
                                ConfigContract.parseElRulesPublic(rules);
                        for (Map.Entry<String, String> e : parsed.entrySet()) {
                            String[] parts = e.getKey().split("\u0000");
                            String rowKey = parts.length > 0 ? parts[0] : "";
                            String dim = parts.length > 1 ? parts[1] : "";
                            if ("icon".equals(dim) && rowKey.length() > 0) {
                                writeIconRule(rowKey, "");
                            }
                        }
                        rebuild();
                        toast("已重置全部图标");
                    }
                }));
        card.addView(actions, wrap());
        return cardWithMargin(card);
    }

    /**
     * 0.5.0-p187: 渲染 L1 主设置页 23 行清单（真机采集）。默认只显示前 5 行，
     * 点「展开全部」后全部铺开，由外层整页滚动承载——不用内层 ScrollView，
     * 因此不存在嵌套滚动抢触摸、拖不动的问题。
     */
    private void renderKeyDirectory() {
        if (iconRowsHost == null) return;
        iconRowsHost.removeAllViews();
        String q = iconSearch == null ? "" : iconSearch.trim().toLowerCase();
        String rules = iconRules() == null ? "" : iconRules();
        boolean searching = q.length() > 0;
        int limit = (iconListExpanded || searching) ? Integer.MAX_VALUE : 5;
        int shown = 0;
        int matched = 0;
        for (int i = 0; i < L1IconRows.TITLES.length; i++) {
            final String title = L1IconRows.TITLES[i];
            if (searching && !title.toLowerCase().contains(q)) continue;
            matched++;
            if (shown >= limit) continue;
            String mark = rules.indexOf(L1IconRows.ruleGid(title) + "|icon|") >= 0
                    ? " ✓" : "";
            TextView chip = text(title + mark, 14f,
                    mark.length() > 0 ? C_GREEN : C_TEXT, dp(7));
            chip.setBackground(roundRect(C_CHIP, 8, 0, 0));
            chip.setPadding(dp(10), dp(9), dp(10), dp(9));
            chip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    iconKey = L1IconRows.ruleGid(title);
                    pickIconSwap();
                }
            });
            iconRowsHost.addView(chip, wrap());
            shown++;
        }

        if (!searching) {
            final boolean expand = !iconListExpanded;
            String label = expand
                    ? "展开全部 " + matched + " 行"
                    : "收起（只显示前 5 行）";
            TextView toggle = text(label, 13f, C_ACCENT, dp(10));
            toggle.setPadding(dp(2), dp(6), dp(2), dp(6));
            toggle.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    iconListExpanded = expand;
                    renderKeyDirectory();
                }
            });
            iconRowsHost.addView(toggle, wrap());
        }

        iconRowsHost.addView(text(
                searching ? "匹配 " + matched + " 行"
                        : "L1 主设置页 " + matched + " 行（真机采集，点行换图标）",
                11f, C_MUTED, dp(8)));
    }

    private String iconRules() {
        Map<String, String> all = ConfigStore.all(this);
        String v = all.get(ConfigContract.key(
                ConfigContract.Slot.GLOBAL, ConfigContract.Param.ICON_RULES));
        return v == null ? "" : v;
    }

    private void writeIconRule(String key, String mediaName) {
        String next = ConfigContract.elRuleSet(iconRules(), key, "icon", mediaName);
        ConfigStore.write(this, ConfigContract.Slot.GLOBAL,
                ConfigContract.Param.ICON_RULES, next, false, false);
        pending = true;
        ui.removeCallbacks(push);
        ui.postDelayed(push, 120L);
    }

    private void pickIconSwap() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        i.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(Intent.createChooser(i, "选择行内图标图片"), REQ_ICON_SWAP);
        } catch (Throwable t) {
            toast("没有可用的文件选择器");
        }
    }

    private View displayCard() {
        LinearLayout card = card();
        card.addView(sectionHeader("显示与诊断"));
        // 0.5.0-p191: 「液态玻璃效果」「诊断日志」两个总开关已上移到外观分组顶部。
        card.addView(text("总开关已移到上方「外观」分组；此处保留批量操作。",
                11f, C_MUTED, dp(4)));
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.addView(flatButton("统一全部页面", new View.OnClickListener() {
            @Override public void onClick(View v) {
                ConfigStore.clearOverrides(SettingsActivity.this);
                ConfigStore.restoreGlassDefaults(SettingsActivity.this);
                ConfigStore.ensureCustomFilling(SettingsActivity.this);
                rebuild();
                toast("所有页面已统一为全局设置");
            }
        }));
        actions.addView(flatButton("诊断信息", new View.OnClickListener() {
            @Override public void onClick(View v) { diagnostics(); }
        }));
        card.addView(actions, wrap());
        return cardWithMargin(card);
    }

    /** 高级页的玻璃参数卡:复用 p172 tunableSlider 八项,默认展开。 */
    private View glassAdvancedCard() {
        LinearLayout card = card();
        card.addView(sectionHeader("高级设置  ·  玻璃几何与质感"));
        card.addView(text("0.5.0-p173 起对所有页面统一生效；拖动微调，点数值恢复默认。",
                11f, C_MUTED, dp(4)));
        card.addView(tunableSlider("圆角", "玻璃卡圆角大小",
                ConfigContract.Param.GEO_RADIUS_DP, 18f, 48f));
        card.addView(tunableSlider("左右内缩", "玻璃左右留边",
                ConfigContract.Param.GEO_H_INSET_DP, 14f, 40f));
        card.addView(tunableSlider("上下内缩", "玻璃上下留边",
                ConfigContract.Param.GEO_V_INSET_DP, 4f, 24f));
        card.addView(divider());
        card.addView(tunableSlider("通透度", "越高越不透明，越低越清透",
                ConfigContract.Param.GLASS_TRANSPARENCY, 0f, 1f));
        card.addView(tunableSlider("模糊", "背景模糊强度",
                ConfigContract.Param.GLASS_BLUR_RADIUS, 0f, 16f));
        card.addView(tunableSlider("折射", "边缘折射高度",
                ConfigContract.Param.GLASS_REFRACTION, 0f, 40f));
        card.addView(tunableSlider("厚度", "折射厚度，负值为外凸",
                ConfigContract.Param.GLASS_THICKNESS, -60f, 0f));
        card.addView(tunableSlider("色散", "折射色散强度",
                ConfigContract.Param.GLASS_DISPERSION, 0f, 1f));
        card.addView(fillCard());
        return cardWithMargin(card);
    }

    /**
     * 0.5.0-p175: 玻璃卡内部填充卡——纯色(#RRGGBB/#AARRGGBB)+不透明度,
     * 以及一张带大小限制的填充图片。默认值全空/-1,不改任何现有观感。
     */
    private View fillCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(0, dp(18), 0, 0);
        card.addView(divider());
        card.addView(text("内部填充（玻璃卡底色）", 14f, C_TEXT, dp(12)));
        card.addView(text("纯色与图片可叠加；留空即沿用系统默认玻璃。改动即时生效，对所有页面统一。",
                11f, C_MUTED, dp(4)));

        // 颜色输入：点色块用系统取色（若不可用则退回文本输入），右侧按钮清空。
        final TextView colorBadge = text(fillColorLabel(),
                12f, C_ACCENT, dp(8));
        colorBadge.setBackground(roundRect(C_ACCENT_SOFT, 8, 0, 0));
        colorBadge.setPadding(dp(10), dp(4), dp(10), dp(4));
        colorBadge.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showColorDialog(colorBadge,
                        ConfigContract.Param.GLASS_FILL_COLOR);
            }
        });
        card.addView(colorBadge, wrap());

        card.addView(fillAlphaSlider("底色不透明度", ConfigContract.Param.GLASS_FILL_ALPHA));

        // 图片：选择/清除 + 最大边与缩放方式。
        LinearLayout imgRow = new LinearLayout(this);
        imgRow.setOrientation(LinearLayout.HORIZONTAL);
        imgRow.setPadding(0, dp(10), 0, 0);
        imgRow.addView(flatButton("选择填充图片", new View.OnClickListener() {
            @Override public void onClick(View v) { pickFillImage(); }
        }));
        imgRow.addView(flatButton("清除图片", new View.OnClickListener() {
            @Override public void onClick(View v) {
                writeLive(ConfigContract.Param.GLASS_FILL_IMAGE, "");
                rebuild();
                toast("填充图片已清除");
            }
        }));
        card.addView(imgRow, wrap());
        card.addView(text("填充图片：" + fillImageLabel(), 11f, C_MUTED, dp(6)));

        card.addView(fillAlphaSlider("图片不透明度", ConfigContract.Param.GLASS_FILL_IMAGE_ALPHA));
        card.addView(fillIntSlider("图片最大边", ConfigContract.Param.GLASS_FILL_IMAGE_MAX_DP,
                64, 1024, 512, "dp"));
        card.addView(fillChoice("图片缩放", ConfigContract.Param.GLASS_FILL_IMAGE_FIT,
                new String[]{"cover", "fit"}, new String[]{"铺满裁切", "完整显示"}));
        return card;
    }

    private String fillColorLabel() {
        String v = stored(ConfigContract.Param.GLASS_FILL_COLOR);
        return (v == null || v.trim().length() == 0)
                ? "填充颜色：默认" : "填充颜色：" + v.trim();
    }

    private String fillImageLabel() {
        String v = stored(ConfigContract.Param.GLASS_FILL_IMAGE);
        return (v == null || v.trim().length() == 0) ? "无" : v.trim();
    }

    /** 取色：内置常用色板 + 手工输入 #RRGGBB，避免依赖系统取色 Activity。 */
    private void showColorDialog(final TextView badge, final ConfigContract.Param p) {
        final String[] palette = {"", "#FFFFFF", "#000000", "#FF5252", "#4CAF50",
                "#2196F3", "#FFC107", "#9C27B0", "#00BCD4"};
        new android.app.AlertDialog.Builder(this)
                .setTitle("填充颜色")
                .setItems(new String[]{"默认(沿用系统)", "白", "黑", "红", "绿",
                                "蓝", "黄", "紫", "青", "手工输入 #RRGGBB"},
                        new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int which) {
                                if (which == palette.length) {
                                    promptColor(badge, p);
                                } else {
                                    writeLive(p, palette[which]);
                                    badge.setText(which == 0 ? "填充颜色：默认"
                                            : "填充颜色：" + palette[which]);
                                }
                            }
                        })
                .show();
    }

    private void promptColor(final TextView badge, final ConfigContract.Param p) {
        final android.widget.EditText et = new android.widget.EditText(this);
        et.setHint("#RRGGBB 或 #AARRGGBB");
        et.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        new android.app.AlertDialog.Builder(this)
                .setTitle("手工输入填充颜色")
                .setView(et)
                .setPositiveButton(android.R.string.ok,
                        new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int w) {
                                String v = et.getText().toString().trim();
                                if (!v.startsWith("#")) v = "#" + v;
                                writeLive(p, v);
                                badge.setText("填充颜色：" + v);
                            }
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 0..1 不透明度滑块,显示百分比;-1 视为默认(100%)。 */
    private View fillAlphaSlider(String title, final ConfigContract.Param p) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(8), 0, 0);
        final TextView badge = text(fillAlphaLabel(p), 12f, C_ACCENT, 0);
        box.addView(headerRow(title, badge), wrap());
        final android.widget.SeekBar seek = new android.widget.SeekBar(this);
        seek.setMax(100);
        float v = storedFloat(p, -1f);
        seek.setProgress(Math.round((v < 0f ? 1f : v) * 100f));
        seek.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar s, int value,
                                                     boolean fromUser) {
                if (!fromUser || building) return;
                writeLive(p, String.format(java.util.Locale.US, "%.3f", value / 100f));
                badge.setText(value + "%");
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar s) { }
            @Override public void onStopTrackingTouch(android.widget.SeekBar s) { }
        });
        box.addView(seek, wrap());
        return box;
    }

    private String fillAlphaLabel(ConfigContract.Param p) {
        float v = storedFloat(p, -1f);
        return (v < 0f ? 100 : Math.round(v * 100)) + "%";
    }

    private View fillIntSlider(String title, final ConfigContract.Param p,
                               final int min, final int max, final int def,
                               final String unit) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(8), 0, 0);
        final TextView badge = text(fillIntLabel(p, def, unit), 12f, C_ACCENT, 0);
        box.addView(headerRow(title, badge), wrap());
        final android.widget.SeekBar seek = new android.widget.SeekBar(this);
        seek.setMax(max - min);
        seek.setProgress(clampInt(stored(p), min, max) - min);
        seek.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar s, int value,
                                                     boolean fromUser) {
                if (!fromUser || building) return;
                int actual = min + value;
                writeLive(p, String.valueOf(actual));
                badge.setText(actual + unit);
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar s) { }
            @Override public void onStopTrackingTouch(android.widget.SeekBar s) { }
        });
        box.addView(seek, wrap());
        return box;
    }

    private String fillIntLabel(ConfigContract.Param p, int def, String unit) {
        return clampInt(stored(p), 64, 1024) + unit;
    }

    private View fillChoice(String title, final ConfigContract.Param p,
                            final String[] values, final String[] labels) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setPadding(0, dp(10), 0, 0);
        box.addView(text(title, 12f, C_SUB, 0), new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        final TextView badge = text(fillChoiceLabel(p, values, labels), 12f, C_ACCENT, 0);
        badge.setBackground(roundRect(C_ACCENT_SOFT, 8, 0, 0));
        badge.setPadding(dp(8), dp(2), dp(8), dp(2));
        badge.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String cur = stored(p);
                int idx = 0;
                for (int i = 0; i < values.length; i++) {
                    if (values[i].equalsIgnoreCase(cur == null ? "" : cur.trim())) idx = i;
                }
                int next = (idx + 1) % values.length;
                writeLive(p, values[next]);
                badge.setText(labels[next]);
            }
        });
        box.addView(badge);
        return box;
    }

    private String fillChoiceLabel(ConfigContract.Param p, String[] values, String[] labels) {
        String cur = stored(p);
        for (int i = 0; i < values.length; i++) {
            if (values[i].equalsIgnoreCase(cur == null ? "" : cur.trim())) return labels[i];
        }
        return labels[0];
    }

    private View headerRow(String title, TextView badge) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(text(title, 13f, C_TEXT, 0), new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(badge);
        return row;
    }

    private float storedFloat(ConfigContract.Param p, float fallback) {
        String v = stored(p);
        if (v == null || v.trim().length() == 0) return fallback;
        try {
            return Float.parseFloat(v.trim());
        } catch (Throwable t) {
            return fallback;
        }
    }

    /**
     * p171: 只读"当前状态"卡——把渲染链真实读到的开关值展示出来,让用户能
     * 一眼确认改动是否生效(而非靠猜)。全部读 ConfigStore 中的当前值,零写入、
     * 零渲染参数改动、零 hook 触碰。
     */
    private View statusCard() {
        LinearLayout card = card();
        card.addView(sectionHeader("当前状态  ·  只读"));
        card.addView(statusLine("构建版本", GlassEntry.BUILD));
        card.addView(statusLine("液态玻璃",
                storedBool(ConfigContract.Param.GLASS_ENABLED) ? "已开启" : "已关闭"));
        card.addView(statusLine("背景类型", bgKindLabel()));
        card.addView(statusLine("诊断日志",
                storedBool(ConfigContract.Param.PERF_DEBUG_LOG) ? "开" : "关"));
        card.addView(text("以上为模块当前读取到的实际值；改动在对应开关下方即时生效。",
                11f, C_MUTED, dp(8)));
        return cardWithMargin(card);
    }

    private View statusLine(String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView l = text(label, 13f, C_SUB, dp(6));
        l.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView v = text(value, 13f, C_TEXT, dp(6));
        v.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(l);
        row.addView(v);
        return row;
    }

    private boolean storedBool(ConfigContract.Param p) {
        return "true".equalsIgnoreCase(stored(p));
    }

    private String bgKindLabel() {
        String k = stored(ConfigContract.Param.BG_KIND);
        if ("video".equals(k)) return "视频";
        if ("none".equals(k)) return "无背景";
        return "图片";
    }

    /**
     * 0.5.0-p172: 玻璃高级设置折叠区。默认全部引擎原始值(-1),用户拖动
     * 才写入 0.00~1.00 的比例;点数值徽标恢复默认(-1)。仅 L2/L3/外进程
     * 生效,L1 在 resolve() 硬门下恒读 p30 值。
     */
    private View advancedGlassSection() {
        final TextView head = text("高级设置  ·  玻璃几何与质感   ›", 13f, C_ACCENT, dp(10));
        final LinearLayout adv = new LinearLayout(this);
        adv.setOrientation(LinearLayout.VERTICAL);
        adv.setVisibility(View.GONE);
        adv.addView(text("默认跟随引擎原始值；拖动微调，点数值恢复默认。仅非 L1 页面生效。",
                11f, C_MUTED, dp(8)));
        adv.addView(tunableSlider("圆角", "玻璃卡圆角大小",
                ConfigContract.Param.GEO_RADIUS_DP, 18f, 48f));
        adv.addView(tunableSlider("左右内缩", "玻璃左右留边",
                ConfigContract.Param.GEO_H_INSET_DP, 14f, 40f));
        adv.addView(tunableSlider("上下内缩", "玻璃上下留边",
                ConfigContract.Param.GEO_V_INSET_DP, 4f, 24f));
        adv.addView(divider());
        adv.addView(tunableSlider("通透度", "越高越不透明，越低越清透",
                ConfigContract.Param.GLASS_TRANSPARENCY, 0f, 1f));
        adv.addView(tunableSlider("模糊", "背景模糊强度",
                ConfigContract.Param.GLASS_BLUR_RADIUS, 0f, 16f));
        adv.addView(tunableSlider("折射", "边缘折射高度",
                ConfigContract.Param.GLASS_REFRACTION, 0f, 40f));
        adv.addView(tunableSlider("厚度", "折射厚度，负值为外凸",
                ConfigContract.Param.GLASS_THICKNESS, -60f, 0f));
        adv.addView(tunableSlider("色散", "折射色散强度",
                ConfigContract.Param.GLASS_DISPERSION, 0f, 1f));
        head.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean open = adv.getVisibility() != View.VISIBLE;
                adv.setVisibility(open ? View.VISIBLE : View.GONE);
                head.setText(open ? "高级设置  ·  玻璃几何与质感   ‹"
                                  : "高级设置  ·  玻璃几何与质感   ›");
            }
        });
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(head);
        wrap.addView(adv);
        return wrap;
    }

    /**
     * p172: 质感/几何滑块。配置值存"实际值"(如 24.0 或 0.40),-1=默认。
     * 滑块按 actualMin..actualMax 线性映射到 0..1000 整数;点徽标写 -1 复位。
     */
    private View tunableSlider(final String title, final String sub,
                                final ConfigContract.Param p,
                                final float actualMin, final float actualMax) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(8), 0, dp(2));
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.addView(text(title, 13f, C_TEXT, 0), new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        final TextView badge = text(tunableLabel(p, actualMin, actualMax),
                12f, C_ACCENT, 0);
        badge.setBackground(roundRect(C_ACCENT_SOFT, 8, 0, 0));
        badge.setPadding(dp(8), dp(2), dp(8), dp(2));
        badge.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                writeLive(p, "-1");
                badge.setText("默认");
            }
        });
        head.addView(badge, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        box.addView(head, wrap());
        box.addView(text(sub, 11f, C_MUTED, dp(2)));

        final android.widget.SeekBar seek = new android.widget.SeekBar(this);
        float cur = tunableValue(p, actualMin, actualMax);
        int progress = cur < 0f ? 0
                : Math.round((cur - actualMin) / (actualMax - actualMin) * 1000f);
        seek.setMax(1000);
        seek.setProgress(progress);
        seek.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar s, int value, boolean fromUser) {
                if (!fromUser || building) return;
                float actual = actualMin + (actualMax - actualMin) * value / 1000f;
                writeLive(p, String.format(java.util.Locale.US, "%.3f", actual));
                badge.setText(String.format(java.util.Locale.US, "%.2f", actual));
            }
            @Override
            public void onStartTrackingTouch(android.widget.SeekBar s) { }
            @Override
            public void onStopTrackingTouch(android.widget.SeekBar s) { }
        });
        box.addView(seek, wrap());
        return box;
    }

    private float tunableValue(ConfigContract.Param p, float min, float max) {
        String v = stored(p);
        try {
            float f = Float.parseFloat(v);
            return f < 0f ? -1f : f;
        } catch (Throwable t) {
            return -1f;
        }
    }

    private String tunableLabel(ConfigContract.Param p, float min, float max) {
        float v = tunableValue(p, min, max);
        return v < 0f ? "默认" : String.format(java.util.Locale.US, "%.2f", v);
    }

    /**
     * The ONE main card. "自定义" is not one option among six any more: it is
     * the only filling model, driven by the position + zoom sliders.
     *
     * 0.5.0-p69: TWO position sliders. BG_SCALE is pinned to custom by
     * ensureCustomFilling(); FOCUS_X / FOCUS_Y / ZOOM are all USER values.
     */
    private View backgroundCard() {
        LinearLayout card = card();
        card.addView(sectionHeader("背景 · 自定义"));
        card.addView(text("图片与视频共用同一套变换；改动即时写入，无需重启。",
                11f, C_MUTED, dp(2)));
        card.addView(text("「自定义背景」总开关已移到上方外观分组顶部。",
                11f, C_MUTED, dp(3)));
        card.addView(divider());

        card.addView(text("背景类型", 14f, C_TEXT, dp(2)));
        card.addView(segment(
                new String[]{"图片", "视频", "无背景"},
                new String[]{"image", "video", "none"},
                ConfigContract.Param.BG_KIND));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.addView(action("选择图片", MODE_IMAGE));
        actions.addView(action("选择视频", MODE_VIDEO));
        actions.addView(action("随机池", MODE_POOL));
        card.addView(actions, wrap());
        card.addView(text(mediaSummary(), 11f, C_MUTED, dp(6)));

        card.addView(divider());
        card.addView(sliderBlock("上下位置", "0 显示画面顶部 · 100 显示画面底部",
                ConfigContract.Param.BG_SCALE_FOCUS_Y, 0, 100));
        // 0.5.0-p69: the SECOND position slider the user asked for. Writes
        // BG_SCALE_FOCUS_X, consumed by ScaleMode.focusXOf() -> matrix() for
        // the image, the video transform AND the baked video plate.
        card.addView(sliderBlock("左右位置", "0 显示画面左侧 · 100 显示画面右侧",
                ConfigContract.Param.BG_SCALE_FOCUS_X, 0, 100));
        // p68 control, deliberately UNCHANGED: "拉伸大小可以不改了".
        card.addView(sliderBlock("拉伸大小", "等比缩放，100% 与默认铺满一致；调小会露出边缘",
                ConfigContract.Param.BG_SCALE_ZOOM, 50, 400));

        return cardWithMargin(card);
    }

    /**
     * Everything not about the background lives here, collapsed. This is why
     * the main screen has no visible 液态玻璃 settings while the p66 live
     * GLASS_ENABLED toggle remains reachable (the user asked for that toggle
     * to be instant one release earlier).
     */
    private View moreCard() {
        LinearLayout card = card();
        // p171: iOS 分组头——标题左、展开指示 ‹/› 右,点整行展开(此前只有
        // 纯文字变色,指示方向与展开态一致性弱)。
        final TextView head = text("更多设置      ›", 14f, C_ACCENT, dp(4));
        head.setPadding(0, dp(2), 0, dp(2));
        card.addView(head);

        final LinearLayout more = new LinearLayout(this);
        more.setOrientation(LinearLayout.VERTICAL);
        more.setVisibility(View.GONE);
        more.addView(text("总开关已移到外观分组顶部。", 11f, C_MUTED, dp(4)));
        // 0.5.0-p172: 玻璃开关下方新增「高级设置」折叠区。8 个滑块对应
        // 3 几何(圆角/左右内缩/上下内缩) + 5 质感(通透度/模糊/折射/厚度/
        // 色散)。默认全部 -1=引擎原始值;点数值徽标可恢复默认。
        // 0.5.0-p173: 用户选 8 项全部统一,这 8 个值现在对所有页面(L1/L2/L3/
        // 外进程)一并生效,p172 的 L1 硬门已撤除。
        more.addView(advancedGlassSection());
        more.addView(divider());

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.addView(flatButton("统一全部页面", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ConfigStore.clearOverrides(SettingsActivity.this);
                // clearOverrides rewrites every non-GLOBAL slot to defaults;
                // put the glass recipe and the custom filling back afterwards.
                ConfigStore.restoreGlassDefaults(SettingsActivity.this);
                ConfigStore.ensureCustomFilling(SettingsActivity.this);
                rebuild();
                toast("所有页面已统一为全局设置");
            }
        }));
        actions.addView(flatButton("诊断信息", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                diagnostics();
            }
        }));
        more.addView(actions, wrap());
        card.addView(more);

        head.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean open = more.getVisibility() != View.VISIBLE;
                more.setVisibility(open ? View.VISIBLE : View.GONE);
                head.setText(open ? "更多设置      ‹" : "更多设置      ›");
            }
        });
        return cardWithMargin(card);
    }

    /** Standalone iOS group: read-only entry into the element browser. */
    private View elementCard() {
        LinearLayout card = card();
        card.addView(sectionHeader("界面元素 · 视觉编辑"));
        card.addView(text("点亮屏幕上的任意一块，一眼看懂它是什么、怎么调；本页只读。",
                11f, C_MUTED, dp(2)));

        TextView entry = text("打开视觉编辑模式  ›", 15f, C_ACCENT, 0);
        entry.setBackground(roundRect(C_CHIP, 10, 0, 0));
        entry.setPadding(dp(12), dp(12), dp(12), dp(12));
        entry.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openElementBrowser();
            }
        });
        card.addView(entry, wrap());
        return cardWithMargin(card);
    }

    private View footerNote() {
        // p171: 文案与实际行为对齐——GLASS_ENABLED 是总开关,质感参数确实是
        // 0.5.0-p173: 8 项高级参数已对所有页面统一生效,文案同步。
        return text("MagicOS11GlassForSetting " + GlassEntry.BUILD
                        + "  ·  所有页面统一"
                        + "\n液态玻璃为总开关；高级参数全局同步",
                11f, C_MUTED, dp(18));
    }

    // ---- modern controls ----------------------------------------------------

    /** iOS UISwitch: 51x31 track, 27dp white thumb with a soft shadow. */
    private View switchRow(String title, String sub,
                           final ConfigContract.Param p) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(9), 0, dp(9));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.addView(text(title, 15f, C_TEXT, 0));
        if (sub != null && sub.length() > 0) {
            left.addView(text(sub, 11f, C_MUTED, dp(3)));
        }
        row.addView(left, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        final boolean on = Boolean.parseBoolean(stored(p));
        final FrameLayout sw = new FrameLayout(this);
        sw.setLayoutParams(new LinearLayout.LayoutParams(dp(51), dp(31)));
        paintSwitch(sw, null, null, on, false);

        final View shadow = new View(this);
        shadow.setBackground(roundRect(C_SHADOW, 999, 0, 0));
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(dp(30), dp(30));
        slp.gravity = Gravity.CENTER_VERTICAL;
        shadow.setLayoutParams(slp);
        sw.addView(shadow);

        final View thumb = new View(this);
        thumb.setBackground(roundRect(C_WHITE, 999, dp(1), C_LINE));
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(dp(27), dp(27));
        tlp.gravity = Gravity.CENTER_VERTICAL;
        thumb.setLayoutParams(tlp);
        sw.addView(thumb);
        paintSwitch(sw, shadow, thumb, on, true);

        sw.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean next = !Boolean.parseBoolean(stored(p));
                paintSwitch(sw, shadow, thumb, next, true);
                writeLive(p, String.valueOf(next));
            }
        });
        row.addView(sw);
        return row;
    }

    private void paintSwitch(FrameLayout sw, View shadow, View thumb,
                             boolean on, boolean withThumb) {
        sw.setBackground(roundRect(on ? C_GREEN : C_TRACK, 16, 0, 0));
        if (!withThumb) return;
        FrameLayout.LayoutParams slp = (FrameLayout.LayoutParams) shadow.getLayoutParams();
        slp.setMargins(on ? dp(21) : dp(1), 0, 0, 0);
        shadow.setLayoutParams(slp);
        FrameLayout.LayoutParams tlp = (FrameLayout.LayoutParams) thumb.getLayoutParams();
        tlp.setMargins(on ? dp(22) : dp(2), 0, 0, 0);
        thumb.setLayoutParams(tlp);
    }

    /** iOS UISegmentedControl: grey track, white selected cell. */
    private View segment(final String[] labels, final String[] values,
                         final ConfigContract.Param p) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackground(roundRect(C_TRACK, 9, 0, 0));
        bar.setPadding(dp(2), dp(2), dp(2), dp(2));

        final TextView[] cells = new TextView[labels.length];
        String cur = stored(p);
        int active = 0;
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView t = new TextView(this);
            t.setText(labels[i]);
            t.setTextSize(13f);
            t.setGravity(android.view.Gravity.CENTER);
            t.setPadding(dp(6), dp(7), dp(6), dp(7));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            bar.addView(t, lp);
            cells[i] = t;
            if (values[i].equals(cur)) active = i;
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    paintSegment(cells, index);
                    writeLive(p, values[index]);
                }
            });
        }
        paintSegment(cells, active);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(10), 0, 0);
        bar.setLayoutParams(lp);
        return bar;
    }

    private void paintSegment(TextView[] cells, int active) {
        for (int i = 0; i < cells.length; i++) {
            boolean on = i == active;
            cells[i].setBackground(on ? roundRect(C_WHITE, 7, 0, 0) : null);
            cells[i].setTextColor(on ? C_TEXT : C_SUB);
        }
    }

    /**
     * iOS-style slider: thin grey track, blue fill, white round thumb with a
     * soft shadow. A transparent native SeekBar stays on top so the touch and
     * write logic are the ORIGINAL ones, byte-for-byte.
     */
    private View sliderBlock(String title, String sub,
                             final ConfigContract.Param p,
                             final int min, final int max) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(10), 0, dp(6));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.addView(text(title, 15f, C_TEXT, 0), new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        final TextView badge = text(clampInt(stored(p), min, max) + "%",
                13f, C_ACCENT, 0);
        badge.setBackground(roundRect(C_ACCENT_SOFT, 8, 0, 0));
        badge.setPadding(dp(9), dp(3), dp(9), dp(3));
        head.addView(badge, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        box.addView(head, wrap());
        box.addView(text(sub, 11f, C_MUTED, dp(3)));

        final FrameLayout bar = new FrameLayout(this);
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(32)));

        final View track = new View(this);
        track.setBackground(roundRect(C_LINE, 3, 0, 0));
        FrameLayout.LayoutParams trLp = new FrameLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(4));
        trLp.gravity = Gravity.CENTER_VERTICAL;
        track.setLayoutParams(trLp);
        bar.addView(track);

        final View fill = new View(this);
        fill.setBackground(roundRect(C_ACCENT, 3, 0, 0));
        FrameLayout.LayoutParams flLp = new FrameLayout.LayoutParams(0, dp(4));
        flLp.gravity = Gravity.CENTER_VERTICAL;
        fill.setLayoutParams(flLp);
        bar.addView(fill);

        final View thumbShadow = new View(this);
        thumbShadow.setBackground(roundRect(C_SHADOW, 999, 0, 0));
        FrameLayout.LayoutParams shLp = new FrameLayout.LayoutParams(dp(30), dp(30));
        shLp.gravity = Gravity.CENTER_VERTICAL;
        thumbShadow.setLayoutParams(shLp);
        bar.addView(thumbShadow);

        final View thumb = new View(this);
        thumb.setBackground(roundRect(C_WHITE, 999, dp(1), C_LINE));
        FrameLayout.LayoutParams thLp = new FrameLayout.LayoutParams(dp(26), dp(26));
        thLp.gravity = Gravity.CENTER_VERTICAL;
        thumb.setLayoutParams(thLp);
        bar.addView(thumb);

        final SeekBar seek = new SeekBar(this);
        seek.setAlpha(0f);
        seek.setMax(Math.max(1, max - min));
        seek.setProgress(clampInt(stored(p), min, max) - min);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress,
                                          boolean fromUser) {
                if (building || !fromUser) return;
                String v = String.valueOf(min + progress);
                badge.setText(v + "%");
                writeLive(p, v);
                updateSlider(fill, thumbShadow, thumb, seek);
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
            }
        });
        bar.addView(seek, new FrameLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT));
        box.addView(bar);

        // Position the custom thumb/fill once the bar has a real width; guard
        // on width change so our own setLayoutParams cannot loop.
        final int[] lastWidth = {-1};
        bar.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b,
                                       int ol, int ot, int or, int ob) {
                int w = seek.getWidth();
                if (w > 0 && w != lastWidth[0]) {
                    lastWidth[0] = w;
                    updateSlider(fill, thumbShadow, thumb, seek);
                }
            }
        });
        return box;
    }

    private void updateSlider(View fill, View shadow, View thumb, SeekBar seek) {
        int w = seek.getWidth();
        if (w <= 0) return;
        int range = Math.max(1, seek.getMax());
        float ratio = (float) seek.getProgress() / range;
        if (ratio < 0f) ratio = 0f;
        if (ratio > 1f) ratio = 1f;
        int travel = w - dp(26);
        int cx = Math.round(travel * ratio);

        FrameLayout.LayoutParams fl = (FrameLayout.LayoutParams) fill.getLayoutParams();
        fl.width = cx + dp(13);
        fill.setLayoutParams(fl);

        FrameLayout.LayoutParams sl = (FrameLayout.LayoutParams) shadow.getLayoutParams();
        sl.setMargins(cx - dp(2), 0, 0, 0);
        shadow.setLayoutParams(sl);

        FrameLayout.LayoutParams tl = (FrameLayout.LayoutParams) thumb.getLayoutParams();
        tl.setMargins(cx, 0, 0, 0);
        thumb.setLayoutParams(tl);
    }

    /** Immediate write + the same 120 ms coalesced broadcast as every control. */
    private void writeLive(ConfigContract.Param p, String value) {
        ConfigStore.write(this, slot, p, value, false, false);
        pending = true;
        ui.removeCallbacks(push);
        ui.postDelayed(push, 120L);
    }

    private void writeNow(ConfigContract.Param p, String value) {
        ConfigStore.write(this, slot, p, value, false, false);
    }

    private String stored(ConfigContract.Param p) {
        Map<String, String> all = ConfigStore.all(this);
        String v = all.get(ConfigContract.key(slot, p));
        if (v == null) v = all.get(ConfigContract.key(ConfigContract.Slot.GLOBAL, p));
        return v == null ? p.def : v;
    }

    /** 当前全局 el.rules 规则串（缺失按空串），供字体编辑页读取已有值。 */
    private String elRules() {
        Map<String, String> all = ConfigStore.all(this);
        String v = all.get(ConfigContract.key(
                ConfigContract.Slot.GLOBAL, ConfigContract.Param.EL_RULES));
        return v == null ? "" : v;
    }

    /** 按 global_id 写一条字体规则，复用 ConfigStore 现有广播即时生效链。 */
    private void writeElRule(String gid, String dim, String value) {
        ConfigStore.writeElRule(this, ConfigContract.Slot.GLOBAL, gid, dim, value);
    }

    // ---- media --------------------------------------------------------------

    private Button action(final String name, final int mode) {
        return capsule(name, C_ACCENT_SOFT, C_ACCENT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pick(mode);
            }
        });
    }

    private Button flatButton(String name, View.OnClickListener l) {
        return capsule(name, C_CHIP, C_TEXT, l);
    }

    private Button capsule(String name, int bg, int fg, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(name);
        b.setTextSize(12f);
        b.setTextColor(fg);
        b.setAllCaps(false);
        b.setBackground(roundRect(bg, 10, 0, 0));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(3), dp(10), dp(3), dp(2));
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }

    private void pick(int mode) {
        mediaMode = mode;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(mode == MODE_VIDEO ? "video/*" : "image/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        i.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(Intent.createChooser(i, "选择背景文件"), REQ_MEDIA);
        } catch (Throwable t) {
            toast("没有可用的文件选择器");
        }
    }

    /** 0.5.0-p175: pick an IMAGE used as the glass card's inner fill. */
    private void pickFillImage() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        i.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(Intent.createChooser(i, "选择填充图片"), REQ_FILL_IMAGE);
        } catch (Throwable t) {
            toast("没有可用的文件选择器");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode == REQ_ICON_SWAP) {
            Uri iconUri = data == null ? null : data.getData();
            if (iconUri == null) return;
            try {
                getContentResolver().takePersistableUriPermission(iconUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Throwable ignored) {
            }
            // 0.5.0-p189: 先进裁剪页（拖动/缩放选区域），裁剪完成再写规则。
            try {
                Intent crop = new Intent(this, IconCropActivity.class);
                crop.setData(iconUri);
                crop.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivityForResult(crop, REQ_ICON_CROP);
            } catch (Throwable t) {
                toast("无法打开裁剪页");
            }
            return;
        }
        if (requestCode == REQ_ICON_CROP) {
            if (data == null) return;
            String iconName = data.getStringExtra(IconCropActivity.EXTRA_RESULT_MEDIA);
            if (iconName == null || iconName.length() == 0) return;
            IconSwapCache.load(this, iconName);
            writeIconRule(iconKey, iconName);
            ui.removeCallbacks(push);
            ui.postDelayed(push, 60L);
            rebuild();
            toast("已替换 " + iconKey + " 的图标，立即生效");
            return;
        }
        if (requestCode == REQ_FILL_IMAGE) {
            Uri fillUri = data.getData();
            if (fillUri == null) return;
            try {
                getContentResolver().takePersistableUriPermission(fillUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Throwable ignored) {
            }
            String fillName = ConfigStore.importMedia(this, fillUri, "image");
            if (fillName == null) {
                toast("填充图片导入失败");
                return;
            }
            writeNow(ConfigContract.Param.GLASS_FILL_IMAGE, fillName);
            ui.removeCallbacks(push);
            ui.postDelayed(push, 60L);
            rebuild();
            toast("填充图片已应用，立即生效");
            return;
        }
        if (requestCode != REQ_MEDIA) return;
        Uri uri = data.getData();
        if (uri == null) return;
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Throwable ignored) {
        }
        String pickedKind = mediaMode == MODE_VIDEO ? "video" : "image";
        String name = ConfigStore.importMedia(this, uri, pickedKind);
        if (name == null) {
            toast("导入失败");
            return;
        }
        // 0.5.0-p77: sample ONE average colour from the freshly imported
        // media so the next cold start opens on the wallpaper's own colour
        // instead of the fixed near-white base. One 16 px decode / one video
        // frame, only here, never in a render frame.
        ConfigStore.onMediaPicked(this, name, pickedKind);
        if (mediaMode == MODE_POOL) {
            String old = stored(ConfigContract.Param.BG_RANDOM_POOL);
            String pool = old == null || old.trim().length() == 0
                    ? name : old.trim() + ";" + name;
            writeNow(ConfigContract.Param.BG_RANDOM_POOL, pool);
            writeNow(ConfigContract.Param.BG_RANDOM_ENABLED, "true");
            toast("已加入随机池，立即生效");
        } else {
            writeNow(ConfigContract.Param.BG_SOURCE, name);
            writeNow(ConfigContract.Param.BG_KIND,
                    mediaMode == MODE_VIDEO ? "video" : "image");
            writeNow(ConfigContract.Param.BG_ENABLED, "true");
            toast("已应用 " + name + "，立即生效");
        }
        ui.removeCallbacks(push);
        ui.postDelayed(push, 60L);
        rebuild();
    }

    private String mediaSummary() {
        String src = stored(ConfigContract.Param.BG_SOURCE);
        String pool = stored(ConfigContract.Param.BG_RANDOM_POOL);
        int n = pool == null || pool.trim().length() == 0
                ? 0 : pool.trim().split(";").length;
        return "当前文件：" + (src == null || src.length() == 0 ? "未设置" : src)
                + "   ·   随机池 " + n + " 张";
    }

    // ---- element browser → visual editor (read-only) -----------------------

    // 一句人话解释每个编号「是什么、改了会怎样」。用户反馈原编号列表"不好理解"，
    // 所以这里不照抄 ElementRegistry 的 anchor 术语，先给大白话，技术锚点放到卡片末尾小字。
    private static final String[] VISUAL_PLAIN = {
        "屏幕上最上面那行字（如 WLAN）。改它 = 改这行字的颜色/大小。",
        "顶栏那条会随滚动渐变透明的层。系统保留，清掉就没渐隐了。",
        "所有卡片后面那整块。改它 = 换整页壁纸/视频/底色，也就是你一直在调的背景。",
        "每一行设置项背后的白色圆角块。改它 = 让白卡变透明/换颜色，玻璃效果就露出来。",
        "“已连接 WLAN”这种小标题行。改它 = 同时改这行的底和字。",
        "每一行左边的大字（如“更多设置”）。改它 = 改字体颜色/大小/粗细。",
        "主标题下面那行小灰字。改法和⑥一样。",
        "左边的图标、右边的箭头和开关。系统保留，透明化时会误伤头像/昵称/开关状态。",
        "热点列表这种运行时生成的行。按容器 key 批量改，不按第几行写死。",
        "行与行之间的细线、按压时的颜色。可选调节。"
    };
    private static final String[] VISUAL_HOW = {
        "改这里 → 顶栏标题文字一起变",
        "",
        "改这里 → 整页背景一起换（就是上方背景设置调的那块）",
        "改这里 → 整页同类的白卡一起变透明",
        "改这里 → 所有分组标题行的底和字一起变",
        "改这里 → 所有行的主标题字体一起变",
        "改这里 → 所有行的摘要字体一起变",
        "",
        "改这里 → 动态列表容器内的行一起变",
        "改这里 → 分隔线与按压态颜色一起变"
    };
    private static final String[] VISUAL_KEEP_REASON = {
        "",
        "清掉会丢滚动渐隐；这是系统渐变模糊层，不是普通白卡。",
        "", "", "", "", "",
        "透明化会误伤头像/昵称/开关状态。",
        "", ""
    };

    private static String plainWords(int no) {
        return no >= 1 && no <= VISUAL_PLAIN.length ? VISUAL_PLAIN[no - 1] : "";
    }

    private static String howTo(int no) {
        return no >= 1 && no <= VISUAL_HOW.length ? VISUAL_HOW[no - 1] : "";
    }

    private static String keepReason(int no) {
        return no >= 1 && no <= VISUAL_KEEP_REASON.length ? VISUAL_KEEP_REASON[no - 1] : "";
    }

    /**
     * Replace body with a read-only visual editor: a self-drawn phone screen
     * with ①~⑩ at their real positions. No config value is touched, no hook.
     */
    private void openElementBrowser() {
        body.removeAllViews();

        TextView back = text("‹ 返回设置", 14f, C_ACCENT, 0);
        back.setPadding(dp(2), dp(2), dp(2), dp(10));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                rebuild();
            }
        });
        body.addView(back);

        body.addView(text("界面元素 · 视觉编辑", 24f, C_TEXT, 0));
        body.addView(text("点亮屏幕上的任意一块，看它对应什么、怎么调。深色=可编辑，灰色=保留项。",
                12f, C_SUB, dp(6)));

        final LinearLayout screenList = new LinearLayout(this);
        screenList.setOrientation(LinearLayout.VERTICAL);
        final LinearLayout detailHost = new LinearLayout(this);
        detailHost.setOrientation(LinearLayout.VERTICAL);

        // ---- 第一级：实机设置 L1（navL1，横向滚动） ----
        final String[] l1Rows = ElementRegistry.navL1();
        final TextView[] l1Chips = new TextView[l1Rows.length];
        LinearLayout l1Strip = new LinearLayout(this);
        l1Strip.setOrientation(LinearLayout.HORIZONTAL);
        l1Strip.setPadding(dp(3), dp(6), dp(3), dp(6));
        for (int i = 0; i < l1Rows.length; i++) {
            final int index = i;
            TextView chip = text(rowLabel(l1Rows[i]), 12f, C_SUB, 0);
            chip.setPadding(dp(12), dp(8), dp(12), dp(8));
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    for (int j = 0; j < l1Chips.length; j++) {
                        paintL1Chip(l1Chips[j], j == index);
                    }
                    visualDomain = rowKey(l1Rows[index]);
                    String[] l2 = ElementRegistry.navL2(visualDomain);
                    visualScreen = l2.length > 0 ? rowKey(l2[0]) : "";
                    renderScreens(screenList, detailHost, visualDomain);
                    renderVisualDetail(detailHost, visualSelected);
                }
            });
            l1Strip.addView(chip, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            l1Chips[i] = chip;
        }
        HorizontalScrollView l1Scroll = new HorizontalScrollView(this);
        l1Scroll.setBackgroundColor(C_CARD);
        l1Scroll.addView(l1Strip);
        body.addView(sectionHeader("设置分类  ·  对应实机 L1"));
        body.addView(l1Scroll, wrap());

        // ---- 第二级：选中 L1 下的界面项（navL2） ----
        body.addView(sectionHeader("界面  ·  点选进入该页的视觉编辑"));
        ScrollView screenScroll = new ScrollView(this);
        screenScroll.addView(screenList, wrap());
        screenScroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(180)));
        body.addView(screenScroll);

        body.addView(sectionHeader("屏幕结构  ·  点选任意一块"));
        buildVisualWireframe(body, detailHost);

        body.addView(sectionHeader("这一块是什么"));
        body.addView(detailHost, wrap());
        body.addView(text("本页是可视化说明，不改任何配置值；实际调节走上方背景设置。",
                11f, C_MUTED, dp(10)));

        if (l1Rows.length > 0) {
            paintL1Chip(l1Chips[0], true);
            visualDomain = rowKey(l1Rows[0]);
            String[] l2 = ElementRegistry.navL2(visualDomain);
            visualScreen = l2.length > 0 ? rowKey(l2[0]) : "";
            renderScreens(screenList, detailHost, visualDomain);
        }
        renderVisualDetail(detailHost, visualSelected);
    }

    /** 选中的 L1 chip：iOS 蓝底白字。 */
    private void paintL1Chip(TextView chip, boolean on) {
        chip.setBackground(on ? roundRect(C_ACCENT, 8, 0, 0) : null);
        chip.setTextColor(on ? C_WHITE : C_SUB);
    }

    /** 取 "key|显示名" 行的 key 段（L1 原始 name / L2 screen）。 */
    private static String rowKey(String row) {
        if (row == null) return "";
        int p = row.indexOf('|');
        return p > 0 ? row.substring(0, p) : row;
    }

    /** 取 "key|显示名" 行的显示名段。 */
    private static String rowLabel(String row) {
        if (row == null) return "";
        int p = row.indexOf('|');
        return p > 0 ? row.substring(p + 1) : row;
    }

    /** 反查 screen 所属技术域（SCREEN_INDEX 首字段）；未收录时用 "l1" 通用前缀。 */
    private static String domainForScreen(String screen) {
        if (screen == null) return "l1";
        String s = screen.trim();
        for (String row : ElementRegistry.SCREEN_INDEX) {
            int p1 = row.indexOf('|');
            int p2 = row.indexOf('|', p1 + 1);
            if (p1 > 0 && p2 > p1 && row.substring(p1 + 1, p2).equalsIgnoreCase(s)) {
                return row.substring(0, p1);
            }
        }
        return "l1";
    }

    private void renderScreens(LinearLayout screenList, final LinearLayout detailHost,
                               final String l1OriginalName) {
        screenList.removeAllViews();
        String[] l2 = ElementRegistry.navL2(l1OriginalName);
        if (l2 == null || l2.length == 0) {
            screenList.addView(text("（该分类暂无界面）", 12f, C_MUTED, 0));
            return;
        }
        for (String entry : l2) {
            final String screen = rowKey(entry);
            TextView row = text(ElementRegistry.navL2Label(screen) + "   " + screen,
                    12f, C_TEXT, 0);
            row.setBackground(roundRect(C_CARD, 8, 0, 0));
            row.setPadding(dp(12), dp(11), dp(12), dp(11));
            LinearLayout.LayoutParams lp = wrap();
            lp.setMargins(0, dp(4), 0, 0);
            row.setLayoutParams(lp);
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    visualScreen = screen;
                    renderVisualDetail(detailHost, visualSelected);
                }
            });
            screenList.addView(row);
        }
    }

    /**
     * The "这一块是什么" card. Plain language first; technical anchor/hook are
     * small print at the bottom. ②⑧ (keep=true) show a grey lock tag and NO
     * config key — config is intentionally unavailable for them.
     */
    private void renderVisualDetail(LinearLayout host, int no) {
        host.removeAllViews();
        ElementRegistry.VisualNo v = ElementRegistry.byNo(no);
        if (v == null) {
            host.addView(text("（无此编号）", 12f, C_MUTED, 0));
            return;
        }
        boolean keep = !ElementRegistry.configurable(v);

        LinearLayout card = card();

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        TextView badge = text(String.valueOf(no), 15f, C_WHITE, 0);
        badge.setGravity(android.view.Gravity.CENTER);
        badge.setBackground(roundRect(keep ? C_MUTED : C_ACCENT, 999, 0, 0));
        badge.setPadding(dp(9), dp(5), dp(9), dp(5));
        head.addView(badge);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(text(safe(v.mark()) + "  " + safe(v.label()), 16f, C_TEXT, dp(2)));
        titles.addView(text(safe(v.visual()), 11f, C_SUB, dp(2)));
        head.addView(titles, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(head, wrap());

        // The plain-language answer, prominent.
        card.addView(text(plainWords(no), 14f, C_TEXT, dp(12)));

        if (keep) {
            TextView lock = text("🔒 保留不可改", 12f, C_SUB, dp(10));
            lock.setBackground(roundRect(0xFFEFEFF4, 8, 0, 0));
            lock.setPadding(dp(10), dp(6), dp(10), dp(6));
            card.addView(lock, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            card.addView(text("原因：" + keepReason(no), 12f, C_SUB, dp(8)));
        } else {
            card.addView(sectionHeader("能改什么 / 怎么调"));
            card.addView(text("能改维度：" + safe(v.dimension()), 13f, C_ACCENT, dp(2)));
            card.addView(text(howTo(no), 12f, C_TEXT, dp(6)));
            String key = ElementRegistry.paramKey(domainForScreen(visualScreen),
                    visualScreen, v, v.dimension());
            card.addView(text("配置键（只读）：" + safe(key), 11f, C_MUTED, dp(8)));
        }

        card.addView(divider());
        card.addView(sectionHeader("技术锚点（给进阶用户）"));
        card.addView(text("锚点：" + safe(v.anchor()), 11f, C_MUTED, dp(2)));
        card.addView(text("Hook：" + safe(v.hook()), 11f, C_MUTED, dp(2)));
        card.addView(text("什么时候改：" + safe(v.example()), 11f, C_MUTED, dp(4)));

        // 0.5.0-p126: 按元素精确编辑入口。只在已选中界面（screen）时出现；
        // 说明卡本身仍保持只读，真正的写入走独立的字体编辑子页。
        if (visualScreen != null && visualScreen.length() > 0) {
            card.addView(divider());
            TextView fontEntry = text("编辑此界面元素字体  ›", 15f, C_ACCENT, dp(8));
            fontEntry.setBackground(roundRect(C_ACCENT_SOFT, 10, 0, 0));
            fontEntry.setPadding(dp(12), dp(11), dp(12), dp(11));
            fontEntry.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    openFontEditor(visualScreen);
                }
            });
            card.addView(fontEntry, wrap());
        }
        host.addView(cardWithMargin(card));
    }

    // ---- per-element font editor (0.5.0-p126, global_id 命中) ---------------

    /**
     * 字体精确编辑子页：从 ElementRegistry.KEY_INDEX 过滤当前界面的元素 key，
     * 选中后用 globalIdFor(screen,key) 得 gid，再按 gid 写 fontColor/fontScale。
     * 全程只切换 body 子 View，不新开 Activity，也不碰任何写入链/渲染链。
     */
    private void openFontEditor(final String screen) {
        body.removeAllViews();

        TextView back = text("‹ 返回视觉编辑", 14f, C_ACCENT, 0);
        back.setPadding(dp(2), dp(2), dp(2), dp(10));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openElementBrowser();
            }
        });
        body.addView(back);

        String label = ElementRegistry.screenLabel(screen);
        body.addView(text("字体精确编辑", 24f, C_TEXT, 0));
        body.addView(text((label == null || label.length() == 0)
                ? screen : label + "   ·   " + screen, 12f, C_SUB, dp(6)));
        body.addView(text("选中元素后改字体颜色 / 大小，保存即时生效。",
                11f, C_MUTED, dp(6)));

        // KEY_INDEX 每行 screen|key|global_id|dims，过滤出当前界面的行。
        final ArrayList<String> rows = new ArrayList<String>();
        for (String row : ElementRegistry.KEY_INDEX) {
            int p1 = row.indexOf('|');
            int p2 = p1 < 0 ? -1 : row.indexOf('|', p1 + 1);
            if (p1 < 0 || p2 < 0) continue;
            if (row.substring(0, p1).equalsIgnoreCase(screen)) rows.add(row);
        }

        body.addView(sectionHeader("元素  ·  共 " + rows.size() + " 项"));
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        if (rows.isEmpty()) {
            list.addView(text("（该界面暂无可编辑元素）", 12f, C_MUTED, dp(6)));
        }
        final LinearLayout editHost = new LinearLayout(this);
        editHost.setOrientation(LinearLayout.VERTICAL);
        editHost.addView(text("请从上方选择一个元素", 12f, C_MUTED, dp(10)));

        for (String row : rows) {
            int p1 = row.indexOf('|');
            int p2 = row.indexOf('|', p1 + 1);
            int p3 = row.indexOf('|', p2 + 1);
            final String key = row.substring(p1 + 1, p2);
            final String gid = row.substring(p2 + 1, p3);
            final String dims = row.substring(p3 + 1);
            int amb = ElementRegistry.keyAmbiguity(key);
            boolean usable = dims.contains("font") && !dims.contains("keep");

            StringBuilder line = new StringBuilder();
            line.append(key).append('\n').append(gid).append("   ·   dims=").append(dims);
            if (amb > 1) line.append("   ·   ⚠同 key 命中 ").append(amb).append(" 个界面");
            if (!usable) {
                line.append("   ·   ").append(dims.contains("keep")
                        ? "保留不可改" : "不支持字体");
            }

            TextView item = text(line.toString(), 12f, usable ? C_TEXT : C_MUTED, 0);
            item.setBackground(roundRect(usable ? C_CARD : C_CHIP, 9, 0, 0));
            item.setPadding(dp(12), dp(10), dp(12), dp(10));
            LinearLayout.LayoutParams lp = wrap();
            lp.setMargins(0, dp(4), 0, 0);
            item.setLayoutParams(lp);
            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    String resolved = ElementRegistry.globalIdFor(screen, key);
                    if (resolved == null || resolved.length() == 0) resolved = gid;
                    renderFontEdit(editHost, key, resolved, dims);
                }
            });
            list.addView(item);
        }

        ScrollView keyScroll = new ScrollView(this);
        keyScroll.addView(list, wrap());
        keyScroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(220)));
        body.addView(keyScroll);

        body.addView(sectionHeader("字体参数"));
        body.addView(editHost, wrap());
    }

    /** 渲染选中元素的两个输入框 + 保存/重置；keep 或不支持字体只给提示。 */
    private void renderFontEdit(LinearLayout host, String key,
                                final String gid, String dims) {
        host.removeAllViews();
        host.addView(text(key, 14f, C_TEXT, 0));
        host.addView(text("global_id：" + gid, 11f, C_MUTED, dp(3)));

        if (dims.contains("keep")) {
            host.addView(text("保留不可改（系统项）", 13f, C_SUB, dp(10)));
            return;
        }
        if (!dims.contains("font")) {
            host.addView(text("该元素不支持字体配置", 13f, C_SUB, dp(10)));
            return;
        }

        String rules = elRules();
        String color = ConfigContract.elRuleGet(rules, gid, "fontColor");
        String scale = ConfigContract.elRuleGet(rules, gid, "fontScale");
        if (scale == null || scale.length() == 0) scale = "100";

        LinearLayout card = card();
        final EditText colorInput = inputRow(card, "字体颜色  #RRGGBB", color, "#007AFF");
        final EditText scaleInput = inputRow(card, "字体大小  60~160 (%)", scale, "100");

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.addView(capsule("保存", C_ACCENT_SOFT, C_ACCENT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String c = colorInput.getText().toString().trim();
                String s = scaleInput.getText().toString().trim();
                if (c.length() > 0 && !c.matches("#[0-9A-Fa-f]{6}")) {
                    toast("颜色格式应为 #RRGGBB");
                    return;
                }
                if (s.length() == 0) s = "100";
                int n;
                try {
                    n = Integer.parseInt(s);
                } catch (Throwable t) {
                    toast("字体大小应为 60~160 的数字");
                    return;
                }
                if (n < 60 || n > 160) {
                    toast("字体大小应在 60~160 之间");
                    return;
                }
                writeElRule(gid, "fontColor", c);
                writeElRule(gid, "fontScale", String.valueOf(n));
                toast("已保存，即时生效");
            }
        }));
        actions.addView(capsule("重置", C_CHIP, C_TEXT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                writeElRule(gid, "fontColor", "");
                writeElRule(gid, "fontScale", "");
                colorInput.setText("");
                scaleInput.setText("100");
                toast("已重置，恢复默认字体");
            }
        }));
        card.addView(actions, wrap());
        host.addView(cardWithMargin(card));
    }

    /** iOS 风格单行文本输入：标题 + 圆角输入框，返回 EditText 供读取。 */
    private EditText inputRow(LinearLayout parent, String label,
                              String value, String hint) {
        parent.addView(text(label, 13f, C_SUB, dp(10)));
        EditText et = new EditText(this);
        et.setText(value == null ? "" : value);
        et.setHint(hint);
        et.setTextSize(15f);
        et.setTextColor(C_TEXT);
        et.setHintTextColor(C_MUTED);
        et.setBackground(roundRect(C_CARD, 9, 0, 0));
        et.setPadding(dp(12), dp(10), dp(12), dp(10));
        parent.addView(et, wrap());
        return et;
    }

    /**
     * Build the wireframe frame: the Canvas-drawn phone plus transparent,
     * clickable hit regions and numbered badges overlaid on top. The hit regions
     * make every annotated block tappable; tapping recolours the badge and
     * draws a blue outline around that block in the wireframe.
     */
    private void buildVisualWireframe(LinearLayout parent, final LinearLayout detailHost) {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackground(roundRect(C_CARD, 14, 0, 0));
        frame.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(430)));

        final VisualWireframeView wire =
                new VisualWireframeView(this, ElementRegistry.all());
        wire.select(visualSelected);
        frame.addView(wire, new FrameLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT));

        final int n = VisualWireframeView.REGIONS.length;
        final View[] hits = new View[n];
        final TextView[] badges = new TextView[n];

        View.OnClickListener pick = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Object tag = v.getTag();
                if (!(tag instanceof Integer)) return;
                int no = (Integer) tag;
                visualSelected = no;
                wire.select(no);
                renderVisualDetail(detailHost, no);
                paintBadges(badges, no);
            }
        };

        // Broad regions first, specific ones last so the topmost view wins a tap.
        int[] order = {3, 2, 4, 9, 10, 5, 1, 6, 7, 8};
        for (int k = 0; k < order.length; k++) {
            int no = order[k];
            int i = no - 1;
            View hit = new View(this);
            hit.setTag(Integer.valueOf(no));
            hit.setOnClickListener(pick);
            frame.addView(hit, new FrameLayout.LayoutParams(0, 0));
            hits[i] = hit;

            TextView badge = text(String.valueOf(no), 12f, C_WHITE, 0);
            badge.setGravity(android.view.Gravity.CENTER);
            badge.setTag(Integer.valueOf(no));
            badge.setOnClickListener(pick);
            frame.addView(badge, new FrameLayout.LayoutParams(dp(30), dp(30)));
            badges[i] = badge;
        }

        final View[] finalHits = hits;
        final TextView[] finalBadges = badges;
        final int[] last = {-1, -1};
        frame.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b,
                                       int ol, int ot, int or, int ob) {
                int w = r - l;
                int h = b - t;
                if (w <= 0 || h <= 0 || (w == last[0] && h == last[1])) return;
                last[0] = w;
                last[1] = h;
                float s = Math.min((float) w / VisualWireframeView.VB_W,
                        (float) h / VisualWireframeView.VB_H);
                float ox = (w - VisualWireframeView.VB_W * s) / 2f;
                float oy = (h - VisualWireframeView.VB_H * s) / 2f;
                for (int i = 0; i < n; i++) {
                    int[] reg = VisualWireframeView.REGIONS[i];
                    View hit = finalHits[i];
                    if (hit != null) {
                        FrameLayout.LayoutParams lp =
                                (FrameLayout.LayoutParams) hit.getLayoutParams();
                        lp.width = Math.max(1, Math.round((reg[3] - reg[1]) * s));
                        lp.height = Math.max(1, Math.round((reg[4] - reg[2]) * s));
                        lp.setMargins(Math.round(ox + reg[1] * s),
                                Math.round(oy + reg[2] * s), 0, 0);
                        hit.setLayoutParams(lp);
                    }
                    TextView badge = finalBadges[i];
                    if (badge != null) {
                        int bw = Math.max(dp(22), Math.round(30 * s));
                        FrameLayout.LayoutParams lp =
                                (FrameLayout.LayoutParams) badge.getLayoutParams();
                        lp.width = bw;
                        lp.height = bw;
                        lp.setMargins(
                                Math.round(ox + VisualWireframeView.MARK_X[i] * s - bw / 2f),
                                Math.round(oy + VisualWireframeView.MARK_Y[i] * s - bw / 2f),
                                0, 0);
                        badge.setLayoutParams(lp);
                    }
                }
            }
        });

        parent.addView(frame);
        paintBadges(badges, visualSelected);
    }

    private void paintBadges(TextView[] badges, int selected) {
        for (int i = 0; i < badges.length; i++) {
            if (badges[i] == null) continue;
            int no = i + 1;
            ElementRegistry.VisualNo v = ElementRegistry.byNo(no);
            boolean keep = v != null && v.keep();
            boolean on = no == selected;
            int bg = keep ? C_MUTED : C_ACCENT;
            badges[i].setBackground(roundRect(bg, 999,
                    on ? dp(3) : 0, on ? C_WHITE : bg));
            badges[i].setTextColor(C_WHITE);
        }
    }

    /**
     * Pure-Java Canvas drawing of a phone settings screen. Logical viewBox is
     * 0..1000 x 0..1400, scaled to the actual view size and centred. ①~⑩ are
     * drawn where those visual objects really sit on screen; the companion
     * hit regions/badges in buildVisualWireframe() share REGIONS/MARK_*.
     */
    static final class VisualWireframeView extends View {
        static final int VB_W = 1000;
        static final int VB_H = 1400;

        /** {no, x0, y0, x1, y1} in viewBox units, ordered by no (index = no-1). */
        static final int[][] REGIONS = {
            {1, 130, 100, 470, 175},    // ① title row
            {2, 40, 72, 960, 252},      // ② top bar + gradient (keep)
            {3, 0, 0, 1000, 1400},      // ③ whole page backdrop
            {4, 40, 300, 960, 440},     // ④ first white card row
            {5, 40, 255, 720, 296},     // ⑤ group title row
            {6, 95, 322, 540, 362},     // ⑥ main title text
            {7, 95, 378, 470, 408},     // ⑦ summary text
            {8, 760, 315, 956, 425},    // ⑧ icon / arrow / switch (keep)
            {9, 40, 790, 960, 1128},    // ⑨ dynamic list rows
            {10, 40, 452, 960, 478}     // ⑩ divider / state colour
        };
        /** Marker (circled-number dot) centre for each region, index = no-1. */
        static final int[] MARK_X = {120, 905, 936, 916, 700, 528, 462, 944, 946, 946};
        static final int[] MARK_Y = {90, 60, 1340, 288, 243, 312, 366, 303, 778, 440};

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ElementRegistry.VisualNo[] visuals;
        private int selectedNo = 4;

        VisualWireframeView(Context ctx, ElementRegistry.VisualNo[] visuals) {
            super(ctx);
            this.visuals = visuals;
            setWillNotDraw(false);
        }

        void select(int no) {
            selectedNo = no;
            invalidate();
        }

        int selected() {
            return selectedNo;
        }

        private void fill(int c) {
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setAlpha(255);
            paint.setColor(c);
        }

        private void stroke(int c, float w) {
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setAlpha(255);
            paint.setColor(c);
            paint.setStrokeWidth(w);
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;
            float s = Math.min((float) w / VB_W, (float) h / VB_H);
            float ox = (w - VB_W * s) / 2f;
            float oy = (h - VB_H * s) / 2f;
            c.save();
            c.translate(ox, oy);
            c.scale(s, s);

            // ③ page base — the whole screen behind every card.
            fill(C_PAGE);
            c.drawRect(0, 0, VB_W, VB_H, paint);

            // phone frame + status bar
            stroke(0xFFD0D0D6, 8f);
            c.drawRoundRect(18, 16, 982, 1384, 56, 56, paint);
            fill(0xFFFFFFFF);
            c.drawRect(28, 28, 972, 70, paint);
            fill(0xFF8E8E93);
            c.drawRect(56, 42, 150, 52, paint);
            c.drawRect(872, 42, 946, 52, paint);

            // ② top bar: keep item → grey fill + hatch + fading gradient tail.
            fill(0xFFECECF1);
            c.drawRoundRect(40, 72, 960, 194, 18, 18, paint);
            stroke(0xFFD6D6DC, 4f);
            for (int x = 70; x < 950; x += 70) {
                c.drawLine(x, 186, x + 44, 80, paint);
            }
            paint.setShader(new LinearGradient(0, 194, 0, 252,
                    0x14E5E5EA, 0x00E5E5EA, Shader.TileMode.CLAMP));
            paint.setStyle(Paint.Style.FILL);
            paint.setAlpha(255);
            c.drawRect(40, 194, 960, 252, paint);
            paint.setShader(null);

            // ① back arrow + title block
            stroke(0xFF3C3C43, 7f);
            c.drawLine(70, 138, 96, 124, paint);
            c.drawLine(70, 138, 96, 152, paint);
            fill(0xFF1C1C1E);
            c.drawRoundRect(130, 118, 470, 158, 14, 14, paint);

            // ⑤ group title row (short light bar + darker text stub)
            fill(0xFFE3E3E8);
            c.drawRoundRect(40, 255, 720, 296, 16, 16, paint);
            fill(0xFFA9A9AE);
            c.drawRoundRect(64, 266, 320, 286, 10, 10, paint);

            // ④ white card rows
            fill(C_WHITE);
            c.drawRoundRect(40, 300, 960, 440, 26, 26, paint);
            stroke(C_LINE, 3f);
            c.drawRoundRect(40, 300, 960, 440, 26, 26, paint);
            fill(C_WHITE);
            c.drawRoundRect(40, 486, 960, 626, 26, 26, paint);
            stroke(C_LINE, 3f);
            c.drawRoundRect(40, 486, 960, 626, 26, 26, paint);

            // ⑥ main title / ⑦ summary text bars (row 1 and a plainer row 2)
            fill(0xFF2C2C2E);
            c.drawRoundRect(95, 322, 540, 360, 12, 12, paint);
            fill(0xFFB0B0B5);
            c.drawRoundRect(95, 378, 470, 406, 10, 10, paint);
            fill(0xFF2C2C2E);
            c.drawRoundRect(95, 508, 460, 546, 12, 12, paint);
            fill(0xFFB0B0B5);
            c.drawRoundRect(95, 564, 380, 592, 10, 10, paint);

            // ⑧ icon / arrow / switch: keep → grey, never styled as blue.
            fill(0xFF9E9EA3);
            c.drawCircle(812, 358, 26, paint);
            stroke(0xFF9E9EA3, 7f);
            c.drawLine(900, 344, 922, 366, paint);
            c.drawLine(922, 366, 900, 388, paint);
            fill(0xFFB4B4B9);
            c.drawRoundRect(872, 398, 942, 424, 13, 13, paint);
            fill(0xFFEDEDF1);
            c.drawCircle(925, 411, 11, paint);

            // ⑩ separators / state colour
            fill(C_LINE);
            c.drawRect(40, 454, 960, 460, paint);
            fill(0xFFD8D8DC);
            c.drawRect(40, 466, 960, 470, paint);

            // ⑨ dynamic list rows with a small "动态" tag
            fill(C_WHITE);
            c.drawRoundRect(40, 790, 960, 940, 26, 26, paint);
            stroke(C_LINE, 3f);
            c.drawRoundRect(40, 790, 960, 940, 26, 26, paint);
            fill(C_WHITE);
            c.drawRoundRect(40, 952, 960, 1102, 26, 26, paint);
            stroke(C_LINE, 3f);
            c.drawRoundRect(40, 952, 960, 1102, 26, 26, paint);
            fill(sWireAccentSoft);
            c.drawRoundRect(60, 802, 168, 830, 12, 12, paint);
            fill(0xFF2C2C2E);
            c.drawRoundRect(92, 850, 520, 886, 12, 12, paint);
            fill(0xFFB0B0B5);
            c.drawRoundRect(92, 902, 400, 926, 10, 10, paint);
            fill(sWireAccentSoft);
            c.drawRoundRect(60, 964, 168, 992, 12, 12, paint);
            fill(0xFF2C2C2E);
            c.drawRoundRect(92, 1012, 560, 1048, 12, 12, paint);
            fill(0xFFB0B0B5);
            c.drawRoundRect(92, 1064, 430, 1088, 10, 10, paint);

            // selected region: single blue outline, nothing else highlighted
            int idx = selectedNo - 1;
            if (idx >= 0 && idx < REGIONS.length) {
                int[] r = REGIONS[idx];
                stroke(sWireAccent, 9f);
                c.drawRoundRect(r[1], r[2], r[3], r[4], 30, 30, paint);
            }
            c.restore();
        }
    }

    private static String safe(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    // ---- helpers ------------------------------------------------------------

    private void diagnostics() {
        StringBuilder b = new StringBuilder(ConfigStore.diagnostics(this));
        b.append("\n构建版本: ").append(GlassEntry.BUILD).append('\n');
        b.append("液态玻璃: 已恢复 LiquidTab 原始参数\n");
        b.append("  渲染代码与 L1-ACCEPTED-p30 基线逐字节相同；\n");
        b.append("  质感参数全部隐藏并在运行时强制为 -1 / 引擎默认。\n");
        b.append("填充方式: 自定义（图片 / 视频同源，仅此一种）\n");
        b.append("上下位置: ").append(stored(ConfigContract.Param.BG_SCALE_FOCUS_Y))
                .append("%\n");
        b.append("左右位置: ").append(stored(ConfigContract.Param.BG_SCALE_FOCUS_X))
                .append("%\n");
        b.append("拉伸大小: ").append(stored(ConfigContract.Param.BG_SCALE_ZOOM))
                .append("%\n");
        b.append("视频玻璃: 采样源已由不透明 plate 兜底\n");
        b.append("作用页面: 统一（所有页面跟随全局）\n");
        b.append("\n作用域:\n");
        for (String pkg : Scope.all()) b.append("  ").append(pkg).append('\n');
        new AlertDialog.Builder(this)
                .setTitle("诊断信息")
                .setMessage(b.toString())
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(roundRect(C_CARD, 12, 0, 0));
        c.setPadding(dp(16), dp(2), dp(16), dp(12));
        return c;
    }

    private View cardWithMargin(LinearLayout c) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(20), 0, 0);
        c.setLayoutParams(lp);
        return c;
    }

    private GradientDrawable roundRect(int color, int radiusDp,
                                       int strokePx, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        if (strokePx > 0) d.setStroke(strokePx, strokeColor);
        return d;
    }

    /** iOS grouped section header: small uppercase grey, inset with the card. */
    private View sectionHeader(String s) {
        TextView h = text(s.toUpperCase(), 13f, C_SUB, 0);
        h.setPadding(0, dp(12), 0, dp(4));
        return h;
    }

    private TextView text(String s, float size, int color, int topPad) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setPadding(0, topPad, 0, 0);
        return t;
    }

    /** iOS separator: 1px, starts 16 dp in from the card's left edge. */
    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(C_LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        lp.setMargins(dp(16), 0, 0, 0);
        v.setLayoutParams(lp);
        return v;
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static int clampInt(String v, int min, int max) {
        try {
            int n = Integer.parseInt(v.trim());
            return Math.max(min, Math.min(max, n));
        } catch (Throwable t) {
            return min;
        }
    }

    private void toast(String s) {
        Toast t = Toast.makeText(this, s, Toast.LENGTH_SHORT);
        t.show();
    }
}
