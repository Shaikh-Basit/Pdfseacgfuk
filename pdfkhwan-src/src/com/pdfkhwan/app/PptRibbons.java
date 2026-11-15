package com.pdfkhwan.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.MetricAffectingSpan;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.VideoView;
import android.widget.Toast;
import com.pdfkhwan.ppt.PptDoc;
import com.pdfkhwan.ppt.PptxReader;
import com.pdfkhwan.ppt.PptxWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/* v1.51: خوشهٔ جدا‌شده از PptEditorActivity — تقسیم ساختاری */
public class PptRibbons {
    final PptEditorActivity host;
    PptRibbons(PptEditorActivity h) { this.host = h; }

    int activeTab = -1;          /* -1 = ribbon collapsed (nothing shown) */
    TextView tabInsert, tabDesign, tabFormat;

    void buildUi() {
        /* FrameLayout root: editor content + inline-text editor + slideshow */
        FrameLayout root = new FrameLayout(host);
        host.setContentView(root);

        LinearLayout content = new LinearLayout(host);
        content.setOrientation(LinearLayout.VERTICAL);
        root.addView(content, new FrameLayout.LayoutParams(-1, -1));

        content.addView(buildTitleBar(), new LinearLayout.LayoutParams(-1, host.dp(56)));

        /* big full-width canvas — the slide is shown HERE when a filmstrip
           thumbnail is tapped (weight 1: fills everything between the title
           bar and the bottom command deck, Office-mobile style) */
        host.canvas = host.new PptCanvas(host);
        host.canvas.setBackgroundColor(PptEditorActivity.OFFICE_CANVAS_BG);
        content.addView(host.canvas, new LinearLayout.LayoutParams(-1, 0, 1f));

        /* filmstrip — horizontal thumbnails above the bottom deck; a slim
           handle bar collapses it to gain canvas space (mobile PowerPoint
           hides the strip behind a Views toggle — same idea) */
        host.filmHost = new LinearLayout(host);
        host.filmHost.setOrientation(LinearLayout.VERTICAL);
        host.filmstrip = host.new FilmStrip(host);
        host.filmHost.addView(host.filmstrip, new LinearLayout.LayoutParams(-1, host.dp(78)));
        host.filmHandle = host.filmHandleBar();
        host.filmHost.addView(host.filmHandle, new LinearLayout.LayoutParams(-1, host.dp(16)));
        content.addView(host.filmHost, new LinearLayout.LayoutParams(-1, host.dp(94)));

        /* ribbon strip — VISIBLE only while a tab is open; sits directly
           ABOVE the bottom tab row like Word/PowerPoint mobile */
        host.ribbonScroller = new HorizontalScrollView(host);
        host.ribbonScroller.setBackgroundColor(0xFFFAF9F8);
        host.ribbonScroller.setHorizontalScrollBarEnabled(false);
        host.ribbonScroller.setVisibility(View.GONE);
        host.ribbon = new LinearLayout(host);
        host.ribbon.setOrientation(LinearLayout.HORIZONTAL);
        host.ribbon.setGravity(Gravity.CENTER_VERTICAL);
        host.ribbon.setPadding(host.dp(4), host.dp(3), host.dp(4), host.dp(3));
        host.ribbonScroller.addView(host.ribbon, new ViewGroup.LayoutParams(-2, host.dp(74)));
        content.addView(host.ribbonScroller, new LinearLayout.LayoutParams(-1, host.dp(80)));

        /* ribbon tab row — at the BOTTOM EDGE (Office mobile signature).
           «قالب‌بندی» appears contextually when a shape is selected. */
        LinearLayout tabs = new LinearLayout(host);
        tabs.setBackgroundColor(0xFFFFFFFF);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        host.tabHome = tabBtn("خانه");
        tabInsert = tabBtn("درج");
        tabDesign = tabBtn("طراحی");
        tabFormat = tabBtn("قالب‌بندی");
        tabFormat.setVisibility(View.GONE);
        tabs.addView(host.tabHome, linW(1));
        tabs.addView(tabInsert, linW(1));
        tabs.addView(tabDesign, linW(1));
        tabs.addView(tabFormat, linW(1));
        tabs.addView(tabCollapseBtn(), new LinearLayout.LayoutParams(host.dp(44), -1));
        content.addView(tabs, new LinearLayout.LayoutParams(-1, host.dp(48)));

        /* floating zoom pill over the canvas (bottom-right) — replaces the
           old status bar; mobile Office keeps zoom controls near the canvas */
        addZoomPill(root);

        /* fullscreen slideshow overlay — covers EVERYTHING
           (FrameLayout child, MATCH_PARENT: no LinearLayout weight bug) */
        host.slideshowLayer = new FrameLayout(host);
        host.slideshowLayer.setVisibility(View.GONE);
        root.addView(host.slideshowLayer, new FrameLayout.LayoutParams(-1, -1));

        /* inline text editor layer (double-tap a text shape on the canvas) */
        host.inlineLayer = new FrameLayout(host);
        host.inlineLayer.setVisibility(View.GONE);
        root.addView(host.inlineLayer, new FrameLayout.LayoutParams(-1, -1));

        collapseRibbon();
    }

    View tabCollapseBtn() {
        TextView t = new TextView(host);
        t.setText("▾");
        t.setTextColor(PptEditorActivity.OFFICE_TAB_IDLE);
        t.setTextSize(18f);
        t.setGravity(Gravity.CENTER);
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                collapseRibbon();
            }
        });
        return t;
    }

    /* floating zoom controls: − / fit / ＋ (+ % readout above) */

    void addZoomPill(FrameLayout root) {
        LinearLayout pill = new LinearLayout(host);
        pill.setOrientation(LinearLayout.VERTICAL);
        pill.setGravity(Gravity.CENTER_HORIZONTAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(host.dp(18));
        bg.setColor(0xE6FFFFFF);
        pill.setBackground(bg);
        pill.setPadding(0, host.dp(2), 0, host.dp(2));

        host.statusZoom = new TextView(host);
        host.statusZoom.setTextColor(0xFF444444);
        host.statusZoom.setTextSize(10f);
        host.statusZoom.setGravity(Gravity.CENTER);
        pill.addView(host.statusZoom, new LinearLayout.LayoutParams(-1, host.dp(16)));

        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(zoomBtn("＋", new Runnable() {
            @Override public void run() { host.zoomBy(1.25f); }
        }));
        row.addView(zoomBtn("⤢", new Runnable() {
            @Override public void run() { host.canvas.resetView(); }
        }));
        row.addView(zoomBtn("－", new Runnable() {
            @Override public void run() { host.zoomBy(0.8f); }
        }));
        pill.addView(row, new LinearLayout.LayoutParams(-2, host.dp(38)));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2);
        lp.gravity = Gravity.BOTTOM | Gravity.LEFT;
        lp.leftMargin = host.dp(10);
        lp.bottomMargin = host.dp(110);
        root.addView(pill, lp);
        pill.bringToFront();
    }

    TextView zoomBtn(String g, final Runnable r) {
        TextView b = new TextView(host);
        b.setText(g);
        b.setTextColor(0xFF3B3B3B);
        b.setTextSize(15f);
        b.setGravity(Gravity.CENTER);
        b.setMinWidth(host.dp(36));
        b.setMinHeight(host.dp(36));
        b.setBackground(pressBg(0x22000000));
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { r.run(); }
        });
        return b;
    }

    LinearLayout.LayoutParams linW(float weight) {
        return new LinearLayout.LayoutParams(0, -1, weight);
    }

    View buildTitleBar() {
        LinearLayout bar = new LinearLayout(host);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(PptEditorActivity.OFFICE_TITLE_BG);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(host.dp(2), 0, host.dp(4), 0);

        /* ← back — Office mobile always offers an explicit exit */
        bar.addView(titleBtn("←", 20f, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                host.onBackPressed();
            }
        }));

        ImageView icon = new ImageView(host);
        icon.setImageResource(R.drawable.ic_ppt);
        bar.addView(icon, new LinearLayout.LayoutParams(host.dp(26), host.dp(34)));

        host.titleView = new TextView(host);
        host.titleView.setTextColor(0xFFFFFFFF);
        host.titleView.setTextSize(14f);
        host.titleView.setSingleLine(true);
        host.titleView.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        host.titleView.setText(host.docName);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1f);
        tp.setMargins(host.dp(4), 0, host.dp(4), 0);
        bar.addView(host.titleView, tp);

        /* ▦ slide sorter — grid of all slides */
        bar.addView(titleBtn("▦", 18f, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                host.showSlideSorter();
            }
        }));
        /* ↗ share the .pptx */
        bar.addView(titleBtn("↗", 17f, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                host.shareDoc();
            }
        }));
        /* big touch targets (≥46dp) — undo / redo / save */
        bar.addView(titleBtn("⟲", 20f, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                host.undo();
            }
        }));
        bar.addView(titleBtn("⟳", 20f, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                host.redo();
            }
        }));
        bar.addView(titleBtn("ذخیره", 14f, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                host.save(false);
            }
        }));
        /* ▶ slideshow — replaces the old نمایش tab, forces LANDSCAPE */
        bar.addView(playBtn());
        bar.addView(titleBtn("⋮", 22f, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                host.moreMenu(v);
            }
        }));
        return bar;
    }

    /* rounded translucent pressed-feedback background */

    StateListDrawable pressBg(int pressedColor) {
        GradientDrawable idle = new GradientDrawable();
        idle.setCornerRadius(host.dp(9));
        idle.setColor(0x00000000);
        GradientDrawable pressed = new GradientDrawable();
        pressed.setCornerRadius(host.dp(9));
        pressed.setColor(pressedColor);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, pressed);
        s.addState(new int[0], idle);
        return s;
    }

    TextView titleBtn(String label, float glyphSize, View.OnClickListener click) {
        TextView b = new TextView(host);
        b.setText(label);
        b.setTextColor(0xFFFFFFFF);
        b.setTextSize(glyphSize);
        b.setGravity(Gravity.CENTER);
        b.setMinWidth(host.dp(46));
        b.setMinHeight(host.dp(46));
        b.setPadding(host.dp(6), host.dp(6), host.dp(6), host.dp(6));
        b.setTypeface(label.length() <= 2 ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        b.setBackground(pressBg(0x40FFFFFF));
        b.setOnClickListener(click);
        return b;
    }

    TextView playBtn() {
        TextView b = titleBtn("▶", 20f, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                host.startSlideshow();
            }
        });
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setCornerRadius(host.dp(10));
        gd.setColor(0x4DFFFFFF);          /* stands out on the Office-red bar */
        b.setBackground(gd);
        b.setContentDescription("نمایش اسلاید‌ها");
        return b;
    }

    TextView tabBtn(String label) {
        TextView t = new TextView(host);
        t.setText(label);
        t.setTextSize(13f);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(null, Typeface.BOLD);
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectTab(v == host.tabHome ? 0 : v == tabInsert ? 1 : v == tabDesign ? 2 : 3);
            }
        });
        return t;
    }

    /* tabs toggle like PowerPoint mobile: first tap opens the ribbon,
       tapping the SAME tab again hides it; nothing shows until a tab
       is tapped. Tab 3 = «قالب‌بندی» — CONTEXTUAL, only exists while a
       shape is selected (PowerPoint's Shape tab). */

    void selectTab(int which) {
        if (which == 3 && host.sel == null) return;
        if (which == activeTab) {
            collapseRibbon();
            return;
        }
        activeTab = which;
        host.ribbonScroller.setVisibility(View.VISIBLE);
        host.ribbon.removeAllViews();
        styleTab(host.tabHome, which == 0);
        styleTab(tabInsert, which == 1);
        styleTab(tabDesign, which == 2);
        styleTab(tabFormat, which == 3);
        if (which == 0) buildHomeRibbon();
        else if (which == 1) buildInsertRibbon();
        else if (which == 2) buildDesignRibbon();
        else buildFormatRibbon();
    }

    void collapseRibbon() {
        activeTab = -1;
        if (host.ribbonScroller != null) host.ribbonScroller.setVisibility(View.GONE);
        styleTab(host.tabHome, false);
        styleTab(tabInsert, false);
        styleTab(tabDesign, false);
        styleTab(tabFormat, false);
    }

    /* contextual tab visibility: shown while a shape is selected; if the
       ribbon is open and the selection disappears, fall back to خانه */

    void syncContextualTab() {
        if (tabFormat == null) return;
        boolean has = host.sel != null;
        if (has && tabFormat.getVisibility() != View.VISIBLE) tabFormat.setVisibility(View.VISIBLE);
        if (!has) {
            if (tabFormat.getVisibility() == View.VISIBLE) tabFormat.setVisibility(View.GONE);
            if (activeTab == 3) selectTab(0);
        }
    }

    void styleTab(TextView t, boolean active) {
        t.setTextColor(active ? PptEditorActivity.OFFICE_TAB_ACTIVE : PptEditorActivity.OFFICE_TAB_IDLE);
        /* Office mobile: active tab carries a slim accent underline */
        if (active) {
            GradientDrawable strip = new GradientDrawable();
            strip.setColor(PptEditorActivity.OFFICE_TAB_ACTIVE);
            LayerDrawable ld = new LayerDrawable(new Drawable[]{new ColorDrawable(0x00000000), strip});
            ld.setLayerInset(1, 0, host.dp(44) - host.dp(3), 0, 0);
            t.setBackground(ld);
        } else {
            t.setBackground(null);
        }
    }

    /* ============================== ribbons ================================ */

    View groupStart(String label) {
        LinearLayout g = new LinearLayout(host);
        g.setOrientation(LinearLayout.VERTICAL);
        g.setGravity(Gravity.CENTER_HORIZONTAL);
        g.setPadding(host.dp(5), host.dp(2), host.dp(5), host.dp(2));
        g.setBackgroundColor(0xFFF3F2F1);
        LinearLayout tools = new LinearLayout(host);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.setGravity(Gravity.CENTER_VERTICAL);
        g.addView(tools);
        TextView t = new TextView(host);
        t.setText(label);
        t.setTextSize(9.5f);
        t.setTextColor(0xFF8A8886);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        g.addView(t);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -1);
        lp.setMargins(host.dp(2), 0, host.dp(2), 0);
        g.setLayoutParams(lp);
        host.groupHost = tools;
        return g;
    }

    View rbTool(String glyph, String label, View.OnClickListener click) {
        LinearLayout b = new LinearLayout(host);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER_HORIZONTAL);
        b.setPadding(host.dp(6), host.dp(4), host.dp(6), host.dp(4));
        b.setMinimumWidth(host.dp(56));
        TextView g = new TextView(host);
        g.setText(glyph);
        g.setTextSize(16f);
        g.setTextColor(PptEditorActivity.OFFICE_RIBBON_FG);
        g.setGravity(Gravity.CENTER);
        g.setSingleLine(true);
        TextView l = new TextView(host);
        l.setText(label);
        l.setTextSize(9.5f);
        l.setTextColor(0xFF5D5B59);
        l.setGravity(Gravity.CENTER);
        l.setSingleLine(true);
        b.addView(g);
        b.addView(l);
        b.setOnClickListener(click);
        if (host.groupHost != null) {
            host.groupHost.addView(b, new LinearLayout.LayoutParams(-2, -2));
            View stub = new View(host);
            stub.setVisibility(View.GONE);
            return stub;
        }
        return b;
    }

    void buildHomeRibbon() {
        host.ribbon.addView(groupStart("متن"));
        host.ribbon.addView(rbTool("Aa", "ویرایش متن", new View.OnClickListener() {
            @Override public void onClick(View v) { host.editSelectedText(); }
        }));
        host.ribbon.addView(rbTool("B", "درشت", new View.OnClickListener() {
            @Override public void onClick(View v) { host.applyTextStyle(2, host.curBold() ? 0f : 1f); }
        }));
        host.ribbon.addView(rbTool("I", "کج", new View.OnClickListener() {
            @Override public void onClick(View v) { host.applyTextStyle(3, host.curItalic() ? 0f : 1f); }
        }));
        host.ribbon.addView(rbTool("U", "زیرخط", new View.OnClickListener() {
            @Override public void onClick(View v) { host.applyTextStyle(4, host.curUnderline() ? 0f : 1f); }
        }));
        host.ribbon.addView(rbTool("A+", "درشت‌تر", new View.OnClickListener() {
            @Override public void onClick(View v) { host.applyTextSize(2f); }
        }));
        host.ribbon.addView(rbTool("A−", "ریزتر", new View.OnClickListener() {
            @Override public void onClick(View v) { host.applyTextSize(-2f); }
        }));
        host.ribbon.addView(rbTool("رنگ", "رنگ متن", new View.OnClickListener() {
            @Override public void onClick(View v) { host.textDialogs.pickTextColor(); }
        }));
        host.ribbon.addView(rbTool("Ab", "فونت", new View.OnClickListener() {
            @Override public void onClick(View v) { host.textDialogs.fontDialog(); }
        }));
        host.ribbon.addView(rbTool("•≡", "بولت", new View.OnClickListener() {
            @Override public void onClick(View v) { host.textDialogs.bulletDialog(); }
        }));
        host.ribbon.addView(rbTool("⇕", "فاصله‌ها", new View.OnClickListener() {
            @Override public void onClick(View v) { host.textDialogs.spacingDialog(); }
        }));
        host.ribbon.addView(rbTool("☰→", "راست‌چین", new View.OnClickListener() {
            @Override public void onClick(View v) { host.applyAlign(PptDoc.Para.ALIGN_R); }
        }));
        host.ribbon.addView(rbTool("→☰", "وسط‌چین", new View.OnClickListener() {
            @Override public void onClick(View v) { host.applyAlign(PptDoc.Para.ALIGN_CTR); }
        }));
        host.ribbon.addView(rbTool("←☰", "چپ‌چین", new View.OnClickListener() {
            @Override public void onClick(View v) { host.applyAlign(PptDoc.Para.ALIGN_L); }
        }));

        host.ribbon.addView(groupStart("شکل"));
        host.ribbon.addView(rbTool("دلخواه", "رنگ پر", new View.OnClickListener() {
            @Override public void onClick(View v) { host.textDialogs.pickFillColor(); }
        }));
        host.ribbon.addView(rbTool("خط", "رنگ خط", new View.OnClickListener() {
            @Override public void onClick(View v) { host.textDialogs.pickLineColor(); }
        }));
        host.ribbon.addView(rbTool("▬", "ضخامت", new View.OnClickListener() {
            @Override public void onClick(View v) { host.textDialogs.lineDialog(); }
        }));
        host.ribbon.addView(rbTool("┄", "خط‌چین", new View.OnClickListener() {
            @Override public void onClick(View v) { host.textDialogs.dashDialog(); }
        }));
        host.ribbon.addView(rbTool("∅", "بدون پر", new View.OnClickListener() {
            @Override public void onClick(View v) { host.mutateSel(new Runnable() {
                @Override public void run() { host.sel.fill.type = PptDoc.Fill.NONE; }
            }); }
        }));

        host.ribbon.addView(groupStart("چیدمان"));
        host.ribbon.addView(rbTool("⧉", "کپی", new View.OnClickListener() {
            @Override public void onClick(View v) { host.duplicateSel(); }
        }));
        host.ribbon.addView(rbTool("🗑", "حذف", new View.OnClickListener() {
            @Override public void onClick(View v) { host.deleteSel(); }
        }));
        host.ribbon.addView(rbTool("⬆", "جلو", new View.OnClickListener() {
            @Override public void onClick(View v) { host.reorder(1); }
        }));
        host.ribbon.addView(rbTool("⬇", "عقب", new View.OnClickListener() {
            @Override public void onClick(View v) { host.reorder(-1); }
        }));
        host.ribbon.addView(rbTool("⊞", "تراز", new View.OnClickListener() {
            @Override public void onClick(View v) { host.textDialogs.alignDialog(); }
        }));

        host.ribbon.addView(groupStart("اسلاید"));
        host.ribbon.addView(rbTool("＋", "اسلاید جدید", new View.OnClickListener() {
            @Override public void onClick(View v) { host.addSlideDialog(); }
        }));
        host.ribbon.addView(rbTool("⧉ˢ", "کپی اسلاید", new View.OnClickListener() {
            @Override public void onClick(View v) { host.duplicateSlide(); }
        }));
        host.ribbon.addView(rbTool("🗑ˢ", "حذف اسلاید", new View.OnClickListener() {
            @Override public void onClick(View v) { host.deleteSlide(); }
        }));
        host.groupHost = null;
    }

    void buildInsertRibbon() {
        host.ribbon.addView(groupStart("درج"));
        host.ribbon.addView(rbTool("متن", "جعبه متن", new View.OnClickListener() {
            @Override public void onClick(View v) { host.insertTextBox(); }
        }));
        host.ribbon.addView(rbTool("🖼", "تصویر", new View.OnClickListener() {
            @Override public void onClick(View v) { host.pickImage(); }
        }));
        host.ribbon.addView(rbTool("▭", "مستطیل", new View.OnClickListener() {
            @Override public void onClick(View v) { host.insertShape(PptDoc.Shape.TYPE_RECT); }
        }));
        host.ribbon.addView(rbTool("▢", "گرد", new View.OnClickListener() {
            @Override public void onClick(View v) { host.insertShape(PptDoc.Shape.TYPE_ROUND_RECT); }
        }));
        host.ribbon.addView(rbTool("◯", "بیضی", new View.OnClickListener() {
            @Override public void onClick(View v) { host.insertShape(PptDoc.Shape.TYPE_OVAL); }
        }));
        host.ribbon.addView(rbTool("／", "خط", new View.OnClickListener() {
            @Override public void onClick(View v) { host.insertShape(PptDoc.Shape.TYPE_LINE); }
        }));
        host.ribbon.addView(rbTool("✨", "گالری شکل", new View.OnClickListener() {
            @Override public void onClick(View v) { host.shapeGallery(); }
        }));
        host.ribbon.addView(rbTool("▦", "جدول", new View.OnClickListener() {
            @Override public void onClick(View v) { host.textDialogs.tableDialog(); }
        }));
        host.ribbon.addView(rbTool("📊", "نمودار", new View.OnClickListener() {
            @Override public void onClick(View v) { host.chartDialog(null); }
        }));
        host.ribbon.addView(rbTool("🎬", "ویدیو", new View.OnClickListener() {
            @Override public void onClick(View v) { host.pickVideo(); }
        }));
        host.groupHost = null;
    }

    void buildDesignRibbon() {
        host.ribbon.addView(groupStart("طراحی"));
        host.ribbon.addView(rbTool("پوسته", "پوسته‌ها", new View.OnClickListener() {
            @Override public void onClick(View v) { host.themeGallery(); }
        }));
        host.ribbon.addView(rbTool("دلخواه", "پس‌زمینه", new View.OnClickListener() {
            @Override public void onClick(View v) { host.textDialogs.pickBgColor(); }
        }));
        host.ribbon.addView(rbTool("◐", "گرادیان", new View.OnClickListener() {
            @Override public void onClick(View v) { host.textDialogs.pickGradient(); }
        }));
        host.ribbon.addView(rbTool("∅", "سفید", new View.OnClickListener() {
            @Override public void onClick(View v) {
                host.pushUndo();
                host.slide().background = new PptDoc.Fill();
                host.refreshAll();
            }
        }));

        host.ribbon.addView(groupStart("قالب اسلاید"));
        host.ribbon.addView(rbTool("▤", "عنوان+متن", new View.OnClickListener() {
            @Override public void onClick(View v) { host.templateSlide(0); }
        }));
        host.ribbon.addView(rbTool("▥", "دو ستون", new View.OnClickListener() {
            @Override public void onClick(View v) { host.templateSlide(1); }
        }));
        host.ribbon.addView(rbTool("▢", "عنوان‌دار", new View.OnClickListener() {
            @Override public void onClick(View v) { host.templateSlide(2); }
        }));
        host.ribbon.addView(rbTool("□", "خالی", new View.OnClickListener() {
            @Override public void onClick(View v) { host.templateSlide(3); }
        }));

        host.ribbon.addView(groupStart("ارائه"));
        host.ribbon.addView(rbTool("⇄", "انتقال", new View.OnClickListener() {
            @Override public void onClick(View v) { host.textDialogs.transitionDialog(); }
        }));
        host.ribbon.addView(rbTool("▭", "اندازه اسلاید", new View.OnClickListener() {
            @Override public void onClick(View v) { host.textDialogs.slideSizeDialog(); }
        }));
        host.groupHost = null;
    }

    /* contextual ribbon — the shape selected on the canvas: fill, line,
       text styling, arrange, duplicate/delete. PowerPoint mobile opens
       exactly this command set when an object is tapped. */

    void buildFormatRibbon() {
        host.ribbon.addView(groupStart("قالب شکل"));
        host.ribbon.addView(rbTool("◧", "رنگ پرکرد", new View.OnClickListener() {
            @Override public void onClick(View v) { host.textDialogs.pickFillColor(); }
        }));
        host.ribbon.addView(rbTool("▢", "بدون پرکرد", new View.OnClickListener() {
            @Override public void onClick(View v) { host.mutateSel(new Runnable() {
                @Override public void run() { host.sel.fill.type = PptDoc.Fill.NONE; }
            }); }
        }));
        host.ribbon.addView(rbTool("▭", "رنگ خط", new View.OnClickListener() {
            @Override public void onClick(View v) { host.textDialogs.pickLineColor(); }
        }));
        host.ribbon.addView(rbTool("⇔", "ضخامت خط", new View.OnClickListener() {
            @Override public void onClick(View v) { host.textDialogs.lineDialog(); }
        }));
        host.ribbon.addView(rbTool("┅", "خط‌چین", new View.OnClickListener() {
            @Override public void onClick(View v) { host.textDialogs.dashDialog(); }
        }));
        host.groupHost = null;
        host.ribbon.addView(groupStart("متن"));
        host.ribbon.addView(rbTool("✎", "ویرایش متن", new View.OnClickListener() {
            @Override public void onClick(View v) { host.editSelectedText(); }
        }));
        host.ribbon.addView(rbTool("B", "پررنگ", new View.OnClickListener() {
            @Override public void onClick(View v) { host.applyTextStyle(2, host.curBold() ? 0f : 1f); }
        }));
        host.ribbon.addView(rbTool("I", "کج", new View.OnClickListener() {
            @Override public void onClick(View v) { host.applyTextStyle(3, host.curItalic() ? 0f : 1f); }
        }));
        host.ribbon.addView(rbTool("A⁺", "بزرگ‌تر", new View.OnClickListener() {
            @Override public void onClick(View v) { host.applyTextSize(2f); }
        }));
        host.ribbon.addView(rbTool("A⁻", "کوچک‌تر", new View.OnClickListener() {
            @Override public void onClick(View v) { host.applyTextSize(-2f); }
        }));
        host.ribbon.addView(rbTool("A◤", "رنگ متن", new View.OnClickListener() {
            @Override public void onClick(View v) { host.textDialogs.pickTextColor(); }
        }));
        host.ribbon.addView(rbTool("≡", "فونت", new View.OnClickListener() {
            @Override public void onClick(View v) { host.textDialogs.fontDialog(); }
        }));
        host.ribbon.addView(rbTool("⊸", "راست‌چین", new View.OnClickListener() {
            @Override public void onClick(View v) { host.applyAlign(PptDoc.Para.ALIGN_R); }
        }));
        host.ribbon.addView(rbTool("⊕", "وسط‌چین", new View.OnClickListener() {
            @Override public void onClick(View v) { host.applyAlign(PptDoc.Para.ALIGN_CTR); }
        }));
        host.ribbon.addView(rbTool("幺", "چپ‌چین", new View.OnClickListener() {
            @Override public void onClick(View v) { host.applyAlign(PptDoc.Para.ALIGN_L); }
        }));
        host.groupHost = null;
        host.ribbon.addView(groupStart("ترتیب"));
        host.ribbon.addView(rbTool("⬆", "جلوتر", new View.OnClickListener() {
            @Override public void onClick(View v) { host.reorder(1); }
        }));
        host.ribbon.addView(rbTool("⬇", "عقب‌تر", new View.OnClickListener() {
            @Override public void onClick(View v) { host.reorder(-1); }
        }));
        host.ribbon.addView(rbTool("⧉", "تکثیر", new View.OnClickListener() {
            @Override public void onClick(View v) { host.duplicateSel(); }
        }));
        host.ribbon.addView(rbTool("🗑", "حذف", new View.OnClickListener() {
            @Override public void onClick(View v) { host.deleteSel(); }
        }));
        host.groupHost = null;
        host.ribbon.addView(groupStart("جلوه‌ها"));
        host.ribbon.addView(rbTool("✨", "انیمیشن", new View.OnClickListener() {
            @Override public void onClick(View v) { host.textDialogs.animDialog(); }
        }));
        host.ribbon.addView(rbTool("▨", "سایه شکل", new View.OnClickListener() {
            @Override public void onClick(View v) { host.textDialogs.toggleShapeShadow(); }
        }));
        host.ribbon.addView(rbTool("▨آ", "سایه متن", new View.OnClickListener() {
            @Override public void onClick(View v) { host.textDialogs.toggleTextShadow(); }
        }));
        host.ribbon.addView(rbTool("◲", "برش تصویر", new View.OnClickListener() {
            @Override public void onClick(View v) { host.enterCropMode(); }
        }));
        host.groupHost = null;
    }

    /* ============================== slide sorter =========================== */
    /* full-screen grid of numbered slide thumbnails — PowerPoint's Slide
       Sorter. Tap = open; long-press = duplicate / delete / move. */
}
