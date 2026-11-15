package com.pdfkhwan.app;

import android.content.Context;
import android.os.Environment;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/* ====================================================================
   v1.32: سیستم کامل حاشیه‌نویسی PDF خوان
   --------------------------------------------------------------------
   Text Annotation (متصل به متن):
     - هایلایت رنگی، زیرخط، خط‌خورده، زیرخط موج‌دار
     - یادداشت متنی متصل به محدودهٔ متن (در بازکردن دوبارهٔ فایل هم
       سر جایش می‌ماند چون به شمارهٔ کلماتِ همان صفحه گره خورده)
   Freehand Annotation (آزاد):
     - قلم، ماژیک نیمه‌شفاف، فلش، پاک‌کن
     - یادداشت آزاد روی صفحه (FNOTE)
   ذخیره‌سازی مطمئن:
     - فایل JSON جداگانه برای هر کتاب در حافظهٔ خصوصی برنامه
       (فایل اصلی PDF هرگز تغییر نمی‌کند)
     - نوشتن اتمی (tmp + rename) — قطع ناگهانی برنامه یا چرخاندن
       دستگاه چیزی را خراب نمی‌کند
     - جلوگیری از ایجاد حاشیه‌نویسی تکراری
     - خروجی/ورود JSON برای انتقال به نسخهٔ پشتیبان یا دستگاه دیگر
   ==================================================================== */
public class Annotations {

    /* ---- انواع حاشیه‌نویسی ---- */
    public static final int T_HL = 0;      /* هایلایت رنگی */
    public static final int T_UL = 1;      /* زیرخط */
    public static final int T_ST = 2;      /* خط‌خورده */
    public static final int T_SQ = 3;      /* زیرخط موج‌دار */
    public static final int T_NOTE = 4;    /* یادداشت متصل به متن */
    public static final int T_FNOTE = 5;   /* یادداشت آزاد روی صفحه */
    public static final int T_STROKE = 6;  /* خط دستی/ماژیک/فلش */

    public static String typeName(int t) {
        switch (t) {
            case T_HL: return "هایلایت";
            case T_UL: return "زیرخط";
            case T_ST: return "خط‌خورده";
            case T_SQ: return "موج‌دار";
            case T_NOTE: return "یادداشت متنی";
            case T_FNOTE: return "یادداشت صفحه";
            default: return "خط دستی";
        }
    }

    /* آیا این نوع به متن گره خورده است؟ */
    public static boolean isTextAttached(int t) {
        return t == T_HL || t == T_UL || t == T_ST || t == T_SQ || t == T_NOTE;
    }

    /* یک قلم حاشیه‌نویسی */
    public static final class Ann {
        public long id;
        public int type = T_HL;
        public int page = 0;          /* صفر-مبنا */
        public int w0 = -1, w1 = -1;  /* بازهٔ شمارهٔ کلمات (متصل به متن) */
        public String text = "";      /* متن انتخاب‌شده */
        public String note = "";      /* متن یادداشت */
        public int color = 0xFFFFD54F;/* رنگ ARGB */
        public float noteSize = 15f;  /* اندازهٔ قلم یادداشت (sp) */
        public float x, y;            /* لنگرگاه FNOTE — نرمال‌شده 0..1 روی صفحه */
        public float[] px, py;        /* نقاط خط دستی — نرمال‌شده 0..1 */
        public boolean arrow;         /* خط، فلش است */
        /* v1.38: خط ماژیک است؟ ماژیک با بلند مولتی‌پلای زیر متن رسم می‌شود
           (مثل ماژیک واقعی) — متن زیر خط هرچند بار روی هم بیفتد سالم می‌ماند */
        public boolean marker;
        public float strokeW = 2.5f;  /* ضخامت قلم (dp) */
        public long ts;               /* زمان ایجاد */

        /* کپی امن برای ویرایش (تغییر رنگ/یادداشت) */
        public Ann copy() {
            Ann a = new Ann();
            a.id = id; a.type = type; a.page = page;
            a.w0 = w0; a.w1 = w1;
            a.text = text == null ? "" : text;
            a.note = note == null ? "" : note;
            a.color = color; a.noteSize = noteSize;
            a.x = x; a.y = y;
            a.px = px == null ? null : px.clone();
            a.py = py == null ? null : py.clone();
            a.arrow = arrow; a.strokeW = strokeW; a.marker = marker; a.ts = ts;
            return a;
        }
    }

    public final ArrayList<Ann> items = new ArrayList<>();
    private File backing;
    private long nextId = 1;
    /* v1.34: برای پشتیبان عمومی (Documents/PDFKhwan-backup) */
    private Context appCtx;
    private String key;

    /* ---------------- بارگذاری / ذخیره ---------------- */

    private static File fileFor(Context ctx, String fileKey) {
        File dir = new File(ctx.getFilesDir(), "annotations");
        if (!dir.exists()) dir.mkdirs();
        /* fileKey = «name_size» — نام امن برای فایل */
        String safe = fileKey == null ? "doc" : fileKey.replaceAll("[^A-Za-z0-9_.\\-\\u0600-\\u06FF]", "_");
        if (safe.length() > 80) safe = safe.substring(safe.length() - 80);
        return new File(dir, safe + ".json");
    }

    /* v1.34: پشتیبان عمومی — حافظهٔ خصوصی برنامه با «حذف نصب» پاک می‌شود؛
       این نسخهٔ آینه در Documents/PDFKhwan-backup می‌ماند تا بعد از نصب
       مجدد (مثلاً بعد از عوض‌شدن کلید امضا) حاشیه‌نویسی‌ها بازیابی شوند.
       نوشتن best-effort است: اگر دسترسی نبود بی‌صدا رد می‌شود. */
    private static File publicBackupFor(Context ctx, String fileKey) {
        try {
            File docs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
            if (docs == null) return null;
            File dir = new File(docs, "PDFKhwan-backup");
            if (!dir.exists()) dir.mkdirs();
            String safe = fileKey == null ? "doc" : fileKey.replaceAll("[^A-Za-z0-9_.\\-\\u0600-\\u06FF]", "_");
            if (safe.length() > 80) safe = safe.substring(safe.length() - 80);
            return new File(dir, safe + ".json");
        } catch (Throwable t) {
            return null;
        }
    }

    private static String readFileUtf8(File f) {
        try {
            if (f == null || !f.exists() || f.length() == 0 || f.length() > 32 * 1024 * 1024) return null;
            StringBuilder sb = new StringBuilder();
            FileInputStream fis = new FileInputStream(f);
            try {
                InputStreamReader r = new InputStreamReader(fis, StandardCharsets.UTF_8);
                char[] buf = new char[8192];
                int n;
                while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
                r.close();
            } finally {
                try { fis.close(); } catch (Exception ignored) {}
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    public static Annotations load(Context ctx, String fileKey) {
        Annotations a = new Annotations();
        a.backing = fileFor(ctx, fileKey);
        a.appCtx = ctx.getApplicationContext();
        a.key = fileKey == null ? "doc" : fileKey;
        try {
            File f = a.backing;
            if (f.exists() && f.length() > 0 && f.length() < 32 * 1024 * 1024) {
                String s = readFileUtf8(f);
                if (s != null) a.fromJson(s);
            }
            /* v1.34: فایل خصوصی نیست (نصب تازه) ولی پشتیبان عمومی هست؟
               → بازیابی خودکار حاشیه‌نویسی‌ها بعد از حذف/نصب مجدد */
            if (a.items.isEmpty() && f != null && (!f.exists() || f.length() == 0)) {
                String s = readFileUtf8(publicBackupFor(ctx, fileKey));
                if (s != null && s.indexOf("items") >= 0) a.fromJson(s);
            }
        } catch (Throwable ignored) {
            /* فایل خراب = شروع خالی؛ اصل فایل دست‌نخورده می‌ماند */
        }
        return a;
    }

    /* ذخیرهٔ اتمی — اول در فایل موقت، بعد rename. حتی اگر وسط نوشتن
       برنامه بسته شود، فایل قبلی سالم می‌ماند. */
    public synchronized void save() {
        /* v1.37: یادداشت‌های خالی (گذاشته‌شده ولی هیچ‌وقت نوشته‌نشده) هرگز
           ذخیره نمی‌شوند — «یاداشت خالی اجازه نیست». */
        purgeEmptyNotes();
        if (backing == null) return;
        File tmp = new File(backing.getParentFile(), backing.getName() + ".tmp");
        try {
            FileOutputStream fos = new FileOutputStream(tmp);
            try {
                OutputStreamWriter w = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
                w.write(toJson());
                w.flush();
                w.close();
            } finally {
                try { fos.close(); } catch (Exception ignored) {}
            }
            /* rename روی یک filesystem اتمی است */
            if (backing.exists()) backing.delete();
            tmp.renameTo(backing);
            /* v1.34: آینهٔ عمومی best-effort (Documents/PDFKhwan-backup) —
               اگر دسترسی مجاز نبود بی‌صدا رد می‌شود؛ ذخیرهٔ اصلی قبلاً انجام شده */
            try {
                if (appCtx != null) {
                    File pub = publicBackupFor(appCtx, key);
                    if (pub != null) {
                        File ptmp = new File(pub.getParentFile(), pub.getName() + ".tmp");
                        FileInputStream in = new FileInputStream(backing);
                        try {
                            FileOutputStream out = new FileOutputStream(ptmp);
                            try {
                                byte[] buf = new byte[8192];
                                int n;
                                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                            } finally {
                                try { out.close(); } catch (Exception ignored) {}
                            }
                        } finally {
                            try { in.close(); } catch (Exception ignored) {}
                        }
                        if (pub.exists()) pub.delete();
                        ptmp.renameTo(pub);
                    }
                }
            } catch (Throwable ignoredPub) {
            }
        } catch (Throwable ignored) {
            try { tmp.delete(); } catch (Exception ignored2) {}
        }
    }

    /* ---------------- افزودن / حذف ---------------- */

    /* افزودن با جلوگیری از تکرار: همان (نوع، صفحه، بازه، رنگ) که هست
       اضافه نمی‌شود و null برمی‌گردد. */
    /* v1.37: حذف یادداشت‌های متنی/آزادِ بی‌محتوا — یک یادداشتی که برای
       نوشتن گذاشته شده ولی هیچ‌چیز داخلش ننوشته شده خودبخود حذف می‌شود. */
    public synchronized void purgeEmptyNotes() {
        for (int i = items.size() - 1; i >= 0; i--) {
            Ann a = items.get(i);
            if ((a.type == T_NOTE || a.type == T_FNOTE)
                    && (a.note == null || a.note.trim().length() == 0)) {
                items.remove(i);
            }
        }
    }

    public synchronized Ann add(Ann a) {
        if (a == null) return null;
        if (a.id <= 0) a.id = nextId++;
        if (a.ts <= 0) a.ts = System.currentTimeMillis();
        if (a.id >= nextId) nextId = a.id + 1;
        if (isTextAttached(a.type) && a.w0 >= 0) {
            for (int i = 0; i < items.size(); i++) {
                Ann b = items.get(i);
                if (b.type == a.type && b.page == a.page
                        && b.w0 == a.w0 && b.w1 == a.w1
                        && b.color == a.color) {
                    return null; /* تکراری */
                }
            }
        }
        items.add(a);
        return a;
    }

    public synchronized Ann byId(long id) {
        for (int i = 0; i < items.size(); i++) {
            Ann a = items.get(i);
            if (a.id == id) return a;
        }
        return null;
    }

    public synchronized void remove(long id) {
        for (int i = items.size() - 1; i >= 0; i--) {
            if (items.get(i).id == id) items.remove(i);
        }
    }

    public synchronized void removeAll(java.util.Collection<Long> ids) {
        for (int i = items.size() - 1; i >= 0; i--) {
            if (ids.contains(items.get(i).id)) items.remove(i);
        }
    }

    /* آخرین خط دستی (برای واگرد) */
    public synchronized Ann lastStroke() {
        for (int i = items.size() - 1; i >= 0; i--) {
            if (items.get(i).type == T_STROKE) return items.get(i);
        }
        return null;
    }

    /* ---------------- JSON ---------------- */

    public synchronized String toJson() {
        try {
            JSONObject root = new JSONObject();
            root.put("v", 1);
            root.put("doc", backing == null ? "" : backing.getName());
            JSONArray arr = new JSONArray();
            for (int i = 0; i < items.size(); i++) {
                Ann a = items.get(i);
                JSONObject o = new JSONObject();
                o.put("id", a.id);
                o.put("t", a.type);
                o.put("pg", a.page);
                if (a.w0 >= 0) { o.put("w0", a.w0); o.put("w1", a.w1); }
                if (a.text != null && a.text.length() > 0) o.put("tx", a.text);
                if (a.note != null && a.note.length() > 0) o.put("nt", a.note);
                o.put("c", a.color);
                if (a.noteSize != 15f) o.put("ns", a.noteSize);
                if (a.type == T_FNOTE) { o.put("x", a.x); o.put("y", a.y); }
                if (a.type == T_STROKE) {
                    JSONArray xs = new JSONArray(), ys = new JSONArray();
                    if (a.px != null) {
                        for (int k = 0; k < a.px.length; k++) {
                            xs.put(Math.round(a.px[k] * 10000f) / 10000.0);
                            ys.put(Math.round(a.py[k] * 10000f) / 10000.0);
                        }
                    }
                    o.put("px", xs); o.put("py", ys);
                    if (a.arrow) o.put("ar", 1);
                    if (a.marker) o.put("mk", 1);
                    o.put("wd", a.strokeW);
                }
                o.put("ts", a.ts);
                arr.put(o);
            }
            root.put("items", arr);
            return root.toString();
        } catch (Throwable t) {
            return "{\"v\":1,\"items\":[]}";
        }
    }

    public synchronized void fromJson(String s) {
        items.clear();
        nextId = 1;
        if (s == null || s.length() == 0) return;
        try {
            JSONObject root = new JSONObject(s);
            JSONArray arr = root.optJSONArray("items");
            if (arr == null) return;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Ann a = new Ann();
                a.id = o.optLong("id", 0);
                a.type = o.optInt("t", T_HL);
                a.page = o.optInt("pg", 0);
                a.w0 = o.optInt("w0", -1);
                a.w1 = o.optInt("w1", -1);
                a.text = o.optString("tx", "");
                a.note = o.optString("nt", "");
                a.color = o.optInt("c", 0xFFFFD54F);
                a.noteSize = (float) o.optDouble("ns", 15.0);
                a.x = (float) o.optDouble("x", 0.0);
                a.y = (float) o.optDouble("y", 0.0);
                a.arrow = o.optInt("ar", 0) == 1;
                a.marker = o.optInt("mk", 0) == 1;
                a.strokeW = (float) o.optDouble("wd", 2.5);
                a.ts = o.optLong("ts", 0);
                JSONArray xs = o.optJSONArray("px"), ys = o.optJSONArray("py");
                if (xs != null && ys != null && xs.length() > 0) {
                    int n = Math.min(xs.length(), ys.length());
                    a.px = new float[n];
                    a.py = new float[n];
                    for (int k = 0; k < n; k++) {
                        a.px[k] = (float) xs.optDouble(k, 0.0);
                        a.py[k] = (float) ys.optDouble(k, 0.0);
                    }
                }
                /* v1.38: قدیمی‌ها — خط‌های ماژیک نسخه‌های قبل رنگ را با آلفای
                   0x55 ذخیره می‌کردند؛ حالا ماژیک پرچم خودش را دارد و رنگ
                   باید یکدست (opaque) باشد تا ضرب مولتی‌پلای درست در بیاید */
                if (a.type == T_STROKE && !a.marker && (a.color >>> 24) < 0xC0) {
                    a.marker = true;
                    a.color |= 0xFF000000;
                }
                if (a.id >= nextId) nextId = a.id + 1;
                if (a.id <= 0) a.id = nextId++;
                items.add(a);
            }
        } catch (Throwable ignored) {
        }
    }

    /* ادغام برای ورود از فایل پشتیبان — موارد تکراری (همان id، یا همان
       نوع/صفحه/بازه/رنگ) رد می‌شوند؛ تعداد موارد اضافه‌شده برگردانده می‌شود */
    public synchronized int mergeFrom(Annotations other) {
        int added = 0;
        if (other == null) return 0;
        for (int i = 0; i < other.items.size(); i++) {
            Ann a = other.items.get(i).copy();
            /* همان شناسه از قبل هست؟ (ورود دوبارهٔ همین پشتیبان) */
            if (a.id > 0 && byId(a.id) != null) continue;
            /* خط دستی مشابه همان صفحه با همان نقاط؟ */
            if (a.type == T_STROKE && a.px != null && hasSimilarStroke(a)) continue;
            if (add(a) != null) added++;
        }
        return added;
    }

    /* خط دستی هم‌نقطه/هم‌رنگ روی همان صفحه؟ */
    private synchronized boolean hasSimilarStroke(Ann a) {
        for (int i = 0; i < items.size(); i++) {
            Ann b = items.get(i);
            if (b.type != T_STROKE || b.page != a.page || b.color != a.color
                    || b.px == null || a.px == null
                    || b.px.length != a.px.length) continue;
            boolean same = true;
            for (int k = 0; k < a.px.length; k++) {
                if (Math.abs(b.px[k] - a.px[k]) > 0.0005f
                        || Math.abs(b.py[k] - a.py[k]) > 0.0005f) {
                    same = false;
                    break;
                }
            }
            if (same) return true;
        }
        return false;
    }

    /* خروجی متنی همهٔ یادداشت‌ها (برای مطالعه/اشتراک) */
    public synchronized String notesAsText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            Ann a = items.get(i);
            if (a.note == null || a.note.length() == 0) continue;
            if (sb.length() > 0) sb.append("\n\n");
            sb.append("— صفحهٔ ").append(a.page + 1).append(" —\n");
            if (a.text != null && a.text.length() > 0) {
                sb.append("«").append(a.text).append("»\n");
            }
            sb.append(a.note);
        }
        return sb.toString();
    }
}
