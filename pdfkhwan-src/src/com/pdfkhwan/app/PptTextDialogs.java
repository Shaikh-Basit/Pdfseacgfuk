package com.pdfkhwan.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.MetricAffectingSpan;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.VideoView;
import android.widget.Toast;
import com.pdfkhwan.ppt.PptDoc;
import com.pdfkhwan.ppt.PptxReader;
import com.pdfkhwan.ppt.PptxWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/* v1.51: خوشهٔ جدا‌شده از PptEditorActivity — تقسیم ساختاری */
public class PptTextDialogs {
    final PptEditorActivity host;
    PptTextDialogs(PptEditorActivity h) { this.host = h; }

    TextView inlinePill(String label, final Runnable r) {
        TextView t = new TextView(host);
        t.setText(label);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(13f);
        t.setGravity(Gravity.CENTER);
        t.setPadding(host.dp(14), host.dp(8), host.dp(14), host.dp(8));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(host.dp(18));
        g.setColor(0xFFB7472A);
        t.setBackground(g);
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { r.run(); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = host.dp(6);
        return t;
    }

    void pickColor(String title, final PptEditorActivity.ColorPick cb) {
        final int[] presets = {
                0xFF000000, 0xFF404040, 0xFF8A8886, 0xFFFFFFFF, 0xFFB7472A, 0xFFFF8A3D,
                0xFFFFD700, 0xFF6CC24A, 0xFF2E9E63, 0xFF17A2A2, 0xFF2F7DD1, 0xFF2E75B6,
                0xFF1F3864, 0xFF8064A2, 0xFF8E5BD9, 0xFFEC407A, 0xFFAD1457, 0xFF880E4F};
        LinearLayout grid = new LinearLayout(host);
        grid.setOrientation(LinearLayout.VERTICAL);
        int pad = host.dp(10);
        grid.setPadding(pad, pad, pad, pad);
        for (int row = 0; row < 6; row++) {
            LinearLayout line = new LinearLayout(host);
            line.setOrientation(LinearLayout.HORIZONTAL);
            for (int col = 0; col < 3; col++) {
                final int c = presets[row * 3 + col];
                View sw = new View(host);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, host.dp(38), 1f);
                lp.setMargins(host.dp(4), host.dp(4), host.dp(4), host.dp(4));
                sw.setLayoutParams(lp);
                android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
                gd.setColor(c);
                gd.setStroke(host.dp(1), 0xFFAAAAAA);
                sw.setBackground(gd);
                sw.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        cb.on(c);
                        dlgDismiss();
                    }
                });
                line.addView(sw);
            }
            grid.addView(line);
        }
        ScrollView sc = new ScrollView(host);
        sc.addView(grid);
        host.dlg = new AlertDialog.Builder(host).setTitle(title).setView(sc)
                .setNegativeButton("انصراف", (DialogInterface.OnClickListener) null).show();
    }

    void pickTextColor() {
        pickColor("رنگ متن", new PptEditorActivity.ColorPick() {
            @Override public void on(int c) { host.applyTextStyle(5, 0, c, null); }
        });
    }

    void pickFillColor() {
        pickColor("رنگ پرکردن", new PptEditorActivity.ColorPick() {
            @Override public void on(int c) { mutateSelFill(c); }
        });
    }

    void mutateSelFill(final int c) {
        host.mutateSel(new Runnable() {
            @Override public void run() { host.sel.fill = PptDoc.Fill.solid(c); }
        });
    }

    void pickLineColor() {
        pickColor("رنگ خط", new PptEditorActivity.ColorPick() {
            @Override public void on(int c) { mutateSelLine(c); }
        });
    }

    void mutateSelLine(final int c) {
        host.mutateSel(new Runnable() {
            @Override public void run() {
                host.sel.line.enabled = true;
                host.sel.line.color = c;
            }
        });
    }

    void pickBgColor() {
        pickColor("رنگ پس‌زمینه", new PptEditorActivity.ColorPick() {
            @Override public void on(int c) {
                host.pushUndo();
                host.slide().background = PptDoc.Fill.solid(c);
                host.refreshAll();
            }
        });
    }

    void pickGradient() {
        pickColor("گرادیان — رنگ دوم", new PptEditorActivity.ColorPick() {
            @Override public void on(int c) {
                host.pushUndo();
                int top = host.slide().background.type != PptDoc.Fill.NONE ? host.slide().background.color : 0xFFFFFFFF;
                host.slide().background = PptDoc.Fill.gradient(top, c, false);
                host.refreshAll();
            }
        });
    }

    void dlgDismiss() {
        if (host.dlg != null) {
            try {
                host.dlg.dismiss();
            } catch (Throwable ignored) {
            }
            host.dlg = null;
        }
    }

    void lineDialog() {
        final String[] items = {"۰٫۵", "۱", "۲", "۳", "۵", "۸"};
        new AlertDialog.Builder(host).setTitle("ضخامت خط")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        final float pt;
                        try {
                            pt = Float.parseFloat(items[w].replace("٫", "."));
                        } catch (Exception e) {
                            return;
                        }
                        host.mutateSel(new Runnable() {
                            @Override public void run() {
                                host.sel.line.enabled = true;
                                host.sel.line.widthPt = pt;
                            }
                        });
                    }
                }).show();
    }

    void dashDialog() {
        final String[] items = {"ساده", "خط‌چین", "نقطه‌چین", "خط‌نقطه"};
        new AlertDialog.Builder(host).setTitle("سبک خط")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        final int dash = w;
                        host.mutateSel(new Runnable() {
                            @Override public void run() {
                                if (!host.sel.line.enabled) host.sel.line.enabled = true;
                                host.sel.line.dash = dash;
                            }
                        });
                    }
                }).show();
    }

    void fontDialog() {
        final String[] names = {
                "پیش‌فرض", "Vazirmatn", "Estedad", "Parastoo", "BalooB",
                "Noto Naskh Arabic", "Noto Sans Arabic", "Roboto", "Inter",
                "Lato", "Open Sans", "Montserrat", "Poppins", "Oswald",
                "Playfair Display", "Source Sans", "Raleway"};
        new AlertDialog.Builder(host).setTitle("فونت")
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        host.applyTextStyle(6, 0, 0, w == 0 ? "" : names[w]);
                    }
                }).show();
    }

    /* =====================================================================
       v1.23: ابزارهای جدید ادیتور — بولت/فاصله‌گذاری/تراز/انتقال/اندازهٔ
       اسلاید/انیمیشن/سایه‌ها/برش تصویر/جست‌وجو و جایگزینی
       ===================================================================== */

    /* بولت و شماره‌گذاری — روی همهٔ بندهای شکل انتخابی اعمال می‌شود */

    void bulletDialog() {
        if (host.sel == null) {
            Toast.makeText(host, "اول یک شکل متنی انتخاب کن", Toast.LENGTH_SHORT).show();
            return;
        }
        List<PptDoc.Para> ps = PptEditorActivity.parasOf(host.sel);
        if (ps == null || ps.isEmpty()) {
            Toast.makeText(host, "یک شکل متنی انتخاب کن", Toast.LENGTH_SHORT).show();
            return;
        }
        /* v1.24: numbering formats live in their own sub-dialog
           (11 real a:buAutoNum types, round-tripped through the writer) */
        final String[] items = {"بدون بولت", "• نقطه", "◦ دایره توخالی", "▪ مربع",
                "- خط تیره", "✔ تیک", "۱. شماره‌گذاری…"};
        final String[] chars = {null, "•", "◦", "▪", "-", "✔", null};
        new AlertDialog.Builder(host).setTitle("بولت و شماره‌گذاری")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        final int choice = w;
                        if (choice == 6) {
                            dlgDismiss();
                            numberingDialog();
                            return;
                        }
                        host.mutateSel(new Runnable() {
                            @Override
                            public void run() {
                                for (PptDoc.Para p : PptEditorActivity.parasOf(host.sel)) {
                                    if (choice == 0) {
                                        p.bullet = false;
                                        p.buChar = null;
                                        p.buAutoNum = false;
                                        /* v1.32 FIX: removing the bullet must also clear
                                           the hanging indent we set when applying it,
                                           otherwise the text stays pushed in */
                                        if (p.marL == PptEditorActivity.SlideRenderer.DEF_BULLET_MARL && p.indent == PptEditorActivity.SlideRenderer.DEF_BULLET_INDENT) {
                                            p.marL = 0;
                                            p.indent = 0;
                                        }
                                    } else {
                                        p.bullet = true;
                                        p.buAutoNum = false;
                                        p.buChar = chars[choice];
                                        /* v1.32 FIX (the "glued/overlapping bullet" bug):
                                           applying a bullet with marL=0/indent=0 left
                                           the marker ON TOP of the text because there
                                           was no hanging indent at all. Give the para
                                           PowerPoint's default 0.38 cm hanging indent
                                           unless the file set explicit values. */
                                        if (p.marL == 0 && p.indent == 0) {
                                            p.marL = PptEditorActivity.SlideRenderer.DEF_BULLET_MARL;
                                            p.indent = PptEditorActivity.SlideRenderer.DEF_BULLET_INDENT;
                                        }
                                    }
                                    p.hasBullet = true;
                                }
                            }
                        });
                    }
                }).show();
    }

    /* v1.24: قالب‌های واقعی شماره‌گذاری — همان type هایی که
       PowerPoint در a:buAutoNum می‌نویسد و می‌خواند */

    void numberingDialog() {
        if (host.sel == null) {
            Toast.makeText(host, "اول یک شکل متنی انتخاب کن", Toast.LENGTH_SHORT).show();
            return;
        }
        List<PptDoc.Para> ps = PptEditorActivity.parasOf(host.sel);
        if (ps == null || ps.isEmpty()) {
            Toast.makeText(host, "یک شکل متنی انتخاب کن", Toast.LENGTH_SHORT).show();
            return;
        }
        final int[] types = {
                PptDoc.Para.NUM_ARABIC_PERIOD, PptDoc.Para.NUM_ARABIC_PAREN_R,
                PptDoc.Para.NUM_ARABIC_DASH,
                PptDoc.Para.NUM_ALPHA_LC_PERIOD, PptDoc.Para.NUM_ALPHA_UC_PERIOD,
                PptDoc.Para.NUM_ALPHA_LC_PAREN_R, PptDoc.Para.NUM_ALPHA_UC_PAREN_R,
                PptDoc.Para.NUM_ROMAN_LC_PERIOD, PptDoc.Para.NUM_ROMAN_UC_PERIOD,
                PptDoc.Para.NUM_ROMAN_LC_PAREN_R, PptDoc.Para.NUM_ROMAN_UC_PAREN_R};
        final String[] labels = {
                "۱. ۲. ۳. — عددی با نقطه",
                "۱) ۲) ۳) — عددی با پرانتز",
                "۱- ۲- ۳- — عددی با خط",
                "a. b. c. — حروف کوچک با نقطه",
                "A. B. C. — حروف بزرگ با نقطه",
                "a) b) c) — حروف کوچک با پرانتز",
                "A) B) C) — حروف بزرگ با پرانتز",
                "i. ii. iii. — رومی کوچک با نقطه",
                "I. II. III. — رومی بزرگ با نقطه",
                "i) ii) iii) — رومی کوچک با پرانتز",
                "I) II) III) — رومی بزرگ با پرانتز"};
        new AlertDialog.Builder(host).setTitle("قالب شماره‌گذاری")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        final int ty = types[w];
                        host.mutateSel(new Runnable() {
                            @Override
                            public void run() {
                                for (PptDoc.Para p : PptEditorActivity.parasOf(host.sel)) {
                                    p.bullet = true;
                                    p.hasBullet = true;
                                    p.buAutoNum = true;
                                    p.buChar = null;
                                    p.buType = ty;
                                    p.buStart = 1;
                                    /* v1.32 FIX: same hanging-indent default as
                                       bulletDialog — numbered lists were rendered
                                       glued to / on top of the text */
                                    if (p.marL == 0 && p.indent == 0) {
                                        p.marL = PptEditorActivity.SlideRenderer.DEF_BULLET_MARL;
                                        p.indent = PptEditorActivity.SlideRenderer.DEF_BULLET_INDENT;
                                    }
                                }
                            }
                        });
                    }
                }).show();
    }

    /* فاصله‌گذاری — پنج دکمهٔ سریع فاصلهٔ خطوط + فاصلهٔ قبل/بعد بند (pt) */

    void spacingDialog() {
        if (host.sel == null) {
            Toast.makeText(host, "اول یک شکل متنی انتخاب کن", Toast.LENGTH_SHORT).show();
            return;
        }
        List<PptDoc.Para> ps = PptEditorActivity.parasOf(host.sel);
        if (ps == null || ps.isEmpty()) {
            Toast.makeText(host, "یک شکل متنی انتخاب کن", Toast.LENGTH_SHORT).show();
            return;
        }
        LinearLayout box = new LinearLayout(host);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = host.dp(16);
        box.setPadding(pad, host.dp(4), pad, 0);

        LinearLayout quick = new LinearLayout(host);
        quick.setOrientation(LinearLayout.HORIZONTAL);
        final float[] vals = {0.8f, 1f, 1.5f, 2f, 2.5f};
        final String[] labels = {"۰٫۸", "۱", "۱٫۵", "۲", "۲٫۵"};
        for (int i = 0; i < vals.length; i++) {
            final float mult = vals[i];   /* نام «v» با onClick(View v) سایه می‌شد */
            TextView b = new TextView(host);
            b.setText(labels[i]);
            b.setTextColor(0xFFB7472A);
            b.setTextSize(14f);
            b.setTypeface(null, Typeface.BOLD);
            b.setGravity(Gravity.CENTER);
            b.setPadding(host.dp(8), host.dp(8), host.dp(8), host.dp(8));
            GradientDrawable gd = new GradientDrawable();
            gd.setCornerRadius(host.dp(8));
            gd.setColor(0xFFF1E4E0);
            b.setBackground(gd);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            lp.setMargins(host.dp(3), 0, host.dp(3), 0);
            quick.addView(b, lp);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    host.mutateSel(new Runnable() {
                        @Override
                        public void run() {
                            for (PptDoc.Para p : PptEditorActivity.parasOf(host.sel)) {
                                p.lnSpcPct = mult;
                                p.lnSpcPt = -1f;
                                p.hasLnSpc = true;
                            }
                        }
                    });
                }
            });
        }
        box.addView(labelEt("فاصلهٔ خطوط (ضریب):"));
        box.addView(quick);

        final EditText bef = new EditText(host);
        bef.setHint("فاصله قبل (پوینت)");
        bef.setText(Fa.d(String.valueOf(Math.max(0, Math.round(ps.get(0).spcBefPt)))));
        bef.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        final EditText aft = new EditText(host);
        aft.setHint("فاصله بعد (پوینت)");
        aft.setText(Fa.d(String.valueOf(Math.max(0, Math.round(ps.get(0).spcAftPt)))));
        aft.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        box.addView(labelEt("فاصلهٔ قبل بند (پوینت):"));
        box.addView(bef);
        box.addView(labelEt("فاصلهٔ بعد بند (پوینت):"));
        box.addView(aft);

        new AlertDialog.Builder(host).setTitle("فاصله‌گذاری").setView(box)
                .setPositiveButton("اعمال", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        final float b = PptEditorActivity.parsePt(bef.getText().toString(), 99f);
                        final float a = PptEditorActivity.parsePt(aft.getText().toString(), 99f);
                        host.mutateSel(new Runnable() {
                            @Override
                            public void run() {
                                for (PptDoc.Para p : PptEditorActivity.parasOf(host.sel)) {
                                    p.spcBefPt = b;
                                    p.spcAftPt = a;
                                    p.hasSpcBef = true;
                                    p.hasSpcAft = true;
                                }
                            }
                        });
                    }
                })
                .setNegativeButton("انصراف", (DialogInterface.OnClickListener) null).show();
    }

    /* عدد فارسی/لاتین → float مهارشده در ۰..max (خالی = ۰) */

    void alignDialog() {
        if (host.sel == null) {
            Toast.makeText(host, "اول یک شکل را انتخاب کن", Toast.LENGTH_SHORT).show();
            return;
        }
        final String[] items = {"راست", "وسط افقی", "چپ", "بالا", "وسط عمودی", "پایین"};
        new AlertDialog.Builder(host).setTitle("تراز با اسلاید")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        host.mutateSel(new Runnable() {
                            @Override
                            public void run() {
                                if (w == 0) host.sel.x = host.doc.slideW - host.sel.w;
                                else if (w == 1) host.sel.x = (host.doc.slideW - host.sel.w) / 2;
                                else if (w == 2) host.sel.x = 0;
                                else if (w == 3) host.sel.y = 0;
                                else if (w == 4) host.sel.y = (host.doc.slideH - host.sel.h) / 2;
                                else if (w == 5) host.sel.y = host.doc.slideH - host.sel.h;
                            }
                        });
                    }
                }).show();
    }

    /* ---------- انتقال اسلاید + پخش خودکار (تب طراحی ← ارائه) ---------- */

    void transitionDialog() {
        final PptDoc.Slide s = host.slide();
        String cur = s.transition == null ? "none" : s.transition;
        int selIx = 0;
        for (int i = 0; i < PptEditorActivity.TRANS_KEYS.length; i++) {
            if (PptEditorActivity.TRANS_KEYS[i].equals(cur)) {
                selIx = i;
                break;
            }
        }
        final int[] chosen = {selIx};
        LinearLayout box = new LinearLayout(host);
        box.setOrientation(LinearLayout.VERTICAL);
        ScrollView sc = new ScrollView(host);
        sc.addView(box);
        final TextView[] rows = new TextView[PptEditorActivity.TRANS_NAMES.length];
        for (int i = 0; i < PptEditorActivity.TRANS_NAMES.length; i++) {
            final int ix = i;
            TextView t = new TextView(host);
            t.setTextSize(15f);
            t.setPadding(host.dp(12), host.dp(10), host.dp(12), host.dp(10));
            box.addView(t, new LinearLayout.LayoutParams(-1, -2));
            rows[i] = t;
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    chosen[0] = ix;
                    for (int k = 0; k < rows.length; k++) {
                        styleTransRow(rows[k], PptEditorActivity.TRANS_NAMES[k], k == chosen[0]);
                    }
                }
            });
        }
        for (int k = 0; k < rows.length; k++) {
            styleTransRow(rows[k], PptEditorActivity.TRANS_NAMES[k], k == chosen[0]);
        }
        final EditText sec = new EditText(host);
        sec.setHint("پخش خودکار این اسلاید (ثانیه، ۰ = خاموش)");
        sec.setText(Fa.d(String.valueOf(Math.max(0, Math.round(s.autoSec)))));
        sec.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        box.addView(labelEt("پخش خودکار این اسلاید (ثانیه، ۰ = خاموش):"));
        box.addView(sec);
        new AlertDialog.Builder(host).setTitle("انتقال اسلاید")
                .setView(sc)
                .setPositiveButton("اعمال", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        host.pushUndo();
                        s.transition = "none".equals(PptEditorActivity.TRANS_KEYS[chosen[0]])
                                ? null : PptEditorActivity.TRANS_KEYS[chosen[0]];
                        s.autoSec = PptEditorActivity.parsePt(sec.getText().toString(), 600f);
                        host.canvas.invalidate();
                        host.filmstrip.rebuild();
                        Toast.makeText(host,
                                "انتقال ذخیره شد", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("انصراف", (DialogInterface.OnClickListener) null)
                .show();
    }

    void styleTransRow(TextView t, String label, boolean on) {
        t.setText((on ? "● " : "○ ") + label);
        t.setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
        t.setTextColor(on ? 0xFFB7472A : 0xFF1A1A1A);
    }

    /* ---------- اندازهٔ اسلاید با مقیاس‌بندی همهٔ اشیا ---------- */

    void slideSizeDialog() {
        final int[][] sizes = {
                {12192000, 6858000},   /* 16:9 عریض */
                {9144000, 6858000},    /* 4:3 استاندارد */
                {12192000, 7620000},   /* 16:10 سینمایی */
                {10692000, 7560000}    /* A4 افقی */
        };
        final String[] items = {"عریض ۱۶:۹ (پیش‌فرض)", "استاندارد ۴:۳",
                "سینمایی ۱۶:۱۰", "A4 افقی"};
        new AlertDialog.Builder(host).setTitle("اندازه اسلاید")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        final int nw = sizes[w][0];
                        final int nh = sizes[w][1];
                        if (nw == host.doc.slideW && nh == host.doc.slideH) {
                            Toast.makeText(host,
                                    "اسلاید همین اندازه است", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        new AlertDialog.Builder(host)
                                .setTitle("اندازه اسلاید عوض شود؟")
                                .setMessage("همهٔ اشیای همهٔ اسلایدها (با پس‌زمینهٔ قالب) به اندازهٔ جدید مقیاس می‌شوند.")
                                .setPositiveButton("تغییر بده", new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface d2, int w2) {
                                        host.pushUndo();
                                        float fx = nw / (float) host.doc.slideW;
                                        float fy = nh / (float) host.doc.slideH;
                                        for (int si = 0; si < host.doc.slides.size(); si++) {
                                            PptDoc.Slide s = host.doc.slides.get(si);
                                            for (int pass = 0; pass < 2; pass++) {
                                                List<PptDoc.Shape> list = pass == 0 ? s.shapes : s.bgShapes;
                                                for (int i = 0; i < list.size(); i++) {
                                                    PptDoc.Shape sh = list.get(i);
                                                    sh.x = Math.round(sh.x * fx);
                                                    sh.y = Math.round(sh.y * fy);
                                                    sh.w = Math.max(1, Math.round(sh.w * fx));
                                                    sh.h = Math.max(1, Math.round(sh.h * fy));
                                                    /* v1.27 FIX: a 16:9 ↔ 4:3 conversion
                                                       now scales FONT SIZES and
                                                       body insets too — before, the
                                                       text kept its old size and
                                                       overflowed the converted
                                                       frames */
                                                    List<PptDoc.Para> ps = PptEditorActivity.parasOf(sh);
                                                    if (ps != null) {
                                                        for (PptDoc.Para p : ps) {
                                                            for (PptDoc.Run r : p.runs) {
                                                                r.sizePt = Math.max(4f, Math.min(400f, r.sizePt * fx));
                                                            }
                                                        }
                                                    }
                                                    PptDoc.BodyPr body = PptEditorActivity.SlideRenderer.bodyOf(sh);
                                                    if (body != null) {
                                                        body.insetL *= fx; body.insetR *= fx;
                                                        body.insetT *= fy; body.insetB *= fy;
                                                    }
                                                    /* v1.31 FIX: tables must scale their COLUMN WIDTHS, ROW
                                                       HEIGHTS and CELL MARGINS too — the frame rect scaled
                                                       but the internal grid kept the old EMU sizes, so a
                                                       16:9→4:3 conversion rendered every table overflowing
                                                       the slide (columns wider than the shrunken frame) */
                                                    if (sh instanceof PptDoc.Table) {
                                                        PptDoc.Table t = (PptDoc.Table) sh;
                                                        for (int cwi = 0; cwi < t.colWidths.size(); cwi++) {
                                                            t.colWidths.set(cwi, t.colWidths.get(cwi) * fx);
                                                        }
                                                        for (int tri = 0; tri < t.rows.size(); tri++) {
                                                            PptDoc.TableRow trow = t.rows.get(tri);
                                                            trow.heightEmu *= fy;
                                                            for (int tci = 0; tci < trow.cells.size(); tci++) {
                                                                PptDoc.TableCell tc = trow.cells.get(tci);
                                                                tc.insetL *= fx; tc.insetR *= fx;
                                                                tc.insetT *= fy; tc.insetB *= fy;
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        host.doc.slideW = nw;
                                        host.doc.slideH = nh;
                                        host.sel = null;
                                        host.canvas.resetView();
                                        host.refreshAll();
                                        Toast.makeText(host,
                                                "اندازه اسلاید اعمال شد", Toast.LENGTH_SHORT).show();
                                    }
                                })
                                .setNegativeButton("انصراف", (DialogInterface.OnClickListener) null)
                                .show();
                    }
                }).show();
    }

    /* ---------- انیمیشن ورود شکل انتخابی ---------- */

    void animDialog() {
        if (host.sel == null) {
            Toast.makeText(host, "اول یک شکل را انتخاب کن", Toast.LENGTH_SHORT).show();
            return;
        }
        final int curAnim = Math.max(0, Math.min(7, host.sel.anim));
        final String[] items = {"بدون انیمیشن", "محو شدن", "پرواز از راست",
                "پرواز از چپ", "پرواز از بالا", "پرواز از پایین",
                "بزرگ‌نمایی", "پاک‌شدن"};
        new AlertDialog.Builder(host).setTitle("انیمیشن ورود")
                .setSingleChoiceItems(items, curAnim, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        final int choice = w;
                        host.mutateSel(new Runnable() {
                            @Override
                            public void run() { host.sel.anim = choice; }
                        });
                        Toast.makeText(host,
                                "حین ارائه پخش می‌شود", Toast.LENGTH_SHORT).show();
                        d.dismiss();
                    }
                })
                .setNegativeButton("انصراف", (DialogInterface.OnClickListener) null)
                .show();
    }

    /* ---------- سایهٔ شکل / سایهٔ متن ---------- */

    void toggleShapeShadow() {
        host.mutateSel(new Runnable() {
            @Override
            public void run() { host.sel.shadow = !host.sel.shadow; }
        });
        if (host.sel != null) {
            Toast.makeText(host, host.sel.shadow ? "سایهٔ شکل روشن شد" : "سایهٔ شکل خاموش شد",
                    Toast.LENGTH_SHORT).show();
        }
    }

    void toggleTextShadow() {
        host.mutateSel(new Runnable() {
            @Override
            public void run() { host.sel.textShadow = !host.sel.textShadow; }
        });
        if (host.sel != null) {
            Toast.makeText(host, host.sel.textShadow ? "سایهٔ متن روشن شد" : "سایهٔ متن خاموش شد",
                    Toast.LENGTH_SHORT).show();
        }
    }

    /* ---------- جست‌وجو و جایگزینی در کل ارائه (⋮ منو) ---------- */

    void findReplaceDialog() {
        LinearLayout box = new LinearLayout(host);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = host.dp(18);
        box.setPadding(pad, host.dp(4), pad, 0);
        final EditText find = new EditText(host);
        find.setHint("متن پیدا شدن");
        final EditText rep = new EditText(host);
        rep.setHint("جایگزین با");
        final android.widget.CheckBox ci = new android.widget.CheckBox(host);
        ci.setText("بی‌توجه به بزرگی/کوچکی حروف");
        box.addView(labelEt("جست‌وجو:"));
        box.addView(find);
        box.addView(labelEt("جایگزینی با:"));
        box.addView(rep);
        box.addView(ci);
        new AlertDialog.Builder(host).setTitle("جست‌وجو و جایگزینی").setView(box)
                .setPositiveButton("جایگزینی همه", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        doFindReplace(find.getText().toString(),
                                rep.getText().toString(), ci.isChecked());
                    }
                })
                .setNeutralButton("شمارش", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String f = find.getText().toString();
                        if (f.length() == 0) {
                            Toast.makeText(host,
                                    "متن جست‌وجو خالی است", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        int n = host.countOccurrences(f, ci.isChecked());
                        if (n == 0) {
                            Toast.makeText(host,
                                    "چیزی پیدا نشد", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(host,
                                    Fa.d(n) + " مورد پیدا شد", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("انصراف", (DialogInterface.OnClickListener) null).show();
    }

    /* شمارش رخدادها فقط در شکل‌های قابل ویرایش (bgShapes نه — قالب است) */

    void doFindReplace(String find, String rep, boolean ic) {
        if (find == null || find.length() == 0) {
            Toast.makeText(host, "متن جست‌وجو خالی است", Toast.LENGTH_SHORT).show();
            return;
        }
        int n = host.countOccurrences(find, ic);
        if (n == 0) {
            Toast.makeText(host, "چیزی پیدا نشد", Toast.LENGTH_SHORT).show();
            return;
        }
        host.pushUndo();
        for (int si = 0; si < host.doc.slides.size(); si++) {
            List<PptDoc.Shape> shapes = host.doc.slides.get(si).shapes;
            for (int i = 0; i < shapes.size(); i++) {
                List<PptDoc.Para> ps = PptEditorActivity.parasOf(shapes.get(i));
                if (ps == null) continue;
                for (int pi = 0; pi < ps.size(); pi++) {
                    List<PptDoc.Run> runs = ps.get(pi).runs;
                    for (int ri = 0; ri < runs.size(); ri++) {
                        PptDoc.Run r = runs.get(ri);
                        if (r.text == null) continue;
                        r.text = PptEditorActivity.replaceIn(r.text, find, rep, ic);
                    }
                }
            }
        }
        host.refreshAll();
        Toast.makeText(host, Fa.d(n) + " مورد جایگزین شد", Toast.LENGTH_SHORT).show();
    }

    /* جایگزینی حساس به حالت با text.replace؛ ناحیه‌ای برای حالت بی‌توجه */

    void tableDialog() {
        final EditText rows = new EditText(host);
        rows.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        rows.setText("3");
        final EditText cols = new EditText(host);
        cols.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        cols.setText("3");
        LinearLayout box = new LinearLayout(host);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(host.dp(18), host.dp(4), host.dp(18), 0);
        box.addView(labelEt("تعداد سطرها:"));
        box.addView(rows);
        box.addView(labelEt("تعداد ستون‌ها:"));
        box.addView(cols);
        new AlertDialog.Builder(host).setTitle("درج جدول").setView(box)
                .setPositiveButton("درج", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        /* v1.29 FIX: Fa.parseNum returns -1 for garbage — after
                           Math.max(1, …) the <1 fallback was unreachable, so
                           clearing the field gave a 1x1 table instead of the
                           intended 3x3 default. */
                        int pr = Fa.parseNum(rows.getText().toString());
                        int pc = Fa.parseNum(cols.getText().toString());
                        int r = pr < 1 ? 3 : Math.min(12, pr);
                        int c = pc < 1 ? 3 : Math.min(12, pc);
                        host.insertTable(r, c);
                    }
                })
                .setNegativeButton("انصراف", (DialogInterface.OnClickListener) null).show();
    }

    TextView labelEt(String s) {
        TextView t = new TextView(host);
        t.setText(s);
        t.setTextSize(13f);
        t.setPadding(0, host.dp(8), 0, host.dp(2));
        return t;
    }

    /* ============================== inserts ================================ */
}
