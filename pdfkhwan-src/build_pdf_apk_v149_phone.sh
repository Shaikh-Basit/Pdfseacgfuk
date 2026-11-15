#!/bin/bash
# v1.49-phone (versionCode 51 - LIGHT BUILD FOR REAL PHONES, 2 ARM ABIs):
#   NEW in v1.49 (TTS reliability + reading-order release on top of v1.48):
#     - FIX: RemoteServiceException crash (Context.startForegroundService() did
#       not call startForeground) when pressing "read selected text" — the
#       service now calls startForeground() in onCreate() at the earliest
#       possible moment, before any session work that could fail.
#     - FIX: app/PDF silently closing while reading — extraction failures now
#       disarm the pending-read state (no more stuck "preparing text…"),
#       stop reading cleanly, and SHOW the real error message; a 30s watchdog
#       timeout ends the wait if page words never arrive.
#     - FIX: view jumping to page 1 while reading page N (reading continued on
#       the right page, but the display jumped to the first page): TTS page
#       turns now cancel in-flight scroll/fling animations first and jump
#       without animation, so the 400ms animation can no longer race with the
#       sentence-centering move.
#     - FIX: "read selected text" now reads ONLY the selected range (up to the
#       end of the last sentence that touches the selection) and then stops,
#       instead of reading the whole page from the selection start.
#     - FIX: Persian amir voice pauses after every '.' (slow start of next
#       sentence) and cuts the last 1-2 words of each sentence:
#       (a) audio now drains fully before "done" (AudioTrack write only blocks
#           until buffered, not until audible — the next sentence's stopTrack
#           used to flush the not-yet-played tail);
#       (b) the next sentence is now generated in the background WHILE the
#           current one plays (prefetch cache) — inter-sentence gap ~0;
#       (c) sentence mid-break line-cut raised 30 -> 60 words.
#     - FIX: two-column books: words are now re-ordered into COLUMN reading
#       order (all of column 1 line by line, then column 2) — selection,
#       copy, search and voice reading all follow the true reading order
#       instead of grabbing equal lines from both columns.
#   Carried over from v1.47/v1.48 (sections 18-21): paper modes, brightness,
#   auto-scroll, 3-state fit; sentence-by-sentence reading with highlight,
#   background media notification; voice model manager (amir/ryan/lessac/alan),
#   SAF import, resumable SHA-256 downloads; per-language engine choice.
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
OUT=$BASE/out-pdf49-phone
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
  --version-code 51 \
  --version-name 1.49 \
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
  --out "$OUT/PDFKhwan-v1.49-phone.apk" \
  "$OUT/aligned.apk"

echo "==> [8/8] verify"
"$BT/apksigner" verify --print-certs "$OUT/PDFKhwan-v1.49-phone.apk" | head -4
ls -la "$OUT/PDFKhwan-v1.49-phone.apk"
echo "BUILD OK"
