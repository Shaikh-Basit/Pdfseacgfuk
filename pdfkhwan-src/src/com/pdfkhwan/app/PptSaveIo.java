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
public class PptSaveIo {
    final PptEditorActivity host;
    PptSaveIo(PptEditorActivity h) { this.host = h; }

    int saveFails = 0;            /* autosave failures (retry cap) */

    void startQuietSave() { startQuietSave(false); }

    void startQuietSave(boolean force) {
        if (host.fileUri == null || !host.dirty) return;
        if (!force && host.saveInFlight) return;
        final PptDoc snapshot = host.cloneDoc();
        final String name = host.docName;
        final Uri target = host.fileUri;
        final boolean fileScheme = "file".equals(target.getScheme());
        final int gen = host.editGen;
        host.saveInFlight = true;
        host.saveExec.execute(new Runnable() {
            @Override public void run() {
                Exception err = null;
                try {
                    if (fileScheme) {
                        err = PptEditorActivity.writeFileAtomic(new File(target.getPath()), snapshot, name);
                    } else {
                        /* v1.29 FIX: SAF saves go through the cache-dir staging
                           helper (writeToSaf) — direct in-place streaming
                           corrupted the file on any mid-write failure. */
                        err = host.writeToSaf(target, snapshot, name);
                    }
                } catch (Exception e) {
                    err = e;
                }
                final Exception ferr = err;
                host.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        host.saveInFlight = false;
                        if (ferr == null) {
                            saveFails = 0;
                            if (host.editGen == gen) {
                                host.dirty = false;      /* no edits since the snapshot */
                                host.updateTitle();
                                if (!host.isDestroyed()) flashSaved();
                            } else {
                                host.scheduleAutosave(); /* edits landed mid-save → next cycle */
                            }
                        } else {
                            CrashGuard.append(host,
                                    "ppt autosave: " + CrashGuard.stackToString(ferr));
                            /* keep dirty (• stays) — bounded auto-retry, then
                               the manual ذخیره button remains the fallback */
                            saveFails++;
                            if (saveFails < 3) host.scheduleAutosave();
                        }
                    }
                });
            }
        });
    }

    /* atomic write: temp file in the SAME directory → fsync → rename over
       the target; falls back to a direct write when rename is blocked.
       A crash/EBADF mid-write leaves the ORIGINAL file untouched. */

    void flashSaved() {
        host.titleView.setText((host.docName == null ? "" : host.docName) + "  ✓");
        host.autoHandler.postDelayed(new Runnable() {
            @Override public void run() { host.updateTitle(); }
        }, 1600);
    }

    /* ============================== inline text edit ======================== */
    /* PowerPoint mobile edits text IN PLACE: double-tap a text shape and
       type right on the slide. We overlay an EditText sized/colored like
       the shape; ✓/✕ pills commit or cancel. */
}
