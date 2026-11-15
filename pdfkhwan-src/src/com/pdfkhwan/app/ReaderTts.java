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
public class ReaderTts {
    final ReaderActivity host;
    ReaderTts(ReaderActivity h) { this.host = h; }

    TextToSpeech tts;

    boolean ttsReady = false;
    /* v1.28: onDestroy already shut the engine down — a late onInit must
       never create a NEW engine bound to the dead activity (it kept
       talking / leaked after the user left the reader) */

    boolean ttsDestroyed = false;

    boolean ttsSpeaking = false;

    int ttsPendingPage = -1;

    String ttsPendingText = null;

    /* ================== v1.47: بخش ۱۹/۲۱ — خوانندهٔ جمله‌به‌جمله ==================
       از متن انتخاب‌شده (یا از اول صفحه) شروع می‌کند، جمله‌به‌جمله با هایلایت
       می‌خواند، صفحه را خودش ورق می‌زند و در پس‌زمینه با اعلان مدیا ادامه
       می‌یابد. موتور هر جمله بر اساس خطِ متن انتخاب می‌شود (فارسی/لاتین). */

    List<ReaderActivity.TtsSent> ttsSents = null;

    int ttsPageIdx = -1;

    int ttsIdx = -1;

    boolean ttsReading = false;

    boolean ttsPaused = false;

    float ttsSpeed = 1.0f;

    int ttsNextReadPage = -1;

    long ttsWaitSeq = 0;   /* v1.49: نسل انتظار — مهلت قدیمی با شروع تازه باطل می‌شود */
    /* v1.49: حالت «فقط همین انتخاب» — وقتی -1 نباشد، خواندن پس از جمله‌ای که
       این کلمه (اندیسِ selEnd در جریان کلمات صفحه) را دربر دارد متوقف می‌شود.
       قبلتر دکمهٔ بلندگوی نوارِ انتخاب، کل صفحه را از ابتدای انتخاب می‌خواند. */

    int ttsStopAtWord = -1;

    boolean ttsStopAtWordArmed = false;

    boolean ttsNoEngineHinted = false;

    LinearLayout ttsBar = null;

    TextView ttsBarInfo = null;

    ImageView ttsBarPlay = null;
    /* v1.47: قلم هایلایت جملهٔ در حال خواندن (ساخته‌شده به تأخیر) */

    void startSpeak() {
        ttsStartPage();
    }

    void speakText(String text) {
        if (text == null || text.trim().length() == 0) {
            host.toast("متن روی این صفحه نیست");
            return;
        }
        if (tts == null) {
            if (ttsDestroyed) return;   /* v1.28: no engine after destroy */
            ttsPendingText = text;
            tts = new TextToSpeech(host, new TextToSpeech.OnInitListener() {
                @Override
                public void onInit(int status) {
                    if (ttsDestroyed) return;   /* v1.28 */
                    ttsReady = status == 0;
                    if (ttsReady) {
                        try {
                            tts.setLanguage(new Locale("fa"));
                        } catch (Throwable unused) {
                        }
                        /* speak whatever was waiting for the engine */
                        String pending = ttsPendingText;
                        ttsPendingText = null;
                        if (pending != null && pending.trim().length() > 0) {
                            speakText(pending);
                        }
                    } else {
                        host.toast("موتور صدا در دسترس نیست");
                    }
                }
            });
            return;
        }
        if (!ttsReady) {
            host.toast("موتور صدا در دسترس نیست");
            return;
        }
        try {
            int avail = tts.setLanguage(new Locale("fa"));
            if (avail == TextToSpeech.LANG_MISSING_DATA || avail == TextToSpeech.LANG_NOT_SUPPORTED) {
                /* v1.27 FIX: without a Persian voice the engine silently spoke
                   nothing or gibberish — tell the user what to install */
                host.toast("صدای فارسی پیدا نشد — از تنظیمات گوشی صدای TTS فارسی را نصب کن");
                return;
            }
            tts.setSpeechRate(0.95f);
            /* v1.27 FIX: long pages were cut at 3000 chars with no
               continuation (reading stopped mid-sentence). The page is now
               split at sentence/space boundaries into ~1500-char chunks,
               queued back to back, and ttsSpeaking resets when the LAST
               chunk finishes (the flag previously never cleared by itself). */
            final java.util.List<String> chunks = ReaderActivity.splitForTts(text, 1500);
            final String lastId = "pdfkhwan_page_" + (chunks.size() - 1);
            try {
                tts.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener() {
                    @Override
                    public void onStart(String utteranceId) {
                    }

                    @Override
                    public void onDone(String utteranceId) {
                        if (lastId.equals(utteranceId)) {
                            host.ui.post(new Runnable() {
                                @Override
                                public void run() {
                                    ttsSpeaking = false;
                                }
                            });
                        }
                    }

                    @Override
                    public void onError(String utteranceId) {
                        if (lastId.equals(utteranceId)) {
                            host.ui.post(new Runnable() {
                                @Override
                                public void run() {
                                    ttsSpeaking = false;
                                }
                            });
                        }
                    }
                });
            } catch (Throwable ignored) {
            }
            for (int ci = 0; ci < chunks.size(); ci++) {
                tts.speak(chunks.get(ci), ci == 0 ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD,
                        null, "pdfkhwan_page_" + ci);
            }
            ttsSpeaking = true;
            host.toast("در حال خواندن صفحه…");
        } catch (Throwable th) {
            CrashGuard.append(host, "tts: " + CrashGuard.stackToString(th));
            host.toast("خواندن ممکن نشد");
        }
    }

    /* v1.27: split text into TTS-sized chunks at sentence/space boundaries */

    void stopSpeak() {
        if (tts != null) {
            try {
                tts.stop();
            } catch (Throwable unused) {
            }
        }
        ttsSpeaking = false;
        ttsPendingPage = -1;
        ttsPendingText = null;
    }

    /* ====================================================================
       ================== v1.47 — بخش ۱۸: صفحه‌آرایی پیشرفته ==================
       ==================================================================== */

    void ttsBuildSentences(int pageIdx) {
        ttsSents = null;
        ttsPageIdx = pageIdx;
        ReaderActivity.SelPage sp = host.selPages.get(Integer.valueOf(pageIdx));
        if (sp == null || sp.words.isEmpty()) return;
        List<ReaderActivity.TtsSent> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        ReaderActivity.TtsSent cur = null;
        for (int i = 0; i < sp.words.size(); i++) {
            ReaderActivity.SelWord w = sp.words.get(i);
            if (w.t.trim().length() == 0) continue;
            if (cur == null) {
                cur = new ReaderActivity.TtsSent();
                cur.w0 = i;
                sb.setLength(0);
            } else {
                if (w.br == 1) sb.append(' ');
                else if (w.gap == 0) sb.append(w.zg == 1 ? '\u200C' : ' ');
            }
            sb.append(w.t);
            cur.w1 = i;
            int len = cur.w1 - cur.w0 + 1;
            boolean end = ReaderActivity.ttsEndsSentence(w.t);
            /* v1.49: سقف قطعِ جمله در مرز خط از ۳۰ به ۶۰ کلمه — قطع‌های زودهنگام
               جمله‌های طولانی را نصف می‌کرد و «کلمهٔ آخر خوانده نشد» حس می‌شد. */
            if (end || (len >= 60 && w.br == 1)) {
                cur.text = sb.toString().trim();
                if (cur.text.length() > 0) {
                    float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
                    for (int k = cur.w0; k <= cur.w1; k++) {
                        ReaderActivity.SelWord ww = sp.words.get(k);
                        x0 = Math.min(x0, Math.min(ww.x0, ww.x1));
                        x1 = Math.max(x1, Math.max(ww.x0, ww.x1));
                        y0 = Math.min(y0, ww.y0);
                        y1 = Math.max(y1, ww.y1);
                    }
                    cur.box = new RectF(x0, y0, x1, y1);
                    cur.lang = ReaderActivity.ttsLangOf(cur.text);
                    out.add(cur);
                }
                cur = null;
            }
        }
        if (cur != null) {
            cur.text = sb.toString().trim();
            if (cur.text.length() > 0) {
                float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
                for (int k = cur.w0; k <= cur.w1 && k < sp.words.size(); k++) {
                    ReaderActivity.SelWord ww = sp.words.get(k);
                    x0 = Math.min(x0, Math.min(ww.x0, ww.x1));
                    x1 = Math.max(x1, Math.max(ww.x0, ww.x1));
                    y0 = Math.min(y0, ww.y0);
                    y1 = Math.max(y1, ww.y1);
                }
                cur.box = new RectF(x0, y0, x1, y1);
                cur.lang = ReaderActivity.ttsLangOf(cur.text);
                out.add(cur);
            }
        }
        ttsSents = out;
    }

    /* خواندن این صفحه از اول (Read Aloud صفحه) */

    void ttsStartPage() {
        if (!host.loaded) {
            host.toast("فایل هنوز باز نشده است");
            return;
        }
        host.clearSelection();
        ttsStopAtWord = -1;
        ttsStopAtWordArmed = false;
        ttsBeginAt(host.pdfView.getCurrentPage(), 0);
    }

    /* v1.49: خواندن فقط متن انتخاب‌شده — قبلاً از ابتدای انتخاب تا «انتهای
       صفحه» خوانده می‌شد؛ درخواست کاربر این بود که دقیقاً همان بازهٔ انتخاب
       خوانده شود. جملهٔ آخرِ انتخاب کامل خوانده می‌شود (تا پایان همان جمله)
       و بعد متوقف می‌شود؛ ادامهٔ خودکار به صفحهٔ بعد در این حالت انجام نمی‌شود. */

    void ttsStartFromSelection() {
        if (!host.loaded) {
            host.toast("فایل هنوز باز نشده است");
            return;
        }
        if (host.selPage == null || host.selStart < 0 || host.selPageIdx < 0) {
            host.toast("اول متنی را انتخاب کن (لمس-بلند روی متن)");
            return;
        }
        ttsStopAtWord = host.selEnd;
        ttsStopAtWordArmed = true;
        ttsBeginAt(host.selPageIdx, host.selStart);
    }

    void ttsBeginAt(int pageIdx, int wordIdx) {
        ReaderActivity.SelPage sp = host.selPages.get(Integer.valueOf(pageIdx));
        if (sp == null || sp.words.isEmpty()) {
            /* کلمات همین صفحه را بگیر؛ وقتی رسید پخش ادامه می‌یابد */
            ttsReading = true;
            ttsPaused = false;
            ttsNextReadPage = pageIdx;
            ttsSessionStart();
            host.requestSelWords(pageIdx, false);
            host.toast("در حال آماده‌سازی متن صفحه…");
            ttsArmWaitTimeout(pageIdx);
            return;
        }
        ttsBuildSentences(pageIdx);
        if (ttsSents == null || ttsSents.isEmpty()) {
            host.toast("متن قابل خواندنی روی این صفحه نیست");
            ttsStopReading();
            return;
        }
        int idx = 0;
        for (int k = 0; k < ttsSents.size(); k++) {
            ReaderActivity.TtsSent s = ttsSents.get(k);
            if (wordIdx >= s.w0 && wordIdx <= s.w1) {
                idx = k;
                break;
            }
            if (s.w0 > wordIdx) {
                idx = k;
                break;
            }
        }
        ttsReading = true;
        ttsPaused = false;
        ttsNoEngineHinted = false;
        ttsSessionStart();
        ttsPlaySentenceAt(idx);
    }

    void ttsSessionStart() {
        ttsRequestAudioFocus();
        /* اندروید ۱۳+ — بدون مجوز اعلان، اعلان مدیا دیده نمی‌شود */
        try {
            if (Build.VERSION.SDK_INT >= 33
                    && host.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                host.requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 90);
            }
        } catch (Throwable ignored) {
        }
        try {
            TtsSession.set(ttsController);
        } catch (Throwable ignored) {
        }
        TtsService.startReading(host);
        ttsEnsureBar();
    }

    /* v1.49: سپر مهلت انتظار — اگر کلمات صفحه هرگز نرسید (موتور متن مرده/
       فایل قفل شد)، خواندن به‌جای گیرکردنِ ابدی تمیز متوقف می‌شود */

    void ttsArmWaitTimeout(final int pageIdx) {
        final long armedAt = ++ttsWaitSeq;
        host.ui.postDelayed(new Runnable() {
            @Override public void run() {
                if (ttsNextReadPage == pageIdx && ttsReading
                        && ttsWaitSeq == armedAt) {
                    ttsNextReadPage = -1;
                    ttsStopReading();
                    host.toast("آماده‌سازی متن صفحهٔ " + Fa.d(pageIdx + 1) + " بیش از حد طول کشید — خواندن متوقف شد");
                }
            }
        }, 30000L);
    }

    /* صفحه‌ای که کلماتش تازه رسید — از جملهٔ اول پخش ادامه می‌یابد */

    void ttsContinuePage(int pageIdx) {
        if (!ttsReading || ttsPaused) return;
        ttsBuildSentences(pageIdx);
        if (ttsSents == null || ttsSents.isEmpty()) {
            /* صفحهٔ بدون متن → صفحهٔ بعد */
            ttsPageIdx = pageIdx;
            ttsSents = null;
            ttsAdvance();
            return;
        }
        ttsPlaySentenceAt(0);
    }

    void ttsPlaySentenceAt(int idx) {
        if (ttsSents == null || idx < 0 || idx >= ttsSents.size()) {
            ttsAdvance();
            return;
        }
        ttsIdx = idx;
        ReaderActivity.TtsSent s = ttsSents.get(idx);
        /* صفحهٔ جمله باید دیده شود (ورق خودکار هنگام خواندن).
           v1.49 FIX: قبلاً jumpTo(page+1, true) با انیمیشن زده می‌شد و بلافاصله
           بعدش ttsEnsureSentenceVisible با آفستِ نیمه‌کارهٔ همان انیمیشن صفحه را
           محاسبه و moveTo می‌زد — دو حرکت روی یک محور مسابقه می‌کردند و در نتیجه
           نمایش به ابتدای سند می‌پرید در حالی که خواندن از صفحهٔ درست ادامه می‌یافت.
           حالا پرشِ تغییر صفحه بدون انیمیشن و بلافاصله قطعی (moveTo+loadPageByOffset)
           انجام می‌شود؛ سپس وسط‌چینِ جمله روی آفستِ پایدار اجرا می‌شود. */
        try {
            if (host.pdfView.getCurrentPage() != ttsPageIdx) {
                host.pdfView.stopAnimations();   /* v1.49: انیمیشن نیمه‌کاره با پرش مسابقه نکند */
                host.jumpTo(ttsPageIdx + 1, false);   /* jumpTo این اکتیویتی ۱-مبنا است */
                host.pdfView.loadPageByOffset();
            }
        } catch (Throwable ignored) {
        }
        ttsEnsureSentenceVisible(s);
        if (host.selOverlay != null) host.selOverlay.invalidate();
        ttsUpdateBar();
        ttsRouteAndSpeak(s);
    }

    /* جملات در دید بمانند (وقتی صفحه از بلندای دید بیرون است وسط‌چین می‌شود) */

    void ttsEnsureSentenceVisible(ReaderActivity.TtsSent s) {
        try {
            ReaderActivity.SelPage sp = host.selPages.get(Integer.valueOf(ttsPageIdx));
            if (sp == null || sp.vh <= 0 || s.box == null) return;
            RectF pr = host.pageRect(ttsPageIdx);
            if (pr.width() <= 0) return;
            float kh = pr.height() / sp.vh;
            float top = pr.top + s.box.top * kh;
            float bottom = pr.top + s.box.bottom * kh;
            float h = host.pdfView.getHeight();
            float viewTop = -host.pdfView.getCurrentYOffset();
            if (bottom < viewTop + host.dp(28) || top > viewTop + h - host.dp(28)) {
                float targetCenter = (top + bottom) / 2.0f;
                host.pdfView.stopAnimations();   /* v1.49: وسط‌چینی قطعی، بدون بازنویسیِ بعدی توسط انیمیشن */
                host.pdfView.moveTo(host.pdfView.getCurrentXOffset(), h / 2.0f - targetCenter);
                host.pdfView.loadPageByOffset();
            }
        } catch (Throwable ignored) {
        }
    }

    /* ---------- بخش ۲۱: انتخاب موتور هر جمله + جابه‌جایی خودکار ---------- */

    void ttsRouteAndSpeak(ReaderActivity.TtsSent s) {
        ttsPrefetchNext(s);
        String lang = s.lang;
        String engine = VoiceModels.prefEngine(host, lang);
        SystemTtsEngine sys = SystemTtsEngine.get(host);
        sys.ensure(null);
        if ("piper".equals(engine)) {
            String vid = VoiceModels.activeVoice(host, lang);
            if (vid != null && vid.length() > 0 && VoiceModels.isReady(host, vid)) {
                PiperEngine.get(host).speak(vid, lang, s.text, ttsSpeed, ttsListener);
                return;
            }
            if (sys.hasLanguage(lang)) {
                if (!ttsNoEngineHinted) {
                    ttsNoEngineHinted = true;
                    host.toast("مدل Piper برای این زبان نصب نیست — فعلاً با صدای سیستم می‌خوانم");
                }
                sys.speak(lang, null, s.text, ttsSpeed, ttsListener);
                return;
            }
            ttsShowNoEngine(lang);
            return;
        }
        if (sys.hasLanguage(lang)) {
            sys.speak(lang, null, s.text, ttsSpeed, ttsListener);
            return;
        }
        String vid = VoiceModels.activeVoice(host, lang);
        if (vid != null && vid.length() > 0 && VoiceModels.isReady(host, vid)) {
            PiperEngine.get(host).speak(vid, lang, s.text, ttsSpeed, ttsListener);
            return;
        }
        ttsShowNoEngine(lang);
    }

    /* v1.49: پیش‌تولید جملهٔ بعد — همزمان با پخشِ همین جمله، صداِ جملهٔ بعد
       پشت صحنه ساخته می‌شود؛ مکثِ بعد از نقطه (مشکل مدل فارسی) تقریباً صفر
       می‌شود. فقط وقتی موتور Piper است (سیستم خودش صف داخلی ندارد). */

    void ttsPrefetchNext(ReaderActivity.TtsSent cur) {
        try {
            if (ttsSents == null || cur == null) return;
            int next = ttsIdx + 1;
            if (next < 0 || next >= ttsSents.size()) return;
            ReaderActivity.TtsSent n = ttsSents.get(next);
            if (n == null || n.text == null || n.text.trim().length() == 0) return;
            String engine = VoiceModels.prefEngine(host, n.lang);
            if (!"piper".equals(engine)) return;
            String vid = VoiceModels.activeVoice(host, n.lang);
            if (vid == null || vid.length() == 0 || !VoiceModels.isReady(host, vid)) return;
            PiperEngine.get(host).prefetch(vid, n.text, ttsSpeed);
        } catch (Throwable ignored) {
        }
    }

    /* هیچ موتوری برای این زبان نیست — پیام خوانا + پرش به صفحهٔ مدل‌ها */

    void ttsShowNoEngine(final String lang) {
        boolean isFa = "fa".equals(lang);
        ttsStopReading();
        /* v1.50: بازطراحی مدرن — کارت به‌جای AlertDialog خام */
        try {
            final Dialog[] dlg = new Dialog[1];
            LinearLayout card = Modern.cardContent(host);
            card.addView(Modern.sheetHeader(host,
                    isFa ? "صدای فارسی آماده نیست" : "صدای انگلیسی آماده نیست", null,
                    new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                        }
                    }));
            TextView msg = new TextView(host);
            msg.setText(isFa
                    ? "برای خواندن فارسی با کیفیت طبیعی، مدل صوتی Piper (حدود ۶۱ مگابایت) را یک بار دانلود کن.\nیا از تنظیمات گوشی صدای TTS فارسی نصب کن."
                    : "صدای انگلیسی در موتور سیستم نصب نیست.\nمی‌توانی مدل انگلیسی Piper (حدود ۶۰ مگابایت) را دانلود کنی یا صدای انگلیسی را در تنظیمات گوشی نصب کنی.");
            msg.setTextColor(Modern.C_TEXT);
            msg.setTextSize(13.5f);
            int m = host.dp(18);
            msg.setPadding(m, host.dp(6), m, host.dp(8));
            card.addView(msg, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout btns = new LinearLayout(host);
            btns.setOrientation(LinearLayout.HORIZONTAL);
            btns.setGravity(android.view.Gravity.END);
            TextView ok = Modern.filledBtn(host, "باشه", 0x2EFFFFFF, Modern.C_TEXT, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                }
            });
            btns.addView(ok, new LinearLayout.LayoutParams(-2, -2));
            TextView go = Modern.filledBtn(host, "مدل‌های صوتی", Modern.C_ACCENT, 0xFF0B1220, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                    try {
                        host.startActivity(new Intent(host, VoiceModelsActivity.class));
                    } catch (Throwable ignored) {
                    }
                }
            });
            LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-2, -2);
            glp.setMargins(host.dp(8), 0, 0, 0);
            btns.addView(go, glp);
            LinearLayout.LayoutParams blpRow = new LinearLayout.LayoutParams(-1, -2);
            blpRow.topMargin = host.dp(10);
            card.addView(btns, blpRow);
            dlg[0] = host.trackDialog(Modern.showCard(host, card));
        } catch (Throwable ignored) {
            host.toast(isFa ? "صدای فارسی آماده نیست — از «مدل‌های صوتی» دانلود کن" : "صدای انگلیسی آماده نیست");
        }
    }

    /* ---------- جریان پخش ---------- */

    final TtsEngine.Listener ttsListener = new TtsEngine.Listener() {
        @Override
        public void onStart() {
            host.ui.post(new Runnable() {
                @Override public void run() {
                    ttsUpdateBar();
                }
            });
        }

        @Override
        public void onDone(final boolean completed) {
            host.ui.post(new Runnable() {
                @Override public void run() {
                    if (ttsReading && !ttsPaused && completed) {
                        ttsAdvance();
                    }
                }
            });
        }

        @Override
        public void onError(final String message) {
            host.ui.post(new Runnable() {
                @Override public void run() {
                    if (ttsReading && !ttsPaused) {
                        host.toastOnce(message != null ? message : "خواندن این جمله ممکن نشد");
                        ttsAdvance();
                    }
                }
            });
        }
    };

    void ttsAdvance() {
        if (!ttsReading || ttsPaused) return;
        /* v1.49: حالت «فقط همین انتخاب» — جملهٔ بعدی که کلمهٔ پایانیِ انتخاب را
           دربر نگیرد، خوانده نمی‌شود؛ خواندن تمیز تمام می‌شود. */
        if (ttsStopAtWordArmed && ttsSents != null && ttsIdx >= 0
                && ttsIdx < ttsSents.size()) {
            ReaderActivity.TtsSent cur = ttsSents.get(ttsIdx);
            if (cur.w1 < ttsStopAtWord) {
                /* هنوز داخل بازهٔ انتخابیم — ادامه */
            } else {
                ttsStopAtWordArmed = false;
                ttsStopAtWord = -1;
                ttsStopReading();
                host.toast("خواندن متن انتخاب‌شده تمام شد ✓");
                return;
            }
        }
        if (ttsSents != null && ttsIdx + 1 < ttsSents.size()) {
            ttsPlaySentenceAt(ttsIdx + 1);
            return;
        }
        int next = ttsPageIdx + 1;
        if (next >= host.pageCount) {
            ttsStopReading();
            host.toast("خواندن تمام شد ✓");
            return;
        }
        ReaderActivity.SelPage sp = host.selPages.get(Integer.valueOf(next));
        if (sp != null) {
            ttsBuildSentences(next);
            ttsPlaySentenceAt(0);
        } else {
            ttsNextReadPage = next;
            host.requestSelWords(next, true);
            ttsArmWaitTimeout(next);
        }
    }

    void ttsPauseResume() {
        if (!ttsReading) return;
        if (!ttsPaused) {
            ttsPaused = true;
            try {
                PiperEngine.get(host).stop();
            } catch (Throwable ignored) {
            }
            try {
                SystemTtsEngine.get(host).stop();
            } catch (Throwable ignored) {
            }
            ttsUpdateBar();
            TtsSession.notifyStateChanged(host);
        } else {
            ttsPaused = false;
            ttsPlaySentenceAt(Math.max(0, ttsIdx));
            TtsSession.notifyStateChanged(host);
        }
    }

    void ttsStopReading() {
        boolean was = ttsReading;
        ttsReading = false;
        ttsPaused = false;
        ttsNextReadPage = -1;
        ttsStopAtWord = -1;          /* v1.49: پایان حالت «فقط همین انتخاب» */
        ttsStopAtWordArmed = false;
        try {
            PiperEngine.get(host).stop();
        } catch (Throwable ignored) {
        }
        try {
            SystemTtsEngine.get(host).stop();
        } catch (Throwable ignored) {
        }
        try {
            TtsSession.clear(ttsController);
        } catch (Throwable ignored) {
        }
        if (was) TtsService.stopReading(host);
        ttsAbandonAudioFocus();
        if (host.selOverlay != null) host.selOverlay.invalidate();
        if (ttsBar != null) ttsBar.setVisibility(View.GONE);
    }

    void ttsNextSentence() {
        if (!ttsReading) return;
        ttsPaused = false;
        if (ttsSents != null && ttsIdx + 1 < ttsSents.size()) {
            ttsPlaySentenceAt(ttsIdx + 1);
        } else {
            ttsAdvance();
        }
    }

    void ttsPrevSentence() {
        if (!ttsReading || ttsSents == null || ttsIdx <= 0) return;
        ttsPaused = false;
        ttsPlaySentenceAt(ttsIdx - 1);
    }

    /* ---------- نوار شناور پخش ---------- */
    /* v1.50: بازطراحی نوار پخش — دکمه‌ها در یک ردیفِ منظم و وسط‌چین، هر
       دکمه با پس‌زمینهٔ گرد لمس‌پذیر (نه آیکونِ لختِ فشرده کنار هم)؛
       قبلاً prev/pause/next/stop با margin=1dp چسبیده و نامنظم بودند. */

    void ttsEnsureBar() {
        if (ttsBar != null) {
            ttsBar.setVisibility(View.VISIBLE);
            ttsUpdateBar();
            return;
        }
        LinearLayout bar = new LinearLayout(host);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        int p = host.dp(12);
        bar.setPadding(p, host.dp(10), p, host.dp(12));
        bar.setBackground(Modern.roundStroke(0xF20B1220, 22, Modern.C_BORDER, 1, host));
        bar.setElevation(host.dp(8));
        TextView info = new TextView(host);
        info.setTextColor(Modern.C_TEXT);
        info.setTextSize(11.5f);
        info.setSingleLine(true);
        info.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        info.setPadding(host.dp(6), 0, host.dp(6), host.dp(6));
        android.widget.LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(-2, -2);
        ilp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        bar.addView(info, ilp);
        ttsBarInfo = info;
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER);
        android.widget.LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = host.dp(2);
        bar.addView(row, rlp);
        /* دکمهٔ قبلی */
        ImageButton prev = ttsBarBtn(android.R.drawable.ic_media_previous, Modern.C_TEXT, host.dp(6), host.dp(4), host.dp(4), host.dp(4));
        prev.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                ttsPrevSentence();
            }
        });
        row.addView(prev, ttsBtnParams());
        /* دکمهٔ پخش/مکث — برجسته */
        ImageButton play = ttsBarBtn(android.R.drawable.ic_media_pause, 0xFF0B1220, host.dp(6), host.dp(4), host.dp(4), host.dp(4));
        play.setBackground(Modern.circle(Modern.C_ACCENT, host));
        play.setColorFilter(0xFF0B1220);
        play.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                ttsPauseResume();
            }
        });
        LinearLayout.LayoutParams plp = ttsBtnParams();
        plp.setMargins(host.dp(6), host.dp(4), host.dp(6), host.dp(4));
        row.addView(play, plp);
        ttsBarPlay = play;
        /* دکمهٔ بعدی */
        ImageButton next = ttsBarBtn(android.R.drawable.ic_media_next, Modern.C_TEXT, host.dp(4), host.dp(4), host.dp(6), host.dp(4));
        next.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                ttsNextSentence();
            }
        });
        row.addView(next, ttsBtnParams());
        /* جداکننده */
        View d = new View(host);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(host.dp(1), host.dp(26));
        dlp.gravity = android.view.Gravity.CENTER_VERTICAL;
        dlp.setMargins(host.dp(10), 0, host.dp(10), 0);
        d.setLayoutParams(dlp);
        d.setBackgroundColor(0x2EFFFFFF);
        row.addView(d);
        /* دکمهٔ قطع */
        ImageButton stop = ttsBarBtn(android.R.drawable.ic_menu_close_clear_cancel, Modern.C_DANGER, host.dp(4), host.dp(4), host.dp(4), host.dp(4));
        stop.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                ttsStopReading();
            }
        });
        row.addView(stop, ttsBtnParams());
        ttsBar = bar;
        ViewGroup parent = (ViewGroup) host.pdfView.getParent();
        parent.addView(bar, new android.widget.FrameLayout.LayoutParams(-2, -2,
                android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL));
        ((android.widget.FrameLayout.LayoutParams) bar.getLayoutParams()).bottomMargin = host.dp(22);
        ttsUpdateBar();
    }

    /* v1.50: دکمهٔ گرد لمس‌پذیر نوار پخش */

    ImageButton ttsBarBtn(int iconRes, int tint, int ml, int mt, int mr, int mb) {
        ImageButton b = new ImageButton(host);
        b.setImageResource(iconRes);
        b.setColorFilter(tint);
        b.setBackground(Modern.circle(0x33FFFFFF, host));
        int p2 = host.dp(9);
        b.setPadding(p2, p2, p2, p2);
        return b;
    }

    LinearLayout.LayoutParams ttsBtnParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(host.dp(40), host.dp(40));
        lp.gravity = android.view.Gravity.CENTER_VERTICAL;
        return lp;
    }

    void ttsUpdateBar() {
        if (ttsBarInfo != null && ttsSents != null && ttsIdx >= 0 && ttsIdx < ttsSents.size()) {
            String s = ttsSents.get(ttsIdx).text;
            if (s.length() > 44) s = s.substring(0, 44) + "…";
            ttsBarInfo.setText("صفحهٔ " + Fa.d(ttsPageIdx + 1)
                    + " • جملهٔ " + Fa.d(ttsIdx + 1) + " از " + Fa.d(ttsSents.size())
                    + (ttsPaused ? " — متوقف" : "") + "\n" + s);
        }
        if (ttsBarPlay != null) {
            ttsBarPlay.setImageResource(ttsPaused
                    ? android.R.drawable.ic_media_play : android.R.drawable.ic_media_pause);
        }
    }

    /* ---------- تمرکز صدا: موسیقی دیگر کم‌صدا می‌شود هنگام خواندن ---------- */

    void ttsRequestAudioFocus() {
        try {
            AudioManager am = (AudioManager) host.getSystemService(Context.AUDIO_SERVICE);
            am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
        } catch (Throwable ignored) {
        }
    }

    void ttsAbandonAudioFocus() {
        try {
            AudioManager am = (AudioManager) host.getSystemService(Context.AUDIO_SERVICE);
            am.abandonAudioFocus(null);
        } catch (Throwable ignored) {
        }
    }

    /* ---------- کنترلر اعلان مدیا ---------- */

    final TtsSession.Controller ttsController = new TtsSession.Controller() {
        @Override public void ttsPlayPause() {
            host.ui.post(new Runnable() {
                @Override public void run() {
                    ttsPauseResume();
                }
            });
        }

        @Override public void ttsStop() {
            host.ui.post(new Runnable() {
                @Override public void run() {
                    ttsStopReading();
                    TtsService.stopReading(host);
                }
            });
        }

        @Override public void ttsNext() {
            host.ui.post(new Runnable() {
                @Override public void run() {
                    ttsNextSentence();
                }
            });
        }

        @Override public void ttsPrev() {
            host.ui.post(new Runnable() {
                @Override public void run() {
                    ttsPrevSentence();
                }
            });
        }

        @Override public boolean ttsIsReading() {
            return ttsReading;
        }

        @Override public boolean ttsIsPaused() {
            return ttsPaused;
        }

        @Override public String ttsPreview() {
            try {
                if (ttsSents != null && ttsIdx >= 0
                        && ttsIdx < ttsSents.size()) {
                    String s = ttsSents.get(ttsIdx).text;
                    return s.length() > 60 ? s.substring(0, 60) + "…" : s;
                }
            } catch (Throwable ignored) {
            }
            return "";
        }
    };

    /* ---------- پنل «خواندن با صدا» (بخش ۱۹+۲۱) ---------- */

    void ttsSheet() {
        /* بخش ۲۱: پیش‌بارگذاری موتورها به‌محض باز شدن پنل — تأخیر اولین جمله کم می‌شود */
        try {
            SystemTtsEngine.get(host).ensure(null);
            String vfa = VoiceModels.activeVoice(host, "fa");
            if (vfa != null && vfa.length() > 0 && VoiceModels.isReady(host, vfa)) {
                PiperEngine.get(host).preload(vfa);
            }
            String ven = VoiceModels.activeVoice(host, "en");
            if (ven != null && ven.length() > 0 && VoiceModels.isReady(host, ven)) {
                PiperEngine.get(host).preload(ven);
            }
        } catch (Throwable ignored) {
        }

        final Dialog[] dlg = new Dialog[1];
        LinearLayout sheet = Modern.sheetContent(host);
        sheet.addView(Modern.sheetHeader(host, "خواندن با صدا", "جمله‌به‌جمله با هایلایت — حتی در پس‌زمینه", new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { dlg[0].dismiss(); } catch (Throwable ignored) {}
            }
        }));

        /* وضعیت/شروع */
        if (ttsReading) {
            sheet.addView(Modern.sectionLabelCompact(host, "در حال پخش"));
            LinearLayout st = new LinearLayout(host);
            st.setOrientation(LinearLayout.HORIZONTAL);
            st.setGravity(android.view.Gravity.CENTER_VERTICAL);
            TextView s1 = Modern.tonalBtn(host, ttsPaused ? "ادامه" : "توقف موقت", Modern.C_ACCENT, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    ttsPauseResume();
                    try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                }
            });
            st.addView(s1);
            TextView s2 = Modern.tonalBtn(host, "پایان خواندن", Modern.C_DANGER, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    ttsStopReading();
                    try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                }
            });
            LinearLayout.LayoutParams s2p = new LinearLayout.LayoutParams(-2, -2);
            s2p.setMarginStart(host.dp(8));
            st.addView(s2, s2p);
            sheet.addView(st);
        } else {
            sheet.addView(Modern.sectionLabelCompact(host, "شروع"));
            LinearLayout st = new LinearLayout(host);
            st.setOrientation(LinearLayout.HORIZONTAL);
            st.setGravity(android.view.Gravity.CENTER_VERTICAL);
            TextView s1 = Modern.filledBtn(host, "خواندن این صفحه", Modern.C_ACCENT, 0xFF06251F, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                    ttsStartPage();
                }
            });
            st.addView(s1);
            TextView s2 = Modern.tonalBtn(host, "از متن انتخاب‌شده", 0xFF4DD0E1, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                    ttsStartFromSelection();
                }
            });
            LinearLayout.LayoutParams s2p = new LinearLayout.LayoutParams(-2, -2);
            s2p.setMarginStart(host.dp(8));
            st.addView(s2, s2p);
            sheet.addView(st);
            TextView hint = new TextView(host);
            hint.setText("متن را انتخاب کن و در نوار انتخاب دکمهٔ بلندگوش را بزن تا «همان متنِ انتخاب‌شده» خوانده شود (نه کل صفحه) و در پایان توقف بیفتد. متن دوزبانه خودکار تشخیص داده می‌شود و هر جمله با موتور همان زبان خوانده می‌شود.");
            hint.setTextColor(Modern.C_MUTED);
            hint.setTextSize(10.5f);
            hint.setPadding(0, host.dp(6), 0, 0);
            sheet.addView(hint);
        }

        /* سرعت */
        sheet.addView(Modern.sectionLabelCompact(host, "سرعت خواندن"));
        LinearLayout sr = new LinearLayout(host);
        sr.setOrientation(LinearLayout.HORIZONTAL);
        sr.setGravity(android.view.Gravity.CENTER_VERTICAL);
        final TextView spLabel = new TextView(host);
        spLabel.setTextColor(Modern.C_MUTED);
        spLabel.setTextSize(12f);
        spLabel.setText(Fa.d(String.format(Locale.US, "%.1f", ttsSpeed)) + "×");
        spLabel.setPadding(0, 0, host.dp(10), 0);
        sr.addView(spLabel);
        SeekBar ss = Modern.slider(host);
        Modern.tintSlider(ss, Modern.C_ACCENT, host);
        ss.setMax(15);
        ss.setProgress((int) Math.round((ttsSpeed - 0.5f) * 10.0f));
        ss.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int val, boolean fromUser) {
                ttsSpeed = 0.5f + val / 10.0f;
                spLabel.setText(Fa.d(String.format(Locale.US, "%.1f", ttsSpeed)) + "×");
            }

            @Override public void onStartTrackingTouch(SeekBar s) {
            }

            @Override public void onStopTrackingTouch(SeekBar s) {
            }
        });
        sr.addView(ss, new LinearLayout.LayoutParams(0, -2, 1f));
        sheet.addView(sr);

        /* موتور و صدای هر زبان (بخش ۲۱) */
        sheet.addView(ttsLangRow("fa"));
        sheet.addView(ttsLangRow("en"));

        /* مدل‌ها */
        sheet.addView(Modern.divider(host));
        sheet.addView(Modern.compactRow(host, R.drawable.ic_m_vol, 0xFFBCAAA4, "مدل‌های صوتی…",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                        try {
                            host.startActivity(new Intent(host, VoiceModelsActivity.class));
                        } catch (Throwable t) {
                            host.toast("صفحهٔ مدل‌ها باز نشد");
                        }
                    }
                }));

        int cap = Math.round(host.getResources().getDisplayMetrics().heightPixels * 0.7f);
        dlg[0] = host.trackSheet(Modern.showSheetCapped(host, sheet, cap), new Runnable() {
            @Override public void run() { ttsSheet(); }
        });
    }

    /* ردیف انتخاب موتور/صدای یک زبان — انتخاب ذخیره می‌شود («همیشه با همین موتور») */

    LinearLayout ttsLangRow(final String lang) {
        LinearLayout wrap = new LinearLayout(host);
        wrap.setOrientation(LinearLayout.VERTICAL);
        String title = "fa".equals(lang) ? "زبان فارسی" : "زبان انگلیسی";
        wrap.addView(Modern.sectionLabelCompact(host, title));

        String engine = VoiceModels.prefEngine(host, lang);
        StringBuilder cur = new StringBuilder();
        if ("piper".equals(engine)) {
            String vid = VoiceModels.activeVoice(host, lang);
            if (vid != null && vid.length() > 0 && VoiceModels.isReady(host, vid)) {
                cur.append("Piper — ").append(voiceLabel(vid, lang));
            } else {
                cur.append("Piper — مدل نصب نیست");
            }
        } else {
            SystemTtsEngine.get(host).ensure(null);
            cur.append("موتور سیستم گوشی");
        }
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int p = host.dp(10);
        row.setPadding(p, host.dp(8), p, host.dp(8));
        row.setBackground(Modern.rippleRound(Modern.C_CARD, 12, Modern.C_RIPPLE, host));
        ImageView iv = new ImageView(host);
        iv.setImageResource(R.drawable.ic_m_speak);
        iv.setColorFilter("fa".equals(lang) ? 0xFF4DD0E1 : 0xFFFFB74D);
        row.addView(iv, new LinearLayout.LayoutParams(host.dp(20), host.dp(20)));
        TextView t = new TextView(host);
        t.setText("موتور خواندن: " + cur.toString());
        t.setTextColor(Modern.C_TEXT);
        t.setTextSize(13f);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1f);
        tl.setMarginStart(host.dp(10));
        row.addView(t, tl);
        TextView pick = Modern.tonalBtn(host, "تغییر", Modern.C_ACCENT, new View.OnClickListener() {
            @Override public void onClick(View v) {
                ttsPickEngine(lang);
            }
        });
        row.addView(pick);
        wrap.addView(row);
        return wrap;
    }

    String voiceLabel(String voiceId, String lang) {
        for (VoiceModels.Rec r : VoiceModels.list(host)) {
            if (r.id.equals(voiceId)) {
                String custom = host.getSharedPreferences("voices", 0).getString("name_" + voiceId, null);
                return (custom != null && custom.length() > 0 ? custom : r.name) + " (" + r.region + ")";
            }
        }
        return voiceId;
    }

    /* دیالوگ انتخاب موتور + صدای یک زبان (بخش ۲۱) */

    void ttsPickEngine(final String lang) {
        /* v1.50: بازطراحی مدرن — شیتِ گزینه‌ها به‌جای AlertDialog خام */
        final List<String> labels = new ArrayList<>();
        final List<String> actions = new ArrayList<>();   /* "system" یا voiceId */
        SystemTtsEngine.get(host).ensure(null);
        boolean sysOk = SystemTtsEngine.get(host).hasLanguage(lang);
        for (VoiceModels.Rec r : VoiceModels.list(host)) {
            if (r.lang.equals(lang) && r.state == VoiceModels.S_READY) {
                labels.add("Piper — " + r.name + " (" + r.region + ")" + (r.starred ? "  ★" : ""));
                actions.add(r.id);
            }
        }
        labels.add("موتور سیستم گوشی" + (sysOk ? "" : " (صدای این زبان نصب نیست)"));
        actions.add("system");
        labels.add("مدل‌های صوتی… (دانلود و درون‌ریزی)");
        actions.add("__models__");
        final Dialog[] dlg = new Dialog[1];
        LinearLayout sheet = Modern.sheetContent(host);
        sheet.addView(Modern.sheetHeader(host,
                "fa".equals(lang) ? "صدای فارسی" : "صدای انگلیسی",
                "انتخاب موتور خواندن این زبان", new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { dlg[0].dismiss(); } catch (Throwable ignored) {}
            }
        }));
        for (int i = 0; i < labels.size(); i++) {
            final String act = actions.get(i);
            String lbl = labels.get(i);
            sheet.addView(Modern.compactRow(host,
                    "__models__".equals(act) ? R.drawable.ic_m_list : R.drawable.ic_m_speak,
                    "__models__".equals(act) ? 0xFF64B5F6
                            : ("system".equals(act) ? 0xFFFFB74D : 0xFF4DD0E1),
                    lbl, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                    if ("__models__".equals(act)) {
                        try {
                            host.startActivity(new Intent(host, VoiceModelsActivity.class));
                        } catch (Throwable ignored) {
                        }
                        return;
                    }
                    if ("system".equals(act)) {
                        VoiceModels.setPrefEngine(host, lang, "system");
                    } else {
                        VoiceModels.setActiveVoice(host, lang, act);
                        VoiceModels.setPrefEngine(host, lang, "piper");
                        PiperEngine.get(host).preload(act);
                    }
                    host.toast("برای این زبان ذخیره شد ✓");
                }
            }), new LinearLayout.LayoutParams(-1, -2));
        }
        dlg[0] = host.trackDialog(Modern.showSheetAuto(host, sheet));
    }


    /* v7: immersive fullscreen (hide status + navigation bars) */
}
