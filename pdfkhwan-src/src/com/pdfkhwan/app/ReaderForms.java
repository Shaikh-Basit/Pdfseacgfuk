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
public class ReaderForms {
    final ReaderActivity host;
    ReaderForms(ReaderActivity h) { this.host = h; }

    boolean interactLoaded = false;

    boolean formMode = false;

    View formOverlay = null;

    LinearLayout formBar = null;

    TextView formCounter = null;

    int formFocus = -1;

    java.util.LinkedHashMap<String, String> formValues = new java.util.LinkedHashMap<String, String>();

    void loadInteract() {
        if (interactLoaded) return;
        interactLoaded = true;
        final ReaderForms self = this;
        new Thread(new Runnable() {
            @Override public void run() {
                FormFiller.Interact r = null;
                try {
                    final java.util.concurrent.atomic.AtomicReference<File> slot =
                            new java.util.concurrent.atomic.AtomicReference<File>();
                    host.share.fileForPdfBox(slot);
                    if (slot.get() != null) r = FormFiller.load(slot.get());
                } catch (Throwable t) {
                    try {
                        CrashGuard.append(host, "interact: " + CrashGuard.stackToString(t) + "\n");
                    } catch (Throwable ignored) {
                    }
                }
                final FormFiller.Interact res = r;
                host.ui.post(new Runnable() {
                    @Override public void run() {
                        try {
                            self.interactReady(res);
                        } catch (Throwable ignored) {
                        }
                    }
                });
            }
        }).start();
    }

    void interactReady(FormFiller.Interact r) {
        host.pdfInter = r;
        if (r == null) return;
        if (r.xfaOnly) {
            /* XFA: پیام واضح به‌جای کرش/سکوت */
            try {
                final Dialog[] dlg = new Dialog[1];
                LinearLayout card = Modern.sheetContent(host);
                card.addView(Modern.sheetHeader(host, "فرم XFA",
                        "نیازمند Adobe Acrobat", new View.OnClickListener() {
                            @Override public void onClick(View v) {
                                try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                            }
                        }));
                TextView tx = new TextView(host);
                tx.setText("این فایل «فرم هوشمند XFA» دارد که فقط در Adobe Acrobat/Reader کامل و قابل پرکردن است. متن و صفحات معمولی همین‌جا عالی خوانده می‌شوند.");
                tx.setTextColor(Modern.C_TEXT);
                tx.setTextSize(13f);
                int m = host.dp(18);
                tx.setPadding(m, host.dp(4), m, host.dp(8));
                card.addView(tx, new LinearLayout.LayoutParams(-1, -2));
                TextView ok = Modern.filledBtn(host, "متوجه شدم",
                        Modern.C_ACCENT, 0xFF0B1220, new View.OnClickListener() {
                            @Override public void onClick(View v) {
                                try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                            }
                        });
                LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(-2, -2);
                olp.setMargins(m, 0, m, host.dp(12));
                card.addView(ok, olp);
                dlg[0] = host.trackDialog(Modern.showCardAuto(host, card));
            } catch (Throwable ignored) {
            }
            return;
        }
        if (r.hasForms) {
            formValues = FormFiller.loadValues(host, host.fileKey);
            if (!formValues.isEmpty()) {
                formStart();
                host.toast("فرم ذخیره‌شده بازیابی شد");
            } else {
                promptFormStart(r.fieldCount);
            }
        }
    }

    void promptFormStart(int n) {
        try {
            final Dialog[] dlg = new Dialog[1];
            LinearLayout card = Modern.sheetContent(host);
            card.addView(Modern.sheetHeader(host, "پر کردن فرم",
                    Fa.d(n) + " فیلد پیدا شد", new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                        }
                    }));
            TextView tx = new TextView(host);
            tx.setText("این PDF فرم تعاملی دارد. الان شروع به پر کردنش می‌کنی؟\n(بعداً هم از منوی ⋮ گزینهٔ «پر کردن فرم…» در دسترس است)");
            tx.setTextColor(Modern.C_TEXT);
            tx.setTextSize(13f);
            int m = host.dp(18);
            tx.setPadding(m, host.dp(4), m, host.dp(8));
            card.addView(tx, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout rowB = new LinearLayout(host);
            rowB.setOrientation(LinearLayout.HORIZONTAL);
            rowB.setGravity(android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL);
            TextView no = Modern.tonalBtn(host, "فعلاً نه", Modern.C_MUTED, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                }
            });
            rowB.addView(no, new LinearLayout.LayoutParams(-2, -2));
            TextView go = Modern.filledBtn(host, "شروع پر کردن", Modern.C_ACCENT, 0xFF0B1220,
                    new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                            formStart();
                        }
                    });
            LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-2, -2);
            glp.setMargins(host.dp(10), 0, 0, 0);
            rowB.addView(go, glp);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
            rlp.setMargins(m, 0, m, host.dp(12));
            card.addView(rowB, rlp);
            dlg[0] = host.trackDialog(Modern.showCardAuto(host, card));
        } catch (Throwable ignored) {
        }
    }

    void formStart() {
        if (host.pdfInter == null || !host.pdfInter.hasForms) return;
        formMode = true;
        try {
            if (formOverlay == null) {
                ReaderActivity.FormOverlay fo = host.new FormOverlay(host);
                formOverlay = fo;
                fo.setVisibility(8);
                ((ViewGroup) host.findViewById(R.id.pdfView).getParent())
                        .addView(fo, 1, new ViewGroup.LayoutParams(-1, -1));
            }
            formOverlay.setVisibility(0);
            formOverlay.invalidate();
        } catch (Throwable ignored) {
        }
        buildFormBar();
        if (formBar != null) formBar.setVisibility(0);
        if (formFocus < 0 || formFocus >= host.pdfInter.fields.size()) {
            formFocus = formFirstEmpty();
        }
        updateFormBar();
        host.startGeomWatch();
    }

    void formStop() {
        formMode = false;
        try {
            if (formOverlay != null) formOverlay.setVisibility(8);
        } catch (Throwable ignored) {
        }
        try {
            if (formBar != null) formBar.setVisibility(8);
        } catch (Throwable ignored) {
        }
    }

    void buildFormBar() {
        if (formBar != null) return;
        LinearLayout bar = new LinearLayout(host);
        formBar = bar;
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        bar.setBackgroundResource(R.drawable.bg_bar);
        bar.setElevation(host.dp(6));
        int pad = host.dp(8);
        bar.setPadding(pad, host.dp(6), pad, host.dp(6));

        bar.addView(Modern.iconBtn(host, R.drawable.ic_close, Modern.C_MUTED, new View.OnClickListener() {
            @Override public void onClick(View v) { formStop(); }
        }), new LinearLayout.LayoutParams(-2, -2));

        TextView cnt = new TextView(host);
        formCounter = cnt;
        cnt.setTextColor(Modern.C_TEXT);
        cnt.setTextSize(12f);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, -2, 1f);
        clp.setMargins(host.dp(8), 0, host.dp(8), 0);
        cnt.setLayoutParams(clp);
        bar.addView(cnt);

        bar.addView(Modern.iconBtn(host, R.drawable.ic_back, Modern.C_TEXT, new View.OnClickListener() {
            @Override public void onClick(View v) { formFieldNext(-1); }
        }), new LinearLayout.LayoutParams(-2, -2));

        ImageView next = Modern.iconBtn(host, R.drawable.ic_back, Modern.C_TEXT, new View.OnClickListener() {
            @Override public void onClick(View v) { formFieldNext(1); }
        });
        next.setScaleX(-1f);
        bar.addView(next, new LinearLayout.LayoutParams(-2, -2));

        bar.addView(Modern.iconBtn(host, R.drawable.ic_ann_trash, Modern.C_DANGER, new View.OnClickListener() {
            @Override public void onClick(View v) { formReset(); }
        }), new LinearLayout.LayoutParams(-2, -2));

        TextView save = Modern.filledBtn(host, "ذخیره در PDF", Modern.C_ACCENT, 0xFF0B1220,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { formSaveDialog(); }
                });
        bar.addView(save, new LinearLayout.LayoutParams(-2, -2));

        try {
            android.widget.FrameLayout.LayoutParams flp = new android.widget.FrameLayout.LayoutParams(
                    -2, -2, android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL);
            flp.bottomMargin = host.dp(16);
            flp.leftMargin = host.dp(14);
            flp.rightMargin = host.dp(14);
            ((ViewGroup) host.findViewById(R.id.pdfView).getParent()).addView(bar, flp);
        } catch (Throwable ignored) {
        }
        bar.setVisibility(8);
    }

    int formFirstEmpty() {
        if (host.pdfInter == null) return -1;
        for (int i = 0; i < host.pdfInter.fields.size(); i++) {
            FormFiller.Field f = host.pdfInter.fields.get(i);
            String v = formValues.containsKey(f.name) ? formValues.get(f.name) : f.value;
            if (v == null || v.length() == 0) return i;
        }
        return 0;
    }

    void updateFormBar() {
        try {
            if (formCounter == null || host.pdfInter == null) return;
            int n = host.pdfInter.fields.size();
            int cur = formFocus < 0 ? 0 : (formFocus >= n ? n - 1 : formFocus);
            java.util.HashSet<String> names = new java.util.HashSet<>();
            int filled = 0;
            for (int i = 0; i < n; i++) {
                FormFiller.Field f = host.pdfInter.fields.get(i);
                if (names.contains(f.name)) continue;
                names.add(f.name);
                String v = formValues.containsKey(f.name) ? formValues.get(f.name) : f.value;
                if (v != null && v.length() > 0) filled++;
            }
            formCounter.setText("فیلد " + Fa.d(n == 0 ? 0 : cur + 1) + " از " + Fa.d(n)
                    + " • " + Fa.d(filled) + " پرشده");
        } catch (Throwable ignored) {
        }
    }

    void formFieldNext(int dir) {
        if (host.pdfInter == null || host.pdfInter.fields.isEmpty()) return;
        int n = host.pdfInter.fields.size();
        if (dir >= 0) {
            formFocus = formFocus < 0 ? 0 : (formFocus + 1) % n;
        } else {
            formFocus = formFocus < 0 ? n - 1 : ((formFocus - 1) % n + n) % n;
        }
        FormFiller.Field f = host.pdfInter.fields.get(formFocus);
        try {
            if (f.page >= 0 && f.page < host.pageCount && host.pdfView.getCurrentPage() != f.page) {
                host.jumpTo(f.page + 1, true);
            }
        } catch (Throwable ignored) {
        }
        updateFormBar();
        if (formOverlay != null) formOverlay.invalidate();
    }

    String displayFormValue(FormFiller.Field f) {
        String v = formValues.containsKey(f.name) ? formValues.get(f.name) : f.value;
        return v == null ? "" : v;
    }

    /* لمس روی فیلد فرم؟ — کوچک‌ترین فیلدِ زیر انگشت برنده است */

    boolean tapForm(float vx, float vy) {
        if (!formMode || host.pdfInter == null || host.pdfInter.fields.isEmpty()) return false;
        int page = host.pageAt(vx, vy);
        if (page < 0) return false;
        RectF pr = host.pageRect(page);
        if (pr.width() <= 1f) return false;
        int hit = -1;
        float bestArea = Float.MAX_VALUE;
        float ex = host.dp(7);
        for (int i = 0; i < host.pdfInter.fields.size(); i++) {
            FormFiller.Field f = host.pdfInter.fields.get(i);
            if (f.page != page) continue;
            float l = pr.left + f.fx0 * pr.width();
            float t = pr.top + f.fy0 * pr.height();
            float r2 = pr.left + f.fx1 * pr.width();
            float b = pr.top + f.fy1 * pr.height();
            if (vx >= l - ex && vx <= r2 + ex && vy >= t - ex && vy <= b + ex) {
                float ar = (r2 - l) * (b - t);
                if (ar < bestArea) {
                    bestArea = ar;
                    hit = i;
                }
            }
        }
        if (hit < 0) return false;
        formFocus = hit;
        updateFormBar();
        if (formOverlay != null) formOverlay.invalidate();
        final FormFiller.Field f = host.pdfInter.fields.get(hit);
        if (f.readonly) {
            host.toast("این فیلد فقط‌خواندنی است");
            return true;
        }
        if (f.type == FormFiller.F_CHECK) {
            formSetValue(f, "1".equals(displayFormValue(f)) ? "" : "1");
        } else if (f.type == FormFiller.F_TEXT) {
            formTextDialog(f);
        } else if (f.type == FormFiller.F_COMBO || f.type == FormFiller.F_LIST
                || f.type == FormFiller.F_RADIO) {
            formChoiceDialog(f);
        } else if (f.type == FormFiller.F_SIGN) {
            formSignDialog(f);
        }
        return true;
    }

    void formSetValue(FormFiller.Field f, String v) {
        if (f == null || f.name == null) return;
        formValues.put(f.name, v == null ? "" : v);
        FormFiller.saveValues(host, host.fileKey, formValues);
        if (formOverlay != null) formOverlay.invalidate();
        updateFormBar();
    }

    /* فیلد متنی — کیبورد با راست‌چین خودکار */

    void formTextDialog(final FormFiller.Field f) {
        final Dialog[] dlg = new Dialog[1];
        LinearLayout card = Modern.sheetContent(host);
        card.addView(Modern.sheetHeader(host,
                f.label != null && f.label.length() > 0 ? f.label : "فیلد متنی",
                f.maxlen > 0 ? "حداکثر " + Fa.d(f.maxlen) + " نویسه" : "مقدار این فیلد",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                    }
                }));
        final EditText et = new EditText(host);
        et.setBackground(Modern.fieldBg(host));
        int p = host.dp(12);
        et.setPadding(p, p, p, p);
        et.setTextColor(Modern.C_TEXT);
        et.setTextSize(15f);
        et.setHint("تایپ کن…");
        et.setHintTextColor(Modern.C_MUTED);
        et.setText(displayFormValue(f));
        et.setGravity(f.rtl
                ? android.view.Gravity.RIGHT | android.view.Gravity.CENTER_VERTICAL
                : android.view.Gravity.LEFT | android.view.Gravity.CENTER_VERTICAL);
        if (f.maxlen > 0) {
            et.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(f.maxlen)});
        }
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(-1, -2);
        int m = host.dp(18);
        elp.setMargins(m, host.dp(10), m, host.dp(6));
        card.addView(et, elp);

        LinearLayout rowB = new LinearLayout(host);
        rowB.setOrientation(LinearLayout.HORIZONTAL);
        rowB.setGravity(android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL);
        TextView clr = Modern.tonalBtn(host, "پاک کردن", Modern.C_DANGER, new View.OnClickListener() {
            @Override public void onClick(View v) {
                et.setText("");
            }
        });
        rowB.addView(clr, new LinearLayout.LayoutParams(-2, -2));
        TextView ok = Modern.filledBtn(host, "ثبت", Modern.C_ACCENT, 0xFF0B1220,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                        formSetValue(f, et.getText().toString());
                    }
                });
        LinearLayout.LayoutParams olp2 = new LinearLayout.LayoutParams(-2, -2);
        olp2.setMargins(host.dp(10), 0, 0, 0);
        rowB.addView(ok, olp2);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.setMargins(m, 0, m, host.dp(12));
        card.addView(rowB, rlp);
        dlg[0] = host.trackDialog(Modern.showCardAuto(host, card));
    }

    /* رادیو / دراپ‌داون / لیست — انتخاب از گزینه‌ها */

    void formChoiceDialog(final FormFiller.Field f) {
        if (f.options == null || f.options.isEmpty()) {
            host.toast("گزینه‌ای برای این فیلد یافت نشد");
            return;
        }
        final Dialog[] dlg = new Dialog[1];
        LinearLayout sheet = Modern.sheetContent(host);
        sheet.addView(Modern.sheetHeader(host,
                f.label != null && f.label.length() > 0 ? f.label : "انتخاب گزینه",
                f.type == FormFiller.F_RADIO ? "یک گزینه از گروه" : "گزینهٔ این فیلد",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                    }
                }));
        String cur = displayFormValue(f);
        for (int i = 0; i < f.options.size(); i++) {
            final String opt = f.options.get(i);
            boolean sel = opt.equals(cur);
            sheet.addView(Modern.compactRow(host,
                    sel ? R.drawable.ic_ann_check : R.drawable.ic_ann_sq,
                    sel ? 0xFF2DD4BF : Modern.C_MUTED, opt,
                    new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                            formSetValue(f, opt);
                        }
                    }), new LinearLayout.LayoutParams(-1, -2));
        }
        sheet.addView(Modern.compactRow(host, R.drawable.ic_ann_close, Modern.C_DANGER,
                "(بدون انتخاب)", new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                        formSetValue(f, "");
                    }
                }), new LinearLayout.LayoutParams(-1, -2));
        int cap = Math.round(host.getResources().getDisplayMetrics().heightPixels * 0.62f);
        dlg[0] = host.trackDialog(Modern.showSheetCapped(host, sheet, cap));
    }

    /* فیلد امضا — پد رسم دستی؛ نتیجه به‌صورت جوهر روی محل فیلد می‌نشیند
       (با واگرد سازگار است و در خروجی پخته می‌شود) */

    void formSignDialog(final FormFiller.Field f) {
        final Dialog[] dlg = new Dialog[1];
        LinearLayout card = new LinearLayout(host);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_panel);
        int m = host.dp(16);
        card.setPadding(m, m, m, m);

        TextView title = new TextView(host);
        title.setText("امضای دستی");
        title.setTextColor(Modern.C_TEXT);
        title.setTextSize(16f);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        card.addView(title, new LinearLayout.LayoutParams(-1, -2));
        TextView sub = new TextView(host);
        sub.setText("با انگشت در کادر زیر امضا کن — روی محل فیلد در صفحه می‌نشیند");
        sub.setTextColor(Modern.C_MUTED);
        sub.setTextSize(12f);
        card.addView(sub, new LinearLayout.LayoutParams(-1, -2));

        final ReaderActivity.SignPad pad = host.new SignPad(host);
        pad.setBackground(Modern.roundStroke(0xFFFCFAF2, host.dp(6), Modern.C_BORDER, 1, host));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, host.dp(170));
        plp.setMargins(0, host.dp(10), 0, host.dp(10));
        card.addView(pad, plp);

        LinearLayout rowB = new LinearLayout(host);
        rowB.setOrientation(LinearLayout.HORIZONTAL);
        rowB.setGravity(android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL);
        TextView clr = Modern.tonalBtn(host, "پاک کردن", Modern.C_MUTED, new View.OnClickListener() {
            @Override public void onClick(View v) { pad.clearPad(); }
        });
        rowB.addView(clr, new LinearLayout.LayoutParams(-2, -2));
        TextView ok = Modern.filledBtn(host, "ثبت امضا", Modern.C_ACCENT, 0xFF0B1220,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (!pad.hasInk()) {
                            host.toast("اول امضا را بکش");
                            return;
                        }
                        try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                        placeSignature(f, pad.ink(), pad.getWidth(), pad.getHeight());
                    }
                });
        LinearLayout.LayoutParams olp2 = new LinearLayout.LayoutParams(-2, -2);
        olp2.setMargins(host.dp(10), 0, 0, 0);
        rowB.addView(ok, olp2);
        card.addView(rowB, new LinearLayout.LayoutParams(-1, -2));

        dlg[0] = host.trackDialog(Modern.showCardAuto(host, card));
    }

    /* تبدیل خطوط پد به حاشیه‌نویسی جوهری روی مستطیل فیلد */

    void placeSignature(FormFiller.Field f, java.util.ArrayList<float[]> strokes, float padW, float padH) {
        try {
            RectF pr = host.pageRect(f.page);
            if (pr.width() <= 1f) {
                host.toast("صفحه آماده نیست — دوباره تلاش کن");
                return;
            }
            float fw = f.fx1 - f.fx0;
            float fh = f.fy1 - f.fy0;
            if (fw <= 0f || fh <= 0f) {
                host.toast("محل امضا معتبر نیست");
                return;
            }
            float inset = 0.06f;
            ReaderActivity.HistOp op = new ReaderActivity.HistOp();
            for (float[] s : strokes) {
                if (s == null || s.length < 4) continue;
                Annotations.Ann a = new Annotations.Ann();
                a.type = Annotations.T_STROKE;
                a.page = f.page;
                a.color = 0xFF16213E;
                a.strokeW = 2.4f;
                int cnt = s.length / 2;
                a.px = new float[cnt];
                a.py = new float[cnt];
                for (int i = 0; i < cnt; i++) {
                    float nx = padW > 1f ? s[i * 2] / padW : 0.5f;
                    float ny = padH > 1f ? s[i * 2 + 1] / padH : 0.5f;
                    a.px[i] = f.fx0 + inset + nx * (fw - 2 * inset);
                    a.py[i] = f.fy0 + inset + ny * (fh - 2 * inset);
                }
                if (host.anns.add(a) != null) {
                    op.changes.add(new ReaderActivity.AnnChange(a.id, null, a.copy()));
                }
            }
            if (!op.changes.isEmpty()) {
                host.anns.save();
                host.pen.histPush(op);
            }
            host.annDraw.annInvalidate();
            formSetValue(f, "SIGNED");
            host.toast("امضا ثبت شد — با «ذخیره در PDF» داخل خروجی می‌رود");
        } catch (Throwable t) {
            host.toast("ثبت امضا ممکن نشد");
        }
    }

    void formReset() {
        final Dialog[] dlg = new Dialog[1];
        LinearLayout card = Modern.sheetContent(host);
        card.addView(Modern.sheetHeader(host, "ریست فرم",
                "همهٔ مقادیر پاک می‌شود", new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                    }
                }));
        TextView tx = new TextView(host);
        tx.setText("مقادیر همهٔ فیلدهای این فرم پاک شود؟\n(فایل اصلی تغییر نمی‌کند — فقط مقادیر ذخیره‌شده در اپ)");
        tx.setTextColor(Modern.C_TEXT);
        tx.setTextSize(13f);
        int m = host.dp(18);
        tx.setPadding(m, host.dp(4), m, host.dp(8));
        card.addView(tx, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout rowB = new LinearLayout(host);
        rowB.setOrientation(LinearLayout.HORIZONTAL);
        rowB.setGravity(android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL);
        TextView no = Modern.tonalBtn(host, "انصراف", Modern.C_MUTED, new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { dlg[0].dismiss(); } catch (Throwable ignored) {}
            }
        });
        rowB.addView(no, new LinearLayout.LayoutParams(-2, -2));
        TextView yes = Modern.filledBtn(host, "بله، پاک کن", Modern.C_DANGER, 0xFFFFFFFF,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                        formValues.clear();
                        FormFiller.clearValues(host, host.fileKey);
                        formFocus = formFirstEmpty();
                        updateFormBar();
                        if (formOverlay != null) formOverlay.invalidate();
                        host.toast("فرم ریست شد");
                    }
                });
        LinearLayout.LayoutParams ylp = new LinearLayout.LayoutParams(-2, -2);
        ylp.setMargins(host.dp(10), 0, 0, 0);
        rowB.addView(yes, ylp);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.setMargins(m, 0, m, host.dp(12));
        card.addView(rowB, rlp);
        dlg[0] = host.trackDialog(Modern.showCardAuto(host, card));
    }

    /* ذخیرهٔ فرم داخل PDF جدید (cache) — قابل ویرایش یا پخته‌شده */

    void formSaveDialog() {
        if (host.pdfInter == null || !host.pdfInter.hasForms) {
            host.toast("فرمی در این فایل نیست");
            return;
        }
        boolean any = false;
        for (java.util.Map.Entry<String, String> e : formValues.entrySet()) {
            if (e.getValue() != null && e.getValue().length() > 0 && !"SIGNED".equals(e.getValue())) {
                any = true;
                break;
            }
        }
        if (!any) {
            host.toast("اول فیلدها را پر کن");
            return;
        }
        final Dialog[] dlg = new Dialog[1];
        LinearLayout sheet = Modern.sheetContent(host);
        sheet.addView(Modern.sheetHeader(host, "ذخیرهٔ فرم در PDF",
                "خروجی تازه؛ فایل اصلی دست نمی‌خورد", new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                    }
                }));
        sheet.addView(Modern.compactRow(host, R.drawable.ic_ann_export, 0xFF64B5F6,
                "قابل ویرایش — فرم در Adobe هم قابل تغییر می‌ماند",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                        formSaveRun(false);
                    }
                }), new LinearLayout.LayoutParams(-1, -2));
        sheet.addView(Modern.compactRow(host, R.drawable.ic_ann_palette, 0xFFFFB74D,
                "پخته‌شده (Flat) — متن دائمی، فرم حذف می‌شود",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                        formSaveRun(true);
                    }
                }), new LinearLayout.LayoutParams(-1, -2));
        TextView hint = new TextView(host);
        hint.setText("حاشیه‌نویسی‌ها و امضاهای دستی هم اگر باشند، داخل خروجی پخته می‌شوند.");
        hint.setTextColor(Modern.C_MUTED);
        hint.setTextSize(11f);
        int hp = host.dp(18);
        hint.setPadding(hp, host.dp(6), hp, 0);
        sheet.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        dlg[0] = host.trackDialog(Modern.showSheetAuto(host, sheet));
    }

    void formSaveRun(final boolean flatten) {
        final Dialog prog = host.busyCard("در حال ذخیرهٔ فرم…");
        final Runnable build = new Runnable() {
            @Override public void run() {
                host.io.execute(new Runnable() {
                    @Override public void run() {
                        try {
                            java.util.concurrent.atomic.AtomicReference<File> slot =
                                    new java.util.concurrent.atomic.AtomicReference<File>();
                            host.share.fileForPdfBox(slot);
                            FormFiller.SaveResult sr = FormFiller.save(slot.get(), formValues, flatten,
                                    new File(host.getCacheDir(), "share"));
                            File f1 = sr.file;
                            File out = f1;
                            if (host.anns != null && !host.anns.items.isEmpty()) {
                                final float ptPerDp = host.share.annPtPerDp();
                                out = AnnPdfExport.buildAnnotated(host, f1,
                                        AnnPdfExport.MODE_FLAT, new ArrayList<>(host.anns.items),
                                        host.share.annWordGeom(), ptPerDp,
                                        new AnnPdfExport.Progress() {
                                            @Override public void progress(final int done, final int total) {
                                                host.ui.post(new Runnable() {
                                                    @Override public void run() {
                                                        host.busyCardText(prog, "حاشیه‌نویسی: " + Fa.d(done)
                                                                + " از " + Fa.d(total));
                                                    }
                                                });
                                            }
                                        });
                            }
                            final File fout = out;
                            final boolean flatOk = !flatten || sr.flattened;
                            host.ui.post(new Runnable() {
                                @Override public void run() {
                                    try { prog.dismiss(); } catch (Throwable ignored) {}
                                    String title = !flatten
                                            ? "فرم پرشده (قابل ویرایش)"
                                            : (flatOk ? "فرم پرشده (پخته‌شده)" : "فرم پرشده (مقادیر ذخیره شد)");
                                    host.share.shareTempPdf(fout, title);
                                }
                            });
                        } catch (final Exception e) {
                            host.ui.post(new Runnable() {
                                @Override public void run() {
                                    try { prog.dismiss(); } catch (Throwable ignored) {}
                                    host.toast("ذخیرهٔ فرم ممکن نشد: " + e.getMessage());
                                }
                            });
                        }
                    }
                });
            }
        };
        if (host.anns != null && !host.anns.items.isEmpty()) {
            host.share.annWarmStart(build);
        } else {
            build.run();
        }
    }

    /* لایهٔ رسم کادر/مقدار فیلدها — شفاف، بدون لمس‌گیری */
}
