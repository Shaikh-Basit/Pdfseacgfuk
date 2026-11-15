package com.pdfkhwan.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.pdf.PdfRenderer;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.LruCache;
import java.io.File;
import java.io.FileOutputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/* loaded from: classes.dex */
public class LibThumbs {
    private static final int W = 132;
    private static File cacheDir;
    private static LruCache<String, Bitmap> mem;
    private static ExecutorService pool;
    private static final Object LOCK = new Object();
    private static final Set<String> failed = new HashSet();
    private static final Set<String> pending = new HashSet();

    public interface Cb {
        void onThumb(String str, Bitmap bitmap);
    }

    public static Bitmap cached(String str) {
        Bitmap bitmap;
        synchronized (LOCK) {
            LruCache<String, Bitmap> lruCache = mem;
            bitmap = lruCache == null ? null : lruCache.get(str);
        }
        return bitmap;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static File diskFile(String str, long j, long j2) {
        long hashCode = (String.valueOf(str) + "_" + j + "_" + j2).hashCode();
        return new File(cacheDir, "t" + Long.toHexString(hashCode) + "_" + Long.toHexString(j) + ".png");
    }

    public static void get(Context context, final String str, final long j, final long j2, final Cb cb) {
        init(context);
        Bitmap cached = cached(str);
        if (cached != null) {
            cb.onThumb(str, cached);
            return;
        }
        synchronized (LOCK) {
            if (!failed.contains(str)) {
                Set<String> set = pending;
                if (!set.contains(str)) {
                    set.add(str);
                    pool.execute(new Runnable() { // from class: com.pdfkhwan.app.LibThumbs.2
                        @Override // java.lang.Runnable
                        public void run() {
                            Bitmap bitmap = null;
                            try {
                                File diskFile = LibThumbs.diskFile(str, j, j2);
                                Bitmap decodeFile = (!diskFile.exists() || diskFile.length() <= 0) ? null : BitmapFactory.decodeFile(diskFile.getAbsolutePath());
                                bitmap = decodeFile == null ? LibThumbs.render(new File(str), diskFile) : decodeFile;
                            } catch (Throwable unused) {
                            }
                            final Bitmap bitmapF = bitmap;
                            boolean z = bitmap != null;
                            synchronized (LibThumbs.LOCK) {
                                LibThumbs.pending.remove(str);
                                if (z) {
                                    LibThumbs.mem.put(str, bitmap);
                                } else {
                                    LibThumbs.failed.add(str);
                                }
                            }
                            if (z) {
                                Handler handler = new Handler(Looper.getMainLooper());
                                final Cb cb2 = cb;
                                final String str2 = str;
                                handler.post(new Runnable() { // from class: com.pdfkhwan.app.LibThumbs.2.1
                                    @Override // java.lang.Runnable
                                    public void run() {
                                        cb2.onThumb(str2, bitmapF);
                                    }
                                });
                            }
                        }
                    });
                }
            }
        }
    }

    private static void init(Context context) {
        synchronized (LOCK) {
            if (pool != null) {
                return;
            }
            pool = Executors.newSingleThreadExecutor();
            mem = new LruCache<String, Bitmap>(20971520) { // from class: com.pdfkhwan.app.LibThumbs.1
                /* JADX INFO: Access modifiers changed from: protected */
                @Override // android.util.LruCache
                public void entryRemoved(boolean z, String str, Bitmap bitmap, Bitmap bitmap2) {
                    /* v1.16 FIX: see PdfThumbs — no eager recycle; evicted
                       library thumbnails may still be bound to list rows. */
                }

                /* JADX INFO: Access modifiers changed from: protected */
                @Override // android.util.LruCache
                public int sizeOf(String str, Bitmap bitmap) {
                    if (bitmap == null) {
                        return 0;
                    }
                    return bitmap.getByteCount();
                }
            };
            File file = new File(context.getCacheDir(), "thumbs");
            cacheDir = file;
            file.mkdirs();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static Bitmap render(File file, File file2) throws Exception {
        ParcelFileDescriptor open = ParcelFileDescriptor.open(file, 268435456);
        PdfRenderer pdfRenderer = null;
        PdfRenderer.Page openPage = null;
        try {
            pdfRenderer = new PdfRenderer(open);
            if (pdfRenderer.getPageCount() <= 0) {
                throw new IllegalStateException("empty pdf");
            }
            openPage = pdfRenderer.openPage(0);
            Bitmap createBitmap = Bitmap.createBitmap(W, Math.max(1, Math.round((openPage.getHeight() * W) / openPage.getWidth())), Bitmap.Config.ARGB_8888);
            createBitmap.eraseColor(-1);
            openPage.render(createBitmap, null, null, 1);
            try {
                FileOutputStream fileOutputStream = new FileOutputStream(file2);
                try {
                    createBitmap.compress(Bitmap.CompressFormat.PNG, 90, fileOutputStream);
                } finally {
                    fileOutputStream.close();
                }
            } catch (Exception unused3) {
            }
            return createBitmap;
        } finally {
            /* v1.29 FIX: strict close order — page BEFORE renderer, renderer
               BEFORE the pfd. AOSP PdfRenderer.close() THROWS
               "Current page not closed" while a page is open, so the old
               close-first-with-page-open pattern silently leaked the native
               pdfium document on EVERY render (the exception was swallowed);
               the old catch(Exception) also missed OOM Errors from
               createBitmap. Every path now closes everything exactly once. */
            if (openPage != null) {
                try {
                    openPage.close();
                } catch (Throwable ignored) {
                }
            }
            if (pdfRenderer != null) {
                try {
                    pdfRenderer.close();
                } catch (Throwable ignored) {
                }
            }
            try {
                open.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
