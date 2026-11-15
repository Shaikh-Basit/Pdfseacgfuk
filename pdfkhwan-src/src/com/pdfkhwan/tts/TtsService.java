package com.pdfkhwan.tts;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import com.pdfkhwan.app.CrashGuard;
import com.pdfkhwan.app.R;
import com.pdfkhwan.app.ReaderActivity;

/* ====================================================================
   v1.47 — بخش ۱۹: خواندن در پس‌زمینه + اعلان مدیا.

   سرویس foreground با اعلان MediaStyle (چارچوبی — بدون وابستگی):
   دکمه‌های قبلی/بعدی، توقف/ادامه و بستن؛ کنترل واقعی از طریق
   TtsSession به خوانندهٔ داخل ReaderActivity وصل می‌شود.
   ==================================================================== */
public class TtsService extends Service {

    public static final String ACTION_START = "com.pdfkhwan.tts.START";
    public static final String ACTION_PAUSE = "com.pdfkhwan.tts.PAUSE";
    public static final String ACTION_RESUME = "com.pdfkhwan.tts.RESUME";
    public static final String ACTION_STOP = "com.pdfkhwan.tts.STOP";
    public static final String ACTION_NEXT = "com.pdfkhwan.tts.NEXT";
    public static final String ACTION_PREV = "com.pdfkhwan.tts.PREV";

    private static final String CHANNEL = "pdfkhwan_tts";
    private static final int NOTIF_ID = 71;

    private PowerManager.WakeLock wakeLock;

    /* ---------- lifecycle ---------- */

    @Override
    public void onCreate() {
        super.onCreate();
        /* v1.49 FIX: RemoteServiceException — وقتی Context.startForegroundService()
           صدا زده می‌شود، اندروید ظرف ~۵ ثانیه منتظر Service.startForeground() می‌ماند
           و اگر نیاید کل اپ را می‌بندد (crash گزارش‌شدهٔ کاربر). قبلاً startForeground
           در onStartCommand و بعد از فراخوانی‌های TtsSession بود؛ اگر آن فراخوانی‌ها
           استثنا می‌دادند یا شروع دیر می‌شد، اعلان هرگز ساخته نمی‌شد. حالا در اولین
           لحظهٔ حیات سرویس (onCreate) فراخوانی می‌شود. */
        startForegroundInternal();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;
        if (ACTION_PAUSE.equals(action)) {
            TtsSession.playPause();
        } else if (ACTION_RESUME.equals(action)) {
            TtsSession.playPause();
        } else if (ACTION_STOP.equals(action)) {
            TtsSession.stop();
            stopSelf();
            return START_NOT_STICKY;
        } else if (ACTION_NEXT.equals(action)) {
            TtsSession.next();
        } else if (ACTION_PREV.equals(action)) {
            TtsSession.prev();
        } else {
            /* START */
        }
        /* اگر onCreate از هر راهی نتوانست startForeground بزند، دوباره تلاش */
        startForegroundInternal();
        updateNotification();
        return START_NOT_STICKY;
    }

    private void startForegroundInternal() {
        holdWake();
        /* v1.51 FIX: ForegroundServiceDidNotStartInTimeException — سه مسیر
           واقعی که startForeground هرگز اجرا نمی‌شد و ۵ ثانیه بعد کل اپ کرش می‌کرد:
           ۱) exception از خود buildNotification() (آرگومانِ فراخوانی) — حتی داخل try،
              چون ensureChannel()/MediaStyle/TtsSession.reads ممکن است پرتاب کنند،
              startForeground اصلاً invoke نمی‌شد؛
           ۲) fallback به buildSimpleNotification بدون ensureChannel → روی API 26+
              Notification با channelId ثبت‌نشده دوباره پرتاب می‌کرد؛
           ۳) TtsSession.reads هنگام controller==null.
           اکنون: نوتیف اول در tryِ جدا ساخته می‌شود؛ اگر شکست خورد fallback سادهٔ
           بدون channel (deprecated builder) می‌آید؛ هر مسیر نوتیف غیر-null را به
           startForeground می‌رساند — به‌علاوه تلاش مجدد در onStartCommand. */
        Notification n = null;
        try {
            n = buildNotification();
        } catch (Throwable t) {
            try {
                ensureChannel();
                n = buildSimpleNotification();
            } catch (Throwable ignored) {
            }
        }
        if (n == null) {
            try {
                n = buildNotification();
            } catch (Throwable ignored) {
            }
        }
        if (n == null) {
            try {
                n = buildSimpleNotification();
            } catch (Throwable ignored) {
            }
        }
        if (n != null) {
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
                } else {
                    startForeground(NOTIF_ID, n);
                }
                return;
            } catch (Throwable t) {
                /* رام‌های قدیمی overload سه‌آرگومانی را ندارند */
                try {
                    startForeground(NOTIF_ID, n);
                } catch (Throwable ignored) {
                }
            }
            return;
        }
        /* آخرین سپر: نوتیفِ کاملاً خام بدون هیچ وابستگی (R و channel) — اگر همین هم
           شکست بخورد، خود سرویس را می‌بندیم تا system آن را «شروع‌نشده» تلقی نکند */
        try {
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, CHANNEL)
                    : new Notification.Builder(this);
            Notification raw = b.setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentTitle("در حال خواندن کتاب").setOngoing(true).build();
            startForeground(NOTIF_ID, raw);
        } catch (Throwable fatal) {
            try {
                CrashGuard.append(this, "startForeground-final: " + CrashGuard.stackToString(fatal) + "\n");
            } catch (Throwable ignored) {
            }
            stopSelf();
        }
    }

    @Override
    public void onDestroy() {
        releaseWake();
        super.onDestroy();
    }

    /* v1.51: API 34+ — اگر سیستم برای مصرف منبع، timeout سرویس foreground را بفرستد،
       خودمان داوطلبانه متوقف می‌شویم (پیش از اینکه system ما را با
       ForegroundServiceDidNotAllowedToStart / کرش ببندد) */
    @Override
    public void onTimeout(int startId) {
        try {
            stopForeground(true);
        } catch (Throwable ignored) {
        }
        stopSelf(startId);
    }

    /* ---------- wake lock: تولید صدا با صفحهٔ خاموش ---------- */

    private void holdWake() {
        try {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pdfkhwan:tts");
                wakeLock.setReferenceCounted(false);
            }
            if (!wakeLock.isHeld()) wakeLock.acquire(4 * 60 * 60 * 1000L);
        } catch (Throwable ignored) {
        }
    }

    private void releaseWake() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Throwable ignored) {
        }
    }

    /* ---------- notification ---------- */

    private PendingIntent action(String a) {
        Intent i = new Intent(this, TtsService.class);
        i.setAction(a);
        int flag = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        return PendingIntent.getService(this, a.hashCode(), i, flag);
    }

    private PendingIntent content() {
        Intent i = new Intent(this, ReaderActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flag = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        return PendingIntent.getActivity(this, 3, i, flag);
    }

    private Notification buildSimpleNotification() {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_m_speak)
                .setContentTitle("در حال خواندن کتاب")
                .setContentText(TtsSession.preview())
                .setContentIntent(content())
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "توقف", action(ACTION_STOP)).build());
        return b.build();
    }

    private Notification buildNotification() {
        ensureChannel();
        boolean paused = TtsSession.isPaused();
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_m_speak)
                .setContentTitle(paused ? "خواندن متوقف است" : "در حال خواندن کتاب")
                .setContentText(TtsSession.preview())
                .setContentIntent(content())
                .setOngoing(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(new Notification.Action.Builder(null, "جملهٔ قبلی", action(ACTION_PREV)).build())
                .addAction(new Notification.Action.Builder(null, paused ? "ادامه" : "توقف", action(ACTION_PAUSE)).build())
                .addAction(new Notification.Action.Builder(null, "جملهٔ بعدی", action(ACTION_NEXT)).build())
                .addAction(new Notification.Action.Builder(null, "بستن", action(ACTION_STOP)).build());
        if (Build.VERSION.SDK_INT >= 21) {
            try {
                Notification.MediaStyle style = new Notification.MediaStyle();
                style.setShowActionsInCompactView(1, 0, 2);
                b.setStyle(style);
            } catch (Throwable ignored) {
            }
        }
        return b.build();
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CHANNEL, "خواندن با صدا",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
    }

    private void updateNotification() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(NOTIF_ID, buildNotification());
        } catch (Throwable ignored) {
        }
    }

    /* ---------- static helpers ---------- */

    public static void startReading(Context c) {
        try {
            Intent i = new Intent(c, TtsService.class);
            i.setAction(ACTION_START);
            if (Build.VERSION.SDK_INT >= 26) {
                c.startForegroundService(i);
            } else {
                c.startService(i);
            }
        } catch (Throwable t) {
            /* v1.49: foreground شروع نشد (مثلاً اپ در پس‌زمینه) — خواندن بدون
               اعلان ادامه می‌یابد؛ سکوت مطلق نگذار که کاربر نفهمد چرا بی‌صدا شد */
            try {
                CrashGuard.append(c, "ttsService.start: " + CrashGuard.stackToString(t));
            } catch (Throwable ignored) {
            }
        }
    }

    public static void refresh(Context c) {
        try {
            Intent i = new Intent(c, TtsService.class);
            i.setAction(ACTION_START);
            if (Build.VERSION.SDK_INT >= 26) {
                c.startForegroundService(i);
            } else {
                c.startService(i);
            }
        } catch (Throwable ignored) {
        }
    }

    public static void stopReading(Context c) {
        try {
            c.stopService(new Intent(c, TtsService.class));
        } catch (Throwable ignored) {
        }
    }
}
