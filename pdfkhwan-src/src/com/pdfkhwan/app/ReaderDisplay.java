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
public class ReaderDisplay {
    final ReaderActivity host;
    ReaderDisplay(ReaderActivity h) { this.host = h; }

    String orientLock = "auto";     /* auto | port | land */

    String paperMode = "white";     /* white | sepia | night */

    int brightnessPct = 0;          /* 0 = سیستم؛ ۲۰ تا ۱۰۰ */

    boolean autoScrollOn = false;

    float autoScrollSpeed = 1.0f;

    Runnable autoScrollTick = null;

    int fitCycle = 0;               /* 0=عرض 1=کل صفحه 2=واقعی */
    /* v7: immersive fullscreen + horizontal page mode */

    boolean immersive = false;
    /* v7: share-current-page-as-image rendering */

    public void applyKeepScreenOn() {
        if (host.keepScreenOn) {
            host.getWindow().addFlags(128);
        } else {
            host.getWindow().clearFlags(128);
        }
    }

    void pageModeDialog() {
        final Dialog[] dlg = new Dialog[1];
        LinearLayout sheet = Modern.sheetContent(host);
        sheet.addView(Modern.sheetHeader(host, "\u0648\u0631\u0642\u200c\u0632\u062f\u0646 \u0635\u0641\u062d\u0627\u062a", "\u0627\u0646\u062a\u062e\u0627\u0628 \u062f\u0627\u0626\u0645\u06cc \u0645\u0627\u0646\u062f", new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { dlg[0].dismiss(); } catch (Throwable ignored) {}
            }
        }));
        final boolean horiz = host.store.getBool("horiz", false);
        final boolean flip = host.store.getBool("hflip", false);
        final boolean book = flip && host.store.getBool("book", false);
        sheet.addView(host.modeOption(dlg, R.drawable.ic_mode_vert, 0xFF64B5F6,
                "\u0627\u0633\u06a9\u0631\u0648\u0644 \u0639\u0645\u0648\u062f\u06cc",
                "\u0635\u0641\u062d\u0627\u062a \u067e\u0634\u062a\u200c\u0633\u0631\u0647\u0645 \u062f\u0631 \u062c\u0647\u062a \u0628\u0627\u0644\u0627 \u0648 \u067e\u0627\u06cc\u06cc\u0646", !horiz, false, false, false));
        sheet.addView(host.modeOption(dlg, R.drawable.ic_mode_horiz, 0xFF4DB6AC,
                "\u0627\u0633\u06a9\u0631\u0648\u0644 \u0627\u0641\u0642\u06cc \u067e\u06cc\u0648\u0633\u062a\u0647",
                "\u0635\u0641\u062d\u0627\u062a \u067e\u0634\u062a\u200c\u0633\u0631\u0647\u0645 \u062f\u0631 \u062c\u0647\u062a \u0686\u067e \u0648 \u0631\u0627\u0633\u062a", horiz && !flip, true, false, false));
        sheet.addView(host.modeOption(dlg, R.drawable.ic_mode_flip, 0xFFBA68C8,
                "\u0648\u0631\u0642\u200c\u0632\u062f\u0646 \u06cc\u06a9\u200c\u0635\u0641\u062d\u0647\u200c\u0627\u06cc",
                "\u0647\u0631 \u0635\u0641\u062d\u0647 \u06a9\u0627\u0645\u0644 \u062f\u06cc\u062f\u0647 \u0645\u06cc\u200c\u0634\u0648\u062f\u061b \u06a9\u0634\u06cc\u062f\u0646 \u0648 \u0631\u0647\u0627\u06a9\u0631\u062f\u0646 = \u06cc\u06a9 \u0635\u0641\u062d\u0647 \u0648\u0631\u0642", horiz && flip && !book, true, true, false));
        /* v1.47 (بخش ۱۸): کتاب دوصفحه‌ای — دو صفحهٔ رو‌به‌رو مثل کتاب چاپی فارسی؛
           توضیح هر صفحه در نصف عرض صفحه‌نمایش جا می‌شود. */
        sheet.addView(host.modeOption(dlg, R.drawable.ic_m_bookmark, 0xFFFFB74D,
                "\u06a9\u062a\u0627\u0628 \u062f\u0648\u0635\u0641\u062d\u0647\u200c\u0627\u06cc",
                "\u062f\u0648 \u0635\u0641\u062d\u0647 \u0631\u0648\u200c\u0628\u0647\u200c\u0631\u0648 (\u0631\u0627\u0633\u062a\u200c\u0628\u0647\u200c\u0686\u067e\u060c \u0645\u0627\u0646\u062f \u06a9\u062a\u0627\u0628 \u0686\u0627\u067e\u06cc) \u2014 \u0628\u0631\u0627\u06cc \u0627\u06cc\u0646 \u062d\u0627\u0644\u062a \u06af\u0648\u0634\u06cc \u0631\u0627 \u0627\u0641\u0642\u06cc \u06a9\u0646", book, true, true, true));
        LinearLayout pad = new LinearLayout(host);
        sheet.addView(pad, new LinearLayout.LayoutParams(-1, host.dp(10)));
        int cap = Math.round(host.getResources().getDisplayMetrics().heightPixels * 0.55f);
        dlg[0] = host.trackSheet(Modern.showSheetCapped(host, sheet, cap), new Runnable() {
            @Override public void run() { pageModeDialog(); }
        });
    }

    void applyPageModeReload() {
        try {
            host.store.savePosition(host.fileKey, host.getCurrentPage1(), host.pdfView.getZoom(), host.pageCount);
        } catch (Throwable unusedH) {
        }
        host.loaded = false;
        host.loading.setVisibility(0);
        host.selPages.evictAll();
        host.geomBuiltW = -1;   /* v1.47: هندسهٔ محور افقی/کتاب تازه ساخته شود */
        host.openDoc();
    }

    /* ================== v1.37: اشتراک‌گذاری و چاپ ==================
       چند دکمه، هرکدام یک کار — فایل اصلی هرگز دست نمی‌خورد:
       • اشتراک کل فایل (همان بایت‌های اصلی)
       • اشتراک صفحات انتخابی (فقط چند صفحه)
       • تبدیل به Word (متن → DOCX سالم)
       • چاپ کل فایل / چاپ صفحات انتخابی — از چاپگر سیستم؛
         انتخاب «ذخیره به PDF» در چاپ، نسخهٔ تازهٔ قابل‌ویرایش می‌سازد */
    /* ================= v1.46: فرم‌های AcroForm ================= */

    /* نقشهٔ فرم/لینک در پس‌زمینه ساخته می‌شود؛ نتیجه روی رشتهٔ UI می‌رسد */

    void loadDisplayPrefs() {
        orientLock = host.store.getStr("orient", "auto");
        paperMode = host.store.getStr("paper", host.night ? "night" : "white");
        brightnessPct = (int) host.store.getLong("bright", 0);
        autoScrollSpeed = (float) host.store.getLong("autospeed", 10) / 10.0f;
        if (autoScrollSpeed < 0.25f) autoScrollSpeed = 1.0f;
    }

    void applyOrientationLock() {
        try {
            int o = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
            if ("port".equals(orientLock)) o = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
            else if ("land".equals(orientLock)) o = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
            host.setRequestedOrientation(o);
        } catch (Throwable ignored) {
        }
    }

    /* حالت کاغذ: روشن / کهربایی (Sepia) / شب */

    void applyPaperMode(boolean save) {
        boolean nightOn = "night".equals(paperMode);
        host.night = nightOn;
        try {
            host.pdfView.setSepia("sepia".equals(paperMode));
            host.pdfView.setNightMode(nightOn);
        } catch (Throwable ignored) {
        }
        if (save) {
            host.store.setBool("night", nightOn);
            host.store.setStr("paper", paperMode);
        }
    }

    /* روشنایی مستقل از سیستم — فقط روی همین پنجرهٔ خواندن */

    void applyBrightness() {
        try {
            WindowManager.LayoutParams lp = host.getWindow().getAttributes();
            if (brightnessPct >= 20 && brightnessPct <= 100) {
                lp.screenBrightness = brightnessPct / 100.0f;
            } else {
                lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
            }
            host.getWindow().setAttributes(lp);
        } catch (Throwable ignored) {
        }
    }

    /* دکمهٔ سه‌حالتهٔ بزرگ‌نمایی: جا دادن عرض ← جا دادن کل صفحه ← اندازهٔ واقعی */

    void applyFitCycle() {
        if (!host.loaded || host.pdfView == null) return;
        try {
            int cur = host.pdfView.getCurrentPage();
            SizeF ps = host.pdfView.getPageSize(cur);
            if (ps.getWidth() <= 1 || ps.getHeight() <= 1) return;
            float vw = host.pdfView.getWidth();
            float vh = host.pdfView.getHeight();
            float z;
            String label;
            if (fitCycle == 0) {
                z = vw / ps.getWidth();
                label = "جا دادن عرض";
            } else if (fitCycle == 1) {
                z = Math.min(vw / ps.getWidth(), vh / ps.getHeight());
                label = "جا دادن کل صفحه";
            } else {
                z = host.getResources().getDisplayMetrics().ydpi / 72.0f;
                label = "اندازهٔ واقعی (۱۰۰٪)";
            }
            z = Math.max(0.5f, Math.min(8.0f, z));
            host.pdfView.zoomWithAnimation(z);
            host.toast("بزرگ‌نمایی: " + label);
            fitCycle = (fitCycle + 1) % 3;
        } catch (Throwable t) {
            host.toast("بزرگ‌نمایی ممکن نشد");
        }
    }

    /* اسکرول خودکار با سرعت قابل تنظیم — هر لمس متوقفش می‌کند */

    void startAutoScroll() {
        if (host.isHoriz()) {
            autoScrollOn = false;
            host.toast("اسکرول خودکار فقط در حالت اسکرول عمودی کار می‌کند");
            return;
        }
        if (!host.loaded) return;
        autoScrollOn = true;
        final Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (!autoScrollOn || !host.loaded) return;
                try {
                    /* v1.48 FIX: جهت اسکرول خودکار برعکس بود (از آخر به اول).
                       در این کتابخانه جلو رفتن در سند = کاهش Y (منفی)؛
                       دلتای مثبت صفحه را به عقب می‌برد. */
                    host.pdfView.moveRelativeTo(0, -host.dp(1) * 0.6f * autoScrollSpeed);
                    host.pdfView.loadPageByOffset();
                    try {
                        float docLen = host.pdfView.getPdfFile().getDocLen(host.pdfView.getZoom());
                        if (host.pdfView.getCurrentYOffset() <= -docLen + host.pdfView.getHeight() + 1.0f) {
                            stopAutoScroll();
                            host.toast("پایان فایل — اسکرول خودکار متوقف شد");
                            return;
                        }
                    } catch (Throwable ignored) {
                    }
                } catch (Throwable ignored) {
                    return;
                }
                host.ui.postDelayed(this, 16L);
            }
        };
        autoScrollTick = tick;
        host.ui.postDelayed(tick, 16L);
        host.toast("اسکرول خودکار روشن شد — برای توقف صفحه را لمس کن");
    }

    void stopAutoScroll() {
        autoScrollOn = false;
        if (autoScrollTick != null) {
            host.ui.removeCallbacks(autoScrollTick);
            autoScrollTick = null;
        }
    }

    void displaySheet() {
        final Dialog[] dlg = new Dialog[1];
        LinearLayout sheet = Modern.sheetContent(host);
        sheet.addView(Modern.sheetHeader(host, "صفحه‌آرایی و کاغذ", "چرخش، رنگ کاغذ، روشنایی و اسکرول خودکار", new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { dlg[0].dismiss(); } catch (Throwable ignored) {}
            }
        }));

        /* چرخش صفحه */
        sheet.addView(Modern.sectionLabelCompact(host, "قفل جهت صفحه"));
        LinearLayout or = new LinearLayout(host);
        or.setOrientation(LinearLayout.HORIZONTAL);
        or.setGravity(android.view.Gravity.CENTER_VERTICAL);
        String[] oLabels = {"خودکار", "عمودی", "افقی"};
        String[] oVals = {"auto", "port", "land"};
        for (int k = 0; k < 3; k++) {
            final String val = oVals[k];
            TextView c = Modern.chip(host, oLabels[k], orientLock.equals(val), new View.OnClickListener() {
                @Override public void onClick(View v) {
                    orientLock = val;
                    host.store.setStr("orient", val);
                    applyOrientationLock();
                    try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                    displaySheet();
                }
            });
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
            clp.setMarginEnd(host.dp(6));
            or.addView(c, clp);
        }
        sheet.addView(or);

        /* حالت کاغذ */
        sheet.addView(Modern.sectionLabelCompact(host, "حالت کاغذ"));
        LinearLayout pr = new LinearLayout(host);
        pr.setOrientation(LinearLayout.HORIZONTAL);
        pr.setGravity(android.view.Gravity.CENTER_VERTICAL);
        String[] pLabels = {"روشن", "کهربایی", "شب"};
        String[] pVals = {"white", "sepia", "night"};
        for (int k = 0; k < 3; k++) {
            final String val = pVals[k];
            TextView c = Modern.chip(host, pLabels[k], paperMode.equals(val), new View.OnClickListener() {
                @Override public void onClick(View v) {
                    paperMode = val;
                    applyPaperMode(true);
                    try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                    displaySheet();
                }
            });
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
            clp.setMarginEnd(host.dp(6));
            pr.addView(c, clp);
        }
        sheet.addView(pr);

        /* روشنایی مستقل */
        sheet.addView(Modern.sectionLabelCompact(host, "روشنایی صفحهٔ خواندن (مستقل از سیستم)"));
        LinearLayout br = new LinearLayout(host);
        br.setOrientation(LinearLayout.HORIZONTAL);
        br.setGravity(android.view.Gravity.CENTER_VERTICAL);
        final TextView brLabel = new TextView(host);
        brLabel.setTextColor(Modern.C_MUTED);
        brLabel.setTextSize(12f);
        brLabel.setText(brightnessPct >= 20 ? Fa.d(brightnessPct) + "٪" : "سیستم");
        brLabel.setPadding(0, 0, host.dp(10), 0);
        br.addView(brLabel);
        SeekBar sb = Modern.slider(host);
        Modern.tintSlider(sb, 0xFFFFD54F, host);
        sb.setMax(100);
        sb.setProgress(Math.max(20, brightnessPct >= 20 ? brightnessPct : 100));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int val, boolean fromUser) {
                int pct = Math.max(20, val);
                brightnessPct = pct;
                host.store.setLong("bright", pct);
                applyBrightness();
                brLabel.setText(Fa.d(pct) + "٪");
            }

            @Override public void onStartTrackingTouch(SeekBar s) {
            }

            @Override public void onStopTrackingTouch(SeekBar s) {
            }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, -2, 1f);
        br.addView(sb, slp);
        TextView sysChip = Modern.chip(host, "سیستم", brightnessPct < 20, new View.OnClickListener() {
            @Override public void onClick(View v) {
                brightnessPct = 0;
                host.store.setLong("bright", 0);
                applyBrightness();
                brLabel.setText("سیستم");
            }
        });
        LinearLayout.LayoutParams chp = new LinearLayout.LayoutParams(-2, -2);
        chp.setMarginStart(host.dp(8));
        br.addView(sysChip, chp);
        sheet.addView(br);

        /* اسکرول خودکار */
        sheet.addView(Modern.sectionLabelCompact(host, "اسکرول خودکار"));
        LinearLayout as = new LinearLayout(host);
        as.setOrientation(LinearLayout.HORIZONTAL);
        as.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView startBtn = Modern.tonalBtn(host, autoScrollOn ? "توقف اسکرول" : "شروع اسکرول خودکار",
                autoScrollOn ? Modern.C_DANGER : Modern.C_ACCENT, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (autoScrollOn) {
                            stopAutoScroll();
                        } else {
                            startAutoScroll();
                        }
                        try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                    }
                });
        as.addView(startBtn);
        final TextView spLabel = new TextView(host);
        spLabel.setTextColor(Modern.C_MUTED);
        spLabel.setTextSize(12f);
        spLabel.setText("  سرعت: " + Fa.d(String.format(Locale.US, "%.1f", autoScrollSpeed)) + "×");
        as.addView(spLabel);
        SeekBar ss = Modern.slider(host);
        Modern.tintSlider(ss, Modern.C_ACCENT, host);
        ss.setMax(40);
        ss.setProgress((int) (autoScrollSpeed * 10));
        ss.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int val, boolean fromUser) {
                float sp = Math.max(2, val) / 10.0f;
                autoScrollSpeed = sp;
                host.store.setLong("autospeed", Math.round(sp * 10));
                spLabel.setText("  سرعت: " + Fa.d(String.format(Locale.US, "%.1f", sp)) + "×");
            }

            @Override public void onStartTrackingTouch(SeekBar s) {
            }

            @Override public void onStopTrackingTouch(SeekBar s) {
            }
        });
        LinearLayout.LayoutParams sslp = new LinearLayout.LayoutParams(0, -2, 1f);
        sslp.setMarginStart(host.dp(8));
        as.addView(ss, sslp);
        sheet.addView(as);

        /* بزرگ‌نمایی سه‌حالته */
        sheet.addView(Modern.sectionLabelCompact(host, "بزرگ‌نمایی"));
        String[] fitNames = {"جا دادن عرض", "جا دادن کل صفحه", "اندازهٔ واقعی"};
        LinearLayout fr = new LinearLayout(host);
        fr.setOrientation(LinearLayout.HORIZONTAL);
        fr.setGravity(android.view.Gravity.CENTER_VERTICAL);
        final TextView fl = new TextView(host);
        fl.setTextColor(Modern.C_MUTED);
        fl.setTextSize(12f);
        fl.setText("چرخه: " + fitNames[fitCycle]);
        fr.addView(fl, new LinearLayout.LayoutParams(0, -2, 1f));
        fr.addView(Modern.tonalBtn(host, "اعمال", Modern.C_ACCENT, new View.OnClickListener() {
            @Override public void onClick(View v) {
                applyFitCycle();
                fl.setText("چرخه: " + fitNames[fitCycle]);
            }
        }));
        sheet.addView(fr);

        int cap = Math.round(host.getResources().getDisplayMetrics().heightPixels * 0.62f);
        dlg[0] = host.trackSheet(Modern.showSheetCapped(host, sheet, cap), new Runnable() {
            @Override public void run() { displaySheet(); }
        });
    }

    /* ====================================================================
       ============ v1.47 — بخش ۱۹/۲۱: خواندن جمله‌به‌جمله با صدا ============
       ==================================================================== */

    /* تشخیص زبان جمله از خطِ متن: حروف فارسی/عربی ← fa، لاتین ← en */

    void applyImmersive() {
        try {
            View decor = host.getWindow().getDecorView();
            if (immersive) {
                decor.setSystemUiVisibility(5894 /* IMMERSIVE_STICKY | FULLSCREEN | HIDE_NAVIGATION | LAYOUT_STABLE | LAYOUT_HIDE_NAVIGATION */);
            } else {
                decor.setSystemUiVisibility(0);
            }
        } catch (Throwable unused) {
        }
    }


}
