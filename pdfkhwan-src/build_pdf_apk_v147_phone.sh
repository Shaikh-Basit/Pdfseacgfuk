#!/bin/bash
# v1.47-phone (versionCode 49 - LIGHT BUILD FOR REAL PHONES, 2 ARM ABIs):
#   NEW in v1.47 (roadmap sections 18, 19, 20, 21):
#     - Section 18 (Advanced layout): RTL two-page book mode, orientation lock,
#       paper modes (white/sepia/night), independent brightness, auto-scroll
#       with speed, 3-state fit cycle (width/page/actual size).
#     - Section 19 (TTS reading): sentence-by-sentence reading from selection
#       or page start, live sentence highlight, play/pause, 0.5x-2x speed,
#       prev/next sentence, auto page turns, background reading with a
#       media-style notification.
#     - Section 20 (Voice models): in-app model manager - Persian amir (default)
#       + English ryan/lessac/alan, resumable downloads with SHA-256 checks,
#       SAF import with precise guidance, validation incl. a real test
#       synthesis before a voice is marked ready.
#     - Section 21 (Engine choice): per-language engine (Piper or system TTS),
#       mixed-script texts switch engine per sentence, preloading on panel
#       open, graceful guidance when no engine exists.
#   Engine: sherpa-onnx 1.12.15 (static-link AAR, one .so per ABI, no
#   libc++_shared needed) + kotlin-stdlib for its Java API.
#   Packaging: armeabi-v7a + arm64-v8a (phone build, like v1.46).
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
OUT=$BASE/out-pdf47-phone
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
  --version-code 49 \
  --version-name 1.47 \
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
  --out "$OUT/PDFKhwan-v1.47-phone.apk" \
  "$OUT/aligned.apk"

echo "==> [8/8] verify"
"$BT/apksigner" verify --print-certs "$OUT/PDFKhwan-v1.47-phone.apk" | head -4
ls -la "$OUT/PDFKhwan-v1.47-phone.apk"
echo "BUILD OK"
