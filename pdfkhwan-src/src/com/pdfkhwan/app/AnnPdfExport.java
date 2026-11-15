package com.pdfkhwan.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import com.tom_roush.pdfbox.cos.COSArray;
import com.tom_roush.pdfbox.cos.COSDictionary;
import com.tom_roush.pdfbox.cos.COSFloat;
import com.tom_roush.pdfbox.cos.COSName;
import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import com.tom_roush.pdfbox.pdmodel.PDResources;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText;
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup;
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationUnknown;
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary;
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/* ====================================================================
   v1.45: خروجی PDF همراه حاشیه‌نویسی + عملیات صفحه — PDFBox
   --------------------------------------------------------------------
   دو حالت خروجی حاشیه‌نویسی:
     FLAT  — حاشیه‌نویسی داخل جوهرِ خود صفحه «پخته» می‌شود
             (content stream ضمیمه + BlendMode Multiply دقیقاً مثل اپ).
             متن اصلی سالم و قابل انتخاب می‌ماند؛ خروجی در همهٔ
             نمایشگرها (Adobe / Foxit / Chrome / پرینتر) یکسان دیده می‌شود.
     EMBED — annotation های رسمی استاندارد PDF:
             هایلایت/زیرخط/خط‌خورده/موج‌دار = TextMarkup با QuadPoints،
             یادداشت = Sticky Note با متن، قلم/ماژیک = Ink، فلش = Ink دومی.
             در Adobe / Foxit قابل انتخاب، جابجایی و حذف است.
   عملیات صفحه (بدون رندر مجدد — importPage کیفیت اصلی را حفظ می‌کند):
             چرخش / حذف / جابجایی / استخراج / ادغام / تقسیم.
   فایل اصلی PDF هرگز تغییر نمی‌کند؛ خروجی در cache موقت ساخته می‌شود.
   ==================================================================== */
public final class AnnPdfExport {

    private AnnPdfExport() {}

    /* ---------------- ورودی‌های هندسهٔ واژه‌ها ----------------
       ReaderActivity این را از کش SelPage تامین می‌کند؛ مختصات واژه‌ها
       در فضای رندر pdf.js با مبدا بالا-چپ است (مثل اپ). */
    public interface WordGeom {
        float vw(int page);
        float vh(int page);
        /* جعبه‌های واژه‌های بازهٔ [w0..w1] — خروجی: ۵ float برای هر واژه:
           x0, y0, x1, y1, line */
        float[] words(int page, int w0, int w1);
    }

    public interface Progress {
        void progress(int done, int total);
    }

    public static final int MODE_FLAT = 0;
    public static final int MODE_EMBED = 1;

    /* ثابت چگالیِ خروجی — ضخامت قلم روی کاغذ (نسبت به dp)؛ نزدیک
       چگالی گوشی‌های رایج تا خروجی شبیه آنچه کاربر می‌بیند باشد */
    private static final float PT_PER_DP = 2.625f;
    private static final float HL_PAD = 1.5f;   /* حاشیهٔ نوار هایلایت (pt) */

    /* ================= خروجی با حاشیه‌نویسی ================= */

    public static File buildAnnotated(Context ctx, File src, int mode,
                                      List<Annotations.Ann> items, WordGeom geom,
                                      float densityDp, Progress cb) throws Exception {
        PDDocument doc = null;
        try {
            doc = PDDocument.load(src, "", MemoryUsageSetting.setupMixed(48L * 1024 * 1024));
            if (mode == MODE_FLAT) flatten(doc, items, geom, densityDp, cb);
            else embed(doc, items, geom, densityDp, cb);
            File out = tempOut(ctx, src, mode == MODE_FLAT ? "-annotated.pdf" : "-annotations.pdf");
            doc.save(out);
            return out;
        } finally {
            try { if (doc != null) doc.close(); } catch (Exception ignored) {}
        }
    }

    private static File tempOut(Context ctx, File src, String suffix) {
        File dir = new File(ctx.getCacheDir(), "annexport");
        if (!dir.exists()) dir.mkdirs();
        /* پاک‌کردن خروجی‌های قدیمی این پوشه — فقط آخرین خروجی زنده می‌ماند */
        try {
            File[] old = dir.listFiles();
            if (old != null) for (File f : old) { try { f.delete(); } catch (Exception ignored) {} }
        } catch (Exception ignored) {}
        String base = src.getName();
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        return new File(dir, base + suffix);
    }

    /* صفحاتی که حاشیه‌نویسی متنی دارند و واژه‌هایشان لازم است */
    public static Set<Integer> pagesNeedingWords(List<Annotations.Ann> items) {
        Set<Integer> out = new HashSet<>();
        if (items == null) return out;
        for (Annotations.Ann a : items) {
            if (Annotations.isTextAttached(a.type) && a.page >= 0) out.add(a.page);
        }
        return out;
    }

    /* ================= FLATTEN ================= */

    private static void flatten(PDDocument doc, List<Annotations.Ann> items,
                                WordGeom geom, float densityDp, Progress cb) throws Exception {
        if (items == null || items.isEmpty()) return;
        int n = doc.getNumberOfPages();
        /* گروه‌بندی بر اساس صفحه */
        List<Annotations.Ann>[] perPage = new List[n];
        for (Annotations.Ann a : items) {
            if (a.page < 0 || a.page >= n) continue;
            if (perPage[a.page] == null) perPage[a.page] = new ArrayList<>();
            perPage[a.page].add(a);
        }
        float ptPerDp = densityDp > 0f ? densityDp : PT_PER_DP;
        int done = 0;
        for (int pg = 0; pg < n; pg++) {
            if (perPage[pg] == null) continue;
            PDPage page = doc.getPage(pg);
            PDRectangle box = cropBox(page);
            float pw = box.getWidth(), ph = box.getHeight();
            float ox = box.getLowerLeftX(), oy = box.getLowerLeftY();
            PDPageContentStream cs = new PDPageContentStream(doc, page, AppendMode.APPEND, true, true);
            try {
                PDResources res = page.getResources();
                PDExtendedGraphicsState gsMult = extGState(res, "Multiply", 1f);
                PDExtendedGraphicsState gsAlpha = extGState(res, "Normal", 0.92f);

                for (Annotations.Ann a : perPage[pg]) {
                    try { flattenOne(doc, cs, a, geom, pw, ph, ox, oy, ptPerDp, gsMult, gsAlpha); } catch (Throwable ignored) {}
                }
            } finally {
                try { cs.close(); } catch (Exception ignored) {}
            }
            done++;
            if (cb != null) cb.progress(done, countPages(perPage));
        }
    }

    private static int countPages(List<Annotations.Ann>[] perPage) {
        int c = 0;
        for (List<Annotations.Ann> l : perPage) if (l != null) c++;
        return c;
    }

    private static void flattenOne(PDDocument doc, PDPageContentStream cs,
                                   Annotations.Ann a, WordGeom geom,
                                   float pw, float ph, float ox, float oy,
                                   float ptPerDp,
                                   PDExtendedGraphicsState gsMult,
                                   PDExtendedGraphicsState gsAlpha) throws Exception {
        switch (a.type) {
            case Annotations.T_HL: {
                /* نوارهای هایلایت per line — مولتی‌پلای کامل مثل اپ */
                float[] w = wordBoxes(geom, a);
                if (w == null) return;
                float sx = sx(geom, a.page, pw), sy = sy(geom, a.page, ph);
                cs.saveGraphicsState();
                cs.setGraphicsStateParameters(gsMult);
                setFillRGB(cs, a.color);
                int curLine = Integer.MIN_VALUE;
                float minX = 0, maxX = 0, top = 0, bot = 0;
                for (int i = 0; i < w.length; i += 5) {
                    if ((int) w[i + 4] != curLine) {
                        if (curLine != Integer.MIN_VALUE) fillBand(cs, ox, oy, ph, sx, sy, minX, maxX, top, bot);
                        curLine = (int) w[i + 4];
                        minX = Float.MAX_VALUE; maxX = -Float.MAX_VALUE;
                        top = Float.MAX_VALUE; bot = -Float.MAX_VALUE;
                    }
                    minX = Math.min(minX, w[i]); maxX = Math.max(maxX, w[i + 2]);
                    top = Math.min(top, w[i + 1]); bot = Math.max(bot, w[i + 3]);
                }
                if (curLine != Integer.MIN_VALUE) fillBand(cs, ox, oy, ph, sx, sy, minX, maxX, top, bot);
                cs.restoreGraphicsState();
                break;
            }
            case Annotations.T_UL:
            case Annotations.T_NOTE: {
                float[] w = wordBoxes(geom, a);
                if (w == null) return;
                float sx = sx(geom, a.page, pw), sy = sy(geom, a.page, ph);
                cs.saveGraphicsState();
                cs.setGraphicsStateParameters(gsAlpha);
                cs.setStrokingColor(Color.red(a.color), Color.green(a.color), Color.blue(a.color));
                cs.setLineWidth(1.4f);
                cs.setLineCapStyle(1); cs.setLineJoinStyle(1);
                for (int i = 0; i < w.length; i += 5) {
                    float x0 = ox + w[i] * sx, x1 = ox + w[i + 2] * sx;
                    float yb = oy + ph - (w[i + 3] * sy) - 1.6f;
                    cs.moveTo(x0, yb); cs.lineTo(x1, yb);
                }
                cs.stroke();
                cs.restoreGraphicsState();
                if (a.type == Annotations.T_NOTE && a.note != null && a.note.trim().length() > 0) {
                    drawNoteBox(doc, cs, a.note, w, sx, sy, pw, ph, ox, oy);
                }
                break;
            }
            case Annotations.T_ST: {
                float[] w = wordBoxes(geom, a);
                if (w == null) return;
                float sx = sx(geom, a.page, pw), sy = sy(geom, a.page, ph);
                cs.saveGraphicsState();
                cs.setGraphicsStateParameters(gsAlpha);
                cs.setStrokingColor(Color.red(a.color), Color.green(a.color), Color.blue(a.color));
                cs.setLineWidth(1.4f);
                cs.setLineCapStyle(1);
                for (int i = 0; i < w.length; i += 5) {
                    float x0 = ox + w[i] * sx, x1 = ox + w[i + 2] * sx;
                    float ym = oy + ph - ((w[i + 1] + w[i + 3]) * 0.5f * sy);
                    cs.moveTo(x0, ym); cs.lineTo(x1, ym);
                }
                cs.stroke();
                cs.restoreGraphicsState();
                break;
            }
            case Annotations.T_SQ: {
                float[] w = wordBoxes(geom, a);
                if (w == null) return;
                float sx = sx(geom, a.page, pw), sy = sy(geom, a.page, ph);
                cs.saveGraphicsState();
                cs.setGraphicsStateParameters(gsAlpha);
                cs.setStrokingColor(Color.red(a.color), Color.green(a.color), Color.blue(a.color));
                cs.setLineWidth(0.9f);
                cs.setLineCapStyle(1); cs.setLineJoinStyle(1);
                for (int i = 0; i < w.length; i += 5) {
                    float x0 = ox + w[i] * sx, x1 = ox + w[i + 2] * sx;
                    float yb = oy + ph - (w[i + 3] * sy) - 1.2f;
                    float x = x0;
                    boolean up = true;
                    cs.moveTo(x, yb);
                    while (x < x1) {
                        float nx = Math.min(x + 3f, x1);
                        cs.curveTo((x + nx) / 2f, yb + (up ? 1.3f : -1.3f), (x + nx) / 2f, yb + (up ? 1.3f : -1.3f), nx, yb);
                        up = !up;
                        x = nx;
                    }
                }
                cs.stroke();
                cs.restoreGraphicsState();
                break;
            }
            case Annotations.T_FNOTE: {
                /* آیکون + جعبهٔ متن تصویری در لنگرگاه نرمال‌شده */
                float ax = ox + (a.x * pw), ay = oy + ph - (a.y * ph);
                if (a.note != null && a.note.trim().length() > 0) {
                    drawFnoteBox(doc, cs, a.note, ax, ay, pw, ph);
                } else {
                    drawNoteDot(cs, ax, ay, a.color);
                }
                break;
            }
            case Annotations.T_STROKE: {
                if (a.px == null || a.px.length < 2) return;
                boolean marker = a.marker;
                float wpt = marker
                        ? Math.max(0.6f, (a.strokeW / 10f) * ptPerDp)
                        : Math.max(0.5f, a.strokeW * ptPerDp);
                cs.saveGraphicsState();
                if (marker) cs.setGraphicsStateParameters(gsMult);
                else cs.setGraphicsStateParameters(gsAlpha);
                cs.setStrokingColor(Color.red(a.color), Color.green(a.color), Color.blue(a.color));
                cs.setLineWidth(wpt);
                cs.setLineCapStyle(1); cs.setLineJoinStyle(1);
                if (a.arrow && a.px.length == 2) {
                    float x0 = ox + (a.px[0] * pw), y0 = oy + ph - (a.py[0] * ph);
                    float x1 = ox + (a.px[1] * pw), y1 = oy + ph - (a.py[1] * ph);
                    cs.moveTo(x0, y0); cs.lineTo(x1, y1);
                    cs.stroke();
                    /* سرِ فلش — مثل drawArrowHead اپ */
                    double ang = Math.atan2(y1 - y0, x1 - x0);
                    double back = ang + Math.PI;
                    float hl = Math.max(6f, wpt * 3f);
                    float spread = (float) (28f * Math.PI / 180f);
                    float ax1 = (float) (x1 + hl * Math.cos(back + spread));
                    float ay1 = (float) (y1 + hl * Math.sin(back + spread));
                    float ax2 = (float) (x1 + hl * Math.cos(back - spread));
                    float ay2 = (float) (y1 + hl * Math.sin(back - spread));
                    cs.setLineJoinStyle(0);
                    cs.moveTo(x1, y1); cs.lineTo(ax1, ay1); cs.moveTo(x1, y1); cs.lineTo(ax2, ay2);
                    cs.stroke();
                } else {
                    cs.moveTo(ox + (a.px[0] * pw), oy + ph - (a.py[0] * ph));
                    for (int k = 1; k < a.px.length; k++) {
                        cs.lineTo(ox + (a.px[k] * pw), oy + ph - (a.py[k] * ph));
                    }
                    cs.stroke();
                }
                cs.restoreGraphicsState();
                break;
            }
            default:
                break;
        }
    }

    private static void fillBand(PDPageContentStream cs, float ox, float oy, float ph,
                                 float sx, float sy,
                                 float minX, float maxX, float top, float bot) throws Exception {
        if (maxX <= minX || bot <= top) return;
        float x0 = ox + (minX * sx) - HL_PAD;
        float x1 = ox + (maxX * sx) + HL_PAD;
        float yTop = oy + ph - (top * sy) + HL_PAD;
        float yBot = oy + ph - (bot * sy) - HL_PAD;
        cs.addRect(Math.min(x0, x1), Math.min(yTop, yBot), Math.abs(x1 - x0), Math.abs(yTop - yBot));
        cs.fill();
    }

    /* ================= EMBED ================= */

    private static void embed(PDDocument doc, List<Annotations.Ann> items,
                              WordGeom geom, float ptPerDp, Progress cb) throws Exception {
        if (items == null || items.isEmpty()) return;
        int n = doc.getNumberOfPages();
        int done = 0;
        for (Annotations.Ann a : items) {
            if (a.page < 0 || a.page >= n) continue;
            try {
                PDPage page = doc.getPage(a.page);
                PDRectangle box = cropBox(page);
                float pw = box.getWidth(), ph = box.getHeight();
                float ox = box.getLowerLeftX(), oy = box.getLowerLeftY();
                float sx = sx(geom, a.page, pw), sy = sy(geom, a.page, ph);
                List<PDAnnotation> anns = page.getAnnotations();
                PDAnnotation pa = embedOne(doc, a, geom, sx, sy, pw, ph, ox, oy, ptPerDp);
                if (pa != null) anns.add(pa);
            } catch (Throwable ignored) {
            }
            done++;
            if (cb != null) cb.progress(done, items.size());
        }
    }

    private static PDAnnotation embedOne(PDDocument doc, Annotations.Ann a, WordGeom geom,
                                         float sx, float sy, float pw, float ph,
                                         float ox, float oy, float ptPerDp) throws Exception {
        switch (a.type) {
            case Annotations.T_HL:
                return textMarkup(doc, PDAnnotationTextMarkup.SUB_TYPE_HIGHLIGHT, a, geom, sx, sy, ph, ox, oy);
            case Annotations.T_UL:
                return textMarkup(doc, PDAnnotationTextMarkup.SUB_TYPE_UNDERLINE, a, geom, sx, sy, ph, ox, oy);
            case Annotations.T_ST:
                return textMarkup(doc, PDAnnotationTextMarkup.SUB_TYPE_STRIKEOUT, a, geom, sx, sy, ph, ox, oy);
            case Annotations.T_SQ:
                return textMarkup(doc, PDAnnotationTextMarkup.SUB_TYPE_SQUIGGLY, a, geom, sx, sy, ph, ox, oy);
            case Annotations.T_NOTE: {
                /* هم زیرخط رنگی، هم Sticky Note با متن یادداشت */
                /* (زیرخط) */
                PDAnnotation ul = textMarkup(doc, PDAnnotationTextMarkup.SUB_TYPE_UNDERLINE, a, geom, sx, sy, ph, ox, oy);
                float[] w = wordBoxes(geom, a);
                float ax = ox + (w != null && w.length >= 5 ? w[w.length - 3] * sx : pw / 2f);
                float ay = oy + ph - (w != null && w.length >= 5 ? w[w.length - 2] * sy : ph / 2f);
                PDAnnotationText st = sticky(a, ax, ay);
                return st != null ? st : ul;
            }
            case Annotations.T_FNOTE: {
                float ax = ox + (a.x * pw), ay = oy + ph - (a.y * ph);
                return sticky(a, ax, ay);
            }
            case Annotations.T_STROKE: {
                if (a.px == null || a.px.length < 2) return null;
                return ink(doc, a, sx, sy, pw, ph, ox, oy, ptPerDp);
            }
            default:
                return null;
        }
    }

    private static PDAnnotation textMarkup(PDDocument doc, String subtype, Annotations.Ann a, WordGeom geom,
                                           float sx, float sy, float ph, float ox, float oy) {
        try {
            float[] w = wordBoxes(geom, a);
            if (w == null || w.length < 5) return null;
            PDAnnotationTextMarkup tm = new PDAnnotationTextMarkup(subtype);
            ArrayList<Float> quads = new ArrayList<>();
            float rMinX = Float.MAX_VALUE, rMaxX = -Float.MAX_VALUE, rMinY = Float.MAX_VALUE, rMaxY = -Float.MAX_VALUE;
            for (int i = 0; i < w.length; i += 5) {
                float x0 = ox + w[i] * sx, x1 = ox + w[i + 2] * sx;
                float yTop = oy + ph - w[i + 1] * sy;
                float yBot = oy + ph - w[i + 3] * sy;
                /* QuadPoints: UL UR LL LR */
                quads.add(x0); quads.add(yTop);
                quads.add(x1); quads.add(yTop);
                quads.add(x0); quads.add(yBot);
                quads.add(x1); quads.add(yBot);
                rMinX = Math.min(rMinX, x0); rMaxX = Math.max(rMaxX, x1);
                rMinY = Math.min(rMinY, yBot); rMaxY = Math.max(rMaxY, yTop);
            }
            float[] qa = new float[quads.size()];
            for (int i = 0; i < qa.length; i++) qa[i] = quads.get(i);
            tm.setQuadPoints(qa);
            /* v1.48: پد ۳pt تا خط زیرخط/موج‌دار هم داخل Rect بماند (بعضی
               نمایشگرها AP را به Rect می‌بُرند) */
            tm.setRectangle(new PDRectangle(rMinX - 3, rMinY - 3, (rMaxX - rMinX) + 6, (rMaxY - rMinY) + 6));
            applyColor(tm, a.color);
            tm.setContents(a.text == null ? "" : a.text);
            tm.setPrinted(true);
            tm.setNoView(false);
            tm.setNoZoom(false);
            tm.setNoRotate(false);
            /* v1.48: ظاهرِ صریح — در هر نمایشگری (حتی بدون پشتیبانی از
               QuadPoints) همان نوارِ داخل اپ دیده می‌شود، نه خط باریک. */
            attachTextMarkupAp(doc, tm, subtype, qa, a.color);
            return tm;
        } catch (Throwable t) {
            return null;
        }
    }

    private static PDAnnotationText sticky(Annotations.Ann a, float ax, float ay) {
        try {
            PDAnnotationText st = new PDAnnotationText();
            try { st.setName(PDAnnotationText.NAME_NOTE); } catch (Throwable ignored) {}
            st.setRectangle(new PDRectangle(ax - 9, ay - 9, 18, 18));
            st.setContents((a.note == null || a.note.trim().length() == 0)
                    ? (a.text == null ? "یادداشت" : a.text) : a.note);
            applyColor(st, a.color);
            st.setPrinted(true);
            return st;
        } catch (Throwable t) {
            return null;
        }
    }

    /* Ink دستی — کلاس Ink در این پورت PDFBox نیست؛ فرهنگ لغت COS مستقیم.
       v1.48: ظاهرِ صریح هم ساخته می‌شود — نمایشگرهایی که InkList بدون AP
       را نمی‌کشند (یا با ضخامت پیش‌فرضِ باریک می‌کشند) هم درست دیده می‌شود. */
    private static PDAnnotation ink(PDDocument doc, Annotations.Ann a, float sx, float sy,
                                    float pw, float ph, float ox, float oy, float ptPerDp) {
        try {
            COSDictionary d = new COSDictionary();
            d.setItem(COSName.TYPE, COSName.getPDFName("Annot"));
            d.setItem(COSName.SUBTYPE, COSName.getPDFName("Ink"));
            /* v1.50: ضخامت با مقیاس واقعی dp→pt (نه چگالی ثابت گوشی) */
            final float wpt = Math.max(0.5f, a.marker ? (a.strokeW / 10f) * ptPerDp : a.strokeW * ptPerDp);
            float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            ArrayList<float[]> strokes = new ArrayList<>();
            if (a.arrow && a.px.length == 2) {
                /* خط + سرِ فلش = دو مسیر */
                float x0 = ox + a.px[0] * pw, y0 = oy + ph - a.py[0] * ph;
                float x1 = ox + a.px[1] * pw, y1 = oy + ph - a.py[1] * ph;
                strokes.add(new float[]{x0, y0, x1, y1});
                double ang = Math.atan2(y1 - y0, x1 - x0);
                double back = ang + Math.PI;
                float hl = Math.max(6f, a.strokeW * 3f);
                float spread = (float) (28f * Math.PI / 180f);
                float ax1 = (float) (x1 + hl * Math.cos(back + spread));
                float ay1 = (float) (y1 + hl * Math.sin(back + spread));
                float ax2 = (float) (x1 + hl * Math.cos(back - spread));
                float ay2 = (float) (y1 + hl * Math.sin(back - spread));
                strokes.add(new float[]{x1, y1, ax1, ay1});
                strokes.add(new float[]{x1, y1, ax2, ay2});
            } else {
                float[] pts = new float[a.px.length * 2];
                for (int k = 0; k < a.px.length; k++) {
                    pts[k * 2] = ox + a.px[k] * pw;
                    pts[k * 2 + 1] = oy + ph - a.py[k] * ph;
                }
                strokes.add(pts);
            }
            COSArray inkList = new COSArray();
            for (float[] s : strokes) {
                COSArray arr = new COSArray();
                for (float v : s) arr.add(new COSFloat(v));
                inkList.add(arr);
                for (int k = 0; k < s.length; k += 2) {
                    minX = Math.min(minX, s[k]); maxX = Math.max(maxX, s[k]);
                    minY = Math.min(minY, s[k + 1]); maxY = Math.max(maxY, s[k + 1]);
                }
            }
            d.setItem(COSName.getPDFName("InkList"), inkList);
            /* v1.48: Rect باید کل ظاهر (ضخامت قلم) را بپوشاند */
            float inkPad = Math.max(4f, wpt + 4f);
            d.setItem(COSName.RECT, new PDRectangle(minX - inkPad, minY - inkPad,
                    (maxX - minX) + 2 * inkPad, (maxY - minY) + 2 * inkPad).getCOSArray());
            COSArray col = new COSArray();
            col.add(new COSFloat(((a.color >> 16) & 0xFF) / 255f));
            col.add(new COSFloat(((a.color >> 8) & 0xFF) / 255f));
            col.add(new COSFloat((a.color & 0xFF) / 255f));
            d.setItem(COSName.C, col);
            /* Border: [horiz corner round] — ضخامت واقعی قلم */
            COSArray border = new COSArray();
            border.add(new COSFloat(0));
            border.add(new COSFloat(0));
            border.add(new COSFloat(wpt));
            d.setItem(COSName.BORDER, border);
            d.setInt(COSName.F, 4); /* print */
            if (a.note != null && a.note.length() > 0) {
                d.setString(COSName.CONTENTS, a.note);
            }
            /* v1.48: ظاهرِ صریح با ضخامت/شفافیت واقعی قلم */
            attachInkAp(doc, d, strokes, a, wpt);
            PDAnnotationUnknown u = new PDAnnotationUnknown(d);
            return u;
        } catch (Throwable t) {
            return null;
        }
    }

    /* ================= v1.48: Appearance (ظاهر صریح) =================
       چرا: نمایشگرهای مختلف برای annotation بدون /AP رفتارهای مختلفی دارند
       (بعضی هیچ نمی‌کشند، بعضی با ضخامت/شکل پیش‌فرضِ خودشان = خط باریک).
       با /AP همه — Adobe، Foxit، Chrome/pdfium، pdf.js، MuPDF، WPS — دقیقاً
       همان چیزی را می‌بینند که داخل اپ هست؛ QuadPoints/InkList هم می‌ماند
       تا در Adobe قابل جابه‌جایی/ویرایش باشد. */

    private static void attachTextMarkupAp(PDDocument doc, PDAnnotationTextMarkup tm,
                                           String subtype, float[] qa, int color) {
        try {
            /* محدودهٔ ظاهر از خود QuadPoints */
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            for (int i = 0; i + 1 < qa.length; i += 2) {
                minX = Math.min(minX, qa[i]); maxX = Math.max(maxX, qa[i]);
                minY = Math.min(minY, qa[i + 1]); maxY = Math.max(maxY, qa[i + 1]);
            }
            float pad = 3f;
            minX -= pad; minY -= pad; maxX += pad; maxY += pad;

            PDAppearanceStream aps = new PDAppearanceStream(doc);
            aps.setResources(new PDResources());
            /* BBox در مختصات مطلق صفحه + ماتریس همانی — همهٔ موتورها این را
               درست می‌فهمند و محاسبه ساده می‌ماند. */
            aps.setBBox(new PDRectangle(minX, minY, maxX - minX, maxY - minY));
            PDPageContentStream cs = new PDPageContentStream(doc, aps);
            try {
                boolean hl = PDAnnotationTextMarkup.SUB_TYPE_HIGHLIGHT.equals(subtype);
                cs.saveGraphicsState();
                if (hl) {
                    /* مولتی‌پلای مثل داخل اپ */
                    cs.setGraphicsStateParameters(extGState(aps.getResources(), "Multiply", 1f));
                    setFillRGB(cs, color);
                    for (int i = 0; i + 7 < qa.length; i += 8) {
                        float x0 = Math.min(qa[i], qa[i + 2]);
                        float x1 = Math.max(qa[i], qa[i + 2]);
                        float y0 = Math.min(qa[i + 1], qa[i + 5]);
                        float y1 = Math.max(qa[i + 1], qa[i + 5]);
                        cs.addRect(x0, y0, x1 - x0, y1 - y0);
                    }
                    cs.fill();
                } else {
                    cs.setGraphicsStateParameters(extGState(aps.getResources(), "Normal", 0.95f));
                    cs.setStrokingColor(((color >> 16) & 0xFF), ((color >> 8) & 0xFF), (color & 0xFF));
                    boolean underline = PDAnnotationTextMarkup.SUB_TYPE_UNDERLINE.equals(subtype);
                    boolean strike = PDAnnotationTextMarkup.SUB_TYPE_STRIKEOUT.equals(subtype);
                    cs.setLineWidth(underline ? 1.4f : (strike ? 1.4f : 0.9f));
                    cs.setLineCapStyle(1);
                    cs.setLineJoinStyle(1);
                    for (int i = 0; i + 7 < qa.length; i += 8) {
                        float x0 = Math.min(qa[i], qa[i + 2]);
                        float x1 = Math.max(qa[i], qa[i + 2]);
                        float yTop = Math.max(qa[i + 1], qa[i + 5]);
                        float yBot = Math.min(qa[i + 1], qa[i + 5]);
                        if (underline) {
                            float y = yBot - 1.6f;
                            cs.moveTo(x0, y);
                            cs.lineTo(x1, y);
                        } else if (strike) {
                            float y = (yTop + yBot) * 0.5f;
                            cs.moveTo(x0, y);
                            cs.lineTo(x1, y);
                        } else {
                            /* موج‌دار — مثل خروجی Flat */
                            float yb = yBot - 1.2f;
                            float x = x0;
                            boolean up = true;
                            cs.moveTo(x, yb);
                            while (x < x1) {
                                float nx = Math.min(x + 3f, x1);
                                cs.curveTo((x + nx) / 2f, yb + (up ? 1.3f : -1.3f),
                                        (x + nx) / 2f, yb + (up ? 1.3f : -1.3f), nx, yb);
                                up = !up;
                                x = nx;
                            }
                        }
                    }
                    cs.stroke();
                }
                cs.restoreGraphicsState();
            } finally {
                try { cs.close(); } catch (Exception ignored) {}
            }
            PDAppearanceDictionary apd = new PDAppearanceDictionary();
            apd.setNormalAppearance(aps);
            tm.setAppearance(apd);
        } catch (Throwable ignored) {
            /* بدون AP هم annotation معتبر است — QuadPoints می‌ماند */
        }
    }

    private static void attachInkAp(PDDocument doc, COSDictionary inkAnnot,
                                    ArrayList<float[]> strokes, Annotations.Ann a, float wptIn) {
        try {
            float wpt = wptIn > 0.3f ? wptIn : Math.max(0.6f, a.marker ? (a.strokeW / 10f) * PT_PER_DP : a.strokeW * PT_PER_DP);
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            for (float[] s : strokes) {
                for (int k = 0; k + 1 < s.length; k += 2) {
                    minX = Math.min(minX, s[k]); maxX = Math.max(maxX, s[k]);
                    minY = Math.min(minY, s[k + 1]); maxY = Math.max(maxY, s[k + 1]);
                }
            }
            float pad = wpt + 2f;
            minX -= pad; minY -= pad; maxX += pad; maxY += pad;

            PDAppearanceStream aps = new PDAppearanceStream(doc);
            aps.setResources(new PDResources());
            aps.setBBox(new PDRectangle(minX, minY, maxX - minX, maxY - minY));
            PDPageContentStream cs = new PDPageContentStream(doc, aps);
            try {
                cs.saveGraphicsState();
                if (a.marker) {
                    cs.setGraphicsStateParameters(extGState(aps.getResources(), "Multiply", 1f));
                } else {
                    cs.setGraphicsStateParameters(extGState(aps.getResources(), "Normal", 0.92f));
                }
                cs.setStrokingColor(((a.color >> 16) & 0xFF), ((a.color >> 8) & 0xFF), (a.color & 0xFF));
                cs.setLineWidth(wpt);
                cs.setLineCapStyle(1);
                cs.setLineJoinStyle(1);
                for (float[] s : strokes) {
                    if (s.length < 4) continue;
                    cs.moveTo(s[0], s[1]);
                    for (int k = 2; k + 1 < s.length; k += 2) {
                        cs.lineTo(s[k], s[k + 1]);
                    }
                }
                cs.stroke();
                cs.restoreGraphicsState();
            } finally {
                try { cs.close(); } catch (Exception ignored) {}
            }
            PDAppearanceDictionary apd = new PDAppearanceDictionary();
            apd.setNormalAppearance(aps);
            inkAnnot.setItem(COSName.getPDFName("AP"), apd.getCOSObject());
        } catch (Throwable ignored) {
        }
    }

    private static void applyColor(PDAnnotation pa, int color) {
        try {
            COSArray col = new COSArray();
            col.add(new COSFloat(((color >> 16) & 0xFF) / 255f));
            col.add(new COSFloat(((color >> 8) & 0xFF) / 255f));
            col.add(new COSFloat((color & 0xFF) / 255f));
            pa.getCOSObject().setItem(COSName.C, col);
        } catch (Throwable ignored) {
        }
    }

    /* ================= جعبهٔ متن یادداشت (تصویری — shaping درست فارسی) ================= */

    private static void drawNoteBox(PDDocument doc, PDPageContentStream cs, String note,
                                    float[] w, float sx, float sy,
                                    float pw, float ph, float ox, float oy) throws Exception {
        /* جایگاه: زیر آخرین واژهٔ بازه */
        float lastX1 = ox + (w[w.length - 3] * sx);
        float lastY1 = oy + ph - (w[w.length - 2] * sy);
        drawTextCard(doc, cs, note, lastX1 - 8, lastY1 - 4, pw, true);
    }

    private static void drawFnoteBox(PDDocument doc, PDPageContentStream cs, String note,
                                     float ax, float ay, float pw, float ph) throws Exception {
        drawTextCard(doc, cs, note, ax - 4, ay + 2, pw, false);
    }

    private static void drawTextCard(PDDocument doc, PDPageContentStream cs, String text,
                                     float x, float yTop, float pw, boolean below) throws Exception {
        /* متن فارسی باید shape شود — با StaticLayout اندروید روی bitmap
           می‌کشیم (رزولوشن ۳x) و به‌صورت تصویر شفاف داخل PDF می‌گذاریم */
        float scale = 3f;
        float cardWpt = Math.min(200f, pw * 0.6f);
        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        tp.setColor(0xFF1F2430);
        tp.setTextSize(9.5f * scale);
        @SuppressWarnings("deprecation")
        StaticLayout sl = new StaticLayout(text, 0, text.length(), tp,
                Math.round(cardWpt * scale),
                Layout.Alignment.ALIGN_NORMAL, 1f, 1.5f * scale, true);
        float cardHpt = (sl.getHeight() / scale) + 10f;
        int bw = Math.max(8, Math.round(cardWpt * scale));
        int bh = Math.max(8, Math.round(cardHpt * scale));
        Bitmap bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        /* پس‌زمینهٔ کارت */
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(0xFFFFF6C9);
        c.drawRoundRect(0, 0, bw, bh, 6 * scale, 6 * scale, bg);
        Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(1.2f * scale);
        edge.setColor(0xFFE8C86A);
        c.drawRoundRect(0.6f * scale, 0.6f * scale, bw - 1.2f * scale, bh - 1.2f * scale,
                6 * scale, 6 * scale, edge);
        c.save();
        c.translate(5 * scale, 4 * scale);
        sl.draw(c);
        c.restore();
        com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject img
                = com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
                .createFromImage(doc, bmp);
        /* مکان‌یابی: زیر نقطهٔ لنگر (یا بالایش اگر جا نیست) — clamp داخل صفحه */
        float drawX = Math.max(4, Math.min(x, pw - cardWpt - 4));
        float drawY = below ? (yTop - cardHpt) : (yTop - cardHpt);
        drawY = Math.max(4, drawY);
        cs.drawImage(img, drawX, drawY, cardWpt, cardHpt);
        try { bmp.recycle(); } catch (Exception ignored) {}
    }

    private static void drawNoteDot(PDPageContentStream cs, float x, float y, int color) throws Exception {
        cs.saveGraphicsState();
        setFillRGB(cs, 0xFFFFD54F);
        float r = 5f;
        /* شش‌ضلعی کوچک به‌جای دایره (PDF بدون bezier لازم) */
        cs.moveTo(x, y + r);
        cs.lineTo(x + r * 0.866f, y + r * 0.5f);
        cs.lineTo(x + r * 0.866f, y - r * 0.5f);
        cs.lineTo(x, y - r);
        cs.lineTo(x - r * 0.866f, y - r * 0.5f);
        cs.lineTo(x - r * 0.866f, y + r * 0.5f);
        cs.closePath();
        cs.fill();
        cs.restoreGraphicsState();
    }

    /* ================= عملیات صفحه (feature 17) ================= */

    public static File opRotate(Context ctx, File src, int[] pages, int deg) throws Exception {
        PDDocument doc = null;
        try {
            doc = PDDocument.load(src, "", MemoryUsageSetting.setupMixed(48L * 1024 * 1024));
            for (int p : pages) {
                if (p < 0 || p >= doc.getNumberOfPages()) continue;
                PDPage page = doc.getPage(p);
                int cur = page.getRotation();
                if (cur % 90 != 0) cur = 0;
                page.setRotation(((cur + deg) % 360 + 360) % 360);
            }
            return saveOps(ctx, doc, src, "-rotated.pdf");
        } finally {
            try { if (doc != null) doc.close(); } catch (Exception ignored) {}
        }
    }

    public static File opDelete(Context ctx, File src, int[] removePages) throws Exception {
        PDDocument doc = null;
        try {
            doc = PDDocument.load(src, "", MemoryUsageSetting.setupMixed(48L * 1024 * 1024));
            /* از آخر به اول تا indexها جابه‌جا نشوند */
            java.util.Arrays.sort(removePages);
            for (int i = removePages.length - 1; i >= 0; i--) {
                int p = removePages[i];
                if (p >= 0 && p < doc.getNumberOfPages()) doc.removePage(p);
            }
            return saveOps(ctx, doc, src, "-pages.pdf");
        } finally {
            try { if (doc != null) doc.close(); } catch (Exception ignored) {}
        }
    }

    /* order: ترتیب جدید صفحات موجود (index های صفر-مبنا) */
    public static File opReorder(Context ctx, File src, int[] order) throws Exception {
        PDDocument src1 = null;
        PDDocument out = null;
        try {
            src1 = PDDocument.load(src, "", MemoryUsageSetting.setupMixed(48L * 1024 * 1024));
            out = new PDDocument();
            for (int p : order) {
                if (p >= 0 && p < src1.getNumberOfPages()) {
                    out.importPage(src1.getPage(p));
                }
            }
            return saveOps(ctx, out, src, "-reordered.pdf");
        } finally {
            try { if (src1 != null) src1.close(); } catch (Exception ignored) {}
            try { if (out != null) out.close(); } catch (Exception ignored) {}
        }
    }

    public static File opExtract(Context ctx, File src, int[] pages) throws Exception {
        PDDocument src1 = null;
        PDDocument out = null;
        try {
            src1 = PDDocument.load(src, "", MemoryUsageSetting.setupMixed(48L * 1024 * 1024));
            out = new PDDocument();
            for (int p : pages) {
                if (p >= 0 && p < src1.getNumberOfPages()) {
                    out.importPage(src1.getPage(p));
                }
            }
            return saveOps(ctx, out, src, "-extract.pdf");
        } finally {
            try { if (src1 != null) src1.close(); } catch (Exception ignored) {}
            try { if (out != null) out.close(); } catch (Exception ignored) {}
        }
    }

    /* فایل‌های دیگر بعد از src ادغام می‌شوند */
    public static File opMerge(Context ctx, File src, List<File> others) throws Exception {
        PDDocument out = null;
        List<PDDocument> opened = new ArrayList<>();
        try {
            out = PDDocument.load(src, "", MemoryUsageSetting.setupMixed(48L * 1024 * 1024));
            for (File f : others) {
                PDDocument d = PDDocument.load(f, "", MemoryUsageSetting.setupMixed(48L * 1024 * 1024));
                opened.add(d);
                for (int i = 0; i < d.getNumberOfPages(); i++) out.importPage(d.getPage(i));
            }
            return saveOps(ctx, out, src, "-merged.pdf");
        } finally {
            for (PDDocument d : opened) { try { d.close(); } catch (Exception ignored) {} }
            try { if (out != null) out.close(); } catch (Exception ignored) {}
        }
    }

    private static File saveOps(Context ctx, PDDocument doc, File src, String suffix) throws Exception {
        File dir = new File(ctx.getCacheDir(), "annexport");
        if (!dir.exists()) dir.mkdirs();
        try {
            File[] old = dir.listFiles();
            if (old != null) for (File f : old) { try { f.delete(); } catch (Exception ignored) {} }
        } catch (Exception ignored) {}
        String base = src.getName();
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        File out = new File(dir, base + suffix);
        doc.save(out);
        return out;
    }

    /* ================= کمکی ================= */

    private static PDRectangle cropBox(PDPage page) {
        try {
            PDRectangle r = page.getCropBox();
            if (r != null && r.getWidth() > 1f && r.getHeight() > 1f) return r;
        } catch (Throwable ignored) {}
        return page.getMediaBox();
    }

    /* ثبت ExtGState در منابع صفحه و برگرداندن خود آبجکت — هم در
       resources ثبت می‌شود و هم به setGraphicsStateParameters داده می‌شود */
    private static PDExtendedGraphicsState extGState(PDResources res, String blend, float alpha) {
        PDExtendedGraphicsState gs = new PDExtendedGraphicsState();
        try { gs.setNonStrokingAlphaConstant(alpha); } catch (Throwable ignored) {}
        try { gs.setStrokingAlphaConstant(alpha); } catch (Throwable ignored) {}
        try { gs.getCOSObject().setName(COSName.BM, blend); } catch (Throwable ignored) {}
        try { res.add(gs); } catch (Throwable ignored) {}
        return gs;
    }

    private static void setFillRGB(PDPageContentStream cs, int color) throws Exception {
        cs.setNonStrokingColor(((color >> 16) & 0xFF), ((color >> 8) & 0xFF), (color & 0xFF));
    }

    private static float[] wordBoxes(WordGeom geom, Annotations.Ann a) {
        try {
            int from = Math.max(0, Math.min(a.w0, a.w1));
            int to = Math.max(a.w0, a.w1);
            return geom.words(a.page, from, to);
        } catch (Throwable t) {
            return null;
        }
    }

    private static float sx(WordGeom geom, int page, float pw) {
        try {
            float vw = geom.vw(page);
            return vw > 1f ? pw / vw : 1f;
        } catch (Throwable t) {
            return 1f;
        }
    }

    private static float sy(WordGeom geom, int page, float ph) {
        try {
            float vh = geom.vh(page);
            return vh > 1f ? ph / vh : 1f;
        } catch (Throwable t) {
            return 1f;
        }
    }

    /* ================= v1.50: بازخوانی annotation های استاندارد =================
       مشکل: خروجیِ EMBED در اپ‌های دیگر (Adobe/Foxit) قابل ویرایش بود ولی
       وقتی همان فایل را دوباره در خودِ اپ باز می‌کردی، annotationها دیده
       نمی‌شدند (اپ فقط از JSON خودش می‌خواند).
       راه‌حل: در بازکردن فایل، اگر فایل PDF خودش annotation استاندارد دارد
       (هایلایت/زیرخط/خط‌خورده/موج‌دار/یادداشت/جوهر)، به‌صورت Ann درمی‌آیند
       و مثل حاشیه‌نویسی‌های خودِ اپ قابل حرکت/حذف/ذخیره هستند. */

    public static List<Annotations.Ann> importAnns(File pdf) {
        List<Annotations.Ann> out = new ArrayList<>();
        PDDocument doc = null;
        try {
            doc = PDDocument.load(pdf, "", MemoryUsageSetting.setupMixed(24L * 1024 * 1024));
            int n = doc.getNumberOfPages();
            for (int i = 0; i < n; i++) {
                PDPage page;
                try {
                    page = doc.getPage(i);
                } catch (Throwable t) {
                    continue;
                }
                PDRectangle box = cropBox(page);
                float pw = box.getWidth(), ph = box.getHeight();
                float ox = box.getLowerLeftX(), oy = box.getLowerLeftY();
                List<PDAnnotation> anns;
                try {
                    anns = page.getAnnotations();
                } catch (Throwable t) {
                    continue;
                }
                if (anns == null) continue;
                for (PDAnnotation an : anns) {
                    try {
                        Annotations.Ann a = importOne(an, i, pw, ph, ox, oy);
                        if (a != null) out.add(a);
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable t) {
            return out;
        } finally {
            try {
                if (doc != null) doc.close();
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private static Annotations.Ann importOne(PDAnnotation an, int pageIdx,
                                             float pw, float ph, float ox, float oy) {
        String subtype = null;
        try {
            if (an.getSubtype() != null) subtype = an.getSubtype();
        } catch (Throwable ignored) {
        }
        if (subtype == null) return null;
        int color = 0xFFFFD54F;
        try {
            float[] cs = an.getColor().getComponents();
            if (cs != null && cs.length >= 3) {
                color = 0xFF000000
                        | (Math.max(0, Math.min(255, (int) (cs[0] * 255f))) << 16)
                        | (Math.max(0, Math.min(255, (int) (cs[1] * 255f))) << 8)
                        | Math.max(0, Math.min(255, (int) (cs[2] * 255f)));
            }
        } catch (Throwable ignored) {
        }
        String contents = null;
        try {
            contents = an.getContents();
        } catch (Throwable ignored) {
        }

        if (an instanceof PDAnnotationTextMarkup) {
            PDAnnotationTextMarkup tm = (PDAnnotationTextMarkup) an;
            float[] qa = null;
            try {
                qa = tm.getQuadPoints();
            } catch (Throwable ignored) {
            }
            if (qa == null || qa.length < 8) return null;
            int type;
            if (PDAnnotationTextMarkup.SUB_TYPE_HIGHLIGHT.equals(subtype)) type = Annotations.T_HL;
            else if (PDAnnotationTextMarkup.SUB_TYPE_UNDERLINE.equals(subtype)) type = Annotations.T_UL;
            else if (PDAnnotationTextMarkup.SUB_TYPE_STRIKEOUT.equals(subtype)) type = Annotations.T_ST;
            else type = Annotations.T_SQ;
            Annotations.Ann a = new Annotations.Ann();
            a.type = type;
            a.page = pageIdx;
            a.color = color;
            a.text = contents == null ? "" : contents;
            a.note = a.text;
            /* v1.50: quadها در px/py به‌صورت چهارتاییِ (x0,y0,x1,y1) نرمال‌شده
               (مبدای بالا-چپ) ذخیره می‌شوند؛ رندرِ w0==-1 اینها را مستقیم
               می‌کشد — بدون نیاز به هندسهٔ کلمات */
            int nq = qa.length / 8;
            float[] px = new float[nq * 4];
            float[] py = new float[nq * 4];
            for (int q = 0; q < nq; q++) {
                float x0 = Math.min(qa[q * 8], qa[q * 8 + 2]);
                float x1 = Math.max(qa[q * 8], qa[q * 8 + 2]);
                float yTop = Math.max(qa[q * 8 + 1], qa[q * 8 + 5]);
                float yBot = Math.min(qa[q * 8 + 1], qa[q * 8 + 5]);
                px[q * 4] = (x0 - ox) / pw;
                py[q * 4] = 1f - ((yTop - oy) / ph);
                px[q * 4 + 2] = (x1 - ox) / pw;
                py[q * 4 + 2] = 1f - ((yBot - oy) / ph);
            }
            a.px = px;
            a.py = py;
            a.w0 = -1;   /* علامتِ «بدون بازهٔ کلمه — از quadها رسم شود» */
            a.w1 = -1;
            a.strokeW = 2.5f;
            return a;
        }
        if (an instanceof PDAnnotationText) {
            /* Sticky Note → یادداشت آزاد */
            Annotations.Ann a = new Annotations.Ann();
            a.type = Annotations.T_FNOTE;
            a.page = pageIdx;
            a.color = color;
            a.note = contents == null ? "" : contents;
            PDRectangle r = null;
            try {
                r = an.getRectangle();
            } catch (Throwable ignored) {
            }
            if (r != null) {
                a.x = (r.getLowerLeftX() - ox) / pw;
                a.y = 1f - (r.getLowerLeftY() - oy) / ph;   /* مبدای اپ بالا-چپ */
            }
            if (a.x < 0 || a.x > 1 || a.y < 0 || a.y > 1) return null;
            return a;
        }
        if ("Ink".equals(subtype)) {
            /* جوهر — InkList آرایه‌ای از مسیرها */
            COSDictionary d = an.getCOSObject();
            COSArray ink = null;
            try {
                ink = (COSArray) d.getDictionaryObject(COSName.getPDFName("InkList"));
            } catch (Throwable ignored) {
            }
            if (ink == null || ink.size() == 0) return null;
            /* همهٔ مسیرها را یکی می‌کنیم (خطِ واحد اپ) */
            ArrayList<Float> xs = new ArrayList<>();
            ArrayList<Float> ys = new ArrayList<>();
            for (int k = 0; k < ink.size(); k++) {
                try {
                    COSArray path = (COSArray) ink.getObject(k);
                    if (path == null) continue;
                    for (int j = 0; j + 1 < path.size(); j += 2) {
                        xs.add(((COSFloat) path.getObject(j)).floatValue());
                        ys.add(((COSFloat) path.getObject(j + 1)).floatValue());
                    }
                    if (k + 1 < ink.size()) {
                        /* شکست بین مسیرها — نقطهٔ تکراری نمی‌گذاریم */
                    }
                } catch (Throwable ignored) {
                }
            }
            if (xs.size() < 2) return null;
            float[] px = new float[xs.size()];
            float[] py = new float[ys.size()];
            for (int k = 0; k < xs.size(); k++) {
                px[k] = (xs.get(k) - ox) / pw;
                py[k] = 1f - ((ys.get(k) - oy) / ph);   /* مبدای اپ بالا-چپ */
            }
            /* ضخامت از Border یا AP */
            float wpt = 2.5f * 0.567f;
            try {
                COSArray border = (COSArray) d.getDictionaryObject(COSName.BORDER);
                if (border != null && border.size() >= 3) {
                    wpt = ((COSFloat) border.getObject(2)).floatValue();
                }
            } catch (Throwable ignored) {
            }
            Annotations.Ann a = new Annotations.Ann();
            a.type = Annotations.T_STROKE;
            a.page = pageIdx;
            a.color = color;
            a.px = px;
            a.py = py;
            a.strokeW = Math.max(0.8f, wpt / 0.567f);   /* pt → dp تقریبی */
            a.marker = false;
            if (contents != null && contents.length() > 0) a.note = contents;
            return a;
        }
        return null;
    }
}
