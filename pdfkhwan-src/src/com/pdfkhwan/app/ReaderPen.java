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
public class ReaderPen {
    final ReaderActivity host;
    ReaderPen(ReaderActivity h) { this.host = h; }

    int penTool = 0;
    /* آخرین ابزار غیرخاموش — برای روشن‌کردن سریع دکمهٔ هاب */

    int lastPenTool = 1;

    int penColor = 0xFFE53935;

    float penWidthDp = 2.5f;
    /* v1.37: رنگ و ضخامت جداگانه برای هر ابزار — دائمی برای همهٔ فایل‌ها
       (قلم/ماژیک/فلش هرکدام رنگ و ضخامت خودشان را به خاطر می‌سپارند) */

    final int[] toolColors = new int[5];

    final float[] toolWidths = new float[5];
    /* v1.37: حالت دست — اسکرول/زوم آزاد بدون خط‌کشی، داخل نوار رسم */

    boolean handMode = false;
    /* v1.37: قفل زوم — بزرگ‌نمایی با دو انگشت غیرفعال می‌شود */

    boolean zoomLocked = false;
    /* v1.37: پاک‌کن — نقطه‌ای (فقط جای لمس) یا کل خط */

    boolean eraserPartial = false;

    float eraserSizeDp = 14f;

    ImageView handBtn;

    ImageView zoomLockBtn;

    ImageView eraserModeBtn;
    /* v1.38: نوار رسم شناور قابل‌چسباندن — مثل Samsung Notes:
       قابل کشیدن به هرجای صفحه + چسبیدن به لبه‌ها (پایین/بالا افقی،
       چپ/راست عمودی)؛ موضع دائمی ذخیره می‌شود */

    int penDock = 1;              /* 0=آزاد 1=پایین 2=بالا 3=چپ 4=راست */

    float penFreeX = 0.5f, penFreeY = 0.7f; /* جای آزاد — کسر صفحه */

    ImageView penGripBtn;

    ImageView penSizeBtn;         /* فقط نوار عمودی — پاپ‌آپ سایز */

    ImageView redoBtn;            /* بازانجام */

    ImageView undoBtn;            /* v1.44: واگرد — وضعیت روشن/کم‌رنگ */
    /* v1.44: موتور واقعی واگرد/بازانجام چندمرحله‌ای — هر عمل، «قبل/بعد»
       هر خط را ثبت می‌کند؛ پاک‌کردن (هر دو حالت) هم واگرد‌شدنی است و
       واگرد/بازانجام هر تعداد مرحله پشت‌سرهم کار می‌کند */

    final ArrayList<ReaderActivity.HistOp> undoStack = new ArrayList<>();

    final ArrayList<ReaderActivity.HistOp> redoStack = new ArrayList<>();
    /* v1.44: تغییرات پاک‌کن در طول «یک لمس» — کل یک حرکت = یک عمل واگرد */

    java.util.LinkedHashMap<Long, Annotations.Ann[]> eraseGesture = null;

    boolean penBarBuilt = false;  /* v1.38: محتوا ساخته شده؟ */

    boolean penBarBuiltVertical = false;
    /* v1.38: دامنهٔ ضخامت جداگانه هر ابزار (dp) — ماژیک واقعی چاق است */

    ImageView penTopBtn;

    LinearLayout penBar;

    LinearLayout penRow;   /* v1.41: ردیف درونی (داخل اسکرول افقی) */

    ImageView[] penToolBtns = new ImageView[5];

    View penColorChip;

    SeekBar penWidthSlider;

    TextView penWidthVal;
    /* v1.37: خروجی Word — متن صفحات از موتور pdf.js */

    int hlColor() {
        return host.annDialogs.hlPalette[Math.max(0, Math.min(host.annDialogs.hlPalette.length - 1, host.annDialogs.hlColorIdx))];
    }

    String penToolName() {
        switch (penTool) {
            case 1: return "قلم";
            case 2: return "ماژیک";
            case 3: return "پاک‌کن";
            case 4: return "فلش";
            default: return "";
        }
    }

    /* بارگذاری حاشیه‌نویسی‌های این سند + پالت و تنظیمات قلم */
    /* v1.50: بازخوانی annotationهای استاندارد از خود فایل — در پس‌زمینه.
       فقط وقتی JSON خودمان خالی است نمی‌گذاریم تکراری دیده شوند؛ در غیر
       این صورت annotationهای واردشده با JSON روی هم می‌افتند، پس اگر JSON
       همین‌ها را دارد (خروجی قبلاً از همین اپ بوده)، چیزی اضافه نمی‌شود.
       تشخیص: اگر anns.items خالی است و PDF annotation دارد → همه وارد
       می‌شوند. اگر JSON پر است (خودِ این اپ حاشیه دارد)، فایل باز‌شده
       همان فایل اولیهٔ بدون annotation است — وارد نمی‌کنیم. */

    void savePenSettings() {
        try {
            org.json.JSONObject st = new org.json.JSONObject(
                    host.getSharedPreferences("pdfkhwan", 0).getString("settings", "{}"));
            org.json.JSONArray arr = new org.json.JSONArray();
            for (int i = 0; i < host.annDialogs.hlPalette.length; i++) arr.put(host.annDialogs.hlPalette[i]);
            st.put("hlPalette", arr);
            st.put("penColor", toolColors[1]);
            st.put("penWidth", (double) toolWidths[1]);
            st.put("fnoteColor", host.annDialogs.fnoteColor);
            st.put("tc1", toolColors[1]);
            st.put("tc2", toolColors[2]);
            st.put("tc4", toolColors[4]);
            st.put("tw1", (double) toolWidths[1]);
            st.put("tw2", (double) toolWidths[2]);
            st.put("tw4", (double) toolWidths[4]);
            st.put("zoomLock", zoomLocked);
            st.put("eraserPartial", eraserPartial);
            st.put("eraserSize", (double) eraserSizeDp);
            /* v1.38: موضع نوار رسم (چسبیده/آزاد) */
            st.put("penDock", penDock);
            st.put("penFreeX", (double) penFreeX);
            st.put("penFreeY", (double) penFreeY);
            host.getSharedPreferences("pdfkhwan", 0).edit()
                    .putString("settings", st.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    /* بازکشیدن کامل لایه‌های حاشیه‌نویسی + راه‌اندازی پایش هندسه
       v1.38: حاشیه‌نویسی داخل پاس رسم PDFView است → خود pdfView هم
       باید بازکشیده شود (نه فقط لایه‌های رویی) */

    void setPenTool(int tool) {
        if (tool != 0) {
            lastPenTool = tool;
            handMode = false;
            applyToolStyle(tool);
        }
        penTool = tool;
        if (host.drawOverlay == null) host.initSelectionUi();
        if (host.drawOverlay != null) {
            host.drawOverlay.setVisibility(tool == 0 ? 8 : 0);
            if (tool != 0) host.drawOverlay.invalidate();
        }
        if (tool != 0) {
            host.annDialogs.pendingFnote = false;
            showPenBar();
            host.toast("\u062d\u0627\u0644\u062a \u00ab" + penToolName() + "\u00bb \u0641\u0639\u0627\u0644 \u0634\u062f");
        } else {
            hidePenBar();
        }
        refreshPenSeg();
        refreshPenModeBtns();
        refreshTopPenButton();
    }

    /* v1.37: اعمال رنگ/ضخامت ذخیره‌شدهٔ همین ابزار — هر ابزار حالت خودش را
       دارد و بین فایل‌ها هم دائمی است.
       v1.38: رنگ ماژیک دیگر آلفا نمی‌گیرد — ماژیک با بلند مولتی‌پلای
       رسم می‌شود و رنگ کامل (opaque) درست است؛ پیش‌نمایش خودش شفافیت
       را اعمال می‌کند. */

    void applyToolStyle(int tool) {
        int c = toolColors[tool];
        float w = toolWidths[tool];
        if ((c >>> 24) < 0xC0) c |= 0xFF000000;
        penColor = c;
        if (w > 0.05f) {
            penWidthDp = Math.max(ReaderActivity.TOOL_WMIN[tool], Math.min(ReaderActivity.TOOL_WMAX[tool], w));
        } else if (tool != 0) {
            penWidthDp = Math.max(ReaderActivity.TOOL_WMIN[tool], Math.min(ReaderActivity.TOOL_WMAX[tool], 3f));
        }
    }

    /* v1.37: دکمهٔ حالت رسم در نوار بالا — هایلایت وقتی فعال است */

    void refreshTopPenButton() {
        if (penTopBtn != null) {
            penTopBtn.setColorFilter(penTool != 0 ? Modern.C_ACCENT : 0xFF8DA0BC);
        }
    }

    /* ================== v1.37: نوار رسم — ابزار + دست + قفل زوم ==================
       [قلم][ماژیک][پاک‌کن][فلش] | [رنگ][اسلایدر][عدد] | [دست][قفل زوم][واگرد][خروج]
       — دست: اسکرول/زوم آزاد بدون خط‌کشی (تک‌انگشتی هم)
       — بدون دست: تک‌انگشتی فقط رسم می‌کشد؛ دو انگشت زوم/اسکرول می‌کند
       — قفل زوم: بزرگ‌نمایی با دو انگشت غیرفعال (اسکرول دو‌انگشتی می‌ماند)
       — پاک‌کن فعال → دکمهٔ «کل خط / نقطه‌ای» + سایز پاک‌کن نقطه‌ای */
    /* ================== v1.38: نوار رسم شناور قابل‌چسباندن ==================
       مثل Samsung Notes روی تبلت:
       — پیش‌فرض چسبیده به «پایین» صفحه، وسط
       — با دستگیرهٔ (⁞⁞) به هرجای صفحه کشیده می‌شود (شناور آزاد)
       — نزدیک لبه که رها شود به همان لبه می‌چسبد: پایین/بالا = نوار
         افقی، چپ/راست = نوار عمودی (ستون ابزار)
       — موضع (لبه یا نقطهٔ آزاد) دائمی ذخیره می‌شود
       دکمه‌ها: [⁞⁞][قلم][ماژیک][پاک‌کن][فلش] | [رنگ][ضخامت⁄سایز] |
                [دست][قفل زوم][حالت پاک‌کن][واگرد][خروج] */

    boolean penBarVertical() {
        return penDock == 3 || penDock == 4;
    }

    void showPenBar() {
        if (penBar == null) {
            /* v1.41: نوار بیرونی (پس‌زمینه/سایه) + ردیف درونی قابل‌اسکرول —
               روی گوشی عمودی همهٔ دکمه‌ها جا نمی‌شدند و چندتا گزینه عملاً
               بیرون صفحه می‌افتاد؛ حالا ردیف افقی داخل HorizontalScrollView
               است و هیچ دکمه‌ای گم نمی‌شود (مثل Samsung Notes) */
            LinearLayout bar = new LinearLayout(host);
            penBar = bar;
            bar.setBackgroundResource(R.drawable.bg_sel_toolbar);
            bar.setElevation(host.dp(8));
            int bp = host.dp(6);
            bar.setPadding(bp, bp, bp, bp);
            ViewGroup root = (ViewGroup) host.findViewById(R.id.pdfView).getParent();
            android.widget.FrameLayout.LayoutParams blp =
                    new android.widget.FrameLayout.LayoutParams(-2, -2,
                            android.view.Gravity.LEFT | android.view.Gravity.TOP);
            root.addView(bar, blp);
        }
        boolean vert = penBarVertical();
        if (!penBarBuilt || vert != penBarBuiltVertical) {
            rebuildPenBar();
            penBarBuilt = true;
            penBarBuiltVertical = vert;
        }
        penBar.setVisibility(0);
        positionPenBar();
        updatePenBar();
        refreshPenSeg();
        refreshPenModeBtns();
    }

    /* بازسازی محتوای نوار برای جهت فعلی (افقی/عمودی) */

    void rebuildPenBar() {
        LinearLayout bar = penBar;
        if (bar == null) return;
        final boolean vertical = penBarVertical();
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        bar.removeAllViews();

        /* v1.41: ردیف محتوا — عمودی مستقیم داخل bar؛ افقی داخل اسکرول */
        LinearLayout row = new LinearLayout(host);
        penRow = row;
        row.setOrientation(vertical ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        if (vertical) row.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        if (vertical) {
            row.addView(row, new LinearLayout.LayoutParams(-2, -2));
        } else {
            android.widget.HorizontalScrollView hsv = new android.widget.HorizontalScrollView(host);
            hsv.setHorizontalScrollBarEnabled(false);
            hsv.setFillViewport(false);
            /* عرض بار حداکثر به عرض ریشه محدود می‌شود تا از لبه بیرون نزند */
            bar.addView(hsv, new LinearLayout.LayoutParams(-2, -2));
            hsv.addView(row, new android.widget.FrameLayout.LayoutParams(-2, -2));
        }

        /* v1.41: حالت فشرده روی صفحه‌های باریک — دکمه‌ها کوچک‌تر و
           اسلایدر جایش دکمهٔ سایز می‌گیرد تا همهٔ ابزارها بدون اسکرول
           هم جا شوند؛ اسکرول هم همیشه هست */
        ViewGroup root = (ViewGroup) host.findViewById(R.id.pdfView).getParent();
        int rootW = root != null ? root.getWidth() : 0;
        boolean compact = !vertical && rootW > 0 && rootW < host.dp(470);
        int itemDp = compact ? host.dp(32) : host.dp(35);

        LinearLayout.LayoutParams itemLp = new LinearLayout.LayoutParams(itemDp, itemDp);

        /* --- دستگیرهٔ کشیدن --- */
        ImageView grip = Modern.segBtn(host, R.drawable.ic_pen_grip, Modern.C_MUTED, false, null);
        penGripBtn = grip;
        attachPenDrag(grip);
        if (vertical) {
            LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(itemDp, host.dp(24));
            row.addView(grip, glp);
        } else {
            LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(host.dp(18), itemDp);
            row.addView(grip, glp);
        }

        /* --- ابزارها --- */
        int[] icons = {0, R.drawable.ic_ann_pen, R.drawable.ic_ann_marker,
                R.drawable.ic_ann_eraser, R.drawable.ic_ann_arrow};
        for (int t = 1; t <= 4; t++) {
            final int tool = t;
            ImageView b = Modern.segBtn(host, icons[t], Modern.C_TEXT, false, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    setPenTool(tool);
                    savePenSettings();
                }
            });
            penToolBtns[t] = b;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(itemLp);
            row.addView(b, lp);
        }

        row.addView(vertical ? hDividerForV() : host.vDivider());

        /* --- چیپ رنگ --- */
        View chip = new View(host);
        penColorChip = chip;
        LinearLayout.LayoutParams clp = vertical
                ? new LinearLayout.LayoutParams(host.dp(28), host.dp(28))
                : new LinearLayout.LayoutParams(host.dp(28), host.dp(28));
        if (vertical) clp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        else clp.setMargins(host.dp(4), 0, host.dp(4), 0);
        chip.setLayoutParams(clp);
        chip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                /* v1.43: یک پاپ‌آپ برای همهٔ ابزارها — رنگ‌ها + اندازه/حالت پاک‌کن +
                   رنگ سفارشی (خواستهٔ کاربر؛ بخش سایزِ جدا حذف شد). */
                host.annDialogs.toolSettingsDialog();
            }
        });
        row.addView(chip);

        /* v1.43: اسلایدر/دکمهٔ سایز از نوار حذف شد — اندازه داخل پاپ‌آپ
           تنظیم ابزار (چیپ رنگ) است؛ دکمه‌ها هم بهم نزدیک‌تر شدند. */
        penWidthSlider = null;
        penWidthVal = null;

        row.addView(vertical ? hDividerForV() : host.vDivider());

        /* --- حالت دست --- */
        handBtn = Modern.segBtn(host, R.drawable.ic_hand, Modern.C_ACCENT, false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                handMode = !handMode;
                host.toast(handMode
                        ? "\u062d\u0627\u0644\u062a \u062f\u0633\u062a: \u0627\u0633\u06a9\u0631\u0648\u0644 \u0648 \u0632\u0648\u0645 \u0622\u0632\u0627\u062f \u0627\u0633\u062a (\u062e\u0637 \u06a9\u0634\u06cc\u062f\u0647 \u0646\u0645\u06cc\u200c\u0634\u0648\u062f)"
                        : "\u062d\u0627\u0644\u062a \u0631\u0633\u0645 \u0641\u0639\u0627\u0644 \u0634\u062f");
                refreshPenModeBtns();
            }
        });
        row.addView(handBtn, new LinearLayout.LayoutParams(itemLp));

        /* --- قفل زوم --- */
        zoomLockBtn = Modern.segBtn(host, R.drawable.ic_zoomlock, Modern.C_ACCENT, zoomLocked, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                zoomLocked = !zoomLocked;
                try { host.pdfView.setZoomLocked(zoomLocked); } catch (Throwable ignored) {}
                savePenSettings();
                host.toast(zoomLocked
                        ? "\u0642\u0641\u0644 \u0632\u0648\u0645 \u0641\u0639\u0627\u0644 \u0634\u062f (\u0628\u0632\u0631\u06af\u200c\u0646\u0645\u0627\u06cc\u06cc \u062a\u063a\u06cc\u06cc\u0631 \u0646\u0645\u06cc\u200c\u06a9\u0646\u062f)"
                        : "\u0642\u0641\u0644 \u0632\u0648\u0645 \u0628\u0627\u0632 \u0634\u062f");
                refreshPenModeBtns();
            }
        });
        row.addView(zoomLockBtn, new LinearLayout.LayoutParams(itemLp));

        /* --- حالت پاک‌کن (کل خط / نقطه‌ای) --- */
        eraserModeBtn = Modern.segBtn(host, eraserPartial
                ? R.drawable.ic_eraser_partial : R.drawable.ic_ann_eraser, Modern.C_ACCENT, false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                eraserPartial = !eraserPartial;
                savePenSettings();
                host.toast(eraserPartial
                        ? "\u067e\u0627\u06a9\u200c\u06a9\u0646 \u0646\u0642\u0637\u0647\u200c\u0627\u06cc: \u0641\u0642\u0637 \u062c\u0627\u06cc\u0644\u0645\u0633 \u067e\u0627\u06a9 \u0645\u06cc\u200c\u0634\u0648\u062f"
                        : "\u067e\u0627\u06a9\u200c\u06a9\u0646 \u06a9\u0644 \u062e\u0637: \u0644\u0628 \u06a9\u0647 \u0644\u0645\u0633 \u06a9\u0646\u06cc \u06a9\u0627\u0645\u0644 \u067e\u0627\u06a9 \u0645\u06cc\u200c\u0634\u0648\u062f");
                refreshPenModeBtns();
                updatePenBar();
            }
        });
        row.addView(eraserModeBtn, new LinearLayout.LayoutParams(itemLp));

        /* --- واگرد (v1.44: چندمرحله‌ای — خطوط/پاک‌کردن‌ها یکی‌یکی برمی‌گردند) --- */
        ImageView undo = Modern.iconBtn(host, R.drawable.ic_ann_undo, Modern.C_TEXT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                historyUndo();
            }
        });
        undoBtn = undo;
        row.addView(undo, new LinearLayout.LayoutParams(itemLp));

        /* --- v1.44: بازانجام (redo) — جفت واگرد، چندمرحله‌ای --- */
        ImageView redo = Modern.iconBtn(host, R.drawable.ic_ann_redo, Modern.C_TEXT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                historyRedo();
            }
        });
        redoBtn = redo;
        row.addView(redo, new LinearLayout.LayoutParams(itemLp));

        /* --- خروج --- */
        ImageView exit = Modern.iconBtn(host, R.drawable.ic_ann_close, Modern.C_DANGER, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setPenTool(0);
                host.toast("\u0627\u0632 \u062d\u0627\u0644\u062a \u0631\u0633\u0645 \u062e\u0627\u0631\u062c \u0634\u062f\u06cc");
            }
        });
        row.addView(exit, new LinearLayout.LayoutParams(itemLp));
    }

    /* جداکنندهٔ افقی برای نوار عمودی */

    View hDividerForV() {
        View d = new View(host);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(host.dp(22), Math.max(1, host.dp(1)));
        lp.setMargins(0, host.dp(5), 0, host.dp(5));
        lp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        d.setLayoutParams(lp);
        d.setBackgroundColor(0x2EFFFFFF);
        return d;
    }

    /* جای نوار بر اساس لبهٔ چسبیده یا موضع آزاد */

    void positionPenBar() {
        if (penBar == null) return;
        final LinearLayout bar = penBar;
        Runnable place = new Runnable() {
            @Override public void run() {
                try {
                    ViewGroup root = (ViewGroup) bar.getParent();
                    if (root == null) return;
                    int rw = root.getWidth(), rh = root.getHeight();
                    if (rw <= 0 || rh <= 0) return;
                    bar.measure(View.MeasureSpec.makeMeasureSpec(rw, View.MeasureSpec.AT_MOST),
                            View.MeasureSpec.makeMeasureSpec(rh, View.MeasureSpec.AT_MOST));
                    int bw = bar.getMeasuredWidth(), bh = bar.getMeasuredHeight();
                    float m = host.dp(12);
                    float x, y;
                    switch (penDock) {
                        case 2: /* بالا — v1.41: جای منوی سرصفحه را می‌گیرد
                                   (مثل پنل‌های فتوشاپ) و خودِ منو پنهان می‌شود */
                            x = (rw - bw) / 2f;
                            y = host.dp(10);
                            break;
                        case 3: /* چپ */
                            x = m;
                            y = (rh - bh) / 2f;
                            break;
                        case 4: /* راست */
                            x = rw - bw - m;
                            y = (rh - bh) / 2f;
                            break;
                        case 0: /* آزاد */
                            x = (penFreeX * rw) - (bw / 2f);
                            y = (penFreeY * rh) - (bh / 2f);
                            break;
                        default: /* پایین */
                            x = (rw - bw) / 2f;
                            y = rh - bh - m;
                            break;
                    }
                    x = Math.max(4f, Math.min(x, rw - bw - 4f));
                    y = Math.max(4f, Math.min(y, rh - bh - 4f));
                    bar.setX(x);
                    bar.setY(y);
                    /* v1.41: چسبیدن به بالا = جایگزین منوی سرصفحه */
                    try {
                        View tb = host.findViewById(R.id.topBar);
                        if (tb != null) {
                            boolean cover = penDock == 2 && penTool != 0;
                            tb.setVisibility(cover || !host.barsVisible ? 4 : 0);
                        }
                    } catch (Throwable ignored2) {
                    }
                } catch (Throwable ignored) {
                }
            }
        };
        bar.post(place);
    }

    /* کشیدن نوار با دستگیره — رهاکردن نزدیک لبه = چسبیدن به همان لبه */

    void attachPenDrag(View grip) {
        grip.setOnTouchListener(new View.OnTouchListener() {
            private float downRX, downRY, barX, barY;
            private boolean moved = false;

            @Override
            public boolean onTouch(View v, MotionEvent ev) {
                try {
                    switch (ev.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            moved = false;
                            downRX = ev.getRawX();
                            downRY = ev.getRawY();
                            if (penBar != null) {
                                barX = penBar.getX();
                                barY = penBar.getY();
                            }
                            return true;
                        case MotionEvent.ACTION_MOVE: {
                            float dx = ev.getRawX() - downRX;
                            float dy = ev.getRawY() - downRY;
                            if (Math.abs(dx) > host.dp(3) || Math.abs(dy) > host.dp(3)) moved = true;
                            if (!moved || penBar == null) return true;
                            ViewGroup root = (ViewGroup) penBar.getParent();
                            int rw = root.getWidth(), rh = root.getHeight();
                            float nx = barX + dx, ny = barY + dy;
                            nx = Math.max(0f, Math.min(nx, rw - penBar.getWidth()));
                            ny = Math.max(0f, Math.min(ny, rh - penBar.getHeight()));
                            penBar.setX(nx);
                            penBar.setY(ny);
                            return true;
                        }
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL: {
                            if (!moved || penBar == null) return true;
                            ViewGroup root = (ViewGroup) penBar.getParent();
                            int rw = root.getWidth(), rh = root.getHeight();
                            int bw = penBar.getWidth(), bh = penBar.getHeight();
                            float snap = host.dp(38);
                            float cx = penBar.getX() + bw / 2f;
                            float cy = penBar.getY() + bh / 2f;
                            int oldDock = penDock;
                            if (cx < snap) penDock = 3;               /* چپ */
                            else if (cx > rw - snap) penDock = 4;     /* راست */
                            else if (cy < snap) penDock = 2;          /* بالا */
                            else if (cy > rh - snap) penDock = 1;     /* پایین */
                            else {
                                penDock = 0;                          /* آزاد */
                                penFreeX = Math.min(0.98f, Math.max(0.02f, cx / rw));
                                penFreeY = Math.min(0.98f, Math.max(0.02f, cy / rh));
                            }
                            boolean wasVertical = oldDock == 3 || oldDock == 4;
                            boolean nowVertical = penDock == 3 || penDock == 4;
                            savePenSettings();
                            if (wasVertical != nowVertical) {
                                rebuildPenBar();
                                penBarBuilt = true;
                                penBarBuiltVertical = nowVertical;
                                refreshPenSeg();
                                refreshPenModeBtns();
                                updatePenBar();
                            }
                            positionPenBar();
                            host.toast(penDock == 0 ? "\u0646\u0648\u0627\u0631 \u0631\u0633\u0645 \u062f\u0631 \u0647\u0645\u06cc\u0646\u200c\u062c\u0627 \u0642\u0631\u0627\u0631 \u06af\u0631\u0641\u062a"
                                    : penDock == 1 ? "\u0646\u0648\u0627\u0631 \u0631\u0633\u0645 \u0628\u0647 \u067e\u0627\u06cc\u06cc\u0646 \u0635\u0641\u062d\u0647 \u0686\u0633\u0628\u06cc\u062f"
                                    : penDock == 2 ? "\u0646\u0648\u0627\u0631 \u0631\u0633\u0645 \u0628\u0647 \u0628\u0627\u0644\u0627\u06cc \u0635\u0641\u062d\u0647 \u0686\u0633\u0628\u06cc\u062f"
                                    : penDock == 3 ? "\u0646\u0648\u0627\u0631 \u0631\u0633\u0645 \u0628\u0647 \u0633\u0645\u062a \u0686\u067e \u0635\u0641\u062d\u0647 \u0686\u0633\u0628\u06cc\u062f (\u0639\u0645\u0648\u062f\u06cc)"
                                    : "\u0646\u0648\u0627\u0631 \u0631\u0633\u0645 \u0628\u0647 \u0633\u0645\u062a \u0631\u0627\u0633\u062a \u0635\u0641\u062d\u0647 \u0686\u0633\u0628\u06cc\u062f (\u0639\u0645\u0648\u062f\u06cc)");
                            return true;
                        }
                    }
                } catch (Throwable ignored) {
                }
                return false;
            }
        });
    }

    /* پاپ‌آپ سایز برای نوار عمودی (و دسترسی سریع در افقی) */

    void penSizePopup() {
        final Dialog[] dlg = new Dialog[1];
        LinearLayout card = Modern.cardContent(host);
        card.addView(Modern.sheetHeader(host, "\u0636\u062e\u0627\u0645\u062a \u0627\u0628\u0632\u0627\u0631", penToolName(), new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { dlg[0].dismiss(); } catch (Throwable ignored) {}
            }
        }));
        /* پیش‌نمایش خط */
        final View prev = new View(host);
        LinearLayout.LayoutParams plp =
                new LinearLayout.LayoutParams(-1, host.dp(34));
        plp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        LinearLayout prevWrap = new LinearLayout(host);
        prevWrap.setGravity(android.view.Gravity.CENTER);
        prevWrap.setPadding(host.dp(18), host.dp(10), host.dp(18), 0);
        prevWrap.addView(prev, plp);
        card.addView(prevWrap);
        final TextView val = new TextView(host);
        val.setTextColor(Modern.C_MUTED);
        val.setTextSize(12.5f);
        val.setGravity(android.view.Gravity.CENTER);
        val.setPadding(0, host.dp(8), 0, 0);
        card.addView(val);
        final SeekBar sb = new SeekBar(host);
        Modern.tintSlider(sb, penColor | 0xFF000000, host);
        card.addView(sb, new LinearLayout.LayoutParams(-1, -2));
        float mn, mx, cur;
        if (penTool == 3 && eraserPartial) {
            mn = ReaderActivity.TOOL_WMIN[3]; mx = ReaderActivity.TOOL_WMAX[3]; cur = eraserSizeDp;
        } else if (penTool != 0) {
            mn = ReaderActivity.TOOL_WMIN[penTool]; mx = ReaderActivity.TOOL_WMAX[penTool]; cur = penWidthDp;
        } else {
            mn = 1f; mx = 60f; cur = penWidthDp;
        }
        final float fMn = mn, fMx = mx;
        int steps = Math.max(1, Math.round((mx - mn) * 2f));
        sb.setMax(steps);
        sb.setProgress(Math.max(0, Math.min(steps, Math.round((cur - mn) * 2f))));
        Runnable upd = new Runnable() {
            @Override public void run() {
                float v = fMn + (sb.getProgress() / 2f);
                if (penTool == 3 && eraserPartial) eraserSizeDp = v;
                else host.pen.penWidthDp = v;
                int px = Math.round(v * host.getResources().getDisplayMetrics().density);
                prev.setBackground(Modern.round(penTool == 2 || (penTool == 3 && eraserPartial)
                        ? (penColor & 0x00FFFFFF) | 0x59000000 : penColor | 0xFF000000, host.dp(10), host));
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
                if (penTool != 3) toolWidths[penTool] = penWidthDp;
                savePenSettings();
                updatePenBar();
            }
        });
        upd.run();
        card.addView(Modern.filledBtn(host, "\u0628\u0633\u062a\u0646", Modern.C_ACCENT, 0xFF0B1220, new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { dlg[0].dismiss(); } catch (Throwable ignored) {}
            }
        }), new LinearLayout.LayoutParams(-1, -2));
        LinearLayout wrap = new LinearLayout(host);
        wrap.setPadding(host.dp(14), 0, host.dp(14), 0);
        wrap.addView(card, new LinearLayout.LayoutParams(-1, -2));
        dlg[0] = host.trackDialog(Modern.showCardAuto(host, wrap));
    }

    /* اعمال مقدار اسلایدر (نیم-dp دقت) روی متغیر درست */

    void applySliderWidth(int progress) {
        int tool = penTool == 0 ? 1 : penTool;
        float mn = ReaderActivity.TOOL_WMIN[tool], mx = ReaderActivity.TOOL_WMAX[tool];
        float v = mn + (progress / 2f);
        v = Math.max(mn, Math.min(mx, v));
        if (penTool == 3 && eraserPartial) {
            eraserSizeDp = Math.max(ReaderActivity.TOOL_WMIN[3], Math.min(ReaderActivity.TOOL_WMAX[3], v));
        } else {
            host.pen.penWidthDp = v;
        }
        updatePenWidthLabel();
    }

    void hidePenBar() {
        if (penBar != null) penBar.setVisibility(8);
        /* v1.41: خروج از رسم — منوی سرصفحه برمی‌گردد */
        try {
            if (host.topBar != null) {
                host.topBar.setVisibility(host.barsVisible ? 0 : 4);
            }
        } catch (Throwable ignored) {
        }
    }

    void updatePenChip() {
        if (penColorChip != null) {
            int show = penTool == 3 ? 0xFF4A5A78 : (toolColors[penTool == 0 ? 1 : penTool] | 0xFF000000);
            penColorChip.setBackground(Modern.ripple(
                    Modern.ring(show, 2.5f, 0x99FFFFFF, host), Modern.C_RIPPLE));
        }
        if (penSizeBtn != null) {
            penSizeBtn.setColorFilter(penTool == 3 ? Modern.C_MUTED : Modern.C_ACCENT);
        }
    }

    void updatePenWidthLabel() {
        if (penWidthVal != null) {
            float v = (penTool == 3 && eraserPartial) ? eraserSizeDp : penWidthDp;
            penWidthVal.setText(Fa.d(Math.round(v)));
        }
    }

    void updatePenBar() {
        updatePenChip();
        if (penWidthSlider != null) {
            int tool = penTool == 0 ? 1 : penTool;
            float mn = ReaderActivity.TOOL_WMIN[tool], mx = ReaderActivity.TOOL_WMAX[tool];
            boolean eraser = penTool == 3;
            float cur = (eraser && eraserPartial) ? eraserSizeDp : penWidthDp;
            int steps = Math.max(1, Math.round((mx - mn) * 2f));
            penWidthSlider.setMax(steps);
            int prog = Math.max(0, Math.min(steps, Math.round((cur - mn) * 2f)));
            penWidthSlider.setProgress(prog);
            /* پاک‌کن «کل خط» سایز ندارد → اسلایدر کم‌رنگ */
            penWidthSlider.setEnabled(!(eraser && !eraserPartial));
            penWidthSlider.setAlpha(eraser && !eraserPartial ? 0.35f : 1f);
            Modern.tintSlider(penWidthSlider, eraser ? Modern.C_MUTED : (penColor | 0xFF000000), host);
        }
        updatePenWidthLabel();
    }

    /* v1.37: وضعیت دکمه‌های دست/قفل زوم/حالت پاک‌کن */

    void refreshPenModeBtns() {
        if (handBtn != null) {
            Modern.refreshSeg(handBtn, host, handMode);
            handBtn.setColorFilter(handMode ? Modern.C_ACCENT : Modern.C_MUTED);
        }
        if (zoomLockBtn != null) {
            Modern.refreshSeg(zoomLockBtn, host, zoomLocked);
            zoomLockBtn.setColorFilter(zoomLocked ? 0xFFFFB74D : Modern.C_MUTED);
        }
        if (eraserModeBtn != null) {
            eraserModeBtn.setVisibility(penTool == 3 ? 0 : 8);
            eraserModeBtn.setImageResource(eraserPartial
                    ? R.drawable.ic_eraser_partial : R.drawable.ic_ann_eraser);
            Modern.refreshSeg(eraserModeBtn, host, eraserPartial);
            eraserModeBtn.setColorFilter(eraserPartial ? Modern.C_ACCENT : Modern.C_MUTED);
        }
    }

    /* \u0647\u06cc\u0627\u0629 \u062f\u06a9\u0645\u0647\u0654 \u0627\u0628\u0632\u0627\u0631 \u0641\u0639\u0627\u0644 */

    void refreshPenSeg() {
        for (int t = 1; t <= 4; t++) {
            ImageView b = penToolBtns[t];
            if (b == null) continue;
            boolean active = penTool == t;
            Modern.refreshSeg(b, host, active);
            b.setColorFilter(active ? Modern.C_ACCENT : Modern.C_MUTED);
        }
    }

    /* ================== v1.44: موتور واگرد/بازانجام چندمرحله‌ای ==================
       هر عمل (کشیدن خط/ماژیک/فلش، پاک‌کردن با هر دو حالت پاک‌کن، حذف
       حاشیه‌نویسی، هایلایت متنی) به‌صورت «قبل/بعد» هر خط ثبت می‌شود؛
       واگرد و بازانجام هر تعداد مرحله پشت‌سرهم کار می‌کنند و دکمه‌ها
       با کم‌رنگ‌شدن وضعیت خالی را نشان می‌دهند. */

    /* ثبت عمل تازه روی تاریخچه — پشتهٔ بازانجام بی‌اعتبار می‌شود */

    void histPush(ReaderActivity.HistOp op) {
        if (op == null || op.changes.isEmpty()) return;
        undoStack.add(op);
        while (undoStack.size() > ReaderActivity.HIST_MAX) undoStack.remove(0);
        redoStack.clear();
        updateUndoRedoBtns();
    }

    void historyUndo() {
        if (undoStack.isEmpty()) {
            host.toast("کاری برای واگرد نیست");
            return;
        }
        ReaderActivity.HistOp op = undoStack.remove(undoStack.size() - 1);
        for (int i = 0; i < op.changes.size(); i++) {
            ReaderActivity.AnnChange c = op.changes.get(i);
            host.anns.remove(c.id);
            if (c.before != null) host.anns.add(c.before);
        }
        host.anns.save();
        redoStack.add(op);
        host.annDraw.annInvalidate();
        host.toast("واگرد شد");
    }

    void historyRedo() {
        if (redoStack.isEmpty()) {
            host.toast("کاری برای بازانجام نیست");
            return;
        }
        ReaderActivity.HistOp op = redoStack.remove(redoStack.size() - 1);
        for (int i = 0; i < op.changes.size(); i++) {
            ReaderActivity.AnnChange c = op.changes.get(i);
            host.anns.remove(c.id);
            if (c.after != null) host.anns.add(c.after);
        }
        host.anns.save();
        undoStack.add(op);
        host.annDraw.annInvalidate();
        host.toast("بازانجام شد");
    }

    /* دکمه‌های واگرد/بازانجام — خالی → کم‌رنگ */

    void updateUndoRedoBtns() {
        try {
            if (undoBtn != null) undoBtn.setAlpha(undoStack.isEmpty() ? 0.35f : 1f);
            if (redoBtn != null) redoBtn.setAlpha(redoStack.isEmpty() ? 0.35f : 1f);
        } catch (Throwable ignored) {
        }
    }

    /* عکسِ «قبل» یک خط برای عمل پاک‌کن — اگر همین خط قبلاً در همین لمس
       دست خورده باشد همان عکسِ اولیه برمی‌گردد (نه وضعیت میانی) */

    Annotations.Ann histEraseBefore(Annotations.Ann a) {
        if (eraseGesture != null) {
            Annotations.Ann[] e = eraseGesture.get(a.id);
            if (e != null) return e[0];
        }
        return a.copy();
    }

    void histErasePut(long id, Annotations.Ann before, Annotations.Ann after) {
        if (eraseGesture == null) eraseGesture = new java.util.LinkedHashMap<>();
        Annotations.Ann[] e = eraseGesture.get(id);
        if (e == null) {
            e = new Annotations.Ann[]{null, null};
            eraseGesture.put(id, e);
        }
        if (before != null) e[0] = before;
        e[1] = after;
    }

    /* پایان یک حرکت پاک‌کن: همهٔ تغییرات این لمس در «یک» عمل ثبت می‌شود
       (تکه‌ای که در همین لمس ساخته و دوباره پاک شده = هیچ، ثبت نمی‌شود) */

    void histEraseCommit() {
        if (eraseGesture != null && !eraseGesture.isEmpty()) {
            ReaderActivity.HistOp op = new ReaderActivity.HistOp();
            for (java.util.Map.Entry<Long, Annotations.Ann[]> en : eraseGesture.entrySet()) {
                Annotations.Ann[] v = en.getValue();
                if (v[0] == null && v[1] == null) continue;
                op.changes.add(new ReaderActivity.AnnChange(en.getKey(), v[0], v[1]));
            }
            if (!op.changes.isEmpty()) histPush(op);
        }
        eraseGesture = null;
    }

    /* v1.33: \u062a\u0623\u06cc\u06cc\u062f \u067e\u0627\u06a9\u200c\u06a9\u0631\u062f\u0646 \u0635\u0641\u062d\u0647 \u2014 \u06a9\u0627\u0631\u062a \u0645\u0631\u06a9\u0632\u06cc */
}
