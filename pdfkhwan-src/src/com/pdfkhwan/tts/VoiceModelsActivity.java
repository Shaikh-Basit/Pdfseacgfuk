package com.pdfkhwan.tts;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.pdfkhwan.app.CrashGuard;
import com.pdfkhwan.app.Fa;
import com.pdfkhwan.app.Modern;
import com.pdfkhwan.app.R;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/* ====================================================================
   v1.47 — بخش ۲۰ از نقشهٔ راه: صفحهٔ «مدل‌های صوتی».

   سه بخش:
     ۱) فارسی — پیپر (امیر، پیش‌فرض)
     ۲) انگلیسی — پیپر (رایان / لساک / آلن)
     ۳) موتور سیستم گوشی (بدون دانلود)
   + درون‌ریزی از فایل (SAF) با راهنمایی دقیق فایل‌های همراه.

   دانلود: پیشرفت لحظه‌ای، ادامه از نقطهٔ قطع، تأیید SHA-256،
   و بعد از پایان، آزمون واقعی تولید صدا پیش از «آماده» شدن.
   ==================================================================== */
public class VoiceModelsActivity extends Activity {

    private static final int REQ_PICK_MODEL = 41;
    private static final int REQ_PICK_ESPEAK = 42;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout listWrap;
    private final Map<String, View> cardById = new HashMap<>();
    private final Map<String, String> nameOverride = new HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Modern.C_BG);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Modern.dp(this, 14);
        root.setPadding(pad, pad, pad, Modern.dp(this, 24));
        scroll.addView(root, new ViewGroup.LayoutParams(-1, -2));
        setContentView(scroll);

        /* ---------- header ---------- */
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("مدل‌های صوتی");
        title.setTextColor(Modern.C_TEXT);
        title.setTextSize(19f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1f);
        head.addView(title, tl);
        head.addView(Modern.closeBtn(this, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        }));
        root.addView(head);

        TextView sub = new TextView(this);
        sub.setText("موتور Piper کاملاً آفلاین کار می‌کند — اینترنت فقط برای دانلود خودِ مدل لازم است. حجم‌ها را قبل از دانلود ببین.");
        sub.setTextColor(Modern.C_MUTED);
        sub.setTextSize(11.5f);
        sub.setPadding(0, Modern.dp(this, 6), 0, 0);
        root.addView(sub);

        root.addView(Modern.divider(this));

        listWrap = new LinearLayout(this);
        listWrap.setOrientation(LinearLayout.VERTICAL);
        root.addView(listWrap, new LinearLayout.LayoutParams(-1, -2));

        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    /* ============================ رندر ============================ */

    private void render() {
        listWrap.removeAllViews();
        cardById.clear();

        List<VoiceModels.Rec> recs = VoiceModels.list(this);

        /* بخش فارسی پیپر */
        listWrap.addView(Modern.sectionLabel(this, "فارسی — موتور Piper (کیفیت طبیعی)"));
        boolean anyFa = false;
        for (VoiceModels.Rec r : recs) {
            if (r.lang.equals("fa") && !r.imported) {
                anyFa = true;
                listWrap.addView(voiceCard(r));
            }
        }
        if (!anyFa) listWrap.addView(hintRow("صدای فارسی پیدا نشد"));

        /* بخش انگلیسی پیپر */
        listWrap.addView(Modern.sectionLabel(this, "انگلیسی — موتور Piper"));
        for (VoiceModels.Rec r : recs) {
            if (r.lang.equals("en") && !r.imported) listWrap.addView(voiceCard(r));
        }

        /* درون‌ریزی‌شده‌ها */
        boolean anyImported = false;
        for (VoiceModels.Rec r : recs) {
            if (r.imported) {
                if (!anyImported) {
                    listWrap.addView(Modern.sectionLabel(this, "درون‌ریزی‌شده (SAF)"));
                    anyImported = true;
                }
                listWrap.addView(voiceCard(r));
            }
        }

        /* موتور سیستم */
        listWrap.addView(Modern.sectionLabel(this, "موتور سیستم گوشی"));
        listWrap.addView(systemCard());

        /* درون‌ریزی */
        listWrap.addView(Modern.sectionLabel(this, "درون‌ریزی مدل از فایل"));
        listWrap.addView(importCard());

        /* یادداشت مسیر */
        TextView path = new TextView(this);
        path.setText("محل ذخیره: " + VoiceModels.root(this).getAbsolutePath()
                + "\nفایل‌های espeak-ng-data بین همهٔ صداها مشترک است و فقط یک‌بار دانلود می‌شود.");
        path.setTextColor(Modern.C_MUTED);
        path.setTextSize(10.5f);
        path.setPadding(0, Modern.dp(this, 14), 0, 0);
        listWrap.addView(path);
    }

    private View hintRow(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Modern.C_MUTED);
        t.setTextSize(12f);
        t.setPadding(Modern.dp(this, 4), Modern.dp(this, 6), Modern.dp(this, 4), Modern.dp(this, 6));
        return t;
    }

    private String displayName(VoiceModels.Rec r) {
        String o = nameOverride.get(r.id);
        if (o != null && o.length() > 0) return o;
        return r.name + " — " + r.region;
    }

    private View voiceCard(final VoiceModels.Rec r) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int p = Modern.dp(this, 12);
        card.setPadding(p, p, p, p);
        card.setBackground(Modern.roundStroke(Modern.C_CARD, 14, Modern.C_BORDER, 1, this));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.topMargin = Modern.dp(this, 8);
        card.setLayoutParams(clp);
        cardById.put(r.id, card);

        /* ردیف ۱: نام + ستاره */
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = new TextView(this);
        name.setText(displayName(r));
        name.setTextColor(r.starred ? Modern.C_ACCENT : Modern.C_TEXT);
        name.setTextSize(14.5f);
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        row1.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        if (r.starred) {
            TextView star = new TextView(this);
            star.setText("پیش‌فرض ★");
            star.setTextColor(Modern.C_ACCENT);
            star.setTextSize(10.5f);
            star.setBackground(Modern.pill(Modern.C_ACCENT_DIM, this));
            star.setPadding(Modern.dp(this, 10), Modern.dp(this, 4), Modern.dp(this, 10), Modern.dp(this, 4));
            row1.addView(star);
        }
        card.addView(row1);

        /* ردیف ۲: مشخصات */
        StringBuilder meta = new StringBuilder();
        if (r.quality != null && r.quality.length() > 0) meta.append("کیفیت ").append(r.quality);
        if (r.imported) {
            meta.append(" • درون‌ریزی‌شده");
        } else {
            meta.append(" • حجم دانلود: ").append(Fa.bytes(r.totalBytes));
        }
        TextView spec = new TextView(this);
        spec.setText(meta.toString());
        spec.setTextColor(Modern.C_MUTED);
        spec.setTextSize(11f);
        spec.setPadding(0, Modern.dp(this, 3), 0, 0);
        card.addView(spec);

        /* ردیف ۳: وضعیت */
        TextView status = new TextView(this);
        status.setTextSize(11.5f);
        status.setPadding(0, Modern.dp(this, 8), 0, 0);
        card.addView(status);
        card.setTag(R.id.btnMenu, status);   /* برای به‌روزرسانی */

        final ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setVisibility(View.GONE);
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(Modern.C_ACCENT));
        card.addView(bar, new LinearLayout.LayoutParams(-1, Modern.dp(this, 6)));
        card.setTag(R.id.btnSearch, bar);

        updateCardState(r, status, bar);

        /* ردیف ۴: دکمه‌ها */
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(0, Modern.dp(this, 10), 0, 0);
        card.addView(actions);

        if (r.state == VoiceModels.S_READY) {
            actions.addView(Modern.tonalBtn(this, "پیش‌نمایش", Modern.C_ACCENT, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    previewVoice(r);
                }
            }));
            View sp = new View(this);
            actions.addView(sp, new LinearLayout.LayoutParams(Modern.dp(this, 8), 1));
            actions.addView(Modern.tonalBtn(this, r.starred ? "بی‌ستاره" : "پیش‌فرض کن", Modern.C_MUTED, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    VoiceModels.setActiveVoice(VoiceModelsActivity.this, r.lang, r.starred ? "" : r.id);
                    VoiceModels.invalidateCaches();
                    render();
                }
            }));
        } else if (r.state == VoiceModels.S_DOWNLOADING) {
            actions.addView(Modern.tonalBtn(this, "توقف", Modern.C_DANGER, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    VoiceModels.cancelDownload(r.id);
                }
            }));
        } else {
            String label;
            if (r.imported || VoiceModels.catalogVoice(r.id) == null) {
                /* صدای درون‌ریزی‌شده — فقط داده‌های زبانی مشترک کم است (~۱ مگ) */
                label = "تکمیل داده‌های زبانی (~۱ مگابایت)";
            } else {
                label = r.state == VoiceModels.S_BROKEN ? "ادامهٔ دانلود" : "دانلود";
            }
            actions.addView(Modern.filledBtn(this, label,
                    Modern.C_ACCENT, 0xFF06251F, new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            startDownload(r);
                        }
                    }));
        }

        View spacer = new View(this);
        actions.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        actions.addView(Modern.iconBtn(this, R.drawable.ic_delete, Modern.C_MUTED, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmDelete(r);
            }
        }));
        actions.addView(Modern.iconBtn(this, R.drawable.ic_m_info, Modern.C_MUTED, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showInfo(r);
            }
        }));
        actions.addView(Modern.iconBtn(this, R.drawable.ic_ann_export, Modern.C_MUTED, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                renameVoice(r);
            }
        }));

        /* اتصال به رویدادهای دانلود */
        if (r.state == VoiceModels.S_DOWNLOADING || r.state == VoiceModels.S_BROKEN) {
            watchDownload(r);
        }
        return card;
    }

    private void updateCardState(VoiceModels.Rec r, TextView status, ProgressBar bar) {
        if (r.state == VoiceModels.S_READY) {
            status.setTextColor(Modern.C_ACCENT);
            status.setText("نصب و تأییدشده ✓ — آمادهٔ خواندن");
            bar.setVisibility(View.GONE);
        } else if (r.state == VoiceModels.S_DOWNLOADING) {
            status.setTextColor(Modern.C_TEXT);
            status.setText("در حال دانلود… " + Fa.bytes(r.doneBytes) + " از " + Fa.bytes(r.totalBytes));
            bar.setVisibility(View.VISIBLE);
            bar.setMax(10000);
            bar.setProgress((int) (r.doneBytes * 10000L / Math.max(1, r.totalBytes)));
        } else if (r.state == VoiceModels.S_BROKEN) {
            status.setTextColor(Modern.C_DANGER);
            String problem = VoiceModels.validateFiles(this, r.id);
            if (r.imported && problem != null && problem.contains("espeak")) {
                status.setText("مدل لود شد ✓ — فقط داده‌های زبانی کم دارد؛ دکمهٔ «تکمیل داده‌های زبانی» را بزن (حدود ۱ مگابایت)");
            } else {
                status.setText(problem != null ? problem : "ناقص است — ادامهٔ دانلود لازم است");
            }
            bar.setVisibility(View.GONE);
        } else {
            status.setTextColor(Modern.C_MUTED);
            status.setText("نصب نشده");
            bar.setVisibility(View.GONE);
        }
    }

    private void refreshCard(String voiceId) {
        View card = cardById.get(voiceId);
        if (card == null) return;
        Object tag = card.getTag(R.id.btnMenu);
        Object tagBar = card.getTag(R.id.btnSearch);
        if (!(tag instanceof TextView) || !(tagBar instanceof ProgressBar)) return;
        VoiceModels.Rec rec = null;
        for (VoiceModels.Rec r : VoiceModels.list(this)) {
            if (r.id.equals(voiceId)) rec = r;
        }
        if (rec == null) return;
        updateCardState(rec, (TextView) tag, (ProgressBar) tagBar);
    }

    private void watchDownload(final VoiceModels.Rec r) {
        VoiceModels.observe(r.id, dlListener(r));
    }

    private VoiceModels.Progress dlListener(final VoiceModels.Rec r) {
        return new VoiceModels.Progress() {
            @Override
            public void onProgress(final int progress, final long done, final long total) {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        View card = cardById.get(r.id);
                        if (card == null) return;
                        Object tag = card.getTag(R.id.btnMenu);
                        Object tagBar = card.getTag(R.id.btnSearch);
                        if (tag instanceof TextView && tagBar instanceof ProgressBar) {
                            ((TextView) tag).setText("در حال دانلود… " + Fa.bytes(done) + " از " + Fa.bytes(total));
                            ProgressBar b = (ProgressBar) tagBar;
                            b.setVisibility(View.VISIBLE);
                            b.setMax(10000);
                            b.setProgress(progress);
                        }
                    }
                });
            }

            @Override
            public void onDone(final boolean ok, final String message) {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (ok) {
                            runValidationThenRefresh(r.id);
                        } else {
                            toast(message);
                            render();
                        }
                    }
                });
            }
        };
    }

    private void startDownload(VoiceModels.Rec r) {
        /* اول ثبت دانلود (صف + شنونده)، بعد رندر — کارت همان لحظه حالت دانلود بگیرد */
        if (r.imported || VoiceModels.catalogVoice(r.id) == null) {
            startEspeakComplete(r);
            return;
        }
        VoiceModels.download(this, r.id, dlListener(r));
        render();
    }

    /* v1.48: تکمیل خودکار داده‌های زبانی espeak — خودِ مدل دانلود نمی‌شود؛
       فقط مجموعهٔ مشترکِ کم‌حجم موتور. */
    private void startEspeakComplete(final VoiceModels.Rec r) {
        startEspeakComplete(r.id);
    }

    private void startEspeakComplete(final String voiceId) {
        VoiceModels.completeEspeakFor(this, voiceId, new VoiceModels.Progress() {
            @Override
            public void onProgress(final int progress, final long done, final long total) {
                ui.post(new Runnable() {
                    @Override public void run() {
                        View card = cardById.get(voiceId);
                        if (card == null) return;
                        Object tag = card.getTag(R.id.btnMenu);
                        Object tagBar = card.getTag(R.id.btnSearch);
                        if (tag instanceof TextView && tagBar instanceof ProgressBar) {
                            ((TextView) tag).setText("دانلود داده‌های زبانی… " + Fa.bytes(done) + " از " + Fa.bytes(total));
                            ProgressBar b = (ProgressBar) tagBar;
                            b.setVisibility(View.VISIBLE);
                            b.setMax(10000);
                            b.setProgress(progress);
                        }
                    }
                });
            }

            @Override
            public void onDone(final boolean ok, final String message) {
                ui.post(new Runnable() {
                    @Override public void run() {
                        VoiceModels.invalidateCaches();
                        if (ok) {
                            runValidationThenRefresh(voiceId);
                        } else {
                            toast(message);
                            render();
                        }
                    }
                });
            }
        });
        render();
    }

    /* بعد از دانلود: آزمون واقعی تولید صدا پیش از علامت‌گذاری آماده */
    private void runValidationThenRefresh(final String voiceId) {
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("بررسی نهایی مدل (تولید یک جملهٔ آزمایشی)…");
        pd.setCancelable(false);
        try {
            pd.show();
        } catch (Throwable ignored) {
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String err = PiperEngine.testVoice(VoiceModelsActivity.this, voiceId);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            pd.dismiss();
                        } catch (Throwable ignored) {
                        }
                        VoiceModels.invalidateCaches();
                        if (err == null) {
                            toast("مدل صوتی آماده شد ✓");
                        } else {
                            new AlertDialog.Builder(VoiceModelsActivity.this)
                                    .setTitle("مدل باز نشد")
                                    .setMessage(err)
                                    .setPositiveButton("باشه", null)
                                    .show();
                        }
                        render();
                    }
                });
            }
        }, "validate-voice").start();
    }

    private void previewVoice(VoiceModels.Rec r) {
        String text = r.sampleText != null && r.sampleText.length() > 0
                ? r.sampleText : "این یک جملهٔ آزمایشی است.";
        PiperEngine.get(this).speak(r.id, r.lang, text, 1.0f, new TtsEngine.Listener() {
            @Override
            public void onStart() {
            }

            @Override
            public void onDone(boolean completed) {
            }

            @Override
            public void onError(String message) {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        toast("پخش پیش‌نمایش ممکن نشد");
                    }
                });
            }
        });
        toast("پخش پیش‌نمایش…");
    }

    private void confirmDelete(final VoiceModels.Rec r) {
        String msg = r.state == VoiceModels.S_READY
                ? "مدل «" + displayName(r) + "» و فایل‌هایش از حافظهٔ برنامه پاک شود؟"
                : "دانلود نیمه‌کارهٔ «" + displayName(r) + "» پاک شود؟";
        new AlertDialog.Builder(this)
                .setTitle("حذف مدل")
                .setMessage(msg + (r.imported ? "\n(فایل اصلی خودت دست‌نخورده می‌ماند)" : ""))
                .setPositiveButton("حذف", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        PiperEngine.get(VoiceModelsActivity.this).stop();
                        VoiceModels.delete(VoiceModelsActivity.this, r.id);
                        render();
                    }
                })
                .setNegativeButton("بی‌خیال", null)
                .show();
    }

    private void showInfo(VoiceModels.Rec r) {
        StringBuilder sb = new StringBuilder();
        sb.append("شناسه: ").append(r.id).append('\n');
        sb.append("مسیر: ").append(VoiceModels.voiceDir(this, r.id).getAbsolutePath()).append('\n');
        File model = null, json = null, tokens = null;
        File[] fs = VoiceModels.voiceDir(this, r.id).listFiles();
        if (fs != null) {
            for (File f : fs) {
                String n = f.getName().toLowerCase();
                if (n.endsWith(".onnx") && !n.endsWith(".onnx.json")) model = f;
                else if (n.endsWith(".onnx.json")) json = f;
                else if (n.equals("tokens.txt")) tokens = f;
            }
        }
        if (model != null) sb.append("مدل: ").append(Fa.bytes(model.length())).append('\n');
        if (json != null) sb.append("تنظیمات: ").append(json.getName()).append('\n');
        if (tokens != null) sb.append("tokens: ").append(Fa.bytes(tokens.length())).append('\n');
        sb.append("espeak-ng-data: ").append(VoiceModels.espeakDir(this).getAbsolutePath());
        new AlertDialog.Builder(this)
                .setTitle(displayName(r))
                .setMessage(sb.toString())
                .setPositiveButton("باشه", null)
                .show();
    }

    private void renameVoice(final VoiceModels.Rec r) {
        final EditText input = new EditText(this);
        input.setText(displayName(r));
        input.setTextColor(Modern.C_TEXT);
        int pad = Modern.dp(this, 16);
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(pad, pad / 2, pad, 0);
        wrap.addView(input);
        new AlertDialog.Builder(this)
                .setTitle("تغییر نام نمایشی")
                .setView(wrap)
                .setPositiveButton("ذخیره", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        String n = input.getText().toString().trim();
                        if (n.length() > 0) {
                            nameOverride.put(r.id, n);
                            getSharedPreferences("voices", 0).edit()
                                    .putString("name_" + r.id, n).apply();
                        }
                        render();
                    }
                })
                .setNegativeButton("بی‌خیال", null)
                .show();
    }

    /* ============================ موتور سیستم ============================ */

    private View systemCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int p = Modern.dp(this, 12);
        card.setPadding(p, p, p, p);
        card.setBackground(Modern.roundStroke(Modern.C_CARD, 14, Modern.C_BORDER, 1, this));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.topMargin = Modern.dp(this, 8);
        card.setLayoutParams(clp);

        TextView t = new TextView(this);
        t.setText("موتور TTS گوشی — بدون هیچ دانلودی (حجم صفر)");
        t.setTextColor(Modern.C_TEXT);
        t.setTextSize(13.5f);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        card.addView(t);

        final TextView status = new TextView(this);
        status.setTextColor(Modern.C_MUTED);
        status.setTextSize(11.5f);
        status.setPadding(0, Modern.dp(this, 6), 0, 0);
        status.setText("در حال بررسی صداهای سیستم…");
        card.addView(status);

        SystemTtsEngine.get(this).ensure(new Runnable() {
            @Override
            public void run() {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        boolean fa = SystemTtsEngine.get(VoiceModelsActivity.this).hasLanguage("fa");
                        boolean en = SystemTtsEngine.get(VoiceModelsActivity.this).hasLanguage("en");
                        status.setText("صدای فارسی: " + (fa ? "موجود ✓" : "نصب نیست")
                                + "\nصدای انگلیسی: " + (en ? "موجود ✓" : "نصب نیست")
                                + "\n(برای انگلیسی انتخاب پیش‌فرض همین است)");
                    }
                });
            }
        });

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, Modern.dp(this, 10), 0, 0);
        card.addView(actions);
        actions.addView(Modern.tonalBtn(this, "تنظیمات صدای سیستم", Modern.C_MUTED, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent("com.android.settings.TTS_SETTINGS"));
                } catch (Throwable t) {
                    toast("تنظیمات TTS این گوشی باز نشد");
                }
            }
        }));
        return card;
    }

    /* ============================ درون‌ریزی ============================ */

    private View importCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int p = Modern.dp(this, 12);
        card.setPadding(p, p, p, p);
        card.setBackground(Modern.roundStroke(Modern.C_CARD, 14, Modern.C_BORDER, 1, this));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.topMargin = Modern.dp(this, 8);
        card.setLayoutParams(clp);

        TextView t = new TextView(this);
        t.setText("درون‌ریزی مدل Piper (فایل‌های .onnx)");
        t.setTextColor(Modern.C_TEXT);
        t.setTextSize(13.5f);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        card.addView(t);

        TextView h = new TextView(this);
        h.setText("همین سه فایلی که داری کافی است:\n۱) model.onnx (خود مدل)\n۲) model.onnx.json (تنظیمات)\n۳) tokens.txt\n\nهر سه را با هم انتخاب کن — لود می‌شوند، دانلود نه. فقط داده‌های زبانیِ کم‌حجم موتور (espeak، حدود ۱ مگابایت) اگر روی گوشی نباشد خودکار دانلود می‌شود.");
        h.setTextColor(Modern.C_MUTED);
        h.setTextSize(11f);
        h.setPadding(0, Modern.dp(this, 6), 0, 0);
        card.addView(h);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, Modern.dp(this, 10), 0, 0);
        card.addView(actions);
        actions.addView(Modern.filledBtn(this, "انتخاب فایل‌ها…", Modern.C_ACCENT, 0xFF06251F, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("*/*");
                    i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    startActivityForResult(i, REQ_PICK_MODEL);
                } catch (Throwable t) {
                    toast("انتخاب‌گر فایل باز نشد");
                }
            }
        }));
        return card;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode == REQ_PICK_MODEL) {
            handleModelPick(data);
        } else if (requestCode == REQ_PICK_ESPEAK) {
            handleEspeakPick(data);
        }
    }

    private Uri pendingModelOnnx, pendingModelJson, pendingModelTokens;

    private void handleModelPick(Intent data) {
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            ClipData cd = data.getClipData();
            for (int i = 0; i < cd.getItemCount(); i++) {
                Uri u = cd.getItemAt(i).getUri();
                if (u != null) uris.add(u);
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        pendingModelOnnx = pendingModelJson = pendingModelTokens = null;
        List<String> missing = new ArrayList<>();
        for (Uri u : uris) {
            String n = displayNameOf(u);
            String ln = n.toLowerCase();
            if (ln.endsWith(".onnx.json")) pendingModelJson = u;
            else if (ln.endsWith(".onnx")) pendingModelOnnx = u;
            else if (ln.equals("tokens.txt")) pendingModelTokens = u;
        }
        if (pendingModelOnnx == null) missing.add("فایل مدل .onnx");
        if (pendingModelJson == null) missing.add("فایل تنظیمات .onnx.json");
        if (pendingModelTokens == null) missing.add("tokens.txt");
        if (!missing.isEmpty()) {
            toast("این فایل(ها) انتخاب نشد: " + joinFa(missing) + " — هر سه را با هم انتخاب کن");
            return;
        }
        /* v1.48: همین سه فایل کافی است — پوشهٔ espeak-ng-data دیگر اجباری
           نیست؛ اگر کم باشد، خودمان تکمیلش می‌کنیم (دانلود ~۱ مگ یا SAF). */
        runImport(null);
    }

    private static String joinFa(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) sb.append("، ");
            sb.append(p);
        }
        return sb.toString();
    }

    private void handleEspeakPick(Intent data) {
        final Uri tree = data.getData();
        if (tree == null) return;
        try {
            getContentResolver().takePersistableUriPermission(tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Throwable ignored) {
        }
        runImport(tree);
    }

    private void runImport(final Uri tree) {
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage(tree == null ? "در حال کپی فایل‌های مدل…" : "در حال کپی داده‌های زبانی…");
        pd.setCancelable(false);
        try {
            pd.show();
        } catch (Throwable ignored) {
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String[] result = new String[1];
                try {
                    result[0] = doImport(tree);
                } catch (final Throwable t) {
                    CrashGuard.append(VoiceModelsActivity.this, "voice import: " + CrashGuard.stackToString(t));
                    result[0] = "درون‌ریزی شکست خورد: " + t.getMessage();
                }
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            pd.dismiss();
                        } catch (Throwable ignored) {
                        }
                        VoiceModels.invalidateCaches();
                        if (result[0] == null) {
                            toast("مدل درون‌ریزی شد ✓");
                            runValidationThenRefresh(pendingVoiceId);
                        } else if ("NEED_ESPEAK".equals(result[0])) {
                            askEspeakCompletion();
                        } else {
                            new AlertDialog.Builder(VoiceModelsActivity.this)
                                    .setTitle("درون‌ریزی کامل نشد")
                                    .setMessage(result[0])
                                    .setPositiveButton("باشه", null)
                                    .show();
                            render();
                        }
                    }
                });
            }
        }, "voice-import").start();
    }

    /* داده‌های زبانی espeak کم است — سه راه: دانلود خودکار (پیشنهادی)،
       انتخاب پوشه از حافظه، یا بعداً از روی کارت. */
    private void askEspeakCompletion() {
        final String vid = pendingVoiceId;
        new AlertDialog.Builder(this)
                .setTitle("داده‌های زبانی موتور")
                .setMessage("سه فایل مدل لود شد ✓\nموتور گفتار به داده‌های زبانیِ espeak (حدود ۱ مگابایت، یک‌بار برای همهٔ صداها) هم نیاز دارد.\n\nهمین حالا دانلود شود؟")
                .setPositiveButton("دانلود (~۱ مگابایت)", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        if (vid != null) startEspeakComplete(vid);
                        else render();
                    }
                })
                .setNeutralButton("انتخاب پوشه از حافظه", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        try {
                            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                            startActivityForResult(i, REQ_PICK_ESPEAK);
                        } catch (Throwable t) {
                            toast("انتخاب‌گر پوشه باز نشد");
                        }
                    }
                })
                .setNegativeButton("فعلاً نه", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        render();
                    }
                })
                .setOnCancelListener(new android.content.DialogInterface.OnCancelListener() {
                    @Override public void onCancel(android.content.DialogInterface d) { render(); }
                })
                .show();
    }

    private String pendingVoiceId;

    /** @return null = موفق؛ "NEED_ESPEAK" = فقط داده‌های زبانی کم است؛ وگرنه پیام مشکل */
    private String doImport(Uri tree) throws Exception {
        File dir;
        String voiceId;
        String espeakVoice;
        if (pendingModelOnnx != null && pendingModelJson != null && pendingModelTokens != null) {
            /* مرحلهٔ ۱: کپی سه فایل اصلی (v1.48: همین سه فایل کافی است) */
            String onnxName = displayNameOf(pendingModelOnnx);
            if (onnxName == null || !onnxName.toLowerCase().endsWith(".onnx")) {
                onnxName = "imported.onnx";
            }
            voiceId = onnxName.substring(0, onnxName.length() - ".onnx".length());
            voiceId = voiceId.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
            if (voiceId.length() == 0) voiceId = "imported";
            pendingVoiceId = voiceId;

            dir = VoiceModels.voiceDir(this, voiceId);
            if (!dir.exists()) dir.mkdirs();

            String jsonErr = copyUriChecked(pendingModelOnnx, new File(dir, onnxName), 1024 * 1024L);
            if (jsonErr != null) return "کپی فایل مدل نشد: " + jsonErr;
            jsonErr = copyUriChecked(pendingModelJson, new File(dir, onnxName + ".json"), 64);
            if (jsonErr != null) return "کپی فایل تنظیمات نشد: " + jsonErr;
            jsonErr = copyUriChecked(pendingModelTokens, new File(dir, "tokens.txt"), 100);
            if (jsonErr != null) return "کپی tokens.txt نشد: " + jsonErr;

            /* سه فایل مصرف شد — ورود دوباره فقط داده‌های زبانی را تکمیل می‌کند */
            pendingModelOnnx = pendingModelJson = pendingModelTokens = null;

            /* espeak voice از داخل json */
            File jsonFile = new File(dir, onnxName + ".json");
            org.json.JSONObject cfg;
            try {
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                InputStream in = getContentResolver().openInputStream(Uri.fromFile(jsonFile));
                if (in == null) return "فایل تنظیمات خوانده نشد";
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) bo.write(b, 0, n);
                in.close();
                cfg = new org.json.JSONObject(new String(bo.toString("UTF-8")));
            } catch (Throwable t) {
                return "فایل تنظیمات (.onnx.json) معتبر نیست";
            }
            espeakVoice = cfg.optJSONObject("espeak") != null
                    ? cfg.optJSONObject("espeak").optString("voice", "") : "";
            if (espeakVoice.length() == 0) {
                return "در فایل تنظیمات، espeak.voice مشخص نشده — این مدل Piper نیست";
            }
        } else {
            /* مرحلهٔ ۲: فایل‌ها قبلاً کپی شده‌اند — فقط داده‌های زبانی */
            voiceId = pendingVoiceId;
            if (voiceId == null) return "فایل‌های مدل کامل انتخاب نشده‌اند";
            dir = VoiceModels.voiceDir(this, voiceId);
            espeakVoice = VoiceModels.espeakVoiceOf(this, voiceId);
            if (espeakVoice == null || espeakVoice.length() == 0) {
                return "در فایل تنظیمات، espeak.voice مشخص نشده — این مدل Piper نیست";
            }
        }

        /* داده‌های زبانی کامل‌اند؟ */
        if (VoiceModels.espeakMissingFor(this, voiceId).isEmpty()) {
            VoiceModels.addImportedId(this, voiceId);
            return null;
        }
        if (tree == null) {
            /* بدون پوشهٔ SAF — دانلود خودکار پیشنهاد می‌شود */
            VoiceModels.addImportedId(this, voiceId);
            return "NEED_ESPEAK";
        }

        /* کپی از درخت SAF: جست‌وجو و کپی اجزای لازم */
        File espeak = VoiceModels.espeakDir(this);
        if (!espeak.exists()) espeak.mkdirs();

        /* فایل‌های پایه — اگر قبلاً هست، رد شو */
        String[] base = {"phontab", "phonindex", "phondata", "intonations"};
        List<String> need = new ArrayList<>();
        for (String b : base) {
            if (!new File(espeak, b).exists()) need.add(b);
        }
        String langCode = espeakVoice.split("-")[0];
        String dictName = langCode + "_dict";
        if (!new File(espeak, dictName).exists()) need.add(dictName);

        /* کپی از درخت: جست‌وجوی بازگشتی تا عمق ۳ */
        TreeCopier copier = new TreeCopier(tree);
        for (String name : need) {
            Uri found = copier.find(name);
            if (found == null) {
                return "در پوشهٔ انتخاب‌شده «" + name + "» پیدا نشد — مطمئن شو پوشهٔ espeak-ng-data را گرفته‌ای";
            }
            String err = copyUriChecked(found, new File(espeak, name), 1);
            if (err != null) return "کپی «" + name + "» نشد: " + err;
        }
        File voiceFile = VoiceModels.findEspeakVoiceFile(espeak, espeakVoice);
        if (voiceFile == null || !voiceFile.exists()) {
            TreeEntry entry = copier.foundEntry(espeakVoice);
            if (entry == null) {
                return "فایل زبان «" + espeakVoice + "» در پوشهٔ espeak-ng-data/lang پیدا نشد";
            }
            String rel = entry.relative;
            /* اگر کاربر پوشهٔ والد را داده، پیشوند espeak-ng-data/ را بردار */
            String low = rel.toLowerCase();
            if (low.startsWith("espeak-ng-data/")) {
                rel = rel.substring("espeak-ng-data/".length());
            } else if (low.startsWith("espeak-ng-data\\")) {
                rel = rel.substring("espeak-ng-data\\".length());
            }
            File dest = new File(espeak, rel);
            if (!dest.getParentFile().exists()) dest.getParentFile().mkdirs();
            String err = copyUriChecked(entry.uri, dest, 1);
            if (err != null) return "کپی فایل زبان نشد: " + err;
        }

        VoiceModels.addImportedId(this, voiceId);
        return null;
    }

    private String displayNameOf(Uri uri) {
        try {
            android.database.Cursor c = getContentResolver().query(uri, null, null, null, null);
            if (c != null) {
                try {
                    int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0 && c.moveToFirst()) {
                        String n = c.getString(idx);
                        if (n != null && n.length() > 0) return n;
                    }
                } finally {
                    c.close();
                }
            }
        } catch (Throwable ignored) {
        }
        String p = uri.getLastPathSegment();
        if (p != null) {
            int s = p.lastIndexOf('/');
            if (s >= 0) p = p.substring(s + 1);
            int c = p.lastIndexOf(':');
            if (c >= 0) p = p.substring(c + 1);
        }
        return p;
    }

    /** کپی با حداقل حجم؛ @return null = موفق */
    private String copyUriChecked(Uri uri, File dest, long minBytes) {
        try {
            InputStream in = getContentResolver().openInputStream(uri);
            if (in == null) return "خوانده نشد";
            OutputStream out = new FileOutputStream(dest);
            byte[] buf = new byte[65536];
            int n;
            try {
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally {
                try {
                    out.close();
                } catch (Exception ignored) {
                }
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
            if (dest.length() < minBytes) {
                dest.delete();
                return "فایل خیلی کوچک است (ناقص)";
            }
            return null;
        } catch (Throwable t) {
            dest.delete();
            return t.getMessage() != null ? t.getMessage() : "خطای ناشناخته";
        }
    }

    /* ---------- پیمایش درخت SAF با DocumentsContract خالص ---------- */

    private static class TreeEntry {
        String docId;
        String name;
        String relative;
        boolean dir;
        Uri uri;
    }

    private class TreeCopier {
        final Uri treeUri;
        final List<TreeEntry> flat = new ArrayList<>();
        final Map<String, TreeEntry> byName = new HashMap<>();

        TreeCopier(Uri treeUri) throws Exception {
            this.treeUri = treeUri;
            String rootId = DocumentsContract.getTreeDocumentId(treeUri);
            collect(rootId, "", 0);
        }

        private void collect(String docId, String relPrefix, int depth) throws Exception {
            if (depth > 3 || flat.size() > 2500) return;
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId);
            Cursor c = getContentResolver().query(children,
                    new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                            DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null);
            if (c == null) return;
            try {
                while (c.moveToNext()) {
                    String id = c.getString(0);
                    String name = c.getString(1);
                    String mime = c.getString(2);
                    if (id == null || name == null) continue;
                    boolean isDir = DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
                    TreeEntry e = new TreeEntry();
                    e.docId = id;
                    e.name = name;
                    e.dir = isDir;
                    e.relative = relPrefix + name;
                    e.uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id);
                    flat.add(e);
                    if (!isDir) {
                        String key = name.toLowerCase();
                        if (!byName.containsKey(key)) byName.put(key, e);
                    } else {
                        collect(id, relPrefix + name + "/", depth + 1);
                    }
                }
            } finally {
                c.close();
            }
        }

        Uri find(String name) {
            TreeEntry e = byName.get(name.toLowerCase());
            return e != null ? e.uri : null;
        }

        TreeEntry foundEntry(String name) {
            return byName.get(name.toLowerCase());
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
