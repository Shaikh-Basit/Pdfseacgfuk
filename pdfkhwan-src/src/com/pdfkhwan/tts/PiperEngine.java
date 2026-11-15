package com.pdfkhwan.tts;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsKittenModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsMatchaModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;
import com.pdfkhwan.app.CrashGuard;

import java.io.File;
import java.util.HashMap;
import java.util.Iterator;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/* ====================================================================
   v1.47 — موتور Piper (sherpa-onnx): گفتار عصبی کاملاً آفلاین.

   معماری: دو نخ — «مولّد» و «پخش‌کننده» که با یک صفِ کوچک از هم
   جدا شده‌اند؛ جملهٔ بعدی همزمان با پخشِ جملهٔ فعلی تولید می‌شود
   (پیش‌واکشی طبیعی، وقفهٔ بین جمله‌ها تقریباً صفر).

   مدل‌ها بر اساس voiceId بارگذاری و در حافظه می‌مانند؛ اگر هر دو
   زبان (فارسی+انگلیسی) با Piper فعال باشند و حافظه کم بیاید،
   مدل دیگر آزاد و یک‌بار دوباره تلاش می‌شود.
   ==================================================================== */
public final class PiperEngine {

    private static PiperEngine instance;

    public static synchronized PiperEngine get(Context ctx) {
        if (instance == null) instance = new PiperEngine(ctx.getApplicationContext());
        return instance;
    }

    /* ---------- pipeline items ---------- */
    private static class Item {
        final String voiceId;
        final String text;
        final float speed;
        final TtsEngine.Listener listener;
        final long epoch;

        Item(String voiceId, String text, float speed, TtsEngine.Listener l, long epoch) {
            this.voiceId = voiceId;
            this.text = text;
            this.speed = speed;
            this.listener = l;
            this.epoch = epoch;
        }
    }

    private static class Clip {
        final Item item;
        final short[] pcm;
        final int sampleRate;
        final String error;

        Clip(Item i, short[] pcm, int rate) {
            this.item = i;
            this.pcm = pcm;
            this.sampleRate = rate;
            this.error = null;
        }

        Clip(Item i, String error) {
            this.item = i;
            this.pcm = null;
            this.sampleRate = 0;
            this.error = error;
        }
    }

    private final Context ctx;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final HashMap<String, OfflineTts> loaded = new HashMap<>();
    private final AtomicLong epoch = new AtomicLong(1);

    private Thread genThread;
    private Thread playThread;
    private LinkedBlockingQueue<Item> requests;
    private LinkedBlockingQueue<Clip> clips;
    /* v1.49: خط پیش‌تولید — جملهٔ بعدی پشت صحنه ساخته می‌شود */
    private LinkedBlockingQueue<Item> prefetchQ = new LinkedBlockingQueue<>();
    /* v1.49: کلیپ‌های آمادهٔ پیش‌تولید (کلید = voiceId + "\\u0000" + متن) */
    private final HashMap<String, Clip> ready = new HashMap<>();
    private AudioTrack track;
    private int trackRate = -1;
    private long writtenFrames = 0;   /* v1.49: فریم‌های نوشته‌شده در ترِک فعلی (برای drain) */
    private volatile boolean running = false;

    private PiperEngine(Context ctx) {
        this.ctx = ctx;
    }

    /* ---------- model lifecycle ---------- */

    private synchronized OfflineTts getOrLoad(String voiceId) throws Exception {
        OfflineTts t = loaded.get(voiceId);
        if (t != null) return t;

        VoiceModels.VoicePaths p = VoiceModels.paths(ctx, voiceId);
        if (p == null) throw new IllegalStateException("مدل صوتی پیدا نشد: " + voiceId);
        String problem = VoiceModels.validateFiles(ctx, voiceId);
        if (problem != null) throw new IllegalStateException(problem);

        t = buildTts(p.model, p.tokens, p.dataDir);
        loaded.put(voiceId, t);
        return t;
    }

    /** ساخت OfflineTts از مسیرها — جدا شده تا تست اعتبارسنجی هم از آن استفاده کند */
    public static OfflineTts buildTts(String modelPath, String tokensPath, String dataDir) {
        OfflineTtsVitsModelConfig vits = new OfflineTtsVitsModelConfig(
                modelPath, "", tokensPath, dataDir, "", 0.667f, 0.8f, 1.0f);
        OfflineTtsModelConfig model = new OfflineTtsModelConfig(
                vits,
                new OfflineTtsMatchaModelConfig("", "", "", "", "", "", 0.667f, 1.0f),
                new OfflineTtsKokoroModelConfig("", "", "", "", "", "", "", 1.0f),
                new OfflineTtsKittenModelConfig("", "", "", "", 1.0f),
                2, false, "cpu");
        OfflineTtsConfig config = new OfflineTtsConfig(model, "", "", 1, 0.6f);
        return new OfflineTts((android.content.res.AssetManager) null, config);
    }

    /** بارگذاری خاموش مدل (بخش ۲۱: پیش‌بارگذاری هنگام باز شدن پنل صدا) */
    public void preload(final String voiceId) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    getOrLoad(voiceId);
                } catch (Throwable t) {
                    CrashGuard.append(ctx, "piper preload " + voiceId + ": " + CrashGuard.stackToString(t));
                }
            }
        }, "piper-preload").start();
    }

    public synchronized boolean isLoaded(String voiceId) {
        return loaded.containsKey(voiceId);
    }

    public synchronized void release(String voiceId) {
        OfflineTts t = loaded.remove(voiceId);
        if (t != null) {
            try {
                t.release();
            } catch (Throwable ignored) {
            }
        }
    }

    public synchronized void releaseAll() {
        Iterator<OfflineTts> it = loaded.values().iterator();
        while (it.hasNext()) {
            try {
                it.next().release();
            } catch (Throwable ignored) {
            }
            it.remove();
        }
    }

    /* ---------- public API ---------- */

    public void speak(String voiceId, String lang, String text, float speed, TtsEngine.Listener l) {
        /* پرش به جملهٔ بعد: پخش/تولید قبلی بی‌درنگ قطع شود (مثل QUEUE_FLUSH) */
        epoch.incrementAndGet();
        if (requests != null) requests.clear();
        if (clips != null) clips.clear();
        stopTrack();
        startPipeline();   /* v1.49: قبل از عرضهٔ کلیپ — startPipeline صف‌ها را می‌سازد */
        /* v1.49: کلیپِ پیش‌تولیدشدهٔ همین متن هست؟ فوراً پخش — بدون تولید */
        Clip pre = takeReady(voiceId, text, speed);
        if (pre != null) {
            /* epoch برای این پخش تازه اعتبار دارد */
            Clip live = new Clip(new Item(voiceId, text, speed, l, epoch.get()), pre.pcm, pre.sampleRate);
            clips.offer(live);
            return;
        }
        requests.offer(new Item(voiceId, text, speed, l, epoch.get()));
    }

    /**
     * v1.49: پیش‌تولید جملهٔ بعد — همزمان با پخشِ جملهٔ فعلی، صداِ جملهٔ بعدی
     * تولید و در «کش کلیپ بعدی» نگه داشته می‌شود تا وقفهٔ بین جمله‌ها (مکثِ
     * مدل فارسی بعد از هر نقطه) تقریباً صفر شود. وقتی speak() همان متن را
     * بخواهد، کلیپ آماده فوراً پخش می‌شود (بدون تولید دوباره).
     */
    public void prefetch(final String voiceId, final String text, final float speed) {
        startPipeline();
        prefetchQ.offer(new Item(voiceId, text, speed, null, 0L));
    }

    public void stop() {
        epoch.incrementAndGet();
        if (requests != null) requests.clear();
        if (clips != null) clips.clear();
        if (prefetchQ != null) prefetchQ.clear();
        clearReady();
        stopTrack();
    }

    public void shutdown() {
        stop();
        running = false;
        if (genThread != null) genThread.interrupt();
        if (playThread != null) playThread.interrupt();
        genThread = playThread = null;
        releaseAll();
        stopTrack();
        if (prefetchQ != null) prefetchQ.clear();
        clearReady();
    }

    /**
     * آزمونِ فعال‌سازی (بخش ۲۰): مدل + فایل‌های همراه سالم‌اند؟
     * یک جملهٔ کوتاه تولید می‌شود (بدون پخش). null = سالم.
     */
    public static String testVoice(Context ctx, String voiceId) {
        try {
            VoiceModels.VoicePaths p = VoiceModels.paths(ctx, voiceId);
            if (p == null) return "مسیر مدل پیدا نشد";
            OfflineTts t = buildTts(p.model, p.tokens, p.dataDir);
            try {
                GeneratedAudio a = t.generate("سلام", 0, 1.0f);
                if (a == null || a.getSamples() == null || a.getSamples().length < 400) {
                    return "تولید صدا بی‌نتیجه بود (فایل مدل خراب است)";
                }
            } finally {
                try {
                    t.release();
                } catch (Throwable ignored) {
                }
            }
            return null;
        } catch (UnsatisfiedLinkError e) {
            return "کتابخانهٔ موتور صدا روی این گوشی بارگذاری نشد";
        } catch (Throwable t) {
            String m = t.getMessage();
            return "مدل صدا باز نشد" + (m != null && m.length() > 0 ? ": " + m : "");
        }
    }

    /* ---------- pipeline ---------- */

    private synchronized void startPipeline() {
        if (running && genThread != null && genThread.isAlive() && playThread != null && playThread.isAlive()) {
            return;
        }
        running = true;
        requests = new LinkedBlockingQueue<>();
        clips = new LinkedBlockingQueue<>();
        genThread = new Thread(new Runnable() {
            @Override
            public void run() {
                generateLoop();
            }
        }, "piper-gen");
        genThread.setDaemon(true);
        playThread = new Thread(new Runnable() {
            @Override
            public void run() {
                playLoop();
            }
        }, "piper-play");
        playThread.setDaemon(true);
        genThread.start();
        playThread.start();
    }

    private void generateLoop() {
        while (running) {
            Item it = null;
            try {
                /* v1.49: اول درخواست‌های واقعی، بعد پیش‌تولید */
                it = requests.poll();
                if (it == null) {
                    it = prefetchQ.poll(300, TimeUnit.MILLISECONDS);
                    if (it == null) continue;
                    it = new Item(it.voiceId, it.text, it.speed, null, 0L);   /* آیتم پیش‌تولید */
                }
            } catch (InterruptedException e) {
                return;
            }
            boolean isPrefetch = (it.listener == null && it.epoch == 0L);
            if (!isPrefetch && it.epoch != epoch.get()) {
                continue;   /* در اثر stop بی‌اعتبار شده */
            }
            Clip clip = null;
            try {
                OfflineTts t = obtainModel(it.voiceId);
                if (t != null) {
                    GeneratedAudio a = t.generate(it.text, 0, it.speed);
                    if (a != null && a.getSamples() != null && a.getSamples().length > 0) {
                        clip = new Clip(it, floatsToPcm16(a.getSamples()), a.getSampleRate());
                    }
                }
            } catch (Throwable t) {
                CrashGuard.append(ctx, "piper gen: " + CrashGuard.stackToString(t));
            }
            if (isPrefetch) {
                /* در کش کلیپ آماده بنشیند تا speak() بعدی فوراً پخشش کند */
                if (clip != null && clip.pcm != null) putReady(it.voiceId, it.text, it.speed, clip);
                continue;
            }
            if (clip == null) clip = new Clip(it, "تولید صدا با Piper ممکن نشد");
            try {
                clips.offer(clip, 2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /* ---------- v1.49: کش کلیپ‌های پیش‌تولید ---------- */

    private static String readyKey(String voiceId, String text, float speed) {
        return voiceId + "\u0000" + (text == null ? "" : text.trim()) + "\u0000" + Math.round(speed * 100f);
    }

    private synchronized void putReady(String voiceId, String text, float speed, Clip clip) {
        /* فقط یکی می‌ماند — جدیدتر جایگزین می‌شود */
        ready.put(readyKey(voiceId, text, speed), clip);
    }

    private synchronized Clip takeReady(String voiceId, String text, float speed) {
        return ready.remove(readyKey(voiceId, text, speed));
    }

    private synchronized void clearReady() {
        ready.clear();
    }

    /** بارگذاری مدل با پشتیبانی کمبود حافظه: مدل دیگر آزاد و دوباره تلاش */
    private OfflineTts obtainModel(String voiceId) {
        try {
            return getOrLoad(voiceId);
        } catch (OutOfMemoryError oom) {
            CrashGuard.append(ctx, "piper OOM -> release others, retry");
            synchronized (this) {
                Iterator<String> it = loaded.keySet().iterator();
                while (it.hasNext()) {
                    String id = it.next();
                    if (!id.equals(voiceId)) {
                        try {
                            loaded.get(id).release();
                        } catch (Throwable ignored) {
                        }
                        it.remove();
                    }
                }
            }
            try {
                return getOrLoad(voiceId);
            } catch (Throwable t2) {
                CrashGuard.append(ctx, "piper retry: " + CrashGuard.stackToString(t2));
                return null;
            }
        } catch (Throwable t) {
            CrashGuard.append(ctx, "piper load: " + CrashGuard.stackToString(t));
            return null;
        }
    }

    private void playLoop() {
        while (running) {
            Clip c;
            try {
                c = clips.poll(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                return;
            }
            if (c == null) continue;
            if (c.item.epoch != epoch.get()) continue;
            final TtsEngine.Listener l = c.item.listener;
            if (c.error != null) {
                final String err = c.error;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (l != null) l.onError(err);
                    }
                });
                continue;
            }
            ui.post(new Runnable() {
                @Override
                public void run() {
                    if (l != null) l.onStart();
                }
            });
            playPcm(c.pcm, c.sampleRate, c.item.epoch);
            final boolean completed = c.item.epoch == epoch.get();
            ui.post(new Runnable() {
                @Override
                public void run() {
                    if (l != null) l.onDone(completed);
                }
            });
        }
    }

    /* ---------- audio ---------- */

    private static short[] floatsToPcm16(float[] samples) {
        short[] out = new short[samples.length];
        for (int i = 0; i < samples.length; i++) {
            float v = samples[i];
            if (v > 1f) v = 1f;
            else if (v < -1f) v = -1f;
            out[i] = (short) (v * 32767f);
        }
        return out;
    }

    private synchronized void ensureTrack(int rate) {
        if (track != null && trackRate == rate) return;
        stopTrack();
        int minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int buf = Math.max(minBuf * 4, rate);   /* ≥ 0.5s بافر */
        track = new AudioTrack(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
                new AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                buf, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);
        trackRate = rate;
        writtenFrames = 0;   /* v1.49: ترِک تازه — شمارش فریم از صفر */
    }

    private synchronized void stopTrack() {
        if (track != null) {
            try {
                track.pause();
                track.flush();
                track.stop();
            } catch (Throwable ignored) {
            }
            try {
                track.release();
            } catch (Throwable ignored) {
            }
            track = null;
            trackRate = -1;
            writtenFrames = 0;
        }
    }

    private void playPcm(short[] pcm, int rate, long itemEpoch) {
        try {
            ensureTrack(rate);
            track.play();
            final int CHUNK = 4096;
            byte[] buf = new byte[CHUNK * 2];
            int off = 0;
            while (off < pcm.length) {
                if (itemEpoch != epoch.get()) {
                    stopTrack();
                    return;
                }
                int n = Math.min(CHUNK, pcm.length - off);
                for (int i = 0; i < n; i++) {
                    int b = off + i;
                    buf[i * 2] = (byte) (pcm[b] & 0xFF);
                    buf[i * 2 + 1] = (byte) ((pcm[b] >> 8) & 0xFF);
                }
                int w = track.write(buf, 0, n * 2);
                if (w > 0) writtenFrames += w / 2;   /* v1.49: شمارش فریم‌های تحویل‌شده */
                off += n;
            }
            /* v1.49 FIX: جمله‌های فارسی «یک-دو کلمهٔ آخر» خوانده نمی‌شدند.
               track.write فقط تا «پذیرشِ بافر» مسدود است، نه تا پخشِ واقعی؛
               onDone فوراً بعد از آخرین write می‌آمد و جملهٔ بعدی (speak) با
               stopTrack → pause+flush بافرِ نیمه‌پخش‌شده را دور می‌ریخت —
               انتهای صدا قطع می‌شد. حالا قبل از اعلام پایان، تا خالی‌شدن
               کامل صف سخت‌افزاری صبر می‌کنیم (Headset/PlaybackPolling). */
            if (itemEpoch == epoch.get()) {
                drainTrack();
            }
        } catch (Throwable t) {
            CrashGuard.append(ctx, "piper play: " + CrashGuard.stackToString(t));
            stopTrack();
        }
    }

    /* v1.49: انتظار تا پخشِ کامل نمونه‌های نوشته‌شده (حداکثر چند ثانیه) */
    private void drainTrack() {
        AudioTrack t = this.track;
        if (t == null) return;
        try {
            long deadline = System.currentTimeMillis() + 8000L;
            while (System.currentTimeMillis() < deadline) {
                if (this.track != t || t != this.track) return;   /* بین‌راهی عوض شد */
                try {
                    if (t.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) return;
                } catch (Throwable ps) {
                    return;
                }
                int head;
                long unwritten = 0;
                try {
                    head = t.getPlaybackHeadPosition();
                    /* تخمین نمونه‌های نوشته‌شده اما هنوز پخش‌نشده */
                    unwritten = writtenFrames - (long) head;
                } catch (Throwable hp) {
                    return;
                }
                if (unwritten <= 32) return;
                try {
                    Thread.sleep(15);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
