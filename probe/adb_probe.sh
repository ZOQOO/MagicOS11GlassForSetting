#!/usr/bin/env bash
# =============================================================================
# MagicGlass P0 device probe  --  STRICTLY READ-ONLY ON THE PHONE
# -----------------------------------------------------------------------------
# Device-side constraints enforced by this script:
#   * NO reboot, NO soft reboot, NO zygote restart, NO adb root / remount.
#   * NO writes of ANY KIND to the phone filesystem. `uiautomator dump` writes
#     to /dev/stdout, framework/APK bytes are streamed out with `exec-out cat`
#     and piped straight into scan_jars.py (no temp file on either side).
#   * Every adb shell command is a read: getprop, id, ls, find, cat, grep,
#     pm path, pm list libraries, settings list, dumpsys, pidof, uiautomator
#     dump /dev/stdout.
#   * Only the PC gets files, under <report_root>/<label>/.
#
# If a later stage needs the LSPosed module to take effect, the USER performs
# a SOFT reboot manually (zygote restart only, never a full device reboot).
# This script contains NO reboot command and will NEVER execute one.
#
# Usage:  bash adb_probe.sh [label]
#   Open the target Settings page on the phone first, then run once per page:
#   bash adb_probe.sh L1 ; bash adb_probe.sh L2 ; bash adb_probe.sh L3 ; bash adb_probe.sh EXT
# =============================================================================
set -u

LABEL="${1:-base}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$ROOT/probe-report/$LABEL"
PY="${PYTHON:-python}"
mkdir -p "$OUT"
REPORT="$OUT/report.txt"
: > "$REPORT"

section() { printf '\n===== %s =====\n' "$1" | tee -a "$REPORT"; }
shc()    { adb shell "$1" 2>&1 | tee -a "$REPORT"; }
note()   { printf '%s\n' "$1" | tee -a "$REPORT"; }

if ! adb get-state >/dev/null 2>&1; then
  echo "No adb device connected. Nothing probed."
  exit 1
fi

note "MagicGlass P0 read-only probe | label=$LABEL | $(date '+%Y-%m-%d %H:%M:%S')"
note "Phone filesystem writes: NONE. Reboot commands: NONE."

section "device"
shc "getprop ro.product.brand; getprop ro.product.model; getprop ro.build.version.release; getprop ro.build.version.sdk; getprop ro.build.display.id; getprop ro.build.version.magicui; getprop ro.build.version.honor; id"

section "root / lsposed (read-only ls)"
shc "which su; which magisk; ls /data/adb/lspd 2>/dev/null | head -20"

section "target packages (9 scopes)"
PACKAGES="com.android.settings com.hihonor.systemmanager com.hihonor.controlcenter com.hihonor.assistant com.hihonor.id com.hihonor.wallet com.hihonor.parentcontrol com.android.systemui com.ciallo.hyperbackground"
for p in $PACKAGES; do
  printf '\n--- %s ---\n' "$p" | tee -a "$REPORT"
  shc "pm path $p"
done

section "transparency related settings keys"
shc "settings list global | grep -iE 'transparen|glass|material|blur|hn_|liquid|frost' | grep -viE 'ai_|pluginengine|volte|sdr|wifi' | head -60"
shc "settings list system | grep -iE 'transparen|glass|material|blur|hn_|liquid|frost' | grep -viE 'numbermark|reduce_sar|rcs|adaptive_tianshan' | head -60"
shc "settings list secure | grep -iE 'transparen|glass|material|blur|hn_|liquid|frost' | head -40"
note "-- explicit values of the known keys --"
shc "settings get global TRANSPARENCY_MAIN_SWITCH; settings get global is_user_has_opened_transparent_settings; settings get system transparency_material; settings get system system_current_transparent_mode; settings get system config_dynamic_transparency_switch"

section "sdk traces in system partitions"
shc "ls /system/framework/ 2>/dev/null | grep -iE 'uikit|transparen|honor|hn|glass' | head -50"
shc "find /system /system_ext /product /vendor /product_h -maxdepth 6 \( -iname '*transparency*' -o -iname '*uikit*' -o -iname '*liquidglass*' -o -iname '*glasseffect*' \) 2>/dev/null | head -80"
shc "pm list libraries | grep -iE 'uikit|transparen|honor|glass' | head -40"
shc "getprop | grep -iE 'transparen|glass|uikit|magicos|magicui' | head -40"

section "device-side prefilter: which framework jars contain SDK class names"
# NOTE: only .jar files are grepped here. Grepping every .vdex is extremely slow
# and redundant -- a VDEX holds the same DEX that is already inside its .jar.
CANDIDATES="$(adb shell "timeout 45 grep -a -l -E 'HnVisualEffectLayout|HnGlassEffect|com.hihonor.uikit.transparency|com/hihonor/uikit/transparency' /system/framework/*.jar /system_ext/framework/*.jar /product/framework/*.jar /vendor/framework/*.jar 2>/dev/null | head -40" | tr -d '\r')"
if [ -z "$CANDIDATES" ]; then
  note "(no framework jar matched by raw grep)"
else
  printf '%s\n' "$CANDIDATES" | tee -a "$REPORT"
fi

section "streaming DEX scan (framework candidates + target APKs)"
if [ "${SKIP_STREAM_SCAN:-0}" = "1" ]; then
  note "skipped (SKIP_STREAM_SCAN=1); use probe-report/offline/ + scan_jars.py instead"
else
  SCAN_TARGETS=""
  for f in $CANDIDATES; do SCAN_TARGETS="$SCAN_TARGETS $f"; done
  for p in $PACKAGES; do
    for f in $(adb shell "pm path $p" 2>/dev/null | sed 's/^package://' | tr -d '\r'); do
      SCAN_TARGETS="$SCAN_TARGETS $f"
    done
  done
  SCANNED=0
  for f in $SCAN_TARGETS; do
    [ -z "$f" ] && continue
    SCANNED=$((SCANNED + 1))
    printf '\n--- streaming %s ---\n' "$f" >> "$REPORT"
    adb exec-out "cat '$f'" 2>/dev/null | "$PY" "$ROOT/scan_jars.py" --stdin "$f" >> "$REPORT" 2>&1
  done
  note "stream-scanned $SCANNED files; empty '###' sections mean no SDK traces"
fi
note "next (optional): python $ROOT/scan_jars.py <dir-of-pulled-jars>"

section "settings process classloader (read-only /proc maps via su)"
shc "pid=\$(pidof com.android.settings); echo pid=\$pid; su -c \"cat /proc/\$pid/maps 2>/dev/null | grep -iE 'uikit|transparen|glass|hn.*\\.jar|framework' | head -40\" 2>/dev/null"

section "current activity / fragment stack"
shc "dumpsys activity activities | grep -iE 'mResumedActivity|topResumedActivity|ResumedActivity' | head -20"
adb shell dumpsys activity top > "$OUT/activity_top.txt" 2>/dev/null
note "full dumpsys saved: activity_top.txt ($(wc -l < "$OUT/activity_top.txt") lines)"
note "-- system transparency state from Configuration --"
grep -oE 'transparent_[a-z_]+:[A-Za-z0-9]+' "$OUT/activity_top.txt" | sort | uniq -c | tee -a "$REPORT"
note "-- View Hierarchy class histogram (card identification input) --"
"$PY" - "$OUT/activity_top.txt" >> "$REPORT" <<'PY'
import collections, re, sys
lines = open(sys.argv[1], encoding='utf-8', errors='ignore').read().splitlines()
starts = [i for i, l in enumerate(lines) if 'View Hierarchy:' in l]
print("view hierarchy blocks: %d" % len(starts))
for s in starts[-1:]:                       # last block == top Activity
    block = []
    for l in lines[s + 1:]:
        if l.strip() and not l.startswith(' ' * 6):
            break
        block.append(l)
    names = re.findall(r'([A-Za-z0-9_.$]+)\{[0-9a-f]+ ', '\n'.join(block))
    for n, c in collections.Counter(names).most_common(30):
        print("  %4d  %s" % (c, n))
    cards = [l.strip() for l in block if 'dashboard_tile' in l or 'HnListCardLayout' in l]
    print("-- card rows (%d) --" % len(cards))
    for c in cards[:18]:
        print("  ", c)
PY
note "-- running module traces in ViewRootHandler (explains uiautomator idle failures) --"
grep -oE 'callback=[A-Za-z0-9_.$]+' "$OUT/activity_top.txt" | sort -u | head -20 | tee -a "$REPORT"

section "view tree (uiautomator dump -> stdout, no phone file)"
# Record which Activity owns this dump. On MagicOS the resumed Activity can
# change between `am start` and the dump, so never assume the label == page.
RESUMED_BEFORE="$(adb shell "dumpsys activity activities | grep -i topResumedActivity | head -1" 2>/dev/null | tr -d '\r')"
note "owner BEFORE dump: $RESUMED_BEFORE"
adb exec-out uiautomator dump /dev/stdout > "$OUT/window.raw.xml" 2>"$OUT/uiautomator.err"
# uiautomator appends a plain-text line "UI hierchary dumped to: ..." AFTER
# </hierarchy>, making the stream invalid XML. Cut everything past the closing
# tag so standard XML parsers work (also fixes ET "junk after document element").
"$PY" - "$OUT/window.raw.xml" "$OUT/window.xml" <<'PY'
import sys
d = open(sys.argv[1], "rb").read()
end = b"</hierarchy>"
i = d.find(end)
open(sys.argv[2], "wb").write(d[:i + len(end)] if i >= 0 else d)
PY
RESUMED_AFTER="$(adb shell "dumpsys activity activities | grep -i topResumedActivity | head -1" 2>/dev/null | tr -d '\r')"
note "owner AFTER  dump: $RESUMED_AFTER"
if [ "$RESUMED_BEFORE" != "$RESUMED_AFTER" ]; then
  note "WARNING: page changed during dump -> window.xml may be mixed; re-run on a settled page"
fi
if head -c 200 "$OUT/window.xml" | grep -q '<hierarchy'; then
  note "window.xml bytes: $(wc -c < "$OUT/window.xml")"
  "$PY" - "$OUT/window.xml" >> "$REPORT" <<'PY'
import collections, re, sys, xml.etree.ElementTree as ET
try:
    t = ET.parse(sys.argv[1])
    nodes = list(t.iter("node"))
    print("nodes: %d" % len(nodes))
    cls = collections.Counter(n.get("class", "") for n in nodes)
    print("-- top classes --")
    for c, k in cls.most_common(25):
        print("  %4d  %s" % (k, c))
    ids = [n.get("resource-id", "") for n in nodes if n.get("resource-id")]
    print("-- resource-ids (%d unique) --" % len(set(ids)))
    for i in sorted(set(ids))[:50]:
        print("  ", i)
    # Card geometry: rows are clickable, full-width-ish, >= 80px tall.
    # A vertical gap > 30px between consecutive rows marks a GROUP boundary.
    # This is the P1 input for "group card + standalone card" classification.
    pat = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")
    rows = []
    for n in nodes:
        m = pat.match(n.get("bounds", "") or "")
        if not m or n.get("clickable") != "true":
            continue
        x1, y1, x2, y2 = map(int, m.groups())
        if (y2 - y1) >= 80 and (x2 - x1) >= 600:
            rows.append((y1, y2, x1, x2, n.get("class", ""), n.get("resource-id", "")))
    rows.sort()
    print("-- clickable card rows (%d) --" % len(rows))
    for y1, y2, x1, x2, c, rid in rows[:30]:
        print("   [%4d,%4d] h=%3d w=%4d  %-42s %s" % (y1, y2, y2-y1, x2-x1, c.split(".")[-1], rid))
    print("-- inferred card GROUPS (gap > 30px splits a block) --")
    groups, cur = [], []
    prev_bottom = None
    for r in rows:
        if prev_bottom is not None and r[0] - prev_bottom > 30:
            groups.append(cur)
            cur = []
        cur.append(r)
        prev_bottom = r[1]
    if cur:
        groups.append(cur)
    for g in groups:
        print("   block [%4d,%4d] rows=%d rowHeight=%d  %s" % (
            g[0][0], g[-1][1], len(g), g[0][1]-g[0][0],
            "GROUP CARD" if len(g) > 1 else "STANDALONE CARD"))
except Exception as e:
    print("view-tree summary failed:", e)
PY
else
  note "uiautomator returned no hierarchy; raw output:"
  head -c 300 "$OUT/window.xml" | tee -a "$REPORT"
  cat "$OUT/uiautomator.err" | tee -a "$REPORT"
  note "Typical cause on this ROM: continuous glass animation / a module posting"
  note "pre-draw callbacks -> window never reaches idle; or a secure/self-drawn window."
  note "FALLBACK: activity_top.txt carries the real View Hierarchy from dumpsys."
fi

section "done"
note "report: $REPORT"
note "send back: report.txt, window.xml, and (after installing :probe) the MagicGlassProbe LSPosed log"
