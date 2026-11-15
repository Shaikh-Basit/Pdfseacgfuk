package com.shockwave.pdfium;

import android.graphics.RectF;
import android.os.ParcelFileDescriptor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/* Reconstructed from the shipped PDFKhwan dex (v1.25) — the complete
   PdfDocument surface: document state + Bookmark/Link/Meta types. */
public final class PdfDocument {

    long mNativeDocPtr;
    final Map<Integer, Long> mNativePagesPtr = new HashMap<>();
    ParcelFileDescriptor parcelFileDescriptor;

    public PdfDocument() {
    }

    public boolean hasPage(int pageIndex) {
        return mNativePagesPtr.containsKey(pageIndex);
    }

    public static final class Bookmark {
        long mNativePtr;
        long pageIdx;
        String title;
        final List<Bookmark> children = new ArrayList<>();

        public long getPageIdx() {
            return pageIdx;
        }

        public String getTitle() {
            return title;
        }

        public List<Bookmark> getChildren() {
            return children;
        }

        public boolean hasChildren() {
            return !children.isEmpty();
        }
    }

    public static final class Link {
        private final RectF bounds;
        private final Integer destPageIdx;
        private final String uri;

        public Link(RectF bounds, Integer destPageIdx, String uri) {
            this.bounds = bounds;
            this.destPageIdx = destPageIdx;
            this.uri = uri;
        }

        public RectF getBounds() {
            return bounds;
        }

        public Integer getDestPageIdx() {
            return destPageIdx;
        }

        public String getUri() {
            return uri;
        }
    }

    public static final class Meta {
        String title;
        String author;
        String subject;
        String keywords;
        String creator;
        String producer;
        String creationDate;
        String modDate;

        public String getTitle() {
            return title;
        }

        public String getAuthor() {
            return author;
        }

        public String getSubject() {
            return subject;
        }

        public String getKeywords() {
            return keywords;
        }

        public String getCreator() {
            return creator;
        }

        public String getProducer() {
            return producer;
        }

        public String getCreationDate() {
            return creationDate;
        }

        public String getModDate() {
            return modDate;
        }
    }
}
