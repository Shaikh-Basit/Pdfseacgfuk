package com.pdfkhwan.tts;

import android.content.Context;

/* ====================================================================
   v1.47 — پلِ سرویس اعلان ↔ خوانندهٔ جمله‌به‌جمله.
   ReaderActivity هنگام شروع خواندن یک Controller ثبت می‌کند؛
   دکمه‌های اعلانِ مدیا از طریق همین کلاس به آن می‌رسند.
   ==================================================================== */
public final class TtsSession {

    public interface Controller {
        void ttsPlayPause();

        void ttsStop();

        void ttsNext();

        void ttsPrev();

        boolean ttsIsReading();

        boolean ttsIsPaused();

        /** پیش‌نمایش جملهٔ فعلی برای اعلان */
        String ttsPreview();
    }

    private static Controller controller;

    private TtsSession() {
    }

    public static void set(Controller c) {
        controller = c;
    }

    public static void clear(Controller c) {
        if (controller == c) controller = null;
    }

    public static void playPause() {
        Controller c = controller;
        if (c != null) c.ttsPlayPause();
    }

    public static void stop() {
        Controller c = controller;
        if (c != null) c.ttsStop();
    }

    public static void next() {
        Controller c = controller;
        if (c != null) c.ttsNext();
    }

    public static void prev() {
        Controller c = controller;
        if (c != null) c.ttsPrev();
    }

    public static boolean isReading() {
        Controller c = controller;
        return c != null && c.ttsIsReading();
    }

    public static boolean isPaused() {
        Controller c = controller;
        return c != null && c.ttsIsReading() && c.ttsIsPaused();
    }

    public static String preview() {
        Controller c = controller;
        if (c == null) return "";
        try {
            String s = c.ttsPreview();
            return s == null ? "" : s;
        } catch (Throwable t) {
            return "";
        }
    }

    /** بعد از هر تغییر حالت، اعلان را تازه کن */
    public static void notifyStateChanged(Context ctx) {
        if (isReading()) {
            TtsService.refresh(ctx);
        }
    }
}
