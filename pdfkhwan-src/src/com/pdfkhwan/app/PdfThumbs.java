package com.pdfkhwan.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.LruCache;
import com.shockwave.pdfium.PdfDocument;
import com.shockwave.pdfium.PdfiumCore;
import java.io.File;
import java.util.HashSet;
import java.util.Set;

/* loaded from: classes.dex */
public class PdfThumbs {
    private static final int REOPEN_EVERY = 16;
    private PdfiumCore core;
    private final Context ctx;
    private PdfDocument doc;
    private ParcelFileDescriptor pfd;
    private final HandlerThread thread;
    private final Uri uri;
    /* v1.27 FIX: password never reached the thumbnail engine — thumbnails of
       an unlocked encrypted PDF stayed empty forever */
    private final String password;
    private volatile Handler worker;
    private int openedCount = 0;
    private volatile boolean released = false;
    private final Set<Integer> pending = new HashSet();
    /* v1.27: remember the open failure so a bad document doesn't retry a
       full PdfiumCore open for EVERY grid cell */
    private volatile boolean openFailed = false;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<Integer, Bitmap> cache = new LruCache<Integer, Bitmap>(33554432) { // from class: com.pdfkhwan.app.PdfThumbs.1
        /* JADX INFO: Access modifiers changed from: protected */
        @Override // android.util.LruCache
        public void entryRemoved(boolean z, Integer num, Bitmap bitmap, Bitmap bitmap2) {
            /* v1.16 FIX: no eager recycle — an evicted thumbnail can still
               be referenced by the thumbnails GridView; recycling it here
               caused rare "Canvas: trying to use a recycled bitmap" crashes.
               The GC reclaims evicted thumbnails (largeHeap is enabled). */
        }

        /* JADX INFO: Access modifiers changed from: protected */
        @Override // android.util.LruCache
        public int sizeOf(Integer num, Bitmap bitmap) {
            if (bitmap == null) {
                return 0;
            }
            return bitmap.getByteCount();
        }
    };

    public interface Callback {
        void onThumb(int i, Bitmap bitmap);
    }

    public PdfThumbs(Context context, Uri uri) {
        this(context, uri, null);
    }

    public PdfThumbs(Context context, Uri uri, String password) {
        this.ctx = context.getApplicationContext();
        this.uri = uri;
        this.password = password;
        HandlerThread handlerThread = new HandlerThread("pdf-thumbs");
        this.thread = handlerThread;
        handlerThread.start();
        this.worker = new Handler(handlerThread.getLooper());
    }

    private void closeQuiet() {
        PdfDocument pdfDocument;
        try {
            PdfiumCore pdfiumCore = this.core;
            if (pdfiumCore != null && (pdfDocument = this.doc) != null) {
                pdfiumCore.closeDocument(pdfDocument);
            }
        } catch (Throwable unused) {
        }
        this.doc = null;
        try {
            ParcelFileDescriptor parcelFileDescriptor = this.pfd;
            if (parcelFileDescriptor != null) {
                parcelFileDescriptor.close();
            }
        } catch (Throwable unused2) {
        }
        this.pfd = null;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void cycleDoc() {
        closeQuiet();
        ensureDoc();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void deliver(final int i, final Bitmap bitmap, final Callback callback) {
        this.main.post(new Runnable() { // from class: com.pdfkhwan.app.PdfThumbs.4
            @Override // java.lang.Runnable
            public void run() {
                if (PdfThumbs.this.released) {
                    return;
                }
                callback.onThumb(i, bitmap);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean ensureDoc() {
        if (this.released) {
            return false;
        }
        if (this.doc != null) {
            return true;
        }
        if (this.openFailed) {
            /* v1.27: don't re-attempt the same failing open per request */
            return false;
        }
        try {
            ParcelFileDescriptor open = "file".equals(this.uri.getScheme()) ? ParcelFileDescriptor.open(new File(this.uri.getPath()), 268435456) : this.ctx.getContentResolver().openFileDescriptor(this.uri, "r");
            this.pfd = open;
            PdfiumCore pdfiumCore = new PdfiumCore(this.ctx);
            this.core = pdfiumCore;
            PdfDocument newDocument = this.password != null
                    ? pdfiumCore.newDocument(open, this.password)
                    : pdfiumCore.newDocument(open);
            this.doc = newDocument;
            this.openedCount = 0;
            return newDocument != null;
        } catch (Throwable unused) {
            this.openFailed = true;
            closeQuiet();
            return false;
        }
    }

    public void release() {
        this.released = true;
        try {
            HandlerThread handlerThread = this.thread;
            if (handlerThread != null) {
                handlerThread.quitSafely();
            }
        } catch (Throwable unused) {
        }
        closeQuiet();
    }

    public void request(final int i, final Callback callback) {
        final Bitmap bitmap = this.cache.get(Integer.valueOf(i));
        if (bitmap != null && !bitmap.isRecycled()) {
            this.main.post(new Runnable() { // from class: com.pdfkhwan.app.PdfThumbs.2
                @Override // java.lang.Runnable
                public void run() {
                    if (PdfThumbs.this.released) {
                        return;
                    }
                    callback.onThumb(i, bitmap);
                }
            });
            return;
        }
        synchronized (this.pending) {
            if (this.pending.add(Integer.valueOf(i))) {
                this.worker.post(new Runnable() { // from class: com.pdfkhwan.app.PdfThumbs.3
                    @Override // java.lang.Runnable
                    public void run() {
                        try {
                            if (PdfThumbs.this.released) {
                                synchronized (PdfThumbs.this.pending) {
                                    PdfThumbs.this.pending.remove(Integer.valueOf(i));
                                }
                                return;
                            }
                            Bitmap bitmap2 = (Bitmap) PdfThumbs.this.cache.get(Integer.valueOf(i));
                            if (bitmap2 != null && !bitmap2.isRecycled()) {
                                PdfThumbs.this.deliver(i, bitmap2, callback);
                                synchronized (PdfThumbs.this.pending) {
                                    PdfThumbs.this.pending.remove(Integer.valueOf(i));
                                }
                                return;
                            }
                            if (!PdfThumbs.this.ensureDoc()) {
                                synchronized (PdfThumbs.this.pending) {
                                    PdfThumbs.this.pending.remove(Integer.valueOf(i));
                                }
                                return;
                            }
                            try {
                                int i2 = i - 1;
                                PdfThumbs.this.core.openPage(PdfThumbs.this.doc, i2);
                                int max = Math.max(1, Math.round((240 * PdfThumbs.this.core.getPageHeightPoint(PdfThumbs.this.doc, i2)) / Math.max(1, PdfThumbs.this.core.getPageWidthPoint(PdfThumbs.this.doc, i2))));
                                Bitmap createBitmap = Bitmap.createBitmap(240, max, Bitmap.Config.ARGB_8888);
                                createBitmap.eraseColor(-1);
                                PdfThumbs.this.core.renderPageBitmap(PdfThumbs.this.doc, createBitmap, i2, 0, 0, 240, max, false);
                                PdfThumbs.this.cache.put(Integer.valueOf(i), createBitmap);
                                PdfThumbs.this.openedCount++;
                                if (PdfThumbs.this.openedCount >= PdfThumbs.REOPEN_EVERY) {
                                    PdfThumbs.this.openedCount = 0;
                                    PdfThumbs.this.cycleDoc();
                                }
                                PdfThumbs.this.deliver(i, createBitmap, callback);
                            } catch (Throwable th) {
                                CrashGuard.append(PdfThumbs.this.ctx, "thumb page " + i + ": " + CrashGuard.stackToString(th) + "\n");
                            }
                            synchronized (PdfThumbs.this.pending) {
                                PdfThumbs.this.pending.remove(Integer.valueOf(i));
                            }
                        } catch (Throwable th2) {
                            synchronized (PdfThumbs.this.pending) {
                                PdfThumbs.this.pending.remove(Integer.valueOf(i));
                                throw th2;
                            }
                        }
                    }
                });
            }
        }
    }
}
