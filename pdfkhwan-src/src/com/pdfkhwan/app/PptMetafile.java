package com.pdfkhwan.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import java.util.List;

/* ============================================================================
   PptMetafile v1.26 — renders WMF/EMF picture parts (the vector formats
   Office embeds for icons, diagrams and Persian clip-art) that Android
   cannot decode at all.

   Design: walk() parses records once and emits ops through the Ops
   interface — the Android-independent half (record dispatch, GDI state,
   charset mapping, DIB decoding) is shared, so parsing is testable on the
   JVM; RenderOps maps ops onto a Canvas.

   Record layouts follow MS-WMF / MS-EMF:
     WMF params are the GDI call arguments in reverse (stack) order;
     EMF records carry [Type, Size, Bounds(16)] then type-specific data.
   Unknown records are skipped by size (formats are self-describing).
   ============================================================================ */
final class PptMetafile {

    private PptMetafile() {
    }

    /* ================================================================ types */

    static final class Pen {
        int style;        /* PS_SOLID=0 DASH=1 DOT=2 DASHDOT=3 DASHDOTDOT=4 NULL=5 */
        int width = 1;
        int color = 0xFF000000;

        boolean isNull() { return style == 5 || (color & 0xFF000000) == 0; }

        /* v1.27: DC-state snapshot */
        Pen copy() { Pen p = new Pen(); p.style = style; p.width = width; p.color = color; return p; }
    }

    static final class Brush {
        int style;        /* BS_SOLID=0, hatch=2, NULL=5(HOLLOW) */
        int color = 0xFFFFFFFF;
        int hatch;

        boolean isNull() { return style == 5; }

        /* v1.27: DC-state snapshot */
        Brush copy() { Brush b = new Brush(); b.style = style; b.color = color; b.hatch = hatch; return b; }
    }

    static final class MFont {
        int height = -12; /* logical; negative = em height */
        int weight = 400;
        boolean italic, underline, strike;
        int charset;
        String face = "";
        int escapement;

        /* v1.27: DC-state snapshot */
        MFont copy() {
            MFont f = new MFont(); f.height = height; f.weight = weight;
            f.italic = italic; f.underline = underline; f.strike = strike;
            f.charset = charset; f.face = face; f.escapement = escapement;
            return f;
        }
    }

    /** decoded BI_RGB DIB */
    static final class Dib {
        int w, h;
        int[] argb;

        Dib(int w, int h, int[] argb) {
            this.w = w;
            this.h = h;
            this.argb = argb;
        }
    }

    static final class Stats {
        int records, drawn, unknown, textChars, dibs, rasterPx;
        /* v1.35: path-bracket usage — Office/Illustrator EMF figures define
           their shapes almost exclusively via BeginPath/.../FillPath */
        int pathOps;
        int winW = -1, winH = -1;

        @Override
        public String toString() {
            return "records=" + records + " drawn=" + drawn + " unknown=" + unknown
                    + " textChars=" + textChars + " dibs=" + dibs
                    + " rasterPx=" + rasterPx + " pathOps=" + pathOps
                    + " win=" + winW + "x" + winH;
        }
    }

    /* ================================================================ entry */

    public static Bitmap render(byte[] data, String fmt, int targetPx) {
        Stats probe = analyze(data, fmt);
        if (probe.records <= 0 || probe.winW <= 0 || probe.winH <= 0) return null;
        int goal = Math.max(200, Math.min(targetPx <= 0 ? 1024 : targetPx, 3000));
        float scale = Math.min(goal / (float) probe.winW, goal / (float) probe.winH);
        int w = Math.max(1, Math.round(probe.winW * scale));
        int h = Math.max(1, Math.round(probe.winH * scale));
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(0xFFFFFFFF);
        RenderOps ro = new RenderOps(c, w / (float) probe.winW, h / (float) probe.winH);
        walk(data, fmt, ro);
        return bmp;
    }

    /** parse-only entry — JVM-testable, no graphics dependency. */
    public static Stats analyze(byte[] data, String fmt) {
        CountOps co = new CountOps();
        walk(data, fmt, co);
        co.stats.winW = co.winW;
        co.stats.winH = co.winH;
        return co.stats;
    }

    /** JVM test hook: walk with a caller-supplied sink (reflection-free). */
    static void walkTest(byte[] data, String fmt, Ops ops) {
        walk(data, fmt, ops);
    }

    static void walk(byte[] data, String fmt, Ops ops) {
        if (data == null || data.length < 12) return;
        boolean emf = "emf".equals(fmt)
                || (data.length >= 8 && (data[0] & 255) == 1 && (data[1] & 255) == 0
                && (data[2] & 255) == 0 && (data[3] & 255) == 0 && (data[4] & 255) == 0x58);
        if (emf) walkEmf(data, ops);
        else walkWmf(data, ops);
    }

    /* ================================================================= WMF */

    private static void walkWmf(byte[] d, Ops ops) {
        Rd r = new Rd(d);
        int p = 0;
        boolean placeable = d.length > 44 && (d[0] & 255) == 0xD7 && (d[1] & 255) == 0xCD
                && (d[2] & 255) == 0xC6 && (d[3] & 255) == 0x9A;
        float ptW = 0, ptH = 0;
        if (placeable) {
            /* 22-byte header: key(4) hmf(2) bbox(8)@6..14 inch(2)@14 */
            int l = r.i16(6), t = r.i16(8), rt = r.i16(10), bm = r.i16(12);
            int inch = r.i16(14);
            if (inch <= 0) inch = 1440;
            /* bbox is in twips; scale by the file's twips-per-inch */
            ptW = Math.abs(rt - l) / 20f * (1440f / inch);
            ptH = Math.abs(bm - t) / 20f * (1440f / inch);
            if (ptW > 0 && ptH > 0) ops.window(0, 0, Math.round(ptW), Math.round(ptH));
            p = 22;
        }
        if (p + 18 > d.length) return;
        p += 18;   /* WMF header: type, hdrsz, version, filesize, numobj, maxrec, numparams */

        List<Object> objects = new java.util.ArrayList<>();
        Gdi gdi = new Gdi();
        int winOrgX = 0, winOrgY = 0, winExtX = 0, winExtY = 0;

        while (p + 6 <= d.length) {
            /* v1.27 CRITICAL FIX: the leading DWORD is the record length in
               16-bit WORDS (MS-WMF §2.3.1.1), not bytes. The old code read it
               as bytes: every record after the header desynced the parser
               (advanced half the real length) and the size<6 guard ate the
               small records — ALL standard .wmf clip-art rendered as the
               «قالب تصویر قابل خواندن نیست» placeholder or garbage. */
            long sizeWords = r.u32(p) & 0xFFFFFFFFL;
            if (sizeWords < 3) break;
            /* v1.28 CRITICAL FIX: سایز رکورد بدون کنترل به int بریده می‌شد —
               0x80000000 ورد → 0 بایت → p هرگز جلو نمی‌رفت → حلقهٔ بی‌نهایت/
               ANR. حالا با ریاضیِ long و کنترل مرز؛ رکورد ناقص/خراب = توقف. */
            long sizeBytesL = sizeWords * 2L;
            if (sizeBytesL < 6 || (long) p + sizeBytesL > d.length) break;
            int sizeBytes = (int) sizeBytesL;
            int fn = r.u16(p + 4);
            int par = p + 6;
            int parEnd = (int) Math.min((long) p + sizeBytes, d.length);
            int left = parEnd - par;
            gdi.records++;
            if (ops instanceof CountOps) ((CountOps) ops).stats.records++;
            switch (fn) {
                /* ---- state ---- */
                case 0x020B:  /* SETWINDOWORG: [y x] */
                    if (left >= 4) { winOrgY = r.i16(par); winOrgX = r.i16(par + 2); }
                    break;
                case 0x020C:  /* SETWINDOWEXT: [h w] */
                    if (left >= 4) {
                        winExtY = Math.abs(r.i16(par));
                        winExtX = Math.abs(r.i16(par + 2));
                        if (winExtX > 0 && winExtY > 0) ops.window(winOrgX, winOrgY, winExtX, winExtY);
                    }
                    break;
                case 0x0201:
                    if (left >= 4) ops.bkColor(r.rgb(par));
                    break;
                case 0x0106:
                    if (left >= 2) ops.fillEvenOdd((r.u16(par) & 6) == 2);
                    break;
                case 0x0209:
                    if (left >= 4) ops.textColor(r.rgb(par));
                    break;
                case 0x001E:
                    /* v1.27: SAVE/RESTORE DC keep pen/brush/font/world state */
                    ops.save();
                    gdi.saved++;
                    break;
                case 0x0127:
                    ops.restore();
                    gdi.saved = Math.max(0, gdi.saved - 1);
                    break;
                case 0x012E:  /* SETTEXTALIGN: v1.27 honored for text anchor */
                    if (left >= 2) gdi.textAlign = r.u16(par);
                    break;
                case 0x0102:  /* v1.35 SETBKMODE: [mode] — OPAQUE text bg */
                    if (left >= 2) ops.bkMode(r.u16(par));
                    break;
                case 0x053A: {  /* v1.35 POLYPOLYLINE — same layout as
                      POLYPOLYGON but NEVER filled */
                    if (left >= 2) {
                        int n = r.u16(par);
                        if (n > 0 && n < 512 && left >= 2 + n * 2) {
                            int off = 2 + n * 2;
                            float[][][] polys = new float[n][][];
                            for (int i = 0; i < n; i++) {
                                int cnt = r.u16(par + 2 + i * 2);
                                polys[i] = wmfPts(r, par + off, left - off, cnt);
                                if (polys[i] == null) { polys = null; break; }
                                off += cnt * 4;
                            }
                            if (polys != null) ops.polyPoly(polys, false);
                        }
                    }
                    break;
                }
                case 0x0214:  /* MOVETO [y x] */
                    if (left >= 4) ops.moveTo(r.i16(par + 2), r.i16(par));
                    break;
                case 0x0213:  /* LINETO [y x] */
                    if (left >= 4) ops.lineTo(r.i16(par + 2), r.i16(par));
                    break;

                /* ---- objects ---- */
                case 0x02FA: {  /* CREATEPENINDIRECT: style, width(POINT16), color */
                    if (left >= 10) {
                        Pen pen = new Pen();
                        pen.style = r.u16(par);
                        pen.width = Math.max(0, r.i16(par + 2));
                        pen.color = r.rgb(par + 6);
                        gdi.addObject(objects, pen);
                    }
                    break;
                }
                case 0x02FC: {  /* CREATEBRUSHINDIRECT: style, color, hatch */
                    if (left >= 8) {
                        Brush br = new Brush();
                        br.style = r.u16(par);
                        br.color = r.rgb(par + 2);
                        br.hatch = r.u16(par + 6);
                        gdi.addObject(objects, br);
                    }
                    break;
                }
                case 0x02FB: {  /* CREATEFONTINDIRECT (LogFont16) */
                    if (left >= 50) {
                        MFont f = new MFont();
                        f.height = r.i16(par);
                        f.escapement = r.i16(par + 4);
                        int weight = r.i16(par + 8);
                        f.weight = weight == 0 ? 400 : weight;
                        int b0 = r.u16(par + 10);
                        f.italic = (b0 & 255) != 0;
                        f.underline = (b0 >> 8 & 255) != 0;
                        int b1 = r.u16(par + 12);
                        f.strike = (b1 & 255) != 0;
                        f.charset = (b1 >> 8 & 255);
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < 32; i++) {
                            int ch = r.u8(par + 18 + i);
                            if (ch == 0) break;
                            sb.append((char) ch);
                        }
                        f.face = sb.toString();
                        gdi.addObject(objects, f);
                        gdi.curCharset = f.charset;
                    }
                    break;
                }
                case 0x012D:
                    if (left >= 2) gdi.select(objects, r.u16(par), ops);
                    break;
                case 0x01F0:
                    if (left >= 2) {
                        int idx = r.u16(par);
                        if (idx >= 0 && idx < objects.size()) objects.set(idx, null);
                    }
                    break;

                /* ---- shapes (params in reverse arg order) ---- */
                case 0x0325: {  /* POLYLINE: count, points */
                    if (left >= 2) {
                        float[][] pts = wmfPts(r, par + 2, left - 2, r.u16(par));
                        if (pts != null) ops.poly(pts, false);
                    }
                    break;
                }
                case 0x0324: {
                    if (left >= 2) {
                        float[][] pts = wmfPts(r, par + 2, left - 2, r.u16(par));
                        if (pts != null) ops.poly(pts, true);
                    }
                    break;
                }
                case 0x0538: {  /* POLYPOLYGON */
                    if (left >= 2) {
                        int n = r.u16(par);
                        if (n > 0 && n < 512 && left >= 2 + n * 2) {
                            int off = 2 + n * 2;
                            float[][][] polys = new float[n][][];
                            for (int i = 0; i < n; i++) {
                                int cnt = r.u16(par + 2 + i * 2);
                                polys[i] = wmfPts(r, par + off, left - off, cnt);
                                if (polys[i] == null) { polys = null; break; }
                                off += cnt * 4;
                            }
                            if (polys != null) ops.polyPoly(polys, true);
                        }
                    }
                    break;
                }
                case 0x041B:  /* RECTANGLE [bottom right top left] */
                    if (left >= 8) ops.rect(r.i16(par + 6), r.i16(par + 4), r.i16(par + 2), r.i16(par), 0, 0);
                    break;
                case 0x061C: {  /* ROUNDRECT [h w bottom right top left] —
                      MS-WMF §2.3.1.2: Height, Width, Bottom, Right, Top, Left;
                      v1.28 FIX: قبلاً [b r t l h w] فرض می‌شد و هم مستطیل
                      و هم شعاع گوشه‌ها غلط خوانده می‌شد */
                    if (left >= 12) {
                        int h = r.i16(par), w = r.i16(par + 2);
                        ops.rect(r.i16(par + 10), r.i16(par + 8), r.i16(par + 6), r.i16(par + 4), w, h);
                    }
                    break;
                }
                case 0x0418:  /* ELLIPSE [bottom right top left] */
                    if (left >= 8) ops.ellipse(r.i16(par + 6), r.i16(par + 4), r.i16(par + 2), r.i16(par));
                    break;
                case 0x0817:
                case 0x081A:
                case 0x0830: {  /* ARC/PIE/CHORD: [endY endX startY startX bottom right top left] */
                    if (left >= 16) {
                        float ey = r.i16(par), ex = r.i16(par + 2);
                        float sy = r.i16(par + 4), sx = r.i16(par + 6);
                        float bm = r.i16(par + 8), rt = r.i16(par + 10);
                        float tp = r.i16(par + 12), lf = r.i16(par + 14);
                        ops.pieChord(lf, tp, rt, bm, sx, sy, ex, ey,
                                fn == 0x081A ? 1 : (fn == 0x0830 ? 2 : 0));
                    }
                    break;
                }

                /* ---- text ---- */
                case 0x0521: {  /* TEXTOUT: count, bytes(+pad), y, x */
                    if (left >= 2) {
                        int cnt = r.u16(par);
                        int pad = cnt & 1;
                        if (cnt >= 0 && left >= 2 + cnt + pad + 4) {
                            int sp = par + 2;
                            String s = gdi.toText(d, sp, cnt);
                            float y = r.i16(par + 2 + cnt + pad);
                            float x = r.i16(par + 2 + cnt + pad + 2);
                            ops.text(s, x, y, null, null, gdi.rtlFace, gdi.textAlign);
                        }
                    }
                    break;
                }
                case 0x0A32: {  /* EXTTEXTOUT: y x count options [rect] string [dx] */
                    if (left >= 6) {
                        float y = r.i16(par), x = r.i16(par + 2);
                        int cnt = r.u16(par + 4);
                        int opt = r.u16(par + 6);
                        int sp = par + 8;
                        float[] rect = null;
                        if ((opt & 6) != 0 && left >= sp - par + 8) {   /* ETO_CLIPPED(4) | ETO_OPAQUE(2) both carry the rect */
                            rect = new float[]{r.i16(sp), r.i16(sp + 2), r.i16(sp + 4), r.i16(sp + 6)};
                            sp += 8;
                        }
                        if (cnt >= 0 && left >= sp - par + cnt + (cnt & 1)) {
                            String s = gdi.toText(d, sp, cnt);
                            ops.text(s, x, y, rect, null, gdi.rtlFace, gdi.textAlign);
                        }
                    }
                    break;
                }

                /* ---- raster ---- */
                case 0x0F43: {  /* STRETCHDIB */
                    if (left >= 22) {
                        int usage = r.u16(par + 4);
                        int srcH = r.i16(par + 6), srcW = r.i16(par + 8);
                        int ySrc = r.i16(par + 10), xSrc = r.i16(par + 12);
                        int dstH = r.i16(par + 14), dstW = r.i16(par + 16);
                        int yDst = r.i16(par + 18), xDst = r.i16(par + 20);
                        int bmiOff = par + 22;
                        if (sanity(srcW, srcH) && sanity(dstW, dstH) && bmiOff + 40 <= parEnd) {
                            Dib img = decodeDib(d, bmiOff, parEnd - bmiOff);
                            if (img != null) ops.dib(xDst, yDst, dstW, dstH, xSrc, ySrc, srcW, srcH, img);
                        }
                    }
                    break;
                }
                default:
                    gdi.unknown++;
                    break;
            }
            p += sizeBytes;
        }
    }

    static boolean sanity(int w, int h) {
        return w > 0 && h > 0 && w < 30000 && h < 30000;
    }

    /** WMF point array: count given separately. MS-WMF §2.2.2.12 PointS is
        [x, y] — only the standalone MOVETO/LINETO records pack [y, x].
        v1.28 FIX: قبلاً [y, x] خوانده می‌شد و همهٔ چندضلعی‌های WMF/EMF16
        نسبت به قطر اصلی آینه می‌شدند (اشکال نامتقارن: پیکان/ستاره و…) */
    static float[][] wmfPts(Rd r, int par, int avail, int n) {
        if (n < 0 || n > 4096 || avail < n * 4) return null;
        float[][] pts = new float[n][];
        for (int i = 0; i < n; i++) {
            pts[i] = new float[]{r.i16(par + i * 4), r.i16(par + i * 4 + 2)};
        }
        return pts;
    }

    /* ================================================================= EMF */

    private static void walkEmf(byte[] d, Ops ops0) {
        Rd r = new Rd(d);
        if (d.length < 88) return;
        long fl = r.i32(24), ft = r.i32(28), frr = r.i32(32), fb = r.i32(36);
        int winW = Math.round((frr - fl) / 100f / 25.4f * 72f);   /* 0.01mm -> px@72dpi */
        int winH = Math.round((fb - ft) / 100f / 25.4f * 72f);
        if (!sanity(winW, winH)) { winW = 1000; winH = 750; }
        ops0.window(0, 0, winW, winH);

        List<Object> objects = new java.util.ArrayList<>();
        Gdi gdi = new Gdi();
        int p = (int) Math.max(8, r.u32(4));   /* skip EMR_HEADER */
        /* v1.36: EMF+ state — created on the first EMF+ comment; from then on
           the GDI half of a dual file is routed to MUTE (LibreOffice's exact
           rule: any EMF+ present → skip GDI unless a GetDC just unlocked it) */
        PlusState plus = null;
        Ops ops = ops0;

        while (p + 8 <= d.length) {
            int type = (int) r.u32(p);
            long size = r.u32(p + 4) & 0xFFFFFFFFL;
            if (size < 8 || p + size > d.length) break;
            int par = p + 8;
            int parEnd = (int) (p + size);
            gdi.records++;
            if (plus != null) ops = plus.sink();
            if (ops0 instanceof CountOps && ops == ops0) ((CountOps) ops0).stats.records++;
            switch (type) {
                case 19:
                    if (parEnd - par >= 4) ops.fillEvenOdd(r.u32(par) == 2);
                    break;
                case 24:
                    /* v1.29 FIX: EMF COLORREF is 00BBGGRR (Reserved byte 0) —
                       without forcing the alpha byte every SETTEXTCOLOR made
                       the text fully TRANSPARENT (invisible) in the Paint.
                       v1.35 FIX: the DWORD's LOW byte is RED — ORing the raw
                       value swapped red/blue (COLORREF 0x000000FF = red read
                       as 0xFF0000FF = blue). Swap the low three bytes. */
                    if (parEnd - par >= 4) ops.textColor(cref(r, par));
                    break;
                case 25:
                    if (parEnd - par >= 4) ops.bkColor(cref(r, par));
                    break;
                case 33:
                    ops.save();
                    gdi.saved++;
                    break;
                case 34:
                    ops.restore();
                    gdi.saved = Math.max(0, gdi.saved - 1);
                    break;
                case 22:  /* SETTEXTALIGN — v1.29 FIX: EMR_SETTEXTALIGN is
                    record type 22 ([MS-EMF]); the old "case 138" was dead
                    code (no such record type exists), so TA_RIGHT/TA_CENTER
                    EMF text was drawn left-anchored, shifted by its width */
                    if (parEnd - par >= 4) gdi.textAlign = (int) r.u32(par);
                    break;
                case 35:  /* SETWORLDTRANSFORM */
                    ops.world(readXform(r, par));
                    break;
                case 36: {  /* MODIFYWORLDTRANSFORM */
                    float[] m = readXform(r, par);
                    int mode = parEnd - par >= 28 ? (int) r.u32(par + 24) : 2;
                    ops.modifyWorld(m, mode);
                    break;
                }
                case 27:  /* MOVETOEX */
                    if (parEnd - par >= 8) ops.moveTo(r.i32(par), r.i32(par + 4));
                    break;
                case 54:  /* LINETO */
                    if (parEnd - par >= 8) ops.lineTo(r.i32(par), r.i32(par + 4));
                    break;
                /* v1.27: bezier records — curved outlines in EMF clip-art used
                   to be DROPPED entirely (icons lost their curved shapes) */
                case 2: {  /* POLYBEZIER: rclBounds(16) cptl(4) aptl[] */
                    if (parEnd - par >= 20) {
                        int n = (int) r.u32(par + 16);
                        /* v1.29 FIX: POLYBEZIER Count MUST be 3k+1 — without
                           the modulo check a crafted Count read one past the
                           array (AIOOBE crash, uncaught at both call sites). */
                        if (n >= 4 && n < 4096 && n % 3 == 1 && parEnd - par - 20 >= n * 8) {
                            float[][] pts = new float[n][2];
                            for (int i = 0; i < n; i++) {
                                pts[i][0] = r.i32(par + 20 + i * 8);
                                pts[i][1] = r.i32(par + 24 + i * 8);
                            }
                            ops.bezier(pts, false);
                        }
                    }
                    break;
                }
                case 5: {  /* POLYBEZIERTO: control points relative to cur pos */
                    if (parEnd - par >= 20) {
                        int n = (int) r.u32(par + 16);
                        if (n >= 3 && n < 4096 && n % 3 == 0 && parEnd - par - 20 >= n * 8) {
                            float[][] pts = new float[n][2];
                            for (int i = 0; i < n; i++) {
                                pts[i][0] = r.i32(par + 20 + i * 8);
                                pts[i][1] = r.i32(par + 24 + i * 8);
                            }
                            ops.bezier(pts, true);
                        }
                    }
                    break;
                }
                case 6: {  /* POLYLINETO: lines from the current position */
                    if (parEnd - par >= 20) {
                        int n = (int) r.u32(par + 16);
                        if (n > 0 && n < 4096 && parEnd - par - 20 >= n * 8) {
                            float[][] pts = new float[n][2];
                            for (int i = 0; i < n; i++) {
                                pts[i][0] = r.i32(par + 20 + i * 8);
                                pts[i][1] = r.i32(par + 24 + i * 8);
                            }
                            ops.polyTo(pts);
                        }
                    }
                    break;
                }

                /* ---- objects ---- */
                case 38: {  /* CREATEPEN: ih, style, width(POINTL), color */
                    /* v1.35 FIX: the spec-minimal payload is 20 bytes
                       (ih+LOGPEN) — the old >=24 guard dropped every
                       non-padded record, so EMF pens fell back to black */
                    if (parEnd - par >= 20) {
                        Pen pen = new Pen();
                        pen.style = (int) r.u32(par + 4);
                        pen.width = Math.abs(r.i32(par + 8));
                        /* v1.29 FIX: force the alpha byte — COLORREF is 00BBGGRR
                           v1.35 FIX: byte-swap the low three (see case 24) */
                        pen.color = cref(r, par + 16);
                        gdi.emfAdd(objects, pen, r.u32(par));
                    }
                    break;
                }
                case 39: {  /* CREATEBRUSHINDIRECT */
                    /* v1.35 FIX: spec-minimal payload = 16 (ih+LogBrush);
                       the old >=20 guard dropped non-padded records */
                    if (parEnd - par >= 16) {
                        Brush b = new Brush();
                        b.style = (int) r.u32(par + 4);
                        /* v1.29 FIX: force the alpha byte — COLORREF is 00BBGGRR
                           v1.35 FIX: byte-swap the low three (see case 24) */
                        b.color = cref(r, par + 8);
                        b.hatch = (int) r.u32(par + 12);
                        gdi.emfAdd(objects, b, r.u32(par));
                    }
                    break;
                }
                case 82: {  /* EXTCREATEFONTINDIRECTW (LogFontW)
                      v1.28 FIX: بدنهٔ حداقلی = ihFonts(4) + LogFont(92) = ۹۶
                      بایت؛ شرط قبلی ۱۶۰ باعث می‌شد فونت‌ها هرگز ساخته نشوند
                      (متن EMF همیشه با فونت پیش‌فرض/سایز غلط) */
                    if (parEnd - par >= 96) {
                        MFont f = new MFont();
                        f.height = r.i32(par + 4);
                        f.weight = r.i32(par + 20);
                        int gr = (int) r.u32(par + 24);
                        f.italic = (gr & 255) != 0;
                        f.underline = (gr >> 8 & 255) != 0;
                        f.strike = (gr >> 16 & 255) != 0;
                        f.charset = (gr >>> 24) & 255;
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < 32; i++) {
                            int ch = r.u16(par + 32 + i * 2);
                            if (ch == 0) break;
                            sb.append((char) ch);
                        }
                        f.face = sb.toString();
                        gdi.emfAdd(objects, f, r.u32(par));
                    }
                    break;
                }
                case 95: {  /* EXTCREATEPEN: ih@+0, elpPenStyle@+20, width@+24, color@+32 */
                    if (parEnd - par >= 40) {
                        Pen pen = new Pen();
                        /* v1.29 FIX: mask to the style nibble — elpPenStyle packs
                           PS_GEOMETRIC/endcap/join in higher bits, which never
                           matched the dash switch and rendered solid */
                        pen.style = (int) r.u32(par + 20) & 0xFF;
                        pen.width = Math.abs(r.i32(par + 24));
                        /* v1.29 FIX: force the alpha byte — COLORREF is 00BBGGRR
                           v1.35 FIX: byte-swap the low three (see case 24) */
                        pen.color = cref(r, par + 32);
                        gdi.emfAdd(objects, pen, r.u32(par));
                    }
                    break;
                }
                case 37:
                    if (parEnd - par >= 4) gdi.select(objects, (int) r.u32(par), ops);
                    break;
                case 40:
                    if (parEnd - par >= 4) {
                        int ih = (int) r.u32(par);
                        if (ih >= 0 && ih < objects.size()) objects.set(ih, null);
                    }
                    break;

                /* ---- shapes: [Bounds16] then data ---- */
                case 3: {   /* POLYGON (32-bit points) */
                    float[][] pts = emfPts(r, par, parEnd, false);
                    if (pts != null) ops.poly(pts, true);
                    break;
                }
                case 4: {
                    float[][] pts = emfPts(r, par, parEnd, false);
                    if (pts != null) ops.poly(pts, false);
                    break;
                }
                case 86: {  /* POLYGON16 */
                    float[][] pts = emfPts(r, par, parEnd, true);
                    if (pts != null) ops.poly(pts, true);
                    break;
                }
                case 87: {  /* POLYLINE16 */
                    float[][] pts = emfPts(r, par, parEnd, true);
                    if (pts != null) ops.poly(pts, false);
                    break;
                }
                case 91:
                case 90: {  /* POLYPOLYGON16 / POLYPOLYLINE16 */
                    if (parEnd - par >= 24) {
                        int n = (int) r.u32(par + 16);
                        if (n > 0 && n < 512 && parEnd - par >= 24 + n * 4) {
                            int off = 24 + n * 4;
                            float[][][] polys = new float[n][][];
                            boolean ok = true;
                            for (int i = 0; i < n; i++) {
                                /* v1.29 FIX: aPolyCounts[] starts at +24 — the
                                   Count (total points) field sits at +20 and
                                   was being read as polygon 0's point count,
                                   so multi-polygon records failed their bounds
                                   check or merged into one mega-polygon. */
                                int cnt = (int) r.u32(par + 24 + i * 4);
                                polys[i] = wmfPts(r, par + off, parEnd - par - off, cnt);
                                if (polys[i] == null) { ok = false; break; }
                                off += cnt * 4;
                            }
                            if (ok) ops.polyPoly(polys, type == 90);
                        }
                    }
                    break;
                }
                case 8: {  /* POLYPOLYGON (32-bit): NumberOfPolygons@+16,
                     Count@+20, aPolyCounts@+24, aPointL@+24+4n ([MS-EMF] §2.3.1.22) */
                    if (parEnd - par >= 24) {
                        int n = (int) r.u32(par + 16);
                        if (n > 0 && n < 512 && parEnd - par >= 24 + n * 4) {
                            int off = 24 + n * 4;
                            float[][][] polys = new float[n][][];
                            boolean ok = true;
                            for (int i = 0; i < n; i++) {
                                /* v1.29 FIX: counts shifted by one slot AND the
                                   point offset was 4 bytes short (20+n*4) —
                                   every point was misaligned garbage. */
                                int cnt = (int) r.u32(par + 24 + i * 4);
                                /* v1.28 FIX: cnt*8 با int سرریز می‌شد و چک
                                   مرز را دور می‌زد → تخصیص غول‌پیکر/OOM */
                                if (cnt < 0 || parEnd - par - off < (long) cnt * 8) { ok = false; break; }
                                float[][] one = new float[cnt][];
                                for (int k = 0; k < cnt; k++) {
                                    one[k] = new float[]{r.i32(par + off + k * 8), r.i32(par + off + k * 8 + 4)};
                                }
                                polys[i] = one;
                                off += cnt * 8;
                            }
                            if (ok) ops.polyPoly(polys, true);
                        }
                    }
                    break;
                }
                case 43: {  /* RECTANGLE: box@+16..+32 */
                    if (parEnd - par >= 32) {
                        ops.rect(r.i32(par + 16), r.i32(par + 20), r.i32(par + 24), r.i32(par + 28), 0, 0);
                    }
                    break;
                }
                case 42: {  /* ELLIPSE: box@+16..+32 */
                    if (parEnd - par >= 32) {
                        ops.ellipse(r.i32(par + 16), r.i32(par + 20), r.i32(par + 24), r.i32(par + 28));
                    }
                    break;
                }
                case 44: {  /* ROUNDRECT: box@+16..+32, w@+32, h@+36 */
                    if (parEnd - par >= 40) {
                        ops.rect(r.i32(par + 16), r.i32(par + 20), r.i32(par + 24), r.i32(par + 28),
                                r.i32(par + 32), r.i32(par + 36));
                    }
                    break;
                }
                case 45: case 46: case 47: {  /* ARC/CHORD/PIE: box@+16, start@+32, end@+40 */
                    if (parEnd - par >= 48) {
                        ops.pieChord(r.i32(par + 16), r.i32(par + 20), r.i32(par + 24), r.i32(par + 28),
                                r.i32(par + 32), r.i32(par + 36), r.i32(par + 40), r.i32(par + 44),
                                type == 47 ? 1 : (type == 46 ? 2 : 0));
                    }
                    break;
                }

                /* ---- text (83=A 84=W): offString/offDx are RECORD-relative) ---- */
                case 83:
                case 84: {
                    /* EMREXTTEXTOUTW: bounds(16) gmode(4) ex(4) ey(4) then
                       EMRTEXT: ref(8) nChars(4) offString(4) fOptions(4)
                       rcl(16) offDx(4)  — offsets per wingdi.h
                       v1.35 FIX: spec-minimal payload = 68; old >=72 guard
                       dropped single-character minimal records */
                    if (parEnd - par >= 68) {
                        int refX = r.i32(par + 28), refY = r.i32(par + 32);
                        int chars = (int) r.u32(par + 36);
                        int offString = (int) r.u32(par + 40);
                        /* v1.28 FIX: offDx در EMRTEXT+36 = par+64 است (ref8+
                           nChars4+offString4+fOptions4+rcl16)؛ +64 نه +68 —
                           قبلاً از بایت بعدی (padding/رشته) خوانده می‌شد */
                        int offDx = (int) r.u32(par + 64);
                        /* v1.29 FIX: long arithmetic — offString up to
                           0x7FFFFFFF wrapped int addition NEGATIVE, slipping
                           past the <= d.length check straight into an
                           AIOOBE (uncaught at both call sites). */
                        long so = (long) p + offString;
                        if (chars > 0 && chars < 4096 && offString > 0
                                && so + (long) chars * (type == 84 ? 2 : 1) <= d.length) {
                            String s;
                            if (type == 84) {
                                StringBuilder sb = new StringBuilder();
                                for (int i = 0; i < chars; i++) {
                                    int ch = r.u16(p + offString + i * 2);
                                    if (ch == 0) break;
                                    sb.append((char) ch);
                                }
                                s = sb.toString();
                            } else {
                                s = gdi.toText(d, p + offString, chars);
                            }
                            int[] dx = null;
                            if (offDx > 0 && p + offDx + chars * 4 <= d.length) {
                                dx = new int[chars];
                                for (int i = 0; i < chars; i++) dx[i] = r.i32(p + offDx + i * 4);
                            }
                            ops.text(s, refX, refY, null, dx, gdi.rtlFace, gdi.textAlign);
                        }
                    }
                    break;
                }

                /* ---- raster ---- */
                /* v1.35: SETBKMODE (1=TRANSPARENT, 2=OPAQUE) — OPAQUE text
                   paints its bkColor box; without it labels sat on whatever
                   was underneath */
                case 18:
                    if (parEnd - par >= 4) ops.bkMode((int) r.u32(par));
                    break;
                case 26:  /* OFFSETCLIPRGN — approximated away (rare in figure EMFs) */
                    break;
                case 28:  /* SETMETARGN — clip to full window = effectively reset */
                    ops.clipReset();
                    break;
                case 30: {  /* INTERSECTCLIPRECT: Bounds@+16..+32 */
                    if (parEnd - par >= 32) {
                        ops.clipIntersect(r.i32(par + 16), r.i32(par + 20), r.i32(par + 24), r.i32(par + 28));
                    }
                    break;
                }
                case 41: {  /* ANGLEARC: Center(8) Radius(4) Start(FLOAT) Sweep(FLOAT) */
                    if (parEnd - par >= 20) {
                        ops.angleArc(r.i32(par), r.i32(par + 4), Math.abs(r.i32(par + 8)),
                                Float.intBitsToFloat(r.i32(par + 12)),
                                Float.intBitsToFloat(r.i32(par + 16)));
                    }
                    break;
                }
                case 48:  /* SELECTCLIPREGION — handle 0 (no region = reset clip) */
                    if (parEnd - par >= 20 && (int) r.u32(par + 16) == 0) ops.clipReset();
                    break;
                case 55: {  /* ARCTO: Bounds@16, Box@16..32, Start@32, End@40 */
                    if (parEnd - par >= 48) {
                        ops.arcTo(r.i32(par + 16), r.i32(par + 20), r.i32(par + 24), r.i32(par + 28),
                                r.i32(par + 32), r.i32(par + 36), r.i32(par + 40), r.i32(par + 44));
                    }
                    break;
                }
                /* ---- v1.35: GDI path brackets (58..67) ---- */
                case 58:  /* BEGINPATH */
                    ops.beginPath();
                    break;
                case 59:  /* ENDPATH */
                    ops.endPath();
                    break;
                case 60:  /* CLOSEFIGURE */
                    ops.closeFigure();
                    break;
                case 61:  /* FILLPATH */
                    ops.fillPath();
                    break;
                case 62:  /* STROKEANDFILLPATH */
                    ops.strokeAndFillPath();
                    break;
                case 63:  /* STROKEPATH */
                    ops.strokePath();
                    break;
                case 64:  /* FLATTENPATH — curves are kept natively */
                case 65:  /* WIDENPATH */
                    break;
                case 66:  /* SELECTCLIPPATH — cannot clip by path; consume it */
                case 67:  /* ABORTPATH */
                    ops.abortPath();
                    break;
                case 76: {  /* BITBLT: xDest@16 yDest@20 cx@24 cy@28 offBmi@32
                      cbBmi@36 offBits@40 cbBits@44 iUsage@48 dwRop@52
                      xSrc@56 ySrc@60 — only DIB-backed SRCCOPY blits draw */
                    if (parEnd - par >= 64) {
                        int xDest = r.i32(par + 16), yDest = r.i32(par + 20);
                        int cx = r.i32(par + 24), cy = r.i32(par + 28);
                        int offBmi = (int) r.u32(par + 32);
                        int dwRop = (int) r.u32(par + 52);
                        int xSrc = r.i32(par + 56), ySrc = r.i32(par + 60);
                        if (offBmi > 0 && dwRop == 0x00CC0020 && p + offBmi + 40 <= d.length
                                && sanity(cx, cy)) {
                            Dib img = decodeDib(d, p + offBmi, parEnd - (p + offBmi));
                            if (img != null) ops.dib(xDest, yDest, cx, cy, xSrc, ySrc, cx, cy, img);
                        }
                    }
                    break;
                }
                case 77: {  /* STRETCHBLT: xDest@16 yDest@20 cxDest@24 cyDest@28
                      dwRop@32 xSrc@36 ySrc@40 XformSrc@44..68 BkColor@68
                      iUsage@72 offBmi@76 cbBmi@80 offBits@84 cbBits@88
                      cxSrc@92 cySrc@96 */
                    if (parEnd - par >= 100) {
                        int xDest = r.i32(par + 16), yDest = r.i32(par + 20);
                        int cxDest = r.i32(par + 24), cyDest = r.i32(par + 28);
                        int dwRop = (int) r.u32(par + 32);
                        int xSrc = r.i32(par + 36), ySrc = r.i32(par + 40);
                        int offBmi = (int) r.u32(par + 76);
                        int cxSrc = r.i32(par + 92), cySrc = r.i32(par + 96);
                        if (offBmi > 0 && dwRop == 0x00CC0020 && p + offBmi + 40 <= d.length
                                && sanity(cxDest, cyDest) && sanity(cxSrc, cySrc)) {
                            Dib img = decodeDib(d, p + offBmi, parEnd - (p + offBmi));
                            if (img != null) ops.dib(xDest, yDest, cxDest, cyDest, xSrc, ySrc, cxSrc, cySrc, img);
                        }
                    }
                    break;
                }
                case 80: {  /* SETDIBITSTODEVICE: xDest@16 yDest@20 xSrc@24
                      ySrc@28 cxSrc@32 cySrc@36 offBmi@40 cbBmi@44
                      offBits@48 cbBits@52 iUsage@56 — THE record PDF/Office
                      converters use to embed a raster strip inside an EMF */
                    if (parEnd - par >= 60) {
                        int xDest = r.i32(par + 16), yDest = r.i32(par + 20);
                        int xSrc = r.i32(par + 24), ySrc = r.i32(par + 28);
                        int cxSrc = r.i32(par + 32), cySrc = r.i32(par + 36);
                        int offBmi = (int) r.u32(par + 40);
                        if (offBmi > 0 && p + offBmi + 40 <= d.length && sanity(cxSrc, cySrc)) {
                            Dib img = decodeDib(d, p + offBmi, parEnd - (p + offBmi));
                            if (img != null) ops.dib(xDest, yDest, cxSrc, cySrc, xSrc, ySrc, cxSrc, cySrc, img);
                        }
                    }
                    break;
                }
                case 81: {  /* STRETCHDIBITS */
                    /* v1.35 FIX: spec payload = 72 (Bounds + 14 fields);
                       the old >=76 guard dropped minimal records */
                    if (parEnd - par >= 72) {
                        int xDest = r.i32(par + 16), yDest = r.i32(par + 20);
                        int xSrc = r.i32(par + 24), ySrc = r.i32(par + 28);
                        int cxSrc = r.i32(par + 32), cySrc = r.i32(par + 36);
                        int offBmi = (int) r.u32(par + 40);
                        /* v1.29 FIX (corrects a WRONG v1.27 "fix"): per [MS-EMF]
                           §2.3.1.7 / wingdi.h EMRSTRETCHDIBITS the field order
                           is xDest@16 yDest@20 xSrc@24 ySrc@28 cxSrc@32 cySrc@36
                           offBmi@40 cbBmi@44 offBits@48 cbBits@52 iUsageSrc@56
                           dwRop@60 cxDest@64 cyDest@68. The v1.27 code read
                           dwRop (0x00CC0020 for SRCCOPY) as cxDest, so
                           sanity() failed and EVERY EMF raster photo was
                           dropped. */
                        int cxDest = r.i32(par + 64), cyDest = r.i32(par + 68);
                        if (sanity(cxDest, cyDest) && sanity(cxSrc, cySrc) && offBmi > 0 && p + offBmi + 40 <= d.length) {
                            Dib img = decodeDib(d, p + offBmi, parEnd - (p + offBmi));
                            if (img != null) ops.dib(xDest, yDest, cxDest, cyDest, xSrc, ySrc, cxSrc, cySrc, img);
                        }
                    }
                    break;
                }
                case 96:  /* POLYTEXTOUTA */
                case 97:  /* POLYTEXTOUTW */
                    walkEmfPolyText(r, d, p, par, parEnd, type, gdi, ops);
                    break;
                case 70: {  /* EMR_COMMENT — the EMF+ payload lives here */
                    if (parEnd - par >= 4 && r.u32(par) == 0x2B46494CL) {
                        if (plus == null) {
                            plus = new PlusState(ops0);
                            /* start EMF+ playback from an identity canvas so
                               the plus transform chain owns the matrix */
                            plusApplyTransform(plus);
                        }
                        plus.haveDC = false;   /* any EMF+ record closes GetDC */
                        walkEmfPlus(d, par + 4, parEnd, plus);
                        ops = plus.sink();
                    }
                    break;
                }
                case 114: {  /* EMR_ALPHABLEND — DIB with per-pixel alpha */
                    if (parEnd - par >= 68) {
                        int xDest = r.i32(par + 16), yDest = r.i32(par + 20);
                        int cxDest = r.i32(par + 24), cyDest = r.i32(par + 28);
                        int offBmi = (int) r.u32(par + 40);
                        if (sanity(cxDest, cyDest) && offBmi > 0 && p + offBmi + 40 <= d.length) {
                            Dib img = decodeDib(d, p + offBmi, parEnd - (p + offBmi));
                            if (img != null)
                                ops.dib(xDest, yDest, cxDest, cyDest, 0, 0, img.w, img.h, img);
                        }
                    }
                    break;
                }
                case 116: {  /* EMR_TRANSPARENTBLT — key-color blit */
                    if (parEnd - par >= 68) {
                        int xDest = r.i32(par + 16), yDest = r.i32(par + 20);
                        int cxDest = r.i32(par + 24), cyDest = r.i32(par + 28);
                        int offBmi = (int) r.u32(par + 32);
                        /* dwRop (the COLORREF transparency key) @36 — we
                           approximate by drawing opaque; a true key-blit needs
                           per-pixel compare, rarely visible on figures */
                        if (sanity(cxDest, cyDest) && offBmi > 0 && p + offBmi + 40 <= d.length) {
                            Dib img = decodeDib(d, p + offBmi, parEnd - (p + offBmi));
                            if (img != null)
                                ops.dib(xDest, yDest, cxDest, cyDest, 0, 0, img.w, img.h, img);
                        }
                    }
                    break;
                }
                case 118: {  /* EMR_GRADIENTFILL — nVer@16 nTri@18 ulMode@20 verts@24
                       each vertex: x@0 y@4 COLORREF@8 (16 bytes); each triangle is
                       3 vertex INDICES (u32 each) AFTER the vertex array */
                    if (parEnd - par >= 24) {
                        int nVer = (int) r.u32(par + 16);
                        int nTri = (int) r.u32(par + 18) & 0xFFFF;
                        if (nVer > 0 && nVer < 8192 && nTri < 8192
                                && par + 24 + nVer * 16 <= parEnd) {
                            float[] vx = new float[nVer], vy = new float[nVer];
                            int[] vc = new int[nVer];
                            for (int i = 0; i < nVer; i++) {
                                int q = par + 24 + i * 16;
                                vx[i] = r.i32(q);
                                vy[i] = r.i32(q + 4);
                                vc[i] = cref(r, q + 8);
                            }
                            int triBase = par + 24 + nVer * 16;
                            for (int i = 0; i < nTri; i++) {
                                int q = triBase + i * 12;
                                if (q + 12 > parEnd) break;
                                int a = (int) r.u32(q), b = (int) r.u32(q + 4), c2 = (int) r.u32(q + 8);
                                if (a >= nVer || b >= nVer || c2 >= nVer) continue;
                                /* flat fill with the first vertex color — a real
                                   gradient needs per-scan shading, out of scope */
                                ops.brush(solidBrush(vc[a]));
                                float l = Math.min(Math.min(vx[a], vx[b]), vx[c2]);
                                float t = Math.min(Math.min(vy[a], vy[b]), vy[c2]);
                                float rr = Math.max(Math.max(vx[a], vx[b]), vx[c2]);
                                float bb = Math.max(Math.max(vy[a], vy[b]), vy[c2]);
                                if (rr > l && bb > t) ops.rect(l, t, rr, bb, 0, 0);
                            }
                        }
                    }
                    break;
                }
                default:
                    gdi.unknown++;
                    break;
            }
            p += size;
        }
    }

    /* v1.35: EMR_POLYTEXTOUTA/W — batched text records (labels!) some
       exporters use instead of repeated EXTTEXTOUT.
       Layout: Bounds(16) iGraphicsMode@16 exScale@20 eyScale@24
       cStrings@28 then cStrings × EMRTEXT[40] at +32:
       refX@0 refY@4 nChars@8 offString@12 fOptions@16 rcl@20..36 offDx@36 */
    private static void walkEmfPolyText(Rd r, byte[] d, int p, int par, int parEnd,
                                        int type, Gdi gdi, Ops ops) {
        if (parEnd - par < 32) return;
        int n = (int) r.u32(par + 28);
        if (n <= 0 || n > 256) return;
        for (int i = 0; i < n; i++) {
            int tp = par + 32 + i * 40;
            if (tp + 40 > parEnd) break;
            int refX = r.i32(tp), refY = r.i32(tp + 4);
            int chars = (int) r.u32(tp + 8);
            int offString = (int) r.u32(tp + 12);
            int offDx = (int) r.u32(tp + 36);
            long so = (long) p + offString;
            if (chars <= 0 || chars >= 4096 || offString <= 0
                    || so + (long) chars * (type == 97 ? 2 : 1) > d.length) continue;
            String s;
            if (type == 97) {
                StringBuilder sb = new StringBuilder(chars);
                for (int k = 0; k < chars; k++) {
                    int ch = r.u16(p + offString + k * 2);
                    if (ch == 0) break;
                    sb.append((char) ch);
                }
                s = sb.toString();
            } else {
                s = gdi.toText(d, p + offString, chars);
            }
            int[] dx = null;
            if (offDx > 0 && p + offDx + chars * 4 <= d.length) {
                dx = new int[chars];
                for (int k = 0; k < chars; k++) dx[k] = r.i32(p + offDx + k * 4);
            }
            ops.text(s, refX, refY, null, dx, gdi.rtlFace, gdi.textAlign);
        }
    }
    static float[][] emfPts(Rd r, int par, int parEnd, boolean w16) {
        if (parEnd - par < 20) return null;
        int n = (int) r.u32(par + 16);
        int body = 20;
        int stride = w16 ? 4 : 8;
        if (n < 0 || n > 4096 || parEnd - par < body + n * stride) return null;
        float[][] pts = new float[n][];
        for (int i = 0; i < n; i++) {
            /* v1.28 FIX: PointS = [x, y] (مثل wmfPts بالا) */
            if (w16) pts[i] = new float[]{r.i16(par + body + i * 4), r.i16(par + body + i * 4 + 2)};
            else pts[i] = new float[]{r.i32(par + body + i * 8), r.i32(par + body + i * 8 + 4)};
        }
        return pts;
    }

    static float[] readXform(Rd r, int par) {
        return new float[]{
                Float.intBitsToFloat(r.i32(par)),
                Float.intBitsToFloat(r.i32(par + 4)),
                Float.intBitsToFloat(r.i32(par + 8)),
                Float.intBitsToFloat(r.i32(par + 12)),
                Float.intBitsToFloat(r.i32(par + 16)),
                Float.intBitsToFloat(r.i32(par + 20))};
    }

    /* v1.35: EMF COLORREF DWORD = 0x00BBGGRR with RED in the LOW byte —
       convert to Android ARGB (red in bits 16..23). The old plain OR kept
       the GDI byte order and swapped every red/blue in EMF pens, brushes
       and text. */
    static int cref(Rd r, int o) {
        int v = (int) r.u32(o);
        return 0xFF000000 | ((v & 0xFF) << 16) | (v & 0x00FF00) | ((v >> 16) & 0xFF);
    }

    /* ------------------------------------------------------------------ GDI */

    static final class Gdi {
        int records, unknown, textChars, dibs, rasterPx, saved;
        boolean rtlFace;
        int curCharset = 0;
        /* v1.27: SETTEXTALIGN (TA_LEFT=0, TA_RIGHT=2, TA_CENTER=6) */
        int textAlign = 0;

        void addObject(List<Object> objects, Object o) {
            objects.add(o);
            if (o instanceof MFont) {
                curCharset = ((MFont) o).charset;
                sniffRtl(((MFont) o).face);
            }
        }

        void emfAdd(List<Object> objects, Object o, long ih) {
            int idx = (int) ih;
            /* v1.29 FIX: a crafted ih (0xFFFFFFFF -> -1) threw IOOBE and huge
               values grew the list toward 2^31 nulls (OOM). Object handles are
               small — cap at 65536. */
            if (idx < 0 || idx > 65536) return;
            while (objects.size() <= idx) objects.add(null);
            objects.set(idx, o);
            if (o instanceof MFont) {
                curCharset = ((MFont) o).charset;
                sniffRtl(((MFont) o).face);
            }
        }

        void sniffRtl(String face) {
            if (face == null) return;
            String l = face.toLowerCase();
            if (l.contains("nazanin") || l.contains("mitra") || l.contains("yekan")
                    || l.contains("vazir") || l.contains("b badr") || l.contains("traffic")
                    || l.contains("roya") || l.contains("titan") || l.contains("lotus")
                    || l.contains("sina") || l.contains("badr") || l.contains("zar")) {
                rtlFace = true;
            }
        }

        void select(List<Object> objects, int idx, Ops ops) {
            /* WMF stock = 0x8000|i (16-bit); EMF stock = 0x80000000|i */
            if ((idx & 0x80000000) != 0 || (idx & 0x8000) != 0) {
                /* v1.29 FIX: [MS-WMF] §2.2.1.16 stock categories — brushes are
                   0-5 (+DC_BRUSH 18), pens 6-8 (+DC_PEN 19), fonts 10-14,
                   palette 15. The old code reset BOTH pen and brush on ANY
                   stock pick, so the classic "create color brush, select it,
                   then select BLACK_PEN for the outline" idiom repainted the
                   whole shape WHITE (default brush). Gray brushes render real
                   grays now. */
                int s = idx & 0xFF;
                if (s > 19) s = 0;
                boolean wantBrush = s <= 5 || s == 18;
                boolean wantPen = (s >= 6 && s <= 8) || s == 19;
                if (wantBrush) {
                    Brush br = new Brush();
                    switch (s) {
                        case 1: br.color = 0xFFC0C0C0; break;   /* LTGRAY_BRUSH */
                        case 2: br.color = 0xFF808080; break;   /* GRAY_BRUSH */
                        case 3: br.color = 0xFF404040; break;   /* DKGRAY_BRUSH */
                        case 4: br.color = 0xFF000000; break;    /* BLACK_BRUSH */
                        case 5: br.style = 5; break;            /* NULL_BRUSH */
                        default: br.color = 0xFFFFFFFF; break;   /* WHITE/DC */
                    }
                    ops.brush(br);
                }
                if (wantPen) {
                    Pen pen = new Pen();
                    if (s == 6) pen.color = 0xFFFFFFFF;         /* WHITE_PEN */
                    else if (s == 7) pen.color = 0xFF000000;    /* BLACK_PEN */
                    else pen.style = 5;                         /* NULL/DC_PEN */
                    ops.pen(pen);
                }
                return;
            }
            if (idx < 0 || idx >= objects.size()) return;
            Object o = objects.get(idx);
            if (o instanceof Pen) ops.pen((Pen) o);
            else if (o instanceof Brush) ops.brush((Brush) o);
            else if (o instanceof MFont) {
                ops.font((MFont) o);
                curCharset = ((MFont) o).charset;
            }
        }

        String toText(byte[] d, int off, int cnt) {
            /* v1.29 FIX: negative offsets must bail — the caller's int
               arithmetic can wrap on crafted offString values (AIOOBE crash). */
            if (off < 0) return "";
            StringBuilder sb = new StringBuilder(Math.max(0, cnt));
            for (int i = 0; i < cnt && off + i < d.length; i++) {
                sb.append(CpTables.map(d[off + i], curCharset));
            }
            return sb.toString();
        }
    }

    /* ---------------------------------------------------------------- DIBs */

    static Dib decodeDib(byte[] d, int bmi, int avail) {
        if (bmi + 40 > d.length || avail < 40) return null;
        Rd r = new Rd(d);
        int biSize = (int) r.u32(bmi);
        if (biSize < 40 || biSize > 4096) return null;
        int w = r.i32(bmi + 4);
        int h0 = r.i32(bmi + 8);
        int bpp = r.u16(bmi + 14);
        int comp = (int) r.u32(bmi + 16);
        int clrUsed = (int) r.u32(bmi + 32);
        if (comp != 0) return null;   /* BI_RGB only */
        boolean topDown = h0 < 0;
        int h = Math.abs(h0);
        if (!sanity(w, h)) return null;
        /* v1.28 FIX: ۶۴ مگاپیکسل = آرایهٔ int ~۲۵۶MB + بیت‌مپ دیگر ~۲۵۶MB
           بدون هیچ مدیریت OOM → کرَش روی فایل‌های بزرگ/خراب؛ سقف واقعی ۱۶
           مگاپیکسل (۱۲۸MB آرایه + بیت‌مپ ~۶۴MB بعد از downscale فراخوان) */
        if ((long) w * h > 16L * 1024 * 1024) return null;
        int palCount = bpp <= 8 ? (clrUsed > 0 ? clrUsed : (1 << bpp)) : 0;
        /* v1.29 FIX: cap + long math — clrUsed=0x20000000 made palCount*4
           wrap negative, bypassing the bounds check into a 2GB allocation
           (OOM crash on crafted files). */
        if (palCount > 256) return null;
        int palOff = bmi + biSize;
        if (palOff + 4L * palCount > d.length) return null;
        int[] pal = new int[palCount];
        for (int i = 0; i < palCount; i++) {
            int b = d[palOff + i * 4] & 255, g = d[palOff + i * 4 + 1] & 255;
            int rr = d[palOff + i * 4 + 2] & 255;
            pal[i] = 0xFF000000 | (rr << 16) | (g << 8) | b;
        }
        int bits = palOff + palCount * 4;
        int rowBytes = (w * bpp + 31) / 32 * 4;
        int[] out = new int[w * h];
        for (int y = 0; y < h; y++) {
            int ro = bits + y * rowBytes;
            if (ro + rowBytes > d.length) break;
            int ty = topDown ? y : h - 1 - y;
            int baseIdx = ty * w;
            switch (bpp) {
                case 1:
                    for (int x = 0; x < w; x++) {
                        int bitv = (d[ro + (x >> 3)] >> (7 - (x & 7))) & 1;
                        out[baseIdx + x] = pal[bitv < palCount ? bitv : 0];
                    }
                    break;
                case 4:
                    for (int x = 0; x < w; x++) {
                        int v = (d[ro + (x >> 1)] >> ((1 - (x & 1)) * 4)) & 15;
                        out[baseIdx + x] = v < palCount ? pal[v] : 0xFF000000;
                    }
                    break;
                case 8:
                    for (int x = 0; x < w; x++) {
                        int v = d[ro + x] & 255;
                        out[baseIdx + x] = v < palCount ? pal[v] : 0xFF000000;
                    }
                    break;
                case 16:
                    for (int x = 0; x < w; x++) {
                        int v = r.u16(ro + x * 2);
                        int rr = (v >> 10) & 31, g = (v >> 5) & 31, b = v & 31;
                        out[baseIdx + x] = 0xFF000000 | (rr * 8 << 16) | (g * 8 << 8) | b * 8;
                    }
                    break;
                case 24:
                    for (int x = 0; x < w; x++) {
                        int b = d[ro + x * 3] & 255, g = d[ro + x * 3 + 1] & 255, rr = d[ro + x * 3 + 2] & 255;
                        out[baseIdx + x] = 0xFF000000 | (rr << 16) | (g << 8) | b;
                    }
                    break;
                case 32:
                    for (int x = 0; x < w; x++) {
                        int b = d[ro + x * 4] & 255, g = d[ro + x * 4 + 1] & 255, rr = d[ro + x * 4 + 2] & 255;
                        int a = d[ro + x * 4 + 3] & 255;
                        /* v1.27 FIX: in BI_RGB 32bpp the 4th byte is PADDING,
                           not alpha (usually 0) — honoring it made GDI-exported
                           bitmaps render fully transparent (invisible).
                           Only BI_BITFIELDS/BI_ALPHABITMAPS carry real alpha
                           (comp!=0), which this decoder doesn't take here. */
                        if (comp == 0) a = 255;
                        out[baseIdx + x] = (a << 24) | (rr << 16) | (g << 8) | b;
                    }
                    break;
                default:
                    return null;
            }
        }
        return new Dib(w, h, out);
    }

    /* ================================================================ Ops */

    interface Ops {
        void window(int orgX, int orgY, int extX, int extY);

        void pen(Pen p);

        void brush(Brush b);

        void font(MFont f);

        void textColor(int c);

        void bkColor(int c);

        void fillEvenOdd(boolean e);

        void world(float[] m6);

        void modifyWorld(float[] m6, int mode);

        /* v1.27: GDI SaveDC/RestoreDC state snapshots */
        void save();

        void restore();

        void moveTo(float x, float y);

        void lineTo(float x, float y);

        /* v1.27: EMF bezier + lineto records */
        void bezier(float[][] pts, boolean toMode);

        void polyTo(float[][] pts);

        void poly(float[][] pts, boolean closed);

        void polyPoly(float[][][] polys, boolean closed);

        void rect(float l, float t, float r, float b, float rx, float ry);

        void ellipse(float l, float t, float r, float b);

        void pieChord(float l, float t, float r, float b, float sx, float sy, float ex, float ey, int kind);

        void text(String s, float x, float y, float[] clipRect, int[] dx, boolean rtl, int align);

        void dib(float xd, float yd, float wd, float hd, float xs, float ys, float ws, float hs, Dib img);

        /* v1.35: GDI path brackets — BeginPath/EndPath wrap a sequence of
           segment/shape records; the bracket is rendered once, by
           StrokePath / FillPath / StrokeAndFillPath. Without these, every
           EMF figure authored by Office/Illustrator/Word (the vector format
           medical textbooks embed) rendered its interiors EMPTY — only the
           stray immediately-drawn outlines survived. */
        void beginPath();

        void endPath();

        void closeFigure();

        void strokePath();

        void fillPath();

        void strokeAndFillPath();

        void abortPath();

        /* v1.35: SETBKMODE — OPAQUE text needs its background painted */
        void bkMode(int mode);

        /* v1.35: rectangular clipping (INTERSECTCLIPRECT / SETMETARGN) */
        void clipIntersect(float l, float t, float r, float b);

        void clipReset();

        /* v1.35: ANGLEARC — center/radius in logical units, degrees CCW
           (logical y-up), sweep may be negative (clockwise) */
        void angleArc(float cx, float cy, float radius, float startDeg, float sweepDeg);

        /* v1.35: ARCTO — elliptical arc appended to the current path */
        void arcTo(float l, float t, float r, float b, float sx, float sy, float ex, float ey);

        /* v1.36: EMF+ DrawImage — an encoded (PNG/JPEG/GIF) or pixel (Dib)
           image drawn as the parallelogram spanned by 3 points (world coords,
           transformed by the current canvas matrix) with a source crop rect */
        void image(byte[] enc, Dib dib, float[] pts6, float sl, float st, float sr, float sb);
    }

    /** parse-only sink */
    static final class CountOps implements Ops {
        final Stats stats = new Stats();
        int winW = -1, winH = -1;

        public void window(int orgX, int orgY, int extX, int extY) {
            if (extX > 0 && extY > 0) { winW = extX; winH = extY; }
            stats.drawn++;
        }

        public void pen(Pen p) { }

        public void brush(Brush b) { }

        public void font(MFont f) { }

        public void textColor(int c) { }

        public void bkColor(int c) { }

        public void fillEvenOdd(boolean e) { }

        public void world(float[] m) { stats.drawn++; }

        public void modifyWorld(float[] m, int mode) { stats.drawn++; }

        public void moveTo(float x, float y) { stats.drawn++; }

        public void lineTo(float x, float y) { stats.drawn++; }

        public void poly(float[][] pts, boolean closed) { stats.drawn++; }

        public void polyPoly(float[][][] polys, boolean closed) { stats.drawn++; }

        public void rect(float l, float t, float r, float b, float rx, float ry) { stats.drawn++; }

        public void ellipse(float l, float t, float r, float b) { stats.drawn++; }

        public void pieChord(float l, float t, float r, float b, float sx, float sy, float ex, float ey, int kind) {
            stats.drawn++;
        }

        public void text(String s, float x, float y, float[] clipRect, int[] dx, boolean rtl, int align) {
            stats.drawn++;
            if (s != null) stats.textChars += s.length();
        }

        public void dib(float xd, float yd, float wd, float hd, float xs, float ys, float ws, float hs, Dib img) {
            stats.dibs++;
            if (img != null) {
                stats.rasterPx += img.w * img.h;
                stats.drawn++;
            }
        }

        public void save() { }

        public void restore() { }

        public void bezier(float[][] pts, boolean toMode) { stats.drawn++; }

        public void polyTo(float[][] pts) { stats.drawn++; }

        public void beginPath() { stats.pathOps++; }

        public void endPath() { stats.pathOps++; }

        public void closeFigure() { stats.pathOps++; }

        public void strokePath() { stats.pathOps++; stats.drawn++; }

        public void fillPath() { stats.pathOps++; stats.drawn++; }

        public void strokeAndFillPath() { stats.pathOps++; stats.drawn++; }

        public void abortPath() { stats.pathOps++; }

        public void bkMode(int mode) { }

        public void clipIntersect(float l, float t, float r, float b) { stats.drawn++; }

        public void clipReset() { }

        public void angleArc(float cx, float cy, float radius, float startDeg, float sweepDeg) {
            stats.drawn++;
        }

        public void arcTo(float l, float t, float r, float b, float sx, float sy, float ex, float ey) {
            stats.drawn++;
        }

        /* v1.36: EMF+ images counted so analyze() reflects them */
        public void image(byte[] enc, Dib dib, float[] pts, float sl, float st, float sr, float sb) {
            stats.dibs++;
            stats.drawn++;
        }
    }

    /* ===================================================== render onto canvas */

    static final class RenderOps implements Ops {
        private final Canvas c;
        private final float sx, sy;
        private Pen pen = new Pen();
        private Brush brush = new Brush();
        private MFont font = new MFont();
        private int textColor = 0xFF000000;
        private boolean evenOdd;
        private final Matrix wt = new Matrix();
        private boolean wtIdentity = true;
        private float curX, curY;
        /* v1.27: DC state stack (SaveDC/RestoreDC) + window origin */
        private final java.util.ArrayDeque<Object[]> dcStack = new java.util.ArrayDeque<>();
        private float winOffX, winOffY;
        /* v1.35: GDI path bracket state — bracketOpen = between BeginPath and
           EndPath (segments accumulate); curPath survives ENDPATH until
           Stroke/Fill consumes it. figOpen tracks whether the current figure
           needs an implicit MoveTo after CloseFigure. */
        private Path curPath;
        private boolean bracketOpen;
        private boolean figOpen;
        /* v1.35: SETBKMODE OPAQUE + background color for text */
        private boolean bkOpaque;
        private int bkCol = 0xFFFFFFFF;
        /* v1.35: rectangular clipping — each intersect is one canvas save, so
           restore is symmetric; clipDepth is snapshotted by save()/restore() */
        private int clipDepth;

        RenderOps(Canvas c, float sx, float sy) {
            this.c = c;
            this.sx = sx;
            this.sy = sy;
        }

        public void window(int orgX, int orgY, int extX, int extY) {
            /* v1.27 FIX: SETWINDOWORG was discarded — WMFs with non-zero
               window origins (very common in Persian clip-art) rendered
               shifted or blank. Fold the origin into the world transform
               idempotently via the base matrix. */
            winOffX = -orgX * sx;
            winOffY = -orgY * sy;
            if (wtIdentity) {
                if (winOffX != 0 || winOffY != 0) {
                    wt.setTranslate(winOffX, winOffY);
                    wtIdentity = false;
                    c.setMatrix(wt);
                }
            } else {
                c.setMatrix(wt);
                c.translate(winOffX, winOffY);
            }
        }

        public void save() {
            dcStack.push(new Object[]{pen.copy(), brush.copy(), font.copy(),
                    Integer.valueOf(textColor), Boolean.valueOf(evenOdd),
                    wtIdentity ? null : new Matrix(wt),
                    Float.valueOf(curX), Float.valueOf(curY),
                    Float.valueOf(winOffX), Float.valueOf(winOffY),
                    Boolean.valueOf(bkOpaque), Integer.valueOf(bkCol),
                    Integer.valueOf(clipDepth)});
        }

        public void restore() {
            Object[] s = dcStack.isEmpty() ? null : dcStack.pop();
            if (s == null) return;
            int hadClip = (Integer) s[12];
            /* v1.35: undo clip intersects applied since the SaveDC (each one
               was one canvas save) before the manual state pop below */
            while (clipDepth > hadClip) {
                c.restore();
                clipDepth--;
            }
            pen = (Pen) s[0]; brush = (Brush) s[1]; font = (MFont) s[2];
            textColor = (Integer) s[3]; evenOdd = (Boolean) s[4];
            Matrix m = (Matrix) s[5];
            if (m == null) { wt.reset(); wtIdentity = true; c.setMatrix(null); }
            else { wt.set(m); wtIdentity = false; c.setMatrix(wt); }
            curX = (Float) s[6]; curY = (Float) s[7];
            winOffX = (Float) s[8]; winOffY = (Float) s[9];
            bkOpaque = (Boolean) s[10]; bkCol = (Integer) s[11];
        }

        public void pen(Pen p) { if (p != null) pen = p; }

        public void brush(Brush b) { if (b != null) brush = b; }

        public void font(MFont f) { if (f != null) font = f; }

        public void textColor(int col) { textColor = col; }

        public void bkColor(int col) { }

        public void fillEvenOdd(boolean e) { evenOdd = e; }

        public void world(float[] m) {
            applyMatrix(m);
        }

        public void modifyWorld(float[] m, int mode) {
            /* v1.28 FIX: MWT_IDENTITY قبلاً زود return می‌شد بدون اعمال روی
               Canvas → ماتریس قدیمی تا فرمان بعدی فعال می‌ماند */
            if (mode == 1) {
                wt.reset(); wtIdentity = true;
                c.setMatrix(null);
                return;
            }
            if (m == null) return;
            Matrix nm = toMatrix(m);
            if (wtIdentity || mode == 4) {
                if (mode == 2) wt.preConcat(nm);      /* xform × current */
                else if (mode == 3) wt.postConcat(nm); /* current × xform */
                else wt.set(nm);
            } else if (mode == 2) wt.preConcat(nm);
            else if (mode == 3) wt.postConcat(nm);
            else wt.set(nm);
            wtIdentity = false;
            c.setMatrix(wtIdentity ? null : wt);
        }

        private void applyMatrix(float[] m) {
            if (m == null) { wt.reset(); wtIdentity = true; }
            else { wt.set(toMatrix(m)); wtIdentity = false; }
            c.setMatrix(wtIdentity ? null : wt);
        }

        private static Matrix toMatrix(float[] m) {
            Matrix mx = new Matrix();
            mx.setValues(new float[]{m[0], m[2], m[4], m[1], m[3], m[5], 0, 0, 1});
            return mx;
        }

        public void moveTo(float x, float y) {
            if (bracketOpen && curPath != null) {
                curPath.moveTo(x * sx, y * sy);
                figOpen = true;
            }
            curX = x; curY = y;
        }

        public void lineTo(float x, float y) {
            if (bracketOpen && curPath != null) {
                pLine(x, y);
                curX = x; curY = y;
                return;
            }
            if (pen.isNull()) { curX = x; curY = y; return; }
            /* v1.27 FIX: the stray duplicate drawLine painted a dot at every
               lineTo endpoint (dotted blobs at polyline joints) */
            c.drawLine(curX * sx, curY * sy, x * sx, y * sy, penPaint());
            curX = x;
            curY = y;
        }

        public void bezier(float[][] pts, boolean toMode) {
            if (pts == null || pts.length == 0) return;
            if (bracketOpen && curPath != null) {
                if (toMode) {
                    ensureFig();
                } else {
                    curPath.moveTo(pts[0][0] * sx, pts[0][1] * sy);
                    figOpen = true;
                }
                int i = toMode ? 0 : 1;
                while (i + 2 < pts.length + 1) {
                    curPath.cubicTo(pts[i][0] * sx, pts[i][1] * sy,
                            pts[i + 1][0] * sx, pts[i + 1][1] * sy,
                            pts[i + 2][0] * sx, pts[i + 2][1] * sy);
                    i += 3;
                    if (i + 3 > pts.length) break;
                }
                curX = pts[pts.length - 1][0];
                curY = pts[pts.length - 1][1];
                return;
            }
            if (pen.isNull()) {
                if (pts != null && pts.length > 0) {
                    curX = pts[pts.length - 1][0]; curY = pts[pts.length - 1][1];
                }
                return;
            }
            Path ph = new Path();
            int start;
            if (toMode) {
                ph.moveTo(curX * sx, curY * sy);
                start = 0;
            } else {
                ph.moveTo(pts[0][0] * sx, pts[0][1] * sy);
                start = 1;
            }
            int i = start;
            while (i + 2 < pts.length + 1) {
                ph.cubicTo(pts[i][0] * sx, pts[i][1] * sy,
                        pts[i + 1][0] * sx, pts[i + 1][1] * sy,
                        pts[i + 2][0] * sx, pts[i + 2][1] * sy);
                i += 3;
                if (i + 3 > pts.length) break;
            }
            c.drawPath(ph, penPaint());
            curX = pts[pts.length - 1][0];
            curY = pts[pts.length - 1][1];
        }

        public void polyTo(float[][] pts) {
            if (pts == null || pts.length == 0) return;
            if (bracketOpen && curPath != null) {
                for (int i = 0; i < pts.length; i++) pLine(pts[i][0], pts[i][1]);
                curX = pts[pts.length - 1][0];
                curY = pts[pts.length - 1][1];
                return;
            }
            for (int i = 0; i < pts.length; i++) {
                float nx = pts[i][0], ny = pts[i][1];
                if (!pen.isNull()) c.drawLine(curX * sx, curY * sy, nx * sx, ny * sy, penPaint());
                curX = nx; curY = ny;
            }
        }

        public void poly(float[][] pts, boolean closed) {
            if (pts == null || pts.length == 0) return;
            if (bracketOpen && curPath != null) {
                appendPoly(pts, closed);
                curX = pts[pts.length - 1][0];
                curY = pts[pts.length - 1][1];
                return;
            }
            Path ph = pathOf(pts, closed);
            fillStroke(ph, closed);
        }

        public void polyPoly(float[][][] polys, boolean closed) {
            if (polys == null || polys.length == 0) return;
            if (bracketOpen && curPath != null) {
                for (float[][] pts : polys) {
                    if (pts == null || pts.length == 0) continue;
                    appendPoly(pts, closed);
                }
                return;
            }
            Path ph = new Path();
            ph.setFillType(evenOdd ? Path.FillType.EVEN_ODD : Path.FillType.WINDING);
            for (float[][] pts : polys) {
                if (pts == null || pts.length == 0) continue;
                ph.moveTo(pts[0][0] * sx, pts[0][1] * sy);
                for (int i = 1; i < pts.length; i++) ph.lineTo(pts[i][0] * sx, pts[i][1] * sy);
                ph.close();
            }
            /* v1.29 FIX: POLYPOLYLINE16 (type 91) shares this path — GDI
               polyline sets are NEVER filled; the old unconditional fill
               painted white blobs over content. */
            fillStroke(ph, closed);
        }

        public void rect(float l, float t, float r, float b, float rx, float ry) {
            if (r < l) { float tmp = l; l = r; r = tmp; }
            if (b < t) { float tmp = t; t = b; b = tmp; }
            if (bracketOpen && curPath != null) {
                if (rx > 0 || ry > 0) {
                    float rad = Math.min(Math.abs(rx), (r - l) / 2f);
                    rad = Math.min(rad, Math.abs(ry));
                    rad = Math.min(rad, (b - t) / 2f);
                    android.graphics.RectF rf = new android.graphics.RectF(l * sx, t * sy, r * sx, b * sy);
                    curPath.addRoundRect(rf, rad * sx, rad * sy, Path.Direction.CW);
                    figOpen = false;
                } else {
                    appendPoly(new float[][]{{l, t}, {r, t}, {r, b}, {l, b}}, true);
                }
                return;
            }
            RectF rf = new RectF(l * sx, t * sy, r * sx, b * sy);
            if (rx > 0 || ry > 0) {
                float rad = Math.min(Math.abs(rx) * sx, Math.min(rf.width(), rf.height()) / 2f);
                rad = Math.min(rad, Math.abs(ry) * sy);
                rad = Math.min(rad, Math.min(rf.width(), rf.height()) / 2f);
                Path ph = new Path();
                ph.addRoundRect(rf, rad, rad, Path.Direction.CW);
                fillStroke(ph, true);
            } else {
                if (!brush.isNull()) c.drawRect(rf, brushPaint());
                if (!pen.isNull()) c.drawRect(rf, penPaint());
            }
        }

        public void ellipse(float l, float t, float r, float b) {
            if (r < l) { float tmp = l; l = r; r = tmp; }
            if (b < t) { float tmp = t; t = b; b = tmp; }
            if (bracketOpen && curPath != null) {
                appendEllipse(l, t, r, b);
                return;
            }
            RectF rf = new RectF(l * sx, t * sy, r * sx, b * sy);
            if (!brush.isNull()) c.drawOval(rf, brushPaint());
            if (!pen.isNull()) c.drawOval(rf, penPaint());
        }

        public void pieChord(float l, float t, float r, float b, float sxp, float syp, float exp, float eyp, int kind) {
            if (r < l) { float tmp = l; l = r; r = tmp; }
            if (b < t) { float tmp = t; t = b; b = tmp; }
            if (bracketOpen && curPath != null) {
                appendArc(l, t, r, b, sxp, syp, exp, eyp, kind);
                return;
            }
            float cx = (l + r) / 2f, cy = (t + b) / 2f;
            float a0 = (float) Math.toDegrees(Math.atan2(-(syp - cy), sxp - cx));
            float a1 = (float) Math.toDegrees(Math.atan2(-(eyp - cy), exp - cx));
            float sweep = a1 - a0;
            if (sweep <= 0) sweep += 360f;
            RectF rf = new RectF(l * sx, t * sy, r * sx, b * sy);
            boolean useCenter = kind == 1;
            /* v1.27 FIX: GDI sweeps COUNTERclockwise visually — the math
               (y-up) sweep must be a NEGATIVE canvas sweep.
               v1.28 FIX: زاویهٔ شروع نیز باید قرینه شود (-a0 نه a0) — با
               +a0 کمان نسبت به قطر افقی آینه می‌شد؛ فقط حالت‌های شروع از
               ساعت ۳/۹ درست دیده می‌شدند و بقیه (مثل ۱۲→۳) سمت غلط */
            if (kind != 0 && !brush.isNull()) c.drawArc(rf, -a0, -sweep, useCenter, brushPaint());
            if (!pen.isNull()) c.drawArc(rf, -a0, -sweep, useCenter, penPaint());
        }

        /* ================= v1.35: path brackets ================= */

        public void beginPath() {
            curPath = new Path();
            curPath.setFillType(evenOdd ? Path.FillType.EVEN_ODD : Path.FillType.WINDING);
            bracketOpen = true;
            figOpen = false;
        }

        public void endPath() {
            bracketOpen = false;
        }

        public void closeFigure() {
            if (curPath != null) {
                curPath.close();
                figOpen = false;
            }
        }

        public void strokePath() {
            if (curPath != null && !curPath.isEmpty() && !pen.isNull()) {
                Paint pp = penPaint();
                pp.setStyle(Paint.Style.STROKE);
                c.drawPath(curPath, pp);
            }
            curPath = null;
            bracketOpen = false;
        }

        public void fillPath() {
            if (curPath != null && !curPath.isEmpty() && !brush.isNull()) {
                c.drawPath(curPath, brushPaint());
            }
            curPath = null;
            bracketOpen = false;
        }

        public void strokeAndFillPath() {
            if (curPath != null && !curPath.isEmpty()) {
                if (!brush.isNull()) c.drawPath(curPath, brushPaint());
                if (!pen.isNull()) {
                    Paint pp = penPaint();
                    pp.setStyle(Paint.Style.STROKE);
                    c.drawPath(curPath, pp);
                }
            }
            curPath = null;
            bracketOpen = false;
        }

        public void abortPath() {
            curPath = null;
            bracketOpen = false;
        }

        public void bkMode(int mode) {
            bkOpaque = mode == 2;   /* TRANSPARENT=1 OPAQUE=2 */
        }

        public void clipIntersect(float l, float t, float r, float b) {
            if (r < l) { float tmp = l; l = r; r = tmp; }
            if (b < t) { float tmp = t; t = b; b = tmp; }
            c.save();
            c.clipRect(l * sx, t * sy, r * sx, b * sy);
            clipDepth++;
        }

        public void clipReset() {
            while (clipDepth > 0) {
                c.restore();
                clipDepth--;
            }
            /* canvas restore rewound the matrix too — re-apply the intended
               base transform (see window()/world()/restore()) */
            if (wtIdentity) {
                c.setMatrix(null);
                if (winOffX != 0 || winOffY != 0) c.translate(winOffX, winOffY);
            } else {
                c.setMatrix(wt);
            }
        }

        public void angleArc(float cx, float cy, float radius, float startDeg, float sweepDeg) {
            if (radius <= 0) return;
            int n = Math.max(2, (int) Math.ceil(Math.abs(sweepDeg) / 15f));
            float[][] pts = new float[n + 1][];
            for (int i = 0; i <= n; i++) {
                double a = Math.toRadians(startDeg + sweepDeg * i / (double) n);
                pts[i] = new float[]{(float) (cx + radius * Math.cos(a)),
                        (float) (cy + radius * Math.sin(a))};
            }
            if (bracketOpen && curPath != null) {
                /* GDI: line from the current position to the arc start, then arc */
                pLine(pts[0][0], pts[0][1]);
                for (int i = 1; i <= n; i++) pLine(pts[i][0], pts[i][1]);
            } else if (!pen.isNull()) {
                Path ph = new Path();
                ph.moveTo(curX * sx, curY * sy);
                ph.lineTo(pts[0][0] * sx, pts[0][1] * sy);
                for (int i = 1; i <= n; i++) ph.lineTo(pts[i][0] * sx, pts[i][1] * sy);
                c.drawPath(ph, penPaint());
            }
            curX = pts[n][0];
            curY = pts[n][1];
        }

        public void arcTo(float l, float t, float r, float b, float sxp, float syp, float exp, float eyp) {
            if (bracketOpen && curPath != null) {
                appendArc(l, t, r, b, sxp, syp, exp, eyp, 3);   /* 3 = arcto: open arc */
            }
        }

        /* ============ v1.36: EMF+ DrawImage — affine parallelogram ====== */

        private final java.util.LinkedHashMap<String, Bitmap> IMG_CACHE =
                new java.util.LinkedHashMap<String, Bitmap>(8, 0.75f, true);

        private Bitmap plusDecode(byte[] enc) {
            String key = enc.length + ":" + java.util.Arrays.hashCode(enc);
            synchronized (IMG_CACHE) {
                Bitmap got = IMG_CACHE.get(key);
                if (got != null) return got.isRecycled() ? null : got;
            }
            Bitmap bmp = null;
            try {
                bmp = android.graphics.BitmapFactory.decodeByteArray(enc, 0, enc.length);
            } catch (Throwable ignored) {
            }
            if (bmp == null) return null;
            synchronized (IMG_CACHE) {
                IMG_CACHE.put(key, bmp);
                if (IMG_CACHE.size() > 6) {
                    java.util.Iterator<Bitmap> it = IMG_CACHE.values().iterator();
                    it.next();
                    it.remove();
                }
            }
            return bmp;
        }

        private Bitmap dibToBitmap(Dib dib) {
            if (dib == null || dib.w <= 0 || dib.h <= 0) return null;
            Bitmap b = Bitmap.createBitmap(dib.w, dib.h, Bitmap.Config.ARGB_8888);
            b.setPixels(dib.argb, 0, dib.w, 0, 0, dib.w, dib.h);
            return b;
        }

        public void image(byte[] enc, Dib dib, float[] pts, float sl, float st, float sr, float sb) {
            Bitmap src = enc != null ? plusDecode(enc) : dibToBitmap(dib);
            if (src == null || pts == null || pts.length < 6) return;
            int iw = src.getWidth(), ih = src.getHeight();
            if (iw <= 0 || ih <= 0) return;
            /* source crop — SrcRect is in image pixels (SrcUnit=Pixel) */
            int sxl = clamp((int) Math.floor(sl), 0, iw);
            int sxt = clamp((int) Math.floor(st), 0, ih);
            int sxr = clamp((int) Math.ceil(sr), sxl + 1, iw);
            int sxr2 = Math.max(sxr, sxl + 1);
            int sxb = clamp((int) Math.ceil(sb), sxt + 1, ih);
            int sxb2 = Math.max(sxb, sxt + 1);
            Bitmap draw = src;
            if (sxl > 0 || sxt > 0 || sxr2 < iw || sxb2 < ih) {
                try {
                    draw = Bitmap.createBitmap(src, sxl, sxt, sxr2 - sxl, sxb2 - sxt);
                } catch (Throwable t) {
                    draw = src;
                }
            }
            if (draw == null || draw.getWidth() <= 0 || draw.getHeight() <= 0) return;
            int cw = draw.getWidth(), ch = draw.getHeight();
            /* parallelogram from 3 points: origin, +U (x axis), +V (y axis) */
            float x0 = pts[0] * sx, y0 = pts[1] * sy;
            float x1 = pts[2] * sx, y1 = pts[3] * sy;
            float x2 = pts[4] * sx, y2 = pts[5] * sy;
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.setValues(new float[]{(x1 - x0) / cw, (x2 - x0) / ch, x0,
                    (y1 - y0) / cw, (y2 - y0) / ch, y0, 0, 0, 1});
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            c.drawBitmap(draw, m, p);
        }

        static int clamp(int v, int lo, int hi) {
            return v < lo ? lo : (v > hi ? hi : v);
        }

        /* ---- v1.35: path-append helpers (device coords) ---- */

        private void ensureFig() {
            if (!figOpen) {
                curPath.moveTo(curX * sx, curY * sy);
                figOpen = true;
            }
        }

        private void pLine(float x, float y) {
            ensureFig();
            curPath.lineTo(x * sx, y * sy);
        }

        private void appendPoly(float[][] pts, boolean close) {
            curPath.moveTo(pts[0][0] * sx, pts[0][1] * sy);
            for (int i = 1; i < pts.length; i++) curPath.lineTo(pts[i][0] * sx, pts[i][1] * sy);
            if (close) {
                curPath.close();
                figOpen = false;
            } else {
                figOpen = true;
            }
        }

        private void appendEllipse(float l, float t, float r, float b) {
            float cx = (l + r) / 2f * sx, cy = (t + b) / 2f * sy;
            float rx = (r - l) / 2f * sx, ry = (b - t) / 2f * sy;
            float k = 0.5522847498f;
            curPath.moveTo(cx + rx, cy);
            curPath.cubicTo(cx + rx, cy + ry * k, cx + rx * k, cy + ry, cx, cy + ry);
            curPath.cubicTo(cx - rx * k, cy + ry, cx - rx, cy + ry * k, cx - rx, cy);
            curPath.cubicTo(cx - rx, cy - ry * k, cx - rx * k, cy - ry, cx, cy - ry);
            curPath.cubicTo(cx + rx * k, cy - ry, cx + rx, cy - ry * k, cx + rx, cy);
            curPath.close();
            figOpen = false;
        }

        /** elliptical arc between two points, appended to the current path.
            kind: 0=arc(open) 1=pie 2=chord 3=arcto(open) */
        private void appendArc(float l, float t, float r, float b,
                               float sxp, float syp, float exp, float eyp, int kind) {
            float cx = (l + r) / 2f, cy = (t + b) / 2f;
            float a0 = (float) Math.toDegrees(Math.atan2(-(syp - cy), sxp - cx));
            float a1 = (float) Math.toDegrees(Math.atan2(-(eyp - cy), exp - cx));
            float sweep = a1 - a0;
            if (sweep <= 0) sweep += 360f;
            float rx = (r - l) / 2f, ry = (b - t) / 2f;
            int n = Math.max(8, (int) Math.ceil(sweep / 10f));
            if (kind == 1) {   /* pie: start at the center */
                curPath.moveTo(cx * sx, cy * sy);
                curPath.lineTo((float) (cx + rx * Math.cos(Math.toRadians(a0))) * sx,
                        (float) (cy - ry * Math.sin(Math.toRadians(a0))) * sy);
            } else {
                curPath.moveTo((float) (cx + rx * Math.cos(Math.toRadians(a0))) * sx,
                        (float) (cy - ry * Math.sin(Math.toRadians(a0))) * sy);
            }
            for (int i = 1; i <= n; i++) {
                double a = Math.toRadians(a0 + sweep * i / (double) n);
                curPath.lineTo((float) (cx + rx * Math.cos(a)) * sx,
                        (float) (cy - ry * Math.sin(a)) * sy);
            }
            if (kind == 1 || kind == 2) {
                curPath.close();
                figOpen = false;
            } else {
                figOpen = true;
            }
        }

        public void text(String s, float x, float y, float[] clipRect, int[] dx, boolean rtl, int align) {
            if (s == null || s.length() == 0) return;
            int hLog = font.height == 0 ? -12 : font.height;
            float sizePx = Math.max(6f, Math.min(600f, Math.abs(hLog) * Math.max(sx, 0.05f)));
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setTextSize(sizePx);
            p.setColor(textColor);
            if (font.weight >= 700) p.setFakeBoldText(true);
            if (font.italic) p.setTextSkewX(-0.25f);
            /* v1.27 FIX: SETTEXTALIGN honored — right/center-aligned metafile
               text used to always draw from the left edge */
            float drawX = x * sx;
            float tw = p.measureText(s);
            int hAlign = align & 0x6;   /* TA_RIGHT=2, TA_CENTER=6 */
            if (rtl) {
                if (hAlign == 0) drawX -= tw;          /* RTL default = right-anchored */
                else if (hAlign == 2) drawX -= tw / 2f;
            } else {
                if (hAlign == 2) drawX -= tw;
                else if (hAlign == 6) drawX -= tw / 2f;
            }
            /* v1.35: SETBKMODE OPAQUE — paint the label's background box
               (GDI default is OPAQUE; converter text over colored shapes
               needs it to stay legible) */
            if (bkOpaque) {
                Paint bp = new Paint();
                bp.setColor(bkCol);
                float asc = -p.ascent();
                c.drawRect(drawX, y * sy - asc, drawX + tw, y * sy + p.descent(), bp);
            }
            c.drawText(s, drawX, y * sy - p.ascent(), p);
            if (font.underline) {
                Paint up = new Paint(Paint.ANTI_ALIAS_FLAG);
                up.setStyle(Paint.Style.STROKE);
                up.setColor(textColor);
                up.setStrokeWidth(Math.max(1f, sizePx / 18f));
                float uy = y * sy - p.ascent() + p.descent() * 0.2f;
                c.drawLine(drawX, uy, drawX + tw, uy, up);
            }
        }

        public void dib(float xd, float yd, float wd, float hd, float xs, float ys, float ws, float hs, Dib img) {
            if (img == null || img.w <= 0) return;
            Bitmap bmp = Bitmap.createBitmap(img.w, img.h, Bitmap.Config.ARGB_8888);
            bmp.setPixels(img.argb, 0, img.w, 0, 0, img.w, img.h);
            RectF dst = new RectF(xd * sx, yd * sy, (xd + wd) * sx, (yd + hd) * sy);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            /* v1.27 FIX: honor the SOURCE rectangle (sprite-sheet/cropped
               blits drew the whole bitmap instead of the sub-rect) */
            android.graphics.Rect src;
            if (xs != 0 || ys != 0 || ws != img.w || hs != img.h) {
                src = new android.graphics.Rect(Math.round(xs), Math.round(ys),
                        Math.round(xs + ws), Math.round(ys + hs));
                if (src.right > img.w) src.right = img.w;
                if (src.bottom > img.h) src.bottom = img.h;
                if (src.left < 0) src.left = 0;
                if (src.top < 0) src.top = 0;
                if (src.right <= src.left || src.bottom <= src.top) src = null;
            } else {
                src = null;
            }
            c.drawBitmap(bmp, src, dst, p);
        }

        private Path pathOf(float[][] pts, boolean closed) {
            Path ph = new Path();
            ph.setFillType(evenOdd ? Path.FillType.EVEN_ODD : Path.FillType.WINDING);
            ph.moveTo(pts[0][0] * sx, pts[0][1] * sy);
            for (int i = 1; i < pts.length; i++) ph.lineTo(pts[i][0] * sx, pts[i][1] * sy);
            if (closed) ph.close();
            return ph;
        }

        private void fillStroke(Path ph, boolean closed) {
            /* v1.29 FIX: honor `closed` — GDI Polyline/PolyPolyline must not
               fill; the parameter existed but was ignored. */
            if (closed && !brush.isNull()) c.drawPath(ph, brushPaint());
            if (!pen.isNull()) {
                Paint pp = penPaint();
                pp.setStyle(Paint.Style.STROKE);
                c.drawPath(ph, pp);
            }
        }

        private Paint penPaint() {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.STROKE);
            p.setColor(pen.color);
            float w = Math.max(1f, Math.max(1, pen.width) * sx);
            switch (pen.style) {
                case 1: p.setPathEffect(new android.graphics.DashPathEffect(new float[]{w * 4, w * 3}, 0)); break;
                case 2: p.setPathEffect(new android.graphics.DashPathEffect(new float[]{w, w * 2}, 0)); break;
                case 3: p.setPathEffect(new android.graphics.DashPathEffect(new float[]{w * 4, w * 2, w, w * 2}, 0)); break;
                case 4: p.setPathEffect(new android.graphics.DashPathEffect(new float[]{w * 4, w * 2, w, w * 2, w, w * 2}, 0)); break;
                default: break;
            }
            p.setStrokeWidth(w);
            return p;
        }

        private Paint brushPaint() {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            if (brush.style == 2) {  /* hatch — translucent approximation */
                int a = (brush.color >>> 25) << 24;   /* half alpha */
                p.setColor((brush.color & 0x00FFFFFF) | a);
            } else {
                p.setColor(brush.color);
            }
            return p;
        }
    }

    /* ----------------------------------------------------------- byte reader */

    /* ======================================================================
       v1.36: EMF+ (GDI+) — records embedded in EMR_COMMENT (type 70).

       Office/Illustrator/browser-copied figures are "EMF+ Dual": the raster
       photo and the modern vector drawing live in EMF+ records inside
       EMR_COMMENT payloads (signature 0x2B46494C "EMF+"); the GDI records
       are only a fallback duplicate. Before v1.36 the comment payload was
       skipped entirely, so the photo half of textbook figures rendered as
       empty white — the "half image" bug.
       ====================================================================== */

    /* one entry of the 64-slot EMF+ object table */
    static final class PlusObj {
        static final int BRUSH = 1, PEN = 2, PATH = 3, REGION = 4, IMAGE = 5,
                FONT = 6, STRFMT = 7, IMGATTR = 8, CAP = 9;
        int kind;
        /* brush */
        int color = 0xFF000000, color2 = 0xFF000000;
        int brushType;              /* 0 solid 1 hatch 2 texture 3 pathGrad 4 linearGrad */
        float[] gradRect;           /* linear gradient axis rect */
        byte[] textureEnc;          /* texture brush image bytes */
        Dib textureDib;
        /* pen */
        float penW = 1f;
        int penDash;                /* 0 solid 1 dash 2 dot 3 dashdot 4 dashdotdot */
        /* path */
        float[][] pts;
        byte[] types;
        /* image */
        byte[] imgEnc;              /* compressed PNG/JPEG/GIF bytes */
        Dib imgDib;
        float imgW, imgH;
        /* font */
        float emSize = 12f;
        int sizeUnit, fStyle;
        String family = "";
        /* region */
        float[] regionRect;         /* only rect regions modeled */
    }

    /* walker state: object table + world/page transform + GDI mute */
    static final class PlusState {
        final Ops real;                     /* the true sink */
        final PlusObj[] table = new PlusObj[64];
        final java.io.ByteArrayOutputStream[] cont = new java.io.ByteArrayOutputStream[64];
        /* per-instance save/container stack — static would clash between the
           main canvas, filmstrip thumbnails and PDF export rendering in parallel */
        final java.util.ArrayDeque<float[]> saves = new java.util.ArrayDeque<>();
        float[] world = matId();            /* [m11 m12 m21 m22 dx dy] */
        float pageScale = 1f;
        int pageUnit = 1;                   /* UnitTypeDisplay */
        int dpi = 96;
        boolean sawHeader, dual;
        boolean haveDC;                     /* GetDC: GDI records are additive */

        PlusState(Ops real) { this.real = real; }

        Ops sink() { return (sawHeader && !haveDC) ? MUTE : real; }

        void pushSave() {
            saves.addLast(new float[]{world[0], world[1], world[2], world[3],
                    world[4], world[5], pageScale, pageUnit, dpi});
            real.save();
        }

        void popSave() {
            if (!saves.isEmpty()) {
                float[] s = saves.removeLast();
                world = new float[]{s[0], s[1], s[2], s[3], s[4], s[5]};
                pageScale = s[6];
                pageUnit = (int) s[7];
                dpi = (int) s[8];
                plusApplyTransform(this);
            }
            real.restore();
        }
    }

    /* no-op sink used to suppress the duplicated GDI half of dual EMF+ */
    static final class MuteOps implements Ops {
        public void window(int a, int b, int c, int d) { }
        public void pen(Pen p) { }
        public void brush(Brush b) { }
        public void font(MFont f) { }
        public void textColor(int c) { }
        public void bkColor(int c) { }
        public void fillEvenOdd(boolean e) { }
        public void world(float[] m) { }
        public void modifyWorld(float[] m, int mode) { }
        public void save() { }
        public void restore() { }
        public void moveTo(float x, float y) { }
        public void lineTo(float x, float y) { }
        public void bezier(float[][] p, boolean t) { }
        public void polyTo(float[][] p) { }
        public void poly(float[][] p, boolean c) { }
        public void polyPoly(float[][][] p, boolean c) { }
        public void rect(float l, float t, float r, float b, float rx, float ry) { }
        public void ellipse(float l, float t, float r, float b) { }
        public void pieChord(float l, float t, float r, float b, float sx, float sy, float ex, float ey, int k) { }
        public void text(String s, float x, float y, float[] cr, int[] dx, boolean rtl, int al) { }
        public void dib(float xd, float yd, float wd, float hd, float xs, float ys, float ws, float hs, Dib img) { }
        public void beginPath() { }
        public void endPath() { }
        public void closeFigure() { }
        public void strokePath() { }
        public void fillPath() { }
        public void strokeAndFillPath() { }
        public void abortPath() { }
        public void bkMode(int m) { }
        public void clipIntersect(float l, float t, float r, float b) { }
        public void clipReset() { }
        public void angleArc(float cx, float cy, float rad, float s, float sw) { }
        public void arcTo(float l, float t, float r, float b, float sx, float sy, float ex, float ey) { }
        public void image(byte[] enc, Dib dib, float[] pts, float sl, float st, float sr, float sb) { }
    }

    static final MuteOps MUTE = new MuteOps();

    /* ---- 2x3 affine matrix helpers, XForm order [m11 m12 m21 m22 dx dy]:
       x' = m11*x + m21*y + dx ; y' = m12*x + m22*y + dy ---- */

    static float[] matId() { return new float[]{1f, 0f, 0f, 1f, 0f, 0f}; }

    static float[] matMul(float[] a, float[] b) {
        /* result = a ∘ b (apply b first, then a) */
        return new float[]{
                a[0] * b[0] + a[2] * b[1], a[1] * b[0] + a[3] * b[1],
                a[0] * b[2] + a[2] * b[3], a[1] * b[2] + a[3] * b[3],
                a[0] * b[4] + a[2] * b[5] + a[4], a[1] * b[4] + a[3] * b[5] + a[5]};
    }

    static float[] matTranslate(float dx, float dy) {
        return new float[]{1f, 0f, 0f, 1f, dx, dy};
    }

    static float[] matScale(float sx, float sy) {
        return new float[]{sx, 0f, 0f, sy, 0f, 0f};
    }

    static float[] matRotate(float deg) {
        double a = Math.toRadians(deg);
        float c = (float) Math.cos(a), s = (float) Math.sin(a);
        return new float[]{c, s, -s, c, 0f, 0f};
    }

    static float matX(float[] m, float x, float y) { return m[0] * x + m[2] * y + m[4]; }
    static float matY(float[] m, float x, float y) { return m[1] * x + m[3] * y + m[5]; }

    /* page-unit → logical-72dpi conversion factor (the RenderOps window is
       in 72dpi units derived from the EMF frame) */
    static float plusUnitK(int unit, int dpi) {
        switch (unit) {
            case 3: return 1f;                 /* Point */
            case 4: return 72f;                /* Inch */
            case 5: return 72f / 300f;         /* Document (1/300") */
            case 6: return 72f / 25.4f;        /* Millimeter */
            default: return 72f / Math.max(72, dpi);  /* Pixel/Display/World */
        }
    }

    /* push the current EMF+ world×page transform onto the ops canvas */
    static void plusApplyTransform(PlusState ps) {
        float k = ps.pageScale * plusUnitK(ps.pageUnit, ps.dpi);
        ps.real.world(new float[]{
                ps.world[0] * k, ps.world[1] * k, ps.world[2] * k,
                ps.world[3] * k, ps.world[4] * k, ps.world[5] * k});
    }

    /* ---- point readers: P=0x800 (byte,relative) C=0x4000 (int16) F=float ---- */

    static float[][] plusPts(Rd r, int off, int end, int count, int flags) {
        if (count <= 0 || count > 8192) return null;
        float[][] out = new float[count][];
        if ((flags & 0x800) != 0) {              /* EmfPlusPointR7 — relative */
            if (off + count * 2 > end) return null;
            float px = 0f, py = 0f;
            for (int i = 0; i < count; i++) {
                px += (byte) r.u8(off + i * 2);
                py += (byte) r.u8(off + i * 2 + 1);
                out[i] = new float[]{px, py};
            }
        } else if ((flags & 0x4000) != 0) {      /* EmfPlusPoint — int16 */
            if (off + count * 4 > end) return null;
            for (int i = 0; i < count; i++)
                out[i] = new float[]{r.i16(off + i * 4), r.i16(off + i * 4 + 2)};
        } else {                                 /* EmfPlusPointF — float */
            if (off + count * 8 > end) return null;
            for (int i = 0; i < count; i++)
                out[i] = new float[]{r.f32(off + i * 8), r.f32(off + i * 8 + 4)};
        }
        return out;
    }

    /* rect reader: C flag → EmfPlusRect (4 x int16) else EmfPlusRectF */
    static float[] plusRect(Rd r, int off, int end, int flags) {
        if ((flags & 0x4000) != 0) {
            if (off + 8 > end) return null;
            return new float[]{r.i16(off), r.i16(off + 2), r.i16(off + 4), r.i16(off + 6)};
        }
        if (off + 16 > end) return null;
        return new float[]{r.f32(off), r.f32(off + 4), r.f32(off + 8), r.f32(off + 12)};
    }

    static int argbToGdi(int plus) {
        /* ARGB 0xAARRGGBB is already the Android convention */
        return plus;
    }

    /* ======================================================================
       the EMF+ record walker — d[start..end) holds the payload AFTER the
       4-byte "EMF+" signature of an EMR_COMMENT record.
       ====================================================================== */
    static void walkEmfPlus(byte[] d, int start, int end, PlusState ps) {
        Rd r = new Rd(d);
        int p = start;
        while (p + 12 <= end && p + 12 <= d.length) {
            int type = r.u16(p);
            int flags = r.u16(p + 2);
            long size = r.u32(p + 4) & 0xFFFFFFFFL;
            long dataSize = r.u32(p + 8) & 0xFFFFFFFFL;
            if (size < 12 || dataSize > size - 12 || p + size > end) break;
            int par = p + 12;
            int parEnd = (int) Math.min(end, p + size);
            try {
                plusRecord(d, r, par, parEnd, type, flags, (int) dataSize, ps);
            } catch (Throwable ignored) {
                /* one malformed record must not kill the whole figure */
            }
            p += size;
        }
    }

    static void plusRecord(byte[] d, Rd r, int par, int parEnd, int type, int flags,
                           int dataSize, PlusState ps) {
        Ops ops = ps.real;
        switch (type) {
            case 0x4001: {  /* EmfPlusHeader */
                if (dataSize >= 12) {
                    ps.dpi = r.i32(par + 8) > 0 ? r.i32(par + 8) : 96;
                }
                ps.sawHeader = true;
                ps.dual = (flags & 1) != 0;
                break;
            }
            case 0x4002:  /* EmfPlusEndOfFile */
                break;
            case 0x4004:  /* EmfPlusGetDC — following GDI records are additive */
                ps.haveDC = false;
                break;
            /* ---------------- state ---------------- */
            case 0x4025: {  /* Save */
                ps.pushSave();
                break;
            }
            case 0x4026: {  /* Restore */
                ps.popSave();
                break;
            }
            case 0x4027:   /* BeginContainer(+NoParams) — approximated as save */
            case 0x4028: {
                ps.pushSave();
                break;
            }
            case 0x4029: {  /* EndContainer */
                ps.popSave();
                break;
            }
            /* ---------------- transforms ---------------- */
            case 0x402A: {  /* SetWorldTransform */
                if (dataSize >= 24) {
                    ps.world = new float[]{r.f32(par), r.f32(par + 4), r.f32(par + 8),
                            r.f32(par + 12), r.f32(par + 16), r.f32(par + 20)};
                    plusApplyTransform(ps);
                }
                break;
            }
            case 0x402B: {  /* ResetWorldTransform */
                ps.world = matId();
                plusApplyTransform(ps);
                break;
            }
            case 0x402C: {  /* MultiplyWorldTransform */
                if (dataSize >= 24) {
                    float[] m = new float[]{r.f32(par), r.f32(par + 4), r.f32(par + 8),
                            r.f32(par + 12), r.f32(par + 16), r.f32(par + 20)};
                    /* flags bits 0-1: 0=prepend(new ∘ old), 1=append(old ∘ new) */
                    ps.world = (flags & 1) == 0 ? matMul(m, ps.world) : matMul(ps.world, m);
                    plusApplyTransform(ps);
                }
                break;
            }
            case 0x402D: {  /* TranslateWorldTransform */
                if (dataSize >= 8) {
                    float[] m = matTranslate(r.f32(par), r.f32(par + 4));
                    ps.world = (flags & 1) == 0 ? matMul(m, ps.world) : matMul(ps.world, m);
                    plusApplyTransform(ps);
                }
                break;
            }
            case 0x402E: {  /* ScaleWorldTransform */
                if (dataSize >= 8) {
                    float[] m = matScale(r.f32(par), r.f32(par + 4));
                    ps.world = (flags & 1) == 0 ? matMul(m, ps.world) : matMul(ps.world, m);
                    plusApplyTransform(ps);
                }
                break;
            }
            case 0x402F: {  /* RotateWorldTransform */
                if (dataSize >= 4) {
                    float[] m = matRotate(r.f32(par));
                    ps.world = (flags & 1) == 0 ? matMul(m, ps.world) : matMul(ps.world, m);
                    plusApplyTransform(ps);
                }
                break;
            }
            case 0x4030: {  /* SetPageTransform: flags = UnitType, data = PageScale */
                if (dataSize >= 4) {
                    ps.pageUnit = flags & 0xFF;
                    ps.pageScale = r.f32(par);
                    if (ps.pageScale <= 0f || Float.isNaN(ps.pageScale)) ps.pageScale = 1f;
                    plusApplyTransform(ps);
                }
                break;
            }
            /* ---------------- clipping ---------------- */
            case 0x4031:  /* ResetClip */
                ops.clipReset();
                break;
            case 0x4032: {  /* SetClipRect: flags = combine mode */
                float[] rc = plusRect(r, par, parEnd, flags & ~0xF);
                if (rc != null) {
                    /* modes: 0 replace 1 intersect 2 union 3 xor 4 exclude 5 complement */
                    int mode = flags & 0xF;
                    if (mode == 0) ops.clipReset();
                    if (mode == 0 || mode == 1)
                        ops.clipIntersect(rc[0], rc[1], rc[0] + rc[2], rc[1] + rc[3]);
                }
                break;
            }
            case 0x4033:  /* SetClipPath — modeled as reset (rare in figures) */
            case 0x4034:  /* SetClipRegion */
                break;
            case 0x4035:  /* OffsetClip */
                break;
            /* ---------------- object table ---------------- */
            case 0x4008: {  /* EmfPlusObject */
                int slot = flags & 0xFF;
                boolean continued = (flags & 0x8000) != 0;
                if (slot >= 64) break;
                if (continued) {
                    if (ps.cont[slot] == null) ps.cont[slot] = new java.io.ByteArrayOutputStream();
                    int len = Math.max(0, parEnd - par);
                    ps.cont[slot].write(d, par, Math.min(len, d.length - par));
                    break;
                }
                byte[] body;
                int bodyLen = parEnd - par;
                java.io.ByteArrayOutputStream prev = ps.cont[slot];
                if (prev != null && prev.size() > 0) {
                    int plen = Math.max(0, Math.min(bodyLen, d.length - par));
                    prev.write(d, par, plen);
                    body = prev.toByteArray();
                    bodyLen = body.length;
                    ps.cont[slot] = null;
                    /* parse from a scratch copy */
                    parsePlusObject(new Rd(body), 0, bodyLen, slot, (flags >> 8) & 0xFF, ps);
                } else {
                    parsePlusObject(r, par, parEnd, slot, (flags >> 8) & 0xFF, ps);
                }
                break;
            }
            /* ---------------- drawing ---------------- */
            case 0x400A: {  /* FillRects: BrushID, Count, rects */
                if (dataSize >= 8) {
                    int br = brushFor(ps, r.i32(par), flags);
                    int count = r.i32(par + 4);
                    int off = par + 8;
                    ops.brush(solidBrush(br));
                    for (int i = 0; i < count && off < parEnd; i++) {
                        float[] rc = plusRect(r, off, parEnd, flags);
                        if (rc == null) break;
                        off += (flags & 0x4000) != 0 ? 8 : 16;
                        ops.rect(rc[0], rc[1], rc[0] + rc[2], rc[1] + rc[3], 0, 0);
                    }
                }
                break;
            }
            case 0x400B: {  /* DrawRects: PenID, Count, rects */
                if (dataSize >= 8) {
                    PlusObj pen = penFor(ps, r.i32(par), flags);
                    int count = r.i32(par + 4);
                    int off = par + 8;
                    ops.pen(plusPen(pen));
                    for (int i = 0; i < count && off < parEnd; i++) {
                        float[] rc = plusRect(r, off, parEnd, flags);
                        if (rc == null) break;
                        off += (flags & 0x4000) != 0 ? 8 : 16;
                        ops.rect(rc[0], rc[1], rc[0] + rc[2], rc[1] + rc[3], 0, 0);
                    }
                }
                break;
            }
            case 0x400C: {  /* FillPolygon: BrushID, Count, points */
                if (dataSize >= 8) {
                    int br = brushFor(ps, r.i32(par), flags);
                    int count = r.i32(par + 4);
                    float[][] pts = plusPts(r, par + 8, parEnd, count, flags);
                    if (pts != null) {
                        ops.brush(solidBrush(br));
                        ops.poly(pts, true);
                    }
                }
                break;
            }
            case 0x400D: {  /* DrawLines: PenID, Count, points */
                if (dataSize >= 8) {
                    PlusObj pen = penFor(ps, r.i32(par), flags);
                    int count = r.i32(par + 4);
                    float[][] pts = plusPts(r, par + 8, parEnd, count, flags);
                    if (pts != null) {
                        ops.pen(plusPen(pen));
                        ops.poly(pts, false);
                    }
                }
                break;
            }
            case 0x400E:  /* FillEllipse: BrushID, Rect */
            case 0x400F: {  /* DrawEllipse: PenID, Rect */
                if (dataSize >= 4) {
                    boolean fill = type == 0x400E;
                    if (fill) ops.brush(solidBrush(brushFor(ps, r.i32(par), flags)));
                    else ops.pen(plusPen(penFor(ps, r.i32(par), flags)));
                    float[] rc = plusRect(r, par + 4, parEnd, flags);
                    if (rc != null) ops.ellipse(rc[0], rc[1], rc[0] + rc[2], rc[1] + rc[3]);
                }
                break;
            }
            case 0x4010:  /* FillPie: BrushID, Start, Sweep, Rect */
            case 0x4011: {  /* DrawPie: PenID, Start, Sweep, Rect */
                if (dataSize >= 12) {
                    boolean fill = type == 0x4010;
                    if (fill) ops.brush(solidBrush(brushFor(ps, r.i32(par), flags)));
                    else ops.pen(plusPen(penFor(ps, r.i32(par), flags)));
                    float start = r.f32(par + 4), sweep = r.f32(par + 8);
                    float[] rc = plusRect(r, par + 12, parEnd, flags);
                    if (rc != null) {
                        float cx = rc[0] + rc[2] / 2f, cy = rc[1] + rc[3] / 2f;
                        double a0 = Math.toRadians(start), a1 = Math.toRadians(start + sweep);
                        /* GDI+ angles are clockwise in y-down space — the
                           ops pieChord expects y-up math, so mirror */
                        float sx = (float) (cx + Math.cos(a0) * rc[2] / 2f);
                        float sy = (float) (cy + Math.sin(a0) * rc[3] / 2f);
                        float ex = (float) (cx + Math.cos(a1) * rc[2] / 2f);
                        float ey = (float) (cy + Math.sin(a1) * rc[3] / 2f);
                        ops.pieChord(rc[0], rc[1], rc[0] + rc[2], rc[1] + rc[3],
                                sx, sy, ex, ey, fill ? 1 : 0);
                    }
                }
                break;
            }
            case 0x4012: {  /* DrawArc: Start, Sweep, Rect (data, no ids) */
                if (dataSize >= 8) {
                    float start = r.f32(par), sweep = r.f32(par + 4);
                    float[] rc = plusRect(r, par + 8, parEnd, flags);
                    if (rc != null) {
                        float cx = rc[0] + rc[2] / 2f, cy = rc[1] + rc[3] / 2f;
                        double a0 = Math.toRadians(start), a1 = Math.toRadians(start + sweep);
                        float sx = (float) (cx + Math.cos(a0) * rc[2] / 2f);
                        float sy = (float) (cy + Math.sin(a0) * rc[3] / 2f);
                        float ex = (float) (cx + Math.cos(a1) * rc[2] / 2f);
                        float ey = (float) (cy + Math.sin(a1) * rc[3] / 2f);
                        ops.pieChord(rc[0], rc[1], rc[0] + rc[2], rc[1] + rc[3],
                                sx, sy, ex, ey, 0);
                    }
                }
                break;
            }
            case 0x4013: {  /* FillRegion */
                if (dataSize >= 4) {
                    int br = brushFor(ps, r.i32(par), flags);
                    int region = flags & 0xFF;
                    PlusObj reg = region < 64 ? ps.table[region] : null;
                    if (reg != null && reg.regionRect != null) {
                        ops.brush(solidBrush(br));
                        ops.rect(reg.regionRect[0], reg.regionRect[1],
                                reg.regionRect[0] + reg.regionRect[2],
                                reg.regionRect[1] + reg.regionRect[3], 0, 0);
                    }
                }
                break;
            }
            case 0x4014: {  /* FillPath: BrushID + Path object */
                if (dataSize >= 4) {
                    int br = brushFor(ps, r.i32(par), flags);
                    int pathId = flags & 0xFF;
                    PlusObj po = pathId < 64 ? ps.table[pathId] : null;
                    if (po != null && po.pts != null) {
                        ops.brush(solidBrush(br));
                        playPlusPath(ops, po);
                        ops.fillPath();
                    }
                }
                break;
            }
            case 0x4015: {  /* DrawPath: PenID + Path object */
                if (dataSize >= 4) {
                    PlusObj pen = penFor(ps, r.i32(par), flags);
                    int pathId = flags & 0xFF;
                    PlusObj po = pathId < 64 ? ps.table[pathId] : null;
                    if (po != null && po.pts != null) {
                        ops.pen(plusPen(pen));
                        playPlusPath(ops, po);
                        ops.strokePath();
                    }
                }
                break;
            }
            case 0x4016:  /* FillClosedCurve: BrushID, Count, pts (tension @4) */
            case 0x4017: {  /* DrawClosedCurve: PenID, Tension, Count, pts */
                boolean fill = type == 0x4016;
                int base = fill ? 4 : 8;
                if (dataSize >= base + 4) {
                    if (fill) ops.brush(solidBrush(brushFor(ps, r.i32(par), flags)));
                    else ops.pen(plusPen(penFor(ps, r.i32(par), flags)));
                    int count = r.i32(par + base - 4);
                    float[][] pts = plusPts(r, par + base, parEnd, count, flags);
                    if (pts != null) ops.poly(pts, true);
                }
                break;
            }
            case 0x4018: {  /* DrawCurve: PenID, Tension, Count, pts (open) */
                if (dataSize >= 12) {
                    ops.pen(plusPen(penFor(ps, r.i32(par), flags)));
                    int count = r.i32(par + 8);
                    float[][] pts = plusPts(r, par + 12, parEnd, count, flags);
                    if (pts != null) ops.poly(pts, false);
                }
                break;
            }
            case 0x4019: {  /* DrawBeziers: PenID, Count, pts */
                if (dataSize >= 8) {
                    ops.pen(plusPen(penFor(ps, r.i32(par), flags)));
                    int count = r.i32(par + 4);
                    float[][] pts = plusPts(r, par + 8, parEnd, count, flags);
                    if (pts != null && pts.length >= 4) ops.bezier(pts, false);
                }
                break;
            }
            case 0x401A: {  /* DrawImage: flags low byte = image slot */
                int imgSlot = flags & 0xFF;
                PlusObj img = imgSlot < 64 ? ps.table[imgSlot] : null;
                if (img == null) break;
                /* ImageAttributesID@0 SrcUnit@4 SrcRect@8 (4 floats) dst rect */
                if (dataSize < 8 + 16) break;
                float sl = r.f32(par + 8), st = r.f32(par + 12);
                float sw = r.f32(par + 16), sh = r.f32(par + 20);
                float[] rc = plusRect(r, par + 24, parEnd, flags & ~0x4000);
                if (rc == null) break;
                float[] pts = new float[]{rc[0], rc[1], rc[0] + rc[2], rc[1],
                        rc[0], rc[1] + rc[3]};
                plusImage(ops, img, pts, sl, st, sl + sw, st + sh);
                break;
            }
            case 0x401B: {  /* DrawImagePoints: flags low byte = image slot */
                int imgSlot = flags & 0xFF;
                PlusObj img = imgSlot < 64 ? ps.table[imgSlot] : null;
                if (img == null) break;
                /* ImageAttributesID@0 SrcUnit@4 SrcRect@8 count@24 pts@28 */
                if (dataSize < 28) break;
                float sl = r.f32(par + 8), st = r.f32(par + 12);
                float sw = r.f32(par + 16), sh = r.f32(par + 20);
                int count = r.i32(par + 24);
                if (count < 3) break;
                float[][] pp = plusPts(r, par + 28, parEnd, 3, flags);
                if (pp == null) break;
                float[] pts = new float[]{pp[0][0], pp[0][1], pp[1][0], pp[1][1],
                        pp[2][0], pp[2][1]};
                plusImage(ops, img, pts, sl, st, sl + sw, st + sh);
                break;
            }
            case 0x401C: {  /* DrawString: flags low = font slot, S = brush color */
                int fontSlot = flags & 0xFF;
                PlusObj fo = fontSlot < 64 ? ps.table[fontSlot] : null;
                if (dataSize < 12) break;
                int brushId = r.i32(par);
                /* FormatID@4 Length@8 LayoutRect@12 (RectF) StringData */
                int len = r.i32(par + 8);
                float[] rc = plusRect(r, par + 12, parEnd, 0);
                int strOff = par + 28;
                if (rc == null || len <= 0 || strOff + len * 2 > parEnd + 2) break;
                StringBuilder sb = new StringBuilder(len);
                for (int i = 0; i < len; i++) {
                    int c = r.u16(strOff + i * 2);
                    if (c == 0) break;
                    sb.append((char) c);
                }
                int color = (flags & 0x8000) != 0 ? brushId
                        : (brushId >= 0 && brushId < 64 && ps.table[brushId] != null
                        && ps.table[brushId].kind == PlusObj.BRUSH
                        ? ps.table[brushId].color : 0xFF000000);
                MFont mf = new MFont();
                if (fo != null && fo.family.length() > 0) {
                    mf.face = fo.family;
                    float sz = fo.emSize;
                    switch (fo.sizeUnit) {
                        case 4: sz *= 72f; break;        /* inch */
                        case 6: sz *= 72f / 25.4f; break; /* mm */
                        default: break;                   /* pt / px ≈ pt */
                    }
                    mf.height = -(int) Math.max(6f, sz);
                    mf.weight = (fo.fStyle & 1) != 0 ? 700 : 400;
                    mf.italic = (fo.fStyle & 2) != 0;
                    mf.underline = (fo.fStyle & 4) != 0;
                    mf.strike = (fo.fStyle & 8) != 0;
                }
                ops.font(mf);
                ops.textColor(argbToGdi(color));
                ops.text(sb.toString(), rc[0], rc[1] + Math.abs(mf.height) * 0.92f,
                        null, null, false, 0);
                break;
            }
            default:
                break;   /* property records (antialias, interpolation, ..) */
        }
    }

    /* resolve a brush reference: S flag → the DWORD IS an ARGB color,
       else it's an object-table slot */
    static int brushFor(PlusState ps, int id, int flags) {
        if ((flags & 0x8000) != 0) return id;
        PlusObj o = id >= 0 && id < 64 ? ps.table[id] : null;
        return o != null && o.kind == PlusObj.BRUSH ? o.color : 0xFF000000;
    }

    static PlusObj penFor(PlusState ps, int id, int flags) {
        if ((flags & 0x8000) != 0) {
            PlusObj p = new PlusObj();
            p.kind = PlusObj.PEN;
            p.color = id;
            p.penW = 1f;
            return p;
        }
        PlusObj o = id >= 0 && id < 64 ? ps.table[id] : null;
        return o != null && o.kind == PlusObj.PEN ? o : null;
    }

    static Brush solidBrush(int argb) {
        Brush b = new Brush();
        b.style = 0;
        b.color = argb == 0 ? 0xFF000000 : argb;
        return b;
    }

    static Pen plusPen(PlusObj po) {
        Pen p = new Pen();
        if (po == null) {
            p.style = 0;
            p.width = 1;
            p.color = 0xFF000000;
            return p;
        }
        p.style = po.penDash;
        p.width = Math.max(1, (int) Math.ceil(po.penW <= 0 ? 1f : po.penW));
        p.color = po.color == 0 ? 0xFF000000 : po.color;
        return p;
    }

    /* append an EMF+ path object into the current GDI path bracket */
    static void playPlusPath(Ops ops, PlusObj po) {
        if (po.pts == null || po.types == null) return;
        ops.beginPath();
        int i = 0;
        while (i < po.pts.length) {
            int t = po.types[i] & 0x07;
            if (i == 0 || t == 0) ops.moveTo(po.pts[i][0], po.pts[i][1]);
            else if (t == 1) ops.lineTo(po.pts[i][0], po.pts[i][1]);
            else if (t == 3 && i + 2 < po.pts.length) {
                ops.bezier(new float[][]{po.pts[i], po.pts[i + 1], po.pts[i + 2],
                        po.pts[i + 2]}, true);
                i += 2;
            }
            if ((po.types[i] & 0x20) != 0) ops.closeFigure();
            i++;
        }
        ops.endPath();
    }

    static void plusImage(Ops ops, PlusObj img, float[] pts6, float sl, float st, float sr, float sb) {
        if (img == null) return;
        if (img.imgEnc != null && img.imgEnc.length > 0) {
            ops.image(img.imgEnc, null, pts6, sl, st, sr, sb);
        } else if (img.imgDib != null) {
            ops.image(null, img.imgDib, pts6, sl, st, sr, sb);
        }
    }

    /* parse one object-table entry from record data [off..end).
       v1.36 FIX: the object KIND comes from the record FLAGS (high byte),
       NOT from the data — the data's second DWORD means something different
       per structure (BrushType / PathPointCount / ImageDataType / EmSize...),
       so reading it as a discriminant misparsed everything but brushes. */
    static void parsePlusObject(Rd r, int off, int end, int slot, int objKind, PlusState ps) {
        if (end - off < 4) return;
        PlusObj o = new PlusObj();
        int body = off + 4;   /* after Version */
        switch (objKind) {
            case 1: {  /* brush: BrushType@0 then BrushData */
                o.kind = PlusObj.BRUSH;
                if (end - body < 4) break;
                o.brushType = r.i32(body);
                int bd = body + 4;
                if (o.brushType == 0 && end - bd >= 4) {
                    o.color = argbToGdi(r.i32(bd));
                } else if (o.brushType == 1 && end - bd >= 12) {
                    o.color = argbToGdi(r.i32(bd + 4));       /* fore */
                } else if (o.brushType == 2 && end - bd >= 8) {
                    /* texture: flags@0 wrap@4 image@8 — colors come from image */
                    o.textureEnc = plusEmbeddedImage(r, bd + 8, end);
                } else if (o.brushType == 4 && end - bd >= 32) {
                    /* LinearGradient: flags@0 wrap@4 rect@8 colors@24,28 */
                    o.color = argbToGdi(r.i32(bd + 24));
                    o.color2 = argbToGdi(r.i32(bd + 28));
                    o.gradRect = new float[]{r.f32(bd + 8), r.f32(bd + 12),
                            r.f32(bd + 16), r.f32(bd + 20)};
                }
                break;
            }
            case 2: {  /* pen: PenDataFlags@0 Width@4 optional... embedded Brush */
                o.kind = PlusObj.PEN;
                if (end - body < 8) break;
                int penFlags = r.i32(body);
                o.penW = r.f32(body + 4);
                int q = body + 8;
                if ((penFlags & 0x001) != 0) q += 24;    /* Transform */
                if ((penFlags & 0x002) != 0) q += 4;     /* StartCap */
                if ((penFlags & 0x004) != 0) q += 4;     /* EndCap */
                if ((penFlags & 0x008) != 0) q += 4;     /* Join */
                if ((penFlags & 0x010) != 0) q += 4;     /* MiterLimit */
                if ((penFlags & 0x020) != 0) {           /* LineStyle */
                    if (end - q >= 4) o.penDash = r.i32(q);
                    q += 4;
                }
                if ((penFlags & 0x040) != 0) q += 4;     /* DashedLineCap */
                if ((penFlags & 0x080) != 0) q += 4;     /* DashedLineOffset */
                if ((penFlags & 0x100) != 0) {           /* DashedLine */
                    if (end - q >= 4) q += 4 + r.i32(q) * 4;
                }
                if ((penFlags & 0x200) != 0) q += 4;     /* NonCenter */
                if ((penFlags & 0x400) != 0) {           /* CompoundLine */
                    if (end - q >= 4) q += 4 + r.i32(q) * 4;
                }
                if ((penFlags & 0x800) != 0 && end - q >= 4) q += 4 + r.i32(q);
                if ((penFlags & 0x1000) != 0 && end - q >= 4) q += 4 + r.i32(q);
                /* embedded brush: Version, BrushType, data */
                if (end - q >= 8) {
                    int bType = r.i32(q + 4);
                    if (bType == 0 && end - q >= 12) o.color = argbToGdi(r.i32(q + 8));
                    else o.color = 0xFF000000;
                }
                break;
            }
            case 3: {  /* path: PointCount@0 PointFlags@4 data@8 */
                o.kind = PlusObj.PATH;
                if (end - body < 8) break;
                int count = r.i32(body);
                int pflags = r.i32(body + 4);
                int data = body + 8;
                if (count <= 0 || count > 65536) break;
                if ((pflags & 0x800) != 0) break;   /* RLE types — unsupported */
                int ptsSize = (pflags & 0x4000) != 0 ? count * 4 : count * 8;
                if (data + ptsSize + count > end + 8) break;
                o.pts = new float[count][];
                if ((pflags & 0x4000) != 0) {
                    for (int i = 0; i < count; i++)
                        o.pts[i] = new float[]{r.i16(data + i * 4), r.i16(data + i * 4 + 2)};
                } else {
                    for (int i = 0; i < count; i++)
                        o.pts[i] = new float[]{r.f32(data + i * 8), r.f32(data + i * 8 + 4)};
                }
                o.types = new byte[count];
                for (int i = 0; i < count; i++) o.types[i] = (byte) r.u8(data + ptsSize + i);
                break;
            }
            case 4: {  /* region: NodeCount@0 nodes@8 — model the rect node */
                o.kind = PlusObj.REGION;
                if (end - body >= 8) {
                    int nodeCount = r.i32(body);
                    if (nodeCount == 1) {
                        int nq = body + 4;
                        /* RegionNodeHeader: NextNode@0(4) NodeType@4(4) then data;
                           RegionDataRect node type 0x1000000 followed by RectF */
                        if (end - nq >= 28) {
                            int nodeType = r.i32(nq + 4);
                            if (nodeType == 0x1000000) {
                                o.regionRect = new float[]{r.f32(nq + 8), r.f32(nq + 12),
                                        r.f32(nq + 16), r.f32(nq + 20)};
                            }
                        }
                    }
                }
                break;
            }
            case 5: {  /* image: ImageDataType@0 (1 bitmap, 2 metafile) */
                if (end - body < 4) break;
                int imgType = r.i32(body);
                if (imgType == 1) {           /* bitmap: W@4 H@8 Stride@12 Fmt@16 Type@20 data@24 */
                    if (end - body < 24) break;
                    o.kind = PlusObj.IMAGE;
                    int w = r.i32(body + 4), h = r.i32(body + 8);
                    int stride = r.i32(body + 12);
                    int pixFmt = r.i32(body + 16);
                    int bmpType = r.i32(body + 20);
                    o.imgW = w;
                    o.imgH = h;
                    int data = body + 24;
                    if (bmpType == 1) {       /* compressed: PNG/JPEG/GIF bytes */
                        int len = Math.max(0, end - data);
                        o.imgEnc = new byte[len];
                        for (int i = 0; i < len; i++) o.imgEnc[i] = (byte) r.u8(data + i);
                    } else {                  /* raw pixels */
                        o.imgDib = plusPixelBitmap(r, data, end, w, h, stride, pixFmt);
                    }
                }
                break;
            }
            case 6: {  /* font: EmSize@0(f) SizeUnit@4 Style@8 Reserved@12 Len@16 Family@20 */
                o.kind = PlusObj.FONT;
                if (end - body < 4) break;
                o.emSize = r.f32(body);
                if (end - body >= 24) {
                    o.sizeUnit = r.i32(body + 4);
                    o.fStyle = r.i32(body + 8);
                    int len = r.i32(body + 16);
                    if (len < 0 || len > 256) len = 0;
                    StringBuilder sb = new StringBuilder(len);
                    for (int i = 0; i < len && body + 20 + i * 2 + 2 <= end; i++) {
                        int c = r.u16(body + 20 + i * 2);
                        if (c == 0) break;
                        sb.append((char) c);
                    }
                    o.family = sb.toString();
                }
                break;
            }
            default:
                return;   /* string format / image attrs / custom cap — no slot */
        }
        if (o.kind != 0 && slot >= 0 && slot < 64) ps.table[slot] = o;
    }

    /* texture-brush embedded image (skip flags/wrap, parse EmfPlusImage) */
    static byte[] plusEmbeddedImage(Rd r, int off, int end) {
        if (end - off < 8) return null;
        int imgType = r.i32(off + 4);
        if (imgType != 1 || end - off < 28) return null;
        int bmpType = r.i32(off + 24);
        if (bmpType != 1) return null;
        int data = off + 28;
        int len = Math.max(0, end - data);
        byte[] out = new byte[len];
        for (int i = 0; i < len; i++) out[i] = (byte) r.u8(data + i);
        return out;
    }

    /* raw GDI+ pixel buffer → Dib (24bpp, 32bpp RGB/ARGB, 8bpp indexed) */
    static Dib plusPixelBitmap(Rd r, int off, int end, int w, int h, int stride, int pixFmt) {
        if (w <= 0 || h <= 0 || w > 20000 || h > 20000) return null;
        int bpp = (pixFmt >> 8) & 0xFF;
        boolean indexed = (pixFmt & 0x10000) != 0;
        int[] out = new int[w * h];
        if (indexed && bpp == 8) {
            /* palette: PaletteFlags@0 Count@4 entries@8 (ARGB each) */
            int palCount = r.i32(off + 4);
            int[] pal = new int[Math.max(0, Math.min(palCount, 256))];
            for (int i = 0; i < pal.length; i++) pal[i] = r.i32(off + 8 + i * 4);
            int data = off + 8 + pal.length * 4;
            for (int y = 0; y < h; y++) {
                int row = stride >= 0 ? data + y * stride : data + (h - 1 + y) * stride;
                for (int x = 0; x < w; x++) {
                    int idx = r.u8(row + x);
                    out[y * w + x] = idx < pal.length ? pal[idx] : 0xFF000000;
                }
            }
            return new Dib(w, h, out);
        }
        if (bpp == 24 || bpp == 32 || bpp == 48 || bpp == 64) {
            int step = bpp / 8;
            int data = off;
            for (int y = 0; y < h; y++) {
                int row = stride >= 0 ? data + y * stride : data + (h - 1 + y) * stride;
                for (int x = 0; x < w; x++) {
                    int q = row + x * step;
                    if (q + step > end) break;
                    if (bpp == 24) {
                        out[y * w + x] = 0xFF000000 | (r.u8(q) << 16) | (r.u8(q + 1) << 8) | r.u8(q + 2);
                    } else if (bpp == 32) {
                        out[y * w + x] = 0xFF000000 | (r.u8(q) << 16) | (r.u8(q + 1) << 8) | r.u8(q + 2);
                    } else if (bpp == 48) {
                        out[y * w + x] = 0xFF000000 | (r.u8(q + 1) << 16) | (r.u8(q + 3) << 8) | r.u8(q + 5);
                    } else {
                        out[y * w + x] = 0xFF000000 | (r.u8(q + 1) << 16) | (r.u8(q + 3) << 8) | r.u8(q + 5);
                    }
                }
            }
            return new Dib(w, h, out);
        }
        if (bpp == 16) {   /* 16bpp RGB 5:6:5 (GDI+ grayscale 16bpp rare) */
            int data = off;
            for (int y = 0; y < h; y++) {
                int row = stride >= 0 ? data + y * stride : data + (h - 1 + y) * stride;
                for (int x = 0; x < w; x++) {
                    int v = r.u16(row + x * 2);
                    out[y * w + x] = 0xFF000000
                            | (((v >> 11) & 31) * 255 / 31 << 16)
                            | (((v >> 5) & 63) * 255 / 63 << 8)
                            | ((v & 31) * 255 / 31);
                }
            }
            return new Dib(w, h, out);
        }
        return null;
    }

    static final class Rd {
        final byte[] b;

        Rd(byte[] b) {
            this.b = b;
        }

        int u8(int o) { return o >= 0 && o < b.length ? b[o] & 255 : 0; }

        int u16(int o) {
            if (o < 0 || o + 2 > b.length) return 0;
            return (b[o] & 255) | ((b[o + 1] & 255) << 8);
        }

        int i16(int o) { return (short) u16(o); }

        long u32(int o) {
            if (o < 0 || o + 4 > b.length) return 0;
            return ((b[o] & 255L) | ((b[o + 1] & 255L) << 8)
                    | ((b[o + 2] & 255L) << 16) | ((b[o + 3] & 255L) << 24)) & 0xFFFFFFFFL;
        }

        int i32(int o) { return (int) u32(o); }

        /* v1.36: EMF+ uses 32-bit IEEE floats everywhere */
        float f32(int o) {
            if (o < 0 || o + 4 > b.length) return 0f;
            return Float.intBitsToFloat((int) u32(o));
        }

        int rgb(int o) {
            if (o + 3 >= b.length) return 0xFF000000;
            return 0xFF000000 | ((b[o] & 255) << 16) | ((b[o + 1] & 255) << 8) | (b[o + 2] & 255);
        }
    }
}
