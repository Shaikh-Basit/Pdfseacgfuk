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
public class ReaderPageOps {
    final ReaderActivity host;
    ReaderPageOps(ReaderActivity h) { this.host = h; }

    void pageOpsSheet() {
        final Dialog[] dlg = new Dialog[1];
        LinearLayout sheet = Modern.sheetContent(host);
        sheet.addView(Modern.sheetHeader(host, "عملیات صفحه",
                "فایل اصلی هرگز تغییر نمی‌کند", new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                    }
                }));
        sheet.addView(Modern.compactRow(host, R.drawable.ic_pages, 0xFF64B5F6,
                "چرخش صفحه‌ها…",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                        rotateDialog();
                    }
                }), new LinearLayout.LayoutParams(-1, -2));
        sheet.addView(Modern.compactRow(host, R.drawable.ic_pages, 0xFFE57373,
                "حذف صفحه‌ها…",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                        deletePagesDialog();
                    }
                }), new LinearLayout.LayoutParams(-1, -2));
        sheet.addView(Modern.compactRow(host, R.drawable.ic_pages, 0xFF4DD0E1,
                "استخراج صفحه‌ها (بدون افت کیفیت)…",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                        pagesOpDialog("استخراج صفحات",
                                "مثلاً ۱-۳،۵،۸-۱۰ — صفحات انتخابی بدون رندر مجدد به یک PDF تازه منتقل می‌شوند (کیفیت اصلی)",
                                new ReaderActivity.PageFileOp() {
                                    @Override public File run(File src, int[] pages1based) throws Exception {
                                        int[] zero = new int[pages1based.length];
                                        for (int i = 0; i < pages1based.length; i++) zero[i] = pages1based[i] - 1;
                                        return AnnPdfExport.opExtract(host, src, zero);
                                    }
                                });
                    }
                }), new LinearLayout.LayoutParams(-1, -2));
        sheet.addView(Modern.compactRow(host, R.drawable.ic_ann_move, 0xFFFFB74D,
                "جابجایی صفحه…",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                        movePageDialog();
                    }
                }), new LinearLayout.LayoutParams(-1, -2));
        sheet.addView(Modern.compactRow(host, R.drawable.ic_ann_share, 0xFFAED581,
                "ادغام با PDF دیگر…",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                        mergeDialog();
                    }
                }), new LinearLayout.LayoutParams(-1, -2));
        sheet.addView(Modern.compactRow(host, R.drawable.ic_word, 0xFFBA68C8,
                "تقسیم به چند فایل…",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                        splitDialog();
                    }
                }), new LinearLayout.LayoutParams(-1, -2));
        dlg[0] = host.trackSheet(Modern.showSheetAuto(host, sheet), new Runnable() {
            @Override public void run() { pageOpsSheet(); }
        });
    }

    /* ---------- دیالوگ‌های عملیات صفحه ---------- */

    /* عملیات بازه‌ای عمومی: ورودی بازه → اجرا در پس‌زمینه → اشتراک خروجی */

    void pagesOpDialog(final String title, final String hint, final ReaderActivity.PageFileOp op) {
        if (host.pageCount <= 0) {
            host.toast("فایل هنوز کامل باز نشده است");
            return;
        }
        final Dialog[] dlg = new Dialog[1];
        LinearLayout card = Modern.cardContent(host);
        card.addView(Modern.sheetHeader(host, title, hint, new View.OnClickListener() {
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
        act.addView(Modern.filledBtn(host, "اجرا و اشتراک", Modern.C_ACCENT,
                Modern.onColor(Modern.C_ACCENT), new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        int[] pages = PdfExport.parseRanges(et.getText().toString(), host.pageCount);
                        if (pages == null || pages.length == 0) {
                            host.toast("بازه نامعتبر است — مثال: ۱-۳،۵");
                            return;
                        }
                        try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                        runPageOp(title, pages, op);
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

    /* اجرای عملیات در پس‌زمینه + اشتراک خروجی */

    void runPageOp(final String title, final int[] pages, final ReaderActivity.PageFileOp op) {
        final Dialog prog = host.busyCard(title + "…");
        host.io.execute(new Runnable() {
            @Override public void run() {
                try {
                    final java.util.concurrent.atomic.AtomicReference<File> slot =
                            new java.util.concurrent.atomic.AtomicReference<File>();
                    host.share.fileForPdfBox(slot);
                    final File out = op.run(slot.get(), pages);
                    host.ui.post(new Runnable() {
                        @Override public void run() {
                            try { prog.dismiss(); } catch (Throwable ignored) {}
                            if (out != null && out.exists() && out.length() > 0) {
                                host.share.shareTempPdf(out, title);
                            } else {
                                host.toast("خروجی خالی است — بازه را بررسی کن");
                            }
                        }
                    });
                } catch (final Exception e) {
                    host.ui.post(new Runnable() {
                        @Override public void run() {
                            try { prog.dismiss(); } catch (Throwable ignored) {}
                            host.toast("عملیات ممکن نشد: " + e.getMessage());
                        }
                    });
                }
            }
        });
    }

    /* چرخش: بازه + زاویه */

    void rotateDialog() {
        if (host.pageCount <= 0) {
            host.toast("فایل هنوز کامل باز نشده است");
            return;
        }
        final Dialog[] dlg = new Dialog[1];
        LinearLayout card = Modern.cardContent(host);
        card.addView(Modern.sheetHeader(host, "چرخش صفحات",
                "خالی = همهٔ صفحه‌ها", new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                    }
                }));
        final EditText et = new EditText(host);
        et.setHint("مثلاً ۲،۵-۷ (خالی = همه)");
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
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, host.dp(12), 0, 0);
        card.addView(row, new LinearLayout.LayoutParams(-1, -2));
        String[] labels = {"۹۰°", "۱۸۰°", "۲۷۰°"};
        final int[] degs = {90, 180, 270};
        for (int i = 0; i < 3; i++) {
            final int deg = degs[i];
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, -2, 1f);
            if (i > 0) blp.setMarginStart(host.dp(8));
            row.addView(Modern.tonalBtn(host, labels[i], Modern.C_ACCENT, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    int[] pages = PdfExport.parseRanges(
                            et.getText().toString().trim().length() == 0
                                    ? "1-" + host.pageCount : et.getText().toString(), host.pageCount);
                    if (pages == null || pages.length == 0) {
                        host.toast("بازه نامعتبر است");
                        return;
                    }
                    try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                    final int[] pages0 = new int[pages.length];
                    for (int k = 0; k < pages.length; k++) pages0[k] = pages[k] - 1;
                    runPageOp("چرخش " + deg + " درجه", pages0, new ReaderActivity.PageFileOp() {
                        @Override public File run(File src, int[] ps) throws Exception {
                            return AnnPdfExport.opRotate(host, src, ps, deg);
                        }
                    });
                }
            }), blp);
        }
        dlg[0] = host.trackDialog(Modern.showCardAuto(host, card));
    }

    /* حذف صفحات با تأیید */

    void deletePagesDialog() {
        pagesOpDialog("حذف صفحات", "این صفحه‌ها از خروجی حذف می‌شوند — فایل اصلی سالم می‌ماند", new ReaderActivity.PageFileOp() {
            @Override public File run(File src, int[] pages1based) throws Exception {
                int[] zero = new int[pages1based.length];
                for (int i = 0; i < pages1based.length; i++) zero[i] = pages1based[i] - 1;
                return AnnPdfExport.opDelete(host, src, zero);
            }
        });
    }

    /* جابجایی: صفحهٔ X به موقعیت Y */

    void movePageDialog() {
        if (host.pageCount <= 0) {
            host.toast("فایل هنوز کامل باز نشده است");
            return;
        }
        final Dialog[] dlg = new Dialog[1];
        LinearLayout card = Modern.cardContent(host);
        card.addView(Modern.sheetHeader(host, "جابجایی صفحه", null, new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { dlg[0].dismiss(); } catch (Throwable ignored) {}
            }
        }));
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, host.dp(10), 0, 0);
        card.addView(row, new LinearLayout.LayoutParams(-1, -2));
        final EditText etFrom = new EditText(host);
        etFrom.setHint("صفحه");
        etFrom.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        etFrom.setTextColor(Modern.C_TEXT);
        etFrom.setHintTextColor(Modern.C_MUTED);
        etFrom.setTextSize(16f);
        etFrom.setGravity(android.view.Gravity.CENTER);
        etFrom.setBackground(Modern.fieldBg(host));
        etFrom.setPadding(host.dp(10), host.dp(11), host.dp(10), host.dp(11));
        etFrom.setSingleLine(true);
        row.addView(etFrom, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView arrow = new TextView(host);
        arrow.setText("←");
        arrow.setTextColor(Modern.C_MUTED);
        arrow.setTextSize(18f);
        arrow.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams alp2 = new LinearLayout.LayoutParams(-2, -2);
        alp2.setMargins(host.dp(8), 0, host.dp(8), 0);
        row.addView(arrow, alp2);
        final EditText etTo = new EditText(host);
        etTo.setHint("به موقعیت");
        etTo.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        etTo.setTextColor(Modern.C_TEXT);
        etTo.setHintTextColor(Modern.C_MUTED);
        etTo.setTextSize(16f);
        etTo.setGravity(android.view.Gravity.CENTER);
        etTo.setBackground(Modern.fieldBg(host));
        etTo.setPadding(host.dp(10), host.dp(11), host.dp(10), host.dp(11));
        etTo.setSingleLine(true);
        row.addView(etTo, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView goBtn = Modern.filledBtn(host, "اجرا و اشتراک", Modern.C_ACCENT,
                Modern.onColor(Modern.C_ACCENT), new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        int from = 0, to = 0;
                        try {
                            from = Integer.parseInt(etFrom.getText().toString().trim());
                            to = Integer.parseInt(etTo.getText().toString().trim());
                        } catch (Exception e) {
                        }
                        if (from < 1 || from > host.pageCount || to < 1 || to > host.pageCount || from == to) {
                            host.toast("شمارهٔ صفحه معتبر وارد کن (۱ تا " + Fa.d(host.pageCount) + ")");
                            return;
                        }
                        try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                        final int f = from - 1, t = to - 1;
                        runPageOp("جابجایی صفحه", new int[]{from}, new ReaderActivity.PageFileOp() {
                            @Override public File run(File src, int[] unused) throws Exception {
                                int n = host.pageCount;
                                int[] order = new int[n];
                                for (int i = 0; i < n; i++) order[i] = i;
                                int moving = order[f];
                                if (t > f) {
                                    for (int i = f; i < t; i++) order[i] = order[i + 1];
                                } else {
                                    for (int i = f; i > t; i--) order[i] = order[i - 1];
                                }
                                order[t] = moving;
                                return AnnPdfExport.opReorder(host, src, order);
                            }
                        });
                    }
                });
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(-1, -2);
        btnLp.topMargin = host.dp(14);
        card.addView(goBtn, btnLp);
        dlg[0] = host.trackDialog(Modern.showCardAuto(host, card));
    }

    /* ---------- ادغام: انتخاب چند PDF با SAF ---------- */

    void mergeDialog() {
        try {
            android.content.Intent it = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
            it.addCategory(android.content.Intent.CATEGORY_OPENABLE);
            it.setType("application/pdf");
            it.putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, true);
            host.startActivityForResult(it, ReaderActivity.REQ_MERGE_PICK);
        } catch (Throwable t) {
            host.toast("باز کردن فایل‌منیجر ممکن نشد");
        }
    }

    /* حذف تکراری REQ_MERGE_PICK — این بخش توسط onActivityResult صدا می‌شود */

    void mergeWith(final java.util.List<Uri> picked) {
        if (picked == null || picked.isEmpty()) {
            host.toast("فایلی انتخاب نشد");
            return;
        }
        final Dialog prog = host.busyCard("در حال ادغام " + Fa.d(picked.size()) + " فایل…");
        host.io.execute(new Runnable() {
            @Override public void run() {
                try {
                    final java.util.concurrent.atomic.AtomicReference<File> slot =
                            new java.util.concurrent.atomic.AtomicReference<File>();
                    host.share.fileForPdfBox(slot);
                    /* فایل‌های انتخابی را به cache کپی کن (SAF ممکن است stream-only باشد) */
                    File dir = new File(host.getCacheDir(), "annexport");
                    if (!dir.exists()) dir.mkdirs();
                    final java.util.List<File> others = new ArrayList<File>();
                    int k = 0;
                    for (Uri u : picked) {
                        k++;
                        File tmp = new File(dir, "merge-" + k + ".pdf");
                        java.io.InputStream in = host.getContentResolver().openInputStream(u);
                        if (in == null) continue;
                        try {
                            java.io.FileOutputStream fo = new java.io.FileOutputStream(tmp);
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
                        if (tmp.length() > 0) others.add(tmp);
                    }
                    if (others.isEmpty()) throw new Exception("فایل قابل‌خواندنی نبود");
                    final File out = AnnPdfExport.opMerge(host, slot.get(), others);
                    host.ui.post(new Runnable() {
                        @Override public void run() {
                            try { prog.dismiss(); } catch (Throwable ignored) {}
                            host.share.shareTempPdf(out, "ادغام " + Fa.d(picked.size() + 1) + " فایل PDF");
                        }
                    });
                } catch (final Exception e) {
                    host.ui.post(new Runnable() {
                        @Override public void run() {
                            try { prog.dismiss(); } catch (Throwable ignored) {}
                            host.toast("ادغام ممکن نشد: " + e.getMessage());
                        }
                    });
                }
            }
        });
    }

    /* ---------- تقسیم: بازه‌ها یا هر N صفحه ---------- */

    void splitDialog() {
        if (host.pageCount <= 0) {
            host.toast("فایل هنوز کامل باز نشده است");
            return;
        }
        final Dialog[] dlg = new Dialog[1];
        LinearLayout card = Modern.cardContent(host);
        card.addView(Modern.sheetHeader(host, "تقسیم PDF",
                "هر بخش یک فایل تازه می‌شود", new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                    }
                }));
        final TextView lab = new TextView(host);
        lab.setText("بازهٔ هر بخش — مثل «۱-۳،۴-۸» یا فقط یک عدد = هر N صفحه");
        lab.setTextColor(Modern.C_MUTED);
        lab.setTextSize(12.5f);
        card.addView(lab, new LinearLayout.LayoutParams(-1, -2));
        final EditText et = new EditText(host);
        et.setHint("مثلاً ۳  یا  ۱-۴،۵-۹");
        et.setTextColor(Modern.C_TEXT);
        et.setHintTextColor(Modern.C_MUTED);
        et.setTextSize(16f);
        et.setGravity(android.view.Gravity.CENTER);
        et.setBackground(Modern.fieldBg(host));
        int ep = host.dp(14);
        et.setPadding(ep, host.dp(11), ep, host.dp(11));
        et.setSingleLine(true);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(-1, -2);
        elp.topMargin = host.dp(8);
        card.addView(et, elp);
        card.addView(Modern.filledBtn(host, "تقسیم و اشتراک", Modern.C_ACCENT,
                Modern.onColor(Modern.C_ACCENT), new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        String raw = et.getText().toString().trim();
                        if (raw.length() == 0) {
                            host.toast("ورودی خالی است");
                            return;
                        }
                        /* «عدد تکی» = هر N صفحه — بازه‌های خودکار */
                        final java.util.List<int[]> parts = new ArrayList<int[]>();
                        String digits = raw.replaceAll("[^0-9]", "");
                        boolean plain = digits.length() > 0 && raw.replace(" ", "").length() == digits.length();
                        int n = 0;
                        try {
                            n = Integer.parseInt(digits.trim());
                        } catch (Exception e) {
                            n = 0;
                        }
                        if (plain && n > 0) {
                            for (int s = 1; s <= host.pageCount; s += n) {
                                parts.add(new int[]{s, Math.min(s + n - 1, host.pageCount)});
                            }
                        } else {
                            /* بازه‌ها: ۱-۴،۵-۹ */
                            String[] chunks = raw.split("[،,;]+");
                            for (String ch : chunks) {
                                String c2 = ch.trim();
                                if (c2.length() == 0) continue;
                                String[] ab = c2.split("[-–—]+");
                                try {
                                    if (ab.length == 1) {
                                        int p = Integer.parseInt(ab[0].trim());
                                        parts.add(new int[]{p, p});
                                    } else {
                                        parts.add(new int[]{Integer.parseInt(ab[0].trim()), Integer.parseInt(ab[1].trim())});
                                    }
                                } catch (Exception ignored) {
                                }
                            }
                        }
                        if (parts.isEmpty()) {
                            host.toast("بازه نامعتبر است — مثال: ۳ یا ۱-۴،۵-۹");
                            return;
                        }
                        try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                        runSplit(parts);
                    }
                }), new LinearLayout.LayoutParams(-1, -2));
        dlg[0] = host.trackDialog(Modern.showCardAuto(host, card));
    }

    void runSplit(final java.util.List<int[]> parts) {
        final Dialog prog = host.busyCard("در حال تقسیم به " + Fa.d(parts.size()) + " فایل…");
        host.io.execute(new Runnable() {
            @Override public void run() {
                try {
                    final java.util.concurrent.atomic.AtomicReference<File> slot =
                            new java.util.concurrent.atomic.AtomicReference<File>();
                    host.share.fileForPdfBox(slot);
                    File src = slot.get();
                    File dir = new File(host.getCacheDir(), "annexport");
                    if (!dir.exists()) dir.mkdirs();
                    try {
                        File[] old = dir.listFiles();
                        if (old != null) for (File f : old) { try { f.delete(); } catch (Exception ignored) {} }
                    } catch (Exception ignored) {}
                    String base = host.share.shareSafeName().replaceAll("\\.[^.]+$", "");
                    final java.util.List<File> outs = new ArrayList<File>();
                    int k = 0;
                    for (int[] rg : parts) {
                        k++;
                        int a = Math.max(1, rg[0]), b = Math.min(host.pageCount, Math.max(rg[0], rg[1]));
                        if (a > b) continue;
                        int[] zero = new int[b - a + 1];
                        for (int i = 0; i < zero.length; i++) zero[i] = a - 1 + i;
                        File out = new File(dir, base + "-part" + k + ".pdf");
                        com.tom_roush.pdfbox.pdmodel.PDDocument srcDoc = null;
                        com.tom_roush.pdfbox.pdmodel.PDDocument outDoc = null;
                        try {
                            srcDoc = com.tom_roush.pdfbox.pdmodel.PDDocument.load(src, "",
                                    com.tom_roush.pdfbox.io.MemoryUsageSetting.setupMixed(48L * 1024 * 1024));
                            outDoc = new com.tom_roush.pdfbox.pdmodel.PDDocument();
                            for (int p : zero) {
                                if (p >= 0 && p < srcDoc.getNumberOfPages()) outDoc.importPage(srcDoc.getPage(p));
                            }
                            outDoc.save(out);
                            outs.add(out);
                        } finally {
                            try { if (srcDoc != null) srcDoc.close(); } catch (Exception ignored) {}
                            try { if (outDoc != null) outDoc.close(); } catch (Exception ignored) {}
                        }
                    }
                    host.ui.post(new Runnable() {
                        @Override public void run() {
                            try { prog.dismiss(); } catch (Throwable ignored) {}
                            if (outs.isEmpty()) {
                                host.toast("بازه‌ها معتبر نیستند");
                                return;
                            }
                            if (outs.size() == 1) {
                                host.share.shareTempPdf(outs.get(0), "تقسیم PDF");
                                return;
                            }
                            /* اشتراک چندگانه */
                            try {
                                java.util.ArrayList<Uri> uris = new java.util.ArrayList<Uri>();
                                android.content.ClipData cd = null;
                                for (File f : outs) {
                                    Uri put = ShareProvider.put(f);
                                    uris.add(put);
                                    if (cd == null) cd = ClipData.newRawUri("pdf", put);
                                    else cd.addItem(new ClipData.Item(put));
                                }
                                Intent it = new Intent("android.intent.action.SEND_MULTIPLE");
                                it.setType("application/pdf");
                                it.putParcelableArrayListExtra("android.intent.extra.STREAM", uris);
                                it.setClipData(cd);
                                it.addFlags(1);
                                host.startActivity(Intent.createChooser(it, "اشتراک " + Fa.d(outs.size()) + " فایل"));
                            } catch (Exception unused) {
                                host.toast("اشتراک ممکن نشد");
                            }
                        }
                    });
                } catch (final Exception e) {
                    host.ui.post(new Runnable() {
                        @Override public void run() {
                            try { prog.dismiss(); } catch (Throwable ignored) {}
                            host.toast("تقسیم ممکن نشد: " + e.getMessage());
                        }
                    });
                }
            }
        });
    }

    /* ================== v1.37: نشانگر ۱۰۰٪ زوم ==================
       یک «لگ» کوچک و بی‌آزار: ویبرهٔ سبک + قرص کوچک «۱۰۰٪» که محو می‌شود. */
}
