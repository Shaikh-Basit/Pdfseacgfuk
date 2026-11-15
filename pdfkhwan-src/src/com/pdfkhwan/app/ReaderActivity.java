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

/* loaded from: classes.dex */
public class ReaderActivity extends Activity implements SearchBridge.Host {
    /* v1.51: خوشهٔ جدا‌شده — ReaderAnnDraw */
    final ReaderAnnDraw annDraw = new ReaderAnnDraw(this);
    final ReaderAnnDialogs annDialogs = new ReaderAnnDialogs(this);
    final ReaderPen pen = new ReaderPen(this);
    private final ReaderTts tts = new ReaderTts(this);
    final ReaderShare share = new ReaderShare(this);
    private final ReaderPageOps pageOps = new ReaderPageOps(this);
    final ReaderForms forms = new ReaderForms(this);
    private final ReaderDisplay display = new ReaderDisplay(this);
    final ReaderLinks links = new ReaderLinks(this);
    static boolean isPptFamily(String lower) {
        return lower.endsWith(".pptx") || lower.endsWith(".ppt")
                || lower.endsWith(".ppsx") || lower.endsWith(".pps")
                || lower.endsWith(".ppsm") || lower.endsWith(".pptm")
                || lower.endsWith(".potx") || lower.endsWith(".potm");
    }

    private BookmarksAdapter bookmarksAdapter;
    private View bookmarksView;
    private TextView docTitle;
    boolean keepScreenOn;
    private float lastYOff;
    private float lastZoom;
    View loading;
    private float maxPageW1;
    /* v1.27: horizontal-axis page geometry (see ensureGeom) */
    private float maxPageH1;
    private float[] pageW1;
    private float[] pageOffX1;
    private float[] pageH1;
    float[] pageOff1;
    PDFView pdfView;
    private ResultsAdapter resultsAdapter;
    private ListView resultsList;
    private EditText searchInput;
    private View searchPanel;
    private View searchProgressRow;
    private TextView searchProgressText;
    private ProgressBar searchProgressView;
    private TextView searchStatus;
    WebView searchWeb;
    private View selHandleEnd;
    private View selHandleStart;
    SelOverlay selOverlay;
    SelPage selPage;
    private LinearLayout selPopup;
    RecentStore store;
    /* v7: password-protected PDF support */
    String docPassword = null;
    /* v1.40: پشتیبانی کامل از PDFهای رمزدار/محافظت‌شده — نسخهٔ رمزگشایی‌شده
       (موقتی، در حافظهٔ برنامه) که پس از بازشدن قفل، جای فایل اصلی را برای
       هر دو موتور (نمایش pdfium و متن pdf.js) می‌گیرد؛ یعنی نمایش، جستجو،
       کپی متن، تبدیل به Word و چاپ همه روی نسخهٔ آزاد کار می‌کنند */
    File decryptedFile = null;
    private boolean unlockBusy = false;
    /* v7: search-hit highlighting (word indexes into selPage.words) */
    private String hlQuery = null;
    private int hlPageIdx = -1;
    private final Set<Integer> hlWords = new HashSet<>();
    /* v7: read-aloud (TTS) */
    static final class TtsSent {
        int w0, w1;          /* بازهٔ کلمات جمله */
        String text;
        RectF box;           /* مربع احاطه (مختصات کسری صفحه) */
        String lang;
    }

    Paint ttsSentPaint = null;

    /* ================== v1.47: بخش ۱۸ — صفحه‌آرایی ================== */
    private ThumbAdapter thumbAdapter;
    private PdfThumbs thumbs;
    private GridView thumbsGrid;
    private View thumbsPanel;
    private TocAdapter tocAdapter;
    private ListView tocList;
    private TocLoader tocLoader;
    private View tocPanel;
    private TextView tocStatus;
    View topBar;
    Uri uri;
    String docName = "document.pdf";
    long docSize = 0;
    String fileKey = "";
    /* ============ v1.46: فرم‌های AcroForm + لینک‌های تعاملی ============ */
    FormFiller.Interact pdfInter = null;
    int pageCount = 0;
    boolean loaded = false;
    private int lastPage1 = 1;
    boolean barsVisible = true;
    boolean night = false;
    boolean webReady = false;
    private boolean searching = false;
    /* v1.27: results-refresh throttle state (see onSearchHit) */
    private boolean searchResultsDirty = false;
    final LruCache<Integer, SelPage> selPages = new LruCache<>(8);
    private boolean selEngineOn = false;
    private long selPrepToastAt = 0;
    /* v1.27: a copy-for-words request is already in flight (see requestSelWords) */
    private volatile boolean selPrepPending = false;
    /* v1.28: a request that arrived while another was in flight — replayed
       when the in-flight one finishes (used to be silently DROPPED, losing
       long-press/read-aloud/search-highlight taps during the first-copy
       window and leaving ttsPendingPage armed for a surprise speak) */
    private int selPrepQueuedPage = -1;
    private boolean selPrepQueuedQuiet = false;
    private float[] selPending = null;
    int selPageIdx = -1;
    int selStart = -1;
    int selEnd = -1;
    /* v1.41: character offsets inside the boundary words — letter-level
       selection (like Chrome/Word handles): the start handle can stop
       mid-word. -1 = whole word. */
    private int selStartChar = -1;
    private int selEndChar = -1;
    private boolean selDragging = false;
    int geomBuiltW = -1;
    private int geomBuiltCount = -1;
    /* v1.27: rebuild the geometry when the page-turn axis changes */
    private boolean geomBuiltHoriz = false;
    private float lastXOff = Float.NaN;
    private int lastVW = -1;
    private int lastVH = -1;
    private boolean geomWatchRunning = false;
    private final List<int[]> hits = new ArrayList();
    private final List<String> snippets = new ArrayList();
    private final List<TocLoader.Item> tocItems = new ArrayList();
    final Handler ui = new Handler();
    /* v1.16: single background thread for file staging (search copy /
       share copy) — the old code copied the WHOLE PDF on the UI thread,
       freezing the app (ANR risk) right when the user tapped search/share. */
    final java.util.concurrent.ExecutorService io = java.util.concurrent.Executors.newSingleThreadExecutor();
    private long lastPageErrorToast = 0;
    private final Runnable geomWatch = new Runnable() { // from class: com.pdfkhwan.app.ReaderActivity.1
        @Override // java.lang.Runnable
        public void run() {
            try {
                if (ReaderActivity.this.pdfView == null) {
                    ReaderActivity.this.geomWatchRunning = false;
                    return;
                }
                /* v1.32: با وجود حاشیه‌نویسی، پایش هندسه ادامه می‌یابد تا
                   هایلایت‌ها/خطوط با اسکرول و زوم هم‌تراز بمانند */
                if (ReaderActivity.this.selPage == null
                        && (ReaderActivity.this.anns == null || ReaderActivity.this.anns.items.isEmpty())
                        && !forms.formMode) {
                    ReaderActivity.this.geomWatchRunning = false;
                    return;
                }
                float currentXOffset = ReaderActivity.this.pdfView.getCurrentXOffset();
                float currentYOffset = ReaderActivity.this.pdfView.getCurrentYOffset();
                float zoom = ReaderActivity.this.pdfView.getZoom();
                int width = ReaderActivity.this.pdfView.getWidth();
                int height = ReaderActivity.this.pdfView.getHeight();
                if (currentXOffset != ReaderActivity.this.lastXOff || currentYOffset != ReaderActivity.this.lastYOff || zoom != ReaderActivity.this.lastZoom || width != ReaderActivity.this.lastVW || height != ReaderActivity.this.lastVH) {
                    ReaderActivity.this.lastXOff = currentXOffset;
                    ReaderActivity.this.lastYOff = currentYOffset;
                    ReaderActivity.this.lastZoom = zoom;
                    ReaderActivity.this.lastVW = width;
                    ReaderActivity.this.lastVH = height;
                    ReaderActivity.this.ensureGeom();
                    ReaderActivity.this.selOverlay.invalidate();
                    /* v1.46: کادرهای فرم هم با اسکرول/زوم تازه رسم شوند */
                    if (forms.formOverlay != null
                            && forms.formOverlay.getVisibility() == 0) {
                        forms.formOverlay.invalidate();
                    }
                    /* v1.32: خطوط دستی هم با اسکرول/زوم تازه رسم شوند */
                    if (ReaderActivity.this.drawOverlay != null
                            && ReaderActivity.this.drawOverlay.getVisibility() == 0) {
                        ReaderActivity.this.drawOverlay.invalidate();
                    }
                    ReaderActivity.this.positionHandles();
                    if (!ReaderActivity.this.selDragging) {
                        ReaderActivity.this.positionPopup();
                    }
                }
            } catch (Throwable unused) {
                /* v1.16: a poll tick could fire after onDestroy recycled the
                   view — it used to crash uncaught; now it simply stops. */
                ReaderActivity.this.geomWatchRunning = false;
                return;
            }
            ReaderActivity.this.ui.postDelayed(this, 16L);
        }
    };
    private boolean tocLoading = false;

    /* ================================================================
       v1.32: سیستم حاشیه‌نویسی کامل
       — هایلایت/زیرخط/خط‌خورده/موج‌دار با رنگ دلخواه
       — یادداشت متنی متصل به محدودهٔ متن + یادداشت آزاد روی صفحه
       — رسم آزاد (قلم/ماژیک/فلش/پاک‌کن)
       — فهرست کامل هایلایت‌ها و یادداشت‌ها با جستجو و حذف گروهی
       — ذخیرهٔ خودکار و اتمیک؛ فایل اصلی PDF دست‌نخورده می‌ماند
       ================================================================ */
    Annotations anns = new Annotations();
    /* v1.40: پالت استاندارد ماژیک — چهار رنگ اصلی «زرد، سرخ، آبی، سیاه»
       همیشه اول‌اند (هم در نوار انتخاب، هم در دیالوگ رنگ)؛ بعد از آن‌ها
       نارنجی/سبز/بنفش/صورتی. رنگ‌ها مثل ماژیک واقعی اشباع‌اند: با بلند
       مولتی‌پلای، متن زیرشان کاملاً خوانا می‌ماند ولی رنگِ روی کاغذ
       همان رنگِ خود ماژیک است — نه کم‌رنگ و شُفاف */
    static final int[] STD_MARKER_COLORS =
            {0xFFFFD500 /* زرد */, 0xFFFF4530 /* سرخ */, 0xFF3B88FF /* آبی */, 0xFF4D4D4D /* سیاه */};
    static final class AnnChange {
        final long id;
        final Annotations.Ann before;  /* null = قبل از عمل وجود نداشت */
        final Annotations.Ann after;   /* null = بعد از عمل وجود ندارد */
        AnnChange(long id, Annotations.Ann b, Annotations.Ann a) {
            this.id = id; this.before = b; this.after = a;
        }
    }
    static final class HistOp {
        final ArrayList<AnnChange> changes = new ArrayList<>();
    }
    static final int HIST_MAX = 120;
    static final float[] TOOL_WMIN = {0, 1f, 10f, 6f, 1f};
    static final float[] TOOL_WMAX = {0, 16f, 60f, 60f, 10f};
    /* v1.37: دکمهٔ مستقیم حالت رسم در نوار بالا (کنار جستجو) */
    DrawOverlay drawOverlay;
    /* v1.33: نوار رسم مدرن */
    private LinearLayout selToolRow;
    private View[] selColorDots;
    /* در انتظار لمس برای گذاشتن یادداشت آزاد */
    private class BookmarksAdapter extends BaseAdapter {
        private final List<int[]> data = new ArrayList();
        private final ListView list;

        BookmarksAdapter(ListView listView) {
            this.list = listView;
            listView.setAdapter((ListAdapter) this);
        }

        @Override // android.widget.Adapter
        public int getCount() {
            return this.data.size();
        }

        @Override // android.widget.Adapter
        public Object getItem(int i) {
            return Integer.valueOf(i);
        }

        @Override // android.widget.Adapter
        public long getItemId(int i) {
            return i;
        }

        @Override // android.widget.Adapter
        public View getView(final int i, View view, ViewGroup viewGroup) {
            TextView textView = (TextView) view;
            if (textView == null) {
                textView = new TextView(ReaderActivity.this);
                int dp = ReaderActivity.this.dp(10);
                textView.setPadding(dp, dp, dp, dp);
                textView.setTextSize(13.0f);
                textView.setBackgroundResource(R.drawable.bg_bar);
                textView.setTextColor(-1511688);
            }
            textView.setText("صفحه " + Fa.d(this.data.get(i)[0]));
            textView.setOnClickListener(new View.OnClickListener() { // from class: com.pdfkhwan.app.ReaderActivity.BookmarksAdapter.1
                @Override // android.view.View.OnClickListener
                public void onClick(View view2) {
                    ReaderActivity.this.jumpTo(((int[]) BookmarksAdapter.this.data.get(i))[0]);
                    ReaderActivity.this.closePanels();
                }
            });
            textView.setOnLongClickListener(new View.OnLongClickListener() { // from class: com.pdfkhwan.app.ReaderActivity.BookmarksAdapter.2
                @Override // android.view.View.OnLongClickListener
                public boolean onLongClick(View view2) {
                    ReaderActivity.this.store.toggleBookmark(ReaderActivity.this.fileKey, ((int[]) BookmarksAdapter.this.data.get(i))[0]);
                    BookmarksAdapter.this.reload();
                    ReaderActivity.this.toast("نشانک حذف شد");
                    return true;
                }
            });
            return textView;
        }

        void reload() {
            this.data.clear();
            JSONArray bookmarks = ReaderActivity.this.store.getBookmarks(ReaderActivity.this.fileKey);
            for (int i = 0; i < bookmarks.length(); i++) {
                this.data.add(new int[]{bookmarks.optInt(i)});
            }
            notifyDataSetChanged();
        }
    }

    private class JumpClick implements View.OnClickListener {
        private final int page;

        JumpClick(int i) {
            this.page = i;
        }

        @Override // android.view.View.OnClickListener
        public void onClick(View view) {
            /* v7: remember the query so the hit words light up on the
               target page (search-hit highlighting) */
            ReaderActivity.this.hlQuery = ReaderActivity.this.lastQuery;
            ReaderActivity.this.hlPageIdx = this.page - 1;
            ReaderActivity.this.jumpTo(this.page);
            ReaderActivity.this.closePanels();
            /* v1.27 FIX: the highlight needs the page's word list — fetch it
               if it is not cached yet, otherwise NOTHING ever lit up (the
               words only loaded when a selection happened to be made). */
            if (ReaderActivity.this.hlQuery != null) {
                SelPage cached = ReaderActivity.this.selPages.get(Integer.valueOf(this.page - 1));
                if (cached != null) {
                    ReaderActivity.this.computeHighlights(cached, ReaderActivity.this.hlQuery);
                } else {
                    ReaderActivity.this.requestSelWords(this.page - 1, true);
                }
            }
        }
    }

    /* v7: last search query — kept for hit highlighting */
    private String lastQuery = null;

    /* v7: Arabic-script-aware normalizer (mirrors the JS norm() used by the
       search engine) so highlighted words match the same normalization the
       search results used: yeh/kaf/alef variants, ta-marbuta, Persian and
       Arabic-Indic digits, ZWNJ, harakat. */
    static String normFa(String s) {
        if (s == null) {
            return "";
        }
        /* v1.27 FIX: older Persian PDFs store text in Arabic PRESENTATION
           FORMS (U+FB50–FDFF / U+FE70–FEFF). The JS search engine folds them
           with NFKC (so search finds the words) but this Java side didn't —
           the amber highlight never matched those hits, and copied text
           pasted as disconnected glyphs. Mirrors the JS: NFKC-fold first. */
        boolean needsNfkc = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if ((ch >= 0xFB50 && ch <= 0xFDFF) || (ch >= 0xFE70 && ch <= 0xFEFF)) {
                needsNfkc = true;
                break;
            }
        }
        if (needsNfkc) {
            try {
                s = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKC);
            } catch (Throwable ignored) {
            }
        }
        /* v1.29 FIX: mirror the JS engine's compact-space handling — NBSP
           (U+00A0) and the U+2000–200A space block fold to a plain space and
           runs of spaces collapse, so words containing odd spaces highlight
           in the amber marks exactly like the engine matched them. */
        StringBuilder sp = new StringBuilder(s.length());
        boolean lastSpace = false;
        for (int i = 0; i < s.length(); i++) {
            char c0 = s.charAt(i);
            boolean isSpace = c0 == ' ' || c0 == 0x00A0
                    || (c0 >= 0x2000 && c0 <= 0x200A) || c0 == 0x3000;
            if (isSpace) {
                if (!lastSpace) sp.append(' ');
                lastSpace = true;
            } else {
                sp.append(c0);
                lastSpace = false;
            }
        }
        s = sp.toString();
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == 0x200B || c == 0x200C || c == 0x200D || c == 0x200E || c == 0x200F || c == 0xFEFF || c == 0x00AD) {
                continue;
            }
            if ((c >= 0x064B && c <= 0x065F) || c == 0x0670 || (c >= 0x06D6 && c <= 0x06ED) || c == 0x0640) {
                continue;
            }
            if (c == 0x064A || c == 0x0649) {
                sb.append('\u06CC');
            } else if (c == 0x0643) {
                sb.append('\u06A9');
            } else if (c == 0x0622 || c == 0x0623 || c == 0x0625 || c == 0x0671) {
                sb.append('\u0627');
            } else if (c == 0x0629) {
                sb.append('\u0647');
            } else if (c >= 0x0660 && c <= 0x0669) {
                sb.append((char) ('0' + (c - 0x0660)));
            } else if (c >= 0x06F0 && c <= 0x06F9) {
                sb.append((char) ('0' + (c - 0x06F0)));
            } else {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString().trim();
    }

    private class ResultsAdapter extends BaseAdapter {
        private ResultsAdapter() {
        }

        /* synthetic */ ResultsAdapter(ReaderActivity readerActivity, ResultsAdapter resultsAdapter) {
            this();
        }

        @Override // android.widget.Adapter
        public int getCount() {
            return ReaderActivity.this.hits.size();
        }

        @Override // android.widget.Adapter
        public Object getItem(int i) {
            return Integer.valueOf(i);
        }

        @Override // android.widget.Adapter
        public long getItemId(int i) {
            return i;
        }

        @Override // android.widget.Adapter
        public View getView(int i, View view, ViewGroup viewGroup) {
            TextView textView = (TextView) view;
            if (textView == null) {
                textView = new TextView(ReaderActivity.this);
                int dp = ReaderActivity.this.dp(10);
                textView.setPadding(dp, dp, dp, dp);
                textView.setTextColor(-1511688);
                textView.setTextSize(12.5f);
                textView.setBackgroundResource(R.drawable.bg_bar);
                LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(-1, -2);
                layoutParams.bottomMargin = ReaderActivity.this.dp(6);
                textView.setLayoutParams(layoutParams);
            }
            int i2 = ((int[]) ReaderActivity.this.hits.get(i))[0];
            textView.setText("صفحه " + Fa.d(i2) + "\n" + ((String) ReaderActivity.this.snippets.get(i)));
            textView.setOnClickListener(ReaderActivity.this.new JumpClick(i2));
            return textView;
        }
    }

    class SelOverlay extends View {
        private final Paint fill;
        private final Paint hl;   /* v7: search-hit highlight (amber) */

        SelOverlay(Context context) {
            super(context);
            Paint paint = new Paint(1);
            this.fill = paint;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(1378735295);
            Paint paint2 = new Paint(1);
            this.hl = paint2;
            paint2.setStyle(Paint.Style.FILL);
            paint2.setColor(1140850688); /* 0x44FFB300 — amber */
        }

        @Override // android.view.View
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            try {
                if (ReaderActivity.this.pageOff1 != null) {
                    /* v1.47: هایلایت جملهٔ در حال خواندن (زیر انتخاب، بالای جستجو) —
                       سبز-فیروزه‌ای با قاب */
                    if (tts.ttsReading && tts.ttsSents != null
                            && tts.ttsIdx >= 0
                            && tts.ttsIdx < tts.ttsSents.size()
                            && tts.ttsPageIdx >= 0) {
                        SelPage tp = ReaderActivity.this.selPages.get(Integer.valueOf(tts.ttsPageIdx));
                        if (tp != null && tp.vw > 0f && tp.vh > 0f) {
                            RectF pr = ReaderActivity.this.pageRect(tts.ttsPageIdx);
                            if (pr.width() > 0.0f && pr.height() > 0.0f) {
                                TtsSent s = tts.ttsSents.get(tts.ttsIdx);
                                if (s != null && s.box != null) {
                                    float kw = pr.width() / tp.vw;
                                    float kh = pr.height() / tp.vh;
                                    float L = pr.left + s.box.left * kw;
                                    float T = pr.top + s.box.top * kh;
                                    float R = pr.left + s.box.right * kw;
                                    float B = pr.top + s.box.bottom * kh;
                                    if (R >= 0f && L <= getWidth() && B >= 0f && T <= getHeight()) {
                                        if (ReaderActivity.this.ttsSentPaint == null) {
                                            Paint p = new Paint(1);
                                            p.setStyle(Paint.Style.FILL);
                                            p.setColor(0x332DD4BF);
                                            ReaderActivity.this.ttsSentPaint = p;
                                        }
                                        canvas.drawRoundRect(L - ReaderActivity.this.dp(2), T - ReaderActivity.this.dp(1),
                                                R + ReaderActivity.this.dp(2), B + ReaderActivity.this.dp(1),
                                                ReaderActivity.this.dp(5), ReaderActivity.this.dp(5),
                                                ReaderActivity.this.ttsSentPaint);
                                    }
                                }
                            }
                        }
                    }
                    /* v1.38: حاشیه‌نویسی‌های ذخیره‌شده دیگر اینجا رسم نمی‌شوند
                       — داخل پاس رسم خود PDFView با بلند مولتی‌پلای زیر
                       متن می‌نشینند (لایهٔ جدا مولتی‌پلای را نمی‌بینید چون
                       بومش شفاف است). این لایه فقط رابط انتخاب است:
                       هایلایت جستجوی فعال + محدودهٔ انتخاب */
                    /* v1.27 FIX: the amber search-hit highlight was gated on
                       an ACTIVE TEXT SELECTION being present on that very
                       page — three independent conditions that almost never
                       coincided, so tapping a search result highlighted
                       nothing. Highlights now draw from the hit page's cached
                       word list on their own. */
                    if (ReaderActivity.this.hlPageIdx >= 0
                            && !ReaderActivity.this.hlWords.isEmpty()
                            && ReaderActivity.this.hlQuery != null
                            && ReaderActivity.this.hlPageIdx != ReaderActivity.this.selPageIdx) {
                        SelPage hp = ReaderActivity.this.selPages.get(Integer.valueOf(ReaderActivity.this.hlPageIdx));
                        if (hp != null && hp.vw > 0f && hp.vh > 0f) {
                            RectF pr = ReaderActivity.this.pageRect(ReaderActivity.this.hlPageIdx);
                            if (pr.width() > 0.0f && pr.height() > 0.0f) {
                                float kw = pr.width() / hp.vw;
                                float kh = pr.height() / hp.vh;
                                for (int i = 0; i < hp.words.size(); i++) {
                                    if (!ReaderActivity.this.hlWords.contains(Integer.valueOf(i))) {
                                        continue;
                                    }
                                    SelWord w = hp.words.get(i);
                                    float f = pr.left + (w.x0 * kw);
                                    float f2 = pr.top + (w.y0 * kh);
                                    float f3 = pr.left + (w.x1 * kw);
                                    float f4 = pr.top + (w.y1 * kh);
                                    if (f3 >= 0.0f && f <= getWidth() && f4 >= 0.0f && f2 <= getHeight()) {
                                        canvas.drawRoundRect(f - ReaderActivity.this.dp(1), f2, f3 + ReaderActivity.this.dp(1), f4,
                                                ReaderActivity.this.dp(4), ReaderActivity.this.dp(4), this.hl);
                                    }
                                }
                            }
                        }
                    }
                    if (ReaderActivity.this.selPage != null && ReaderActivity.this.selPageIdx >= 0) {
                        RectF pageRect = ReaderActivity.this.pageRect(ReaderActivity.this.selPageIdx);
                        if (pageRect.width() > 0.0f && pageRect.height() > 0.0f) {
                            float width = pageRect.width() / ReaderActivity.this.selPage.vw;
                            float height = pageRect.height() / ReaderActivity.this.selPage.vh;
                            /* v7: search hits first (underneath), amber */
                            if (ReaderActivity.this.hlPageIdx == ReaderActivity.this.selPageIdx && !ReaderActivity.this.hlWords.isEmpty()) {
                                for (int i = 0; i < ReaderActivity.this.selPage.words.size(); i++) {
                                    if (!ReaderActivity.this.hlWords.contains(Integer.valueOf(i))) {
                                        continue;
                                    }
                                    SelWord selWord = ReaderActivity.this.selPage.words.get(i);
                                    float f = pageRect.left + (selWord.x0 * width);
                                    float f2 = pageRect.top + (selWord.y0 * height);
                                    float f3 = pageRect.left + (selWord.x1 * width);
                                    float f4 = pageRect.top + (selWord.y1 * height);
                                    if (f3 >= 0.0f && f <= getWidth() && f4 >= 0.0f && f2 <= getHeight()) {
                                        canvas.drawRoundRect(f - ReaderActivity.this.dp(1), f2, f3 + ReaderActivity.this.dp(1), f4, ReaderActivity.this.dp(4), ReaderActivity.this.dp(4), this.hl);
                                    }
                                }
                            }
                            if (ReaderActivity.this.selStart >= 0) {
                                for (int i = ReaderActivity.this.selStart; i <= ReaderActivity.this.selEnd && i < ReaderActivity.this.selPage.words.size(); i++) {
                                    SelWord selWord = ReaderActivity.this.selPage.words.get(i);
                                    float f = pageRect.left + (selWord.x0 * width);
                                    float f2 = pageRect.top + (selWord.y0 * height);
                                    float f3 = pageRect.left + (selWord.x1 * width);
                                    float f4 = pageRect.top + (selWord.y1 * height);
                                    /* v1.41: letter-level — the boundary words light up
                                       only their selected char range */
                                    if (selWord.cs != null && selWord.cs.length > 0 && (i == ReaderActivity.this.selStart || i == ReaderActivity.this.selEnd)) {
                                        int maxIdx = selWord.cs.length - 1;
                                        int fromC = (i == ReaderActivity.this.selStart) ? Math.max(0, Math.min(maxIdx, ReaderActivity.this.selStartChar)) : 0;
                                        int toC = (i == ReaderActivity.this.selEnd) ? Math.max(0, Math.min(maxIdx, ReaderActivity.this.selEndChar)) : maxIdx;
                                        if (fromC > toC) { int tmpC = fromC; fromC = toC; toC = tmpC; }
                                        float bx0 = Float.MAX_VALUE, bx1 = -Float.MAX_VALUE;
                                        for (int k = fromC; k <= toC; k++) {
                                            float cLo = Math.min(selWord.cx0[k], selWord.cx1[k]);
                                            float cHi = Math.max(selWord.cx0[k], selWord.cx1[k]);
                                            if (cLo < bx0) bx0 = cLo;
                                            if (cHi > bx1) bx1 = cHi;
                                        }
                                        if (bx0 <= bx1) {
                                            f = pageRect.left + (bx0 * width);
                                            f3 = pageRect.left + (bx1 * width);
                                        }
                                    }
                                    if (f3 >= 0.0f && f <= getWidth() && f4 >= 0.0f && f2 <= getHeight()) {
                                        canvas.drawRoundRect(f - ReaderActivity.this.dp(1), f2, f3 + ReaderActivity.this.dp(1), f4, ReaderActivity.this.dp(4), ReaderActivity.this.dp(4), this.fill);
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (Throwable unused) {
            }
        }
    }

    static class SelPage {
        final float vh;
        final float vw;
        final List<SelWord> words;

        SelPage(float f, float f2, List<SelWord> list) {
            this.vw = f;
            this.vh = f2;
            this.words = list;
        }
    }

    static class SelWord {
        final int gap;       // v1.14: 0 = space before, 1 = glued to previous word
        final int br;        // v1.14: 1 = line break before this word, 0 = same line
        final int line;
        final boolean rtl;
        final String t;
        final float x0;
        final float x1;
        final float y0;
        final float y1;
        /* v1.41: zg=1 → the separator before this word was a TIGHT ZWNJ
           (نیم‌فاصله) — copy joins with ZWNJ instead of a space */
        final int zg;
        /* v1.41: per-CHARACTER boxes (reading order) — enables letter-level
           selection: handles can stop inside a word */
        final char[] cs;
        final float[] cx0;
        final float[] cx1;

        SelWord(String str, float f, float f2, float f3, float f4, int i, boolean z, int gap, int br) {
            this(str, f, f2, f3, f4, i, z, gap, br, 0, null, null, null);
        }

        SelWord(String str, float f, float f2, float f3, float f4, int i, boolean z, int gap, int br,
                int zg, char[] cs, float[] cx0, float[] cx1) {
            this.t = str;
            this.x0 = f;
            this.y0 = f2;
            this.x1 = f3;
            this.y1 = f4;
            this.line = i;
            this.rtl = z;
            this.gap = gap;
            this.br = br;
            this.zg = zg;
            this.cs = cs;
            this.cx0 = cx0;
            this.cx1 = cx1;
        }

        /* reading-order char index whose box contains vx (view coords);
           fallback: nearest edge → char whose boundary is closest.
           v1.52: RTL edge semantics — the reading-FIRST char of an RTL word
           sits at the word's RIGHT edge and the reading-LAST at the LEFT.
           The old code mapped left-of-word → char 0 unconditionally, so
           dragging an RTL handle off the word's left side selected the
           FIRST letter instead of the last — highlight and copied text
           disagreed. Direction is now decided by this.rtl. */
        int charAt(float vx) {
            int n = cs == null ? 0 : cs.length;
            if (n == 0) return 0;
            for (int k = 0; k < n; k++) {
                float lo = Math.min(cx0[k], cx1[k]), hi = Math.max(cx0[k], cx1[k]);
                if (vx >= lo && vx <= hi) return k;
            }
            /* outside: snap to the nearest boundary */
            int best = 0;
            float bd = Float.MAX_VALUE;
            for (int k = 0; k < n; k++) {
                float d = Math.min(Math.abs(vx - cx0[k]), Math.abs(vx - cx1[k]));
                if (d < bd) { bd = d; best = k; }
            }
            float firstLo = Math.min(cx0[0], cx1[0]);
            float firstHi = Math.max(cx0[0], cx1[0]);
            float lastLo = Math.min(cx0[n - 1], cx1[n - 1]);
            float lastHi = Math.max(cx0[n - 1], cx1[n - 1]);
            if (vx < Math.min(firstLo, lastLo)) {
                /* left of the whole word: RTL → reading-LAST char, LTR → first */
                return rtl ? n - 1 : 0;
            }
            if (vx > Math.max(firstHi, lastHi)) {
                /* right of the whole word: RTL → reading-FIRST char, LTR → last */
                return rtl ? 0 : n - 1;
            }
            return best;
        }
    }

    private class ThumbAdapter extends BaseAdapter {
        private int count;
        private final SparseArray<Bitmap> map;

        private ThumbAdapter() {
            this.count = 0;
            this.map = new SparseArray<>();
        }

        /* synthetic */ ThumbAdapter(ReaderActivity readerActivity, ThumbAdapter thumbAdapter) {
            this();
        }

        @Override // android.widget.Adapter
        public int getCount() {
            return this.count;
        }

        @Override // android.widget.Adapter
        public Object getItem(int i) {
            return Integer.valueOf(i);
        }

        @Override // android.widget.Adapter
        public long getItemId(int i) {
            return i;
        }

        @Override // android.widget.Adapter
        public View getView(int i, View view, ViewGroup viewGroup) {
            ImageView imageView = (ImageView) view;
            if (imageView == null) {
                imageView = new ImageView(ReaderActivity.this);
                imageView.setAdjustViewBounds(true);
                imageView.setBackgroundResource(R.drawable.bg_bar);
                int dp = ReaderActivity.this.dp(4);
                imageView.setPadding(dp, dp, dp, dp);
                imageView.setLayoutParams(new ViewGroup.LayoutParams(-1, -2));
            }
            final int i2 = i + 1;
            Bitmap bitmap = this.map.get(i2);
            if (bitmap == null || bitmap.isRecycled()) {
                imageView.setImageDrawable(null);
                imageView.post(new Runnable() { // from class: com.pdfkhwan.app.ReaderActivity.ThumbAdapter.2
                    @Override // java.lang.Runnable
                    public void run() {
                        if (ReaderActivity.this.thumbs != null) {
                            ReaderActivity.this.thumbs.request(i2, new PdfThumbs.Callback() { // from class: com.pdfkhwan.app.ReaderActivity.ThumbAdapter.2.1
                                @Override // com.pdfkhwan.app.PdfThumbs.Callback
                                public void onThumb(int i3, Bitmap bitmap2) {
                                    ThumbAdapter.this.onThumbReady(i3, bitmap2);
                                }
                            });
                        }
                    }
                });
            } else {
                imageView.setImageBitmap(bitmap);
            }
            imageView.setOnClickListener(new View.OnClickListener() { // from class: com.pdfkhwan.app.ReaderActivity.ThumbAdapter.3
                @Override // android.view.View.OnClickListener
                public void onClick(View view2) {
                    ReaderActivity.this.jumpTo(i2);
                    ReaderActivity.this.closePanels();
                }
            });
            return imageView;
        }

        void onThumbReady(int i, Bitmap bitmap) {
            /* v1.27 FIX: the old size cap wiped ALL 160 cached thumbnails at
               once (map.clear()) — scrolling a long document made the whole
               grid flash blank and re-request everything.
               v1.28 FIX: evicting the LOWEST indexes broke backward scrolling
               (the delivered low pages were evicted the moment they arrived →
               blank flashing cells + a self-sustaining request loop). Evict
               the entries FARTHEST from the just-delivered page instead, so
               the window around the visible range always survives. */
            if (this.map.size() > 160) {
                int size = this.map.size();
                int toEvict = size - 128;
                int[] keys = new int[size];
                for (int k = 0; k < size; k++) keys[k] = this.map.keyAt(k);
                java.util.Arrays.sort(keys);
                int lo = 0, hi = size - 1, evicted = 0;
                while (evicted < toEvict && lo <= hi) {
                    int dlo = Math.abs(keys[lo] - i);
                    int dhi = Math.abs(keys[hi] - i);
                    if (dlo >= dhi) { this.map.delete(keys[lo]); lo++; }
                    else { this.map.delete(keys[hi]); hi--; }
                    evicted++;
                }
            }
            this.map.put(i, bitmap);
            ReaderActivity.this.thumbsGrid.post(new Runnable() { // from class: com.pdfkhwan.app.ReaderActivity.ThumbAdapter.1
                @Override // java.lang.Runnable
                public void run() {
                    ThumbAdapter.this.notifyDataSetChanged();
                }
            });
        }

        void setCount(int i) {
            this.count = i;
        }
    }

    private class TocAdapter extends BaseAdapter {
        private TocAdapter() {
        }

        /* synthetic */ TocAdapter(ReaderActivity readerActivity, TocAdapter tocAdapter) {
            this();
        }

        @Override // android.widget.Adapter
        public int getCount() {
            return ReaderActivity.this.tocItems.size();
        }

        @Override // android.widget.Adapter
        public Object getItem(int i) {
            return ReaderActivity.this.tocItems.get(i);
        }

        @Override // android.widget.Adapter
        public long getItemId(int i) {
            return i;
        }

        @Override // android.widget.Adapter
        public View getView(int i, View view, ViewGroup viewGroup) {
            /* v1.50: ردیف مدرن فهرست مطالب — قبلاً TextView خام با پس‌زمینهٔ
               نوار بود؛ حالا ردیف لمس‌پذیر با نشانهٔ تورفتگی و شمارهٔ صفحه */
            LinearLayout row = (LinearLayout) view;
            if (row == null) {
                row = new LinearLayout(ReaderActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                row.setBackground(Modern.rippleRound(Modern.C_CARD, 12, Modern.C_RIPPLE, ReaderActivity.this));
                row.setPadding(dp(12), dp(10), dp(12), dp(10));
                TextView title = new TextView(ReaderActivity.this);
                title.setTextColor(Modern.C_TEXT);
                title.setTextSize(13.5f);
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1f);
                title.setLayoutParams(tlp);
                row.addView(title);
                TextView pg = new TextView(ReaderActivity.this);
                pg.setTextColor(Modern.C_MUTED);
                pg.setTextSize(12f);
                pg.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                row.addView(pg, new LinearLayout.LayoutParams(-2, -2));
                row.setTag(new Object[]{title, pg});
            }
            TocLoader.Item item = (TocLoader.Item) ReaderActivity.this.tocItems.get(i);
            Object[] holder = (Object[]) row.getTag();
            TextView title = (TextView) holder[0];
            TextView pg = (TextView) holder[1];
            StringBuilder sb = new StringBuilder();
            for (int d = 0; d < item.depth && d < 6; d++) sb.append("    ");
            sb.append(item.title);
            title.setText(sb.toString());
            pg.setText(item.page > 0 ? "ص " + Fa.d(item.page) : "");
            final int i2 = item.page;
            row.setOnClickListener(new View.OnClickListener() {
                @Override // android.view.View.OnClickListener
                public void onClick(View view2) {
                    if (i2 > 0) {
                        /* v1.50 FIX: پرش فهرست مطالب — قبلاً jumpTo انیمیشنی زده
                           می‌شد که با وسط‌چین‌سازی/اسکرول مسابقه می‌کرد و نمایش
                           بی‌جابه‌جا می‌ماند. حالا: انیمیشن‌های در جریان قطع،
                           پرش قطعی و بدون انیمیشن، صفحهٔ درست همان لحظه رندر
                           می‌شود (درخواست ۱۶). */
                        ReaderActivity.this.closePanels();
                        try {
                            ReaderActivity.this.pdfView.stopAnimations();
                        } catch (Throwable ignored) {
                        }
                        ReaderActivity.this.jumpTo(i2);
                        ReaderActivity.this.store.savePosition(ReaderActivity.this.fileKey, i2,
                                ReaderActivity.this.pdfView.getZoom(), ReaderActivity.this.pageCount);
                    }
                }
            });
            return row;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    private void applySelAt(SelPage selPage, int i, float f, float f2) {
        int wordAt;
        if (selPage != null) {
            try {
                if (!selPage.words.isEmpty()) {
                    RectF pageRect = pageRect(i);
                    if (pageRect.width() <= 0.0f || pageRect.height() <= 0.0f || selPage.vw <= 0.0f || selPage.vh <= 0.0f || (wordAt = wordAt(selPage, ((f - pageRect.left) * selPage.vw) / pageRect.width(), ((f2 - pageRect.top) * selPage.vh) / pageRect.height())) < 0) {
                        return;
                    }
                    this.selPage = selPage;
                    this.selPageIdx = i;
                    this.selStart = wordAt;
                    this.selEnd = wordAt;
                    this.selStartChar = 0;
                    this.selEndChar = Math.max(0, selPage.words.get(wordAt).t.length() - 1);
                    showSelectionUi();
                    return;
                }
            } catch (Throwable th) {
                CrashGuard.append(this, "applySel: " + CrashGuard.stackToString(th));
                return;
            }
        }
        long currentTimeMillis = System.currentTimeMillis();
        if (currentTimeMillis - this.selPrepToastAt > 2500) {
            this.selPrepToastAt = currentTimeMillis;
            toast("متنی روی این صفحه نیست");
        }
    }

    private void askAllFilesPermission() {
        /* v1.50: بازطراحی مدرن — قبلاً AlertDialog خام سیستم بود */
        final Dialog[] dlg = new Dialog[1];
        LinearLayout card = Modern.cardContent(this);
        card.addView(Modern.sheetHeader(this, "دسترسی لازم است",
                "برای نمایش PDFهای گوشی", new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                ReaderActivity.this.finish();
            }
        }));
        TextView msg = new TextView(this);
        msg.setText("برنامه به دسترسی «همهٔ فایل‌ها» نیاز دارد.\n\nدر صفحهٔ بعدی، اجازهٔ دسترسی به همهٔ فایل‌ها را برای «PDF خوان» روشن کنید.");
        msg.setTextColor(Modern.C_TEXT);
        msg.setTextSize(13.5f);
        int m = dp(18);
        msg.setPadding(m, dp(4), m, dp(6));
        card.addView(msg, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(android.view.Gravity.END);
        TextView cancel = Modern.filledBtn(this, "بستن", 0x2EFFFFFF, Modern.C_TEXT, new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                ReaderActivity.this.finish();
            }
        });
        btns.addView(cancel, new LinearLayout.LayoutParams(-2, -2));
        TextView ok = Modern.filledBtn(this, "دادن دسترسی", Modern.C_ACCENT, 0xFF0B1220, new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                try {
                    try {
                        ReaderActivity.this.startActivity(new Intent("android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION", Uri.parse("package:" + ReaderActivity.this.getPackageName())));
                    } catch (Exception unused) {
                        ReaderActivity.this.startActivity(new Intent("android.settings.MANAGE_ALL_FILES_ACCESS_PERMISSION"));
                    }
                } catch (Exception unused2) {
                }
                ReaderActivity.this.finish();
            }
        });
        LinearLayout.LayoutParams oklp = new LinearLayout.LayoutParams(-2, -2);
        oklp.setMargins(dp(8), 0, 0, 0);
        btns.addView(ok, oklp);
        LinearLayout.LayoutParams blpRow = new LinearLayout.LayoutParams(-1, -2);
        blpRow.topMargin = dp(10);
        card.addView(btns, blpRow);
        dlg[0] = trackDialog(Modern.showCard(this, card));
    }

    /* v1.29 FIX: keep every modal dialog reachable by onDestroy — leaving the
       reader with one open leaked the window (WindowLeaked logspam). */
    final ArrayList<Dialog> liveDialogs = new ArrayList<>();

    /* v1.33: generalized — modern sheets are plain Dialogs, not AlertDialogs */
    Dialog trackDialog(Dialog d) {
        liveDialogs.add(d);
        d.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface di) {
                liveDialogs.remove(di);
            }
        });
        return d;
    }

    private void dismissLiveDialogs() {
        for (int i = liveDialogs.size() - 1; i >= 0; i--) {
            try {
                liveDialogs.get(i).dismiss();
            } catch (Throwable ignored) {
            }
        }
        liveDialogs.clear();
    }

    /* v1.48: شیت‌هایی که به جهت صفحه حساس‌اند (چیدمان دوستونه/ارتفاع سقف)
       هنگام چرخش گوشی همان لحظه بسته و با چیدمان تازه باز می‌شوند —
       دیگر لازم نیست کاربر خودش ببندد و دوباره باز کند. */
    private final java.util.HashMap<Dialog, Runnable> rebuildOnRotate = new java.util.HashMap<>();

    Dialog trackSheet(Dialog d, final Runnable rebuild) {
        trackDialog(d);
        if (rebuild != null) rebuildOnRotate.put(d, rebuild);
        final Dialog fd = d;
        d.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface di) {
                liveDialogs.remove(di);
                rebuildOnRotate.remove(di);
            }
        });
        return d;
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (rebuildOnRotate.isEmpty()) return;
        /* کپی قبل از پیمایش — dismiss خودش از نقشه حذف می‌کند */
        ArrayList<Dialog> toClose = new ArrayList<>();
        ArrayList<Runnable> toRebuild = new ArrayList<>();
        for (java.util.Map.Entry<Dialog, Runnable> e : rebuildOnRotate.entrySet()) {
            try {
                if (e.getKey().isShowing()) {
                    toClose.add(e.getKey());
                    toRebuild.add(e.getValue());
                }
            } catch (Throwable ignored) {
            }
        }
        for (Dialog d : toClose) {
            try {
                d.dismiss();
            } catch (Throwable ignored) {
            }
        }
        for (Runnable r : toRebuild) {
            this.ui.post(r);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void clearSelection() {
        this.selPage = null;
        this.selPageIdx = -1;
        this.selEnd = -1;
        this.selStart = -1;
        this.selStartChar = -1;
        this.selEndChar = -1;
        this.selPending = null;
        SelOverlay selOverlay = this.selOverlay;
        if (selOverlay != null) {
            /* v1.32: اگر حاشیه‌نویسی‌ای هست، لایه نمایان می‌ماند (هایلایت‌ها
               باید همیشه دیده شوند) — فقط بدون انتخاب */
            selOverlay.setVisibility(this.anns != null && !this.anns.items.isEmpty() ? 0 : 8);
            if (selOverlay.getVisibility() == 0) selOverlay.invalidate();
        }
        View view = this.selHandleStart;
        if (view != null) {
            view.setVisibility(8);
        }
        View view2 = this.selHandleEnd;
        if (view2 != null) {
            view2.setVisibility(8);
        }
        LinearLayout linearLayout = this.selPopup;
        if (linearLayout != null) {
            linearLayout.setVisibility(8);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void closePanels() {
        hideAllPanels();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void copySelection() {
        String selText = selText();
        if (selText.length() == 0) {
            return;
        }
        try {
            ((ClipboardManager) getSystemService("clipboard")).setPrimaryClip(ClipData.newPlainText("pdfkhwan", selText));
            toast("کپی شد ✓");
        } catch (Throwable unused) {
            toast("کپی ممکن نشد");
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public int dp(int i) {
        return Math.round(i * getResources().getDisplayMetrics().density);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void dragSelTo(float f, float f2, boolean z) {
        int i;
        int i2;
        try {
            if (this.selPage != null && this.pageOff1 != null && this.pageH1 != null && (i = this.pageCount) > 0 && (i2 = this.selPageIdx) >= 0 && i2 < i) {
                RectF pageRect = pageRect(i2);
                if (pageRect.width() > 0.0f && pageRect.height() > 0.0f) {
                    /* v1.27 FIX: the in-band test was hardcoded to the vertical
                       page stack — in horizontal mode every handle drag
                       fell outside the band and the selection never moved.
                       The band now follows the active page-turn axis. */
                    boolean inBand;
                    if (isHoriz()) {
                        float dx = f - pageRect.left;
                        inBand = dx >= -pageRect.width() && dx <= pageRect.width() * 2.0f;
                    } else {
                        float dy = f2 - pageRect.top;
                        inBand = dy >= -pageRect.height() && dy <= pageRect.height() * 2.0f;
                    }
                    if (inBand) {
                        float vx = ((f - pageRect.left) * this.selPage.vw) / pageRect.width();
                        float vy = ((f2 - pageRect.top) * this.selPage.vh) / pageRect.height();
                        int wordAt = wordAt(this.selPage, vx, vy);
                        if (wordAt < 0) {
                            return;
                        }
                        /* v1.41: letter-level — where in the word did the handle land? */
                        SelWord w = this.selPage.words.get(wordAt);
                        int charAt = w.charAt(vx);
                        /* charAt is ALWAYS reading-order index 0..n-1 (w.charAt translates visual vx to reading index
                           taking w.rtl into account), so reading-order index comparison is exact for RTL, LTR, and mixed words. */
                        if (z) {
                            int i4 = this.selEnd;
                            boolean after = wordAt > i4 || (wordAt == i4 && charAt > this.selEndChar);
                            if (after) {
                                this.selStart = i4;
                                this.selEnd = wordAt;
                                this.selStartChar = this.selEndChar;
                                this.selEndChar = charAt;
                            } else {
                                this.selStart = wordAt;
                                this.selStartChar = charAt;
                            }
                        } else {
                            int i5 = this.selStart;
                            boolean before = wordAt < i5 || (wordAt == i5 && charAt < this.selStartChar);
                            if (before) {
                                this.selEnd = i5;
                                this.selStart = wordAt;
                                this.selEndChar = this.selStartChar;
                                this.selStartChar = charAt;
                            } else {
                                this.selEnd = wordAt;
                                this.selEndChar = charAt;
                            }
                        }
                        /* same word with inverted char range → normalize */
                        if (this.selStart == this.selEnd && this.selStartChar > this.selEndChar) {
                            int tc = this.selStartChar;
                            this.selStartChar = this.selEndChar;
                            this.selEndChar = tc;
                        }
                        this.selOverlay.invalidate();
                        positionHandles();
                    }
                }
            }
        } catch (Throwable unused) {
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void ensureGeom() {
        if (this.pageCount <= 0) {
            this.pageOff1 = null;
            this.pageOffX1 = null;
            return;
        }
        int width = this.pdfView.getWidth();
        if (this.pageOff1 != null && this.geomBuiltW == width && this.geomBuiltCount == this.pageCount
                && this.geomBuiltHoriz == isHoriz()) {
            return;
        }
        try {
            int i = this.pageCount;
            this.pageOff1 = new float[i];
            this.pageH1 = new float[i];
            /* v1.27 FIX: the geometry was ALWAYS built as a vertical stack —
               in the «ورق زدن افقی صفحات» (horizontal page-turn) mode every
               selection/handle/highlight sat on the WRONG page or the wrong
               spot. Both axes are now tracked. */
            this.pageOffX1 = new float[i];
            this.pageW1 = new float[i];
            this.maxPageH1 = 0.0f;
            float f = 0.0f;
            float fx = 0.0f;
            this.maxPageW1 = 0.0f;
            float spacingPx = this.pdfView.getSpacingPx();
            int i2 = 0;
            while (true) {
                int i3 = this.pageCount;
                if (i2 >= i3) {
                    this.geomBuiltW = width;
                    this.geomBuiltCount = i3;
                    this.geomBuiltHoriz = isHoriz();
                    return;
                }
                this.pageOff1[i2] = f;
                this.pageOffX1[i2] = fx;
                SizeF pageSize = this.pdfView.getPageSize(i2);
                if (pageSize.getWidth() > this.maxPageW1) {
                    this.maxPageW1 = pageSize.getWidth();
                }
                if (pageSize.getHeight() > this.maxPageH1) {
                    this.maxPageH1 = pageSize.getHeight();
                }
                this.pageH1[i2] = pageSize.getHeight();
                this.pageW1[i2] = pageSize.getWidth();
                f += pageSize.getHeight() + spacingPx;
                /* v1.47: در محور افقی (به‌ویژه حالت کتابِ راست‌به‌چپ) آفست واقعی
                   هر صفحه را از خودِ PdfFile بگیر — جمع ترتیبی فقط برای چیدمان
                   چپ‌به‌راست درست است. */
                try {
                    com.github.barteksc.pdfviewer.PdfFile pf = this.pdfView.getPdfFile();
                    if (pf != null && !this.pdfView.isSwipeVertical()) {
                        this.pageOffX1[i2] = pf.getPageOffset(i2, 1.0f);
                    }
                } catch (Throwable ignoredBook) {
                }
                fx += pageSize.getWidth() + spacingPx;
                i2++;
            }
        } catch (Throwable unused) {
            this.pageOff1 = null;
            this.pageOffX1 = null;
        }
    }

    /* v1.27: current page-turn axis */
    boolean isHoriz() {
        try {
            return this.pdfView != null && !this.pdfView.isSwipeVertical();
        } catch (Throwable t) {
            return false;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void fileInfo() {
        final Dialog[] dlg = new Dialog[1];
        LinearLayout card = Modern.cardContent(this);
        card.addView(Modern.sheetHeader(this, "اطلاعات فایل", this.docName, new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { dlg[0].dismiss(); } catch (Throwable ignored) {}
            }
        }));
        card.addView(Modern.infoRow(this, "نام فایل", this.docName));
        card.addView(Modern.infoRow(this, "حجم فایل", Fa.bytes(this.docSize)));
        card.addView(Modern.infoRow(this, "تعداد صفحات", Fa.d(this.pageCount)));
        card.addView(Modern.infoRow(this, "صفحهٔ فعلی", Fa.d(getCurrentPage1())));
        card.addView(Modern.infoRow(this, "بزرگ‌نمایی", Fa.d(Math.round(this.pdfView.getZoom() * 100.0f)) + "٪"));
        /* v1.37: اطلاعات کامل — تاریخ آخرین تغییر + نوع، و مسیر با دکمهٔ کپی
           (دکمه‌های «اطلاعات فایل» و «نمایش آدرس» یکی شدند) */
        if ("file".equals(this.uri.getScheme())) {
            try {
                File f = new File(this.uri.getPath());
                if (f.exists() && f.lastModified() > 0) {
                    String date = new java.text.SimpleDateFormat("yyyy/MM/dd — HH:mm", java.util.Locale.US)
                            .format(new java.util.Date(f.lastModified()));
                    card.addView(Modern.infoRow(this, "آخرین تغییر", Fa.d(date)));
                }
                String p = this.uri.getPath();
                if (p != null && p.lastIndexOf('.') > 0) {
                    card.addView(Modern.infoRow(this, "فرمت", p.substring(p.lastIndexOf('.') + 1).toUpperCase()));
                }
            } catch (Throwable ignoredDate) {
            }
        }
        final boolean isFile = "file".equals(this.uri.getScheme());
        if (isFile) {
            TextView pathLabel = new TextView(this);
            pathLabel.setText("مسیر فایل");
            pathLabel.setTextColor(Modern.C_MUTED);
            pathLabel.setTextSize(12.5f);
            int hp = dp(18);
            pathLabel.setPadding(hp, dp(10), hp, dp(4));
            card.addView(pathLabel);
            TextView path = new TextView(this);
            path.setText(this.uri.getPath());
            path.setTextColor(Modern.C_TEXT);
            path.setTextSize(11.5f);
            path.setBackground(Modern.fieldBg(this));
            path.setPadding(hp, dp(8), hp, dp(8));
            card.addView(path, new LinearLayout.LayoutParams(-1, -2));
        }
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(android.view.Gravity.CENTER_VERTICAL);
        btns.setPadding(0, dp(14), 0, 0);
        if (isFile) {
            TextView copy = Modern.tonalBtn(this, "کپی مسیر", Modern.C_ACCENT, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    try {
                        ((ClipboardManager) getSystemService("clipboard")).setPrimaryClip(ClipData.newPlainText("pdfkhwan", uri.getPath()));
                        toast("مسیر کپی شد ✓");
                    } catch (Exception unused) {
                    }
                }
            });
            btns.addView(copy, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView close = Modern.filledBtn(this, "بستن", Modern.C_SURFACE, Modern.C_TEXT, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                }
            });
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, -2, 1f);
            clp.setMarginStart(dp(8));
            btns.addView(close, clp);
        } else {
            TextView close = Modern.filledBtn(this, "بستن", Modern.C_ACCENT, Modern.onColor(Modern.C_ACCENT), new View.OnClickListener() {
                @Override public void onClick(View v) {
                    try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                }
            });
            btns.addView(close, new LinearLayout.LayoutParams(-1, -2));
        }
        card.addView(btns, new LinearLayout.LayoutParams(-1, -2));
        dlg[0] = trackDialog(Modern.showCardAuto(this, card));
    }


    /* JADX INFO: Access modifiers changed from: private */
    public int getCurrentPage1() {
        try {
            return this.pdfView.getCurrentPage() + 1;
        } catch (Exception unused) {
            return this.lastPage1;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void gotoPageDialog() {
        /* v1.15: Fa.parseNum ارقام فارسی/عربی/انگلیسی؛ v1.16: پیش‌پرشدن با
           شمارهٔ صفحهٔ فعلی؛ v1.34: ظاهر مدرن (کارت به‌جای AlertDialog)،
           دکمهٔ «برو» روی کیبورد و بازشدن خودکار کیبورد. */
        if (this.pageCount <= 0) {
            toast("فایل هنوز کامل باز نشده — کمی صبر کن و دوباره بزن");
            return;
        }
        final Dialog[] dlg = new Dialog[1];
        LinearLayout card = Modern.cardContent(this);
        card.addView(Modern.sheetHeader(this, "رفتن به صفحه", "این فایل " + Fa.d(this.pageCount) + " صفحه دارد",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                    }
                }));
        final EditText editText = new EditText(this);
        editText.setInputType(2);
        String cur = Fa.d(getCurrentPage1());
        editText.setText(cur);
        editText.setSelection(cur.length());
        editText.setHint("شمارهٔ صفحه (۱ تا " + Fa.d(this.pageCount) + ")");
        editText.setTextColor(Modern.C_TEXT);
        editText.setHintTextColor(Modern.C_MUTED);
        editText.setTextSize(17f);
        editText.setGravity(android.view.Gravity.CENTER);
        editText.setBackground(Modern.fieldBg(this));
        int ep = dp(14);
        editText.setPadding(ep, dp(12), ep, dp(12));
        editText.setSingleLine(true);
        editText.setImeOptions(6); /* IME_ACTION_DONE */
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(-1, -2);
        elp.topMargin = dp(10);
        card.addView(editText, elp);

        /* منطق پرش — مشترک بین دکمهٔ «برو» و کلید کیبورد */
        final Runnable go = new Runnable() {
            @Override public void run() {
                int pageNum = Fa.parseNum(editText.getText().toString());
                if (pageNum < 0) {
                    toast("عدد نامعتبر — عدد صفحه را بنویس (مثلاً ۱۲)");
                    return;
                }
                if (pageNum < 1 || pageNum > pageCount) {
                    toast("صفحه بین ۱ تا " + Fa.d(pageCount));
                    return;
                }
                try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                jumpTo(pageNum);
                closePanels();
                /* اگر انیمیشن پرش توسط لمسی قاطع شد، یک‌بار بدون انیمیشن */
                ui.postDelayed(new Runnable() {
                    @Override public void run() {
                        try {
                            if (loaded && getCurrentPage1() != pageNum) {
                                jumpTo(pageNum);
                            }
                        } catch (Throwable unused) {
                        }
                    }
                }, 500L);
            }
        };
        editText.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView tv, int action, KeyEvent ke) {
                go.run();
                return true;
            }
        });
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setPadding(0, dp(14), 0, 0);
        TextView cancel = Modern.tonalBtn(this, "انصراف", Modern.C_MUTED, new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { dlg[0].dismiss(); } catch (Throwable ignored) {}
            }
        });
        btns.addView(cancel, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView ok = Modern.filledBtn(this, "برو", Modern.C_ACCENT, Modern.onColor(Modern.C_ACCENT),
                new View.OnClickListener() {
                    @Override public void onClick(View v) { go.run(); }
                });
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(0, -2, 1f);
        olp.setMarginStart(dp(8));
        btns.addView(ok, olp);
        card.addView(btns, new LinearLayout.LayoutParams(-1, -2));
        dlg[0] = trackDialog(Modern.showCardAuto(this, card));
        editText.postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    android.view.inputmethod.InputMethodManager imm =
                            (android.view.inputmethod.InputMethodManager) getSystemService("input_method");
                    if (imm != null) imm.showSoftInput(editText, 0);
                } catch (Throwable ignored) {}
            }
        }, 250L);
    }


    private View.OnTouchListener handleDrag(final boolean z) {
        return new View.OnTouchListener() { // from class: com.pdfkhwan.app.ReaderActivity.30
            private final int[] loc = new int[2];

            @Override // android.view.View.OnTouchListener
            public boolean onTouch(View view, MotionEvent motionEvent) {
                int actionMasked = motionEvent.getActionMasked();
                if (actionMasked == 0) {
                    ReaderActivity.this.selDragging = true;
                    ReaderActivity.this.selPopup.setVisibility(8);
                    return true;
                }
                if (actionMasked != 1) {
                    if (actionMasked == 2) {
                        ReaderActivity.this.pdfView.getLocationOnScreen(this.loc);
                        ReaderActivity.this.dragSelTo(motionEvent.getRawX() - this.loc[0], motionEvent.getRawY() - this.loc[1], z);
                        return true;
                    }
                    if (actionMasked != 3) {
                        return false;
                    }
                }
                ReaderActivity.this.selDragging = false;
                ReaderActivity.this.positionPopup();
                ReaderActivity.this.selPopup.setVisibility(0);
                return true;
            }
        };
    }

    private boolean hasAllFiles() {
        return Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void hideAllPanels() {
        this.searchPanel.setVisibility(8);
        this.tocPanel.setVisibility(8);
        this.thumbsPanel.setVisibility(8);
        View view = this.bookmarksView;
        if (view != null) {
            view.setVisibility(8);
        }
    }

    /* JADX WARN: Multi-variable type inference failed */
    private void init(Bundle bundle) {
        setContentView(R.layout.activity_reader);
        RecentStore recentStore = RecentStore.get(this);
        this.store = recentStore;
        this.keepScreenOn = recentStore.getBool("keepOn", true);
        display.applyKeepScreenOn();
        this.night = this.store.getBool("night", false);
        /* v1.47: بخش ۱۸ — ترجیحات نمایش (کاغذ/چرخش/روشنایی/اسکرول خودکار) */
        display.loadDisplayPrefs();
        display.applyOrientationLock();
        display.applyPaperMode(false);
        display.applyBrightness();
        String stringExtra = getIntent().getStringExtra("uri");
        if (stringExtra == null && getIntent().getData() != null) {
            stringExtra = getIntent().getData().toString();
        }
        if (stringExtra == null) {
            finish();
            return;
        }
        Uri parse = Uri.parse(stringExtra);
        this.uri = parse;
        String[] queryMeta = queryMeta(parse);
        String str = queryMeta[0];
        this.docName = str;
        String lowerCase = str.toLowerCase(Locale.US);
        /* v7 FIX: the isPptFamily() helper (v1.22) accepts ALL presentation
           formats (.pptx/.ppt/.ppsx/.ppsm/.pptm/.potx/.potm) but this
           redirect only checked .pptx/.ppt — the rest fell into the PDF
           path and failed with a confusing error. */
        if (isPptFamily(lowerCase)) {
            Intent intent = new Intent(this, (Class<?>) PptEditorActivity.class);
            intent.putExtra("uri", stringExtra);
            intent.putExtra("name", this.docName);
            startActivity(intent);
            finish();
            return;
        }
        try {
            this.docSize = Long.parseLong(queryMeta[1]);
        } catch (Exception unused) {
            this.docSize = 0L;
        }
        this.fileKey = RecentStore.fileKey(this.docName, this.docSize);
        this.pdfView = (PDFView) findViewById(R.id.pdfView);
        /* v1.38: هایلایت‌ها حالا داخل همان پاس رسم صفحه (لایهٔ PDFView)
           کشیده می‌شوند تا با بلند مولتی‌پلای واقعاً مثل ماژیک زیر متن
           بیفتند — متن زیر هایلایت هرچند بار روی هم بیفتد سالم می‌ماند */
        this.pdfView.setViewOverlay(new PDFView.OnViewOverlay() {
            @Override public void onViewDraw(Canvas c) {
                try {
                    annDraw.drawAnnotations(c, pdfView.getWidth(), pdfView.getHeight());
                } catch (Throwable ignored) {
                }
            }
        });
        this.loading = findViewById(R.id.loading);
        this.topBar = findViewById(R.id.topBar);
        this.docTitle = (TextView) findViewById(R.id.docTitle);
        this.searchPanel = findViewById(R.id.searchPanel);
        this.tocPanel = findViewById(R.id.tocPanel);
        this.thumbsPanel = findViewById(R.id.thumbsPanel);
        this.searchInput = (EditText) findViewById(R.id.searchInput);
        this.searchProgressView = (ProgressBar) findViewById(R.id.searchProgress);
        this.searchProgressRow = findViewById(R.id.searchProgressRow);
        this.searchProgressText = (TextView) findViewById(R.id.searchProgressText);
        this.searchStatus = (TextView) findViewById(R.id.searchStatus);
        this.tocStatus = (TextView) findViewById(R.id.tocStatus);
        this.resultsList = (ListView) findViewById(R.id.searchResults);
        this.tocList = (ListView) findViewById(R.id.tocList);
        this.thumbsGrid = (GridView) findViewById(R.id.thumbsGrid);
        this.searchWeb = (WebView) findViewById(R.id.searchWeb);
        this.docTitle.setText(this.docName);
        ResultsAdapter resultsAdapter = new ResultsAdapter(this, null);
        this.resultsAdapter = resultsAdapter;
        this.resultsList.setAdapter((ListAdapter) resultsAdapter);
        TocAdapter tocAdapter = new TocAdapter();
        this.tocAdapter = tocAdapter;
        this.tocList.setAdapter((ListAdapter) tocAdapter);
        ThumbAdapter thumbAdapter = new ThumbAdapter();
        this.thumbAdapter = thumbAdapter;
        this.thumbsGrid.setAdapter((ListAdapter) thumbAdapter);
        this.thumbs = new PdfThumbs(this, openUri(), this.docPassword);
        /* v1.47: پیش‌گرم‌کردن خاموش موتور صدای سیستم — اولین «خواندن با صدا»
           بدون تأخیرِ ساخت موتور شروع می‌شود (بخش ۲۱) */
        try {
            SystemTtsEngine.get(this).ensure(null);
        } catch (Throwable ignoredWarm) {
        }
        /* v1.32: بارگذاری حاشیه‌نویسی‌های این سند + پالت شخصی */
        annDialogs.initAnnotations();
        setupBars();
        setupSearchWeb();
        cleanupUnlocked();   /* v1.40: نسخه‌های رمزگشایی‌شدهٔ جامانده از اجرای قبل */
        openDoc();
    }

    void initSelectionUi() {
        if (this.selOverlay != null) {
            return;
        }
        ViewGroup viewGroup = (ViewGroup) findViewById(R.id.pdfView).getParent();
        /* v1.32: لایهٔ رسم آزاد — زیر لایهٔ هایلایت‌ها تا خطوط دستی زیر
           متن‌های علامت‌خورده دیده شوند؛ فقط وقتی ابزاری فعال است لمس را می‌گیرد */
        DrawOverlay dOverlay = new DrawOverlay(this);
        this.drawOverlay = dOverlay;
        dOverlay.setVisibility(8);
        viewGroup.addView(dOverlay, 1, new ViewGroup.LayoutParams(-1, -1));
        SelOverlay selOverlay = new SelOverlay(this);
        this.selOverlay = selOverlay;
        selOverlay.setVisibility(8);
        viewGroup.addView(this.selOverlay, 1, new ViewGroup.LayoutParams(-1, -1));
        ImageView imageView = new ImageView(this);
        imageView.setImageResource(R.drawable.sel_handle);
        imageView.setVisibility(8);
        imageView.setOnTouchListener(handleDrag(true));
        /* v1.28 CRITICAL FIX (RTL): بی‌تعیین‌کردن gravity یعنی TOP|START که
           در لوکِیل فارسی به «راست» تفسیر می‌شود؛ بعد setTranslationX
           (که همیشه از چپ حساب می‌شود) دستگیره را بیرون صفحه می‌برد و
           کپی/اشتراک/جستجو برای کاربر فارسی عملاً غیرقابل‌دسترس بود.
           Gravity.LEFT مطلق است و به جهت چیدمان وابسته نیست. */
        viewGroup.addView(imageView, 2, new android.widget.FrameLayout.LayoutParams(
                dp(24), dp(24), android.view.Gravity.LEFT | android.view.Gravity.TOP));
        this.selHandleStart = imageView;
        ImageView imageView2 = new ImageView(this);
        imageView2.setImageResource(R.drawable.sel_handle);
        imageView2.setVisibility(8);
        imageView2.setOnTouchListener(handleDrag(false));
        viewGroup.addView(imageView2, 3, new android.widget.FrameLayout.LayoutParams(
                dp(24), dp(24), android.view.Gravity.LEFT | android.view.Gravity.TOP));
        this.selHandleEnd = imageView2;
        /* ============ v1.41: نوار انتخاب — حیاتی‌ها همیشه جلوی دست ============
           [● زرد][● سرخ][● آبی] | [کپی][انتخاب همه][اشتراک] | [⋮]
           — سه رنگ اصلی هایلایت با ۱ لمس (زرد FFD500، سرخ FF4530، آبی 3B88FF)
           — کپی / انتخاب همه / اشتراک‌گذاری مستقیم روی نوار (حیاتی‌اند)
           — بقیهٔ ابزارها (ماژیکِ رنگ فعال، زیرخط، خط‌خورده، موج‌دار،
             یادداشت، جستجوی وب، بستن) داخل منوی ⋮ */
        LinearLayout popup = new LinearLayout(this);
        this.selPopup = popup;
        popup.setOrientation(LinearLayout.VERTICAL);
        popup.setBackgroundResource(R.drawable.bg_sel_toolbar);
        popup.setElevation(dp(6));
        popup.setVisibility(8);

        android.widget.HorizontalScrollView hsv = new android.widget.HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.setFillViewport(false);
        popup.addView(hsv, new LinearLayout.LayoutParams(-2, -2));

        LinearLayout row = new LinearLayout(this);
        this.selToolRow = row;
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int rp = dp(5);
        row.setPadding(rp, rp, rp, rp);
        hsv.addView(row, new android.widget.FrameLayout.LayoutParams(-2, -2));

        /* --- سه رنگ اصلی: هایلایت فوری --- */
        final int[] QUICK3 = {0xFFFFD500, 0xFFFF4530, 0xFF3B88FF};
        this.selColorDots = new View[QUICK3.length];
        for (int i = 0; i < QUICK3.length; i++) {
            final int color = QUICK3[i];
            View dot = new View(this);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(22), dp(22));
            dlp.setMargins(dp(3), 0, dp(3), 0);
            dot.setLayoutParams(dlp);
            dot.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    annDialogs.addTextAnn(Annotations.T_HL, color);   /* ۱ کلیک: هایلایت با همین رنگ */
                }
            });
            this.selColorDots[i] = dot;
            row.addView(dot);
        }

        row.addView(vDivider());

        /* --- کپی متن (حیاتی) --- */
        ImageView copyBtn = Modern.iconBtn(this, R.drawable.ic_ann_copy, Modern.C_ACCENT, new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                copySelection();
            }
        });
        row.addView(copyBtn, btnParams());

        /* --- انتخاب همهٔ صفحه (حیاتی) --- */
        ImageView allBtn = Modern.iconBtn(this, R.drawable.ic_ann_selall, 0xFFFFD54F, new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                selectAllSel();
            }
        });
        row.addView(allBtn, btnParams());

        /* --- اشتراک‌گذاری متن (حیاتی) --- */
        ImageView shareBtn = Modern.iconBtn(this, R.drawable.ic_ann_share, 0xFF64B5F6, new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                String selText = selText();
                if (selText.length() == 0) return;
                try {
                    Intent intent = new Intent("android.intent.action.SEND");
                    intent.setType("text/plain");
                    intent.putExtra("android.intent.extra.TEXT", selText);
                    startActivity(Intent.createChooser(intent, "اشتراک‌گذاری متن"));
                } catch (Exception unused) {
                    toast("اشتراک ممکن نشد");
                }
            }
        });
        row.addView(shareBtn, btnParams());

        /* v1.47: خواندن از همین‌جا — شروع خواندن پیوسته از ابتدای انتخاب */
        ImageView speakBtn = Modern.iconBtn(this, R.drawable.ic_m_speak, 0xFF4DD0E1, new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                tts.ttsStartFromSelection();
            }
        });
        row.addView(speakBtn, btnParams());

        row.addView(vDivider());

        /* --- منوی بیشتر: ابزارهای حاشیه‌نویسی + وب + بستن --- */
        ImageView more = Modern.iconBtn(this, R.drawable.ic_ann_list, Modern.C_MUTED, new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showSelMoreMenu(view);
            }
        });
        row.addView(more, btnParams());

        refreshSelToolbar();
        viewGroup.addView(this.selPopup, 4, new android.widget.FrameLayout.LayoutParams(
                -2, -2, android.view.Gravity.LEFT | android.view.Gravity.TOP));
    }

    /* جدا کنندهٔ عمودی داخل پیل */
    View vDivider() {
        View d = new View(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Math.max(1, dp(1)), dp(22));
        lp.setMargins(dp(5), 0, dp(5), 0);
        d.setLayoutParams(lp);
        d.setBackgroundColor(0x2EFFFFFF);
        return d;
    }

    /* پارامتر دکمه‌های آیکونی نوار انتخاب */
    private LinearLayout.LayoutParams btnParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(38), dp(38));
        lp.setMargins(dp(1), 0, dp(1), 0);
        return lp;
    }

    /* به‌روزرسانی ظاهر نوار انتخاب — v1.41: سه رنگ ثابت (زرد/سرخ/آبی)،
       بدون حلقهٔ انتخاب؛ هرکدام با ۱ لمس هایلایت می‌زنند */
    private void refreshSelToolbar() {
        if (this.selColorDots == null) return;
        final int[] QUICK3 = {0xFFFFD500, 0xFFFF4530, 0xFF3B88FF};
        for (int i = 0; i < this.selColorDots.length && i < QUICK3.length; i++) {
            Modern.applySwatch(this.selColorDots[i], this, QUICK3[i] | 0xFF000000, false);
        }
    }

    /* منوی «بیشتر» نوار انتخاب — v1.41: ابزارهای حاشیه‌نویسی (ماژیک با رنگ
       فعال، زیرخط، خط‌خورده، موج‌دار، یادداشت) + جستجوی وب + بستن؛
       کپی/انتخاب همه/اشتراک مستقیم روی خود نوارند */
    private void showSelMoreMenu(View anchor) {
        final Dialog[] dlg = new Dialog[1];
        LinearLayout sheet = Modern.sheetContent(this);
        sheet.addView(Modern.sheetHeader(this, "متن انتخاب‌شده", null, new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { dlg[0].dismiss(); } catch (Throwable ignored) {}
            }
        }));
        sheet.addView(Modern.actionRow(this, R.drawable.ic_ann_marker, pen.hlColor(), "ماژیک (رنگ فعال پالت)", null,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                        annDialogs.addTextAnn(Annotations.T_HL);
                    }
                }), new LinearLayout.LayoutParams(-1, -2));
        sheet.addView(Modern.actionRow(this, R.drawable.ic_ann_ul, Modern.C_TEXT, "زیرخط", null,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                        annDialogs.addTextAnn(Annotations.T_UL);
                    }
                }), new LinearLayout.LayoutParams(-1, -2));
        sheet.addView(Modern.actionRow(this, R.drawable.ic_ann_st, Modern.C_TEXT, "خط‌خورده", null,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                        annDialogs.addTextAnn(Annotations.T_ST);
                    }
                }), new LinearLayout.LayoutParams(-1, -2));
        sheet.addView(Modern.actionRow(this, R.drawable.ic_ann_sq, Modern.C_TEXT, "زیرخط موج‌دار", null,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                        annDialogs.addTextAnn(Annotations.T_SQ);
                    }
                }), new LinearLayout.LayoutParams(-1, -2));
        sheet.addView(Modern.actionRow(this, R.drawable.ic_ann_note, Modern.C_TEXT, "یادداشت متنی", null,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                        annDialogs.addTextAnn(Annotations.T_NOTE);
                    }
                }), new LinearLayout.LayoutParams(-1, -2));
        sheet.addView(Modern.actionRow(this, R.drawable.ic_ann_web, 0xFFBA68C8, "جستجو در وب", null,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                        String selText = selText();
                        if (selText.length() == 0) return;
                        try {
                            Uri u = Uri.parse("https://www.google.com/search?q=" + Uri.encode(selText));
                            startActivity(new Intent("android.intent.action.VIEW", u));
                        } catch (Exception unused) {
                            toast("مرورگر باز نشد");
                        }
                    }
                }), new LinearLayout.LayoutParams(-1, -2));
        sheet.addView(Modern.actionRow(this, R.drawable.ic_ann_close, Modern.C_DANGER, "بستن نوار", null,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                        clearSelection();
                    }
                }), new LinearLayout.LayoutParams(-1, -2));
        LinearLayout pad = new LinearLayout(this);
        sheet.addView(pad, new LinearLayout.LayoutParams(-1, dp(14)));
        dlg[0] = trackDialog(Modern.showSheetAuto(this, sheet));
    }


    /* JADX INFO: Access modifiers changed from: private */
    public void jumpTo(int i) {
        jumpTo(i, true);
    }

    /* v1.16: non-animated variant for volume-key page turns — a 400 ms
       animation per key press felt laggy. */
    public void jumpTo(int i, boolean withAnimation) {
        try {
            int i2 = this.pageCount;
            if (i2 <= 0) {
                return;
            }
            this.pdfView.jumpTo(Math.max(1, Math.min(i2, i)) - 1, withAnimation);
        } catch (Throwable unused) {
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void loadTocNative() {
        this.tocLoading = true;
        this.tocStatus.setVisibility(0);
        this.tocStatus.setText("در حال استخراج فهرست…");
        TocLoader tocLoader = new TocLoader(this, openUri(), this.docPassword);
        this.tocLoader = tocLoader;
        tocLoader.start(new TocLoader.Callback() { // from class: com.pdfkhwan.app.ReaderActivity.32
            @Override // com.pdfkhwan.app.TocLoader.Callback
            public void onDone(List<TocLoader.Item> list) {
                ReaderActivity.this.tocLoading = false;
                ReaderActivity.this.tocItems.clear();
                ReaderActivity.this.tocItems.addAll(list);
                if (ReaderActivity.this.tocItems.isEmpty()) {
                    ReaderActivity.this.tocStatus.setVisibility(0);
                    ReaderActivity.this.tocStatus.setText("این فایل فهرست مطالب ندارد.");
                } else {
                    ReaderActivity.this.tocStatus.setVisibility(8);
                }
                ReaderActivity.this.tocAdapter.notifyDataSetChanged();
            }

            @Override // com.pdfkhwan.app.TocLoader.Callback
            public void onError(String str) {
                ReaderActivity.this.tocLoading = false;
                ReaderActivity.this.tocStatus.setVisibility(0);
                ReaderActivity.this.tocStatus.setText(str);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onLoaded(int i, int i2) {
        this.loaded = true;
        this.pageCount = i;
        this.loading.setVisibility(8);
        /* v1.37: حالت‌های ژست — ورق‌زدن، قفل زوم و نشانگر ۱۰۰٪ */
        try {
            this.pdfView.setPageFlipMode(this.store.getBool("horiz", false) && this.store.getBool("hflip", false));
            this.pdfView.setZoomLocked(pen.zoomLocked);
            this.pdfView.setZoomAtHundredListener(new Runnable() {
                @Override public void run() {
                    ReaderActivity.this.showZoomHundredBadge();
                }
            });
        } catch (Throwable gestureIgnored) {
        }
        /* v1.32: لایهٔ حاشیه‌نویسی را همین‌جا بساز تا هایلایت‌های موجود
           از همان اول دیده شوند */
        try {
            initSelectionUi();
            if (!this.anns.items.isEmpty()) {
                this.selOverlay.setVisibility(0);
                this.selOverlay.invalidate();
                startGeomWatch();
            }
        } catch (Throwable annIgnored) {
        }
        /* v1.46: نقشهٔ فرم/لینک — بارگذاری خاموش در پس‌زمینه */
        try {
            forms.loadInteract();
        } catch (Throwable interIgnored) {
        }
        try {
            this.pdfView.setMinZoom(0.5f);
            this.pdfView.setMidZoom(2.5f);
            this.pdfView.setMaxZoom(8.0f);
            /* v1.39: دوبار ضربهٔ سطح ۲ (زوم حداکثری ۸x) — فقط با انتخاب کاربر */
            this.pdfView.setDoubleTapMax(this.store.getBool("dblTapMax", false));
            /* v7: restore the reader's zoom from the last session (the page
               was restored but the zoom always reset to fit-width) */
            /* v1.29 FIX: the save path stores ANY zoom (0.5–8), but the
               restore only accepted >1.02 — a user who left the document
               zoomed OUT always reopened at fit-width. Restore everything
               inside the allowed zoom range now. */
            float savedZoom = (float) this.store.getPosition(this.fileKey).optDouble("zoom", 1.0d);
            /* v1.47: در حالت کتاب دوصفحه‌ای زوم ذخیره‌شدهٔ جلسهٔ قبل بی‌معنی است
               (صفحه‌ها نصف‌عرض شده‌اند) — همیشه با تناسب تازه شروع شود. */
            if (savedZoom >= 0.5f && savedZoom <= 8.0f && !this.pdfView.isBookRtl()) {
                this.pdfView.zoomTo(savedZoom);
            }
        } catch (Throwable unused) {
        }
        this.thumbAdapter.setCount(i);
        if (i2 > 1 && i2 <= i) {
            toast("ادامه از صفحهٔ " + Fa.d(i2));
        }
        this.store.addRecent(this.uri.toString(), this.docName, this.docSize, i);
        /* v1.29: رمز درست بود — شمارندهٔ «رمز غلط» صفر شود */
        this.passwordAttempts = 0;
        /* v1.40: انتخاب متن پیش‌فرض آماده است — موتور متن همین حالا برای
           صفحهٔ فعلی (بی‌صدا) گرم می‌شود تا اولین لمس-بلطنی بدون انتظارِ
           «در حال آماده‌سازی» کار کند */
        try {
            if (this.webReady) {
                requestSelWords(this.pdfView.getCurrentPage(), true);
            }
        } catch (Throwable warmIgnored) {
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onOpenFailed(Throwable th) {
        this.loading.setVisibility(8);
        CrashGuard.append(this, "open: " + CrashGuard.stackToString(th) + "\n");
        String simpleName = th == null ? "Unknown" : th.getClass().getSimpleName();
        String valueOf = th == null ? "" : String.valueOf(th.getMessage());
        /* v7 + v1.40: هر خطای رمز/قفل → مسیر بازکردن قفل (دیالوگ رمز یا
           رمزگشایی خودکار). پیش از v1.40 فقط «Password» تشخیص داده می‌شد؛
           فایل‌های AES-256 (که pdfium اصلاً نمی‌شناسد) با «Unsupported
           security scheme» به بن‌بست «فایل خراب است» می‌رسیدند. */
        if (simpleName.contains("Password") || PdfUnlock.isSecurityFailure(th)) {
            tryUnlock();
            return;
        }
        showOpenError(((th instanceof SecurityException) || simpleName.contains("FileNotFound")) ? "دسترسی به فایل ممکن نشد. لطفاً دسترسی «همهٔ فایل\u200cها» را در تنظیمات برنامه بدهید." : th instanceof IOException ? "این فایل خراب است یا PDF معتبری نیست." : "باز کردن فایل ممکن نشد.", String.valueOf(simpleName) + ": " + valueOf);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onPageChangedUi(int i, int i2) {
        this.lastPage1 = i + 1;
        if (this.selPage != null && this.selPageIdx != i) {
            clearSelection();
        }
        /* v7: search highlights live on their hit page only */
        if (this.hlPageIdx != i) {
            this.hlWords.clear();
            if (this.selOverlay != null) {
                this.selOverlay.invalidate();
            }
        }
        if (this.selEngineOn && this.selPages.get(Integer.valueOf(i)) == null) {
            requestSelWords(i, true);
        }
        /* v1.40: پیش‌واگذاری صفحهٔ جدید هم وقتی موتور تازه گرم شده — دیگری
           منتظر اولین لمس-بلطنی نمی‌مانیم */
        if (!this.selEngineOn && this.webReady && this.selPages.get(Integer.valueOf(i)) == null) {
            requestSelWords(i, true);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onPdfLongPress(MotionEvent motionEvent) {
        int pageAtDocY;
        /* v1.46: نگه‌داشتن روی لینک → نمایش آدرس/مقصد */
        try {
            FormFiller.Link hl = links.linkAt(motionEvent.getX(), motionEvent.getY());
            if (hl != null) {
                if (hl.kind == FormFiller.L_URI) {
                    toast(hl.uri);
                } else if (hl.kind == FormFiller.L_GOTO) {
                    toast("مقصد: صفحهٔ " + Fa.d(hl.destPage + 1));
                } else {
                    toast("لینک تعاملی — این نوع اینجا باز نمی‌شود");
                }
                return;
            }
        } catch (Throwable ignored) {
        }
        try {
            if (this.loaded && this.pageCount > 0 && this.pdfView != null) {
                ensureGeom();
                if (this.pageOff1 != null && (pageAtDocY = pageAt(motionEvent.getX(), motionEvent.getY())) >= 0) {
                    SelPage selPage = this.selPages.get(Integer.valueOf(pageAtDocY));
                    if (selPage != null) {
                        applySelAt(selPage, pageAtDocY, motionEvent.getX(), motionEvent.getY());
                    } else {
                        this.selPending = new float[]{pageAtDocY, motionEvent.getX(), motionEvent.getY()};
                        requestSelWords(pageAtDocY, false);
                    }
                }
            }
        } catch (Throwable th) {
            CrashGuard.append(this, "selLongPress: " + CrashGuard.stackToString(th));
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void openBookmarks() {
        if (this.bookmarksView == null) {
            LinearLayout linearLayout = new LinearLayout(this);
            linearLayout.setOrientation(1);
            linearLayout.setBackgroundResource(R.drawable.bg_panel);
            linearLayout.setPadding(dp(14), dp(14), dp(14), dp(14));
            LinearLayout linearLayout2 = new LinearLayout(this);
            linearLayout2.setOrientation(0);
            linearLayout2.setGravity(16);
            TextView textView = new TextView(this);
            textView.setText("نشانک\u200cها");
            textView.setTextColor(-1511688);
            textView.setTextSize(15.0f);
            textView.setTypeface(null, 1);
            textView.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));
            ImageButton imageButton = new ImageButton(this);
            imageButton.setImageResource(R.drawable.ic_close);
            imageButton.setBackgroundResource(android.R.color.transparent);
            imageButton.setOnClickListener(new View.OnClickListener() { // from class: com.pdfkhwan.app.ReaderActivity.24
                @Override // android.view.View.OnClickListener
                public void onClick(View view) {
                    ReaderActivity.this.hideAllPanels();
                }
            });
            linearLayout2.addView(textView);
            linearLayout2.addView(imageButton);
            ListView listView = new ListView(this);
            listView.setDivider(null);
            LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(-1, 0, 1.0f);
            layoutParams.topMargin = dp(8);
            listView.setLayoutParams(layoutParams);
            linearLayout.addView(linearLayout2);
            linearLayout.addView(listView);
            ((ViewGroup) findViewById(R.id.pdfView).getParent()).addView(linearLayout, new ViewGroup.LayoutParams(-1, -1));
            this.bookmarksView = linearLayout;
            this.bookmarksAdapter = new BookmarksAdapter(listView);
        }
        this.bookmarksAdapter.reload();
        openPanel(this.bookmarksView);
    }

    /* v7: password prompt for protected PDFs — retried with the entered
       password on both engines (pdfium viewer + pdf.js search/copy).
       v1.29 FIX: the retry count distinguishes a WRONG password from a
       first prompt — the dialog now says the password was incorrect
       instead of showing the identical «این PDF رمز دارد» forever. */
    private int passwordAttempts = 0;

    private void askPassword() {
        /* v1.50: بازطراحی مدرن — قبلاً AlertDialog خامِ سیستم بود (سبک قدیمی)؛
           حالا کارت گرد هم‌شکل بقیهٔ اپ، با دکمه‌های پررنگ/متن. */
        final EditText input = new EditText(this);
        input.setInputType(129); /* textPassword */
        input.setTextColor(Modern.C_TEXT);
        input.setTextSize(15f);
        final Dialog[] dlg = new Dialog[1];
        LinearLayout card = Modern.cardContent(this);
        card.addView(Modern.sheetHeader(this,
                this.passwordAttempts > 0 ? "رمز درست نبود" : "این PDF رمز دارد",
                "برای باز کردن فایل رمز آن را بنویسید", new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                ReaderActivity.this.finish();
            }
        }));
        int m = dp(18);
        input.setPadding(m, dp(10), m, dp(10));
        card.addView(input, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(android.view.Gravity.END);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
        TextView cancel = Modern.filledBtn(this, "بستن", 0x2EFFFFFF, Modern.C_TEXT, new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                ReaderActivity.this.finish();
            }
        });
        btns.addView(cancel, blp);
        TextView ok = Modern.filledBtn(this, "باز کردن", Modern.C_ACCENT, 0xFF0B1220, new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                ReaderActivity.this.docPassword = input.getText().toString();
                ReaderActivity.this.passwordAttempts++;   /* v1.29 */
                /* v1.28 FIX: بندانگشتی‌ها رمز را فقط در سازنده می‌بینند و
                   openFailed برای همیشه قفل می‌شود؛ بدون بازسازی، بعد از
                   بازکردن قفل، پنل بندانگشتی همیشه خالی می‌ماند */
                try {
                    if (ReaderActivity.this.thumbs != null) ReaderActivity.this.thumbs.release();
                } catch (Throwable ignored) {
                }
                ReaderActivity.this.thumbs = new PdfThumbs(ReaderActivity.this,
                        ReaderActivity.this.openUri(), ReaderActivity.this.docPassword);
                ReaderActivity.this.loaded = false;
                ReaderActivity.this.loading.setVisibility(0);
                ReaderActivity.this.openDoc();
            }
        });
        LinearLayout.LayoutParams oklp = new LinearLayout.LayoutParams(-2, -2);
        oklp.setMargins(dp(8), 0, 0, 0);
        btns.addView(ok, oklp);
        LinearLayout.LayoutParams blpRow = new LinearLayout.LayoutParams(-1, -2);
        blpRow.topMargin = dp(10);
        card.addView(btns, blpRow);
        dlg[0] = trackDialog(Modern.showCard(this, card));
    }

    /* ====================================================================
       v1.40: مسیر بازکردن PDF رمزدار/محافظت‌شده
       --------------------------------------------------------------------
       ترتیب: (۱) خود pdfium (RC4/AES-128 را مستقیم باز می‌کند)
              (۲) اگر خطای امنیتی بود → PDFBox با رمزِ فعلی (یا رمز خالی
                   برای فایل‌های فقط-محدودشده) نسخهٔ رمزگشایی‌شده می‌سازد
              (۳) باز/بسته شدن → دوباره openDoc روی نسخهٔ آزاد
       فایل‌های فقط-محدودشده (رمزِ بازکردن ندارند ولی کپی/چاپ قفل است)
       بی‌هیچ دیالوگی باز می‌شوند — و چون نسخهٔ آزاد ساخته می‌شود، کپی
       متن و چاپشان هم آزاد است؛ همان چیزی که اپ‌های دیگر نمی‌توانند. */
    private void tryUnlock() {
        if (this.unlockBusy) return;
        /* v1.40: اگر نسخهٔ رمزگشایی‌شده قبلاً ساخته شده ولی بازکردنش هم
           شکست خورده، دوباره رمزگشایی نکن — حلقهٔ بی‌پایان نمی‌سازیم */
        if (this.decryptedFile != null) {
            showOpenError("باز کردن فایل ممکن نشد.", "decrypted copy failed to open");
            return;
        }
        this.unlockBusy = true;
        final Dialog prog = busyCard("در حال بازکردن فایل محافظت‌شده…");
        final String pw = this.docPassword;
        this.io.execute(new Runnable() {
            @Override public void run() {
                int res = PdfUnlock.R_FAILED;
                File out = null;
                try {
                    String safe = share.shareSafeName().replaceAll("\\.[^.]+$", "");
                    out = new File(getCacheDir(), "unlocked_" + safe + ".pdf");
                    res = PdfUnlock.decryptTo(ReaderActivity.this, ReaderActivity.this.uri, pw, out);
                } catch (Throwable t) {
                    res = PdfUnlock.R_FAILED;
                }
                final int r = res;
                final File fout = out;
                ui.post(new Runnable() {
                    @Override public void run() {
                        try { prog.dismiss(); } catch (Throwable ignored) {}
                        unlockBusy = false;
                        if (r == PdfUnlock.R_OK && fout != null && fout.exists()) {
                            /* موفق — از این به بعد همهٔ موتورها روی نسخهٔ آزاد */
                            decryptedFile = fout;
                            try {
                                if (thumbs != null) thumbs.release();
                            } catch (Throwable ignored) {}
                            thumbs = new PdfThumbs(ReaderActivity.this, openUri(), null);
                            loaded = false;
                            loading.setVisibility(0);
                            openDoc();
                        } else if (r == PdfUnlock.R_NEED_PASSWORD) {
                            askPassword();
                        } else if (r == PdfUnlock.R_WRONG_PASSWORD) {
                            passwordAttempts++;   /* پیام «رمز درست نبود» */
                            askPassword();
                        } else {
                            showOpenError("این فایل یا PDF معتبری نیست، یا قفلی دارد که این نسخه نمی‌تواند باز کند.",
                                    "unlock failed");
                        }
                    }
                });
            }
        });
    }

    /* v1.40: پاک‌سازی نسخه‌های رمزگشایی‌شدهٔ قدیمی (باقی‌مانده از اجرای قبلی) */
    private void cleanupUnlocked() {
        try {
            File[] files = getCacheDir().listFiles();
            if (files == null) return;
            for (File f : files) {
                String n = f.getName();
                if (n.startsWith("unlocked_") && (n.endsWith(".pdf") || n.endsWith(".tmp") || n.endsWith(".src"))) {
                    f.delete();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    void openDoc() {
        try {
            File file = null;
            if (this.decryptedFile != null && this.decryptedFile.exists() && this.decryptedFile.length() > 0) {
                /* v1.40: نسخهٔ رمزگشایی‌شده در حافظهٔ خودِ برنامه است — نه به
                   رمز نیاز دارد نه به مجوز «همهٔ فایل‌ها» */
                file = this.decryptedFile;
            } else if ("file".equals(this.uri.getScheme())) {
                file = new File(this.uri.getPath());
                if (!file.exists() || file.length() == 0) {
                    showOpenError("فایل پیدا نشد یا خالی است.", "path: " + this.uri.getPath());
                    return;
                }
                if (!hasAllFiles()) {
                    askAllFilesPermission();
                    return;
                }
            }
            PDFView.Configurator fromFile = file != null ? this.pdfView.fromFile(file) : this.pdfView.fromUri(this.uri);
            final int optInt = this.store.getPosition(this.fileKey).optInt("page", 1);
            this.lastPage1 = optInt;
            /* v1.38: روش ورق‌زدن — در حالت «ورق‌زدن یک‌صفحه‌ای» هر صفحه باید
               کاملاً داخل صفحه‌ٔ نمایش جا شود (FitPolicy.BOTH) تا واقعاً مثل
               کتاب ورق بخورد؛ در بقیهٔ حالت‌ها همان جا‌شدن عرض قبلی */
            boolean horizMode = this.store.getBool("horiz", false);
            boolean flipMode = horizMode && this.store.getBool("hflip", false);
            /* v1.47: حالت کتاب دوصفحه‌ای — دو صفحه رو‌به‌رو، راست‌به‌چپ */
            boolean bookMode = flipMode && this.store.getBool("book", false);
            fromFile.defaultPage(Math.max(0, optInt - 1))
                    .pageFitPolicy((flipMode || bookMode) ? FitPolicy.BOTH : FitPolicy.WIDTH).fitEachPage(true)
                    /* v1.39: دوبار ضربهٔ سطح ۱ (یک پله بزرگ‌نمایی) همیشه روشن است؛
                       سطح ۲ (پرش تا زوم حداکثری ۸x) جداگانه از منو روشن می‌شود */
                    .enableSwipe(true).enableDoubletap(true)
                    .enableAnnotationRendering(true).enableAntialiasing(true)
                    .spacing(12).autoSpacing(false)
                    .pageGapColor(-12828605)
                    .pageBorder(-6643546, 1.0f)
                    .nightMode(this.night)
                    /* v7: password-protected PDFs + horizontal page mode */
                    .password(this.docPassword)
                    /* v1.37: سه روش ورق‌زدن — عمودی / اسکرول افقی / ورق‌زدن
                       (ورق‌زدن: pageSnap+pageFling فعال؛ DragPinchManager کشیدن
                       و پرتاب را به ورق‌خوردن یک‌صفحه‌ای تبدیل می‌کند) */
                    .swipeHorizontal(horizMode)
                    .pageSnap(flipMode)
                    .pageFling(flipMode)
                    .bookMode(bookMode)
                    .scrollHandle(new DefaultScrollHandle(this)).onLoad(new OnLoadCompleteListener() { // from class: com.pdfkhwan.app.ReaderActivity.2
                @Override // com.github.barteksc.pdfviewer.listener.OnLoadCompleteListener
                public void loadComplete(int i) {
                    ReaderActivity.this.onLoaded(i, optInt);
                }
            }).onPageChange(new OnPageChangeListener() { // from class: com.pdfkhwan.app.ReaderActivity.3
                @Override // com.github.barteksc.pdfviewer.listener.OnPageChangeListener
                public void onPageChanged(int i, int i2) {
                    ReaderActivity.this.onPageChangedUi(i, i2);
                }
            }).onTap(new OnTapListener() { // from class: com.pdfkhwan.app.ReaderActivity.4
                @Override // com.github.barteksc.pdfviewer.listener.OnTapListener
                public boolean onTap(MotionEvent motionEvent) {
                    /* v1.32: جابجایی یادداشت آزاد در انتظار لمس است؟ */
                    if (annDialogs.pendingFnoteMove != null) {
                        annDialogs.moveFnoteTo(motionEvent.getX(), motionEvent.getY());
                        return true;
                    }
                    /* v1.32: یادداشت آزاد در انتظار لمس است؟ همان نقطه را
                       لنگر یادداشت کن */
                    if (annDialogs.pendingFnote) {
                        annDialogs.placeFnoteAt(motionEvent.getX(), motionEvent.getY());
                        return true;
                    }
                    /* v1.46: فرم‌ها و لینک‌های تعاملی */
                    if (forms.formMode
                            && forms.tapForm(motionEvent.getX(), motionEvent.getY())) {
                        return true;
                    }
                    if (links.tapLink(motionEvent.getX(), motionEvent.getY())) {
                        return true;
                    }
                    if (ReaderActivity.this.selPage != null) {
                        ReaderActivity.this.clearSelection();
                        return true;
                    }
                    /* v1.32: اول ببین لمس روی یک حاشیه‌نویسی افتاده یا نه */
                    if (annDraw.tapAnnotation(motionEvent.getX(), motionEvent.getY())) {
                        return true;
                    }
                    ReaderActivity.this.toggleBars();
                    return true;
                }
            }).onLongPress(new OnLongPressListener() { // from class: com.pdfkhwan.app.ReaderActivity.5
                @Override // com.github.barteksc.pdfviewer.listener.OnLongPressListener
                public void onLongPress(MotionEvent motionEvent) {
                    ReaderActivity.this.onPdfLongPress(motionEvent);
                }
            }).onPageError(new OnPageErrorListener() { // from class: com.pdfkhwan.app.ReaderActivity.6
                @Override // com.github.barteksc.pdfviewer.listener.OnPageErrorListener
                public void onPageError(int i, Throwable th) {
                    ReaderActivity.this.toastOnce("صفحهٔ " + Fa.d(i + 1) + " رندر نشد");
                    CrashGuard.append(ReaderActivity.this, "page " + i + ": " + CrashGuard.stackToString(th) + "\n");
                }
            }).onError(new OnErrorListener() { // from class: com.pdfkhwan.app.ReaderActivity.7
                @Override // com.github.barteksc.pdfviewer.listener.OnErrorListener
                public void onError(Throwable th) {
                    ReaderActivity.this.onOpenFailed(th);
                }
            });
            try {
                fromFile.load();
            } catch (Throwable th) {
                onOpenFailed(th);
            }
        } catch (Throwable th2) {
            onOpenFailed(th2);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void openPanel(View view) {
        clearSelection();
        hideAllPanels();
        view.setVisibility(0);
        view.setAlpha(0.0f);
        view.setTranslationY(getResources().getDisplayMetrics().density * 30.0f);
        view.animate().alpha(1.0f).translationY(0.0f).setDuration(180L).start();
    }

    private int pageAtDocY(float f) {
        if (this.pageOff1 == null || this.pageH1 == null) {
            return -1;
        }
        float zoom = this.pdfView.getZoom();
        for (int i = 0; i < this.pageCount; i++) {
            float f2 = this.pageOff1[i] * zoom;
            if (f < f2) {
                return -1;
            }
            if (f < f2 + (this.pageH1[i] * zoom)) {
                return i;
            }
        }
        return -1;
    }

    /* v1.27: page lookup by VIEW point — correct in BOTH page-turn axes.
       Horizontal mode identifies the page along X (pages laid out
       left→right), vertical mode along Y as before. */
    int pageAt(float vx, float vy) {
        if (this.pageOff1 == null || this.pageH1 == null) {
            return -1;
        }
        float zoom = this.pdfView.getZoom();
        if (isHoriz()) {
            if (this.pageOffX1 == null || this.pageW1 == null) {
                return -1;
            }
            float dx = vx - this.pdfView.getCurrentXOffset();
            /* v1.47: حالت کتاب — آفست‌ها معکوس‌چیده‌اند؛ اسکن کامل در برگیرنده */
            if (this.pdfView.isBookRtl()) {
                for (int i = 0; i < this.pageCount; i++) {
                    float x0 = this.pageOffX1[i] * zoom;
                    if (dx >= x0 && dx < x0 + (this.pageW1[i] * zoom)) {
                        return i;
                    }
                }
                return -1;
            }
            for (int i = 0; i < this.pageCount; i++) {
                float x0 = this.pageOffX1[i] * zoom;
                if (dx < x0) {
                    return -1;
                }
                if (dx < x0 + (this.pageW1[i] * zoom)) {
                    return i;
                }
            }
            return -1;
        }
        return pageAtDocY(vy - this.pdfView.getCurrentYOffset());
    }

    /* JADX INFO: Access modifiers changed from: private */
    public RectF pageRect(int i) {
        float zoom = this.pdfView.getZoom();
        SizeF pageSize = this.pdfView.getPageSize(i);
        if (isHoriz()) {
            /* v1.27: horizontal mode — pages stack along X, each centered
               vertically against the tallest page */
            float x = this.pdfView.getCurrentXOffset() + (this.pageOffX1[i] * zoom);
            float y = this.pdfView.getCurrentYOffset() + (((this.maxPageH1 - pageSize.getHeight()) * zoom) / 2.0f);
            return new RectF(x, y, x + (pageSize.getWidth() * zoom), y + (pageSize.getHeight() * zoom));
        }
        float currentXOffset = this.pdfView.getCurrentXOffset() + (((this.maxPageW1 - pageSize.getWidth()) * zoom) / 2.0f);
        float currentYOffset = this.pdfView.getCurrentYOffset() + (this.pageOff1[i] * zoom);
        return new RectF(currentXOffset, currentYOffset, (pageSize.getWidth() * zoom) + currentXOffset, (zoom * pageSize.getHeight()) + currentYOffset);
    }

    private void placeTip(View view, float f, float f2, boolean z) {
        float dp = dp(24);
        view.setTranslationX(f - (dp / 2.0f));
        float dp2 = dp(2);
        view.setTranslationY(z ? (f2 + dp2) - dp : f2 - dp2);
    }


    /* JADX INFO: Access modifiers changed from: private */
    public void positionHandles() {
        int i;
        if (this.selPage == null || this.selStart < 0 || (i = this.selPageIdx) < 0 || this.pageOff1 == null) {
            return;
        }
        try {
            RectF pageRect = pageRect(i);
            if (pageRect.width() > 0.0f && pageRect.height() > 0.0f) {
                float width = pageRect.width() / this.selPage.vw;
                float height = pageRect.height() / this.selPage.vh;
                SelWord selWord = this.selPage.words.get(this.selStart);
                SelWord selWord2 = this.selPage.words.get(this.selEnd);
                /* v1.41: handles sit at the exact CHARACTER boundary — the
                   start handle at the reading-first edge of the first selected
                   char, the end handle at the reading-last edge of the last
                   selected char (letter-level selection). */
                int maxS = (selWord.cs != null && selWord.cs.length > 0) ? Math.min(selWord.t.length(), selWord.cs.length) - 1 : Math.max(0, selWord.t.length() - 1);
                int maxE = (selWord2.cs != null && selWord2.cs.length > 0) ? Math.min(selWord2.t.length(), selWord2.cs.length) - 1 : Math.max(0, selWord2.t.length() - 1);
                int sc = Math.max(0, Math.min(maxS, this.selStartChar));
                int ec = Math.max(0, Math.min(maxE, this.selEndChar));
                float f, f2;
                if (selWord.cs != null && selWord.cs.length > sc) {
                    boolean startRtl = charEdgeRtl(selWord.cs[sc], selWord.rtl);
                    f = startRtl ? Math.max(selWord.cx0[sc], selWord.cx1[sc]) : Math.min(selWord.cx0[sc], selWord.cx1[sc]);
                } else {
                    f = selWord.rtl ? selWord.x1 : selWord.x0;
                }
                if (selWord2.cs != null && selWord2.cs.length > ec) {
                    boolean endRtl = charEdgeRtl(selWord2.cs[ec], selWord2.rtl);
                    f2 = endRtl ? Math.min(selWord2.cx0[ec], selWord2.cx1[ec]) : Math.max(selWord2.cx0[ec], selWord2.cx1[ec]);
                } else {
                    f2 = selWord2.rtl ? selWord2.x0 : selWord2.x1;
                }
                placeTip(this.selHandleStart, pageRect.left + (f * width), pageRect.top + (selWord.y0 * height), true);
                placeTip(this.selHandleEnd, pageRect.left + (f2 * width), pageRect.top + (selWord2.y1 * height), false);
            }
        } catch (Throwable unused) {
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void positionPopup() {
        if (this.selPage == null || this.selStart < 0 || this.selPageIdx < 0) {
            return;
        }
        try {
            RectF selectionBounds = selectionBounds();
            this.selPopup.measure(0, 0);
            int measuredWidth = this.selPopup.getMeasuredWidth();
            int measuredHeight = this.selPopup.getMeasuredHeight();
            int max = Math.max(dp(8), Math.min((int) (selectionBounds.centerX() - (measuredWidth / 2.0f)), (((ViewGroup) this.selPopup.getParent()).getWidth() - measuredWidth) - dp(8)));
            int dp = (int) ((selectionBounds.top - measuredHeight) - dp(12));
            View findViewById = findViewById(R.id.topBar);
            if (dp < (findViewById.getVisibility() == 0 ? findViewById.getBottom() + dp(8) : dp(8))) {
                dp = (int) (selectionBounds.bottom + dp(12));
            }
            /* v1.28 FIX: no bottom clamp — a selection near the bottom of the
               screen pushed the popup out of view and the copy/share buttons
               became unreachable until the user scrolled */
            ViewParent vp = this.selPopup.getParent();
            if (vp instanceof View) {
                int maxBottom = ((View) vp).getHeight() - measuredHeight - dp(8);
                if (dp > maxBottom) dp = Math.max(dp(8), maxBottom);
            }
            this.selPopup.setTranslationX(max);
            this.selPopup.setTranslationY(dp);
        } catch (Throwable unused) {
        }
    }

    private String[] queryMeta(Uri uri) {
        String str = "document.pdf";
        long j = 0;
        if ("file".equals(uri.getScheme())) {
            try {
                File file = new File(uri.getPath());
                if (file.getName() != null && file.getName().length() > 0) {
                    str = file.getName();
                }
                j = file.length();
            } catch (Exception unused) {
            }
            return new String[]{str, String.valueOf(j)};
        }
        try {
            Cursor query = getContentResolver().query(uri, null, null, null, null);
            if (query != null) {
                try {
                    int columnIndex = query.getColumnIndex("_display_name");
                    int columnIndex2 = query.getColumnIndex("_size");
                    if (query.moveToFirst()) {
                        if (columnIndex >= 0 && query.getString(columnIndex) != null) {
                            str = query.getString(columnIndex);
                        }
                        if (columnIndex2 >= 0 && !query.isNull(columnIndex2)) {
                            j = query.getLong(columnIndex2);
                        }
                    }
                    query.close();
                } catch (Throwable th) {
                    query.close();
                    throw th;
                }
            }
        } catch (Exception unused2) {
        }
        return new String[]{str, String.valueOf(j)};
    }

    private String readText(File file) {
        /* v1.28 FIX: fd leak — the reader was never closed (runs on every
           search/selection prep; leaked descriptors could exhaust the
           process fd table on long sessions) */
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(file));
            return br.readLine();
        } catch (Exception unused) {
            return "";
        } finally {
            if (br != null) {
                try { br.close(); } catch (Exception ignored) {}
            }
        }
    }

    void requestSelWords(int i, boolean z) {
        /* v1.27 FIX: the FIRST long-press / read-aloud copied the WHOLE PDF
           on the UI thread (searchCopyUrl()) — a 100–300 MB book froze the
           app for seconds (ANR dialog). The copy now runs on the background
           I/O executor like runSearch() already does. */
        if (!this.webReady) {
            /* v1.27 FIX: silent no-op → the user now learns WHY selection,
               search and read-aloud do nothing (engine not ready) */
            if (!z) toast("موتور متن هنوز آماده نیست — کمی صبر کن");
            /* v1.29 FIX: an armed ttsPendingPage / selPending used to survive
               this bail — minutes later, an UNRELATED requestSelWords for the
               same page (a long-press, a search jump, the onPageChanged
               prefetch) delivered the words and triggered a SURPRISE
               read-aloud or a stale selection popup with no user action.
               Disarm both whenever the request cannot be dispatched. */
            if (tts.ttsPendingPage == i) tts.ttsPendingPage = -1;
            this.selPending = null;
            return;
        }
        try {
            final int page = i;
            final boolean quiet = z;
            if (this.selPrepPending) {
                /* v1.28 FIX: صف به‌جای حذف — درخواست بعد از پایان آماده‌سازی
                   در جریان، دوباره اجرا می‌شود */
                this.selPrepQueuedPage = i;
                this.selPrepQueuedQuiet = z;
                return;
            }
            this.selPrepPending = true;
            long now = System.currentTimeMillis();
            final boolean showPrepToast = !quiet && now - this.selPrepToastAt > 2500;
            if (showPrepToast) this.selPrepToastAt = now;
            this.io.execute(new Runnable() {
                @Override
                public void run() {
                    final String copyUrl;
                    try {
                        copyUrl = ReaderActivity.this.searchCopyUrl();
                    } catch (final Exception e) {
                        ReaderActivity.this.ui.post(new Runnable() {
                            @Override
                            public void run() {
                                ReaderActivity.this.finishSelPrep();
                                if (!quiet) toast("آماده\u200cسازی متن ممکن نشد");
                            }
                        });
                        return;
                    }
                    ReaderActivity.this.ui.post(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                ReaderActivity.this.finishSelPrep();
                                ReaderActivity.this.searchWeb.evaluateJavascript("requestWords(" + JSONObject.quote(copyUrl) + ", " + (page + 1) + ", "
                                        + (ReaderActivity.this.docPassword != null ? JSONObject.quote(ReaderActivity.this.docPassword) : "null") + ")", null);
                                ReaderActivity.this.selEngineOn = true;
                                if (showPrepToast) toast("در حال آماده\u200cسازی متن صفحه…");
                            } catch (Throwable unused) {
                                if (!quiet) toast("آماده\u200cسازی متن ممکن نشد");
                            }
                        }
                    });
                }
            });
        } catch (Throwable unused) {
            this.finishSelPrep();
            if (!z) toast("آماده\u200cسازی متن ممکن نشد");
        }
    }

    /* v1.28: پایان آماده‌سازی در جریان + پخش درخواست صف‌شده (اگر هست) */
    private void finishSelPrep() {
        this.selPrepPending = false;
        if (this.selPrepQueuedPage >= 0) {
            int p = this.selPrepQueuedPage;
            boolean q = this.selPrepQueuedQuiet;
            this.selPrepQueuedPage = -1;
            this.selPrepQueuedQuiet = false;
            requestSelWords(p, q);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void runSearch() {
        final String trim = this.searchInput.getText().toString().trim();
        if (trim.length() == 0 || !this.webReady) {
            return;
        }
        this.lastQuery = trim;
        if (this.searching) {
            /* v1.16 FIX: the IME action listener could fire together with the
               search button — two startSearch() calls raced inside the WebView
               and produced interleaved/duplicated results. */
            return;
        }
        this.hits.clear();
        this.snippets.clear();
        this.resultsAdapter.notifyDataSetChanged();
        this.searchStatus.setVisibility(0);
        this.searchStatus.setText("آماده\u200cسازی… (کپی فایل برای موتور جستجو)");
        findViewById(R.id.btnDoSearch).setEnabled(false);
        this.searching = true;
        this.ui.postDelayed(new Runnable() { // from class: com.pdfkhwan.app.ReaderActivity.31
            @Override // java.lang.Runnable
            public void run() {
                /* v1.16 FIX: searchCopyUrl() copies the whole PDF — it used to
                   run on the UI thread (jank/ANR on large files). The copy now
                   runs on the background I/O thread; the engine starts on the
                   UI thread once the cached copy is ready. */
                ReaderActivity.this.io.execute(new Runnable() {
                    @Override // java.lang.Runnable
                    public void run() {
                        try {
                            final String searchCopyUrl = ReaderActivity.this.searchCopyUrl();
                            ReaderActivity.this.ui.post(new Runnable() {
                                @Override // java.lang.Runnable
                                public void run() {
                                    try {
                                        int max = Math.max(1, ReaderActivity.this.getCurrentPage1());
                                        ReaderActivity.this.searchWeb.evaluateJavascript("startSearch(" + JSONObject.quote(searchCopyUrl) + ", " + JSONObject.quote(trim) + ", " + max + ", "
                                                + (ReaderActivity.this.docPassword != null ? JSONObject.quote(ReaderActivity.this.docPassword) : "null") + ")", null);
                                        TextView textView = ReaderActivity.this.searchStatus;
                                        StringBuilder sb = new StringBuilder("در حال جستجو در کل فایل…");
                                        textView.setText(sb.toString());
                                    } catch (Exception unused) {
                                        ReaderActivity.this.onSearchError("شروع جستجو ممکن نشد");
                                    }
                                }
                            });
                        } catch (Exception unused) {
                            ReaderActivity.this.ui.post(new Runnable() {
                                @Override // java.lang.Runnable
                                public void run() {
                                    ReaderActivity.this.onSearchError("خواندن فایل ممکن نشد");
                                }
                            });
                        }
                    }
                });
            }
        }, 60L);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public String searchCopyUrl() throws Exception {
        File file = new File(getCacheDir(), "search_copy.pdf");
        File file2 = new File(getCacheDir(), "search_copy.uri");
        /* v1.40: سند مؤثر (نسخهٔ رمزگشایی‌شده اگر هست) — کلید کش همان است */
        String str = String.valueOf(openUri().toString()) + ":" + this.docSize;
        if ((file.exists() && file.length() != 0 && file2.exists() && str.equals(readText(file2))) ? false : true) {
            InputStream openInputStream = getContentResolver().openInputStream(openUri());
            try {
                FileOutputStream fileOutputStream = new FileOutputStream(file);
                try {
                    byte[] bArr = new byte[65536];
                    while (true) {
                        int read = openInputStream.read(bArr);
                        if (read <= 0) {
                            break;
                        }
                        fileOutputStream.write(bArr, 0, read);
                    }
                } finally {
                    /* v1.16 FIX: streams leaked when read/write threw — the old
                       code closed them only on the success path. */
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
            writeText(file2, str);
        }
        return "file://" + file.getAbsolutePath();
    }

    /* v1.52: mid-x of the k-th char box (view coords) — used for RTL-aware
       char comparison during handle drags. Falls back to the word box when
       per-char boxes are missing. */
    static float charMidX(SelWord w, int k) {
        if (w == null) return 0f;
        int n = w.cs == null ? 0 : w.cs.length;
        int kk = Math.max(0, Math.min(n - 1, k));
        if (n > 0 && kk >= 0 && kk < n) {
            return (w.cx0[kk] + w.cx1[kk]) * 0.5f;
        }
        return (w.x0 + w.x1) * 0.5f;
    }

    String selText() {
        if (this.selPage == null || this.selStart < 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        /* v1.14: build the selected text using the gap/br flags that
           were computed by computeWords() in VISUAL order. We never
           insert a fake space inside a word again — the gap flag is
           already 1 when two boxes belong to the same word (kerning
           offsets inside a Persian word), and 0 when there is a real
           visual space between them. Line breaks are encoded by br=1
           on the first word of each new line.
           v1.16: a line break now copies as a real '\n' (Xodo/Adobe keep
           paragraph structure on paste) instead of a flat space.
           v1.41: letter-level — the boundary words contribute only the
           selected char range; a tight-ZWNJ separator (zg=1) copies as
           نیم‌فاصله instead of a space. */
        /* (verified v1.52: wire gap semantics unchanged — gap==0 means a
           separator precedes this word, gap==1 means glued to it; the
           engine sets gap=(cut||sepPending)?0:1.) */
        for (int i = this.selStart; i <= this.selEnd && i < this.selPage.words.size(); i++) {
            SelWord w = this.selPage.words.get(i);
            if (i > this.selStart) {
                if (w.br == 1) {
                    sb.append('\n');
                } else if (w.gap == 0) {
                    sb.append(w.zg == 1 ? '\u200C' : ' ');
                }
                /* gap == 1: glued to the previous word, no space */
            }
            String t = w.t;
            int from = (i == this.selStart) ? Math.max(0, this.selStartChar) : 0;
            int to = (i == this.selEnd) ? Math.min(t.length(), this.selEndChar + 1) : t.length();
            if (from > to) from = 0;
            if (i == this.selStart && i == this.selEnd && from > to) { from = 0; to = t.length(); }
            sb.append(t.substring(Math.min(from, t.length()), Math.max(0, to)));
        }
        return sb.toString();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void selectAllSel() {
        if (this.selPage == null) {
            return;
        }
        this.selStart = 0;
        this.selStartChar = 0;
        this.selEnd = this.selPage.words.size() - 1;
        this.selEndChar = Math.max(0, this.selPage.words.get(this.selEnd).t.length() - 1);
        this.selOverlay.invalidate();
        positionHandles();
        positionPopup();
    }

    private RectF selectionBounds() {
        RectF pageRect = pageRect(this.selPageIdx);
        float width = pageRect.width() / this.selPage.vw;
        float height = pageRect.height() / this.selPage.vh;
        RectF rectF = null;
        for (int i = this.selStart; i <= this.selEnd && i < this.selPage.words.size(); i++) {
            SelWord selWord = this.selPage.words.get(i);
            /* v1.41: boundary words contribute only the selected char range */
            float wx0 = selWord.x0, wx1 = selWord.x1;
            if (selWord.cs != null && selWord.cs.length > 0 && (i == this.selStart || i == this.selEnd)) {
                int maxIdx = selWord.cs.length - 1;
                int fromC = (i == this.selStart) ? Math.max(0, Math.min(maxIdx, this.selStartChar)) : 0;
                int toC = (i == this.selEnd) ? Math.max(0, Math.min(maxIdx, this.selEndChar)) : maxIdx;
                if (fromC > toC) { int tmpC = fromC; fromC = toC; toC = tmpC; }
                float bx0 = Float.MAX_VALUE, bx1 = -Float.MAX_VALUE;
                for (int k = fromC; k <= toC; k++) {
                    float cLo = Math.min(selWord.cx0[k], selWord.cx1[k]);
                    float cHi = Math.max(selWord.cx0[k], selWord.cx1[k]);
                    if (cLo < bx0) bx0 = cLo;
                    if (cHi > bx1) bx1 = cHi;
                }
                if (bx0 <= bx1) {
                    wx0 = bx0;
                    wx1 = bx1;
                }
            }
            RectF rectF2 = new RectF(pageRect.left + (wx0 * width), pageRect.top + (selWord.y0 * height), pageRect.left + (wx1 * width), pageRect.top + (selWord.y1 * height));
            if (rectF == null) {
                rectF = rectF2;
            } else {
                rectF.union(rectF2);
            }
        }
        return rectF == null ? pageRect : rectF;
    }

    private void setupBars() {
        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() { // from class: com.pdfkhwan.app.ReaderActivity.12
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                ReaderActivity.this.finish();
            }
        });
        findViewById(R.id.btnSearch).setOnClickListener(new View.OnClickListener() { // from class: com.pdfkhwan.app.ReaderActivity.13
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                ReaderActivity readerActivity = ReaderActivity.this;
                readerActivity.openPanel(readerActivity.searchPanel);
            }
        });
        findViewById(R.id.btnToc).setOnClickListener(new View.OnClickListener() { // from class: com.pdfkhwan.app.ReaderActivity.14
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                ReaderActivity readerActivity = ReaderActivity.this;
                readerActivity.openPanel(readerActivity.tocPanel);
                if (!ReaderActivity.this.tocItems.isEmpty() || ReaderActivity.this.tocLoading) {
                    return;
                }
                ReaderActivity.this.loadTocNative();
            }
        });
        findViewById(R.id.btnThumbs).setOnClickListener(new View.OnClickListener() { // from class: com.pdfkhwan.app.ReaderActivity.15
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                ReaderActivity readerActivity = ReaderActivity.this;
                readerActivity.openPanel(readerActivity.thumbsPanel);
                ReaderActivity.this.thumbAdapter.notifyDataSetChanged();
                if (ReaderActivity.this.pageCount > 0) {
                    ReaderActivity.this.thumbsGrid.smoothScrollToPosition(Math.max(0, ReaderActivity.this.getCurrentPage1() - 2));
                }
            }
        });
        /* v1.33: \u062f\u06a9\u0645\u0647\u0654 \u0647\u0627\u0628 \u062d\u0627\u0634\u06cc\u0647\u200c\u0646\u0648\u06cc\u0633\u06cc */
        findViewById(R.id.btnAnnotate).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                annDialogs.annHubSheet();
            }
        });
        /* v1.37: دکمهٔ مستقیم «حالت رسم و قلم» کنار جستجو — از داخل منوی
           حاشیه‌نویسی بیرون آمد تا دسترسی سریع‌تر باشد */
        pen.penTopBtn = (ImageView) findViewById(R.id.btnPen);
        findViewById(R.id.btnPen).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (pen.penTool != 0) {
                    pen.setPenTool(0);
                    ReaderActivity.this.toast("از حالت رسم خارج شدی");
                } else {
                    pen.setPenTool(pen.lastPenTool <= 0 ? 1 : pen.lastPenTool);
                }
            }
        });
        pen.refreshTopPenButton();
        findViewById(R.id.btnMenu).setOnClickListener(new View.OnClickListener() { // from class: com.pdfkhwan.app.ReaderActivity.16
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                ReaderActivity.this.showMenu(view);
            }
        });
        findViewById(R.id.btnSearchClose).setOnClickListener(new View.OnClickListener() { // from class: com.pdfkhwan.app.ReaderActivity.17
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                ReaderActivity.this.closePanels();
            }
        });
        findViewById(R.id.btnTocClose).setOnClickListener(new View.OnClickListener() { // from class: com.pdfkhwan.app.ReaderActivity.18
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                ReaderActivity.this.closePanels();
            }
        });
        findViewById(R.id.btnThumbsClose).setOnClickListener(new View.OnClickListener() { // from class: com.pdfkhwan.app.ReaderActivity.19
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                ReaderActivity.this.closePanels();
            }
        });
        findViewById(R.id.btnSearchCancel).setOnClickListener(new View.OnClickListener() { // from class: com.pdfkhwan.app.ReaderActivity.20
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                ReaderActivity.this.searchWeb.evaluateJavascript("cancelSearch()", null);
            }
        });
        findViewById(R.id.btnDoSearch).setOnClickListener(new View.OnClickListener() { // from class: com.pdfkhwan.app.ReaderActivity.21
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                ReaderActivity.this.runSearch();
            }
        });
        this.searchInput.setOnEditorActionListener(new TextView.OnEditorActionListener() { // from class: com.pdfkhwan.app.ReaderActivity.22
            @Override // android.widget.TextView.OnEditorActionListener
            public boolean onEditorAction(TextView textView, int i, KeyEvent keyEvent) {
                ReaderActivity.this.runSearch();
                return true;
            }
        });
    }

    private void setupSearchWeb() {
        WebSettings settings = this.searchWeb.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                settings.setForceDark(0);
            } catch (Throwable unused) {
            }
        }
        this.searchWeb.setBackgroundColor(0);
        this.searchWeb.addJavascriptInterface(new SearchBridge(this), "AndroidSearch");
        this.searchWeb.setWebViewClient(new WebViewClient() { // from class: com.pdfkhwan.app.ReaderActivity.33
            @Override // android.webkit.WebViewClient
            public void onPageFinished(WebView webView, String str) {
            }

            /* v1.27 FIX: when the WebView renderer dies (OOM on huge files,
               system kill) the engine silently went dead — search, selection
               and read-aloud all became silent no-ops with no explanation.
               Mark the engine dead and tell the user. */
            @Override // android.webkit.WebViewClient
            public boolean onRenderProcessGone(WebView webView, android.webkit.RenderProcessGoneDetail detail) {
                /* v1.29 FIX: re-enable the search button (runSearch() disabled
                   it and only onSearchDone/onSearchError re-enabled — a
                   renderer death mid-search left it permanently disabled) and
                   drop the stale selection/TTS arming so a later unrelated
                   request cannot resurrect either. */
                ReaderActivity.this.webReady = false;
                ReaderActivity.this.searching = false;
                tts.ttsPendingPage = -1;
                ReaderActivity.this.selPending = null;
                try {
                    android.view.View sb = findViewById(R.id.btnDoSearch);
                    if (sb != null) sb.setEnabled(true);
                } catch (Throwable unused) {
                }
                toast("موتور متن از دست رفت — برنامه را دوباره باز کن");
                return true;   /* we consumed the crash; the app keeps running */
            }
        });
        this.searchWeb.loadUrl("file:///android_asset/pdfjs/search.html");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void showMenu(View view) {
        mainHubSheet();
    }

    /* ================== v1.37: منوی اصلی — جمع‌وجور و غیرتمام‌صفحه ==================
       خواستهٔ کاربر: منو کوچک باشد مثل استاندارد همهٔ اپ‌ها، فاصله بین
       قابلیت‌ها کم شود، توضیح زیر هر مورد حذف شود، و هیچ‌وقت کل صفحه
       را نگیرد (سقف ۶۸٪ ارتفاع + اسکرول در صورت لزوم).
       حذف‌شده‌ها: اشتراک تصویر صفحه، تمام‌صفحه، بزرگ‌نمایی ۱۰۰٪،
       خواندن با صدا، نمایش آدرس (ادغام در اطلاعات فایل). */
    private void mainHubSheet() {
        /* آمار حاشیه‌نویسی */
        int nHl = 0, nNote = 0, nStroke = 0;
        for (int ai = 0; ai < this.anns.items.size(); ai++) {
            Annotations.Ann a = this.anns.items.get(ai);
            if (a.type == Annotations.T_NOTE || a.type == Annotations.T_FNOTE) nNote++;
            else if (a.type == Annotations.T_STROKE) nStroke++;
            else nHl++;
        }
        final int nAnn = nHl + nNote + nStroke;
        int nBm = 0;
        try { nBm = this.store.getBookmarks(this.fileKey).length(); } catch (Throwable ignored) {}
        final int fnBm = nBm;
        final boolean hasBm = this.store.hasBookmark(this.fileKey, getCurrentPage1());

        final Dialog[] dlg = new Dialog[1];
        LinearLayout sheet = Modern.sheetContent(this);
        /* v1.43: خواستهٔ کاربر — سرصفحهٔ «منو + نام فایل» و چیپ‌های آمار حذف شد تا
           منو جمع‌وجورتر شود؛ بستن با لمس بیرون یا کشیدن به پایین. */
        /* v1.43: در گوشی افقی ردیف‌ها دوستونه می‌شوند تا منو بدون اسکرول طولانی
           جا شود (تبلت/عمودی تک‌ستونه مثل قبل). */
        final boolean twoCol = getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        final ArrayList<View> rows = new ArrayList<>();
        final ArrayList<String> secs = new ArrayList<>();
        /* v1.48 FIX: مرز بخش‌ها به‌صورت پویا ثبت می‌شود — فهرست ثابت
           قبلی (HUB_SECTION_ROWS) با اضافه‌شدن گزینه‌ها کهنه شده بود و
           آخرین ردیف تنظیمات را جا می‌انداخت. */
        final ArrayList<Integer> secStart = new ArrayList<>();

        /* ================= بخش: صفحه ================= */
        secs.add("صفحه");
        secStart.add(rows.size());
        rows.add(Modern.compactRow(this, R.drawable.ic_m_bookmark, hasBm ? Modern.C_ACCENT : 0xFF8DA0BC,
                hasBm ? "حذف نشانک این صفحه" : "نشانک‌گذاری این صفحه",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                        store.toggleBookmark(fileKey, getCurrentPage1());
                        toast(store.hasBookmark(fileKey, getCurrentPage1()) ? "نشانک ثبت شد ✓" : "نشانک حذف شد");
                    }
                }));
        rows.add(Modern.compactRow(this, R.drawable.ic_m_list, 0xFF64B5F6, "فهرست نشانک‌ها",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                        openBookmarks();
                    }
                }));
        rows.add(Modern.compactRow(this, R.drawable.ic_m_goto, 0xFFBA68C8, "رفتن به صفحه…",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                        gotoPageDialog();
                    }
                }));

        /* ================= بخش: حاشیه‌نویسی ================= */
        secs.add("حاشیه‌نویسی");
        secStart.add(rows.size());
        rows.add(Modern.compactRow(this, R.drawable.ic_m_ann, Modern.C_ACCENT, "حاشیه‌نویسی این فایل",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                        annDialogs.annHubSheet();
                    }
                }));

        /* ================= بخش: صدا (v1.47 — بخش ۱۹ تا ۲۱) ================= */
        secs.add("صدا");
        secStart.add(rows.size());
        rows.add(Modern.compactRow(this, R.drawable.ic_m_speak, 0xFF4DD0E1,
                tts.ttsReading ? "خواندن با صدا (در حال پخش…)" : "خواندن با صدا…",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                        tts.ttsSheet();
                    }
                }));
        rows.add(Modern.compactRow(this, R.drawable.ic_m_vol, 0xFFBCAAA4, "مدل‌های صوتی…",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                        try {
                            startActivity(new Intent(ReaderActivity.this, VoiceModelsActivity.class));
                        } catch (Throwable t) {
                            toast("صفحهٔ مدل‌ها باز نشد");
                        }
                    }
                }));

        /* ================= بخش: نمایش ================= */
        secs.add("نمایش");
        secStart.add(rows.size());
        rows.add(Modern.compactToggle(this, R.drawable.ic_m_moon, 0xFF9575CD, "حالت شب", this.night,
                new Modern.Toggle.OnChange() {
                    @Override public void onChange(boolean on) {
                        night = on;
                        try { pdfView.setNightMode(night); } catch (Throwable unused) {}
                        store.setBool("night", night);
                    }
                }));
        rows.add(Modern.compactRow(this, R.drawable.ic_m_flip, 0xFF4DB6AC, "ورق‌زدن صفحات",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                        display.pageModeDialog();
                    }
                }));
        /* v1.47: بخش ۱۸ — صفحه‌آرایی (چرخش/کاغذ/روشنایی/اسکرول خودکار/بزرگ‌نمایی) */
        rows.add(Modern.compactRow(this, R.drawable.ic_m_full, 0xFFFFB74D, "صفحه‌آرایی و کاغذ…",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                        display.displaySheet();
                    }
                }));

        /* ================= بخش: تنظیمات ================= */
        secs.add("تنظیمات");
        secStart.add(rows.size());
        rows.add(Modern.compactToggle(this, R.drawable.ic_m_bulb, 0xFFFFD54F, "روشن ماندن صفحه", this.keepScreenOn,
                new Modern.Toggle.OnChange() {
                    @Override public void onChange(boolean on) {
                        keepScreenOn = on;
                        store.setBool("keepOn", keepScreenOn);
                        display.applyKeepScreenOn();
                    }
                }));
        rows.add(Modern.compactToggle(this, R.drawable.ic_m_vol, 0xFFBCAAA4, "ورق‌زدن با کلید ولوم",
                this.store.getBool("volKeys", true),
                new Modern.Toggle.OnChange() {
                    @Override public void onChange(boolean on) {
                        store.setBool("volKeys", on);
                    }
                }));

        /* v1.39: زوم حداکثری با دو ضربه — مدل دو سطحی کلاسیک:
           سطح ۱ (همیشه روشن): دوبار ضربه در زوم پایه ← یک پله بزرگ‌نمایی (۲۵۰٪)
           حول محل لمس؛ همان رفتار خوبی که از قبل بود.
           سطح ۲ (این کلید؛ پیش‌فرض خاموش): در حال زوم، دوبار ضربهٔ بعدی تا
           زوم حداکثری ۸x می‌رود؛ خاموش باشد، همان‌جا به ۱۰۰٪ برمی‌گردد. */
        rows.add(Modern.compactToggle(this, R.drawable.ic_m_zoom, 0xFF4FC3F7, "زوم حداکثری با دو ضربه",
                this.store.getBool("dblTapMax", false),
                new Modern.Toggle.OnChange() {
                    @Override public void onChange(boolean on) {
                        store.setBool("dblTapMax", on);
                        try { pdfView.setDoubleTapMax(on); } catch (Throwable ignored) {}
                        toast(on ? "دو ضربهٔ دوم: تا زوم حداکثری ۸x" : "دو ضربهٔ دوم: بازگشت به ۱۰۰٪");
                    }
                }));

        /* ================= بخش: فایل ================= */
        secs.add("فایل");
        secStart.add(rows.size());
        rows.add(Modern.compactRow(this, R.drawable.ic_m_info, 0xFF90A4AE, "اطلاعات و مسیر فایل",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                        fileInfo();
                    }
                }));
        rows.add(Modern.compactRow(this, R.drawable.ic_ann_share, 0xFF64B5F6, "اشتراک‌گذاری و چاپ…",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                        share.shareSheet();
                    }
                }));
        /* v1.45: عملیات صفحه — چرخش/حذف/استخراج/جابجایی/ادغام/تقسیم */
        rows.add(Modern.compactRow(this, R.drawable.ic_pages, 0xFF4DD0E1, "عملیات صفحه…",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                        pageOps.pageOpsSheet();
                    }
                }));
        /* v1.46: پر کردن فرم — فقط وقتی فایل فرم AcroForm دارد */
        if (this.pdfInter != null && this.pdfInter.hasForms) {
            rows.add(Modern.compactRow(this, R.drawable.ic_ann_check, 0xFFFFD54F,
                    forms.formMode ? "بستن حالت فرم" : "پر کردن فرم…",
                    new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                            if (forms.formMode) forms.formStop();
                            else forms.formStart();
                        }
                    }));
        }

        /* چیدمان: هر بخش با برچسبش؛ ردیف‌ها در گوشی افقی دوستونه */
        int ri = 0;
        for (int si = 0; si < secs.size(); si++) {
            int end = (si + 1 < secStart.size()) ? secStart.get(si + 1) : rows.size();
            sheet.addView(Modern.sectionLabelCompact(this, secs.get(si)));
            int from = ri;
            if (twoCol) {
                for (int a = from; a < end; a += 2) {
                    LinearLayout pair = new LinearLayout(this);
                    pair.setOrientation(LinearLayout.HORIZONTAL);
                    LinearLayout.LayoutParams p0 = new LinearLayout.LayoutParams(0, -2, 1f);
                    p0.setMarginEnd(dp(4));
                    pair.addView(rows.get(a), p0);
                    if (a + 1 < end) {
                        LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(0, -2, 1f);
                        p1.setMarginStart(dp(4));
                        pair.addView(rows.get(a + 1), p1);
                    } else {
                        View pad = new View(this);
                        pair.addView(pad, new LinearLayout.LayoutParams(0, -2, 1f));
                    }
                    sheet.addView(pair, new LinearLayout.LayoutParams(-1, -2));
                }
                ri = end;
            } else {
                while (ri < end) {
                    sheet.addView(rows.get(ri), new LinearLayout.LayoutParams(-1, -2));
                    ri++;
                }
            }
        }

        LinearLayout pad = new LinearLayout(this);
        sheet.addView(pad, new LinearLayout.LayoutParams(-1, dp(10)));
        /* منو هرگز تمام‌صفحه نمی‌شود — سقف ۶۸٪ ارتفاع صفحه */
        int cap = Math.round(getResources().getDisplayMetrics().heightPixels * 0.86f);
        /* v1.48: با چرخش گوشی، منوی باز هم‌زمان با چیدمان تازه بازمی‌گردد */
        dlg[0] = trackSheet(Modern.showSheetCapped(this, sheet, cap), new Runnable() {
            @Override public void run() { mainHubSheet(); }
        });
    }

    /* چیپ کوچک آمار برای هاب منو */
    private void hubChip(LinearLayout parent, int dotColor, String label) {
        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(android.view.Gravity.CENTER_VERTICAL);
        chip.setBackground(Modern.pillStroke(0x14FFFFFF, Modern.C_BORDER, this));
        int cp = dp(12);
        chip.setPadding(cp, dp(7), cp, dp(7));
        View d = new View(this);
        d.setBackground(Modern.circle(dotColor, this));
        chip.addView(d, new LinearLayout.LayoutParams(dp(9), dp(9)));
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(Modern.C_MUTED);
        t.setTextSize(12f);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.setMarginStart(dp(7));
        chip.addView(t, tlp);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
        clp.setMarginEnd(dp(8));
        parent.addView(chip, clp);
    }

    /* ================== v1.37: روش ورق‌زدن صفحات ==================
       سه حالت — انتخاب دائمی است (تا وقتی کاربر عوضش کند):
       ۱) اسکرول عمودی (پیش‌فرض)
       ۲) اسکرول افقی پیوسته (مثل قبلاً)
       ۳) ورق‌زدن افقی — اسکرول نمی‌شود؛ هر کشیدن/پرتاب یک صفحه ورق می‌خورد */
    /* ================== v1.38: روش ورق‌زدن صفحات — بازطراحی ==================
       سه حالت دائمی، هرکدام با آیکون اختصاصی و توضیح یک‌خطی روشن:
       ۱) اسکرول عمودی (پیش‌فرض) — صفحات پشت‌سرهم مثل نوار طولانی
       ۲) اسکرول افقی پیوسته — همان نوار ولی در جهت چپ/راست
       ۳) ورق‌زدن افقی یک‌صفحه‌ای — هر صفحه کامل داخل صفحه جا می‌شود
          (v1.38: هر صفحه با FitPolicy.BOTH کامل دیده می‌شود، کشیدن با
          بازخشت کشویی دنبال می‌شود و رهاکردن/پرتاب یک صفحه ورق می‌خورد) */
    LinearLayout modeOption(final Dialog[] dlg, int icon, int iconTint, String title, String sub,
                                    boolean selected, final boolean horiz, final boolean flip, final boolean book) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int p = dp(12);
        row.setPadding(p, dp(8), p, dp(8));
        LinearLayout ibg = new LinearLayout(this);
        ibg.setOrientation(LinearLayout.VERTICAL);
        ibg.setGravity(android.view.Gravity.CENTER);
        ibg.setBackground(Modern.round(iconTint & 0x2EFFFFFF, 12, this));
        row.addView(ibg, new LinearLayout.LayoutParams(dp(38), dp(38)));
        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        iv.setColorFilter(iconTint);
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(21), dp(21)));
        ibg.addView(iv);
        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1f);
        tlp.setMarginStart(dp(13));
        txt.setLayoutParams(tlp);
        TextView t1 = new TextView(this);
        t1.setText(title);
        t1.setTextColor(Modern.C_TEXT);
        t1.setTextSize(14.5f);
        t1.setTypeface(null, android.graphics.Typeface.BOLD);
        txt.addView(t1);
        TextView t2 = new TextView(this);
        t2.setText(sub);
        t2.setTextColor(Modern.C_MUTED);
        t2.setTextSize(11.5f);
        t2.setPadding(0, dp(2), 0, 0);
        txt.addView(t2);
        row.addView(txt);
        row.addView(Modern.radioDot(this, selected));
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                store.setBool("horiz", horiz);
                store.setBool("hflip", flip);
                store.setBool("book", book);
                try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                display.applyPageModeReload();
            }
        });
        row.setBackground(Modern.rippleRound(0x00000000, 14, Modern.C_RIPPLE, this));
        return row;
    }

    class FormOverlay extends View {
        private final Paint boxOn = new Paint(1);
        private final Paint boxOff = new Paint(1);
        private final Paint boxFocus = new Paint(1);
        private final Paint fillP = new Paint(1);
        private final Paint txtP = new Paint(1);
        private final Paint hintP = new Paint(1);
        private final Paint markP = new Paint(1);
        private final Paint dotP = new Paint(1);

        FormOverlay(Context context) {
            super(context);
            boxOn.setStyle(Paint.Style.STROKE);
            boxOn.setColor(0xCC2DD4BF);
            boxOn.setStrokeWidth(dp(1) + 0.4f);
            boxOff.setStyle(Paint.Style.STROKE);
            boxOff.setColor(0x802DD4BF);
            boxOff.setStrokeWidth(dp(1) + 0.2f);
            boxOff.setPathEffect(new android.graphics.DashPathEffect(new float[]{dp(6), dp(5)}, 0));
            boxFocus.setStyle(Paint.Style.STROKE);
            boxFocus.setColor(0xFFFFD54F);
            boxFocus.setStrokeWidth(dp(2) + 0.2f);
            fillP.setStyle(Paint.Style.FILL);
            fillP.setColor(0x1A2DD4BF);
            txtP.setColor(0xFF0F766E);
            hintP.setColor(0x990F766E);
            markP.setStyle(Paint.Style.STROKE);
            markP.setColor(0xFF0F766E);
            markP.setStrokeWidth(dp(2));
            markP.setStrokeCap(Paint.Cap.ROUND);
            dotP.setStyle(Paint.Style.FILL);
            dotP.setColor(0xFF0F766E);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            try {
                if (!forms.formMode || ReaderActivity.this.pdfInter == null) return;
                int cw = getWidth(), chh = getHeight();
                int n = ReaderActivity.this.pdfInter.fields.size();
                float cull = dp(30);
                for (int i = 0; i < n; i++) {
                    FormFiller.Field f = ReaderActivity.this.pdfInter.fields.get(i);
                    if (f.page < 0 || f.page >= ReaderActivity.this.pageCount) continue;
                    RectF pr = ReaderActivity.this.pageRect(f.page);
                    if (pr.width() <= 1f || pr.height() <= 1f) continue;
                    float l = pr.left + f.fx0 * pr.width();
                    float t = pr.top + f.fy0 * pr.height();
                    float r = pr.left + f.fx1 * pr.width();
                    float b = pr.top + f.fy1 * pr.height();
                    if (r - l < dp(11)) {
                        float cx = (l + r) / 2f;
                        l = cx - dp(6);
                        r = cx + dp(6);
                    }
                    if (b - t < dp(11)) {
                        float cy = (t + b) / 2f;
                        t = cy - dp(6);
                        b = cy + dp(6);
                    }
                    if (r < -cull || l > cw + cull || b < -cull || t > chh + cull) continue;

                    String v = forms.displayFormValue(f);
                    boolean filled = v.length() > 0;
                    if (filled) {
                        canvas.drawRoundRect(l, t, r, b, dp(4), dp(4), fillP);
                    }
                    Paint stroke = i == forms.formFocus ? boxFocus : (filled ? boxOn : boxOff);
                    canvas.drawRoundRect(l, t, r, b, dp(4), dp(4), stroke);

                    if (f.type == FormFiller.F_CHECK) {
                        if (filled) {
                            float cx = (l + r) / 2f, cy = (t + b) / 2f;
                            float s = Math.min(r - l, b - t) * 0.22f;
                            canvas.drawLine(cx - s, cy, cx - s * 0.15f, cy + s, markP);
                            canvas.drawLine(cx - s * 0.15f, cy + s, cx + s, cy - s, markP);
                        }
                    } else if (f.type == FormFiller.F_RADIO) {
                        if (filled) {
                            float cx = (l + r) / 2f, cy = (t + b) / 2f;
                            canvas.drawCircle(cx, cy, Math.min(r - l, b - t) * 0.2f, dotP);
                        }
                    } else if (f.type == FormFiller.F_SIGN) {
                        drawOvText(canvas, filled ? "امضا شد" : "محل امضا",
                                l, t, r, b, filled ? txtP : hintP, f.rtl, true);
                    } else {
                        String s = filled ? v : (f.type == FormFiller.F_TEXT ? "تایپ…" : "انتخاب…");
                        drawOvText(canvas, s, l, t, r, b, filled ? txtP : hintP, f.rtl, false);
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        private void drawOvText(Canvas canvas, String s, float l, float t, float r, float b,
                                Paint p, boolean rtl, boolean center) {
            if (s == null || s.length() == 0) return;
            float hAvail = (b - t) - dp(4);
            float size = Math.max(9f, Math.min(dp(15), hAvail * 0.62f));
            p.setTextSize(size);
            float maxW = (r - l) - dp(10);
            if (maxW > 8f) {
                while (size > 8f && p.measureText(s) > maxW) {
                    size -= 1f;
                    p.setTextSize(size);
                }
            }
            p.setTextAlign(center ? Paint.Align.CENTER : (rtl ? Paint.Align.RIGHT : Paint.Align.LEFT));
            float x = center ? (l + r) / 2f : (rtl ? r - dp(5) : l + dp(5));
            float cy = (t + b) / 2f - (p.ascent() + p.descent()) / 2f;
            canvas.save();
            canvas.clipRect(l, t - dp(1), r, b + dp(1));
            canvas.drawText(s, x, cy, p);
            canvas.restore();
        }
    }

    /* پد امضا — جمع‌آوری خطوط دستی */
    class SignPad extends View {
        private final java.util.ArrayList<float[]> strokes = new java.util.ArrayList<float[]>();
        private final java.util.ArrayList<Float> cur = new java.util.ArrayList<Float>();
        private final Paint pen = new Paint(1);

        SignPad(Context c) {
            super(c);
            pen.setStyle(Paint.Style.STROKE);
            pen.setStrokeWidth(dp(2) + 0.6f);
            pen.setColor(0xFF16213E);
            pen.setStrokeCap(Paint.Cap.ROUND);
            pen.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        public boolean onTouchEvent(android.view.MotionEvent ev) {
            try {
                switch (ev.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        cur.clear();
                        cur.add(ev.getX());
                        cur.add(ev.getY());
                        invalidate();
                        return true;
                    case android.view.MotionEvent.ACTION_MOVE:
                        cur.add(ev.getX());
                        cur.add(ev.getY());
                        invalidate();
                        return true;
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        if (cur.size() >= 4) {
                            float[] a = new float[cur.size()];
                            for (int i = 0; i < a.length; i++) a[i] = cur.get(i);
                            strokes.add(a);
                        }
                        cur.clear();
                        invalidate();
                        return true;
                }
            } catch (Throwable ignored) {
            }
            return true;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            try {
                for (float[] s : strokes) {
                    if (s == null || s.length < 4) continue;
                    android.graphics.Path pa = new android.graphics.Path();
                    pa.moveTo(s[0], s[1]);
                    for (int i = 2; i + 1 < s.length; i += 2) pa.lineTo(s[i], s[i + 1]);
                    canvas.drawPath(pa, pen);
                }
                if (cur.size() >= 4) {
                    android.graphics.Path pa = new android.graphics.Path();
                    pa.moveTo(cur.get(0), cur.get(1));
                    for (int i = 2; i + 1 < cur.size(); i += 2) pa.lineTo(cur.get(i), cur.get(i + 1));
                    canvas.drawPath(pa, pen);
                }
            } catch (Throwable ignored) {
            }
        }

        void clearPad() {
            strokes.clear();
            cur.clear();
            invalidate();
        }

        boolean hasInk() {
            return !strokes.isEmpty();
        }

        java.util.ArrayList<float[]> ink() {
            return strokes;
        }
    }

    /* ================= v1.46: لینک‌های تعاملی ================= */

    Dialog busyCard(String title) {
        final Dialog[] d = new Dialog[1];
        LinearLayout card = Modern.cardContent(this);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(Modern.C_TEXT);
        t.setTextSize(14f);
        t.setGravity(android.view.Gravity.CENTER);
        card.addView(t, new LinearLayout.LayoutParams(-1, -2));
        final TextView sub = new TextView(this);
        sub.setTag("sub");
        sub.setTextColor(Modern.C_MUTED);
        sub.setTextSize(12f);
        sub.setGravity(android.view.Gravity.CENTER);
        sub.setPadding(0, dp(6), 0, 0);
        card.addView(sub, new LinearLayout.LayoutParams(-1, -2));
        android.widget.ProgressBar pb = new android.widget.ProgressBar(this);
        pb.setIndeterminate(true);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-2, -2);
        plp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        plp.topMargin = dp(12);
        card.addView(pb, plp);
        d[0] = trackDialog(Modern.showCardAuto(this, card));
        return d[0];
    }

    void busyCardText(Dialog d, String text) {
        try {
            if (d != null) {
                TextView sub = (TextView) d.getWindow().getDecorView().findViewWithTag("sub");
                if (sub != null) sub.setText(text);
            }
        } catch (Throwable ignored) {
        }
    }

    /* بازسازی صفحات انتخابی → اشتراک */
    interface PageFileOp {
        File run(File src, int[] pages) throws Exception;
    }

    static final int REQ_MERGE_PICK = 4761;

    private TextView zoomBadge;
    private long lastBadgeAt = 0;

    private void showZoomHundredBadge() {
        try {
            long now = android.os.SystemClock.uptimeMillis();
            if (now - this.lastBadgeAt < 250) return;
            this.lastBadgeAt = now;
            if (this.zoomBadge == null) {
                this.zoomBadge = new TextView(this);
                this.zoomBadge.setText("۱۰۰٪");
                this.zoomBadge.setTextColor(0xFF0B1220);
                this.zoomBadge.setTextSize(12f);
                this.zoomBadge.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                this.zoomBadge.setBackground(Modern.pill(Modern.C_ACCENT, this));
                int h = dp(12);
                this.zoomBadge.setPadding(h, dp(4), h, dp(4));
                this.zoomBadge.setElevation(dp(8));
                ViewGroup root = (ViewGroup) findViewById(R.id.pdfView).getParent();
                android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(-2, -2,
                        android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL);
                lp.bottomMargin = dp(96);
                root.addView(this.zoomBadge, lp);
            }
            this.zoomBadge.setVisibility(0);
            this.zoomBadge.setAlpha(0f);
            this.zoomBadge.animate().alpha(1f).setDuration(90)
                    .withEndAction(new Runnable() {
                        @Override public void run() {
                            zoomBadge.animate().alpha(0f).setStartDelay(600).setDuration(240)
                                    .withEndAction(new Runnable() {
                                        @Override public void run() {
                                            zoomBadge.setVisibility(4);
                                        }
                                    }).start();
                        }
                    }).start();
            /* لگ سبک */
            try {
                Object v = getSystemService(VIBRATOR_SERVICE);
                if (v instanceof android.os.Vibrator) {
                    ((android.os.Vibrator) v).vibrate(16);
                }
            } catch (Throwable ignoredV) {
            }
        } catch (Throwable ignored) {
        }
    }


    private void showOpenError(String str, final String str2) {
        /* v1.50: بازطراحی مدرن — کارت هم‌شکل اپ به‌جای AlertDialog خام */
        try {
            final Dialog[] dlg = new Dialog[1];
            LinearLayout card = Modern.cardContent(this);
            card.addView(Modern.sheetHeader(this, "باز کردن ممکن نشد", null, null));
            TextView msg = new TextView(this);
            msg.setText(String.valueOf(str) + "\n\nجزئیات: " + str2);
            msg.setTextColor(Modern.C_TEXT);
            msg.setTextSize(13f);
            int m = dp(18);
            msg.setPadding(m, dp(6), m, dp(8));
            card.addView(msg, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout btns = new LinearLayout(this);
            btns.setOrientation(LinearLayout.HORIZONTAL);
            btns.setGravity(android.view.Gravity.END);
            TextView close = Modern.filledBtn(this, "بستن", 0x2EFFFFFF, Modern.C_TEXT, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                    ReaderActivity.this.finish();
                }
            });
            btns.addView(close, new LinearLayout.LayoutParams(-2, -2));
            TextView grant = Modern.filledBtn(this, "دادن دسترسی", 0x2EFFFFFF, Modern.C_TEXT, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                    try {
                        ReaderActivity.this.startActivity(new Intent("android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION", Uri.parse("package:" + ReaderActivity.this.getPackageName())));
                    } catch (Exception unused) {
                        try {
                            ReaderActivity.this.startActivity(new Intent("android.settings.MANAGE_ALL_FILES_ACCESS_PERMISSION"));
                        } catch (Exception unused2) {
                        }
                    }
                    ReaderActivity.this.finish();
                }
            });
            LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-2, -2);
            glp.setMargins(dp(8), 0, 0, 0);
            btns.addView(grant, glp);
            TextView copy = Modern.filledBtn(this, "کپی جزئیات", Modern.C_ACCENT, 0xFF0B1220, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                    try {
                        ((ClipboardManager) ReaderActivity.this.getSystemService("clipboard")).setPrimaryClip(ClipData.newPlainText("pdfkhwan", str2));
                        ReaderActivity.this.toast("کپی شد ✓");
                    } catch (Exception unused) {
                    }
                    ReaderActivity.this.finish();
                }
            });
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
            clp.setMargins(dp(8), 0, 0, 0);
            btns.addView(copy, clp);
            LinearLayout.LayoutParams blpRow = new LinearLayout.LayoutParams(-1, -2);
            blpRow.topMargin = dp(10);
            card.addView(btns, blpRow);
            dlg[0] = trackDialog(Modern.showCard(this, card));
        } catch (Throwable unused) {
            finish();
        }
    }

    private void showSelectionUi() {
        initSelectionUi();
        this.selOverlay.setVisibility(0);
        this.selHandleStart.setVisibility(0);
        this.selHandleEnd.setVisibility(0);
        this.selOverlay.invalidate();
        positionHandles();
        positionPopup();
        this.selPopup.setVisibility(0);
        startGeomWatch();
    }

    void startGeomWatch() {
        if (this.geomWatchRunning) {
            return;
        }
        this.geomWatchRunning = true;
        this.ui.post(this.geomWatch);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void toast(String str) {
        Toast.makeText(this, str, 0).show();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void toastOnce(String str) {
        long currentTimeMillis = System.currentTimeMillis();
        if (currentTimeMillis - this.lastPageErrorToast > 3000) {
            this.lastPageErrorToast = currentTimeMillis;
            toast(str);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void toggleBars() {
        this.barsVisible = !this.barsVisible;
        /* v1.16 FIX: the old code set visibility IMMEDIATELY and animated
           alpha from the CURRENT value — both directions defeated the fade
           (hide: the bar vanished instantly; show: alpha was already 1 so
           the animation did nothing). Now: show = reveal then fade 0→1,
           hide = fade 1→0 then GONE, alpha restored for the next show. */
        if (this.barsVisible) {
            this.topBar.setAlpha(0.0f);
            this.topBar.setVisibility(0);
            this.topBar.animate().alpha(1.0f).setDuration(160L).start();
        } else {
            this.topBar.animate().alpha(0.0f).setDuration(160L).withEndAction(new Runnable() {
                @Override // java.lang.Runnable
                public void run() {
                    ReaderActivity.this.topBar.setVisibility(4);
                    ReaderActivity.this.topBar.setAlpha(1.0f);
                }
            }).start();
        }
    }

    int wordAt(SelPage selPage, float f, float f2) {
        float f3 = Float.MAX_VALUE;
        int i = -1;
        for (int i2 = 0; i2 < selPage.words.size(); i2++) {
            SelWord selWord = selPage.words.get(i2);
            float f4 = selWord.y1 - selWord.y0;
            if (f4 <= 0.0f) {
                f4 = Math.max(1.0f, Math.abs(selWord.y1 + selWord.y0) * 0.01f);
            }
            float f5 = 0.18f * f4;
            if (f >= selWord.x0 - f5 && f <= selWord.x1 + f5) {
                float f6 = f4 * 0.25f;
                if (f2 >= selWord.y0 - f6 && f2 <= selWord.y1 + f6) {
                    float f7 = ((selWord.x0 + selWord.x1) * 0.5f) - f;
                    float f8 = ((selWord.y0 + selWord.y1) * 0.5f) - f2;
                    float f9 = (f7 * f7) + (f8 * f8);
                    if (f9 < f3) {
                        i = i2;
                        f3 = f9;
                    }
                }
            }
        }
        if (i >= 0) {
            return i;
        }
        for (int i3 = 0; i3 < selPage.words.size(); i3++) {
            SelWord selWord2 = selPage.words.get(i3);
            float f10 = ((selWord2.x0 + selWord2.x1) * 0.5f) - f;
            float f11 = ((selWord2.y0 + selWord2.y1) * 0.5f) - f2;
            float f12 = (f10 * f10) + (f11 * f11);
            if (f12 < f3) {
                i = i3;
                f3 = f12;
            }
        }
        if (i < 0) {
            return -1;
        }
        SelWord selWord3 = selPage.words.get(i);
        float f13 = (selWord3.y1 - selWord3.y0) * 1.4f;
        if (f3 <= f13 * f13) {
            return i;
        }
        return -1;
    }

    private void writeText(File file, String str) {
        FileWriter fw = null;
        try {
            fw = new FileWriter(file);
            fw.write(str);   /* v1.28 FIX: leak on write() failure */
        } catch (Exception unused) {
        } finally {
            if (fw != null) {
                try { fw.close(); } catch (Exception ignored) {}
            }
        }
    }

    /* v1.32: نتیجهٔ انتخاب مقصد خروجی (JSON/متن) یا فایل ورودی پشتیبان */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        /* v1.45: ادغام — چند فایل انتخاب شد (EXTRA_ALLOW_MULTIPLE) */
        if (requestCode == REQ_MERGE_PICK) {
            if (resultCode != -1 || data == null) return;
            java.util.ArrayList<Uri> picked = new java.util.ArrayList<Uri>();
            try {
                android.content.ClipData cd = data.getClipData();
                if (cd != null) {
                    for (int i = 0; i < cd.getItemCount(); i++) {
                        Uri u = cd.getItemAt(i).getUri();
                        if (u != null) picked.add(u);
                    }
                } else if (data.getData() != null) {
                    picked.add(data.getData());
                }
            } catch (Throwable ignored) {
            }
            pageOps.mergeWith(picked);
            return;
        }
        if (resultCode != -1 || data == null || data.getData() == null) return;
        android.net.Uri uri = data.getData();
        if (requestCode == 401) {
            /* خروجی JSON همهٔ حاشیه‌نویسی‌ها */
            try {
                java.io.OutputStream os = getContentResolver().openOutputStream(uri, "wt");
                os.write(this.anns.toJson().getBytes("UTF-8"));
                os.close();
                toast("خروجی حاشیه‌نویسی\u200cها ذخیره شد ✓");
            } catch (Exception e) {
                toast("ذخیره ممکن نشد: " + e.getMessage());
            }
        } else if (requestCode == 402) {
            /* خروجی متنی یادداشت‌ها */
            try {
                java.io.OutputStream os = getContentResolver().openOutputStream(uri, "wt");
                os.write(this.anns.notesAsText().getBytes("UTF-8"));
                os.close();
                toast("خروجی یادداشت\u200cها ذخیره شد ✓");
            } catch (Exception e) {
                toast("ذخیره ممکن نشد: " + e.getMessage());
            }
        } else if (requestCode == 403) {
            /* ورود از فایل پشتیبان JSON */
            try {
                java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(getContentResolver().openInputStream(uri), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
                r.close();
                Annotations imported = new Annotations();
                imported.fromJson(sb.toString());
                int added = this.anns.mergeFrom(imported);
                this.anns.save();
                annDraw.annInvalidate();
                toast(added > 0 ? Fa.d(added) + " حاشیه\u200cنویسی وارد شد ✓"
                        : "چیز جدیدی برای ورود نبود (تکراری‌ها رد شدند)");
            } catch (Exception e) {
                toast("خواندن فایل ممکن نشد: " + e.getMessage());
            }
        }
    }

    /* ====================================================================
       ==================  v1.32: سیستم حاشیه‌نویسی  ======================
       ==================================================================== */

    /* v1.40: نشانی مؤثر سند — اگر نسخهٔ رمزگشایی‌شده ساخته شده باشد، همان؛
       وگرنه فایل اصلی. همهٔ موتورها (بندانگشتی/فهرست/جستجو/کپی/چاپ/
       خروجی) از این مسیر می‌خوانند */
    Uri openUri() {
        if (this.decryptedFile != null && this.decryptedFile.exists()) {
            return Uri.fromFile(this.decryptedFile);
        }
        return this.uri;
    }

    /* رنگ هایلایت فعلی از پالت */
    static final PorterDuffXfermode MARKER_XFER =
            new PorterDuffXfermode(PorterDuff.Mode.MULTIPLY);

    /* v1.41: جوهرِ ثابت به کاغذ — ضخامت خط/ماژیک با بزرگ‌نمایی «صفحه»
       مقیاس می‌شود تا همان خط در هر زومی نسبت به متن همان‌قدر دیده
       شود (WYSIWYG مثل Xodo/Samsung Notes). قبلاً ضخامت dp ثابت بود و
       در زوم این خط موئی و در زوم اوت پهن و مزاحم دیده می‌شد. */
    static float ptSegDist(float px, float py, float x0, float y0, float x1, float y1) {
        float dx = x1 - x0, dy = y1 - y0;
        float len2 = (dx * dx) + (dy * dy);
        if (len2 < 0.0001f) {
            float ex = px - x0, ey = py - y0;
            return (float) Math.sqrt((ex * ex) + (ey * ey));
        }
        float t = (((px - x0) * dx) + ((py - y0) * dy)) / len2;
        t = Math.max(0f, Math.min(1f, t));
        float cx = x0 + (t * dx), cy = y0 + (t * dy);
        float ex = px - cx, ey = py - cy;
        return (float) Math.sqrt((ex * ex) + (ey * ey));
    }

    /* ================== پنجرهٔ عملیات یک حاشیه‌نویسی ================== */

    class DrawOverlay extends View {
        /* خط در حال کشیده‌شدن — مختصات نمایش */
        private final ArrayList<float[]> cur = new ArrayList<>();
        private int curPage = -1;
        private float ax0, ay0, ax1, ay1;
        private boolean arrowDrag = false;
        private final Paint p = new Paint(1);
        private final Path hp = new Path();
        /* v1.37: ژست دو‌انگشتی */
        private boolean twoFinger = false;
        private float lastPinchDist = 0;
        private float lastMidX = 0, lastMidY = 0;

        DrawOverlay(Context context) {
            super(context);
            setWillNotDraw(false);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            try {
                float cw = getWidth(), ch = getHeight();
                /* v1.38: خطوط ذخیره‌شده داخل پاس رسم PDFView کشیده می‌شوند
                   (با مولتی‌پلای برای ماژیک) — اینجا فقط «خط در جریان» به‌صورت
                   پیش‌نمایش رسم می‌شود؛ پیش‌نمایش ماژیک با ته‌رنگ نیمه‌شفاف
                   به نتیجهٔ نهایی (مولتی‌پلای) نزدیک‌ترین حالت ممکن است */
                if (!cur.isEmpty()) {
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeJoin(Paint.Join.ROUND);
                    p.setStrokeCap(Paint.Cap.ROUND);
                    /* v1.41: پیش‌نمایش هم مثل جوهر نهایی با زوم مقیاس می‌شود
                       (WYSIWYG) — ضخامت ذخیره‌شده بر حسب کاغذ است */
                    p.setStrokeWidth(annDraw.strokeScreenW(pen.penWidthDp));
                    int pvCol = pen.penColor | 0xFF000000;
                    if (pen.penTool == 2) {
                        pvCol = (pvCol & 0x00FFFFFF) | 0x59000000;
                    }
                    p.setColor(pvCol);
                    hp.reset();
                    hp.moveTo(cur.get(0)[0], cur.get(0)[1]);
                    for (int i = 1; i < cur.size(); i++) {
                        hp.lineTo(cur.get(i)[0], cur.get(i)[1]);
                    }
                    canvas.drawPath(hp, p);
                }
                if (arrowDrag) {
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeJoin(Paint.Join.ROUND);
                    p.setStrokeCap(Paint.Cap.ROUND);
                    p.setStrokeWidth(annDraw.strokeScreenW(pen.penWidthDp));
                    p.setColor(pen.penColor | 0xFF000000);
                    canvas.drawLine(ax0, ay0, ax1, ay1, p);
                    annDraw.drawArrowHead(canvas, ax0, ay0, ax1, ay1, p);
                }
            } catch (Throwable ignored) {
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            /* v1.37: حالت دست → لمس آزاد به pdfView می‌رسد (اسکرول/زوم) */
            if (pen.penTool == 0 || pen.handMode) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    twoFinger = false;
                    lastPinchDist = 0;
                    curPage = pageAt(e.getX(), e.getY());
                    if (curPage < 0 || pageCount <= 0) return false;
                    if (pen.penTool == 3) {
                        eraseAt(e.getX(), e.getY());
                        return true;
                    }
                    if (pen.penTool == 4) {
                        ax0 = ax1 = e.getX();
                        ay0 = ay1 = e.getY();
                        arrowDrag = true;
                        invalidate();
                        return true;
                    }
                    cur.clear();
                    addCur(e.getX(), e.getY());
                    invalidate();
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                    /* v1.37: دو انگشت → خط در جریان لغو؛ زوم/اسکرول دو‌انگشتی */
                    if (e.getPointerCount() >= 2) {
                        cancelInProgress();
                        twoFinger = true;
                        lastPinchDist = 0;
                        return true;
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (twoFinger) {
                        pinchMove(e);
                        return true;
                    }
                    if (curPage < 0) return true;
                    if (pen.penTool == 3) {
                        eraseAt(e.getX(), e.getY());
                        return true;
                    }
                    if (pen.penTool == 4) {
                        ax1 = e.getX();
                        ay1 = e.getY();
                        invalidate();
                        return true;
                    }
                    addCur(e.getX(), e.getY());
                    invalidate();
                    return true;
                case MotionEvent.ACTION_POINTER_UP:
                    /* تا برداشته‌شدن همهٔ انگشتان در حالت دو‌انگشتی می‌مانیم —
                       تک‌انگشت باقی‌مانده نباید ناگهان خط بکشد */
                    if (twoFinger) return true;
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (twoFinger) {
                        twoFinger = false;
                        lastPinchDist = 0;
                        pen.histEraseCommit(); /* v1.44: اگر وسط پاک‌کردن دو‌انگشتی شد، عمل بسته شود */
                        try {
                            anns.save();
                            pdfView.loadPages();
                        } catch (Throwable ignored) {
                        }
                        return true;
                    }
                    if (curPage >= 0) {
                        if (pen.penTool == 3) {
                            pen.histEraseCommit(); /* v1.44: کل حرکت پاک‌کن = یک عمل واگرد */
                            anns.save();
                            annDraw.annInvalidate();
                        } else if (pen.penTool == 4 && arrowDrag) {
                            commitArrow();
                        } else if (!cur.isEmpty()) {
                            commitStroke();
                        }
                    }
                    cur.clear();
                    arrowDrag = false;
                    curPage = -1;
                    invalidate();
                    return true;
            }
            return false;
        }

        /* v1.37: لغو خط/فلش در جریان هنگام شروع ژست دو‌انگشتی */
        private void cancelInProgress() {
            cur.clear();
            arrowDrag = false;
            invalidate();
        }

        /* v1.37: زوم/اسکرول دو‌انگشتی داخل حالت رسم — با قفل زوم، فقط
           اسکرول می‌ماند. فاصلهٔ انگشت‌ها زوم می‌کند و جابه‌جایی نقطهٔ
           میانی صفحه را دنبال می‌کند (مثل اپ‌های پیشرفته). */
        private void pinchMove(MotionEvent e) {
            if (e.getPointerCount() < 2) return;
            try {
                float dx = e.getX(0) - e.getX(1);
                float dy = e.getY(0) - e.getY(1);
                float dist = (float) Math.sqrt((dx * dx) + (dy * dy));
                float midX = (e.getX(0) + e.getX(1)) / 2f;
                float midY = (e.getY(0) + e.getY(1)) / 2f;
                if (lastPinchDist > 1f) {
                    if (!pen.zoomLocked) {
                        float dr = dist / lastPinchDist;
                        if (dr > 2f) dr = 2f;
                        if (dr < 0.5f) dr = 0.5f;
                        float target = pdfView.getZoom() * dr;
                        if (target < 0.5f) dr = 0.5f / pdfView.getZoom();
                        if (target > 8f) dr = 8f / pdfView.getZoom();
                        pdfView.zoomCenteredRelativeTo(dr,
                                new android.graphics.PointF(midX, midY));
                    }
                    pdfView.moveRelativeTo(midX - lastMidX, midY - lastMidY);
                    pdfView.loadPageByOffset();
                }
                lastPinchDist = dist;
                lastMidX = midX;
                lastMidY = midY;
            } catch (Throwable ignored) {
            }
        }

        private void addCur(float x, float y) {
            if (!cur.isEmpty()) {
                float[] last = cur.get(cur.size() - 1);
                float dx = x - last[0], dy = y - last[1];
                if ((dx * dx) + (dy * dy) < 4f) return; /* نویز لمس */
            }
            cur.add(new float[]{x, y});
            /* سقف نقاط برای ایمنی حافظه */
            if (cur.size() > 4000) cur.remove(0);
        }

        private void commitStroke() {
            if (cur.size() < 2) return;
            RectF pr = pageRect(curPage);
            if (pr.width() <= 1f) return;
            Annotations.Ann a = new Annotations.Ann();
            a.type = Annotations.T_STROKE;
            a.page = curPage;
            a.color = pen.penColor | 0xFF000000;
            a.strokeW = pen.penWidthDp;
            a.marker = (pen.penTool == 2);
            a.px = new float[cur.size()];
            a.py = new float[cur.size()];
            for (int i = 0; i < cur.size(); i++) {
                a.px[i] = Math.max(-0.05f, Math.min(1.05f, (cur.get(i)[0] - pr.left) / pr.width()));
                a.py[i] = Math.max(-0.05f, Math.min(1.05f, (cur.get(i)[1] - pr.top) / pr.height()));
            }
            if (anns.add(a) != null) {
                anns.save();
                /* v1.44: خط تازه روی تاریخچه — بازانجام بی‌اعتبار می‌شود */
                HistOp op = new HistOp();
                op.changes.add(new AnnChange(a.id, null, a.copy()));
                pen.histPush(op);
            }
            annDraw.annInvalidate();
        }

        private void commitArrow() {
            RectF pr = pageRect(curPage);
            if (pr.width() <= 1f) return;
            Annotations.Ann a = new Annotations.Ann();
            a.type = Annotations.T_STROKE;
            a.page = curPage;
            a.color = pen.penColor | 0xFF000000;
            a.strokeW = pen.penWidthDp;
            a.arrow = true;
            a.px = new float[2];
            a.py = new float[2];
            a.px[0] = Math.max(0f, Math.min(1f, (ax0 - pr.left) / pr.width()));
            a.py[0] = Math.max(0f, Math.min(1f, (ay0 - pr.top) / pr.height()));
            a.px[1] = Math.max(0f, Math.min(1f, (ax1 - pr.left) / pr.width()));
            a.py[1] = Math.max(0f, Math.min(1f, (ay1 - pr.top) / pr.height()));
            if (anns.add(a) != null) {
                anns.save();
                /* v1.44: فلش هم روی تاریخچه */
                HistOp op = new HistOp();
                op.changes.add(new AnnChange(a.id, null, a.copy()));
                pen.histPush(op);
            }
            annDraw.annInvalidate();
        }

        /* پاک‌کن: v1.37 دو حالت — «کل خط» (هر خطی که لمس رویش بیفتد حذف
           می‌شود) یا «نقطه‌ای» (فقط نقاط داخل شعاع پاک‌کن حذف می‌شوند و
           خط به چند تکه شکسته می‌شود) */
        private void eraseAt(float vx, float vy) {
            RectF pr = pageRect(curPage);
            if (pr.width() <= 1f) return;
            if (pen.eraserPartial) {
                erasePartial(vx, vy, pr);
                return;
            }
            float thr = dp(18);
            boolean removed = false;
            for (int i = anns.items.size() - 1; i >= 0; i--) {
                Annotations.Ann a = anns.items.get(i);
                if (a.type != Annotations.T_STROKE || a.page != curPage || a.px == null) continue;
                for (int k = 1; k < a.px.length; k++) {
                    float sx0 = pr.left + (a.px[k - 1] * pr.width());
                    float sy0 = pr.top + (a.py[k - 1] * pr.height());
                    float sx1 = pr.left + (a.px[k] * pr.width());
                    float sy1 = pr.top + (a.py[k] * pr.height());
                    if (ptSegDist(vx, vy, sx0, sy0, sx1, sy1) <= thr) {
                        /* v1.44: پاک‌کردن کل خط هم واگرد‌شدنی */
                        pen.histErasePut(a.id, pen.histEraseBefore(a), null);
                        anns.remove(a.id);
                        removed = true;
                        break;
                    }
                }
            }
            if (removed) invalidate();
        }

        /* v1.37: پاک‌کردن نقطه‌ای — نقاط داخل شعاع حذف؛ خط به تکه‌های
           مجاور شکسته می‌شود؛ اگر همهٔ نقاط پاک شد خط کامل حذف می‌شود.
           فلش (۲ نقطه): لمس هر کدام از دو سر، کل فلش را پاک می‌کند. */
        private void erasePartial(float vx, float vy, RectF pr) {
            float thr = pen.eraserSizeDp * getResources().getDisplayMetrics().density;
            float thr2 = thr * thr;
            boolean changed = false;
            for (int i = anns.items.size() - 1; i >= 0; i--) {
                Annotations.Ann a = anns.items.get(i);
                if (a.type != Annotations.T_STROKE || a.page != curPage || a.px == null) continue;

                if (a.arrow && a.px.length == 2) {
                    float x0 = pr.left + (a.px[0] * pr.width());
                    float y0 = pr.top + (a.py[0] * pr.height());
                    float x1 = pr.left + (a.px[1] * pr.width());
                    float y1 = pr.top + (a.py[1] * pr.height());
                    if (((vx - x0) * (vx - x0)) + ((vy - y0) * (vy - y0)) <= thr2
                            || ((vx - x1) * (vx - x1)) + ((vy - y1) * (vy - y1)) <= thr2) {
                        /* v1.44: فلش پاک‌شده هم واگرد‌شدنی */
                        pen.histErasePut(a.id, pen.histEraseBefore(a), null);
                        anns.remove(a.id);
                        changed = true;
                    }
                    continue;
                }

                /* تقسیم خط به تکه‌های سالم */
                ArrayList<ArrayList<Float>> runsX = new ArrayList<>();
                ArrayList<ArrayList<Float>> runsY = new ArrayList<>();
                ArrayList<Float> rx = null, ry = null;
                boolean anyHit = false;
                for (int k = 0; k < a.px.length; k++) {
                    float sx = pr.left + (a.px[k] * pr.width());
                    float sy = pr.top + (a.py[k] * pr.height());
                    if (((vx - sx) * (vx - sx)) + ((vy - sy) * (vy - sy)) <= thr2) {
                        anyHit = true;
                        rx = null;
                        continue;
                    }
                    if (rx == null) {
                        rx = new ArrayList<>();
                        ry = new ArrayList<>();
                        runsX.add(rx);
                        runsY.add(ry);
                    }
                    rx.add(a.px[k]);
                    ry.add(a.py[k]);
                }
                if (!anyHit) continue;
                changed = true;
                /* v1.44: عکسِ «قبلِ» خط برای واگرد — اگر همین لمس قبلاً دستش
                   خورده باشد همان عکسِ اولیه نگه داشته می‌شود */
                Annotations.Ann beforeSnap = pen.histEraseBefore(a);

                if (runsX.isEmpty()) {
                    pen.histErasePut(a.id, beforeSnap, null);
                    anns.remove(a.id);
                    continue;
                }
                /* تکهٔ اول جای خود خط می‌نشیند (همان رنگ/ضخامت/شناسه) */
                ArrayList<Float> first = runsX.get(0);
                ArrayList<Float> firstY = runsY.get(0);
                a.px = new float[first.size()];
                a.py = new float[firstY.size()];
                for (int k = 0; k < first.size(); k++) {
                    a.px[k] = first.get(k);
                    a.py[k] = firstY.get(k);
                }
                pen.histErasePut(a.id, beforeSnap, a.copy());
                /* تکه‌های بعدی → خط‌های جدید */
                for (int r = 1; r < runsX.size(); r++) {
                    if (runsX.get(r).size() < 2) continue;
                    Annotations.Ann na = a.copy();
                    na.id = 0;
                    na.ts = System.currentTimeMillis();
                    na.px = new float[runsX.get(r).size()];
                    na.py = new float[runsY.get(r).size()];
                    for (int k = 0; k < na.px.length; k++) {
                        na.px[k] = runsX.get(r).get(k);
                        na.py[k] = runsY.get(r).get(k);
                    }
                    if (anns.add(na) != null) {
                        /* v1.44: تکهٔ تازه هم در عمل همین لمس ثبت می‌شود */
                        pen.histErasePut(na.id, null, na.copy());
                    }
                }
            }
            if (changed) invalidate();
        }
    }

    /* کال‌بک انتخاب رنگ */
    interface ColorPick {
        void onPick(int color);
    }

    /* دیالوگ انتخاب رنگ — شبکهٔ رنگ‌های آماده + رنگ سفارشی RGB */
    /* ================== v1.33: \u0627\u0646\u062a\u062e\u0627\u0628 \u0631\u0646\u06af \u0645\u062f\u0631\u0646 ==================
       \u0634\u06cc\u062a \u067e\u0627\u06cc\u06cc\u0646\u06cc: \u067e\u0627\u0644\u062a \u0634\u0645\u0627 + \u0634\u0628\u06a9\u0647 \u0631\u0646\u06af\u200c\u0647\u0627 + \u0631\u0646\u06af \u0633\u0641\u0627\u0631\u0634\u06cc (HSV) */
    /* هشت رنگ اصلی — نمایش اولیهٔ همهٔ پاپ‌آپهای رنگ (v1.43) */
    static final int[] QUICK_COLORS = {
            0xFFFFD500, 0xFFFF4530, 0xFF3B88FF, 0xFF4D4D4D,
            0xFFFF9500, 0xFF2FBF71, 0xFFAF52DE, 0xFFFF6482,
    };

    /* ردیف هشت‌رنگهٔ فشرده — swatchهای درشت‌تر با فاصلهٔ کم (خواستهٔ کاربر) */
    private static final int[] ANN_FILTERS = {
            Annotations.T_HL, Annotations.T_UL, Annotations.T_ST, Annotations.T_SQ,
            Annotations.T_NOTE, Annotations.T_FNOTE, Annotations.T_STROKE};

    public void onBackPressed() {
        View view;
        if (this.selPage != null) {
            clearSelection();
            return;
        }
        if (this.searchPanel.getVisibility() == 0 || this.tocPanel.getVisibility() == 0 || this.thumbsPanel.getVisibility() == 0 || ((view = this.bookmarksView) != null && view.getVisibility() == 0)) {
            closePanels();
        } else {
            super.onBackPressed();
        }
    }

    @Override // android.app.Activity
    protected void onCreate(Bundle bundle) {
        CrashGuard.install(this);
        /* v1.46: منابع فونت PDFBox (afm/glyphlist) — باید قبل از هر
           ساخت فونت Standard14 مقداردهی شود (فرم‌ها/خروجی فرم) */
        try {
            com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(getApplicationContext());
        } catch (Throwable ignored) {
        }
        super.onCreate(bundle);
        try {
            init(bundle);
        } catch (Throwable th) {
            CrashGuard.append(this, "reader onCreate: " + CrashGuard.stackToString(th));
            CrashGuard.show(this, "خطای غیرمنتظره در باز کردن فایل", CrashGuard.describe(th), CrashGuard.stackToString(th));
        }
    }

    @Override // android.app.Activity
    protected void onDestroy() {
        dismissLiveDialogs();   /* v1.29 FIX: no more WindowLeaked dialogs */
        /* v1.47: توقف کامل خواندن + سرویس اعلان + موتورها */
        try {
            tts.ttsStopReading();
        } catch (Throwable ignoredTts2) {
        }
        try {
            PiperEngine.get(this).stop();
        } catch (Throwable ignoredTts3) {
        }
        try {
            SystemTtsEngine.get(this).shutdown();
        } catch (Throwable ignoredTts4) {
        }
        /* v1.32: حاشیه‌نویسی‌ها قبل از نابودی، ذخیرهٔ اتمیک می‌شوند — حتی
           پس از بسته‌شدن ناگهانی برنامه همه‌چیز سر جایش برمی‌گردد */
        try {
            if (this.anns != null) this.anns.save();
        } catch (Throwable annIgnored) {
        }
        /* v1.40: نسخهٔ رمزگشایی‌شدهٔ موقت پاک شود (فایل اصلی دست‌نخورده است) */
        try {
            if (this.decryptedFile != null) {
                this.decryptedFile.delete();
                this.decryptedFile = null;
            }
        } catch (Throwable ignored) {
        }
        super.onDestroy();
        /* v7: stop read-aloud and release the TTS engine */
        tts.ttsDestroyed = true;   /* v1.28: late onInit must not revive it */
        try {
            if (tts.tts != null) {
                tts.tts.stop();
                tts.tts.shutdown();
                tts.tts = null;
            }
        } catch (Throwable unusedTts) {
        }
        /* v1.16: stop the 16 ms geometry poll and any queued UI messages
           before tearing the view down — a queued tick could touch the
           recycled PDFView. */
        this.ui.removeCallbacksAndMessages(null);
        try {
            this.io.shutdownNow();
        } catch (Throwable unused3) {
        }
        clearSelection();
        try {
            PdfThumbs pdfThumbs = this.thumbs;
            if (pdfThumbs != null) {
                pdfThumbs.release();
            }
        } catch (Throwable unused) {
        }
        try {
            PDFView pDFView = this.pdfView;
            if (pDFView != null) {
                pDFView.recycle();
            }
        } catch (Throwable unused2) {
        }
        WebView webView = this.searchWeb;
        if (webView != null) {
            webView.loadUrl("about:blank");
            this.searchWeb.destroy();
        }
    }

    @Override // android.app.Activity, android.view.KeyEvent.Callback
    public boolean onKeyDown(int i, KeyEvent keyEvent) {
        /* v1.47: هنگام خواندن با صدا، کلید ولوم = جملهٔ قبلی/بعدی */
        if (tts.ttsReading && this.loaded && (i == 24 || i == 25)) {
            if (i == 24) tts.ttsPrevSentence(); else tts.ttsNextSentence();
            return true;
        }
        if (this.store.getBool("volKeys", true) && this.loaded) {
            if (i == 24) {
                jumpTo(getCurrentPage1() - 1, false);
                return true;
            }
            if (i == 25) {
                jumpTo(getCurrentPage1() + 1, false);
                return true;
            }
        }
        return super.onKeyDown(i, keyEvent);
    }

    @Override // android.app.Activity
    protected void onPause() {
        super.onPause();
        /* v1.29 FIX: the 16 ms geometry poll kept running at 60 Hz while the
           activity was backgrounded (Home pressed) as long as a selection was
           active — touching the PDFView getters every tick = battery drain.
           onPause stops it; onResume restarts it only while a selection lives. */
        this.geomWatchRunning = false;
        this.ui.removeCallbacks(this.geomWatch);
        /* v1.32: ذخیرهٔ فوری حاشیه‌نویسی‌ها هنگام خروج از صفحه */
        try {
            if (this.anns != null) this.anns.save();
        } catch (Throwable annIgnored) {
        }
        if (!this.loaded || this.fileKey.length() <= 0) {
            return;
        }
        try {
            this.store.savePosition(this.fileKey, getCurrentPage1(), this.pdfView.getZoom(), this.pageCount);
        } catch (Throwable unused) {
        }
    }

    @Override // android.app.Activity
    protected void onResume() {
        super.onResume();
        /* v1.29: restart the geometry watcher if an active selection needs it */
        if ((this.selPage != null || (this.anns != null && !this.anns.items.isEmpty()))
                && !this.geomWatchRunning) {
            this.geomWatchRunning = true;
            this.ui.postDelayed(this.geomWatch, 16L);
        }
    }

    @Override // com.pdfkhwan.app.SearchBridge.Host
    public void onSearchDocInfo(int i) {
    }

    @Override // com.pdfkhwan.app.SearchBridge.Host
    public void onSearchDone(int i) {
        String str;
        this.searching = false;
        this.searchProgressRow.setVisibility(8);
        this.searchStatus.setVisibility(0);
        TextView textView = this.searchStatus;
        if (i == 0) {
            str = "چیزی پیدا نشد.";
        } else if (i == -1) {
            /* v1.27: the JS engine hit the 500-hit cap — say so instead of
               pretending that is ALL of them */
            str = "پیدا شد: ۵۰۰ مورد (بیشتر از این نمایش داده نشد)";
        } else {
            str = "پیدا شد: " + Fa.d(i) + " مورد";
        }
        textView.setText(str);
        findViewById(R.id.btnDoSearch).setEnabled(true);
        /* v1.27: flush the throttled results list one final time */
        this.resultsAdapter.notifyDataSetChanged();
    }

    @Override // com.pdfkhwan.app.SearchBridge.Host
    public void onSearchError(String str) {
        this.searching = false;
        this.searchProgressRow.setVisibility(8);
        this.searchStatus.setVisibility(0);
        this.searchStatus.setText("خطا: " + str);
        findViewById(R.id.btnDoSearch).setEnabled(true);
    }

    @Override // com.pdfkhwan.app.SearchBridge.Host
    public void onSearchHit(int i, String str) {
        /* v1.27 FIX: searching a common word («و», «در») in a large book
           crossed the JS→Java bridge and REBOUND the whole results ListView
           for EVERY hit — seconds of UI stutter and unbounded memory. Now:
           capped at 500 hits, throttled UI refresh (max 4/s). */
        if (this.hits.size() >= 500) {
            return;
        }
        this.hits.add(new int[]{i});
        this.snippets.add(str);
        if (!this.searchResultsDirty) {
            this.searchResultsDirty = true;
            this.ui.postDelayed(new Runnable() {
                @Override
                public void run() {
                    ReaderActivity.this.searchResultsDirty = false;
                    ReaderActivity.this.resultsAdapter.notifyDataSetChanged();
                }
            }, 250L);
        }
        this.searchStatus.setVisibility(0);
        if (this.hits.size() % 20 == 0 || this.hits.size() >= 500) {
            this.searchStatus.setText("پیدا شد: " + Fa.d(this.hits.size()) + (this.hits.size() >= 500 ? " مورد (سقف)" : " مورد (تا اینجا)"));
        }
    }

    @Override // com.pdfkhwan.app.SearchBridge.Host
    public void onSearchProgress(int i, int i2) {
        if (this.searching) {
            this.searchProgressRow.setVisibility(0);
            this.searchProgressView.setMax(Math.max(1, i2));
            this.searchProgressView.setProgress(i);
            this.searchProgressText.setText(String.valueOf(Fa.d(i)) + "/" + Fa.d(i2));
        }
    }

    @Override // com.pdfkhwan.app.SearchBridge.Host
    public void onSearchReady() {
        this.webReady = true;
        /* v1.40: همین لحظه که موتور متن بالا آمد، واژه‌های صفحهٔ فعلی را
           بی‌صدا بگیر — انتخاب/جستجو/کپی از همان اول آماده است، نه اینکه
           اولین لمس-بلطنی «در حال آماده‌سازی» بگوید و معطل کند */
        try {
            int p = this.loaded && this.pdfView != null
                    ? this.pdfView.getCurrentPage()
                    : Math.max(0, this.lastPage1 - 1);
            requestSelWords(Math.max(0, p), true);
        } catch (Throwable ignored) {
        }
    }

    /* ================== v1.37: جریان متن برای تبدیل به Word ================== */

    @Override // com.pdfkhwan.app.SearchBridge.Host
    public void onTextPage(int page, String text) {
        if (share.wordBusy) {
            share.wordTexts.put(page, text == null ? "" : text);
        }
    }

    @Override // com.pdfkhwan.app.SearchBridge.Host
    public void onTextProgress(int done, int total) {
        if (share.wordBusy) {
            busyCardText(share.wordProg, Fa.d(done) + " از " + Fa.d(total) + " صفحه");
        }
    }

    @Override // com.pdfkhwan.app.SearchBridge.Host
    public void onTextDone(int pages) {
        if (!share.wordBusy) return;
        busyCardText(share.wordProg, "در حال ساختن فایل Word…");
        final int n = pages;
        this.io.execute(new Runnable() {
            @Override public void run() {
                try {
                    ArrayList<String[]> pageLines = new ArrayList<>();
                    for (int p = 1; p <= n; p++) {
                        String t = share.wordTexts.get(p, "");
                        pageLines.add(t == null ? new String[0] : t.split("\\n"));
                    }
                    File dir = new File(getCacheDir(), "share");
                    if (!dir.exists()) dir.mkdirs();
                    String base = share.shareSafeName().replaceAll("\\.[^.]+$", "");
                    final File out = new File(dir, base + ".docx");
                    PdfExport.buildDocx(out, docName, pageLines);
                    final Uri put = ShareProvider.put(out);
                    ui.post(new Runnable() {
                        @Override public void run() {
                            share.wordBusy = false;
                            try { if (share.wordProg != null) share.wordProg.dismiss(); } catch (Throwable ignored) {}
                            share.wordProg = null;
                            try {
                                Intent intent = new Intent("android.intent.action.SEND");
                                intent.setType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
                                intent.putExtra("android.intent.extra.STREAM", put);
                                intent.setClipData(ClipData.newRawUri("docx", put));
                                intent.addFlags(1);
                                startActivity(Intent.createChooser(intent, "اشتراک فایل Word"));
                            } catch (Exception unused) {
                                toast("اشتراک فایل Word ممکن نشد");
                            }
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override public void run() {
                            share.wordFinishError("ساختن فایل Word ممکن نشد: " + e.getMessage());
                        }
                    });
                }
            }
        });
    }

    @Override // com.pdfkhwan.app.SearchBridge.Host
    public void onTextError(String err) {
        share.wordFinishError("استخراج متن ممکن نشد");
    }

    /* ================== v1.38: جریان layout برای Word حافظ‌قالب ==================
       هر صفحه با واژه‌ها + جعبه‌ها + فونت‌ها + اندازه‌ها می‌رسد؛ در پایان
       PdfExport.buildDocxLayout سند Word را با پاراگراف/جهت/فونت/اندازه/
       تراز/تورفتگیِ بازسازی‌شده می‌سازد. */

    @Override // com.pdfkhwan.app.SearchBridge.Host
    public void onLayoutPage(int page, String json) {
        if (share.wordBusy) {
            share.wordLayout.put(page, PdfExport.parseLayout(json));
        }
    }

    @Override // com.pdfkhwan.app.SearchBridge.Host
    public void onLayoutProgress(int done, int total) {
        if (share.wordBusy) {
            busyCardText(share.wordProg, Fa.d(done) + " از " + Fa.d(total) + " صفحه");
        }
    }

    @Override // com.pdfkhwan.app.SearchBridge.Host
    public void onLayoutDone(int pages) {
        if (!share.wordBusy) return;
        busyCardText(share.wordProg, "در حال ساختن فایل Word…");
        this.io.execute(new Runnable() {
            @Override public void run() {
                try {
                    ArrayList<PdfExport.LayoutPage> list = new ArrayList<>();
                    for (int p = 1; p <= pages; p++) {
                        PdfExport.LayoutPage lp = share.wordLayout.get(p);
                        list.add(lp != null ? lp : new PdfExport.LayoutPage());
                    }
                    File dir = new File(getCacheDir(), "share");
                    if (!dir.exists()) dir.mkdirs();
                    String base = share.shareSafeName().replaceAll("\\.[^.]+$", "");
                    final File out = new File(dir, base + ".docx");
                    /* v1.43: تصاویر PDF هم داخل Word می‌روند — برش از رندر pdfium */
                    PdfExport.buildDocxLayout(out, docName, list, share.wordImageSource());
                    final Uri put = ShareProvider.put(out);
                    ui.post(new Runnable() {
                        @Override public void run() {
                            share.wordBusy = false;
                            try { if (share.wordProg != null) share.wordProg.dismiss(); } catch (Throwable ignored) {}
                            share.wordProg = null;
                            try {
                                Intent intent = new Intent("android.intent.action.SEND");
                                intent.setType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
                                intent.putExtra("android.intent.extra.STREAM", put);
                                intent.setClipData(ClipData.newRawUri("docx", put));
                                intent.addFlags(1);
                                startActivity(Intent.createChooser(intent, "اشتراک فایل Word"));
                            } catch (Exception unused) {
                                toast("اشتراک فایل Word ممکن نشد");
                            }
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override public void run() {
                            share.wordFinishError("ساختن فایل Word ممکن نشد: " + e.getMessage());
                        }
                    });
                }
            }
        });
    }

    @Override // com.pdfkhwan.app.SearchBridge.Host
    public void onLayoutError(String err) {
        share.wordFinishError("استخراج متن ممکن نشد");
    }

    /* ================== v1.43: رندر تصاویر برای خروجی Word ==================
       ناحیهٔ خواسته‌شده (مختصات viewport همان صفحه) از رندر pdfium بریده و
       PNG می‌شود؛ اگر سند در دسترس نباشد null برمی‌گردد (تصویر رد می‌شود). */
    float lpVw(int pageIdx) {
        PdfExport.LayoutPage lp = share.wordLayout.get(pageIdx + 1);
        return lp != null && lp.vw > 10 ? lp.vw : 595f;
    }

    float lpVh(int pageIdx) {
        PdfExport.LayoutPage lp = share.wordLayout.get(pageIdx + 1);
        return lp != null && lp.vh > 10 ? lp.vh : 842f;
    }

    @Override // com.pdfkhwan.app.SearchBridge.Host
    public void onSelError(String str) {
        this.selPending = null;
        tts.ttsPendingPage = -1;   /* v1.29 FIX: a failed extraction must disarm the read-aloud too */
        /* v1.49 FIX: خطای استخراج در حال خواندن، وضعیت TTS را هم بی‌اثر کند —
           قبلاً ttsNextReadPage مسلح می‌ماند و خواننده در «در حال آماده‌سازی…»
           گیر می‌کرد؛ بعد با بستن صفحه، نشست نیمه‌کاره اپ را می‌بست (خروج بی‌صدا).
           حالا خواندن متوقف می‌شود و خودِ پیام خطا (نه یک پیام کلی) دیده می‌شود. */
        if (tts.ttsNextReadPage >= 0 || tts.ttsReading) {
            tts.ttsNextReadPage = -1;
            tts.ttsStopReading();
            toast("خواندن ادامه نیافت — استخراج متن صفحه خطا داد" + (str != null && str.trim().length() > 0 ? ":\n" + str : ""));
            return;
        }
        long currentTimeMillis = System.currentTimeMillis();
        if (currentTimeMillis - this.selPrepToastAt > 2500) {
            this.selPrepToastAt = currentTimeMillis;
            toast("استخراج متن این صفحه ممکن نشد" + (str != null && str.trim().length() > 0 ? ":\n" + str : ""));
        }
    }

    @Override // com.pdfkhwan.app.SearchBridge.Host
    public void onSelPage(int i, String str) {
        try {
            JSONObject jSONObject = new JSONObject(str);
            float optDouble = (float) jSONObject.optDouble("vw", 0.0d);
            float optDouble2 = (float) jSONObject.optDouble("vh", 0.0d);
            JSONArray optJSONArray = jSONObject.optJSONArray("w");
            ArrayList arrayList = new ArrayList();
            if (optJSONArray != null) {
                /* v1.16: when a word is dropped because it became empty after
                   sanitizing (pure zero-width chars), its br (line-break) flag
                   is transferred to the next RETAINED word — otherwise the
                   line start lost its \n and two lines merged on copy. */
                boolean carryBr = false;
                boolean carryGap0 = false;
                boolean carryZg = false;
                for (int i5 = 0; i5 < optJSONArray.length(); i5++) {
                    JSONArray optJSONArray2 = optJSONArray.optJSONArray(i5);
                    if (optJSONArray2 != null && optJSONArray2.length() >= 6) {
                        String replaceAll = optJSONArray2.optString(5).replaceAll("[\\u200B\\u00AD\\uE000-\\uF8FF\\uFEFF]", "");
                        if (replaceAll.length() != 0) {
                            /* v1.14: wire format — 9+ elements:
                               [x0,y0,x1,y1,line,t,rtl,gap,br, (v1.41:) zg, gl]
                               gap: 0=space before, 1=glued
                               br:  1=line break before this word, 0=same line
                               zg:  1=tight ZWNJ before (نیم‌فاصله on copy)
                               gl:  [[ch,x0,x1],…] per-char boxes (reading order) */
                            boolean rtl = optJSONArray2.length() > 6 && optJSONArray2.optInt(6) == 1;
                            int gap = optJSONArray2.length() > 7 ? optJSONArray2.optInt(7, 0) : 0;
                            int br = optJSONArray2.length() > 8 ? optJSONArray2.optInt(8, 0) : 0;
                            int zg = optJSONArray2.length() > 9 ? optJSONArray2.optInt(9, 0) : 0;
                            char[] cs = null;
                            float[] cx0 = null, cx1 = null;
                            JSONArray gl = optJSONArray2.length() > 10 ? optJSONArray2.optJSONArray(10) : null;
                            if (gl != null && gl.length() > 0) {
                                int n = gl.length();
                                /* defensive: char boxes must match the word text */
                                if (n <= replaceAll.length()) {
                                    cs = new char[n];
                                    cx0 = new float[n];
                                    cx1 = new float[n];
                                    boolean ok = true;
                                    for (int k = 0; k < n; k++) {
                                        JSONArray g = gl.optJSONArray(k);
                                        if (g == null || g.length() < 3) { ok = false; break; }
                                        cs[k] = g.optString(0, "").length() > 0 ? g.optString(0, "\u0000").charAt(0) : '\u0000';
                                        cx0[k] = (float) g.optDouble(1, 0);
                                        cx1[k] = (float) g.optDouble(2, 0);
                                    }
                                    if (!ok) { cs = null; cx0 = null; cx1 = null; }
                                }
                            }
                            if (carryBr && br == 0) br = 1;
                            if (carryGap0 && gap != 0) gap = 0;
                            if (carryZg && zg == 0) zg = 1;
                            carryBr = false;
                            carryGap0 = false;
                            carryZg = false;
                            arrayList.add(new SelWord(replaceAll,
                                    (float) optJSONArray2.optDouble(0),
                                    (float) optJSONArray2.optDouble(1),
                                    (float) optJSONArray2.optDouble(2),
                                    (float) optJSONArray2.optDouble(3),
                                    optJSONArray2.optInt(4),
                                    rtl, gap, br, zg, cs, cx0, cx1));
                        } else {
                            if (optJSONArray2.length() > 8 && optJSONArray2.optInt(8, 0) == 1) carryBr = true;
                            if (optJSONArray2.length() > 7 && optJSONArray2.optInt(7, 1) == 0) carryGap0 = true;
                            if (optJSONArray2.length() > 9 && optJSONArray2.optInt(9, 0) == 1) carryZg = true;
                        }
                    }
                }
            }
            int i6 = i - 1;
            /* v1.49: چیدمان ستونی — در کتاب‌های دو ستونه، کلمات هر خط از هر
               دو ستون با هم می‌رسند (خط ۱ ستون۱، خط ۱ ستون۲، …) و هم سلکت
               «دو خطِ موازی از دو ستون» برمی‌داشت هم خواندن قاطی می‌شد.
               حالا مرزِ ستون‌ها پیدا می‌شود و کلمات به ترتیب واقعی خواندن
               مرتب می‌شوند: اول تمامِ ستون اول (خط به خط)، بعد ستون دوم.
               چون همهٔ سلکت/کپی/جستجو/خواندن از همین ترتیب استفاده می‌کنند،
               همه یکجا درست می‌شوند. */
            arrayList = reorderColumns(arrayList, (float) optDouble);
            this.selPages.put(Integer.valueOf(i6), new SelPage(optDouble, optDouble2, arrayList));
            /* v1.47: صفحهٔ در انتظار خواندن — جمله‌ها همین‌جا ساخته و پخش ادامه می‌یابد */
            if (tts.ttsNextReadPage == i6) {
                tts.ttsNextReadPage = -1;
                tts.ttsContinuePage(i6);
            }
            /* v1.45: گرم‌کردن صفحات برای خروجی حاشیه‌نویسی — بعدی */
            try { share.annWarmStep(); } catch (Throwable ignoredWarm) {}
            /* v7: search-hit highlighting — light up the words on the hit
               page that match the current query (same normalization as the
               search engine). */
            if (this.hlQuery != null && this.hlPageIdx == i6) {
                computeHighlights(this.selPages.get(Integer.valueOf(i6)), this.hlQuery);
            }
            /* v7: read-aloud — speak the page once its words arrive */
            if (tts.ttsPendingPage == i6) {
                tts.ttsPendingPage = -1;
                tts.speakText(pageText(this.selPages.get(Integer.valueOf(i6))));
            }
            float[] fArr = this.selPending;
            if (fArr == null || ((int) fArr[0]) != i6) {
                return;
            }
            this.selPending = null;
            SelPage selPage = this.selPages.get(Integer.valueOf(i6));
            if (selPage != null) {
                applySelAt(selPage, i6, fArr[1], fArr[2]);
            }
        } catch (Throwable th) {
            CrashGuard.append(this, "onSelPage: " + CrashGuard.stackToString(th));
        }
    }

    /* v1.49: مرتب‌سازی کلمات صفحه به ترتیب خواندنِ ستونی.
       الگوریتم:
       ۱) فواصل افقیِ خالی بین کلماتِ هم‌خط پیدا می‌شود (gut).
       ۲) اگر یک شکاف خالیِ عمودی (ستون) با عرض ≥ ~۴٪ عرض صفحه و ≥ ۱.۵em
          وجود داشته باشد که «هیچ کلمه‌ای از آن عبور نمی‌کند»، مرز ستون است.
       ۳) کلمات بر اساس (شمارهٔ ستونِ مرکزشان، شمارهٔ خط، x) مرتب می‌شوند.
       تک‌ستونی‌ها را دست نمی‌زند (هیچ شکافی معیار را رد نمی‌کند). */
    static boolean charEdgeRtl(char ch, boolean wordRtl) {
        if ((ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z')
                || (ch >= '0' && ch <= '9')
                || (ch >= '\u0660' && ch <= '\u0669')
                || (ch >= '\u06F0' && ch <= '\u06F9')) {
            return false;
        }
        if ((ch >= '\u0620' && ch <= '\u065F') || (ch >= '\u066E' && ch <= '\u06D5')
                || (ch >= '\u06EE' && ch <= '\u06FF') || (ch >= '\u0590' && ch <= '\u05FF')
                || (ch >= '\uFB50' && ch <= '\uFDFF') || (ch >= '\uFE70' && ch <= '\uFEFF')) {
            return true;
        }
        return wordRtl;
    }

    static ArrayList<SelWord> reorderColumns(List<SelWord> words, float pageVw) {
        try {
            if (words == null || words.size() < 8 || pageVw <= 10) return words instanceof ArrayList ? (ArrayList) words : new ArrayList<>(words);
            int n = words.size();
            float[][] spans = new float[n][2];
            int rtlLetters = 0, ltrLetters = 0, rtlWordCnt = 0;
            for (int i = 0; i < n; i++) {
                SelWord w = words.get(i);
                spans[i][0] = Math.min(w.x0, w.x1);
                spans[i][1] = Math.max(w.x0, w.x1);
                if (w.rtl) rtlWordCnt++;
                if (w.t != null) {
                    for (int ci = 0; ci < w.t.length(); ci++) {
                        char c = w.t.charAt(ci);
                        if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) ltrLetters++;
                        else if (((c >= '\u0620' && c <= '\u064A') || (c >= '\u066E' && c <= '\u06D5')
                                || (c >= '\u0590' && c <= '\u05FF') || (c >= '\uFB50' && c <= '\uFDFF')
                                || (c >= '\uFE70' && c <= '\uFEFF'))
                                && c != '\u060C' && c != '\u061B' && c != '\u061F' && c != '\u0640') {
                            rtlLetters++;
                        }
                    }
                }
            }
            final boolean pageRtl = (rtlLetters + ltrLetters > 0) ? (rtlLetters > ltrLetters) : (rtlWordCnt * 2 > n);
            java.util.TreeSet<Float> candidates = new java.util.TreeSet<>();
            for (int i = 0; i < n; i++) {
                candidates.add(spans[i][0]);
                candidates.add(spans[i][1]);
            }
            float emSum = 0;
            int emN = 0;
            for (int i = 0; i < n; i++) {
                float h = words.get(i).y1 - words.get(i).y0;
                if (h > 1 && h < 200) { emSum += h; emN++; }
            }
            float em = emN > 0 ? emSum / emN : 10f;
            float minGap = Math.max(em * 1.6f, pageVw * 0.045f);
            float bestCut = Float.NaN, bestW = 0;
            List<Float> cuts = new ArrayList<>(candidates);
            java.util.HashSet<Integer> leftLines = new java.util.HashSet<>();
            java.util.HashSet<Integer> rightLines = new java.util.HashSet<>();
            for (int ci = 0; ci < cuts.size(); ci++) {
                float cut = cuts.get(ci);
                if (cut < pageVw * 0.15f || cut > pageVw * 0.85f) continue;
                float leftEdge = Float.NEGATIVE_INFINITY, rightEdge = Float.POSITIVE_INFINITY;
                int leftWords = 0, rightWords = 0;
                leftLines.clear();
                rightLines.clear();
                for (int i = 0; i < n; i++) {
                    if (spans[i][1] <= cut) {
                        leftWords++;
                        leftLines.add(words.get(i).line);
                        if (spans[i][1] > leftEdge) leftEdge = spans[i][1];
                    } else if (spans[i][0] >= cut) {
                        rightWords++;
                        rightLines.add(words.get(i).line);
                        if (spans[i][0] < rightEdge) rightEdge = spans[i][0];
                    } else {
                        leftEdge = Float.POSITIVE_INFINITY;
                        break;
                    }
                }
                if (leftEdge == Float.POSITIVE_INFINITY) continue;
                if (leftWords < 4 || rightWords < 4 || leftLines.size() < 3 || rightLines.size() < 3) continue;
                float gap = rightEdge - leftEdge;
                if (gap >= minGap && gap > bestW) { bestW = gap; bestCut = cut; }
            }
            if (Float.isNaN(bestCut)) {
                return words instanceof ArrayList ? (ArrayList) words : new ArrayList<>(words);
            }
            final float cut = bestCut;
            final java.util.IdentityHashMap<SelWord, Integer> origIdx = new java.util.IdentityHashMap<>(n);
            for (int i = 0; i < n; i++) origIdx.put(words.get(i), i);
            ArrayList<SelWord> out = new ArrayList<>(words);
            java.util.Collections.sort(out, new java.util.Comparator<SelWord>() {
                @Override
                public int compare(SelWord a, SelWord b) {
                    int ca = (a.x0 + a.x1) / 2f < cut ? 0 : 1;
                    int cb = (b.x0 + b.x1) / 2f < cut ? 0 : 1;
                    if (ca != cb) return pageRtl ? (cb - ca) : (ca - cb);
                    if (a.line != b.line) return Integer.compare(a.line, b.line);
                    float ya = (a.y0 + a.y1), yb = (b.y0 + b.y1);
                    if (Math.abs(ya - yb) > 0.5f) return ya < yb ? -1 : 1;
                    Integer ia = origIdx.get(a), ib = origIdx.get(b);
                    return Integer.compare(ia != null ? ia : 0, ib != null ? ib : 0);
                }
            });
            ArrayList<SelWord> res = new ArrayList<>(out.size());
            for (int i = 0; i < out.size(); i++) {
                SelWord w = out.get(i);
                int col = (w.x0 + w.x1) / 2f < cut ? 0 : 1;
                boolean newLine = false;
                if (i > 0) {
                    SelWord p = out.get(i - 1);
                    int pcol = (p.x0 + p.x1) / 2f < cut ? 0 : 1;
                    newLine = pcol != col || p.line != w.line;
                }
                res.add(new SelWord(w.t, w.x0, w.y0, w.x1, w.y1, w.line, w.rtl, w.gap, newLine ? 1 : 0, w.zg, w.cs, w.cx0, w.cx1));
            }
            return res;
        } catch (Throwable t) {
            return words instanceof ArrayList ? (ArrayList) words : new ArrayList<>(words);
        }
    }

    /* v7: mark all words of `page` matching `query` (normalized contains) */
    private void computeHighlights(SelPage page, String query) {
        this.hlWords.clear();
        if (page == null || query == null) {
            return;
        }
        String q = normFa(query);
        String qCompact = q.replace(" ", "");
        if (q.length() == 0) {
            return;
        }
        /* v1.28 FIX: multi-word queries («سلام دنیا») never lit up — the JS
           engine matches across word boundaries, but this loop only checked
           single words. Now consecutive words are joined (with the engine's
           gap handling approximated by a plain space) and matched too. */
        int n = page.words.size();
        String[] norm = new String[n];
        for (int i = 0; i < n; i++) norm[i] = normFa(page.words.get(i).t);
        for (int i = 0; i < n; i++) {
            String wn = norm[i];
            if (wn.length() == 0) {
                continue;
            }
            /* v1.27 FIX: q.contains(wn) lit up EVERY short word that happens
               to be a substring of the query («فارماکولوژی» also marked «فار»،
               «ما»، «لو»…) — only match the query INSIDE the word now, or a
               word inside the query when the word is long enough to matter. */
            if (wn.contains(q) || (wn.length() >= 4 && q.contains(wn))
                    || (qCompact.length() >= 4 && wn.replace(" ", "").contains(qCompact))) {
                this.hlWords.add(Integer.valueOf(i));
                continue;
            }
            /* v1.28: multi-word match — join up to 8 consecutive words */
            if (q.contains(" ")) {
                StringBuilder sb = new StringBuilder(wn);
                int j = i + 1;
                while (j < n && j <= i + 8) {
                    String wn2 = norm[j];
                    if (wn2.length() == 0) { j++; continue; }
                    sb.append(' ').append(wn2);
                    String joined = sb.toString();
                    if (joined.contains(q) || joined.replace(" ", "").contains(qCompact)) {
                        for (int k = i; k <= j; k++) this.hlWords.add(Integer.valueOf(k));
                        break;
                    }
                    if (joined.length() > q.length() + 24) break;
                    j++;
                }
            }
        }
        if (this.selOverlay != null) {
            this.selOverlay.invalidate();
        }
    }

    /* v7: reconstruct a page's reading text from its word stream (for TTS) */
    private String pageText(SelPage page) {
        if (page == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < page.words.size(); i++) {
            SelWord w = page.words.get(i);
            if (i > 0) {
                if (w.br == 1) {
                    sb.append('\n');
                } else if (w.gap == 0) {
                    sb.append(w.zg == 1 ? '\u200C' : ' ');   /* v1.41: نیم‌فاصله */
                }
            }
            sb.append(w.t);
        }
        return sb.toString();
    }

    /* v7: read-aloud (TTS) — lazily initializes the engine, prefers Persian */
    /* v1.47: کل مسیر به خوانندهٔ جمله‌به‌جمله (بخش ۱۹) منتقل شد — موتور سیستم
       یا Piper + هایلایت + ادامهٔ خودکار صفحه‌ها + اعلان مدیا */
    static java.util.List<String> splitForTts(String text, int maxChars) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (text == null || text.isEmpty()) {
            out.add("");
            return out;
        }
        int n = text.length();
        int i = 0;
        while (i < n) {
            int end = Math.min(n, i + maxChars);
            if (end < n) {
                int cut = -1;
                for (int k = end; k > i + maxChars / 2; k--) {
                    char ch = text.charAt(k - 1);
                    if (ch == '.' || ch == '!' || ch == '?' || ch == '\n' || ch == '،' || ch == '؛') {
                        cut = k;
                        break;
                    }
                }
                if (cut < 0) {
                    for (int k = end; k > i + maxChars / 2; k--) {
                        if (text.charAt(k - 1) == ' ') {
                            cut = k;
                            break;
                        }
                    }
                }
                if (cut > i) end = cut;
            }
            out.add(text.substring(i, end));
            i = end;
        }
        if (out.isEmpty()) out.add("");
        return out;
    }

    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN && display.autoScrollOn) {
            display.stopAutoScroll();
            toast("اسکرول خودکار متوقف شد");
        }
        return super.dispatchTouchEvent(ev);
    }

    /* ---------- شیت صفحه‌آرایی (منوی نمایش ← «صفحه‌آرایی و کاغذ…») ---------- */
    static String ttsLangOf(String text) {
        if (text == null) return "fa";
        int fa = 0, en = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x0600 && c <= 0x06FF || c >= 0xFB50 && c <= 0xFDFF || c >= 0xFE70 && c <= 0xFEFF) fa++;
            else if (c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z') en++;
        }
        return en > fa ? "en" : "fa";
    }

    static boolean ttsEndsSentence(String w) {
        if (w == null || w.length() == 0) return false;
        char c = w.charAt(w.length() - 1);
        /* v1.49: ویرگول (،) عمداً جمله را نمی‌بندد — جمله‌های نیمه‌کاره
           مکث‌های ناخواسته بین جمله‌ها می‌سازند؛ فقط پایانگرهای واقعی. */
        return c == '.' || c == '!' || c == '?' || c == '\u061F' /*؟*/
                || c == '\u2026' /*…*/ || c == '\u061B' /*؛*/ || c == ':';
    }

    /* ساخت جملات صفحه از روی کلمات استخراج‌شده (نیم‌فاصله‌ها حفظ می‌شوند) */
    public void onToc(String str) {
    }
}
