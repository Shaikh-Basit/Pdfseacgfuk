package com.pdfkhwan.app;

import android.animation.ValueAnimator;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/* ====================================================================
   v1.33 — Modern UI toolkit (framework-only, no external deps).
   Cohesive dark design matching the app theme:
     bg #0B1220 · card #121B2E · surface #16203A · border #24345A
     text #E8EEF8 · muted #8DA0BC · accent #2DD4BF · danger #F87171
   Every surface is rounded; every touch target has a ripple.
   ==================================================================== */
public final class Modern {

    /* ---------- palette (matches res/values/colors.xml) ---------- */
    public static final int C_BG      = 0xFF0B1220;
    public static final int C_CARD    = 0xFF121B2E;
    public static final int C_SURFACE = 0xFF16203A;
    public static final int C_FIELD   = 0xFF0A1120;
    public static final int C_BORDER  = 0xFF2A3E66;
    public static final int C_TEXT    = 0xFFE8EEF8;
    public static final int C_MUTED   = 0xFF8DA0BC;
    public static final int C_ACCENT  = 0xFF2DD4BF;
    public static final int C_ACCENT_DIM = 0x332DD4BF;
    public static final int C_DANGER  = 0xFFF87171;
    public static final int C_DANGER_DIM = 0x26F87171;
    public static final int C_RIPPLE  = 0x33FFFFFF;

    private Modern() {}

    /* ---------- basics ---------- */

    public static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /** rounded-rect shape */
    public static GradientDrawable round(int color, float radiusDp, Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    public static GradientDrawable roundStroke(int color, float radiusDp, int strokeColor, float strokeDp, Context c) {
        GradientDrawable g = round(color, radiusDp, c);
        g.setStroke(dp(c, strokeDp), strokeColor);
        return g;
    }

    /** pill = fully rounded */
    public static GradientDrawable pill(int color, Context c) {
        return round(color, 999, c);
    }

    public static GradientDrawable pillStroke(int color, int strokeColor, Context c) {
        GradientDrawable g = pill(color, c);
        g.setStroke(dp(c, 1), strokeColor);
        return g;
    }

    /** circle shape */
    public static GradientDrawable circle(int color, Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    public static GradientDrawable ring(int color, float strokeDp, int strokeColor, Context c) {
        GradientDrawable g = circle(color, c);
        g.setStroke(dp(c, strokeDp), strokeColor);
        return g;
    }

    /** ripple wrapper over any content drawable */
    public static RippleDrawable ripple(Drawable content, int rippleColor) {
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, content);
    }

    public static RippleDrawable rippleRound(int bgColor, float radiusDp, int rippleColor, Context c) {
        return ripple(round(bgColor, radiusDp, c), rippleColor);
    }

    /* ---------- views ---------- */

    /** circular color swatch with optional selection ring */
    public static View swatch(final Context c, final int color, float sizeDp, boolean selected) {
        View v = new View(c);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp));
        v.setLayoutParams(lp);
        applySwatch(v, c, color, selected);
        v.setBackground(ripple(ring(color, selected ? 2.5f : 1.5f,
                selected ? 0xFFFFFFFF : 0x66FFFFFF, c), C_RIPPLE));
        return v;
    }

    public static void applySwatch(View v, Context c, int color, boolean selected) {
        v.setBackground(ripple(ring(color, selected ? 2.5f : 1.5f,
                selected ? 0xFFFFFFFF : 0x66FFFFFF, c), C_RIPPLE));
    }

    /** icon button — 40dp touch target, round ripple, icon tint */
    public static ImageView iconBtn(Context c, int iconRes, int tint, View.OnClickListener l) {
        ImageView iv = new ImageView(c);
        iv.setImageResource(iconRes);
        iv.setColorFilter(tint);
        int pad = dp(c, 9);
        iv.setPadding(pad, pad, pad, pad);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setOnClickListener(l);
        return iv;
    }

    /** icon button with active-state pill background (for segmented controls) */
    public static ImageView segBtn(Context c, int iconRes, int tint, boolean active, View.OnClickListener l) {
        ImageView iv = iconBtn(c, iconRes, active ? tint == C_ACCENT ? C_ACCENT : tint : C_MUTED, l);
        refreshSeg(iv, c, active);
        return iv;
    }

    public static void refreshSeg(ImageView iv, Context c, boolean active) {
        iv.setBackground(active
                ? rippleRound(C_ACCENT_DIM, 12, C_RIPPLE, c)
                : rippleRound(0x00000000, 12, C_RIPPLE, c));
    }

    /** vertical icon+label button (action grids) */
    public static LinearLayout iconLabelBtn(Context c, int iconRes, int tint, String label,
                                            View.OnClickListener l) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        int p = dp(c, 10);
        col.setPadding(p, p, p, p);
        ImageView iv = new ImageView(c);
        iv.setImageResource(iconRes);
        iv.setColorFilter(tint);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(c, 26), dp(c, 26));
        iv.setLayoutParams(ilp);
        col.addView(iv);
        TextView tv = new TextView(c);
        tv.setText(label);
        tv.setTextColor(C_MUTED);
        tv.setTextSize(11.5f);
        tv.setPadding(0, dp(c, 6), 0, 0);
        col.addView(tv);
        col.setOnClickListener(l);
        col.setBackground(rippleRound(0x00000000, 14, C_RIPPLE, c));
        return col;
    }

    /** filled pill button (primary) */
    public static TextView filledBtn(Context c, String label, int bg, int fg, View.OnClickListener l) {
        TextView tv = new TextView(c);
        tv.setText(label);
        tv.setTextColor(fg);
        tv.setTextSize(14f);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setGravity(Gravity.CENTER);
        int h = dp(c, 12), v = dp(c, 10);
        tv.setPadding(h, v, h, v);
        tv.setMinimumHeight(dp(c, 44));
        tv.setOnClickListener(l);
        tv.setBackground(ripple(pill(bg, c), C_RIPPLE));
        return tv;
    }

    /** tonal outline pill button (secondary) */
    public static TextView tonalBtn(Context c, String label, int color, View.OnClickListener l) {
        TextView tv = new TextView(c);
        tv.setText(label);
        tv.setTextColor(color);
        tv.setTextSize(13.5f);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setGravity(Gravity.CENTER);
        int h = dp(c, 14), v = dp(c, 10);
        tv.setPadding(h, v, h, v);
        tv.setMinimumHeight(dp(c, 44));
        tv.setOnClickListener(l);
        tv.setBackground(ripple(pillStroke(0x00000000, (color & 0x00FFFFFF) | 0x55FF0000, c), C_RIPPLE));
        return tv;
    }

    /** small close (✕) button */
    public static ImageView closeBtn(Context c, View.OnClickListener l) {
        ImageView iv = iconBtn(c, R.drawable.ic_ann_close, C_MUTED, l);
        iv.setBackground(rippleRound(0x22FFFFFF, 999, C_RIPPLE, c));
        return iv;
    }

    /** sheet header row: title (+optional subtitle) + close */
    public static LinearLayout sheetHeader(Context c, String title, String sub, View.OnClickListener onClose) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.VERTICAL);
        int p = dp(c, 18);
        row.setPadding(p, dp(c, 14), p, dp(c, 6));

        LinearLayout trow = new LinearLayout(c);
        trow.setOrientation(LinearLayout.HORIZONTAL);
        trow.setGravity(Gravity.CENTER_VERTICAL);
        TextView tv = new TextView(c);
        tv.setText(title);
        tv.setTextColor(C_TEXT);
        tv.setTextSize(16.5f);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        trow.addView(tv, new LinearLayout.LayoutParams(0, -2, 1f));
        trow.addView(closeBtn(c, onClose));
        row.addView(trow);

        if (sub != null && sub.length() > 0) {
            TextView st = new TextView(c);
            st.setText(sub);
            st.setTextColor(C_MUTED);
            st.setTextSize(12f);
            st.setPadding(0, dp(c, 4), 0, 0);
            row.addView(st);
        }
        return row;
    }

    /** rounded input-style container background */
    public static GradientDrawable fieldBg(Context c) {
        return roundStroke(C_FIELD, 14, C_BORDER, 1, c);
    }

    /** filter chip */
    public static TextView chip(Context c, String label, boolean active, View.OnClickListener l) {
        TextView tv = new TextView(c);
        tv.setText(label);
        tv.setTextSize(12.5f);
        tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tv.setTextColor(active ? 0xFF0B1220 : C_MUTED);
        int h = dp(c, 13), v = dp(c, 8);
        tv.setPadding(h, v, h, v);
        tv.setOnClickListener(l);
        tv.setBackground(ripple(active ? pill(C_ACCENT, c) : pillStroke(0x14FFFFFF, C_BORDER, c), C_RIPPLE));
        return tv;
    }

    /** styled slider */
    public static SeekBar slider(Context c) {
        SeekBar sb = new SeekBar(c);
        sb.setMax(100);
        sb.setProgress(50);
        return sb;
    }

    /** tint the whole seekbar track+thumb */
    public static void tintSlider(SeekBar sb, int color, Context c) {
        GradientDrawable track = round(0x33FFFFFF, 3, c);
        GradientDrawable prog = round(color, 3, c);
        android.graphics.drawable.ClipDrawable cd =
                new android.graphics.drawable.ClipDrawable(prog, Gravity.LEFT,
                        android.graphics.drawable.ClipDrawable.HORIZONTAL);
        android.graphics.drawable.LayerDrawable ld = new android.graphics.drawable.LayerDrawable(
                new Drawable[]{track, cd});
        sb.setProgressDrawable(ld);
        GradientDrawable thumb = circle(color, c);
        int s = dp(c, 16);
        thumb.setSize(s, s);
        sb.setThumb(thumb);
        sb.setThumbOffset(dp(c, 2));
        sb.setPadding(dp(c, 6), 0, dp(c, 6), 0);
    }

    /* ---------- dialogs ---------- */

    /** content root for a bottom sheet (drag handle already included) */
    public static LinearLayout sheetContent(Context c) {
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(roundStroke(C_SURFACE, 26, C_BORDER, 1, c));
        View handle = new View(c);
        handle.setBackground(round(0x3DFFFFFF, 2.5f, c));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(dp(c, 36), dp(c, 4));
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        hlp.topMargin = dp(c, 10);
        hlp.bottomMargin = dp(c, 6);
        root.addView(handle, hlp);
        return root;
    }

    /** show a view as a modern bottom sheet; returns the dialog */
    public static Dialog showSheet(Context c, LinearLayout content) {
        final Dialog d = new Dialog(c);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setContentView(content);
        d.setCanceledOnTouchOutside(true);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0x00000000));
            w.setGravity(Gravity.BOTTOM);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setWindowAnimations(R.style.SheetAnim);
        }
        d.show();
        return d;
    }

    /** v1.43: auto-capped bottom sheet — scrolls whenever the content is
        taller than ~86% of the window (phones in landscape); portrait
        behaviour is unchanged (content is usually shorter than the cap). */
    public static Dialog showSheetAuto(Context c, LinearLayout content) {
        int cap = Math.round(c.getResources().getDisplayMetrics().heightPixels * 0.86f);
        return showSheetCapped(c, content, cap);
    }

    /** show a view as a modern centered card; returns the dialog */
    public static Dialog showCard(Context c, View content) {
        final Dialog d = new Dialog(c);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        FrameLayout wrap = new FrameLayout(c);
        wrap.setPadding(dp(c, 18), 0, dp(c, 18), 0);
        wrap.addView(content, new FrameLayout.LayoutParams(-1, -2));
        d.setContentView(wrap);
        d.setCanceledOnTouchOutside(true);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0x00000000));
            w.setGravity(Gravity.CENTER);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setWindowAnimations(R.style.CardAnim);
        }
        content.setElevation(dp(c, 12));
        d.show();
        return d;
    }

    /** v1.43: centered card that never exceeds 86% of the window height —
        taller cards scroll (landscape phones used to cut sliders/buttons). */
    public static Dialog showCardAuto(Context c, View content) {
        int cap = Math.round(c.getResources().getDisplayMetrics().heightPixels * 0.86f);
        CappedScroll scroll = new CappedScroll(c, cap);
        scroll.addView(content, new FrameLayout.LayoutParams(-1, -2));
        final Dialog d = new Dialog(c);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        FrameLayout wrap = new FrameLayout(c);
        wrap.setPadding(dp(c, 18), 0, dp(c, 18), 0);
        wrap.addView(scroll, new FrameLayout.LayoutParams(-1, -2));
        d.setContentView(wrap);
        d.setCanceledOnTouchOutside(true);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0x00000000));
            w.setGravity(Gravity.CENTER);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setWindowAnimations(R.style.CardAnim);
        }
        content.setElevation(dp(c, 12));
        d.show();
        return d;
    }

    /** card content root (rounded, bordered) */
    public static LinearLayout cardContent(Context c) {
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(roundStroke(C_SURFACE, 24, C_BORDER, 1, c));
        int p = dp(c, 18);
        root.setPadding(p, p, p, p);
        return root;
    }

    /** horizontal action row with icon + title + subtitle; returns the row */
    public static LinearLayout actionRow(Context c, int iconRes, int iconTint, String title, String sub,
                                         View.OnClickListener l) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int p = dp(c, 12);
        row.setPadding(p, dp(c, 11), p, dp(c, 11));

        LinearLayout ibg = new LinearLayout(c);
        ibg.setOrientation(LinearLayout.VERTICAL);
        ibg.setGravity(Gravity.CENTER);
        ibg.setBackground(round(iconTint == C_DANGER ? C_DANGER_DIM : (iconTint & 0x2EFFFFFF), 12, c));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(c, 38), dp(c, 38));
        ibg.setLayoutParams(blp);
        ImageView iv = new ImageView(c);
        iv.setImageResource(iconRes);
        iv.setColorFilter(iconTint);
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(c, 21), dp(c, 21)));
        ibg.addView(iv);
        row.addView(ibg);

        LinearLayout txt = new LinearLayout(c);
        txt.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1f);
        tlp.setMarginStart(dp(c, 13));
        txt.setLayoutParams(tlp);
        TextView t1 = new TextView(c);
        t1.setText(title);
        t1.setTextColor(C_TEXT);
        t1.setTextSize(14.5f);
        t1.setTypeface(null, android.graphics.Typeface.BOLD);
        txt.addView(t1);
        if (sub != null && sub.length() > 0) {
            TextView t2 = new TextView(c);
            t2.setText(sub);
            t2.setTextColor(C_MUTED);
            t2.setTextSize(11.5f);
            t2.setPadding(0, dp(c, 2), 0, 0);
            txt.addView(t2);
        }
        row.addView(txt);

        row.setOnClickListener(l);
        row.setBackground(rippleRound(0x00000000, 14, C_RIPPLE, c));
        return row;
    }

    /** thin divider */
    public static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, dp(c, 0.7f)));
        lp.topMargin = dp(c, 2);
        lp.bottomMargin = dp(c, 2);
        v.setLayoutParams(lp);
        return v;
    }

    /* ---------- v1.34: menu components ---------- */

    /** section label inside sheets (small bold accent-colored) */
    public static TextView sectionLabel(Context c, String title) {
        TextView tv = new TextView(c);
        tv.setText(title);
        tv.setTextColor(C_ACCENT);
        tv.setTextSize(11.5f);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setPadding(dp(c, 18), dp(c, 13), dp(c, 18), dp(c, 3));
        return tv;
    }

    /** key-value info line for info cards */
    public static LinearLayout infoRow(Context c, String key, String value) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL | Gravity.TOP);
        TextView k = new TextView(c);
        k.setText(key);
        k.setTextColor(C_MUTED);
        k.setTextSize(12.5f);
        TextView v = new TextView(c);
        v.setText(value == null ? "—" : value);
        v.setTextColor(C_TEXT);
        v.setTextSize(12.5f);
        v.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(0, -2, 1f);
        vlp.setMarginStart(dp(c, 10));
        v.setGravity(Gravity.END);
        row.addView(k, new LinearLayout.LayoutParams(-2, -2));
        row.addView(v, vlp);
        int h = dp(c, 18);
        row.setPadding(h, dp(c, 4), h, dp(c, 4));
        return row;
    }

    /* ================= v1.34: animated toggle switch =================
       Framework Switch drawables differ wildly across API levels, so we
       draw our own pill toggle — 46x26dp, animated knob, RTL-aware. */
    public static class Toggle extends View {
        public interface OnChange { void onChange(boolean on); }
        private float t;                 /* 0..1 knob position */
        private boolean on;
        private ValueAnimator anim;
        private final OnChange cb;
        private final android.graphics.Paint p = new android.graphics.Paint(1);

        public Toggle(Context c, boolean initial, OnChange cb) {
            super(c);
            this.on = initial;
            this.t = initial ? 1f : 0f;
            this.cb = cb;
            setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { toggle(); }
            });
        }

        public boolean isOn() { return on; }

        public void setOnSilently(boolean v) {
            on = v;
            t = v ? 1f : 0f;
            invalidate();
        }

        public void toggle() {
            setOn(!on, true);
        }

        public void setOn(boolean v, boolean notify) {
            on = v;
            if (anim != null) anim.cancel();
            anim = ValueAnimator.ofFloat(t, v ? 1f : 0f);
            anim.setDuration(180);
            anim.setInterpolator(new android.view.animation.DecelerateInterpolator());
            anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(ValueAnimator a) {
                    t = (Float) a.getAnimatedValue();
                    invalidate();
                }
            });
            anim.start();
            if (notify && cb != null) cb.onChange(on);
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float r = h / 2f;
            p.setColor(on ? C_ACCENT : 0x2EFFFFFF);
            canvas.drawRoundRect(0, 0, w, h, r, r, p);
            float kd = h - dp(getContext(), 7);
            boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
            float tt = rtl ? 1f - t : t;
            float kx = dp(getContext(), 3.5f) + tt * (w - kd - dp(getContext(), 7));
            p.setColor(0xFFFFFFFF);
            canvas.drawCircle(kx + kd / 2f, h / 2f, kd / 2f, p);
        }

        @Override
        protected void onMeasure(int ws, int hs) {
            setMeasuredDimension(dp(getContext(), 46), dp(getContext(), 26));
        }
    }

    /** row: icon-box + title/sub + animated toggle — live toggling, no dismiss */
    public static LinearLayout toggleRow(final Context c, int iconRes, int iconTint, String title, String sub,
                                         boolean on, final Toggle.OnChange cb) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int p = dp(c, 12);
        row.setPadding(p, dp(c, 8), p, dp(c, 8));

        LinearLayout ibg = new LinearLayout(c);
        ibg.setOrientation(LinearLayout.VERTICAL);
        ibg.setGravity(Gravity.CENTER);
        ibg.setBackground(round(iconTint & 0x2EFFFFFF, 12, c));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(c, 38), dp(c, 38));
        ibg.setLayoutParams(blp);
        ImageView iv = new ImageView(c);
        iv.setImageResource(iconRes);
        iv.setColorFilter(iconTint);
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(c, 21), dp(c, 21)));
        ibg.addView(iv);
        row.addView(ibg);

        LinearLayout txt = new LinearLayout(c);
        txt.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1f);
        tlp.setMarginStart(dp(c, 13));
        txt.setLayoutParams(tlp);
        TextView t1 = new TextView(c);
        t1.setText(title);
        t1.setTextColor(C_TEXT);
        t1.setTextSize(14.5f);
        t1.setTypeface(null, android.graphics.Typeface.BOLD);
        txt.addView(t1);
        if (sub != null && sub.length() > 0) {
            TextView t2 = new TextView(c);
            t2.setText(sub);
            t2.setTextColor(C_MUTED);
            t2.setTextSize(11.5f);
            t2.setPadding(0, dp(c, 2), 0, 0);
            txt.addView(t2);
        }
        row.addView(txt);

        final Toggle tg = new Toggle(c, on, cb);
        row.addView(tg, new LinearLayout.LayoutParams(-2, -2));
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { tg.toggle(); }
        });
        row.setBackground(rippleRound(0x00000000, 14, C_RIPPLE, c));
        return row;
    }

    /* ---------- v1.37: compact menu components ----------
       کاربر: «فاصله بین قابلیت‌ها کم شود، توضیح زیر هرکدام حذف شود،
       منو تمام‌صفحه نباشد» — ردیف‌های بدون زیرنویس با ارتفاع کم. */

    /** compact action row (no subtitle) — icon-box 32dp, ~42dp tall */
    public static LinearLayout compactRow(Context c, int iconRes, int iconTint, String title,
                                          View.OnClickListener l) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int p = dp(c, 12);
        row.setPadding(p, dp(c, 5), p, dp(c, 5));

        LinearLayout ibg = new LinearLayout(c);
        ibg.setOrientation(LinearLayout.VERTICAL);
        ibg.setGravity(Gravity.CENTER);
        ibg.setBackground(round(iconTint == C_DANGER ? C_DANGER_DIM : (iconTint & 0x2EFFFFFF), 10, c));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(c, 32), dp(c, 32));
        ibg.setLayoutParams(blp);
        ImageView iv = new ImageView(c);
        iv.setImageResource(iconRes);
        iv.setColorFilter(iconTint);
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(c, 18), dp(c, 18)));
        ibg.addView(iv);
        row.addView(ibg);

        TextView t1 = new TextView(c);
        t1.setText(title);
        t1.setTextColor(C_TEXT);
        t1.setTextSize(14f);
        t1.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1f);
        tlp.setMarginStart(dp(c, 12));
        row.addView(t1, tlp);

        row.setOnClickListener(l);
        row.setBackground(rippleRound(0x00000000, 12, C_RIPPLE, c));
        return row;
    }

    /** compact row with an end accessory view (radio dot / toggle) */
    public static LinearLayout compactRowAcc(Context c, int iconRes, int iconTint, String title,
                                             View accessory, View.OnClickListener l) {
        LinearLayout row = compactRow(c, iconRes, iconTint, title, l);
        row.addView(accessory, new LinearLayout.LayoutParams(-2, -2));
        return row;
    }

    /** compact toggle row (no subtitle) */
    public static LinearLayout compactToggle(Context c, int iconRes, int iconTint, String title,
                                             boolean on, final Toggle.OnChange cb) {
        final Toggle tg = new Toggle(c, on, cb);
        LinearLayout row = compactRow(c, iconRes, iconTint, title, null);
        row.addView(tg, new LinearLayout.LayoutParams(-2, -2));
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { tg.toggle(); }
        });
        return row;
    }

    /** selected-option dot for choosers */
    public static View radioDot(Context c, boolean selected) {
        View v = new View(c);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(c, 18), dp(c, 18));
        v.setLayoutParams(lp);
        v.setBackground(ring(selected ? C_ACCENT : 0x00000000, selected ? 5f : 2f,
                selected ? C_ACCENT : C_BORDER, c));
        return v;
    }

    /** section label — tighter than v1.34 */
    public static TextView sectionLabelCompact(Context c, String title) {
        TextView tv = new TextView(c);
        tv.setText(title);
        tv.setTextColor(C_ACCENT);
        tv.setTextSize(10.5f);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setPadding(dp(c, 18), dp(c, 7), dp(c, 18), dp(c, 2));
        return tv;
    }

    /** ScrollView that never grows past maxPx — keeps sheets compact */
    public static class CappedScroll extends android.widget.ScrollView {
        private final int maxPx;
        public CappedScroll(Context c, int maxPx) {
            super(c);
            this.maxPx = maxPx;
            setVerticalScrollBarEnabled(false);
        }
        @Override
        protected void onMeasure(int ws, int hs) {
            super.onMeasure(ws, View.MeasureSpec.makeMeasureSpec(maxPx, View.MeasureSpec.AT_MOST));
        }
    }

    /** bottom sheet capped to maxPx height (never a full-screen takeover) */
    public static Dialog showSheetCapped(Context c, LinearLayout content, int maxPx) {
        CappedScroll scroll = new CappedScroll(c, maxPx);
        scroll.addView(content, new FrameLayout.LayoutParams(-1, -2));
        /* v1.43: the sheet must not hide its last rows under the gesture bar —
           phones in landscape had menus that would not scroll to the end. */
        try {
            scroll.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @Override public android.view.WindowInsets onApplyWindowInsets(View v, android.view.WindowInsets insets) {
                    int nb = insets.getSystemWindowInsetBottom();
                    if (nb > 0) v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), nb);
                    return insets;
                }
            });
        } catch (Throwable ignored) {
        }
        /* v1.50: حاشیهٔ استاندارد از لبهٔ صفحه — قبلاً شیت‌ها به‌تمام‌عرض
           به لبه می‌چسبیدند؛ روی گوشی‌های با حاشیهٔ نمایشگر (گلسی‌مانند)
           نصف محتوا زیر می‌رفت. */
        FrameLayout wrap = new FrameLayout(c);
        int side = dp(c, 10);
        wrap.setPadding(side, 0, side, dp(c, 8));
        wrap.addView(scroll, new FrameLayout.LayoutParams(-1, -2));
        final Dialog d = new Dialog(c);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setContentView(wrap);
        d.setCanceledOnTouchOutside(true);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0x00000000));
            w.setGravity(Gravity.BOTTOM);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setWindowAnimations(R.style.SheetAnim);
        }
        d.show();
        return d;
    }

    /* ---------- HSL color math for the custom picker ---------- */

    public static int hsl(float h, float s, float l) {
        h = ((h % 360f) + 360f) % 360f;
        s = clamp01(s);
        l = clamp01(l);
        float c = (1f - Math.abs(2f * l - 1f)) * s;
        float x = c * (1f - Math.abs(((h / 60f) % 2f) - 1f));
        float m = l - c / 2f;
        float r, g, b;
        if (h < 60f)       { r = c; g = x; b = 0; }
        else if (h < 120f) { r = x; g = c; b = 0; }
        else if (h < 180f) { r = 0; g = c; b = x; }
        else if (h < 240f) { r = 0; g = x; b = c; }
        else if (h < 300f) { r = x; g = 0; b = c; }
        else               { r = c; g = 0; b = x; }
        return 0xFF000000 | ((int) ((r + m) * 255) << 16)
                | ((int) ((g + m) * 255) << 8) | (int) ((b + m) * 255);
    }

    public static float[] toHsl(int color) {
        float r = ((color >> 16) & 0xFF) / 255f;
        float g = ((color >> 8) & 0xFF) / 255f;
        float b = (color & 0xFF) / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        float l = (max + min) / 2f;
        if (max == min) return new float[]{0f, 0f, l};
        float d = max - min;
        float s = l > 0.5f ? d / (2f - max - min) : d / (max + min);
        float h;
        if (max == r)      h = 60f * (((g - b) / d) % 6f);
        else if (max == g) h = 60f * (((b - r) / d) + 2f);
        else               h = 60f * (((r - g) / d) + 4f);
        return new float[]{((h % 360f) + 360f) % 360f, s, l};
    }

    public static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    /** readable text color on top of a given bg */
    public static int onColor(int bg) {
        double r = ((bg >> 16) & 0xFF) / 255.0, g = ((bg >> 8) & 0xFF) / 255.0, b = (bg & 0xFF) / 255.0;
        double lum = 0.2126 * r + 0.7152 * g + 0.0722 * b;
        return lum > 0.55 ? 0xFF10151F : 0xFFFFFFFF;
    }
}
