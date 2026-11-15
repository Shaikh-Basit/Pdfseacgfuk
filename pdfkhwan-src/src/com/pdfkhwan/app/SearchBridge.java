package com.pdfkhwan.app;

import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;

/* loaded from: classes.dex */
public class SearchBridge {
    private final Host host;

    public interface Host {
        void onSearchDocInfo(int i);

        void onSearchDone(int i);

        void onSearchError(String str);

        void onSearchHit(int i, String str);

        void onSearchProgress(int i, int i2);

        void onSearchReady();

        void onSelError(String str);

        void onSelPage(int i, String str);

        void onToc(String str);

        /* v1.37: PDF→Word text extraction stream */
        void onTextPage(int page, String text);

        void onTextProgress(int done, int total);

        void onTextDone(int pages);

        void onTextError(String err);

        /* v1.38: PDF→Word LAYOUT extraction stream (fonts, sizes, boxes) */
        void onLayoutPage(int page, String json);

        void onLayoutProgress(int done, int total);

        void onLayoutDone(int pages);

        void onLayoutError(String err);
    }

    public SearchBridge(Host host) {
        this.host = host;
    }

    private void post(Runnable runnable) {
        new Handler(Looper.getMainLooper()).post(runnable);
    }

    @JavascriptInterface
    public void onDocInfo(final int i) {
        post(new Runnable() { // from class: com.pdfkhwan.app.SearchBridge.2
            @Override // java.lang.Runnable
            public void run() {
                SearchBridge.this.host.onSearchDocInfo(i);
            }
        });
    }

    @JavascriptInterface
    public void onError(final String str) {
        post(new Runnable() { // from class: com.pdfkhwan.app.SearchBridge.7
            @Override // java.lang.Runnable
            public void run() {
                SearchBridge.this.host.onSearchError(str);
            }
        });
    }

    @JavascriptInterface
    public void onProgress(final int i, final int i2) {
        post(new Runnable() { // from class: com.pdfkhwan.app.SearchBridge.3
            @Override // java.lang.Runnable
            public void run() {
                SearchBridge.this.host.onSearchProgress(i, i2);
            }
        });
    }

    @JavascriptInterface
    public void onReady() {
        post(new Runnable() { // from class: com.pdfkhwan.app.SearchBridge.1
            @Override // java.lang.Runnable
            public void run() {
                SearchBridge.this.host.onSearchReady();
            }
        });
    }

    @JavascriptInterface
    public void onSearchDone(final int i) {
        post(new Runnable() { // from class: com.pdfkhwan.app.SearchBridge.5
            @Override // java.lang.Runnable
            public void run() {
                SearchBridge.this.host.onSearchDone(i);
            }
        });
    }

    @JavascriptInterface
    public void onSearchResult(final int i, final String str) {
        post(new Runnable() { // from class: com.pdfkhwan.app.SearchBridge.4
            @Override // java.lang.Runnable
            public void run() {
                SearchBridge.this.host.onSearchHit(i, str);
            }
        });
    }

    @JavascriptInterface
    public void onSelError(final String str) {
        post(new Runnable() { // from class: com.pdfkhwan.app.SearchBridge.9
            @Override // java.lang.Runnable
            public void run() {
                SearchBridge.this.host.onSelError(str);
            }
        });
    }

    @JavascriptInterface
    public void onSelPage(final int i, final String str) {
        post(new Runnable() { // from class: com.pdfkhwan.app.SearchBridge.8
            @Override // java.lang.Runnable
            public void run() {
                SearchBridge.this.host.onSelPage(i, str);
            }
        });
    }

    @JavascriptInterface
    public void onToc(final String str) {
        post(new Runnable() { // from class: com.pdfkhwan.app.SearchBridge.6
            @Override // java.lang.Runnable
            public void run() {
                SearchBridge.this.host.onToc(str);
            }
        });
    }

    /* v1.37: text-extraction callbacks (Word export) */
    @JavascriptInterface
    public void onTextPage(final int page, final String text) {
        post(new Runnable() {
            @Override public void run() {
                SearchBridge.this.host.onTextPage(page, text);
            }
        });
    }

    @JavascriptInterface
    public void onTextProgress(final int done, final int total) {
        post(new Runnable() {
            @Override public void run() {
                SearchBridge.this.host.onTextProgress(done, total);
            }
        });
    }

    @JavascriptInterface
    public void onTextDone(final int pages) {
        post(new Runnable() {
            @Override public void run() {
                SearchBridge.this.host.onTextDone(pages);
            }
        });
    }

    @JavascriptInterface
    public void onTextError(final String err) {
        post(new Runnable() {
            @Override public void run() {
                SearchBridge.this.host.onTextError(err);
            }
        });
    }

    /* v1.38: layout-extraction callbacks (Word export with formatting) */
    @JavascriptInterface
    public void onLayoutPage(final int page, final String json) {
        post(new Runnable() {
            @Override public void run() {
                SearchBridge.this.host.onLayoutPage(page, json);
            }
        });
    }

    @JavascriptInterface
    public void onLayoutProgress(final int done, final int total) {
        post(new Runnable() {
            @Override public void run() {
                SearchBridge.this.host.onLayoutProgress(done, total);
            }
        });
    }

    @JavascriptInterface
    public void onLayoutDone(final int pages) {
        post(new Runnable() {
            @Override public void run() {
                SearchBridge.this.host.onLayoutDone(pages);
            }
        });
    }

    @JavascriptInterface
    public void onLayoutError(final String err) {
        post(new Runnable() {
            @Override public void run() {
                SearchBridge.this.host.onLayoutError(err);
            }
        });
    }
}
