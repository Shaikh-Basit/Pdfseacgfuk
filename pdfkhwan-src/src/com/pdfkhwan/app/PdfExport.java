package com.pdfkhwan.app;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.print.PrintManager;

import com.shockwave.pdfium.PdfDocument;
import com.shockwave.pdfium.PdfiumCore;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/* ====================================================================
   v1.37: ابزارهای خروجی PDF — اشتراک صفحات انتخابی، تبدیل به Word و چاپ
   --------------------------------------------------------------------
   - parseRanges: «۱-۳،۵» → فهرست صفحات (پشتیبانی از ارقام فارسی/عربی)
   - buildSubset: بازسازی صفحات انتخابی به یک PDF تازه و سالم
     (رندر pdfium → JPEG → نوشتن PDF مینیمال با DCTDecode)
   - buildDocx: ساخت فایل Word از متن استخراج‌شده (RTL-aware)
   - printFile: چاپ از طریق PrintManager اندروید (بدون دست‌زدن به
     فایل اصلی — کپی بایت‌به‌بایت داخل adapter)
   فایل اصلی PDF هرگز تغییر نمی‌کند.
   ==================================================================== */
public final class PdfExport {

    private PdfExport() {}

    /* ---------------- پارس بازهٔ صفحات ----------------
       ورودی مثل «۱-۳،5 ،۷» یا «2,4-6». خروجی: صفحات صفر-مبنا،
       مرتب و بدون تکرار. null یعنی ورودی نامعتبر بود. */
    public static int[] parseRanges(String raw, int pageCount) {
        if (raw == null) return null;
        /* نرمال‌سازی ارقام فارسی/عربی و جداکننده‌ها */
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= 0x06F0 && c <= 0x06F9) sb.append((char) ('0' + (c - 0x06F0)));
            else if (c >= 0x0660 && c <= 0x0669) sb.append((char) ('0' + (c - 0x0660)));
            else if (c == '،' || c == '؛' || c == ',' || c == ';' || c == ' ') sb.append(',');
            else if (c == '−' || c == '–' || c == '—' || c == '\u0640') sb.append('-');
            else sb.append(c);
        }
        TreeSet<Integer> set = new TreeSet<>();
        String[] parts = sb.toString().split(",");
        for (String part : parts) {
            String p = part.trim();
            if (p.length() == 0) continue;
            int dash = p.indexOf('-');
            try {
                if (dash > 0 && p.indexOf('-', dash + 1) < 0) {
                    int a = Integer.parseInt(p.substring(0, dash).trim());
                    int b = Integer.parseInt(p.substring(dash + 1).trim());
                    if (a > b) { int t = a; a = b; b = t; }
                    for (int pg = a; pg <= b; pg++) {
                        if (pg >= 1 && pg <= pageCount) set.add(pg - 1);
                    }
                } else {
                    int pg = Integer.parseInt(p);
                    if (pg >= 1 && pg <= pageCount) set.add(pg - 1);
                }
            } catch (NumberFormatException e) {
                return null; /* ورودی خراب */
            }
        }
        if (set.isEmpty()) return null;
        int[] out = new int[set.size()];
        int i = 0;
        for (Integer v : set) out[i++] = v;
        return out;
    }

    public interface Progress {
        void onProgress(int done, int total);
    }

    /* ---------------- بازسازی صفحات انتخابی → PDF تازه ---------------- */
    public static File buildSubset(Context ctx, Uri uri, String password, int[] pages0,
                                   File outFile, Progress prog) throws Exception {
        ParcelFileDescriptor pfd = null;
        PdfiumCore core = null;
        PdfDocument doc = null;
        try {
            pfd = "file".equals(uri.getScheme())
                    ? ParcelFileDescriptor.open(new File(uri.getPath()), ParcelFileDescriptor.MODE_READ_ONLY)
                    : ctx.getContentResolver().openFileDescriptor(uri, "r");
            if (pfd == null) throw new IllegalStateException("open failed");
            core = new PdfiumCore(ctx);
            doc = password != null ? core.newDocument(pfd, password) : core.newDocument(pfd);

            int n = pages0.length;
            byte[][] jpegs = new byte[n][];
            float[] ws = new float[n], hs = new float[n];
            for (int i = 0; i < n; i++) {
                int idx = pages0[i];
                core.openPage(doc, idx);
                int wpt = Math.max(1, core.getPageWidthPoint(doc, idx));
                int hpt = Math.max(1, core.getPageHeightPoint(doc, idx));
                /* ~۲۰۰ نقطه در اینچ (کافی برای چاپ و اشتراک)، سقف ۲۴۰۰ پیکسل عرض */
                float scale = Math.min(2400f / wpt, Math.max(1.2f, 200f / 72f));
                int w = Math.max(32, Math.round(wpt * scale));
                int h = Math.max(32, Math.round(hpt * scale));
                Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                bmp.eraseColor(0xFFFFFFFF);
                core.renderPageBitmap(doc, bmp, idx, 0, 0, w, h, false);
                ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 16);
                bmp.compress(Bitmap.CompressFormat.JPEG, 82, bos);
                bmp.recycle();
                jpegs[i] = bos.toByteArray();
                ws[i] = wpt;
                hs[i] = hpt;
                if (prog != null) prog.onProgress(i + 1, n);
            }
            writeImagePdf(outFile, jpegs, ws, hs);
            return outFile;
        } finally {
            try { if (core != null && doc != null) core.closeDocument(doc); } catch (Throwable ignored) {}
            try { if (pfd != null) pfd.close(); } catch (Throwable ignored) {}
        }
    }

    /* ---------------- PDF مینیمال با تصاویر JPEG ---------------- */
    private static void writeImagePdf(File out, byte[][] jpegs, float[] ws, float[] hs) throws Exception {
        int n = jpegs.length;
        /* شمارهٔ آبجکت‌ها: 1=catalog 2=pages، بعد به‌ازای هر صفحه ۳ آبجکت */
        int[] pageObj = new int[n], imgObj = new int[n], cntObj = new int[n];
        for (int i = 0; i < n; i++) { pageObj[i] = 3 + i * 3; imgObj[i] = 4 + i * 3; cntObj[i] = 5 + i * 3; }

        FileOutputStream fos = new FileOutputStream(out);
        /* شمارندهٔ دقیق بایت‌ها → آفست‌های xref همیشه درست */
        class Count extends OutputStream {
            long n = 0;
            FileOutputStream f;
            Count(FileOutputStream f) { this.f = f; }
            @Override public void write(int b) throws java.io.IOException { f.write(b); n++; }
            @Override public void write(byte[] b, int off, int len) throws java.io.IOException { f.write(b, off, len); n += len; }
            @Override public void flush() throws java.io.IOException { f.flush(); }
            @Override public void close() throws java.io.IOException { f.close(); }
        }
        Count c = new Count(fos);
        ArrayList<Long> offs = new ArrayList<>(); /* آفست هر آبجکت به‌ترتیب شماره */
        try {
            byte[] bin = new byte[]{'%', (byte) 0xE2, (byte) 0xE3, (byte) 0xCF, (byte) 0xD3, 0x0A};
            c.write("%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII));
            c.write(bin);
            offs.add(c.n);
            c.write("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n".getBytes(StandardCharsets.US_ASCII));
            offs.add(c.n);
            StringBuilder kids = new StringBuilder();
            for (int i = 0; i < n; i++) kids.append(pageObj[i]).append(" 0 R ");
            c.write(("2 0 obj\n<< /Type /Pages /Kids [" + kids.toString().trim() + "] /Count " + n + " >>\nendobj\n")
                    .getBytes(StandardCharsets.US_ASCII));

            for (int i = 0; i < n; i++) {
                offs.add(c.n);
                c.write((pageObj[i] + " 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 "
                        + fmt(ws[i]) + " " + fmt(hs[i]) + "] /Resources << /XObject << /Im" + i + " "
                        + imgObj[i] + " 0 R >> >> /Contents " + cntObj[i] + " 0 R >>\nendobj\n")
                        .getBytes(StandardCharsets.US_ASCII));

                offs.add(c.n);
                c.write((imgObj[i] + " 0 obj\n<< /Type /XObject /Subtype /Image /Width "
                        + jpegDim(jpegs[i], true) + " /Height " + jpegDim(jpegs[i], false)
                        + " /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length "
                        + jpegs[i].length + " >>\nstream\n").getBytes(StandardCharsets.US_ASCII));
                c.write(jpegs[i]);
                c.write("\nendstream\nendobj\n".getBytes(StandardCharsets.US_ASCII));

                String content = "q " + fmt(ws[i]) + " 0 0 " + fmt(hs[i]) + " 0 0 cm /Im" + i + " Do Q\n";
                byte[] cb = content.getBytes(StandardCharsets.US_ASCII);
                offs.add(c.n);
                c.write((cntObj[i] + " 0 obj\n<< /Length " + cb.length + " >>\nstream\n")
                        .getBytes(StandardCharsets.US_ASCII));
                c.write(cb);
                c.write("endstream\nendobj\n".getBytes(StandardCharsets.US_ASCII));
            }

            long xrefAt = c.n;
            int count = 3 + n * 3;
            StringBuilder xb = new StringBuilder();
            xb.append("xref\n0 ").append(count).append("\n0000000000 65535 f \n");
            for (Long off : offs) {
                xb.append(String.format("%010d 00000 n \n", off));
            }
            xb.append("trailer\n<< /Size ").append(count).append(" /Root 1 0 R >>\nstartxref\n")
              .append(xrefAt).append("\n%%EOF\n");
            c.write(xb.toString().getBytes(StandardCharsets.US_ASCII));
        } finally {
            try { c.close(); } catch (Exception ignored) {}
        }
    }

    private static String fmt(float v) {
        return String.valueOf(Math.round(v * 100f) / 100f);
    }

    /* ابعاد JPEG از هدر SOF (بدون کتابخانه) */
    private static int jpegDim(byte[] j, boolean width) {
        try {
            int i = 2;
            while (i + 9 < j.length) {
                if (j[i] != (byte) 0xFF) { i++; continue; }
                int marker = j[i + 1] & 0xFF;
                if (marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                    int h = ((j[i + 5] & 0xFF) << 8) | (j[i + 6] & 0xFF);
                    int w = ((j[i + 7] & 0xFF) << 8) | (j[i + 8] & 0xFF);
                    return width ? w : h;
                }
                int len = ((j[i + 2] & 0xFF) << 8) | (j[i + 3] & 0xFF);
                i += 2 + len;
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /* ---------------- چاپ فایل PDF (کپی بایت‌به‌بایت، بدون خرابکاری) ---------------- */
    public static boolean printFile(final Activity act, File pdf, String jobName, int pageCount) {
        try {
            PrintManager pm = (PrintManager) act.getSystemService(Context.PRINT_SERVICE);
            if (pm == null) return false;
            final File src = pdf;
            PrintDocumentAdapter adapter = new PrintDocumentAdapter() {
                @Override
                public void onLayout(PrintAttributes oldA, PrintAttributes newA,
                                     final android.os.CancellationSignal signal,
                                     final LayoutResultCallback callback,
                                     android.os.Bundle extras) {
                    if (signal.isCanceled()) {
                        callback.onLayoutCancelled();
                        return;
                    }
                    PrintDocumentInfo info = new PrintDocumentInfo.Builder(jobName + ".pdf")
                            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                            .setPageCount(pageCount > 0 ? pageCount : PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                            .build();
                    callback.onLayoutFinished(info, true);
                }

                @Override
                public void onWrite(PageRange[] ranges, ParcelFileDescriptor destination,
                                    final android.os.CancellationSignal signal,
                                    final WriteResultCallback callback) {
                    FileInputStream in = null;
                    OutputStream out = null;
                    try {
                        in = new FileInputStream(src);
                        out = new FileOutputStream(destination.getFileDescriptor());
                        byte[] buf = new byte[65536];
                        int r;
                        while ((r = in.read(buf)) > 0) {
                            if (signal.isCanceled()) {
                                callback.onWriteCancelled();
                                return;
                            }
                            out.write(buf, 0, r);
                        }
                        out.flush();
                        callback.onWriteFinished(new PageRange[]{PageRange.ALL_PAGES});
                    } catch (Exception e) {
                        callback.onWriteFailed(String.valueOf(e.getMessage()));
                    } finally {
                        try { if (in != null) in.close(); } catch (Exception ignored) {}
                        try { if (out != null) out.close(); } catch (Exception ignored) {}
                    }
                }
            };
            pm.print(jobName, adapter, new PrintAttributes.Builder().build());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /* ---------------- ساخت DOCX از متن صفحات ---------------- */
    public static File buildDocx(File outFile, String docTitle, ArrayList<String[]> pageLines) throws Exception {
        FileOutputStream fos = new FileOutputStream(outFile);
        ZipOutputStream zip = new ZipOutputStream(fos);
        try {
            putZip(zip, "[Content_Types].xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                    + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                    + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                    + "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>"
                    + "</Types>");
            putZip(zip, "_rels/.rels",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
                    + "</Relationships>");
            putZip(zip, "word/_rels/document.xml.rels",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>"
                    + "</Relationships>");
            putZip(zip, "word/styles.xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                    + "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                    + "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\">"
                    + "<w:name w:val=\"Normal\"/><w:qFormat/>"
                    + "<w:rPr><w:sz w:val=\"24\"/><w:szCs w:val=\"24\"/></w:rPr>"
                    + "</w:style></w:styles>");

            StringBuilder doc = new StringBuilder();
            doc.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
               .append("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">")
               .append("<w:body>");
            /* عنوان سند */
            doc.append(par(true, docTitle, 32, true, "2DD4BF"));
            for (int p = 0; p < pageLines.size(); p++) {
                String[] lines = pageLines.get(p);
                doc.append(par(true, "\u0635\u0641\u062d\u0647\u0654 " + Fa.d(p + 1), 20, true, "8DA0BC"));
                boolean any = false;
                for (String ln : lines) {
                    if (ln == null) continue;
                    String t = ln.trim();
                    if (t.length() == 0) continue;
                    any = true;
                    doc.append(par(isRtl(t), t, 24, false, null));
                }
                if (!any) {
                    doc.append(par(true, "\u200f[\u0627\u06cc\u0646 \u0635\u0641\u062d\u0647 \u0645\u062a\u0646 \u0642\u0627\u0628\u0644 \u0627\u0633\u062a\u062e\u0631\u0627\u062c \u0646\u062f\u0627\u0631\u062f \u2014 \u062a\u0635\u0648\u06cc\u0631\u06cc \u0627\u0633\u062a]", 22, false, "8DA0BC"));
                }
                if (p < pageLines.size() - 1) {
                    doc.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>");
                }
            }
            doc.append("<w:sectPr>")
               .append("<w:pgSz w:w=\"11906\" w:h=\"16838\"/>")
               .append("<w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\"/>")
               .append("<w:bidi/>")
               .append("</w:sectPr>");
            doc.append("</w:body></w:document>");
            putZip(zip, "word/document.xml", doc.toString());
        } finally {
            try { zip.close(); } catch (Exception ignored) {}
            try { fos.close(); } catch (Exception ignored) {}
        }
        return outFile;
    }

    private static String par(boolean rtl, String text, int halfPts, boolean bold, String colorHex) {
        StringBuilder sb = new StringBuilder();
        sb.append("<w:p><w:pPr>");
        if (rtl) sb.append("<w:bidi/><w:jc w:val=\"right\"/>");
        else sb.append("<w:jc w:val=\"left\"/>");
        sb.append("</w:pPr>");
        appendScriptSplitRuns(sb, text == null ? "" : text, null, halfPts, bold, false, colorHex, rtl);
        sb.append("</w:p>");
        return sb.toString();
    }

    static boolean isStrongRtlChar(char c) {
        if ((c >= 0x0660 && c <= 0x0669) || (c >= 0x06F0 && c <= 0x06F9)) return false;
        if (c == '\u060C' || c == '\u061B' || c == '\u061F' || c == '\u066A'
                || c == '\u066B' || c == '\u066C' || c == '\u066D' || c == '\u06D4') return false;
        return (c >= 0x0590 && c <= 0x08FF) || (c >= 0xFB1D && c <= 0xFDFF) || (c >= 0xFE70 && c <= 0xFEFF);
    }

    static boolean isStrongLtrChar(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9')
                || (c >= 0x0660 && c <= 0x0669) || (c >= 0x06F0 && c <= 0x06F9)
                || (c >= 0x00C0 && c <= 0x024F && c != 0x00D7 && c != 0x00F7);
    }

    static boolean isArabicOrPersianRtlDoc(String s) {
        if (s == null) return false;
        int rtl = 0, ltr = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (isStrongRtlChar(c)) rtl++;
            else if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) ltr++;
        }
        if (rtl == 0) return false;
        if (ltr == 0) return true;
        return rtl * 3 >= ltr;
    }

    private static boolean isRtl(String s) {
        return isArabicOrPersianRtlDoc(s);
    }

    private static boolean isPersianOnlyFont(String fname) {
        if (fname == null) return false;
        String low = fname.toLowerCase(java.util.Locale.US);
        return low.startsWith("b ") || low.contains("nazanin") || low.contains("iransans")
                || low.contains("vazir") || low.contains("sahel") || low.contains("samim")
                || low.contains("shabnam") || low.contains("estedad") || low.contains("parastoo")
                || low.contains("noto naskh") || low.contains("noto kufi") || low.contains("amiri")
                || low.contains("scheherazade");
    }

    static void appendScriptSplitRuns(StringBuilder out, String text, String fname, int szHalf,
                                      boolean bold, boolean italic, String colorHex, boolean paraRtl) {
        if (text == null || text.length() == 0) return;
        int n = text.length();
        boolean curRtl = paraRtl;
        for (int k = 0; k < n; k++) {
            char ck = text.charAt(k);
            if (isStrongRtlChar(ck)) { curRtl = true; break; }
            if (isStrongLtrChar(ck)) { curRtl = false; break; }
        }
        StringBuilder seg = new StringBuilder();
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            boolean nextIsStrong = false, nextStrongRtl = curRtl;
            if (isStrongRtlChar(c)) {
                nextIsStrong = true;
                nextStrongRtl = true;
            } else if (isStrongLtrChar(c)) {
                nextIsStrong = true;
                nextStrongRtl = false;
            } else {
                /* Neutral/space/punct: stay with curRtl if the next strong char matches curRtl;
                   if crossing from RTL to LTR or vice-versa at a space/bracket, keep trailing/leading boundary
                   neutrals aligned with paraRtl so Word renders bidi spacing cleanly. */
                boolean foundNext = false, upcomingRtl = paraRtl;
                for (int k = i + 1; k < n; k++) {
                    char ck = text.charAt(k);
                    if (isStrongRtlChar(ck)) { foundNext = true; upcomingRtl = true; break; }
                    if (isStrongLtrChar(ck)) { foundNext = true; upcomingRtl = false; break; }
                }
                if (foundNext && upcomingRtl != curRtl && curRtl != paraRtl) {
                    nextIsStrong = true;
                    nextStrongRtl = upcomingRtl;
                }
            }
            if (nextIsStrong && nextStrongRtl != curRtl && seg.length() > 0) {
                emitOneRun(out, seg.toString(), fname, szHalf, bold, italic, colorHex, curRtl);
                seg.setLength(0);
            }
            if (nextIsStrong) curRtl = nextStrongRtl;
            seg.append(c);
        }
        if (seg.length() > 0) {
            emitOneRun(out, seg.toString(), fname, szHalf, bold, italic, colorHex, curRtl);
        }
    }

    private static void emitOneRun(StringBuilder out, String t, String fname, int szHalf,
                                   boolean bold, boolean italic, String colorHex, boolean rtl) {
        if (t == null || t.length() == 0) return;
        out.append("<w:r><w:rPr>");
        if (fname != null) {
            String escF = xmlEsc(fname);
            if (!rtl && isPersianOnlyFont(fname)) {
                out.append("<w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:cs=\"").append(escF).append("\"/>");
            } else {
                out.append("<w:rFonts w:ascii=\"").append(escF)
                        .append("\" w:hAnsi=\"").append(escF)
                        .append("\" w:cs=\"").append(escF).append("\"/>");
            }
        }
        if (bold) out.append("<w:b/><w:bCs/>");
        if (italic) out.append("<w:i/><w:iCs/>");
        if (colorHex != null) out.append("<w:color w:val=\"").append(colorHex).append("\"/>");
        out.append("<w:sz w:val=\"").append(szHalf).append("\"/><w:szCs w:val=\"").append(szHalf).append("\"/>");
        if (rtl) out.append("<w:rtl/>");
        out.append("</w:rPr><w:t xml:space=\"preserve\">").append(xmlEsc(t)).append("</w:t></w:r>");
    }

    private static String xmlEsc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                    sb.append(c).append(s.charAt(++i));
                }
                continue;
            }
            if (Character.isLowSurrogate(c)) continue;
            if (c == '&') sb.append("&amp;");
            else if (c == '<') sb.append("&lt;");
            else if (c == '>') sb.append("&gt;");
            else if (c == '"') sb.append("&quot;");
            else if (c == '\'') sb.append("&apos;");
            else if (c < 0x20 && c != '\t') sb.append(' ');
            else if (c == 0xFFFE || c == 0xFFFF) continue;
            else sb.append(c);
        }
        return sb.toString();
    }



    /* ================================================================
       v1.38: تبدیل به Word — نسخهٔ ۲ (حافظ چیدمان)
       ----------------------------------------------------------------
       ورودی: خروجی استخراج layout از موتور pdf.js (به‌ازای هر صفحه:
       ابعاد + جدول فونت‌ها + واژه‌ها با جعبه/اندازه/فونت/جهت/خط).
       خروجی: DOCX که مثل سند اصلی می‌شود:
       — راست‌به‌چپ/چپ‌به‌راستِ هر پاراگراف جدا تشخیص و اعمال می‌شود
       — فونت (نام واقعی، پاک‌شده)، اندازهٔ خط (pt→half-point)،
         توپُر/مورب از خود PDF خوانده می‌شود
       — پاراگراف‌بندی از فاصلهٔ خطوط، تراز (راست/چپ/وسط/justify)
         از لبه‌های خطوط، تورفتگی از ستون متن
       ================================================================ */

    /** یک صفحهٔ استخراج‌شدهٔ layout */
    public static final class LayoutPage {
        public float vw = 595f, vh = 842f;      /* نقاط PDF */
        public String[][] fonts = new String[0][]; /* {name, bold, italic, generic} */
        public String[][] words = new String[0][]; /* {t,x0,y0,x1,y1,fs,fi,rtl,line,gap,zg} */
        /* v1.43: تصاویر صفحه — {x0,y0,x1,y1} در مختصات viewport (هم‌راستا با واژه‌ها) */
        public float[][] imgs = new float[0][];
        public boolean valid = false;
    }

    /* v1.43: منبع رندر تصویر — ReaderOffice صفحه را با pdfium رندر و برش می‌دهد */
    public interface ImageSource {
        /** رندر ناحیهٔ [x0,y0,x1,y1] از صفحهٔ pageIdx (۰-مبنا)؛ خروجی: بایت‌های
            PNG/JPEG + ابعاد پیکسلی در out[0],out[1]؛ null = رد شدن از این تصویر */
        byte[] renderRegion(int pageIdx, float x0, float y0, float x1, float y1, int[] out);
    }

    /** json → LayoutPage (آرام — خطا یعنی صفحهٔ بی‌متن) */
    public static LayoutPage parseLayout(String json) {
        LayoutPage p = new LayoutPage();
        if (json == null || json.length() == 0) return p;
        try {
            JSONObject o = new JSONObject(json);
            p.vw = (float) o.optDouble("vw", 595);
            p.vh = (float) o.optDouble("vh", 842);
            JSONArray f = o.optJSONArray("f");
            if (f != null) {
                p.fonts = new String[f.length()][];
                for (int i = 0; i < f.length(); i++) {
                    JSONArray e = f.optJSONArray(i);
                    String name = e != null && e.length() > 0 ? e.optString(0, "") : "";
                    String bold = e != null && e.length() > 1 ? e.optString(1, "0") : "0";
                    String ital = e != null && e.length() > 2 ? e.optString(2, "0") : "0";
                    String generic = e != null && e.length() > 3 ? e.optString(3, "") : "";
                    p.fonts[i] = new String[]{name, bold, ital, generic};
                }
            }
            JSONArray w = o.optJSONArray("w");
            if (w != null) {
                java.util.ArrayList<String[]> rows = new java.util.ArrayList<>(w.length());
                for (int i = 0; i < w.length(); i++) {
                    JSONArray e = w.optJSONArray(i);
                    if (e == null || e.length() < 10) continue;
                    /* v1.41: ۱۱مین عنصر = zg (۱ یعنی جداکنندهٔ قبلِ واژه نیم‌فاصلهٔ
                       چسبیده بوده — در Word هم نیم‌فاصله می‌نویسیم، نه فاصله) */
                    int n = Math.max(10, e.length());
                    String[] row = new String[n];
                    for (int k = 0; k < n; k++) row[k] = e.optString(k, k == 0 ? "" : "0");
                    rows.add(row);
                }
                p.words = rows.toArray(new String[0][]);
            }
            /* v1.43: تصاویر صفحه */
            JSONArray im = o.optJSONArray("im");
            if (im != null) {
                java.util.ArrayList<float[]> ims = new java.util.ArrayList<>(im.length());
                for (int i = 0; i < im.length(); i++) {
                    JSONArray e = im.optJSONArray(i);
                    if (e == null || e.length() < 4) continue;
                    try {
                        ims.add(new float[]{(float) e.optDouble(0), (float) e.optDouble(1),
                                (float) e.optDouble(2), (float) e.optDouble(3)});
                    } catch (Throwable ignored2) {
                    }
                }
                p.imgs = ims.toArray(new float[0][]);
            }
            p.valid = true;
        } catch (Throwable ignored) {
            p.valid = false;
        }
        return p;
    }

    /* ---------- نام فونت: پاک‌سازی و نگاشت به نام امن Word ---------- */

    private static final String[][] FONT_MAP = {
            {"times", "Times New Roman"},
            {"georgia", "Georgia"},
            {"garamond", "Garamond"},
            {"arial", "Arial"},
            {"helvetica", "Arial"},
            {"calibri", "Calibri"},
            {"segoe", "Segoe UI"},
            {"tahoma", "Tahoma"},
            {"verdana", "Verdana"},
            {"trebuchet", "Trebuchet MS"},
            {"courier", "Courier New"},
            {"consolas", "Consolas"},
            {"mono", "Courier New"},
            {"dejavu", "DejaVu Sans"},
            {"nazanin", "B Nazanin"},
            {"zar", "B Zar"},
            {"mitra", "B Mitra"},
            {"lotus", "B Lotus"},
            {"yekan", "B Yekan"},
            {"roya", "B Roya"},
            {"titr", "B Titr"},
            {"koodak", "B Koodak"},
            {"homa", "B Homa"},
            {"yagut", "B Yagut"},
            {"traffic", "B Traffic"},
            {"badr", "B Badr"},
            {"compset", "B Compset"},
            {"irsans", "IRANSans"},
            {"iransans", "IRANSans"},
            {"vazirmatn", "Vazirmatn"},
            {"vazir", "Vazirmatn"},
            {"sahel", "Sahel"},
            {"samim", "Samim"},
            {"shabnam", "Shabnam"},
            {"estedad", "Estedad"},
            {"parastoo", "Parastoo"},
            {"kufi", "Noto Kufi Arabic"},
            {"naskh", "Noto Naskh Arabic"},
            {"noto", "Noto Naskh Arabic"},
            {"amiri", "Amiri"},
            {"scheherazade", "Scheherazade"},
    };

    /** نام PDF → نام امن برای Word؛ null = فونت نامعلوم */
    static String cleanFontName(String raw) {
        if (raw == null) return null;
        String n = raw.trim();
        if (n.length() == 0) return null;
        /* پیشوند زیرمجموعه: ABCDEF+ArialMT */
        if (n.length() > 7 && n.charAt(6) == '+' && n.indexOf('+', 7) < 0) {
            n = n.substring(7);
        }
        String low = n.toLowerCase(java.util.Locale.US);
        for (String[] m : FONT_MAP) {
            if (low.contains(m[0])) return m[1];
        }
        /* نام‌های رمزی pdf.js یا استاندارد نبود → null (به Word واگذار می‌کنیم) */
        if (low.startsWith("g_d") || low.startsWith("sans") || low.startsWith("serif")) return null;
        /* فقط حروف/فاصله/خط‌تیره نگه دار — نام‌های عجیب سند را خراب نکنند */
        n = n.replaceAll("[^\\p{L}\\p{N} _\\-]", "");
        n = n.trim();
        return n.length() == 0 ? null : n;
    }

    static boolean fontBold(String name, String flag) {
        if ("1".equals(flag)) return true;
        if (name == null) return false;
        String l = name.toLowerCase(java.util.Locale.US);
        return l.contains("bold") || l.contains("black") || l.contains("heavy")
                || l.contains("semibold") || l.contains("demibold");
    }

    static boolean fontItalic(String name, String flag) {
        if ("1".equals(flag)) return true;
        if (name == null) return false;
        String l = name.toLowerCase(java.util.Locale.US);
        return l.contains("italic") || l.contains("oblique") || l.contains("kursiv");
    }

    /* ---------- ساخت DOCX حافظ چیدمان ---------- */

    public static File buildDocxLayout(File outFile, String docTitle, ArrayList<LayoutPage> pages,
                                        ImageSource images) throws Exception {
        /* اندازهٔ صفحهٔ بخش از صفحهٔ اول (A4 پیش‌فرض) */
        float pw = 595f, ph = 842f;
        for (LayoutPage p : pages) {
            if (p.valid && p.vw > 100 && p.vh > 100) {
                pw = p.vw;
                ph = p.vh;
                break;
            }
        }
        int pgW = Math.round(pw * 20), pgH = Math.round(ph * 20);

        FileOutputStream fos = new FileOutputStream(outFile);
        ZipOutputStream zip = new ZipOutputStream(fos);
        try {
            /* v1.43: تصاویر (media) در بخش پایین نوشته می‌شوند؛ نوعهای پیش‌فرض png/jpeg همین‌جا */
            java.util.ArrayList<String[]> imgRels = new ArrayList<>();  /* {rid, target} */
            java.util.ArrayList<byte[]> imgData = new ArrayList<>();
            java.util.ArrayList<String> imgExt = new ArrayList<>();
            putZip(zip, "[Content_Types].xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                            + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                            + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                            + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                            + "<Default Extension=\"png\" ContentType=\"image/png\"/>"
                            + "<Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/>"
                            + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                            + "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>"
                            + "<Override PartName=\"/word/settings.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml\"/>"
                            + "</Types>");
            putZip(zip, "_rels/.rels",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                            + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                            + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
                            + "</Relationships>");
            /* استایل پایه + docDefaults — Word بدون این‌ها فونت/جهت پیش‌فرض را
               خودش انتخاب می‌کرد و سند «شکل سند اصلی» را از دست می‌داد */
            putZip(zip, "word/styles.xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                            + "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                            + "<w:docDefaults><w:rPrDefault><w:rPr>"
                            + "<w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:cs=\"Tahoma\"/>"
                            + "<w:sz w:val=\"24\"/><w:szCs w:val=\"24\"/>"
                            + "</w:rPr></w:rPrDefault><w:pPrDefault><w:pPr>"
                            + "<w:spacing w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/>"
                            + "</w:pPr></w:pPrDefault></w:docDefaults>"
                            + "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\">"
                            + "<w:name w:val=\"Normal\"/><w:qFormat/>"
                            + "</w:style></w:styles>");
            /* تنظیمات: فعال‌بودن استفاده از قابلیت‌های bidi سند */
            putZip(zip, "word/settings.xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                            + "<w:settings xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                            + "<w:zoom w:percent=\"100\"/>"
                            + "<w:defaultTabStop w:val=\"720\"/>"
                            + "<w:compat><w:compatSetting w:name=\"compatibilityMode\" w:uri=\"http://schemas.microsoft.com/office/word\" w:val=\"15\"/></w:compat>"
                            + "</w:settings>");

            int docRtlChars = 0, docLtrChars = 0;
            for (LayoutPage p : pages) {
                if (!p.valid || p.words == null) continue;
                for (String[] w : p.words) {
                    if (w == null || w[0] == null) continue;
                    for (int ci = 0; ci < w[0].length(); ci++) {
                        char c = w[0].charAt(ci);
                        if (isStrongRtlChar(c)) docRtlChars++;
                        else if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) docLtrChars++;
                    }
                }
            }
            boolean docRtl = docRtlChars > 0 && docRtlChars >= docLtrChars;
            StringBuilder doc = new StringBuilder();
            doc.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
                    .append("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">")
                    .append("<w:body>");
            /* عنوان سند */
            doc.append("<w:p><w:pPr><w:bidi/><w:jc w:val=\"center\"/></w:pPr>");
            appendScriptSplitRuns(doc, docTitle == null ? "" : docTitle, null, 34, true, false, "2DD4BF", docRtl);
            doc.append("</w:p>");

            for (int p = 0; p < pages.size(); p++) {
                LayoutPage lp = pages.get(p);
                /* برچسب صفحه — کوچک و کم‌رنگ */
                doc.append("<w:p><w:pPr><w:bidi/><w:jc w:val=\"center\"/></w:pPr>");
                appendScriptSplitRuns(doc, "\u0635\u0641\u062d\u0647\u0654 " + Fa.d(p + 1), null, 18, false, false, "8DA0BC", true);
                doc.append("</w:p>");
                try {
                    appendPageParas(doc, lp, p, docRtl, images, imgRels, imgData, imgExt);
                } catch (Throwable pageErr) {
                    doc.append(parRtl("\u200f[\u0627\u06cc\u0646 \u0635\u0641\u062d\u0647 \u0645\u062a\u0646 \u0642\u0627\u0628\u0644 \u0627\u0633\u062a\u062e\u0631\u0627\u062c \u0646\u062f\u0627\u0631\u062f]"));
                }
                if (p < pages.size() - 1) {
                    doc.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>");
                }
            }

            doc.append("<w:sectPr>")
                    .append("<w:pgSz w:w=\"").append(pgW).append("\" w:h=\"").append(pgH).append("\"/>")
                    .append("<w:pgMar w:top=\"720\" w:right=\"720\" w:bottom=\"720\" w:left=\"720\"/>");
            if (docRtl) doc.append("<w:bidi/>");
            doc.append("</w:sectPr>");
            doc.append("</w:body></w:document>");
            /* v1.43: پاراگرافهای تصویری بالا رشتهٔ rels را پر کرده‌اند —
               ابتدا media و رابطه‌ها را بنویس، بعد سند اصلی */
            StringBuilder rels = new StringBuilder(
                    "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                            + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                            + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>"
                            + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/settings\" Target=\"settings.xml\"/>");
            for (int q = 0; q < imgRels.size(); q++) {
                rels.append("<Relationship Id=\"").append(imgRels.get(q)[0])
                        .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"")
                        .append(imgRels.get(q)[1]).append("\"/>");
            }
            rels.append("</Relationships>");
            putZip(zip, "word/_rels/document.xml.rels", rels.toString());
            for (int q = 0; q < imgData.size(); q++) {
                putZipBytes(zip, "word/" + imgRels.get(q)[1], imgData.get(q));  /* target نسبت به word/ است */
            }
            putZip(zip, "word/document.xml", doc.toString());
        } finally {
            try { zip.close(); } catch (Exception ignored) {}
            try { fos.close(); } catch (Exception ignored) {}
        }
        return outFile;
    }

    private static String parRtl(String text) {
        return "<w:p><w:pPr><w:bidi/></w:pPr><w:r><w:rPr>"
                + "<w:color w:val=\"8DA0BC\"/><w:sz w:val=\"20\"/><w:szCs w:val=\"20\"/><w:rtl/></w:rPr>"
                + "<w:t xml:space=\"preserve\">" + xmlEsc(text) + "</w:t></w:r></w:p>";
    }

    /* ---------- الگوریتم صفحه → پاراگراف ---------- */

    private static void appendPageParas(StringBuilder doc, LayoutPage lp, int pageIdx, boolean docRtl,
                                        ImageSource images, ArrayList<String[]> imgRels,
                                        ArrayList<byte[]> imgData, ArrayList<String> imgExt) {
        /* v1.43: تصاویر قابل‌استفادهٔ این صفحه (پالایش: حداقل اندازه، بدون پوشاندن متن) */
        ArrayList<float[]> usable = new ArrayList<>();
        boolean hasWords = lp.valid && lp.words.length > 0;
        for (float[] r : lp.imgs) {
            if (r == null || r.length < 4) continue;
            float w = r[2] - r[0], h = r[3] - r[1];
            if (w < 24f || h < 24f) continue;                       /* گلوله/خط جداکننده */
            if (w > lp.vw * 0.92f && h > lp.vh * 0.92f && hasWords) continue;  /* زمینهٔ کل‌صفحه */
            if (hasWords && wordsCover(lp, r) > 0.30f) continue;    /* واترمارک زیر متن */
            usable.add(r);
        }
        if (!hasWords) {
            if (usable.isEmpty() && (!lp.valid || lp.imgs.length == 0)) {
                doc.append(parRtl("\u200f[\u0627\u06cc\u0646 \u0635\u0641\u062d\u0647 \u0645\u062a\u0646 \u0642\u0627\u0628\u0644 \u0627\u0633\u062a\u062e\u0631\u0627\u062c \u0646\u062f\u0627\u0631\u062f]"));
                return;
            }
            for (float[] r : usable) {
                appendImagePar(doc, pageIdx, r, images, imgRels, imgData, imgExt);
            }
            if (usable.isEmpty()) {
                doc.append(parRtl("\u200f[\u0627\u06cc\u0646 \u0635\u0641\u062d\u0647 \u0645\u062a\u0646 \u0642\u0627\u0628\u0644 \u0627\u0633\u062a\u062e\u0631\u0627\u062c \u0646\u062f\u0627\u0631\u062f]"));
            }
            return;
        }

        int pageRtlChars = 0, pageLtrChars = 0;
        for (String[] w : lp.words) {
            if (w == null || w[0] == null) continue;
            for (int ci = 0; ci < w[0].length(); ci++) {
                char c = w[0].charAt(ci);
                if (isStrongRtlChar(c)) pageRtlChars++;
                else if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) pageLtrChars++;
            }
        }
        boolean pageRtl = (pageRtlChars + pageLtrChars > 0) ? (pageRtlChars >= pageLtrChars) : docRtl;

        /* ۱) خطوط: واژه‌ها از قبل شمارهٔ خط (یا برش ستونی) دارند */
        ArrayList<int[]> lineSpans = new ArrayList<>(); /* {start,end} در words */
        int curLine = Integer.MIN_VALUE, start = -1;
        float prevLineY = Float.NaN;
        for (int i = 0; i < lp.words.length; i++) {
            int line = parseIntSafe(lp.words[i][8], 0);
            float wy0 = parseFloatSafe(lp.words[i][2]);
            boolean colJump = !Float.isNaN(prevLineY) && wy0 < prevLineY - 12f;
            if (line != curLine || colJump) {
                if (start >= 0) lineSpans.add(new int[]{start, i - 1});
                curLine = line;
                start = i;
            }
            prevLineY = wy0;
        }
        if (start >= 0) lineSpans.add(new int[]{start, lp.words.length - 1});
        if (lineSpans.isEmpty()) return;

        /* ۲) مشخصه‌های هر خط (شمارش کاراکترهای قوی فارسی و لاتین در سطح حرف) */
        int n = lineSpans.size();
        float[] lx0 = new float[n], lx1 = new float[n], ly0 = new float[n], ly1 = new float[n];
        float[] lfs = new float[n];
        int[] lfont = new int[n], lRtlCh = new int[n], lLtrCh = new int[n];
        boolean[] lrtl = new boolean[n];
        for (int li = 0; li < n; li++) {
            int[] span = lineSpans.get(li);
            float x0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y0 = Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
            float fsMax = 0, fsSum = 0;
            int fsCnt = 0, lineRtlC = 0, lineLtrC = 0;
            int[] fontVote = new int[Math.max(1, lp.fonts.length)];
            for (int i = span[0]; i <= span[1]; i++) {
                String[] w = lp.words[i];
                if (w == null) continue;
                float wx0 = parseFloatSafe(w[1]), wy0 = parseFloatSafe(w[2]);
                float wx1 = parseFloatSafe(w[3]), wy1 = parseFloatSafe(w[4]);
                float wfs = parseFloatSafe(w[5]);
                if (wx0 < x0) x0 = wx0;
                if (wx1 > x1) x1 = wx1;
                if (wy0 < y0) y0 = wy0;
                if (wy1 > y1) y1 = wy1;
                if (wfs > fsMax) fsMax = wfs;
                fsSum += wfs;
                fsCnt++;
                if (w[0] != null) {
                    for (int ci = 0; ci < w[0].length(); ci++) {
                        char c = w[0].charAt(ci);
                        if (isStrongRtlChar(c)) lineRtlC++;
                        else if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) lineLtrC++;
                    }
                }
                int fi = parseIntSafe(w[6], -1);
                if (fi >= 0 && fi < fontVote.length) fontVote[fi]++;
            }
            lx0[li] = x0;
            lx1[li] = x1;
            ly0[li] = y0;
            ly1[li] = y1;
            lfs[li] = fsCnt > 0 ? fsSum / fsCnt : fsMax;
            lRtlCh[li] = lineRtlC;
            lLtrCh[li] = lineLtrC;
            if (lineRtlC > 0 && lineLtrC == 0) lrtl[li] = true;
            else if (lineLtrC > 0 && lineRtlC == 0) lrtl[li] = false;
            else if (lineRtlC > 0 && lineLtrC > 0) lrtl[li] = (lineLtrC > lineRtlC * 3 && !pageRtl) ? false : pageRtl;
            else lrtl[li] = pageRtl;
            int bestF = -1, bestC = 0;
            for (int fi = 0; fi < fontVote.length; fi++) {
                if (fontVote[fi] > bestC) {
                    bestC = fontVote[fi];
                    bestF = fi;
                }
            }
            lfont[li] = bestF;
        }

        /* ۳) ستون متن صفحه: رایج‌ترین لبهٔ چپ/راست خطوط */
        float colL = Float.MAX_VALUE, colR = -Float.MAX_VALUE;
        {
            java.util.HashMap<String, Integer> vote = new java.util.HashMap<>();
            for (int li = 0; li < n; li++) {
                if (lx1[li] - lx0[li] < lp.vw * 0.35f) continue;
                String key = Math.round(lx0[li] / 6f) + ":" + Math.round(lx1[li] / 6f);
                Integer c = vote.get(key);
                vote.put(key, c == null ? 1 : c + 1);
            }
            String bestK = null;
            int bestC = 0;
            for (java.util.Map.Entry<String, Integer> e : vote.entrySet()) {
                if (e.getValue() > bestC) {
                    bestC = e.getValue();
                    bestK = e.getKey();
                }
            }
            if (bestK != null && bestC >= 2) {
                try {
                    String[] ab = bestK.split(":");
                    colL = Float.parseFloat(ab[0]) * 6f;
                    colR = Float.parseFloat(ab[1]) * 6f;
                } catch (Throwable ignored) {
                }
            }
            if (colL == Float.MAX_VALUE || colR <= colL) {
                colL = Float.MAX_VALUE;
                colR = -Float.MAX_VALUE;
                for (int li = 0; li < n; li++) {
                    if (lx0[li] < colL) colL = lx0[li];
                    if (lx1[li] > colR) colR = lx1[li];
                }
            }
        }

        /* گام پایهٔ خطوط صفحه (برای تشخیص درستِ ۱.۵ برابر فاصلهٔ خط در اسناد فارسی) */
        ArrayList<Float> pitches = new ArrayList<>();
        for (int li = 1; li < n; li++) {
            float g = ly0[li] - ly0[li - 1];
            float fs = Math.max(6f, Math.max(lfs[li], lfs[li - 1]));
            if (g >= fs * 0.85f && g <= fs * 2.45f) pitches.add(g);
        }
        float basePitch = 0f;
        if (!pitches.isEmpty()) {
            java.util.Collections.sort(pitches);
            basePitch = pitches.get(pitches.size() / 2);
        }

        /* ۴) پاراگراف‌بندی: شکست وقتی فاصلهٔ خط > سقفِ فاصله، تغییر ستون (gap < -2)، یا تغییر درشت اندازه */
        ArrayList<int[]> paras = new ArrayList<>();
        {
            int pStart = 0;
            for (int li = 1; li < n; li++) {
                float gap = ly0[li] - ly0[li - 1];
                float fs = Math.max(6f, Math.max(lfs[li], lfs[li - 1]));
                boolean fsJump = Math.abs(lfs[li] - lfs[li - 1]) > Math.max(1.5f, fs * 0.28f);
                float maxGap = basePitch > 0f ? Math.max(fs * 2.0f, basePitch * 1.45f) : (fs * 2.0f);
                boolean bigGap = gap > maxGap || gap < -2.0f;
                if (fsJump || bigGap) {
                    paras.add(new int[]{pStart, li - 1});
                    pStart = li;
                }
            }
            paras.add(new int[]{pStart, n - 1});
        }

        /* ۵) حفظ ترتیب خواندنِ ستونیِ پاراگراف‌ها + درج هر تصویر کنار بلوک متنیِ هم‌ستون/هم‌تراز */
        boolean[] imgPlaced = new boolean[usable.size()];
        for (int pi = 0; pi < paras.size(); pi++) {
            int[] span = paras.get(pi);
            float pTop = Float.MAX_VALUE, pLeft = Float.MAX_VALUE, pRight = -Float.MAX_VALUE;
            for (int li = span[0]; li <= span[1]; li++) {
                if (ly0[li] < pTop) pTop = ly0[li];
                if (lx0[li] < pLeft) pLeft = lx0[li];
                if (lx1[li] > pRight) pRight = lx1[li];
            }
            for (int ii = 0; ii < usable.size(); ii++) {
                if (imgPlaced[ii]) continue;
                float[] r = usable.get(ii);
                boolean horizOverlap = Math.min(r[2], pRight) >= Math.max(r[0], pLeft) - 20f;
                boolean wideImage = (r[2] - r[0]) > lp.vw * 0.45f;
                if ((horizOverlap || wideImage) && r[1] <= pTop + 4f) {
                    appendImagePar(doc, pageIdx, r, images, imgRels, imgData, imgExt);
                    imgPlaced[ii] = true;
                }
            }
            appendPara(doc, lp, lineSpans, span, lx0, lx1, ly0, lfs, lrtl, lRtlCh, lLtrCh, pageRtl, colL, colR);
        }
        for (int ii = 0; ii < usable.size(); ii++) {
            if (!imgPlaced[ii]) {
                appendImagePar(doc, pageIdx, usable.get(ii), images, imgRels, imgData, imgExt);
            }
        }
    }

    /* نسبت پوشیده‌شدن مستطیل تصویر با جعبهٔ واژه‌ها (تقریبی — برای واترمارک‌ها) */
    private static float wordsCover(LayoutPage lp, float[] r) {
        double cover = 0;
        for (String[] w : lp.words) {
            if (w == null || w[0] == null || w[0].length() == 0) continue;
            float wx0 = parseFloatSafe(w[1]), wy0 = parseFloatSafe(w[2]);
            float wx1 = parseFloatSafe(w[3]), wy1 = parseFloatSafe(w[4]);
            float lox = Math.max(wx0, r[0]), loy = Math.max(wy0, r[1]);
            float hix = Math.min(wx1, r[2]), hiy = Math.min(wy1, r[3]);
            if (hix > lox && hiy > loy) {
                double ov = (hix - lox) * (double) (hiy - loy);
                double wa = Math.max(1, (wx1 - wx0) * (double) Math.max(1, wy1 - wy0));
                cover += Math.min(ov, wa);
            }
        }
        double ra = Math.max(1, (r[2] - r[0]) * (double) Math.max(1, r[3] - r[1]));
        return (float) Math.min(1.0, cover / ra);
    }

    /* پاراگراف تصویری — رندر برش از pdfium + w:drawing درون‌خطی */
    private static void appendImagePar(StringBuilder doc, int pageIdx, float[] r,
                                       ImageSource images, ArrayList<String[]> imgRels,
                                       ArrayList<byte[]> imgData, ArrayList<String> imgExt) {
        if (images == null) return;
        try {
            int[] wh = new int[2];
            byte[] data = images.renderRegion(pageIdx, r[0], r[1], r[2], r[3], wh);
            if (data == null || wh[0] <= 4 || wh[1] <= 4) return;
            String rid = "rId" + (100 + imgData.size());
            String ext = imgExt.isEmpty() ? "png" : imgExt.get(imgData.size() - 1); /* set below */
            ext = "png";
            String target = "media/img" + (imgData.size() + 1) + "." + ext;  /* نسبت به word/ */
            imgRels.add(new String[]{rid, target});
            imgData.add(data);
            imgExt.add(ext);
            /* اندازهٔ نمایش: نصف عرض صفحه حداکثر، حفظ نسبت، حداکثر ۱۵ سانتی‌متر */
            float dispW = Math.min(r[2] - r[0], 280f);
            float ratio = (float) wh[1] / (float) wh[0];
            float dispH = dispW * ratio;
            if (dispH > 400f) {
                dispH = 400f;
                dispW = dispH / ratio;
            }
            long cx = Math.round(dispW * 12700f), cy = Math.round(dispH * 12700f);
            int docId = 100 + imgData.size();
            doc.append("<w:p><w:pPr><w:spacing w:after=\"80\"/><w:jc w:val=\"center\"/></w:pPr>")
                    .append("<w:r><w:drawing>")
                    .append("<wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\" xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\">")
                    .append("<wp:extent cx=\"").append(cx).append("\" cy=\"").append(cy).append("\"/>")
                    .append("<wp:effectExtent l=\"0\" t=\"0\" r=\"0\" b=\"0\"/>")
                    .append("<wp:docPr id=\"").append(docId).append("\" name=\"Picture ").append(docId).append("\"/>")
                    .append("<wp:cNvGraphicFramePr><a:graphicFrameLocks xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" noChangeAspect=\"1\"/></wp:cNvGraphicFramePr>")
                    .append("<a:graphic xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\">")
                    .append("<a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">")
                    .append("<pic:pic xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">")
                    .append("<pic:nvPicPr><pic:cNvPr id=\"").append(docId).append("\" name=\"").append(target).append("\"/><pic:cNvPicPr/></pic:nvPicPr>")
                    .append("<pic:blipFill><a:blip xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" r:embed=\"").append(rid).append("\"/>")
                    .append("<a:stretch><a:fillRect/></a:stretch></pic:blipFill>")
                    .append("<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"").append(cx).append("\" cy=\"").append(cy).append("\"/></a:xfrm>")
                    .append("<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr>")
                    .append("</pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>");
        } catch (Throwable ignored) {
        }
    }

    private static void appendPara(StringBuilder doc, LayoutPage lp, ArrayList<int[]> lineSpans,
                                   int[] para, float[] lx0, float[] lx1, float[] ly0, float[] lfs,
                                   boolean[] lrtl, int[] lRtlCh, int[] lLtrCh, boolean pageRtl,
                                   float colL, float colR) {
        int first = para[0], last = para[1];

        /* جهت پاراگراف: رأی خطوط + وزن کاراکترهای قوی فارسی/لاتین در صفحهٔ دوزبانه */
        int rtlN = 0, pRtlCh = 0, pLtrCh = 0;
        float fsMode = 0;
        for (int li = first; li <= last; li++) {
            if (lrtl[li]) rtlN++;
            pRtlCh += lRtlCh[li];
            pLtrCh += lLtrCh[li];
            if (lfs[li] > fsMode) fsMode = lfs[li];
        }
        int nLines = last - first + 1;
        boolean rtl;
        if (pRtlCh > 0 && pLtrCh == 0) rtl = true;
        else if (pLtrCh > 0 && pRtlCh == 0) rtl = false;
        else if (pRtlCh > 0 && pLtrCh > 0) {
            rtl = (pageRtl && pLtrCh <= pRtlCh * 3) || (rtlN * 2 > nLines);
        } else {
            rtl = pageRtl;
        }

        /* فاصلهٔ خطوط از گام خط‌ها (سطرهای متوالی) */
        int pitchCnt = 0;
        float pitchSum = 0;
        for (int li = first + 1; li <= last; li++) {
            float g = ly0[li] - ly0[li - 1];
            if (g > 4f && g < 100f) {
                pitchSum += g;
                pitchCnt++;
            }
        }
        float pitch = pitchCnt > 0 ? pitchSum / pitchCnt : fsMode * 1.35f;

        /* تراز: وسط‌چینی وقتی هر دو حاشیه پاراگراف از ستون تقریباً برابر دور است؛
           justify وقتی همهٔ خطوط هم‌عرض و پهن‌اند */
        float pL = Float.MAX_VALUE, pR = -Float.MAX_VALUE, minW = Float.MAX_VALUE, maxW = 0;
        for (int li = first; li <= last; li++) {
            if (lx0[li] < pL) pL = lx0[li];
            if (lx1[li] > pR) pR = lx1[li];
            float w = lx1[li] - lx0[li];
            if (w < minW) minW = w;
            if (w > maxW) maxW = w;
        }
        boolean center = Math.abs((pL - colL) - (colR - pR)) < lp.vw * 0.025f
                && (pL - colL) > lp.vw * 0.06f;
        boolean justify = (last - first + 1) >= 3
                && (maxW - minW) < lp.vw * 0.04f
                && (maxW) > (colR - colL) * 0.82f;

        /* تورفتگی نسبت به ستون (برای پاراگراف‌های تک‌خطی هرگز indEnd بزرگ اعمال نشود تا متن مچاله نشود) */
        float indStart, indEnd;
        if (rtl) {
            indStart = colR - pR;
            indEnd = (last > first) ? (pL - colL) : 0f;
        } else {
            indStart = pL - colL;
            indEnd = (last > first) ? (colR - pR) : 0f;
        }

        StringBuilder pPr = new StringBuilder("<w:pPr>");
        if (rtl) pPr.append("<w:bidi/>");
        int lineTw = Math.round(Math.max(1.05f, Math.min(2.4f, pitch / Math.max(4f, fsMode))) * 240f);
        pPr.append("<w:spacing w:after=\"40\" w:line=\"").append(lineTw).append("\" w:lineRule=\"auto\"/>");
        if (indStart > 8f) {
            pPr.append("<w:ind w:start=\"").append(Math.round(indStart * 20f)).append("\"");
            if (indEnd > 8f) pPr.append(" w:end=\"").append(Math.round(indEnd * 20f)).append("\"");
            pPr.append("/>");
        } else if (indEnd > 8f) {
            pPr.append("<w:ind w:end=\"").append(Math.round(indEnd * 20f)).append("\"/>");
        }
        if (center) pPr.append("<w:jc w:val=\"center\"/>");
        /* v1.42: justify ONLY on LTR — w:jc="both" on bidi paragraphs sends LibreOffice into hang */
        else if (justify && !rtl) pPr.append("<w:jc w:val=\"both\"/>");
        else if (rtl) pPr.append("<w:jc w:val=\"right\"/>");
        else pPr.append("<w:jc w:val=\"left\"/>");
        pPr.append("</w:pPr>");
        doc.append("<w:p>").append(pPr);

        /* ران‌ها: گروه‌بندی واژه‌های پشت‌سرهم با (فونت، اندازه، توپُر، مورب)؛
           جداکردنِ درونی بر اساس خطِ اسکریپت قوی در flushRun انجام می‌شود تا هرگز <w:rtl/> به لاتین و ارقام نخورد */
        int runLine = -1, runFont = -1, runSzHalf = -1;
        boolean runB = false, runI = false, runOpen = false;
        StringBuilder runText = new StringBuilder();
        for (int li = first; li <= last; li++) {
            int[] span = lineSpans.get(li);
            for (int i = span[0]; i <= span[1]; i++) {
                String[] w = lp.words[i];
                if (w == null || w[0] == null || w[0].length() == 0) continue;
                float fs = parseFloatSafe(w[5]);
                int fi = parseIntSafe(w[6], -1);
                boolean b = false, it = false;
                if (fi >= 0 && fi < lp.fonts.length && lp.fonts[fi] != null) {
                    b = fontBold(lp.fonts[fi][0], lp.fonts[fi][1]);
                    it = fontItalic(lp.fonts[fi][0], lp.fonts[fi][2]);
                }
                int szHalf = Math.max(12, Math.min(200, Math.round(fs * 2f)));
                if (runOpen && (li != runLine || fi != runFont || szHalf != runSzHalf
                        || b != runB || it != runI)) {
                    if (li != runLine && runText.length() > 0) runText.append(' ');
                    else if ("0".equals(w[9]) && runText.length() > 0) {
                        runText.append(w.length > 10 && "1".equals(w[10]) ? '\u200C' : ' ');
                    }
                    flushRun(doc, runText, runFont, runSzHalf, runB, runI, rtl, lp);
                    runText.setLength(0);
                    runOpen = false;
                }
                if (!runOpen) {
                    runOpen = true;
                    runLine = li;
                    runFont = fi;
                    runSzHalf = szHalf;
                    runB = b;
                    runI = it;
                }
                if (runText.length() > 0 && "0".equals(w[9])) {
                    runText.append(w.length > 10 && "1".equals(w[10]) ? '\u200C' : ' ');
                }
                runText.append(w[0]);
            }
        }
        if (runOpen) {
            flushRun(doc, runText, runFont, runSzHalf, runB, runI, rtl, lp);
        }
        doc.append("</w:p>");
    }

    private static void flushRun(StringBuilder doc, StringBuilder text, int fontIdx,
                                 int szHalf, boolean bold, boolean italic, boolean paraRtl,
                                 LayoutPage lp) {
        String t = text.toString();
        int ti = 0;
        while (ti < t.length() && (t.charAt(ti) == ' ' || t.charAt(ti) == '\u200C')) ti++;
        if (ti > 0) t = t.substring(ti);
        if (t.length() == 0) return;
        String fname = null;
        if (fontIdx >= 0 && fontIdx < lp.fonts.length && lp.fonts[fontIdx] != null) {
            fname = cleanFontName(lp.fonts[fontIdx][0]);
        }
        appendScriptSplitRuns(doc, t, fname, szHalf, bold, italic, null, paraRtl);
    }

    private static float parseFloatSafe(String s) {
        try {
            return Float.parseFloat(s);
        } catch (Throwable t) {
            return 0f;
        }
    }

    private static int parseIntSafe(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (Throwable t) {
            return def;
        }
    }

    private static void putZip(ZipOutputStream zip, String name, String content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static void putZipBytes(ZipOutputStream zip, String name, byte[] data) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }
}
