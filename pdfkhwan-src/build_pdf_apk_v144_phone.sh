#!/bin/bash
# v1.44-phone (versionCode 46, same app - LIGHT BUILD FOR REAL PHONES):
#   identical features to v1.44, but packaged with the two ARM ABIs only
#   (armeabi-v7a + arm64-v8a) - every real phone is covered - and WITHOUT
#   the x86 / x86_64 emulator libraries, cutting the APK from ~15.4 MB to
#   ~9-10 MB: faster to download, faster to install, faster to test.
#   (The full 4-ABI build remains available via build_pdf_apk_v144.sh.)
#
set -e

# resolve the project dir next to this script so the build works
# wherever the source tree is extracted
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJ="$SCRIPT_DIR/pdfkhwan"
[ -d "$PROJ" ] || PROJ="$SCRIPT_DIR"

BASE=${ANDROID_BUILD_DIR:-/tmp/my-project/android-build}
SDK=$BASE/sdk
BT=$SDK/build-tools/34.0.0
PLATFORM=$SDK/platforms/android-34/android.jar
OUT=$BASE/out-pdf44-phone
PDFBOX=$BASE/libs/pdfbox/aar/classes.jar

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/native"

echo "==> [1/8] aapt2 compile resources"
"$BT/aapt2" compile --dir "$PROJ/res" -o "$OUT/res.zip"

echo "==> [2/8] aapt2 link"
mkdir -p "$OUT/gen"
"$BT/aapt2" link \
  -o "$OUT/base.apk" \
  -I "$PLATFORM" \
  --manifest "$PROJ/AndroidManifest.xml" \
  -A "$PROJ/assets" \
  --java "$OUT/gen" \
  --min-sdk-version 21 \
  --target-sdk-version 34 \
  --version-code 46 \
  --version-name 1.44 \
  --auto-add-overlay \
  "$OUT/res.zip"

echo "==> [3/8] javac compile (app + viewer + ppt module + pdfbox; android.jar on classpath)"
find "$PROJ/src" "$OUT/gen" -name "*.java" > "$OUT/sources.txt"
if ! "$BASE/jdk/bin/javac" -source 8 -target 8 \
  -bootclasspath "$PLATFORM" \
  -classpath "$PLATFORM:$PDFBOX" \
  -d "$OUT/classes" \
  -encoding UTF-8 \
  -nowarn \
  @"$OUT/sources.txt" > "$OUT/compile.log" 2>&1; then
  echo "COMPILE FAILED:"
  cat "$OUT/compile.log"
  exit 1
fi
if grep -qiE "error:" "$OUT/compile.log"; then
  echo "COMPILE ERRORS:"; cat "$OUT/compile.log"; exit 1
fi
echo "compile clean: $(find "$OUT/classes" -name '*.class' | grep -c '\.class$') classes"

echo "==> [4/8] d8 dex (app + viewer + pdfium binding + pdfbox)"
"$BT/d8" --release \
  --lib "$PLATFORM" \
  --min-api 21 \
  --output "$OUT/dex" \
  $(find "$OUT/classes" -name "*.class") \
  "$PDFBOX"
ls -la "$OUT/dex/"

echo "==> [5/8] package dex + native libs into APK"
cd "$OUT/dex" && zip -q -u "$OUT/base.apk" classes*.dex && cd "$OUT"
for abi in armeabi-v7a arm64-v8a; do
  mkdir -p "$OUT/native/lib/$abi"
  cp "$BASE/libs/pdfium-aar/jni/$abi/"*.so "$OUT/native/lib/$abi/"
done
cd "$OUT/native" && zip -q -u "$OUT/base.apk" lib/*/*.so && cd "$OUT"
echo "APK contents (dex+lib):"
unzip -l "$OUT/base.apk" | grep -E "classes|lib/" | head -20

echo "==> [6/8] zipalign (page-align native libs)"
"$BT/zipalign" -f -p 4 "$OUT/base.apk" "$OUT/aligned.apk"

echo "==> [7/8] sign"
KS=$BASE/qrsaz.keystore
"$BT/apksigner" sign \
  --ks "$KS" --ks-key-alias qrsaz \
  --ks-pass pass:qrsaz2025key --key-pass pass:qrsaz2025key \
  --out "$OUT/PDFKhwan-v1.44-phone.apk" \
  "$OUT/aligned.apk"

echo "==> [8/8] verify"
"$BT/apksigner" verify --print-certs "$OUT/PDFKhwan-v1.44-phone.apk" | head -4
ls -la "$OUT/PDFKhwan-v1.44-phone.apk"
echo "BUILD OK"
