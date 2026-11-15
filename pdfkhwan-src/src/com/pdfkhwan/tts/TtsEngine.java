package com.pdfkhwan.tts;

/* ====================================================================
   v1.47 — بخش ۱۹/۲۱ از نقشهٔ راه: لایهٔ موتور گفتار.

   TtsEngine: قرارداد مشترک دو موتور خواندن:
     • SystemTtsEngine — موتور TTS خودِ اندروید (سریع، بدون دانلود؛
       برای انگلیسی پیش‌فرض است)
     • PiperEngine — مدل‌های عصبی آفلاین Piper (sherpa-onnx) با کیفیت
       طبیعی برای فارسی و انگلیسی
   خوانندهٔ جمله‌به‌جمله (داخل ReaderActivity) برای هر جمله، زبانش را
   تشخیص می‌دهد و موتور مناسب را انتخاب می‌کند.
   ==================================================================== */
public interface TtsEngine {

    /** "system" یا "piper" */
    String id();

    /** آیا این موتور همین حالا می‌تواند این زبان را بخواند؟ */
    boolean isAvailable(String lang);

    /**
     * خواندن یک جمله. صداها روی thread موتور پخش می‌شوند؛
     * onStart وقتی پخشِ جمله شروع شد (برای هایلایت)،
     * onDone وقتی تمام شد (completed=true) یا قطع شد (false).
     */
    void speak(String lang, String voiceId, String text, float speed, Listener l);

    /** قطع فوری پخشِ در جریان */
    void stop();

    interface Listener {
        void onStart();

        void onDone(boolean completed);

        void onError(String message);
    }
}
