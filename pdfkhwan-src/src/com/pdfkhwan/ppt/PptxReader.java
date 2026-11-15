package com.pdfkhwan.ppt;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

/* ============================================================================
   PptxReader v3 (v1.17) — deep-review rewrite of the PPTX importer.

   v2 (v1.15) already fixed: slide order via r:id, theme colors with
   lumMod/lumOff/tint/shade/alpha, layout+master placeholder geometry,
   grpSp flattening, tables, connectors, rotation, crop, gradients.

   v3 closes the gaps that still made REAL complex decks render wrong
   (verified on a 23-slide "Banded"-theme medical deck):

     1. p:clrMap — every schemeClr is resolved THROUGH the master color
        map. Dark templates (bg1->dk1, tx1->lt1) rendered with inverted
        colors before.
     2. p:bgRef idx 1001/1002/1003 — theme background styles. Master
        "bg2 -> dk2 = 099BDD blue" backgrounds were silently dropped.
     3. Master + layout CHROME shapes (the white title bar, section
        bands, date/footer/slide-number placeholders) are now parsed and
        attached to each slide as bgShapes, honoring showMasterSp="0".
     4. Master p:txStyles (titleStyle/bodyStyle/otherStyle) + layout
        lstStyle — the full 3-level text-style inheritance chain:
        slide run/para props -> layout ph lstStyle -> master ph
        lstStyle -> master txStyles. Real title sizes (40/60pt), body
        level sizes (22/20/18/16...), bullets, line spacing, ALL-CAPS,
        hanging indents now survive import.
     5. Paragraph levels (lvl 0..8), lnSpc pct/pts, spcBef/spcAft,
        marL/indent, buChar (Wingdings PUA mapped to real glyphs),
        buAutoNum, buClr, buNone.
     6. a:fld — slide-number/date fields are materialized as runs
        (slide numbers now show, resolved per slide).
     7. normAutofit/@fontScale — 90%-scaled titles no longer overflow.
     8. bodyPr attribute-level inheritance (anchor, insets, wrap,
        autofit) from layout -> master.
     9. p:style fillRef/lnRef/fontRef fallback when spPr carries no
        explicit fill/line (theme-styled shapes).
    10. Theme part resolved via slideMasterN.xml.rels (was: arbitrary
        HashMap order — could pick the NOTES theme by accident).
    11. Theme font scheme: +mj-lt/+mn-lt resolve to real typefaces.
    12. satMod color modifier, a:lin gradient angle, group rotation,
        cap="all", rPr/@spc, more prstGeom mappings.
   ============================================================================ */
public final class PptxReader {
    private static final int TOTAL_BUDGET = 144 * 1024 * 1024;

    private PptxReader() {
    }

    /* ---------------------------------------------------------------- helpers */
    private static String attr(XmlPullParser p, String name) {
        for (int i = 0; i < p.getAttributeCount(); i++) {
            if (local(p.getAttributeName(i)).equals(name)) {
                return p.getAttributeValue(i);
            }
        }
        return null;
    }

    private static String local(String s) {
        if (s == null) return "";
        int c = s.indexOf(':');
        return c >= 0 ? s.substring(c + 1) : s;
    }

    private static int parseInt(String s, int def) {
        if (s == null) return def;
        try { return (int) Math.round(Double.parseDouble(s)); }
        catch (Exception e) { return def; }
    }

    private static float parseFloat(String s, float def) {
        if (s == null) return def;
        try { return Float.parseFloat(s); }
        catch (Exception e) { return def; }
    }

    private static int parseColor(String s) {
        if (s == null) return 0;
        String t = s.trim();
        if (t.startsWith("#")) t = t.substring(1);
        if (t.length() == 8) t = t.substring(2);
        if (t.length() != 6) return 0;
        try {
            return ((int) (Long.parseLong(t, 16) & 0xFFFFFF)) | 0xFF000000;
        } catch (Exception e) {
            return 0;
        }
    }

    private static String norm(String s) {
        while (s != null && s.startsWith("/")) s = s.substring(1);
        return s;
    }

    private static String urlDecode(String s) {
        if (s == null || s.indexOf('%') < 0) return s;
        try { return java.net.URLDecoder.decode(s, "UTF-8"); }
        catch (Exception e) { return s; }
    }

    private static String resolve(String base, String target) {
        if (target == null) return null;
        if (target.startsWith("/")) return urlDecode(norm(target));
        Deque<String> stack = new ArrayDeque<>();
        for (String p : base.split("/")) if (p.length() > 0) stack.add(p);
        for (String p : target.split("/")) {
            if (p.length() == 0 || p.equals(".")) continue;
            if (p.equals("..")) { if (!stack.isEmpty()) stack.pollLast(); }
            else stack.add(p);
        }
        StringBuilder sb = new StringBuilder();
        for (String p : stack) {
            if (sb.length() > 0) sb.append('/');
            sb.append(p);
        }
        /* v1.27 FIX: OPC relationship targets are URI-escaped
           ("my%20image.png") while zip entries hold the decoded name —
           decode before lookup so images with spaces/non-ASCII names stop
           silently vanishing */
        return urlDecode(sb.toString());
    }

    /* v1.35: case-insensitive part lookup — zip entries are case-SENSITIVE on
       Android but Windows/Office writers sometimes disagree with their own
       rels ("../media/Image1.PNG" vs ppt/media/image1.png); WPS tolerates the
       mismatch, we used to render the picture as missing. Exact match first,
       then a lowercase fallback. */
    private static byte[] part(Map<String, byte[]> parts, String path) {
        if (path == null) return null;
        byte[] b = parts.get(path);
        if (b != null) return b;
        return parts.get(path.toLowerCase(java.util.Locale.ROOT));
    }

    private static XmlPullParser parser(byte[] data) throws Exception {
        XmlPullParser p = XmlPullParserFactory.newInstance().newPullParser();
        /* v1.30 FIX: some producers write a UTF-8 BOM (EF BB BF) at the head of
           a part. InputStreamReader KEEPS the BOM and kxml2 then aborts with
           "PI must not start with xml" — the exception is swallowed by the
           per-part catch blocks, so the ENTIRE part (a master's chrome and
           background, or a whole slide) silently vanished. Strip the BOM. */
        int off = 0;
        if (data.length >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) {
            off = 3;
        }
        p.setInput(new InputStreamReader(new ByteArrayInputStream(data, off, data.length - off), "UTF-8"));
        return p;
    }

    /* ------------------------------------------------------------------ theme */
    static final class Theme {
        final Map<String, Integer> colors = new HashMap<>();
        String majorFont = null;
        String minorFont = null;

        int get(String slot) {
            Integer v = colors.get(slot);
            return v == null ? 0 : v;
        }

        int resolveColor(String slot, List<String> mods) {
            int base = get(slot);
            if (base == 0) {
                if ("bg1".equals(slot) || "lt1".equals(slot)) base = 0xFFFFFFFF;
                else if ("tx1".equals(slot) || "dk1".equals(slot)) base = 0xFF000000;
                else if ("bg2".equals(slot) || "lt2".equals(slot)) base = 0xFFEEECE1;
                else if ("tx2".equals(slot) || "dk2".equals(slot)) base = 0xFF1F497D;
                else if ("accent1".equals(slot)) base = 0xFF4F81BD;
                else if ("accent2".equals(slot)) base = 0xFFC0504D;
                else if ("accent3".equals(slot)) base = 0xFF9BBB59;
                else if ("accent4".equals(slot)) base = 0xFF8064A2;
                else if ("accent5".equals(slot)) base = 0xFF4BACC6;
                else if ("accent6".equals(slot)) base = 0xFFF79646;
                else if ("hlink".equals(slot)) base = 0xFF0000FF;
                else if ("folHlink".equals(slot)) base = 0xFF800080;
                else if ("phClr".equals(slot)) base = 0xFF000000;
                else return 0;
            }
            return applyMods(base, mods);
        }

        static int applyMods(int base, List<String> mods) {
            if (mods == null || mods.isEmpty()) return base;
            int c = base;
            for (String m : mods) {
                if (m.startsWith("lumMod:")) {
                    c = lum(c, f(m, 7, 1f), 0f);
                } else if (m.startsWith("lumOff:")) {
                    c = lum(c, 1f, f(m, 7, 0f));
                } else if (m.startsWith("shade:")) {
                    c = lum(c, f(m, 6, 1f), 0f);
                } else if (m.startsWith("tint:")) {
                    float k = f(m, 5, 1f);
                    int a = (c >> 24) & 0xFF, r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                    r = Math.round(r + (255 - r) * k);
                    g = Math.round(g + (255 - g) * k);
                    b = Math.round(b + (255 - b) * k);
                    c = (a << 24) | (r << 16) | (g << 8) | b;
                } else if (m.startsWith("satMod:")) {
                    c = satMod(c, f(m, 7, 1f));
                } else if (m.startsWith("alpha:")) {
                    float k = f(m, 6, 1f);
                    int a = Math.max(0, Math.min(255, Math.round(((c >> 24) & 0xFF) * k)));
                    c = (a << 24) | (c & 0xFFFFFF);
                }
            }
            return c;
        }

        private static float f(String m, int off, float def) {
            /* v1.29 FIX: DrawingML modifier values are on a 100000 scale
               (val="75000" = 75%). All producers (mods.add sites) store the
               raw attribute, so the parser must normalize here. Without the
               /100000f every lumMod/shade/tint/alpha snapped to 0..1 overflow
               (lumMod 50000 -> clamp01(0.5*50000)=1 -> white, tint 60000
               shifted past 255 -> garbage channels, alpha 50000 -> opaque). */
            try { return Float.parseFloat(m.substring(off)) / 100000f; } catch (Exception e) { return def; }
        }

        static int lum(int c, float mul, float add) {
            /* v1.27 FIX: Office applies lumMod/lumOff/shade to the HSL
               LUMINANCE while keeping hue+saturation. The old 70%-toward-gray
               push desaturated every themed color (dark accents rendered
               grayish/washed out on real decks). */
            int a = (c >> 24) & 0xFF;
            float r = ((c >> 16) & 0xFF) / 255f, g = ((c >> 8) & 0xFF) / 255f, b = (c & 0xFF) / 255f;
            float mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
            float l = (mx + mn) / 2f;
            float h = 0f, s = 0f;
            if (mx > mn) {
                float d = mx - mn;
                s = l <= 0.5f ? d / (mx + mn) : d / (2f - mx - mn);
                if (mx == r) h = (g - b) / d + (g < b ? 6f : 0f);
                else if (mx == g) h = (b - r) / d + 2f;
                else h = (r - g) / d + 4f;
                h /= 6f;
            }
            float nl = clamp01(l * mul + add);
            float ns = s;
            float q = nl <= 0.5f ? nl * (1f + ns) : nl + ns - nl * ns;
            float p = 2f * nl - q;
            r = hue2rgb(p, q, h + 1f / 3f);
            g = hue2rgb(p, q, h);
            b = hue2rgb(p, q, h - 1f / 3f);
            return (a << 24) | ((int) (clamp01(r) * 255 + 0.5f) << 16)
                    | ((int) (clamp01(g) * 255 + 0.5f) << 8) | (int) (clamp01(b) * 255 + 0.5f);
        }

        /* saturation modulation used all over theme fill styles */
        static int satMod(int c, float k) {
            /* v1.29 FIX: the old body (a) passed the raw RGB channel values
               as hue angles through hue() (a no-op t-wrapper) so the hue was
               lost entirely, and (b) cast before multiplying:
               `(int) clamp01(r) * 255` truncates every channel to 0 or 255,
               posterizing the result (accent blue satMod 1.05 -> black).
               Hue is now computed exactly like Theme.lum() does. */
            int a = (c >> 24) & 0xFF;
            float r = ((c >> 16) & 0xFF) / 255f, g = ((c >> 8) & 0xFF) / 255f, b = (c & 0xFF) / 255f;
            float mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
            if (mx <= mn) return c;
            float l = (mx + mn) / 2f;
            float s = (l <= 0.5f) ? (mx - mn) / (mx + mn) : (mx - mn) / (2f - mx - mn);
            float h;
            {
                float d = mx - mn;
                if (mx == r) h = (g - b) / d + (g < b ? 6f : 0f);
                else if (mx == g) h = (b - r) / d + 2f;
                else h = (r - g) / d + 4f;
                h /= 6f;
            }
            s = clamp01(s * k);
            float q = (l <= 0.5f) ? l * (1f + s) : l + s - l * s;
            float p = 2f * l - q;
            r = hue2rgb(p, q, h + 1f / 3f);
            g = hue2rgb(p, q, h);
            b = hue2rgb(p, q, h - 1f / 3f);
            return (a << 24) | ((int) (clamp01(r) * 255 + 0.5f) << 16)
                    | ((int) (clamp01(g) * 255 + 0.5f) << 8) | (int) (clamp01(b) * 255 + 0.5f);
        }

        private static float hue(float p, float q, float t) {
            if (t < 0) t += 1;
            if (t > 1) t -= 1;
            return t;
        }

        private static float hue2rgb(float p, float q, float t) {
            if (t < 1f / 6f) return p + (q - p) * 6f * t;
            if (t < 1f / 2f) return q;
            if (t < 2f / 3f) return p + (q - p) * (2f / 3f - t) * 6f;
            return p;
        }

        static float clamp01(float v) { return v < 0 ? 0 : v > 1 ? 1 : v; }
    }

    /* ------------------------------------------------------- scheme + clrMap */
    /* resolves schemeClr slots through the master color map (p:clrMap).
       e.g. the "Banded" master maps tx1->lt1 so text is WHITE on blue. */
    static final class Scheme {
        final Theme theme;
        final Map<String, String> clrMap = new HashMap<>();

        Scheme(Theme theme) { this.theme = theme; }

        int resolve(String slot, List<String> mods) {
            if (slot == null) return 0;
            String mapped = clrMap.containsKey(slot) ? clrMap.get(slot) : slot;
            return theme.resolveColor(mapped, mods);
        }
    }

    private static Map<String, String> parseClrMap(byte[] data) {
        Map<String, String> map = new HashMap<>();
        try {
            XmlPullParser p = parser(data);
            int ev = p.getEventType();
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && "clrMap".equals(local(p.getName()))) {
                    for (int i = 0; i < p.getAttributeCount(); i++) {
                        map.put(local(p.getAttributeName(i)), p.getAttributeValue(i));
                    }
                    break;
                }
                ev = p.next();
            }
        } catch (Throwable ignored) {
        }
        return map;
    }

    private static Theme parseTheme(byte[] data) {
        Theme t = new Theme();
        try {
            XmlPullParser p = parser(data);
            String slot = null;
            String fontCtx = null;   // "major" | "minor"
            List<String> mods = new ArrayList<>();
            /* v1.30 FIX: deferred color resolution — the theme color is put
               at the color element's END, after its child modifiers */
            String pendClrName = null;
            int pendClrBase = 0;
            final List<String> pendClrMods = new ArrayList<>();
            int ev = p.getEventType();
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    String name = local(p.getName());
                    if ("dk1".equals(name) || "lt1".equals(name) || "dk2".equals(name) || "lt2".equals(name)
                            || (name != null && name.startsWith("accent")) || "hlink".equals(name) || "folHlink".equals(name)) {
                        slot = name;
                        mods.clear();
                        pendClrName = null;
                        pendClrMods.clear();
                    } else if ("sysClr".equals(name) || "srgbClr".equals(name)) {
                        if (slot != null) {
                            String v = "sysClr".equals(name) ? attr(p, "lastClr") : attr(p, "val");
                            int c = parseColor(v);
                            /* v1.29 FIX: the window/windowText fallbacks must
                               compare the sysClr VAL attribute ("window"), not
                               lastClr (the raw color "000000") — the old check
                               never fired. */
                            if (c == 0 && "sysClr".equals(name)) {
                                String sv = attr(p, "val");
                                if ("window".equals(sv)) c = 0xFFFFFFFF;
                                else if ("windowText".equals(sv)) c = 0xFF000000;
                            }
                            if (c != 0) {
                                pendClrName = name;
                                pendClrBase = c;
                                pendClrMods.clear();
                            }
                        }
                    } else if ("lumMod".equals(name) || "lumOff".equals(name) || "shade".equals(name)
                            || "tint".equals(name) || "alpha".equals(name) || "satMod".equals(name)) {
                        if (slot != null && pendClrName != null) pendClrMods.add(name + ":" + attr(p, "val"));
                        else if (slot != null) mods.add(name + ":" + attr(p, "val"));
                    } else if ("majorFont".equals(name) || "minorFont".equals(name)) {
                        fontCtx = "majorFont".equals(name) ? "major" : "minor";
                    } else if ("latin".equals(name) && fontCtx != null) {
                        String tf = attr(p, "typeface");
                        if (tf != null && tf.length() > 0) {
                            if ("major".equals(fontCtx)) t.majorFont = tf;
                            else t.minorFont = tf;
                        }
                        fontCtx = null;
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    String name = local(p.getName());
                    if (pendClrName != null && name != null && pendClrName.equals(name) && slot != null) {
                        /* v1.30 FIX: apply the color's OWN modifiers at close */
                        t.colors.put(slot, Theme.applyMods(pendClrBase, pendClrMods));
                        pendClrName = null;
                        pendClrMods.clear();
                        slot = null;
                    }
                    if ("majorFont".equals(name) || "minorFont".equals(name)) fontCtx = null;
                }
                ev = p.next();
            }
        } catch (Throwable ignored) {
        }
        return t;
    }

    /* ------------------------------------------------------- level styles */
    /* one paragraph-level style: from p:txStyles lvlNpPr or a shape's
       a:lstStyle lvlNpPr. Sentinel values = "unset" so the inheritance
       chain can overlay them. */
    static final class Lvl {
        int align = -1;
        int marL = Integer.MIN_VALUE, indent = Integer.MIN_VALUE;
        float lnSpcPct = -1f, lnSpcPt = -1f, spcBef = -1f, spcAft = -1f;
        /* v1.27: spcBef/spcAft as percent-of-font-size (a:spcPct) — kept
           separate from the exact-points fields and resolved against the
           paragraph's run size at commit */
        float spcBefPct = -1f, spcAftPct = -1f;
        String buChar = null;          // raw char (maybe Wingdings PUA)
        String buFont = null;
        int buOn = -1;                 // -1 unset, 0 buNone, 1 bullet on
        boolean buAutoNum = false;
        int buClr = 0;
        /* v1.24: numbering format + start + bullet size (buSzPct/buSzPts) */
        int buType = 0;
        int buStart = 1;
        float buSzPct = -1f;
        float buSzPt = -1f;
        float sizePt = -1f;
        int bold = -1, italic = -1, under = -1, color = 0;
        String font = null;
        int caps = -1;
        int rtl = -1;
    }

    /* parses <a:lvl1pPr>..<a:lvl9pPr> subtrees inside txStyles / lstStyle */
    private static Lvl parseLvl(XmlPullParser p, Scheme scheme) throws Exception {
        Lvl l = new Lvl();
        String algn = attr(p, "algn");
        if (algn != null) {
            if ("ctr".equals(algn)) l.align = PptDoc.Para.ALIGN_CTR;
            else if ("r".equals(algn)) l.align = PptDoc.Para.ALIGN_R;
            else if ("just".equals(algn)) l.align = PptDoc.Para.ALIGN_JUST;
            else l.align = PptDoc.Para.ALIGN_L;
        }
        l.marL = parseInt(attr(p, "marL"), Integer.MIN_VALUE);
        l.indent = parseInt(attr(p, "indent"), Integer.MIN_VALUE);
        String rtlA = attr(p, "rtl");
        if (rtlA != null) l.rtl = "1".equals(rtlA) ? 1 : 0;

        int depth = 1;
        int ev = p.getEventType();
        String colorCtx = null;   // "defRPr" | "buClr"
        List<String> mods = new ArrayList<>();
        /* v1.30 FIX: deferred color resolution (same as the main parser) —
           modifiers are children of the color element */
        String pendClrName = null;
        int pendClrBase = 0;
        String pendClrSlot = null;
        final List<String> pendClrMods = new ArrayList<>();
        int defSeen = 0;
        while (!(ev == XmlPullParser.END_TAG && depth == 0)) {
            ev = p.next();
            if (ev == XmlPullParser.START_TAG) {
                String n = local(p.getName());
                if ("lnSpc".equals(n) || "spcBef".equals(n) || "spcAft".equals(n)) {
                    colorCtx = n;
                } else if ("spcPct".equals(n) || "spcPts".equals(n)) {
                    String vs = attr(p, "val");
                    if ("spcPts".equals(n)) {
                        /* spcPts val is in HUNDREDTHS of a point (1200 = 12pt) */
                        float pt = parseFloat(vs, -1f) / 100f;
                        if ("lnSpc".equals(colorCtx)) l.lnSpcPt = pt;
                        else if ("spcBef".equals(colorCtx)) l.spcBef = pt;
                        else if ("spcAft".equals(colorCtx)) l.spcAft = pt;
                    } else {
                        float pct = parseFloat(vs, -1f) / 100000f;
                        if ("lnSpc".equals(colorCtx)) l.lnSpcPct = Math.max(0.1f, Math.min(4f, pct));
                        else if ("spcBef".equals(colorCtx)) l.spcBefPct = Math.max(0f, Math.min(10f, pct));
                        else if ("spcAft".equals(colorCtx)) l.spcAftPct = Math.max(0f, Math.min(10f, pct));
                    }
                } else if ("buNone".equals(n)) {
                    l.buOn = 0;
                } else if ("buChar".equals(n)) {
                    String ch = attr(p, "char");
                    if (ch != null && ch.length() > 0) {
                        l.buChar = ch;
                        l.buOn = 1;
                    }
                } else if ("buAutoNum".equals(n)) {
                    l.buAutoNum = true;
                    l.buOn = 1;
                    /* v1.24: numbering format + start index */
                    String ty = attr(p, "type");
                    if (ty != null) l.buType = PptDoc.Para.parseBuType(ty);
                    String st = attr(p, "startAt");
                    if (st != null) l.buStart = Math.max(1, Math.min(32767, parseInt(st, 1)));
                } else if ("buSzPct".equals(n)) {
                    /* v1.24: bullet size as percent of text (100000 = 100%) */
                    /* v1.29 FIX: a missing val must stay UNSET (-1), not clamp
                       to 10% — matches the v1.27 direct-paragraph fix. */
                    float bv = parseFloat(attr(p, "val"), -1f);
                    if (bv >= 0) l.buSzPct = Math.max(0.25f, Math.min(4f, bv / 100000f));
                } else if ("buSzPts".equals(n)) {
                    /* v1.24: bullet size in points (val is hundredths of pt) */
                    float bp = parseFloat(attr(p, "val"), -1f);
                    if (bp >= 0) l.buSzPt = Math.max(1f, Math.min(400f, bp / 100f));
                } else if ("buFont".equals(n)) {
                    l.buFont = attr(p, "typeface");
                } else if ("buClr".equals(n)) {
                    colorCtx = "buClr";
                    mods.clear();
                    pendClrName = null;
                    pendClrMods.clear();
                } else if ("defRPr".equals(n)) {
                    colorCtx = "defRPr";
                    mods.clear();
                    pendClrName = null;
                    pendClrMods.clear();
                    defSeen++;
                    String sz = attr(p, "sz");
                    if (sz != null) l.sizePt = Math.max(4f, parseInt(sz, 1800) / 100f);
                    String b = attr(p, "b");
                    if (b != null) l.bold = "1".equals(b) ? 1 : 0;
                    String i = attr(p, "i");
                    if (i != null) l.italic = "1".equals(i) ? 1 : 0;
                    String u = attr(p, "u");
                    if (u != null) l.under = (u.length() > 0 && !"none".equals(u)) ? 1 : 0;
                    String cap = attr(p, "cap");
                    if ("all".equals(cap)) l.caps = 1;
                    else if ("small".equals(cap)) l.caps = 2;
                    String typeface = attr(p, "typeface");
                    if (typeface != null && typeface.length() > 0) l.font = typeface;
                } else if ("srgbClr".equals(n) || "schemeClr".equals(n) || "sysClr".equals(n)) {
                    /* v1.30 FIX: park base, assign at the element's END */
                    pendClrName = n;
                    pendClrSlot = null;
                    pendClrBase = 0;
                    pendClrMods.clear();
                    if ("schemeClr".equals(n)) pendClrSlot = attr(p, "val");
                    else if ("sysClr".equals(n)) pendClrBase = parseColor(attr(p, "lastClr"));
                    else pendClrBase = parseColor(attr(p, "val"));
                } else if ("latin".equals(n) || "cs".equals(n)) {
                    if ("defRPr".equals(colorCtx)) {
                        String tf = attr(p, "typeface");
                        if (tf != null && tf.length() > 0) l.font = tf;
                    }
                } else if ("lumMod".equals(n) || "lumOff".equals(n) || "shade".equals(n)
                        || "tint".equals(n) || "alpha".equals(n) || "satMod".equals(n)) {
                    if (pendClrName != null) pendClrMods.add(n + ":" + attr(p, "val"));
                }
                depth++;
            } else if (ev == XmlPullParser.END_TAG) {
                String n = local(p.getName());
                if (pendClrName != null && n != null && pendClrName.equals(n)) {
                    boolean wasScheme = "schemeClr".equals(pendClrName);
                    int c = wasScheme
                            ? scheme.resolve(pendClrSlot, pendClrMods)
                            : Theme.applyMods(pendClrBase, pendClrMods);
                    pendClrName = null;
                    if ("buClr".equals(colorCtx)) {
                        if (c != 0) l.buClr = c;
                    } else if ("defRPr".equals(colorCtx) && defSeen > 0) {
                        if (c != 0) l.color = c;
                    }
                }
                depth--;
            }
        }
        return l;
    }

    /* txStyles of a master: [0]=title, [1]=body, [2]=other; each Lvl[9] */
    private static Lvl[][] parseTxStyles(byte[] data, Scheme scheme) {
        Lvl[][] out = new Lvl[3][];
        for (int f = 0; f < 3; f++) out[f] = new Lvl[9];
        try {
            XmlPullParser p = parser(data);
            int family = -1;
            int ev = p.getEventType();
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    String n = local(p.getName());
                    if ("titleStyle".equals(n)) family = 0;
                    else if ("bodyStyle".equals(n)) family = 1;
                    else if ("otherStyle".equals(n)) family = 2;
                    else if (family >= 0 && n != null && n.startsWith("lvl") && n.endsWith("pPr") && n.length() == 7) {
                        int lvl = n.charAt(3) - '1';
                        if (lvl >= 0 && lvl < 9) {
                            try {
                                out[family][lvl] = parseLvl(p, scheme);
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    String n = local(p.getName());
                    if ("titleStyle".equals(n) || "bodyStyle".equals(n) || "otherStyle".equals(n)) family = -1;
                }
                ev = p.next();
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /* -------------------------------------------------- layout/master info */
    static final class PhInfo {
        String type = "body";
        String idx = "";
        int x, y, w, h;
        boolean hasGeom;
        Lvl[] lvls = new Lvl[9];    // from the ph's own a:lstStyle
        PptDoc.BodyPr body = new PptDoc.BodyPr();
        /* v1.29 FIX: which bodyPr attributes were EXPLICIT on this ph —
           mirrors SpBuilder.bodySeen so layout/master inheritance can
           distinguish "anchor=t" from "attribute absent" (the old
           chain1 == info2 object-identity check never fired). */
        int bodySeen;
    }

    static final class PartInfo {
        final List<PhInfo> phs = new ArrayList<>();
        PptDoc.Fill bg = null;
        boolean showMasterSp = true;
        final List<PptDoc.Shape> bgShapes = new ArrayList<>();
        /* v1.21: layouts remember the master chain they belong to, so each
           slide resolves ITS OWN master's scheme/txStyles (multi-master
           decks: title master + content master with different themes). */
        Scheme scheme = null;
        Lvl[][] txStyles = null;
        PartInfo master = null;
        /* v1.29 FIX: multi-master decks also carry different THEME FONTS;
           doc.fontMajor/fontMinor alone (from the first master's theme)
           gave every slide master #1's fonts. */
        String fontMajor = null;
        String fontMinor = null;
    }

    private static PartInfo parsePartInfo(byte[] data, Map<String, byte[]> parts,
                                          Map<String, String> rels, String base,
                                          int slideW, int slideH, Scheme scheme,
                                          int mode, Lvl[][] txStyles,
                                          String fontMajor, String fontMinor) {
        PartInfo info = new PartInfo();
        ParsedTarget tgt = parseTree(data, parts, rels, base, slideW, slideH, scheme,
                null, null, txStyles, mode, 0, fontMajor, fontMinor, null);
        info.phs.addAll(tgt.phs);
        info.bg = tgt.bg;
        info.bgShapes.addAll(tgt.shapes);
        String sms = rootAttr(data, "showMasterSp");
        if ("0".equals(sms)) info.showMasterSp = false;
        return info;
    }

    private static String rootAttr(byte[] data, String name) {
        try {
            XmlPullParser p = parser(data);
            int ev = p.getEventType();
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    String v = attr(p, name);
                    return v;
                }
                ev = p.next();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /* ------------------------------------------------------------------ read */
    public static PptDoc read(InputStream inputStream) throws Exception {
        Map<String, byte[]> parts = new HashMap<>();
        long total = 0;
        java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(inputStream);
        try {
            java.util.zip.ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[65536];
                long cap = Math.min(64L * 1024 * 1024, Math.max(1024L, TOTAL_BUDGET - total));
                long n = 0;
                int r;
                boolean overflow = false;
                while ((r = zin.read(buf)) > 0) {
                    n += r;
                    if (n > cap) { overflow = true; break; }
                    bo.write(buf, 0, r);
                }
                if (overflow) {
                    /* v1.27 FIX: skip ONLY the oversized entry and keep
                       reading — breaking the whole loop dropped every part
                       after it (later slides/masters vanished when a deck
                       embedded a big video). Drain the remaining bytes. */
                    while ((r = zin.read(buf)) > 0) { /* drain */ }
                    continue;
                }
                byte[] arr = bo.toByteArray();
                total += arr.length;
                if (total <= TOTAL_BUDGET) {
                    parts.put(norm(e.getName()), arr);
                    /* v1.27: percent-encoded alias (rel targets are URI-escaped) */
                    String dec = urlDecode(norm(e.getName()));
                    if (!dec.equals(norm(e.getName())) && !parts.containsKey(dec)) parts.put(dec, arr);
                    /* v1.35: lowercase alias for case-mismatched rel targets */
                    String lc = norm(e.getName()).toLowerCase(java.util.Locale.ROOT);
                    if (!lc.equals(norm(e.getName())) && !parts.containsKey(lc)) parts.put(lc, arr);
                }
            }
        } finally {
            try { zin.close(); } catch (Exception ignored) {}
        }

        PptDoc doc = new PptDoc();

        /* presentation.xml: size + order via rels (v1.14 r:id bug FIXED) */
        byte[] pres = parts.get("ppt/presentation.xml");
        if (pres == null) throw new Exception("presentation.xml پیدا نشد");
        Map<String, String> presRels = rels(parts, "ppt/_rels/presentation.xml.rels");
        List<String> slideParts = new ArrayList<>();
        try {
            XmlPullParser p = parser(pres);
            int ev = p.getEventType();
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    String name = local(p.getName());
                    if ("sldSz".equals(name)) {
                        doc.slideW = parseInt(attr(p, "cx"), PptDoc.DEFAULT_SLIDE_W);
                        doc.slideH = parseInt(attr(p, "cy"), PptDoc.DEFAULT_SLIDE_H);
                    } else if ("sldId".equals(name)) {
                        String rid = null;
                        for (int i = 0; i < p.getAttributeCount(); i++) {
                            String an = p.getAttributeName(i);
                            if ("id".equals(local(an))) {
                                String prefix = p.getAttributePrefix(i);
                                if (an.indexOf(':') >= 0 || (prefix != null && prefix.length() > 0)) {
                                    rid = p.getAttributeValue(i);
                                    break;
                                }
                            }
                        }
                        if (rid != null && presRels.containsKey(rid)) {
                            slideParts.add(resolve("ppt/", presRels.get(rid)));
                        }
                    }
                }
                ev = p.next();
            }
        } catch (Throwable ignored) {
        }
        if (slideParts.isEmpty()) {
            List<String> fallback = new ArrayList<>();
            for (String k : parts.keySet()) {
                if (k.startsWith("ppt/slides/slide") && k.endsWith(".xml") && !k.contains("_rels")) {
                    fallback.add(k);
                }
            }
            sortNatural(fallback);
            slideParts.addAll(fallback);
        }

        /* ---------- masters (theme via master rels — theme1 vs notes theme2) */
        List<String> masterParts = new ArrayList<>();
        for (String k : parts.keySet()) {
            if (k.startsWith("ppt/slideMasters/slideMaster") && k.endsWith(".xml") && !k.contains("_rels")) {
                masterParts.add(k);
            }
        }
        sortNatural(masterParts);

        List<PartInfo> masters = new ArrayList<>();
        Map<String, PartInfo> layoutInfo = new HashMap<>();
        Theme firstTheme = null;
        Scheme master0Scheme = null;
        Lvl[][] master0Tx = null;

        for (String mp : masterParts) {
            byte[] mdata = parts.get(mp);
            if (mdata == null) continue;
            String mbase = mp.substring(0, mp.lastIndexOf('/') + 1);
            Map<String, String> mrels = rels(parts, mbase + "_rels/" + mp.substring(mp.lastIndexOf('/') + 1) + ".rels");

            /* theme = the one THIS master references */
            Theme theme = null;
            if (mrels != null) {
                for (Map.Entry<String, String> en : mrels.entrySet()) {
                    if (en.getValue() != null && en.getValue().contains("theme")) {
                        byte[] tdata = parts.get(resolve(mbase, en.getValue()));
                        if (tdata != null) {
                            theme = parseTheme(tdata);
                            break;
                        }
                    }
                }
            }
            if (theme == null) theme = new Theme();
            if (firstTheme == null) firstTheme = theme;

            Scheme scheme = new Scheme(theme);
            scheme.clrMap.putAll(parseClrMap(mdata));
            if (scheme.clrMap.isEmpty()) {
                scheme.clrMap.put("bg1", "lt1");
                scheme.clrMap.put("tx1", "dk1");
                scheme.clrMap.put("bg2", "lt2");
                scheme.clrMap.put("tx2", "dk2");
            }

            /* master: clrMap + txStyles + shapes (chrome + ph carriers) */
            Lvl[][] txStyles = parseTxStyles(mdata, scheme);
            PartInfo masterInfo = parsePartInfo(mdata, parts, mrels, mbase,
                    doc.slideW, doc.slideH, scheme, M_MASTER, txStyles,
                    theme.majorFont, theme.minorFont);
            masters.add(masterInfo);
            if (master0Scheme == null) {
                master0Scheme = scheme;
                master0Tx = txStyles;
            }

            /* layouts owned by this master */
            if (mrels != null) {
                List<String> lp = new ArrayList<>();
                for (String target : mrels.values()) {
                    if (target != null && target.contains("slideLayout")) {
                        String lk = resolve(mbase, target);
                        if (parts.containsKey(lk)) lp.add(lk);
                    }
                }
                sortNatural(lp);
                for (String lk : lp) {
                    byte[] ldata = parts.get(lk);
                    String lbase = lk.substring(0, lk.lastIndexOf('/') + 1);
                    Map<String, String> lrels = rels(parts, lbase + "_rels/" + lk.substring(lk.lastIndexOf('/') + 1) + ".rels");
                    PartInfo li = parsePartInfo(ldata, parts, lrels, lbase,
                            doc.slideW, doc.slideH, scheme, M_LAYOUT, txStyles,
                            theme.majorFont, theme.minorFont);
                    li.scheme = scheme;
                    li.txStyles = txStyles;
                    li.master = masterInfo;
                    li.fontMajor = theme.majorFont;
                    li.fontMinor = theme.minorFont;
                    layoutInfo.put(lk, li);
                }
            }
        }
        if (masters.isEmpty()) masters.add(new PartInfo());

        if (firstTheme != null) {
            doc.fontMajor = firstTheme.majorFont;
            doc.fontMinor = firstTheme.minorFont;
        }
        if (master0Scheme == null) {
            master0Scheme = new Scheme(firstTheme == null ? new Theme() : firstTheme);
        }
        if (master0Tx == null) master0Tx = new Lvl[][]{new Lvl[9], new Lvl[9], new Lvl[9]};

        /* ---------- slides (v1.21: per-slide master chain via the layout;
           masters[0] is only the fallback) */
        int idx = 0;
        for (String sp : slideParts) {
            byte[] data = parts.get(sp);
            if (data == null) continue;
            idx++;   /* v1.29 FIX: count only PRESENT parts, so a missing
                        slide part no longer punches a hole into the
                        <a:fld type="slidenum"> numbering sequence */
            String base = sp.substring(0, sp.lastIndexOf('/') + 1);
            Map<String, String> srels = rels(parts, base + "_rels/" + sp.substring(sp.lastIndexOf('/') + 1) + ".rels");

            /* this slide's layout */
            PartInfo layout = null;
            PartInfo master = masters.get(0);
            Scheme slideScheme = master0Scheme;
            Lvl[][] slideTx = master0Tx;
            if (srels != null) {
                for (Map.Entry<String, String> en : srels.entrySet()) {
                    String target = en.getValue();
                    if (target != null && target.contains("slideLayout")) {
                        PartInfo pi = layoutInfo.get(resolve(base, target));
                        if (pi != null) layout = pi;
                    }
                }
            }
            if (layout == null && layoutInfo.size() > 0) {
                layout = layoutInfo.values().iterator().next();
            }
            if (layout != null) {
                if (layout.master != null) master = layout.master;
                if (layout.scheme != null) slideScheme = layout.scheme;
                if (layout.txStyles != null) slideTx = layout.txStyles;
            }

            /* v1.29 FIX: per-slide theme fonts follow the same master chain
               as scheme/txStyles above (doc-level fonts stay the fallback). */
            String slideFontMajor = doc.fontMajor, slideFontMinor = doc.fontMinor;
            if (layout != null) {
                if (layout.fontMajor != null) slideFontMajor = layout.fontMajor;
                if (layout.fontMinor != null) slideFontMinor = layout.fontMinor;
            }

            ParsedTarget tgt = parseTree(data, parts, srels, base, doc.slideW, doc.slideH,
                    slideScheme, layout, master, slideTx, M_SLIDE, idx,
                    slideFontMajor, slideFontMinor, slideParts);

            PptDoc.Slide slide = new PptDoc.Slide();
            slide.background = tgt.bg != null ? tgt.bg : new PptDoc.Fill();
            slide.shapes.addAll(tgt.shapes);
            slide.transition = tgt.transition;

            /* ---------- v1.23: the slide's own background picture goes
               beneath the master/layout chrome ---------- */
            for (PptDoc.Shape bgSh : tgt.bgPics) {
                slide.bgShapes.add(bgSh);
            }

            /* ---------- v1.23: pk:anims — entrance animations + auto
               advance; index first, name fallback. Only slide mode ever
               collects anims, so layouts/masters stay untouched ---------- */
            slide.autoSec = tgt.autoSec;
            for (int animI = 0; animI < tgt.anims.size(); animI++) {
                AnimRec rec = tgt.anims.get(animI);
                PptDoc.Shape animSh = null;
                if (rec.ix >= 0 && rec.ix < slide.shapes.size()) {
                    animSh = slide.shapes.get(rec.ix);
                }
                if (animSh == null && rec.id != null && rec.id.length() > 0) {
                    for (int nameI = 0; nameI < slide.shapes.size(); nameI++) {
                        if (rec.id.equals(slide.shapes.get(nameI).name)) {
                            animSh = slide.shapes.get(nameI);
                            break;
                        }
                    }
                }
                if (animSh != null && rec.a >= 1 && rec.a <= 7) animSh.anim = rec.a;
            }

            /* ---------- chrome: master shapes first, then layout shapes -- */
            if (layout != null) {
                if (layout.showMasterSp && master != null) {
                    for (PptDoc.Shape sh : master.bgShapes) {
                        slide.bgShapes.add(sh.copy());
                    }
                }
                for (PptDoc.Shape sh : layout.bgShapes) {
                    slide.bgShapes.add(sh.copy());
                }
            } else {
                for (PptDoc.Shape sh : master.bgShapes) {
                    slide.bgShapes.add(sh.copy());
                }
            }
            /* resolve slide-number fields in chrome */
            for (PptDoc.Shape sh : slide.bgShapes) {
                resolveSlideNum(sh, idx);
            }

            if (slide.background.type == PptDoc.Fill.NONE) {
                if (layout != null && layout.bg != null) slide.background = cloneFillOf(layout.bg);
                else if (master != null && master.bg != null) slide.background = cloneFillOf(master.bg);
            }

            /* ---------- speaker notes (v1.21): the notesSlide this slide
               references carries the presenter text in its body ph -- */
            if (srels != null) {
                for (Map.Entry<String, String> en : srels.entrySet()) {
                    String target = en.getValue();
                    if (target != null && target.contains("notesSlide")) {
                        byte[] ndata = parts.get(resolve(base, target));
                        if (ndata != null) {
                            String notes = parseNotesText(ndata);
                            if (notes != null && notes.length() > 0) slide.notes = notes;
                        }
                    }
                }
            }
            doc.slides.add(slide);
        }
        if (doc.slides.isEmpty()) doc.newSlide();
        return doc;
    }

    /* v1.21: extract the presenter text from a notesSlide part. The notes
       body lives in the <p:sp> whose <p:ph type="body"/>; other shapes
       (slide image, slide number, header) are skipped. Paragraphs join
       with '\n', runs join inline — matching how PowerPoint displays them. */
    private static String parseNotesText(byte[] data) {
        try {
            XmlPullParser p = parser(data);
            int ev = p.getEventType();
            boolean inSp = false, phSeen = false, phIsBody = false;
            boolean isBodySp = false, inT = false;
            StringBuilder out = new StringBuilder();
            StringBuilder cur = new StringBuilder();
            while ((ev = p.next()) != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    String name = local(p.getName());
                    if ("sp".equals(name)) {
                        inSp = true; phSeen = false; phIsBody = false; isBodySp = false;
                    } else if (inSp && "ph".equals(name)) {
                        phSeen = true;
                        String t = attr(p, "type");
                        phIsBody = t == null || t.length() == 0 || "body".equals(t);
                    } else if (inSp && "txBody".equals(name)) {
                        isBodySp = phSeen && phIsBody;
                    } else if (isBodySp && "t".equals(name)) {
                        inT = true;
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    String name = local(p.getName());
                    if ("t".equals(name) && inT) {
                        inT = false;
                    } else if ("p".equals(name) && isBodySp) {
                        if (out.length() > 0) out.append('\n');
                        out.append(cur);
                        cur.setLength(0);
                    } else if ("txBody".equals(name)) {
                        isBodySp = false;
                    } else if ("sp".equals(name)) {
                        inSp = false; isBodySp = false; inT = false;
                    }
                } else if (ev == XmlPullParser.TEXT && inT) {
                    cur.append(p.getText());
                }
            }
            String s = out.toString();
            while (s.endsWith("\n")) s = s.substring(0, s.length() - 1);
            return s;
        } catch (Throwable t) {
            return null;
        }
    }

    private static PptDoc.Fill cloneFillOf(PptDoc.Fill f) {
        PptDoc.Fill n = new PptDoc.Fill();
        n.type = f.type; n.color = f.color; n.color2 = f.color2;
        n.gradHorizontal = f.gradHorizontal; n.gradAngleDeg = f.gradAngleDeg;
        return n;
    }

    /* resolves <a:fld type="slidenum"> text inside chrome shapes */
    private static void resolveSlideNum(PptDoc.Shape sh, int slideIdx) {
        List<PptDoc.Para> paras = null;
        if (sh instanceof PptDoc.TextShape) paras = ((PptDoc.TextShape) sh).paras;
        else if (sh instanceof PptDoc.AutoShape) paras = ((PptDoc.AutoShape) sh).paras;
        if (paras == null) return;
        for (PptDoc.Para p : paras) {
            for (PptDoc.Run r : p.runs) {
                if (r.fld == 1) r.text = String.valueOf(slideIdx);
            }
        }
    }

    private static Map<String, String> rels(Map<String, byte[]> parts, String path) {
        Map<String, String> out = new HashMap<>();
        byte[] data = parts.get(norm(path));
        if (data == null) return out;
        try {
            XmlPullParser p = parser(data);
            int ev = p.getEventType();
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && "Relationship".equals(local(p.getName()))) {
                    String id = attr(p, "Id");
                    String target = attr(p, "Target");
                    if (id != null && target != null) out.put(id, target);
                }
                ev = p.next();
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /* v1.29: hyperlink targets that denote a real external URL. Internal
       relationships (slideN.xml, ../media/..., notesSlides/...) must never
       be treated as browsable links. */
    private static boolean isExternalUrl(String t) {
        if (t == null) return false;
        String s = t.toLowerCase(java.util.Locale.ROOT);
        return s.startsWith("http://") || s.startsWith("https://")
                || s.startsWith("mailto:") || s.startsWith("ftp://")
                || s.startsWith("ftps://") || s.startsWith("file://")
                || s.startsWith("tel:") || s.startsWith("news:");
    }

    /* v1.36: broader external test for MEDIA relationships — keeps the
       link alive in the file even for non-browsable schemes (file:, mhtml:,
       cid:, onehttp:...). A "scheme:" prefix before any '/' or a UNC
       '//server' start both qualify; plain relative OPC targets never do. */
    private static boolean looksExternal(String t) {
        if (t == null) return false;
        if (isExternalUrl(t)) return true;
        if (t.startsWith("\\\\") || t.startsWith("//")) return true;
        int slash = t.indexOf('/');
        int colon = t.indexOf(':');
        return colon > 0 && (slash < 0 || colon < slash);
    }

    /* ======================================================================
       tree parser — slides, layouts and masters share one event loop
       ====================================================================== */
    private static final int M_SLIDE = 0, M_LAYOUT = 1, M_MASTER = 2;

    /* v1.23: our private animation extLst URI — matched exactly (case is
       ignored) so PowerPoint's own p:ext elements are never mis-grabbed;
       unknown exts keep being skipped exactly as before */
    private static final String ANIM_EXT_URI = "{5A0E2C91-8F4B-4D3A-9E7C-6F1D2B3A4C5D}";

    private static final class ParsedTarget {
        final List<PhInfo> phs = new ArrayList<>();
        PptDoc.Fill bg = null;
        final List<PptDoc.Shape> shapes = new ArrayList<>();
        /* v1.22: slide transition name (fade/push/...) */
        String transition = null;
        /* v1.23: the part's OWN background picture (p:bg with a blipFill);
           filled in slide mode only — layouts/masters take the plain
           shapes path (shapes -> chrome bgShapes) instead */
        final List<PptDoc.Shape> bgPics = new ArrayList<>();
        /* v1.23: pk:anims entrance animations + auto-advance seconds */
        float autoSec = 0f;
        final List<AnimRec> anims = new ArrayList<>();
    }

    /* v1.23: one <pk:s ix=".." id=".." a=".."/> entry — shape index within
       the slide's shapes list, the shape name (may be empty) and the
       entrance-animation code 1..7 */
    private static final class AnimRec {
        final int ix;
        final String id;
        final int a;

        AnimRec(int ix, String id, int a) {
            this.ix = ix;
            this.id = id;
            this.a = a;
        }
    }

    private static final class SpBuilder {
        int x, y, w, h;
        boolean hasXfrm;
        int rot;
        boolean flipH, flipV;
        int geom = -1;
        boolean connector = false;
        /* v1.30 FIX: p:cNvSpPr/@txBox="1" marks a real TEXT BOX. The reader
           used to ignore it, so a PowerPoint text box (txBox=1 + prstGeom rect)
           imported as an AUTOSHAPE and our own saved text boxes flipped type
           on every open→save cycle. */
        boolean txBox = false;
        String phType, phIdx;
        boolean hasPh = false;
        PptDoc.Fill fill = new PptDoc.Fill();
        boolean fillExplicit = false;
        PptDoc.Line line = new PptDoc.Line();
        boolean lnExplicit = false;
        /* v1.23: shape shadow (spPr) + text shadow (rPr), from effectLst */
        boolean shadow = false, textShadow = false;
        final List<PptDoc.Para> paras = new ArrayList<>();
        final PptDoc.BodyPr body = new PptDoc.BodyPr();
        int bodySeen;         // bitmask of explicitly-set bodyPr attrs

        /* p:style theme references */
        int styleFillIdx = -1, styleLnIdx = -1;
        int styleFillClr = 0, styleLnClr = 0, styleFontClr = 0;

        /* v1.27: freeform geometry (a:custGeom) — normalized 0..1 polygon */
        boolean hasCustGeom = false;
        float[] custX, custY;
        boolean custClosed = true;
        /* v1.27: picture fill on the shape (spPr/a:blipFill) */
        byte[] picFillData;
        String picFillFormat = "png";

        PptDoc.Para curPara;
        PptDoc.Run curRun;
        StringBuilder textBuf;

        PptDoc.Shape build() {
            PptDoc.Shape out;
            if (connector) {
                PptDoc.Connector c = new PptDoc.Connector();
                out = c;
            } else if (hasCustGeom) {
                /* v1.27: freeform — AutoShape with a custom polygon path
                   (previously degraded to an invisible text box) */
                PptDoc.AutoShape a = new PptDoc.AutoShape(PptDoc.Shape.TYPE_RECT);
                a.paras.addAll(paras);
                a.body.copyFrom(body);
                a.pathX = custX; a.pathY = custY; a.pathClosed = custClosed;
                out = a;
            } else if (geom >= 0 && !txBox) {
                PptDoc.AutoShape a = new PptDoc.AutoShape(geom);
                a.paras.addAll(paras);
                a.body.copyFrom(body);
                out = a;
            } else {
                PptDoc.TextShape t = new PptDoc.TextShape();
                t.paras.addAll(paras);
                t.body.copyFrom(body);
                out = t;
            }
            out.x = x; out.y = y; out.w = w; out.h = h;
            out.rot = rot; out.flipH = flipH; out.flipV = flipV;
            out.fill = fill;
            out.line = line;
            out.picFillData = picFillData;
            out.picFillFormat = picFillFormat;
            out.shadow = shadow;
            out.textShadow = textShadow;
            return out;
        }
    }

    /* color context inside a shape */
    private static final int CTX_NONE = 0, CTX_FILL = 1, CTX_LINE = 2, CTX_TEXT = 3, CTX_GRAD1 = 4, CTX_GRAD2 = 5, CTX_STYLEFILL = 6;

    private static ParsedTarget parseTree(byte[] data, Map<String, byte[]> parts,
                                          Map<String, String> rels, String base,
                                          int slideW, int slideH, Scheme scheme,
                                          PartInfo layout, PartInfo master,
                                          Lvl[][] txStyles, int mode, int slideIdx,
                                          String fontMajor, String fontMinor,
                                          List<String> slideParts) {
        ParsedTarget tgt = new ParsedTarget();
        boolean isSlide = mode == M_SLIDE;
        try {
            XmlPullParser p = parser(data);
            int ev = p.getEventType();
            Deque<int[]> groups = new ArrayDeque<>();  // offX,offY,extW,extH,chOffX,chOffY,chExtW,chExtH,rot
            SpBuilder sp = null;
            PptDoc.Picture pic = null;
            PptDoc.Table table = null;
            PptDoc.TableRow row = null;
            PptDoc.TableCell cell = null;
            boolean inTable = false, inTc = false, inTxBody = false;
            /* v1.27: inside <a:t> — only TEXT between a:t tags is text content */
            boolean inT = false;
            boolean inPic = false, inFrame = false;
            boolean inLn = false, inBg = false, inGrad = false, inStyleEl = false;
            boolean inLstStyle = false;
            int gradN = 0, bgGradN = 0, bgRefIdx = 0;
            int colorCtx = CTX_NONE;
            List<String> mods = new ArrayList<>();
            /* v1.30 FIX: color modifiers (tint/shade/lumMod/lumOff/satMod/alpha)
               are CHILD ELEMENTS of the color element — they arrive AFTER its
               START tag. The old code resolved and assigned the color at START
               with whatever mods were left from the PREVIOUS color, so every
               modifier set leaked onto the NEXT color parsed (themed gradients
               went raw, an alpha/tint of one shape hit the following shape's
               srgbClr — real decks showed wrong colors throughout). The base
               color is now parked here and the assignment happens at the
               color element's END tag, when its own modifiers are complete. */
            String pendClrName = null;          // "srgbClr" | "schemeClr" | "sysClr"
            int pendClrBase = 0;                // literal base color (srgb/sys)
            String pendClrSlot = null;          // scheme slot name (schemeClr)
            final List<String> pendClrMods = new ArrayList<>();
            String pendingBlip = null;
            float[] crop = new float[4];
            PhInfo collectingPh = null;
            String lvlColorCtx = null;
            PptDoc.Para curCellPara = null;
            PptDoc.Run curCellRun = null;
            StringBuilder cellText = null;
            /* v1.27: table-cell property parsing (a:tcPr) — fills, borders,
               margins and anchor of a cell */
            boolean inTcPr = false;
            int tcEdgeIdx = -1;            // 0=L,1=T,2=R,3=B while inside a:lnL/T/R/B of tcPr
            /* v1.22 BUGFIX: a graphicFrame's <p:xfrm> arrives BEFORE <a:tbl>,
               so its off/ext must be parked until the table starts — before,
               tables imported from real decks kept w=0/h=0 and were dropped */
            int frameX = 0, frameY = 0, frameW = 0, frameH = 0;
            boolean frameHasGeom = false;
            String frameUri = null;
            /* v1.22: video inside p:pic (a:videoFile r:link) */
            String pendingVideoRid = null;
            boolean sawVideoFile = false;
            /* v1.31: the p:pic's own cNvPr name (display name of the media) */
            String picName = null;
            /* v1.22: slide transition capture */
            boolean inTransition = false;
            String transChild = null;
            /* v1.23: background picture — r:embed of the blip in p:bgPr */
            String bgBlipRid = null;
            /* v1.23: inside a:spPr (shape shadow) / inside a:rPr (text shadow) */
            boolean inSpPr = false, inRPr = false;
            /* v1.27: freeform geometry (a:custGeom/a:pathLst) */
            boolean inCustGeom = false;
            float custW = 0, custH = 0, custLastX = 0, custLastY = 0;
            final List<Float> custXs = new ArrayList<>();
            final List<Float> custYs = new ArrayList<>();
            final List<float[]> custBez = new ArrayList<>();
            String custOp = null;
            boolean custClosedPath = true;
            /* v1.23: inside our pk:anims p:ext (slides only) */
            boolean inAnimsExt = false;
            /* v1.24: inside a:pPr/a:defRPr of the current paragraph — routes
               latin/cs typefaces and colors to the paragraph defaults */
            boolean inPPrDefRPr = false;
            /* v1.24: rotation of a graphicFrame (tables/charts), applied
               alongside the parked frame geometry */
            int frameRot = 0;

            while (ev != XmlPullParser.END_DOCUMENT) {
                String name = (ev == XmlPullParser.START_TAG || ev == XmlPullParser.END_TAG) ? local(p.getName()) : null;
                if (ev == XmlPullParser.START_TAG) {
                    /* ------------- containers ------------- */
                    if ("grpSp".equals(name)) {
                        groups.push(new int[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
                    } else if ("cNvSpPr".equals(name) && sp != null) {
                        /* v1.30 FIX: honor p:cNvSpPr/@txBox — a real text box
                           stays a TextShape even when it carries a prstGeom */
                        sp.txBox = "1".equals(attr(p, "txBox")) || "true".equals(attr(p, "txBox"));
                    } else if ("sp".equals(name) || "cxnSp".equals(name)) {
                        sp = new SpBuilder();
                        if ("cxnSp".equals(name)) {
                            sp.connector = true;
                            sp.geom = PptDoc.Shape.TYPE_LINE;
                        }
                    } else if ("cNvPr".equals(name) && inPic && pic != null) {
                        /* v1.31 FIX: capture the pic's OWN name — the video
                           display name used to be the media PART name, so a
                           save/rename cycle drifted it ("clip.mpg" ->
                           "vid1.mpg"); PowerPoint keeps the author's name. */
                        String pn = attr(p, "name");
                        if (pn != null && pn.length() > 0) picName = pn;
                    } else if ("pic".equals(name)) {
                        inPic = true;
                        pic = new PptDoc.Picture();
                        pendingBlip = null;
                        pendingVideoRid = null;
                        sawVideoFile = false;
                        picName = null;
                        crop[0] = crop[1] = crop[2] = crop[3] = 0;
                    } else if ("graphicFrame".equals(name)) {
                        inFrame = true;
                        frameHasGeom = false;
                        frameX = frameY = frameW = frameH = 0;
                        frameRot = 0;   /* v1.24 */
                    } else if ("tbl".equals(name)) {
                        inTable = true;
                        table = new PptDoc.Table();
                        /* v1.22: apply the parked frame geometry */
                        if (frameHasGeom) {
                            table.x = frameX; table.y = frameY;
                            table.w = frameW; table.h = frameH;
                        }
                        /* v1.24: tables keep their rotation (was dropped) */
                        table.rot = frameRot;
                    } else if ("bg".equals(name)) {
                        inBg = true;
                        if (tgt.bg == null) tgt.bg = new PptDoc.Fill();
                    } else if ("bgRef".equals(name) && inBg) {
                        /* theme background style: idx 1001/1002 -> solid phClr,
                           1003 -> gradient. clr captured by the color leaf. */
                        bgRefIdx = parseInt(attr(p, "idx"), 1001);
                        bgGradN = -bgRefIdx;   /* negative marks bgRef */
                    } else if ("graphicData".equals(name)) {
                        frameUri = attr(p, "uri");
                    } else if ("chart".equals(name) && inFrame && frameUri != null
                            && frameUri.contains("chart") && rels != null) {
                        /* v1.22: real chart import — the c:chart r:id points
                           at ppt/charts/chartN.xml which we parse inline */
                        String rid = attr(p, "id");
                        byte[] chartXml = rid == null ? null : parts.get(resolve(base, rels.get(rid)));
                        if (chartXml != null) {
                            PptDoc.Chart ch = parseChartShape(chartXml, scheme);
                            ch.x = frameX; ch.y = frameY;
                            ch.w = frameW; ch.h = frameH;
                            ch.rot = frameRot;   /* v1.24: chart rotation (was dropped) */
                            mapGroup(ch, groups);
                            if (ch.w > 0 && ch.h > 0) tgt.shapes.add(ch);
                        }
                    } else if ("relIds".equals(name) && inFrame && frameUri != null
                            && frameUri.contains("diagram") && rels != null) {
                        /* v1.22: SmartArt — PowerPoint ships a pre-rendered
                           fallback (ppt/diagrams/drawingN.xml with plain dsp:sp
                           shapes). Parse those like normal shapes. */
                        String dmRid = attr(p, "dm");
                        String dmPath = dmRid == null ? null : resolve(base, rels.get(dmRid));
                        byte[] dmData = dmPath == null ? null : parts.get(dmPath);
                        if (dmData != null) {
                            String dBase = dmPath.substring(0, dmPath.lastIndexOf('/') + 1);
                            Map<String, String> dRels = rels(parts, dBase + "_rels/"
                                    + dmPath.substring(dmPath.lastIndexOf('/') + 1) + ".rels");
                            byte[] drawing = null;
                            if (dRels != null) {
                                for (String target : dRels.values()) {
                                    if (target != null && target.contains("drawing")) {
                                        drawing = parts.get(resolve(dBase, target));
                                        if (drawing != null) break;
                                    }
                                }
                            }
                            /* v1.30 FIX: modern decks put the diagramDrawing
                               relationship on the SLIDE (2007 namespace
                               .../2007/relationships/diagramDrawing), NOT on
                               data1.xml (which often has no rels at all —
                               PowerPoint writes none). Without the drawing
                               fallback the whole SmartArt silently vanished. */
                            if (drawing == null) {
                                String drawRid = null;
                                for (Map.Entry<String, String> e : rels.entrySet()) {
                                    if (e.getValue() != null && e.getValue().contains("drawing")) {
                                        drawRid = e.getKey();
                                        break;
                                    }
                                }
                                if (drawRid == null) {
                                    for (Map.Entry<String, String> e : rels.entrySet()) {
                                        if (e.getKey() != null && e.getValue() != null
                                                && (e.getValue().endsWith(".xml") || e.getValue().endsWith(".rels"))
                                                && resolve(base, e.getValue()).contains("/diagrams/")) {
                                            drawRid = e.getKey();
                                            break;
                                        }
                                    }
                                }
                                if (drawRid != null) {
                                    String drawPath = resolve(base, rels.get(drawRid));
                                    drawing = parts.get(drawPath);
                                }
                            }
                            if (drawing != null) {
                                /* v1.36 FIX (تصاویر حذف‌شونده در SmartArt): the
                                   drawing part was parsed with the DATA part's
                                   rels/base — but pictures inside the drawing
                                   reference the DRAWING part's OWN rels
                                   (ppt/diagrams/_rels/drawingN.xml.rels →
                                   ../media/imageX.png). Their r:embed resolved
                                   to nothing → imgData=null → the writer emitted
                                   a bare <a:blip/> → the picture was DELETED on
                                   every save. Use the drawing part's own rels. */
                                String drawPathUsed = null;
                                for (String target : dRels.values()) {
                                    if (target != null && target.contains("drawing")) {
                                        String cand = resolve(dBase, target);
                                        if (parts.get(cand) != null) { drawPathUsed = cand; break; }
                                    }
                                }
                                Map<String, String> drawRels = dRels;
                                String drawBase = dBase;
                                if (drawPathUsed == null) {
                                    for (Map.Entry<String, String> e : rels.entrySet()) {
                                        if (e.getValue() != null && e.getValue().contains("drawing")) {
                                            drawPathUsed = resolve(base, e.getValue());
                                            break;
                                        }
                                    }
                                }
                                if (drawPathUsed != null) {
                                    drawBase = drawPathUsed.substring(0, drawPathUsed.lastIndexOf('/') + 1);
                                    drawRels = rels(parts, drawBase + "_rels/"
                                            + drawPathUsed.substring(drawPathUsed.lastIndexOf('/') + 1) + ".rels");
                                }
                                ParsedTarget dt = parseTree(drawing, parts, drawRels, drawBase,
                                        slideW, slideH, scheme, null, null, txStyles, mode, slideIdx,
                                        fontMajor, fontMinor, null);
                                int[] xfrm = diagramRootXfrm(drawing);
                                ArrayDeque<int[]> synth = new ArrayDeque<>();
                                synth.push(new int[]{
                                        frameX, frameY,
                                        xfrm != null && xfrm[2] > 0 ? xfrm[2] : frameW,
                                        xfrm != null && xfrm[3] > 0 ? xfrm[3] : frameH,
                                        xfrm != null ? xfrm[4] : 0, xfrm != null ? xfrm[5] : 0,
                                        xfrm != null && xfrm[6] > 0 ? xfrm[6] : frameW,
                                        xfrm != null && xfrm[7] > 0 ? xfrm[7] : frameH,
                                        0});
                                for (PptDoc.Shape sh : dt.shapes) {
                                    mapGroup(sh, synth);
                                    /* v1.27 FIX: a SmartArt graphicFrame inside
                                       a p:grpSp never received the OUTER group
                                       transform (charts/tables do) */
                                    mapGroup(sh, groups);
                                    if (isSlide && (sh.w > 0 && sh.h > 0)) tgt.shapes.add(sh);
                                }
                            }
                        }
                    } else if (("videoFile".equals(name) || "audioFile".equals(name)) && inPic) {
                        /* v1.22: embedded media — a:videoFile / a:audioFile inside
                           p:nvPicPr; r:link targets the media part */
                        pendingVideoRid = attr(p, "link");
                        sawVideoFile = "videoFile".equals(name);
                    } else if ("tblPr".equals(name) && table != null) {
                        String fr = attr(p, "firstRow");
                        String br = attr(p, "bandRow");
                        if (fr != null) table.firstRow = "1".equals(fr) || "true".equals(fr);
                        if (br != null) table.bandRow = "1".equals(br) || "true".equals(br);
                    } else if ("transition".equals(name) && isSlide) {
                        /* v1.22: slide transition kept for round-trip + slideshow */
                        inTransition = true;
                        transChild = null;
                    } else if ("anims".equals(name) && inAnimsExt) {
                        /* v1.23: pk:anims — slide auto-advance seconds */
                        tgt.autoSec = Math.max(0f, parseFloat(attr(p, "auto"), 0f));
                    } else if ("s".equals(name) && inAnimsExt) {
                        /* v1.23: pk:s — entrance animation of one shape (ix/id/a) */
                        String animId = attr(p, "id");
                        tgt.anims.add(new AnimRec(parseInt(attr(p, "ix"), -1),
                                animId == null ? "" : animId, parseInt(attr(p, "a"), 0)));
                    } else if ("clrMap".equals(name) || "txStyles".equals(name) || "sldLayoutIdLst".equals(name)) {
                        /* skip whole subtrees of non-shape content */
                        inStyleEl = true;
                    } else if ("lstStyle".equals(name) && (sp != null || collectingPh != null)) {
                        inLstStyle = true;
                    }
                    /* ------------- geometry ------------- */
                    else if ("off".equals(name)) {
                        int gx = parseInt(attr(p, "x"), 0), gy = parseInt(attr(p, "y"), 0);
                        if (sp != null && collectingPh == null) { sp.x = gx; sp.y = gy; sp.hasXfrm = true; }
                        else if (collectingPh != null) { collectingPh.x = gx; collectingPh.y = gy; collectingPh.hasGeom = true; }
                        else if (inPic && pic != null) { pic.x = gx; pic.y = gy; }
                        else if (inFrame) { frameX = gx; frameY = gy; frameHasGeom = true; }
                        else if (!groups.isEmpty()) {
                            int[] g = groups.peek();
                            if (g[2] == 0 && g[3] == 0) { g[0] = gx; g[1] = gy; }
                        }
                    } else if ("ext".equals(name)) {
                        /* v1.23: an ext carrying a uri attribute is a p:extLst
                           member, not geometry — only our animation GUID is
                           opened (slides only); every other ext keeps flowing
                           through the old no-op geometry path, exactly as
                           before.
                           v1.27 FIX: extLst members (a16:creationId etc.)
                           must NEVER be treated as geometry — an <a:ext
                           uri="..."> with no cx/cy used to zero w/h AND set
                           hasXfrm=true, which silently broke placeholder
                           inheritance (the slide then looked like it had
                           explicit geometry) and dropped shapes whose ext
                           appeared after the real xfrm. */
                        String extUri = attr(p, "uri");
                        String cxv = attr(p, "cx"), cyv = attr(p, "cy");
                        if (extUri != null && ANIM_EXT_URI.equalsIgnoreCase(extUri) && isSlide) {
                            inAnimsExt = true;
                        } else if (extUri == null || cxv != null || cyv != null) {
                            int gw = parseInt(cxv, 0), gh = parseInt(cyv, 0);
                            if (sp != null && collectingPh == null) { sp.w = gw; sp.h = gh; sp.hasXfrm = true; }
                            else if (collectingPh != null) { collectingPh.w = gw; collectingPh.h = gh; collectingPh.hasGeom = true; }
                            else if (inPic && pic != null) { pic.w = gw; pic.h = gh; }
                            else if (inFrame) { frameW = gw; frameH = gh; frameHasGeom = true; }
                            else if (!groups.isEmpty()) {
                                int[] g = groups.peek();
                                if (g[2] == 0 && g[3] == 0) { g[2] = gw; g[3] = gh; }
                            }
                        }
                        /* else: pure extLst marker (has uri, no cx/cy) — ignored */
                    } else if ("chOff".equals(name) && !groups.isEmpty()) {
                        int[] g = groups.peek();
                        g[4] = parseInt(attr(p, "x"), 0);
                        g[5] = parseInt(attr(p, "y"), 0);
                    } else if ("chExt".equals(name) && !groups.isEmpty()) {
                        int[] g = groups.peek();
                        g[6] = parseInt(attr(p, "cx"), 0);
                        g[7] = parseInt(attr(p, "cy"), 0);
                    } else if ("xfrm".equals(name)) {
                        int rot = parseInt(attr(p, "rot"), 0);
                        boolean fh = "1".equals(attr(p, "flipH"));
                        boolean fv = "1".equals(attr(p, "flipV"));
                        if (sp != null) { sp.rot = rot; sp.flipH = fh; sp.flipV = fv; }
                        else if (collectingPh != null) { }
                        else if (inPic && pic != null) { pic.rot = rot; pic.flipH = fh; pic.flipV = fv; }
                        else if (inFrame) { frameRot = rot; }   /* v1.24: tables/charts */
                        else if (!groups.isEmpty()) {
                            int[] g = groups.peek();
                            g[8] = rot;
                            /* v1.27 FIX: group-level flipH/flipV — mirrored
                               groups used to lose the mirror on every child */
                            g[9] = fh ? 1 : 0;
                            g[10] = fv ? 1 : 0;
                        }
                    } else if ("prstGeom".equals(name)) {
                        if (sp != null) sp.geom = geomType(attr(p, "prst"));
                        else if (inPic && pic != null) pic.type = geomType(attr(p, "prst"));   /* v1.30: picture frame geometry */
                        else if (collectingPh != null) { /* rect default */ }
                    } else if ("custGeom".equals(name) && sp != null && !inLstStyle) {
                        /* v1.27 FIX: freeform shapes — a:custGeom/a:pathLst
                           parsed into a polygon (moveTo/lnTo sampled
                           bezier segments); previously freeforms degraded to
                           empty TEXT boxes and their silhouette vanished */
                        inCustGeom = true;
                        custXs.clear(); custYs.clear(); custBez.clear();
                        custOp = null; custClosedPath = false;   /* v1.29 FIX: only <a:close/> closes */
                        custW = 0; custH = 0; custLastX = 0; custLastY = 0;
                    } else if ("path".equals(name) && inCustGeom) {
                        custW = parseInt(attr(p, "w"), 0);
                        custH = parseInt(attr(p, "h"), 0);
                    } else if ("moveTo".equals(name) && inCustGeom) {
                        custOp = "move"; custBez.clear();
                    } else if ("lnTo".equals(name) && inCustGeom) {
                        custOp = "ln"; custBez.clear();
                    } else if ("cubicBezTo".equals(name) && inCustGeom) {
                        custOp = "cbez"; custBez.clear();
                    } else if ("quadBezTo".equals(name) && inCustGeom) {
                        custOp = "qbez"; custBez.clear();
                    } else if ("close".equals(name) && inCustGeom) {
                        custClosedPath = true;
                    } else if ("pt".equals(name) && inCustGeom && custOp != null) {
                        float px = parseInt(attr(p, "x"), 0), py = parseInt(attr(p, "y"), 0);
                        if ("move".equals(custOp) || "ln".equals(custOp)) {
                            custXs.add(px); custYs.add(py);
                            custLastX = px; custLastY = py;
                            custOp = null;
                        } else {
                            custBez.add(new float[]{px, py});
                            int need = "cbez".equals(custOp) ? 3 : 2;
                            if (custBez.size() >= need) {
                                int steps = need == 3 ? 8 : 5;
                                for (int i = 1; i <= steps; i++) {
                                    float t = (float) i / steps, u = 1f - t;
                                    float bx, by;
                                    if (need == 3) {
                                        float[] c1 = custBez.get(0), c2 = custBez.get(1), c3 = custBez.get(2);
                                        bx = u*u*u*custLastX + 3*u*u*t*c1[0] + 3*u*t*t*c2[0] + t*t*t*c3[0];
                                        by = u*u*u*custLastY + 3*u*u*t*c1[1] + 3*u*t*t*c2[1] + t*t*t*c3[1];
                                    } else {
                                        float[] c1 = custBez.get(0), c2 = custBez.get(1);
                                        bx = u*u*custLastX + 2*u*t*c1[0] + t*t*c2[0];
                                        by = u*u*custLastY + 2*u*t*c1[1] + t*t*c2[1];
                                    }
                                    custXs.add(bx); custYs.add(by);
                                }
                                custLastX = px; custLastY = py;
                                custOp = null; custBez.clear();
                            }
                        }
                    } else if ("ph".equals(name)) {
                        String ptype = attr(p, "type");
                        String pidx = attr(p, "idx") == null ? "" : attr(p, "idx");
                        if (ptype == null || ptype.length() == 0) ptype = "body";
                        if (!isSlide) {
                            /* layout/master: collect placeholder info */
                            collectingPh = new PhInfo();
                            collectingPh.type = ptype;
                            collectingPh.idx = pidx;
                            tgt.phs.add(collectingPh);
                        } else if (sp != null) {
                            sp.phType = ptype;
                            sp.phIdx = pidx;
                            sp.hasPh = true;
                        }
                    }
                    /* ------------- body properties ------------- */
                    else if ("bodyPr".equals(name)) {
                        /* in layout/master mode the ph's BodyPr goes to the
                           PhInfo carrier (for slide-level inheritance), not
                           to the transient SpBuilder */
                        PptDoc.BodyPr tgtBody = collectingPh != null ? collectingPh.body
                                : (sp != null ? sp.body : null);
                        if (tgtBody != null) {
                            int seenMask = 0;
                            String anchor = attr(p, "anchor");
                            if ("ctr".equals(anchor)) { tgtBody.anchor = 1; seenMask |= 32; }
                            else if ("b".equals(anchor)) { tgtBody.anchor = 2; seenMask |= 32; }
                            else if (anchor != null) { tgtBody.anchor = 0; seenMask |= 32; }
                            String wrap = attr(p, "wrap");
                            if (wrap != null) { tgtBody.wrap = !"none".equals(wrap); seenMask |= 16; }
                            String il = attr(p, "lIns");
                            if (il != null) { tgtBody.insetL = parseInt(il, (int) tgtBody.insetL); seenMask |= 1; }
                            String ir = attr(p, "rIns");
                            if (ir != null) { tgtBody.insetR = parseInt(ir, (int) tgtBody.insetR); seenMask |= 2; }
                            String it = attr(p, "tIns");
                            if (it != null) { tgtBody.insetT = parseInt(it, (int) tgtBody.insetT); seenMask |= 4; }
                            String ib = attr(p, "bIns");
                            if (ib != null) { tgtBody.insetB = parseInt(ib, (int) tgtBody.insetB); seenMask |= 8; }
                            /* v1.27: text columns + vertical text + table-cell bodyPr
                               (v1.29: numCol/vert join bodySeen as bits
                               128/256 so they INHERIT from layout/master) */
                            String nc = attr(p, "numCol");
                            if (nc != null) { tgtBody.numCol = Math.max(1, Math.min(16, parseInt(nc, 1))); seenMask |= 128; }
                            String vt = attr(p, "vert");
                            if (vt != null) {
                                if ("vert270".equals(vt)) tgtBody.vert = 1;
                                else if ("vert".equals(vt)) tgtBody.vert = 2;
                                else if ("eaVert".equals(vt) || "mongolianVert".equals(vt)
                                        || "wordArtVert".equals(vt) || "wordArtVertRtl".equals(vt)) tgtBody.vert = 3;
                                else tgtBody.vert = 0;
                                seenMask |= 256;
                            }
                            if (sp != null) sp.bodySeen = seenMask;
                            if (collectingPh != null) collectingPh.bodySeen = seenMask;
                            /* keep the transient shape's body in sync too
                               (footer chrome renders with the same bodyPr) */
                            if (collectingPh != null && sp != null) sp.body.copyFrom(collectingPh.body);
                        }
                    } else if ("normAutofit".equals(name)) {
                        PptDoc.BodyPr tgtBody = collectingPh != null ? collectingPh.body
                                : (sp != null ? sp.body : null);
                        if (tgtBody != null) {
                            tgtBody.autofit = 1;
                            float fs = parseFloat(attr(p, "fontScale"), 100000f) / 100000f;
                            tgtBody.fontScale = Math.max(0.1f, Math.min(2f, fs));
                            /* v1.27: normAutofit/@lnSpcReduction (10000 = 10%) */
                            String lsr = attr(p, "lnSpcReduction");
                            if (lsr != null) tgtBody.lnSpcReduction =
                                    Math.max(0f, Math.min(0.9f, parseFloat(lsr, 0f) / 100000f));
                            if (collectingPh != null && sp != null) sp.body.copyFrom(collectingPh.body);
                            if (sp != null && collectingPh == null) sp.bodySeen |= 64;
                        }
                    } else if ("spAutoFit".equals(name)) {
                        PptDoc.BodyPr tgtBody = collectingPh != null ? collectingPh.body
                                : (sp != null ? sp.body : null);
                        if (tgtBody != null) {
                            tgtBody.autofit = 2;
                            if (collectingPh != null && sp != null) sp.body.copyFrom(collectingPh.body);
                            if (sp != null && collectingPh == null) sp.bodySeen |= 64;
                        }
                    }
                    /* ------------- p:style (theme refs) ------------- */
                    else if ("lnRef".equals(name) && sp != null) {
                        sp.styleLnIdx = parseInt(attr(p, "idx"), 0);
                        colorCtx = CTX_LINE;
                    } else if ("fillRef".equals(name) && sp != null) {
                        sp.styleFillIdx = parseInt(attr(p, "idx"), 0);
                        /* v1.27 FIX: a fillRef color is a THEME STYLE color —
                           it must NOT set fillExplicit (that hijacked the
                           idx-based gradient/darker rendering in
                           applyStyleRefs and made every themed shape a flat
                           solid) */
                        colorCtx = CTX_STYLEFILL;
                    } else if ("fontRef".equals(name) && sp != null) {
                        colorCtx = CTX_TEXT;
                    }
                    /* ------------- text ------------- */
                    else if ("txBody".equals(name)) {
                        inTxBody = true;
                    } else if ("t".equals(name) && inTxBody) {
                        /* v1.27: mark run-text scope (whitespace between tags
                           in pretty-printed files must not leak into the text) */
                        inT = true;
                    } else if ("pPr".equals(name) && inTxBody && !inLstStyle) {
                        /* v1.26 FIX (CRITICAL, RTL): direct paragraph properties
                           live on <a:pPr> INSIDE <a:p> — never on <a:p> itself.
                           Before v1.26 the reader looked for algn/rtl/lvl/marL/
                           indent on <a:p> (which carries no attributes in valid
                           OOXML), so EVERY imported deck lost its paragraph
                           direction/alignment/indents: Persian decks opened as
                           LTR + left aligned and a save made the loss permanent. */
                        PptDoc.Para cur = inTc && curCellPara != null ? curCellPara
                                : (sp != null ? sp.curPara : null);
                        if (cur == null && inTc && cell != null && !cell.paras.isEmpty()) {
                            cur = cell.paras.get(cell.paras.size() - 1);
                        }
                        if (cur != null) {
                            String algn = attr(p, "algn");
                            if (algn != null) {
                                cur.hasAlign = true;
                                if ("ctr".equals(algn)) cur.align = PptDoc.Para.ALIGN_CTR;
                                else if ("r".equals(algn)) cur.align = PptDoc.Para.ALIGN_R;
                                else if ("just".equals(algn)) cur.align = PptDoc.Para.ALIGN_JUST;
                                else cur.align = PptDoc.Para.ALIGN_L;
                            }
                            String rtlA = attr(p, "rtl");
                            if (rtlA != null) { cur.rtl = "1".equals(rtlA); cur.hasRtl = true; }
                            String lvlS = attr(p, "lvl");
                            if (lvlS != null) cur.lvl = Math.max(0, Math.min(8, parseInt(lvlS, 0)));
                            String marLS = attr(p, "marL");
                            if (marLS != null) { cur.marL = parseInt(marLS, 0); cur.hasMarL = true; }
                            String indentS = attr(p, "indent");
                            if (indentS != null) { cur.indent = parseInt(indentS, 0); cur.hasIndent = true; }
                        }
                    } else if ("p".equals(name) && inTxBody) {
                        boolean inCell = inTc && cell != null;
                        PptDoc.Para para = new PptDoc.Para();
                        String algn = attr(p, "algn");
                        if (algn != null) {
                            para.hasAlign = true;
                            if ("ctr".equals(algn)) para.align = PptDoc.Para.ALIGN_CTR;
                            else if ("r".equals(algn)) para.align = PptDoc.Para.ALIGN_R;
                            else if ("just".equals(algn)) para.align = PptDoc.Para.ALIGN_JUST;
                            else para.align = PptDoc.Para.ALIGN_L;
                        }
                        String rtl = attr(p, "rtl");
                        if (rtl != null) { para.rtl = "1".equals(rtl); para.hasRtl = true; }
                        String lvlS = attr(p, "lvl");
                        if (lvlS != null) para.lvl = Math.max(0, Math.min(8, parseInt(lvlS, 0)));
                        String marLS = attr(p, "marL");
                        if (marLS != null) { para.marL = parseInt(marLS, 0); para.hasMarL = true; }
                        String indentS = attr(p, "indent");
                        if (indentS != null) { para.indent = parseInt(indentS, 0); para.hasIndent = true; }
                        if (inCell) {
                            cell.paras.add(para);
                            curCellPara = para;
                        } else if (sp != null) {
                            sp.paras.add(para);
                            sp.curPara = para;
                        }
                    } else if (("lnSpc".equals(name) || "spcBef".equals(name) || "spcAft".equals(name)) && inTxBody) {
                        lvlColorCtx = name;
                    } else if (("spcPct".equals(name) || "spcPts".equals(name)) && inTxBody) {
                        String vs = attr(p, "val");
                        PptDoc.Para para = inTc && curCellPara != null ? curCellPara
                                : (sp != null ? sp.curPara : null);
                        if (para != null && vs != null) {
                            if ("spcPts".equals(name)) {
                                float pt = parseFloat(vs, -1f) / 100f;
                                if ("lnSpc".equals(lvlColorCtx)) { para.lnSpcPt = pt; para.hasLnSpc = true; }
                                else if ("spcBef".equals(lvlColorCtx)) { para.spcBefPt = pt; para.hasSpcBef = true; }
                                else if ("spcAft".equals(lvlColorCtx)) { para.spcAftPt = pt; para.hasSpcAft = true; }
                            } else {
                                float pct = parseFloat(vs, -1f) / 100000f;
                                if ("lnSpc".equals(lvlColorCtx)) { para.lnSpcPct = Math.max(0.1f, Math.min(4f, pct)); para.hasLnSpc = true; }
                                /* v1.24: spcBef/spcAft may also arrive as
                                   percentages — same 12pt-per-100% scale the
                                   level parser uses; before v1.24 these were
                                   silently dropped for direct paragraphs */
                                else if ("spcBef".equals(lvlColorCtx)) { para.spcBefPct = pct; para.hasSpcBef = true; }
                                else if ("spcAft".equals(lvlColorCtx)) { para.spcAftPct = pct; para.hasSpcAft = true; }
                            }
                        }
                    } else if ("buNone".equals(name) && inTxBody) {
                        PptDoc.Para para = inTc && curCellPara != null ? curCellPara
                                : (sp != null ? sp.curPara : null);
                        if (para != null) { para.hasBullet = true; para.buChar = null; para.buAutoNum = false; para.bullet = false; }
                    } else if ("buChar".equals(name) && inTxBody) {
                        String ch = attr(p, "char");
                        PptDoc.Para para = inTc && curCellPara != null ? curCellPara
                                : (sp != null ? sp.curPara : null);
                        if (para != null && ch != null && ch.length() > 0) {
                            para.buChar = mapBullet(ch, null);
                            para.hasBullet = true;
                            para.bullet = true;
                        }
                    } else if ("buAutoNum".equals(name) && inTxBody) {
                        PptDoc.Para para = inTc && curCellPara != null ? curCellPara
                                : (sp != null ? sp.curPara : null);
                        if (para != null) {
                            para.buAutoNum = true; para.hasBullet = true; para.bullet = true;
                            /* v1.24: numbering format + start index (was dropped) */
                            String ty = attr(p, "type");
                            if (ty != null) para.buType = PptDoc.Para.parseBuType(ty);
                            String st = attr(p, "startAt");
                            if (st != null) para.buStart = Math.max(1, Math.min(32767, parseInt(st, 1)));
                            /* v1.24 compat: v1.23's editor encoded «number
                               with dash» as buAutoNum + buChar='-'; promote
                               it to the real arabicDash format */
                            if ("-".equals(para.buChar)) {
                                if (ty == null) para.buType = PptDoc.Para.NUM_ARABIC_DASH;
                                para.buChar = null;
                            }
                        }
                    } else if ("buSzPct".equals(name) && inTxBody) {
                        /* v1.24: bullet size as percent of text size (was dropped) */
                        PptDoc.Para para = inTc && curCellPara != null ? curCellPara
                                : (sp != null ? sp.curPara : null);
                        String bsVal = attr(p, "val");
                        if (para != null && bsVal != null) {
                            /* v1.27 FIX: element without val used to clamp the
                               -1 sentinel into 0.1 (10% bullets) */
                            para.buSzPct = Math.max(0.1f, Math.min(4f, parseFloat(bsVal, 100000f) / 100000f));
                        }
                    } else if ("buSzPts".equals(name) && inTxBody) {
                        /* v1.24: bullet size in points (was dropped) */
                        PptDoc.Para para = inTc && curCellPara != null ? curCellPara
                                : (sp != null ? sp.curPara : null);
                        String bsVal = attr(p, "val");
                        if (para != null && bsVal != null) {
                            para.buSizePt = Math.max(1f, Math.min(400f, parseFloat(bsVal, 2500f) / 100f));
                        }
                    } else if ("buClr".equals(name) && inTxBody) {
                        lvlColorCtx = "buClr";
                        mods.clear();
                        pendClrName = null;
                        pendClrMods.clear();
                    } else if ("br".equals(name) && inTxBody) {
                        PptDoc.Para para = inTc && curCellPara != null ? curCellPara
                                : (sp != null ? sp.curPara : null);
                        if (para != null) {
                            PptDoc.Run br = new PptDoc.Run();
                            br.text = "\n";
                            if (!para.runs.isEmpty()) {
                                PptDoc.Run proto = para.runs.get(para.runs.size() - 1);
                                br.sizePt = proto.sizePt; br.bold = proto.bold; br.color = proto.color;
                                br.italic = proto.italic; br.underline = proto.underline; br.font = proto.font;
                            }
                            para.runs.add(br);
                        }
                    } else if (("r".equals(name) || "fld".equals(name)) && inTxBody) {
                        boolean inCell = inTc && curCellPara != null;
                        PptDoc.Para para = inCell ? curCellPara : (sp != null ? sp.curPara : null);
                        if (para != null) {
                            PptDoc.Run run = new PptDoc.Run();
                            if ("fld".equals(name)) {
                                String ft = attr(p, "type");
                                if ("slidenum".equals(ft)) run.fld = 1;
                                else run.fld = 2;
                            }
                            /* v1.27 FIX: a run with no <a:rPr> inherits from the
                               paragraph defRPr / lstStyle chain — NOT from the
                               PREVIOUS run. The old proto-copy made "normal
                               **bold** normal" render the trailing run bold
                               and smearing formatting across the paragraph. */
                            para.runs.add(run);
                            if (inCell) { curCellRun = run; cellText = new StringBuilder(); }
                            else if (sp != null) { sp.curRun = run; sp.textBuf = new StringBuilder(); }
                        }
                    } else if ("rPr".equals(name) && inTxBody) {
                        inRPr = true;
                        PptDoc.Run run = (inTc && curCellRun != null) ? curCellRun : (sp != null ? sp.curRun : null);
                        if (run != null) {
                            String sz = attr(p, "sz");
                            String b = attr(p, "b");
                            String i = attr(p, "i");
                            String u = attr(p, "u");
                            if (sz != null) { run.sizePt = Math.max(4f, parseInt(sz, 1800) / 100f); run.hasSize = true; }
                            if (b != null) { run.bold = "1".equals(b); run.hasBold = true; }
                            if (i != null) { run.italic = "1".equals(i); run.hasItalic = true; }
                            if (u != null) { run.underline = u.length() > 0 && !"none".equals(u); run.hasUnder = true; }
                            /* v1.29 FIX: an explicit u="none" now records
                               hasUnder=true so the layout/master chain can no
                               longer re-underline a run the author turned
                               underlining OFF for. */
                            String cap = attr(p, "cap");
                            if ("all".equals(cap)) run.caps = 1;
                            else if ("small".equals(cap)) run.caps = 2;
                            String spcS = attr(p, "spc");
                            if (spcS != null) run.spcPt = parseInt(spcS, 0) / 100f;
                            colorCtx = CTX_TEXT;
                        }
                    } else if ("defRPr".equals(name) && inTxBody) {
                        colorCtx = CTX_TEXT;
                        /* v1.24: a:pPr/a:defRPr — paragraph-level default run
                           props (sz/b/i/u), applied to runs without explicit
                           rPr at </a:p>; font+color arrive via the latin/cs
                           and srgbClr leaf handlers below */
                        PptDoc.Para dpara = inTc && curCellPara != null ? curCellPara
                                : (sp != null ? sp.curPara : null);
                        inPPrDefRPr = dpara != null;
                        if (dpara != null) {
                            String dsz = attr(p, "sz");
                            if (dsz != null) dpara.defSzPt = Math.max(4f, parseInt(dsz, 1800) / 100f);
                            String db = attr(p, "b");
                            if (db != null) dpara.defBold = "1".equals(db) ? 1 : 0;
                            String di = attr(p, "i");
                            if (di != null) dpara.defItalic = "1".equals(di) ? 1 : 0;
                            String du = attr(p, "u");
                            if (du != null && du.length() > 0 && !"none".equals(du)) dpara.defUnder = 1;
                        }
                    } else if ("endParaRPr".equals(name)) {
                        colorCtx = CTX_NONE;
                        /* v1.24: defRPr scope ends here too */
                        inPPrDefRPr = false;
                    } else if ("hlinkClick".equals(name)) {
                        /* v1.21: a:hlinkClick inside a:rPr marks the run as a
                           hyperlink; r:id resolves against THIS part's rels
                           (external http/mailto targets; action-only links
                           keep link=true with no URL). Shape-level
                           hlinkClick (inside p:cNvPr) finds no active run
                           and is ignored. */
                        PptDoc.Run hRun = (inTc && curCellRun != null) ? curCellRun : (sp != null ? sp.curRun : null);
                        if (hRun != null) {
                            hRun.link = true;
                            String rid = attr(p, "id");
                            String action = attr(p, "action");
                            /* v1.29 FIX: rels.get() returns targets for BOTH
                               internal and external relationships. A "go to
                               slide" jump writes Target="slide5.xml" (an
                               internal rel, action="ppaction://hlinksldjump")
                               — storing it in linkUrl made taps try to browse
                               to "slide5.xml". Only accept real external
                               targets now: action links keep link=true with
                               linkUrl=null, and rel targets must carry an
                               external URL scheme (PowerPoint always writes
                               absolute http/https/mailto/ftp/file URLs for
                               TargetMode="External" rels). */
                            boolean isAction = action != null && action.contains("ppaction");
                            String target = (rid != null && rid.length() > 0 && rels != null) ? rels.get(rid) : null;
                            if (!isAction && target != null && target.length() > 0 && isExternalUrl(target)) {
                                hRun.linkUrl = target;
                            } else if (target != null && target.length() > 0 && slideParts != null) {
                                /* v1.29 NEW: internal "go to slide" jump — resolve
                                   the rel target against THIS part, find the
                                   slide's position in the presentation order and
                                   store its 1-based number. The slideshow then
                                   jumps on tap and the writer round-trips it. */
                                String tpath = resolve(base, target);
                                if (tpath != null) {
                                    for (int spi = 0; spi < slideParts.size(); spi++) {
                                        if (tpath.equals(slideParts.get(spi))) {
                                            hRun.linkSlide = spi + 1;
                                            break;
                                        }
                                    }
                                }
                            }
                        }
                    } else if (("latin".equals(name) || "cs".equals(name)) && colorCtx == CTX_TEXT) {
                        String tf = attr(p, "typeface");
                        /* v1.30 FIX: keep a LITERAL typeface. The old code also
                           filtered "Calibri"/"Calibri Light" by name — that
                           dropped an explicitly written <a:latin typeface="Calibri">
                           and, because our own saved text boxes have no placeholder
                           chain to re-supply it, every round-trip silently lost
                           the run font. Only theme REFERENCES ("+mj-lt","+mn-lt")
                           are skipped — they are resolved by the style chain. */
                        if (tf != null && tf.length() > 0 && !tf.startsWith("+")) {
                            PptDoc.Run run = (inTc && curCellRun != null) ? curCellRun : (sp != null ? sp.curRun : null);
                            if (run != null) {
                                run.font = tf;
                                run.hasFont = true;
                            } else if (inPPrDefRPr) {
                                /* v1.24: latin/cs inside a:pPr/a:defRPr —
                                   paragraph-level default typeface */
                                PptDoc.Para dpara = inTc && curCellPara != null ? curCellPara
                                        : (sp != null ? sp.curPara : null);
                                if (dpara != null) dpara.defFont = tf;
                            }
                        }
                    }
                    /* ------------- lstStyle level parsing (layout/master ph) ----- */
                    else if (inLstStyle && name != null && name.startsWith("lvl") && name.endsWith("pPr") && name.length() == 7) {
                        int lvl = name.charAt(3) - '1';
                        if (lvl >= 0 && lvl < 9 && collectingPh != null) {
                            try {
                                collectingPh.lvls[lvl] = parseLvl(p, scheme);
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                    /* ------------- fill / line context ------------- */
                    else if ("spPr".equals(name)) {
                        colorCtx = CTX_FILL;
                        inSpPr = true;
                    } else if ("ln".equals(name)) {
                        inLn = true;
                        colorCtx = CTX_LINE;
                        String w = attr(p, "w");
                        if (sp != null) {
                            sp.line.enabled = true;
                            sp.lnExplicit = true;
                            /* v1.27 FIX: OOXML implicit line width is 9525 EMU
                               (0.75pt), not 1pt */
                            sp.line.widthPt = Math.max(0.25f, parseInt(w, 9525) / 12700f);
                        } else if (inPic && pic != null) {
                            pic.line.enabled = true;
                            if (w != null) pic.line.widthPt = Math.max(0.25f, parseInt(w, 9525) / 12700f);
                        }
                    } else if ("noFill".equals(name) && !inTcPr) {
                        /* v1.31 FIX: !inTcPr — same guard the v1.29 fix gave
                           prstDash. Without it this generic branch matched
                           FIRST for every noFill element and the dedicated
                           cell-edge branch further down the chain was DEAD
                           CODE: <a:lnL><a:noFill/></a:lnL> (a border the
                           author explicitly removed) kept the black default
                           edge the lnL open-tag installed — tables re-opened
                           with phantom borders. */
                        if (inLn) {
                            if (sp != null) { sp.line.enabled = false; sp.lnExplicit = true; }
                            else if (inPic && pic != null) pic.line.enabled = false;
                        } else if (inBg) {
                            tgt.bg.type = PptDoc.Fill.NONE;
                        } else {
                            if (sp != null) { sp.fill.type = PptDoc.Fill.NONE; sp.fillExplicit = true; }
                            else if (inPic && pic != null) pic.fill.type = PptDoc.Fill.NONE;
                        }
                    } else if ("noFill".equals(name) && inTcPr && cell != null) {
                        /* v1.31 FIX: this branch is now REACHABLE (see above) —
                           a noFill inside a:tcPr either disables the current
                           cell edge (inside lnL/T/R/B) or makes the cell fill
                           explicitly transparent */
                        if (tcEdgeIdx >= 0) cell.edges[tcEdgeIdx].enabled = false;
                        else cell.fill.type = PptDoc.Fill.NONE;
                    } else if ("gradFill".equals(name)) {
                        inGrad = true;
                        gradN = 0;
                        if (inBg && tgt.bg != null) {
                            tgt.bg.type = PptDoc.Fill.GRADIENT;
                        }
                    } else if ("lin".equals(name) && inGrad) {
                        float ang = parseFloat(attr(p, "ang"), -1f) / 60000f;
                        PptDoc.Fill f = fillTarget(sp, pic, inBg, tgt);
                        if (f != null && ang >= 0) {
                            f.gradAngleDeg = ang;
                            f.gradHorizontal = Math.abs(ang - 0f) < 1f || Math.abs(ang - 180f) < 1f;
                        }
                    } else if ("gs".equals(name)) {
                        gradN++;
                        colorCtx = inBg ? CTX_NONE : (gradN <= 1 ? CTX_GRAD1 : CTX_GRAD2);
                        if (inBg) bgGradN = gradN;
                    } else if ("prstDash".equals(name) && !inTcPr) {
                        /* v1.29 FIX: !inTcPr — inside a:tcPr the dedicated
                           cell-edge branch (further down this chain) must own
                           the element; with sp/pic both null this generic
                           branch used to swallow it and dashed cell borders
                           were lost. */
                        String v = attr(p, "val");
                        PptDoc.Line l = sp != null ? sp.line : null;
                        if (l == null && inPic && pic != null) l = pic.line;
                        if (l != null) {
                            if ("dash".equals(v)) l.dash = 1;
                            else if ("sysDot".equals(v) || "dot".equals(v)) l.dash = 2;
                            else if ("dashDot".equals(v) || "lgDashDot".equals(v)) l.dash = 3;
                            else if ("sysDash".equals(v) || "lgDash".equals(v)) l.dash = 4;
                        }
                    } else if ("effectLst".equals(name) && (inSpPr || inRPr || inBg)) {
                        /* v1.29 FIX: || inBg — inside p:bgPr the shadow
                           subtree was NOT consumed, so an outerShdw/srgbClr
                           could overwrite the slide background fill with the
                           shadow color. */
                        /* v1.23: shadow effects — the whole subtree is consumed
                           inline so the shadow colors (srgbClr/alpha inside
                           outerShdw) can never rewrite the fill or text color
                           the way they did before; only the PRESENCE of
                           outerShdw matters: shape shadow within spPr, text
                           shadow within rPr (cell runs map to the Table) */
                        int effDepth = 1;
                        while (effDepth > 0) {
                            int eev = p.next();
                            if (eev == XmlPullParser.START_TAG) {
                                if ("outerShdw".equals(local(p.getName()))) {
                                    if (inSpPr) {
                                        if (sp != null) sp.shadow = true;
                                        else if (inPic && pic != null) pic.shadow = true;
                                    } else {
                                        if (sp != null) sp.textShadow = true;
                                        else if (inTc && table != null) table.textShadow = true;
                                    }
                                }
                                effDepth++;
                            } else if (eev == XmlPullParser.END_TAG) {
                                effDepth--;
                            } else if (eev == XmlPullParser.END_DOCUMENT) {
                                break;
                            }
                        }
                    }
                    /* ------------- colors (leaf elements) ------------- */
                    else if ("srgbClr".equals(name) || "schemeClr".equals(name) || "sysClr".equals(name)) {
                        /* v1.30 FIX: park the base color; modifiers arrive as
                           children and the assignment runs at the element's END */
                        pendClrName = name;
                        pendClrSlot = null;
                        pendClrBase = 0;
                        pendClrMods.clear();
                        if ("schemeClr".equals(name)) {
                            pendClrSlot = attr(p, "val");
                        } else if ("sysClr".equals(name)) {
                            pendClrBase = parseColor(attr(p, "lastClr"));
                            /* v1.29 FIX kept: window/windowText compare the VAL
                               attribute, not lastClr */
                            if (pendClrBase == 0) {
                                String sv = attr(p, "val");
                                if ("window".equals(sv)) pendClrBase = 0xFFFFFFFF;
                                else if ("windowText".equals(sv)) pendClrBase = 0xFF000000;
                            }
                        } else {
                            pendClrBase = parseColor(attr(p, "val"));
                        }
                    } else if ("lumMod".equals(name) || "lumOff".equals(name) || "shade".equals(name)
                            || "tint".equals(name) || "alpha".equals(name) || "satMod".equals(name)) {
                        /* v1.30 FIX: a modifier belongs to the pending color
                           element it lives in — never to a global list that the
                           next color would consume */
                        if (pendClrName != null) pendClrMods.add(name + ":" + attr(p, "val"));
                    }
                    /* ------------- pictures ------------- */
                    else if ("blip".equals(name) && inPic) {
                        pendingBlip = attr(p, "embed");
                        /* v1.28 FIX: تصاویر «پیوندی» (r:link) قبلاً کلاً حذف
                           می‌شدند — بدون حتی یک جای‌نگهدار؛ حالا باقی
                           می‌مانند و رندر جای‌نگهدار گویا می‌گیرند */
                        if (pendingBlip == null) pendingBlip = attr(p, "link");
                    } else if ("srcRect".equals(name) && inPic) {
                        crop[0] = parseInt(attr(p, "l"), 0) / 100000f;
                        crop[1] = parseInt(attr(p, "t"), 0) / 100000f;
                        crop[2] = parseInt(attr(p, "r"), 0) / 100000f;
                        crop[3] = parseInt(attr(p, "b"), 0) / 100000f;
                    } else if ("blip".equals(name) && inSpPr && sp != null && !inPic && !inBg && rels != null) {
                        /* v1.27 FIX: picture fill on autoshapes — spPr/a:blipFill
                           (rounded rects, ovals etc. with a photo) used to render
                           as blank/unfilled shapes */
                        String pfRid = attr(p, "embed");
                        if (pfRid != null) {
                            byte[] pfImg = part(parts, resolve(base, rels.get(pfRid)));
                            if (pfImg != null) {
                                sp.picFillData = pfImg;
                                sp.picFillFormat = sniffImgFormat(pfImg);
                                sp.fillExplicit = true;   /* style fillRef must not override */
                            }
                        }
                    } else if ("blip".equals(name) && inBg) {
                        /* v1.23: background picture — blipFill inside p:bgPr */
                        bgBlipRid = attr(p, "embed");
                    }
                    /* ------------- table ------------- */
                    else if ("gridCol".equals(name) && table != null) {
                        table.colWidths.add((float) parseInt(attr(p, "w"), 0));
                    } else if ("tr".equals(name) && inTable) {
                        row = new PptDoc.TableRow();
                        row.heightEmu = parseInt(attr(p, "h"), 0);
                        table.rows.add(row);
                    } else if ("tc".equals(name) && inTable) {
                        inTc = true;
                        cell = new PptDoc.TableCell();
                        cell.spanR = Math.max(1, parseInt(attr(p, "gridSpan"), 1));
                        cell.spanD = Math.max(1, parseInt(attr(p, "rowSpan"), 1));
                        cell.skipR = "1".equals(attr(p, "hMerge"));
                        cell.skipD = "1".equals(attr(p, "vMerge"));
                        if (row != null) row.cells.add(cell);
                    } else if ("tcPr".equals(name) && inTc && cell != null) {
                        /* v1.27 FIX: a:tcPr — cell margins + vertical anchor.
                           Before, explicit cell fills/borders/margins/anchor
                           were ALL silently dropped (the model fields existed
                           but nothing ever wrote them), so tables rendered
                           with fake banding and no borders. */
                        inTcPr = true;
                        tcEdgeIdx = -1;
                        String anch = attr(p, "anchor");
                        if ("ctr".equals(anch)) cell.anchor = 1;
                        else if ("b".equals(anch)) cell.anchor = 2;
                        else if (anch != null) cell.anchor = 0;
                        String ml = attr(p, "marL");
                        if (ml != null) cell.insetL = parseInt(ml, (int) cell.insetL);
                        String mr = attr(p, "marR");
                        if (mr != null) cell.insetR = parseInt(mr, (int) cell.insetR);
                        String mt = attr(p, "marT");
                        if (mt != null) cell.insetT = parseInt(mt, (int) cell.insetT);
                        String mb = attr(p, "marB");
                        if (mb != null) cell.insetB = parseInt(mb, (int) cell.insetB);
                    } else if (("lnL".equals(name) || "lnT".equals(name) || "lnR".equals(name) || "lnB".equals(name))
                            && inTcPr && cell != null) {
                        /* v1.27 FIX: cell border lines (a:tcPr/a:lnL/T/R/B) */
                        tcEdgeIdx = "lnL".equals(name) ? 0 : "lnT".equals(name) ? 1
                                : "lnR".equals(name) ? 2 : 3;
                        PptDoc.Line ln = new PptDoc.Line();
                        ln.enabled = true;
                        String w = attr(p, "w");
                        ln.widthPt = Math.max(0.25f, parseInt(w, 9525) / 12700f);
                        if ("none".equals(attr(p, "cap"))) { /* style hint, unused */ }
                        ln.color = 0xFF000000;
                        cell.edges[tcEdgeIdx] = ln;
                    } else if (("lnL".equals(name) || "lnT".equals(name) || "lnR".equals(name) || "lnB".equals(name))
                            && inTable && !inTcPr) {
                        /* table-wide border in a:tblPr — ignored (theme default) */
                    } else if ("prstDash".equals(name) && inTcPr && cell != null && tcEdgeIdx >= 0) {
                        /* v1.29 FIX: the DrawingML element is a:prstDash —
                           "dashStyle" (VML-era name) never appears in OOXML,
                           so dashed cell borders from third-party decks used
                           to import as solid. */
                        String v = attr(p, "val");
                        if ("dash".equals(v)) cell.edges[tcEdgeIdx].dash = 1;
                        else if ("sysDot".equals(v) || "dot".equals(v)) cell.edges[tcEdgeIdx].dash = 2;
                        else if ("dashDot".equals(v) || "lgDashDot".equals(v)) cell.edges[tcEdgeIdx].dash = 3;
                        else if ("sysDash".equals(v) || "lgDash".equals(v)) cell.edges[tcEdgeIdx].dash = 4;
                    } else if ("noFill".equals(name) && inTcPr && cell != null) {
                        if (tcEdgeIdx >= 0) cell.edges[tcEdgeIdx].enabled = false;
                        else cell.fill.type = PptDoc.Fill.NONE;
                    }
                    /* catch transition children (fade/push/wipe/...) while
                       inside <p:transition> — the first visual child wins */
                    if (inTransition && transChild == null && isVisualTransition(name)) {
                        transChild = name;
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    /* v1.30 FIX: color element closes — its modifiers are now
                       complete, so resolve the final color and run the
                       assignment chain HERE (moved from the START branch where
                       modifiers could not be known yet) */
                    if (pendClrName != null && name != null && pendClrName.equals(name)) {
                        boolean wasScheme = "schemeClr".equals(pendClrName);
                        int c = wasScheme
                                ? scheme.resolve(pendClrSlot, pendClrMods)
                                : Theme.applyMods(pendClrBase, pendClrMods);
                        pendClrName = null;
                        pendClrSlot = null;
                        pendClrMods.clear();
                        if (c != 0 || wasScheme) {
                            if (inTcPr && cell != null) {
                                /* v1.27 FIX: colors inside a:tcPr — cell fill or
                                   border color (previously dropped) */
                                if (tcEdgeIdx >= 0) {
                                    cell.edges[tcEdgeIdx].color = c == 0 ? 0xFF000000 : c;
                                } else {
                                    cell.fill.type = PptDoc.Fill.SOLID;
                                    cell.fill.color = c == 0 ? 0xFFFFFFFF : c;
                                }
                            } else if ("buClr".equals(lvlColorCtx)) {
                                PptDoc.Para buPara = inTc && curCellPara != null ? curCellPara
                                        : (sp != null ? sp.curPara : null);
                                if (buPara != null && c != 0) buPara.buClr = c;
                            } else if (inBg && tgt.bg != null) {
                                if (bgGradN < 0) {
                                    /* bgRef idx marker: solid approximation;
                                       1003 gradient refinement happens at </bg> */
                                    tgt.bg.type = PptDoc.Fill.SOLID;
                                    tgt.bg.color = c;
                                    bgGradN = 0;
                                } else if (tgt.bg.type == PptDoc.Fill.GRADIENT) {
                                    if (bgGradN <= 1) tgt.bg.color = c;
                                    else tgt.bg.color2 = c;
                                } else {
                                    tgt.bg.type = PptDoc.Fill.SOLID;
                                    tgt.bg.color = c;
                                }
                            } else if (inLn) {
                                PptDoc.Line l = sp != null ? sp.line : (inPic && pic != null ? pic.line : null);
                                if (l != null) l.color = c == 0 ? 0xFF404040 : c;
                                if (sp != null && sp.styleLnClr == 0 && !sp.lnExplicit) sp.styleLnClr = l != null ? l.color : 0;
                            } else if (colorCtx == CTX_TEXT) {
                                PptDoc.Run run = (inTc && curCellRun != null) ? curCellRun : (sp != null ? sp.curRun : null);
                                if (run != null) { run.color = c == 0 ? 0xFF000000 : c; run.hasColor = true; }
                                else if (inPPrDefRPr) {
                                    /* v1.24: color inside a:pPr/a:defRPr —
                                       paragraph-level default text color */
                                    PptDoc.Para dpara = inTc && curCellPara != null ? curCellPara
                                            : (sp != null ? sp.curPara : null);
                                    if (dpara != null && c != 0) dpara.defColor = c;
                                }
                                else if (sp != null && !sp.lnExplicit) {
                                    /* fontRef color inside p:style */
                                    if (sp.styleFontClr == 0) sp.styleFontClr = c;
                                }
                            } else if (colorCtx == CTX_GRAD1 && sp != null) {
                                sp.fill.type = PptDoc.Fill.GRADIENT;
                                sp.fill.color = c;
                                sp.fillExplicit = true;
                            } else if (colorCtx == CTX_GRAD2 && sp != null) {
                                sp.fill.type = PptDoc.Fill.GRADIENT;
                                sp.fill.color2 = c;
                                sp.fillExplicit = true;
                            } else if (colorCtx == CTX_GRAD1 && inPic && pic != null) {
                                pic.fill.type = PptDoc.Fill.GRADIENT;
                                pic.fill.color = c;
                            } else if (colorCtx == CTX_GRAD2 && inPic && pic != null) {
                                pic.fill.type = PptDoc.Fill.GRADIENT;
                                pic.fill.color2 = c;
                            } else if (colorCtx == CTX_LINE && sp != null) {
                                /* v1.27 FIX: lnRef color from p:style — the outline
                                   color of theme-styled shapes (usually accent1).
                                   Previously dropped: every themed autoshape got
                                   the default gray outline instead. */
                                if (sp.styleLnClr == 0) sp.styleLnClr = c == 0 ? 0xFF404040 : c;
                            } else if (colorCtx == CTX_STYLEFILL && sp != null) {
                                /* v1.27 FIX: fillRef color — theme style color only */
                                if (sp.styleFillClr == 0) sp.styleFillClr = c == 0 ? 0xFF4F81BD : c;
                            } else if (colorCtx == CTX_FILL) {
                                if (sp != null) {
                                    if (!sp.fillExplicit) {
                                        sp.fill.type = PptDoc.Fill.SOLID;
                                        sp.fill.color = c;
                                        sp.fillExplicit = true;
                                        if (sp.styleFillClr == 0) sp.styleFillClr = c;
                                    }
                                } else if (inPic && pic != null && pic.fill.type == PptDoc.Fill.NONE) {
                                    pic.fill.type = PptDoc.Fill.SOLID;
                                    pic.fill.color = c;
                                }
                            }
                        }
                    }
                    if ("transition".equals(name)) {
                        inTransition = false;
                        if (isSlide && tgt.transition == null) {
                            tgt.transition = transChild != null ? transChild : "cut";
                        }
                    } else
                    if ("grpSp".equals(name)) {
                        if (!groups.isEmpty()) groups.pop();
                    } else if ("sp".equals(name) || "cxnSp".equals(name)) {
                        if (sp != null) {
                            commitSp(tgt, sp, groups, layout, master, slideW, slideH,
                                    scheme, txStyles, mode, rels, parts, base, slideIdx,
                                    fontMajor, fontMinor);
                        }
                        sp = null;
                        collectingPh = null;
                    } else if ("pic".equals(name)) {
                        if (pendingVideoRid != null && rels != null && pic != null) {
                            /* v1.22: embedded video/audio — media bytes ride
                               with the shape; blip embed is the poster frame */
                            String rawTarget = rels.get(pendingVideoRid);
                            /* v1.31 FIX: an EXTERNAL media rel (TargetMode="External",
                               http/https URL — linked videos) must NEVER go through
                               resolve(): resolve() folds an absolute URL into the
                               part path ("ppt/slides/https:/host/x.mp4") and that
                               garbage was stored as videoUrl, so the re-saved rel
                               pointed at a nonsense relative target and the video
                               link died on every round-trip. */
                            if (rawTarget != null && looksExternal(rawTarget)) {
                                PptDoc.Video vid = new PptDoc.Video();
                                vid.x = pic.x; vid.y = pic.y;
                                vid.w = pic.w > 0 ? pic.w : 3048000;
                                vid.h = pic.h > 0 ? pic.h : 2286000;
                                vid.rot = pic.rot; vid.flipH = pic.flipH; vid.flipV = pic.flipV;
                                vid.videoUrl = rawTarget;
                                boolean linkedAudio = !sawVideoFile;
                                vid.mimeType = linkedAudio ? "audio/mp4" : "video/mp4";
                                String nm = picName != null ? picName : rawTarget;
                                int slash = nm.lastIndexOf('/');
                                vid.videoName = slash >= 0 ? nm.substring(slash + 1) : nm;
                                int dot = vid.videoName.lastIndexOf('.');
                                String ext = dot >= 0 && vid.videoName.length() - dot - 1 <= 5
                                        ? vid.videoName.substring(dot + 1).toLowerCase() : "mp4";
                                vid.videoFormat = ext;
                                if (pendingBlip != null) {
                                    byte[] img = part(parts, resolve(base, rels.get(pendingBlip)));
                                    if (img != null) vid.posterData = img;
                                }
                                mapGroup(vid, groups);
                                if (isSlide && vid.w > 0 && vid.h > 0) tgt.shapes.add(vid);
                            } else {
                            String mediaPath = rawTarget == null ? null : resolve(base, rawTarget);
                            byte[] media = mediaPath == null ? null : part(parts, mediaPath);
                            if (media != null || pic.w > 0) {
                                PptDoc.Video vid = new PptDoc.Video();
                                vid.x = pic.x; vid.y = pic.y;
                                vid.w = pic.w > 0 ? pic.w : 3048000;
                                vid.h = pic.h > 0 ? pic.h : 2286000;
                                vid.rot = pic.rot; vid.flipH = pic.flipH; vid.flipV = pic.flipV;
                                if (mediaPath != null) {
                                    /* v1.27 FIX: take the format from the media
                                       part's OWN extension — the old fixed list
                                       (mp4/m4v/webm/...) relabelled mpg/ogg/
                                       aac/etc. as mp4, and the saved package
                                       then had the wrong content type */
                                    String low = mediaPath.toLowerCase();
                                    String ext;
                                    int dot = low.lastIndexOf('.');
                                    int q = low.indexOf('?', dot + 1);
                                    if (dot >= 0) {
                                        ext = q > dot ? low.substring(dot + 1, q) : low.substring(dot + 1);
                                        ext = ext.replaceAll("[^a-z0-9]", "");
                                        if (ext.length() == 0 || ext.length() > 5) ext = "mp4";
                                    } else {
                                        ext = "mp4";
                                    }
                                    vid.videoFormat = ext;
                                    vid.mimeType = sawVideoFile
                                            ? ("audio".equals(ext) || ext.equals("mp3") || ext.equals("m4a") || ext.equals("wav")
                                               ? "audio/" + ext : "video/" + ("avi".equals(ext) ? "x-msvideo" : ext))
                                            : "audio/" + ext;
                                    int slash = mediaPath.lastIndexOf('/');
                                    /* v1.31 FIX: prefer the author's cNvPr name over
                                       the media part name (round-trips through the
                                       writer instead of drifting to vidN.ext) */
                                    vid.videoName = picName != null && picName.length() > 0
                                            ? picName
                                            : (slash >= 0 ? mediaPath.substring(slash + 1) : mediaPath);
                                    vid.videoData = media;
                                    /* v1.30 FIX: LINKED media — the relationship
                                       is External (http/https/...) and has no
                                       bytes in the package. Keep the URL so the
                                       writer can re-emit the external
                                       relationship; without it every save
                                       degraded the video to a plain picture. */
                                    if (media == null && mediaPath.length() > 0
                                            && (mediaPath.indexOf(':') > 0 || mediaPath.startsWith("//"))) {
                                        vid.videoUrl = mediaPath;
                                    }
                                }
                                if (pendingBlip != null) {
                                    byte[] img = part(parts, resolve(base, rels.get(pendingBlip)));
                                    if (img != null) vid.posterData = img;
                                }
                                mapGroup(vid, groups);
                                if (isSlide && vid.w > 0 && vid.h > 0) tgt.shapes.add(vid);
                            }
                            }
                        } else if (pic != null && (pendingBlip != null || pic.w > 0)) {
                            /* v1.28 FIX: تصویر پیوندی (بدون بایت درون فایل)
                               با imgData=null اضافه می‌شود تا جای‌نگهدارش
                               رندر شود، به‌جای ناپدید شدن کامل؛ عکسِ بی‌blipِ
                               ذخیره‌شدهٔ خود اپ هم رفت‌وبرگشت می‌کند */
                            pic.name = picName;   /* v1.31: keep the author's shape name */
                            if (pendingBlip != null && rels != null) {
                                String rawTarget = rels.get(pendingBlip);
                                /* v1.35 FIX (تصاویر حذف‌شونده): a linked image
                                   whose relationship is an EXTERNAL url kept
                                   NO url in the model — the writer then emitted
                                   a bare <a:blip/> and the picture was deleted
                                   from the file on every save. Keep the URL.
                                   v1.36: ANY scheme-bearing or UNC target counts
                                   as external (file:, mhtml:, //server/...),
                                   not just the browsable list. */
                                if (rawTarget != null && looksExternal(rawTarget)) {
                                    pic.imgUrl = rawTarget;
                                }
                                String targetPath = resolve(base, rawTarget);
                                byte[] img = targetPath == null ? null : part(parts, targetPath);
                                if (img != null) {
                                    pic.imgData = img;
                                    pic.imgFormat = sniffImgFormat(img);
                                }
                            }
                            pic.cropL = crop[0]; pic.cropT = crop[1];
                            pic.cropR = crop[2]; pic.cropB = crop[3];
                            if (pic.w <= 0 || pic.h <= 0) {
                                pic.w = 3048000;
                                pic.h = 2286000;
                            }
                            mapGroup(pic, groups);
                            if (isSlide) {
                                if (pic.w > 0 && pic.h > 0) tgt.shapes.add(pic);
                            } else {
                                tgt.shapes.add(pic);
                            }
                        }
                        inPic = false;
                        pic = null;
                        picName = null;
                        pendingBlip = null;
                        pendingVideoRid = null;
                        sawVideoFile = false;
                        frameUri = null;
                    } else if ("tbl".equals(name)) {
                        inTable = false;
                        if (table != null) {
                            for (PptDoc.TableRow r : table.rows) {
                                for (PptDoc.TableCell c : r.cells) resolveSpcPct(c.paras);
                            }
                            mapGroup(table, groups);
                            if ((table.w > 0 && table.h > 0) && !table.rows.isEmpty()) {
                                tgt.shapes.add(table);
                            }
                        }
                        table = null;
                        row = null;
                        cell = null;
                    } else if ("graphicFrame".equals(name)) {
                        inFrame = false;
                    } else if ("tc".equals(name)) {
                        inTc = false;
                        cell = null;
                        curCellPara = null;
                        curCellRun = null;
                        cellText = null;
                        inTcPr = false;
                        tcEdgeIdx = -1;
                    } else if ("tcPr".equals(name)) {
                        inTcPr = false;
                        tcEdgeIdx = -1;
                    } else if (("lnL".equals(name) || "lnT".equals(name) || "lnR".equals(name) || "lnB".equals(name)) && inTcPr) {
                        tcEdgeIdx = -1;
                    } else if ("txBody".equals(name)) {
                        inTxBody = false;
                        inLstStyle = false;
                    } else if ("t".equals(name)) {
                        inT = false;
                    } else if ("r".equals(name) || "fld".equals(name)) {
                        if (inTc && curCellRun != null && cellText != null) {
                            curCellRun.text = cellText.toString();
                        } else if (sp != null && sp.curRun != null && sp.textBuf != null) {
                            sp.curRun.text = sp.textBuf.toString();
                        }
                        curCellRun = null;
                        cellText = null;
                        if (sp != null) { sp.curRun = null; sp.textBuf = null; }
                    } else if ("p".equals(name)) {
                        /* v1.24: fold a:pPr/a:defRPr into runs that never got
                           explicit values — BEFORE the layout/master chain
                           (defRPr is closer than lstStyle) */
                        PptDoc.Para done = inTc && curCellPara != null ? curCellPara
                                : (sp != null ? sp.curPara : null);
                        if (done != null) applyDefRPr(done);
                        inPPrDefRPr = false;
                        if (sp != null) sp.curPara = null;
                        curCellPara = null;
                    } else if ("rPr".equals(name)) {
                        /* v1.23: rPr closes — text-shadow scope ends here */
                        inRPr = false;
                    } else if ("ln".equals(name)) {
                        inLn = false;
                        colorCtx = sp != null ? CTX_FILL : CTX_NONE;
                    } else if ("gradFill".equals(name)) {
                        inGrad = false;
                        gradN = 0;
                    } else if ("spPr".equals(name)) {
                        colorCtx = CTX_NONE;
                        inSpPr = false;
                    } else if ("bg".equals(name)) {
                        inBg = false;
                        /* bgRef idx 1003 = theme gradient variant */
                        if (bgRefIdx == 1003 && tgt.bg != null && tgt.bg.type == PptDoc.Fill.SOLID
                                && tgt.bg.color != 0) {
                            tgt.bg.type = PptDoc.Fill.GRADIENT;
                            tgt.bg.color2 = Theme.applyMods(tgt.bg.color,
                                    java.util.Collections.singletonList("shade:85000"));
                            tgt.bg.gradAngleDeg = 90f;
                        }
                        bgGradN = 0;
                        bgRefIdx = 0;
                        /* v1.23: photo background — when the bg carried a
                           blipFill, materialize it as a full-slide Picture:
                           slide mode parks it in bgPics (rendered beneath all
                           chrome), layout/master mode appends it to the part's
                           shape list (the existing chrome flow). The r:embed
                           resolves through THIS part's rels exactly like the
                           pictures in spTree do */
                        if (bgBlipRid != null && rels != null) {
                            String bgPath = resolve(base, rels.get(bgBlipRid));
                            byte[] bgImg = bgPath == null ? null : part(parts, bgPath);
                            if (bgImg != null) {
                                PptDoc.Picture bgPic = new PptDoc.Picture();
                                bgPic.imgData = bgImg;
                                bgPic.imgFormat = sniffImgFormat(bgImg);
                                bgPic.x = 0;
                                bgPic.y = 0;
                                bgPic.w = slideW;
                                bgPic.h = slideH;
                                bgPic.name = "background";
                                if (isSlide) tgt.bgPics.add(bgPic);
                                else tgt.shapes.add(bgPic);
                            }
                        }
                        bgBlipRid = null;
                    } else if ("clrMap".equals(name) || "txStyles".equals(name) || "sldLayoutIdLst".equals(name)) {
                        inStyleEl = false;
                    } else if ("lstStyle".equals(name)) {
                        inLstStyle = false;
                    } else if ("buClr".equals(name) || "lnSpc".equals(name) || "spcBef".equals(name)
                            || "spcAft".equals(name)) {
                        if ("buClr".equals(name)) lvlColorCtx = null;
                        else lvlColorCtx = null;
                    } else if ("ext".equals(name) && inAnimsExt) {
                        /* v1.23: end of our pk:anims p:ext */
                        inAnimsExt = false;
                    } else if ("custGeom".equals(name) && inCustGeom) {
                        /* v1.27: freeform path complete — normalize 0..1 into
                           the shape's bounding box */
                        inCustGeom = false;
                        custOp = null;
                        if (sp != null && custW > 0 && custH > 0 && custXs.size() >= 3) {
                            float[] xs = new float[custXs.size()];
                            float[] ys = new float[custYs.size()];
                            for (int i = 0; i < xs.length; i++) {
                                /* v1.31 FIX: quantize to the writer's 1e-5 grid —
                                   the writer rounds every path point to 5 decimal
                                   places, so a raw float here re-read DIFFERENTLY
                                   after one save and the polygon visibly crept
                                   (fa-ghbook.pptx freeforms drifted 0.001/cycle) */
                                xs[i] = Math.round(Math.max(0f, Math.min(1f, custXs.get(i) / custW)) * 100000f) / 100000f;
                                ys[i] = Math.round(Math.max(0f, Math.min(1f, custYs.get(i) / custH)) * 100000f) / 100000f;
                            }
                            sp.hasCustGeom = true;
                            sp.custX = xs; sp.custY = ys;
                            sp.custClosed = custClosedPath;
                        }
                    }
                } else if (ev == XmlPullParser.TEXT && inTxBody) {
                    /* v1.27 FIX: only TEXT between <a:t> and </a:t> is run
                       text — pretty-printed XML (Apache POI etc.) used to leak
                       the indentation whitespace into the text as stray
                       line breaks/spacing. */
                    if (inT) {
                        String t = p.getText();
                        if (t != null && t.length() > 0) {
                            if (inTc && cellText != null) cellText.append(t);
                            else if (sp != null && sp.textBuf != null) sp.textBuf.append(t);
                        }
                    }
                }
                ev = p.next();
            }
        } catch (Throwable ignored) {
        }
        return tgt;
    }

    private static PptDoc.Fill fillTarget(SpBuilder sp, PptDoc.Picture pic, boolean inBg, ParsedTarget tgt) {
        if (inBg) return tgt.bg;
        if (sp != null) return sp.fill;
        if (pic != null) return pic.fill;
        return null;
    }

    /* commits one parsed <p:sp>: style resolution + inheritance + output */
    private static void commitSp(ParsedTarget tgt, SpBuilder sp, Deque<int[]> groups,
                                 PartInfo layout, PartInfo master, int slideW, int slideH,
                                 Scheme scheme, Lvl[][] txStyles, int mode,
                                 Map<String, String> rels, Map<String, byte[]> parts,
                                 String base, int slideIdx,
                                 String fontMajor, String fontMinor) {
        boolean isSlide = mode == M_SLIDE;
        if (!isSlide) {
            /* layout/master mode:
               - ph shapes -> collected into tgt.phs (carriers, not chrome)
               - master footer-family ph shapes (dt/ftr/sldNum) -> chrome,
                 styled through the master's own txStyles chain
               - everything else -> chrome */
            boolean masterFooter = sp.hasPh && mode == M_MASTER && isFooterFamily(sp.phType);
            if (sp.hasPh && !masterFooter) return;
            PptDoc.Shape shape = sp.build();
            applyStyleRefs(shape, sp);
            if (masterFooter) {
                applyMasterPhChain(shape, sp, tgt, txStyles, fontMajor, fontMinor);
            }
            mapGroup(shape, groups);
            if (masterFooter || (shape.w > 0 && shape.h > 0 && keepShape(shape))) {
                /* footer chrome survives even with empty cached field text */
                tgt.shapes.add(shape);
            }
            return;
        }

        PptDoc.Shape shape = sp.build();
        applyStyleRefs(shape, sp);
        shape = resolvePh(shape, sp, layout, master, slideW, slideH, scheme);
        mapGroup(shape, groups);

        /* v1.27 FIX: normAutofit fontScale applies to EVERY text-bearing
           shape, not just placeholders — text boxes with "shrink text on
           overflow" also carry fontScale in their bodyPr. */
        if (sp.body.fontScale != 1f) {
            List<PptDoc.Para> paras0 = parasOfShape(shape);
            if (paras0 != null) {
                for (PptDoc.Para para : paras0) {
                    for (PptDoc.Run r : para.runs) {
                        r.sizePt = Math.max(4f, r.sizePt * sp.body.fontScale);
                    }
                }
            }
        }
        /* v1.27 FIX: slide-number fields resolve in ANY shape (a plain
           text box with <a:fld type="slidenum"> used to keep its stale
           cached text); lnSpcReduction now also reaches paragraphs. */
        List<PptDoc.Para> paras = parasOfShape(shape);
        if (paras != null) {
            for (PptDoc.Para para : paras) {
                if (sp.body.lnSpcReduction > 0f && para.lnSpcPct > 0f) {
                    para.lnSpcPct = Math.max(0.5f, para.lnSpcPct * (1f - sp.body.lnSpcReduction));
                }
                for (PptDoc.Run r : para.runs) {
                    if (r.fld == 1) r.text = String.valueOf(slideIdx);
                }
            }
        }
        /* v1.29 FIX: fontScale/lnSpcReduction are now fully BAKED into the
           run sizes / line spacing above — the body fields must be reset (on
           BOTH the built shape and the transient builder) so the writer does
           not re-emit <a:normAutofit fontScale=.../> on top of the
           already-scaled sizes (PowerPoint scaled the text AGAIN on every
           open->save cycle -> progressive shrink corruption). */
        PptDoc.BodyPr bakedBody = bodyOfShape(shape);
        if (bakedBody != null) { bakedBody.fontScale = 1f; bakedBody.lnSpcReduction = 0f; }
        sp.body.fontScale = 1f;
        sp.body.lnSpcReduction = 0f;

        if (sp.hasPh) {
            applyTextStyleChain(shape, sp, layout, master, txStyles, slideW, slideH,
                    fontMajor, fontMinor);
        }

        /* v1.27: spcPct → points, using the resolved first-run size
           (all styling has settled by now) */
        resolveSpcPct(parasOfShape(shape));

        /* v1.27 FIX: zero-width/height connectors are valid geometry */
        boolean geomOk = (shape.w > 0 && shape.h > 0) || shape instanceof PptDoc.Connector;
        if (geomOk && keepShape(shape)) {
            tgt.shapes.add(shape);
        }
    }

    private static boolean isFooterFamily(String phType) {
        return "dt".equals(phType) || "ftr".equals(phType) || "sldNum".equals(phType) || "hdr".equals(phType);
    }

    /* style the master's own footer placeholders (slide number size/color)
       and restore their geometry from the collected PhInfo */
    private static void applyMasterPhChain(PptDoc.Shape shape, SpBuilder sp, ParsedTarget tgt,
                                           Lvl[][] txStyles, String fontMajor, String fontMinor) {
        PartInfo self = new PartInfo();
        self.phs.addAll(tgt.phs);
        PhInfo own = findPh(self, null, sp.phType, sp.phIdx);
        if (own != null && own.hasGeom) {
            shape.x = own.x; shape.y = own.y; shape.w = own.w; shape.h = own.h;
        }
        applyTextStyleChain(shape, sp, null, self, txStyles, 0, 0, fontMajor, fontMinor);
    }

    /* p:style fallback: fillRef / lnRef / fontRef when spPr is silent */
    private static void applyStyleRefs(PptDoc.Shape shape, SpBuilder sp) {
        if (sp.styleFillIdx > 0 && !sp.fillExplicit) {
            int ph = sp.styleFillClr != 0 ? sp.styleFillClr : 0xFF4F81BD;
            if (sp.styleFillIdx == 1) {
                shape.fill.type = PptDoc.Fill.SOLID;
                shape.fill.color = ph;
            } else if (sp.styleFillIdx == 2) {
                shape.fill.type = PptDoc.Fill.GRADIENT;
                shape.fill.color = ph;
                shape.fill.color2 = Theme.applyMods(ph, java.util.Collections.singletonList("tint:65000"));
                shape.fill.gradAngleDeg = 90f;
            } else {
                shape.fill.type = PptDoc.Fill.SOLID;
                shape.fill.color = Theme.applyMods(ph, java.util.Collections.singletonList("shade:80000"));
            }
        }
        if (sp.styleLnIdx == 0 && !sp.lnExplicit) {
            shape.line.enabled = false;
        } else if (sp.styleLnIdx > 0 && !sp.lnExplicit) {
            shape.line.enabled = true;
            if (sp.styleLnClr != 0) shape.line.color = sp.styleLnClr;
            float w = sp.styleLnIdx == 1 ? 6350f : sp.styleLnIdx == 2 ? 12700f : 19050f;
            shape.line.widthPt = w / 12700f;
        }
        if (sp.styleFontClr != 0 && !sp.hasPh) {
            List<PptDoc.Para> paras = parasOfShape(shape);
            if (paras != null) {
                for (PptDoc.Para para : paras) {
                    for (PptDoc.Run r : para.runs) {
                        if (!r.hasColor) r.color = sp.styleFontClr;
                    }
                }
            }
        }
    }

    private static List<PptDoc.Para> parasOfShape(PptDoc.Shape shape) {
        if (shape instanceof PptDoc.TextShape) return ((PptDoc.TextShape) shape).paras;
        if (shape instanceof PptDoc.AutoShape) return ((PptDoc.AutoShape) shape).paras;
        return null;
    }

    /* v1.27: spcBef/spcAft given as a PERCENT of the font size (a:spcPct
       val="50000" = 50%) become points here, after every style chain has
       resolved the run sizes. PowerPoint's own conversion is
       pct × (resolved run size); the old code used a fixed 12pt-per-100%
       which made a 40pt title with 50% spacing render 6pt instead of 20pt. */
    private static void resolveSpcPct(List<PptDoc.Para> paras) {
        if (paras == null) return;
        for (PptDoc.Para para : paras) {
            float ref = 18f;
            if (!para.runs.isEmpty()) ref = para.runs.get(0).sizePt;
            if (para.spcBefPct >= 0) para.spcBefPt = Math.max(0f, para.spcBefPct * ref);
            if (para.spcAftPct >= 0) para.spcAftPt = Math.max(0f, para.spcAftPct * ref);
        }
    }

    /* decide whether a parsed shape is worth keeping */
    private static boolean keepShape(PptDoc.Shape s) {
        if (s instanceof PptDoc.TextShape) {
            PptDoc.TextShape t = (PptDoc.TextShape) s;
            if (t.paras.isEmpty()) return false;
            if (isBlank(t.plainText()) && t.fill.isEmpty() && !t.line.enabled) return false;
            return true;
        }
        return true;
    }

    private static boolean isBlank(String s) {
        if (s == null) return true;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isWhitespace(s.charAt(i)) && s.charAt(i) != '\u200C') return false;
        }
        return true;
    }

    /* placeholder geometry + bodyPr resolution */
    private static PptDoc.Shape resolvePh(PptDoc.Shape shape, SpBuilder sp, PartInfo layout, PartInfo master,
                                          int slideW, int slideH, Scheme scheme) {
        if (!sp.hasPh || sp.hasXfrm) {
            /* v1.27 FIX: zero-width/zero-height shapes are VALID geometry
               (perfectly vertical/horizontal connectors are written with
               cx=0 or cy=0). Only inflate a shape that has NO geometry at
               all, and never a connector (which would turn a thin line
               into a huge diagonal). */
            boolean noGeomAtAll = !sp.hasXfrm && shape.w <= 0 && shape.h <= 0;
            if (noGeomAtAll && !(shape instanceof PptDoc.Connector)) {
                shape.w = Math.max(shape.w, slideW / 3);
                shape.h = Math.max(shape.h, slideH / 5);
            }
            return shape;
        }
        PhInfo info = findPh(layout, master, sp.phType, sp.phIdx);
        PhInfo info2 = findPh(master, null, sp.phType, sp.phIdx);
        if (info != null && info.hasGeom) {
            shape.x = info.x; shape.y = info.y; shape.w = info.w; shape.h = info.h;
        } else {
            if ("title".equals(sp.phType) || "ctrTitle".equals(sp.phType)) {
                shape.x = slideW * 8 / 100; shape.y = slideH * 6 / 100;
                shape.w = slideW * 84 / 100; shape.h = slideH * 14 / 100;
            } else if ("dt".equals(sp.phType) || "ftr".equals(sp.phType) || "sldNum".equals(sp.phType)) {
                shape.x = slideW * 8 / 100; shape.y = slideH * 90 / 100;
                shape.w = slideW * 30 / 100; shape.h = slideH * 6 / 100;
            } else {
                shape.x = slideW * 8 / 100; shape.y = slideH * 24 / 100;
                shape.w = slideW * 84 / 100; shape.h = slideH * 62 / 100;
            }
        }
        /* bodyPr attribute-level inheritance: slide -> layout ph -> master ph */
        PhInfo chain1 = findPh(layout, null, sp.phType, sp.phIdx);
        PhInfo chain2 = findPh(master, null, sp.phType, sp.phIdx);
        PptDoc.BodyPr dst = bodyOfShape(shape);
        if (dst != null) {
            /* v1.29 FIX: anchor inheritance is flag-driven now. The old
               `chain1 == info2` identity test never fired (layout and
               master carriers are distinct objects), so a layout that
               EXPLICITLY set anchor="t" was overridden by the master. */
            if ((sp.bodySeen & 32) == 0) {
                if (chain1 != null && (chain1.bodySeen & 32) != 0) dst.anchor = chain1.body.anchor;
                else if (chain1 != null && chain1.body.anchor != 0) dst.anchor = chain1.body.anchor;
                else if (chain2 != null && (chain2.bodySeen & 32) != 0) dst.anchor = chain2.body.anchor;
                else if (chain2 != null) dst.anchor = chain2.body.anchor;
            }
            if ((sp.bodySeen & 16) == 0) {
                if (chain1 != null) dst.wrap = chain1.body.wrap;
                else if (chain2 != null) dst.wrap = chain2.body.wrap;
            }
            if ((sp.bodySeen & 1) == 0) {
                if (chain1 != null) dst.insetL = chain1.body.insetL;
                else if (chain2 != null) dst.insetL = chain2.body.insetL;
            }
            if ((sp.bodySeen & 2) == 0) {
                if (chain1 != null) dst.insetR = chain1.body.insetR;
                else if (chain2 != null) dst.insetR = chain2.body.insetR;
            }
            if ((sp.bodySeen & 4) == 0) {
                if (chain1 != null) dst.insetT = chain1.body.insetT;
                else if (chain2 != null) dst.insetT = chain2.body.insetT;
            }
            if ((sp.bodySeen & 8) == 0) {
                if (chain1 != null) dst.insetB = chain1.body.insetB;
                else if (chain2 != null) dst.insetB = chain2.body.insetB;
            }
            if ((sp.bodySeen & 64) == 0) {
                if (chain1 != null && chain1.body.autofit != 0) dst.autofit = chain1.body.autofit;
                else if (chain2 != null && chain2.body.autofit != 0) dst.autofit = chain2.body.autofit;
            }
            /* v1.29 FIX: numCol / vert now inherit through the ph chain
               (a vertical title in the layout renders vertical on the slide) */
            if ((sp.bodySeen & 128) == 0) {
                if (chain1 != null && (chain1.bodySeen & 128) != 0) dst.numCol = chain1.body.numCol;
                else if (chain2 != null && (chain2.bodySeen & 128) != 0) dst.numCol = chain2.body.numCol;
            }
            if ((sp.bodySeen & 256) == 0) {
                if (chain1 != null && (chain1.bodySeen & 256) != 0) dst.vert = chain1.body.vert;
                else if (chain2 != null && (chain2.bodySeen & 256) != 0) dst.vert = chain2.body.vert;
            }
        }
        return shape;
    }

    private static PptDoc.BodyPr bodyOfShape(PptDoc.Shape shape) {
        if (shape instanceof PptDoc.TextShape) return ((PptDoc.TextShape) shape).body;
        if (shape instanceof PptDoc.AutoShape) return ((PptDoc.AutoShape) shape).body;
        return null;
    }

    /* v1.24: apply a:pPr/a:defRPr values to runs lacking explicit rPr attrs.
       Called at </a:p> so the values also win over the layout/master
       lstStyle chain (which fills the remaining gaps afterwards). */
    private static void applyDefRPr(PptDoc.Para para) {
        if (para == null || para.runs.isEmpty()) return;
        if (para.defSzPt < 0 && para.defBold < 0 && para.defItalic < 0
                && para.defUnder < 0 && para.defColor == 0 && para.defFont == null) return;
        for (PptDoc.Run r : para.runs) {
            if (!r.hasSize && para.defSzPt > 0) { r.sizePt = para.defSzPt; r.hasSize = true; }
            if (!r.hasBold && para.defBold >= 0) { r.bold = para.defBold == 1; r.hasBold = true; }
            if (!r.hasItalic && para.defItalic >= 0) { r.italic = para.defItalic == 1; r.hasItalic = true; }
            if (!r.hasUnder && para.defUnder >= 0) { r.underline = para.defUnder == 1; r.hasUnder = true; }
            if (!r.hasColor && para.defColor != 0) { r.color = para.defColor; r.hasColor = true; }
            if (!r.hasFont && para.defFont != null) { r.font = para.defFont; r.hasFont = true; }
        }
    }

    /* ============== the text-style inheritance chain ==================
       run/para props -> layout ph lstStyle -> master ph lstStyle ->
       master txStyles (titleStyle / bodyStyle / otherStyle)
       ================================================================== */
    private static void applyTextStyleChain(PptDoc.Shape shape, SpBuilder sp, PartInfo layout,
                                            PartInfo master, Lvl[][] txStyles,
                                            int slideW, int slideH,
                                            String fontMajor, String fontMinor) {
        List<PptDoc.Para> paras = parasOfShape(shape);
        if (paras == null || paras.isEmpty()) return;
        boolean title = "title".equals(sp.phType) || "ctrTitle".equals(sp.phType);

        for (PptDoc.Para para : paras) {
            int lvl = Math.max(0, Math.min(8, para.lvl));
            Lvl l = chainLvl(layout, master, txStyles, sp.phType, sp.phIdx, lvl);
            if (l != null) {
                if (!para.hasAlign && l.align >= 0) para.align = l.align;
                if (!para.hasRtl && l.rtl >= 0) para.rtl = l.rtl == 1;
                if (!para.hasLnSpc) {
                    if (l.lnSpcPct >= 0) para.lnSpcPct = l.lnSpcPct;
                    if (l.lnSpcPt >= 0) para.lnSpcPt = l.lnSpcPt;
                }
                if (!para.hasSpcBef) {
                    if (l.spcBef >= 0) para.spcBefPt = l.spcBef;
                    else if (l.spcBefPct >= 0 && para.spcBefPct < 0) para.spcBefPct = l.spcBefPct;
                }
                if (!para.hasSpcAft) {
                    if (l.spcAft >= 0) para.spcAftPt = l.spcAft;
                    else if (l.spcAftPct >= 0 && para.spcAftPct < 0) para.spcAftPct = l.spcAftPct;
                }
                if (!para.hasMarL && l.marL != Integer.MIN_VALUE) para.marL = l.marL;
                if (!para.hasIndent && l.indent != Integer.MIN_VALUE) para.indent = l.indent;
                if (!para.hasBullet) {
                    if (l.buOn == 0) { para.buChar = null; para.buAutoNum = false; para.bullet = false; }
                    else if (l.buOn == 1) {
                        if (l.buAutoNum) {
                            para.buAutoNum = true; para.bullet = true;
                            /* v1.24: numbering format + start from the chain */
                            para.buType = l.buType; para.buStart = l.buStart;
                        }
                        else if (l.buChar != null) { para.buChar = mapBullet(l.buChar, l.buFont); para.bullet = true; }
                    }
                    if (l.buClr != 0) para.buClr = l.buClr;
                }
                /* v1.24: bullet size from the chain (percent or points) */
                if (para.buSzPct < 0 && l.buSzPct >= 0) para.buSzPct = l.buSzPct;
                if (para.buSizePt < 0 && l.buSzPt >= 0) para.buSizePt = l.buSzPt;
            }
            for (PptDoc.Run r : para.runs) {
                if (l != null) {
                    if (!r.hasSize && l.sizePt > 0) r.sizePt = l.sizePt;
                    if (!r.hasColor && l.color != 0) r.color = l.color;
                    if (!r.hasBold && l.bold >= 0) r.bold = l.bold == 1;
                    if (!r.hasItalic && l.italic >= 0) r.italic = l.italic == 1;
                    if (!r.hasUnder && l.under >= 0) r.underline = l.under == 1;
                    if (!r.hasFont && l.font != null) r.font = fontRef(l.font, fontMajor, fontMinor);
                    if (l.caps >= 0 && r.caps == 0) r.caps = l.caps;
                }
                if (r.font != null && r.font.startsWith("+")) r.font = fontRef(r.font, fontMajor, fontMinor);
            }
        }

        /* legacy heuristic only when the chain gave nothing */
        boolean chainHadSize = false;
        for (PptDoc.Para para : paras) {
            for (PptDoc.Run r : para.runs) {
                if (r.hasSize) { chainHadSize = true; break; }
            }
            if (chainHadSize) break;
        }
        Lvl first = paras.isEmpty() ? null
                : chainLvl(layout, master, txStyles, sp.phType, sp.phIdx, Math.max(0, Math.min(8, paras.get(0).lvl)));
        if (first != null && first.sizePt > 0) chainHadSize = true;
        if (!chainHadSize) {
            for (PptDoc.Para para : paras) {
                for (PptDoc.Run r : para.runs) {
                    if (r.sizePt <= 18f) {
                        r.sizePt = title ? 36f : 20f;
                        if (title) r.bold = true;
                    }
                }
            }
        }
        if (shape instanceof PptDoc.TextShape) {
            ((PptDoc.TextShape) shape).placeholderTitle = title;
        }
    }

    /* merge chain: txStyles <- master ph lstStyle <- layout ph lstStyle */
    private static Lvl chainLvl(PartInfo layout, PartInfo master, Lvl[][] txStyles,
                                String phType, String phIdx, int lvl) {
        Lvl out = null;
        int family = txFamily(phType);
        if (txStyles != null && txStyles[family] != null && txStyles[family][lvl] != null) {
            out = copyLvl(txStyles[family][lvl]);
        }
        Lvl m = phLvl(master, phType, phIdx, lvl);
        if (m != null) out = out == null ? m : overlayLvl(out, m);
        Lvl l = phLvl(layout, phType, phIdx, lvl);
        if (l != null) out = out == null ? l : overlayLvl(out, l);
        return out;
    }

    private static int txFamily(String phType) {
        if ("title".equals(phType) || "ctrTitle".equals(phType)) return 0;
        if ("dt".equals(phType) || "ftr".equals(phType) || "sldNum".equals(phType) || "hdr".equals(phType)) return 2;
        return 1;
    }

    private static Lvl phLvl(PartInfo part, String phType, String phIdx, int lvl) {
        if (part == null) return null;
        PhInfo pi = findPh(part, null, phType, phIdx);
        if (pi == null) return null;
        Lvl l = pi.lvls[lvl];
        return l == null ? null : copyLvl(l);
    }

    private static Lvl copyLvl(Lvl src) {
        Lvl n = new Lvl();
        n.align = src.align; n.marL = src.marL; n.indent = src.indent;
        n.lnSpcPct = src.lnSpcPct; n.lnSpcPt = src.lnSpcPt;
        n.spcBef = src.spcBef; n.spcAft = src.spcAft;
        n.buChar = src.buChar; n.buFont = src.buFont;
        n.buOn = src.buOn; n.buAutoNum = src.buAutoNum; n.buClr = src.buClr;
        n.buType = src.buType; n.buStart = src.buStart;
        n.buSzPct = src.buSzPct; n.buSzPt = src.buSzPt;
        n.sizePt = src.sizePt; n.bold = src.bold; n.italic = src.italic;
        n.under = src.under; n.color = src.color; n.font = src.font; n.caps = src.caps;
        n.rtl = src.rtl;
        return n;
    }

    /* overlay: values from `over` replace sentinel values in `base` */
    private static Lvl overlayLvl(Lvl base, Lvl over) {
        if (over.align >= 0) base.align = over.align;
        if (over.marL != Integer.MIN_VALUE) base.marL = over.marL;
        if (over.indent != Integer.MIN_VALUE) base.indent = over.indent;
        if (over.lnSpcPct >= 0) base.lnSpcPct = over.lnSpcPct;
        if (over.lnSpcPt >= 0) base.lnSpcPt = over.lnSpcPt;
        if (over.spcBef >= 0) base.spcBef = over.spcBef;
        if (over.spcAft >= 0) base.spcAft = over.spcAft;
        if (over.buOn >= 0) { base.buOn = over.buOn; base.buChar = over.buChar; base.buAutoNum = over.buAutoNum; }
        if (over.buClr != 0) base.buClr = over.buClr;
        if (over.buFont != null) base.buFont = over.buFont;
        /* v1.24 */
        if (over.buAutoNum) { base.buType = over.buType; base.buStart = over.buStart; }
        if (over.buSzPct >= 0) base.buSzPct = over.buSzPct;
        if (over.buSzPt >= 0) base.buSzPt = over.buSzPt;
        if (over.sizePt > 0) base.sizePt = over.sizePt;
        if (over.bold >= 0) base.bold = over.bold;
        if (over.italic >= 0) base.italic = over.italic;
        if (over.under >= 0) base.under = over.under;
        if (over.color != 0) base.color = over.color;
        if (over.font != null) base.font = over.font;
        if (over.caps >= 0) base.caps = over.caps;
        if (over.rtl >= 0) base.rtl = over.rtl;
        return base;
    }

    /* "+mj-lt"/"+mn-lt" -> theme major/minor latin typeface */
    private static String fontRef(String f, String fontMajor, String fontMinor) {
        if (f == null) return null;
        if (f.startsWith("+mj")) return fontMajor != null ? fontMajor : f;
        if (f.startsWith("+mn")) return fontMinor != null ? fontMinor : f;
        return f;
    }

    /* Wingdings/Symbol PUA bullet chars -> real Unicode glyphs */
    static String mapBullet(String ch, String buFont) {
        if (ch == null || ch.length() == 0) return "\u2022";
        char c = ch.charAt(0);
        if (c >= 0xE000 && c <= 0xF8FF) {
            /* private use area — Wingdings/Symbol/Webdings map */
            switch (c) {
                case 0xF0B7:
                case 0xF0B8:
                case 0xF06C: return "\u2022";      /* • */
                case 0xF0A0:
                case 0xF0A7:
                case 0xF06E:
                case 0xF0A8: return "\u25AA";      /* ▪ */
                case 0xF075:
                case 0xF076: return "\u25C6";      /* ◆ */
                case 0xF06F: return "\u25CB";      /* ○ */
                case 0xF0FC: return "\u2713";      /* ✓ */
                case 0xF0D8:
                case 0xF0DA: return "\u25B6";      /* ► */
                case 0xF0E6: return "\u2043";      /* ⁃ */
                case 0xF05E: return "\u25B2";      /* ▲ */
                case 0xF070: return "\u2716";      /* ✖ */
                default: return "\u2022";
            }
        }
        if (c == '\u2013' || c == '\u2014') return ch;
        return ch;
    }

    private static PhInfo findPh(PartInfo layout, PartInfo master, String type, String idx) {
        if (layout != null) {
            for (PhInfo pi : layout.phs) if (pi.type.equals(type) && pi.idx.equals(idx)) return pi;
            for (PhInfo pi : layout.phs) if (pi.type.equals(type)) return pi;
        }
        if (master != null) {
            for (PhInfo pi : master.phs) if (pi.type.equals(type) && pi.idx.equals(idx)) return pi;
            for (PhInfo pi : master.phs) if (pi.type.equals(type)) return pi;
        }
        return null;
    }

    private static int geomType(String prst) {
        if (prst == null) return PptDoc.Shape.TYPE_RECT;
        switch (prst) {
            case "roundRect": case "round1Rect": case "round2SameRect": case "round2DiagRect": return PptDoc.Shape.TYPE_ROUND_RECT;
            case "ellipse": case "circle": return PptDoc.Shape.TYPE_OVAL;
            case "line": case "straightConnector1": case "bentConnector2": case "bentConnector3":
            case "bentConnector4": case "bentConnector5":
            case "curvedConnector2": case "curvedConnector3": case "curvedConnector4":
            case "curvedConnector5": return PptDoc.Shape.TYPE_LINE;
            case "triangle": case "rtTriangle": return PptDoc.Shape.TYPE_TRIANGLE;
            case "rightArrow": case "leftArrow": case "upArrow": case "downArrow":
            case "leftRightArrow": case "upDownArrow":
            case "rightArrowCallout": case "leftArrowCallout": case "bentArrow":
            case "circularArrow": case "curvedRightArrow": case "curvedLeftArrow": return PptDoc.Shape.TYPE_RIGHT_ARROW;
            case "star5": case "star4": case "star6": case "star8": case "star10": case "star12":
            case "star16": case "star24": case "star32": case "seal": case "seal8": return PptDoc.Shape.TYPE_STAR;
            case "diamond": return PptDoc.Shape.TYPE_DIAMOND;
            case "pentagon": case "homePlate": case "regularPentagon": return PptDoc.Shape.TYPE_PENTAGON;
            case "hexagon": case "regularHexagon": return PptDoc.Shape.TYPE_HEXAGON;
            case "parallelogram": case "trapezoid": return PptDoc.Shape.TYPE_PARALLELOGRAM;
            case "heart": return PptDoc.Shape.TYPE_HEART;
            case "cloud": case "cloudCallout": return PptDoc.Shape.TYPE_CLOUD;
            case "lightningBolt": return PptDoc.Shape.TYPE_LIGHTNING;
            case "mathPlus": case "mathMinus": case "mathMultiply": case "mathDivide":
            case "mathEqual": case "mathNotEqual": case "plus": return PptDoc.Shape.TYPE_PLUS;
            /* v1.29 FIX: "homePlate" is PowerPoint's "Pentagon" shape — it
               belongs with TYPE_PENTAGON above; a dead duplicate case
               ("homePlate " with a trailing space) used to shadow it. */
            case "chevron": return PptDoc.Shape.TYPE_CHEVRON;
            case "block": case "can": case "cube": case "bevel": case "foldedCorner":
            case "corner": case "diag": case "frame": case "halfFrame":
            case "flowChartProcess": case "flowChartAlternateProcess":
            case "flowChartPredefinedProcess": case "flowChartInputOutput":
            case "flowChartMagneticDisk": case "flowChartMagneticDrum":
            case "flowChartInternalStorage": case "flowChartDisplay":
            case "flowChartTerminator": case "flowChartPreparation":
            case "flowChartManualOperation": case "flowChartConnector":
            case "flowChartOffpageConnector": case "flowChartDecision":
            case "flowChartDocument": case "flowChartMultidocument":
            case "flowChartExtract": case "flowChartMerge":
            case "flowChartOnlineStorage": case "flowChartDelay":
            case "flowChartStoredData": case "flowChartSort":
            case "flowChartOr": case "flowChartSummingJunction":
            case "flowChartPunchedTape": case "flowChartPunchedCard":
            case "flowChartManualInput": case "flowChartDirectAccessStorage":
            case "flowChartSequentialAccessStorage": case "flowChartAuxiliaryProcess":
            case "flowChartCollate":
            case "rect": return PptDoc.Shape.TYPE_RECT;
            case "teardrop": case "moon": case "smileyFace": case "donut":
            case "noSmoking": case "arc": case "chord": case "pie":
            case "tear": return PptDoc.Shape.TYPE_OVAL;
            default: return PptDoc.Shape.TYPE_RECT;
        }
    }

    /* map a child shape out of its group's coordinate space (flatten) */
    private static void mapGroup(PptDoc.Shape s, Deque<int[]> groups) {
        if (groups.isEmpty()) return;
        for (int[] g : groups) {
            /* v1.35 FIX (اشکال جابه‌جاشده/بریده‌شده داخل گروه): some
               converters omit <a:chOff>/<a:chExt> (or write them as 0) even
               though the children are in the PARENT coordinate space — the
               old "skip this group" left the children on their raw child
               coordinates, which shifted them and clipped them at the slide
               edge. A missing/zero chExt now means IDENTITY scale, and a
               missing chOff stays 0 (both per the OPC default rules). */
            if (g[2] <= 0 || g[3] <= 0) continue;   /* degenerate group: no mapping at all */
            int chW = g[6] > 0 ? g[6] : g[2];
            int chH = g[7] > 0 ? g[7] : g[3];
            float sx = (float) g[2] / chW;
            float sy = (float) g[3] / chH;
            s.x = Math.round(g[0] + (s.x - g[4]) * sx);
            s.y = Math.round(g[1] + (s.y - g[5]) * sy);
            s.w = Math.round(s.w * sx);
            s.h = Math.round(s.h * sy);
            /* v1.27 FIX: group-level flipH/flipV mirror the child rect about
               the group's own center and toggle the child's flip flags */
            if (g.length > 9 && g[9] != 0) {
                s.x = 2 * g[0] + g[2] - s.x - s.w;
                s.flipH = !s.flipH;
            }
            if (g.length > 10 && g[10] != 0) {
                s.y = 2 * g[1] + g[3] - s.y - s.h;
                s.flipV = !s.flipV;
            }
            if (g[8] != 0) {
                /* group rotation: rotate child center around group center */
                double a = Math.toRadians(g[8] / 60000f);
                float gcx = g[0] + g[2] / 2f, gcy = g[1] + g[3] / 2f;
                float ccx = s.x + s.w / 2f, ccy = s.y + s.h / 2f;
                float dx = ccx - gcx, dy = ccy - gcy;
                float nx = gcx + (float) (dx * Math.cos(a) - dy * Math.sin(a));
                float ny = gcy + (float) (dx * Math.sin(a) + dy * Math.cos(a));
                s.x = Math.round(nx - s.w / 2f);
                s.y = Math.round(ny - s.h / 2f);
                s.rot = (s.rot + g[8]) % 21600000;
                if (s.rot < 0) s.rot += 21600000;
            }
        }
    }

    public static String sniffImgFormat(byte[] b) {
        /* v1.26 FIX: the old sniffer labeled EVERY unknown format as "png".
           BMP/TIFF/EMF/WMF images therefore round-tripped as imgN.png parts
           (wrong content type, wrong extension) and the renderer could not
           decode them. Detect the real formats now. */
        if (b == null) return "png";
        int n = b.length;
        if (n >= 12 && (b[0] & 255) == 0x89 && (b[1] & 255) == 0x50
                && (b[2] & 255) == 0x4E && (b[3] & 255) == 0x47) return "png";
        if (n >= 3 && (b[0] & 255) == 0xFF && (b[1] & 255) == 0xD8) return "jpg";
        if (n >= 12) {
            String riff = new String(b, 0, 4, java.nio.charset.StandardCharsets.US_ASCII);
            if ("RIFF".equals(riff)) {
                /* RIFF is a container: WEBP only when bytes 8..11 == "WEBP" */
                if (n >= 12) {
                    String fmt = new String(b, 8, 4, java.nio.charset.StandardCharsets.US_ASCII);
                    if ("WEBP".equals(fmt)) return "webp";
                }
                return "webp"; /* best guess for media parts */
            }
        }
        if (n >= 5) {
            String gif = new String(b, 0, 4, java.nio.charset.StandardCharsets.US_ASCII);
            if ("GIF8".equals(gif)) return "gif";
        }
        if (n >= 2 && (b[0] & 255) == 0x42 && (b[1] & 255) == 0x4D) return "bmp";
        /* v1.28: HEIC/HEIF (عکس‌های گوشی‌های مدرن) — فایل‌های pptx آن را
           ندارند؛ درج باید آن را PNG کند، و sniff باید صادقانه بگوید چیست */
        if (n >= 12) {
            if (b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p') {
                String brand = new String(b, 8, 4, java.nio.charset.StandardCharsets.US_ASCII);
                if (brand.startsWith("hei") || "mif1".equals(brand) || "msf1".equals(brand)
                        || "hevx".equals(brand)) return "heic";
            }
        }
        if (n >= 4) {
            /* TIFF: II*\0 (LE 49 49 2A 00) or MM\0* (BE 4D 4D 00 2A) */
            if ((b[0] & 255) == 0x49 && (b[1] & 255) == 0x49 && (b[2] & 255) == 0x2A && (b[3] & 255) == 0x00) return "tiff";
            if ((b[0] & 255) == 0x4D && (b[1] & 255) == 0x4D && (b[2] & 255) == 0x00 && (b[3] & 255) == 0x2A) return "tiff";
            /* EMF: EMR_HEADER (type 1, size >= 88) — magic 01 00 00 00 */
            if ((b[0] & 255) == 0x01 && (b[2] & 255) == 0x00 && (b[3] & 255) == 0x00 && n >= 88) return "emf";
            /* WMF: classic D7 CD header, or placeable 01 00 09 00 */
            if ((b[0] & 255) == 0xD7 && (b[1] & 255) == 0xCD) return "wmf";
            if (n >= 4 && (b[0] & 255) == 0x01 && (b[1] & 255) == 0x00
                    && (b[2] & 255) == 0x09 && (b[3] & 255) == 0x00) return "wmf";
            /* v1.36: bare (non-placeable) WMF — header word 1/2 (memory/disk)
               + headerSize 9; these used to fall through to "png" and the
               re-saved part lied about its format (repair prompt, lost image) */
            if (n >= 6 && ((b[0] & 255) == 0x01 || (b[0] & 255) == 0x02) && (b[1] & 255) == 0x00
                    && (b[2] & 255) == 0x09 && (b[3] & 255) == 0x00
                    && (b[4] | (b[5] << 8)) > 0) return "wmf";
        }
        return "png";
    }

    private static void sortNatural(List<String> list) {
        /* anonymous class instead of a lambda — ecj cross-compiling against
           android.jar (no java.lang.invoke.LambdaMetafactory) cannot compile
           lambdas at source 8 */
        java.util.Collections.sort(list, new java.util.Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return extractNum(a) - extractNum(b);
            }
        });
    }

    private static int extractNum(String s) {
        try {
            return Integer.parseInt(s.replaceAll("\\D+", ""));
        } catch (Exception e) {
            return 0;
        }
    }

    /* ================================================== v1.22: chart parsing ==
       parseChartShape reads a real ppt/charts/chartN.xml part into the
       PptDoc.Chart model: plot type (bar/col/stacked/line/pie/doughnut/
       area/scatter — combo charts keep the FIRST plot type but merge all
       series), series names/colors/values, categories, per-point colors
       (pie dPt fills), title/axis titles, legend, data labels, gridlines.
       ======================================================================== */
    static PptDoc.Chart parseChartShape(byte[] data, Scheme scheme) {
        PptDoc.Chart ch = new PptDoc.Chart();
        ch.legend = false;             /* set true when a c:legend element exists */
        /* v1.24: per-parse label list — a previous chart's leftovers can
           never leak into this one (was a shared static) */
        catLabelsTmp.clear();
        try {
            XmlPullParser p = parser(data);
            int ev = p.getEventType();
            PptDoc.ChartSeries curSer = null;
            List<String> mods = new ArrayList<>();
            /* v1.30 FIX: deferred color resolution (see main parser) */
            String pendClrName = null;
            int pendClrBase = 0;
            String pendClrSlot = null;
            final List<String> pendClrMods = new ArrayList<>();
            /* which cached list we are currently filling */
            String cacheCtx = null;         // "serTx"|"cat"|"val"|"xVal"|"yVal"|null
            boolean inVal = false;          // inside a <c:v>
            StringBuilder valBuf = new StringBuilder();
            int ptIdx = -1;
            List<long[]> catPts = new ArrayList<>();   // (idx, value) as parsed
            List<double[]> valPts = new ArrayList<>();
            List<double[]> xPts = new ArrayList<>();
            List<double[]> yPts = new ArrayList<>();
            boolean haveCat = false, haveVal = false, haveX = false, haveY = false;
            /* plot state */
            boolean inChart = false, inPlot = false, inSer = false;
            boolean inChartTitle = false, inCatAx = false, inValAx = false, inAxTitle = false;
            boolean autoTitleDeleted = false;
            String axTitleCtx = null;       // "cat"|"val" while reading axis title
            StringBuilder titleBuf = new StringBuilder();
            StringBuilder axTitleBuf = new StringBuilder();
            StringBuilder serTxBuf = new StringBuilder();
            boolean serTxSeen = false;
            String plotKind = null;         // first plot wins
            String barDir = "col", grouping = "clustered";
            /* v1.27: markers state — ON for <c:marker val="1"/> or a marker
               container, OFF for val="0" or <c:symbol val="none"/> */
            boolean markersOn = false;
            boolean gridSeen = false;
            int curDptIdx = -1;             // per-point color target
            boolean inDpt = false;
            List<Integer> curPtColors = null;
            int serCount = 0;

            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    String n = local(p.getName());
                    if ("chart".equals(n)) {
                        inChart = true;
                    } else if ("autoTitleDeleted".equals(n)) {
                        if ("1".equals(attr(p, "val"))) autoTitleDeleted = true;
                    } else if ("title".equals(n) && inChart && !inCatAx && !inValAx) {
                        inChartTitle = true;
                    } else if ("title".equals(n) && (inCatAx || inValAx)) {
                        inAxTitle = true;
                        axTitleCtx = inCatAx ? "cat" : "val";
                    } else if ("catAx".equals(n)) {
                        inCatAx = true;
                    } else if ("valAx".equals(n)) {
                        inValAx = true;
                    } else if ("majorGridlines".equals(n) && inValAx) {
                        gridSeen = true;
                    } else if ("barChart".equals(n) || "bar3DChart".equals(n) || "lineChart".equals(n)
                            || "pieChart".equals(n) || "doughnutChart".equals(n)
                            || "areaChart".equals(n) || "scatterChart".equals(n)
                            || "radarChart".equals(n) || "bubbleChart".equals(n)
                            /* v1.27 FIX: 3D variants are the DEFAULT chart
                               buttons in modern PowerPoint — area3DChart etc.
                               used to fall through to clustered BAR rendering */
                            || "area3DChart".equals(n) || "line3DChart".equals(n)
                            || "pie3DChart".equals(n) || "surfaceChart".equals(n)
                            || "surface3DChart".equals(n) || "stockChart".equals(n)
                            || "ofPieChart".equals(n) || "radar3DChart".equals(n)) {
                        inPlot = true;
                        if (plotKind == null) plotKind = n;
                        catPts = new ArrayList<>(); valPts = new ArrayList<>();
                    } else if ("barDir".equals(n)) {
                        String v = attr(p, "val");
                        if (v != null) barDir = v;
                    } else if ("grouping".equals(n)) {
                        String v = attr(p, "val");
                        if (v != null) grouping = v;
                    } else if ("marker".equals(n) && inPlot) {
                        /* v1.27 FIX: <c:marker val="1"/> means markers ON,
                           val="0" OFF; a marker CONTAINER is ON unless it
                           carries <c:symbol val="none"/> (handled below) */
                        String mv = attr(p, "val");
                        if (mv == null || "1".equals(mv)) markersOn = true;
                        else if ("0".equals(mv)) markersOn = false;
                    } else if ("symbol".equals(n) && inPlot) {
                        if ("none".equals(attr(p, "val"))) markersOn = false;
                    } else if ("gapWidth".equals(n)) {
                        ch.gapWidthPct = Math.max(0, Math.min(500, parseInt(attr(p, "val"), 150)));
                    } else if ("holeSize".equals(n)) {
                        ch.holePct = Math.max(5, Math.min(90, parseInt(attr(p, "val"), 50)));
                    } else if ("legend".equals(n) && inChart) {
                        ch.legend = true;
                    } else if ("legendPos".equals(n)) {
                        String v = attr(p, "val");
                        if ("b".equals(v)) ch.legendPos = 1;
                        else if ("l".equals(v)) ch.legendPos = 2;
                        else if ("t".equals(v) || "tr".equals(v)) ch.legendPos = 3;
                        else ch.legendPos = 0;
                    } else if ("ser".equals(n) && inPlot) {
                        inSer = true;
                        curSer = new PptDoc.ChartSeries();
                        serTxBuf.setLength(0);
                        serTxSeen = false;
                        curDptIdx = -1;
                        curPtColors = null;
                        catPts = new ArrayList<>();
                        valPts = new ArrayList<>();
                        xPts = new ArrayList<>();
                        yPts = new ArrayList<>();
                        ch.series.add(curSer);
                        serCount++;
                    } else if ("dPt".equals(n) && inSer) {
                        /* the point index arrives as a CHILD <c:idx val=".."/> */
                        inDpt = true;
                        curDptIdx = -1;
                        curPtColors = curPtColors == null ? new ArrayList<Integer>() : curPtColors;
                    } else if ("idx".equals(n) && inDpt) {
                        curDptIdx = parseInt(attr(p, "val"), -1);
                    } else if ("tx".equals(n) && inSer && !inChartTitle) {
                        cacheCtx = "serTx";
                    } else if ("cat".equals(n) && inSer) {
                        cacheCtx = "cat";
                    } else if ("val".equals(n) && inSer) {
                        cacheCtx = "val";
                    } else if ("xVal".equals(n) && inSer) {
                        cacheCtx = "xVal";
                    } else if ("yVal".equals(n) && inSer) {
                        cacheCtx = "yVal";
                    } else if ("pt".equals(n) && cacheCtx != null) {
                        ptIdx = parseInt(attr(p, "idx"), 0);
                    } else if ("v".equals(n)) {
                        inVal = true;
                        valBuf.setLength(0);
                    } else if ("showVal".equals(n) && "1".equals(attr(p, "val"))) {
                        ch.dataLabels = true;
                    } else if ("spPr".equals(n) && inSer) {
                        /* color parsing happens on the srgbClr/schemeClr leaf;
                           nothing to do here — context flags below drive it */
                    } else if ("srgbClr".equals(n) || "schemeClr".equals(n)) {
                        /* v1.30 FIX: park base; resolve at the color END tag
                           so its own child modifiers are applied */
                        pendClrName = n;
                        pendClrSlot = null;
                        pendClrBase = 0;
                        pendClrMods.clear();
                        if ("schemeClr".equals(n)) pendClrSlot = attr(p, "val");
                        else pendClrBase = parseColor(attr(p, "val"));
                    } else if ("lumMod".equals(n) || "lumOff".equals(n) || "shade".equals(n)
                            || "tint".equals(n) || "alpha".equals(n) || "satMod".equals(n)) {
                        if (pendClrName != null) pendClrMods.add(n + ":" + attr(p, "val"));
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    String n = local(p.getName());
                    if (pendClrName != null && n != null && pendClrName.equals(n)) {
                        int c = "schemeClr".equals(pendClrName)
                                ? scheme.resolve(pendClrSlot, pendClrMods)
                                : Theme.applyMods(pendClrBase, pendClrMods);
                        pendClrName = null;
                        if (c != 0 && inSer && curSer != null) {
                            if (curDptIdx >= 0 && curPtColors != null) {
                                /* dPt fill → per-point color at its index */
                                while (curPtColors.size() <= curDptIdx) curPtColors.add(0);
                                curPtColors.set(curDptIdx, c);
                            } else if (curSer.color == 0) {
                                curSer.color = c;
                            }
                        }
                    }
                    if ("chart".equals(n)) {
                        inChart = false;
                    } else if ("chartSpace".equals(n)) {
                        break;
                    } else if ("title".equals(n) && (inChartTitle || inAxTitle)) {
                        if (inAxTitle) {
                            String t = axTitleBuf.toString().trim();
                            if ("cat".equals(axTitleCtx)) ch.catTitle = t;
                            else ch.valTitle = t;
                            axTitleBuf.setLength(0);
                            inAxTitle = false;
                        } else {
                            inChartTitle = false;
                        }
                    } else if ("catAx".equals(n)) {
                        inCatAx = false;
                    } else if ("valAx".equals(n)) {
                        inValAx = false;
                    } else if ("barChart".equals(n) || "lineChart".equals(n) || "pieChart".equals(n)
                            || "doughnutChart".equals(n) || "areaChart".equals(n)
                            || "scatterChart".equals(n) || "bar3DChart".equals(n)
                            || "radarChart".equals(n) || "bubbleChart".equals(n)
                            /* v1.29 FIX: the END list must mirror the START list
                               exactly (v1.27 added 8 3D/stock plots to START
                               only) — after such a plot closed, inPlot stayed
                               true for the rest of the chart part and stray
                               c:marker/c:symbol elements flipped markersOn. */
                            || "area3DChart".equals(n) || "line3DChart".equals(n)
                            || "pie3DChart".equals(n) || "surfaceChart".equals(n)
                            || "surface3DChart".equals(n) || "stockChart".equals(n)
                            || "ofPieChart".equals(n) || "radar3DChart".equals(n)) {
                        inPlot = false;
                    } else if ("ser".equals(n)) {
                        inSer = false;
                        if (curSer != null) {
                            if (serTxSeen) curSer.name = serTxBuf.toString().trim();
                            if (curPtColors != null) curSer.pointColors.addAll(curPtColors);
                            /* store the point lists by idx order */
                            if (cacheCtx != null) {
                                /* last context in this ser decides the lists below */
                            }
                        }
                        applySerData(ch, curSer, catPts, valPts, xPts, yPts,
                                haveCat, haveVal, haveX, haveY, serCount == 1);
                        haveCat = haveVal = haveX = haveY = false;
                        cacheCtx = null;
                        curSer = null;
                    } else if ("dPt".equals(n)) {
                        inDpt = false;
                        curDptIdx = -1;
                    } else if ("tx".equals(n) || "cat".equals(n) || "val".equals(n)
                            || "xVal".equals(n) || "yVal".equals(n)) {
                        if ("cat".equals(n)) haveCat = true;
                        else if ("val".equals(n)) haveVal = true;
                        else if ("xVal".equals(n)) haveX = true;
                        else if ("yVal".equals(n)) haveY = true;
                        cacheCtx = null;
                    } else if ("pt".equals(n)) {
                        ptIdx = -1;
                    } else if ("v".equals(n)) {
                        inVal = false;
                        String v = valBuf.toString().trim();
                        if (cacheCtx != null && v.length() > 0) {
                            if ("serTx".equals(cacheCtx)) {
                                serTxBuf.append(v);
                                serTxSeen = true;
                            } else {
                                double d;
                                try { d = Double.parseDouble(v); }
                                catch (Exception e) { d = Double.NaN; }
                                if ("cat".equals(cacheCtx)) {
                                    catPts.add(new long[]{ptIdx, 0});
                                    /* the label is the raw text — kept below */
                                    catLabelsTmp.add(new String[]{String.valueOf(ptIdx), v});
                                } else if ("val".equals(cacheCtx) && !Double.isNaN(d)) {
                                    valPts.add(new double[]{ptIdx, d});
                                } else if ("xVal".equals(cacheCtx) && !Double.isNaN(d)) {
                                    xPts.add(new double[]{ptIdx, d});
                                } else if ("yVal".equals(cacheCtx) && !Double.isNaN(d)) {
                                    yPts.add(new double[]{ptIdx, d});
                                }
                            }
                        }
                    }
                } else if (ev == XmlPullParser.TEXT) {
                    String t = p.getText();
                    if (t != null && t.length() > 0) {
                        if (inVal) valBuf.append(t);
                        else if (inChartTitle || inAxTitle) {
                            if (inAxTitle) axTitleBuf.append(t);
                            else titleBuf.append(t);
                        }
                    }
                }
                ev = p.next();
            }

            /* title */
            if (!autoTitleDeleted && titleBuf.length() > 0) {
                ch.title = titleBuf.toString().trim();
            }
            ch.gridlines = gridSeen;

            /* plot type mapping */
            if ("barChart".equals(plotKind) || "bar3DChart".equals(plotKind)) {
                boolean horiz = "bar".equals(barDir);
                if ("stacked".equals(grouping)) ch.chartType = horiz ? PptDoc.Chart.BARH_STACKED : PptDoc.Chart.BAR_STACKED;
                else if ("percentStacked".equals(grouping)) ch.chartType = horiz ? PptDoc.Chart.BARH_PERCENT : PptDoc.Chart.BAR_PERCENT;
                else ch.chartType = horiz ? PptDoc.Chart.BARH_CLUSTERED : PptDoc.Chart.BAR_CLUSTERED;
            } else if ("lineChart".equals(plotKind) || "line3DChart".equals(plotKind)) {
                ch.chartType = markersOn ? PptDoc.Chart.LINE_MARKER : PptDoc.Chart.LINE;
            } else if ("pieChart".equals(plotKind) || "pie3DChart".equals(plotKind)
                    || "ofPieChart".equals(plotKind)) {
                ch.chartType = PptDoc.Chart.PIE;
            } else if ("doughnutChart".equals(plotKind)) {
                ch.chartType = PptDoc.Chart.DOUGHNUT;
            } else if ("areaChart".equals(plotKind) || "area3DChart".equals(plotKind)) {
                /* v1.27 FIX: area3DChart is the DEFAULT area button in
                   modern PowerPoint — it used to render as clustered bars */
                ch.chartType = PptDoc.Chart.AREA;
            } else if ("scatterChart".equals(plotKind) || "bubbleChart".equals(plotKind)) {
                ch.chartType = PptDoc.Chart.SCATTER;
            } else if ("radarChart".equals(plotKind) || "radar3DChart".equals(plotKind)
                    || "surfaceChart".equals(plotKind) || "surface3DChart".equals(plotKind)
                    || "stockChart".equals(plotKind)) {
                /* closest visual equivalents the renderer supports */
                ch.chartType = PptDoc.Chart.LINE_MARKER;
            }
        } catch (Throwable ignored) {
        }
        if (ch.series.isEmpty()) {
            /* degenerate chart — keep it visible with one empty series so the
               user at least sees a chart placeholder, not a hole */
            ch.series.add(new PptDoc.ChartSeries());
        }
        return ch;
    }

    /* cat labels side-channel (idx + text) — filled by parseChartShape */
    private static final List<String[]> catLabelsTmp = new ArrayList<>();

    /* moves the cached point lists of one series into the model, ordered by
       point index; scatter uses xVal as categories and yVal as values */
    private static void applySerData(PptDoc.Chart ch, PptDoc.ChartSeries ser,
                                     List<long[]> catPts, List<double[]> valPts,
                                     List<double[]> xPts, List<double[]> yPts,
                                     boolean haveCat, boolean haveVal,
                                     boolean haveX, boolean haveY, boolean firstSer) {
        if (ser == null) return;
        java.util.Collections.sort(valPts, new java.util.Comparator<double[]>() {
            @Override public int compare(double[] a, double[] b) { return Long.compare((long) a[0], (long) b[0]); }
        });
        java.util.Collections.sort(xPts, new java.util.Comparator<double[]>() {
            @Override public int compare(double[] a, double[] b) { return Long.compare((long) a[0], (long) b[0]); }
        });
        java.util.Collections.sort(yPts, new java.util.Comparator<double[]>() {
            @Override public int compare(double[] a, double[] b) { return Long.compare((long) a[0], (long) b[0]); }
        });
        if (haveVal) {
            for (double[] pv : valPts) ser.values.add(pv[1]);
        } else if (haveY) {
            for (double[] pv : yPts) ser.values.add(pv[1]);
        }
        if (haveCat && firstSer) {
            java.util.Collections.sort(catLabelsTmp, new java.util.Comparator<String[]>() {
                @Override public int compare(String[] a, String[] b) {
                    return Integer.parseInt(a[0]) - Integer.parseInt(b[0]);
                }
            });
            for (String[] cl : catLabelsTmp) ch.categories.add(cl[1]);
        } else if (haveX && firstSer) {
            for (double[] xv : xPts) ch.categories.add(fmtNum(xv[1]));
        }
        catLabelsTmp.clear();
    }

    public static String fmtNum(double d) {
        if (d == Math.floor(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
            return String.valueOf((long) d);
        }
        return String.valueOf(Math.round(d * 100.0) / 100.0);
    }

    /* ================================================= v1.22: SmartArt root ==
       diagram drawing parts root <dsp:spTree> carries a grpSpPr xfrm whose
       off/ext match the slide frame and chOff/chExt define the child
       coordinate space — needed to flatten the fallback shapes. */
    private static int[] diagramRootXfrm(byte[] drawingData) {
        try {
            XmlPullParser p = parser(drawingData);
            int ev = p.getEventType();
            int[] out = new int[8];
            boolean haveOff = false, haveExt = false;
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    String n = local(p.getName());
                    if ("off".equals(n) && !haveOff) {
                        out[0] = parseInt(attr(p, "x"), 0);
                        out[1] = parseInt(attr(p, "y"), 0);
                        haveOff = true;
                    } else if ("ext".equals(n) && !haveExt) {
                        out[2] = parseInt(attr(p, "cx"), 0);
                        out[3] = parseInt(attr(p, "cy"), 0);
                        haveExt = true;
                    } else if ("chOff".equals(n)) {
                        out[4] = parseInt(attr(p, "x"), 0);
                        out[5] = parseInt(attr(p, "y"), 0);
                    } else if ("chExt".equals(n)) {
                        out[6] = parseInt(attr(p, "cx"), 0);
                        out[7] = parseInt(attr(p, "cy"), 0);
                    }
                }
                /* stop after the root spTree's xfrm closes */
                if (ev == XmlPullParser.END_TAG && "xfrm".equals(local(p.getName())) && haveExt) {
                    break;
                }
                ev = p.next();
            }
            return out;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isVisualTransition(String name) {
        if (name == null) return false;
        switch (name) {
            case "fade": case "push": case "wipe": case "cover": case "cut":
            case "dissolve": case "split": case "wheel": case "zoom": case "newsflash":
            case "checker": case "blinds": case "comb": case "randomBar": case "pull":
            case "strips": case "wedge": case "random": case "circle": case "diamond":
            case "plus": case "warp": case "vortex": case "switch": case "flip":
            case "ripple": case "honeycomb": case "glitter": case "prism": case "doors":
            case "window": case "ferris": case "gallery": case "conveyor": case "pan":
            case "reveal": case "wheelReverse": case "warpReverse":
                return true;
            default:
                return false;
        }
    }
}
