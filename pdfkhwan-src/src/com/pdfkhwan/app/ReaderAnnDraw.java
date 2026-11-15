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
public class ReaderAnnDraw {
    final ReaderActivity host;
    ReaderAnnDraw(ReaderActivity h) { this.host = h; }

    final SparseArray<Long> annWordReq = new SparseArray<>();
    /* رنگ/ضخامت قلم ذخیره‌شده */

    void annInvalidate() {
        /* v1.44: وضعیت روشن/کم‌رنگ دکمه‌های واگرد/بازانجام */
        host.pen.updateUndoRedoBtns();
        try {
            if (host.selOverlay != null) {
                host.selOverlay.setVisibility(!host.anns.items.isEmpty() || host.selPage != null ? 0 : 8);
                host.selOverlay.invalidate();
            }
            if (host.drawOverlay != null && host.drawOverlay.getVisibility() == 0) {
                host.drawOverlay.invalidate();
            }
            if (host.pdfView != null) host.pdfView.invalidate();
            if (!host.anns.items.isEmpty()) host.startGeomWatch();
        } catch (Throwable ignored) {
        }
    }

    /* انتخاب سریع رنگ هایلایت از پالت ۵تایی + «رنگ دیگر…» — بعد از
       انتخاب، هایلایت روی همان بازهٔ فعال اعمال می‌شود */
    /* افزودن حاشیه‌نویسی متنی از روی انتخاب فعلی */

    final Paint annFill = new Paint(1);

    final Paint annLine = new Paint(1);

    final Paint annNote = new Paint(1);

    final Path annPath = new Path();
    /* بلند ماژیک: رنگ × متن — مثل ماژیک واقعی روی کاغذ. متن سیاه زیر
       ماژیک سیاه می‌ماند (سایه ۰ = سایه ۰×رنگ) و هایلایت روی هایلایت
       هم رنگ را عملاً تغییر نمی‌دهد. */

    float annZoom() {
        try {
            return host.pdfView != null ? Math.max(0.25f, Math.min(8f, host.pdfView.getZoom())) : 1f;
        } catch (Throwable t) {
            return 1f;
        }
    }

    float strokeScreenW(float wDp) {
        return Math.max(1f, wDp * host.getResources().getDisplayMetrics().density * annZoom());
    }

    /* یک نوار هایلایت برای یک خط — بدون گوشه گرد و بدون AntiAlias:
       مستطیل تراز‌شده است و AA با xfermode فقط لبهٔ کثیف می‌سازد.
       v1.40: به‌جای رسم فوری، داخل «مسیرِ» همان رنگ افزوده می‌شود تا همهٔ
       نوارهای هم‌رنگ در یک فراخوان واحد رسم شوند. */

    void addHlBand(Path out, float x0, float x1, float top, float bot) {
        if (x1 <= x0 || bot <= top) return;
        float padX = host.dp(2) * annZoom();   /* v1.41: با زوم مقیاس می‌شود */
        float padY = (bot - top) * 0.12f;
        out.addRect(x0 - padX, top - padY, x1 + padX, bot + padY, Path.Direction.CW);
    }

    /* رسم همهٔ حاشیه‌نویسی‌های قابل‌مشاهده — زیر لایهٔ انتخاب
       v1.40: دو پاس —
       پاس ۱ (ماژیک‌ها): هایلایت متنی + خط ماژیک، «گروه‌بندی‌شده به رنگ»؛
         همهٔ شکل‌های هم‌رنگ در یک مسیر و یک فراخوان رسم می‌شوند. نتیجه:
         هرچند بار هم روی همان متن هایلایت بزنی یا ماژیک بکشی، رنگ همان
         یک‌بار اعمال می‌شود — نه تیره‌تر می‌شود نه متن زیرش کم‌رنگ/مخفی.
         (مولتی‌پلای بین فراخوان‌های جدا ضرب می‌شود؛ داخل یک فراخوان فقط
         یک‌بار — مثل ماژیک واقعی که دو بار روی هم کشیدنش رنگ را عوض
         نمی‌کند چون جوهرِ جاافتاده دوباره جا نمی‌افتد.)
       پاس ۲ (بقیه): زیرخط/خط‌خورده/موج‌دار/یادداشت متنی/آیکون‌ها/قلم/فلش. */

    void drawAnnotations(Canvas canvas, float cw, float ch) {
        if (host.anns == null || host.anns.items.isEmpty() || host.pageCount <= 0) return;
        if (host.pageOff1 == null || host.pdfView == null) {
            host.ensureGeom();   /* اولین رسم ممکن است از پایش هندسه جلو بیفتد */
            if (host.pageOff1 == null) return;
        }
        float pad = host.dp(60);

        /* ---------- پاس ۱: جمع‌آوری ماژیک‌ها ---------- */
        java.util.LinkedHashMap<Integer, Path> bandPaths = null;          /* رنگ → نوارهای هایلایت */
        java.util.LinkedHashMap<Long, Path> markerPaths = null;           /* (رنگ|ضخامت) → مسیرها */
        java.util.LinkedHashMap<Long, int[]> markerSpec = null;           /* کلید → {رنگ، ضخامت×۱۰} */
        for (int i = 0; i < host.anns.items.size(); i++) {
            Annotations.Ann a = host.anns.items.get(i);
            if (a.page < 0 || a.page >= host.pageCount) continue;
            if (a.type == Annotations.T_HL
                    || (a.type == Annotations.T_STROKE && a.marker)) {
                RectF pr = host.pageRect(a.page);
                if (pr.width() <= 1f || pr.height() <= 1f) continue;
                if (pr.right < -pad || pr.left > cw + pad || pr.bottom < -pad || pr.top > ch + pad) continue;
                try {
                    if (a.type == Annotations.T_HL) {
                        if (a.w0 < 0 && a.px != null && a.px.length >= 4) {
                            /* v1.50: هایلایتِ واردشده از annotation استاندارد —
                               مستطیل‌های quad مستقیم رسم می‌شود */
                            if (bandPaths == null) bandPaths = new java.util.LinkedHashMap<>();
                            Integer key = Integer.valueOf(a.color | 0xFF000000);
                            Path p = bandPaths.get(key);
                            if (p == null) { p = new Path(); bandPaths.put(key, p); }
                            for (int q = 0; q + 3 < a.px.length && q + 3 < a.py.length; q += 4) {
                                p.addRect(pr.left + (a.px[q] * pr.width()),
                                        pr.top + (a.py[q] * pr.height()),
                                        pr.left + (a.px[q + 2] * pr.width()),
                                        pr.top + (a.py[q + 2] * pr.height()),
                                        Path.Direction.CW);
                            }
                        } else {
                            ReaderActivity.SelPage sp = host.selPages.get(a.page);
                            if (sp == null || sp.words.isEmpty()) {
                                maybeRequestAnnWords(a.page);
                                continue;
                            }
                            if (bandPaths == null) bandPaths = new java.util.LinkedHashMap<>();
                            Integer key = Integer.valueOf(a.color | 0xFF000000);
                            Path p = bandPaths.get(key);
                            if (p == null) { p = new Path(); bandPaths.put(key, p); }
                            collectHlBands(pr, sp, a, p);
                        }
                    } else {
                        if (a.px == null || a.px.length < 2) continue;
                        int col = a.color | 0xFF000000;
                        int wq = Math.max(1, Math.round(a.strokeW * 10f));
                        Long key = Long.valueOf((((long) col) << 20) | (long) wq);
                        if (markerPaths == null) {
                            markerPaths = new java.util.LinkedHashMap<>();
                            markerSpec = new java.util.LinkedHashMap<>();
                        }
                        Path p = markerPaths.get(key);
                        if (p == null) {
                            p = new Path();
                            markerPaths.put(key, p);
                            markerSpec.put(key, new int[]{col, wq});
                        }
                        buildStrokePath(pr, a, p);
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        /* ---------- پاس ۱: رسم ماژیک‌ها ---------- */
        if (bandPaths != null && !bandPaths.isEmpty()) {
            annFill.setStyle(Paint.Style.FILL);
            annFill.setAntiAlias(false);
            for (java.util.Map.Entry<Integer, Path> e : bandPaths.entrySet()) {
                int col = e.getKey().intValue();
                if (host.night) {
                    /* حالت شب (صفحهٔ معکوس): مولتی‌پلای روی زمینهٔ سیاه محو
                       می‌شود → ته‌رنگ استاندارد؛ v1.40 کمی پررنگ‌تر از قبل */
                    annFill.setXfermode(null);
                    annFill.setColor((col & 0x00FFFFFF) | 0x50000000);
                } else {
                    annFill.setXfermode(ReaderActivity.MARKER_XFER);
                    annFill.setColor(col);
                }
                canvas.drawPath(e.getValue(), annFill);
            }
            annFill.setXfermode(null);
            annFill.setAntiAlias(true);
        }
        if (markerPaths != null && !markerPaths.isEmpty()) {
            annLine.setStyle(Paint.Style.STROKE);
            annLine.setStrokeJoin(Paint.Join.ROUND);
            annLine.setStrokeCap(Paint.Cap.ROUND);
            annLine.setAntiAlias(true);
            for (java.util.Map.Entry<Long, Path> e : markerPaths.entrySet()) {
                int[] spec = markerSpec.get(e.getKey());
                if (spec == null) continue;
                annLine.setStrokeWidth(strokeScreenW(spec[1] / 10f));
                if (host.night) {
                    annLine.setXfermode(null);
                    annLine.setColor((spec[0] & 0x00FFFFFF) | 0x50000000);
                } else {
                    annLine.setXfermode(ReaderActivity.MARKER_XFER);
                    annLine.setColor(spec[0]);
                }
                canvas.drawPath(e.getValue(), annLine);
            }
            annLine.setXfermode(null);
        }

        /* ---------- پاس ۲: بقیهٔ حاشیه‌نویسی‌ها ---------- */
        for (int i = 0; i < host.anns.items.size(); i++) {
            Annotations.Ann a = host.anns.items.get(i);
            if (a.page < 0 || a.page >= host.pageCount) continue;
            /* ماژیک‌ها در پاس ۱ کشیده شدند */
            if (a.type == Annotations.T_HL || (a.type == Annotations.T_STROKE && a.marker)) continue;
            RectF pr = host.pageRect(a.page);
            if (pr.width() <= 1f || pr.height() <= 1f) continue;
            if (pr.right < -pad || pr.left > cw + pad || pr.bottom < -pad || pr.top > ch + pad) continue;
            try {
                if (Annotations.isTextAttached(a.type)) {
                    if (a.w0 < 0 && a.px != null && a.px.length >= 4) {
                        /* v1.50: زیرخط/خط‌خورده/موج‌دارِ واردشده — از quadها */
                        drawImportedWordAnn(canvas, pr, a);
                    } else {
                        ReaderActivity.SelPage sp = host.selPages.get(a.page);
                        if (sp == null || sp.words.isEmpty()) {
                            maybeRequestAnnWords(a.page);
                            continue;
                        }
                        drawWordAnn(canvas, pr, sp, a);
                    }
                } else if (a.type == Annotations.T_FNOTE) {
                    drawNoteIcon(canvas, pr.left + (a.x * pr.width()), pr.top + (a.y * pr.height()), a);
                } else if (a.type == Annotations.T_STROKE) {
                    drawStrokeAnn(canvas, pr, a);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /* درخواست خاموش واژه‌های صفحه‌ای که حاشیه‌نویسی متنی دارد */

    void maybeRequestAnnWords(int page) {
        long now = System.currentTimeMillis();
        Long last = annWordReq.get(page);
        if (last != null && now - last < 8000) return;
        annWordReq.put(page, now);
        try {
            host.requestSelWords(page, true);
        } catch (Throwable ignored) {
        }
    }

    /* v1.40: جمع‌آوری نوارهای هایلایت یک حاشیه‌نویسی داخل «مسیرِ» رنگ —
       واژه‌های هم‌خط در یک نوار پیوسته ادغام می‌شوند، نوار کل ارتفاع خط
       + ۱۲٪ بالای/پایین را می‌گیرد (اندازهٔ استاندارد اپ‌های بزرگ) */

    void collectHlBands(RectF pr, ReaderActivity.SelPage sp, Annotations.Ann a, Path out) {
        float kw = pr.width() / sp.vw;
        float kh = pr.height() / sp.vh;
        int from = Math.max(0, Math.min(a.w0, a.w1));
        int to = Math.min(sp.words.size() - 1, Math.max(a.w0, a.w1));
        if (from > to) return;
        int curLine = Integer.MIN_VALUE;
        float minX = 0f, maxX = 0f, top = 0f, bot = 0f;
        for (int wi = from; wi <= to; wi++) {
            ReaderActivity.SelWord w = sp.words.get(wi);
            if (w.line != curLine) {
                if (curLine != Integer.MIN_VALUE) {
                    addHlBand(out, minX, maxX, top, bot);
                }
                curLine = w.line;
                minX = Float.MAX_VALUE;
                maxX = -Float.MAX_VALUE;
                top = Float.MAX_VALUE;
                bot = -Float.MAX_VALUE;
            }
            float wx0 = pr.left + (w.x0 * kw);
            float wx1 = pr.left + (w.x1 * kw);
            float wy0 = pr.top + (w.y0 * kh);
            float wy1 = pr.top + (w.y1 * kh);
            if (wx0 < minX) minX = wx0;
            if (wx1 > maxX) maxX = wx1;
            if (wy0 < top) top = wy0;
            if (wy1 > bot) bot = wy1;
        }
        if (curLine != Integer.MIN_VALUE) {
            addHlBand(out, minX, maxX, top, bot);
        }
    }

    /* v1.40: ساخت مسیر یک خط دستی (نرمال‌شده → مختصات نمایش) داخل مسیر
       مشترکِ همان (رنگ، ضخامت) — برای ماژیک‌های پاس ۱ */

    void buildStrokePath(RectF pr, Annotations.Ann a, Path out) {
        if (a.px == null || a.px.length < 2) return;
        if (a.arrow && a.px.length == 2) {
            out.moveTo(pr.left + (a.px[0] * pr.width()), pr.top + (a.py[0] * pr.height()));
            out.lineTo(pr.left + (a.px[1] * pr.width()), pr.top + (a.py[1] * pr.height()));
            return;
        }
        out.moveTo(pr.left + (a.px[0] * pr.width()), pr.top + (a.py[0] * pr.height()));
        for (int k = 1; k < a.px.length; k++) {
            out.lineTo(pr.left + (a.px[k] * pr.width()), pr.top + (a.py[k] * pr.height()));
        }
    }

    /* رسم یک حاشیه‌نویسی متنی روی بازهٔ واژه‌ها
       v1.40: هایلایت (T_HL) در پاس ۱ کشیده می‌شود — اینجا فقط انواع
       خطی (زیرخط/خط‌خورده/موج‌دار/یادداشت متنی) */

    void drawWordAnn(Canvas canvas, RectF pr, ReaderActivity.SelPage sp, Annotations.Ann a) {
        float kw = pr.width() / sp.vw;
        float kh = pr.height() / sp.vh;
        int from = Math.max(0, Math.min(a.w0, a.w1));
        int to = Math.min(sp.words.size() - 1, Math.max(a.w0, a.w1));
        if (from > to) return;
        float x0 = 0f, y0 = 0f, x1 = 0f, y1 = 0f;
        switch (a.type) {
            case Annotations.T_UL:
            case Annotations.T_NOTE:
                /* یادداشت متنی هم یک زیرخط رنگی روی بازه دارد تا معلوم
                   باشد به چه متنی وصل است */
                annLine.setStyle(Paint.Style.STROKE);
                annLine.setStrokeWidth(Math.max(2f, (float) host.dp(2)));
                annLine.setColor(a.color | 0xFF000000);
                for (int wi = from; wi <= to; wi++) {
                    ReaderActivity.SelWord w = sp.words.get(wi);
                    x0 = pr.left + (w.x0 * kw);
                    y1 = pr.top + (w.y1 * kh);
                    x1 = pr.left + (w.x1 * kw);
                    canvas.drawLine(x0, y1 + host.dp(1), x1, y1 + host.dp(1), annLine);
                }
                break;
            case Annotations.T_ST:
                annLine.setStyle(Paint.Style.STROKE);
                annLine.setStrokeWidth(Math.max(2f, (float) host.dp(2)));
                annLine.setColor(a.color | 0xFF000000);
                for (int wi = from; wi <= to; wi++) {
                    ReaderActivity.SelWord w = sp.words.get(wi);
                    x0 = pr.left + (w.x0 * kw);
                    y0 = pr.top + (w.y0 * kh);
                    x1 = pr.left + (w.x1 * kw);
                    y1 = pr.top + (w.y1 * kh);
                    canvas.drawLine(x0, (y0 + y1) / 2f, x1, (y0 + y1) / 2f, annLine);
                }
                break;
            case Annotations.T_SQ:
                /* زیرخط موج‌دار */
                annLine.setStyle(Paint.Style.STROKE);
                annLine.setStrokeWidth(Math.max(1.6f, (float) host.dp(1)));
                annLine.setColor(a.color | 0xFF000000);
                float wave = (float) host.dp(2);
                float half = (float) host.dp(3);
                for (int wi = from; wi <= to; wi++) {
                    ReaderActivity.SelWord w = sp.words.get(wi);
                    x0 = pr.left + (w.x0 * kw);
                    y1 = pr.top + (w.y1 * kh);
                    x1 = pr.left + (w.x1 * kw);
                    if (x1 - x0 < host.dp(2)) continue;
                    annPath.reset();
                    annPath.moveTo(x0, y1);
                    float x = x0;
                    boolean up = true;
                    while (x < x1) {
                        float nx = Math.min(x + half, x1);
                        annPath.quadTo((x + nx) / 2f, y1 + (up ? wave : -wave), nx, y1);
                        up = !up;
                        x = nx;
                    }
                    canvas.drawPath(annPath, annLine);
                }
                break;
        }
        /* آیکون یادداشت متنی: دایره کوچک کنار اولین واژه */
        if (a.type == Annotations.T_NOTE) {
            ReaderActivity.SelWord w0 = sp.words.get(from);
            float ix = pr.left + (w0.x0 * kw);
            float iy = pr.top + (((w0.y0 + w0.y1) / 2f) * kh);
            drawNoteIcon(canvas, ix, iy, a);
        }
    }

    /* v1.50: رسم حاشیه‌نویسی متنیِ واردشده (از annotation استاندارد) —
       quadها در px/py چهارتایی (x0,y0,x1,y1) نرمال‌شده هستند */

    void drawImportedWordAnn(Canvas canvas, RectF pr, Annotations.Ann a) {
        if (a.px == null || a.py == null || a.px.length < 4) return;
        annLine.setStyle(Paint.Style.STROKE);
        annLine.setStrokeWidth(Math.max(2f, (float) host.dp(2)));
        annLine.setColor(a.color | 0xFF000000);
        for (int q = 0; q + 3 < a.px.length && q + 3 < a.py.length; q += 4) {
            float x0 = pr.left + (a.px[q] * pr.width());
            float y0 = pr.top + (a.py[q] * pr.height());
            float x1 = pr.left + (a.px[q + 2] * pr.width());
            float y1 = pr.top + (a.py[q + 2] * pr.height());
            if (x1 <= x0) continue;
            switch (a.type) {
                case Annotations.T_UL:
                case Annotations.T_NOTE:
                    canvas.drawLine(x0, y1 + host.dp(1), x1, y1 + host.dp(1), annLine);
                    break;
                case Annotations.T_ST:
                    canvas.drawLine(x0, (y0 + y1) / 2f, x1, (y0 + y1) / 2f, annLine);
                    break;
                case Annotations.T_SQ: {
                    float wave = (float) host.dp(2);
                    float half = (float) host.dp(3);
                    if (x1 - x0 < host.dp(2)) break;
                    annPath.reset();
                    annPath.moveTo(x0, y1);
                    float x = x0;
                    boolean up = true;
                    while (x < x1) {
                        float nx = Math.min(x + half, x1);
                        annPath.quadTo((x + nx) / 2f, y1 + (up ? wave : -wave), nx, y1);
                        up = !up;
                        x = nx;
                    }
                    canvas.drawPath(annPath, annLine);
                    break;
                }
                default:
                    break;
            }
        }
    }

    /* آیکون یادداشت — دایره رنگی با علامت ! */

        void drawNoteIcon(Canvas canvas, float cx, float cy, Annotations.Ann a) {
        /* v1.33: \u0622\u06cc\u06a9\u0648\u0646 \u0645\u062f\u0631\u0646 \u0633\u062a\u06cc\u06a9\u06cc \u2014 \u0645\u0631\u0628\u0639 \u06af\u0631\u062f \u0628\u0627 \u06af\u0648\u0634\u0647 \u0648 \u0633\u0627\u06cc\u0647\u0654 \u062f\u0641\u062a\u0631 \u0645\u062f \u062f\u0627\u0631 */
        float s = host.dp(11);
        float l = cx - s, t = cy - s * 0.92f, r = cx + s, b = cy + s * 0.92f;
        float fold = host.dp(5);
        annPath.reset();
        annPath.moveTo(l, t);
        annPath.lineTo(r - fold, t);
        annPath.lineTo(r, t + fold);
        annPath.lineTo(r, b);
        annPath.lineTo(l, b);
        annPath.close();
        annNote.setStyle(Paint.Style.FILL);
        annNote.setAntiAlias(true);
        annNote.setColor((a.color & 0x00FFFFFF) | 0xF2000000);
        canvas.drawPath(annPath, annNote);
        /* \u0644\u0648\u0627\u06cc \u0645\u062f */
        annNote.setStyle(Paint.Style.STROKE);
        annNote.setStrokeWidth(Math.max(1f, host.dp(1)));
        annNote.setColor(0x66FFFFFF);
        annPath.reset();
        annPath.moveTo(l, b - host.dp(6));
        annPath.lineTo(r, b - host.dp(6));
        annPath.moveTo(l, b - host.dp(10));
        annPath.lineTo(r - host.dp(4), b - host.dp(10));
        canvas.drawPath(annPath, annNote);
        /* \u062d\u0644\u0642\u0647\u0654 \u0647\u0627\u0644\u0627 \u0647\u0646\u0648\u0632 \u062f\u06cc\u062f\u0647 \u0646\u0634\u062f\u0647 */
        annNote.setStyle(Paint.Style.STROKE);
        annNote.setStrokeWidth(Math.max(1.2f, host.dp(1)));
        annNote.setColor(0xFFFFFFFF);
        annPath.reset();
        annPath.moveTo(r - fold, t);
        annPath.lineTo(r - fold, t + fold);
        annPath.lineTo(r, t + fold);
        canvas.drawPath(annPath, annNote);
    }

    /* رسم یک خط دستی (نرمال‌شده → مختصات نمایش)
       v1.38: خط‌های ماژیک با بلند مولتی‌پلای زیر متن می‌نشینند — مثل
       ماژیک واقعی؛ متن زیر خط ماژیک خوانا می‌ماند و ماژیک روی ماژیک
       رنگ را تیره نمی‌کند */

    void drawStrokeAnn(Canvas canvas, RectF pr, Annotations.Ann a) {
        if (a.px == null || a.px.length < 2) return;
        /* v1.40: ماژیک‌ها در پاس ۱ (مسیر مشترک هم‌رنگ) کشیده می‌شوند؛
           اینجا فقط قلم و فلش — رنگ کامل، بدون مولتی‌پلای */
        annLine.setStyle(Paint.Style.STROKE);
        annLine.setStrokeJoin(Paint.Join.ROUND);
        annLine.setStrokeCap(Paint.Cap.ROUND);
        annLine.setAntiAlias(true);
        annLine.setStrokeWidth(strokeScreenW(a.strokeW));
        annLine.setColor(a.color | 0xFF000000);
        annLine.setXfermode(null);
        try {
            if (a.arrow && a.px.length == 2) {
                float x0 = pr.left + (a.px[0] * pr.width()), y0 = pr.top + (a.py[0] * pr.height());
                float x1 = pr.left + (a.px[1] * pr.width()), y1 = pr.top + (a.py[1] * pr.height());
                canvas.drawLine(x0, y0, x1, y1, annLine);
                drawArrowHead(canvas, x0, y0, x1, y1, annLine);
            } else {
                annPath.reset();
                annPath.moveTo(pr.left + (a.px[0] * pr.width()), pr.top + (a.py[0] * pr.height()));
                for (int k = 1; k < a.px.length; k++) {
                    annPath.lineTo(pr.left + (a.px[k] * pr.width()), pr.top + (a.py[k] * pr.height()));
                }
                canvas.drawPath(annPath, annLine);
            }
        } finally {
            annLine.setXfermode(null);
        }
    }

    void drawArrowHead(Canvas canvas, float x0, float y0, float x1, float y1, Paint p) {
        double ang = Math.atan2(y1 - y0, x1 - x0);
        float len = Math.max(host.dp(9), p.getStrokeWidth() * 3.2f);
        for (int s = -1; s <= 1; s += 2) {
            double a2 = ang + (s * 0.42);
            canvas.drawLine(x1, y1, (float) (x1 - (len * Math.cos(a2))), (float) (y1 - (len * Math.sin(a2))), p);
        }
    }

    /* ================== لمس روی حاشیه‌نویسی ================== */

    /* اگر لمس روی حاشیه‌نویسی‌ای بود، پنجرهٔ عملیاتش را باز کن */

    boolean tapAnnotation(float vx, float vy) {
        if (host.anns == null || host.anns.items.isEmpty() || host.pageCount <= 0) return false;
        int page = host.pageAt(vx, vy);
        if (page < 0) return false;
        RectF pr = host.pageRect(page);
        if (pr.width() <= 1f) return false;
        float touch = host.dp(22);
        /* ۱) یادداشت آزاد */
        for (int i = host.anns.items.size() - 1; i >= 0; i--) {
            Annotations.Ann a = host.anns.items.get(i);
            if (a.type != Annotations.T_FNOTE || a.page != page) continue;
            float ax = pr.left + (a.x * pr.width());
            float ay = pr.top + (a.y * pr.height());
            if (Math.abs(vx - ax) <= touch && Math.abs(vy - ay) <= touch) {
                host.annDialogs.noteEditor(a, true);
                return true;
            }
        }
        /* ۲) خط دستی — با فاصلهٔ نقطه تا پاره‌خط */
        for (int i = host.anns.items.size() - 1; i >= 0; i--) {
            Annotations.Ann a = host.anns.items.get(i);
            if (a.type != Annotations.T_STROKE || a.page != page || a.px == null) continue;
            float thr = Math.max(touch, strokeScreenW(a.strokeW) * 0.5f + host.dp(10));
            for (int k = 1; k < a.px.length; k++) {
                float sx0 = pr.left + (a.px[k - 1] * pr.width());
                float sy0 = pr.top + (a.py[k - 1] * pr.height());
                float sx1 = pr.left + (a.px[k] * pr.width());
                float sy1 = pr.top + (a.py[k] * pr.height());
                if (ReaderActivity.ptSegDist(vx, vy, sx0, sy0, sx1, sy1) <= thr) {
                    host.annDialogs.annActionDialog(a);
                    return true;
                }
            }
        }
        /* ۳) حاشیه‌نویسی متنی — از روی واژهٔ زیر انگشت */
        boolean pageHasTextAnn = false;
        for (int i = 0; i < host.anns.items.size(); i++) {
            Annotations.Ann a = host.anns.items.get(i);
            if (a.page == page && Annotations.isTextAttached(a.type)) { pageHasTextAnn = true; break; }
        }
        if (!pageHasTextAnn) return false;
        ReaderActivity.SelPage sp = host.selPages.get(page);
        if (sp == null || sp.words.isEmpty()) {
            /* واژه‌ها هنوز آماده نیستند — آماده کن تا لمس بعدی کار کند */
            host.requestSelWords(page, false);
            return false;
        }
        int wi = host.wordAt(sp, ((vx - pr.left) * sp.vw) / pr.width(), ((vy - pr.top) * sp.vh) / pr.height());
        if (wi < 0) return false;
        for (int i = host.anns.items.size() - 1; i >= 0; i--) {
            Annotations.Ann a = host.anns.items.get(i);
            if (a.page != page || !Annotations.isTextAttached(a.type)) continue;
            int lo = Math.min(a.w0, a.w1), hi = Math.max(a.w0, a.w1);
            if (wi >= lo && wi <= hi) {
                if (a.type == Annotations.T_NOTE) host.annDialogs.noteEditor(a, true);
                else host.annDialogs.annActionDialog(a);
                return true;
            }
        }
        return false;
    }
}
