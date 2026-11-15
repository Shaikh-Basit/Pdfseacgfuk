#!/bin/bash
# v1.42 (versionCode 44): LibreOffice-built PDF text engine — TJ kern sign
#   per PDF spec, marks-run sentinels snap to real holes, wide-glyph char
#   boxes redistributed, positionally-inconsistent lines rebuilt as one
#   fragment, word-merge pass (هٔ / جانبی / نیم‌فاصله pairs) + punct attach,
#   Word export: no kashida-justify hang, cross-line spaces, leading-only trim.
# v1.40 (versionCode 42): protected & password-protected PDFs fully open
#   (AES-256 via PDFBox decrypt-to-temp + password dialog for every scheme),
#   text selection pre-warmed at document load (no "preparing" wait),
#   marker palette yellow/red/blue/black always first, and same-color
#   markers merged into ONE draw call (dark precise highlight that never
#   darkens text or hides lines no matter how many times you re-stroke).
# v1.39 (versionCode 41): double-tap zoom rebuilt as the classic two-level
#   model — level 1 (one zoom-in step, 100% -> 250% centred on the finger)
#   is ALWAYS ON by default; level 2 (the extreme second jump to 8x while
#   already zoomed) is OFF by default and opt-in via the menu switch
#   «زوم حداکثری با دو ضربه».
# v1.38 (versionCode 40): marker-style multiply highlights, floating dockable
#   pen toolbar (Samsung-Notes style), page-flip mode actually flips (fit-both
#   + real zoom test), redesigned page-mode sheet, layout-preserving PDF->Word
#   export (fonts / sizes / direction / paragraphs from the pdf.js word stream).
# ============================================================
# PDFKhwan v1.31 build — deep re-audit: engine round-trip suite on 46 real internet files.
# v1.30 (see CHANGELOG-v1.29.md § v1.30 for the complete list):
#   - PPTX reader: color modifiers (tint/shade/alpha/satMod)
#     now apply to their OWN color element (were leaking onto
#     the next color — wrong colors on themed decks)
#   - PPTX writer: alpha channel round-trips via <a:alpha>
#     (semi-transparent fills/lines/text were saved opaque)
#   - UTF-8 BOM parts no longer kill a whole slide/master
#   - SmartArt: diagramDrawing rel found on the slide too
#   - txBox honored; run fonts survive open->save cycles
#   - picture frame geometry + fill/line round-trip
#   - linked (external) media round-trips as video
#   - full-app compile: 528 classes, 0 errors; round-trip
#     suite on 16 real internet files: 96 checks, 0 failures
# ============================================================
# v1.29 history (kept for reference):
#   - PPTX engine: color modifier scale fixed (lumMod/shade/tint/alpha
#     were off by 100000x), satMod hue math rewritten, internal
#     hyperlink targets no longer misread as URLs, table borders no
#     longer swap T/R on save, normAutofit double-scaling removed,
#     picture-filled shapes emit ONE fill element (schema-valid)
#   - NEW FEATURE: "go to slide" action links now work in the
#     slideshow and round-trip through the .pptx writer
#   - EMF/WMF: every created pen/brush/text visible again (COLORREF
#     alpha), STRETCHDIBITS/POLYPOLYGON offsets corrected, stock
#     object categories, +5 crafted-file crash paths closed
#   - JPEG/TIFF decoders: progressive 4:2:0 scan grid, DHT stride,
#     16-bit RGB predictor, pixel caps
#   - pdf.js text engine: TD / T* / "" operators restored (search,
#     selection, copy, TTS correctness on classic producer PDFs)
#   - editor: slideshow links + layout cache invalidation, SAF
#     atomic saves, autosave/destroy race fixed, video insert fixed,
#     background loads, crop undoable, dialog cleanup
#   - reader: surprise-TTS/selection disarm, wrong-password feedback,
#     zoom-out restore, dialog leaks, TOC entries without destinations
#   - shell: ACTION_EDIT handled, .pps opens, share cache correctness,
#     PdfRenderer close order, GB sizes
#
# v1.21..v1.28 history: see the old header notes in the repo
#   (PDF text engine v6.7, PPT notes/hyperlinks/letter-spacing,
#   display + autosave + slideshow fixes).
# ============================================================
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
OUT=$BASE/out-pdf43
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
  --version-code 45 \
  --version-name 1.43 \
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
  --out "$OUT/PDFKhwan-v1.43.apk" \
  "$OUT/aligned.apk"

echo "==> [8/8] verify"
"$BT/apksigner" verify --print-certs "$OUT/PDFKhwan-v1.43.apk" | head -4
ls -la "$OUT/PDFKhwan-v1.43.apk"
echo "BUILD OK"
