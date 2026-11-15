package com.pdfkhwan.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.media.AudioManager;
import android.speech.tts.TextToSpeech;
import android.util.LruCache;
import android.util.SparseArray;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import com.github.barteksc.pdfviewer.PDFView;
import com.github.barteksc.pdfviewer.listener.OnErrorListener;
import com.github.barteksc.pdfviewer.listener.OnLoadCompleteListener;
import com.github.barteksc.pdfviewer.listener.OnLongPressListener;
import com.github.barteksc.pdfviewer.listener.OnPageChangeListener;
import com.github.barteksc.pdfviewer.listener.OnPageErrorListener;
import com.github.barteksc.pdfviewer.listener.OnTapListener;
import com.github.barteksc.pdfviewer.scroll.DefaultScrollHandle;
import com.github.barteksc.pdfviewer.util.FitPolicy;
import com.pdfkhwan.app.PdfThumbs;
import com.pdfkhwan.app.SearchBridge;
import com.pdfkhwan.app.TocLoader;
import com.pdfkhwan.ppt.PptDoc;
import com.pdfkhwan.tts.PiperEngine;
import com.pdfkhwan.tts.SystemTtsEngine;
import com.pdfkhwan.tts.TtsEngine;
import com.pdfkhwan.tts.TtsService;
import com.pdfkhwan.tts.TtsSession;
import com.pdfkhwan.tts.VoiceModels;
import com.pdfkhwan.tts.VoiceModelsActivity;
import com.shockwave.pdfium.PdfDocument;
import com.shockwave.pdfium.PdfiumCore;
import com.shockwave.pdfium.util.SizeF;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/* v1.51: خوشهٔ جدا‌شده از ReaderActivity — تقسیم ساختاری */
public class ReaderShare {
    final ReaderActivity host;
    ReaderShare(ReaderActivity h) { this.host = h; }

    boolean snapRunning = false;

    final SparseArray<String> wordTexts = new SparseArray<>();

    volatile boolean wordBusy = false;

    Dialog wordProg;
    /* v1.38: خروجی Word نسخهٔ ۲ — layout کامل (واژه + جعبه + فونت + اندازه) */

    final SparseArray<PdfExport.LayoutPage> wordLayout = new SparseArray<>();
    /* v1.33: نوار ابزار انتخاب مدرن */

    public void shareFile() {
        /* v1.15 FIX (bug: هنگام اشتراک‌گذاری یک نام اضافه به فایل چسبید):
           نام قدیمی کش «share_<hash>_<mangled-name>» بود؛ نامی که در سمت
           گیرنده (تلگرام/واتس‌اپ/…) دیده می‌شد همان نام این فایل کش بود،
           یعنی یک پیشوند «share_1a2b3c_» و تبدیل حروف فارسی به «_»!
           اکنون فایل کش دقیقاً همان نام اصلی سند را دارد (فقط نویسه‌های
           ممنوع مسیر حذف می‌شوند) و در زیرپوشهٔ share/ کش قرار می‌گیرد،
           پس فایل اشتراکی «نام و پسوند اصلی» خودش را نگه می‌دارد.
           v1.16 FIX: the copy used to run on the UI thread — a large PDF
           froze the app (ANR risk) the moment the user tapped share.
           Staging now runs on the background I/O thread and the chooser
           opens on the UI thread when the file is ready. */
        try {
            File quick = new File(new File(host.getCacheDir(), "share"), shareSafeName());
            if (!quick.exists() || quick.length() != host.docSize) {
                host.toast("در حال آماده‌سازی فایل برای اشتراک…");
            }
        } catch (Throwable unused) {
        }
        host.io.execute(new Runnable() {
            @Override // java.lang.Runnable
            public void run() {
                try {
                    String safeName = shareSafeName();
                    File shareDir = new File(host.getCacheDir(), "share");
                    if (!shareDir.exists()) {
                        shareDir.mkdirs();
                    }
                    File file = new File(shareDir, safeName);
                    /* v1.29 FIX: the staged copy was REUSED when name+size
                       matched — a DIFFERENT document with the same name and
                       length (template-derived PDFs) served the old bytes.
                       Recopy unconditionally (and overwrite the stale file). */
                    if (file.exists()) {
                        file.delete();
                    }
                    {
                        InputStream openInputStream = host.getContentResolver().openInputStream(host.uri);
                        try {
                            FileOutputStream fileOutputStream = new FileOutputStream(file);
                            try {
                                byte[] bArr = new byte[65536];
                                while (true) {
                                    int read = openInputStream.read(bArr);
                                    if (read <= 0) {
                                        break;
                                    } else {
                                        fileOutputStream.write(bArr, 0, read);
                                    }
                                }
                            } finally {
                                try {
                                    fileOutputStream.close();
                                } catch (Throwable ignored) {
                                }
                            }
                        } finally {
                            try {
                                openInputStream.close();
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                    final Uri put = ShareProvider.put(file);
                    final String lower = safeName.toLowerCase();
                    final String mime = lower.endsWith(".pptx") ? "application/vnd.openxmlformats-officedocument.presentationml.presentation"
                            : lower.endsWith(".ppt") ? "application/vnd.ms-powerpoint" : "application/pdf";
                    final String chooserTitle = "اشتراک\u200cگذاری " + (lower.endsWith(".pdf") ? "PDF" : "پاورپوینت");
                    host.ui.post(new Runnable() {
                        @Override // java.lang.Runnable
                        public void run() {
                            try {
                                Intent intent = new Intent("android.intent.action.SEND");
                                intent.setType(mime);
                                intent.putExtra("android.intent.extra.STREAM", put);
                                intent.setClipData(ClipData.newRawUri("pdf", put));
                                intent.addFlags(1);
                                host.startActivity(Intent.createChooser(intent, chooserTitle));
                            } catch (Exception unused) {
                                host.toast("اشتراک\u200cگذاری ممکن نشد");
                            }
                        }
                    });
                } catch (Exception unused) {
                    host.ui.post(new Runnable() {
                        @Override // java.lang.Runnable
                        public void run() {
                            host.toast("اشتراک\u200cگذاری ممکن نشد");
                        }
                    });
                }
            }
        });
    }

    /* sanitized cache name that still keeps the document's ORIGINAL name
       (v1.15) — extracted so the quick cache check and the background
       staging agree on the same file (v1.16). */

    String shareSafeName() {
        String safeName = host.docName == null || host.docName.length() == 0 ? "document.pdf" : host.docName;
        safeName = safeName.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return safeName.length() == 0 ? "document.pdf" : safeName;
    }


    /* JADX INFO: Access modifiers changed from: private */

    void shareSheet() {
        final Dialog[] dlg = new Dialog[1];
        LinearLayout sheet = Modern.sheetContent(host);
        sheet.addView(Modern.sheetHeader(host, "اشتراک‌گذاری و چاپ",
                "فایل اصلی هیچ‌وقت تغییر نمی‌کند", new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                    }
                }));
        sheet.addView(Modern.compactRow(host, R.drawable.ic_ann_share, 0xFF64B5F6, "اشتراک کل فایل",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                        shareFile();
                    }
                }), new LinearLayout.LayoutParams(-1, -2));
        sheet.addView(Modern.compactRow(host, R.drawable.ic_pages, 0xFF4DD0E1, "اشتراک صفحات انتخابی…",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                        pageRangeDialog(true);
                    }
                }), new LinearLayout.LayoutParams(-1, -2));
        /* v1.45: خروجی همراه حاشیه‌نویسی — Flat / Embed */
        sheet.addView(Modern.compactRow(host, R.drawable.ic_ann_palette, 0xFFFFD54F, "اشتراک با حاشیه‌نویسی…",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                        annotatedExportDialog(false);
                    }
                }), new LinearLayout.LayoutParams(-1, -2));
        sheet.addView(Modern.compactRow(host, R.drawable.ic_ann_palette, 0xFFFFB74D, "چاپ همراه حاشیه‌نویسی…",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                        annotatedExportDialog(true);
                    }
                }), new LinearLayout.LayoutParams(-1, -2));
        sheet.addView(Modern.compactRow(host, R.drawable.ic_word, 0xFF5C9DFF, "تبدیل به Word",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                        wordExport();
                    }
                }), new LinearLayout.LayoutParams(-1, -2));
        sheet.addView(Modern.compactRow(host, R.drawable.ic_print, 0xFFAED581, "چاپ کل فایل",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                        printWhole();
                    }
                }), new LinearLayout.LayoutParams(-1, -2));
        sheet.addView(Modern.compactRow(host, R.drawable.ic_print, 0xFFFFB74D, "چاپ صفحات انتخابی…",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                        pageRangeDialog(false);
                    }
                }), new LinearLayout.LayoutParams(-1, -2));
        TextView hint = new TextView(host);
        hint.setText("\u062f\u0631 \u0635\u0641\u062d\u0647\u0654 \u0686\u0627\u067e\u060c \u06af\u0632\u06cc\u0646\u0647\u0654 \u00ab\u0630\u062e\u06cc\u0631\u0647 \u0628\u0647 \u0635\u0648\u0631\u062a PDF\u00bb \u0646\u0633\u062e\u0647\u0654 \u062a\u0627\u0632\u0647\u0654 \u0642\u0627\u0628\u0644\u0648\u06cc\u0631\u0627\u06cc\u0634 \u0645\u06cc\u0633\u0627\u0632\u062f.");
        hint.setTextColor(Modern.C_MUTED);
        hint.setTextSize(11f);
        int hp2 = host.dp(18);
        hint.setPadding(hp2, host.dp(6), hp2, 0);
        sheet.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout pad = new LinearLayout(host);
        sheet.addView(pad, new LinearLayout.LayoutParams(-1, host.dp(10)));
        int cap = Math.round(host.getResources().getDisplayMetrics().heightPixels * 0.62f);
        dlg[0] = host.trackSheet(Modern.showSheetCapped(host, sheet, cap), new Runnable() {
            @Override public void run() { shareSheet(); }
        });
    }

    /* انتخاب بازهٔ صفحات — «۱-۳،۵،۸-۱۰» با ارقام فارسی یا انگلیسی */

    void pageRangeDialog(final boolean forShare) {
        if (host.pageCount <= 0) {
            host.toast("فایل هنوز کامل باز نشده است");
            return;
        }
        final Dialog[] dlg = new Dialog[1];
        LinearLayout card = Modern.cardContent(host);
        card.addView(Modern.sheetHeader(host, forShare ? "اشتراک صفحات انتخابی" : "چاپ صفحات انتخابی",
                "این فایل " + Fa.d(host.pageCount) + " صفحه دارد", new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                    }
                }));
        final EditText et = new EditText(host);
        et.setHint("مثلاً ۱-۳،۵،۸-۱۰");
        et.setTextColor(Modern.C_TEXT);
        et.setHintTextColor(Modern.C_MUTED);
        et.setTextSize(16f);
        et.setGravity(android.view.Gravity.CENTER);
        et.setBackground(Modern.fieldBg(host));
        int ep = host.dp(14);
        et.setPadding(ep, host.dp(11), ep, host.dp(11));
        et.setSingleLine(true);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(-1, -2);
        elp.topMargin = host.dp(10);
        card.addView(et, elp);
        LinearLayout act = new LinearLayout(host);
        act.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-1, -2);
        alp.topMargin = host.dp(14);
        act.setLayoutParams(alp);
        act.addView(Modern.filledBtn(host, forShare ? "اشتراک" : "چاپ", Modern.C_ACCENT,
                Modern.onColor(Modern.C_ACCENT), new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        int[] pages = PdfExport.parseRanges(et.getText().toString(), host.pageCount);
                        if (pages == null) {
                            host.toast("بازه نامعتبر است — مثال: ۱-۳،۵");
                            return;
                        }
                        try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                        if (forShare) subsetAndShare(pages);
                        else subsetAndPrint(pages);
                    }
                }), new LinearLayout.LayoutParams(-1, -2, 1f));
        card.addView(act);
        dlg[0] = host.trackDialog(Modern.showCardAuto(host, card));
        et.postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    et.requestFocus();
                    android.view.inputmethod.InputMethodManager imm =
                            (android.view.inputmethod.InputMethodManager) host.getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) imm.showSoftInput(et, 0);
                } catch (Throwable ignored) {
                }
            }
        }, 200L);
    }

    /* کارت پیشرفت کوچک برای کارهای پس‌زمینه */

    void subsetAndShare(final int[] pages) {
        final Dialog prog = host.busyCard("در حال ساختن نسخهٔ صفحات انتخابی…");
        host.io.execute(new Runnable() {
            @Override public void run() {
                try {
                    File dir = new File(host.getCacheDir(), "share");
                    if (!dir.exists()) dir.mkdirs();
                    String base = shareSafeName().replaceAll("\\.[^.]+$", "");
                    File out = new File(dir, base + "-pages.pdf");
                    PdfExport.buildSubset(host, host.openUri(), host.docPassword, pages, out,
                            new PdfExport.Progress() {
                                @Override public void onProgress(final int done, final int total) {
                                    host.ui.post(new Runnable() {
                                        @Override public void run() {
                                            host.busyCardText(prog, Fa.d(done) + " از " + Fa.d(total) + " صفحه");
                                        }
                                    });
                                }
                            });
                    final Uri put = ShareProvider.put(out);
                    host.ui.post(new Runnable() {
                        @Override public void run() {
                            try { prog.dismiss(); } catch (Throwable ignored) {}
                            try {
                                Intent intent = new Intent("android.intent.action.SEND");
                                intent.setType("application/pdf");
                                intent.putExtra("android.intent.extra.STREAM", put);
                                intent.setClipData(ClipData.newRawUri("pdf", put));
                                intent.addFlags(1);
                                host.startActivity(Intent.createChooser(intent, "اشتراک صفحات انتخابی"));
                            } catch (Exception unused) {
                                host.toast("اشتراک ممکن نشد");
                            }
                        }
                    });
                } catch (final Exception e) {
                    host.ui.post(new Runnable() {
                        @Override public void run() {
                            try { prog.dismiss(); } catch (Throwable ignored) {}
                            host.toast("ساختن فایل ممکن نشد: " + e.getMessage());
                        }
                    });
                }
            }
        });
    }

    /* بازسازی صفحات انتخابی → چاپ */

    void subsetAndPrint(final int[] pages) {
        final Dialog prog = host.busyCard("در حال آماده‌سازی صفحات برای چاپ…");
        host.io.execute(new Runnable() {
            @Override public void run() {
                try {
                    File dir = new File(host.getCacheDir(), "share");
                    if (!dir.exists()) dir.mkdirs();
                    String base = shareSafeName().replaceAll("\\.[^.]+$", "");
                    File out = new File(dir, base + "-pages.pdf");
                    PdfExport.buildSubset(host, host.openUri(), host.docPassword, pages, out,
                            new PdfExport.Progress() {
                                @Override public void onProgress(final int done, final int total) {
                                    host.ui.post(new Runnable() {
                                        @Override public void run() {
                                            host.busyCardText(prog, Fa.d(done) + " از " + Fa.d(total) + " صفحه");
                                        }
                                    });
                                }
                            });
                    host.ui.post(new Runnable() {
                        @Override public void run() {
                            try { prog.dismiss(); } catch (Throwable ignored) {}
                            boolean ok = PdfExport.printFile(host, out,
                                    shareSafeName().replaceAll("\\.[^.]+$", ""), pages.length);
                            if (!ok) host.toast("چاپگر روی این دستگاه در دسترس نیست");
                        }
                    });
                } catch (final Exception e) {
                    host.ui.post(new Runnable() {
                        @Override public void run() {
                            try { prog.dismiss(); } catch (Throwable ignored) {}
                            host.toast("آماده‌سازی چاپ ممکن نشد: " + e.getMessage());
                        }
                    });
                }
            }
        });
    }

    /* چاپ کل فایل — کپی بایت‌به‌بایت از خود فایل، بدون هیچ تغییری
       v1.40: اگر نسخهٔ رمزگشایی‌شده هست (فایل محافظت‌شده) همان چاپ
       می‌شود — چاپ فایل‌هایی که اپ‌های دیگر به‌خاطر قفلِ چاپ نمی‌توانند */

    void printWhole() {
        if (host.decryptedFile != null && host.decryptedFile.exists() && host.decryptedFile.length() > 0) {
            if (!PdfExport.printFile(host, host.decryptedFile, shareSafeName().replaceAll("\\.[^.]+$", ""), host.pageCount)) {
                host.toast("چاپگر روی این دستگاه در دسترس نیست");
            }
            return;
        }
        if ("file".equals(host.uri.getScheme())) {
            File f = new File(host.uri.getPath());
            if (f.exists()) {
                if (!PdfExport.printFile(host, f, shareSafeName().replaceAll("\\.[^.]+$", ""), host.pageCount)) {
                    host.toast("چاپگر روی این دستگاه در دسترس نیست");
                }
                return;
            }
        }
        /* content:// — اول یک کپی محلی بساز بعد چاپ کن */
        final Dialog prog = host.busyCard("در حال آماده‌سازی چاپ…");
        host.io.execute(new Runnable() {
            @Override public void run() {
                try {
                    File dir = new File(host.getCacheDir(), "share");
                    if (!dir.exists()) dir.mkdirs();
                    final File out = new File(dir, shareSafeName());
                    java.io.InputStream in = host.getContentResolver().openInputStream(host.openUri());
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
                    try {
                        byte[] buf = new byte[65536];
                        int r;
                        while ((r = in.read(buf)) > 0) fos.write(buf, 0, r);
                    } finally {
                        try { in.close(); } catch (Exception ignored) {}
                        try { fos.close(); } catch (Exception ignored) {}
                    }
                    host.ui.post(new Runnable() {
                        @Override public void run() {
                            try { prog.dismiss(); } catch (Throwable ignored) {}
                            if (!PdfExport.printFile(host, out,
                                    shareSafeName().replaceAll("\\.[^.]+$", ""), host.pageCount)) {
                                host.toast("چاپگر روی این دستگاه در دسترس نیست");
                            }
                        }
                    });
                } catch (final Exception e) {
                    host.ui.post(new Runnable() {
                        @Override public void run() {
                            try { prog.dismiss(); } catch (Throwable ignored) {}
                            host.toast("آماده‌سازی چاپ ممکن نشد");
                        }
                    });
                }
            }
        });
    }

    /* ================== v1.37: تبدیل به Word ==================
       متن همهٔ صفحات با همان موتور pdf.js استخراج می‌شود و داخل یک
       DOCX سالم (RTL-aware، با جداکنندهٔ صفحه) ذخیره و اشتراک می‌شود. */

    void wordExport() {
        if (wordBusy) {
            host.toast("استخراج متن در جریان است…");
            return;
        }
        if (!host.webReady) {
            host.toast("موتور متن هنوز آماده نیست — کمی صبر کن و دوباره بزن");
            return;
        }
        wordBusy = true;
        wordTexts.clear();
        wordLayout.clear();
        wordProg = host.busyCard("در حال استخراج متن برای تبدیل به Word…");
        host.io.execute(new Runnable() {
            @Override public void run() {
                try {
                    final String url = host.searchCopyUrl();
                    host.ui.post(new Runnable() {
                        @Override public void run() {
                            try {
                                host.searchWeb.evaluateJavascript(
                                        "extractLayout(" + JSONObject.quote(url) + ", "
                                                + (host.docPassword != null ? JSONObject.quote(host.docPassword) : "null")
                                                + ", null)", null);
                            } catch (final Exception e) {
                                wordFinishError("شروع استخراج ممکن نشد");
                            }
                        }
                    });
                } catch (final Exception e) {
                    wordFinishError("خواندن فایل ممکن نشد");
                }
            }
        });
    }

    void wordFinishError(String why) {
        wordBusy = false;
        try { if (wordProg != null) wordProg.dismiss(); } catch (Throwable ignored) {}
        wordProg = null;
        host.toast(why);
    }

    /* ==================================================================
       v1.45: خروجی PDF همراه حاشیه‌نویسی (Flat / Embed) + چاپ همراه +
              عملیات صفحه (چرخش/حذف/جابجایی/استخراج/ادغام/تقسیم)
       ================================================================== */

    /* تأمین هندسهٔ واژه‌ها برای موتور خروجی — از کش SelPage */
    /* v1.50: تبدیل dp→pt برای خروجی — «هر dp روی صفحهٔ جاری چند نقطهٔ کاغذ
       است؟» صفحه در fit-width (زوم ۱) به پهنای نما می‌نشیند؛ پس
       pt-per-px = pageW_pt / viewW_px و pt-per-dp = آن × density.
       اندازهٔ واقعی صفحه از pdfium (بر حسب pt)؛ حدس محافظه‌کارانه اگر
       در دسترس نبود: ۶۱۲pt روی نمایش ۱۰۸۰px. */

    float annPtPerDp() {
        try {
            float density = host.getResources().getDisplayMetrics().density;
            int viewW = host.pdfView.getWidth();
            if (viewW <= 0) viewW = host.getResources().getDisplayMetrics().widthPixels;
            float pageWpt = 612f;
            try {
                com.shockwave.pdfium.util.SizeF sz = host.pdfView.getPageSize(host.pdfView.getCurrentPage());
                if (sz != null && sz.getWidth() > 0) pageWpt = sz.getWidth();
            } catch (Throwable ignored) {
            }
            return Math.max(0.4f, Math.min(3f, pageWpt * density / (float) viewW));
        } catch (Throwable t) {
            return 1.56f;   /* حدس: ۶۱۲pt/۱۰۸۰px × density 2.75 */
        }
    }

    AnnPdfExport.WordGeom annWordGeom() {
        return new AnnPdfExport.WordGeom() {
            @Override public float vw(int page) {
                ReaderActivity.SelPage sp = host.selPages.get(page);
                return sp == null ? 0f : sp.vw;
            }
            @Override public float vh(int page) {
                ReaderActivity.SelPage sp = host.selPages.get(page);
                return sp == null ? 0f : sp.vh;
            }
            @Override public float[] words(int page, int w0, int w1) {
                ReaderActivity.SelPage sp = host.selPages.get(page);
                if (sp == null || sp.words.isEmpty()) return null;
                int from = Math.max(0, Math.min(w0, w1));
                int to = Math.min(sp.words.size() - 1, Math.max(w0, w1));
                if (from > to) return null;
                float[] out = new float[(to - from + 1) * 5];
                int k = 0;
                for (int i = from; i <= to; i++) {
                    ReaderActivity.SelWord w = sp.words.get(i);
                    out[k++] = w.x0; out[k++] = w.y0; out[k++] = w.x1; out[k++] = w.y1;
                    out[k++] = w.line;
                }
                return out;
            }
        };
    }

    /* ---------- گرم‌کردن صفحات دارای حاشیه‌نویسی متنی ---------- */

    ArrayList<Integer> annWarmQueue = null;

    Runnable annWarmDone = null;

    Runnable annWarmTimeout = null;

    int annWarmTotal = 0;

    /* اگر همهٔ صفحات لازم آماده‌اند همین‌جا صدا می‌زند؛ وگرنه یکی‌یکی
       requestSelWords می‌کند و onSelPage بعدی را می‌فرستد */

    void annWarmStart(Runnable done) {
        annWarmDone = done;
        Set<Integer> need = AnnPdfExport.pagesNeedingWords(host.anns.items);
        ArrayList<Integer> missing = new ArrayList<>();
        for (Integer p : need) {
            if (p >= 0 && p < host.pageCount && host.selPages.get(p) == null) missing.add(p);
        }
        annWarmTotal = need.size();
        if (missing.isEmpty() || !host.webReady) {
            /* همه آماده‌اند (یا موتور خاموش است — بدون متن ادامه می‌دهیم) */
            if (!host.webReady && !missing.isEmpty()) {
                host.toast("موتور متن آماده نیست — هایلایت‌های متنی ممکن است جا بیفتند");
            }
            annWarmFinish();
            return;
        }
        annWarmQueue = missing;
        annWarmTimeout = new Runnable() {
            @Override public void run() { annWarmFinish(); }
        };
        host.ui.postDelayed(annWarmTimeout, 25000);
        host.requestSelWords(missing.get(0), true);
    }

    /* یک صفحه آماده شد — بعدی یا پایان */

    void annWarmStep() {
        if (annWarmQueue == null) return;
        try {
            /* صفحات آماده‌شده را از صف بردار */
            for (int i = annWarmQueue.size() - 1; i >= 0; i--) {
                if (host.selPages.get(annWarmQueue.get(i)) != null) annWarmQueue.remove(i);
            }
        } catch (Throwable ignored) {
        }
        if (annWarmQueue.isEmpty()) {
            annWarmFinish();
            return;
        }
        host.requestSelWords(annWarmQueue.get(0), true);
    }

    void annWarmFinish() {
        try {
            if (annWarmTimeout != null) host.ui.removeCallbacks(annWarmTimeout);
        } catch (Throwable ignored) {
        }
        annWarmTimeout = null;
        annWarmQueue = null;
        Runnable d = annWarmDone;
        annWarmDone = null;
        if (d != null) d.run();
    }

    /* فایل محلی برای PDFBox (رمزگشایی‌شده یا اصلی؛ content:// کپی می‌شود) */

    void fileForPdfBox(final java.util.concurrent.atomic.AtomicReference<File> slot) throws Exception {
        if (host.decryptedFile != null && host.decryptedFile.exists() && host.decryptedFile.length() > 0) {
            slot.set(host.decryptedFile);
            return;
        }
        if ("file".equals(host.uri.getScheme())) {
            File f = new File(host.uri.getPath());
            if (f.exists() && f.length() > 0) {
                slot.set(f);
                return;
            }
        }
        File dir = new File(host.getCacheDir(), "share");
        if (!dir.exists()) dir.mkdirs();
        File out = new File(dir, "src-" + shareSafeName());
        java.io.InputStream in = host.getContentResolver().openInputStream(host.openUri());
        if (in == null) throw new Exception("خواندن فایل ممکن نشد");
        try {
            java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
            try {
                byte[] buf = new byte[65536];
                int r;
                while ((r = in.read(buf)) > 0) fos.write(buf, 0, r);
            } finally {
                try { fos.close(); } catch (Exception ignored) {}
            }
        } finally {
            try { in.close(); } catch (Exception ignored) {}
        }
        slot.set(out);
    }

    /* دیالوگ انتخاب حالت خروجی حاشیه‌نویسی */

    void annotatedExportDialog(final boolean forPrint) {
        if (host.anns == null || host.anns.items.isEmpty()) {
            host.toast("این فایل حاشیه‌نویسی ندارد — از حالت رسم یا هایلایت استفاده کن");
            return;
        }
        final Dialog[] dlg = new Dialog[1];
        LinearLayout sheet = Modern.sheetContent(host);
        sheet.addView(Modern.sheetHeader(host, forPrint ? "چاپ همراه حاشیه‌نویسی" : "اشتراک با حاشیه‌نویسی",
                "فایل اصلی دست نمی‌خورد", new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                    }
                }));
        sheet.addView(Modern.compactRow(host, R.drawable.ic_ann_share, 0xFFFFB74D,
                "پخته‌شده (Flat) — در همه‌جا یکسان دیده می‌شود",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                        annotatedBuild(AnnPdfExport.MODE_FLAT, forPrint);
                    }
                }), new LinearLayout.LayoutParams(-1, -2));
        if (!forPrint) {
            sheet.addView(Modern.compactRow(host, R.drawable.ic_ann_pen, 0xFF64B5F6,
                    "قابل ویرایش (Embed) — در Adobe/Foxit قابل جابجایی",
                    new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                            annotatedBuild(AnnPdfExport.MODE_EMBED, false);
                        }
                    }), new LinearLayout.LayoutParams(-1, -2));
        }
        dlg[0] = host.trackSheet(Modern.showSheetAuto(host, sheet), new Runnable() {
            @Override public void run() { annotatedExportDialog(false); }
        });
    }

    /* ساخت خروجی: گرم‌کردن واژه‌ها → ساخت در پس‌زمینه → اشتراک/چاپ */

    void annotatedBuild(final int mode, final boolean forPrint) {
        if (!host.webReady) {
            host.toast("موتور متن هنوز آماده نیست — کمی صبر کن و دوباره بزن");
            return;
        }
        final Dialog prog = host.busyCard("در حال آماده‌سازی حاشیه‌نویسی‌ها…");
        annWarmStart(new Runnable() {
            @Override public void run() {
                host.io.execute(new Runnable() {
                    @Override public void run() {
                        try {
                            final java.util.concurrent.atomic.AtomicReference<File> srcSlot =
                                    new java.util.concurrent.atomic.AtomicReference<File>();
                            fileForPdfBox(srcSlot);
                            File src = srcSlot.get();
                            /* v1.50 FIX: مقیاس dp→pt واقعی — قبلاً چگالی خام گوشی
                               (مثلاً 2.75) مستقیم ضرب می‌شد؛ خروجی روی کاغذ ۵ تا
                               ۲۰ برابرِ ضخیم‌تر از آنچه کاربر دیده بود درمی‌آمد
                               (ماژیک ۲۶dp → ۷۲pt!). حالا: هر dp روی صفحهٔ fit-width
                               چقدر pt کاغذ است؟ pageW_pt/(viewW_px/density). */
                            final float ptPerDp = annPtPerDp();
                            final File out = AnnPdfExport.buildAnnotated(
                                    host, src, mode,
                                    new ArrayList<>(host.anns.items), annWordGeom(), ptPerDp,
                                    new AnnPdfExport.Progress() {
                                        @Override public void progress(final int done, final int total) {
                                            host.ui.post(new Runnable() {
                                                @Override public void run() {
                                                    host.busyCardText(prog, Fa.d(done) + " از " + Fa.d(total) + " صفحه");
                                                }
                                            });
                                        }
                                    });
                            host.ui.post(new Runnable() {
                                @Override public void run() {
                                    try { prog.dismiss(); } catch (Throwable ignored) {}
                                    if (forPrint) {
                                        if (!PdfExport.printFile(host, out,
                                                shareSafeName().replaceAll("\\.[^.]+$", ""), host.pageCount)) {
                                            host.toast("چاپگر روی این دستگاه در دسترس نیست");
                                        }
                                    } else {
                                        shareTempPdf(out, mode == AnnPdfExport.MODE_FLAT
                                                ? "اشتراک PDF حاشیه‌نویسی‌شده" : "اشتراک PDF با annotationها");
                                    }
                                }
                            });
                        } catch (final Exception e) {
                            host.ui.post(new Runnable() {
                                @Override public void run() {
                                    try { prog.dismiss(); } catch (Throwable ignored) {}
                                    host.toast("ساختن خروجی ممکن نشد: " + e.getMessage());
                                }
                            });
                        }
                    }
                });
            }
        });
    }

    /* اشتراک یک فایل موقت PDF (از ShareProvider — امن برای اندروید ۱۰+) */

    void shareTempPdf(File out, String title) {
        try {
            Uri put = ShareProvider.put(out);
            Intent intent = new Intent("android.intent.action.SEND");
            intent.setType("application/pdf");
            intent.putExtra("android.intent.extra.STREAM", put);
            intent.setClipData(ClipData.newRawUri("pdf", put));
            intent.addFlags(1);
            host.startActivity(Intent.createChooser(intent, title));
        } catch (Exception unused) {
            host.toast("اشتراک ممکن نشد");
        }
    }

    /* ---------- عملیات صفحه (v1.45) ---------- */

    PdfExport.ImageSource wordImageSource() {
        return new PdfExport.ImageSource() {
            @Override
            public byte[] renderRegion(int pageIdx, float x0, float y0, float x1, float y1, int[] out) {
                try {
                    com.github.barteksc.pdfviewer.PdfFile pf = host.pdfView.getPdfFile();
                    if (pf == null || pf.getPdfDocument() == null || pf.getPdfiumCore() == null) return null;
                    PdfiumCore core = pf.getPdfiumCore();
                    PdfDocument doc = pf.getPdfDocument();
                    com.shockwave.pdfium.util.Size ps = core.getPageSize(doc, pageIdx);
                    if (ps == null || ps.getWidth() <= 0 || ps.getHeight() <= 0) return null;
                    float rw = Math.max(1f, x1 - x0), rh = Math.max(1f, y1 - y0);
                    /* مقیاس: ناحیه حدود ۱۰۰۰px؛ سقف کل صفحه ۲۲۰۰px */
                    float pageW = ps.getWidth(), pageH = ps.getHeight();
                    float scale = Math.min(3.0f, Math.max(1.0f, 1000f / rw));
                    int bw = Math.round(pageW * scale), bh = Math.round(pageH * scale);
                    if (bw > 2200) { bw = 2200; }
                    if (bh > 2200) { bh = 2200; }
                    if (bw < 8 || bh < 8) return null;
                    /* نسبت‌ها (مستقل از اختلاف جزئی اندازهٔ pdfium و pdf.js) */
                    float fx0 = Math.max(0f, Math.min(1f, x0 / Math.max(1f, host.lpVw(pageIdx)))),
                          fx1 = Math.max(0f, Math.min(1f, x1 / Math.max(1f, host.lpVw(pageIdx))));
                    float fy0 = Math.max(0f, Math.min(1f, y0 / Math.max(1f, host.lpVh(pageIdx)))),
                          fy1 = Math.max(0f, Math.min(1f, y1 / Math.max(1f, host.lpVh(pageIdx))));
                    int cx0 = Math.round(fx0 * bw), cx1 = Math.round(fx1 * bw);
                    int cy0 = Math.round(fy0 * bh), cy1 = Math.round(fy1 * bh);
                    int cw = Math.max(1, cx1 - cx0), ch = Math.max(1, cy1 - cy0);
                    core.openPage(doc, pageIdx);
                    android.graphics.Bitmap page = android.graphics.Bitmap.createBitmap(bw, bh,
                            android.graphics.Bitmap.Config.ARGB_8888);
                    page.eraseColor(0xFFFFFFFF);
                    core.renderPageBitmap(doc, page, pageIdx, 0, 0, bw, bh, true);
                    android.graphics.Bitmap crop;
                    if (cx0 == 0 && cy0 == 0 && cw == bw && ch == bh) {
                        crop = page;
                    } else {
                        crop = android.graphics.Bitmap.createBitmap(page, cx0, cy0,
                                Math.min(cw, bw - cx0), Math.min(ch, bh - cy0));
                    }
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    crop.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, bos);
                    if (crop != page) crop.recycle();
                    page.recycle();
                    out[0] = Math.min(cw, bw - cx0);
                    out[1] = Math.min(ch, bh - cy0);
                    return bos.toByteArray();
                } catch (Throwable t) {
                    return null;
                }
            }
        };
    }

    /* ابعاد viewport همان صفحه که لایهٔ متن برایش ساخته شده (۱-مبنا → ۰-مبنا) */
}
