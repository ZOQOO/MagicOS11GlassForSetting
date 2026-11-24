import io

p = 'app/src/main/java/com/magicglass/ConfigBus.java'
lines = io.open(p, encoding='utf-8').read().split('\n')

# Work on 1-based indices collected from grep; remove the SECOND copy of each
# duplicated block (highest line numbers first so indices stay valid).
def drop(a, b):
    """remove lines a..b inclusive (1-based)"""
    del lines[a - 1:b]

# second duplicate blur-read block: 378..~395 (ends with the say line)
# find its end precisely
start2 = 378
end2 = start2
for i in range(start2 - 1, min(start2 + 30, len(lines))):
    if 'startingWindowPreview blur=' in lines[i]:
        end2 = i + 1          # include the closing of say(...)
        break
# the say spans 3 lines; extend to the line ending with ');'
while end2 - 1 < len(lines) and not lines[end2 - 1].rstrip().endswith(');'):
    end2 += 1
drop(start2, end2)

# second startingBlur() method: 238..~245
start1 = 238
end1 = start1
while end1 - 1 < len(lines) and not lines[end1 - 1].startswith('    public static Bitmap startingPreview()'):
    end1 += 1
drop(start1, end1 - 1)

# second field + second null assignment
drop(267, 267)
drop(82, 82)

io.open(p, 'w', encoding='utf-8', newline='\n').write('\n'.join(lines))
print('dedup done')
