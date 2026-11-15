package com.pdfkhwan.app;

import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/* loaded from: classes.dex */
public class LibraryScanner {
    private static final Object LOCK = new Object();
    private static Cancel current = new Cancel();
    private static int maxFiles = 5000;

    public interface Callback {
        void onScanDone(List<Item> list, boolean z);
    }

    public static final class Cancel {
        public volatile boolean stop = false;
    }

    public static final class Item {
        public final int kind;
        public final long mtime;
        public final String name;
        public final String path;
        public final long size;

        Item(String str, String str2, long j, long j2) {
            this(str, str2, j, j2, 0);
        }

        Item(String str, String str2, long j, long j2, int i) {
            this.path = str;
            this.name = str2;
            this.size = j;
            this.mtime = j2;
            this.kind = i;
        }
    }

    public static Cancel beginScan() {
        Cancel cancel;
        synchronized (LOCK) {
            current.stop = true;
            cancel = new Cancel();
            current = cancel;
        }
        return cancel;
    }

    public static void cancel() {
        synchronized (LOCK) {
            current.stop = true;
        }
    }

    public static void dedupe(List<Item> list) {
        HashSet hashSet = new HashSet((list.size() * 2) + 8);
        int i = 0;
        for (int i2 = 0; i2 < list.size(); i2++) {
            Item item = list.get(i2);
            if (hashSet.add(item.path)) {
                list.set(i, item);
                i++;
            }
        }
        while (list.size() > i) {
            list.remove(list.size() - 1);
        }
    }

    private static boolean hasPath(List<File> list, File file) {
        String absolutePath = file.getAbsolutePath();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).getAbsolutePath().equals(absolutePath)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPdf(String str) {
        if (str.length() < 4) {
            return false;
        }
        return str.substring(str.length() - 4).equalsIgnoreCase(".pdf");
    }

    private static boolean isPptx(String str) {
        String l = str.toLowerCase(java.util.Locale.US);
        return l.endsWith(".pptx") || l.endsWith(".ppt") || l.endsWith(".ppsx")
                || l.endsWith(".pps") /* v7: legacy slideshow */
                || l.endsWith(".ppsm") || l.endsWith(".pptm")
                || l.endsWith(".potx") || l.endsWith(".potm");
    }

    private static boolean isSupported(String str) {
        return isPdf(str) || isPptx(str);
    }

    public static List<File> roots(Context context) {
        ArrayList arrayList = new ArrayList();
        try {
            File externalStorageDirectory = Environment.getExternalStorageDirectory();
            if (externalStorageDirectory != null) {
                arrayList.add(externalStorageDirectory);
            }
        } catch (Throwable unused) {
        }
        try {
            File[] externalFilesDirs = context.getExternalFilesDirs(null);
            if (externalFilesDirs != null) {
                int length = externalFilesDirs.length;
                for (int i = 0; i < length; i++) {
                    File file = externalFilesDirs[i];
                    for (int i2 = 0; i2 < 4 && file != null; i2++) {
                        file = file.getParentFile();
                    }
                    if (file != null && !hasPath(arrayList, file)) {
                        arrayList.add(file);
                    }
                }
            }
        } catch (Throwable unused2) {
        }
        return arrayList;
    }

    public static Cancel scan(Context context, final Callback callback) {
        final List<File> roots = roots(context);
        final Cancel beginScan = beginScan();
        new Thread(new Runnable() { // from class: com.pdfkhwan.app.LibraryScanner.1
            @Override // java.lang.Runnable
            public void run() {
                final ArrayList arrayList = new ArrayList();
                try {
                    LibraryScanner.scanSync(roots, beginScan, arrayList);
                    boolean z = beginScan.stop;
                } catch (Throwable unused) {
                }
                if (!beginScan.stop) {
                    LibraryScanner.sort(arrayList);
                }
                final boolean z2 = beginScan.stop;
                Handler handler = new Handler(Looper.getMainLooper());
                final Callback callback2 = callback;
                handler.post(new Runnable() { // from class: com.pdfkhwan.app.LibraryScanner.1.1
                    @Override // java.lang.Runnable
                    public void run() {
                        callback2.onScanDone(arrayList, z2);
                    }
                });
            }
        }, "pdf-scan").start();
        /* v1.28: توکنِ همین اسکن به فراخوان برگردد تا onDestroy فقط اسکنِ
           خودش را لغو کند (قبلاً cancel() سراسری اسکنِ اکتیویتیِ جانشین را
           هم می‌کشت و کتابخانه بعد از تغییر زبان/فونت خالی گیر می‌کرد) */
        return beginScan;
    }

    public static void scanSync(List<File> list, Cancel cancel, List<Item> list2) {
        if (cancel == null || cancel.stop) {
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            File file = list.get(i);
            if (file != null && file.exists()) {
                walk(file, 0, cancel, list2);
            }
            if (cancel.stop) {
                return;
            }
        }
        dedupe(list2);
    }

    public static void sort(List<Item> list) {
        Collections.sort(list, new Comparator<Item>() { // from class: com.pdfkhwan.app.LibraryScanner.2
            @Override // java.util.Comparator
            public int compare(Item item, Item item2) {
                return item.mtime != item2.mtime ? item.mtime > item2.mtime ? -1 : 1 : item.name.compareToIgnoreCase(item2.name);
            }
        });
    }

    private static void walk(File file, int i, Cancel cancel, List<Item> list) {
        File[] listFiles;
        if (cancel.stop || i > 16 || list.size() >= maxFiles || (listFiles = file.listFiles()) == null) {
            return;
        }
        for (int i2 = 0; i2 < listFiles.length && !cancel.stop; i2++) {
            File file2 = listFiles[i2];
            String name = file2.getName();
            if (name != null && !name.startsWith(".")) {
                if (file2.isDirectory()) {
                    if (i != 0 || (!"Android".equalsIgnoreCase(name) && !"LOST.DIR".equalsIgnoreCase(name))) {
                        walk(file2, i + 1, cancel, list);
                    }
                } else if (isSupported(name)) {
                    long length = file2.length();
                    if (length <= 0) {
                        continue;
                    } else {
                        list.add(new Item(file2.getAbsolutePath(), name, length, file2.lastModified(), isPptx(name) ? 1 : 0));
                        if (list.size() >= maxFiles) {
                            return;
                        }
                    }
                } else {
                    continue;
                }
            }
        }
    }
}
