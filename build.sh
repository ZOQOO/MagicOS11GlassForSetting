#!/usr/bin/env bash
# MagicGlass standalone APK build -- no Gradle / Android Studio required.
#
# IMPORTANT (device-safety): this script NEVER invokes adb and NEVER touches
# the phone. It only produces a signed APK on the LOCAL disk. Installing that
# APK is a deliberate, separate user action because it writes to the device.
#
# Reuses the verified toolchain layout from harness/hb-work/standalone/build.sh:
#   bt36/android-16/{aapt2.exe,d8.bat,zipalign.exe,apksigner.bat}
#   platform-35/android-35/android.jar
#   libs/api-101.0.1-classes.jar          (libxposed API 101, compileOnly)
# Override with MAGICGLASS_TOOLS / JAVA_HOME / MAGICGLASS_PY.
set -e

cd "$(dirname "$0")"
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL="*"
ROOT=$(pwd -W)
TOOLS=${MAGICGLASS_TOOLS:-"C:/Users/falte/harness/hb-work/tools"}
JDK=${JAVA_HOME:-"C:/Program Files/Java/jdk-21"}
PY=${MAGICGLASS_PY:-"C:/Users/falte/.workbuddy/binaries/python/versions/3.13.12/python"}

ANDROID_JAR="$TOOLS/platform-35/android-35/android.jar"
API_JAR="$TOOLS/libs/api-101.0.1-classes.jar"
AAPT2="$TOOLS/bt36/android-16/aapt2.exe"
D8="$TOOLS/bt36/android-16/d8.bat"
ZIPALIGN="$TOOLS/bt36/android-16/zipalign.exe"
APKSIGNER="$TOOLS/bt36/android-16/apksigner.bat"

KS="$ROOT/probe/tools/magicglass.jks"
KS_PASS=${MAGICGLASS_KS_PASS:-magicglass123}
OUT="$ROOT/build-out"
META="$ROOT/app/src/main/resources/META-INF/xposed"

for f in "$ANDROID_JAR" "$API_JAR" "$AAPT2" "$D8" "$ZIPALIGN" "$APKSIGNER" \
         "$META/module.prop" "$META/java_init.list" "$META/scope.list"; do
  [ -e "$f" ] || { echo "missing: $f" >&2; exit 1; }
done

VERSION=$("$PY" - "$META/module.prop" <<'PY'
import sys
d = dict(l.strip().split('=', 1) for l in open(sys.argv[1], encoding='utf-8') if '=' in l)
print(d['version'].strip())
PY
)
VERSION_CODE=$("$PY" - "$META/module.prop" <<'PY'
import sys
d = dict(l.strip().split('=', 1) for l in open(sys.argv[1], encoding='utf-8') if '=' in l)
print(d['versionCode'].strip())
PY
)
echo "BUILD MagicGlass $VERSION (code $VERSION_CODE)"

rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/dex"

echo "== 1/6 manifest (inject package for standalone aapt2) =="
"$PY" - "$ROOT/app/src/main/AndroidManifest.xml" "$OUT/AndroidManifest.xml" <<'PY'
import sys
src, dst = sys.argv[1], sys.argv[2]
s = open(src, encoding='utf-8').read()
head = s.split('>', 1)[0]
if 'package=' not in head:
    s = s.replace('<manifest xmlns:android=',
                  '<manifest package="com.magicglass" xmlns:android=', 1)
open(dst, 'w', encoding='utf-8').write(s)
print('manifest ready')
PY

echo "== 2/6 aapt2 compile+link (framework + launcher icon mipmaps) =="
RES_DIR="$ROOT/app/src/main/res"
if [ -d "$RES_DIR" ]; then
  "$AAPT2" compile --dir "$RES_DIR" -o "$OUT/res.zip"
fi
LINK_ARGS=""
if [ -f "$OUT/res.zip" ]; then
  LINK_ARGS="-R $OUT/res.zip"
fi
"$AAPT2" link -o "$OUT/base.apk" \
  -I "$ANDROID_JAR" \
  $LINK_ARGS \
  --manifest "$OUT/AndroidManifest.xml" \
  --min-sdk-version 33 --target-sdk-version 35 \
  --version-code "$VERSION_CODE" --version-name "$VERSION"

echo "== 3/6 javac =="
"$JDK/bin/javac" --release 11 -encoding UTF-8 -nowarn \
  -cp "$ANDROID_JAR;$API_JAR" \
  -d "$OUT/classes" "$ROOT"/app/src/main/java/com/magicglass/*.java

echo "== 4/6 d8 =="
# 0.5.0-p94: the class list outgrew the Windows command line (d8.bat forwards
# through cmd.exe, ~8k chars). Pack the classes into a jar first so d8 gets a
# single short argument; d8 accepts jar inputs directly.
"$JDK/bin/jar" --create --file "$OUT/classes.jar" -C "$OUT/classes" .
"$D8" --min-api 33 --release --lib "$ANDROID_JAR" --classpath "$API_JAR" \
  --output "$OUT/dex" "$OUT/classes.jar"

echo "== 5/6 package dex + xposed metadata =="
"$PY" - "$OUT" "$META" <<'PY'
import sys, zipfile, os, shutil
out, meta = sys.argv[1], sys.argv[2]
src = os.path.join(out, 'base.apk')
dst = os.path.join(out, 'unsigned.apk')
shutil.copyfile(src, dst)
with zipfile.ZipFile(dst, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(os.path.join(out, 'dex', 'classes.dex'), 'classes.dex')
    for name in ('module.prop', 'java_init.list', 'scope.list'):
        z.write(os.path.join(meta, name), 'META-INF/xposed/' + name)
with zipfile.ZipFile(dst) as z:
    print('entries:', ', '.join(z.namelist()))
PY

echo "== 6/6 keystore, align, sign, verify =="
if [ ! -e "$KS" ]; then
  "$JDK/bin/keytool" -genkeypair -alias magicglass -keyalg RSA -keysize 2048 \
    -validity 10000 -keystore "$KS" \
    -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -dname "CN=MagicGlass, O=magicglass, L=magicos" >/dev/null 2>&1
  echo "created keystore $KS"
fi
"$ZIPALIGN" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
SIGNED="$OUT/MagicGlass-$VERSION.apk"
"$APKSIGNER" sign --ks "$KS" --ks-pass "pass:$KS_PASS" \
  --key-pass "pass:$KS_PASS" \
  --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true \
  --out "$SIGNED" "$OUT/aligned.apk"
"$APKSIGNER" verify --print-certs "$SIGNED" | head -8

echo ""
echo "OUTPUT: $SIGNED"
echo "SIZE:   $(stat -c%s "$SIGNED") bytes"
echo "SHA256: $(sha256sum "$SIGNED" | cut -d' ' -f1)"
