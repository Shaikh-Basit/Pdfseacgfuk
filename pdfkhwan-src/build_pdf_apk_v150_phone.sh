#!/bin/bash
# v1.50-phone (versionCode 52 - LIGHT BUILD FOR REAL PHONES, 2 ARM ABIs):
#   NEW in v1.50 (TOC/links + UI polish + annotation round-trip):
#     - FIX: Table of contents (outline) — most books listed nothing (or the
#       panel felt like it "closed by itself"): when pdfium resolves no
#       outline entries (Named/RemoteGoTo destinations, Adobe-written XRefs),
#       the loader now falls back to PDFBox's outline walker which resolves
#       every destination type; the panel always shows the real list.
#     - FIX: tapping a TOC entry / an internal PDF link now jumps to that
#       exact page (request #16): in-flight scroll animations are cancelled
#       first and the jump is instant & final (no animation race, no stuck
#       half-rendered page, position is saved).
#     - FIX: read-aloud floating bar redesigned — prev / play-pause / next /
#       stop are properly centered in one even row with round touch targets
#       (the close button no longer hugs the edge).
#     - FIX: all menus & sheets now keep a standard margin from the screen
#       edges (bottom sheets, centered cards, and the full-screen search/TOC/
#       thumbnails panels) — on bezel/glass phones content no longer bleeds
#       under the edges; the display-layout sheet in particular.
#     - FIX: exported annotated PDFs opened in OTHER apps could be edited but
#       re-opening the SAME exported file inside PDFKhwan showed no
#       annotations — standard PDF annotations (Ink, TextMarkup, Sticky) found
#       in an opened file are now imported and fully editable in-app.
#     - FIX: stroke widths in exported PDFs were up to 20x thicker than what
#       the user drew (raw device density was used as dp->pt factor). The
#       converter now computes the true pt-per-dp from the actual page size
#       vs view width — a 26dp marker exports as ~10pt, not 72pt.
#     - All remaining legacy system AlertDialogs modernized (password prompt,
#       open-error, all-files permission, no-engine notice, engine picker).
#   Carried over from v1.47-v1.49: paper modes, brightness, auto-scroll,
#   3-state fit; sentence-by-sentence reading with highlight + background
#   media notification; voice model manager (amir/ryan/lessac/alan) with
#   resumable SHA-256 downloads + SAF import; per-language engine choice;
#   column reading order for two-column books; TTS reliability fixes
#   (foreground service, watchdog, prefetch + full audio drain).
#   Engine: sherpa-onnx 1.12.15 (static-link AAR) + kotlin-stdlib.
#   Packaging: armeabi-v7a + arm64-v8a (phone build).
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
OUT=$BASE/out-pdf50-phone
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
  --version-code 52 \
  --version-name 1.50 \
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
  --out "$OUT/PDFKhwan-v1.50-phone.apk" \
  "$OUT/aligned.apk"

echo "==> [8/8] verify"
"$BT/apksigner" verify --print-certs "$OUT/PDFKhwan-v1.50-phone.apk" | head -4
ls -la "$OUT/PDFKhwan-v1.50-phone.apk"
echo "BUILD OK"
