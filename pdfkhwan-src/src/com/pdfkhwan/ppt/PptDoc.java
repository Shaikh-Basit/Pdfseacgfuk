package com.pdfkhwan.ppt;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/* ============================================================================
   PptDoc v4 (v1.22) — the PowerPoint document model.

   v4 extends v3 with the pieces a REAL PowerPoint deck needs:
     - Chart shapes (bar/col/stacked/horizontal, line, pie, doughnut,
       area, scatter) with series/categories/colors/titles/legend —
       parsed from real chart parts, rendered by the canvas, and
       written back as valid chart XML other apps can edit
     - Video shapes carry their media bytes + poster frame + format
       so embedded movies survive a round-trip and can be played
     - slide transitions (fade/push/...) kept per slide
   v3 (v1.17) properties kept: paragraph levels, real line spacing,
   bullets, run caps/tracking, master/layout inheritance, autofit,
   gradients, per-slide bgShapes, theme fonts.

   The old TYPE_* constants keep their numeric values: ReaderActivity's
   menu switch uses them as compile-time constants.
   ============================================================================ */
public final class PptDoc implements Serializable {
    private static final long serialVersionUID = 4;

    public static final int DEFAULT_SLIDE_W = 12192000;   // 16:9, EMU
    public static final int DEFAULT_SLIDE_H = 6858000;
    public static final int EMU_PER_INCH = 914400;
    public static final int EMU_PER_PT = 12700;

    public int slideW = DEFAULT_SLIDE_W;
    public int slideH = DEFAULT_SLIDE_H;
    public final List<Slide> slides = new ArrayList<>();

    /* theme accents chosen in the Design tab (writer emits them into the
       theme part so other apps see matching colors) */
    public int themeBgTop = 0xFFFFFFFF;
    public int themeBgBottom = 0xFFF4F7FB;
    public int themeAccent = 0xFF2E75B6;

    /* theme font scheme (from fontScheme/majorFont|minorFont latin) */
    public String fontMajor = null;
    public String fontMinor = null;

    public Slide newSlide() {
        Slide s = new Slide();
        slides.add(s);
        return s;
    }

    /* ------------------------------------------------------------------ fill */
    public static final class Fill implements Serializable {
        private static final long serialVersionUID = 3;
        public static final int NONE = 0;
        public static final int SOLID = 1;
        public static final int GRADIENT = 2;
        public int type = NONE;
        public int color = 0;          // solid color or gradient top
        public int color2 = 0;         // gradient bottom
        public boolean gradHorizontal = false;
        public float gradAngleDeg = -1f;   // a:lin ang / 60000; -1 = default vertical

        public static Fill solid(int c) {
            Fill f = new Fill();
            f.type = SOLID;
            f.color = c;
            return f;
        }
        public static Fill gradient(int top, int bottom, boolean horizontal) {
            Fill f = new Fill();
            f.type = GRADIENT;
            f.color = top;
            f.color2 = bottom;
            f.gradHorizontal = horizontal;
            return f;
        }
        public boolean isEmpty() { return type == NONE; }
    }

    /* ------------------------------------------------------------------ line */
    public static final class Line implements Serializable {
        private static final long serialVersionUID = 3;
        public boolean enabled = false;
        public int color = 0xFF404040;
        public float widthPt = 1.0f;
        public int dash = 0;           // 0 solid, 1 dash, 2 dot, 3 dashDot, 4 sysDash
    }

    /* ------------------------------------------------------------------ text */
    public static final class Run implements Serializable {
        private static final long serialVersionUID = 3;
        public String text = "";
        public float sizePt = 18f;
        public boolean bold = false;
        public boolean italic = false;
        public boolean underline = false;
        public int color = 0xFF1F1F1F;
        public String font = null;     // display name or null = default
        public int caps = 0;           // 0 none, 1 all, 2 small
        public float spcPt = Float.NaN; // letter spacing pt (rPr/@spc / 100)
        public int fld = 0;            // 0 none, 1 slidenum, 2 other field
        public boolean link = false;   // a:hlinkClick present → hyperlink run
        public String linkUrl = null;  // resolved external target (null = internal jump)
        /* v1.29 NEW: "go to slide N" internal jumps (action links) — 1-based
           slide number; -1 = none. Round-trips through the writer and is
           honored by the slideshow (tap → jump to that slide). */
        public int linkSlide = -1;

        /* explicit-set flags — the reader uses these to know which values
           came from the file and which must be inherited through the
           layout/master chain (unset at parse time, filled by resolver) */
        public boolean hasSize, hasColor, hasBold, hasItalic, hasUnder, hasFont;

        public void copyFrom(Run r) {
            text = r.text; sizePt = r.sizePt; bold = r.bold; italic = r.italic;
            underline = r.underline; color = r.color; font = r.font;
            caps = r.caps; spcPt = r.spcPt; fld = r.fld;
            link = r.link; linkUrl = r.linkUrl; linkSlide = r.linkSlide;
            hasSize = r.hasSize; hasColor = r.hasColor; hasBold = r.hasBold;
            hasItalic = r.hasItalic; hasUnder = r.hasUnder; hasFont = r.hasFont;
        }
    }

    public static final class Para implements Serializable {
        private static final long serialVersionUID = 3;
        public static final int ALIGN_L = 0;
        public static final int ALIGN_CTR = 1;
        public static final int ALIGN_R = 2;
        public static final int ALIGN_JUST = 3;
        public int align = ALIGN_L;
        public boolean rtl = false;        // paragraph base direction
        public boolean bullet = false;     // editor-side bullet flag
        public float spaceBeforePt = 0f;
        public final List<Run> runs = new ArrayList<>();

        /* paragraph level 0..8 (pPr/@lvl) */
        public int lvl = 0;
        /* line spacing: pct (1.0 = single) or exact pt; NaN/-1 = unset */
        public float lnSpcPct = -1f;
        public float lnSpcPt = -1f;
        public float spcBefPt = -1f, spcAftPt = -1f;
        /* v1.27: spcBef/spcAft as PERCENT of font size (a:spcPct) — converted
           to points at commit time using the resolved first-run size */
        public float spcBefPct = -1f, spcAftPct = -1f;
        /* hanging indent model: text at marL, bullet hangs at marL+indent */
        public int marL = 0, indent = 0;
        /* bullet definition; null char + !bullet + !autoNum = none */
        public String buChar = null;
        public boolean buAutoNum = false;
        public int buClr = 0;              // 0 = inherit text color
        public float buSizePt = -1f;       // -1 = follow text (a:buSzPts)
        /* v1.24: bullet size as percent of text size (a:buSzPct);
           -1 = unset (falls back to buSizePt, then 100% of text) */
        public float buSzPct = -1f;
        /* v1.24: numbering format (a:buAutoNum/@type) + start index
           (a:buAutoNum/@startAt). Constants NUM_* below. */
        public int buType = 0;
        public int buStart = 1;

        /* v1.24: paragraph-level default run properties captured from
           a:pPr/a:defRPr (applied to runs without explicit rPr attrs at
           </a:p> time by the reader). -1/0/null = unset. */
        public float defSzPt = -1f;
        public int defBold = -1, defItalic = -1, defUnder = -1, defColor = 0;
        public String defFont = null;

        /* ---- v1.24: a:buAutoNum/@type values (subset that matters) ---- */
        public static final int NUM_ARABIC_PERIOD = 0;     // 1.
        public static final int NUM_ARABIC_PAREN_R = 1;    // 1)
        public static final int NUM_ARABIC_DASH = 2;       // 1-
        public static final int NUM_ALPHA_LC_PERIOD = 3;   // a.
        public static final int NUM_ALPHA_UC_PERIOD = 4;   // A.
        public static final int NUM_ALPHA_LC_PAREN_R = 5;  // a)
        public static final int NUM_ALPHA_UC_PAREN_R = 6;  // A)
        public static final int NUM_ROMAN_LC_PERIOD = 7;   // i.
        public static final int NUM_ROMAN_UC_PERIOD = 8;   // I.
        public static final int NUM_ROMAN_LC_PAREN_R = 9;  // i)
        public static final int NUM_ROMAN_UC_PAREN_R = 10; // I)
        /* v1.26: formats used by real Persian/Arabic decks (PowerPoint's
           «hindi» = Arabic-script numerals). Without these, decks made in
           other projects fell back to Western 1. 2. 3. */
        public static final int NUM_HINDI_PERIOD = 11;     // ۱.
        public static final int NUM_HINDI_PAREN_R = 12;    // ۱)
        public static final int NUM_HINDI_DASH1 = 13;      // ۱-
        public static final int NUM_HINDI_NUMBERS = 14;    // ۱ ۲ ۳ (bare)
        public static final int NUM_ARABIC_ABJAD = 15;     // ا ب ج (abjad letters)
        public static final int NUM_ALPHA_FA = 16;         // ا ب پ (Persian letters)

        /* OOXML type string for a buType constant (writer) */
        public static String buTypeName(int t) {
            switch (t) {
                case NUM_ARABIC_PAREN_R: return "arabicParenR";
                case NUM_ARABIC_DASH: return "arabicDash";
                case NUM_ALPHA_LC_PERIOD: return "alphaLcPeriod";
                case NUM_ALPHA_UC_PERIOD: return "alphaUcPeriod";
                case NUM_ALPHA_LC_PAREN_R: return "alphaLcParenR";
                case NUM_ALPHA_UC_PAREN_R: return "alphaUcParenR";
                case NUM_ROMAN_LC_PERIOD: return "romanLcPeriod";
                case NUM_ROMAN_UC_PERIOD: return "romanUcPeriod";
                case NUM_ROMAN_LC_PAREN_R: return "romanLcParenR";
                case NUM_ROMAN_UC_PAREN_R: return "romanUcParenR";
                case NUM_HINDI_PERIOD: return "hindiPeriod";
                case NUM_HINDI_PAREN_R: return "hindiParenR";
                case NUM_HINDI_DASH1: return "hindiDash1";
                case NUM_HINDI_NUMBERS: return "hindiNumbers";
                case NUM_ARABIC_ABJAD: return "hindiAlpha1Period";   /* v1.27 FIX: correct OOXML token (case-sensitive) */
                case NUM_ALPHA_FA: return "hindiAlphaPeriod";
                default: return "arabicPeriod";
            }
        }

        /* buType constant for an OOXML type string (reader) */
        public static int parseBuType(String s) {
            if (s == null) return NUM_ARABIC_PERIOD;
            if ("arabicParenR".equals(s)) return NUM_ARABIC_PAREN_R;
            if ("arabicDash".equals(s)) return NUM_ARABIC_DASH;
            if ("alphaLcPeriod".equals(s)) return NUM_ALPHA_LC_PERIOD;
            if ("alphaUcPeriod".equals(s)) return NUM_ALPHA_UC_PERIOD;
            if ("alphaLcParenR".equals(s)) return NUM_ALPHA_LC_PAREN_R;
            if ("alphaUcParenR".equals(s)) return NUM_ALPHA_UC_PAREN_R;
            if ("romanLcPeriod".equals(s)) return NUM_ROMAN_LC_PERIOD;
            if ("romanUcPeriod".equals(s)) return NUM_ROMAN_UC_PERIOD;
            if ("romanLcParenR".equals(s)) return NUM_ROMAN_LC_PAREN_R;
            if ("romanUcParenR".equals(s)) return NUM_ROMAN_UC_PAREN_R;
            if ("alphaLcDash".equals(s) || "alphaUcDash".equals(s)) return NUM_ARABIC_DASH;
            if ("hebrewPeriod".equals(s) || "hebrewDash".equals(s)) return NUM_ARABIC_DASH;
            /* v1.26: hindi family + Persian/Arabic letter lists */
            if ("hindiPeriod".equals(s) || "hindi1Period".equals(s)) return NUM_HINDI_PERIOD;
            if ("hindiParenR".equals(s) || "hindiNumParenR".equals(s)) return NUM_HINDI_PAREN_R;
            if ("hindiDash1".equals(s) || "hindiNum1Dash".equals(s)) return NUM_HINDI_DASH1;
            if ("hindiNumbers".equals(s)) return NUM_HINDI_NUMBERS;
            if ("hindiAlpha1Period".equals(s) || "hindiAlpha1period".equals(s)) return NUM_ARABIC_ABJAD;
            if ("hindiAlphaPeriod".equals(s) || "hindiAlphabetPeriod".equals(s)) return NUM_ALPHA_FA;
            /* double-byte / EA schemes map to close Latin equivalents */
            if ("arabicDbPeriod".equals(s) || "ea1Period".equals(s)
                    || "thaiPeriod".equals(s) || "thai1Period".equals(s)
                    || "vietPeriod".equals(s)) return NUM_ARABIC_PERIOD;
            if ("arabicDbParenR".equals(s) || "ea1ParenR".equals(s)
                    || "thaiParenR".equals(s) || "thai1ParenR".equals(s)
                    || "vietParenR".equals(s)) return NUM_ARABIC_PAREN_R;
            if ("ea1Dash".equals(s) || "thaiDash".equals(s) || "thai1Dash".equals(s)
                    || "vietDash".equals(s) || "arabic1Minus".equals(s)
                    || "arabic2Minus".equals(s) || "hebrew2Minus".equals(s)) return NUM_ARABIC_DASH;
            if ("koreanPeriod".equals(s) || "japanesePeriod".equals(s)
                    || "japaneseKoreanPeriod".equals(s)) return NUM_ALPHA_LC_PERIOD;
            if ("koreanParenR".equals(s) || "japaneseParenR".equals(s)
                    || "japaneseKoreanParenR".equals(s)) return NUM_ALPHA_LC_PAREN_R;
            if ("koreanDash".equals(s) || "japaneseDash".equals(s)
                    || "japaneseKoreanDash".equals(s)) return NUM_ARABIC_DASH;
            return NUM_ARABIC_PERIOD;
        }

        /* explicit-set flags for inheritance */
        public boolean hasAlign, hasRtl, hasLnSpc, hasSpcBef, hasSpcAft;
        public boolean hasMarL, hasIndent, hasBullet;

        public String plainText() {
            StringBuilder sb = new StringBuilder();
            for (Run r : runs) sb.append(r.text);
            return sb.toString();
        }
        public Run firstRun() { return runs.isEmpty() ? null : runs.get(0); }

        /* dominant formatting of the paragraph (first run / defaults) — used
           when the editor applies a style to a whole paragraph */
        public Run styleProto() {
            Run p = new Run();
            if (!runs.isEmpty()) {
                Run f = runs.get(0);
                p.sizePt = f.sizePt;
                p.bold = f.bold;
                p.italic = f.italic;
                p.underline = f.underline;
                p.color = f.color;
                p.font = f.font;
            }
            return p;
        }
        public void applyToRuns(int what, float value, int color, String font) {
            for (Run r : runs) {
                switch (what) {
                    case 1: r.sizePt = value; break;
                    case 2: r.bold = value > 0.5f; break;
                    case 3: r.italic = value > 0.5f; break;
                    case 4: r.underline = value > 0.5f; break;
                    case 5: r.color = color; break;
                    case 6: r.font = font; break;
                }
            }
        }

        public void copyFrom(Para p) {
            align = p.align; rtl = p.rtl; bullet = p.bullet; spaceBeforePt = p.spaceBeforePt;
            lvl = p.lvl; lnSpcPct = p.lnSpcPct; lnSpcPt = p.lnSpcPt;
            spcBefPt = p.spcBefPt; spcAftPt = p.spcAftPt; spcBefPct = p.spcBefPct; spcAftPct = p.spcAftPct;
            marL = p.marL; indent = p.indent;
            buChar = p.buChar; buAutoNum = p.buAutoNum; buClr = p.buClr; buSizePt = p.buSizePt;
            buSzPct = p.buSzPct; buType = p.buType; buStart = p.buStart;
            defSzPt = p.defSzPt; defBold = p.defBold; defItalic = p.defItalic;
            defUnder = p.defUnder; defColor = p.defColor; defFont = p.defFont;
            hasAlign = p.hasAlign; hasRtl = p.hasRtl; hasLnSpc = p.hasLnSpc;
            hasSpcBef = p.hasSpcBef; hasSpcAft = p.hasSpcAft;
            hasMarL = p.hasMarL; hasIndent = p.hasIndent; hasBullet = p.hasBullet;
            runs.clear();
            for (Run r : p.runs) {
                Run nr = new Run();
                nr.copyFrom(r);
                runs.add(nr);
            }
        }
    }

    /* body-level text properties (a:bodyPr) */
    public static final class BodyPr implements Serializable {
        private static final long serialVersionUID = 3;
        public float insetL = 91440f, insetR = 91440f, insetT = 45720f, insetB = 45720f; // EMU
        public int anchor = 0;         // 0 top, 1 middle, 2 bottom
        public int autofit = 0;        // 0 none, 1 shrink-on-overflow, 2 grow
        public boolean wrap = true;
        public float fontScale = 1f;   // normAutofit/@fontScale (90000 = 0.9)
        /* v1.27: normAutofit/@lnSpcReduction (10000 = 10%) — applied to lnSpcPct */
        public float lnSpcReduction = 0f;
        /* v1.27: a:bodyPr/@numCol (text columns) + @vert (vertical text) */
        public int numCol = 1;
        public int vert = 0;           // 0 horizontal, 1 vert270, 2 vert, 3 eaVert

        public boolean isVertical() { return vert == 1 || vert == 2 || vert == 3; }

        public void copyFrom(BodyPr b) {
            insetL = b.insetL; insetR = b.insetR; insetT = b.insetT; insetB = b.insetB;
            anchor = b.anchor; autofit = b.autofit; wrap = b.wrap; fontScale = b.fontScale;
            lnSpcReduction = b.lnSpcReduction; numCol = b.numCol; vert = b.vert;
        }
    }

    /* ------------------------------------------------------------- shape base */
    public static abstract class Shape implements Serializable {
        private static final long serialVersionUID = 3;
        /* shape kinds (NOT the legacy TYPE_* numbering) */
        public static final int KIND_TEXT = 0;
        public static final int KIND_AUTOSHAPE = 1;
        public static final int KIND_PICTURE = 2;
        public static final int KIND_TABLE = 3;
        public static final int KIND_CONNECTOR = 4;
        public static final int KIND_VIDEO = 5;
        public static final int KIND_CHART = 6;

        /* legacy constants — keep values! (ReaderActivity switch) */
        public static final int TYPE_TEXT = 0;
        public static final int TYPE_RECT = 1;
        public static final int TYPE_ROUND_RECT = 2;
        public static final int TYPE_OVAL = 3;
        public static final int TYPE_LINE = 4;
        public static final int TYPE_IMAGE = 5;
        public static final int TYPE_TRIANGLE = 6;
        public static final int TYPE_RIGHT_ARROW = 7;
        public static final int TYPE_STAR = 8;
        public static final int TYPE_DIAMOND = 9;
        public static final int TYPE_PENTAGON = 10;
        public static final int TYPE_HEXAGON = 11;
        public static final int TYPE_PARALLELOGRAM = 12;
        public static final int TYPE_HEART = 13;
        public static final int TYPE_CLOUD = 14;
        public static final int TYPE_LIGHTNING = 15;
        public static final int TYPE_PLUS = 16;
        public static final int TYPE_CHEVRON = 17;
        public static final int TYPE_VIDEO = 18;

        public int kind = KIND_TEXT;
        public String name = "";
        public int x, y, w, h;                 // EMU, absolute (groups flattened)
        public int rot = 0;                    // 60000ths of a degree
        public boolean flipH = false, flipV = false;
        public Fill fill = new Fill();
        public Line line = new Line();
        /* v1.27: picture fill on shapes (a:blipFill inside p:spPr) — the
           image bytes render as the shape fill and round-trip on save */
        public byte[] picFillData;
        public String picFillFormat = "png";
        /* v1.23: entrance animation for the slideshow — 0 none, 1 fade,
           2 fly from right, 3 fly from left, 4 fly from top, 5 fly from
           bottom, 6 zoom, 7 wipe. Persisted via slide p:extLst (spec-safe). */
        public int anim = 0;
        /* v1.23: drop shadows (spPr / rPr outerShdw round-trip) */
        public boolean shadow = false;
        public boolean textShadow = false;

        public abstract Shape copy();

        /* copies the shared base fields into another shape — instance
           method of Shape so every subclass copy() can call it directly */
        void copyBaseTo(Shape into) {
            into.kind = kind;
            into.name = name;
            into.x = x; into.y = y; into.w = w; into.h = h;
            into.rot = rot;
            into.flipH = flipH; into.flipV = flipV;
            into.fill = cloneFill(fill);
            into.line = new Line();
            into.line.enabled = line.enabled;
            into.line.color = line.color;
            into.line.widthPt = line.widthPt;
            into.line.dash = line.dash;
            into.picFillData = picFillData;
            into.picFillFormat = picFillFormat;
            into.anim = anim;
            into.shadow = shadow;
            into.textShadow = textShadow;
        }

        /* helpers */
        public boolean hasText() { return false; }
        public void rotateBy(int d60000) {
            rot = (rot + d60000) % 21600000;
            if (rot < 0) rot += 21600000;
        }
    }

    /* helper used by every copy() below */
    static void copyParaInto(List<Para> dst, List<Para> src) {
        for (Para p : src) {
            Para np = new Para();
            np.copyFrom(p);
            dst.add(np);
        }
    }

    /* ------------------------------------------------------------ text shapes */
    public static final class TextShape extends Shape implements Serializable {
        private static final long serialVersionUID = 3;
        public final List<Para> paras = new ArrayList<>();
        public final BodyPr body = new BodyPr();
        public boolean placeholderTitle = false;   // styling hint from reader

        public TextShape() { kind = KIND_TEXT; }

        public TextShape(String txt, int x, int y, int w, int h, float sizePt, int color, boolean bold, boolean center, boolean rtl) {
            kind = KIND_TEXT;
            this.x = x; this.y = y; this.w = w; this.h = h;
            Para p = new Para();
            p.align = center ? Para.ALIGN_CTR : Para.ALIGN_R;
            p.rtl = rtl;
            Run r = new Run();
            r.text = txt == null ? "" : txt;
            r.sizePt = sizePt;
            r.color = color;
            r.bold = bold;
            p.runs.add(r);
            paras.add(p);
        }

        /* 9-arg convenience used by the editor templates and the
           insert-text-box path: (txt, x, y, w, h, sizePt, color, bold, rtl).
           Persian/RTL default — right-aligned like Office RTL decks. */
        public TextShape(String txt, int x, int y, int w, int h, float sizePt, int color, boolean bold, boolean rtl) {
            this(txt, x, y, w, h, sizePt, color, bold, false, rtl);
        }

        @Override public boolean hasText() { return true; }

        public String plainText() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < paras.size(); i++) {
                if (i > 0) sb.append('\n');
                sb.append(paras.get(i).plainText());
            }
            return sb.toString();
        }

        /* replace all text keeping the first paragraph's style (editor path).
           v1.32 FIX: this used to copy ONLY align/rtl/bullet — buChar,
           buAutoNum, marL/indent, numbering format and marker size/color
           were all dropped, so editing a bulleted/numbered list turned every
           line back into a marker-less paragraph. Now the FULL paragraph
           style survives the edit. */
        public void setTextKeepingStyle(String txt) {
            Para proto = paras.isEmpty() ? new Para() : paras.get(0);
            Run pr = proto.styleProto();
            paras.clear();
            String[] lines = (txt == null ? "" : txt).split("\n", -1);
            for (String ln : lines) {
                Para p = new Para();
                p.copyFrom(proto);
                p.runs.clear();
                Run r = new Run();
                r.text = ln;
                r.sizePt = pr.sizePt;
                r.bold = pr.bold;
                r.italic = pr.italic;
                r.underline = pr.underline;
                r.color = pr.color;
                r.font = pr.font;
                p.runs.add(r);
                paras.add(p);
            }
        }

        @Override public Shape copy() {
            TextShape c = new TextShape();
            copyBaseTo(c);
            c.body.copyFrom(body);
            c.placeholderTitle = placeholderTitle;
            copyParaInto(c.paras, paras);
            return c;
        }
    }

    /* ------------------------------------------------------------- autoshapes */
    public static final class AutoShape extends Shape implements Serializable {
        private static final long serialVersionUID = 3;
        public int type = TYPE_RECT;          // TYPE_* geometry
        public final List<Para> paras = new ArrayList<>();   // autoshapes can carry text
        public final BodyPr body = new BodyPr();
        public float adj = 0.16667f;          // roundRect corner etc.
        /* v1.27: freeform geometry (a:custGeom/a:pathLst) — points are
           NORMALIZED 0..1 within the shape bounding box, path closed at the
           end; null = preset geometry. Bezier segments are sampled to lines. */
        public float[] pathX, pathY;
        public boolean pathClosed = true;

        public AutoShape() { kind = KIND_AUTOSHAPE; }
        public AutoShape(int type) { kind = KIND_AUTOSHAPE; this.type = type; }

        @Override public boolean hasText() { return !paras.isEmpty(); }

        @Override public Shape copy() {
            AutoShape c = new AutoShape(type);
            copyBaseTo(c);
            c.adj = adj;
            c.body.copyFrom(body);
            copyParaInto(c.paras, paras);
            /* v1.29 FIX: deep-copy the freeform geometry arrays — sharing them
               meant any future in-place path mutation (normalization/scaling)
               would silently corrupt the source shape and every other copy. */
            c.pathX = pathX == null ? null : pathX.clone();
            c.pathY = pathY == null ? null : pathY.clone();
            c.pathClosed = pathClosed;
            return c;
        }
    }

    /* ---------------------------------------------------------------- pictures */
    public static final class Picture extends Shape implements Serializable {
        private static final long serialVersionUID = 4;
        public byte[] imgData;
        public String imgFormat = "png";
        /* v1.35: external linked image (a:blip r:link with an External
           relationship). imgData stays null but the URL round-trips through
           the writer — saving can no longer silently DELETE the picture. */
        public String imgUrl;
        public float cropL, cropR, cropT, cropB;   // 0..1 source-rect
        /* v1.30 FIX: the frame geometry + fill/line of a PICTURE round-trip.
           The model already renders pic.fill/line; the frame geometry
           (roundRect frames etc.) and the background fill used to be
           dropped on every save (writer hardcoded prst="rect" and never
           wrote the fill). */
        public int type = Shape.TYPE_RECT;

        public Picture() { kind = KIND_PICTURE; }

        @Override public Shape copy() {
            Picture c = new Picture();
            copyBaseTo(c);
            c.imgData = imgData;
            c.imgFormat = imgFormat;
            c.imgUrl = imgUrl;
            c.cropL = cropL; c.cropR = cropR; c.cropT = cropT; c.cropB = cropB;
            c.type = type;
            return c;
        }
    }

    /* ------------------------------------------------------------------ video */
    public static final class Video extends Shape implements Serializable {
        private static final long serialVersionUID = 5;
        public byte[] posterData;               // poster frame (blip), may be null
        public byte[] videoData;                // embedded media bytes (may be null for linked)
        public String videoFormat = "mp4";      // extension of the media part
        public String videoName = "";
        public String mimeType = "video/mp4";
        /* v1.30 FIX: LINKED media (r:link to an external URL, no embedded
           bytes) round-trips — it used to degrade to a plain picture on
           every save because the writer had no relationship to emit. */
        public String videoUrl;

        public Video() { kind = KIND_VIDEO; }

        @Override public Shape copy() {
            Video c = new Video();
            copyBaseTo(c);
            c.posterData = posterData;
            c.videoData = videoData;
            c.videoFormat = videoFormat;
            c.videoName = videoName;
            c.mimeType = mimeType;
            c.videoUrl = videoUrl;
            return c;
        }
    }

    /* ------------------------------------------------------------------ chart */
    public static final class ChartSeries implements Serializable {
        private static final long serialVersionUID = 4;
        public String name = "";
        public int color = 0;                   // 0 = auto palette slot
        /* per-point colors (pie/doughnut dPt fills); Integer.MIN_VALUE = unset */
        public final List<Integer> pointColors = new ArrayList<>();
        public final List<Double> values = new ArrayList<>();
    }

    public static final class Chart extends Shape implements Serializable {
        private static final long serialVersionUID = 4;
        /* chart kinds */
        public static final int BAR_CLUSTERED = 0;     // vertical columns
        public static final int BAR_STACKED = 1;
        public static final int BAR_PERCENT = 2;       // 100% stacked
        public static final int BARH_CLUSTERED = 3;    // horizontal bars
        public static final int BARH_STACKED = 4;
        public static final int BARH_PERCENT = 5;
        public static final int LINE = 6;
        public static final int LINE_MARKER = 7;
        public static final int PIE = 8;
        public static final int DOUGHNUT = 9;
        public static final int AREA = 10;
        public static final int SCATTER = 11;

        public int chartType = BAR_CLUSTERED;
        public String title = null;             // chart title (null = none)
        public final List<String> categories = new ArrayList<>();
        public final List<ChartSeries> series = new ArrayList<>();
        public boolean legend = true;
        public int legendPos = 0;               // 0 right, 1 bottom, 2 left, 3 top
        public boolean dataLabels = false;
        public boolean gridlines = true;
        public String catTitle = null, valTitle = null;   // axis titles
        public int gapWidthPct = 150;           // bar gap (OOXML c:gapWidth)
        public int holePct = 50;                // doughnut hole

        /* Office-2016+ default chart accent palette (also the writer fallback) */
        public static final int[] PALETTE = {
                0xFF4472C4, 0xFFED7D31, 0xFFA5A5A5, 0xFFFFC000,
                0xFF5B9BD5, 0xFF70AD47, 0xFF264478, 0xFF9E480E,
                0xFF636363, 0xFF997300, 0xFF255E91, 0xFF43682B
        };

        public Chart() { kind = KIND_CHART; }

        public boolean isHorizontal() {
            return chartType == BARH_CLUSTERED || chartType == BARH_STACKED || chartType == BARH_PERCENT;
        }

        public boolean isStacked() {
            return chartType == BAR_STACKED || chartType == BARH_STACKED
                    || chartType == BAR_PERCENT || chartType == BARH_PERCENT;
        }

        public boolean isCircular() {
            return chartType == PIE || chartType == DOUGHNUT;
        }

        public int seriesColor(int i) {
            ChartSeries s = i < series.size() ? series.get(i) : null;
            if (s != null && s.color != 0) return s.color;
            return PALETTE[Math.abs(i) % PALETTE.length];
        }

        @Override public Shape copy() {
            Chart c = new Chart();
            copyBaseTo(c);
            c.chartType = chartType;
            c.title = title;
            for (String cat : categories) c.categories.add(cat);
            for (ChartSeries s : series) {
                ChartSeries ns = new ChartSeries();
                ns.name = s.name;
                ns.color = s.color;
                ns.pointColors.addAll(s.pointColors);
                ns.values.addAll(s.values);
                c.series.add(ns);
            }
            c.legend = legend;
            c.legendPos = legendPos;
            c.dataLabels = dataLabels;
            c.gridlines = gridlines;
            c.catTitle = catTitle;
            c.valTitle = valTitle;
            c.gapWidthPct = gapWidthPct;
            c.holePct = holePct;
            return c;
        }
    }

    /* ------------------------------------------------------------------ table */
    public static final class TableCell implements Serializable {
        private static final long serialVersionUID = 3;
        public Fill fill = new Fill();          // NONE = inherit banding
        public int spanR = 1, spanD = 1;
        public boolean skipR = false, skipD = false;   // covered by a merge
        public final List<Para> paras = new ArrayList<>();
        public Line[] edges = new Line[4];      // L,T,R,B; null = theme default
        /* v1.27: a:tcPr cell margins (EMU) + vertical anchor (0 t, 1 ctr, 2 b) */
        public float insetL = 91440f, insetR = 91440f, insetT = 45720f, insetB = 45720f;
        public int anchor = -1;                 // -1 = inherit from table style
    }

    public static final class TableRow implements Serializable {
        private static final long serialVersionUID = 3;
        public float heightEmu;
        public final List<TableCell> cells = new ArrayList<>();
    }

    public static final class Table extends Shape implements Serializable {
        private static final long serialVersionUID = 3;
        public final List<Float> colWidths = new ArrayList<>();   // EMU
        public final List<TableRow> rows = new ArrayList<>();
        public int styleBase = 0;       // 0 = light banding, 1 = plain
        public boolean firstRow = true, bandRow = true;

        public Table() { kind = KIND_TABLE; }

        @Override public Shape copy() {
            Table c = new Table();
            copyBaseTo(c);
            c.styleBase = styleBase; c.firstRow = firstRow; c.bandRow = bandRow;
            for (Float cw : colWidths) c.colWidths.add(cw);
            for (TableRow r : rows) {
                TableRow nr = new TableRow();
                nr.heightEmu = r.heightEmu;
                for (TableCell cell : r.cells) {
                    TableCell nc = new TableCell();
                    nc.spanR = cell.spanR; nc.spanD = cell.spanD;
                    nc.skipR = cell.skipR; nc.skipD = cell.skipD;
                    nc.fill = cell.fill == null ? new Fill() : cloneFill(cell.fill);
                    copyParaInto(nc.paras, cell.paras);
                    /* v1.28 FIX: حاشیه‌ها، حاشیهٔ داخلی و لنگر عمودی سلول
                       در کپی گم می‌شدند → بعد از UNDO/ذخیرهٔ خودکار، جدول
                       بدون خط و با فاصله‌های غلط ذخیره می‌شد */
                    if (cell.edges != null) {
                        for (int ei = 0; ei < 4; ei++) {
                            Line el = cell.edges[ei];
                            if (el != null) {
                                Line nl = new Line();
                                nl.enabled = el.enabled;
                                nl.color = el.color;
                                nl.widthPt = el.widthPt;
                                nl.dash = el.dash;
                                nc.edges[ei] = nl;
                            }
                        }
                    }
                    nc.insetL = cell.insetL; nc.insetR = cell.insetR;
                    nc.insetT = cell.insetT; nc.insetB = cell.insetB;
                    nc.anchor = cell.anchor;
                    nr.cells.add(nc);
                }
                c.rows.add(nr);
            }
            return c;
        }
    }

    /* ------------------------------------------------------------- connector */
    public static final class Connector extends Shape implements Serializable {
        private static final long serialVersionUID = 3;
        /* the connector line goes from (x,y) to (x+w,y+h) with flips deciding
           which corners are the actual endpoints */

        public Connector() { kind = KIND_CONNECTOR; }

        @Override public Shape copy() {
            Connector c = new Connector();
            copyBaseTo(c);
            return c;
        }
    }

    static Fill cloneFill(Fill f) {
        Fill n = new Fill();
        n.type = f.type; n.color = f.color; n.color2 = f.color2;
        n.gradHorizontal = f.gradHorizontal;
        n.gradAngleDeg = f.gradAngleDeg;
        return n;
    }

    /* ------------------------------------------------------------------ slide */
    public static final class Slide implements Serializable {
        private static final long serialVersionUID = 4;
        public Fill background = new Fill();     // NONE = white
        /* master/layout chrome (white title bar, bands, slide number…).
           Rendered beneath editable shapes; excluded from selection and
           from undo snapshots; baked into the file on save. */
        public final List<Shape> bgShapes = new ArrayList<>();
        public final List<Shape> shapes = new ArrayList<>();
        public String notes = null;              // speaker notes (notesSlide body text)
        /* v1.22: slide transition (fade/push/wipe/cut/...) kept for
           round-trip + used by the slideshow (fade approximation) */
        public String transition = null;
        /* v1.23: auto-advance seconds for the slideshow (0 = manual) */
        public float autoSec = 0f;

        public Slide copy() {
            Slide s = new Slide();
            s.background = cloneFill(background);
            for (Shape sh : bgShapes) s.bgShapes.add(sh.copy());
            for (Shape sh : shapes) s.shapes.add(sh.copy());
            s.notes = notes;
            s.transition = transition;
            s.autoSec = autoSec;
            return s;
        }
    }
}
