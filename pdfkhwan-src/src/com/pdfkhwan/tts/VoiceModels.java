package com.pdfkhwan.tts;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import com.pdfkhwan.app.CrashGuard;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/* ====================================================================
   v1.47 — بخش ۲۰ از نقشهٔ راه: مدیریت مدل‌های صوتی Piper.

   • کاتالوگ ثابت: فارسی امیر (پیش‌فرض) + انگلیسی رایان/لساک/آلن
     (منبع: HuggingFace — فقط برای دانلودِ خودِ مدل‌ها به اینترنت
     دسترسی لازم است؛ خودِ خواندن ۱۰۰٪ آفلاین است).
   • دانلود با ادامه از نقطهٔ قطع (Range) + تأیید SHA-256 هر فایل.
   • اعتبارسنجی: سرصفحهٔ ONNX، فایل config (json)، tokens و
     مجموعهٔ espeak-ng-data (همان چیزی که موتور لازم دارد).
   • درون‌ریزی از SAF: فایل‌های کاربر داخل حافظهٔ اپ کپی می‌شوند
     (موتور به مسیر فایل محلی نیاز دارد).

   چیدمان روی دیسک (getExternalFilesDir/voices):
     voices/<voiceId>/<voiceId>.onnx | .onnx.json | tokens.txt
     voices/espeak-ng-data/{phontab,phonindex,phondata,intonations,
                            fa_dict,en_dict,lang/...}   ← مشترک
   ==================================================================== */
public final class VoiceModels {

    private VoiceModels() {
    }

    /* ============================ کاتالوگ ============================ */

    public static final class CatFile {
        public final String rel;      /* مسیر مقصد نسبی به ریشهٔ voices */
        public final long size;
        public final String sha256;   /* null = فقط بررسی حجم */
        public final String url;

        CatFile(String rel, long size, String sha256, String url) {
            this.rel = rel;
            this.size = size;
            this.sha256 = sha256;
            this.url = url;
        }
    }

    public static final class Voice {
        public final String id;
        public final String lang;          /* "fa" | "en" */
        public final String name;          /* نام نمایشی */
        public final String region;        /* زیرمجموعهٔ زبان */
        public final String quality;       /* medium و… */
        public final String sampleText;
        public final List<CatFile> files;

        Voice(String id, String lang, String name, String region, String quality, String sampleText, List<CatFile> files) {
            this.id = id;
            this.lang = lang;
            this.name = name;
            this.region = region;
            this.quality = quality;
            this.sampleText = sampleText;
            this.files = files;
        }

        public long totalBytes() {
            long t = 0;
            for (CatFile f : files) t += f.size;
            return t;
        }
    }

    private static final String HF = "https://huggingface.co/";
    private static final String AMIR = "csukuangfj/vits-piper-fa_IR-amir-medium/resolve/main/";
    private static final String RYAN = "csukuangfj/vits-piper-en_US-ryan-medium/resolve/main/";
    private static final String LESSAC = "csukuangfj/vits-piper-en_US-lessac-medium/resolve/main/";
    private static final String ALAN = "csukuangfj/vits-piper-en_GB-alan-medium/resolve/main/";

    /* فایل‌های پایهٔ espeak — یک‌بار برای همهٔ صداها دانلود می‌شوند */
    private static void addEspeakBase(List<CatFile> l) {
        l.add(new CatFile("espeak-ng-data/phontab", 55796L,
                "886f3fa402cb0ba73d483aa8ad000af47a6b7cc06293c75a97913fba68a530f6", HF + AMIR + "espeak-ng-data/phontab"));
        l.add(new CatFile("espeak-ng-data/phonindex", 39074L,
                "3ca7b8fa3b42624e4b0f152707e7a39245fce569aa99ea47c055d9e622fcf0c4", HF + AMIR + "espeak-ng-data/phonindex"));
        l.add(new CatFile("espeak-ng-data/phondata", 550424L,
                "4e0288957874029a8c3c9f41a8f517ad4bf18127046decbdd4b9d1d6807ce3a3", HF + AMIR + "espeak-ng-data/phondata"));
        l.add(new CatFile("espeak-ng-data/intonations", 2040L,
                "3f8af65fd3eda9759a10f021d61361c120871f463515229c925995c7f90918cc", HF + AMIR + "espeak-ng-data/intonations"));
    }

    /* ============ v1.48: داده‌های زبانی espeak (مشترک، کم‌حجم) ============
       کاربر فقط ۳ فایل مدل را دارد (model.onnx / .onnx.json / tokens.txt)
       و پوشهٔ espeak-ng-data را ندارد. این داده‌ها کمتر از ۱ مگابایتند و
       خود برنامه در صورت نبود، تکمیلاً دانلودشان می‌کند. */

    /** شناسهٔ شبه‌صدا برای صف دانلود: "espeak:fa" یا "espeak:en-us" … */
    public static String espeakQueueId(String espeakVoice) {
        return "espeak:" + (espeakVoice == null ? "" : espeakVoice);
    }

    static boolean isEspeakQueueId(String id) {
        return id != null && id.startsWith("espeak:");
    }

    /** فایل‌های espeak لازم برای یک صدای espeak مشخص (مثل "fa" یا "en-us") */
    public static List<CatFile> espeakFilesFor(String espeakVoice) {
        List<CatFile> l = new ArrayList<>();
        addEspeakBase(l);
        String want = espeakVoice == null ? "" : espeakVoice.toLowerCase(Locale.US);
        String langCode = want.split("-")[0];
        /* فایل زبان — از کاتالوگ (هر مخزنی که همین زبان را دارد) */
        CatFile langFile = null, dictFile = null;
        outer:
        for (Voice v : CATALOG) {
            for (CatFile f : v.files) {
                String rel = f.rel.toLowerCase(Locale.US);
                if (langFile == null && rel.startsWith("espeak-ng-data/lang/") && rel.endsWith("/" + want)) {
                    langFile = f;
                }
                if (dictFile == null && f.rel.equals("espeak-ng-data/" + langCode + "_dict")) {
                    dictFile = f;
                }
                if (langFile != null && dictFile != null) break outer;
            }
        }
        if (langFile != null) l.add(langFile);
        if (dictFile != null) l.add(dictFile);
        return l;
    }

    /** چه اجزای espeak برای این voice کم است (برای پیام دقیق UI) */
    public static List<String> espeakMissingFor(Context c, String voiceId) {
        List<String> miss = new ArrayList<>();
        String ev = espeakVoiceOf(c, voiceId);
        if (ev == null || ev.length() == 0) {
            miss.add("espeak-ng-data");
            return miss;
        }
        for (CatFile f : espeakFilesFor(ev)) {
            File disk = new File(root(c), f.rel);
            if (!disk.exists() || disk.length() != f.size) {
                miss.add(new File(f.rel).getName());
            }
        }
        return miss;
    }

    /** نام صدای espeak از داخل .onnx.json یک voice (مثل "fa" یا "en-us") */
    public static String espeakVoiceOf(Context c, String voiceId) {
        try {
            File[] fs = voiceDir(c, voiceId).listFiles();
            if (fs != null) {
                for (File f : fs) {
                    if (f.getName().toLowerCase(Locale.US).endsWith(".onnx.json")) {
                        JSONObject cfg = new JSONObject(readText(f));
                        return cfg.optJSONObject("espeak") != null
                                ? cfg.optJSONObject("espeak").optString("voice", "") : "";
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    /** دانلود داده‌های زبانی espeak برای یک voice — اگر کامل است، بی‌درنگ
        onDone صدا زده می‌شود. (فقط ~۱ مگابایت؛ خودِ مدل دانلود نمی‌شود) */
    public static void completeEspeakFor(final Context c, final String voiceId, final Progress p) {
        String ev = espeakVoiceOf(c, voiceId);
        if (ev == null || ev.length() == 0) {
            if (p != null) {
                ui.post(new Runnable() {
                    @Override public void run() { p.onDone(false, "فایل تنظیمات مدل (.onnx.json) پیدا نشد"); }
                });
            }
            return;
        }
        if (espeakMissingFor(c, voiceId).isEmpty()) {
            if (p != null) {
                ui.post(new Runnable() {
                    @Override public void run() { p.onDone(true, "داده‌های زبانی از قبل کامل است"); }
                });
            }
            return;
        }
        download(c, espeakQueueId(ev), p);
    }

    public static final List<Voice> CATALOG = buildCatalog();

    private static List<Voice> buildCatalog() {
        List<Voice> out = new ArrayList<>();

        /* ---- فارسی: امیر (پیش‌فرض) ---- */
        List<CatFile> fa = new ArrayList<>();
        addEspeakBase(fa);
        fa.add(new CatFile("fa_IR-amir-medium/fa_IR-amir-medium.onnx", 63531507L,
                "5a832803df099857d92cf7ec1e340c7ef6910433f089d9aca9ff1ebf9b7fa6c5", HF + AMIR + "fa_IR-amir-medium.onnx"));
        fa.add(new CatFile("fa_IR-amir-medium/fa_IR-amir-medium.onnx.json", 4958L,
                "75f918a3bf0f57a9179abe725af529f2a5c79d6c899e2a84aec76c685d5dfb9a", HF + AMIR + "fa_IR-amir-medium.onnx.json"));
        fa.add(new CatFile("fa_IR-amir-medium/tokens.txt", 940L,
                "ef3a7e4a8d1af0c9d4dc45aaae1a6242ebe24a7ed6f3d025a49eb29682784c6d", HF + AMIR + "tokens.txt"));
        fa.add(new CatFile("espeak-ng-data/lang/ira/fa", 90L,
                "0dc7e93be4d1fbdf7ff52de2fe8bcdc23304e6d86e012b4ab8276d626b5d980c", HF + AMIR + "espeak-ng-data/lang/ira/fa"));
        fa.add(new CatFile("espeak-ng-data/fa_dict", 292423L,
                "f92f35ce7eb1d97360016617d97786412a94af55b776a18f1c280dab4f43befd", HF + AMIR + "espeak-ng-data/fa_dict"));
        out.add(new Voice("fa_IR-amir-medium", "fa", "امیر", "فارسی", "متوسط — پیشنهادی",
                "سلام! این صدای فارسیِ کتاب‌خوان است.", fa));

        /* ---- انگلیسی: رایان (en_US) ---- */
        List<CatFile> ryan = new ArrayList<>();
        addEspeakBase(ryan);
        ryan.add(new CatFile("en_US-ryan-medium/en_US-ryan-medium.onnx", 63201425L,
                "8a4063318faeda9bf67f89d25b29cc4bd5fde8832422aa451a5d5d2647553081", HF + RYAN + "en_US-ryan-medium.onnx"));
        ryan.add(new CatFile("en_US-ryan-medium/en_US-ryan-medium.onnx.json", 4883L,
                "44034c056cb15681b2ad494307c7f3f2e4499d1253c700c711fa0a4607ffe78d", HF + RYAN + "en_US-ryan-medium.onnx.json"));
        ryan.add(new CatFile("en_US-ryan-medium/tokens.txt", 921L,
                "87c8ef66eae5473ed0cc0366b3964c736ca6c5f676c979522ea31234e47430b9", HF + RYAN + "tokens.txt"));
        ryan.add(new CatFile("espeak-ng-data/lang/gmw/en-US", 257L,
                "41534c2a22df5dd4f1052ff9e1a33a3ea7bff5a26b5c02bdad5ba8ddb7524704", HF + RYAN + "espeak-ng-data/lang/gmw/en-US"));
        ryan.add(new CatFile("espeak-ng-data/en_dict", 166944L,
                "71bd330ba8a2e3e8076e631508208ef49449d6147c17b7bd2b4b1e1468292e35", HF + RYAN + "espeak-ng-data/en_dict"));
        out.add(new Voice("en_US-ryan-medium", "en", "رایان", "انگلیسی آمریکایی", "متوسط",
                "Hello! This is the English reading voice.", ryan));

        /* ---- انگلیسی: لساک (en_US) ---- */
        List<CatFile> lessac = new ArrayList<>();
        addEspeakBase(lessac);
        lessac.add(new CatFile("en_US-lessac-medium/en_US-lessac-medium.onnx", 63201425L,
                "ea793a648ce0370665f99fbf891eae3b3a973565c72b08534bb5b2c04e8f2332", HF + LESSAC + "en_US-lessac-medium.onnx"));
        lessac.add(new CatFile("en_US-lessac-medium/en_US-lessac-medium.onnx.json", 4885L,
                "efe19c417bed055f2d69908248c6ba650fa135bc868b0e6abb3da181dab690a0", HF + LESSAC + "en_US-lessac-medium.onnx.json"));
        lessac.add(new CatFile("en_US-lessac-medium/tokens.txt", 921L,
                "87c8ef66eae5473ed0cc0366b3964c736ca6c5f676c979522ea31234e47430b9", HF + LESSAC + "tokens.txt"));
        lessac.add(new CatFile("espeak-ng-data/lang/gmw/en-US", 257L,
                "41534c2a22df5dd4f1052ff9e1a33a3ea7bff5a26b5c02bdad5ba8ddb7524704", HF + LESSAC + "espeak-ng-data/lang/gmw/en-US"));
        lessac.add(new CatFile("espeak-ng-data/en_dict", 166944L,
                "71bd330ba8a2e3e8076e631508208ef49449d6147c17b7bd2b4b1e1468292e35", HF + LESSAC + "espeak-ng-data/en_dict"));
        out.add(new Voice("en_US-lessac-medium", "en", "لساک", "انگلیسی آمریکایی", "متوسط",
                "Hello! This is the English reading voice.", lessac));

        /* ---- انگلیسی: آلن (en_GB) ---- */
        List<CatFile> alan = new ArrayList<>();
        addEspeakBase(alan);
        alan.add(new CatFile("en_GB-alan-medium/en_GB-alan-medium.onnx", 63201430L,
                "d907c48857000940104a9ad3248a94617df917d1a525c9495490fdbf87fd54b2", HF + ALAN + "en_GB-alan-medium.onnx"));
        alan.add(new CatFile("en_GB-alan-medium/en_GB-alan-medium.onnx.json", 4888L,
                "c0f0d124e5895c00e7c03b35dcc8287f319a6998a365b182deb5c8e752ee8c1e", HF + ALAN + "en_GB-alan-medium.onnx.json"));
        alan.add(new CatFile("en_GB-alan-medium/tokens.txt", 921L,
                "87c8ef66eae5473ed0cc0366b3964c736ca6c5f676c979522ea31234e47430b9", HF + ALAN + "tokens.txt"));
        alan.add(new CatFile("espeak-ng-data/lang/gmw/en-GB-x-rp", 249L,
                "d0625af7f58561b1b8cf96fd7f93eee6553bcb3eadb9020ae0757bf96e5115e5", HF + ALAN + "espeak-ng-data/lang/gmw/en-GB-x-rp"));
        alan.add(new CatFile("espeak-ng-data/en_dict", 166944L,
                "71bd330ba8a2e3e8076e631508208ef49449d6147c17b7bd2b4b1e1468292e35", HF + ALAN + "espeak-ng-data/en_dict"));
        out.add(new Voice("en_GB-alan-medium", "en", "آلن", "انگلیسی بریتیش", "متوسط",
                "Hello! This is the British English reading voice.", alan));

        return out;
    }

    public static Voice catalogVoice(String id) {
        for (Voice v : CATALOG) if (v.id.equals(id)) return v;
        return null;
    }

    /* ============================ مسیرها ============================ */

    public static File root(Context c) {
        File base = c.getExternalFilesDir(null);
        if (base == null || !base.canWrite()) base = c.getFilesDir();
        File f = new File(base, "voices");
        if (!f.exists()) f.mkdirs();
        return f;
    }

    public static File fileFor(Context c, CatFile f) {
        return new File(root(c), f.rel);
    }

    public static File fileForRel(Context c, String rel) {
        return new File(root(c), rel);
    }

    public static File voiceDir(Context c, String voiceId) {
        return new File(root(c), voiceId);
    }

    public static File espeakDir(Context c) {
        return new File(root(c), "espeak-ng-data");
    }

    public static final class VoicePaths {
        public final String model;
        public final String tokens;
        public final String dataDir;

        VoicePaths(String model, String tokens, String dataDir) {
            this.model = model;
            this.tokens = tokens;
            this.dataDir = dataDir;
        }
    }

    /** مسیرهای موتور برای یک voiceId؛ null اگر فایل‌ها ناقص‌اند */
    public static VoicePaths paths(Context c, String voiceId) {
        File dir = voiceDir(c, voiceId);
        File model = null, tokens = null;
        File[] arr = dir.listFiles();
        if (arr != null) {
            for (File f : arr) {
                String n = f.getName().toLowerCase(Locale.US);
                if (n.endsWith(".onnx")) model = f;
                else if (n.equals("tokens.txt")) tokens = f;
            }
        }
        if (model == null || tokens == null || !model.exists() || !tokens.exists()) return null;
        return new VoicePaths(model.getAbsolutePath(), tokens.getAbsolutePath(), espeakDir(c).getAbsolutePath());
    }

    /* ============================ وضعیت ============================ */

    public static final int S_NOT_INSTALLED = 0;
    public static final int S_DOWNLOADING = 1;
    public static final int S_READY = 2;
    public static final int S_BROKEN = 3;      /* فایل ناقص/خراب — دانلود مجدد */

    public static final class Rec {
        public final String id;
        public final String lang;
        public final String name;
        public final String region;
        public final String quality;
        public final long totalBytes;
        public final boolean imported;
        public int state;
        public long doneBytes;      /* فایل‌های سالمِ موجود */
        public boolean starred;     /* پیش‌فرضِ این زبان */
        public String sampleText;

        Rec(String id, String lang, String name, String region, String quality, long total, boolean imported, String sample) {
            this.id = id;
            this.lang = lang;
            this.name = name;
            this.region = region;
            this.quality = quality;
            this.totalBytes = total;
            this.imported = imported;
            this.sampleText = sample;
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("voices", 0);
    }

    /* کش‌های داغ — خوانندهٔ جمله‌به‌جمله هر جمله این‌ها را می‌پرسد؛
       اعتبارسنجی کامل فقط وقتی لازم باشد اجرا می‌شود. */
    private static final Map<String, Boolean> readyCache = new HashMap<>();
    private static final Map<String, String> activeCache = new HashMap<>();

    public static void invalidateCaches() {
        synchronized (readyCache) {
            readyCache.clear();
        }
        synchronized (activeCache) {
            activeCache.clear();
        }
    }

    /* صداهای درون‌ریزی‌شده (SAF) — مجموعهٔ شناسه‌ها با جداکنندهٔ «|» */
    private static List<String> importedIds(Context c) {
        List<String> out = new ArrayList<>();
        String s = prefs(c).getString("imported", "");
        if (s != null && s.length() > 0) {
            for (String p : s.split("\\|")) if (p.length() > 0) out.add(p);
        }
        /* به‌علاوهٔ هر پوشهٔ واقعاً موجود که در کاتالوگ نیست */
        File[] dirs = root(c).listFiles();
        if (dirs != null) {
            for (File d : dirs) {
                if (d.isDirectory() && catalogVoice(d.getName()) == null && !out.contains(d.getName())) {
                    File[] fs = d.listFiles();
                    if (fs != null) {
                        for (File f : fs) {
                            if (f.getName().toLowerCase(Locale.US).endsWith(".onnx")) {
                                out.add(d.getName());
                                break;
                            }
                        }
                    }
                }
            }
        }
        return out;
    }

    static void addImportedId(Context c, String id) {
        List<String> ids = importedIds(c);
        if (!ids.contains(id)) ids.add(id);
        StringBuilder sb = new StringBuilder();
        for (String i : ids) {
            if (sb.length() > 0) sb.append('|');
            sb.append(i);
        }
        prefs(c).edit().putString("imported", sb.toString()).apply();
    }

    static void removeImportedId(Context c, String id) {
        List<String> ids = importedIds(c);
        ids.remove(id);
        StringBuilder sb = new StringBuilder();
        for (String i : ids) {
            if (sb.length() > 0) sb.append('|');
            sb.append(i);
        }
        prefs(c).edit().putString("imported", sb.toString()).apply();
    }

    /** فهرست همهٔ صداها (کاتالوگ + درون‌ریزی) با وضعیت لحظه‌ای */
    public static List<Rec> list(Context c) {
        return list(c, true);
    }

    /* v1.48 FIX: بازگشت بی‌نهایت list()↔activeVoice() — اولین اجرا (بدون
       ترجیح ذخیره‌شده و بدون کش) مستقیم به StackOverflowError می‌رسید.
       حل: activeVoice وقتی «اولین صدای آماده» را می‌گردد، فهرست را بدون
       حل‌کردن دوبارهٔ voice فعال می‌سازد (resolveActive=false). */
    private static List<Rec> list(Context c, boolean resolveActive) {
        List<Rec> out = new ArrayList<>();
        String activeFa = resolveActive ? activeVoice(c, "fa") : "";
        String activeEn = resolveActive ? activeVoice(c, "en") : "";
        for (Voice v : CATALOG) {
            Rec r = new Rec(v.id, v.lang, v.name, v.region, v.quality, v.totalBytes(), false, v.sampleText);
            fillState(c, r, v.files, activeFa, activeEn);
            out.add(r);
        }
        for (String id : importedIds(c)) {
            if (catalogVoice(id) != null) continue;
            File dir = voiceDir(c, id);
            File[] fs = dir.listFiles();
            long total = 0;
            String name = id;
            if (fs != null) {
                for (File f : fs) {
                    total += f.length();
                    if (f.getName().toLowerCase(Locale.US).endsWith(".onnx.json")) {
                        name = f.getName().substring(0, f.getName().length() - ".onnx.json".length());
                    }
                }
            }
            String lang = langOf(c, id, null);
            Rec r = new Rec(id, lang, name, "درون‌ریزی‌شده", "", total, true, "");
            fillState(c, r, null, activeFa, activeEn);
            out.add(r);
        }
        return out;
    }

    private static void fillState(Context c, Rec r, List<CatFile> files, String activeFa, String activeEn) {
        r.starred = r.id.equals("fa".equals(r.lang) ? activeFa : activeEn);
        if (isDownloading(r.id)) {
            r.state = S_DOWNLOADING;
            r.doneBytes = doneBytes.get(r.id) != null ? doneBytes.get(r.id) : verifiedBytes(c, r.id, files);
            return;
        }
        String problem = validateFiles(c, r.id);
        if (problem == null) {
            r.state = S_READY;
            r.doneBytes = r.totalBytes;
        } else {
            long have = verifiedBytes(c, r.id, files);
            r.state = have > 0 ? S_BROKEN : S_NOT_INSTALLED;
            r.doneBytes = have;
        }
    }

    private static long verifiedBytes(Context c, String id, List<CatFile> files) {
        if (files == null) return 0;
        long t = 0;
        /* فقط تطبیق حجم — SHA-256 هنگام خودِ دانلود تأیید می‌شود؛ این تابع روی
           رشتهٔ UI صدا زده می‌شود و هش‌کردن ۶۳ مگابایت آن را می‌لرزاند. */
        for (CatFile f : files) {
            File disk = fileFor(c, f);
            if (disk.exists() && disk.length() == f.size) t += f.size;
        }
        return t;
    }

    /* ============================ ترجیحات ============================ */

    /** زبان یک voice: اول ترجیح ذخیره‌شده (درون‌ریزی)، بعد espeak داخل
        .onnx.json خودش، در آخر از روی نام شناسه */
    public static String langOf(Context c, String voiceId, String fallbackFromJson) {
        String saved = prefs(c).getString("lang_" + voiceId, null);
        if (saved != null && saved.length() > 0) return saved;
        if (fallbackFromJson != null && fallbackFromJson.length() > 0) return fallbackFromJson;
        /* از خودِ فایلِ روی دیسک بخوان (یک‌بار — بعدش در ترجیحات می‌نشیند) */
        try {
            File dir = voiceDir(c, voiceId);
            File[] fs = dir.listFiles();
            if (fs != null) {
                for (File f : fs) {
                    if (f.getName().toLowerCase(Locale.US).endsWith(".onnx.json")) {
                        JSONObject cfg = new JSONObject(readText(f));
                        String ev = cfg.optJSONObject("espeak") != null
                                ? cfg.optJSONObject("espeak").optString("voice", "") : "";
                        if (ev.length() > 0) {
                            String l = ev.split("-")[0];
                            if (l.startsWith("fa")) l = "fa";
                            else if (l.startsWith("en")) l = "en";
                            prefs(c).edit().putString("lang_" + voiceId, l).apply();
                            return l;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return voiceId.startsWith("fa") || voiceId.contains("_fa") || voiceId.contains("fa_") ? "fa" : "en";
    }

    public static void setImportedLang(Context c, String voiceId, String lang) {
        prefs(c).edit().putString("lang_" + voiceId, lang).apply();
    }

    /** voiceId فعالِ یک زبان ("" = هیچ) — با کش */
    public static String activeVoice(Context c, String lang) {
        synchronized (activeCache) {
            String hit = activeCache.get(lang);
            if (hit != null) return hit;
        }
        String v = prefs(c).getString("voice_" + lang, null);
        if (v != null && isReady(c, v)) {
            synchronized (activeCache) {
                activeCache.put(lang, v);
            }
            return v;
        }
        /* اولین صدای آمادهٔ این زبان — بدون resolve فعال (وگرنه بازگشت بی‌نهایت) */
        for (Rec r : list(c, false)) {
            if (r.lang.equals(lang) && r.state == S_READY) {
                synchronized (activeCache) {
                    activeCache.put(lang, r.id);
                }
                return r.id;
            }
        }
        if (v == null) v = "";
        synchronized (activeCache) {
            activeCache.put(lang, v);
        }
        return v;
    }

    public static void setActiveVoice(Context c, String lang, String voiceId) {
        prefs(c).edit().putString("voice_" + lang, voiceId).apply();
        synchronized (activeCache) {
            activeCache.put(lang, voiceId);
        }
    }

    public static boolean isReady(Context c, String voiceId) {
        synchronized (readyCache) {
            Boolean hit = readyCache.get(voiceId);
            if (hit != null) return hit.booleanValue();
        }
        boolean ok = validateFiles(c, voiceId) == null;
        synchronized (readyCache) {
            readyCache.put(voiceId, Boolean.valueOf(ok));
        }
        return ok;
    }

    /** موتور انتخابی زبان (بخش ۲۱): "piper" یا "system" */
    public static String prefEngine(Context c, String lang) {
        String p = prefs(c).getString("engine_" + lang, null);
        if (p != null) return p;
        return "fa".equals(lang) ? "piper" : "system";
    }

    public static void setPrefEngine(Context c, String lang, String engine) {
        prefs(c).edit().putString("engine_" + lang, engine).apply();
    }

    /* ============================ اعتبارسنجی ============================ */

    /**
     * سلامت کامل فایل‌های یک صدا.
     * @return null = سالم؛ وگرنه پیام دقیق فارسی که دقیقاً بگوید چه چیزی کم است.
     */
    public static String validateFiles(Context c, String voiceId) {
        File dir = voiceDir(c, voiceId);
        if (!dir.isDirectory()) return "هنوز دانلود نشده است";
        File model = null, json = null, tokens = null;
        File[] fs = dir.listFiles();
        if (fs != null) {
            for (File f : fs) {
                String n = f.getName().toLowerCase(Locale.US);
                if (n.endsWith(".onnx") && !n.endsWith(".onnx.json")) model = f;
                else if (n.endsWith(".onnx.json")) json = f;
                else if (n.equals("tokens.txt")) tokens = f;
            }
        }
        if (model == null) return "فایل مدل (.onnx) موجود نیست";
        if (model.length() < 1024 * 1024) return "فایل مدل ناقص است (کمتر از ۱ مگابایت)";
        /* سرصفحهٔ ONNX: بایت اول باید 0x08 باشد (فیلد ir_version پروتوباف) */
        try {
            FileInputStream fin = new FileInputStream(model);
            try {
                int b = fin.read();
                if (b != 0x08) return "فایل مدل ONNX معتبر نیست (سرصفحهٔ خراب)";
            } finally {
                fin.close();
            }
        } catch (IOException e) {
            return "فایل مدل خوانده نشد";
        }
        if (json == null) return "فایل تنظیمات مدل (.onnx.json) موجود نیست";
        if (tokens == null || tokens.length() < 100) return "فایل tokens.txt موجود نیست یا خالی است";

        String espeakVoice = null;
        try {
            JSONObject cfg = new JSONObject(readText(json));
            espeakVoice = cfg.optJSONObject("espeak") != null
                    ? cfg.optJSONObject("espeak").optString("voice", null) : null;
        } catch (Throwable ignored) {
            return "فایل تنظیمات مدل خراب است (JSON نامعتبر)";
        }
        if (espeakVoice == null || espeakVoice.length() == 0) {
            return "در تنظیمات مدل، زبان espeak مشخص نشده است";
        }

        /* فایل‌های پایهٔ espeak */
        String[] base = {"phontab", "phonindex", "phondata", "intonations"};
        for (String b : base) {
            File f = new File(espeakDir(c), b);
            if (!f.exists() || f.length() == 0) return "پوشهٔ espeak-ng-data ناقص است — «" + b + "» کم دارد";
        }
        /* فایل صدای espeak برای این زبان (مثلاً fa یا en-us) */
        File voiceFile = findEspeakVoiceFile(espeakDir(c), espeakVoice);
        if (voiceFile == null) {
            return "فایل زبانِ «" + espeakVoice + "» در espeak-ng-data/lang پیدا نشد";
        }
        /* فرهنگ زبان (fa_dict / en_dict) بر اساس کد زبان */
        String langCode = espeakVoice.split("-")[0];
        File dict = new File(espeakDir(c), langCode + "_dict");
        if (!dict.exists() || dict.length() == 0) {
            return "فایل فرهنگ «" + langCode + "_dict» در espeak-ng-data کم دارد";
        }
        return null;
    }

    /** جست‌وجوی بازگشتی فایل صدای espeak با نام برابر (بدون حساسیت به بزرگی) */
    public static File findEspeakVoiceFile(File espeakRoot, String voiceName) {
        File langDir = new File(espeakRoot, "lang");
        if (!langDir.isDirectory()) {
            /* ممکن است خودِ ریشه را داده باشند */
            File direct = new File(espeakRoot, voiceName);
            if (direct.exists()) return direct;
            return null;
        }
        return findByName(langDir, voiceName);
    }

    private static File findByName(File dir, String name) {
        File[] fs = dir.listFiles();
        if (fs == null) return null;
        for (File f : fs) {
            if (f.isDirectory()) {
                File r = findByName(f, name);
                if (r != null) return r;
            } else if (f.getName().equalsIgnoreCase(name)) {
                return f;
            }
        }
        return null;
    }

    private static String readText(File f) throws IOException {
        FileInputStream fin = new FileInputStream(f);
        try {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = fin.read(b)) > 0) bo.write(b, 0, n);
            return new String(bo.toString("UTF-8"));
        } finally {
            fin.close();
        }
    }

    /* ============================ دانلود ============================ */

    public interface Progress {
        /** progress: 0..10000 (صدم درصد) */
        void onProgress(int progress, long doneBytes, long totalBytes);

        void onDone(boolean ok, String message);
    }

    private static final Map<String, Long> doneBytes = new HashMap<>();
    private static final Map<String, Boolean> cancelFlags = new HashMap<>();
    private static final Map<String, Progress> listeners = new HashMap<>();
    private static Thread worker;
    private static final List<String> queue = new ArrayList<>();
    private static final Handler ui = new Handler(Looper.getMainLooper());

    public static boolean isDownloading(String voiceId) {
        synchronized (queue) {
            return queue.contains(voiceId);
        }
    }

    public static void download(final Context c, final String voiceId, Progress p) {
        if (catalogVoice(voiceId) == null && !isEspeakQueueId(voiceId)) return;
        synchronized (queue) {
            if (queue.contains(voiceId)) {
                /* ادامهٔ همان دانلود — فقط شنونده تازه؛ پرچم لغو تنها با
                   «شروع/ادامهٔ» صریح کاربر برداشته می‌شود (نه با نگاه UI) */
                if (p != null) listeners.put(voiceId, p);
                return;
            }
            listeners.put(voiceId, p);
            cancelFlags.put(voiceId, Boolean.FALSE);
            queue.add(voiceId);
            if (worker == null || !worker.isAlive()) {
                worker = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        downloadLoop(c.getApplicationContext());
                    }
                }, "voice-dl");
                worker.start();
            }
        }
    }

    /** فقط نگاه‌کردن به دانلودِ در جریان — وضعیت لغو را دست نمی‌زند */
    public static void observe(String voiceId, Progress p) {
        synchronized (queue) {
            if (queue.contains(voiceId) && p != null) {
                listeners.put(voiceId, p);
            }
        }
    }

    public static void cancelDownload(String voiceId) {
        cancelFlags.put(voiceId, Boolean.TRUE);
        synchronized (queue) {
            listeners.remove(voiceId);
        }
    }

    private static void downloadLoop(Context c) {
        while (true) {
            String id;
            synchronized (queue) {
                if (queue.isEmpty()) {
                    worker = null;
                    return;
                }
                id = queue.get(0);
            }
            try {
                runDownload(c, id);
            } catch (Throwable t) {
                CrashGuard.append(c, "voice-dl " + id + ": " + CrashGuard.stackToString(t));
                finish(id, false, "دانلود نیمه‌کاره ماند — بعداً دوباره ادامه بده");
            }
            synchronized (queue) {
                queue.remove(id);
                listeners.remove(id);
                cancelFlags.remove(id);
                doneBytes.remove(id);
            }
        }
    }

    private static void finish(final String id, final boolean ok, final String msg) {
        final Progress p;
        synchronized (queue) {
            p = listeners.get(id);
        }
        if (p != null) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    p.onDone(ok, msg);
                }
            });
        }
    }

    private static void reportProgress(final String id, final int progress, final long done, final long total) {
        final Progress p;
        synchronized (queue) {
            p = listeners.get(id);
        }
        if (p != null) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    p.onProgress(progress, done, total);
                }
            });
        }
    }

    private static void runDownload(Context c, String id) {
        List<CatFile> files;
        if (isEspeakQueueId(id)) {
            files = espeakFilesFor(id.substring("espeak:".length()));
        } else {
            Voice v = catalogVoice(id);
            if (v == null) return;
            files = v.files;
        }
        long total = 0;
        for (CatFile f : files) total += f.size;
        long done = 0;
        boolean cancelled = false;
        String error = null;

        for (CatFile f : files) {
            if (Boolean.TRUE.equals(cancelFlags.get(id))) {
                cancelled = true;
                break;
            }
            File out = fileFor(c, f);
            if (out.exists() && out.length() == f.size && shaOk(out, f)) {
                done += f.size;
                doneBytes.put(id, done);
                reportProgress(id, (int) (done * 10000L / Math.max(1, total)), done, total);
                continue;
            }
            try {
                downloadFile(f, out, id, done, total);
                if (!shaOk(out, f)) {
                    out.delete();
                    error = "فایل «" + new File(f.rel).getName() + "» سالم دانلود نشد (تأیید SHA-256 ناموفق) — دوباره تلاش کن";
                    break;
                }
                done += f.size;
                doneBytes.put(id, done);
                reportProgress(id, (int) (done * 10000L / Math.max(1, total)), done, total);
            } catch (SecurityException se) {
                error = "دسترسی شبکه داده نشده است";
                break;
            } catch (Exception e) {
                if (Boolean.TRUE.equals(cancelFlags.get(id))) {
                    cancelled = true;
                } else {
                    error = "دانلود قطع شد («" + new File(f.rel).getName()
                            + "») — اینترنت را چک کن و «ادامهٔ دانلود» را بزن";
                }
                break;
            }
        }

        if (cancelled) {
            finish(id, false, "دانلود متوقف شد — هر وقت خواستی ادامه بده");
            return;
        }
        if (error != null) {
            finish(id, false, error);
            return;
        }
        invalidateCaches();
        finish(id, true, "دانلود کامل شد");
    }

    /** دانلود یک فایل با ادامه از نقطهٔ قطع (Range) و .part */
    private static void downloadFile(CatFile f, File out, String id, long baseDone, long total) throws Exception {
        File part = new File(out.getParentFile(), out.getName() + ".part");
        if (!out.getParentFile().exists()) out.getParentFile().mkdirs();
        long have = part.exists() ? part.length() : 0;
        if (have > f.size) {
            part.delete();
            have = 0;
        }

        int attempt = 0;
        while (true) {
            attempt++;
            HttpURLConnection conn = (HttpURLConnection) new URL(f.url).openConnection();
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(60000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "PDFKhwan/1.47 (Android)");
            if (have > 0) conn.setRequestProperty("Range", "bytes=" + have + "-");
            try {
                int code = conn.getResponseCode();
                if (code == 206) {
                    /* ادامه */
                } else if (code == 200) {
                    have = 0;
                    if (part.exists()) part.delete();
                } else if (code == 416) {
                    /* سرور می‌گوید Range بیرون است — فایل کامل است؟ */
                    if (part.length() == f.size) {
                        rename(part, out);
                        return;
                    }
                    part.delete();
                    have = 0;
                    conn.disconnect();
                    continue;
                } else {
                    throw new IOException("HTTP " + code);
                }
                InputStream in = conn.getInputStream();
                FileOutputStream fo = new FileOutputStream(part, have > 0 && code == 206);
                try {
                    byte[] buf = new byte[65536];
                    int n;
                    long fileDone = have;
                    while ((n = in.read(buf)) > 0) {
                        if (Boolean.TRUE.equals(cancelFlags.get(id))) {
                            throw new InterruptedException("cancel");
                        }
                        fo.write(buf, 0, n);
                        fileDone += n;
                        doneBytes.put(id, baseDone + fileDone);
                        reportProgress(id, (int) ((baseDone + fileDone) * 10000L / Math.max(1, total)),
                                baseDone + fileDone, total);
                    }
                    fo.flush();
                } finally {
                    try {
                        fo.close();
                    } catch (IOException ignored) {
                    }
                    try {
                        in.close();
                    } catch (IOException ignored) {
                    }
                }
                conn.disconnect();
                if (part.length() != f.size) {
                    /* اتصال وسط راه قطع شد — تا ۵ بار ادامه بده */
                    if (attempt < 6 && !Boolean.TRUE.equals(cancelFlags.get(id))) {
                        have = part.length();
                        continue;
                    }
                    throw new IOException("incomplete: " + part.length() + "/" + f.size);
                }
                rename(part, out);
                return;
            } catch (InterruptedException ie) {
                throw new Exception("cancel");
            } catch (IOException ioe) {
                if (attempt >= 6) throw ioe;
                have = part.exists() ? part.length() : 0;
                Thread.sleep(1500L);
            } finally {
                conn.disconnect();
            }
        }
    }

    private static void rename(File from, File to) throws IOException {
        if (to.exists()) to.delete();
        if (!from.renameTo(to)) throw new IOException("rename failed");
    }

    private static boolean shaOk(File f, CatFile spec) {
        if (spec.sha256 == null) return f.length() == spec.size;
        if (f.length() != spec.size) return false;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            FileInputStream fin = new FileInputStream(f);
            try {
                byte[] buf = new byte[65536];
                int n;
                while ((n = fin.read(buf)) > 0) md.update(buf, 0, n);
            } finally {
                fin.close();
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return spec.sha256.equals(sb.toString());
        } catch (Throwable t) {
            return false;
        }
    }

    /* ============================ حذف ============================ */

    public static void delete(Context c, String voiceId) {
        cancelDownload(voiceId);
        PiperEngine.get(c).release(voiceId);
        File dir = voiceDir(c, voiceId);
        File[] fs = dir.listFiles();
        if (fs != null) {
            for (File f : fs) f.delete();
        }
        dir.delete();
        /* پاک‌سازی .partهای همین صدا */
        if (catalogVoice(voiceId) != null) {
            Voice v = catalogVoice(voiceId);
            for (CatFile f : v.files) {
                File part = new File(fileFor(c, f).getParentFile(), fileFor(c, f).getName() + ".part");
                part.delete();
            }
        }
        if (catalogVoice(voiceId) == null) removeImportedId(c, voiceId);
        prefs(c).edit().remove("lang_" + voiceId).apply();
        /* اگر صدای فعال بود، آزادش کن */
        for (String lang : new String[]{"fa", "en"}) {
            if (voiceId.equals(prefs(c).getString("voice_" + lang, null))) {
                prefs(c).edit().remove("voice_" + lang).apply();
            }
        }
        invalidateCaches();
    }
}
