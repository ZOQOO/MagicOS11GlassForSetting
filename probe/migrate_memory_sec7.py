import io

BASE = 'C:/Users/falte/WorkBuddy/2026-11-11-21-16-37/.workbuddy/memory/'
m = BASE + 'MEMORY.md'
d = BASE + 'MEMORY-DETAIL.md'

s = io.open(m, encoding='utf-8').read()
a = s.index('## 7 当前状态')
b = s.index('## 8 findings 索引')
sec7 = s[a:b]

# 整段旧 §7 原样搬进 DETAIL（内容不删），加一个新章节号
dt = io.open(d, encoding='utf-8').read()
if '## 15 从 MEMORY.md §7 搬迁的历史状态' not in dt:
    dt += '\n\n## 15 从 MEMORY.md §7 搬迁的历史状态（内容未删，2026-11-20 搬迁）\n\n'
    dt += sec7.rstrip() + '\n'
    io.open(d, 'w', encoding='utf-8', newline='\n').write(dt)
    print('DETAIL appended', len(sec7))

new7 = '''## 7 当前状态（2026-11-20 · p120 已装机 · settings 完全自定义阶段）
- **p120（当前，已装机）**：账号安全四卡玻璃背景。实测根因=四卡在
  `account_center_list_other` RecyclerView 内被 `insideRecyclerView()` 归进
  strips（只剥背景不出矩形）。改 `CardScanner.isAccountSquareCard()` 窄门控
  （父 id + 尺寸窗 500..600×450..560）使四卡改走 cards。静态 44/44、
  APK 106,921 B / SHA256 3460fcb0…6c6e / code 148。设备判据待主进程换码
  采 CONTENTRECTS（预期 n=1→n≥3、出现 y≥2390 新矩形），未宣称视觉完成。
- **已完成并用户验收（按时间）**：L1(p30)、L2 开关栏(p35)、返回白卡(p41)、
  窗口底色(p43)、蓝牙首入(p56)、L2 标题栏(p64)、视频 GPU 层(p73)、
  过渡图几何(p75)、起始窗口直出(p87)、退出动画无残留(p99)、
  GPU 模糊(p103)、账号中心顶栏满宽(p113)。
- **settings 完全自定义阶段（进行中，2026-11-20 启动）**：用户提供分析底座
  项目 `C:/Users/falte/WorkBuddy/2026-11-20-13-29-33/settings_module/`
  （495 界面 / 4701 全局唯一元素 ID / 10 类视觉编号；第三步 hook 实现原为空）。
  用户决策「在 MagicGlass 上演进」，不另起炉灶。本阶段目标：元素级精确配置
  （按 `global_id` / `screen+key` 命中 card/font/backdrop）+ 模块设置界面
  重做为 iOS 分组列表风格。设计见 `MEMORY-DETAIL.md` §15 与该项目的
  `docs/ID_SPEC.md`、`docs/VISUAL_WORKFLOW.md`。
- **历史版本 p104~p119 完整状态**（定因/产物/验证边界）→
  `MEMORY-DETAIL.md` §14.3 与 §15，内容未删，本文件只留当前构建。
- **里程碑存档** `magicglass/probe/probe-report/milestone-L2L3-p115/`
  （p115 全部 20 源码 + APK + 报告 + 记忆快照，31 文件 / 1.3 MB）。
- **近期定因速查**（展开见 DETAIL §15 / findings）：
  §99 整体走查三类修复在位；§100 帮助客服双标题栏=ROM 结构不修；
  §104 p119 跨级别实测渐隐 131/135 保留、档1仅命中已验收 HwToolbar；
  §105 下拉迟缓=EdgeFade 设计内渐隐非 bug；§106 账号中心四症状实测
  （半屏白=自绘、四卡=RecyclerView 不出矩形、隐私=WebView、双标题 ROM）；
  §107 p120 四卡窄门控实修。
- **待办（settings 完全自定义阶段）**：元素级配置数据底座 + iOS 设置界面；
  账号中心半屏白/隐私 WebView 白/帮助客服双标题（前两者超能力、后者 ROM）；
  返回偶发黑屏、L1→L2 整屏白、荣耀互联自绘、视频 prepareAsync、
  p1 的 P0/P1/P2（按指令搁置）；深浅色联动、per-slot opacity、随机图、
  清理 DIAG 探针。

'''
s = s[:a] + new7 + s[b:]
# 头部注记同步
s = s.replace(
    '**2026-11-18 · p118 离线已构建未装机，设备仍 p117**',
    '**2026-11-20 · p120 已装机 · settings 完全自定义阶段**')
io.open(m, 'w', encoding='utf-8', newline='\n').write(s)
print('MEMORY rewritten', len(s))
