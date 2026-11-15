package com.shockwave.pdfium;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.RectF;
import android.os.ParcelFileDescriptor;
import android.view.Surface;
import com.shockwave.pdfium.util.Size;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/* ============================================================================
   PdfiumCore — reconstructed from the shipped PDFKhwan dex (v1.25) so the
   project can be recompiled without the discontinued barteksc AAR.

   The native method names/signatures below match the symbols exported by
   libjniPdfium.so (verified against `nm -D`):
     nativeOpenDocument(ILjava/lang/String;)J
     nativeOpenMemDocument([BLjava/lang/String;)J
     nativeGetPageCount(J)I
     nativeLoadPage(JI)J
     nativeLoadPages(JII)[J
     nativeClosePage(J)V
     nativeClosePages([J)V
     nativeCloseDocument(J)V
     nativeGetPageWidthPixel(JI)I
     nativeGetPageHeightPixel(JI)I
     nativeGetPageWidthPoint(J)I
     nativeGetPageHeightPoint(J)I
     nativeGetPageSizeByIndex(JII)Lcom/shockwave/pdfium/util/Size;
     nativeGetDocumentMetaText(JLjava/lang/String;)Ljava/lang/String;
     nativeGetFirstChildBookmark(JLjava/lang/Long;)Ljava/lang/Long;
     nativeGetSiblingBookmark(JJ)Ljava/lang/Long;
     nativeGetBookmarkTitle(J)Ljava/lang/String;
     nativeGetBookmarkDestIndex(JJ)J
     nativeGetDestPageIndex(JJ)Ljava/lang/Integer;
     nativeGetPageLinks(J)[J
     nativeGetLinkURI(JJ)Ljava/lang/String;
     nativeGetLinkRect(J)Landroid/graphics/RectF;
     nativePageCoordsToDevice(JIIIIIDD)Landroid/graphics/Point;
     nativeRenderPage(JLandroid/view/Surface;IIIIIZ)V
     nativeRenderPageBitmap(JLandroid/graphics/Bitmap;IIIIIZ)V
   ============================================================================ */
public final class PdfiumCore {

    private static final String TAG = "PdfiumCore";
    private static final Object lock = new Object();

    private static Class<?> FD_CLASS;
    private static final String FD_FIELD_NAME = "mFd";
    private static Field mFdField;
    private int mCurrentDpi = 72;

    static {
        /* dependency order: c++_shared -> modpng -> modft2 -> modpdfium ->
           jniPdfium (matches DT_NEEDED chains of the shipped .so files) */
        try {
            System.loadLibrary("c++_shared");
        } catch (UnsatisfiedLinkError ignored) {
            /* some devices already provide the STL */
        }
        try {
            System.loadLibrary("modpng");
            System.loadLibrary("modft2");
            System.loadLibrary("modpdfium");
            System.loadLibrary("jniPdfium");
        } catch (UnsatisfiedLinkError e) {
            throw new RuntimeException("Pdfium native libs unavailable", e);
        }
        try {
            FD_CLASS = ParcelFileDescriptor.class;
            mFdField = ParcelFileDescriptor.class.getDeclaredField(FD_FIELD_NAME);
            mFdField.setAccessible(true);
        } catch (Throwable t) {
            mFdField = null;
        }
    }

    public PdfiumCore(Context ctx) {
    }

    /* ------------------------------------------------------------------ open */

    public PdfDocument newDocument(ParcelFileDescriptor fd) throws IOException {
        return newDocument(fd, null);
    }

    public PdfDocument newDocument(ParcelFileDescriptor fd, String password) throws IOException {
        PdfDocument doc = new PdfDocument();
        synchronized (lock) {
            doc.parcelFileDescriptor = fd;
            try {
                doc.mNativeDocPtr = nativeOpenDocument(getNumFd(fd), password);
            } catch (Throwable t) {
                /* v1.29 FIX: a failed open (wrong password ->
                   PdfPasswordException) leaked the fd — every retry of the
                   password loop burned another descriptor for the life of
                   the process. Close it before rethrowing. */
                try {
                    fd.close();
                } catch (IOException ignored) {
                }
                if (t instanceof RuntimeException) {
                    throw (RuntimeException) t;
                }
                if (t instanceof IOException) {
                    throw (IOException) t;
                }
                throw new IOException(t);
            }
        }
        return doc;
    }

    public PdfDocument newDocument(byte[] bytes) throws IOException {
        return newDocument(bytes, null);
    }

    public PdfDocument newDocument(byte[] bytes, String password) throws IOException {
        PdfDocument doc = new PdfDocument();
        synchronized (lock) {
            doc.mNativeDocPtr = nativeOpenMemDocument(bytes, password);
        }
        return doc;
    }

    /* ---------------------------------------------------------------- pages */

    public int getPageCount(PdfDocument doc) {
        synchronized (lock) {
            return nativeGetPageCount(doc.mNativeDocPtr);
        }
    }

    public long openPage(PdfDocument doc, int pageIndex) throws IOException {
        synchronized (lock) {
            Long ptr = doc.mNativePagesPtr.get(pageIndex);
            if (ptr != null) return ptr;
            long pagePtr = nativeLoadPage(doc.mNativeDocPtr, pageIndex);
            doc.mNativePagesPtr.put(pageIndex, pagePtr);
            return pagePtr;
        }
    }

    public long[] openPage(PdfDocument doc, int fromIndex, int toIndex) throws IOException {
        synchronized (lock) {
            long[] ptrs = nativeLoadPages(doc.mNativeDocPtr, fromIndex, toIndex);
            for (int i = 0; i < toIndex - fromIndex + 1; i++) {
                doc.mNativePagesPtr.put(fromIndex + i, ptrs[i]);
            }
            return ptrs;
        }
    }

    /* ----------------------------------------------------------------- meta */

    public PdfDocument.Meta getDocumentMeta(PdfDocument doc) {
        synchronized (lock) {
            PdfDocument.Meta meta = new PdfDocument.Meta();
            meta.title = nativeGetDocumentMetaText(doc.mNativeDocPtr, "Title");
            meta.author = nativeGetDocumentMetaText(doc.mNativeDocPtr, "Author");
            meta.subject = nativeGetDocumentMetaText(doc.mNativeDocPtr, "Subject");
            meta.keywords = nativeGetDocumentMetaText(doc.mNativeDocPtr, "Keywords");
            meta.creator = nativeGetDocumentMetaText(doc.mNativeDocPtr, "Creator");
            meta.producer = nativeGetDocumentMetaText(doc.mNativeDocPtr, "Producer");
            meta.creationDate = nativeGetDocumentMetaText(doc.mNativeDocPtr, "CreationDate");
            meta.modDate = nativeGetDocumentMetaText(doc.mNativeDocPtr, "ModDate");
            return meta;
        }
    }

    /* ------------------------------------------------------------- bookmarks */

    public List<PdfDocument.Bookmark> getTableOfContents(PdfDocument doc) {
        synchronized (lock) {
            List<PdfDocument.Bookmark> outline = new ArrayList<>();
            Long first = nativeGetFirstChildBookmark(doc.mNativeDocPtr, null);
            recursiveGetBookmark(outline, doc, first);
            return outline;
        }
    }

    private void recursiveGetBookmark(List<PdfDocument.Bookmark> out, PdfDocument doc, Long bookmarkPtr) {
        if (bookmarkPtr == null) return;
        do {
            PdfDocument.Bookmark bm = new PdfDocument.Bookmark();
            bm.mNativePtr = bookmarkPtr;
            bm.title = nativeGetBookmarkTitle(bookmarkPtr);
            /* v1.29 FIX: the JNI signature is (JJ)J — a PRIMITIVE long, so
               autoboxing made `dest != null` a dead check; dest-less
               bookmarks (URI actions) flowed a 0 into GetDestPageIndex and
               picked up page 0, so the outline showed «ص 1» and tapped to
               the FIRST page. 0 now means "no destination" (pageIdx -1). */
            long dest = nativeGetBookmarkDestIndex(doc.mNativeDocPtr, bookmarkPtr);
            if (dest != 0) {
                Integer idx = nativeGetDestPageIndex(doc.mNativeDocPtr, dest);
                bm.pageIdx = idx == null ? -1 : idx;
            }
            Long child = nativeGetFirstChildBookmark(doc.mNativeDocPtr, bookmarkPtr);
            if (child != null) {
                recursiveGetBookmark(bm.children, doc, child);
            }
            out.add(bm);
            bookmarkPtr = nativeGetSiblingBookmark(doc.mNativeDocPtr, bookmarkPtr);
        } while (bookmarkPtr != null);
    }

    /* ----------------------------------------------------------------- size */

    public Size getPageSize(PdfDocument doc, int index) {
        synchronized (lock) {
            return nativeGetPageSizeByIndex(doc.mNativeDocPtr, index, mCurrentDpi);
        }
    }

    public int getPageWidth(PdfDocument doc, int index) {
        synchronized (lock) {
            return nativeGetPageWidthPixel(doc.mNativePagesPtr.get(index), mCurrentDpi);
        }
    }

    public int getPageHeight(PdfDocument doc, int index) {
        synchronized (lock) {
            return nativeGetPageHeightPixel(doc.mNativePagesPtr.get(index), mCurrentDpi);
        }
    }

    public int getPageWidthPoint(PdfDocument doc, int index) {
        synchronized (lock) {
            return nativeGetPageWidthPoint(doc.mNativePagesPtr.get(index));
        }
    }

    public int getPageHeightPoint(PdfDocument doc, int index) {
        synchronized (lock) {
            return nativeGetPageHeightPoint(doc.mNativePagesPtr.get(index));
        }
    }

    /* ----------------------------------------------------------------- links */

    public List<PdfDocument.Link> getPageLinks(PdfDocument doc, int pageIndex) {
        synchronized (lock) {
            List<PdfDocument.Link> ret = new ArrayList<>();
            Long pagePtr = doc.mNativePagesPtr.get(pageIndex);
            if (pagePtr == null) return ret;
            long[] linkPtrs = nativeGetPageLinks(pagePtr);
            for (long linkPtr : linkPtrs) {
                RectF bounds = nativeGetLinkRect(linkPtr);
                if (bounds == null) continue;
                String uri = nativeGetLinkURI(pagePtr, linkPtr);
                Integer destIdx = null;
                if (uri == null || uri.length() == 0) {
                    destIdx = nativeGetDestPageIndex(doc.mNativeDocPtr, linkPtr);
                }
                ret.add(new PdfDocument.Link(bounds, destIdx, uri));
            }
            return ret;
        }
    }

    /* ------------------------------------------------------------ coordinates */

    public Point mapPageCoordsToDevice(PdfDocument doc, int pageIndex, int startX, int startY,
                                       int sizeX, int sizeY, double zoom, double pptX, double pptY) {
        synchronized (lock) {
            return nativePageCoordsToDevice(doc.mNativePagesPtr.get(pageIndex), startX, startY,
                    sizeX, sizeY, 0, zoom, pptX, pptY);
        }
    }

    public RectF mapRectToDevice(PdfDocument doc, int pageIndex, int startX, int startY,
                                  int sizeX, int sizeY, int rotation, RectF rect) {
        synchronized (lock) {
            return mapRectToDeviceInt(doc, pageIndex, startX, startY, sizeX, sizeY, rotation, rect);
        }
    }

    private RectF mapRectToDeviceInt(PdfDocument doc, int pageIndex, int startX, int startY,
                                     int sizeX, int sizeY, int rotation, RectF rect) {
        synchronized (lock) {
            double[] coords = {rect.left, rect.top, rect.right, rect.bottom};
            for (int i = 0; i < 4; i += 2) {
                Point lt = nativePageCoordsToDevice(doc.mNativePagesPtr.get(pageIndex),
                        startX, startY, sizeX, sizeY, rotation, 1.0, coords[i], coords[i + 1]);
                if (lt == null) return null;
                coords[i] = lt.x;
                coords[i + 1] = lt.y;
            }
            return new RectF((float) coords[0], (float) coords[1],
                    (float) coords[2], (float) coords[3]);
        }
    }

    /* ---------------------------------------------------------------- render */

    public void renderPage(PdfDocument doc, Surface surface, int pageIndex,
                           int startX, int startY, int sizeX, int sizeY) {
        synchronized (lock) {
            nativeRenderPage(doc.mNativePagesPtr.get(pageIndex), surface, mCurrentDpi,
                    startX, startY, sizeX, sizeY, true);
        }
    }

    public void renderPageBitmap(PdfDocument doc, Bitmap bitmap, int pageIndex,
                                 int startX, int startY, int sizeX, int sizeY) {
        synchronized (lock) {
            nativeRenderPageBitmap(doc.mNativePagesPtr.get(pageIndex), bitmap, mCurrentDpi,
                    startX, startY, sizeX, sizeY, true);
        }
    }

    public void renderPageBitmap(PdfDocument doc, Bitmap bitmap, int pageIndex,
                                 int startX, int startY, int sizeX, int sizeY, boolean hideAnnotations) {
        synchronized (lock) {
            nativeRenderPageBitmap(doc.mNativePagesPtr.get(pageIndex), bitmap, mCurrentDpi,
                    startX, startY, sizeX, sizeY, hideAnnotations);
        }
    }

    /* ---------------------------------------------------------------- close */

    public void closeDocument(PdfDocument doc) {
        synchronized (lock) {
            if (doc == null || doc.mNativeDocPtr == 0) return;
            Iterator<Map.Entry<Integer, Long>> it = doc.mNativePagesPtr.entrySet().iterator();
            List<Long> pages = new ArrayList<>();
            while (it.hasNext()) {
                pages.add(it.next().getValue());
            }
            if (!pages.isEmpty()) {
                long[] arr = new long[pages.size()];
                for (int i = 0; i < arr.length; i++) arr[i] = pages.get(i);
                nativeClosePages(arr);
            }
            doc.mNativePagesPtr.clear();
            nativeCloseDocument(doc.mNativeDocPtr);
            doc.mNativeDocPtr = 0;
            if (doc.parcelFileDescriptor != null) {
                try {
                    doc.parcelFileDescriptor.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /* ----------------------------------------------------------------- misc */

    static int getNumFd(ParcelFileDescriptor fd) {
        try {
            if (mFdField == null) {
                mFdField = ParcelFileDescriptor.class.getDeclaredField(FD_FIELD_NAME);
                mFdField.setAccessible(true);
            }
            return mFdField.getInt(fd);
        } catch (Throwable t) {
            try {
                return fd.getFd();
            } catch (Throwable t2) {
                return -1;
            }
        }
    }

    /* --------------------------------------------------------------- natives */

    native long nativeOpenDocument(int fd, String password);

    native long nativeOpenMemDocument(byte[] data, String password);

    native int nativeGetPageCount(long documentPtr);

    native long nativeLoadPage(long documentPtr, int pageIndex);

    native long[] nativeLoadPages(long documentPtr, int fromIndex, int toIndex);

    native void nativeClosePage(long pagePtr);

    native void nativeClosePages(long[] pages);

    native void nativeCloseDocument(long documentPtr);

    native int nativeGetPageWidthPixel(long pagePtr, int dpi);

    native int nativeGetPageHeightPixel(long pagePtr, int dpi);

    native int nativeGetPageWidthPoint(long pagePtr);

    native int nativeGetPageHeightPoint(long pagePtr);

    native Size nativeGetPageSizeByIndex(long documentPtr, int pageIndex, int dpi);

    native String nativeGetDocumentMetaText(long documentPtr, String tag);

    native Long nativeGetFirstChildBookmark(long documentPtr, Long parent);

    native Long nativeGetSiblingBookmark(long documentPtr, Long bookmark);

    native String nativeGetBookmarkTitle(long bookmarkPtr);

    native long nativeGetBookmarkDestIndex(long documentPtr, long bookmark);

    native Integer nativeGetDestPageIndex(long documentPtr, long dest);

    native long[] nativeGetPageLinks(long pagePtr);

    native String nativeGetLinkURI(long pagePtr, long linkPtr);

    native RectF nativeGetLinkRect(long linkPtr);

    native Point nativePageCoordsToDevice(long pagePtr, int startX, int startY,
                                          int sizeX, int sizeY, int rotate,
                                          double zoom, double pptX, double pptY);

    native void nativeRenderPage(long pagePtr, Surface surface, int dpi,
                                  int startX, int startY, int sizeX, int sizeY,
                                  boolean hideAnnotations);

    native void nativeRenderPageBitmap(long pagePtr, Bitmap surface, int dpi,
                                       int startX, int startY, int sizeX, int sizeY,
                                       boolean hideAnnotations);
}
