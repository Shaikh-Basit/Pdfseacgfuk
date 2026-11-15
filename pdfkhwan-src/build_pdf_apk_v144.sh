#!/bin/bash
# v1.44 (versionCode 46): CRASH FIX + real multi-step undo/redo.
#   - Fixed the ClassCastException crash (LinearLayout$LayoutParams cannot be
#     cast to FrameLayout$LayoutParams) that killed the app the moment you
#     opened the pen/marker/eraser settings popup (toolSettingsDialog) or the
#     size popup (penSizePopup): the stroke-preview View lives inside a
#     LinearLayout, so its LayoutParams are read via the base
#     ViewGroup.LayoutParams now - no cast, no crash, on any tool.
#   - Undo/Redo rebuilt as a true multi-step history engine (op-based,
#     before/after snapshots per annotation, up to 120 steps):
#       * draw as many strokes/markers/arrows as you like - undo walks them
#         back one by one, redo replays them one by one (previously redo
#         silently collapsed to a single step - fixed)
#       * the ERASER is now undoable: whole-line erases restore the strokes,
#         partial erases restore the original stroke (pieces created by a
#         split are removed again automatically); one finger swipe = ONE undo
#         step, not dozens
#       * deleting an annotation (context menu, note editor, bulk delete in
#         the annotation list) and adding text highlights/underlines/notes is
#         undoable too
#       * undo/redo buttons dim when their stack is empty
#       * history resets when a different document is opened
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
OUT=$BASE/out-pdf44
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
for abi in armeabi-v7a arm64-v8a x86 x86_64; do
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
  --out "$OUT/PDFKhwan-v1.44.apk" \
  "$OUT/aligned.apk"

echo "==> [8/8] verify"
"$BT/apksigner" verify --print-certs "$OUT/PDFKhwan-v1.44.apk" | head -4
ls -la "$OUT/PDFKhwan-v1.44.apk"
echo "BUILD OK"
