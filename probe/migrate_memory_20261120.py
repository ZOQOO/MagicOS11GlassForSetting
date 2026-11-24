import io

BASE = 'C:/Users/falte/WorkBuddy/2026-11-11-21-16-37/.workbuddy/memory/'
m = BASE + 'MEMORY.md'
d = BASE + 'MEMORY-DETAIL.md'

s = io.open(m, encoding='utf-8').read()
a = s.index('- **p124（已被 p125 取代')
b = s.index('## 8 findings 索引')
history = s[a:b]
# 保留 history 后可能有的"待办"等非版本块？先检查尾部
print('--- history tail ---')
print(history[-600:])

dt = io.open(d, encoding='utf-8').read()
if '## 16 从 MEMORY.md §7 搬迁的被取代版本（2026-11-20）' not in dt:
    dt += '\n\n## 16 从 MEMORY.md §7 搬迁的被取代版本（内容未删，2026-11-20）\n\n'
    dt += history.rstrip() + '\n'
    io.open(d, 'w', encoding='utf-8', newline='\n').write(dt)
    print('DETAIL appended', len(history))

# 主文件删掉这三个详述块，留一句指针
pointer = '''- **历史版本 p121~p124 完整状态**（iOS 界面 / 视觉编辑 / 两级导航 / ID 命中
  首版：定因、产物、验证边界）→ `MEMORY-DETAIL.md` §16，内容未删，本文件只留当前构建。

'''
s = s[:a] + pointer + s[b:]
io.open(m, 'w', encoding='utf-8', newline='\n').write(s)
print('MEMORY rewritten', len(s))
