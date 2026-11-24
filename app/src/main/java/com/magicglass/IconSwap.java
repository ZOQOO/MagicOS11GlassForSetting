package com.magicglass;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 0.5.0-p186: L1 主设置页行内图标替换（重建版）。
 *
 * <p><b>为什么旧方案失败</b>：p178~p182 挂的是
 * {@code androidx.preference.Preference.onBindViewHolder}。反编译
 * {@code com.android.settings.dashboard.g} 证实 L1 主屏由
 * {@code HWSettings -> DashboardSummary -> HwRecyclerView #dashboard_container
 * -> 行 #dashboard_tile} 渲染，行是 HwImageView/HwTextView，绑定走
 * {@code g.U(g$g,int) -> private S(g$g, m)}，<b>根本不经过
 * Preference.onBindViewHolder</b>，旧 hook 在 L1 永不触发。</p>
 *
 * <p><b>正确的落点</b>：{@code S()} 依次
 * {@code holder.a(ImageView#0x1020006).setImageDrawable(...)} 与
 * {@code holder.b(TextView#0x1020016).setText(tile.a)}。挂在
 * {@link TextView#setText(CharSequence)} 上：标题文字设置完成后，回头按
 * 该行标题匹配规则，替换同一行 {@code android.R.id.icon} 的图片。
 * 只依赖标准 android id 与公开 API，不依赖混淆类名（g / g$m / S 均可能随
 * ROM 变化），因此比 hook 混淆方法稳。</p>
 *
 * <p>约束：只在 L1（HWSettings / Settings）生效；有界遍历（深度≤5 / 节点≤32）；
 * 全 try-catch；不在渲染帧路径（setText 是数据绑定回调）；不写设备文件。</p>
 */
public final class IconSwap {

    /** android.R.id.icon —— 行内图标 ImageView（反编译 g$g 构造器实证）。 */
    static final int ID_ICON = 0x01020006;
    /** android.R.id.title —— 行标题 TextView（反编译 g$g 构造器实证）。 */
    static final int ID_TITLE = 0x01020016;

    /** 原 Drawable 按 ImageView 弱引用保存；规则删除时还原，不阻止回收。 */
    private static final WeakHashMap<ImageView, Drawable> ORIGINALS =
            new WeakHashMap<ImageView, Drawable>();

    private static volatile boolean sInstalled;
    private static volatile XposedModule sModule;

    private IconSwap() {
    }

    /**
     * 在设置宿主进程装一次 {@code TextView.setText(CharSequence)} hook。
     * 由 GlassEntry.onActivityReady 在 pkg == com.android.settings 时调用。
     */
    public static void install(ClassLoader loader, XposedModule module) {
        if (sInstalled) return;
        if (module == null || loader == null) {
            Log.w("IconSwap install skipped: module/loader null");
            return;
        }
        sModule = module;
        try {
            Class<?> tv = Class.forName("android.widget.TextView", false, loader);
            Method target = null;
            for (Method m : tv.getDeclaredMethods()) {
                if ("setText".equals(m.getName())
                        && m.getParameterTypes().length == 1
                        && CharSequence.class.equals(m.getParameterTypes()[0])) {
                    target = m;
                    break;
                }
            }
            if (target == null) {
                Log.w("IconSwap install: TextView.setText(CharSequence) not found");
                return;
            }
            module.hook(target).intercept(new SetTextHooker());
            sInstalled = true;
            Log.i("IconSwap hook installed: TextView.setText(CharSequence)");
        } catch (Throwable t) {
            Log.w("IconSwap install failed: " + t);
        }
    }

    /** 由 GlassEntry 注入模块实例（用于安装期日志/兼容）。 */
    public static void bindModule(XposedModule m) {
        sModule = m;
    }

    /** hook 体：标题设置完成后，按标题替换同一行的图标。 */
    public static final class SetTextHooker implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            Object result = chain.proceed();
            try {
                Object self = chain.getThisObject();
                if (!(self instanceof TextView)) return result;
                TextView title = (TextView) self;
                if (title.getId() != ID_TITLE) return result;
                if (!GlassEntry.isL1Now()) return result;
                applyForTitle(title);
            } catch (Throwable ignored) {
            }
            return result;
        }
    }

    /**
     * 按标题匹配规则并替换同行的 {@code android.R.id.icon}。
     * 标题取自刚设置的 TextView 文本；summary 是另一个 View，不会误配。
     */
    private static void applyForTitle(TextView title) {
        CharSequence cs = title.getText();
        if (cs == null) return;
        String rowTitle = cs.toString().trim();
        if (rowTitle.length() == 0) return;
        if (!L1IconRows.contains(rowTitle)) return;

        ImageView icon = findIconInRow(title);
        if (icon == null) return;

        String rules = readRules(icon.getContext());
        String media = rules.length() == 0 ? ""
                : ConfigContract.elRuleGet(rules, L1IconRows.ruleGid(rowTitle), "icon");

        if (media == null || media.length() == 0) {
            // 无规则 / 已重置：还原系统原图（仅在曾替换过时）。
            Drawable orig = ORIGINALS.remove(icon);
            if (orig != null) icon.setImageDrawable(orig);
            return;
        }
        Bitmap bmp = IconSwapCache.peek(media);
        if (bmp == null) {
            Context c = icon.getContext();
            if (c == null) return;
            bmp = IconSwapCache.load(c, media);
            if (bmp == null) return;
        }
        if (!ORIGINALS.containsKey(icon)) {
            ORIGINALS.put(icon, icon.getDrawable());
        }
        icon.setImageDrawable(new BitmapDrawable(icon.getResources(), bmp));
    }

    /**
     * 从标题 TextView 向上找到行容器，再向下找 {@code android.R.id.icon}。
     * 有界：向上 ≤4 层、每层向下 ≤32 节点。
     */
    private static ImageView findIconInRow(View title) {
        View cur = title;
        for (int up = 0; up < 4 && cur != null; up++) {
            View parent = cur.getParent() instanceof View ? (View) cur.getParent() : null;
            if (parent == null) return null;
            ImageView hit = findIconDown(parent, 0, new int[1]);
            if (hit != null) return hit;
            cur = parent;
        }
        return null;
    }

    private static ImageView findIconDown(View v, int depth, int[] visited) {
        if (v == null || depth > 4 || visited[0] >= 32) return null;
        visited[0]++;
        try {
            if (v instanceof ImageView && v.getId() == ID_ICON) {
                return (ImageView) v;
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                int n = g.getChildCount();
                for (int i = 0; i < n; i++) {
                    ImageView hit = findIconDown(g.getChildAt(i), depth + 1, visited);
                    if (hit != null) return hit;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 配置即时生效：配置广播后重扫当前页所有行标题，重新应用/还原图标，
     * 不必等行重绑。有界（深度≤12 / 节点≤800）；非渲染帧。
     */
    public static void refresh(View root) {
        if (root == null) return;
        try {
            walkTitles(root, 0, new int[1]);
        } catch (Throwable ignored) {
        }
    }

    private static void walkTitles(View v, int depth, int[] visited) {
        if (v == null || depth > 12 || visited[0] >= 800) return;
        visited[0]++;
        try {
            if (v instanceof TextView && v.getId() == ID_TITLE) {
                applyForTitle((TextView) v);
                return;
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                int n = g.getChildCount();
                for (int i = 0; i < n; i++) {
                    walkTitles(g.getChildAt(i), depth + 1, visited);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static String readRules(Context c) {
        try {
            if (c == null) return "";
            ConfigContract.Source src = ConfigBus.load(c);
            if (src == null) return "";
            String rules = src.get(ConfigContract.key(
                    ConfigContract.Slot.GLOBAL, ConfigContract.Param.ICON_RULES));
            return rules == null ? "" : rules;
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** 极简日志：沿用 GlassEntry 的 mg-state 通道，便于真机核对。 */
    private static final class Log {
        static void i(String s) {
            GlassEntry.say("mg-state " + s);
        }

        static void w(String s) {
            GlassEntry.say("mg-state " + s);
        }
    }
}
