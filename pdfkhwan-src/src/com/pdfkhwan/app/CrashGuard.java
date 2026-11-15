package com.pdfkhwan.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.widget.Toast;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.Thread;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/* loaded from: classes.dex */
public final class CrashGuard {
    private static final String FILE = "crash.log";

    private CrashGuard() {
    }

    public static void append(Context context, String str) {
        /* v1.29 FIX: synchronized — append is called from crash handlers on
           arbitrary threads AND from the thumbnail/TOC/IO background error
           logging; concurrent FileWriter/tail-rewrite sequences could
           interleave and corrupt the log. */
        synchronized (CrashGuard.class) {
        try {
            File file = new File(context.getApplicationContext().getFilesDir(), FILE);
            FileWriter fileWriter = null;
            try {
                fileWriter = new FileWriter(file, true);
                fileWriter.write(str);
            } finally {
                /* v7 FIX: the writer leaked when write() threw */
                if (fileWriter != null) {
                    try {
                        fileWriter.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
            if (file.length() > 262144) {
                /* v1.28 FIX: delete() نابودکننده بود — آخرین کرشِ همین لحظه
                   را هم پاک می‌کرد و «دفعهٔ قبل خطا داد» هیچ‌وقت دیده
                   نمی‌شد؛ حالا فقط ۱۲۸KB انتهایی (جدیدترین رخدادها که
                   انتهای فایل append می‌شوند) نگه داشته می‌شود */
                try {
                    java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r");
                    byte[] tail;
                    try {
                        long len = raf.length();
                        long start = Math.max(0L, len - 131072L);
                        tail = new byte[(int) (len - start)];
                        raf.seek(start);
                        raf.readFully(tail);
                    } finally {
                        raf.close();
                    }
                    java.io.FileOutputStream fo = new java.io.FileOutputStream(file, false);
                    try {
                        fo.write(tail);
                    } finally {
                        try { fo.close(); } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable unused) {
        }
        }
    }

    public static void clear(Context context) {
        try {
            new File(context.getApplicationContext().getFilesDir(), FILE).delete();
        } catch (Throwable unused) {
        }
    }

    public static String describe(Throwable th) {
        StringBuilder sb = new StringBuilder();
        sb.append(th.getClass().getName());
        String message = th.getMessage();
        if (message != null && message.length() > 0) {
            sb.append("\n\n");
            sb.append(message);
        }
        StackTraceElement[] stackTrace = th.getStackTrace();
        int min = Math.min(stackTrace.length, 5);
        for (int i = 0; i < min; i++) {
            sb.append("\n");
            sb.append(stackTrace[i].toString());
        }
        if (stackTrace.length > 5) {
            sb.append("\n…");
        }
        return sb.toString();
    }

    public static String head(String str, int i) {
        if (str == null) {
            return "";
        }
        if (str.length() <= i) {
            return str;
        }
        return String.valueOf(str.substring(0, i)) + "\n…";
    }

    /* v7 FIX: install() chained a NEW handler on every Activity creation —
       after opening N activities one crash was appended N times and blew
       the 256 KB cap. Install once per process. */
    private static volatile boolean installed = false;

    public static void install(Context context) {
        if (installed) {
            return;
        }
        installed = true;
        final Context applicationContext = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler defaultUncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() { // from class: com.pdfkhwan.app.CrashGuard.1
            @Override // java.lang.Thread.UncaughtExceptionHandler
            public void uncaughtException(Thread thread, Throwable th) {
                try {
                    CrashGuard.append(applicationContext, "---- crash on " + CrashGuard.now() + "  thread=" + thread.getName() + "\n" + CrashGuard.stackToString(th) + "\n");
                } catch (Throwable unused) {
                }
                Thread.UncaughtExceptionHandler uncaughtExceptionHandler = defaultUncaughtExceptionHandler;
                if (uncaughtExceptionHandler != null) {
                    uncaughtExceptionHandler.uncaughtException(thread, th);
                }
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
    }

    public static String read(Context context) {
        try {
            File file = new File(context.getApplicationContext().getFilesDir(), FILE);
            if (file.exists() && file.length() != 0) {
                BufferedReader bufferedReader = new BufferedReader(new FileReader(file));
                try {
                    /* v1.16: reader closed in finally — the old code leaked it
                       when read() threw mid-file. */
                    StringBuilder sb = new StringBuilder();
                    char[] cArr = new char[8192];
                    while (true) {
                        int read = bufferedReader.read(cArr);
                        if (read <= 0) {
                            return sb.toString();
                        }
                        sb.append(cArr, 0, read);
                    }
                } finally {
                    try {
                        bufferedReader.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable unused) {
        }
        return "";
    }

    public static void show(final Activity activity, String str, String str2, final String str3) {
        try {
            try {
                new AlertDialog.Builder(activity).setTitle(str).setMessage(str2).setPositiveButton("کپی جزئیات", new DialogInterface.OnClickListener() { // from class: com.pdfkhwan.app.CrashGuard.2
                    @Override // android.content.DialogInterface.OnClickListener
                    public void onClick(DialogInterface dialogInterface, int i) {
                        try {
                            ClipboardManager clipboardManager = (ClipboardManager) activity.getSystemService("clipboard");
                            if (clipboardManager != null) {
                                clipboardManager.setPrimaryClip(ClipData.newPlainText("pdfkhwan-error", str3));
                            }
                            Toast.makeText(activity, "کپی شد", 0).show();
                        } catch (Throwable unused) {
                        }
                    }
                }).setNegativeButton("بستن", new DialogInterface.OnClickListener() { // from class: com.pdfkhwan.app.CrashGuard.3
                    @Override // android.content.DialogInterface.OnClickListener
                    public void onClick(DialogInterface dialogInterface, int i) {
                        try {
                            activity.finish();
                        } catch (Throwable unused) {
                        }
                    }
                }).setOnCancelListener(new DialogInterface.OnCancelListener() { // from class: com.pdfkhwan.app.CrashGuard.4
                    @Override // android.content.DialogInterface.OnCancelListener
                    public void onCancel(DialogInterface dialogInterface) {
                        try {
                            activity.finish();
                        } catch (Throwable unused) {
                        }
                    }
                }).show();
            } catch (Throwable unused) {
                activity.finish();
            }
        } catch (Throwable unused2) {
        }
    }

    public static String stackToString(Throwable th) {
        StringWriter stringWriter = new StringWriter();
        th.printStackTrace(new PrintWriter(stringWriter));
        return stringWriter.toString();
    }
}
