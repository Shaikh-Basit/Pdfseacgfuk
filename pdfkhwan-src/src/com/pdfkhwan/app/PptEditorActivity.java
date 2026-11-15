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

/* ============================================================================
   PptEditorActivity v3 (v1.18) — MOBILE PowerPoint layout (smartphone UI).

   v2 copied the DESKTOP Office layout (side slide rail, always-open ribbon).
   v3 follows PowerPoint for phones as the user asked:

     ┌ bar 56dp: [ppt] name … ذخیره ⟲ ⟳ ▶ ⋮   (big 46dp+ touch targets) ┐
     │ tabs 44dp: خانه │ درج │ طراحی              ← no نمایش tab
     │ ribbon: HIDDEN until a tab is tapped; tapping it again hides it    │
     │ ┌──────── big full-width canvas ────────┐                          │
     │ └───────────────────────────────────────┘                          │
     │ status 34dp: اسلاید ۳/۱۲ · − ۱۰۰٪ + ⤢                              │
     └ filmstrip 96dp: [۴][۳][۲][۱][＋]  ← BOTTOM, RTL (slide 1 right)    ┘

   ▶ starts a LANDSCAPE slideshow: right half of the screen = next slide,
   left half = previous (PowerPoint's own tap convention); a dialog asks
   «از ابتدا / از همین اسلاید» when not on slide 1; at the last slide,
   next announces the end and exits.
   Tapping a filmstrip thumbnail jumps the big canvas to that slide.
   ============================================================================ */
public class PptEditorActivity extends Activity {
    /* v1.51: خوشهٔ جدا‌شده — PptRibbons */
    private final PptRibbons ribbons = new PptRibbons(this);
    final PptTextDialogs textDialogs = new PptTextDialogs(this);
    final PptShow show = new PptShow(this);
    private final PptInsert insert = new PptInsert(this);
    private final PptSaveIo saveIo = new PptSaveIo(this);
    static final int OFFICE_TAB_ACTIVE = 0xFFB7472A;
    static final int OFFICE_TAB_IDLE = 0xFF6B6B6B;
    static final int OFFICE_RIBBON_BG = 0xFFF3F2F1;
    static final int OFFICE_RIBBON_FG = 0xFF3B3B3B;
    static final int OFFICE_TITLE_BG = 0xFFB7472A;
    static final int OFFICE_CANVAS_BG = 0xFF444444;
    static final int OFFICE_SEL = 0xFF2F7DD1;

    /* ============================ design themes ============================= */
    static final String[] THEME_NAMES = {
            "روشن کلاسیک", "آبی شرکتی", "شب", "گرافیتی", "سبز آرام",
            "بنفش مدرن", "غروب", "فیروزه‌ای", "شرابی", "طلایی مات",
            "آبی متالیک", "نسکافه‌ای", "مرجانی", "زرشکی", "یخی",
            "مشکی طلایی", "صحرایی", "اخگر", "یشمی", "بنفش تیره",
            "روشن گرافیت", "آبی شب", "کرم پاییزی", "گل‌رنگ"};
    static final int[][] THEME_COLS = {
            {0xFFFFFFFF, 0xFFF4F7FB, 0xFF1F3864, 0xFF404040, 0xFF2E75B6},
            {0xFFFFFFFF, 0xFFCFE2F7, 0xFF1F3864, 0xFF33475B, 0xFF2E75B6},
            {0xFF0F1A2B, 0xFF0B1322, 0xFFFFFFFF, 0xFFC9D6E8, 0xFF4FC3F7},
            {0xFF2B2B2B, 0xFF171717, 0xFFF5F5F5, 0xFFCFCFCF, 0xFFFF8A3D},
            {0xFFF1F8F4, 0xFFCDE9DC, 0xFF1E5B3A, 0xFF335444, 0xFF2E9E63},
            {0xFF4A2D7A, 0xFF8E5BD9, 0xFFFFFFFF, 0xFFF0E7FB, 0xFFF2C14E},
            {0xFFFFD9A0, 0xFFFF7E5F, 0xFF4A150C, 0xFF5E2A18, 0xFFD9411E},
            {0xFFE6F7F7, 0xFFB5E6E6, 0xFF0B4F4F, 0xFF2E5F5F, 0xFF17A2A2},
            {0xFF5C1A2E, 0xFF8C2F4F, 0xFFFFFFFF, 0xFFF2D9E0, 0xFFE8B4C8},
            {0xFF3A3226, 0xFF6B5D3E, 0xFFFFFFFF, 0xFFEFE6D5, 0xFFD4AF37},
            {0xFF1E3A5F, 0xFF152A47, 0xFFFFFFFF, 0xFFB0C4DE, 0xFF4A90E2},
            {0xFF4A3525, 0xFF2E1F14, 0xFFF5E6D3, 0xFFD4B896, 0xFF8B5A2B},
            {0xFFFF6B6B, 0xFFEE5A6F, 0xFFFFFFFF, 0xFFFFE5E5, 0xFFFFA505},
            {0xFFB71C1C, 0xFF7F0000, 0xFFFFFFFF, 0xFFFFCDD2, 0xFFFFEB3B},
            {0xFFE1F5FE, 0xFFB3E5FC, 0xFF01579B, 0xFF0277BD, 0xFF00ACC1},
            {0xFF000000, 0xFF1A1A1A, 0xFFFFD700, 0xFFBBBBBB, 0xFFFFC107},
            {0xFFD7CCC8, 0xFF8D6E63, 0xFF3E2723, 0xFF4E342E, 0xFFE64A19},
            {0xFFFF5722, 0xFFBF360C, 0xFFFFEB3B, 0xFFFFE0B2, 0xFFFFC107},
            {0xFF004D40, 0xFF00251A, 0xFFB2DFDB, 0xFF80CBC4, 0xFFA7FFEB},
            {0xFF311B92, 0xFF1A0A6B, 0xFFE1BEE7, 0xFFD1C4E9, 0xFFFFEB3B},
            {0xFFF5F5F5, 0xFFE0E0E0, 0xFF212121, 0xFF424242, 0xFF9E9E9E},
            {0xFF0D47A1, 0xFF002171, 0xFFBBDEFB, 0xFF90CAF9, 0xFF82B1FF},
            {0xFFFFF8E1, 0xFFFFECB3, 0xFF5D4037, 0xFF6D4C41, 0xFFFFA000},
            {0xFFF8BBD0, 0xFFF48FB1, 0xFF880E4F, 0xFFAD1457, 0xFFEC407A}};

    static final int[] GALLERY_TYPES = {6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17};
    static final String[] GALLERY_NAMES = {
            "مثلث", "پیکان", "ستاره", "لوزی", "پنج‌ضلعی", "شش‌ضلعی",
            "متوازی‌الاضلاع", "قلب", "ابر", "آذرخش", "بعلاوه", "شورون"};

    private static final int REQ_IMG = 41;
    private static final int REQ_SAVE_AS = 42;
    private static final int REQ_VID = 43;   /* v1.22: insert video/audio */

    /* ============================== state ================================== */
    PptDoc doc = new PptDoc();
    private final ArrayDeque<PptDoc> undoStack = new ArrayDeque<>();
    private final ArrayDeque<PptDoc> redoStack = new ArrayDeque<>();
    int cur = 0;
    PptDoc.Shape sel;
    String docName = "ارائه جدید";
    Uri fileUri = null;

    /* UI */
    PptCanvas canvas;
    FilmStrip filmstrip;
    TextView statusZoom;
    TextView titleView;
    LinearLayout ribbon;
    LinearLayout groupHost;
    HorizontalScrollView ribbonScroller;
    private float zoom = 1.0f;
    FrameLayout slideshowLayer;
    SlideShowView showView;
    AlertDialog dlg;
    /* v1.23: حالت برش تصویر (تب قالب‌بندی ← جلوه‌ها ← برش تصویر) */
    private boolean cropMode = false;
    private float crop0L, crop0R, crop0T, crop0B;   /* برای لغو */
    private boolean cropUndoPushed = false;        /* v1.29: crop now undoable */
    private int cropHandle = -1;

    /* fonts preloaded from assets */
    private static final HashMap<String, Typeface> FONT_CACHE = new HashMap<>();

    /* ============================== lifecycle ============================== */
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            CrashGuard.install(this);
            preloadFonts();
            ribbons.buildUi();
            Intent it = getIntent();
            String uriS = it.getStringExtra("uri");
            docName = it.getStringExtra("name");
            if (docName == null || docName.length() == 0) docName = "ارائه جدید";
            if (uriS != null) {
                fileUri = Uri.parse(uriS);
                loadDoc();
            } else {
                newDoc();
            }
        } catch (Throwable t) {
            CrashGuard.append(this, "ppt onCreate: " + CrashGuard.stackToString(t));
            Toast.makeText(this, "خطای غیرمنتظره", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private void preloadFonts() {
        String[][] fonts = {
                {"vazirmatn", "fonts/Vazirmatn-Regular.ttf"},
                {"estedad", "fonts/Estedad-Regular.ttf"},
                {"parastoo", "fonts/Parastoo-Regular.ttf"},
                {"baloob", "fonts/BalooB-Regular.ttf"},
                {"noto naskh arabic", "fonts/NotoNaskhArabic-Regular.ttf"},
                {"noto sans arabic", "fonts/NotoSansArabic-Regular.ttf"},
        };
        for (String[] f : fonts) {
            try {
                FONT_CACHE.put(f[0], Typeface.createFromAsset(getAssets(), f[1]));
            } catch (Throwable ignored) {
            }
        }
    }

    static Typeface typefaceForName(String name) {
        if (name == null || name.length() == 0) return Typeface.DEFAULT;
        Typeface tf = FONT_CACHE.get(name.toLowerCase());
        return tf != null ? tf : Typeface.DEFAULT;
    }

    private void newDoc() {
        doc = new PptDoc();
        PptDoc.Slide s = doc.newSlide();
        s.shapes.add(new PptDoc.TextShape("عنوان را اینجا بنویس",
                doc.slideW * 10 / 100, doc.slideH * 10 / 100,
                doc.slideW * 80 / 100, doc.slideH * 15 / 100,
                36f, 0xFF1F3864, true, true));
        s.shapes.add(new PptDoc.TextShape("متن اسلاید…",
                doc.slideW * 10 / 100, doc.slideH * 34 / 100,
                doc.slideW * 80 / 100, doc.slideH * 50 / 100,
                20f, 0xFF404040, false, true));
        refreshAll();
    }

    private void loadDoc() {
        try {
            /* v1.22: legacy PowerPoint 97-2003 (.ppt) is an OLE compound
               file, not a zip — detect the magic and explain instead of the
               cryptic "presentation.xml پیدا نشد" error */
            {
                InputStream pin = getContentResolver().openInputStream(fileUri);
                byte[] magic = new byte[8];
                int n = 0;
                try {
                    while (n < 8) {
                        int r = pin.read(magic, n, 8 - n);
                        if (r < 0) break;
                        n += r;
                    }
                } finally {
                    closeQuietly(pin);
                }
                boolean ole = n == 8
                        && (magic[0] & 255) == 0xD0 && (magic[1] & 255) == 0xCF
                        && (magic[2] & 255) == 0x11 && (magic[3] & 255) == 0xE0
                        && (magic[4] & 255) == 0xA1 && (magic[5] & 255) == 0xB1
                        && (magic[6] & 255) == 0x1A && (magic[7] & 255) == 0xE1;
                if (ole) {
                    throw new Exception("این فایل PowerPoint قدیمی (قالب ۱۹۹۷-۲۰۰۳) است.\n"
                            + "در PowerPoint یا WPS آن را با «Save As / ذخیره با نام» به قالب .pptx تبدیل کن، "
                            + "بعد همین‌جا بازش کن. (اپ برای حفظ اندازه آفلاین، فقط قالب مدرن pptx را می‌خواند)");
                }
            }
            /* v1.29 FIX: PptxReader.read is a synchronous zip+XML parse — on
               decks with embedded media it froze the UI thread for seconds
               (ANR risk). The parse now runs on a loader thread; the doc
               assignment + first render hop back to the UI thread. */
            final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
            new Thread(new Runnable() {
                @Override public void run() {
                    PptDoc loaded;
                    try {
                        InputStream in = getContentResolver().openInputStream(fileUri);
                        try {
                            loaded = PptxReader.read(in);
                        } finally {
                            closeQuietly(in);
                        }
                    } catch (final Throwable t) {
                        ui.post(new Runnable() {
                            @Override public void run() { showLoadError(t); }
                        });
                        return;
                    }
                    final PptDoc ld = loaded;
                    ui.post(new Runnable() {
                        @Override public void run() {
                            if (isFinishing() || isDestroyed()) return;
                            doc = ld;
                            if (docName == null || docName.length() == 0 || "ارائه جدید".equals(docName)) {
                                docName = queryDisplayName(fileUri);
                            }
                            cur = 0;
                            sel = null;
                            refreshAll();
                            Toast.makeText(PptEditorActivity.this, "باز شد: " + Fa.d(doc.slides.size()) + " اسلاید", Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }, "ppt-load").start();
        } catch (final Throwable t) {
            showLoadError(t);
        }
    }

    private void showLoadError(final Throwable t) {
        if (isFinishing() || isDestroyed()) return;
        CrashGuard.append(this, "ppt load: " + CrashGuard.stackToString(t));
        new AlertDialog.Builder(this)
                .setTitle("باز کردن ممکن نشد")
                .setMessage("این فایل پاورپوینت خوانده نشد.\nجزئیات: " + t.getMessage())
                .setPositiveButton("ساخت ارائه جدید", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        newDoc();
                    }
                })
                .setNegativeButton("بستن", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        finish();
                    }
                })
                .show();
    }

    private String queryDisplayName(Uri uri) {
        try {
            android.database.Cursor c = getContentResolver().query(uri, null, null, null, null);
            if (c != null) {
                try {
                    int i = c.getColumnIndex("_display_name");
                    if (i >= 0 && c.moveToFirst()) {
                        String n = c.getString(i);
                        if (n != null && n.length() > 0) return n;
                    }
                } finally {
                    c.close();
                }
            }
        } catch (Throwable ignored) {
        }
        String p = uri.getLastPathSegment();
        return p == null ? "ارائه.pptx" : p;
    }

    /* ============================== UI shell =============================== */
    int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    TextView tabHome;
    LinearLayout filmHost;
    View filmHandle;
    FrameLayout inlineLayer;
    private EditText inlineEdit;
    /* v1.29: the shape the inline editor was opened for — commitInlineEditor
       binds to THIS, not the live `sel` (a tap through the old non-clickable
       scrim used to reassign `sel` mid-edit and the typed text was committed
       to the WRONG shape). */
    private PptDoc.Shape inlineSel;
    private boolean filmCollapsed = false;

    View filmHandleBar() {
        TextView h = new TextView(this);
        h.setText("▬▬▬");
        h.setTextColor(0xFFB9B9B9);
        h.setTextSize(10f);
        h.setGravity(Gravity.CENTER);
        h.setBackgroundColor(0xFF2E2E2E);
        h.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                filmCollapsed = !filmCollapsed;
                filmstrip.setVisibility(filmCollapsed ? View.GONE : View.VISIBLE);
                h.setText(filmCollapsed ? "▾ اسلایدها" : "▬▬▬");
                h.setTextColor(filmCollapsed ? 0xFFE8E8E8 : 0xFFB9B9B9);
            }
        });
        return h;
    }

    /* ▾ collapse chevron at the end of the bottom tab row — closes the
       ribbon entirely (one tap, Office mobile behavior) */
    void showSlideSorter() {
        if (dlg != null) { textDialogs.dlgDismiss(); }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF3C3C3C);
        TextView head = new TextView(this);
        head.setText("نمایش اسلایدها — " + Fa.d(doc.slides.size()) + " اسلاید");
        head.setTextColor(0xFFFFFFFF);
        head.setTextSize(15f);
        head.setTypeface(null, Typeface.BOLD);
        head.setGravity(Gravity.CENTER);
        head.setPadding(dp(12), dp(14), dp(12), dp(10));
        root.addView(head, new LinearLayout.LayoutParams(-1, -2));

        GridLayoutLike grid = new GridLayoutLike(this, 3);
        ScrollView sc = new ScrollView(this);
        sc.addView(grid, new ViewGroup.LayoutParams(-1, -2));
        root.addView(sc, new LinearLayout.LayoutParams(-1, 0, 1f));
        rebuildSorterGrid(grid);

        dlg = new AlertDialog.Builder(this)
                .setView(root)
                .setPositiveButton("بستن", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { textDialogs.dlgDismiss(); }
                })
                .create();
        dlg.show();
        android.view.Window win = dlg.getWindow();
        if (win != null) win.setLayout(-1, -1);
    }

    private void rebuildSorterGrid(final GridLayoutLike grid) {
        grid.removeAllViews();
        for (int i = 0; i < doc.slides.size(); i++) {
            final int idx = i;
            FrameLayout cell = new FrameLayout(this);
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER_HORIZONTAL);
            SlideThumb th = new SlideThumb(this, i);
            if (i == cur) {
                GradientDrawable g = new GradientDrawable();
                g.setCornerRadius(dp(4));
                g.setColor(0x44B7472A);
                th.setBackground(g);
            }
            th.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    gotoSlide(idx);
                    textDialogs.dlgDismiss();
                }
            });
            th.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    slideCtxtMenu(idx);
                    return true;
                }
            });
            box.addView(th, new LinearLayout.LayoutParams(-2, -2));
            TextView num = new TextView(this);
            num.setText(Fa.d(i + 1));
            num.setTextColor(i == cur ? 0xFFFFB29A : 0xFFDDDDDD);
            num.setTextSize(11f);
            num.setGravity(Gravity.CENTER);
            num.setPadding(0, dp(3), 0, dp(6));
            box.addView(num, new LinearLayout.LayoutParams(-1, -2));
            cell.addView(box, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
            grid.addView(cell);
        }
    }

    private void slideCtxtMenu(final int idx) {
        String[] items = { "باز کردن", "تکثیر اسلاید", "حذف اسلاید", "انتقال به ابتدا", "انتقال به انتها" };
        new AlertDialog.Builder(this)
                .setTitle("اسلاید " + Fa.d(idx + 1))
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        if (w == 0) { gotoSlide(idx); textDialogs.dlgDismiss(); }
                        else if (w == 1) {
                            pushUndo();
                            doc.slides.add(idx + 1, doc.slides.get(idx).copy());
                            /* v1.31 FIX: keep the view on the same slide when
                               the duplicate lands BELOW the current one —
                               `cur` used to drift onto the next slide (same
                               class of bug the v1.29 deleteSlide fix closed) */
                            if (idx < cur) cur++;
                            else if (idx == cur) cur = idx + 1;
                            refreshAll();
                        }
                        else if (w == 2) {
                            if (doc.slides.size() > 1) {
                                pushUndo();
                                doc.slides.remove(idx);
                                /* v1.29 FIX: match ribbon deleteSlide() — a
                                   stale `sel` (shape of the removed slide)
                                   left phantom handles on the NEXT slide and
                                   edits silently mutated an orphaned shape;
                                   `cur` must follow deletions ABOVE it too. */
                                if (idx == cur) sel = null;
                                if (idx < cur) cur--;
                                if (cur >= doc.slides.size()) cur = doc.slides.size() - 1;
                                refreshAll();
                            }
                        }
                        else if (w == 3) { pushUndo(); PptDoc.Slide s = doc.slides.remove(idx); doc.slides.add(0, s); cur = 0; refreshAll(); }
                        else if (w == 4) { pushUndo(); PptDoc.Slide s = doc.slides.remove(idx); doc.slides.add(s); cur = doc.slides.size() - 1; refreshAll(); }
                        /* refresh the sorter if it is still open */
                        if (dlg != null && dlg.isShowing()) {
                            View v = dlg.getWindow() != null ? dlg.getWindow().getDecorView() : null;
                            if (v instanceof ViewGroup) {
                                ViewGroup vg = (ViewGroup) v;
                                ArrayList<GridLayoutLike> found = new ArrayList<>();
                                findSorterGrid(vg, found);
                                if (!found.isEmpty()) rebuildSorterGrid(found.get(0));
                            }
                        }
                    }
                })
                .setNegativeButton("انصراف", (DialogInterface.OnClickListener) null)
                .show();
    }

    private void findSorterGrid(ViewGroup vg, ArrayList<GridLayoutLike> out) {
        for (int i = 0; i < vg.getChildCount(); i++) {
            View c = vg.getChildAt(i);
            if (c instanceof GridLayoutLike) out.add((GridLayoutLike) c);
            if (c instanceof ViewGroup) findSorterGrid((ViewGroup) c, out);
        }
    }

    /* ============================== share ================================== */
    void shareDoc() {
        try {
            if (fileUri != null && "file".equals(fileUri.getScheme())) {
                launchShare(new File(fileUri.getPath()));
                return;
            }
            /* not saved as a plain file: stage to the app's share cache.
               v7 FIX: the staged URI used to OVERWRITE this.fileUri —
               after sharing once, autosave wrote into the throwaway
               cache copy and the user's real file silently stopped
               updating. Use a LOCAL variable only.
               v1.29 FIX: the pptx zip build ran SYNCHRONOUSLY on the UI
               thread (seconds-long freeze / ANR on decks with embedded
               media) — the snapshot now builds on the background save
               executor and the chooser opens when the file is ready. */
            final PptDoc snapshot = cloneDoc();
            final String nm = docName;
            Toast.makeText(this, "در حال آماده‌سازی برای اشتراک…", Toast.LENGTH_SHORT).show();
            saveExec.execute(new Runnable() {
                @Override public void run() {
                    try {
                        File dir = new File(getCacheDir(), "share");
                        dir.mkdirs();
                        final File f = new File(dir, (nm == null ? "ارائه" : nm) + ".pptx");
                        OutputStream out = new FileOutputStream(f);
                        try { PptxWriter.write(out, snapshot, nm); } finally { closeQuietly(out); }
                        runOnUiThread(new Runnable() {
                            @Override public void run() { launchShare(f); }
                        });
                    } catch (final Throwable t) {
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                CrashGuard.append(PptEditorActivity.this, "ppt share: " + CrashGuard.stackToString(t));
                                Toast.makeText(PptEditorActivity.this, "اشتراک ممکن نشد — اول ذخیره کن", Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                }
            });
        } catch (Throwable t) {
            CrashGuard.append(this, "ppt share: " + CrashGuard.stackToString(t));
            Toast.makeText(this, "اشتراک ممکن نشد — اول ذخیره کن", Toast.LENGTH_SHORT).show();
        }
    }

    private void launchShare(File shareFile) {
        try {
            Intent it = new Intent(Intent.ACTION_SEND);
            it.setType("application/vnd.openxmlformats-officedocument.presentationml.presentation");
            Uri put = ShareProvider.put(shareFile);
            it.putExtra(Intent.EXTRA_STREAM, put);
            it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(it, "اشتراک ارائه"));
        } catch (Throwable t) {
            CrashGuard.append(this, "ppt share: " + CrashGuard.stackToString(t));
            Toast.makeText(this, "اشتراک ممکن نشد — اول ذخیره کن", Toast.LENGTH_SHORT).show();
        }
    }

    /* ============================== autosave ================================ */
    /* Office mobile autosaves only REAL edits. Every mutation marks the doc
       dirty (• in the title bar); 2.5s later a quiet background save runs.
       The write is ATOMIC for file:// targets (temp file + fsync + rename),
       so a failed/interrupted write can never corrupt the original file.
       No edit → no write at all: a file merely OPENED stays byte-identical
       (no phantom «modified today» copies in the recent list). */
    final android.os.Handler autoHandler = new android.os.Handler();
    private Runnable autoRun = null;
    boolean dirty = false;        /* doc has unsaved edits */
    boolean saveInFlight = false; /* a background save is running */
    int editGen = 0;              /* bumped on every edit — a save that
                                            completes behind newer edits must
                                            NOT clear the dirty flag */
    final java.util.concurrent.ExecutorService saveExec =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    /* title bar shows • while there are unsaved edits (Office convention) */
    void updateTitle() {
        titleView.setText((dirty ? "• " : "") + (docName == null ? "" : docName));
    }

    void markDirty() {
        dirty = true;
        editGen++;
        /* v1.29 FIX: every real edit invalidates the paragraph-layout cache —
           Run objects are mutated in place, so the identity-keyed cache would
           keep serving STALE formatting (see SlideRenderer.LAYOUT_CACHE). */
        SlideRenderer.invalidateLayoutCache();
        updateTitle();
        scheduleAutosave();
    }

    void scheduleAutosave() {
        if (fileUri == null || !dirty) return;   /* nothing to save until edited */
        if (autoRun != null) autoHandler.removeCallbacks(autoRun);
        autoRun = new Runnable() {
            @Override public void run() { autoRun = null; saveQuietly(); }
        };
        autoHandler.postDelayed(autoRun, 2500);
    }

    void saveQuietly() {
        if (isFinishing() || isDestroyed()) return;
        saveIo.startQuietSave();
    }

    /* one quiet save on the background thread — snapshot the doc first so
       concurrent edits/undo can't interleave into the zip being written.
       v1.29: `force` bypasses the saveInFlight guard so the FINAL onDestroy
       save can queue behind an in-flight autosave (executor is FIFO — the
       newer snapshot always lands last) instead of being silently skipped. */
    static Exception writeFileAtomic(File dst, PptDoc snapshot, String name) {
        File dir = dst.getParentFile();
        if (dir == null) {                 /* no parent — plain direct write */
            try {
                OutputStream out = new FileOutputStream(dst);
                try { PptxWriter.write(out, snapshot, name); } finally { closeQuietly(out); }
                return null;
            } catch (Exception e) { return e; }
        }
        File tmp = new File(dir, dst.getName() + ".pdfkhwan.tmp");
        boolean tmpOk = false;
        try {
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                PptxWriter.write(out, snapshot, name);
                out.flush();
                try { out.getFD().sync(); } catch (Throwable ignored) { }
            } finally { closeQuietly(out); }
            tmpOk = true;
        } catch (Exception e) {
            tmp.delete();
            return e;
        }
        if (tmp.renameTo(dst)) return null;
        /* some OEMs/FUSE mounts refuse rename-over: direct write fallback */
        tmp.delete();
        try {
            OutputStream out = new FileOutputStream(dst);
            try { PptxWriter.write(out, snapshot, name); } finally { closeQuietly(out); }
            return null;
        } catch (Exception e) { return e; }
    }

    @Override
    protected void onDestroy() {
        autoHandler.removeCallbacksAndMessages(null);
        if (showView != null) showView.stopClock();   /* v1.29: stop the clock chain */
        /* v1.29 FIX: the old `!saveInFlight` guard SKIPPED the final save
           whenever an autosave was mid-flight — edits made after that save's
           snapshot were silently lost (the rescheduled follow-up aborted on
           isDestroyed()). Forcing the write is safe: the executor is FIFO,
           so the fresh snapshot lands AFTER the in-flight one, and
           shutdown() still runs already-queued tasks. */
        if (dirty && fileUri != null) saveIo.startQuietSave(true);
        super.onDestroy();
        /* v7 FIX: the non-daemon single-thread executor (and the Activity it
           captured via Runnables) outlived every editor session. */
        try { saveExec.shutdown(); } catch (Throwable ignored) { }
    }

    void showInlineEditor() {
        if (sel == null) return;
        List<PptDoc.Para> ps = parasOf(sel);
        if (ps == null || ps.isEmpty()) { editSelectedText(); return; }
        hideInlineEditor();

        PptDoc.Para p0 = ps.get(0);
        PptDoc.Run r0 = p0.runs.isEmpty() ? null : p0.runs.get(0);
        float sizePt = r0 != null ? r0.sizePt : 18f;
        int color = r0 != null ? r0.color : 0xFF1F1F1F;
        boolean bold = r0 != null && r0.bold;
        boolean rtl = p0.rtl;

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ps.size(); i++) {
            PptDoc.Para p = ps.get(i);
            if (i > 0) sb.append('\n');
            for (PptDoc.Run r : p.runs) sb.append(r.text);
        }

        RectF r = canvas.shapeRectPub(sel);
        if (r == null) { editSelectedText(); return; }
        int[] loc = new int[2];
        canvas.getLocationOnScreen(loc);
        /* screen → window coords for the overlay layer */
        int[] winLoc = new int[2];
        getWindow().getDecorView().getLocationOnScreen(winLoc);
        int left = Math.round(r.left) + loc[0] - winLoc[0];
        int top = Math.round(r.top) + loc[1] - winLoc[1];
        int w = Math.max(dp(120), Math.round(r.width()));
        int h = Math.max(dp(44), Math.round(r.height()));

        inlineEdit = new EditText(this);
        inlineEdit.setText(sb.toString());
        /* v1.29 FIX: bind the editor to the shape it was OPENED for — falling
           taps through the scrim used to move `sel`, and the typed text was
           silently committed to whatever shape was tapped last. */
        inlineSel = sel;
        /* v1.27 FIX: the editor font size was ALWAYS clamped to 9sp — the
           old formula (sizePt * canvas.s()) missed the 12700 EMU-per-point
           factor, producing ~0.001 for an 18pt run and losing the Math.max
           every time. Convert pt → canvas px (×12700×scale) → sp (÷density)
           so the editor's text size MATCHES the canvas rendering. */
        float editPx = sizePt * 12700f * canvas.s() * 1.05f;
        float editSp = dp(1) > 0f ? editPx / dp(1) : 9f;
        inlineEdit.setTextSize(TypedValue.COMPLEX_UNIT_SP, Math.max(9f, Math.min(400f, editSp)));
        /* v1.26 FIX (edit mode was nearly invisible): the old 20% black scrim
           over the dark canvas made the field and its text unreadable. Now a
           solid near-white card with an accent border — visible on ANY slide
           background; light text colors are darkened for editing only (the
           committed text keeps the file's real color). */
        int editColor = color;
        if ((android.graphics.Color.red(color) > 0x9F && android.graphics.Color.green(color) > 0x9F
                && android.graphics.Color.blue(color) > 0x9F) || color == 0) {
            editColor = 0xFF3B3B3B;
        }
        inlineEdit.setTextColor(editColor);
        inlineEdit.setTypeface(null, bold ? Typeface.BOLD : Typeface.NORMAL);
        inlineEdit.setGravity(rtl ? Gravity.RIGHT | Gravity.TOP : Gravity.LEFT | Gravity.TOP);
        GradientDrawable ebg = new GradientDrawable();
        ebg.setColor(0xF2FFFFFF);
        ebg.setCornerRadius(dp(6));
        ebg.setStroke(dp(2), 0xFF2F7DD1);
        inlineEdit.setBackground(ebg);
        inlineEdit.setPadding(dp(10), dp(8), dp(10), dp(8));
        if (rtl) inlineEdit.setTextDirection(android.view.View.TEXT_DIRECTION_RTL);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
        lp.leftMargin = left;
        lp.topMargin = top;
        inlineLayer.addView(inlineEdit, lp);
        inlineLayer.setBackgroundColor(0x66000000);
        inlineLayer.setVisibility(View.VISIBLE);
        /* v1.29 FIX: consume scrim taps in EDIT mode (commit, PowerPoint-style).
           The layer must stay NON-clickable in crop mode — crop relies on
           touches falling through to the canvas handle drags. */
        inlineLayer.setClickable(true);
        inlineLayer.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { commitInlineEditor(true); }
        });
        inlineLayer.bringToFront();
        inlineEdit.requestFocus();
        inlineEdit.setSelection(inlineEdit.getText().length());
        showKeyboard(true);

        /* ✓ / ✕ pills under the editor */
        LinearLayout pills = new LinearLayout(this);
        pills.setOrientation(LinearLayout.HORIZONTAL);
        pills.addView(textDialogs.inlinePill("✓ ذخیره", new Runnable() {
            @Override public void run() { commitInlineEditor(true); }
        }));
        pills.addView(textDialogs.inlinePill("✕ انصراف", new Runnable() {
            @Override public void run() { commitInlineEditor(false); }
        }));
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(-2, -2);
        plp.leftMargin = left;
        plp.topMargin = Math.min(top + h + dp(6), Math.max(0, getWindowH() - dp(60)));
        inlineLayer.addView(pills, plp);
    }

    private int getWindowH() {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        return dm.heightPixels;
    }

    void commitInlineEditor(boolean apply) {
        if (inlineEdit == null) { hideInlineEditor(); return; }
        /* v1.29 FIX: commit to the shape the editor was OPENED for (inlineSel)
           — not the live `sel`, which could have been reassigned by a tap
           that fell through the old non-clickable scrim. */
        PptDoc.Shape target = inlineSel != null ? inlineSel : sel;
        if (apply && target != null) {
            final String txt = inlineEdit.getText().toString();
            if (target instanceof PptDoc.TextShape) {
                /* v1.23: استایل بندها حفظ می‌شود (اندازه/رنگ/قلم/تراز بند
                   اول) — قبلاً همه‌چیز به ۱۸pt مشکی برمی‌گشت */
                pushUndo();
                ((PptDoc.TextShape) target).setTextKeepingStyle(txt);
            } else {
                final List<PptDoc.Para> ps = parasOf(target);
                if (ps != null && !ps.isEmpty()) {
                    pushUndo();
                    /* v1.26 FIX: keep the first paragraph's real style (size/
                       color/bold/font/rtl) — before, every AutoShape edit reset
                       the text to 18pt black. Same contract as TextShape. */
                    PptDoc.Para proto = new PptDoc.Para();
                    proto.copyFrom(ps.get(0));
                    PptDoc.Run rproto = proto.styleProto();
                    boolean protoRtl = ps.get(0).hasRtl ? ps.get(0).rtl : true;
                    ps.clear();
                    String[] lines = txt.split("\n", -1);
                    for (String ln : lines) {
                        PptDoc.Para p = new PptDoc.Para();
                        p.align = proto.align;
                        p.rtl = protoRtl;
                        p.hasRtl = true;
                        p.lvl = proto.lvl;
                        p.marL = proto.marL;
                        p.indent = proto.indent;
                        p.buChar = proto.buChar;
                        p.buAutoNum = proto.buAutoNum;
                        p.buType = proto.buType;
                        /* v1.32: full bullet props survive an Enter-split in
                           the inline editor (start index + marker size/color
                           were dropped, so split numbered lines restarted /
                           resized) */
                        p.buStart = proto.buStart;
                        p.buClr = proto.buClr;
                        p.buSzPct = proto.buSzPct;
                        p.buSizePt = proto.buSizePt;
                        p.hasBullet = proto.hasBullet;
                        p.bullet = proto.bullet;
                        PptDoc.Run r = new PptDoc.Run();
                        r.text = ln;
                        r.sizePt = rproto.sizePt > 0 ? rproto.sizePt : 18f;
                        r.color = rproto.color != 0 ? rproto.color : 0xFF1F1F1F;
                        r.bold = rproto.bold;
                        r.italic = rproto.italic;
                        r.underline = rproto.underline;
                        r.font = rproto.font;
                        p.runs.add(r);
                        ps.add(p);
                    }
                }
            }
        }
        hideInlineEditor();
        refreshAll();
    }

    void hideInlineEditor() {
        if (inlineLayer == null) return;
        /* v1.29 FIX: hide the IME with the EDITOR'S window token BEFORE the
           views come off — after removeAllViews() getCurrentFocus() is null,
           so showKeyboard(false) was a no-op and the keyboard (with
           adjustResize, the shrunken window) stayed open after commit. */
        if (inlineEdit != null) {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(inlineEdit.getWindowToken(), 0);
        }
        inlineLayer.removeAllViews();
        inlineLayer.setVisibility(View.GONE);
        /* v1.29: restore fall-through for crop mode reuse + drop the binding */
        inlineLayer.setClickable(false);
        inlineEdit = null;
        inlineSel = null;
        showKeyboard(false);
    }

    /* ===================== v1.23: برش تعاملی تصویر =====================
       حالت برش: تصویر کامل زیر یک پنجرهٔ برش روشن رندر می‌شود و کاربر
       دستگیره‌های لبه را می‌کشد؛ ✓ تأیید / ✕ لغو شناور پایین صفحه.
       لایهٔ inline (روی همه‌چیز) فقط دکمه‌ها را دارد و شفاف است، پس
       لمس‌های خارج از دکمه‌ها به بوم می‌رسند و دستگیره‌ها کشیده می‌شوند. */
    void enterCropMode() {
        if (cropMode) return;
        if (!(sel instanceof PptDoc.Picture)) {
            Toast.makeText(this, "اول یک تصویر انتخاب کن", Toast.LENGTH_SHORT).show();
            return;
        }
        hideInlineEditor();
        PptDoc.Picture pic = (PptDoc.Picture) sel;
        crop0L = pic.cropL;
        crop0R = pic.cropR;
        crop0T = pic.cropT;
        crop0B = pic.cropB;
        cropHandle = -1;
        cropUndoPushed = false;   /* v1.29: crop is undoable now — see handleCropTouch */
        cropMode = true;
        if (inlineLayer != null) {
            inlineLayer.removeAllViews();
            inlineLayer.setBackgroundColor(0x00000000);
            LinearLayout pills = new LinearLayout(this);
            pills.setOrientation(LinearLayout.HORIZONTAL);
            pills.addView(textDialogs.inlinePill("✓ تأیید برش", new Runnable() {
                @Override public void run() { exitCropMode(true); }
            }));
            pills.addView(textDialogs.inlinePill("✕ لغو برش", new Runnable() {
                @Override public void run() { exitCropMode(false); }
            }));
            FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(-2, -2);
            plp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            plp.bottomMargin = dp(120);
            inlineLayer.addView(pills, plp);
            inlineLayer.setVisibility(View.VISIBLE);
            inlineLayer.bringToFront();
        }
        Toast.makeText(this, "لبه‌های برش را بکش", Toast.LENGTH_SHORT).show();
        canvas.invalidate();
    }

    void exitCropMode(boolean apply) {
        if (!cropMode) return;
        cropMode = false;
        cropHandle = -1;
        if (sel instanceof PptDoc.Picture) {
            PptDoc.Picture pic = (PptDoc.Picture) sel;
            if (!apply) {
                pic.cropL = crop0L;
                pic.cropR = crop0R;
                pic.cropT = crop0T;
                pic.cropB = crop0B;
            }
        }
        if (inlineLayer != null) {
            inlineLayer.removeAllViews();
            inlineLayer.setVisibility(View.GONE);
        }
        if (apply) {
            markDirty();
            filmstrip.rebuild();
        }
        canvas.invalidate();
    }

    private void showKeyboard(boolean show) {
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm == null) return;
        if (show && inlineEdit != null) {
            imm.showSoftInput(inlineEdit, 0);
        } else {
            View fv = getCurrentFocus();
            if (fv != null) imm.hideSoftInputFromWindow(fv.getWindowToken(), 0);
        }
    }

    /* ============================== doc access ============================= */
    PptDoc.Slide slide() {
        while (doc.slides.isEmpty()) doc.newSlide();
        if (cur >= doc.slides.size()) cur = doc.slides.size() - 1;
        if (cur < 0) cur = 0;
        return doc.slides.get(cur);
    }

    void pushUndo() {
        undoStack.push(cloneDoc());
        if (undoStack.size() > 24) undoStack.removeLast();
        redoStack.clear();
        markDirty();   /* every undoable action is a real edit */
    }

    PptDoc cloneDoc() {
        PptDoc c = new PptDoc();
        c.slideW = doc.slideW;
        c.slideH = doc.slideH;
        c.themeBgTop = doc.themeBgTop;
        c.themeBgBottom = doc.themeBgBottom;
        c.themeAccent = doc.themeAccent;
        c.fontMajor = doc.fontMajor;
        c.fontMinor = doc.fontMinor;
        for (PptDoc.Slide s : doc.slides) c.slides.add(s.copy());
        return c;
    }

    void undo() {
        if (undoStack.isEmpty()) {
            Toast.makeText(this, "چیزی برای برگرداندن نیست", Toast.LENGTH_SHORT).show();
            return;
        }
        redoStack.push(cloneDoc());
        restoreDoc(undoStack.pop());
    }

    void redo() {
        if (redoStack.isEmpty()) {
            Toast.makeText(this, "چیزی برای جلو بردن نیست", Toast.LENGTH_SHORT).show();
            return;
        }
        undoStack.push(cloneDoc());
        restoreDoc(redoStack.pop());
    }

    private void restoreDoc(PptDoc d) {
        int wanted = cur;
        doc = d;
        if (wanted >= doc.slides.size()) wanted = doc.slides.size() - 1;
        cur = Math.max(0, wanted);
        sel = null;
        markDirty();   /* undo/redo changes the doc vs disk — Office keeps the save indicator on too */
        refreshAll();
    }

    void refreshAll() {
        canvas.invalidate();
        filmstrip.rebuild();
        updateTitle();
        if (filmHandle instanceof TextView) {
            ((TextView) filmHandle).setText(filmCollapsed
                ? "▾ اسلایدها (" + Fa.d(cur + 1) + "/" + Fa.d(doc.slides.size()) + ")"
                : "▬▬▬  اسلاید " + Fa.d(cur + 1) + " از " + Fa.d(doc.slides.size()));
        }
        statusZoom.setText(Fa.d(Math.round(zoom * 100)) + "٪");
        ribbons.syncContextualTab();
    }

    /* filmstrip thumbnail tap → show this slide BIG on the canvas:
       switch, reset zoom, recenter, update status + highlight */
    void gotoSlide(int idx) {
        if (idx < 0 || idx >= doc.slides.size()) return;
        if (idx == cur) {
            canvas.resetView();
            return;
        }
        cur = idx;
        sel = null;
        zoom = 1f;
        canvas.resetView();
        refreshAll();
    }

    void mutateSel(Runnable r) {
        if (sel == null) {
            Toast.makeText(this, "اول یک شکل را انتخاب کن", Toast.LENGTH_SHORT).show();
            return;
        }
        pushUndo();
        r.run();
        canvas.invalidate();
        filmstrip.rebuild();
    }

    static List<PptDoc.Para> parasOf(PptDoc.Shape s) {
        if (s instanceof PptDoc.TextShape) return ((PptDoc.TextShape) s).paras;
        if (s instanceof PptDoc.AutoShape) return ((PptDoc.AutoShape) s).paras;
        return null;
    }

    boolean curBold() {
        PptDoc.Para p = firstPara();
        return p != null && p.styleProto().bold;
    }

    boolean curItalic() {
        PptDoc.Para p = firstPara();
        return p != null && p.styleProto().italic;
    }

    boolean curUnderline() {
        PptDoc.Para p = firstPara();
        return p != null && p.styleProto().underline;
    }

    private PptDoc.Para firstPara() {
        if (sel == null) return null;
        List<PptDoc.Para> ps = parasOf(sel);
        return ps == null || ps.isEmpty() ? null : ps.get(0);
    }

    void applyTextStyle(int what, float value) {
        applyTextStyle(what, value, 0, null);
    }

    void applyTextStyle(final int what, final float value, final int color, final String font) {
        if (sel == null) {
            Toast.makeText(this, "اول یک شکل متنی انتخاب کن", Toast.LENGTH_SHORT).show();
            return;
        }
        List<PptDoc.Para> ps = parasOf(sel);
        if (ps == null || ps.isEmpty()) {
            Toast.makeText(this, "این شکل متن ندارد", Toast.LENGTH_SHORT).show();
            return;
        }
        mutateSel(new Runnable() {
            @Override
            public void run() {
                for (PptDoc.Para p : parasOf(sel)) {
                    for (PptDoc.Run r : p.runs) {
                        switch (what) {
                            case 1:
                                r.sizePt = Math.max(4f, Math.min(400f, r.sizePt + value));
                                break;
                            case 2:
                                r.bold = value > 0.5f;
                                break;
                            case 3:
                                r.italic = value > 0.5f;
                                break;
                            case 4:
                                r.underline = value > 0.5f;
                                break;
                            case 5:
                                r.color = color;
                                break;
                            case 6:
                                r.font = font;
                                break;
                        }
                    }
                }
            }
        });
    }

    void applyTextSize(float delta) {
        applyTextStyle(1, delta);
    }

    void applyAlign(final int align) {
        if (sel == null) {
            Toast.makeText(this, "اول یک شکل متنی انتخاب کن", Toast.LENGTH_SHORT).show();
            return;
        }
        List<PptDoc.Para> ps = parasOf(sel);
        if (ps == null || ps.isEmpty()) {
            Toast.makeText(this, "این شکل متن ندارد", Toast.LENGTH_SHORT).show();
            return;
        }
        mutateSel(new Runnable() {
            @Override
            public void run() {
                for (PptDoc.Para p : parasOf(sel)) p.align = align;
            }
        });
    }

    /* ============================== dialogs ================================ */
    interface ColorPick {
        void on(int color);
    }

    static float parsePt(String raw, float max) {
        String s = Fa.digitsToAscii(raw == null ? "" : raw)
                .replace("٫", ".").replace(",", ".").trim();
        if (s.length() == 0) return 0f;
        try {
            float v = Float.parseFloat(s);
            return Math.max(0f, Math.min(max, v));
        } catch (Exception e) {
            return 0f;
        }
    }

    /* تراز با اسلاید — شش حالت (سه افقی + سه عمودی) */
    static final String[] TRANS_KEYS = {
            "none", "fade", "push", "wipe", "cover", "pull", "cut", "dissolve",
            "split", "wheel", "zoom", "newsflash", "checker", "blinds", "comb",
            "strips", "wedge", "random"};
    static final String[] TRANS_NAMES = {
            "بدون انتقال", "محو", "هل", "پاک‌شدن", "پوشش", "کشیدن", "برش", "انحلال",
            "شکافتن", "چرخش", "بزرگ‌نمایی", "تله‌کوب", "شطرنجی", "پرده‌ای", "شانه",
            "نوارها", "گوه", "تصادفی"};

    int countOccurrences(String find, boolean ic) {
        if (find == null || find.length() == 0) return 0;
        int n = 0;
        for (int si = 0; si < doc.slides.size(); si++) {
            List<PptDoc.Shape> shapes = doc.slides.get(si).shapes;
            for (int i = 0; i < shapes.size(); i++) {
                List<PptDoc.Para> ps = parasOf(shapes.get(i));
                if (ps == null) continue;
                for (int pi = 0; pi < ps.size(); pi++) {
                    List<PptDoc.Run> runs = ps.get(pi).runs;
                    for (int ri = 0; ri < runs.size(); ri++) {
                        String t = runs.get(ri).text;
                        if (t == null) continue;
                        n += countIn(t, find, ic);
                    }
                }
            }
        }
        return n;
    }

    private static int countIn(String text, String find, boolean ic) {
        if (!ic) {
            int n = 0, ix = 0;
            while ((ix = text.indexOf(find, ix)) >= 0) {
                n++;
                ix += find.length();
            }
            return n;
        }
        String low = text.toLowerCase();
        String f = find.toLowerCase();
        int n = 0, ix = 0;
        while ((ix = low.indexOf(f, ix)) >= 0) {
            n++;
            ix += f.length();
        }
        return n;
    }

    /* اول شمارش، بعد undo+اعمال — تا وقتی چیزی پیدا نشد undo فشار داده نمی‌شود */
    static String replaceIn(String text, String find, String rep, boolean ic) {
        if (!ic) return text.replace(find, rep);
        String low = text.toLowerCase();
        String f = find.toLowerCase();
        StringBuilder sb = new StringBuilder(text.length() + 16);
        int ix = 0;
        while (true) {
            int hit = low.indexOf(f, ix);
            if (hit < 0) {
                sb.append(text, ix, text.length());
                break;
            }
            sb.append(text, ix, hit);
            sb.append(rep);
            ix = hit + f.length();
        }
        return sb.toString();
    }

    void insertTextBox() {
        pushUndo();
        PptDoc.TextShape t = new PptDoc.TextShape("متن جدید…",
                doc.slideW * 25 / 100, doc.slideH * 40 / 100,
                doc.slideW / 2, doc.slideH / 5, 20f, 0xFF1F1F1F, false, true);
        slide().shapes.add(t);
        sel = t;
        refreshAll();
        editSelectedText();
    }

    void insertShape(int type) {
        pushUndo();
        PptDoc.AutoShape a = new PptDoc.AutoShape(type);
        a.x = doc.slideW * 28 / 100;
        a.y = doc.slideH * 34 / 100;
        a.w = doc.slideW / 5;
        a.h = doc.slideH / 5;
        a.fill = PptDoc.Fill.solid(0xFF2E75B6);
        a.line.enabled = true;
        a.line.color = 0xFF1F3864;
        a.line.widthPt = 1f;
        slide().shapes.add(a);
        sel = a;
        refreshAll();
    }

    void insertTable(int rows, int cols) {
        pushUndo();
        PptDoc.Table t = new PptDoc.Table();
        t.x = doc.slideW * 12 / 100;
        t.y = doc.slideH * 30 / 100;
        t.w = doc.slideW * 76 / 100;
        t.h = Math.round(doc.slideH * 0.5f);
        float cw = (float) t.w / cols;
        for (int i = 0; i < cols; i++) t.colWidths.add(cw);
        float ch = (float) t.h / rows;
        for (int r = 0; r < rows; r++) {
            PptDoc.TableRow row = new PptDoc.TableRow();
            row.heightEmu = ch;
            for (int c2 = 0; c2 < cols; c2++) {
                PptDoc.TableCell cell = new PptDoc.TableCell();
                PptDoc.Para p = new PptDoc.Para();
                p.align = PptDoc.Para.ALIGN_CTR;
                p.rtl = true;
                PptDoc.Run run = new PptDoc.Run();
                run.text = "";
                run.sizePt = 16f;
                run.color = 0xFF1F1F1F;
                p.runs.add(run);
                cell.paras.add(p);
                row.cells.add(cell);
            }
            t.rows.add(row);
        }
        slide().shapes.add(t);
        sel = t;
        refreshAll();
    }

    void pickImage() {
        Intent it = new Intent(Intent.ACTION_GET_CONTENT);
        it.addCategory(Intent.CATEGORY_OPENABLE);
        it.setType("image/*");
        startActivityForResult(Intent.createChooser(it, "انتخاب تصویر"), REQ_IMG);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode == REQ_VID && data.getData() != null) {
            handleVideoResult(data.getData());
        } else if (requestCode == REQ_IMG && data.getData() != null) {
            handleImageResult(data.getData());
        } else if (requestCode == REQ_SAVE_AS && data.getData() != null) {
            writeTo(data.getData());
        }
    }

    void handleImageResult(final Uri imageUri) {
        /* v1.29 FIX: stream read + HEIC normalize (decode up to 4096px + PNG
           re-encode) ran SYNCHRONOUSLY on the UI thread — multiple seconds of
           frozen editor on a modern 12-50MP phone photo. Heavy work moved to
           a loader thread; the actual insertion stays on the UI thread. */
        Toast.makeText(this, "در حال درج تصویر…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override public void run() {
                byte[] bytes;
                int wPx = 1, hPx = 1;
                try {
                    InputStream in = getContentResolver().openInputStream(imageUri);
                    try {
                        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                        byte[] buf = new byte[65536];
                        int r;
                        while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
                        bytes = bo.toByteArray();
                    } finally {
                        closeQuietly(in);
                    }
                    /* v1.28 FIX: HEIC عکس‌های گوشی‌های مدرن قبلاً خام ذخیره می‌شد
                       و sniff آن را «png» می‌گفت → فایل خراب در پاورپوینت PC و
                       دستگاه‌های دیگر؛ حالا فرمت‌های پشتیبانی‌نشده به PNG
                       تبدیل می‌شوند. چرخش EXIF (عکس‌های دوربین) هم اعمال
                       می‌شود تا تصویر درست بایستد. */
                    bytes = insert.normalizeInsertedImage(bytes);
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
                    wPx = Math.max(1, o.outWidth);
                    hPx = Math.max(1, o.outHeight);
                    if (wPx <= 1 || hPx <= 1) {   /* decodeBounds ناموفق */
                        PptJpegDecoder.Result jr = PptJpegDecoder.decode(bytes, 64);
                        if (jr != null) { wPx = jr.width; hPx = jr.height; }
                    }
                } catch (final Throwable t) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            Toast.makeText(PptEditorActivity.this, "خواندن تصویر ممکن نشد", Toast.LENGTH_SHORT).show();
                        }
                    });
                    return;
                }
                final byte[] fbytes = bytes;
                final int fwPx = wPx, fhPx = hPx;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (isFinishing() || isDestroyed()) return;
                        try {
                            pushUndo();
                            PptDoc.Picture pic = new PptDoc.Picture();
                            pic.imgData = fbytes;
                            pic.imgFormat = PptxReader.sniffImgFormat(fbytes);
                            float wantW = doc.slideW * 0.45f;
                            pic.w = Math.round(wantW);
                            pic.h = Math.round(wantW * fhPx / fwPx);
                            if (pic.h > doc.slideH * 0.8f) {
                                pic.h = Math.round(doc.slideH * 0.8f);
                                pic.w = Math.round(pic.h * fwPx / fhPx);
                            }
                            pic.x = (doc.slideW - pic.w) / 2;
                            pic.y = (doc.slideH - pic.h) / 2;
                            slide().shapes.add(pic);
                            sel = pic;
                            refreshAll();
                        } catch (Throwable t) {
                            Toast.makeText(PptEditorActivity.this, "خواندن تصویر ممکن نشد", Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }
        }, "ppt-insert-img").start();
    }

    /* v1.28: فرمت‌های pptx-ناپذار (HEIC/…‌) و چرخش EXIF — درج تصویر مثل
       پاورپوینت موبایل: یا بایت‌های اصلی دست‌نخورده می‌مانند (PNG/JPEG/
       GIF/BMP/WebP بدون چرخش) یا یک‌بار به PNG بازذخیره می‌شوند. */
    static int readExifOrientation(byte[] b) {
        try {
            if (b == null || b.length < 12) return 0;
            if ((b[0] & 255) != 0xFF || (b[1] & 255) != 0xD8) return 0;
            int p = 2;
            while (p + 4 < b.length) {
                if ((b[p] & 255) != 0xFF) return 0;
                int marker = b[p + 1] & 255;
                if (marker == 0xD8 || (marker >= 0xD0 && marker <= 0xD7)) { p += 2; continue; }
                if (marker == 0xDA || marker == 0xD9) return 0;   /* entropy/EOI — دیگر APP1 نیست */
                int len = ((b[p + 2] & 255) << 8) | (b[p + 3] & 255);
                if (len < 2 || p + 2 + len > b.length) return 0;
                if (marker == 0xE1 && len >= 8
                        && b[p + 4] == 'E' && b[p + 5] == 'x' && b[p + 6] == 'i'
                        && b[p + 7] == 'f' && b[p + 8] == 0 && b[p + 9] == 0) {
                    int tiff = p + 10;
                    int end = p + 2 + len;
                    boolean le = b[tiff] == 0x49 && b[tiff + 1] == 0x49;
                    if (!le && !(b[tiff] == 0x4D && b[tiff + 1] == 0x4D)) return 0;
                    int ifdOff = le ? ((b[tiff + 4] & 255) | (b[tiff + 5] & 255) << 8)
                            : ((b[tiff + 4] & 255) << 8 | (b[tiff + 5] & 255));
                    int ifd = tiff + ifdOff;
                    if (ifd + 2 > end) return 0;
                    int cnt = le ? ((b[ifd] & 255) | (b[ifd + 1] & 255) << 8)
                            : ((b[ifd] & 255) << 8 | (b[ifd + 1] & 255));
                    for (int i = 0; i < cnt && ifd + 2 + i * 12 + 12 <= end; i++) {
                        int e = ifd + 2 + i * 12;
                        int tag = le ? ((b[e] & 255) | (b[e + 1] & 255) << 8)
                                : ((b[e] & 255) << 8 | (b[e + 1] & 255));
                        if (tag == 0x0112) {
                            int v = le ? ((b[e + 8] & 255) | (b[e + 9] & 255) << 8)
                                    : ((b[e + 8] & 255) << 8 | (b[e + 9] & 255));
                            return (v >= 1 && v <= 8) ? v : 0;
                        }
                    }
                    return 0;
                }
                p += 2 + len;
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /* ===================== v1.22: hyperlinks + media ====================== */

    /* opens a hyperlink run the user tapped (with a confirm step, like
       PowerPoint mobile does for external links) */
    void openLink(final String url) {
        if (url == null || url.length() == 0) return;
        String shown = url.length() > 80 ? url.substring(0, 80) + "…" : url;
        new AlertDialog.Builder(this)
                .setTitle("باز کردن پیوند؟")
                .setMessage(shown)
                .setPositiveButton("باز کن", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW,
                                    Uri.parse(url)));
                        } catch (Throwable t) {
                            Toast.makeText(PptEditorActivity.this,
                                    "برنامه‌ای برای باز کردن این پیوند پیدا نشد",
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("لغو", null)
                .show();
    }

    /* ==================== v1.22: video/audio playback ==================== */
    void pickVideo() {
        Intent it = new Intent(Intent.ACTION_GET_CONTENT);
        it.setType("video/*");
        /* v1.29 FIX: EXTRA_MIME_TYPES is a RESTRICTIVE filter — with only
           audio/* the system picker hid every video file, so the
           «انتخاب ویدیو» flow could not pick videos at all. */
        String[] extra = {"video/*", "audio/*"};
        it.putExtra(Intent.EXTRA_MIME_TYPES, extra);
        it.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(it, "انتخاب ویدیو یا صدا"), REQ_VID);
        } catch (Throwable t) {
            Toast.makeText(this, "انتخاب‌گر فایل در دسترس نیست", Toast.LENGTH_SHORT).show();
        }
    }

    void handleVideoResult(final Uri videoUri) {
        /* v1.29 FIX: reading up to 48MB on the UI thread froze the editor for
           seconds (ANR risk). The copy now runs on a loader thread; only the
           insertion mutates the doc (on the UI thread). The size cap is also
           enforced DURING the read instead of after it. */
        Toast.makeText(this, "در حال درج رسانه…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override public void run() {
                byte[] data;
                String name, mime;
                try {
                    name = queryDisplayName(videoUri);
                    mime = getContentResolver().getType(videoUri);
                    if (mime == null) mime = "video/mp4";
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    InputStream in = getContentResolver().openInputStream(videoUri);
                    boolean tooBig = false;
                    try {
                        byte[] buf = new byte[65536];
                        int r;
                        while ((r = in.read(buf)) > 0) {
                            bo.write(buf, 0, r);
                            if (bo.size() > 48L * 1024 * 1024) { tooBig = true; break; }
                        }
                    } finally {
                        closeQuietly(in);
                    }
                    if (tooBig) {
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                Toast.makeText(PptEditorActivity.this, "فایل بزرگ‌تر از ۴۸ مگابایت است", Toast.LENGTH_SHORT).show();
                            }
                        });
                        return;
                    }
                    data = bo.toByteArray();
                    if (data.length == 0) throw new Exception("empty");
                } catch (final Throwable t) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            CrashGuard.append(PptEditorActivity.this, "vid insert: " + CrashGuard.stackToString(t));
                            Toast.makeText(PptEditorActivity.this, "خواندن فایل ویدیو ممکن نشد", Toast.LENGTH_SHORT).show();
                        }
                    });
                    return;
                }
                final byte[] fdata = data;
                final String fname = name, fmime = mime;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (isFinishing() || isDestroyed()) return;
                        PptDoc.Video vid = new PptDoc.Video();
                        vid.x = (int) (doc.slideW * 0.12);
                        vid.y = (int) (doc.slideH * 0.18);
                        vid.w = (int) (doc.slideW * 0.76);
                        vid.h = (int) (doc.slideW * 0.76 * 9f / 16f);
                        if (vid.y + vid.h > doc.slideH) vid.h = (int) (doc.slideH * 0.6);
                        vid.videoData = fdata;
                        vid.mimeType = fmime;
                        vid.videoName = fname;
                        vid.videoFormat = fmime.startsWith("audio") ? "mp3" : "mp4";
                        if (fname != null && fname.lastIndexOf('.') > 0) {
                            String ext = fname.substring(fname.lastIndexOf('.') + 1).toLowerCase();
                            if (ext.length() > 0 && ext.length() <= 5) vid.videoFormat = ext;
                        }
                        pushUndo();
                        slide().shapes.add(vid);
                        sel = vid;
                        refreshAll();
                        Toast.makeText(PptEditorActivity.this, "ویدیو درج شد — برای پخش رویش بزن", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }, "ppt-insert-video").start();
    }

    /* fullscreen-ish player dialog — media bytes go to a cache file so
       MediaPlayer/VideoView can stream them */
    void playVideo(PptDoc.Video vid) {
        if (vid.videoData == null || vid.videoData.length == 0) {
            Toast.makeText(this, "فایل ویدیو داخل این ارائه جاسازی نشده است", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            File tmp = new File(getCacheDir(), "ppt_media_" + System.currentTimeMillis()
                    + "." + (vid.videoFormat == null ? "mp4" : vid.videoFormat));
            FileOutputStream fo = new FileOutputStream(tmp);
            try {
                fo.write(vid.videoData);
                fo.getFD().sync();
            } finally {
                closeQuietly(fo);
            }
            final File finalTmp = tmp;
            boolean audio = vid.mimeType != null && vid.mimeType.startsWith("audio");
            FrameLayout host = new FrameLayout(this);
            host.setBackgroundColor(0xFF000000);
            if (audio) {
                /* audio: simple MediaPlayer + status text */
                final TextView status = new TextView(this);
                status.setTextColor(0xFFFFFFFF);
                status.setGravity(Gravity.CENTER);
                status.setText("♪ " + (vid.videoName == null ? "صدا" : vid.videoName) + "\nدر حال پخش…");
                status.setPadding(dp(24), dp(24), dp(24), dp(24));
                host.addView(status, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
                final android.media.MediaPlayer mp = new android.media.MediaPlayer();
                try {
                    mp.setDataSource(finalTmp.getPath());
                    mp.prepare();
                    mp.start();
                } catch (Throwable playErr) {
                    Toast.makeText(this, "پخش این صدا ممکن نشد", Toast.LENGTH_SHORT).show();
                }
                /* v1.29 FIX: release on ANY dismiss — the dialog is cancelable
                   (Back / outside tap) and the old cleanup only ran on the
                   بستن button, so the audio kept playing with no way to stop
                   it and the temp file leaked. */
                final boolean[] cleaned = {false};
                final Runnable cleanup = new Runnable() {
                    @Override public void run() {
                        if (cleaned[0]) return;
                        cleaned[0] = true;
                        try { mp.stop(); } catch (Throwable ignored) {}
                        try { mp.release(); } catch (Throwable ignored) {}
                        finalTmp.delete();
                    }
                };
                new AlertDialog.Builder(this)
                        .setTitle("پخش صدا")
                        .setView(host)
                        .setPositiveButton("بستن", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) {
                                d.dismiss();
                            }
                        })
                        .setOnDismissListener(new DialogInterface.OnDismissListener() {
                            @Override public void onDismiss(DialogInterface d) { cleanup.run(); }
                        })
                        .show();
            } else {
                VideoView vv = new VideoView(this);
                host.addView(vv, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
                vv.setVideoPath(finalTmp.getPath());
                MediaController mc = new MediaController(this);
                vv.setMediaController(mc);
                /* v1.29 FIX: Back/touch-outside dismissed the dialog without
                   stopping playback and without deleting the temp file — the
                   audio kept playing forever (MediaPlayer has no finalizer).
                   Both dialogs now get a dismiss listener that always cleans
                   up; the بستن button just closes the dialog. */
                final VideoView fvv = vv;
                final boolean[] cleaned = {false};
                final Runnable cleanup = new Runnable() {
                    @Override public void run() {
                        if (cleaned[0]) return;
                        cleaned[0] = true;
                        try { fvv.stopPlayback(); } catch (Throwable ignored) {}
                        finalTmp.delete();
                    }
                };
                AlertDialog dlg = new AlertDialog.Builder(this)
                        .setTitle(vid.videoName == null ? "پخش ویدیو" : vid.videoName)
                        .setView(host)
                        .setPositiveButton("بستن", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) {
                                d.dismiss();
                            }
                        })
                        .setOnDismissListener(new DialogInterface.OnDismissListener() {
                            @Override public void onDismiss(DialogInterface d) { cleanup.run(); }
                        })
                        .show();
                vv.start();
            }
        } catch (Throwable t) {
            CrashGuard.append(this, "vid play: " + CrashGuard.stackToString(t));
            Toast.makeText(this, "پخش ممکن نشد", Toast.LENGTH_SHORT).show();
        }
    }

    /* ==================== v1.22: chart insert / edit =====================
       chartDialog(null) inserts a new chart; chartDialog(chart) edits an
       existing one's type + data (series/categories grid editor). */
    static final String[] CHART_TYPE_NAMES = {
            "ستونی خوشه‌ای", "ستونی انباشته", "ستونی ۱۰۰٪",
            "میله‌ای خوشه‌ای", "میله‌ای انباشته", "میله‌ای ۱۰۰٪",
            "خطی", "خطی با نشانگر", "دایره‌ای", "دوناتی", "ناحیه‌ای", "پراکنده"
    };
    static final int[] CHART_TYPE_CODES = {
            PptDoc.Chart.BAR_CLUSTERED, PptDoc.Chart.BAR_STACKED, PptDoc.Chart.BAR_PERCENT,
            PptDoc.Chart.BARH_CLUSTERED, PptDoc.Chart.BARH_STACKED, PptDoc.Chart.BARH_PERCENT,
            PptDoc.Chart.LINE, PptDoc.Chart.LINE_MARKER, PptDoc.Chart.PIE,
            PptDoc.Chart.DOUGHNUT, PptDoc.Chart.AREA, PptDoc.Chart.SCATTER
    };

    void chartDialog(final PptDoc.Chart existing) {
        final boolean isEdit = existing != null;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, dp(8), pad, 0);

        /* type picker */
        final int[] picked = {isEdit ? indexOfChartType(existing.chartType) : 0};
        final TextView typeLbl = new TextView(this);
        typeLbl.setTextSize(14);
        typeLbl.setTextColor(0xFF1A1A1A);
        typeLbl.setText("نوع نمودار: " + CHART_TYPE_NAMES[picked[0]]);
        typeLbl.setPadding(0, dp(4), 0, dp(6));
        root.addView(typeLbl);
        LinearLayout typeRow = new LinearLayout(this);
        typeRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView prev = new TextView(this);
        prev.setText("◀");
        prev.setTextSize(18);
        prev.setPadding(dp(10), dp(6), dp(10), dp(6));
        TextView next = new TextView(this);
        next.setText("▶");
        next.setTextSize(18);
        next.setPadding(dp(10), dp(6), dp(10), dp(6));
        Runnable upd = new Runnable() {
            @Override public void run() {
                typeLbl.setText("نوع نمودار: " + CHART_TYPE_NAMES[picked[0]]);
            }
        };
        prev.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                picked[0] = (picked[0] + CHART_TYPE_NAMES.length - 1) % CHART_TYPE_NAMES.length;
                upd.run();
            }
        });
        next.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                picked[0] = (picked[0] + 1) % CHART_TYPE_NAMES.length;
                upd.run();
            }
        });
        typeRow.addView(prev);
        typeRow.addView(next);
        root.addView(typeRow);

        /* options row */
        LinearLayout optRow = new LinearLayout(this);
        optRow.setOrientation(LinearLayout.HORIZONTAL);
        final TextView titleEt = new EditText(this);
        titleEt.setHint("عنوان نمودار");
        titleEt.setText(isEdit && existing.title != null ? existing.title : "");
        titleEt.setSingleLine();
        titleEt.setTextSize(13);
        optRow.addView(titleEt, ribbons.linW(1));
        root.addView(optRow, new LinearLayout.LayoutParams(-1, -2));

        final boolean[] legendOn = {isEdit ? existing.legend : true};
        final boolean[] labelOn = {isEdit ? existing.dataLabels : false};
        TextView legendBtn = new TextView(this);
        legendBtn.setTextSize(13);
        legendBtn.setPadding(dp(10), dp(8), dp(10), dp(8));
        Runnable legUpd = new Runnable() {
            @Override public void run() {
                legendBtn.setText(legendOn[0] ? "راهنما: روشن" : "راهنما: خاموش");
            }
        };
        legendBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { legendOn[0] = !legendOn[0]; legUpd.run(); }
        });
        legUpd.run();
        TextView lblBtn = new TextView(this);
        lblBtn.setTextSize(13);
        lblBtn.setPadding(dp(10), dp(8), dp(10), dp(8));
        Runnable lblUpd = new Runnable() {
            @Override public void run() {
                lblBtn.setText(labelOn[0] ? "اعداد: روشن" : "اعداد: خاموش");
            }
        };
        lblBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { labelOn[0] = !labelOn[0]; lblUpd.run(); }
        });
        lblUpd.run();
        LinearLayout opt2 = new LinearLayout(this);
        opt2.setOrientation(LinearLayout.HORIZONTAL);
        opt2.addView(legendBtn);
        opt2.addView(lblBtn);
        root.addView(opt2, new LinearLayout.LayoutParams(-1, -2));

        /* data grid: header row = series names; rows = category + values */
        final List<EditText> nameEts = new ArrayList<>();
        final List<EditText> cellEts = new ArrayList<>();
        final LinearLayout gridHost = new LinearLayout(this);
        gridHost.setOrientation(LinearLayout.VERTICAL);
        ScrollView gridScroll = new ScrollView(this);
        gridScroll.addView(gridHost);

        /* working data (copy for edit) */
        final List<String> cats = new ArrayList<>();
        final List<String> serNames = new ArrayList<>();
        final List<List<String>> vals = new ArrayList<>();
        if (isEdit) {
            for (String c : existing.categories) cats.add(c);
            for (PptDoc.ChartSeries s : existing.series) {
                serNames.add(s.name == null ? "" : s.name);
                List<String> row = new ArrayList<>();
                for (Double v : s.values) row.add(PptxReader.fmtNum(v == null ? 0 : v));
                vals.add(row);
            }
        } else {
            cats.add("دسته ۱"); cats.add("دسته ۲"); cats.add("دسته ۳"); cats.add("دسته ۴");
            serNames.add("سری ۱"); serNames.add("سری ۲");
            vals.add(new ArrayList<>(java.util.Arrays.asList("۲۰", "۳۵", "۲۵", "۴۰")));
            vals.add(new ArrayList<>(java.util.Arrays.asList("۱۲", "۱۸", "۳۰", "۲۲")));
        }

        final Runnable[] rebuildRef = new Runnable[1];
        rebuildRef[0] = new Runnable() {
            @Override public void run() {
                gridHost.removeAllViews();
                nameEts.clear();
                cellEts.clear();
                /* header: corner + series names */
                LinearLayout hdr = new LinearLayout(PptEditorActivity.this);
                hdr.setOrientation(LinearLayout.HORIZONTAL);
                TextView corner = new TextView(PptEditorActivity.this);
                corner.setText("دسته \\ سری");
                corner.setTextSize(11);
                corner.setPadding(dp(4), dp(8), dp(4), dp(8));
                hdr.addView(corner, new LinearLayout.LayoutParams(dp(80), -2));
                for (int si = 0; si < serNames.size(); si++) {
                    EditText et = new EditText(PptEditorActivity.this);
                    et.setText(serNames.get(si));
                    et.setTextSize(12);
                    et.setSingleLine();
                    et.setMinWidth(dp(70));
                    final int fsi = si;
                    et.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                        @Override public void onFocusChange(View v, boolean hasFocus) {
                            if (!hasFocus && fsi < serNames.size()) {
                                serNames.set(fsi, nameEts.get(fsi).getText().toString());
                            }
                        }
                    });
                    nameEts.add(et);
                    hdr.addView(et, ribbons.linW(1));
                }
                gridHost.addView(hdr);
                /* data rows */
                for (int ci = 0; ci < cats.size(); ci++) {
                    LinearLayout row = new LinearLayout(PptEditorActivity.this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    EditText cet = new EditText(PptEditorActivity.this);
                    cet.setText(cats.get(ci));
                    cet.setTextSize(12);
                    cet.setSingleLine();
                    final int fci = ci;
                    cet.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                        @Override public void onFocusChange(View v, boolean hasFocus) {
                            /* v7 FIX: flat indexing + bounds guard — the old
                               ci*1000 scheme crashed (IndexOutOfBounds) because
                               only category cells were ever added to cellEts */
                            if (!hasFocus && fci < cats.size()) {
                                int idx = fci * (vals.size() + 1);
                                if (idx >= 0 && idx < cellEts.size()) {
                                    cats.set(fci, cellEts.get(idx).getText().toString());
                                }
                            }
                        }
                    });
                    cellEts.add(cet);   /* flat row layout: [cat, val0, val1, …] */
                    row.addView(cet, new LinearLayout.LayoutParams(dp(80), -2));
                    for (int si = 0; si < vals.size(); si++) {
                        EditText vet = new EditText(PptEditorActivity.this);
                        vet.setText(vals.get(si).get(ci));
                        vet.setTextSize(12);
                        vet.setSingleLine();
                        vet.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                                | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
                        /* v7 FIX: value cells were NEVER added to cellEts —
                           the harvester read wrong views and corrupted data */
                        cellEts.add(vet);
                        row.addView(vet, ribbons.linW(1));
                    }
                    gridHost.addView(row);
                }
                /* add/remove row buttons */
                LinearLayout ops = new LinearLayout(PptEditorActivity.this);
                ops.setOrientation(LinearLayout.HORIZONTAL);
                TextView addR = new TextView(PptEditorActivity.this);
                addR.setText("＋ دسته");
                addR.setTextSize(13);
                addR.setPadding(dp(10), dp(8), dp(10), dp(8));
                addR.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        cats.add("دسته " + Fa.d(cats.size() + 1));
                        for (List<String> vs : vals) vs.add("۰");
                        saveGridState();
                        rebuildRef[0].run();
                    }
                });
                TextView delR = new TextView(PptEditorActivity.this);
                delR.setText("－ دسته");
                delR.setTextSize(13);
                delR.setPadding(dp(10), dp(8), dp(10), dp(8));
                delR.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (cats.size() > 1) {
                            cats.remove(cats.size() - 1);
                            for (List<String> vs : vals) vs.remove(vs.size() - 1);
                            saveGridState();
                            rebuildRef[0].run();
                        }
                    }
                });
                TextView addS = new TextView(PptEditorActivity.this);
                addS.setText("＋ سری");
                addS.setTextSize(13);
                addS.setPadding(dp(10), dp(8), dp(10), dp(8));
                addS.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (serNames.size() < 6) {
                            /* v1.29 FIX: harvest BEFORE the mutation —
                               findCell/findVal stride is derived from
                               vals.size() AFTER the mutation, but cellEts
                               was built with the OLD stride: every typed
                               value beyond row 0 used to be scrambled
                               (read from the WRONG cell). */
                            saveGridState();
                            serNames.add("سری " + Fa.d(serNames.size() + 1));
                            List<String> nv = new ArrayList<>();
                            for (int i = 0; i < cats.size(); i++) nv.add("۰");
                            vals.add(nv);
                            rebuildRef[0].run();
                        }
                    }
                });
                TextView delS = new TextView(PptEditorActivity.this);
                delS.setText("－ سری");
                delS.setTextSize(13);
                delS.setPadding(dp(10), dp(8), dp(10), dp(8));
                delS.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (serNames.size() > 1) {
                            /* v1.29 FIX: harvest BEFORE removing (see addS) */
                            saveGridState();
                            serNames.remove(serNames.size() - 1);
                            vals.remove(vals.size() - 1);
                            rebuildRef[0].run();
                        }
                    }
                });
                ops.addView(addR); ops.addView(delR); ops.addView(addS); ops.addView(delS);
                gridHost.addView(ops);
            }

            /* harvest current EditText values back into cats/serNames/vals
               BEFORE rebuilding or committing */
            void saveGridState() {
                for (int si = 0; si < serNames.size() && si < nameEts.size(); si++) {
                    serNames.set(si, nameEts.get(si).getText().toString());
                }
                for (int ci = 0; ci < cats.size(); ci++) {
                    EditText cet = findCell(ci);
                    if (cet != null) cats.set(ci, cet.getText().toString());
                    for (int si = 0; si < vals.size(); si++) {
                        EditText vet = findVal(ci, si);
                        if (vet != null) vals.get(si).set(ci, vet.getText().toString());
                    }
                }
            }

            EditText findCell(int ci) {
                /* v1.27 FIX: the grid stores cells with row stride
                   vals.size()+1 ([cat, val0, val1, …] per row) — the old
                   ci*1000 indexing read the wrong EditTexts, so adding or
                   removing a category/series WIPED every typed value
                   beyond row 0 (silent data loss). */
                int stride = vals.size() + 1;
                int idx = ci * stride;
                return idx >= 0 && idx < cellEts.size() ? cellEts.get(idx) : null;
            }

            EditText findVal(int ci, int si) {
                int stride = vals.size() + 1;
                int idx = ci * stride + 1 + si;
                return idx >= 0 && idx < cellEts.size() ? cellEts.get(idx) : null;
            }
        };
        /* NOTE: cellEts layout — category cells at ci*1000, value cells at
           ci*1000+1+si — rebuilt per row in run() */
        rebuildRef[0].run();
        root.addView(gridScroll, new LinearLayout.LayoutParams(-1, dp(240)));

        new AlertDialog.Builder(this)
                .setTitle(isEdit ? "ویرایش نمودار" : "درج نمودار")
                .setView(root)
                .setPositiveButton("ثبت", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        /* harvest the grid */
                        for (int si = 0; si < serNames.size() && si < nameEts.size(); si++) {
                            serNames.set(si, nameEts.get(si).getText().toString());
                        }
                        int stride = vals.size() + 1; /* [cat, val0, val1, …] per row */
                        for (int ci = 0; ci < cats.size(); ci++) {
                            int cidx = ci * stride;
                            if (cidx >= 0 && cidx < cellEts.size()) {
                                cats.set(ci, cellEts.get(cidx).getText().toString());
                            }
                            for (int si = 0; si < vals.size(); si++) {
                                int idx = ci * stride + 1 + si;
                                if (idx >= 0 && idx < cellEts.size()) {
                                    vals.get(si).set(ci, cellEts.get(idx).getText().toString());
                                }
                            }
                        }
                        /* v7 FIX (undo): pushUndo() ran AFTER the target chart
                           was mutated, so the snapshot held the POST-edit state
                           and chart edits could not be undone. Snapshot FIRST. */
                        pushUndo();
                        /* Persian/Arabic digits → ASCII so parse works */
                        PptDoc.Chart target;
                        if (isEdit) {
                            target = existing;
                        } else {
                            target = new PptDoc.Chart();
                            target.x = (int) (doc.slideW * 0.10);
                            target.y = (int) (doc.slideH * 0.16);
                            target.w = (int) (doc.slideW * 0.80);
                            target.h = (int) (doc.slideH * 0.64);
                            target.legend = true;
                            target.legendPos = 1;
                        }
                        target.chartType = CHART_TYPE_CODES[picked[0]];
                        String ttl = titleEt.getText().toString().trim();
                        target.title = ttl.length() > 0 ? ttl : null;
                        target.legend = legendOn[0];
                        target.dataLabels = labelOn[0];
                        target.categories.clear();
                        for (String c : cats) target.categories.add(c);
                        /* keep series colors when count matches */
                        List<Integer> oldColors = new ArrayList<>();
                        for (PptDoc.ChartSeries s : target.series) oldColors.add(s.color);
                        target.series.clear();
                        for (int si = 0; si < serNames.size(); si++) {
                            PptDoc.ChartSeries s = new PptDoc.ChartSeries();
                            s.name = serNames.get(si);
                            if (si < oldColors.size()) s.color = oldColors.get(si);
                            for (int ci = 0; ci < cats.size(); ci++) {
                                String raw = vals.get(si).get(ci);
                                String ascii = Fa.digitsToAscii(raw);
                                double v;
                                try {
                                    v = ascii == null || ascii.length() == 0 ? 0
                                            : Double.parseDouble(ascii.trim().replace(",", ""));
                                } catch (Exception e) {
                                    v = 0;
                                }
                                s.values.add(v);
                            }
                            target.series.add(s);
                        }
                        if (!isEdit) {
                            slide().shapes.add(target);
                            sel = target;
                        }
                        refreshAll();
                        Toast.makeText(PptEditorActivity.this,
                                isEdit ? "نمودار به‌روز شد" : "نمودار درج شد",
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("لغو", null)
                .show();
    }

    static int indexOfChartType(int code) {
        for (int i = 0; i < CHART_TYPE_CODES.length; i++) {
            if (CHART_TYPE_CODES[i] == code) return i;
        }
        return 0;
    }

    void editSelectedText() {
        if (sel == null) {
            Toast.makeText(this, "اول یک شکل را انتخاب کن", Toast.LENGTH_SHORT).show();
            return;
        }
        List<PptDoc.Para> ps = parasOf(sel);
        if (ps == null || ps.isEmpty()) {
            Toast.makeText(this, "این شکل متن ندارد", Toast.LENGTH_SHORT).show();
            return;
        }
        StringBuilder txt = new StringBuilder();
        for (int i = 0; i < ps.size(); i++) {
            if (i > 0) txt.append('\n');
            txt.append(ps.get(i).plainText());
        }
        final EditText et = new EditText(this);
        et.setText(txt.toString());
        et.setTextColor(0xFF1A1A1A);
        et.setGravity(Gravity.RIGHT);
        et.setMinLines(3);
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(dp(18), dp(6), dp(18), 0);
        wrap.addView(et, new FrameLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this)
                .setTitle("ویرایش متن")
                .setView(wrap)
                .setPositiveButton("ثبت", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        final String s = et.getText().toString();
                        mutateSel(new Runnable() {
                            @Override
                            public void run() {
                                if (sel instanceof PptDoc.TextShape) {
                                    ((PptDoc.TextShape) sel).setTextKeepingStyle(s);
                                } else if (sel instanceof PptDoc.AutoShape) {
                                    /* v1.27 FIX: editing an AutoShape's text from
                                       the Home ribbon used to reset its style to
                                       16pt black centered — keep the first
                                       paragraph's real style like TextShape */
                                    PptDoc.AutoShape a = (PptDoc.AutoShape) sel;
                                    PptDoc.Para proto = a.paras.isEmpty() ? new PptDoc.Para() : a.paras.get(0);
                                    PptDoc.Run pr = proto.styleProto();
                                    a.paras.clear();
                                    String[] lines = s.split("\n", -1);
                                    for (String ln : lines) {
                                        PptDoc.Para p = new PptDoc.Para();
                                        p.align = proto.align;
                                        p.rtl = proto.rtl;
                                        p.bullet = proto.bullet;
                                        p.buChar = proto.buChar;
                                        p.buAutoNum = proto.buAutoNum;
                                        p.lvl = proto.lvl;
                                        p.marL = proto.marL;
                                        p.indent = proto.indent;
                                        /* v1.32: keep the numbering format/size too */
                                        p.buType = proto.buType;
                                        p.buStart = proto.buStart;
                                        p.buClr = proto.buClr;
                                        p.buSzPct = proto.buSzPct;
                                        p.buSizePt = proto.buSizePt;
                                        p.hasBullet = proto.hasBullet;
                                        PptDoc.Run r = new PptDoc.Run();
                                        r.text = ln;
                                        r.sizePt = pr.sizePt;
                                        r.bold = pr.bold;
                                        r.italic = pr.italic;
                                        r.underline = pr.underline;
                                        r.color = pr.color;
                                        r.font = pr.font;
                                        p.runs.add(r);
                                        a.paras.add(p);
                                    }
                                } else if (sel instanceof PptDoc.Table) {
                                    PptDoc.Table t = (PptDoc.Table) sel;
                                    if (!t.rows.isEmpty() && !t.rows.get(0).cells.isEmpty()) {
                                        PptDoc.TableCell cell = t.rows.get(0).cells.get(0);
                                        /* v1.27 FIX: keep the cell's existing style */
                                        PptDoc.Para proto = cell.paras.isEmpty() ? new PptDoc.Para() : cell.paras.get(0);
                                        PptDoc.Run pr = proto.styleProto();
                                        cell.paras.clear();
                                        PptDoc.Para p = new PptDoc.Para();
                                        p.align = proto.align;
                                        p.rtl = proto.rtl;
                                        PptDoc.Run r = new PptDoc.Run();
                                        r.text = s.replace("\n", " ");
                                        r.sizePt = pr.sizePt;
                                        r.bold = pr.bold;
                                        r.italic = pr.italic;
                                        r.underline = pr.underline;
                                        r.color = pr.color;
                                        r.font = pr.font;
                                        p.runs.add(r);
                                        cell.paras.add(p);
                                    }
                                }
                            }
                        });
                    }
                })
                .setNegativeButton("انصراف", (DialogInterface.OnClickListener) null)
                .show();
        et.postDelayed(new Runnable() {
            @Override
            public void run() {
                et.requestFocus();
                et.setSelection(et.getText().length());
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(et, 0);
            }
        }, 120);
    }

    /* v1.31: double-tap a TABLE cell edits THAT cell — the old path always
       edited cell(0,0) regardless of where the user tapped, which made the
       «ویرایش متن» button effectively unusable on real tables. */
    void editTableCell(final PptDoc.Table t, final int rowI, final int colI) {
        if (rowI < 0 || rowI >= t.rows.size()) return;
        PptDoc.TableRow row = t.rows.get(rowI);
        if (colI < 0 || colI >= row.cells.size()) return;
        final PptDoc.TableCell cell = row.cells.get(colI);
        StringBuilder txt = new StringBuilder();
        for (int i = 0; i < cell.paras.size(); i++) {
            if (i > 0) txt.append('\n');
            txt.append(cell.paras.get(i).plainText());
        }
        final EditText et = new EditText(this);
        et.setText(txt.toString());
        et.setTextColor(0xFF1A1A1A);
        et.setGravity(Gravity.RIGHT);
        et.setMinLines(3);
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(dp(18), dp(6), dp(18), 0);
        wrap.addView(et, new FrameLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this)
                .setTitle("ویرایش سلول " + Fa.d(rowI + 1) + "×" + Fa.d(colI + 1))
                .setView(wrap)
                .setPositiveButton("ثبت", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        final String s = et.getText().toString();
                        mutateSel(new Runnable() {
                            @Override
                            public void run() {
                                PptDoc.Para proto = cell.paras.isEmpty() ? new PptDoc.Para() : cell.paras.get(0);
                                PptDoc.Run pr = proto.styleProto();
                                cell.paras.clear();
                                String[] lines = s.split("\n", -1);
                                for (String ln : lines) {
                                    /* v1.32: full paragraph style (bullets,
                                       numbering, indents) survives a cell edit */
                                    PptDoc.Para p = new PptDoc.Para();
                                    p.copyFrom(proto);
                                    p.runs.clear();
                                    PptDoc.Run r = new PptDoc.Run();
                                    r.text = ln;
                                    r.sizePt = pr.sizePt > 0 ? pr.sizePt : 16f;
                                    r.bold = pr.bold;
                                    r.italic = pr.italic;
                                    r.underline = pr.underline;
                                    r.color = pr.color != 0 ? pr.color : 0xFF1F1F1F;
                                    r.font = pr.font;
                                    p.runs.add(r);
                                    cell.paras.add(p);
                                }
                            }
                        });
                    }
                })
                .setNegativeButton("انصراف", (DialogInterface.OnClickListener) null)
                .show();
        et.postDelayed(new Runnable() {
            @Override
            public void run() {
                et.requestFocus();
                et.setSelection(et.getText().length());
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(et, 0);
            }
        }, 120);
    }

    /* which table cell sits at doc-EMU point (ex,ey)? returns {row, col} */
    static int[] tableCellAt(PptDoc.Table t, float exEmu, float eyEmu) {
        float y = t.y;
        for (int ri = 0; ri < t.rows.size(); ri++) {
            PptDoc.TableRow row = t.rows.get(ri);
            float rh = row.heightEmu > 0 ? row.heightEmu : 360000f;
            if (eyEmu >= y && eyEmu < y + rh) {
                float x = t.x;
                for (int ci = 0; ci < row.cells.size() && ci < t.colWidths.size(); ci++) {
                    PptDoc.TableCell cell = row.cells.get(ci);
                    float cw = 0;
                    for (int m = 0; m < cell.spanR && ci + m < t.colWidths.size(); m++) {
                        cw += t.colWidths.get(ci + m);
                    }
                    if (exEmu >= x && exEmu < x + cw) return new int[]{ri, ci};
                    x += cw;
                }
                return null;
            }
            y += rh;
        }
        return null;
    }

    void duplicateSel() {
        if (sel == null) {
            Toast.makeText(this, "اول یک شکل را انتخاب کن", Toast.LENGTH_SHORT).show();
            return;
        }
        mutateSel(new Runnable() {
            @Override
            public void run() {
                PptDoc.Shape c = sel.copy();
                c.x += doc.slideW / 25;
                c.y += doc.slideH / 25;
                slide().shapes.add(c);
                sel = c;
            }
        });
    }

    void deleteSel() {
        if (sel == null) {
            Toast.makeText(this, "اول یک شکل را انتخاب کن", Toast.LENGTH_SHORT).show();
            return;
        }
        pushUndo();
        slide().shapes.remove(sel);
        sel = null;
        refreshAll();
    }

    void reorder(final int dir) {
        if (sel == null) return;
        List<PptDoc.Shape> list = slide().shapes;
        int i = list.indexOf(sel);
        if (i < 0) return;
        int j = Math.max(0, Math.min(list.size() - 1, i + dir));
        /* v1.29 FIX: the no-op case must bail BEFORE pushUndo() — undoing a
           no-op previously destroyed the redo stack and marked the doc dirty
           (phantom autosave rewrite) although nothing moved. */
        if (i == j) return;
        pushUndo();
        list.remove(i);
        list.add(j, sel);
        canvas.invalidate();
        filmstrip.rebuild();
    }

    /* ============================== slides ================================= */
    void addSlideDialog() {
        final String[] items = {"اسلاید خالی", "عنوان + متن", "دو ستون", "فقط عنوان"};
        new AlertDialog.Builder(this).setTitle("اسلاید جدید")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        pushUndo();
                        cur++;
                        doc.slides.add(cur, insert.templateSlideDoc(w));
                        sel = null;
                        refreshAll();
                    }
                }).show();
    }

    void duplicateSlide() {
        pushUndo();
        doc.slides.add(cur + 1, slide().copy());
        cur++;
        sel = null;
        refreshAll();
    }

    /* v1.21: speaker-notes editor — long-press a slide thumbnail →
       «یادداشت‌های ارائه...». Notes round-trip through the .pptx writer
       and display in the slideshow via the 📝 toggle. */
    void editNotes(final int idx) {
        if (idx < 0 || idx >= doc.slides.size()) return;
        final EditText et = new EditText(this);
        et.setText(doc.slides.get(idx).notes == null ? "" : doc.slides.get(idx).notes);
        et.setMinLines(6);
        et.setGravity(Gravity.TOP | Gravity.START);
        et.setTextSize(14f);
        et.setHint("متن یادداشت‌های ارائه‌دهنده برای اسلاید " + Fa.d(idx + 1) + "…");
        int pad = dp(14);
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(pad, dp(6), pad, 0);
        wrap.addView(et, new FrameLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this)
                .setTitle("یادداشت‌های ارائه — اسلاید " + Fa.d(idx + 1))
                .setView(wrap)
                .setPositiveButton("ذخیره", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String t = et.getText().toString().trim();
                        String old = doc.slides.get(idx).notes;
                        boolean changed = (t.length() == 0 && old != null && old.length() > 0)
                                || (t.length() > 0 && !t.equals(old));
                        if (changed) {
                            pushUndo();
                            doc.slides.get(idx).notes = t.length() == 0 ? null : t;
                            markDirty();
                            Toast.makeText(PptEditorActivity.this,
                                    "یادداشت ذخیره شد", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("انصراف", (DialogInterface.OnClickListener) null)
                .show();
    }

    void deleteSlide() {
        if (doc.slides.size() <= 1) {
            Toast.makeText(this, "آخرین اسلاید حذف نمی‌شود", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("حذف اسلاید")
                .setMessage("اسلاید " + Fa.d(cur + 1) + " حذف شود؟")
                .setPositiveButton("حذف", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        pushUndo();
                        doc.slides.remove(cur);
                        cur = Math.min(cur, doc.slides.size() - 1);
                        sel = null;
                        refreshAll();
                    }
                })
                .setNegativeButton("انصراف", (DialogInterface.OnClickListener) null).show();
    }

    void templateSlide(final int kind) {
        pushUndo();
        PptDoc.Slide s = insert.templateSlideDoc(kind);
        slide().background = s.background;
        slide().shapes.clear();
        slide().shapes.addAll(s.shapes);
        sel = null;
        refreshAll();
    }

    void shapeGallery() {
        GridLayoutLike g = new GridLayoutLike(this, 4);
        for (int i = 0; i < GALLERY_TYPES.length; i++) {
            final int type = GALLERY_TYPES[i];
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(dp(4), dp(4), dp(4), dp(4));
            ShapeIconView iv = new ShapeIconView(this, type);
            cell.addView(iv, new LinearLayout.LayoutParams(dp(52), dp(52)));
            TextView t = new TextView(this);
            t.setText(GALLERY_NAMES[i]);
            t.setTextSize(9.5f);
            t.setGravity(Gravity.CENTER);
            t.setTextColor(0xFF3B3B3B);
            cell.addView(t);
            cell.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    insertShape(type);
                    textDialogs.dlgDismiss();
                }
            });
            g.addView(cell);
        }
        ScrollView sc = new ScrollView(this);
        sc.addView(g);
        dlg = new AlertDialog.Builder(this).setTitle("گالری شکل‌ها").setView(sc)
                .setNegativeButton("انصراف", (DialogInterface.OnClickListener) null).show();
    }

    void themeGallery() {
        GridLayoutLike g = new GridLayoutLike(this, 3);
        for (int i = 0; i < THEME_NAMES.length; i++) {
            final int idx = i;
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(dp(4), dp(4), dp(4), dp(4));
            ThemeCard card = new ThemeCard(this, THEME_COLS[i]);
            cell.addView(card, new LinearLayout.LayoutParams(dp(78), dp(50)));
            TextView t = new TextView(this);
            t.setText(THEME_NAMES[i]);
            t.setTextSize(10f);
            t.setGravity(Gravity.CENTER);
            t.setTextColor(0xFF3B3B3B);
            cell.addView(t);
            cell.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    applyTheme(idx);
                    textDialogs.dlgDismiss();
                }
            });
            g.addView(cell);
        }
        ScrollView sc = new ScrollView(this);
        sc.addView(g);
        dlg = new AlertDialog.Builder(this).setTitle("پوسته‌های طراحی").setView(sc)
                .setNegativeButton("انصراف", (DialogInterface.OnClickListener) null).show();
    }

    void applyTheme(int idx) {
        pushUndo();
        final int[] cols = THEME_COLS[idx];
        doc.themeBgTop = cols[0];
        doc.themeBgBottom = cols[1];
        doc.themeAccent = cols[4];
        for (PptDoc.Slide s : doc.slides) {
            s.background = PptDoc.Fill.gradient(cols[0], cols[1], false);
            for (PptDoc.Shape sh : s.shapes) {
                List<PptDoc.Para> ps = parasOf(sh);
                if (ps == null) continue;
                for (PptDoc.Para p : ps) {
                    for (PptDoc.Run r : p.runs) {
                        if (r.color == 0xFF1F3864) r.color = cols[2];
                        else if (r.color == 0xFF404040) r.color = cols[3];
                    }
                }
            }
        }
        refreshAll();
    }

    /* ============================== slideshow ============================== */
    /* ▶ button: on slide 1 start immediately; otherwise ASK «از ابتدا / از
       همین اسلاید». The show runs in LANDSCAPE; left half = next (RTL
       reading), right half = previous; past the LAST slide it announces
       «به پایان ارائه رسیدید» and exits. */
    void startSlideshow() {
        if (doc.slides.isEmpty()) return;
        if (cur == 0) {
            beginShow(0);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("شروع نمایش")
                .setMessage("از کجا شروع شود؟")
                .setPositiveButton("از ابتدا", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        beginShow(0);
                    }
                })
                .setNeutralButton("از همین اسلاید (" + Fa.d(cur + 1) + ")", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        beginShow(cur);
                    }
                })
                .setNegativeButton("انصراف", (DialogInterface.OnClickListener) null)
                .show();
    }

    /* v1.23: نمای شبکه‌ای اسلایدها حین ارائه (مثل PowerPoint) — لمس هر
       بندانگشتی مستقیم به همان اسلاید می‌پرد. برای دک‌های بزرگ محافظ
       حافظه دارد (بندانگشتی‌های نصف‌عرض بالای ۶۰ اسلاید، فهرست
       شماره‌دار بالای ۱۲۰ اسلاید) */
    void showSlideGrid() {
        if (doc.slides.isEmpty()) return;
        int n = doc.slides.size();
        ScrollView sv = new ScrollView(this);
        LinearLayout list = null;
        GridLayoutLike grid = null;
        if (n > 120) {
            list = new LinearLayout(this);
            list.setOrientation(LinearLayout.VERTICAL);
            sv.addView(list);
        } else {
            grid = new GridLayoutLike(this, 4);
            sv.addView(grid);
        }
        final AlertDialog gd = new AlertDialog.Builder(this)
                .setTitle("اسلایدها (" + Fa.d(n) + ")")
                .setView(sv)
                .setNegativeButton("بستن", null)
                .show();
        gd.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                show.hideSystemUi();   /* دیالوگ نوار سیستم را برگردانده بود */
            }
        });
        if (list != null) {
            for (int i = 0; i < n; i++) {
                final int idx = i;
                TextView tv = new TextView(this);
                tv.setText("اسلاید " + Fa.d(i + 1));
                tv.setTextSize(16f);
                tv.setTextColor(0xFF3B3B3B);
                tv.setPadding(dp(12), dp(10), dp(12), dp(10));
                tv.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (showView != null) showView.go(idx);
                        gd.dismiss();
                    }
                });
                list.addView(tv);
            }
        } else {
            int cw = (int) ((getResources().getDisplayMetrics().widthPixels - dp(56)) / 4f);
            if (n > 60) cw /= 2;   /* دک بزرگ → بندانگشتی‌های کوچک‌تر */
            /* v1.27 FIX: the grid used to render EVERY slide thumbnail
               synchronously on the UI thread before the dialog was even
               visible — a 60-slide deck froze the app for seconds (ANR
               during a presentation). Cells are created empty and the
               bitmaps render on a background thread, streaming in. */
            final ImageView[] thumbs = new ImageView[n];
            for (int i = 0; i < n; i++) {
                final int idx = i;
                ImageView iv = new ImageView(this);
                thumbs[i] = iv;
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                iv.setBackgroundColor(0xFF2E2E2E);
                iv.setPadding(dp(2), dp(2), dp(2), dp(2));
                TextView num = new TextView(this);
                num.setText(Fa.d(i + 1));
                if (showView != null && i == showView.index) {
                    num.setTextColor(0xFFC62828);   /* اسلاید جاری پررنگ */
                    num.setTypeface(null, Typeface.BOLD);
                } else {
                    num.setTextColor(0xFF3B3B3B);
                }
                num.setTextSize(10f);
                num.setGravity(Gravity.CENTER);
                LinearLayout cell = new LinearLayout(this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER);
                cell.setPadding(dp(4), dp(4), dp(4), dp(4));
                cell.addView(iv, new LinearLayout.LayoutParams(cw, (int) (cw * doc.slideH / (float) doc.slideW)));
                cell.addView(num, new LinearLayout.LayoutParams(-1, dp(16)));
                cell.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (showView != null) showView.go(idx);
                        gd.dismiss();
                    }
                });
                grid.addView(cell);
            }
            final int cwF = cw;
            /* v1.29 FIX: read by the worker thread while the UI thread writes
               it in onDismiss — a plain boolean[] has no happens-before. */
            final java.util.concurrent.atomic.AtomicBoolean dismissed =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
            gd.setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override
                public void onDismiss(DialogInterface d) {
                    dismissed.set(true);
                    show.hideSystemUi();   /* دیالوگ نوار سیستم را برگردانده بود */
                }
            });
            new Thread(new Runnable() {
                @Override
                public void run() {
                    for (int i = 0; i < n; i++) {
                        if (dismissed.get()) return;
                        try {
                            float th = cwF * doc.slideH / (float) doc.slideW;
                            final Bitmap bmp = Bitmap.createBitmap(Math.max(1, cwF),
                                    Math.max(1, (int) th), Bitmap.Config.ARGB_8888);
                            Canvas bc = new Canvas(bmp);
                            bc.drawColor(0xFFFFFFFF);
                            SlideRenderer.render(bc, doc.slides.get(i), doc, bmp.getWidth() / (float) doc.slideW);
                            final int fi = i;
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    if (dismissed.get() || thumbs[fi] == null) { bmp.recycle(); return; }
                                    thumbs[fi].setImageBitmap(bmp);
                                }
                            });
                        } catch (Throwable t) {
                            /* one bad slide must not kill the rest */
                        }
                    }
                }
            }, "slidegrid-thumbs").start();
        }
    }

    void beginShow(int from) {
        if (showView == null) {
            showView = new SlideShowView(this);
            slideshowLayer.addView(showView, new FrameLayout.LayoutParams(-1, -1));
            slideshowLayer.setBackgroundColor(0xFF000000);
            show.addShowCloseBtn();
        }
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        /* v1.23: فول‌اسکرین واقعی — صفحه روشن بماند + رسم زیر ناچ/بریدگی
           صفحه (cutout) که قبلاً حاشیهٔ سیاه می‌انداخت و اسلاید کوچک
           می‌شد */
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            try {
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                getWindow().setAttributes(lp);
            } catch (Throwable ignored) {
            }
        }
        showView.penMode = false;
        /* v1.29 FIX: SlideShowView is REUSED across shows — the previous
           session's black-screen/laser/notes/scale/zoom state used to leak
           into the NEXT presentation (it could start BLACK, or with the
           laser hijacking taps, still zoomed in, ...). Reset everything
           PowerPoint resets between slide shows. */
        showView.blank = 0;
        showView.laser = false;
        showView.laserDown = false;
        showView.laserX = -1f;
        showView.laserY = -1f;
        showView.showNotes = false;
        showView.scaleMode = 0;
        showView.autoMode = 0;
        showView.userZoom = 1f;
        showView.panX = 0f;
        showView.panY = 0f;
        showView.animsActive = false;
        showView.strokes.clear();
        showView.strokeColors.clear();
        showView.strokeW.clear();
        showView.curStroke = null;
        showView.go(from);
        slideshowLayer.setVisibility(View.VISIBLE);
        show.hideSystemUi();
        showView.startClock();
    }

    /* small translucent ✕ pinned top-left so exiting is always possible
       (phones with gesture navigation) */
    void closeSlideshow() {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (showView != null) showView.stopClock();
        slideshowLayer.setVisibility(View.GONE);
        show.showSystemUi();
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        if (showView != null) cur = showView.index;
        refreshAll();
    }

    void save(boolean as) {
        if (fileUri == null || as) {
            saveAs();
            return;
        }
        writeTo(fileUri);
    }

    void saveAs() {
        String name = docName == null ? "ارائه.pptx" : docName;
        if (!name.toLowerCase().endsWith(".pptx")) name += ".pptx";
        /* v1.29 FIX: the SAF «انتخاب محل» picker suggested a hardcoded
           "ارائه.pptx", ignoring the current document's name. */
        final String pickName = name;
        final File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOCUMENTS), "PDFKhwan");
        if (!dir.exists()) dir.mkdirs();
        final File out = new File(dir, name);
        new AlertDialog.Builder(this)
                .setTitle("ذخیره در")
                .setMessage(out.getAbsolutePath())
                .setPositiveButton("ذخیره", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        writeTo(Uri.fromFile(out));
                    }
                })
                .setNeutralButton("انتخاب محل", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        Intent it = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                        it.addCategory(Intent.CATEGORY_OPENABLE);
                        it.setType("application/vnd.openxmlformats-officedocument.presentationml.presentation");
                        it.putExtra(Intent.EXTRA_TITLE, pickName);
                        startActivityForResult(it, REQ_SAVE_AS);
                    }
                })
                .setNegativeButton("انصراف", (DialogInterface.OnClickListener) null).show();
    }

    /* v1.29 FIX: SAF (content://) targets were written IN-PLACE — any
       mid-zip failure (OOM with a large embedded video, a provider error)
       truncated/corrupted the user's ONLY copy, and autosave repeated that
       exposure every 2.5s. Now the package is fully built in the cache dir
       first; only a COMPLETE zip is stream-copied over the SAF stream, so
       the original is intact while the zip is being built. */
    Exception writeToSaf(Uri target, PptDoc snapshot, String name) {
        File tmp = null;
        try {
            tmp = File.createTempFile("pdfkhwan_save", ".pptx", getCacheDir());
            OutputStream out = new FileOutputStream(tmp);
            try { PptxWriter.write(out, snapshot, name); } finally { closeQuietly(out); }
        } catch (Exception e) {
            if (tmp != null) tmp.delete();
            return e;
        }
        try {
            OutputStream out = getContentResolver().openOutputStream(target, "wt");
            if (out == null) { tmp.delete(); return new Exception("null SAF stream"); }
            try {
                InputStream in = new FileInputStream(tmp);
                try {
                    byte[] buf = new byte[65536];
                    int r;
                    while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
                    out.flush();
                } finally { closeQuietly(in); }
            } finally { closeQuietly(out); }
            tmp.delete();
            return null;
        } catch (Exception e) {
            tmp.delete();
            return e;
        }
    }

    void writeTo(final Uri uri) {
        /* remember the target immediately — a pending autosave must aim
           at the same place; the write itself runs on the save thread
           (atomic for file://) so big decks never jank the UI */
        fileUri = uri;
        String dn = queryDisplayName(uri);
        if (dn != null && dn.toLowerCase().endsWith(".pptx")) docName = dn;
        final PptDoc snapshot = cloneDoc();
        final String name = docName;
        final boolean fileScheme = "file".equals(uri.getScheme());
        final int gen = ++editGen;   /* the manual save supersedes older edits */
        if (autoRun != null) { autoHandler.removeCallbacks(autoRun); autoRun = null; }
        dirty = true;   /* cleared on success below; stays set if the write fails */
        updateTitle();
        saveExec.execute(new Runnable() {
            @Override public void run() {
                Exception err = null;
                try {
                    if (fileScheme) {
                        err = writeFileAtomic(new File(uri.getPath()), snapshot, name);
                    } else {
                        err = writeToSaf(uri, snapshot, name);
                    }
                } catch (Exception e) {
                    err = e;
                }
                final Exception ferr = err;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (ferr == null) {
                            saveIo.saveFails = 0;
                            if (editGen == gen) {
                                dirty = false;
                                updateTitle();
                            } else {
                                scheduleAutosave(); /* edits landed mid-save */
                            }
                            refreshAll();
                            Toast.makeText(PptEditorActivity.this, "ذخیره شد ✓", Toast.LENGTH_SHORT).show();
                        } else {
                            CrashGuard.append(PptEditorActivity.this,
                                    "ppt save: " + CrashGuard.stackToString(ferr));
                            Toast.makeText(PptEditorActivity.this,
                                    "ذخیره ممکن نشد — دسترسی فایل‌ها را بررسی کن", Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }
        });
    }

    void exportPdf() {
        /* rendering every slide to PDF can take seconds — off the UI thread */
        final PptDoc snapshot = cloneDoc();
        /* v1.29 FIX: capture docName on the UI thread (the field is UI-mutated;
        the old code read it on the worker) and strip the extension
        case-INSENSITIVELY — "FILE.PPTX" used to become "FILE.PPTX.pdf". */
        final String nm = docName == null ? "ارائه" : docName.replaceAll("(?i)\\.pptx?$", "");
        saveExec.execute(new Runnable() {
            @Override public void run() {
                Exception err = null;
                String outName = null;
                try {
                    File dir = new File(Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOCUMENTS), "PDFKhwan");
                    if (!dir.exists()) dir.mkdirs();
                    String name = nm + ".pdf";
                    File out = new File(dir, name);
                    outName = out.getName();
                    PdfDocument pdf = new PdfDocument();
                    try {
                        /* v7 FIX: 1pt = 1px (PdfDocument PageInfo is in POINTS,
                           not pixels) — 2x produced 26.7"×15" pages with
                           oversized text; PowerPoint exports 16:9 as 960×540pt */
                        float pxPerEmu = 1f / 12700f;
                        for (int i = 0; i < snapshot.slides.size(); i++) {
                            int pw = Math.round(snapshot.slideW * pxPerEmu);
                            int ph = Math.round(snapshot.slideH * pxPerEmu);
                            PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(pw, ph, i + 1).create();
                            PdfDocument.Page page = pdf.startPage(info);
                            SlideRenderer.render(page.getCanvas(), snapshot.slides.get(i), snapshot, pxPerEmu);
                            pdf.finishPage(page);
                        }
                        FileOutputStream fo = new FileOutputStream(out);
                        try { pdf.writeTo(fo); } finally { closeQuietly(fo); }
                    } finally {
                        /* v7 FIX: the native document leaked when any render
                           step threw */
                        try { pdf.close(); } catch (Throwable ignored) {}
                    }
                } catch (Throwable t) {
                    err = (t instanceof Exception) ? (Exception) t : new RuntimeException(t);
                }
                final Exception ferr = err;
                final String fname = outName;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (ferr == null) {
                            Toast.makeText(PptEditorActivity.this,
                                    "PDF ساخته شد ✓\n" + fname, Toast.LENGTH_LONG).show();
                        } else {
                            CrashGuard.append(PptEditorActivity.this,
                                    "ppt exportPdf: " + CrashGuard.stackToString(ferr));
                            Toast.makeText(PptEditorActivity.this, "ساخت PDF ممکن نشد", Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }
        });
    }

    void moreMenu(View anchor) {
        android.widget.PopupMenu pm = new android.widget.PopupMenu(this, anchor);
        pm.getMenu().add(0, 1, 0, "خروجی PDF");
        pm.getMenu().add(0, 2, 0, "ذخیره در…");
        pm.getMenu().add(0, 3, 0, "اطلاعات فایل");
        pm.getMenu().add(0, 4, 0, "جست‌وجو و جایگزینی…");
        pm.setOnMenuItemClickListener(new android.widget.PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(android.view.MenuItem item) {
                switch (item.getItemId()) {
                    case 1:
                        exportPdf();
                        return true;
                    case 2:
                        saveAs();
                        return true;
                    case 3:
                        info();
                        return true;
                    case 4:
                        textDialogs.findReplaceDialog();
                        return true;
                }
                return false;
            }
        });
        pm.show();
    }

    void info() {
        int shapes = 0;
        for (PptDoc.Slide s : doc.slides) shapes += s.shapes.size();
        Toast.makeText(this, "نام: " + docName + "\nاسلایدها: " + Fa.d(doc.slides.size())
                + "\nشکل‌ها: " + Fa.d(shapes), Toast.LENGTH_LONG).show();
    }

    /* ============================== misc =================================== */
    void zoomBy(float f) {
        zoom = Math.max(0.4f, Math.min(6f, zoom * f));
        canvas.clampPanHard();   /* v1.23: تغییر زوم → پن هم در محدوده بماند */
        canvas.invalidate();
        statusZoom.setText(Fa.d(Math.round(zoom * 100)) + "٪");
    }

    void resetZoom() {
        zoom = 1f;
        canvas.invalidate();
        statusZoom.setText("۱۰۰٪");
    }

    private static void closeQuietly(java.io.Closeable c) {
        try {
            if (c != null) c.close();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onBackPressed() {
        if (cropMode) {
            exitCropMode(false);
            return;
        }
        if (inlineLayer != null && inlineLayer.getVisibility() == View.VISIBLE) {
            commitInlineEditor(false);
            return;
        }
        if (slideshowLayer != null && slideshowLayer.getVisibility() == View.VISIBLE) {
            closeSlideshow();
            return;
        }
        if (sel != null) {
            sel = null;
            canvas.invalidate();
            return;
        }
        /* v7 FIX: a fresh (never saved) presentation with unsaved edits was
           discarded SILENTLY on back-press — autosave never runs without a
           fileUri, so all work vanished. Offer save-as first. */
        if (dirty && fileUri == null && doc != null && doc.slides.size() > 0) {
            new AlertDialog.Builder(this)
                    .setTitle("تغییرات ذخیره نشده")
                    .setMessage("این ارائه هنوز ذخیره نشده است. قبل از خروج ذخیره شود؟")
                    .setPositiveButton("ذخیره", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            saveAs();
                        }
                    })
                    .setNeutralButton("خروج بدون ذخیره", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            dirty = false;
                            PptEditorActivity.super.onBackPressed();
                        }
                    })
                    .setNegativeButton("ماندن", null)
                    .setCancelable(true)
                    .show();
            return;
        }
        super.onBackPressed();
    }

    /* =======================================================================
       FilmStrip — horizontal slide thumbnails at the BOTTOM of the screen
       (PowerPoint-mobile phone layout; the old side rail ate too much
       width on phones). RTL order: slide 1 sits at the RIGHT end. Tapping
       a thumbnail jumps the big canvas to that slide; long-press opens
       the per-slide menu; the ＋ cell (far LEFT in RTL) appends a slide.
       ======================================================================= */
    class FilmStrip extends HorizontalScrollView {
        private final LinearLayout list;
        static final int THUMB_W = 68;          /* dp */

        FilmStrip(Context ctx) {
            super(ctx);
            setBackgroundColor(0xFF2E2E2E);
            setHorizontalScrollBarEnabled(false);
            list = new LinearLayout(ctx);
            list.setOrientation(LinearLayout.HORIZONTAL);
            list.setGravity(Gravity.CENTER_VERTICAL);
            list.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);   /* slide 1 at the right */
            int p = dp(6);
            list.setPadding(p, p, p, p);
            addView(list, new ViewGroup.LayoutParams(-2, -2));
        }

        void rebuild() {
            list.removeAllViews();
            for (int i = 0; i < doc.slides.size(); i++) {
                final int idx = i;
                LinearLayout cell = new LinearLayout(PptEditorActivity.this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER_HORIZONTAL);
                cell.setPadding(dp(3), 0, dp(3), 0);

                FrameLayout card = new FrameLayout(PptEditorActivity.this);
                SlideThumb thumb = new SlideThumb(PptEditorActivity.this, i);
                card.addView(thumb, new FrameLayout.LayoutParams(-1, -2));
                card.setPadding(dp(2), dp(2), dp(2), dp(2));
                card.setBackgroundColor(idx == cur ? 0xFFB7472A : 0xFF555555);

                TextView num = new TextView(PptEditorActivity.this);
                num.setText(Fa.d(i + 1));
                num.setTextColor(idx == cur ? 0xFFFFFFFF : 0xFF9A9A9A);
                num.setTextSize(10f);
                num.setGravity(Gravity.CENTER);

                cell.addView(card, new LinearLayout.LayoutParams(dp(THUMB_W), -2));
                cell.addView(num, new LinearLayout.LayoutParams(dp(THUMB_W), -2));

                card.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        gotoSlide(idx);
                    }
                });
                card.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override
                    public boolean onLongClick(View v) {
                        slideMenu(idx);
                        return true;
                    }
                });
                list.addView(cell, new LinearLayout.LayoutParams(-2, -2));
            }

            /* ＋ append cell — last child → far LEFT in RTL */
            LinearLayout addCell = new LinearLayout(PptEditorActivity.this);
            addCell.setOrientation(LinearLayout.VERTICAL);
            addCell.setGravity(Gravity.CENTER);
            addCell.setPadding(dp(3), 0, dp(3), 0);
            TextView add = new TextView(PptEditorActivity.this);
            add.setText("＋");
            add.setTextColor(0xFFEDEDED);
            add.setTextSize(22f);
            add.setGravity(Gravity.CENTER);
            add.setBackgroundColor(0xFF3A3A3A);
            int ah = Math.round(dp(THUMB_W) * doc.slideH / (float) doc.slideW) + dp(4);
            addCell.addView(add, new LinearLayout.LayoutParams(dp(THUMB_W), ah));
            TextView addLbl = new TextView(PptEditorActivity.this);
            addLbl.setText("اسلاید");
            addLbl.setTextColor(0xFF9A9A9A);
            addLbl.setTextSize(10f);
            addLbl.setGravity(Gravity.CENTER);
            addCell.addView(addLbl, new LinearLayout.LayoutParams(dp(THUMB_W), -2));
            add.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    addSlideDialog();
                }
            });
            list.addView(addCell, new LinearLayout.LayoutParams(-2, -2));

            post(new Runnable() {
                @Override
                public void run() {
                    centerOn(cur);
                }
            });
        }

        /* scroll so thumbnail #idx sits in the middle of the strip
           (works for both LTR and RTL layouts — coordinates are real) */
        void centerOn(int idx) {
            if (list.getChildCount() <= idx) return;
            View c = list.getChildAt(idx);
            int target = c.getLeft() + c.getWidth() / 2 - getWidth() / 2;
            int max = Math.max(0, list.getWidth() - getWidth());
            target = Math.max(0, Math.min(max, target));
            smoothScrollTo(target, 0);
        }

        void slideMenu(final int idx) {
            android.widget.PopupMenu pm = new android.widget.PopupMenu(PptEditorActivity.this, this);
            pm.getMenu().add(0, 1, 0, "اسلاید جدید بعدی");
            pm.getMenu().add(0, 2, 0, "کپی اسلاید");
            pm.getMenu().add(0, 3, 0, "انتقال بالا");
            pm.getMenu().add(0, 4, 0, "انتقال پایین");
            pm.getMenu().add(0, 6, 0, "یادداشت‌های ارائه...");
            pm.getMenu().add(0, 5, 0, "حذف اسلاید");
            pm.setOnMenuItemClickListener(new android.widget.PopupMenu.OnMenuItemClickListener() {
                @Override
                public boolean onMenuItemClick(android.view.MenuItem item) {
                    switch (item.getItemId()) {
                        case 1:
                            pushUndo();
                            doc.slides.add(idx + 1, insert.templateSlideDoc(0));
                            cur = idx + 1;
                            refreshAll();
                            return true;
                        case 2:
                            pushUndo();
                            doc.slides.add(idx + 1, doc.slides.get(idx).copy());
                            cur = idx + 1;
                            refreshAll();
                            return true;
                        case 3:
                            if (idx > 0) {
                                pushUndo();
                                doc.slides.add(idx - 1, doc.slides.remove(idx));
                                cur = idx - 1;
                                refreshAll();
                            }
                            return true;
                        case 4:
                            if (idx < doc.slides.size() - 1) {
                                pushUndo();
                                doc.slides.add(idx + 1, doc.slides.remove(idx));
                                cur = idx + 1;
                                refreshAll();
                            }
                            return true;
                        case 5:
                            cur = idx;
                            deleteSlide();
                            return true;
                        case 6:
                            editNotes(idx);
                            return true;
                    }
                    return false;
                }
            });
            pm.show();
        }
    }

    /* =======================================================================
       PptCanvas — the editing surface
       ======================================================================= */
    class PptCanvas extends View {
        private float baseScale = 1f;
        private float offX = 0f, offY = 0f;
        private float lastX, lastY;
        private float rotLastX, rotLastY;
        private int mode = 0;   /* 0 pan, 1 move, 2..9 resize, 10 rotate */
        private ScaleGestureDetector scaleDetector;
        private GestureDetector tapDetector;
        private final Paint selPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint guidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        /* smart-guide state: while dragging a shape its center/edges snap
           to the slide center/edges and other shapes' centers/edges */
        private float guideX = Float.MIN_VALUE, guideY = Float.MIN_VALUE;
        private boolean movedFar = false;
        /* v1.27: one undo snapshot per gesture (see ACTION_MOVE) */
        private boolean gestureUndoPushed = false;
        private float downX, downY;

        PptCanvas(Context ctx) {
            super(ctx);
            setBackgroundColor(OFFICE_CANVAS_BG);
            selPaint.setColor(OFFICE_SEL);
            selPaint.setStyle(Paint.Style.STROKE);
            selPaint.setStrokeWidth(dp(2) * 1f);
            guidePaint.setColor(0xFFFF3355);
            guidePaint.setStyle(Paint.Style.STROKE);
            guidePaint.setStrokeWidth(dp(1));
            guidePaint.setPathEffect(new DashPathEffect(new float[]{dp(6), dp(4)}, 0));
            scaleDetector = new ScaleGestureDetector(ctx, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(ScaleGestureDetector d) {
                    zoom = Math.max(0.4f, Math.min(6f, zoom * d.getScaleFactor()));
                    statusZoom.setText(Fa.d(Math.round(zoom * 100)) + "٪");
                    clampPanRubber();   /* v1.23: پینچ-زوم اسلاید را رها نمی‌کند */
                    invalidate();
                    return true;
                }
            });
            /* PowerPoint mobile gestures: double-tap a TEXT shape edits it in
               place; double-tap empty canvas toggles zoom 1x <-> 2x */
            tapDetector = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDoubleTap(MotionEvent e) {
                    PptDoc.Shape hit = hitShape(e.getX(), e.getY());
                    if (hit != null) {
                        sel = hit;
                        invalidate();
                        if (hit instanceof PptDoc.Chart) {
                            chartDialog((PptDoc.Chart) hit);
                        } else if (hit instanceof PptDoc.Table) {
                            /* v1.31: edit the TAPPED cell, not always (0,0) */
                            PptDoc.Table t = (PptDoc.Table) hit;
                            int[] rc = tableCellAt(t, (e.getX() - offX) / s(), (e.getY() - offY) / s());
                            if (rc != null) editTableCell(t, rc[0], rc[1]);
                            else editSelectedText();
                        } else if (parasOf(hit) != null) {
                            showInlineEditor();
                        } else if (hit instanceof PptDoc.Video) {
                            playVideo((PptDoc.Video) hit);
                        } else {
                            editSelectedText();
                        }
                        return true;
                    }
                    if (zoom > 1.05f) resetView();
                    else {
                        zoom = 2f;
                        offX = (getWidth() - doc.slideW * s()) / 2f;
                        offY = (getHeight() - doc.slideH * s()) / 2f;
                        statusZoom.setText("۲۰۰٪");
                        clampPanHard();
                        invalidate();
                    }
                    return true;
                }
            });
        }

        private float s() {
            return baseScale * zoom;
        }

        /* public accessors for the inline editor overlay */
        float sPub() { return s(); }

        RectF shapeRectPub(PptDoc.Shape sh) { return shapeRect(sh); }

        private void fitScale() {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0 || doc.slideW <= 0) return;
            float bs = Math.min(w / (float) doc.slideW, h / (float) doc.slideH) * 0.92f;
            if (bs != baseScale) {
                baseScale = bs;
                offX = (w - doc.slideW * s()) / 2f;
                offY = (h - doc.slideH * s()) / 2f;
            }
        }

        /* recenter + refit (filmstrip thumbnail tap / slideshow exit) */
        void resetView() {
            zoom = 1f;
            statusZoom.setText("۱۰۰٪");
            baseScale = 0;
            offX = 0;
            offY = 0;
            fitScale();
            invalidate();
        }

        /* v1.23: محدودهٔ مجاز پن — اسلاید هرگز کامل بیرون از صفحه نمی‌رود.
           وقتی اسلاید از viewport بزرگ‌تر است، لبه‌ها باید viewport را
           بپوشانند؛ وقتی کوچک‌تر است، وسط می‌ماند (با کمی کشسانی). */
        private void panBounds(float[] lo, float[] hi) {
            int w = getWidth(), h = getHeight();
            float sw = doc.slideW * s(), sh = doc.slideH * s();
            if (sw <= w) {
                float c = (w - sw) / 2f;
                lo[0] = c - sw * 0.15f;
                hi[0] = c + sw * 0.15f;
            } else {
                lo[0] = w - sw;
                hi[0] = 0f;
            }
            if (sh <= h) {
                float c = (h - sh) / 2f;
                lo[1] = c - sh * 0.15f;
                hi[1] = c + sh * 0.15f;
            } else {
                lo[1] = h - sh;
                hi[1] = 0f;
            }
        }

        private void clampPanRubber() {
            float[] lo = new float[2], hi = new float[2];
            panBounds(lo, hi);
            if (offX < lo[0]) offX = lo[0] - (lo[0] - offX) * 0.35f;
            if (offX > hi[0]) offX = hi[0] + (offX - hi[0]) * 0.35f;
            if (offY < lo[1]) offY = lo[1] - (lo[1] - offY) * 0.35f;
            if (offY > hi[1]) offY = hi[1] + (offY - hi[1]) * 0.35f;
        }

        private void clampPanHard() {
            float[] lo = new float[2], hi = new float[2];
            panBounds(lo, hi);
            if (offX < lo[0]) offX = lo[0];
            if (offX > hi[0]) offX = hi[0];
            if (offY < lo[1]) offY = lo[1];
            if (offY > hi[1]) offY = hi[1];
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            baseScale = 0;
            fitScale();
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            fitScale();
            c.save();
            c.translate(offX, offY);
            /* NOTE: SlideRenderer bakes `scale` into every coordinate itself
               (x*scale, pxPerPt = 12700*scale …) — it must be drawn in a
               translate-only transform. Applying c.scale() here TOO would
               double-scale the slide (microscopic / invisible canvas). */
            SlideRenderer.render(c, slide(), doc, s());
            c.restore();

            if (sel != null) {
                RectF r = shapeRect(sel);
                if (r != null) {
                    c.save();
                    if (sel.rot != 0) c.rotate(sel.rot / 60000f, r.centerX(), r.centerY());
                    c.drawRect(r, selPaint);
                    drawHandles(c, r);
                    c.restore();
                }
            }

            /* v1.23: پوشش برش تصویر — روی همه‌چیز رسم می‌شود */
            if (cropMode && sel instanceof PptDoc.Picture) {
                drawCropOverlay(c);
            }

            /* smart guides while dragging (PowerPoint red dashed lines) */
            if (mode == 1 && sel != null) {
                if (guideX != Float.MIN_VALUE)
                    c.drawLine(guideX, 0, guideX, getHeight(), guidePaint);
                if (guideY != Float.MIN_VALUE)
                    c.drawLine(0, guideY, getWidth(), guideY, guidePaint);
            }
        }

        private RectF shapeRect(PptDoc.Shape sh) {
            return new RectF(offX + sh.x * s(), offY + sh.y * s(),
                    offX + (sh.x + sh.w) * s(), offY + (sh.y + sh.h) * s());
        }

        private float clampf(float v, float lo, float hi) {
            return v < lo ? lo : (v > hi ? hi : v);
        }

        /* v1.23: مستطیل پنجرهٔ برش روی صفحه (کراپ‌ها نسبت به ابعاد شکل) */
        private RectF cropWindow(PptDoc.Picture pic, RectF r) {
            return new RectF(
                    r.left + pic.cropL * r.width(),
                    r.top + pic.cropT * r.height(),
                    r.right - pic.cropR * r.width(),
                    r.bottom - pic.cropB * r.height());
        }

        /* v1.23: پوشش گرافیکی برش — تصویر کامل (بدون برش) زیر پنجرهٔ روشن،
           تیره‌کردن چهار نوار بیرونی، قاب سفید و چهار دستگیرهٔ لبه */
        private void drawCropOverlay(Canvas c) {
            PptDoc.Picture pic = (PptDoc.Picture) sel;
            RectF r = shapeRect(sel);
            c.save();
            if (pic.rot != 0) c.rotate(pic.rot / 60000f, r.centerX(), r.centerY());
            PptDoc.Picture tmp = new PptDoc.Picture();
            tmp.imgData = pic.imgData;
            tmp.imgFormat = pic.imgFormat;
            tmp.w = pic.w;
            tmp.h = pic.h;
            SlideRenderer.drawPicture(c, tmp, r.left, r.top, r.right, r.bottom);
            RectF cw = cropWindow(pic, r);
            Paint dim = new Paint();
            dim.setColor(0x88000000);
            c.drawRect(r.left, r.top, r.right, cw.top, dim);
            c.drawRect(r.left, cw.bottom, r.right, r.bottom, dim);
            c.drawRect(r.left, Math.max(r.top, cw.top), cw.left, Math.min(r.bottom, cw.bottom), dim);
            c.drawRect(cw.right, Math.max(r.top, cw.top), r.right, Math.min(r.bottom, cw.bottom), dim);
            Paint brd = new Paint(Paint.ANTI_ALIAS_FLAG);
            brd.setStyle(Paint.Style.STROKE);
            brd.setStrokeWidth(dp(2));
            brd.setColor(0xFFFFFFFF);
            c.drawRect(cw, brd);
            Paint hf = new Paint(Paint.ANTI_ALIAS_FLAG);
            hf.setColor(0xFFFFFFFF);
            Paint hr = new Paint(Paint.ANTI_ALIAS_FLAG);
            hr.setStyle(Paint.Style.STROKE);
            hr.setStrokeWidth(dp(2));
            hr.setColor(0xFFB7472A);
            float[] hx = {cw.left, cw.right, cw.centerX(), cw.centerX()};
            float[] hy = {cw.centerY(), cw.centerY(), cw.top, cw.bottom};
            for (int i = 0; i < 4; i++) {
                c.drawCircle(hx[i], hy[i], dp(6), hf);
                c.drawCircle(hx[i], hy[i], dp(6), hr);
            }
            c.restore();
        }

        /* v1.23: لمس در حالت برش — فقط کشیدن دستگیره‌های لبه (چپ/راست/
           بالا/پایین)؛ سایر لمس‌ها قورت داده می‌شوند (بوم قفل است) */
        private boolean handleCropTouch(MotionEvent e) {
            if (!(sel instanceof PptDoc.Picture)) {
                exitCropMode(false);
                return true;
            }
            PptDoc.Picture pic = (PptDoc.Picture) sel;
            RectF r = shapeRect(sel);
            RectF cw = cropWindow(pic, r);
            float x = e.getX(), y = e.getY();
            float tol = dp(18);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    cropHandle = -1;
                    if (Math.abs(x - cw.left) < tol && Math.abs(y - cw.centerY()) < tol) cropHandle = 0;
                    else if (Math.abs(x - cw.right) < tol && Math.abs(y - cw.centerY()) < tol) cropHandle = 1;
                    else if (Math.abs(x - cw.centerX()) < tol && Math.abs(y - cw.top) < tol) cropHandle = 2;
                    else if (Math.abs(x - cw.centerX()) < tol && Math.abs(y - cw.bottom) < tol) cropHandle = 3;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    /* v1.29 FIX: crop was NOT undoable (exitCropMode only
                       markDirty()s) — undo after a crop used to revert a much
                       OLDER action as well. Push the undo snapshot exactly
                       once, right before the first real handle drag. */
                    if (cropHandle >= 0 && !cropUndoPushed) {
                        cropUndoPushed = true;
                        pushUndo();
                    }
                    if (cropHandle == 0)
                        pic.cropL = clampf((x - r.left) / r.width(), 0f, 0.95f - pic.cropR);
                    else if (cropHandle == 1)
                        pic.cropR = clampf((r.right - x) / r.width(), 0f, 0.95f - pic.cropL);
                    else if (cropHandle == 2)
                        pic.cropT = clampf((y - r.top) / r.height(), 0f, 0.95f - pic.cropB);
                    else if (cropHandle == 3)
                        pic.cropB = clampf((r.bottom - y) / r.height(), 0f, 0.95f - pic.cropT);
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    cropHandle = -1;
                    return true;
            }
            return true;
        }

        private void drawHandles(Canvas c, RectF r) {
            Paint hp = new Paint(selPaint);
            hp.setStyle(Paint.Style.FILL);
            float rad = dp(5);
            for (int i = 0; i < 8; i++) {
                float[] pt = handlePos(r, i);
                c.drawCircle(pt[0], pt[1], rad, hp);
            }
            float[] rot = handlePos(r, 8);
            c.drawCircle(rot[0], rot[1] - dp(18), rad, hp);
            c.drawLine(rot[0], rot[1], rot[0], rot[1] - dp(18), selPaint);
        }

        private float[] handlePos(RectF r, int i) {
            switch (i) {
                case 0: return new float[]{r.left, r.top};
                case 1: return new float[]{r.centerX(), r.top};
                case 2: return new float[]{r.right, r.top};
                case 3: return new float[]{r.right, r.centerY()};
                case 4: return new float[]{r.right, r.bottom};
                case 5: return new float[]{r.centerX(), r.bottom};
                case 6: return new float[]{r.left, r.bottom};
                case 7: return new float[]{r.left, r.centerY()};
                default: return new float[]{r.centerX(), r.top};
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (cropMode) return handleCropTouch(e);
            tapDetector.onTouchEvent(e);
            scaleDetector.onTouchEvent(e);
            if (scaleDetector.isInProgress()) {
                mode = 0;
                return true;
            }
            float x = e.getX(), y = e.getY();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastX = x;
                    lastY = y;
                    rotLastX = x;
                    rotLastY = y;
                    downX = x;
                    downY = y;
                    movedFar = false;
                    gestureUndoPushed = false;
                    guideX = Float.MIN_VALUE;
                    guideY = Float.MIN_VALUE;
                    if (sel != null) {
                        RectF r = shapeRect(sel);
                        float hx = x, hy = y;
                        if (sel.rot != 0 && r != null) {
                            /* v1.27 FIX: handles of a ROTATED shape are drawn
                               rotated but were hit-tested unrotated — after
                               rotating a shape you could no longer grab its
                               corner handles. Un-rotate the tap first. */
                            double a = Math.toRadians(-sel.rot / 60000f);
                            float cx = r.centerX(), cy = r.centerY();
                            float dx = x - cx, dy = y - cy;
                            hx = (float) (cx + dx * Math.cos(a) - dy * Math.sin(a));
                            hy = (float) (cy + dx * Math.sin(a) + dy * Math.cos(a));
                        }
                        int h = hitHandle(r, hx, hy);
                        if (h >= 0) {
                            mode = h == 8 ? 10 : (h + 2);
                            return true;
                        }
                    }
                    PptDoc.Shape hit = hitShape(x, y);
                    if (hit != null) {
                        sel = hit;
                        mode = 1;
                        ribbons.syncContextualTab();
                        invalidate();
                        return true;
                    }
                    sel = null;
                    mode = 0;
                    ribbons.syncContextualTab();
                    invalidate();
                    return true;

                case MotionEvent.ACTION_MOVE: {
                    float dx = x - lastX, dy = y - lastY;
                    lastX = x;
                    lastY = y;
                    if (Math.abs(x - downX) > dp(6) || Math.abs(y - downY) > dp(6)) movedFar = true;
                    /* v1.27 FIX: move/resize/rotate/crop gestures are now
                       UNDOABLE — an undo snapshot is pushed ONCE when the
                       gesture first really moves (before, ⟲ reverted the
                       PREVIOUS edit too: dragging then undoing jumped two
                       actions back, and crop was never undoable). */
                    if (movedFar && !gestureUndoPushed && mode != 0 && sel != null) {
                        gestureUndoPushed = true;
                        pushUndo();
                    }
                    if (mode == 0) {
                        /* v1.23 BUGFIX: در حالت معمول (زوم=۱) اسلاید قفل است
                           و با کشیدن جابه‌جا نمی‌شود؛ در زوم>۱ فقط تا لبهٔ
                           مجاز + کشسان (rubber-band) جابه‌جا می‌شود. */
                        if (zoom > 1.02f) {
                            offX += dx;
                            offY += dy;
                            clampPanRubber();
                        }
                        invalidate();
                    } else if (mode == 1 && sel != null) {
                        sel.x += Math.round(dx / s());
                        sel.y += Math.round(dy / s());
                        applySmartGuides();
                        invalidate();
                    } else if (mode >= 2 && mode <= 9 && sel != null) {
                        applyResize(sel, mode - 2, dx / s(), dy / s());
                        invalidate();
                    } else if (mode == 10 && sel != null) {
                        RectF r = shapeRect(sel);
                        float cx = r.centerX(), cy = r.centerY();
                        double a1 = Math.atan2(rotLastY - cy, rotLastX - cx);
                        double a2 = Math.atan2(y - cy, x - cx);
                        rotLastX = x;
                        rotLastY = y;
                        double deg = Math.toDegrees(a2 - a1);
                        if (Math.abs(deg) > 0.4) {
                            sel.rotateBy((int) Math.round(deg * 60000));
                            invalidate();
                        }
                    }
                    return true;
                }

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (mode != 0) {
                        filmstrip.rebuild();
                        /* drag / resize / rotate are real edits (no pushUndo
                           on this path) — mark dirty for ALL gesture modes */
                        if (movedFar && sel != null) markDirty();
                        /* v1.22: a clean TAP on a shape is a click — videos
                           play, hyperlink runs open (PowerPoint behaviour) */
                        if (!movedFar && sel != null) {
                            if (sel instanceof PptDoc.Video) {
                                playVideo((PptDoc.Video) sel);
                            } else {
                                RectF r = shapeRect(sel);
                                if (r != null && sel.rot == 0) {
                                    /* v1.27 FIX: hitLink expects CANVAS-local
                                       coordinates — the canvas pan offset
                                       (offX/offY) was never subtracted, so
                                       links tapped open-the-wrong-run or
                                       nothing whenever the slide wasn't at
                                       origin (always — it's centered) */
                                    String url = SlideRenderer.hitLink(sel, x - offX, y - offY, s());
                                    if (url != null) {
                                        openLink(url);
                                    }
                                }
                            }
                        }
                    }
                    if (mode == 0) clampPanHard();
                    guideX = Float.MIN_VALUE;
                    guideY = Float.MIN_VALUE;
                    mode = 0;
                    invalidate();
                    return true;
            }
            return false;
        }

        /* PowerPoint SMART GUIDES: snap the dragged shape's center/edges to
           the slide's center/edges and to other shapes' centers/edges within
           ~10px; draw red dashed guide lines at the snapped coordinates. */
        private void applySmartGuides() {
            if (sel == null) return;
            guideX = Float.MIN_VALUE;
            guideY = Float.MIN_VALUE;
            float tolPx = dp(10);
            float tolDoc = tolPx / s();
            List<Float> xs = new ArrayList<>();
            List<Float> ys = new ArrayList<>();
            xs.add(doc.slideW / 2f); xs.add(0f); xs.add((float) doc.slideW);
            ys.add(doc.slideH / 2f); ys.add(0f); ys.add((float) doc.slideH);
            for (PptDoc.Shape sh : slide().shapes) {
                if (sh == sel) continue;
                xs.add(sh.x + sh.w / 2f);
                ys.add(sh.y + sh.h / 2f);
            }
            float cx = sel.x + sel.w / 2f, cy = sel.y + sel.h / 2f;
            for (float target : xs) {
                if (Math.abs(cx - target) < tolDoc) {
                    sel.x = Math.round(target - sel.w / 2f);
                    guideX = offX + target * s();
                    break;
                }
            }
            for (float target : ys) {
                if (Math.abs(cy - target) < tolDoc) {
                    sel.y = Math.round(target - sel.h / 2f);
                    guideY = offY + target * s();
                    break;
                }
            }
        }

        private int hitHandle(RectF r, float x, float y) {
            if (r == null) return -1;
            float tol = dp(18);
            for (int i = 0; i < 9; i++) {
                float[] pt = handlePos(r, i);
                float py = i == 8 ? pt[1] - dp(18) : pt[1];
                if (Math.abs(x - pt[0]) < tol && Math.abs(y - py) < tol) return i;
            }
            return -1;
        }

        private PptDoc.Shape hitShape(float px, float py) {
            PptDoc.Slide s = slide();
            for (int i = s.shapes.size() - 1; i >= 0; i--) {
                PptDoc.Shape sh = s.shapes.get(i);
                RectF r = shapeRect(sh);
                if (sh.rot != 0) {
                    double a = Math.toRadians(-sh.rot / 60000f);
                    float cx = r.centerX(), cy = r.centerY();
                    float dx = px - cx, dy = py - cy;
                    float nx = (float) (cx + dx * Math.cos(a) - dy * Math.sin(a));
                    float ny = (float) (cy + dx * Math.sin(a) + dy * Math.cos(a));
                    px = nx;
                    py = ny;
                }
                if (r.contains(px, py)) return sh;
            }
            return null;
        }

        private void applyResize(PptDoc.Shape sh, int handle, float dx, float dy) {
            /* v1.31 FIX: resize along the ROTATED shape's own axes — the
               handles are drawn rotated, but the drag delta arrived in
               screen space, so resizing a rotated shape skewed it (dragging
               its top handle sideways moved the width instead). Un-rotate
               the delta into shape-local space first, exactly like the
               handle hit-test already does. */
            if (sh.rot != 0) {
                double a = Math.toRadians(-sh.rot / 60000f);
                float ndx = (float) (dx * Math.cos(a) - dy * Math.sin(a));
                float ndy = (float) (dx * Math.sin(a) + dy * Math.cos(a));
                dx = ndx;
                dy = ndy;
            }
            switch (handle) {
                case 0: sh.x += dx; sh.y += dy; sh.w -= dx; sh.h -= dy; break;
                case 1: sh.y += dy; sh.h -= dy; break;
                case 2: sh.w += dx; sh.y += dy; sh.h -= dy; break;
                case 3: sh.w += dx; break;
                case 4: sh.w += dx; sh.h += dy; break;
                case 5: sh.h += dy; break;
                case 6: sh.x += dx; sh.w -= dx; sh.h += dy; break;
                case 7: sh.x += dx; sh.w -= dx; break;
            }
            float minW = dp(12) / s(), minH = dp(12) / s();
            if (sh.w < minW) sh.w = (int) minW;
            if (sh.h < minH) sh.h = (int) minH;
        }
    }

    /* =======================================================================
       SlideShowView — fullscreen LANDSCAPE presentation. Tap the RIGHT half
       = next slide, LEFT half = previous (PowerPoint's own convention).
       At the last slide, "next" shows the black END slide «پایان ارائه»
       and any further tap exits. Edge chevrons + a Persian slide counter
       keep the user oriented.
       ======================================================================= */
    /* PowerPoint-mobile style presentation: LANDSCAPE, tap right half =
       next / left half = back, FADE transition between slides, a
       presentation TIMER (top-center), a PEN (draw over the slide while
       presenting — red/yellow/green, erase, never saved into the file)
       and a black END slide «پایان ارائه» that exits on tap. */
    class SlideShowView extends View {
        int index = 0;
        private final Paint chev = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint cnt = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pen = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
        private long startedAt = 0L;
        private boolean ended = false;
        /* pen state */
        boolean penMode = false;
        int penColor = 0xFFFF2A2A;
        float penWidth = dp(4);
        /* v1.21: presenter notes panel */
        boolean showNotes = false;
        private final ArrayList<Path> strokes = new ArrayList<>();
        private final ArrayList<Integer> strokeColors = new ArrayList<>();
        private final ArrayList<Float> strokeW = new ArrayList<>();
        private Path curStroke = null;
        private float lastPenX, lastPenY;
        /* v1.23: حالت‌های ارائهٔ واقعی پاورپوینت */
        int scaleMode = 0;              /* 0 جاگذاری، 1 پرکردن، 2 هم‌عرض */
        float userZoom = 1f;            /* زوم دو-انگشتی حین ارائه */
        float panX = 0f, panY = 0f;     /* جابه‌جایی فقط وقتی زوم>۱ */
        boolean laser = false;          /* اشاره‌گر لیزری */
        float laserX = -1f, laserY = -1f;
        long laserMoveAt = 0L;
        boolean laserDown = false;
        int blank = 0;                  /* 0 عادی، 1 سیاه، 2 سفید */
        int autoMode = 0;               /* 0 خاموش، 1 پنج‌ثانیه، 2 سه‌ثانیه */
        long slideShownAt = 0L;
        boolean animsActive = false;
        private ScaleGestureDetector showScaleDetector;

        SlideShowView(Context ctx) {
            super(ctx);
            setBackgroundColor(0xFF000000);
            cnt.setColor(0xB3FFFFFF);
            cnt.setTextSize(dp(12));
            chev.setColor(0x80FFFFFF);
            chev.setTextSize(dp(26));
            chev.setTextAlign(Paint.Align.CENTER);
            pen.setColor(0xB3FFFFFF);
            pen.setTextSize(dp(13));
            ink.setStyle(Paint.Style.STROKE);
            ink.setStrokeCap(Paint.Cap.ROUND);
            ink.setStrokeJoin(Paint.Join.ROUND);
            /* v1.23: زوم دو-انگشتی حین ارائه — کانون زوم روی نقطهٔ
               انگشت کاربر می‌ماند (مثل پاورپوینت) */
            showScaleDetector = new ScaleGestureDetector(ctx, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(ScaleGestureDetector d) {
                    if (ended || blank != 0) return true;
                    float oldZ = userZoom;
                    userZoom = Math.max(1f, Math.min(5f, userZoom * d.getScaleFactor()));
                    if (userZoom < 1f) userZoom = 1f;
                    int w = getWidth(), h = getHeight();
                    float bs = baseScale();
                    float sw = doc.slideW * bs;
                    float shh = doc.slideH * bs;
                    float fx = d.getFocusX(), fy = d.getFocusY();
                    if (oldZ > 0f && userZoom != oldZ) {
                        /* نقطهٔ زیر انگشت ثابت بماند */
                        float sx = (fx - ((w - sw * oldZ) / 2f + panX)) / (bs * oldZ);
                        float sy = (fy - ((h - shh * oldZ) / 2f + panY)) / (bs * oldZ);
                        panX = fx - sx * (bs * userZoom) - (w - sw * userZoom) / 2f;
                        panY = fy - sy * (bs * userZoom) - (h - shh * userZoom) / 2f;
                    }
                    clampUserPan();
                    invalidate();
                    return true;
                }
            });
        }

        void go(int i) {
            index = Math.max(0, Math.min(doc.slides.size() - 1, i));
            ended = false;
            strokes.clear();
            strokeColors.clear();
            strokeW.clear();
            startedAt = android.os.SystemClock.elapsedRealtime();
            /* v1.23: ریست حالت ارائه برای اسلاید تازه */
            userZoom = 1f;
            panX = 0f;
            panY = 0f;
            slideShownAt = startedAt;
            animsActive = SlideRenderer.animCount(doc.slides.get(index)) > 0;
            scheduleAuto();
            fade(true);
        }

        /* 240ms fade — PowerPoint's default slide transition */
        private void fade(boolean in) {
            try {
                android.animation.ObjectAnimator a = android.animation.ObjectAnimator.ofFloat(this, "alpha",
                        in ? 0f : 1f, in ? 1f : 0f);
                a.setDuration(240);
                a.start();
            } catch (Throwable ignored) {
            }
        }

        void step(int d) {
            if (ended) {                 /* end slide: any further tap exits */
                closeSlideshow();
                return;
            }
            int n = index + d;
            if (n < 0) return;                     /* before the first slide: stay */
            if (n >= doc.slides.size()) {          /* past the last: END slide */
                ended = true;
                strokes.clear();
                removeCallbacks(autoNext);   /* v1.23: پایان ارائه → پخش خودکار هم می‌ایستد */
                animsActive = false;
                invalidate();
                return;
            }
            index = n;
            strokes.clear();
            strokeColors.clear();
            strokeW.clear();
            /* v1.23: ریست زوم/پن + شمارش معکوس انیمیشن‌های ورود */
            userZoom = 1f;
            panX = 0f;
            panY = 0f;
            slideShownAt = android.os.SystemClock.elapsedRealtime();
            animsActive = SlideRenderer.animCount(doc.slides.get(n)) > 0;
            scheduleAuto();
            /* v1.22: 'cut'/'none' transitions jump instantly, everything
               else fades (approximation of the file's real transition) */
            String tr = doc.slides.get(n).transition;
            if (!("cut".equals(tr) || "none".equals(tr))) fade(true);
            invalidate();
        }

        void togglePen() {
            penMode = !penMode;
            if (penMode) laser = false;   /* v1.23: قلم و لیزر هم‌زمان فعال نمی‌شوند */
            invalidate();
        }

        /* v1.23: اشاره‌گر لیزری — نقطهٔ سرخ درخشان روی اسلاید (مثل
           ارائهٔ واقعی پاورپوینت) */
        void toggleLaser() {
            laser = !laser;
            if (laser) penMode = false;
            laserX = -1f;
            laserDown = false;
            invalidate();
        }

        /* v1.21: presenter-notes panel toggle (📝). The panel shows the
           current slide's notes at the bottom; taps still navigate. */
        void toggleNotes() {
            showNotes = !showNotes;
            invalidate();
        }

        void clearInk() {
            strokes.clear();
            strokeColors.clear();
            strokeW.clear();
            invalidate();
        }

        void cyclePen() {
            if (penColor == 0xFFFF2A2A) penColor = 0xFFFF5C00;
            else if (penColor == 0xFFFF5C00) penColor = 0xFF00C853;
            else penColor = 0xFFFF2A2A;
            penWidth = penWidth >= dp(9) ? dp(3) : penWidth + dp(2);
            invalidate();
        }

        /* v1.23: مقیاس پایه بر اساس حالت اندازه (جاگذاری/پرکردن/هم‌عرض) */
        float baseScale() {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || doc.slideW <= 0) return 1f;
            if (scaleMode == 1) return Math.max(w / (float) doc.slideW, h / (float) doc.slideH);
            if (scaleMode == 2) return w / (float) doc.slideW;
            return Math.min(w / (float) doc.slideW, h / (float) doc.slideH);
        }

        /* v1.23: پن کاربر فقط وقتی اسلاید بزرگ‌تر از صفحه است مجاز است */
        void clampUserPan() {
            int w = getWidth(), h = getHeight();
            float sc = baseScale() * userZoom;
            float sw = doc.slideW * sc, shh = doc.slideH * sc;
            if (sw <= w) panX = 0f;
            else panX = Math.max(-((sw - w) / 2f), Math.min((sw - w) / 2f, panX));
            if (shh <= h) panY = 0f;
            else panY = Math.max(-((shh - h) / 2f), Math.min((shh - h) / 2f, panY));
        }

        /* v1.23: چرخش حالت اندازه — جاگذاری، پرکردن، هم‌عرض */
        void cycleScaleMode() {
            scaleMode = (scaleMode + 1) % 3;
            userZoom = 1f;
            panX = 0f;
            panY = 0f;
            String[] names = {"جاگذاری در صفحه", "پرکردن کامل صفحه", "هم‌عرض صفحه"};
            Toast.makeText(PptEditorActivity.this, "اندازه: " + names[scaleMode], Toast.LENGTH_SHORT).show();
            invalidate();
        }

        /* v1.23: چرخش پخش خودکار — اولویت با autoSec خودِ اسلاید اگر
           تنظیم شده باشد، وگرنه ۵ یا ۳ ثانیه */
        void cycleAuto() {
            autoMode = (autoMode + 1) % 3;
            String[] names = {"پخش خودکار: خاموش", "پخش خودکار: هر ۵ ثانیه", "پخش خودکار: هر ۳ ثانیه"};
            Toast.makeText(PptEditorActivity.this, names[autoMode], Toast.LENGTH_SHORT).show();
            scheduleAuto();
        }

        /* v1.23: صفحهٔ سیاه/سفید (مثل کلید B/W در پاورپوینت) */
        void cycleBlank() {
            blank = (blank + 1) % 3;
            invalidate();
        }

        /* v1.23: قدم بعدی پخش خودکار */
        private final Runnable autoNext = new Runnable() {
            @Override
            public void run() {
                if (autoMode == 0 || ended || slideshowLayer.getVisibility() != View.VISIBLE) return;
                step(1);
                scheduleAuto();
            }
        };

        void scheduleAuto() {
            removeCallbacks(autoNext);
            if (autoMode == 0 || ended) return;
            PptDoc.Slide s = doc.slides.isEmpty() ? null : doc.slides.get(index);
            long delay = (s != null && s.autoSec > 0f) ? (long) (s.autoSec * 1000f) : (autoMode == 1 ? 5000L : 3000L);
            postDelayed(autoNext, delay);
        }

        private String elapsed() {
            long ms = android.os.SystemClock.elapsedRealtime() - startedAt;
            long sec = ms / 1000;
            return Fa.two(sec / 60) + ":" + Fa.two(sec % 60);
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            int w = getWidth(), h = getHeight();
            if (w <= 0 || doc.slideW <= 0 || doc.slides.isEmpty()) return;

            /* v1.23: صفحهٔ سیاه/سفید — کل نمایش پوشیده می‌شود (دکمهٔ ⬛
               می‌چرخاند: عادی، سیاه، سفید) */
            if (blank != 0) {
                c.drawColor(blank == 1 ? 0xFF000000 : 0xFFFFFFFF);
                Paint bp2 = new Paint(Paint.ANTI_ALIAS_FLAG);
                bp2.setColor(blank == 1 ? 0x66FFFFFF : 0x66000000);
                bp2.setTextSize(dp(14));
                bp2.setTextAlign(Paint.Align.CENTER);
                c.drawText(blank == 1 ? "صفحهٔ سیاه — لمس برای بازگشت" : "صفحهٔ سفید — لمس برای بازگشت", w / 2f, h / 2f, bp2);
                return;
            }

            if (ended) {
                /* black END slide — PowerPoint's «Click to exit» */
                Paint ep = new Paint(Paint.ANTI_ALIAS_FLAG);
                ep.setColor(0xFFFFFFFF);
                ep.setTextSize(dp(24));
                ep.setTextAlign(Paint.Align.CENTER);
                c.drawText("پایان ارائه", w / 2f, h / 2f - dp(8), ep);
                Paint ep2 = new Paint(ep);
                ep2.setTextSize(dp(13));
                ep2.setColor(0x99FFFFFF);
                c.drawText("برای خروج لمس کن", w / 2f, h / 2f + dp(26), ep2);
                Paint tp = new Paint(ep);
                tp.setTextSize(dp(12));
                tp.setColor(0x88FFFFFF);
                c.drawText("زمان: " + elapsed(), w / 2f, dp(24), tp);
                return;
            }

            /* v1.23: مقیاس بر اساس حالت اندازه × زوم کاربر؛ در حالت
               عادی اسلاید قفل است (پن فقط وقتی زوم>۱) */
            float bs = baseScale() * userZoom;
            float ox = (w - doc.slideW * bs) / 2f + panX, oy = (h - doc.slideH * bs) / 2f + panY;
            c.save();
            c.translate(ox, oy);
            /* translate-only: the renderer bakes the scale into its
               coordinates (double-scaling made the slide invisible in
               v1.19) */
            if (animsActive) {
                long since = android.os.SystemClock.elapsedRealtime() - slideShownAt;
                SlideRenderer.renderAnim(c, doc.slides.get(index), doc, bs, since);
                if (since >= SlideRenderer.animTotalMs(doc.slides.get(index))) animsActive = false;
                else postInvalidateOnAnimation();
            } else {
                SlideRenderer.render(c, doc.slides.get(index), doc, bs);
            }
            c.restore();

            /* ink strokes (screen space) */
            for (int i = 0; i < strokes.size(); i++) {
                ink.setColor(strokeColors.get(i));
                ink.setStrokeWidth(strokeW.get(i));
                c.drawPath(strokes.get(i), ink);
            }
            if (curStroke != null) {
                ink.setColor(penColor);
                ink.setStrokeWidth(penWidth);
                c.drawPath(curStroke, ink);
            }

            /* v1.23: اشاره‌گر لیزری — نقطهٔ سرخ درخشان که بعد از رها شدن
               سریع محو می‌شود (مثل ارائهٔ واقعی) */
            if (laser && laserX >= 0f) {
                long age = android.os.SystemClock.elapsedRealtime() - laserMoveAt;
                float a = laserDown ? 1f : Math.max(0f, 1f - age / 500f);
                if (a > 0f) {
                    Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
                    halo.setColor(0x33FF1744);
                    halo.setAlpha((int) (51 * a));
                    c.drawCircle(laserX, laserY, dp(16), halo);
                    Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
                    dot.setColor(0xFFFF1744);
                    dot.setAlpha((int) (255 * a));
                    c.drawCircle(laserX, laserY, dp(7), dot);
                    if (!laserDown) postInvalidateOnAnimation();
                }
            }

            /* tap hints: ❯ right edge = forward (next), ❮ left edge = back
               (dimmed at the respective ends) */
            Paint pl = new Paint(chev);
            if (index <= 0) pl.setAlpha(30); else pl.setAlpha(128);
            c.drawText("❮", dp(22), h / 2f + dp(9), pl);
            Paint pr = new Paint(chev);
            if (index >= doc.slides.size() - 1) pr.setAlpha(30); else pr.setAlpha(128);
            c.drawText("❯", w - dp(22), h / 2f + dp(9), pr);

            /* slide counter «۳ / ۱۲» bottom-right + timer top-center */
            String counter = Fa.d(index + 1) + " / " + Fa.d(doc.slides.size());
            c.drawText(counter, w - dp(14) - cnt.measureText(counter), h - dp(12), cnt);
            String t = "⏱ " + elapsed();
            c.drawText(t, (w - cnt.measureText(t)) / 2f, dp(20), cnt);

            /* v1.21: presenter notes panel (bottom strip, dark glass).
               v1.23: با پیش‌نمایش اسلاید بعدی بالای نوار (مثل نمای
               ارائه‌دهندهٔ پاورپوینت) پنل کمی بلندتر می‌شود */
            if (showNotes) {
                String notes = doc.slides.get(index).notes;
                boolean hasNext = index + 1 < doc.slides.size();
                float nh = h * (hasNext ? 0.38f : 0.30f);
                Paint np = new Paint(Paint.ANTI_ALIAS_FLAG);
                np.setColor(0xD9121212);
                c.drawRoundRect(dp(8), h - nh - dp(10), w - dp(8), h - dp(6), dp(10), dp(10), np);
                /* v1.23: پیش‌نمایش اسلاید بعدی — بندانگشتی وسط‌چین با
                   قاب و زیرنویس */
                float textTop = h - nh;
                if (hasNext) {
                    float ph = nh * 0.50f;
                    float pw = ph * doc.slideW / (float) doc.slideH;
                    if (pw > w * 0.55f) {
                        pw = w * 0.55f;
                        ph = pw * doc.slideH / (float) doc.slideW;
                    }
                    float px = (w - pw) / 2f;
                    float py = h - nh + dp(12);
                    Paint cap = new Paint(pen);
                    cap.setTextAlign(Paint.Align.CENTER);
                    cap.setTextSize(dp(11));
                    c.drawText("اسلاید بعدی", w / 2f, py - dp(4), cap);
                    c.save();
                    c.translate(px, py);
                    SlideRenderer.render(c, doc.slides.get(index + 1), doc, pw / doc.slideW);
                    c.restore();
                    Paint brd = new Paint(Paint.ANTI_ALIAS_FLAG);
                    brd.setStyle(Paint.Style.STROKE);
                    brd.setStrokeWidth(dp(2) * 0.75f);   /* حدود ۱.۵dp — dp() فقط int می‌گیرد */
                    brd.setColor(0x80FFFFFF);
                    c.drawRoundRect(px, py, px + pw, py + ph, dp(4), dp(4), brd);
                    textTop = py + ph + dp(6);
                }
                float avail = h - dp(10) - textTop;
                if (avail < 0f) avail = 0f;
                if (notes == null || notes.length() == 0) {
                    Paint npT = new Paint(pen);
                    npT.setTextAlign(Paint.Align.CENTER);
                    npT.setTextSize(dp(13));
                    c.drawText("یادداشتی برای این اسلاید نیست", w / 2f,
                            textTop + avail / 2f, npT);
                } else {
                    android.text.TextPaint tp = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
                    tp.setTextSize(dp(13));
                    tp.setColor(0xFFF2F2F2);
                    StaticLayout sl = new StaticLayout(notes, tp,
                            (int) (w - dp(28)), Layout.Alignment.ALIGN_NORMAL, 1.25f, 0f, false);
                    c.save();
                    c.translate(dp(14), textTop);
                    if (sl.getHeight() > avail) {
                        c.save();
                        c.clipRect(0, 0, (int) (w - dp(28)), (int) (avail - dp(2)));
                        sl.draw(c);
                        c.restore();
                    } else {
                        c.translate(0, (avail - sl.getHeight()) / 2f);
                        sl.draw(c);
                    }
                    c.restore();
                }
            }

            /* pen toolbar (visible in pen mode) */
            if (penMode) {
                float bx = w / 2f - dp(84), by = h - dp(40);
                String[] items = {"✏", "⌫", "⏻"};
                for (int k = 0; k < items.length; k++) {
                    Paint bp = new Paint(pen);
                    bp.setTextAlign(Paint.Align.CENTER);
                    c.drawText(items[k], bx + dp(28) * k + dp(14), by + dp(14), bp);
                }
                Paint col = new Paint(pen);
                col.setColor(penColor);
                c.drawCircle(bx + dp(28) * 3 + dp(14), by + dp(9), dp(6), col);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            int w = getWidth(), h = getHeight();
            /* v1.23: زوم دو-انگشتی حین ارائه */
            showScaleDetector.onTouchEvent(e);
            if (showScaleDetector.isInProgress()) {
                if (curStroke != null) {
                    strokes.add(curStroke);
                    strokeColors.add(penColor);
                    strokeW.add(penWidth);
                    curStroke = null;
                }
                return true;
            }
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                lastPenX = e.getX();
                lastPenY = e.getY();
                laserDown = false;
                /* v1.23: صفحهٔ سیاه/سفید — هر لمسی برمی‌گرداند */
                if (blank != 0) {
                    blank = 0;
                    invalidate();
                    return true;
                }
                /* v1.23: لیزر از نقطهٔ لمس شروع می‌شود */
                if (laser && !ended) {
                    laserX = e.getX();
                    laserY = e.getY();
                    laserMoveAt = android.os.SystemClock.elapsedRealtime();
                    laserDown = true;
                    invalidate();
                    return true;
                }
                /* pen toolbar hits */
                if (penMode) {
                    float bx = w / 2f - dp(84), by = h - dp(40);
                    if (e.getY() > by - dp(14) && e.getY() < by + dp(28)) {
                        if (e.getX() >= bx && e.getX() < bx + dp(28)) { cyclePen(); return true; }
                        if (e.getX() >= bx + dp(28) && e.getX() < bx + dp(56)) { clearInk(); return true; }
                        if (e.getX() >= bx + dp(56) && e.getX() < bx + dp(84)) { penMode = false; invalidate(); return true; }
                        if (e.getX() >= bx + dp(84) && e.getX() < bx + dp(112)) { cyclePen(); return true; }
                    }
                }
                return true;
            }
            if (e.getActionMasked() == MotionEvent.ACTION_MOVE) {
                /* قلم جوهر */
                if (penMode && !ended) {
                    if (curStroke == null) {
                        curStroke = new Path();
                        curStroke.moveTo(lastPenX, lastPenY);
                    }
                    curStroke.quadTo(lastPenX, lastPenY, (lastPenX + e.getX()) / 2f, (lastPenY + e.getY()) / 2f);
                    lastPenX = e.getX();
                    lastPenY = e.getY();
                    invalidate();
                    return true;
                }
                /* v1.23: اشاره‌گر لیزری */
                if (laser && !ended) {
                    laserX = e.getX();
                    laserY = e.getY();
                    laserMoveAt = android.os.SystemClock.elapsedRealtime();
                    laserDown = true;
                    invalidate();
                    return true;
                }
                /* v1.23: جابه‌جایی فقط وقتی زوم>۱ (در حالت عادی اسلاید
                   قفل است و لمس ورق می‌زند) */
                if (userZoom > 1.02f && !ended) {
                    panX += e.getX() - lastPenX;
                    panY += e.getY() - lastPenY;
                    lastPenX = e.getX();
                    lastPenY = e.getY();
                    clampUserPan();
                    invalidate();
                    return true;
                }
                lastPenX = e.getX();
                lastPenY = e.getY();
                return true;
            }
            if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                if (curStroke != null) {
                    curStroke.lineTo(e.getX(), e.getY());
                    strokes.add(curStroke);
                    strokeColors.add(penColor);
                    strokeW.add(penWidth);
                    curStroke = null;
                    invalidate();
                    return true;
                }
                laserDown = false;
                if (laser) {
                    invalidate();
                    return true;
                }
                if (penMode) return true;      /* taps don't flip while penning */
                /* v1.23: زوم نزدیک به ۱ خودکار به ۱ برمی‌گردد */
                if (userZoom > 1f && userZoom <= 1.15f) {
                    userZoom = 1f;
                    panX = 0f;
                    panY = 0f;
                    invalidate();
                    return true;
                }
                if (userZoom > 1.02f) return true;   /* در زوم، لمس ورق نمی‌زند */
                if (e.getActionMasked() == MotionEvent.ACTION_CANCEL) return true;   /* لمس لغو شد: ورق نزن */
                /* v1.22: tapped a hyperlink run? open it instead of flipping */
                {
                    float sc = baseScale();
                    float ox = (w - doc.slideW * sc) / 2f + panX, oy = (h - doc.slideH * sc) / 2f + panY;
                    float sx = (e.getX() - ox) / sc, sy = (e.getY() - oy) / sc;
                    PptDoc.Slide sld = doc.slides.get(index);
                    for (int i = sld.shapes.size() - 1; i >= 0; i--) {
                        PptDoc.Shape sh = sld.shapes.get(i);
                        if (sh.rot != 0) continue;
                        if (sh.x * sc + ox <= e.getX() && (sh.x + sh.w) * sc + ox >= e.getX()
                                && sh.y * sc + oy <= e.getY() && (sh.y + sh.h) * sc + oy >= e.getY()) {
                            /* v1.29 FIX: hitLink expects SLIDE-LOCAL pixels (the
                               editor canvas calls it with x-offX/y-offY since
                               v1.27) — raw screen coords resolved the WRONG
                               line/run in landscape (oy is hundreds of px),
                               making slideshow links effectively dead.
                               v1.29 NEW: internal "go to slide" links jump
                               straight to the target slide (PowerPoint kiosk
                               navigation decks finally work). */
                            PptDoc.Run linkRun = SlideRenderer.hitLinkRun(sh, sx, sy, sc);
                            if (linkRun != null) {
                                if (linkRun.linkUrl != null && linkRun.linkUrl.length() > 0) {
                                    openLink(linkRun.linkUrl);
                                    return true;
                                }
                                if (linkRun.linkSlide > 0) {
                                    if (linkRun.linkSlide - 1 != index) go(linkRun.linkSlide - 1);
                                    return true;
                                }
                                return true;   /* a link run with no usable target: consume the tap */
                            }
                            if (sh instanceof PptDoc.Video) {
                                playVideo((PptDoc.Video) sh);
                                return true;
                            }
                            break;
                        }
                    }
                }
                if (e.getX() >= w / 2f) step(1);   /* right half → forward */
                else step(-1);                     /* left half → back */
                return true;
            }
            return true;
        }

        /* timer repaint — ~1s ticks keep the clock honest */
        private final Runnable tick = new Runnable() {
            @Override public void run() {
                /* v1.29 FIX: also require isAttachedToWindow() — being destroyed
                   mid-show (no closeSlideshow() path) left the layer VISIBLE, so
                   the chain re-posted forever pinning the destroyed Activity. */
                if (slideshowLayer != null && slideshowLayer.getVisibility() == View.VISIBLE
                        && !ended && isAttachedToWindow()) {
                    invalidate();
                    postDelayed(tick, 1000);
                }
            }
        };

        void startClock() {
            removeCallbacks(tick);
            postDelayed(tick, 600);
        }

        void stopClock() {
            removeCallbacks(tick);
            removeCallbacks(autoNext);   /* v1.23: توقف پخش خودکار هم */
        }
    }

    /* =======================================================================
       SlideThumb / ThemeCard / ShapeIconView / GridLayoutLike
       ======================================================================= */
    private class SlideThumb extends View {
        private final int idx;

        SlideThumb(Context ctx, int idx) {
            super(ctx);
            this.idx = idx;
        }

        @Override
        protected void onMeasure(int wms, int hms) {
            int w = MeasureSpec.getSize(wms);
            int h = Math.max(1, Math.round(w * doc.slideH / (float) doc.slideW));
            setMeasuredDimension(w, h);
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            if (idx >= doc.slides.size()) return;
            int w = getWidth(), h = getHeight();
            float sc = w / (float) doc.slideW;
            c.save();
            SlideRenderer.render(c, doc.slides.get(idx), doc, sc);
            c.restore();
        }
    }

    private static class ThemeCard extends View {
        private final int[] cols;

        ThemeCard(Context ctx, int[] cols) {
            super(ctx);
            this.cols = cols;
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            int w = getWidth(), h = getHeight();
            float d = getResources().getDisplayMetrics().density;
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setShader(new LinearGradient(0, 0, 0, h, cols[0], cols[1], Shader.TileMode.CLAMP));
            c.drawRoundRect(new RectF(0, 0, w, h), 6 * d, 6 * d, p);
            p.setShader(null);
            p.setColor(cols[4]);
            c.drawRect(0, h - 6 * d, w, h, p);
            p.setColor(cols[2]);
            c.drawRect(w / 2f - 20 * d, 6 * d, w / 2f + 20 * d, 11 * d, p);
        }
    }

    private static class ShapeIconView extends View {
        private final int type;

        ShapeIconView(Context ctx, int type) {
            super(ctx);
            this.type = type;
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            float w = getWidth(), h = getHeight();
            float d = getResources().getDisplayMetrics().density;
            Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            fill.setColor(0xFF2E75B6);
            Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(2 * d);
            line.setColor(0xFF1F3864);
            Path p = SlideRenderer.shapePath(type, w * 0.1f, h * 0.12f, w * 0.9f, h * 0.88f);
            c.drawPath(p, fill);
            c.drawPath(p, line);
        }
    }

    private static class GridLayoutLike extends LinearLayout {
        private final int cols;

        GridLayoutLike(Context ctx, int cols) {
            super(ctx);
            this.cols = cols;
            setOrientation(LinearLayout.VERTICAL);
            float d = ctx.getResources().getDisplayMetrics().density;
            setPadding(Math.round(8 * d), Math.round(8 * d), Math.round(8 * d), Math.round(8 * d));
        }

        @Override
        public void addView(View child) {
            LinearLayout row = null;
            if (getChildCount() > 0) {
                row = (LinearLayout) getChildAt(getChildCount() - 1);
                if (row.getChildCount() >= cols) row = null;
            }
            if (row == null) {
                row = new LinearLayout(getContext());
                row.setOrientation(LinearLayout.HORIZONTAL);
                super.addView(row, new LinearLayout.LayoutParams(-1, -2));
            }
            row.addView(child, new LinearLayout.LayoutParams(0, -2, 1f));
        }
    }

    /* =======================================================================
       SlideRenderer — shared by canvas / thumbnails / slideshow / PDF export
       ======================================================================= */
    static final class SlideRenderer {

        static void render(Canvas c, PptDoc.Slide s, PptDoc doc, float scale) {
            renderAnim(c, s, doc, scale, -1L);
        }

        /* v1.23: رندر با انیمیشن ورود — sinceMs از شروع نمایش اسلاید؛
           منفی یعنی حالت نهایی (ادیتور/بندانگشتی/PDF) */
        static void renderAnim(Canvas c, PptDoc.Slide s, PptDoc doc, float scale, long sinceMs) {
            int w = Math.round(doc.slideW * scale);
            int h = Math.round(doc.slideH * scale);
            drawBackground(c, s, w, h);
            /* master/layout chrome beneath the editable shapes */
            for (int i = 0; i < s.bgShapes.size(); i++) {
                drawShape(c, s.bgShapes.get(i), scale);
            }
            if (sinceMs < 0L) {
                for (int i = 0; i < s.shapes.size(); i++) {
                    drawShape(c, s.shapes.get(i), scale);
                }
            } else {
                /* انیمیشن‌های ورود — هر شکل ۲۲۰ms بعد از قبلی، ۳۸۰ms طول،
                   با شیب ease-out؛ شکل بدون انیمیشن فوراً کامل رسم می‌شود */
                int slot = 0;
                for (int i = 0; i < s.shapes.size(); i++) {
                    PptDoc.Shape sh = s.shapes.get(i);
                    if (sh.anim <= 0) {
                        drawShape(c, sh, scale);
                        continue;
                    }
                    long start = 200L + (long) slot * 220L, dur = 380L;
                    slot++;
                    float p = (sinceMs - start) / (float) dur;
                    if (p <= 0f) continue;                     /* هنوز ظاهر نشده */
                    if (p >= 1f) {
                        drawShape(c, sh, scale);
                        continue;
                    }
                    float e = 1f - (1f - p) * (1f - p);        /* ease-out */
                    float sw = doc.slideW * scale, shh = doc.slideH * scale;
                    float alpha = 1f;
                    boolean useAlpha = false;
                    c.save();
                    switch (sh.anim) {
                        case 1:                               /* محو */
                            alpha = e;
                            useAlpha = true;
                            break;
                        case 2:                               /* پرواز از راست */
                            c.translate((1f - e) * sw * 0.55f, 0f);
                            alpha = e * 1.6f;
                            useAlpha = true;
                            break;
                        case 3:                               /* پرواز از چپ */
                            c.translate(-(1f - e) * sw * 0.55f, 0f);
                            alpha = e * 1.6f;
                            useAlpha = true;
                            break;
                        case 4:                               /* پرواز از بالا */
                            c.translate(0f, -(1f - e) * shh * 0.55f);
                            alpha = e * 1.6f;
                            useAlpha = true;
                            break;
                        case 5:                               /* پرواز از پایین */
                            c.translate(0f, (1f - e) * shh * 0.55f);
                            alpha = e * 1.6f;
                            useAlpha = true;
                            break;
                        case 6: {                             /* بزرگ‌نمایی */
                            float z = 0.55f + 0.45f * e;
                            c.scale(z, z, (sh.x + sh.w / 2f) * scale, (sh.y + sh.h / 2f) * scale);
                            alpha = e * 1.6f;
                            useAlpha = true;
                            break;
                        }
                        default: {                            /* 7: پاک‌شدن از راست (RTL) */
                            float l = sh.x * scale, t = sh.y * scale;
                            c.clipRect(l + sh.w * scale * (1f - e), t, l + sh.w * scale, t + sh.h * scale);
                            break;
                        }
                    }
                    boolean layered = false;
                    if (useAlpha && alpha < 0.999f) {
                        Paint ap = new Paint();
                        ap.setAlpha((int) Math.max(0f, Math.min(1f, alpha) * 255f));
                        c.saveLayer(null, ap);
                        layered = true;
                    }
                    drawShape(c, sh, scale);
                    if (layered) c.restore();
                    c.restore();
                }
            }
        }

        /* v1.23: تعداد شکل‌های دارای انیمیشن ورود در اسلاید */
        static int animCount(PptDoc.Slide s) {
            int n = 0;
            for (int i = 0; i < s.shapes.size(); i++) {
                if (s.shapes.get(i).anim > 0) n++;
            }
            return n;
        }

        /* v1.23: زمان پایان آخرین انیمیشن ورود (توقف invalidate های پیوسته) */
        static long animTotalMs(PptDoc.Slide s) {
            int n = animCount(s);
            return n == 0 ? 0L : 200L + (long) (n - 1) * 220L + 380L + 60L;
        }

        static void drawBackground(Canvas c, PptDoc.Slide s, int w, int h) {
            Paint p = new Paint();
            if (s.background.type == PptDoc.Fill.GRADIENT) {
                float ang = s.background.gradAngleDeg >= 0f ? s.background.gradAngleDeg
                        : (s.background.gradHorizontal ? 0f : 90f);
                float[] pts = gradLine(ang, 0, 0, w, h);
                p.setShader(new LinearGradient(pts[0], pts[1], pts[2], pts[3],
                        s.background.color, s.background.color2, Shader.TileMode.CLAMP));
            } else {
                p.setColor(s.background.type == PptDoc.Fill.SOLID ? s.background.color : 0xFFFFFFFF);
            }
            c.drawRect(0, 0, w, h, p);
        }

        /* gradient axis endpoints across a box for an OOXML angle (deg,
           clockwise from 3 o'clock; 90 = top->bottom) */
        static float[] gradLine(float angDeg, float l, float t, float r, float b) {
            double rad = Math.toRadians(angDeg);
            float cx = (l + r) / 2f, cy = (t + b) / 2f;
            float dx = (float) Math.cos(rad) * (r - l) / 2f;
            float dy = (float) Math.sin(rad) * (b - t) / 2f;
            return new float[]{cx - dx, cy - dy, cx + dx, cy + dy};
        }

        static void drawShape(Canvas c, PptDoc.Shape sh, float scale) {
            if (sh.w <= 0 || sh.h <= 0) return;
            float l = sh.x * scale, t = sh.y * scale;
            float w = sh.w * scale, h = sh.h * scale;
            float r = l + w, b = t + h;

            c.save();
            if (sh.rot != 0) {
                c.rotate(sh.rot / 60000f, l + w / 2f, t + h / 2f);
            }
            /* v1.27 FIX: flipH/flipV now apply to EVERY shape type —
               pictures, rects, ovals, arrows and stars used to render
               un-mirrored (arrowheads/compositions pointed the wrong way) */
            if (sh.flipH || sh.flipV) {
                c.scale(sh.flipH ? -1f : 1f, sh.flipV ? -1f : 1f, l + w / 2f, t + h / 2f);
            }

            if (sh instanceof PptDoc.Connector) {
                Paint lp = linePaint(sh.line, scale);
                /* v1.27: flip handled by the canvas mirror above */
                c.drawLine(l, t, r, b, lp);
                c.restore();
                return;
            }

            if (sh instanceof PptDoc.AutoShape && ((PptDoc.AutoShape) sh).type == PptDoc.Shape.TYPE_LINE) {
                /* line-shaped autoshape (inserted from the Insert tab) */
                Paint lp = linePaint(sh.line, scale);
                if (!sh.line.enabled) {
                    lp.setColor(0xFF404040);
                    lp.setStrokeWidth(Math.max(1.5f, 12700f * scale));
                }
                /* v1.27: flip handled by the canvas mirror above */
                c.drawLine(l, t, r, b, lp);
                c.restore();
                return;
            }

            if (sh instanceof PptDoc.Picture) {
                if (sh.shadow) drawShapeShadow(c, sh, null, l, t, r, b, scale);
                drawPicture(c, (PptDoc.Picture) sh, l, t, r, b);
                if (sh.line.enabled) {
                    c.drawRect(l, t, r, b, linePaint(sh.line, scale));
                }
                c.restore();
                return;
            }

            if (sh instanceof PptDoc.Table) {
                if (sh.shadow) drawShapeShadow(c, sh, null, l, t, r, b, scale);
                drawTable(c, (PptDoc.Table) sh, scale);
                c.restore();
                return;
            }

            if (sh instanceof PptDoc.Chart) {
                /* v1.22: real chart rendering (bar/line/pie/doughnut/area/
                   scatter, stacked + horizontal variants, axes, gridlines,
                   legend, data labels, per-point colors) */
                if (sh.shadow) drawShapeShadow(c, sh, null, l, t, r, b, scale);
                drawChart(c, (PptDoc.Chart) sh, l, t, r, b, scale);
                c.restore();
                return;
            }

            if (sh instanceof PptDoc.Video) {
                /* v1.22: embedded video/audio — poster + play affordance */
                if (sh.shadow) drawShapeShadow(c, sh, null, l, t, r, b, scale);
                drawVideo(c, (PptDoc.Video) sh, l, t, r, b, scale);
                if (sh.line.enabled) {
                    c.drawRect(l, t, r, b, linePaint(sh.line, scale));
                }
                c.restore();
                return;
            }

            Path path = null;
            if (sh instanceof PptDoc.AutoShape) {
                PptDoc.AutoShape a = (PptDoc.AutoShape) sh;
                if (a.pathX != null && a.pathX.length >= 3) {
                    /* v1.27: freeform geometry — build the polygon from the
                       normalized custGeom points (the shape's silhouette used
                       to vanish entirely) */
                    path = new Path();
                    path.moveTo(l + a.pathX[0] * w, t + a.pathY[0] * h);
                    for (int i = 1; i < a.pathX.length; i++) {
                        path.lineTo(l + a.pathX[i] * w, t + a.pathY[i] * h);
                    }
                    if (a.pathClosed) path.close();
                } else if (a.type != PptDoc.Shape.TYPE_RECT) {
                    path = shapePath(a.type, l, t, r, b);
                }
            }

            if (sh.shadow) drawShapeShadow(c, sh, path, l, t, r, b, scale);
            /* v1.27: picture fill on the shape (spPr/a:blipFill) */
            if (sh.picFillData != null) {
                Bitmap pbm = decodeBytes(sh.picFillData, Math.max(sh.w, sh.h));
                if (pbm != null && !pbm.isRecycled()) {
                    Paint pfp = new Paint(Paint.ANTI_ALIAS_FLAG);
                    android.graphics.Matrix shm = new android.graphics.Matrix();
                    shm.setScale(w / Math.max(1, pbm.getWidth()), h / Math.max(1, pbm.getHeight()));
                    android.graphics.BitmapShader shd = new android.graphics.BitmapShader(pbm, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                    shd.setLocalMatrix(shm);
                    pfp.setShader(shd);
                    if (path != null) c.drawPath(path, pfp);
                    else c.drawRect(l, t, r, b, pfp);
                }
            } else if (sh.fill.type != PptDoc.Fill.NONE) {
                Paint fp = new Paint(Paint.ANTI_ALIAS_FLAG);
                if (sh.fill.type == PptDoc.Fill.GRADIENT) {
                    float ang = sh.fill.gradAngleDeg >= 0f ? sh.fill.gradAngleDeg
                            : (sh.fill.gradHorizontal ? 0f : 90f);
                    float[] pts = gradLine(ang, l, t, r, b);
                    fp.setShader(new LinearGradient(pts[0], pts[1], pts[2], pts[3],
                            sh.fill.color, sh.fill.color2, Shader.TileMode.CLAMP));
                } else {
                    fp.setColor(sh.fill.color);
                }
                if (path != null) c.drawPath(path, fp);
                else c.drawRect(l, t, r, b, fp);
            }
            if (sh.line.enabled) {
                Paint lp = linePaint(sh.line, scale);
                if (path != null) c.drawPath(path, lp);
                else c.drawRect(l, t, r, b, lp);
            }

            List<PptDoc.Para> paras = parasOf(sh);
            if (paras != null && !paras.isEmpty()) {
                PptDoc.BodyPr body = bodyOf(sh);
                drawRichText(c, paras, body, l, t, r, b, scale, sh.textShadow);
            }
            c.restore();
        }

        /* v1.23: سایهٔ شکل — سیاه‌مش هم‌شکل با خود شکل، ۲pt راست‌پایین‌تر،
           نیمه‌شفاف (۰x42 سیاه). شکل‌های متن‌دار/اتوشیپ مسیر هندسی خودشان
           را می‌گیرند؛ تصویر/ویدیو/جدول/نمودار مستطیل گرد. رسم دستی
           silhouette چون setShadowLayer برای شکل‌های غیرمتنی زیر
           شتاب سخت‌افزاری پشتیبانی نمی‌شود. */
        static void drawShapeShadow(Canvas c, PptDoc.Shape sh, Path path,
                                    float l, float t, float r, float b, float scale) {
            float pxPerPt = 12700f * scale;
            float off = 2f * pxPerPt;
            Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG);
            sp.setColor(0x42000000);
            c.save();
            c.translate(off, off);
            if (path != null) {
                c.drawPath(path, sp);
            } else if (sh instanceof PptDoc.Picture || sh instanceof PptDoc.Video
                    || sh instanceof PptDoc.Table || sh instanceof PptDoc.Chart) {
                float rad = Math.min(6f * pxPerPt, Math.min(r - l, b - t) / 4f);
                c.drawRoundRect(new RectF(l, t, r, b), rad, rad, sp);
            } else {
                c.drawRect(l, t, r, b, sp);
            }
            c.restore();
        }

        /* v1.31: clamp a source-rect pixel into bitmap bounds (negative crops) */
        static int clampPx(int v, int max) {
            return v < 0 ? 0 : (v > max ? max : v);
        }

        static void drawPicture(Canvas c, PptDoc.Picture pic, float l, float t, float r, float b) {
            Bitmap bmp = decode(pic);
            if (bmp != null && !bmp.isRecycled()) {
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                /* v1.31 FIX: negative crops (image extends BEYOND the frame)
                   rendered as uncropped — the "> 0" test skipped them. Any
                   non-zero crop now applies (src clamped into bitmap bounds,
                   matching PowerPoint's edge-stretch behavior). */
                if (pic.cropL != 0 || pic.cropT != 0 || pic.cropR != 0 || pic.cropB != 0) {
                    int iw = bmp.getWidth(), ih = bmp.getHeight();
                    android.graphics.Rect src = new android.graphics.Rect(
                            clampPx(Math.round(pic.cropL * iw), iw),
                            clampPx(Math.round(pic.cropT * ih), ih),
                            clampPx(Math.round((1f - pic.cropR) * iw), iw),
                            clampPx(Math.round((1f - pic.cropB) * ih), ih));
                    if (src.right <= src.left) src.right = Math.min(iw, src.left + 1);
                    if (src.bottom <= src.top) src.bottom = Math.min(ih, src.top + 1);
                    c.drawBitmap(bmp, src, new RectF(l, t, r, b), p);
                } else {
                    c.drawBitmap(bmp, null, new RectF(l, t, r, b), p);
                }
            } else {
                /* v1.23: جای «؟» خشک، جای‌نگهدارندهٔ گویاتر — اگر فرمت
                   پشتیبانی‌نشده (EMF/WMF/TIFF) باشد کاربر دلیل را می‌فهمد */
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setColor(0xFFEFEFEF);
                c.drawRect(l, t, r, b, p);
                p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setColor(0xFF8A8886);
                float ts = Math.max(9f, Math.min((b - t) / 7f, (r - l) / 7f));
                p.setTextSize(ts);
                p.setTextAlign(Paint.Align.CENTER);
                boolean unsupported = isUnsupportedImg(pic.imgData);
                /* v1.28: پیام درست برای تصویر پیوندی (r:link بی‌کشِ درون فایل)
                   که قبلاً کلاً حذف می‌شد و جایش هیچ ردی نمی‌ماند */
                String msg = pic.imgData == null
                        ? "تصویر پیوندی است (در فایل نیست)"
                        : (unsupported ? "قالب تصویر قابل خواندن نیست" : "تصویر خوانده نشد");
                c.drawText(unsupported ? "!" : "؟", (l + r) / 2f, (t + b) / 2f - ts * 0.1f, p);
                Paint p2 = new Paint(p);
                p2.setTextSize(ts * 0.62f);
                p2.setColor(0xFFB0AEA9);
                c.drawText(msg, (l + r) / 2f, (t + b) / 2f + ts * 0.9f, p2);
            }
        }

        /* v1.23 BUGFIX (تصاویر بعضی اسلایدها نمایش داده نمی‌شد):
           (۱) decodeByteArray بدون try/catch و بدون اندازه‌گیری ابعاد بود —
               تصاویر خیلی بزرگ OOM می‌دادند و رندر همان اسلاید را می‌کشت؛
               حالا دو-فازی است (اول فقط ابعاد، بعد inSampleSize امن).
           (۲) cache هنگام تخلیه Bitmap.recycle() می‌زد و بیت‌مپِ در حال
               رسم روی canvas/filmstrip/thumbnail خاموش می‌شد («trying to
               use recycled bitmap» → تصویر ناپدید). دیگر recycle نمی‌کنیم.
           (۳) HashMap ترتیب LRU نداشت؛ LinkedHashMap(accessOrder) آمده.
           (۴) PDF-export روی نخ پس‌زمینه رندر می‌کند → دسترسی همگام‌سازی شد. */
        private static final Object BMP_LOCK = new Object();
        private static final java.util.LinkedHashMap<String, Bitmap> BMP_CACHE =
                new java.util.LinkedHashMap<String, Bitmap>(16, 0.75f, true);

        static Bitmap decode(PptDoc.Picture pic) {
            if (pic.imgData == null || pic.imgData.length == 0) return null;
            /* v1.26: real decoders for the formats BitmapFactory cannot read.
               TIFF -> pure-Java baseline decoder; EMF/WMF -> metafile renderer.
               These render the ORIGINAL bytes (no lossy PNG re-encode), and
               are cached exactly like raster pictures. */
            String fmt = pic.imgFormat == null ? "png" : pic.imgFormat;
            if ("tiff".equals(fmt) || "tif".equals(fmt)) {
                String key = "tiff:" + pic.imgData.length + ":" + pic.imgData.hashCode();
                synchronized (BMP_LOCK) {
                    Bitmap cached = BMP_CACHE.get(key);
                    if (cached != null) {
                        if (!cached.isRecycled()) return cached;
                        BMP_CACHE.remove(key);
                    }
                }
                Bitmap tb = PptTiffDecoder.decode(pic.imgData,
                        Math.max(256, Math.round(Math.max(pic.w, pic.h) / 12700f * 1.3f)));
                if (tb != null) {
                    synchronized (BMP_LOCK) { BMP_CACHE.put(key, tb); trimCache(); }
                }
                if (tb != null) return tb;
                /* TIFF decoder could not handle this variant — fall through
                   to BitmapFactory (a few TIFFs are actually mislabeled PNG) */
            } else if ("emf".equals(fmt) || "wmf".equals(fmt)) {
                String key = fmt + ":" + pic.imgData.length + ":" + pic.imgData.hashCode();
                synchronized (BMP_LOCK) {
                    Bitmap cached = BMP_CACHE.get(key);
                    if (cached != null) {
                        if (!cached.isRecycled()) return cached;
                        BMP_CACHE.remove(key);
                    }
                }
                int target = Math.max(256, Math.round(Math.max(pic.w, pic.h) / 12700f * 2.0f));
                Bitmap mb = PptMetafile.render(pic.imgData, fmt, target);
                if (mb != null) {
                    synchronized (BMP_LOCK) { BMP_CACHE.put(key, mb); trimCache(); }
                    return mb;
                }
                return null;
            }
            String key = pic.imgFormat + ":" + pic.imgData.length + ":" + pic.imgData.hashCode();
            synchronized (BMP_LOCK) {
                Bitmap cached = BMP_CACHE.get(key);
                if (cached != null) {
                    if (!cached.isRecycled()) return cached;
                    BMP_CACHE.remove(key);
                }
            }
            Bitmap bmp = decodeRobust(pic.imgData, Math.max(pic.w, pic.h));
            /* v1.28 FIX (تصاویری که فقط در اپ‌های دیگر دیده می‌شدند): اگر
               BitmapFactory نتوانست (مثل جی‌پگ‌های CMYK/YCCK چاپی که Skia
               اصلاً دیکد نمی‌کند)، دیکدر خالص‌جاوا امتحان می‌شود */
            if (bmp == null) {
                bmp = javaJpeg(pic.imgData, Math.max(pic.w, pic.h));
                if (bmp == null) return null;
            }
            synchronized (BMP_LOCK) {
                BMP_CACHE.put(key, bmp);
                trimCache();
            }
            return bmp;
        }

        /* v1.26: shared LRU trim for the picture cache */
        static void trimCache() {
            if (BMP_CACHE.size() >= 14) {
                Iterator<Map.Entry<String, Bitmap>> it = BMP_CACHE.entrySet().iterator();
                while (it.hasNext() && BMP_CACHE.size() > 12) {
                    it.next();
                    it.remove();   /* بدون recycle — رفرنس‌ها GC می‌شوند */
                }
            }
        }

        /* دو-فازی + نردبان تلاش مجدد — فقط وقتی null برمی‌گردد که بایتی
           واقعاً قابل دیکود نباشد (EMF/WMF/TIFF/خراب) */
        static Bitmap decodeRobust(byte[] data, long targetExtentEmu) {
            int ow = 0, oh = 0;
            try {
                BitmapFactory.Options b = new BitmapFactory.Options();
                b.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, b);
                ow = b.outWidth;
                oh = b.outHeight;
            } catch (Throwable ignored) {
            }
            /* چند پیکسل واقعاً لازم است؟ اندازه روی صفحه (سقف ۴۰۹۶) */
            int need = 2048;
            if (targetExtentEmu > 0) {
                need = Math.max(256, Math.round(targetExtentEmu / 12700f * 1.5f));
            }
            need = Math.min(need, 4096);
            int sample = 1;
            if (ow > 0 && oh > 0) {
                while (ow / (sample * 2) >= need && oh / (sample * 2) >= need) sample *= 2;
            }
            for (int attempt = 0; attempt < 6; attempt++) {
                try {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inSampleSize = sample;
                    o.inPreferredConfig = Bitmap.Config.ARGB_8888;
                    Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length, o);
                    return bmp;      /* null یعنی فرمت پشتیبانی‌نشده */
                } catch (OutOfMemoryError oom) {
                    sample = Math.max(sample * 2, 8);
                } catch (Throwable t) {
                    return null;
                }
            }
            return null;
        }

        /* v1.26: isUnsupportedImg — a format the app cannot render AT ALL.
           EMF/WMF/TIFF now have real decoders (PptMetafile / PptTiffDecoder),
           so they no longer count as unsupported; this predicate is only the
           honest fallback message when even those decoders fail.
           v1.26 FIX: the TIFF-LE magic was wrong (49 2A …); real TIFF is
           II*\0 = 49 49 2A 00 (and MM\0* = 4D 4D 00 2A). */
        static boolean isUnsupportedImg(byte[] b) {
            if (b == null || b.length < 4) return false;
            if ((b[0] & 255) == 0x01 && (b[1] & 255) == 0x00 && (b[2] & 255) == 0x00 && (b[3] & 255) == 0x00) return true; /* EMF */
            if ((b[0] & 255) == 0xD7 && (b[1] & 255) == 0xCD) return true;   /* WMF */
            if ((b[0] & 255) == 0x01 && (b[1] & 255) == 0x00 && (b[2] & 255) == 0x09 && (b[3] & 255) == 0x00) return true; /* WMF classic */
            if ((b[0] & 255) == 0x49 && (b[1] & 255) == 0x49 && (b[2] & 255) == 0x2A && (b[3] & 255) == 0x00) return true;  /* TIFF LE */
            if ((b[0] & 255) == 0x4D && (b[1] & 255) == 0x4D && (b[2] & 255) == 0x00 && (b[3] & 255) == 0x2A) return true;  /* TIFF BE */
            return false;
        }

        static void drawTable(Canvas c, PptDoc.Table tb, float scale) {
            float l = tb.x * scale;
            float yTop = tb.y * scale;
            Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
            grid.setStyle(Paint.Style.STROKE);
            grid.setStrokeWidth(Math.max(1f, scale * 12700f));
            grid.setColor(0xFF8A8886);

            /* v1.22: rows grow to fit their text (PowerPoint auto-fit) —
               first measure every cell, then draw with effective heights */
            int nr = tb.rows.size();
            float[] rowH = new float[nr];
            for (int ri = 0; ri < nr; ri++) {
                PptDoc.TableRow row = tb.rows.get(ri);
                float declared = row.heightEmu > 0 ? row.heightEmu * scale : 360000f * scale;
                float need = declared;
                float x = l;
                for (int ci = 0; ci < row.cells.size() && ci < tb.colWidths.size(); ci++) {
                    PptDoc.TableCell cell = row.cells.get(ci);
                    float cw = colW(tb, ci, cell.spanR) * scale;
                    if (!cell.skipR && !cell.skipD && !cell.paras.isEmpty()) {
                        /* v1.31 FIX: measure with the cell's OWN margins —
                           the hardcoded 45720/13716 made every imported table
                           wrap earlier than PowerPoint (real margins were
                           often wider) */
                        PptDoc.BodyPr cb = cellBody(cell);
                        float boxW = cw - cb.insetL * scale - cb.insetR * scale;
                        if (boxW < 24f) boxW = Math.max(24f, cw * 0.8f);
                        float pxPerPt = 12700f * scale;
                        float h = 0f;
                        for (PptDoc.Para p : cell.paras) {
                            StaticLayout sl = buildParaLayout(p, (int) boxW, pxPerPt);
                            h += sl.getHeight() + paraSpacingPx(p, pxPerPt);
                        }
                        h += (cb.insetT + cb.insetB) * scale;
                        if (h > need) need = h;
                    }
                    x += cw;
                }
                rowH[ri] = need;
            }

            float y = yTop;
            for (int ri = 0; ri < nr; ri++) {
                PptDoc.TableRow row = tb.rows.get(ri);
                float rh = rowH[ri];
                float x = l;
                for (int ci = 0; ci < row.cells.size() && ci < tb.colWidths.size(); ci++) {
                    PptDoc.TableCell cell = row.cells.get(ci);
                    float cw = colW(tb, ci, cell.spanR) * scale;
                    if (!cell.skipR && !cell.skipD) {
                        /* v1.31 FIX: a vMerge MASTER cell (spanD>1) must cover
                           the merged rows too — its fill/borders/text box
                           used to stop after the first row, leaving a white
                           gap where the covered rows are */
                        float cellBottom = y + rh;
                        if (cell.spanD > 1) {
                            for (int m = 1; m < cell.spanD && ri + m < nr; m++) {
                                cellBottom += rowH[ri + m];
                            }
                        }
                        Paint fp = null;
                        if (cell.fill.type == PptDoc.Fill.SOLID) {
                            fp = new Paint(Paint.ANTI_ALIAS_FLAG);
                            fp.setColor(cell.fill.color);
                        } else if (tb.firstRow && ri == 0) {
                            fp = new Paint(Paint.ANTI_ALIAS_FLAG);
                            fp.setColor(0xFFD9E2F0);
                        } else if (tb.bandRow && ri % 2 == 1) {
                            fp = new Paint(Paint.ANTI_ALIAS_FLAG);
                            fp.setColor(0xFFEFF3F8);
                        }
                        if (fp != null) c.drawRect(x, y, x + cw, cellBottom, fp);
                        /* v1.31 FIX: real per-cell borders — the reader
                           round-trips a:tcPr/lnL/lnT/lnR/lnB (color, width,
                           dash, on/off) but the renderer ignored them and
                           drew the same thin gray grid for every table:
                           thick/colored/dashed borders were invisible and
                           explicitly-removed borders came back as black
                           lines. Cells that define ANY edge now draw exactly
                           their own edges; cells with none keep the theme
                           grid. */
                        boolean custom = false;
                        for (int ei = 0; ei < 4; ei++) {
                            if (cell.edges[ei] != null) { custom = true; break; }
                        }
                        if (custom) {
                            drawCellEdges(c, cell, x, y, x + cw, cellBottom, scale);
                        } else {
                            c.drawRect(x, y, x + cw, cellBottom, grid);
                        }
                        if (!cell.paras.isEmpty()) {
                            /* v1.31 FIX: honor the cell's vertical anchor
                               (a:tcPr/@anchor — was hardcoded center) and
                               its real margins */
                            PptDoc.BodyPr cb = cellBody(cell);
                            cb.anchor = cell.anchor >= 0 ? cell.anchor : 1;
                            drawRichText(c, cell.paras, cb, x, y, x + cw, cellBottom, scale, tb.textShadow);
                        }
                    }
                    x += cw;
                }
                y += rh;
            }
        }

        /* v1.31: cell width incl. gridSpan (EMU) */
        static float colW(PptDoc.Table tb, int ci, int spanR) {
            float w = 0;
            for (int m = 0; m < spanR && ci + m < tb.colWidths.size(); m++) {
                w += tb.colWidths.get(ci + m);
            }
            return w;
        }

        /* v1.31: the cell's own margins as a BodyPr */
        static PptDoc.BodyPr cellBody(PptDoc.TableCell cell) {
            PptDoc.BodyPr cb = new PptDoc.BodyPr();
            cb.insetL = cell.insetL;
            cb.insetR = cell.insetR;
            cb.insetT = cell.insetT;
            cb.insetB = cell.insetB;
            return cb;
        }

        /* v1.31: per-cell border strokes (L,T,R,B order) — enabled edges
           draw with their own color/width/dash; disabled edges draw nothing */
        static void drawCellEdges(Canvas c, PptDoc.TableCell cell,
                                  float l, float t, float r, float b, float scale) {
            float[][] segs = {
                    {l, t, l, b},   /* lnL */
                    {l, t, r, t},   /* lnT */
                    {r, t, r, b},   /* lnR */
                    {l, b, r, b},   /* lnB */
            };
            for (int ei = 0; ei < 4; ei++) {
                PptDoc.Line e = cell.edges[ei];
                if (e == null || !e.enabled) continue;
                Paint p = linePaint(e, scale);
                float[] s = segs[ei];
                c.drawLine(s[0], s[1], s[2], s[3], p);
            }
        }

        static void drawRichText(Canvas c, List<PptDoc.Para> paras, PptDoc.BodyPr body,
                                 float l, float t, float r, float b, float scale, boolean tshadow) {
            if (body == null) body = new PptDoc.BodyPr();
            float boxL = l + body.insetL * scale;
            float boxR = r - body.insetR * scale;
            float boxT = t + body.insetT * scale;
            float boxB = b - body.insetB * scale;
            float textW = boxR - boxL;
            if (textW < 20f) textW = Math.max(20f, (r - l) * 0.5f);

            float pxPerPt = 12700f * scale;
            List<StaticLayout> layouts = new ArrayList<>();
            float totalH = 0f;
            for (PptDoc.Para p : paras) {
                StaticLayout sl = buildParaLayout(p, (int) textW, pxPerPt, tshadow);
                layouts.add(sl);
                totalH += sl.getHeight() + paraSpacingPx(p, pxPerPt);
            }

            float y = boxT;
            if (body.anchor == 1) y = boxT + Math.max(0, (boxB - boxT - totalH) / 2f);
            else if (body.anchor == 2) y = Math.max(boxT, boxB - totalH);

            /* v1.27 FIX: the autofit shrink factor now reaches the WHOLE
               draw loop — bullets and spcBef/spcAft used to stay at 100%
               size next to shrunken text, overflowing the frame */
            float fEff = 1f;
            if (body.autofit == 1 && totalH > (boxB - boxT) && boxB > boxT && totalH > 0) {
                float f = Math.max(0.35f, (boxB - boxT) / totalH);
                if (f < 0.95f) {
                    fEff = f;
                    layouts.clear();
                    totalH = 0;
                    for (PptDoc.Para p : paras) {
                        StaticLayout sl = buildParaLayout(p, (int) textW, pxPerPt * f, tshadow);
                        layouts.add(sl);
                        totalH += sl.getHeight() + paraSpacingPx(p, pxPerPt * f);
                    }
                    if (body.anchor == 1) y = boxT + Math.max(0, (boxB - boxT - totalH) / 2f);
                    else if (body.anchor == 2) y = Math.max(boxT, boxB - totalH);
                }
            }

            c.save();
            c.clipRect(l, t, r, b);
            /* v1.24: per-level numbering counters — PowerPoint numbers each
               outline level independently inside one body; startAt seeds
               the first numbered paragraph of a level */
            int[] autoCnt = new int[9];
            for (int i = 0; i < layouts.size(); i++) {
                PptDoc.Para p = paras.get(i);
                StaticLayout sl = layouts.get(i);
                float spx = pxPerPt * fEff;
                if (p.spcBefPt > 0) y += p.spcBefPt * spx;
                c.save();
                c.translate(boxL, y);
                sl.draw(c);
                c.restore();
                if (p.buChar != null || p.buAutoNum) {
                    int lvl = Math.max(0, Math.min(8, p.lvl));
                    if (p.buAutoNum) {
                        /* v1.32 FIX: an explicit startAt RESTARTS the level's
                           counter at that value (PowerPoint semantics); only
                           paragraphs without startAt continue ++ */
                        if (p.buStart > 1) autoCnt[lvl] = p.buStart;
                        else if (autoCnt[lvl] == 0) autoCnt[lvl] = Math.max(1, p.buStart);
                        else autoCnt[lvl]++;
                    }
                    drawBullet(c, p, sl, boxL, boxR, y, spx, autoCnt[lvl]);
                }
                y += sl.getHeight();
                if (p.spcAftPt > 0) y += p.spcAftPt * spx;
            }
            c.restore();
        }

        private static float paraSpacingPx(PptDoc.Para p, float pxPerPt) {
            float s = 0f;
            if (p.spcBefPt > 0) s += p.spcBefPt * pxPerPt;
            if (p.spcAftPt > 0) s += p.spcAftPt * pxPerPt;
            return s;
        }

        /* bullet marker in the hanging indent area (LTR left / RTL right).
           v1.32 REWRITE: the old positioning ignored the effective indents
           (a bulleted para with marL=0 clamped the marker ONTO the text),
           and the vertical baseline came from the paint metrics rather
           than the layout, so markers floated high on some fonts. Now:
           - horizontal: the marker lives in [hangEdge, textEdge-gap],
             guaranteed at least a 0.16em gap from the text, right-/
             left-aligned to the hang edge like PowerPoint;
           - vertical: drawn on the layout's own first-line baseline. */
        static void drawBullet(Canvas c, PptDoc.Para p, StaticLayout sl,
                               float boxL, float boxR, float y, float pxPerPt, int autoNum) {
            PptDoc.Run fr = p.firstRun();
            /* v1.24: real bullet size — a:buSzPct / a:buSzPts when present,
               otherwise 100% of the text size like PowerPoint (was a fixed
               85% fudge) */
            float txtPt = fr != null ? Math.max(4f, fr.sizePt) : 18f;
            float szPt = p.buSzPct > 0f ? txtPt * p.buSzPct
                    : (p.buSizePt > 0f ? p.buSizePt : txtPt);
            float sizePx = Math.max(5f, szPt * pxPerPt);
            String bt;
            if (p.buAutoNum) {
                /* v1.24: the numbering format comes from a:buAutoNum/@type
                   (arabic/alpha/roman × period/paren/dash); digits follow the
                   paragraph direction — Persian for RTL, ASCII otherwise */
                bt = numMarker(p, autoNum);
            } else {
                bt = p.buChar == null ? "\u2022" : p.buChar;
            }
            Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG);
            bp.setTextSize(sizePx);
            bp.setColor(p.buClr != 0 ? p.buClr
                    : (fr != null && fr.color != 0 ? fr.color : 0xFF1F1F1F));
            float w = bp.measureText(bt);
            float gap = Math.max(2f, sizePx * 0.22f);   /* marker↔text gap (≥0.16em) */
            long eff = effIndents(p);
            float marLpx = (float) (int) (eff >> 32) * pxPerPt / 12700f;
            float indentPx = (int) eff * pxPerPt / 12700f;
            /* hang zone = [boxL + max(0,marL+indent), boxL + marL) for LTR;
               mirrored from boxR for RTL. The marker is placed flush to
               the hang edge (PowerPoint aligns markers to the hang edge)
               but never closer than `gap` to the text edge. */
            float by = y + sl.getLineBaseline(0);
            float bx;
            if (paraIsRtl(p)) {
                float textEdge = boxR - Math.max(0f, marLpx);            /* text right edge */
                float hangEdge = boxR - Math.max(0f, marLpx + indentPx); /* hang right edge (≥ textEdge) */
                float right = hangEdge;                                   /* right-aligned at hang edge */
                if (right - w < textEdge + gap) right = Math.min(boxR, textEdge + gap + w);
                bx = right - w;
            } else {
                float textEdge = boxL + Math.max(0f, marLpx);            /* text left edge */
                float hangEdge = boxL + Math.max(0f, marLpx + indentPx); /* hang left edge (≤ textEdge) */
                bx = hangEdge;                                            /* left-aligned at hang edge */
                if (bx + w > textEdge - gap) bx = Math.max(boxL, textEdge - gap - w);
            }
            c.drawText(bt, bx, by, bp);
        }

        /* v1.26: marker text of a numbered paragraph — format by p.buType.
           New: the «hindi» family (Arabic-script digits ۱۲۳) renders Persian
           digits regardless of paragraph language, plus the Persian-letter
           (الف ب پ …) and Arabic-abjad (ا ب ج …) lists used by real
           Persian/Arabic decks.
           RTL punctuation order: in an RTL paragraph the marker's period/
           dash/paren belongs on the LEFT of the number («.۱» not «۱.»);
           since drawText renders the string in logical order, we emit the
           punctuation as a PREFIX for RTL markers and as a SUFFIX for LTR. */
        static String numMarker(PptDoc.Para p, int n) {
            int t = p.buType;
            boolean rtl = paraIsRtl(p);
            String body;
            if (t >= PptDoc.Para.NUM_ALPHA_LC_PERIOD && t <= PptDoc.Para.NUM_ALPHA_UC_PAREN_R) {
                body = letters26(n);
                if (t == PptDoc.Para.NUM_ALPHA_UC_PERIOD || t == PptDoc.Para.NUM_ALPHA_UC_PAREN_R) {
                    body = body.toUpperCase();
                }
            } else if (t >= PptDoc.Para.NUM_ROMAN_LC_PERIOD && t <= PptDoc.Para.NUM_ROMAN_UC_PAREN_R) {
                body = romanNum(n);
                if (t == PptDoc.Para.NUM_ROMAN_UC_PERIOD || t == PptDoc.Para.NUM_ROMAN_UC_PAREN_R) {
                    body = body.toUpperCase();
                }
            } else if (t == PptDoc.Para.NUM_HINDI_PERIOD || t == PptDoc.Para.NUM_HINDI_PAREN_R
                    || t == PptDoc.Para.NUM_HINDI_DASH1 || t == PptDoc.Para.NUM_HINDI_NUMBERS) {
                body = Fa.d(n);                      /* ۱۲۳ — always Arabic-script */
            } else if (t == PptDoc.Para.NUM_ARABIC_ABJAD) {
                body = abjad(n);                     /* ا ب ج د … */
            } else if (t == PptDoc.Para.NUM_ALPHA_FA) {
                body = faAlpha(n);                   /* ا ب پ ت … */
            } else {
                body = rtl ? Fa.d(n) : String.valueOf(n);
            }
            boolean paren = t == PptDoc.Para.NUM_ARABIC_PAREN_R
                    || t == PptDoc.Para.NUM_ALPHA_LC_PAREN_R || t == PptDoc.Para.NUM_ALPHA_UC_PAREN_R
                    || t == PptDoc.Para.NUM_ROMAN_LC_PAREN_R || t == PptDoc.Para.NUM_ROMAN_UC_PAREN_R
                    || t == PptDoc.Para.NUM_HINDI_PAREN_R;
            boolean dash = t == PptDoc.Para.NUM_ARABIC_DASH || t == PptDoc.Para.NUM_HINDI_DASH1;
            boolean bare = t == PptDoc.Para.NUM_HINDI_NUMBERS;
            if (rtl && !bare) {
                if (paren) return ")" + body;
                if (dash) return "-" + body;
                return "." + body;
            }
            if (paren) return body + ")";
            if (dash) return body + "-";
            if (bare) return body;
            return body + ".";
        }

        /* v1.26: Arabic abjad letters (traditional ordering) */
        static String abjad(int n) {
            final String A = "\u0627\u0628\u062C\u062F\u0647\u0648\u0632\u062D\u0637\u064A"
                    + "\u0643\u0644\u0645\u0646\u0633\u0639\u0641\u0635\u0642\u0631\u0634\u062A"
                    + "\u062B\u062E\u0630\u0636\u0638\u063A";
            if (n < 1) n = 1;
            return String.valueOf(A.charAt((n - 1) % A.length()));
        }

        /* v1.26: Persian letter list in the order Persian PowerPoint uses */
        static String faAlpha(int n) {
            final String A = "\u0627\u0628\u067E\u062A\u062B\u062C\u0686\u062D\u062E\u062F"
                    + "\u0630\u0631\u0632\u0698\u0633\u0634\u0635\u0636\u0637\u0638"
                    + "\u0639\u063A\u0641\u0642\u06A9\u06AF\u0644\u0645\u0646\u0648"
                    + "\u0647\u06CC";
            if (n < 1) n = 1;
            return String.valueOf(A.charAt((n - 1) % A.length()));
        }

        /* v1.24: 1→a, 26→z, 27→aa … (bijective base-26, Office-style) */
        static String letters26(int n) {
            if (n <= 0) n = 1;
            StringBuilder sb = new StringBuilder();
            while (n > 0) {
                n--;
                sb.insert(0, (char) ('a' + (n % 26)));
                n /= 26;
            }
            return sb.length() == 0 ? "a" : sb.toString();
        }

        /* v1.24: lowercase roman numerals */
        static String romanNum(int n) {
            if (n <= 0) return "i";
            int[] vals = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
            String[] syms = {"m", "cm", "d", "cd", "c", "xc", "l", "xl", "x", "ix", "v", "iv", "i"};
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < vals.length; i++) {
                while (n >= vals[i]) { sb.append(syms[i]); n -= vals[i]; }
            }
            return sb.toString();
        }

        /* paragraph base direction.
           v1.26 FIX: the file's explicit a:pPr/@rtl flag now WINS over the
           first-strong-character guess. Before, a Persian paragraph whose
           text happened to start with a Latin word/URL/number («1. عنوان»،
           «PDF را ببینید») rendered LTR — the whole line flipped. Only when
           the file says nothing (hasRtl unset) do we guess from content,
           then fall back to the flag. */
        static boolean paraIsRtl(PptDoc.Para p) {
            if (p.hasRtl) return p.rtl;
            String t = p.plainText();
            if (t != null) {
                for (int i = 0; i < t.length(); i++) {
                    byte d = Character.getDirectionality(t.charAt(i));
                    if (d == Character.DIRECTIONALITY_RIGHT_TO_LEFT
                            || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
                            || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING
                            || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE) return true;
                    if (d == Character.DIRECTIONALITY_LEFT_TO_RIGHT
                            || d == Character.DIRECTIONALITY_LEFT_TO_RIGHT_EMBEDDING
                            || d == Character.DIRECTIONALITY_LEFT_TO_RIGHT_OVERRIDE
                            || d == Character.DIRECTIONALITY_PARAGRAPH_SEPARATOR
                            || d == Character.DIRECTIONALITY_SEGMENT_SEPARATOR) return false;
                }
            }
            return p.rtl;
        }

        /* v1.32: PowerPoint's default hanging indent for a bulleted /
           numbered level-1 paragraph: text at 0.38 cm, bullet hanging at
           the box edge (marL=342900, indent=-342900 EMU). Used when a
           bullet is applied without explicit indents AND when rendering
           files whose pPr carries no marL/indent — without it the marker
           rendered glued to / on top of the text. */
        static final int DEF_BULLET_MARL = 342900;
        static final int DEF_BULLET_INDENT = -342900;

        /* effective (marL, indent) pair for a paragraph — bullets always
           get at least the default hanging indent so the marker never
           sits on the text. */
        static long effIndents(PptDoc.Para p) {
            int marL = p.marL, ind = p.indent;
            if ((p.buChar != null || p.buAutoNum) && marL == 0 && ind == 0) {
                marL = DEF_BULLET_MARL;
                ind = DEF_BULLET_INDENT;
            }
            return ((long) marL << 32) | (ind & 0xFFFFFFFFL);
        }

        /* v1.27: StaticLayout cache — buildParaLayout allocated a spannable
           + TextPaint + spans for EVERY paragraph on EVERY frame (pan/drag
           churned megabytes per second on big decks; GC thrash visible as
           jank). Layouts are immutable, safe to reuse while unchanged. */
        private static final android.util.LruCache<String, StaticLayout> LAYOUT_CACHE =
                new android.util.LruCache<>(96);

        /* v1.29 FIX: the cache key covers only the FIRST run's formatting and
           nothing ever evicted entries — applyTextStyle/applyTheme mutate Run
           objects IN PLACE (same Para object, same text), so toggling italic/
           underline/font/color kept rendering the OLD formatting everywhere
           (canvas, filmstrip, slideshow, PDF export). Every edit now clears
           the cache wholesale via markDirty(). */
        static void invalidateLayoutCache() {
            LAYOUT_CACHE.evictAll();
        }

        static StaticLayout buildParaLayout(PptDoc.Para p, int width, float pxPerPt) {
            return buildParaLayout(p, width, pxPerPt, false);
        }

        /* v1.23: نسخهٔ با سایهٔ متن — سایه باید روی TextPaint قبل از ساخت
           StaticLayout تنظیم شود (رسم و اندازه‌گیری سازگار می‌مانند) */
        static StaticLayout buildParaLayout(PptDoc.Para p, int width, float pxPerPt, boolean tshadow) {
            String cacheKey = null;
            if (p != null) {
                PptDoc.Run fr = p.firstRun();
                cacheKey = System.identityHashCode(p) + "|" + width + "|"
                        + Math.round(pxPerPt * 256) + "|" + (tshadow ? 1 : 0) + "|"
                        + p.plainText().hashCode() + "|" + p.align + (p.rtl ? "r" : "l")
                        + p.lvl + "|" + p.marL + "," + p.indent + "," + p.buChar
                        + "," + p.buAutoNum + "," + p.buType + "," + p.buSzPct + "," + p.buSizePt
                        + "|" + p.lnSpcPct + "," + p.lnSpcPt + "," + p.spcBefPt + "," + p.spcAftPt
                        + "|" + (fr != null ? fr.sizePt + "," + fr.color + "," + fr.bold : "-");
                StaticLayout cached = LAYOUT_CACHE.get(cacheKey);
                if (cached != null) return cached;
            }
            SpannableStringBuilder sb = new SpannableStringBuilder();
            /* v1.27 FIX: force the paragraph BASE DIRECTION — Android derives
               it from the first strong character, so an rtl=1 paragraph
               starting with a Latin word or a number («۱. عنوان»، «PDF را
               ببینید») laid out LTR with flipped run order. A leading RLM
               makes the whole line RTL like PowerPoint. */
            if (paraIsRtl(p)) sb.append('\u200F');
            TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            float basePx = 18f * pxPerPt;
            boolean first = true;
            for (PptDoc.Run r : p.runs) {
                /* v1.27 FIX: the 5px floor on run size was ABSOLUTE — at
                   thumbnail scale real 18pt text rendered at ~5px, oversized
                   relative to its shapes. The floor now scales with pxPerPt
                   (equivalent to 4pt). */
                float px = Math.max(4f * pxPerPt, r.sizePt * pxPerPt);
                if (first) { basePx = px; first = false; }
                int start = sb.length();
                String txt = r.text == null ? "" : r.text;
                if (r.caps == 1 || r.caps == 2) {
                    txt = txt.toUpperCase(java.util.Locale.US);
                }
                sb.append(txt);
                int end = sb.length();
                if (end > start) {
                    sb.setSpan(new AbsoluteSizeSpan((int) px), start, end, 0);
                    if (r.color != 0) sb.setSpan(new ForegroundColorSpan(r.color), start, end, 0);
                    int style = (r.bold ? Typeface.BOLD : 0) | (r.italic ? Typeface.ITALIC : 0);
                    if (style != 0) sb.setSpan(new StyleSpan(style), start, end, 0);
                    if (r.underline) sb.setSpan(new UnderlineSpan(), start, end, 0);
                    if (r.font != null && r.font.length() > 0 && typefaceForName(r.font) != Typeface.DEFAULT) {
                        sb.setSpan(new TypefaceSpan2(typefaceForName(r.font)), start, end, 0);
                    }
                    /* v1.21: real letter tracking (rPr/@spc) — Paint letter
                       spacing is in em of the text size */
                    if (!Float.isNaN(r.spcPt) && r.spcPt != 0f && r.sizePt > 0f) {
                        sb.setSpan(new LetterSpacingSpan(r.spcPt / r.sizePt), start, end, 0);
                    }
                    /* v1.21: hyperlink runs render with the Office link look
                       (underline + theme hlink blue) unless the file set an
                       explicit run color */
                    if (r.link) {
                        sb.setSpan(new UnderlineSpan(), start, end, 0);
                        if (!r.hasColor) sb.setSpan(new ForegroundColorSpan(0xFF0563C1), start, end, 0);
                    }
                }
            }
            tp.setTextSize(basePx);
            tp.setColor(0xFF1F1F1F);
            if (tshadow) {
                tp.setShadowLayer(2.5f * pxPerPt, 1.2f * pxPerPt, 1.2f * pxPerPt, 0x66000000);
            }

            /* absolute alignment: OOXML l/r are physical edges, while
               ALIGN_NORMAL/OPPOSITE follow the paragraph bidi direction.
               This was INVERTED for all LTR (English) decks in v1.15/1.16. */
            Layout.Alignment al;
            boolean rtl = paraIsRtl(p);
            if (p.align == PptDoc.Para.ALIGN_CTR) {
                al = Layout.Alignment.ALIGN_CENTER;
            } else if (rtl) {
                al = (p.align == PptDoc.Para.ALIGN_L) ? Layout.Alignment.ALIGN_OPPOSITE
                        : Layout.Alignment.ALIGN_NORMAL;
            } else {
                al = (p.align == PptDoc.Para.ALIGN_R) ? Layout.Alignment.ALIGN_OPPOSITE
                        : Layout.Alignment.ALIGN_NORMAL;
            }

            /* real line spacing (a:lnSpc): pct multiplier or exact points.
               v1.27 FIX: the old fixed 1.15 default and ×1.17 pct inflation
               made EVERY paragraph render 15–37% taller than PowerPoint; 100%
               single spacing in OOXML == the font's natural line height,
               which is exactly what Android spacingMult=1.0 gives. */
            float spacing = 1.0f;
            if (p.lnSpcPct > 0f) {
                spacing = Math.max(0.5f, Math.min(4f, p.lnSpcPct));
            } else if (p.lnSpcPt > 0f) {
                /* exact points: convert to a multiplier against the REAL
                 font line height of the base paint (was a 1.2× guess on the
                 first run's point size, drifting on mixed-size lines) */
                float fmH = -tp.ascent() + tp.descent();
                if (fmH <= 1f) fmH = basePx * 1.2f;
                spacing = Math.max(0.5f, Math.min(6f, (p.lnSpcPt * pxPerPt) / fmH));
            }

            /* hanging indent: text at marL, bullet hangs at marL+indent.
               v1.32 FIX: bulleted/numbered paragraphs WITHOUT explicit
               marL/indent (files that rely on list defaults, or bullets
               applied by the editor pre-v1.32) get PowerPoint's default
               0.38 cm hanging indent — before, both the marker and the
               text started at x=0 and the bullet sat ON the text. */
            long eff = effIndents(p);
            int eMarL = (int) (eff >> 32);
            int eInd = (int) eff;
            if (eMarL != 0 || eInd != 0) {
                float k = pxPerPt / 12700f;
                int marLpx = Math.round(eMarL * k);
                int firstPx = (p.buChar != null || p.buAutoNum)
                        ? marLpx : Math.round((eMarL + eInd) * k);
                if (firstPx < 0) firstPx = 0;
                if (marLpx < 0) marLpx = 0;
                sb.setSpan(new android.text.style.LeadingMarginSpan.Standard(firstPx, marLpx),
                        0, sb.length(), 0);
            }

            StaticLayout sl = new StaticLayout(sb, tp, Math.max(8, width), al, spacing, 0f, false);
            if (cacheKey != null && sb.length() > 0) LAYOUT_CACHE.put(cacheKey, sl);
            return sl;
        }

        static final class TypefaceSpan2 extends MetricAffectingSpan {
            private final Typeface tf;

            TypefaceSpan2(Typeface tf) {
                this.tf = tf;
            }

            @Override
            public void updateMeasureState(TextPaint p) {
                p.setTypeface(tf);
            }

            @Override
            public void updateDrawState(android.text.TextPaint p) {
                p.setTypeface(tf);
            }
        }

        /* v1.21: letter tracking (rPr/@spc) — Paint.setLetterSpacing takes EM
           of the text size; spcPt is points. A MetricAffectingSpan keeps
           measure and draw consistent so wrapped lines match. */
        static final class LetterSpacingSpan extends MetricAffectingSpan {
            private final float em;

            LetterSpacingSpan(float em) {
                this.em = em;
            }

            @Override
            public void updateMeasureState(TextPaint p) {
                p.setLetterSpacing(em);
            }

            @Override
            public void updateDrawState(android.text.TextPaint p) {
                p.setLetterSpacing(em);
            }
        }

        static Paint linePaint(PptDoc.Line l, float scale) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1f, l.widthPt * 12700f * scale));
            p.setColor(l.color);
            applyDash(p, l.dash, p.getStrokeWidth());
            return p;
        }

        static void applyDash(Paint p, int dash, float w) {
            if (dash <= 0) return;
            w = Math.max(1f, w);
            DashPathEffect eff;
            if (dash == 1) eff = new DashPathEffect(new float[]{w * 3, w * 2}, 0);
            else if (dash == 2) eff = new DashPathEffect(new float[]{w, w * 2}, 0);
            else if (dash == 3) eff = new DashPathEffect(new float[]{w * 3, w * 2, w, w * 2}, 0);
            else eff = new DashPathEffect(new float[]{w * 2, w * 2}, 0);
            p.setPathEffect(eff);
        }

        static PptDoc.BodyPr bodyOf(PptDoc.Shape sh) {
            if (sh instanceof PptDoc.TextShape) return ((PptDoc.TextShape) sh).body;
            if (sh instanceof PptDoc.AutoShape) return ((PptDoc.AutoShape) sh).body;
            return new PptDoc.BodyPr();
        }

        /* ---------------- geometry paths ---------------- */
        static Path shapePath(int type, float l, float t, float r, float b) {
            Path p = new Path();
            float w = r - l, h = b - t, cx = (l + r) / 2f, cy = (t + b) / 2f;
            switch (type) {
                case PptDoc.Shape.TYPE_ROUND_RECT: {
                    float rad = Math.min(w, h) * 0.18f;
                    p.addRoundRect(new RectF(l, t, r, b), rad, rad, Path.Direction.CW);
                    break;
                }
                case PptDoc.Shape.TYPE_OVAL:
                    p.addOval(new RectF(l, t, r, b), Path.Direction.CW);
                    break;
                case PptDoc.Shape.TYPE_TRIANGLE:
                    p.moveTo(cx, t);
                    p.lineTo(r, b);
                    p.lineTo(l, b);
                    p.close();
                    break;
                case PptDoc.Shape.TYPE_RIGHT_ARROW: {
                    float hx = l + w * 0.62f, n1 = t + h * 0.30f, n2 = b - h * 0.30f;
                    p.moveTo(l, n1);
                    p.lineTo(hx, n1);
                    p.lineTo(hx, t);
                    p.lineTo(r, cy);
                    p.lineTo(hx, b);
                    p.lineTo(hx, n2);
                    p.lineTo(l, n2);
                    p.close();
                    break;
                }
                case PptDoc.Shape.TYPE_STAR: {
                    double a0 = -Math.PI / 2;
                    for (int i = 0; i < 10; i++) {
                        double ang = a0 + i * Math.PI / 5;
                        double rad = (i % 2 == 0) ? 1.0 : 0.382;
                        float px = cx + (float) (Math.cos(ang) * (w / 2) * rad);
                        float py = cy + (float) (Math.sin(ang) * (h / 2) * rad);
                        if (i == 0) p.moveTo(px, py);
                        else p.lineTo(px, py);
                    }
                    p.close();
                    break;
                }
                case PptDoc.Shape.TYPE_DIAMOND:
                    p.moveTo(cx, t);
                    p.lineTo(r, cy);
                    p.lineTo(cx, b);
                    p.lineTo(l, cy);
                    p.close();
                    break;
                case PptDoc.Shape.TYPE_PENTAGON:
                    polyPath(p, cx, cy, w, h, 5, -Math.PI / 2);
                    break;
                case PptDoc.Shape.TYPE_HEXAGON:
                    polyPath(p, cx, cy, w, h, 6, 0);
                    break;
                case PptDoc.Shape.TYPE_PARALLELOGRAM: {
                    float o = w * 0.22f;
                    p.moveTo(l + o, t);
                    p.lineTo(r, t);
                    p.lineTo(r - o, b);
                    p.lineTo(l, b);
                    p.close();
                    break;
                }
                case PptDoc.Shape.TYPE_HEART:
                    p.moveTo(cx, b);
                    p.cubicTo(l + w * 0.02f, t + h * 0.62f, l, t + h * 0.25f, cx - w * 0.22f, t + h * 0.16f);
                    p.cubicTo(cx - w * 0.04f, t + h * 0.08f, cx, t + h * 0.18f, cx, t + h * 0.24f);
                    p.cubicTo(cx, t + h * 0.18f, cx + w * 0.04f, t + h * 0.08f, cx + w * 0.22f, t + h * 0.16f);
                    p.cubicTo(r, t + h * 0.25f, r - w * 0.02f, t + h * 0.62f, cx, b);
                    p.close();
                    break;
                case PptDoc.Shape.TYPE_CLOUD:
                    p.addCircle(cx - w * 0.26f, cy + h * 0.14f, h * 0.24f, Path.Direction.CW);
                    p.addCircle(cx - w * 0.02f, cy - h * 0.02f, h * 0.30f, Path.Direction.CW);
                    p.addCircle(cx + w * 0.26f, cy + h * 0.14f, h * 0.24f, Path.Direction.CW);
                    p.addRect(cx - w * 0.26f, cy + h * 0.02f, cx + w * 0.26f, b - h * 0.04f, Path.Direction.CW);
                    break;
                case PptDoc.Shape.TYPE_LIGHTNING: {
                    float[][] pts = {{0.60f, 0.00f}, {0.16f, 0.55f}, {0.44f, 0.55f},
                            {0.34f, 1.00f}, {0.84f, 0.40f}, {0.56f, 0.40f}};
                    for (int i = 0; i < pts.length; i++) {
                        float px = l + pts[i][0] * w, py = t + pts[i][1] * h;
                        if (i == 0) p.moveTo(px, py);
                        else p.lineTo(px, py);
                    }
                    p.close();
                    break;
                }
                case PptDoc.Shape.TYPE_PLUS: {
                    float aw = w * 0.34f, ah = h * 0.34f;
                    p.addRect(cx - aw / 2, t, cx + aw / 2, b, Path.Direction.CW);
                    p.addRect(l, cy - ah / 2, r, cy + ah / 2, Path.Direction.CW);
                    break;
                }
                case PptDoc.Shape.TYPE_CHEVRON:
                    p.moveTo(l, t);
                    p.lineTo(r - w * 0.28f, t);
                    p.lineTo(r, cy);
                    p.lineTo(r - w * 0.28f, b);
                    p.lineTo(l, b);
                    p.lineTo(l + w * 0.28f, cy);
                    p.close();
                    break;
                default:
                    p.addRect(l, t, r, b, Path.Direction.CW);
                    break;
            }
            return p;
        }

        static void polyPath(Path p, float cx, float cy, float w, float h, int n, double a0) {
            for (int i = 0; i < n; i++) {
                double ang = a0 + i * 2 * Math.PI / n;
                float px = cx + (float) (Math.cos(ang) * w / 2);
                float py = cy + (float) (Math.sin(ang) * h / 2);
                if (i == 0) p.moveTo(px, py);
                else p.lineTo(px, py);
            }
            p.close();
        }

        /* ================= v1.22: hyperlink hit-testing ==================
           Rebuilds the paragraph layouts exactly like drawRichText and maps
           a tap to the run underneath; hitLink returns that run's link URL,
           hitLinkSlide (v1.29) its internal "go to slide" target. */
        static String hitLink(PptDoc.Shape sh, float pxCanvas, float pyCanvas, float scale) {
            PptDoc.Run hit = hitLinkRun(sh, pxCanvas, pyCanvas, scale);
            return hit == null ? null : hit.linkUrl;
        }

        /* v1.29 NEW: the run's internal jump target (1-based slide number)
           or 0 when the tap did not land on a jump link. */
        static int hitLinkSlide(PptDoc.Shape sh, float pxCanvas, float pyCanvas, float scale) {
            PptDoc.Run hit = hitLinkRun(sh, pxCanvas, pyCanvas, scale);
            return hit == null ? 0 : hit.linkSlide;
        }

        static PptDoc.Run hitLinkRun(PptDoc.Shape sh, float pxCanvas, float pyCanvas, float scale) {
            if (!(sh instanceof PptDoc.TextShape) && !(sh instanceof PptDoc.AutoShape)) return null;
            List<PptDoc.Para> paras = parasOf(sh);
            if (paras == null || paras.isEmpty()) return null;
            boolean anyLink = false;
            for (PptDoc.Para p : paras) {
                for (PptDoc.Run r : p.runs) if (r.link && (r.linkUrl != null || r.linkSlide > 0)) { anyLink = true; break; }
                if (anyLink) break;
            }
            if (!anyLink) return null;

            PptDoc.BodyPr body = bodyOf(sh);
            if (body == null) body = new PptDoc.BodyPr();
            float l = sh.x * scale, t = sh.y * scale;
            float r = l + sh.w * scale, b = t + sh.h * scale;
            float boxL = l + body.insetL * scale;
            float boxR = r - body.insetR * scale;
            float boxT = t + body.insetT * scale;
            float boxB = b - body.insetB * scale;
            float textW = boxR - boxL;
            if (textW < 20f) textW = Math.max(20f, (r - l) * 0.5f);
            float pxPerPt = 12700f * scale;

            List<StaticLayout> layouts = new ArrayList<>();
            float totalH = 0f;
            for (PptDoc.Para p : paras) {
                StaticLayout sl = buildParaLayout(p, (int) textW, pxPerPt);
                layouts.add(sl);
                totalH += sl.getHeight() + paraSpacingPx(p, pxPerPt);
            }
            float shrink = 1f;
            if (body.autofit == 1 && totalH > (boxB - boxT) && boxB > boxT && totalH > 0) {
                shrink = Math.max(0.35f, (boxB - boxT) / totalH);
                if (shrink < 0.95f) {
                    layouts.clear();
                    totalH = 0;
                    for (PptDoc.Para p : paras) {
                        StaticLayout sl = buildParaLayout(p, (int) textW, pxPerPt * shrink);
                        layouts.add(sl);
                        totalH += sl.getHeight() + paraSpacingPx(p, pxPerPt * shrink);
                    }
                }
            }

            float y = boxT;
            if (body.anchor == 1) y = boxT + Math.max(0, (boxB - boxT - totalH) / 2f);
            else if (body.anchor == 2) y = Math.max(boxT, boxB - totalH);

            for (int i = 0; i < layouts.size() && i < paras.size(); i++) {
                PptDoc.Para p = paras.get(i);
                StaticLayout sl = layouts.get(i);
                if (p.spcBefPt > 0) y += p.spcBefPt * pxPerPt * shrink;
                float paraBottom = y + sl.getHeight();
                if (pyCanvas >= y - 2f && pyCanvas <= paraBottom + 2f) {
                    int line = sl.getLineForVertical((int) (pyCanvas - y));
                    if (line >= 0 && line < sl.getLineCount()) {
                        float lx = pxCanvas - boxL;
                        float lineLeft = sl.getLineLeft(line);
                        float lineRight = sl.getLineRight(line);
                        if (lx >= lineLeft - 4f && lx <= lineRight + 4f) {
                            int off = sl.getOffsetForHorizontal(line, Math.max(lineLeft, Math.min(lineRight, lx)));
                            /* v1.29 FIX: RTL paragraphs render with a leading
                               '\u200F' RLM sentinel (see buildParaLayout) — the
                               LAYOUT offset is +1 relative to the paragraph's
                               run text; without the correction an end-edge
                               tap attributed the link to the WRONG run. */
                            if (paraIsRtl(p) && off > 0) off -= 1;
                            int cum = 0;
                            for (PptDoc.Run run : p.runs) {
                                int len = run.text == null ? 0 : run.text.length();
                                if (off <= cum + len) {
                                    if (run.link && (run.linkUrl != null || run.linkSlide > 0)) {
                                        return run;
                                    }
                                    return null;
                                }
                                cum += len;
                            }
                        }
                    }
                    return null;
                }
                y = paraBottom;
                if (p.spcAftPt > 0) y += p.spcAftPt * pxPerPt * shrink;
            }
            return null;
        }

        /* ==================== v1.22: video/audio poster ==================== */
        static void drawVideo(Canvas c, PptDoc.Video vid, float l, float t, float r, float b, float scale) {
            boolean audio = vid.mimeType != null && vid.mimeType.startsWith("audio");
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            boolean drew = false;
            if (vid.posterData != null) {
                Bitmap bmp = decodeBytes(vid.posterData, Math.max(vid.w, vid.h));
                if (bmp != null && !bmp.isRecycled()) {
                    c.drawBitmap(bmp, null, new RectF(l, t, r, b), p);
                    /* dark scrim so the play glyph reads on any poster */
                    Paint scrim = new Paint();
                    scrim.setColor(0x44000000);
                    c.drawRect(l, t, r, b, scrim);
                    drew = true;
                }
            }
            if (!drew) {
                Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG);
                bp.setColor(0xFF1E1E1E);
                c.drawRect(l, t, r, b, bp);
            }
            float cx = (l + r) / 2f, cy = (t + b) / 2f;
            float rad = Math.min(r - l, b - t) * 0.16f;
            Paint cp = new Paint(Paint.ANTI_ALIAS_FLAG);
            cp.setColor(0xE6FFFFFF);
            c.drawCircle(cx, cy, rad, cp);
            Paint tri = new Paint(Paint.ANTI_ALIAS_FLAG);
            tri.setColor(0xFF1E1E1E);
            Path tp = new Path();
            float ix = rad * 0.52f;
            tp.moveTo(cx - ix * 0.8f, cy - ix);
            tp.lineTo(cx - ix * 0.8f, cy + ix);
            tp.lineTo(cx + ix, cy);
            tp.close();
            c.drawPath(tp, tri);
            if (audio) {
                Paint ap = new Paint(Paint.ANTI_ALIAS_FLAG);
                ap.setColor(0xFFFFFFFF);
                ap.setTextSize(Math.max(8f, (b - t) * 0.07f));
                ap.setTextAlign(Paint.Align.CENTER);
                c.drawText("♪ " + (vid.videoName == null ? "audio" : vid.videoName),
                        cx, b - ap.getTextSize() * 0.8f, ap);
            }
        }

        /* v1.23: همان دیکود مقاومِ تصاویر برای پوستر ویدئو/صدا */
        static Bitmap decodeBytes(byte[] data) {
            return decodeBytes(data, 0);
        }

        /* v1.28 FIX: picture-fills and video posters now go through the SAME
           decode chain as real pictures — TIFF/EMF/WMF fills and CMYK JPEG
           posters used to hit raw BitmapFactory and render nothing */
        static Bitmap decodeBytes(byte[] data, long targetExtentEmu) {
            if (data == null || data.length == 0) return null;
            String fmt = PptxReader.sniffImgFormat(data);
            if ("tiff".equals(fmt) || "tif".equals(fmt)) {
                String key = "tiff:" + data.length + ":" + data.hashCode();
                synchronized (BMP_LOCK) {
                    Bitmap cached = BMP_CACHE.get(key);
                    if (cached != null) {
                        if (!cached.isRecycled()) return cached;
                        BMP_CACHE.remove(key);
                    }
                }
                int target = targetExtentEmu > 0
                        ? Math.max(256, Math.round(targetExtentEmu / 12700f * 2.0f)) : 1024;
                Bitmap tb = PptTiffDecoder.decode(data, target);
                if (tb != null) {
                    synchronized (BMP_LOCK) { BMP_CACHE.put(key, tb); trimCache(); }
                    return tb;
                }
                return null;
            }
            if ("emf".equals(fmt) || "wmf".equals(fmt)) {
                String key = fmt + ":" + data.length + ":" + data.hashCode();
                synchronized (BMP_LOCK) {
                    Bitmap cached = BMP_CACHE.get(key);
                    if (cached != null) {
                        if (!cached.isRecycled()) return cached;
                        BMP_CACHE.remove(key);
                    }
                }
                int target = targetExtentEmu > 0
                        ? Math.max(256, Math.round(targetExtentEmu / 12700f * 2.0f)) : 512;
                Bitmap mb = PptMetafile.render(data, fmt, target);
                if (mb != null) {
                    synchronized (BMP_LOCK) { BMP_CACHE.put(key, mb); trimCache(); }
                    return mb;
                }
                return null;
            }
            Bitmap b = decodeRobust(data, targetExtentEmu);
            if (b == null) b = javaJpeg(data, targetExtentEmu);
            return b;
        }

        /* v1.28: pure-Java JPEG fallback — CMYK/YCCK (4-component) JPEGs and
           odd-but-valid streams Skia rejects. Sizing matches decodeRobust. */
        static Bitmap javaJpeg(byte[] data, long targetExtentEmu) {
            if (data == null || data.length < 4) return null;
            if ((data[0] & 255) != 0xFF || (data[1] & 255) != 0xD8) return null;
            int maxDim = 2048;
            if (targetExtentEmu > 0) {
                maxDim = Math.max(256, Math.round(targetExtentEmu / 12700f * 1.5f));
            }
            maxDim = Math.min(maxDim, 4096);
            PptJpegDecoder.Result r = PptJpegDecoder.decode(data, maxDim);
            if (r == null || r.width <= 0 || r.height <= 0) return null;
            try {
                return Bitmap.createBitmap(r.pixels, r.width, r.height, Bitmap.Config.ARGB_8888);
            } catch (Throwable t) {
                return null;
            }
        }

        /* ========================= v1.22: CHARTS ============================
           A real PowerPoint viewer renders native DrawingML charts; this
           engine covers bar/col (clustered|stacked|100%|horizontal), line
           (+markers), area, pie, doughnut (per-point colors + hole), scatter
           with axes, gridlines, tick + category labels, axis titles, legend,
           data labels and a chart title — in Persian/Latin text alike. */
        static void drawChart(Canvas c, PptDoc.Chart ch, float l, float t, float r, float b, float scale) {
            float pxPerPt = 12700f * scale;

            /* chart body: explicit fill or white + subtle frame */
            Paint bgP = new Paint(Paint.ANTI_ALIAS_FLAG);
            bgP.setColor(ch.fill.type == PptDoc.Fill.SOLID ? ch.fill.color : 0xFFFFFFFF);
            c.drawRect(l, t, r, b, bgP);
            if (ch.line.enabled) {
                c.drawRect(l, t, r, b, linePaint(ch.line, scale));
            } else {
                Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
                frame.setStyle(Paint.Style.STROKE);
                frame.setStrokeWidth(Math.max(1f, 0.75f * pxPerPt));
                frame.setColor(0xFFD9D9D9);
                c.drawRect(l, t, r, b, frame);
            }

            float pad = 5f * pxPerPt;
            float x0 = l + pad, x1 = r - pad, y0 = t + pad, y1 = b - pad;
            if (x1 - x0 < 40f * scale || y1 - y0 < 40f * scale) return;

            /* ---------- title ---------- */
            if (ch.title != null && ch.title.length() > 0) {
                Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
                tp.setTypeface(Typeface.DEFAULT_BOLD);
                tp.setColor(0xFF404040);
                tp.setTextSize(12f * pxPerPt);
                while (tp.measureText(ch.title) > (x1 - x0) && tp.getTextSize() > 6.5f * pxPerPt) {
                    tp.setTextSize(tp.getTextSize() * 0.92f);
                }
                tp.setTextAlign(Paint.Align.CENTER);
                c.drawText(ch.title, (x0 + x1) / 2f, y0 + tp.getTextSize(), tp);
                y0 += tp.getTextSize() * 1.55f;
            }

            boolean circular = ch.isCircular();
            int nSer = ch.series.size();
            int nCat = ch.categories.size();
            for (PptDoc.ChartSeries s : ch.series) {
                nCat = Math.max(nCat, s.values.size());
            }
            if (nCat == 0) nCat = 1;

            /* ---------- legend ---------- */
            List<String> legTexts = new ArrayList<>();
            if (circular) {
                for (int i = 0; i < nCat; i++) legTexts.add(i < ch.categories.size() ? ch.categories.get(i) : ("" + (i + 1)));
            } else {
                for (PptDoc.ChartSeries s : ch.series) legTexts.add(s.name == null ? "" : s.name);
                boolean anyName = false;
                for (String s : legTexts) if (s.length() > 0) { anyName = true; break; }
                if (!anyName) legTexts.clear();
            }
            boolean legendOn = ch.legend && !legTexts.isEmpty();
            Paint legP = new Paint(Paint.ANTI_ALIAS_FLAG);
            legP.setTextSize(9f * pxPerPt);
            legP.setColor(0xFF595959);
            float legSw = 8f * pxPerPt;                     /* color square */
            float legGap = 4f * pxPerPt;
            float legMaxW = 0f;
            for (String s : legTexts) legMaxW = Math.max(legMaxW, legP.measureText(s));
            float legItemW = legSw + legGap + legMaxW;
            float legRowH = legP.getTextSize() * 1.9f;

            if (legendOn && (ch.legendPos == 0 || ch.legendPos == 2)) {
                /* side legend (vertical list) */
                float legW = legItemW + 2f * pxPerPt;
                if (legW > (x1 - x0) * 0.4f) legW = (x1 - x0) * 0.4f;
                if (ch.legendPos == 0) {
                    drawLegendSide(c, legTexts, ch, x1 - legW, y0, x1, y1, legP, legSw, legGap, circular);
                    x1 -= legW + 3f * pxPerPt;
                } else {
                    drawLegendSide(c, legTexts, ch, x0, y0, x0 + legW, y1, legP, legSw, legGap, circular);
                    x0 += legW + 3f * pxPerPt;
                }
            } else if (legendOn) {
                /* top/bottom legend (wrapped rows) */
                java.util.List<java.util.List<Integer>> rows = legendRows(legTexts, legP, legItemW, x1 - x0);
                float legH = rows.size() * legRowH;
                if (ch.legendPos == 1) {
                    drawLegendRows(c, rows, legTexts, ch, x0, y1 - legH, x1, y1, legP, legSw, legGap, circular, legItemW);
                    y1 -= legH + 2f * pxPerPt;
                } else {
                    drawLegendRows(c, rows, legTexts, ch, x0, y0, x1, y0 + legH, legP, legSw, legGap, circular, legItemW);
                    y0 += legH + 2f * pxPerPt;
                }
            }

            if (circular) {
                drawPiePlot(c, ch, x0, y0, x1, y1, pxPerPt);
                return;
            }

            /* ---------- axis gutters ---------- */
            Paint axP = new Paint(Paint.ANTI_ALIAS_FLAG);
            axP.setTextSize(8.5f * pxPerPt);
            axP.setColor(0xFF595959);
            boolean horiz = ch.isHorizontal();

            /* value range */
            double vMin = 0, vMax = 0, base = 0;
            boolean stacked = ch.isStacked();
            boolean percent = ch.chartType == PptDoc.Chart.BAR_PERCENT || ch.chartType == PptDoc.Chart.BARH_PERCENT;
            if (stacked) {
                for (int i = 0; i < nCat; i++) {
                    double sum = 0, minSum = 0, acc = 0;
                    for (PptDoc.ChartSeries s : ch.series) {
                        double v = i < s.values.size() && s.values.get(i) != null ? s.values.get(i) : 0;
                        acc += v;
                        sum = Math.max(sum, acc);
                        minSum = Math.min(minSum, acc);
                    }
                    vMax = Math.max(vMax, sum);
                    vMin = Math.min(vMin, minSum);
                }
                if (percent) { vMin = 0; vMax = 100; }
            } else {
                for (PptDoc.ChartSeries s : ch.series) {
                    for (Double v : s.values) {
                        if (v == null || Double.isNaN(v) || Double.isInfinite(v)) continue;
                        vMin = Math.min(vMin, v);
                        vMax = Math.max(vMax, v);
                    }
                }
            }
            if (vMin > 0) vMin = 0;
            if (vMax < 0) vMax = 0;
            if (vMax == vMin) vMax = vMin + 1;
            double range = vMax - vMin;
            vMax += range * 0.04;
            if (vMin < 0) vMin -= range * 0.04;
            double step = niceStep((vMax - vMin) / 4.0);
            vMax = Math.ceil(vMax / step) * step;
            vMin = vMin < 0 ? Math.floor(vMin / step) * step : 0;
            if (vMax <= vMin) vMax = vMin + step;

            /* widest tick label */
            String fmt0 = tickFmt(step);
            float tickW = 0f;
            for (double v = vMin; v <= vMax + step / 2; v += step) {
                tickW = Math.max(tickW, axP.measureText(tick(v, fmt0)));
            }
            float valTitleSize = 9f * pxPerPt;
            float catTitleSize = 9f * pxPerPt;

            float padVal = tickW + 5f * pxPerPt;
            float padCat = axP.getTextSize() * 1.7f;
            if (ch.valTitle != null && ch.valTitle.length() > 0) padVal += valTitleSize * 1.6f;
            if (ch.catTitle != null && ch.catTitle.length() > 0) padCat += catTitleSize * 1.6f;

            float plotL, plotT, plotR, plotB;
            if (horiz) {
                plotL = x0 + padCat;
                plotR = x1 - 4f * pxPerPt;
                plotT = y0 + 2f * pxPerPt;
                plotB = y1 - padVal;
                if (plotR - plotL < 30f * scale) { plotL = x0; plotR = x1; }
            } else {
                plotL = x0 + padVal;
                plotR = x1 - 3f * pxPerPt;
                plotT = y0 + 2f * pxPerPt;
                plotB = y1 - padCat;
                if (plotR - plotL < 30f * scale) { plotL = x0; plotR = x1; }
            }
            if (plotB - plotT < 30f * scale) { plotT = y0; plotB = y1; }

            double valSpan = vMax - vMin;
            /* map value → plot coordinate */
            /* vertical: y = plotB - (v - vMin)/span * (plotB-plotT)
               horizontal: x = plotL + (v - vMin)/span * (plotR-plotL) */

            Paint gridP = new Paint(Paint.ANTI_ALIAS_FLAG);
            gridP.setStyle(Paint.Style.STROKE);
            gridP.setStrokeWidth(Math.max(0.8f, 0.6f * pxPerPt));
            gridP.setColor(0xFFE6E6E6);
            Paint axisP = new Paint(Paint.ANTI_ALIAS_FLAG);
            axisP.setStyle(Paint.Style.STROKE);
            axisP.setStrokeWidth(Math.max(1f, 0.9f * pxPerPt));
            axisP.setColor(0xFF9E9E9E);

            /* ---------- gridlines + value ticks ---------- */
            float baseCoord = horiz ? plotL : plotB;
            if (!horiz && vMin < 0) baseCoord = plotB - (float) ((0 - vMin) / valSpan * (plotB - plotT));
            if (horiz && vMin < 0) baseCoord = plotL + (float) ((0 - vMin) / valSpan * (plotR - plotL));

            for (double v = vMin; v <= vMax + step / 2; v += step) {
                String lbl = tick(v, fmt0);
                float coord;
                if (horiz) {
                    coord = plotL + (float) ((v - vMin) / valSpan * (plotR - plotL));
                    if (ch.gridlines && Math.abs(v) > step / 1e6) {
                        c.drawLine(coord, plotT, coord, plotB, gridP);
                    }
                    axP.setTextAlign(Paint.Align.CENTER);
                    c.drawText(lbl, coord, plotB + axP.getTextSize() * 1.25f, axP);
                } else {
                    coord = plotB - (float) ((v - vMin) / valSpan * (plotB - plotT));
                    if (ch.gridlines && Math.abs(v) > step / 1e6) {
                        c.drawLine(plotL, coord, plotR, coord, gridP);
                    }
                    axP.setTextAlign(Paint.Align.RIGHT);
                    c.drawText(lbl, plotL - 4f * pxPerPt, coord + axP.getTextSize() * 0.35f, axP);
                }
            }
            /* axes lines + baseline */
            if (!horiz) {
                c.drawLine(plotL, plotT, plotL, plotB, axisP);
                c.drawLine(plotL, baseCoord, plotR, baseCoord, axisP);
            } else {
                c.drawLine(plotL, plotT, plotL, plotB, axisP);
                c.drawLine(plotL, baseCoord, plotR, baseCoord, axisP);
            }

            /* ---------- category labels ---------- */
            Paint catP = new Paint(Paint.ANTI_ALIAS_FLAG);
            catP.setColor(0xFF595959);
            for (int i = 0; i < nCat; i++) {
                String s = i < ch.categories.size() ? ch.categories.get(i) : "";
                float avail = horiz ? (plotB - plotT) / nCat : (plotR - plotL) / nCat;
                catP.setTextSize(9f * pxPerPt);
                while (catP.measureText(s) > avail && catP.getTextSize() > 5f * pxPerPt) {
                    catP.setTextSize(catP.getTextSize() * 0.92f);
                }
                if (horiz) {
                    float cy = plotT + (i + 0.5f) * (plotB - plotT) / nCat;
                    catP.setTextAlign(Paint.Align.RIGHT);
                    c.drawText(s, plotL - 4f * pxPerPt, cy + catP.getTextSize() * 0.35f, catP);
                } else {
                    float cx = plotL + (i + 0.5f) * (plotR - plotL) / nCat;
                    catP.setTextAlign(Paint.Align.CENTER);
                    c.drawText(s, cx, plotB + catP.getTextSize() * 1.35f + (axP.getTextSize() * 0.6f), catP);
                }
            }

            /* ---------- axis titles ---------- */
            if (ch.valTitle != null && ch.valTitle.length() > 0) {
                Paint at = new Paint(Paint.ANTI_ALIAS_FLAG);
                at.setTextSize(valTitleSize);
                at.setColor(0xFF7F7F7F);
                at.setTextAlign(Paint.Align.CENTER);
                c.save();
                if (horiz) {
                    c.rotate(0, (plotL + plotR) / 2f, y1 - valTitleSize * 0.2f);
                    c.drawText(ch.valTitle, (plotL + plotR) / 2f, y1 - valTitleSize * 0.2f, at);
                } else {
                    float ax = x0 + axP.getTextSize() * 0.6f;
                    c.rotate(-90, ax, (plotT + plotB) / 2f);
                    c.drawText(ch.valTitle, ax, (plotT + plotB) / 2f, at);
                }
                c.restore();
            }
            if (ch.catTitle != null && ch.catTitle.length() > 0) {
                Paint at = new Paint(Paint.ANTI_ALIAS_FLAG);
                at.setTextSize(catTitleSize);
                at.setColor(0xFF7F7F7F);
                at.setTextAlign(Paint.Align.CENTER);
                if (horiz) {
                    float ax = x0 + axP.getTextSize() * 0.6f;
                    c.save();
                    c.rotate(-90, ax, (plotT + plotB) / 2f);
                    c.drawText(ch.catTitle, ax, (plotT + plotB) / 2f, at);
                    c.restore();
                } else {
                    c.drawText(ch.catTitle, (plotL + plotR) / 2f, y1 - catTitleSize * 0.3f, at);
                }
            }

            /* ---------- series ---------- */
            Paint fillP = new Paint(Paint.ANTI_ALIAS_FLAG);
            Paint lblP = new Paint(Paint.ANTI_ALIAS_FLAG);
            lblP.setTextSize(8f * pxPerPt);
            lblP.setColor(0xFF404040);
            lblP.setTextAlign(Paint.Align.CENTER);

            int type = ch.chartType;
            if (type == PptDoc.Chart.LINE || type == PptDoc.Chart.LINE_MARKER || type == PptDoc.Chart.SCATTER) {
                for (int si = 0; si < nSer; si++) {
                    PptDoc.ChartSeries s = ch.series.get(si);
                    if (s.values.isEmpty()) continue;
                    int col = ch.seriesColor(si);
                    Paint ln = new Paint(Paint.ANTI_ALIAS_FLAG);
                    ln.setColor(col);
                    ln.setStyle(Paint.Style.STROKE);
                    ln.setStrokeWidth(Math.max(1.5f, 2.25f * pxPerPt));
                    ln.setStrokeCap(Paint.Cap.ROUND);
                    ln.setStrokeJoin(Paint.Join.ROUND);
                    Path poly = new Path();
                    boolean first = true;
                    for (int i = 0; i < nCat && i < s.values.size(); i++) {
                        Double v = s.values.get(i);
                        if (v == null || Double.isNaN(v)) continue;
                        float px = plotL + (i + 0.5f) * (plotR - plotL) / nCat;
                        float py = plotB - (float) ((clampV(v, vMin, vMax) - vMin) / valSpan * (plotB - plotT));
                        if (first) { poly.moveTo(px, py); first = false; }
                        else poly.lineTo(px, py);
                    }
                    if (type != PptDoc.Chart.LINE) {
                        /* markers */
                        Paint mk = new Paint(Paint.ANTI_ALIAS_FLAG);
                        mk.setColor(col);
                        float mrad = Math.max(2f, 3.2f * pxPerPt);
                        for (int i = 0; i < nCat && i < s.values.size(); i++) {
                            Double v = s.values.get(i);
                            if (v == null || Double.isNaN(v)) continue;
                            float px = plotL + (i + 0.5f) * (plotR - plotL) / nCat;
                            float py = plotB - (float) ((clampV(v, vMin, vMax) - vMin) / valSpan * (plotB - plotT));
                            c.drawCircle(px, py, mrad, mk);
                        }
                    }
                    if (type != PptDoc.Chart.SCATTER) c.drawPath(poly, ln);
                    if (ch.dataLabels && s.values.size() <= 10) {
                        for (int i = 0; i < nCat && i < s.values.size(); i++) {
                            Double v = s.values.get(i);
                            if (v == null || Double.isNaN(v)) continue;
                            float px = plotL + (i + 0.5f) * (plotR - plotL) / nCat;
                            float py = plotB - (float) ((clampV(v, vMin, vMax) - vMin) / valSpan * (plotB - plotT));
                            c.drawText(PptxReader.fmtNum(v), px, py - 4f * pxPerPt, lblP);
                        }
                    }
                }
            } else if (type == PptDoc.Chart.AREA) {
                for (int si = nSer - 1; si >= 0; si--) {
                    PptDoc.ChartSeries s = ch.series.get(si);
                    if (s.values.isEmpty()) continue;
                    int col = ch.seriesColor(si);
                    fillP.setColor(col);
                    Path area = new Path();
                    boolean first = true;
                    float lastX = 0;
                    for (int i = 0; i < nCat && i < s.values.size(); i++) {
                        Double v = s.values.get(i);
                        if (v == null || Double.isNaN(v)) continue;
                        float px = plotL + (i + 0.5f) * (plotR - plotL) / nCat;
                        float py = plotB - (float) ((clampV(v, vMin, vMax) - vMin) / valSpan * (plotB - plotT));
                        if (first) { area.moveTo(px, baseCoord); area.lineTo(px, py); first = false; }
                        else area.lineTo(px, py);
                        lastX = px;
                    }
                    if (!first) {
                        area.lineTo(lastX, baseCoord);
                        area.close();
                        c.drawPath(area, fillP);
                    }
                }
            } else {
                /* bar family */
                float slot = horiz ? (plotB - plotT) / nCat : (plotR - plotL) / nCat;
                float groupW = slot * 100f / (100f + Math.max(20, ch.gapWidthPct));
                float barW = stacked ? groupW : groupW / Math.max(1, nSer);
                float barGap = Math.max(0.5f * pxPerPt, barW * 0.06f);
                for (int i = 0; i < nCat; i++) {
                    float slotStart = horiz ? plotT + i * slot : plotL + i * slot;
                    float groupStart = slotStart + (slot - groupW) / 2f;
                    double[] cum = new double[nSer];
                    double total = 0;
                    if (percent) {
                        for (int si = 0; si < nSer; si++) {
                            PptDoc.ChartSeries s = ch.series.get(si);
                            total += i < s.values.size() && s.values.get(i) != null ? s.values.get(i) : 0;
                        }
                    }
                    for (int si = 0; si < nSer; si++) {
                        PptDoc.ChartSeries s = ch.series.get(si);
                        double v = i < s.values.size() && s.values.get(i) != null ? s.values.get(i) : 0;
                        if (percent && total != 0) v = v / total * 100.0;
                        double from = cum[si > 0 ? si - 1 : 0];
                        if (stacked) {
                            from = si == 0 ? 0 : cum[si - 1];
                            cum[si] = from + v;
                        }
                        double v0 = stacked ? from : 0;
                        double v1 = stacked ? from + v : v;
                        int col = ch.seriesColor(si);
                        fillP.setColor(col);
                        if (horiz) {
                            float yy = groupStart + (stacked ? 0 : si * barW) + barGap / 2;
                            float hh = Math.max(1f, barW - barGap);
                            float xx0 = plotL + (float) ((clampV(v0, vMin, vMax) - vMin) / valSpan * (plotR - plotL));
                            float xx1 = plotL + (float) ((clampV(v1, vMin, vMax) - vMin) / valSpan * (plotR - plotL));
                            c.drawRect(Math.min(xx0, xx1), yy, Math.max(xx0, xx1), yy + hh, fillP);
                            if (ch.dataLabels) {
                                lblP.setTextAlign(Paint.Align.LEFT);
                                c.drawText(PptxReader.fmtNum(v), Math.max(xx0, xx1) + 2f * pxPerPt,
                                        yy + hh / 2f + lblP.getTextSize() * 0.35f, lblP);
                            }
                        } else {
                            float xx = groupStart + (stacked ? 0 : si * barW) + barGap / 2;
                            float ww = Math.max(1f, barW - barGap);
                            float yy0 = plotB - (float) ((clampV(v1, vMin, vMax) - vMin) / valSpan * (plotB - plotT));
                            float yy1 = plotB - (float) ((clampV(v0, vMin, vMax) - vMin) / valSpan * (plotB - plotT));
                            if (v >= 0 && !stacked) yy1 = baseCoord;
                            c.drawRect(xx, Math.min(yy0, yy1), xx + ww, Math.max(yy0, yy1), fillP);
                            if (ch.dataLabels) {
                                lblP.setTextAlign(Paint.Align.CENTER);
                                c.drawText(PptxReader.fmtNum(v), xx + ww / 2f,
                                        Math.min(yy0, yy1) - 2f * pxPerPt, lblP);
                            }
                        }
                    }
                }
            }
        }

        static double clampV(double v, double vMin, double vMax) {
            if (Double.isNaN(v) || Double.isInfinite(v)) return vMin;
            return Math.max(vMin, Math.min(vMax, v));
        }

        static double niceStep(double raw) {
            if (raw <= 0 || Double.isNaN(raw) || Double.isInfinite(raw)) return 1;
            double mag = Math.pow(10, Math.floor(Math.log10(raw)));
            double norm = raw / mag;
            double nice = norm <= 1 ? 1 : norm <= 2 ? 2 : norm <= 2.5 ? 2.5 : norm <= 5 ? 5 : 10;
            return nice * mag;
        }

        static String tickFmt(double step) {
            if (step >= 1) return null;               /* integer */
            double a = Math.abs(step);
            if (a >= 0.1) return "%.1f";
            if (a >= 0.01) return "%.2f";
            return "%.3f";
        }

        static String tick(double v, String fmt) {
            if (fmt == null) {
                double r = Math.round(v);
                if (Math.abs(v - r) < 1e-9) return String.valueOf((long) r);
                return String.valueOf(Math.round(v * 100.0) / 100.0);
            }
            return String.format(java.util.Locale.US, fmt, v);
        }

        static java.util.List<java.util.List<Integer>> legendRows(List<String> texts, Paint p,
                                                                 float itemW, float availW) {
            java.util.List<java.util.List<Integer>> rows = new ArrayList<>();
            java.util.List<Integer> cur = new ArrayList<>();
            float w = 0f;
            for (int i = 0; i < texts.size(); i++) {
                float need = itemW + (cur.isEmpty() ? 0 : 10f);
                if (w + need > availW && !cur.isEmpty()) {
                    rows.add(cur);
                    cur = new ArrayList<>();
                    w = 0f;
                }
                cur.add(i);
                w += need;
            }
            if (!cur.isEmpty()) rows.add(cur);
            return rows;
        }

        static void drawLegendSide(Canvas c, List<String> texts, PptDoc.Chart ch,
                                   float l, float t, float r, float b,
                                   Paint p, float sw, float gap, boolean circular) {
            float lh = p.getTextSize() * 1.9f;
            float totalH = texts.size() * lh;
            float y = t + Math.max(0, (b - t - totalH) / 2f);
            Paint sq = new Paint(Paint.ANTI_ALIAS_FLAG);
            for (int i = 0; i < texts.size(); i++) {
                int col = circular ? pointColor(ch, i) : ch.seriesColor(i);
                sq.setColor(col);
                c.drawRect(l, y + p.getTextSize() * 0.15f, l + sw, y + p.getTextSize() * 1.05f, sq);
                p.setTextAlign(Paint.Align.LEFT);
                c.drawText(texts.get(i), l + sw + gap, y + p.getTextSize(), p);
                y += lh;
            }
        }

        static void drawLegendRows(Canvas c, java.util.List<java.util.List<Integer>> rows,
                                   List<String> texts, PptDoc.Chart ch,
                                   float l, float t, float r, float b,
                                   Paint p, float sw, float gap, boolean circular, float itemW) {
            float lh = p.getTextSize() * 1.9f;
            Paint sq = new Paint(Paint.ANTI_ALIAS_FLAG);
            for (int ri = 0; ri < rows.size(); ri++) {
                java.util.List<Integer> row = rows.get(ri);
                float rowW = row.size() * (itemW + 10f) - 10f;
                float x = l + Math.max(0, (r - l - rowW) / 2f);
                float y = t + ri * lh;
                for (int k = 0; k < row.size(); k++) {
                    int i = row.get(k);
                    int col = circular ? pointColor(ch, i) : ch.seriesColor(i);
                    sq.setColor(col);
                    c.drawRect(x, y + p.getTextSize() * 0.15f, x + sw, y + p.getTextSize() * 1.05f, sq);
                    p.setTextAlign(Paint.Align.LEFT);
                    c.drawText(texts.get(i), x + sw + gap, y + p.getTextSize(), p);
                    x += itemW + 10f;
                }
            }
        }

        static int pointColor(PptDoc.Chart ch, int i) {
            PptDoc.ChartSeries s = ch.series.isEmpty() ? null : ch.series.get(0);
            if (s != null && i < s.pointColors.size() && s.pointColors.get(i) != null
                    && s.pointColors.get(i) != 0) {
                return s.pointColors.get(i);
            }
            return PptDoc.Chart.PALETTE[Math.abs(i) % PptDoc.Chart.PALETTE.length];
        }

        static void drawPiePlot(Canvas c, PptDoc.Chart ch, float l, float t, float r, float b, float pxPerPt) {
            PptDoc.ChartSeries s = ch.series.isEmpty() ? new PptDoc.ChartSeries() : ch.series.get(0);
            float cx = (l + r) / 2f, cy = (t + b) / 2f;
            float rad = Math.min(r - l, b - t) / 2f - 3f * pxPerPt;
            if (rad <= 4f) return;
            double total = 0;
            for (Double v : s.values) {
                if (v != null && !Double.isNaN(v) && !Double.isInfinite(v)) total += Math.abs(v);
            }
            Paint fp = new Paint(Paint.ANTI_ALIAS_FLAG);
            Paint outP = new Paint(Paint.ANTI_ALIAS_FLAG);
            outP.setStyle(Paint.Style.STROKE);
            outP.setStrokeWidth(Math.max(1f, 0.75f * pxPerPt));
            outP.setColor(0xFFFFFFFF);
            RectF box = new RectF(cx - rad, cy - rad, cx + rad, cy + rad);
            float start = -90f;
            int n = Math.max(s.values.size(), ch.categories.size());
            if (total <= 0) {
                /* empty pie — outline only */
                fp.setColor(0xFFD9D9D9);
                c.drawArc(box, 0, 360, true, fp);
                return;
            }
            int sc = c.saveLayer(box, null);
            for (int i = 0; i < s.values.size(); i++) {
                Double v = s.values.get(i);
                if (v == null || Double.isNaN(v) || Double.isInfinite(v) || v == 0) continue;
                float sweep = (float) (Math.abs(v) / total * 360.0);
                fp.setColor(pointColor(ch, i));
                c.drawArc(box, start, sweep, true, fp);
                c.drawArc(box, start, sweep, true, outP);
                if (ch.dataLabels) {
                    float mid = (float) Math.toRadians(start + sweep / 2f);
                    float lr = rad * (ch.chartType == PptDoc.Chart.DOUGHNUT ? 0.72f : 0.62f);
                    float tx = cx + (float) Math.cos(mid) * lr;
                    float ty = cy + (float) Math.sin(mid) * lr;
                    Paint lp = new Paint(Paint.ANTI_ALIAS_FLAG);
                    lp.setTextSize(Math.max(7f, 9f * pxPerPt));
                    lp.setColor(0xFFFFFFFF);
                    lp.setTextAlign(Paint.Align.CENTER);
                    lp.setTypeface(Typeface.DEFAULT_BOLD);
                    int pct = (int) Math.round(Math.abs(v) / total * 100);
                    c.drawText(pct + "٪", tx, ty + lp.getTextSize() * 0.35f, lp);
                }
                start += sweep;
            }
            if (ch.chartType == PptDoc.Chart.DOUGHNUT) {
                float hole = rad * Math.max(5, Math.min(90, ch.holePct)) / 100f;
                Paint clear = new Paint(Paint.ANTI_ALIAS_FLAG);
                clear.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
                c.drawCircle(cx, cy, hole, clear);
            }
            c.restoreToCount(sc);
        }
    }
}
