# MagicOS11GlassForSetting

MagicOS 11 设置界面的液态玻璃模块（LSPosed / Xposed 独立模块）。

对设置页逐层启用玻璃效果、可编辑背景、行内图标自定义；所有配置改动即时生效，不需要软重启或重启进程。第一次使用需要软重启。

## 功能

- **逐层玻璃**：L1 主设置页 / L2 子页 / L3 深层页分级别控制，玻璃容器、边缘渐隐、静态卡与列表行分别处理。
- **可编辑背景**：图片 / 视频 / 图片池三种模式，背景类型与圆角、模糊等几何参数即时写入。
- **行内图标替换**：L1 主设置页 23 个入口图标，从内置清单点选并自定义图片；选中后自动居中裁切、圆形化，配置即时生效。
- **主题配色**：六套界面主题（表达紫 / 系统蓝 / 玻璃青 / 薄荷绿 / 暖阳橙 / 高级灰）。
- **赞赏入口**：内置赞赏码（点击可直达微信扫一扫并把码图存入相册），无支付 SDK、不联网。
- **诊断日志**：可选逐帧统计，默认关闭。

## 目录结构

```
app/src/main/java/com/magicglass/   模块源码（纯 Java，无 XML 布局）
app/src/main/res/                   启动图标与内置赞赏码
probe/compile_check.py             离线编译检查（stub 方式，无需 Android SDK）
probe/assert_p201.py                当前版本断言（代码形状 + 打包检查）
probe/probe-report/findings.txt     完整开发记录与定因
probe/probe-report/p*_report.txt    逐版报告
build.sh                            构建脚本（aapt2 + javac + d8 + 签名）
```

历史原始像素、录屏、逐版 APK 与旧断言脚本未纳入仓库，见 `.gitignore`。

## 构建

需要本地准备 Android build-tools（aapt2 / d8 / zipalign / apksigner）与 android.jar，路径在 `build.sh` 顶部配置。

```bash
bash probe/compile_check.py   # 离线编译检查（stub）
bash probe/assert_p201.py     # 断言（含打包后检查）
bash build.sh                 # 构建签名 APK，输出到 build-out/
```

## 安装

1. 设备需已安装 LSPosed（或兼容的 Xposed 框架）并启用该模块。
2. 在 LSPosed 里勾选作用域（设置、SystemUI 等相关应用）。
3. 安装 `build-out/MagicGlass-*.apk`，打开模块界面配置。
4. 配置改动通过广播即时送达宿主；不需要软重启或重启进程。

## 兼容性

模块界面、主题、赞赏入口与配置存储完全在模块自身进程内，跨设备一致。

玻璃渲染与图标替换依赖 MagicOS 设置页的内部实现，目前仅在以下设备完成真机验证：

- HONOR AAK-AN00 / Android 17 / MagicOS 11.0.0.117（SP3C00E117）/ 1272×2800

其中：
- 图标替换 hook 使用标准 `TextView.setText(CharSequence)` 与 Android 标准资源 id，不依赖混淆类名。
- 几何判断使用比例阈值和 dp，不写死屏幕像素。
- L1 入口判定依赖 `com.android.settings.HWSettings` / `Settings` 类名，图标匹配依赖内置的 23 行标题清单（`L1IconRows.java`）。其他机型若设置页 Activity 名或行标题不同，相关功能可能不生效。

## 作者

ZOQOO · 基于 LSPosed
