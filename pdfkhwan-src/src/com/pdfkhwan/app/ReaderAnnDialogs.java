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
public class ReaderAnnDialogs {
    final ReaderActivity host;
    ReaderAnnDialogs(ReaderActivity h) { this.host = h; }

    int[] hlPalette = {0xFFFFD500, 0xFFFF4530, 0xFF3B88FF, 0xFF4D4D4D,
            0xFFFF9500, 0xFF2FBF71, 0xFFAF52DE, 0xFFFF6482};

    int hlColorIdx = 0;
    /* ابزار رسم فعال: 0=خاموش 1=قلم 2=ماژیک 3=پاک‌کن 4=فلش */

    boolean pendingFnote = false;
    /* در انتظار لمس برای جابجایی یادداشت آزاد موجود */

    Annotations.Ann pendingFnoteMove = null;
    /* صف درخواست واژه‌ها برای صفحاتی که حاشیه‌نویسی متنی دارند */

    int fnoteColor = 0xFF64B5F6;

    void annImportFromPdf() {
        if (host.anns == null || !host.anns.items.isEmpty()) return;
        final ReaderActivity self = host;
        new Thread(new Runnable() {
            @Override public void run() {
                List<Annotations.Ann> imported = null;
                try {
                    final java.util.concurrent.atomic.AtomicReference<File> slot =
                            new java.util.concurrent.atomic.AtomicReference<File>();
                    host.share.fileForPdfBox(slot);
                    if (slot.get() != null) {
                        imported = AnnPdfExport.importAnns(slot.get());
                    }
                } catch (Throwable t) {
                    imported = null;
                }
                final List<Annotations.Ann> res = imported;
                if (res == null || res.isEmpty()) return;
                self.ui.post(new Runnable() {
                    @Override public void run() {
                        try {
                            if (self.anns == null || !self.anns.items.isEmpty()) return;
                            for (Annotations.Ann a : res) {
                                self.anns.add(a);
                            }
                            if (!self.anns.items.isEmpty()) {
                                if (self.selOverlay != null) {
                                    self.selOverlay.setVisibility(View.VISIBLE);
                                    self.selOverlay.invalidate();
                                }
                                host.startGeomWatch();
                                host.toast("حاشیه‌نویسی‌های داخل فایل بارگذاری شد — قابل ویرایش");
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                });
            }
        }, "ann-import").start();
    }

    void initAnnotations() {
        try {
            host.anns = Annotations.load(host, host.fileKey);
        } catch (Throwable t) {
            host.anns = new Annotations();
        }
        /* v1.50: بازخوانی annotationهای استانداردِ داخل خود PDF —
           خروجی «قابل ویرایش» که قبلاً ساخته‌ایم (یا annotationهای Adobe)
           حالا در خودِ اپ هم قابل مشاهده/ویرایش‌اند. در پس‌زمینه می‌آید. */
        try {
            annImportFromPdf();
        } catch (Throwable ignoredImport) {
        }
        /* v1.44: سند تازه → تاریخچهٔ واگرد/بازانجام تازه */
        host.pen.undoStack.clear();
        host.pen.redoStack.clear();
        host.pen.eraseGesture = null;
        /* v1.46: سند تازه → نقشهٔ فرم/لینک تازه */
        host.pdfInter = null;
        host.forms.interactLoaded = false;
        host.forms.formMode = false;
        host.forms.formFocus = -1;
        host.forms.formValues = new java.util.LinkedHashMap<String, String>();
        host.links.linkBackStack.clear();
        try {
            if (host.forms.formBar != null) host.forms.formBar.setVisibility(8);
        } catch (Throwable ignored) {
        }
        try {
            if (host.forms.formOverlay != null) host.forms.formOverlay.setVisibility(8);
        } catch (Throwable ignored) {
        }
        try {
            if (host.links.linkBackBtn != null) host.links.linkBackBtn.setVisibility(8);
        } catch (Throwable ignored) {
        }
        /* پالت شخصی‌سازی‌شده از تنظیمات */
        try {
            org.json.JSONObject st = new org.json.JSONObject(
                    host.getSharedPreferences("pdfkhwan", 0).getString("settings", "{}"));
            org.json.JSONArray arr = st.optJSONArray("hlPalette");
            if (arr != null && arr.length() >= 5) {
                /* v1.38: اگر پالت ذخیره‌شده همان پیش‌فرضِ قدیمی است (کاربر هرگز
                   خودش عوضش نکرده)، با پالت جدید ماژیک‌های واقعی جایگزین می‌شود */
                boolean oldDefault = arr.optInt(0, 0) == 0xFFFFD54F
                        && arr.optInt(1, 0) == 0xFF81C784
                        && arr.optInt(2, 0) == 0xFF64B5F6
                        && arr.optInt(3, 0) == 0xFFF06292
                        && arr.optInt(4, 0) == 0xFFFF8A65;
                /* v1.40: پیش‌فرض v1.38 هم مهاجرت می‌کند (پالت پاستلی قدیمی) */
                boolean oldDefault38 = arr.optInt(0, 0) == 0xFFFFD84D
                        && arr.optInt(1, 0) == 0xFFFFA94D
                        && arr.optInt(2, 0) == 0xFF8CE88C
                        && arr.optInt(3, 0) == 0xFF7EC8FF
                        && arr.optInt(4, 0) == 0xFFC9A6FF;
                if (!oldDefault && !oldDefault38) {
                    for (int i = 0; i < Math.min(hlPalette.length, arr.length()); i++) {
                        hlPalette[i] = arr.optInt(i, hlPalette[i]) | 0xFF000000;
                    }
                }
            }
            /* v1.40: چهار رنگ استاندارد (زرد/سرخ/آبی/سیاه) «همیشه» چهار
               جایگاه اول پالت‌اند — در هر حالتی اول دیده می‌شوند */
            for (int i = 0; i < 4 && i < hlPalette.length; i++) {
                hlPalette[i] = ReaderActivity.STD_MARKER_COLORS[i];
            }
            host.pen.penColor = st.optInt("penColor", host.pen.penColor);
            host.pen.penWidthDp = (float) st.optDouble("penWidth", 2.5);
            fnoteColor = st.optInt("fnoteColor", fnoteColor);
            /* v1.37: رنگ/ضخامت جداگانهٔ هر ابزار + حالت‌های پاک‌کن/قفل زوم */
            int cDef = host.pen.penColor != 0 ? host.pen.penColor : 0xFFE53935;
            host.pen.toolColors[1] = st.optInt("tc1", cDef);
            /* v1.40: رنگ پیش‌فرض ماژیک — زرد استاندارد جدید؛ اگر کاربر هنوز
               رنگ پیش‌فرضِ قدیمی (زرد پاستلی) را دارد، خودکار ارتقا می‌یابد */
            int tc2Stored = st.optInt("tc2", ReaderActivity.STD_MARKER_COLORS[0]);
            if (tc2Stored == 0xFFFFD84D) tc2Stored = ReaderActivity.STD_MARKER_COLORS[0];
            host.pen.toolColors[2] = tc2Stored;
            host.pen.toolColors[3] = 0;
            host.pen.toolColors[4] = st.optInt("tc4", 0xFF42A5F5);
            float wDef = host.pen.penWidthDp > 0 ? host.pen.penWidthDp : 2.5f;
            host.pen.toolWidths[1] = (float) st.optDouble("tw1", wDef);
            /* v1.38: مقیاس جدید ماژیک — قدیم حداکثر ۱۴dp بود و حتی درشت‌ترین حالتش
               از ماژیک اپ‌های دیگر خیلی باریک‌تر؛ مقادیر قدیمی (زیر ۱۵) یک‌بار به
               پیش‌فرض جدید ۲۶dp ارتقا می‌یابند — پرچم tw2m جلوی تکرار را می‌گیرد */
            float tw2 = (float) st.optDouble("tw2", 26.0);
            if (!st.optBoolean("tw2m", false)) {
                if (tw2 <= 15f) tw2 = 26.0f;
                try {
                    st.put("tw2m", true);
                    st.put("tw2", (double) tw2);
                    host.getSharedPreferences("pdfkhwan", 0).edit()
                            .putString("settings", st.toString()).apply();
                } catch (Throwable ignoredM) {
                }
            }
            host.pen.toolWidths[2] = tw2;
            host.pen.toolWidths[3] = 0;
            host.pen.toolWidths[4] = (float) st.optDouble("tw4", 2.5);
            host.pen.zoomLocked = st.optBoolean("zoomLock", false);
            host.pen.eraserPartial = st.optBoolean("eraserPartial", false);
            host.pen.eraserSizeDp = (float) st.optDouble("eraserSize", 14.0);
            /* v1.38: موضع نوار رسم — چسبیده به کدام لبه، یا شناور آزاد */
            host.pen.penDock = st.optInt("penDock", 1);
            host.pen.penFreeX = (float) st.optDouble("penFreeX", 0.5);
            host.pen.penFreeY = (float) st.optDouble("penFreeY", 0.7);
        } catch (Throwable ignored) {
        }
    }

    void addTextAnn(int type) {
        addTextAnn(type, 0);
    }

    /* v1.41: رنگ صریح (پاپ‌آپ سه‌رنگ) — ۰ یعنی رنگ فعال پالت */

    void addTextAnn(int type, int explicitColor) {
        if (host.selPage == null || host.selStart < 0 || host.selEnd < 0 || host.selPageIdx < 0) {
            host.toast("اول متنی را انتخاب کن");
            return;
        }
        Annotations.Ann a = new Annotations.Ann();
        a.type = type;
        a.page = host.selPageIdx;
        a.w0 = Math.min(host.selStart, host.selEnd);
        a.w1 = Math.max(host.selStart, host.selEnd);
        a.text = host.selText();
        a.color = type == Annotations.T_NOTE ? fnoteColor
                : (explicitColor != 0 ? (explicitColor | 0xFF000000) : host.pen.hlColor());
        Annotations.Ann added = host.anns.add(a);
        if (added == null) {
            host.toast("همین حاشیه‌نویسی از قبل هست (تکراری)");
            host.clearSelection();
            return;
        }
        host.anns.save();
        /* v1.44: هایلایت/زیرخط/یادداشت متنی هم واگرد‌شدنی */
        ReaderActivity.HistOp top = new ReaderActivity.HistOp();
        top.changes.add(new ReaderActivity.AnnChange(added.id, null, added.copy()));
        host.pen.histPush(top);
        if (type == Annotations.T_NOTE) {
            host.clearSelection();
            noteEditor(added, true);
        } else {
            host.clearSelection();
            host.toast(Annotations.typeName(type) + " ثبت شد ✓");
        }
        host.annDraw.annInvalidate();
    }

    /* ================== رسم حاشیه‌نویسی‌ها ==================
       v1.38: این لایه حالا داخل پاس رسم خود PDFView کشیده می‌شود
       (PDFView.OnViewOverlay) — مختصات همان مختصات دید است ولی پیکسل‌ها
       با خود صفحه یکی‌اند؛ به همین دلیل هایلایت/ماژیک با بلند
       مولتی‌پلای می‌تواند واقعاً «زیر جوهر متن» بیفتد. */

    void annActionDialog(final Annotations.Ann a) {
        final Dialog[] dlg = new Dialog[1];
        LinearLayout sheet = Modern.sheetContent(host);

        /* --- سربرگ: نوع + صفحه --- */
        LinearLayout head = new LinearLayout(host);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int hp = host.dp(18);
        head.setPadding(hp, host.dp(14), hp, host.dp(4));

        View dot = new View(host);
        dot.setBackground(Modern.circle((a.color & 0x00FFFFFF) | 0xFF000000, host));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(host.dp(13), host.dp(13));
        dlp.setMarginEnd(host.dp(9));
        dot.setLayoutParams(dlp);
        head.addView(dot);

        TextView title = new TextView(host);
        title.setText(Annotations.typeName(a.type) + " \u00b7 \u0635\u0641\u062d\u0647\u0654 " + Fa.d(a.page + 1));
        title.setTextColor(Modern.C_TEXT);
        title.setTextSize(16f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        head.addView(Modern.closeBtn(host, new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { dlg[0].dismiss(); } catch (Throwable ignored) {}
            }
        }));
        sheet.addView(head);

        /* --- پیش‌نمایش متن/یادداشت --- */
        String text = a.text != null && a.text.length() > 0 ? a.text : "";
        String note = a.note != null && a.note.length() > 0 ? a.note : "";
        if (text.length() > 0 || note.length() > 0) {
            LinearLayout prev = new LinearLayout(host);
            prev.setOrientation(LinearLayout.VERTICAL);
            prev.setBackground(Modern.fieldBg(host));
            int pp = host.dp(13);
            prev.setPadding(pp, pp, pp, pp);
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, -2);
            int m = host.dp(18);
            plp.setMargins(m, host.dp(8), m, host.dp(4));
            sheet.addView(prev, plp);
            if (text.length() > 0) {
                TextView tv = new TextView(host);
                tv.setText(text.length() > 280 ? text.substring(0, 280) + "\u2026" : text);
                tv.setTextColor(Modern.C_TEXT);
                tv.setTextSize(13.5f);
                tv.setLineSpacing(host.dp(3), 1f);
                prev.addView(tv);
            }
            if (note.length() > 0) {
                TextView nv = new TextView(host);
                nv.setText("\ud83d\udcdd " + note);
                nv.setTextColor(Modern.C_MUTED);
                nv.setTextSize(13f);
                nv.setLineSpacing(host.dp(3), 1f);
                if (text.length() > 0) {
                    LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-1, -2);
                    nlp.topMargin = host.dp(8);
                    nv.setLayoutParams(nlp);
                }
                prev.addView(nv);
            }
        }

        /* --- شبکهٔ عملیات --- */
        LinearLayout grid = new LinearLayout(host);
        grid.setOrientation(LinearLayout.HORIZONTAL);
        grid.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int gp = host.dp(10);
        grid.setPadding(gp, host.dp(14), gp, host.dp(16));

        grid.addView(Modern.iconLabelBtn(host, R.drawable.ic_ann_palette, Modern.C_ACCENT,
                "\u062a\u063a\u06cc\u06cc\u0631 \u0631\u0646\u06af", new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                        colorPickerDialog(a.color | 0xFF000000, new ReaderActivity.ColorPick() {
                            @Override public void onPick(int color) {
                                a.color = color;
                                host.anns.save();
                                host.annDraw.annInvalidate();
                                host.toast("\u0631\u0646\u06af \u062a\u063a\u06cc\u06cc\u0631 \u06a9\u0631\u062f \u2713");
                            }
                        });
                    }
                }), new LinearLayout.LayoutParams(0, -2, 1f));

        if (a.type == Annotations.T_NOTE || a.type == Annotations.T_FNOTE) {
            grid.addView(Modern.iconLabelBtn(host, R.drawable.ic_ann_pen, Modern.C_ACCENT,
                    "\u0648\u06cc\u0631\u0627\u06cc\u0634", new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                            noteEditor(a, true);
                        }
                    }), new LinearLayout.LayoutParams(0, -2, 1f));
        } else {
            grid.addView(Modern.iconLabelBtn(host, R.drawable.ic_ann_copy, Modern.C_ACCENT,
                    "\u06a9\u067e\u06cc \u0645\u062a\u0646", new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            if (a.text != null && a.text.length() > 0) {
                                try {
                                    ((ClipboardManager) host.getSystemService("clipboard"))
                                            .setPrimaryClip(ClipData.newPlainText("pdfkhwan", a.text));
                                    host.toast("\u06a9\u067e\u06cc \u0634\u062f \u2713");
                                } catch (Exception ignored) {
                                }
                            }
                        }
                    }), new LinearLayout.LayoutParams(0, -2, 1f));
        }

        if (a.type == Annotations.T_FNOTE && a.id > 0) {
            grid.addView(Modern.iconLabelBtn(host, R.drawable.ic_ann_move, Modern.C_MUTED,
                    "\u062c\u0627\u0628\u062c\u0627\u06cc\u06cc", new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                            pendingFnoteMove = a;
                            host.toast("\u0645\u062d\u0644 \u062c\u062f\u06cc\u062f \u06cc\u0627\u062f\u062f\u0627\u0634\u062a \u0631\u0627 \u0644\u0645\u0633 \u06a9\u0646");
                        }
                    }), new LinearLayout.LayoutParams(0, -2, 1f));
        }

        grid.addView(Modern.iconLabelBtn(host, R.drawable.ic_ann_trash, Modern.C_DANGER,
                "\u062d\u0630\u0641", new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                        /* v1.44: حذف هم واگرد‌شدنی */
                        ReaderActivity.HistOp dop = new ReaderActivity.HistOp();
                        dop.changes.add(new ReaderActivity.AnnChange(a.id, a.copy(), null));
                        host.pen.histPush(dop);
                        host.anns.remove(a.id);
                        host.anns.save();
                        host.annDraw.annInvalidate();
                        host.toast("\u062d\u0630\u0641 \u0634\u062f (\u0642\u0627\u0628\u0644 \u0648\u0627\u06af\u0631\u062f)");
                    }
                }), new LinearLayout.LayoutParams(0, -2, 1f));

        sheet.addView(grid, new LinearLayout.LayoutParams(-1, -2));
        dlg[0] = host.trackDialog(Modern.showSheetAuto(host, sheet));
    }

    /* ================== لایهٔ رسم آزاد (قلم/ماژیک/پاک‌کن/فلش) ==================
       فقط وقتی ابزاری فعال است لمس را می‌گیرد؛ خطوط نرمال‌شده روی صفحه
       ذخیره می‌شوند تا با اسکرول و زوم هم‌تراز بمانند. */

    void addQuickColors(LinearLayout sheet, int current, final Dialog[] dlg, final ReaderActivity.ColorPick cb) {
        LinearLayout grid = new LinearLayout(host);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(host.dp(16), host.dp(8), host.dp(16), host.dp(2));
        sheet.addView(grid, new LinearLayout.LayoutParams(-1, -2));
        for (int row = 0; row < 2; row++) {
            LinearLayout r = new LinearLayout(host);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(android.view.Gravity.CENTER);
            for (int col = 0; col < 4; col++) {
                final int c = ReaderActivity.QUICK_COLORS[(row * 4) + col];
                View sw = Modern.swatch(host, c, 38, (c | 0xFF000000) == (current | 0xFF000000));
                sw.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                        cb.onPick(c);
                    }
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(host.dp(38), host.dp(38));
                lp.setMargins(host.dp(5), host.dp(5), host.dp(5), host.dp(5));
                sw.setLayoutParams(lp);
                r.addView(sw);
            }
            grid.addView(r);
        }
    }

    void colorPickerDialog(int current, final ReaderActivity.ColorPick cb) {
        final Dialog[] dlg = new Dialog[1];
        LinearLayout sheet = Modern.sheetContent(host);
        sheet.addView(Modern.sheetHeader(host, "\u0627\u0646\u062a\u062e\u0627\u0628 \u0631\u0646\u06af", null, new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { dlg[0].dismiss(); } catch (Throwable ignored) {}
            }
        }));
        /* v1.43: فقط هشت رنگ اول + رنگ سفارشی — جمع‌وجور و همیشه جادر؛
           پالت ۱۶تایی و ردیف اسکرول حذف شد (خواستهٔ کاربر). */
        addQuickColors(sheet, current, dlg, cb);
        LinearLayout actRow = new LinearLayout(host);
        actRow.setOrientation(LinearLayout.HORIZONTAL);
        actRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        actRow.setPadding(host.dp(16), host.dp(8), host.dp(16), host.dp(16));
        sheet.addView(actRow, new LinearLayout.LayoutParams(-1, -2));
        actRow.addView(Modern.tonalBtn(host, "\u0631\u0646\u06af \u0633\u0641\u0627\u0631\u0634\u06cc\u2026", Modern.C_ACCENT, new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                customColorDialog(cb);
            }
        }), new LinearLayout.LayoutParams(0, -2, 1f));

        dlg[0] = host.trackDialog(Modern.showSheetAuto(host, sheet));
    }

    /* ================== v1.43: پاپ‌آپ تنظیم ابزار (چیپ رنگ نوار رسم) ==================
       خواستهٔ کاربر — همه‌چیز در یک پاپ‌آپ جمع‌وجور:
       ۱) هشت رنگ اول (زیبا و فشرده)
       ۲) اسلایدر اندازهٔ قلم/ماژیک (یا حالت و اندازهٔ پاک‌کن وقتی پاک‌کن فعال است)
       ۳) دکمهٔ رنگ سفارشی
       دکمهٔ سایز و اسلایدر از خودِ نوار حذف شده‌اند. */

    void toolSettingsDialog() {
        final Dialog[] dlg = new Dialog[1];
        LinearLayout sheet = Modern.sheetContent(host);
        sheet.addView(Modern.sheetHeader(host, host.pen.penToolName(), null, new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { dlg[0].dismiss(); } catch (Throwable ignored) {}
            }
        }));

        final boolean eraser = host.pen.penTool == 3;
        final ReaderActivity.ColorPick apply = new ReaderActivity.ColorPick() {
            @Override public void onPick(int color) {
                host.pen.toolColors[host.pen.penTool] = color;
                host.pen.applyToolStyle(host.pen.penTool);
                host.pen.savePenSettings();
                host.pen.updatePenBar();
            }
        };

        /* ۱) هشت رنگ (برای پاک‌کن مخفی می‌شود — رنگ ندارد) */
        if (!eraser) {
            addQuickColors(sheet, host.pen.toolColors[host.pen.penTool == 0 ? 1 : host.pen.penTool] | 0xFF000000, dlg, apply);
        }

        /* ۲) اسلایدر اندازه / حالت پاک‌کن */
        LinearLayout mid = new LinearLayout(host);
        mid.setOrientation(LinearLayout.VERTICAL);
        int mp = host.dp(18);
        mid.setPadding(mp, host.dp(6), mp, host.dp(4));
        sheet.addView(mid, new LinearLayout.LayoutParams(-1, -2));

        if (eraser) {
            /* حالت پاک‌کن: کل خط / نقطه‌ای */
            LinearLayout modes = new LinearLayout(host);
            modes.setOrientation(LinearLayout.HORIZONTAL);
            mid.addView(modes, new LinearLayout.LayoutParams(-1, -2));
            modes.addView(Modern.tonalBtn(host, host.pen.eraserPartial ? "پاک‌کن: نقطه‌ای" : "پاک‌کن: کل خط",
                    Modern.C_ACCENT, new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            host.pen.eraserPartial = !host.pen.eraserPartial;
                            host.pen.savePenSettings();
                            host.pen.refreshPenModeBtns();
                            host.pen.updatePenBar();
                            try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                            host.toast(host.pen.eraserPartial
                                    ? "پاک‌کن نقطه‌ای: فقط جای لمس پاک می‌شود"
                                    : "پاک‌کن کل خط: لمس کافی است کل خط پاک شود");
                        }
                    }), new LinearLayout.LayoutParams(0, -2, 1f));
        }

        TextView sizeLab = new TextView(host);
        sizeLab.setText(eraser ? "اندازهٔ پاک‌کن نقطه‌ای" : "اندازهٔ " + host.pen.penToolName());
        sizeLab.setTextColor(Modern.C_MUTED);
        sizeLab.setTextSize(12.5f);
        mid.addView(sizeLab, new LinearLayout.LayoutParams(-1, -2));

        final View prev = new View(host);
        LinearLayout prevWrap = new LinearLayout(host);
        prevWrap.setGravity(android.view.Gravity.CENTER);
        prevWrap.setPadding(0, host.dp(6), 0, 0);
        prevWrap.addView(prev, new LinearLayout.LayoutParams(-1, host.dp(30)));
        mid.addView(prevWrap, new LinearLayout.LayoutParams(-1, -2));
        final TextView val = new TextView(host);
        val.setTextColor(Modern.C_MUTED);
        val.setTextSize(12.5f);
        val.setGravity(android.view.Gravity.CENTER);
        val.setPadding(0, host.dp(4), 0, 0);
        mid.addView(val, new LinearLayout.LayoutParams(-1, -2));
        final SeekBar sb = new SeekBar(host);
        Modern.tintSlider(sb, eraser ? 0xFF8DA0BC : (host.pen.penColor | 0xFF000000), host);
        mid.addView(sb, new LinearLayout.LayoutParams(-1, -2));

        float mn, mx, cur;
        if (eraser && host.pen.eraserPartial) {
            mn = ReaderActivity.TOOL_WMIN[3]; mx = ReaderActivity.TOOL_WMAX[3]; cur = host.pen.eraserSizeDp;
        } else if (host.pen.penTool != 0) {
            mn = ReaderActivity.TOOL_WMIN[host.pen.penTool]; mx = ReaderActivity.TOOL_WMAX[host.pen.penTool]; cur = host.pen.penWidthDp;
        } else {
            mn = 1f; mx = 60f; cur = host.pen.penWidthDp;
        }
        final float fMn = mn, fMx = mx;
        int steps = Math.max(1, Math.round((mx - mn) * 2f));
        sb.setMax(steps);
        sb.setProgress(Math.max(0, Math.min(steps, Math.round((cur - mn) * 2f))));
        final boolean liveEraser = eraser;
        Runnable upd = new Runnable() {
            @Override public void run() {
                float v = fMn + (sb.getProgress() / 2f);
                if (liveEraser && host.pen.eraserPartial) host.pen.eraserSizeDp = v;
                else host.pen.penWidthDp = v;
                int px = Math.round(v * host.getResources().getDisplayMetrics().density);
                int showColor = liveEraser ? 0xFF8DA0BC : (host.pen.penColor | 0xFF000000);
                prev.setBackground(Modern.round(liveEraser || host.pen.penTool == 2
                        ? (showColor & 0x00FFFFFF) | 0x59000000 : showColor, host.dp(10), host));
                /* v1.44: prev داخل LinearLayout است → LayoutParams پایه
                   (کست به FrameLayout باعث کرش ClassCastException می‌شد) */
                android.view.ViewGroup.LayoutParams lp = prev.getLayoutParams();
                lp.height = Math.max(host.dp(4), px);
                prev.setLayoutParams(lp);
                val.setText(Fa.d(Math.round(v)) + " dp");
            }
        };
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) { upd.run(); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {
                if (host.pen.penTool != 3) host.pen.toolWidths[host.pen.penTool] = host.pen.penWidthDp;
                host.pen.savePenSettings();
                host.pen.updatePenBar();
            }
        });
        upd.run();

        /* ۳) رنگ سفارشی — آخر پاپ‌آپ */
        LinearLayout actRow = new LinearLayout(host);
        actRow.setOrientation(LinearLayout.HORIZONTAL);
        actRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        actRow.setPadding(host.dp(16), host.dp(8), host.dp(16), host.dp(16));
        sheet.addView(actRow, new LinearLayout.LayoutParams(-1, -2));
        actRow.addView(Modern.tonalBtn(host, "\u0631\u0646\u06af \u0633\u0641\u0627\u0631\u0634\u06cc\u2026", Modern.C_ACCENT, new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                customColorDialog(apply);
            }
        }), new LinearLayout.LayoutParams(0, -2, 1f));

        dlg[0] = host.trackSheet(Modern.showSheetAuto(host, sheet), new Runnable() {
            @Override public void run() { toolSettingsDialog(); }
        });
    }

    /* \u0631\u0646\u06af \u0633\u0641\u0627\u0631\u0634\u06cc \u2014 \u06f3 \u0644\u063a\u0632\u0646\u062f\u0647 \u0645\u062f\u0631\u0646 (\u0631\u0646\u06af/\u0627\u0634\u0628\u0627\u0639/\u0631\u0648\u0634\u0646\u0627\u06cc\u06cc) \u0628\u0627 \u067e\u06cc\u0634\u200c\u0646\u0645\u0627\u06cc\u0634 \u0632\u0646\u062f\u0647 */

    void customColorDialog(final ReaderActivity.ColorPick cb) {
        final Dialog[] dlg = new Dialog[1];
        LinearLayout sheet = Modern.sheetContent(host);
        sheet.addView(Modern.sheetHeader(host, "\u0631\u0646\u06af \u0633\u0641\u0627\u0631\u0634\u06cc", null, new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { dlg[0].dismiss(); } catch (Throwable ignored) {}
            }
        }));

        float[] hsl = Modern.toHsl(host.pen.penColor | 0xFF000000);
        final float[] cur = {hsl[0], Math.max(0.15f, hsl[1]), hsl[2]};
        final int[] shown = {host.pen.penColor | 0xFF000000};

        /* \u067e\u06cc\u0634\u200c\u0646\u0645\u0627\u06cc\u0634 \u0628\u0632\u0631\u06af */
        final View preview = new View(host);
        preview.setBackground(Modern.circle(shown[0], host));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(host.dp(74), host.dp(74));
        plp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        plp.topMargin = host.dp(12);
        plp.bottomMargin = host.dp(14);
        preview.setLayoutParams(plp);
        sheet.addView(preview);

        /* \u06f3 \u0644\u063a\u0632\u0646\u062f\u0647 */
        String[] names = {"\u0631\u0646\u06af", "\u0634\u062f\u062a", "\u0631\u0648\u0634\u0646\u0627\u06cc\u06cc"};
        final SeekBar[] sbs = new SeekBar[3];
        int[] maxes = {359, 100, 100};
        int[] inits = {(int) cur[0], (int) (cur[1] * 100), (int) (cur[2] * 100)};
        int sp = host.dp(20);
        for (int i = 0; i < 3; i++) {
            TextView lab = new TextView(host);
            lab.setText(names[i]);
            lab.setTextColor(Modern.C_MUTED);
            lab.setTextSize(12.5f);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-2, -2);
            llp.topMargin = host.dp(8);
            lab.setLayoutParams(llp);
            sheet.addView(lab);
            SeekBar sb = new SeekBar(host);
            sb.setMax(maxes[i]);
            sb.setProgress(inits[i]);
            sbs[i] = sb;
            sheet.addView(sb, new LinearLayout.LayoutParams(-1, -2));
        }

        Runnable upd = new Runnable() {
            @Override public void run() {
                shown[0] = Modern.hsl(sbs[0].getProgress(),
                        Modern.clamp01(sbs[1].getProgress() / 100f),
                        Modern.clamp01(sbs[2].getProgress() / 100f));
                preview.setBackground(Modern.circle(shown[0], host));
                Modern.tintSlider(sbs[0], shown[0], host);
                Modern.tintSlider(sbs[1], shown[0], host);
                Modern.tintSlider(sbs[2], shown[0], host);
            }
        };
        for (int i = 0; i < 3; i++) {
            final Runnable u = upd;
            sbs[i].setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar seekBar, int v, boolean fromUser) {
                    u.run();
                }

                @Override public void onStartTrackingTouch(SeekBar seekBar) {
                }

                @Override public void onStopTrackingTouch(SeekBar seekBar) {
                }
            });
        }
        upd.run();

        /* \u062f\u06a9\u0645\u0647 */
        LinearLayout act = new LinearLayout(host);
        act.setOrientation(LinearLayout.HORIZONTAL);
        act.setGravity(android.view.Gravity.CENTER_VERTICAL);
        act.setPadding(sp, host.dp(14), sp, host.dp(18));
        sheet.addView(act, new LinearLayout.LayoutParams(-1, -2));
        act.addView(Modern.filledBtn(host, "\u0627\u0646\u062a\u062e\u0627\u0628", shown[0] == 0 ? Modern.C_ACCENT : shown[0], Modern.onColor(shown[0]), new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                cb.onPick(shown[0]);
            }
        }), new LinearLayout.LayoutParams(-1, -2));

        dlg[0] = host.trackDialog(Modern.showSheetAuto(host, sheet));
    }

    /* بستن آخرین دیالوگ باز (برای دیالوگ انتخاب رنگ) */

    void dlgDismiss() {
        try {
            if (!host.liveDialogs.isEmpty()) host.liveDialogs.get(host.liveDialogs.size() - 1).dismiss();
        } catch (Throwable ignored) {
        }
    }

    /* ================== ویرایشگر یادداشت ================== */

    /* ================== v1.33: \u0648\u06cc\u0631\u0627\u06cc\u0634\u06af\u0631 \u06cc\u0627\u062f\u062f\u0627\u0634\u062a \u0645\u062f\u0631\u0646 ==================
       \u06a9\u0627\u0631\u062a \u0645\u0631\u06a9\u0632\u06cc: \u0645\u062f\u0627\u0631 \u0645\u062a\u0646 \u06af\u0631\u062f\u06af\u0648\u0634\u0647 + \u0627\u0646\u062f\u0627\u0632\u0647\u0654 \u0642\u0644\u0645 \u0628\u0627
       \u062f\u06a9\u0645\u0647\u200c\u0647\u0627\u06cc \u06af\u0631\u062f + \u067e\u0627\u0644\u062a \u0631\u0646\u06af + \u0627\u06a9\u0634\u0646\u200c\u0628\u0627\u0631 \u067e\u0627\u06cc\u06cc\u0646 */

    void noteEditor(final Annotations.Ann a, final boolean canDelete) {
        final Dialog[] dlg = new Dialog[1];
        LinearLayout card = Modern.cardContent(host);

        /* --- \u0633\u0631\u0628\u0631\u06af --- */
        LinearLayout head = new LinearLayout(host);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        View dot = new View(host);
        dot.setBackground(Modern.circle(a.color | 0xFF000000, host));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(host.dp(12), host.dp(12));
        dlp.setMarginEnd(host.dp(8));
        dot.setLayoutParams(dlp);
        head.addView(dot);
        TextView title = new TextView(host);
        title.setText("\u06cc\u0627\u062f\u062f\u0627\u0634\u062a \u00b7 \u0635\u0641\u062d\u0647\u0654 " + Fa.d(a.page + 1));
        title.setTextColor(Modern.C_TEXT);
        title.setTextSize(15.5f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        head.addView(Modern.closeBtn(host, new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { dlg[0].dismiss(); } catch (Throwable ignored) {}
            }
        }));
        card.addView(head, new LinearLayout.LayoutParams(-1, -2));

        /* --- \u0645\u062f\u0627\u0631 \u0645\u062a\u0646 --- */
        final EditText et = new EditText(host);
        et.setText(a.note == null ? "" : a.note);
        et.setTextColor(Modern.C_TEXT);
        et.setHintTextColor(0x66E8EEF8);
        et.setHint("\u0645\u062a\u0646 \u06cc\u0627\u062f\u062f\u0627\u0634\u062a\u2026");
        et.setTextSize(Math.max(11f, Math.min(30f, a.noteSize)));
        et.setMinLines(4);
        et.setGravity(android.view.Gravity.TOP | android.view.Gravity.RIGHT);
        et.setBackground(Modern.fieldBg(host));
        int ep = host.dp(12);
        et.setPadding(ep, ep, ep, ep);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(-1, -2);
        elp.topMargin = host.dp(12);
        card.addView(et, elp);

        /* --- \u0631\u062f\u06cc\u0641: \u0627\u0646\u062f\u0627\u0632\u0647 + \u0631\u0646\u06af --- */
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = host.dp(12);
        row.setLayoutParams(rlp);

        ImageView minus = Modern.iconBtn(host, R.drawable.ic_ann_minus, Modern.C_MUTED, null);
        final float[] size = {a.noteSize};
        final TextView szVal = new TextView(host);
        szVal.setTextColor(Modern.C_TEXT);
        szVal.setTextSize(13f);
        szVal.setTypeface(null, android.graphics.Typeface.BOLD);
        szVal.setMinWidth(host.dp(30));
        szVal.setGravity(android.view.Gravity.CENTER);
        minus.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                size[0] = Math.max(10f, size[0] - 1f);
                szVal.setText(Fa.d(Math.round(size[0])));
                et.setTextSize(size[0]);
            }
        });
        row.addView(minus, new LinearLayout.LayoutParams(host.dp(36), host.dp(36)));
        row.addView(szVal);
        ImageView plus = Modern.iconBtn(host, R.drawable.ic_ann_plus, Modern.C_MUTED, null);
        plus.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                size[0] = Math.min(32f, size[0] + 1f);
                szVal.setText(Fa.d(Math.round(size[0])));
                et.setTextSize(size[0]);
            }
        });
        row.addView(plus, new LinearLayout.LayoutParams(host.dp(36), host.dp(36)));
        szVal.setText(Fa.d(Math.round(size[0])));

        View spacer = new View(host);
        row.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        for (int i = 0; i < hlPalette.length; i++) {
            final int c = hlPalette[i] | 0xFF000000;
            View sw = Modern.swatch(host, c, 26, (a.color | 0xFF000000) == c);
            sw.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    a.color = c;
                    fnoteColor = c;
                    host.pen.savePenSettings();
                    dot.setBackground(Modern.circle(c, host));
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(host.dp(26), host.dp(26));
            lp.setMarginStart(host.dp(7));
            sw.setLayoutParams(lp);
            row.addView(sw);
        }
        card.addView(row);

        /* --- \u0627\u06a9\u0634\u0646\u200c\u0628\u0627\u0631 --- */
        LinearLayout act = new LinearLayout(host);
        act.setOrientation(LinearLayout.HORIZONTAL);
        act.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-1, -2);
        alp.topMargin = host.dp(16);
        act.setLayoutParams(alp);

        act.addView(Modern.filledBtn(host, "\u0630\u062e\u06cc\u0631\u0647", Modern.C_ACCENT, 0xFF0B1220, new View.OnClickListener() {
            @Override public void onClick(View v) {
                String txt = et.getText().toString();
                if (txt.trim().length() == 0) {
                    /* v1.37: یادداشت خالی مجاز نیست — به‌جای ذخیره، حذف می‌شود */
                    host.anns.remove(a.id);
                    host.anns.save();
                    host.annDraw.annInvalidate();
                    try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                    host.toast("یادداشت خالی ذخیره نمی‌شود — حذف شد");
                    return;
                }
                a.note = txt;
                a.noteSize = size[0];
                if (host.anns.byId(a.id) == null) host.anns.add(a);
                host.anns.save();
                host.annDraw.annInvalidate();
                try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                host.toast("\u06cc\u0627\u062f\u062f\u0627\u0634\u062a \u0630\u062e\u06cc\u0631\u0647 \u0634\u062f \u2713");
            }
        }), new LinearLayout.LayoutParams(0, -2, 1f));

        if (a.type == Annotations.T_FNOTE && a.id > 0) {
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(0, -2, 1f);
            mlp.setMarginStart(host.dp(8));
            act.addView(Modern.tonalBtn(host, "\u062c\u0627\u0628\u062c\u0627\u06cc\u06cc", Modern.C_MUTED, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    /* v1.37: جابجایی یادداشت خالی هم مجاز نیست */
                    if (et.getText().toString().trim().length() == 0) {
                        host.anns.remove(a.id);
                        host.anns.save();
                        host.annDraw.annInvalidate();
                        try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                        host.toast("یادداشت خالی ذخیره نمی‌شود — حذف شد");
                        return;
                    }
                    a.note = et.getText().toString();
                    a.noteSize = size[0];
                    if (host.anns.byId(a.id) == null) host.anns.add(a);
                    host.anns.save();
                    host.annDraw.annInvalidate();
                    try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                    pendingFnoteMove = a;
                    host.toast("\u0645\u062d\u0644 \u062c\u062f\u06cc\u062f \u06cc\u0627\u062f\u062f\u0627\u0634\u062a \u0631\u0627 \u0644\u0645\u0633 \u06a9\u0646");
                }
            }), mlp);
        }

        if (canDelete && a.id > 0 && a.note != null && a.note.length() > 0) {
            LinearLayout.LayoutParams dlp2 = new LinearLayout.LayoutParams(0, -2, 1f);
            dlp2.setMarginStart(host.dp(8));
            act.addView(Modern.tonalBtn(host, "\u062d\u0630\u0641", Modern.C_DANGER, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    /* v1.44: حذف یادداشت هم واگرد‌شدنی */
                    ReaderActivity.HistOp dop = new ReaderActivity.HistOp();
                    dop.changes.add(new ReaderActivity.AnnChange(a.id, a.copy(), null));
                    host.pen.histPush(dop);
                    host.anns.remove(a.id);
                    host.anns.save();
                    host.annDraw.annInvalidate();
                    try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                    host.toast("\u06cc\u0627\u062f\u062f\u0627\u0634\u062a \u062d\u0630\u0641 \u0634\u062f (\u0642\u0627\u0628\u0644 \u0648\u0627\u06af\u0631\u062f)");
                }
            }), dlp2);
        }
        card.addView(act);

        dlg[0] = host.trackDialog(Modern.showCardAuto(host, card));
        /* v1.37: بستن ویرایشگر بدون نوشتن → یادداشت خالی خودکار حذف می‌شود */
        try {
            dlg[0].setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override public void onDismiss(DialogInterface di) {
                    try { host.liveDialogs.remove(di); } catch (Throwable ignoredLv) {}
                    try {
                        Annotations.Ann live = host.anns.byId(a.id);
                        if (live != null && (live.note == null || live.note.trim().length() == 0)) {
                            host.anns.remove(a.id);
                            host.anns.save();
                            host.annDraw.annInvalidate();
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable ignoredDl) {
        }
        et.postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    et.setSelection(et.getText().length());
                    android.view.inputmethod.InputMethodManager imm =
                            (android.view.inputmethod.InputMethodManager)
                                    host.getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) imm.showSoftInput(et, 0);
                } catch (Throwable ignored) {
                }
            }
        }, 250L);
    }

    /* جابجایی یادداشت آزاد به نقطهٔ لمس‌شده (می‌تواند صفحهٔ دیگری باشد) */

    void moveFnoteTo(float vx, float vy) {
        Annotations.Ann a = pendingFnoteMove;
        pendingFnoteMove = null;
        if (a == null) return;
        int page = host.pageAt(vx, vy);
        if (page < 0) {
            host.toast("روی صفحه لمس کن");
            return;
        }
        RectF pr = host.pageRect(page);
        if (pr.width() <= 1f) return;
        a.page = page;
        a.x = Math.max(0.02f, Math.min(0.98f, (vx - pr.left) / pr.width()));
        a.y = Math.max(0.02f, Math.min(0.98f, (vy - pr.top) / pr.height()));
        host.anns.save();
        host.annDraw.annInvalidate();
        host.toast("یادداشت جابجا شد ✓");
    }

    /* گذاشتن یادداشت آزاد در نقطهٔ لمس‌شده */

    void placeFnoteAt(float vx, float vy) {
        pendingFnote = false;
        int page = host.pageAt(vx, vy);
        if (page < 0) {
            host.toast("روی صفحه لمس کن");
            return;
        }
        RectF pr = host.pageRect(page);
        if (pr.width() <= 1f) return;
        Annotations.Ann a = new Annotations.Ann();
        a.type = Annotations.T_FNOTE;
        a.page = page;
        a.x = Math.max(0.02f, Math.min(0.98f, (vx - pr.left) / pr.width()));
        a.y = Math.max(0.02f, Math.min(0.98f, (vy - pr.top) / pr.height()));
        a.color = fnoteColor;
        /* v1.37: بدون ذخیرهٔ فوری — یادداشت خالی هرگز روی دیسک نمی‌رود؛
           با «ذخیره» (متن غیرخالی) یا بستن پاک‌کن خودکار ذخیره/حذف می‌شود */
        host.anns.add(a);
        host.annDraw.annInvalidate();
        noteEditor(a, true);
    }

    /* ================== ابزار رسم ================== */

    void clearPageAnnDialog() {
        final int page = host.getCurrentPage1() - 1;
        int n = 0;
        for (int i = 0; i < host.anns.items.size(); i++) {
            if (host.anns.items.get(i).page == page) n++;
        }
        if (n == 0) {
            host.toast("\u0627\u06cc\u0646 \u0635\u0641\u062d\u0647 \u062d\u0627\u0634\u06cc\u0647\u200c\u0646\u0648\u06cc\u0633\u06cc \u0646\u062f\u0627\u0631\u062f");
            return;
        }
        final Dialog[] dlg = new Dialog[1];
        LinearLayout card = Modern.cardContent(host);
        TextView title = new TextView(host);
        title.setText("\u067e\u0627\u06a9\u200c\u06a9\u0631\u062f\u0646 \u062d\u0627\u0634\u06cc\u0647\u200c\u0646\u0648\u06cc\u0633\u06cc\u200c\u0647\u0627\u06cc \u0635\u0641\u062d\u0647\u0654 " + Fa.d(page + 1));
        title.setTextColor(Modern.C_TEXT);
        title.setTextSize(15.5f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        card.addView(title, new LinearLayout.LayoutParams(-1, -2));
        TextView msg = new TextView(host);
        msg.setText(Fa.d(n) + " \u062d\u0627\u0634\u06cc\u0647\u200c\u0646\u0648\u06cc\u0633\u06cc \u0627\u06cc\u0646 \u0635\u0641\u062d\u0647 \u067e\u0627\u06a9 \u0634\u0648\u062f\u061f \u0627\u06cc\u0646 \u0639\u0645\u0644 \u0628\u0631\u06af\u0634\u062a\u0627\u0646\u06cc \u0646\u062f\u0627\u0631\u062f.");
        msg.setTextColor(Modern.C_MUTED);
        msg.setTextSize(13f);
        msg.setLineSpacing(host.dp(3), 1f);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-1, -2);
        mlp.topMargin = host.dp(8);
        msg.setLayoutParams(mlp);
        card.addView(msg);
        LinearLayout act = new LinearLayout(host);
        act.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-1, -2);
        alp.topMargin = host.dp(16);
        act.setLayoutParams(alp);
        act.addView(Modern.tonalBtn(host, "\u0627\u0646\u0635\u0631\u0627\u0641", Modern.C_MUTED, new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { dlg[0].dismiss(); } catch (Throwable ignored) {}
            }
        }), new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams dlp2 = new LinearLayout.LayoutParams(0, -2, 1f);
        dlp2.setMarginStart(host.dp(8));
        act.addView(Modern.filledBtn(host, "\u067e\u0627\u06a9 \u06a9\u0646", Modern.C_DANGER, 0xFFFFFFFF, new View.OnClickListener() {
            @Override public void onClick(View v) {
                for (int i = host.anns.items.size() - 1; i >= 0; i--) {
                    if (host.anns.items.get(i).page == page) host.anns.items.remove(i);
                }
                host.anns.save();
                host.annDraw.annInvalidate();
                try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                host.toast("\u067e\u0627\u06a9 \u0634\u062f");
            }
        }), dlp2);
        card.addView(act);
        dlg[0] = host.trackDialog(Modern.showCardAuto(host, card));
    }


    /* ================== فهرست هایلایت‌ها / یادداشت‌ها ================== */

    /* ================== v1.33: \u0641\u0647\u0631\u0633\u062a \u062d\u0627\u0634\u06cc\u0647\u200c\u0646\u0648\u06cc\u0633\u06cc\u200c\u0647\u0627 \u2014 \u0634\u06cc\u062a \u0645\u062f\u0631\u0646 ==================
       \u0641\u06cc\u0644\u062a\u0631 \u0686\u06cc\u067e\u06cc + \u062c\u0633\u062a\u062c\u0648 + \u0627\u0646\u062a\u062e\u0627\u0628 \u06af\u0631\u0648\u0647\u06cc \u062f\u0631\u0647\u0645\u0648\u0646 \u0645\u062d\u0644.
       mode: 0=\u0647\u0645\u0647 1=\u0647\u0627\u06cc\u0644\u0627\u06cc\u062a\u200c\u0647\u0627 2=\u06cc\u0627\u062f\u062f\u0627\u0634\u062a\u200c\u0647\u0627 3=\u0631\u0633\u0645\u200c\u0647\u0627 */

    void showAnnList(final boolean highlightsOnly) {
        showAnnListSheet(highlightsOnly ? 1 : 2);
    }

    void showAnnListSheet(final int initialMode) {
        if (host.anns.items.isEmpty()) {
            host.toast("\u0647\u0646\u0648\u0632 \u062d\u0627\u0634\u06cc\u0647\u200c\u0646\u0648\u06cc\u0633\u06cc \u062b\u0628\u062a \u0646\u0634\u062f\u0647 \u2014 \u0645\u062a\u0646\u06cc \u0631\u0627 \u0627\u0646\u062a\u062e\u0627\u0628 \u06a9\u0646 \u06cc\u0627 \u0627\u0632 \u062d\u0627\u0644\u062a \u0631\u0633\u0645 \u0627\u0633\u062a\u0641\u0627\u062f\u0647 \u06a9\u0646");
            return;
        }
        final Dialog[] dlg = new Dialog[1];
        final int[] mode = {initialMode};            /* 0 \u0647\u0645\u0647 | 1 \u0645\u0631\u06a9\u0628 | 2 \u06cc\u0627\u062f\u062f\u0627\u0634\u062a | 3 \u0631\u0633\u0645 */
        final boolean[] multi = {false};
        final java.util.HashSet<Long> checked = new java.util.HashSet<>();
        /* \u0627\u0639\u0644\u0627\u0646 \u067e\u06cc\u0634\u0627\u067e \u2014 \u062f\u0633\u062a\u0631\u0633\u06cc \u062f\u06a9\u0645\u0647\u200c\u0647\u0627 \u0642\u0628\u0644 \u0627\u0633\u0627\u06cc\u0646\u062f \u0628\u0647 \u0627\u06cc\u0646 */
        final Runnable[] refresher = new Runnable[1];

        final LinearLayout sheet = Modern.sheetContent(host);

        /* --- \u0633\u0631\u0628\u0631\u06af + \u062f\u06a9\u0645\u0647\u0654 \u0686\u0646\u062f\u062a\u0627\u06cc\u06cc --- */
        LinearLayout head = new LinearLayout(host);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int hp = host.dp(18);
        head.setPadding(hp, host.dp(14), hp, host.dp(4));
        final TextView title = new TextView(host);
        title.setTextColor(Modern.C_TEXT);
        title.setTextSize(16.5f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        final ImageView[] multiRef = new ImageView[1];
        final ImageView multiBtn = Modern.iconBtn(host, R.drawable.ic_ann_multi, Modern.C_MUTED, new View.OnClickListener() {
            @Override public void onClick(View v) {
                multi[0] = !multi[0];
                checked.clear();
                if (multiRef[0] != null) multiRef[0].setColorFilter(multi[0] ? Modern.C_ACCENT : Modern.C_MUTED);
                refresher[0].run();
            }
        });
        multiRef[0] = multiBtn;
        head.addView(multiBtn, new LinearLayout.LayoutParams(host.dp(40), host.dp(40)));
        head.addView(Modern.closeBtn(host, new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { dlg[0].dismiss(); } catch (Throwable ignored) {}
            }
        }));
        sheet.addView(head, new LinearLayout.LayoutParams(-1, -2));

        /* --- \u062c\u0633\u062a\u062c\u0648 --- */
        final EditText search = new EditText(host);
        search.setHint("\u062c\u0633\u062a\u062c\u0648\u2026");
        search.setTextColor(Modern.C_TEXT);
        search.setHintTextColor(0x66E8EEF8);
        search.setSingleLine(true);
        search.setTextSize(13.5f);
        search.setBackground(Modern.fieldBg(host));
        int spx = host.dp(12);
        search.setPadding(spx, host.dp(9), spx, host.dp(9));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.setMargins(hp, host.dp(8), hp, 0);
        sheet.addView(search, slp);

        /* --- \u0686\u06cc\u067e\u200c\u0647\u0627\u06cc \u0641\u06cc\u0644\u062a\u0631 --- */
        android.widget.HorizontalScrollView chipScroll = new android.widget.HorizontalScrollView(host);
        chipScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout.LayoutParams cslp = new LinearLayout.LayoutParams(-1, -2);
        cslp.setMargins(hp, host.dp(9), hp, 0);
        chipScroll.setLayoutParams(cslp);
        LinearLayout chips = new LinearLayout(host);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chipScroll.addView(chips, new android.widget.FrameLayout.LayoutParams(-2, -2));
        sheet.addView(chipScroll);

        final String[] chipLabels = {"\u0647\u0645\u0647", "\u0645\u0631\u06a9\u0628", "\u06cc\u0627\u062f\u062f\u0627\u0634\u062a", "\u0631\u0633\u0645"};
        final TextView[] chipViews = new TextView[4];
        for (int i = 0; i < 4; i++) {
            final int m = i;
            TextView c = Modern.chip(host, chipLabels[i], m == mode[0], new View.OnClickListener() {
                @Override public void onClick(View v) {
                    mode[0] = m;
                    for (int k = 0; k < 4; k++) {
                        chipViews[k].setTextColor(k == m ? 0xFF0B1220 : Modern.C_MUTED);
                        chipViews[k].setBackground(k == m
                                ? Modern.ripple(Modern.pill(Modern.C_ACCENT, host), Modern.C_RIPPLE)
                                : Modern.ripple(Modern.pillStroke(0x14FFFFFF, Modern.C_BORDER, host), Modern.C_RIPPLE));
                    }
                    refresher[0].run();
                }
            });
            chipViews[i] = c;
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
            clp.setMarginEnd(host.dp(7));
            chips.addView(c, clp);
        }

        /* --- \u0641\u0636\u0627\u06cc \u0644\u06cc\u0633\u062a --- */
        final android.widget.ScrollView sview = new android.widget.ScrollView(host);
        sview.setHorizontalScrollBarEnabled(false);
        LinearLayout.LayoutParams svp = new LinearLayout.LayoutParams(-1, -2);
        svp.setMargins(hp, host.dp(10), hp, 0);
        sview.setLayoutParams(svp);
        final LinearLayout listLin = new LinearLayout(host);
        listLin.setOrientation(LinearLayout.VERTICAL);
        sview.addView(listLin, new android.widget.FrameLayout.LayoutParams(-1, -2));
        sheet.addView(sview);

        /* --- \u0646\u0648\u0627\u0631 \u0627\u06a9\u0634\u0646 \u0686\u0646\u062f\u062a\u0627\u06cc\u06cc --- */
        final LinearLayout actionBar = new LinearLayout(host);
        actionBar.setOrientation(LinearLayout.HORIZONTAL);
        actionBar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        actionBar.setVisibility(8);
        actionBar.setPadding(hp, host.dp(10), hp, host.dp(14));
        sheet.addView(actionBar, new LinearLayout.LayoutParams(-1, -2));
        final TextView selCount = new TextView(host);
        selCount.setTextColor(Modern.C_TEXT);
        selCount.setTextSize(13.5f);
        actionBar.addView(selCount, new LinearLayout.LayoutParams(0, -2, 1f));
        actionBar.addView(Modern.tonalBtn(host, "\u0627\u0646\u0635\u0631\u0627\u0641", Modern.C_MUTED, new View.OnClickListener() {
            @Override public void onClick(View v) {
                multi[0] = false;
                checked.clear();
                multiBtn.setColorFilter(Modern.C_MUTED);
                refresher[0].run();
            }
        }));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, -2);
        dlp.setMarginStart(host.dp(8));
        actionBar.addView(Modern.filledBtn(host, "\u062d\u0630\u0641", Modern.C_DANGER, 0xFFFFFFFF, new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (checked.isEmpty()) return;
                /* v1.44: حذف گروهی هم در یک عمل واگرد‌شدنی */
                ReaderActivity.HistOp bop = new ReaderActivity.HistOp();
                for (Long id : new java.util.ArrayList<>(checked)) {
                    Annotations.Ann ba = host.anns.byId(id);
                    if (ba != null) bop.changes.add(new ReaderActivity.AnnChange(id, ba.copy(), null));
                    host.anns.remove(id);
                }
                host.pen.histPush(bop);
                host.anns.save();
                host.annDraw.annInvalidate();
                checked.clear();
                multi[0] = false;
                multiBtn.setColorFilter(Modern.C_MUTED);
                refresher[0].run();
                host.toast("\u062d\u0630\u0641 \u0634\u062f \u2713");
            }
        }), dlp);


        /* ================== \u0644\u06cc\u0633\u062a \u062f\u06cc\u0646\u0627\u0645\u06cc\u06a9 \u2014 \u0628\u0627\u0632\u0633\u0627\u0632\u06cc \u0633\u0627\u062f\u0647 ================== */
        final Runnable updateCount = new Runnable() {
            @Override public void run() {
                if (multi[0]) {
                    actionBar.setVisibility(0);
                    selCount.setText(Fa.d(checked.size()) + " \u0627\u0646\u062a\u062e\u0627\u0628 \u0634\u062f\u0647");
                } else {
                    actionBar.setVisibility(8);
                }
            }
        };

        refresher[0] = new Runnable() {
            @Override public void run() {
                listLin.removeAllViews();

                /* \u0641\u06cc\u0644\u062a\u0631 + \u062c\u0633\u062a\u062c\u0648 + \u0645\u0631\u062a\u0628\u200c\u0633\u0627\u0632\u06cc */
                String q = search.getText().toString().trim().toLowerCase();
                ArrayList<Annotations.Ann> all = new ArrayList<>(host.anns.items);
                java.util.Collections.sort(all, new java.util.Comparator<Annotations.Ann>() {
                    @Override public int compare(Annotations.Ann a, Annotations.Ann b) {
                        return a.page - b.page;
                    }
                });
                final ArrayList<Annotations.Ann> vis = new ArrayList<>();
                for (int i = 0; i < all.size(); i++) {
                    Annotations.Ann a = all.get(i);
                    if (mode[0] == 1 && a.type == Annotations.T_STROKE) continue;
                    if (mode[0] == 2 && a.type != Annotations.T_NOTE && a.type != Annotations.T_FNOTE) continue;
                    if (mode[0] == 3 && a.type != Annotations.T_STROKE) continue;
                    if (q.length() > 0) {
                        String hay = ((a.note == null ? "" : a.note) + " " + (a.text == null ? "" : a.text)).toLowerCase();
                        if (!hay.contains(q)) continue;
                    }
                    vis.add(a);
                }

                title.setText("\u062d\u0627\u0634\u06cc\u0647\u200c\u0646\u0648\u06cc\u0633\u06cc\u200c\u0647\u0627 (" + Fa.d(vis.size()) + ")");

                if (vis.isEmpty()) {
                    TextView empty = new TextView(host);
                    empty.setText("\u0686\u06cc\u0632\u06cc \u067e\u06cc\u062f\u0627 \u0646\u0634\u062f");
                    empty.setTextColor(Modern.C_MUTED);
                    empty.setTextSize(13f);
                    empty.setGravity(android.view.Gravity.CENTER);
                    empty.setPadding(0, host.dp(26), 0, host.dp(26));
                    listLin.addView(empty, new LinearLayout.LayoutParams(-1, -2));
                    updateCount.run();
                    return;
                }

                for (int i = 0; i < vis.size(); i++) {
                    final Annotations.Ann a = vis.get(i);

                    LinearLayout card = new LinearLayout(host);
                    card.setOrientation(LinearLayout.HORIZONTAL);
                    card.setGravity(android.view.Gravity.CENTER_VERTICAL);
                    card.setBackground(Modern.rippleRound(Modern.C_CARD, 15, Modern.C_RIPPLE, host));
                    int cp = host.dp(11);
                    card.setPadding(cp, cp, cp, cp);

                    View accent = new View(host);
                    accent.setBackground(Modern.round((a.color & 0x00FFFFFF) | 0xFF000000, 4, host));
                    LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(host.dp(4), host.dp(40));
                    alp.setMarginEnd(host.dp(11));
                    accent.setLayoutParams(alp);
                    card.addView(accent);

                    LinearLayout txt = new LinearLayout(host);
                    txt.setOrientation(LinearLayout.VERTICAL);
                    LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1f);
                    txt.setLayoutParams(tlp);
                    TextView main = new TextView(host);
                    main.setTextColor(Modern.C_TEXT);
                    main.setTextSize(13.5f);
                    main.setMaxLines(2);
                    main.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                    String body = (a.note != null && a.note.length() > 0) ? a.note
                            : (a.text != null ? a.text : Annotations.typeName(a.type));
                    main.setText(body == null || body.length() == 0 ? Annotations.typeName(a.type) : body);
                    txt.addView(main);
                    TextView sub = new TextView(host);
                    sub.setTextColor(Modern.C_MUTED);
                    sub.setTextSize(11f);
                    sub.setPadding(0, host.dp(3), 0, 0);
                    sub.setText(Annotations.typeName(a.type) + " \u00b7 " + "\u0635\u0641\u062d\u0647\u0654 " + Fa.d(a.page + 1));
                    txt.addView(sub);
                    card.addView(txt);

                    if (multi[0]) {
                        ImageView cb = new ImageView(host);
                        cb.setImageResource(checked.contains(a.id)
                                ? R.drawable.ic_ann_check : R.drawable.ic_ann_multi);
                        cb.setColorFilter(checked.contains(a.id) ? Modern.C_ACCENT : 0x668DA0BC);
                        cb.setBackground(Modern.rippleRound(checked.contains(a.id) ? Modern.C_ACCENT_DIM : 0x14FFFFFF,
                                999, Modern.C_RIPPLE, host));
                        int pp = host.dp(8);
                        cb.setPadding(pp, pp, pp, pp);
                        card.addView(cb, new LinearLayout.LayoutParams(host.dp(36), host.dp(36)));
                    }

                    card.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            if (multi[0]) {
                                if (checked.contains(a.id)) checked.remove(a.id);
                                else checked.add(a.id);
                                refresher[0].run();
                                return;
                            }
                            try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                            host.jumpTo(a.page + 1);
                        }
                    });
                    card.setOnLongClickListener(new View.OnLongClickListener() {
                        @Override public boolean onLongClick(View v) {
                            if (multi[0]) return false;
                            try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                            annActionDialog(a);
                            return true;
                        }
                    });

                    LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(-1, -2);
                    vlp.bottomMargin = host.dp(7);
                    listLin.addView(card, vlp);
                }
                /* \u0633\u0642\u0641 \u0627\u0631\u062a\u0641\u0627\u0639 \u0634\u06cc\u062a: \u0644\u06cc\u0633\u062a \u0644\u0646\u06af \u0627\u0632 \u06f6\u06f0\u0662 \u0635\u0641\u062d\u0647 \u0646\u0631\u0648\u062f */
                int maxH = (int) (host.getResources().getDisplayMetrics().heightPixels * 0.62f);
                sview.getLayoutParams().height = vis.size() > 7 ? maxH : -2;
                updateCount.run();
            }
        };

        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                refresher[0].run();
            }

            @Override public void afterTextChanged(android.text.Editable s) {
            }
        });

        /* \u0641\u0636\u0627\u06cc \u062e\u0627\u0644\u06cc \u067e\u0627\u06cc\u06cc\u0646 \u0634\u06cc\u062a */
        LinearLayout bottomPad = new LinearLayout(host);
        sheet.addView(bottomPad, new LinearLayout.LayoutParams(-1, host.dp(14)));

        refresher[0].run();
        dlg[0] = host.trackDialog(Modern.showSheetAuto(host, sheet));
    }



    /* ================== v1.33: \u0634\u06cc\u062a \u062e\u0631\u0648\u062c\u06cc / \u0648\u0631\u0648\u062f ================== */

    void exportImportDialog() {
        final Dialog[] dlg = new Dialog[1];
        LinearLayout sheet = Modern.sheetContent(host);
        sheet.addView(Modern.sheetHeader(host, "\u067e\u0634\u062a\u06cc\u0628\u0627\u0646 \u0648 \u0627\u0646\u062a\u0642\u0627\u0644",
                "\u0641\u0627\u06cc\u0644 \u0627\u0635\u0644\u06cc PDF \u0647\u0631\u06af\u0632 \u062a\u063a\u06cc\u06cc\u0631 \u0646\u0645\u06cc\u200c\u06a9\u0646\u062f.",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                    }
                }));

        sheet.addView(Modern.actionRow(host, R.drawable.ic_ann_export, Modern.C_ACCENT,
                "\u062e\u0631\u0648\u062c\u06cc \u06a9\u0627\u0645\u0644 (JSON)",
                "\u067e\u0634\u062a\u06cc\u0628\u0627\u0646 \u0648 \u0627\u0646\u062a\u0642\u0627\u0644 \u0628\u0647 \u062f\u0633\u062a\u06af\u0627\u0647 \u062f\u06cc\u06af\u0631",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                        try {
                            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                            i.addCategory(Intent.CATEGORY_OPENABLE);
                            i.setType("application/json");
                            i.putExtra(Intent.EXTRA_TITLE, host.docName + ".annotations.json");
                            host.startActivityForResult(i, 401);
                        } catch (Exception e) {
                            host.toast("\u0628\u0627\u0632 \u06a9\u0631\u062f\u0646 \u0627\u0646\u062a\u062e\u0627\u0628\u06af\u0631 \u0641\u0627\u06cc\u0644 \u0645\u0645\u06a9\u0646 \u0646\u0634\u062f");
                        }
                    }
                }), new LinearLayout.LayoutParams(-1, -2));

        sheet.addView(Modern.actionRow(host, R.drawable.ic_ann_note, Modern.C_ACCENT,
                "\u062e\u0631\u0648\u062c\u06cc \u0645\u062a\u0646 \u06cc\u0627\u062f\u062f\u0627\u0634\u062a\u200c\u0647\u0627",
                "\u0641\u0627\u06cc\u0644 \u0645\u062a\u0646\u06cc \u0633\u0627\u062f\u0647 \u0628\u0631\u0627\u06cc \u0645\u0637\u0627\u0644\u0639\u0647 \u0648 \u0686\u0627\u067e",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                        try {
                            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                            i.addCategory(Intent.CATEGORY_OPENABLE);
                            i.setType("text/plain");
                            i.putExtra(Intent.EXTRA_TITLE, host.docName + ".notes.txt");
                            host.startActivityForResult(i, 402);
                        } catch (Exception e) {
                            host.toast("\u0628\u0627\u0632 \u06a9\u0631\u062f\u0646 \u0627\u0646\u062a\u062e\u0627\u0628\u06af\u0631 \u0641\u0627\u06cc\u0644 \u0645\u0645\u06a9\u0646 \u0646\u0634\u062f");
                        }
                    }
                }), new LinearLayout.LayoutParams(-1, -2));

        sheet.addView(Modern.actionRow(host, R.drawable.ic_ann_import, Modern.C_ACCENT,
                "\u0648\u0631\u0648\u062f \u0627\u0632 \u067e\u0634\u062a\u06cc\u0628\u0627\u0646 (JSON)",
                "\u0645\u0648\u0631\u062f\u0647\u0627\u06cc \u062a\u06a9\u0631\u0627\u0631\u06cc \u0631\u062f \u0645\u06cc\u200c\u0634\u0648\u0646\u062f",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                        try {
                            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                            i.addCategory(Intent.CATEGORY_OPENABLE);
                            i.setType("application/json");
                            host.startActivityForResult(i, 403);
                        } catch (Exception e) {
                            host.toast("\u0628\u0627\u0632 \u06a9\u0631\u062f\u0646 \u0627\u0646\u062a\u062e\u0627\u0628\u06af\u0631 \u0641\u0627\u06cc\u0644 \u0645\u0645\u06a9\u0646 \u0646\u0634\u062f");
                        }
                    }
                }), new LinearLayout.LayoutParams(-1, -2));

        LinearLayout pad = new LinearLayout(host);
        sheet.addView(pad, new LinearLayout.LayoutParams(-1, host.dp(14)));
        dlg[0] = host.trackDialog(Modern.showSheetAuto(host, sheet));
    }

    /* ================== v1.33: \u0647\u0627\u0628 \u062d\u0627\u0634\u06cc\u0647\u200c\u0646\u0648\u06cc\u0633\u06cc ==================
       \u062f\u0633\u062a\u0631\u0633\u06cc \u062a\u06a9\u0627\u0646 \u0627\u0632 \u062f\u06a9\u0645\u0647\u0654 \u0642\u0644\u0645 \u062f\u0631 \u0646\u0648\u0627\u0631 \u0628\u0627\u0644\u0627. */
    /* ================== v1.37: هاب حاشیه‌نویسی — جمع‌وجور ==================
       «حالت رسم و قلم» به نوار بالا رفت (کنار جستجو)؛ «فهرست مرکب‌ها» و
       «رنگ‌بندی هایلایت» طبق خواستهٔ کاربر حذف شدند. */

    void annHubSheet() {
        int nNote = 0;
        for (int ai = 0; ai < host.anns.items.size(); ai++) {
            Annotations.Ann a = host.anns.items.get(ai);
            if (a.type == Annotations.T_NOTE || a.type == Annotations.T_FNOTE) nNote++;
        }
        final Dialog[] dlg = new Dialog[1];
        LinearLayout sheet = Modern.sheetContent(host);
        sheet.addView(Modern.sheetHeader(host, "حاشیه‌نویسی این فایل", null,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                    }
                }));

        sheet.addView(Modern.compactRow(host, R.drawable.ic_ann_note, 0xFF64B5F6,
                "فهرست یادداشت‌ها" + (nNote > 0 ? " (" + Fa.d(nNote) + ")" : ""),
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                        showAnnListSheet(2);
                    }
                }), new LinearLayout.LayoutParams(-1, -2));

        sheet.addView(Modern.compactRow(host, R.drawable.ic_ann_plus, Modern.C_ACCENT,
                "یادداشت آزاد روی این صفحه",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                        pendingFnote = true;
                        host.toast("جای یادداشت را روی صفحه لمس کن");
                    }
                }), new LinearLayout.LayoutParams(-1, -2));

        sheet.addView(Modern.compactRow(host, R.drawable.ic_ann_export, 0xFF81C784,
                "پشتیبان و انتقال",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                        exportImportDialog();
                    }
                }), new LinearLayout.LayoutParams(-1, -2));

        sheet.addView(Modern.compactRow(host, R.drawable.ic_ann_trash, Modern.C_DANGER,
                "پاک‌کردن حاشیه‌نویسی‌های این صفحه",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                        clearPageAnnDialog();
                    }
                }), new LinearLayout.LayoutParams(-1, -2));

        LinearLayout pad = new LinearLayout(host);
        sheet.addView(pad, new LinearLayout.LayoutParams(-1, host.dp(10)));
        int cap = Math.round(host.getResources().getDisplayMetrics().heightPixels * 0.62f);
        dlg[0] = host.trackSheet(Modern.showSheetCapped(host, sheet, cap), new Runnable() {
            @Override public void run() { annHubSheet(); }
        });
    }
}
