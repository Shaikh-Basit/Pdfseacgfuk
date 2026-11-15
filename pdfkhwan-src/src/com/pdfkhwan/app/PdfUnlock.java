package com.pdfkhwan.app;

import android.content.Context;
import android.net.Uri;
import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;

/* ====================================================================
   v1.40: بازکردن PDFهای محافظت‌شده و رمزدار — برای همه
   --------------------------------------------------------------------
   مشکل تاریخی: موتور نمایش (pdfium) فقط RC4 و AES-128 را می‌شناسد؛
   فایل‌های AES-256 (R5/R6 — پیش‌فرض همهٔ ابزارهای امروزی مثل
   Acrobat و Word) با خطای «Unsupported security scheme» به بن‌بست
   «فایل خراب است» می‌رسیدند و حتی دیالوگ رمز هم نشان داده نمی‌شد.

   راه‌حل: PDFBox (مجوز Apache 2.0) هر چهار نسل استاندارد امنیت PDF
   (RC4 / AES-128 / AES-256-R5 / AES-256-R6) را باز می‌کند. این کلاس
   با رمزِ داده‌شده (یا رمز خالی برای فایل‌های فقط-محدودشده) فایل را
   یک‌بار باز می‌کند، قفل را کامل برمی‌داند و یک نسخهٔ رمزگشایی‌شده
   در حافظهٔ موقت می‌نویسد؛ از آن به بعد هر دو موتور (نمایش pdfium و
   متن pdf.js) روی نسخهٔ باز کار می‌کنند: نمایش، جستجو، کپی متن،
   تبدیل به Word و چاپ — همه آزاد می‌شوند.
   ==================================================================== */
public final class PdfUnlock {

    /* نتیجهٔ رمزگشایی */
    public static final int R_OK = 0;             /* فایل باز شد و نسخهٔ آزاد ساخته شد */
    public static final int R_NEED_PASSWORD = 1;  /* فایل رمز دارد — رمز بخواه */
    public static final int R_WRONG_PASSWORD = 2; /* رمزِ داده‌شده غلط بود */
    public static final int R_FAILED = 3;         /* نه قفل قابل‌بازکردن بود نه فایل سالم */

    private PdfUnlock() {}

    /* آیا خطای بازکردنِ pdfium از جنس قفل/امنیتی است؟
       (PdfPasswordException، «Unsupported security scheme» یا
        «cannot create document» — پیام‌های nativeOpenDocument) */
    public static boolean isSecurityFailure(Throwable t) {
        if (t == null) return false;
        String name = t.getClass().getSimpleName();
        if (name != null && name.contains("Password")) return true;
        String m = String.valueOf(t.getMessage());
        return m.contains("Unsupported security scheme")
                || m.contains("cannot create document")
                || m.contains("Password required");
    }

    /* رمزگشایی به یک فایل آزاد.
       password == null یعنی «هنوز رمزی نداریم» — با رمز خالی (فایل‌های
       محدودشده که رمزِ بازکردن ندارند) امتحان می‌شود.
       خروجی: یکی از R_OK / R_NEED_PASSWORD / R_WRONG_PASSWORD / R_FAILED */
    public static int decryptTo(Context ctx, Uri uri, String password, File out) {
        PDDocument doc = null;
        File src = null;
        boolean srcTemp = false;
        try {
            /* PDFBox با فایل تصادفی-خوان کار می‌کند؛ content:// را یک‌بار
               به فایل موقت کپی می‌کنیم (file:// مستقیم باز می‌شود) */
            if ("file".equals(uri.getScheme())) {
                src = new File(uri.getPath());
                if (!src.exists() || src.length() == 0) return R_FAILED;
            } else {
                src = new File(out.getParentFile(), out.getName() + ".src");
                InputStream in = ctx.getContentResolver().openInputStream(uri);
                if (in == null) return R_FAILED;
                try {
                    FileOutputStream fo = new FileOutputStream(src);
                    try {
                        byte[] buf = new byte[65536];
                        int r;
                        while ((r = in.read(buf)) > 0) fo.write(buf, 0, r);
                    } finally {
                        try { fo.close(); } catch (Exception ignored) {}
                    }
                } finally {
                    try { in.close(); } catch (Exception ignored) {}
                }
                srcTemp = true;
                if (src.length() == 0) return R_FAILED;
            }

            String pw = password == null ? "" : password;
            try {
                /* ۴۸ مگابایت در حافظه، بقیه در فایل موقت — کتاب‌های بزرگ
                   بدون پرشدن RAM باز می‌شوند */
                doc = PDDocument.load(src, pw,
                        MemoryUsageSetting.setupMixed(48L * 1024 * 1024));
            } catch (InvalidPasswordException e) {
                return pw.length() == 0 ? R_NEED_PASSWORD : R_WRONG_PASSWORD;
            }

            /* قفل را کامل بردار (مجوزهای کپی/چاپ هم پاک می‌شوند) و دوباره بنویس */
            doc.setAllSecurityToBeRemoved(true);
            File tmp = new File(out.getParentFile(), out.getName() + ".tmp");
            doc.save(tmp);
            try { doc.close(); } catch (Exception ignored) {}
            doc = null;
            if (out.exists()) out.delete();
            if (!tmp.renameTo(out)) {
                /* rename روی برخی دستگاه‌ها بین پوشه‌ها می‌لغزد — کپی بایت‌به‌بایت */
                copyFile(tmp, out);
                tmp.delete();
            }
            return out.exists() && out.length() > 0 ? R_OK : R_FAILED;
        } catch (Throwable t) {
            /* خود PDFBox هم رمزِ غلط را همین‌طور نشان می‌دهد */
            if (t instanceof InvalidPasswordException) {
                String pw = password == null ? "" : password;
                return pw.length() == 0 ? R_NEED_PASSWORD : R_WRONG_PASSWORD;
            }
            return R_FAILED;
        } finally {
            if (doc != null) {
                try { doc.close(); } catch (Exception ignored) {}
            }
            if (srcTemp && src != null) {
                try { src.delete(); } catch (Exception ignored) {}
            }
        }
    }

    private static void copyFile(File from, File to) throws Exception {
        FileInputStream in = new FileInputStream(from);
        try {
            FileOutputStream out = new FileOutputStream(to);
            try {
                byte[] buf = new byte[65536];
                int r;
                while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            } finally {
                try { out.close(); } catch (Exception ignored) {}
            }
        } finally {
            try { in.close(); } catch (Exception ignored) {}
        }
    }
}
