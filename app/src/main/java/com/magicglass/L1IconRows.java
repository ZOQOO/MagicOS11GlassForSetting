package com.magicglass;

/**
 * 0.5.0-p182: L1 主设置页行标题清单 —— 真机 uiautomator dump 采集
 * (2026-11-23, 见 probe/probe-report/l1_rows/l1_titles_2026-11-23.txt)。
 *
 * 这些是设置页每一行的 title TextView 原文,用来在 hook 里按【行标题】匹配
 * 要替换图标的行,不再依赖 preference key 或运行时抓取。标题来自设备只读
 * view tree,是静态数据;匹配时读的是 PREFBIND 回调中该行 title 的实时文字,
 * 与 summary(如「蓝牙 已开启」)无关,不会误配。
 *
 * 行标题与目前图标替换规则的 key 是两套命名空间:title 匹配写在单独的
 * "icon.title" dim 下,值为媒体名;格式仍复用 el.rules 的 gid|dim|value。
 */
public final class L1IconRows {

    public static final String[] TITLES = {
            "WLAN", "蓝牙", "移动网络", "荣耀互联", "更多连接",
            "荣耀 AI & YOYO", "桌面和个性化", "显示和亮度", "荣耀绿洲护眼",
            "健康使用手机", "关于手机", "声音和触感", "存储", "应用",
            "情景模式", "生物识别和密码", "用户和账户", "电池",
            "系统和更新", "荣耀钱包", "辅助功能", "通知和状态栏",
            "隐私和安全"
    };

    /**
     * 规则 gid 前缀:标题规则的 gid 形如 "title:WLAN"(不含 '|',因为
     * el.rules 串用 '|' 分段),dim 仍是 "icon",value 是媒体名。
     */
    public static final String RULE_KEY = "title:";

    public static String ruleGid(String title) {
        return RULE_KEY + (title == null ? "" : title.trim());
    }

    private L1IconRows() {
    }

    public static boolean contains(String title) {
        if (title == null) return false;
        for (int i = 0; i < TITLES.length; i++) {
            if (TITLES[i].equals(title.trim())) return true;
        }
        return false;
    }
}
