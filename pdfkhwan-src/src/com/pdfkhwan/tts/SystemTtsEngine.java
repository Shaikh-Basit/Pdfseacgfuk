package com.pdfkhwan.tts;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;

import com.pdfkhwan.app.CrashGuard;

import java.util.Locale;

/* ====================================================================
   v1.47 — موتور گفتار «سیستم»: همان TTS اندروید.
   • صفر دانلود، صفر حجم — برای انگلیسی پیش‌فرض است.
   • توقف/ادامه: سیستم TTS «توقف واقعی» ندارد؛ Pause = قطع +
     یادآوری جمله، Resume = خواندن دوبارهٔ همان جمله از اول
     (خوانندهٔ جمله‌ها همین را مدیریت می‌کند).
   ==================================================================== */
public final class SystemTtsEngine implements TtsEngine {

    private static SystemTtsEngine instance;

    public static synchronized SystemTtsEngine get(Context ctx) {
        if (instance == null) instance = new SystemTtsEngine(ctx.getApplicationContext());
        return instance;
    }

    private final Context ctx;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextToSpeech tts;
    private boolean ready = false;
    private boolean initing = false;
    private boolean destroyed = false;
    private Listener active;
    private int uttSeq = 0;
    /* v1.47: جملهٔ در انتظار گرم‌شدن موتور — اولین خواندن چیزی را از دست نمی‌دهد */
    private String[] pendingSpeak;   /* {lang, text} */
    private float pendingSpeed = 1f;
    private Listener pendingListener = null;

    private SystemTtsEngine(Context ctx) {
        this.ctx = ctx;
    }

    @Override
    public String id() {
        return "system";
    }

    /** ساخت تنبل موتور (مصرف صفر تا اولین نیاز) */
    public synchronized void ensure(final Runnable onReady) {
        if (destroyed) {
            return;
        }
        if (ready) {
            if (onReady != null) onReady.run();
            return;
        }
        if (tts != null && initing) {
            return;
        }
        initing = true;
        try {
            tts = new TextToSpeech(ctx, new TextToSpeech.OnInitListener() {
                @Override
                public void onInit(int status) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            synchronized (SystemTtsEngine.this) {
                                initing = false;
                                ready = status == 0;
                                /* جملهٔ منتظر را همین حالا بخوان */
                                if (ready && pendingSpeak != null) {
                                    String lang = pendingSpeak[0];
                                    String text = pendingSpeak[1];
                                    float speed = pendingSpeed;
                                    Listener l = pendingListener;
                                    pendingSpeak = null;
                                    pendingListener = null;
                                    SystemTtsEngine.this.speakDirect(lang, text, speed, l);
                                }
                            }
                        }
                    });
                }
            });
        } catch (Throwable t) {
            initing = false;
            CrashGuard.append(ctx, "systemTts: " + CrashGuard.stackToString(t));
        }
    }

    @Override
    public synchronized boolean isAvailable(final String lang) {
        if (!ready || tts == null) return false;
        try {
            int r = tts.setLanguage(localeOf(lang));
            return r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** صدا/زبان سیستم برای این زبان موجود است؟ (بدون تغییر وضعیت پخش) */
    public synchronized boolean hasLanguage(String lang) {
        if (!ready || tts == null) return false;
        return isAvailable(lang);
    }

    private static Locale localeOf(String lang) {
        if ("en".equals(lang)) return Locale.US;
        return new Locale("fa");
    }

    @Override
    public synchronized void speak(final String lang, String voiceId, String text, float speed, final Listener l) {
        ensure(null);
        if (!ready || tts == null) {
            if (initing) {
                /* موتور در حال گرم‌شدن — جمله صف شود تا آماده شد خوانده شود */
                pendingSpeak = new String[]{lang, text};
                pendingSpeed = speed;
                pendingListener = l;
                /* مسابقهٔ ظریف: ممکن است همین الان آماده شده باشد */
                if (ready) {
                    String[] p = pendingSpeak;
                    Listener pl = pendingListener;
                    float sp = pendingSpeed;
                    pendingSpeak = null;
                    pendingListener = null;
                    speakDirect(p[0], p[1], sp, pl);
                }
                return;
            }
            if (l != null) l.onError("موتور صدای سیستم در دسترس نیست");
            return;
        }
        speakDirect(lang, text, speed, l);
    }

    private synchronized void speakDirect(final String lang, String text, float speed, final Listener l) {
        if (!ready || tts == null) {
            if (l != null) l.onError("موتور صدای سیستم در دسترس نیست");
            return;
        }
        try {
            int avail = tts.setLanguage(localeOf(lang));
            if (avail == TextToSpeech.LANG_MISSING_DATA || avail == TextToSpeech.LANG_NOT_SUPPORTED) {
                if (l != null) l.onError("صدای " + ("en".equals(lang) ? "انگلیسی" : "فارسی")
                        + " در موتور سیستم نصب نیست");
                return;
            }
            tts.setSpeechRate(speed);
            stopInternal();
            active = l;
            final String utt = "pk_sys_" + (++uttSeq);
            try {
                tts.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener() {
                    @Override
                    public void onStart(String id) {
                        if (!utt.equals(id)) return;
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                if (active == l) l.onStart();
                            }
                        });
                    }

                    @Override
                    public void onDone(String id) {
                        if (!utt.equals(id)) return;
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                Listener a = active;
                                active = null;
                                if (a == l) l.onDone(true);
                            }
                        });
                    }

                    @Override
                    public void onError(String id) {
                        if (!utt.equals(id)) return;
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                Listener a = active;
                                active = null;
                                if (a == l) l.onDone(false);
                            }
                        });
                    }
                });
            } catch (Throwable ignored) {
            }
            if (l != null) l.onStart();   /* بازخورد فوری برای هایلایت */
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utt);
        } catch (Throwable t) {
            CrashGuard.append(ctx, "systemTts.speak: " + CrashGuard.stackToString(t));
            if (l != null) l.onError("خواندن با موتور سیستم ممکن نشد");
        }
    }

    private void stopInternal() {
        if (tts != null) {
            try {
                tts.stop();
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public synchronized void stop() {
        stopInternal();
        Listener a = active;
        active = null;
        if (a != null) a.onDone(false);
    }

    public synchronized void shutdown() {
        destroyed = true;
        active = null;
        if (tts != null) {
            try {
                tts.stop();
                tts.shutdown();
            } catch (Throwable ignored) {
            }
            tts = null;
        }
        ready = false;
    }
}
