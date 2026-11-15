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
public class PptShow {
    final PptEditorActivity host;
    PptShow(PptEditorActivity h) { this.host = h; }

    void addShowCloseBtn() {
        TextView close = new TextView(host);
        close.setText("✕");
        close.setTextColor(0xFFFFFFFF);
        close.setTextSize(18f);
        close.setGravity(Gravity.CENTER);
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setCornerRadius(host.dp(22));
        gd.setColor(0x66000000);
        close.setBackground(gd);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                host.closeSlideshow();
            }
        });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(host.dp(44), host.dp(44));
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.leftMargin = host.dp(12);
        lp.topMargin = host.dp(18);
        host.slideshowLayer.addView(close, lp);

        /* ✏ pen toggle — presenter ink (draw over the slide, never saved) */
        TextView penBtn = new TextView(host);
        penBtn.setText("✏");
        penBtn.setTextColor(0xFFFFFFFF);
        penBtn.setTextSize(17f);
        penBtn.setGravity(Gravity.CENTER);
        GradientDrawable pg = new GradientDrawable();
        pg.setShape(GradientDrawable.RECTANGLE);
        pg.setCornerRadius(host.dp(22));
        pg.setColor(0x66000000);
        penBtn.setBackground(pg);
        penBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (host.showView != null) host.showView.togglePen();
            }
        });
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(host.dp(44), host.dp(44));
        plp.gravity = Gravity.TOP | Gravity.LEFT;
        plp.leftMargin = host.dp(60);
        plp.topMargin = host.dp(18);
        host.slideshowLayer.addView(penBtn, plp);

        /* 📝 presenter-notes toggle (v1.21) — shows the slide's notes strip */
        TextView notesBtn = new TextView(host);
        notesBtn.setText("📝");
        notesBtn.setTextColor(0xFFFFFFFF);
        notesBtn.setTextSize(15f);
        notesBtn.setGravity(Gravity.CENTER);
        GradientDrawable ng = new GradientDrawable();
        ng.setShape(GradientDrawable.RECTANGLE);
        ng.setCornerRadius(host.dp(22));
        ng.setColor(0x66000000);
        notesBtn.setBackground(ng);
        notesBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (host.showView != null) host.showView.toggleNotes();
            }
        });
        FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(host.dp(44), host.dp(44));
        nlp.gravity = Gravity.TOP | Gravity.LEFT;
        nlp.leftMargin = host.dp(108);
        nlp.topMargin = host.dp(18);
        host.slideshowLayer.addView(notesBtn, nlp);

        /* v1.23: امکانات ارائهٔ واقعی پاورپوینت — نمای اسلایدها، اندازه،
           لیزر، پخش خودکار و صفحهٔ سیاه/سفید */
        makeShowBtn("▦", "نمای اسلایدها", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                host.showSlideGrid();
            }
        }, host.dp(156));
        makeShowBtn("⛶", "اندازه", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (host.showView != null) host.showView.cycleScaleMode();
            }
        }, host.dp(204));
        makeShowBtn("🔴", "لیزر", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (host.showView != null) host.showView.toggleLaser();
            }
        }, host.dp(252));
        makeShowBtn("⏵", "پخش خودکار", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (host.showView != null) host.showView.cycleAuto();
            }
        }, host.dp(300));
        makeShowBtn("⬛", "صفحهٔ سیاه", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (host.showView != null) host.showView.cycleBlank();
            }
        }, host.dp(348));
    }

    /* v1.23: دکمهٔ گرد شیشه‌ای نوار ارائه (هم‌سبک ✕/✏/📝 — ۴۴dp، بالا-چپ) */

    void makeShowBtn(String glyph, String hint, View.OnClickListener l, int leftMargin) {
        TextView b = new TextView(host);
        b.setText(glyph);
        b.setTextColor(0xFFFFFFFF);
        b.setTextSize(16f);
        b.setGravity(Gravity.CENTER);
        b.setContentDescription(hint);
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setCornerRadius(host.dp(22));
        gd.setColor(0x66000000);
        b.setBackground(gd);
        b.setOnClickListener(l);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(host.dp(44), host.dp(44));
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.leftMargin = leftMargin;
        lp.topMargin = host.dp(18);
        host.slideshowLayer.addView(b, lp);
    }

    void hideSystemUi() {
        host.getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    void showSystemUi() {
        host.getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
    }

    /* ============================== saving ================================= */
}
