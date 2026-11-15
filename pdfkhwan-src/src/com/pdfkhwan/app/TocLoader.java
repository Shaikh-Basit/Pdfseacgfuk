package com.pdfkhwan.app;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import com.shockwave.pdfium.PdfDocument;
import com.shockwave.pdfium.PdfiumCore;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/* loaded from: classes.dex */
public class TocLoader {
    private final Context ctx;
    private final Uri uri;
    /* v1.27 FIX: password-protected PDFs never passed the password here —
       the outline panel kept saying «این فایل رمز عبور دارد» even after a
       successful unlock */
    private final String password;

    public interface Callback {
        void onDone(List<Item> list);

        void onError(String str);
    }

    public static class Item {
        public final int depth;
        public final int page;
        public final String title;

        Item(String str, int i, int i2) {
            this.title = str;
            this.page = i;
            this.depth = i2;
        }
    }

    public TocLoader(Context context, Uri uri) {
        this(context, uri, null);
    }

    public TocLoader(Context context, Uri uri, String password) {
        this.ctx = context.getApplicationContext();
        this.uri = uri;
        this.password = password;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void postDone(final Callback callback, final List<Item> list) {
        new Handler(Looper.getMainLooper()).post(new Runnable() { // from class: com.pdfkhwan.app.TocLoader.2
            @Override // java.lang.Runnable
            public void run() {
                callback.onDone(list);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public int walk(List<PdfDocument.Bookmark> list, int i, List<Item> list2, int i2) {
        if (list != null && !list.isEmpty() && i <= 6 && i2 <= 4000) {
            for (PdfDocument.Bookmark bookmark : list) {
                String title = bookmark.getTitle();
                /* v1.29 FIX: skip entries with NO page destination (URI-action
                   bookmarks) — they used to be listed as «ص 1» and a tap
                   jumped to the FIRST page. Their children are still walked. */
                if (bookmark.getPageIdx() >= 0) {
                    int min = (int) Math.min(2147483646L, bookmark.getPageIdx() + 1);
                    if (title == null || title.length() == 0) {
                        title = "…";
                    }
                    list2.add(new Item(title, min, i));
                }
                i2 = walk(bookmark.getChildren(), i + 1, list2, i2 + 1);
                if (i2 > 4000) {
                    break;
                }
            }
        }
        return i2;
    }

    public void start(final Callback callback) {
        new Thread(new Runnable() { // from class: com.pdfkhwan.app.TocLoader.1
            @Override
            public void run() {
                ParcelFileDescriptor pfd = null;
                PdfiumCore core = null;
                PdfDocument doc = null;
                try {
                    try {
                        if ("file".equals(TocLoader.this.uri.getScheme())) {
                            pfd = ParcelFileDescriptor.open(new File(TocLoader.this.uri.getPath()), ParcelFileDescriptor.MODE_READ_ONLY);
                        } else {
                            pfd = TocLoader.this.ctx.getContentResolver().openFileDescriptor(TocLoader.this.uri, "r");
                        }
                        if (pfd == null) {
                            throw new IllegalStateException("openFileDescriptor returned null");
                        }
                        core = new PdfiumCore(TocLoader.this.ctx);
                        doc = TocLoader.this.password != null
                                ? core.newDocument(pfd, TocLoader.this.password)
                                : core.newDocument(pfd);
                        ArrayList<Item> out = new ArrayList<Item>();
                        TocLoader.this.walk(core.getTableOfContents(doc), 0, out, 0);
                        /* v1.50: pdfium فهرست برنگرداند؟ تمام شد.
                           اگر خالی ماند (خیلی از فایل‌ها outline دارند ولی
                           مقصدهایشان توسط pdfium حل نمی‌شود، یا با اکشن‌های
                           RemoteGoTo/Named رها می‌شوند) به‌جای «این فایل فهرست
                           مطالب ندارد» با PDFBox همان outline را از دیشت
                           فایل می‌خوانیم — PDDocumentOutline برای همهٔ این
                           انواع مقصد جواب می‌دهد و فهرست واقعاً لیست می‌شود. */
                        if (!out.isEmpty()) {
                            TocLoader.this.postDone(callback, out);
                            return;
                        }
                        List<Item> fallback = TocLoader.this.tocViaPdfBox();
                        if (fallback != null && !fallback.isEmpty()) {
                            TocLoader.this.postDone(callback, fallback);
                            return;
                        }
                        TocLoader.this.postDone(callback, out);   /* خالی واقعی */
                        return;
                    } catch (Throwable t) {
                        String sn = t.getClass().getSimpleName();
                        /* v1.50: pdfium نشست؟ PDFBox را امتحان کن — فایل
                           content:// که provider آن فقط یک fd همزمان می‌دهد
                           یا سند با ساختار XRef خروجی-Adobe بود، قبلاً فقط
                           «فهرست استخراج نشد» می‌شد و پنل بلاتکلیف می‌ماند */
                        List<Item> fallback = null;
                        try {
                            fallback = TocLoader.this.tocViaPdfBox();
                        } catch (Throwable ignored) {
                        }
                        if (fallback != null && !fallback.isEmpty()) {
                            TocLoader.this.postDone(callback, fallback);
                            return;
                        }
                        final String msg = sn.contains("Password") ? "این فایل رمز عبور دارد." : "فهرست استخراج نشد: " + sn;
                        CrashGuard.append(TocLoader.this.ctx, "toc: " + CrashGuard.stackToString(t) + "\n");
                        new Handler(Looper.getMainLooper()).post(new Runnable() { // from class: com.pdfkhwan.app.TocLoader.1.1
                            @Override // java.lang.Runnable
                            public void run() {
                                callback.onError(msg);
                            }
                        });
                        return;
                    }
                } finally {
                    if (core != null && doc != null) {
                        try {
                            core.closeDocument(doc);
                        } catch (Throwable ignored) {
                        }
                    } else if (pfd != null) {
                        /* v1.50: اگر doc ساخته نشد (open شکست خورد) خودمان
                           ببندیم — newDocument در موفقیت مالکیت fd را می‌گیرد
                           و closeDocument می‌بنددش */
                        try {
                            pfd.close();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        }, "pdf-toc").start();
    }

    /* v1.50: فهرست مطالب از دیشت فایل با PDFBox — برای همهٔ فایل‌هایی که
       pdfium فهرستشان را نمی‌فهمد (مقصدهای Named/RemoteGoTo، XRefهای
       نوشته‌شده با ابزارهای Adobe). findDestinationPage هر نوع مقصد را
       با صفحهٔ واقعی حل می‌کند. */
    private List<Item> tocViaPdfBox() {
        com.tom_roush.pdfbox.pdmodel.PDDocument pdoc = null;
        try {
            File f = null;
            if ("file".equals(this.uri.getScheme())) {
                f = new File(this.uri.getPath());
            }
            if (f != null && f.exists() && f.length() > 0) {
                pdoc = com.tom_roush.pdfbox.pdmodel.PDDocument.load(f, this.password == null ? "" : this.password,
                        com.tom_roush.pdfbox.io.MemoryUsageSetting.setupMixed(24L * 1024 * 1024));
            } else {
                InputStream in = this.ctx.getContentResolver().openInputStream(this.uri);
                if (in == null) return null;
                try {
                    byte[] all = readAll(in, 64L * 1024 * 1024);
                    if (all == null) return null;
                    pdoc = com.tom_roush.pdfbox.pdmodel.PDDocument.load(all, this.password == null ? "" : this.password);
                } finally {
                    try {
                        in.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
            if (pdoc == null) return null;
            List<Item> out = new ArrayList<Item>();
            try {
                com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline outline =
                        pdoc.getDocumentCatalog().getDocumentOutline();
                if (outline == null) return out;
                walkPdfBox(outline.getFirstChild(), 0, out, pdoc, 0);
            } catch (Throwable ignored) {
            }
            return out;
        } catch (Throwable t) {
            return null;
        } finally {
            if (pdoc != null) {
                try {
                    pdoc.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static byte[] readAll(InputStream in, long cap) {
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int r;
            long total = 0;
            while ((r = in.read(buf)) > 0) {
                total += r;
                if (total > cap) return null;
                bos.write(buf, 0, r);
            }
            return bos.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    private void walkPdfBox(com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem item,
                            int depth, List<Item> out,
                            com.tom_roush.pdfbox.pdmodel.PDDocument doc, int count) {
        try {
            while (item != null && depth <= 6 && count <= 4000) {
                String title = null;
                try {
                    title = item.getTitle();
                } catch (Throwable ignored) {
                }
                Integer pageIdx = null;
                try {
                    com.tom_roush.pdfbox.pdmodel.PDPage pg = item.findDestinationPage(doc);
                    if (pg != null) {
                        int idx = doc.getPages().indexOf(pg);
                        if (idx >= 0) pageIdx = idx;
                    }
                } catch (Throwable ignored) {
                }
                if (pageIdx != null) {
                    if (title == null || title.length() == 0) title = "…";
                    out.add(new Item(title, pageIdx + 1, depth));
                }
                walkPdfBox(item.getFirstChild(), depth + 1, out, doc, count + 1);
                item = item.getNextSibling();
                count++;
            }
        } catch (Throwable ignored) {
        }
    }
}
