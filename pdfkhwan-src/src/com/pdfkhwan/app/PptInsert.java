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
public class PptInsert {
    final PptEditorActivity host;
    PptInsert(PptEditorActivity h) { this.host = h; }

    byte[] normalizeInsertedImage(byte[] bytes) {
        try {
            String fmt = PptxReader.sniffImgFormat(bytes);
            boolean stdFmt = "png".equals(fmt) || "jpg".equals(fmt) || "gif".equals(fmt)
                    || "bmp".equals(fmt) || "webp".equals(fmt);
            int orient = PptEditorActivity.readExifOrientation(bytes);
            boolean needsRotate = orient >= 3;   /* 3=180, 6=90cw, 8=90ccw, 2..9 mirrored */
            if (stdFmt && !needsRotate) return bytes;
            /* decodeRobust کف ۲۰۴۸ دارد؛ برای بازذخیره، سقف ۴۰۹۶ می‌خواهیم:
               4096×12700/1.5 → پس از min(need,4096) دقیقاً ۴۰۹۶ می‌شود */
            Bitmap src = PptEditorActivity.SlideRenderer.decodeBytes(bytes, 34679466L);
            if (src == null || src.getWidth() < 1 || src.getHeight() < 1) return bytes;
            android.graphics.Matrix m = new android.graphics.Matrix();
            boolean rotated = false;
            switch (orient) {
                case 2: m.setScale(-1, 1); rotated = true; break;
                case 3: m.postRotate(180); rotated = true; break;
                case 4: m.setScale(1, -1); rotated = true; break;
                case 5: m.postRotate(90); m.postScale(-1, 1); rotated = true; break;
                case 6: m.postRotate(90); rotated = true; break;
                case 7: m.postRotate(-90); m.postScale(-1, 1); rotated = true; break;
                case 8: m.postRotate(-90); rotated = true; break;
                default: break;
            }
            Bitmap out = src;
            if (rotated) {
                try {
                    out = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
                } catch (Throwable t) {
                    out = src;
                }
            }
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            out.compress(Bitmap.CompressFormat.PNG, 100, bo);
            byte[] png = bo.toByteArray();
            /* اگر نتیجه عجیب بزرگ شد و منبع استاندارد بود، همان اصلی را نگه دار */
            if (stdFmt && png.length > bytes.length * 4L + 2L * 1024L * 1024L) return bytes;
            return png;
        } catch (Throwable t) {
            return bytes;
        }
    }

    /* v1.28: جهت EXIF (1..8) از APP1 جی‌پگ — 0 یعنی بدون EXIF */

    PptDoc.Slide templateSlideDoc(int kind) {
        PptDoc.Slide s = new PptDoc.Slide();
        s.background = PptDoc.Fill.gradient(host.doc.themeBgTop, host.doc.themeBgBottom, false);
        if (kind == 0) {
            s.shapes.add(new PptDoc.TextShape("عنوان",
                    host.doc.slideW * 10 / 100, host.doc.slideH * 8 / 100,
                    host.doc.slideW * 80 / 100, host.doc.slideH * 14 / 100, 34f, 0xFF1F3864, true, true));
            s.shapes.add(new PptDoc.TextShape("متن…",
                    host.doc.slideW * 10 / 100, host.doc.slideH * 30 / 100,
                    host.doc.slideW * 80 / 100, host.doc.slideH * 55 / 100, 20f, 0xFF404040, false, true));
        } else if (kind == 1) {
            s.shapes.add(new PptDoc.TextShape("عنوان",
                    host.doc.slideW * 10 / 100, host.doc.slideH * 8 / 100,
                    host.doc.slideW * 80 / 100, host.doc.slideH * 12 / 100, 30f, 0xFF1F3864, true, true));
            s.shapes.add(new PptDoc.TextShape("ستون راست",
                    host.doc.slideW * 52 / 100, host.doc.slideH * 26 / 100,
                    host.doc.slideW * 38 / 100, host.doc.slideH * 58 / 100, 18f, 0xFF404040, false, true));
            s.shapes.add(new PptDoc.TextShape("ستون چپ",
                    host.doc.slideW * 10 / 100, host.doc.slideH * 26 / 100,
                    host.doc.slideW * 38 / 100, host.doc.slideH * 58 / 100, 18f, 0xFF404040, false, true));
        } else if (kind == 2) {
            s.shapes.add(new PptDoc.TextShape("عنوان بزرگ",
                    host.doc.slideW * 8 / 100, host.doc.slideH * 38 / 100,
                    host.doc.slideW * 84 / 100, host.doc.slideH * 24 / 100, 44f, 0xFF1F3864, true, true));
        }
        return s;
    }

    /* ============================== galleries ============================== */
}
