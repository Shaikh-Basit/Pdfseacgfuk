#!/bin/bash
# v1.53-phone (versionCode 153 - PERSIAN/RTL ENGINE, SELECTION/COPY & DOCX FIXES):
#   NEW in v1.53:
#     - PkEngine (assets/pdfjs/search.html): fixed mixed single-fragment RTL
#       classification, PUA stripping & Presentation Form / Lam-Alef char-box
#       splitting, diacritics attachment before reversal, inter-fragment pair
#       cuts (_uf guard), affixJoin ('م|ی' pre-pass, real Unicode escapes,
#       no 'ی'->'می' hallucination), tightened fragment glue & RTL 2-column order.
#     - RTL Selection & Copy (ReaderActivity.java): fixed partial RTL word
#       highlight/bounds rect inversion, mixed-script handle drag index ordering,
#       per-char edge handling (charEdgeRtl), dropped-word gap/zg carry, and
#       RTL Right-before-Left two-column ordering.
#     - PDF -> Word (PdfExport.java): split strong-script runs so Latin & digits
#       never receive <w:rtl/>, explicit <w:jc w:val="right"/> + <w:bidi/> on
#       RTL sections, expanded Persian font map + Latin fallback on Persian-only
#       fonts, 1.5x line-spacing paragraph threshold, column order preservation.
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJ="$SCRIPT_DIR/pdfkhwan"
[ -d "$PROJ" ] || PROJ="$SCRIPT_DIR"

BASE=${ANDROID_BUILD_DIR:-/tmp/my-project/android-build}
SDK=$BASE/sdk
BT=$SDK/build-tools/34.0.0
PLATFORM=$SDK/platforms/android-34/android.jar
OUT=$BASE/out-pdf53-phone
PDFBOX=$BASE/libs/pdfbox/aar/classes.jar
SHERPA=$BASE/libs/sherpa/sherpa-classes.jar
KOTLIN=$BASE/libs/sherpa/kotlin-stdlib.jar

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
  --version-code 153 \
  --version-name 1.53 \
  --auto-add-overlay \
  "$OUT/res.zip"

echo "==> [3/8] javac compile (app + viewer + ppt + tts + pdfbox + sherpa; android.jar on classpath)"
find "$PROJ/src" "$OUT/gen" -name "*.java" > "$OUT/sources.txt"
if ! "$BASE/jdk/bin/javac" -source 8 -target 8 \
  -bootclasspath "$PLATFORM" \
  -classpath "$PLATFORM:$PDFBOX:$SHERPA:$KOTLIN" \
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

echo "==> [4/8] d8 dex (app + pdfium binding + pdfbox + sherpa + kotlin-stdlib)"
"$BT/d8" --release \
  --lib "$PLATFORM" \
  --min-api 21 \
  --output "$OUT/dex" \
  $(find "$OUT/classes" -name "*.class") \
  "$PDFBOX" "$SHERPA" "$KOTLIN"
ls -la "$OUT/dex/"

echo "==> [5/8] package dex + native libs into APK"
cd "$OUT/dex" && zip -q -u "$OUT/base.apk" classes*.dex && cd "$OUT"
for abi in armeabi-v7a arm64-v8a; do
  mkdir -p "$OUT/native/lib/$abi"
  cp "$BASE/libs/pdfium-aar/jni/$abi/"*.so "$OUT/native/lib/$abi/"
  cp "$BASE/libs/sherpa/jni/$abi/"*.so "$OUT/native/lib/$abi/"
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
  --out "$OUT/PDFKhwan-v1.53-phone.apk" \
  "$OUT/aligned.apk"

echo "==> [8/8] verify"
"$BT/apksigner" verify --print-certs "$OUT/PDFKhwan-v1.53-phone.apk" | head -4
ls -la "$OUT/PDFKhwan-v1.53-phone.apk"
echo "BUILD OK"
