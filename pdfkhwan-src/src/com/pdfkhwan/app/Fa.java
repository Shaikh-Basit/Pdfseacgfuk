package com.pdfkhwan.app;

/* loaded from: classes.dex */
public final class Fa {
    private static final char[] FA = {1776, 1777, 1778, 1779, 1780, 1781, 1782, 1783, 1784, 1785};

    private Fa() {
    }

    public static String bytes(long j) {
        if (j < 1024) {
            return String.valueOf(d(j)) + " بایت";
        }
        if (j < 1048576) {
            return String.valueOf(d(String.format("%.1f", Double.valueOf(j / 1024.0d)))) + " KB";
        }
        /* v1.29 FIX: GB tier — large books/videos rendered as «۱۲۰۴.۰ MB». */
        if (j < 1073741824L) {
            return String.valueOf(d(String.format("%.1f", Double.valueOf(j / 1048576.0d)))) + " MB";
        }
        return String.valueOf(d(String.format("%.1f", Double.valueOf(j / 1073741824.0d)))) + " GB";
    }

    /** zero-padded two digits (timer display) */
    public static String two(long v) {
        StringBuilder sb = new StringBuilder();
        if (v < 10) sb.append('0');
        sb.append(v);
        return sb.toString();
    }

    public static String d(long j) {
        return d(String.valueOf(j));
    }

    public static String d(String str) {
        StringBuilder sb = new StringBuilder(str.length());
        for (int i = 0; i < str.length(); i++) {
            char charAt = str.charAt(i);
            if (charAt < '0' || charAt > '9') {
                sb.append(charAt);
            } else {
                sb.append(FA[charAt - '0']);
            }
        }
        return sb.toString();
    }

    /* ====================================================================
       v1.15 FIX (bug: «رفتن به صفحه» با اعداد فارسی کار نمی‌کرد):
       Robust page-number parser. Accepts English digits (0-9), Persian
       digits (۰-۹, U+06F0..U+06F9), Arabic-Indic digits (٠-٩, U+0660..U+0669),
       mixed scripts, Persian/Arabic thousands separators (٬ U+066C, ،)
       and stray spaces / RTL marks. Returns -1 when there is no digit
       at all or the value overflows — callers must treat -1 as invalid.
       ==================================================================== */
    public static int parseNum(String raw) {
        if (raw == null) {
            return -1;
        }
        long value = 0;
        boolean anyDigit = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            int d;
            if (c >= '0' && c <= '9') {            // English digits
                d = c - '0';
            } else if (c >= 0x06F0 && c <= 0x06F9) { // Persian digits ۰..۹
                d = c - 0x06F0;
            } else if (c >= 0x0660 && c <= 0x0669) { // Arabic digits ٠..٩
                d = c - 0x0660;
            } else {
                // separators, spaces, bidi marks (200E/200F/202A-202E/2066-2069) → skip
                continue;
            }
            anyDigit = true;
            value = value * 10L + (long) d;
            if (value > 999999L) {
                return -1; // absurdly large — treat as invalid input
            }
        }
        if (!anyDigit) {
            return -1;
        }
        // fits comfortably in int (we capped at 999999)
        return (int) value;
    }

    /* v1.22: convert Persian/Arabic-Indic digits back to ASCII so numeric
       parsers (chart data editor etc.) accept localized input */
    public static String digitsToAscii(String s) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x06F0 && c <= 0x06F9) sb.append((char) ('0' + (c - 0x06F0)));
            else if (c >= 0x0660 && c <= 0x0669) sb.append((char) ('0' + (c - 0x0660)));
            else sb.append(c);
        }
        return sb.toString();
    }
}
