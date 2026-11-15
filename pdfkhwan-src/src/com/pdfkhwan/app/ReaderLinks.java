package com.pdfkhwan.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.media.AudioManager;
import android.speech.tts.TextToSpeech;
import android.util.LruCache;
import android.util.SparseArray;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import com.github.barteksc.pdfviewer.PDFView;
import com.github.barteksc.pdfviewer.listener.OnErrorListener;
import com.github.barteksc.pdfviewer.listener.OnLoadCompleteListener;
import com.github.barteksc.pdfviewer.listener.OnLongPressListener;
import com.github.barteksc.pdfviewer.listener.OnPageChangeListener;
import com.github.barteksc.pdfviewer.listener.OnPageErrorListener;
import com.github.barteksc.pdfviewer.listener.OnTapListener;
import com.github.barteksc.pdfviewer.scroll.DefaultScrollHandle;
import com.github.barteksc.pdfviewer.util.FitPolicy;
import com.pdfkhwan.app.PdfThumbs;
import com.pdfkhwan.app.SearchBridge;
import com.pdfkhwan.app.TocLoader;
import com.pdfkhwan.ppt.PptDoc;
import com.pdfkhwan.tts.PiperEngine;
import com.pdfkhwan.tts.SystemTtsEngine;
import com.pdfkhwan.tts.TtsEngine;
import com.pdfkhwan.tts.TtsService;
import com.pdfkhwan.tts.TtsSession;
import com.pdfkhwan.tts.VoiceModels;
import com.pdfkhwan.tts.VoiceModelsActivity;
import com.shockwave.pdfium.PdfDocument;
import com.shockwave.pdfium.PdfiumCore;
import com.shockwave.pdfium.util.SizeF;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/* v1.51: خوشهٔ جدا‌شده از ReaderActivity — تقسیم ساختاری */
public class ReaderLinks {
    final ReaderActivity host;
    ReaderLinks(ReaderActivity h) { this.host = h; }

    final java.util.ArrayList<Integer> linkBackStack = new java.util.ArrayList<Integer>();

    ImageView linkBackBtn = null;

    FormFiller.Link linkAt(float vx, float vy) {
        if (host.pdfInter == null || host.pdfInter.links.isEmpty()) return null;
        int page = host.pageAt(vx, vy);
        if (page < 0 || page >= host.pdfInter.links.size()) return null;
        java.util.ArrayList<FormFiller.Link> ls = host.pdfInter.links.get(page);
        if (ls == null || ls.isEmpty()) return null;
        RectF pr = host.pageRect(page);
        if (pr.width() <= 1f) return null;
        FormFiller.Link best = null;
        float bestArea = Float.MAX_VALUE;
        float ex = host.dp(4);
        for (FormFiller.Link L : ls) {
            float l = pr.left + L.fx0 * pr.width();
            float t = pr.top + L.fy0 * pr.height();
            float r2 = pr.left + L.fx1 * pr.width();
            float b = pr.top + L.fy1 * pr.height();
            if (vx >= l - ex && vx <= r2 + ex && vy >= t - ex && vy <= b + ex) {
                float ar = (r2 - l) * (b - t);
                if (ar < bestArea) {
                    bestArea = ar;
                    best = L;
                }
            }
        }
        return best;
    }

    boolean tapLink(float vx, float vy) {
        try {
            FormFiller.Link L = linkAt(vx, vy);
            if (L == null) return false;
            if (L.kind == FormFiller.L_URI) {
                linkConfirmDialog(L.uri);
                return true;
            }
            if (L.kind == FormFiller.L_GOTO) {
                linkGoto(L.destPage);
                return true;
            }
            host.toast("این نوع لینک در PDFKhwan باز نمی‌شود");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /* دیالوگ تأمین امنیتی لینک خارجی */

    void linkConfirmDialog(final String uri) {
        final Dialog[] dlg = new Dialog[1];
        LinearLayout card = Modern.sheetContent(host);
        card.addView(Modern.sheetHeader(host, "باز کردن لینک",
                "نمایش در مرورگر پیش‌فرض", new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                    }
                }));
        String hostname = uri;
        try {
            java.net.URI u = new java.net.URI(uri);
            if (u.getHost() != null && u.getHost().length() > 0) hostname = u.getHost();
        } catch (Throwable ignored) {
        }
        int m = host.dp(18);
        TextView tv = new TextView(host);
        tv.setText(hostname);
        tv.setTextColor(Modern.C_TEXT);
        tv.setTextSize(16f);
        tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tv.setPadding(m, host.dp(4), m, 0);
        card.addView(tv, new LinearLayout.LayoutParams(-1, -2));
        TextView full = new TextView(host);
        full.setText(uri);
        full.setTextColor(Modern.C_MUTED);
        full.setTextSize(11f);
        full.setPadding(m, host.dp(2), m, 0);
        card.addView(full, new LinearLayout.LayoutParams(-1, -2));
        boolean https = false;
        try {
            https = uri.toLowerCase().startsWith("https:");
        } catch (Throwable ignored) {
        }
        TextView sec = new TextView(host);
        sec.setText(https ? "اتصال امن (HTTPS)" : "هشدار: اتصال ناامن (HTTP)");
        sec.setTextColor(https ? Modern.C_ACCENT : 0xFFFFB74D);
        sec.setTextSize(12f);
        sec.setPadding(m, host.dp(6), m, host.dp(8));
        card.addView(sec, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout rowB = new LinearLayout(host);
        rowB.setOrientation(LinearLayout.HORIZONTAL);
        rowB.setGravity(android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL);
        TextView cp = Modern.tonalBtn(host, "کپی آدرس", Modern.C_MUTED, new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager) host.getSystemService(host.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("link", uri));
                    host.toast("آدرس کپی شد");
                } catch (Throwable ignored) {
                }
                try { dlg[0].dismiss(); } catch (Throwable ignored) {}
            }
        });
        rowB.addView(cp, new LinearLayout.LayoutParams(-2, -2));
        TextView go = Modern.filledBtn(host, "باز کن", Modern.C_ACCENT, 0xFF0B1220,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try { dlg[0].dismiss(); } catch (Throwable ignored) {}
                        try {
                            host.startActivity(new android.content.Intent(
                                    android.content.Intent.ACTION_VIEW, android.net.Uri.parse(uri)));
                        } catch (Throwable t) {
                            host.toast("مرورگری برای این لینک پیدا نشد");
                        }
                    }
                });
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-2, -2);
        glp.setMargins(host.dp(10), 0, 0, 0);
        rowB.addView(go, glp);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.setMargins(m, 0, m, host.dp(12));
        card.addView(rowB, rlp);
        dlg[0] = host.trackDialog(Modern.showCardAuto(host, card));
    }

    /* پرش داخلی + پشتهٔ بازگشت مثل مرورگر */

    void linkGoto(int page0) {
        if (page0 < 0 || page0 >= host.pageCount) return;
        try {
            int cur = host.pdfView.getCurrentPage();
            if (cur != page0) linkBackStack.add(cur);
            /* v1.50 FIX: پرش لینک داخلی (درخواست ۱۶) — بدون انیمیشنِ مسابقه‌کننده؛
               قطعی و فوری مثل فهرست مطالب */
            host.pdfView.stopAnimations();
            host.jumpTo(page0 + 1, false);
            host.pdfView.loadPageByOffset();
        } catch (Throwable ignored) {
        }
        showLinkBack();
    }

    void showLinkBack() {
        try {
            if (linkBackStack.isEmpty()) {
                if (linkBackBtn != null) linkBackBtn.setVisibility(8);
                return;
            }
            if (linkBackBtn == null) {
                ImageView btn = Modern.iconBtn(host, R.drawable.ic_back, 0xFFFFFFFF,
                        new View.OnClickListener() {
                            @Override public void onClick(View v) { linkBackClicked(); }
                        });
                linkBackBtn = btn;
                btn.setBackground(Modern.circle(0xE616203A, host));
                int p2 = host.dp(10);
                btn.setPadding(p2, p2, p2, p2);
                btn.setElevation(host.dp(6));
                android.widget.FrameLayout.LayoutParams flp = new android.widget.FrameLayout.LayoutParams(
                        host.dp(46), host.dp(46), android.view.Gravity.TOP | android.view.Gravity.LEFT);
                flp.topMargin = host.dp(58);
                flp.leftMargin = host.dp(12);
                ((ViewGroup) host.findViewById(R.id.pdfView).getParent()).addView(btn, flp);
            }
            linkBackBtn.setVisibility(0);
            linkBackBtn.bringToFront();
        } catch (Throwable ignored) {
        }
    }

    void linkBackClicked() {
        if (linkBackStack.isEmpty()) return;
        int pg = linkBackStack.remove(linkBackStack.size() - 1);
        try {
            host.jumpTo(pg + 1, true);
        } catch (Throwable ignored) {
        }
        showLinkBack();
    }
}
