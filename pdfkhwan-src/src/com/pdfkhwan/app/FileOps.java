package com.pdfkhwan.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;

/* loaded from: classes.dex */
public final class FileOps {
    private static final int REQ_WRITE = 9017;

    public interface Done {
        void on(String str, String str2);
    }

    private FileOps() {
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void applyRename(Activity activity, File file, String str, Done done) {
        if (str.length() == 0) {
            toast(activity, "نام خالی است");
            return;
        }
        if (str.matches(".*[\\\\/:*?\"<>|].*")) {
            toast(activity, "این نویسه\u200cها در نام فایل مجاز نیستند: \\ / : * ? \" < > |");
            return;
        }
        String extOf = extOf(file.getName());
        final String finalName;
        if (extOf.length() > 0) {
            /* v1.28 FIX: اگر کاربر خودش پسوند معتبری تایپ کرده بود که
               همان پسوند اصلی نیست (a.ppt → b.pptx)، کورکورانه دوباره
               چسبانده می‌شد و نام «b.pptx.ppt» ساخته می‌شد؛ ابتدا هر
               پسوند شناخته‌شدهٔ تایپ‌شده حذف می‌شود */
            String stripped = str;
            int lastDot = stripped.lastIndexOf('.');
            if (lastDot > 0) {
                String typedExt = stripped.substring(lastDot).toLowerCase();
                if (typedExt.matches("\\.(pdf|pptx?|ppt|docx?|xlsx?|txt|jpg|jpeg|png|gif|webp|bmp|tiff?)")) {
                    stripped = stripped.substring(0, lastDot);
                }
            }
            if (!stripped.toLowerCase().endsWith(extOf)) {
                finalName = stripped + extOf;
            } else {
                finalName = stripped;
            }
        } else if (!str.toLowerCase().endsWith(".pdf")) {
            finalName = str + ".pdf";
        } else {
            finalName = str;
        }
        File file2 = new File(file.getParentFile(), finalName);
        if (file2.exists()) {
            toast(activity, "در همین پوشه فایلی با این نام هست");
            return;
        }
        /* v1.28 FIX: کپیِ fallback روی نخ UI (مخصوصاً بین دو حافظه)
           ANR می‌داد؛ همان منطق روی نخ پس‌زمینه اجرا می‌شود */
        mutateAsync(activity, file, file2, new Runnable() {
            @Override public void run() {
                scan(activity, new String[]{file2.getAbsolutePath(), file.getParentFile().getAbsolutePath()});
                toast(activity, "نام تغییر کرد ✓");
                if (done != null) {
                    done.on(file2.getAbsolutePath(), finalName);
                }
            }
        }, new Runnable() {
            @Override public void run() { toast(activity, "تغییر نام ممکن نشد"); }
        });
    }

    public static boolean canWrite(Activity activity) {
        try {
            return Build.VERSION.SDK_INT >= 30 ? Environment.isExternalStorageManager() : Build.VERSION.SDK_INT < 23 || activity.checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE") == 0;
        } catch (Throwable unused) {
            return false;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void copy(Context context, String str) {
        try {
            ((ClipboardManager) context.getSystemService("clipboard")).setPrimaryClip(ClipData.newPlainText("pdfkhwan", str));
        } catch (Throwable unused) {
        }
    }

    public static void deleteWithConfirm(final Activity activity, final File file, final Runnable runnable) {
        if (file == null || !file.exists()) {
            toast(activity, "فایل روی حافظه پیدا نشد");
            return;
        }
        new AlertDialog.Builder(activity).setTitle("حذف از دستگاه").setMessage("«" + file.getName() + "» برای همیشه از حافظهٔ گوشی حذف می\u200cشود.\nاین کار قابل بازگشت نیست.").setPositiveButton("حذف کن", new DialogInterface.OnClickListener() { // from class: com.pdfkhwan.app.FileOps.1
            @Override // android.content.DialogInterface.OnClickListener
            public void onClick(DialogInterface dialogInterface, int i) {
                boolean z;
                try {
                    z = file.delete();
                } catch (Throwable unused) {
                    z = false;
                }
                if (!z) {
                    FileOps.toast(activity, "حذف ممکن نشد — دسترسی «همهٔ فایل\u200cها» را بررسی کن");
                    return;
                }
                FileOps.scan(activity, new String[]{file.getParentFile().getAbsolutePath()});
                FileOps.toast(activity, "فایل از دستگاه حذف شد ✓");
                Runnable runnable2 = runnable;
                if (runnable2 != null) {
                    runnable2.run();
                }
            }
        }).setNegativeButton("انصراف", (DialogInterface.OnClickListener) null).show();
    }

    private static int dp(Context context, int i) {
        return Math.round(i * context.getResources().getDisplayMetrics().density);
    }

    public static boolean ensureWrite(Activity activity) {
        if (canWrite(activity)) {
            return true;
        }
        if (Build.VERSION.SDK_INT < 30) {
            toast(activity, "برای این کار، اجازهٔ نوشتن در حافظه لازم است");
            requestWrite(activity);
            return false;
        }
        toast(activity, "برای این کار، دسترسی «همهٔ فایل\u200cها» لازم است");
        /* v1.29 FIX: several OEMs ship no package-specific settings activity —
           fall back to the GLOBAL all-files screen (same chain as
           MainActivity.requestAccess) so the toast is never a dead end. */
        try {
            activity.startActivity(new Intent("android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION", Uri.parse("package:" + activity.getPackageName())));
        } catch (Throwable unused) {
            try {
                activity.startActivity(new Intent("android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION"));
            } catch (Throwable unused2) {
            }
        }
        return false;
    }

    private static String extOf(String str) {
        int lastIndexOf = str == null ? -1 : str.lastIndexOf(46);
        if (lastIndexOf <= 0 || lastIndexOf == str.length() - 1) {
            return "";
        }
        String lowerCase = str.substring(lastIndexOf).toLowerCase(Locale.US);
        if (lowerCase.equals(".pdf")) return lowerCase;
        if (lowerCase.equals(".pptx") || lowerCase.equals(".ppt")
                || lowerCase.equals(".ppsx") || lowerCase.equals(".pps")
                || lowerCase.equals(".ppsm") || lowerCase.equals(".pptm")
                || lowerCase.equals(".potx") || lowerCase.equals(".potm")) {
            /* v7 FIX: renaming a .ppt used to rewrite the extension as .pptx
               while the CONTENT stayed binary PPT — the editor then failed to
               reopen it. Keep the ORIGINAL extension. */
            return lowerCase;
        }
        return "";
    }

    public static void moveDialog(Activity activity, File file, Done done) {
        if (file == null || !file.exists()) {
            toast(activity, "فایل روی حافظه پیدا نشد");
        } else if (file.getParentFile() != null) {
            showDirPicker(activity, file, file.getParentFile(), done);
        }
    }

    /* v1.28: mutate روی نخ پس‌زمینه — نتیجه روی نخ UI */
    public static void mutateAsync(final Activity activity, final File file, final File file2,
                                   final Runnable ok, final Runnable fail) {
        final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override public void run() {
                final boolean success = mutate(activity, file, file2);
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (success) ok.run(); else fail.run();
                    }
                });
            }
        }, "pdfkhwan-mutate").start();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static boolean mutate(Activity activity, File file, File file2) {
        try {
            if (file.renameTo(file2)) {
                return true;
            }
        } catch (Throwable unused) {
        }
        FileInputStream fileInputStream = null;
        FileOutputStream fileOutputStream = null;
        try {
            try {
                fileInputStream = new FileInputStream(file);
                fileOutputStream = new FileOutputStream(file2);
                byte[] bArr = new byte[65536];
                while (true) {
                    int read = fileInputStream.read(bArr);
                    if (read <= 0) {
                        break;
                    }
                    fileOutputStream.write(bArr, 0, read);
                }
            } finally {
                /* v1.16 FIX: the old code closed the streams only on the
                   success path — a read/write error leaked both descriptors
                   (and could leave a half-written target). */
                if (fileOutputStream != null) {
                    try {
                        fileOutputStream.close();
                    } catch (Throwable ignored) {
                    }
                }
                if (fileInputStream != null) {
                    try {
                        fileInputStream.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
            if (file2.length() == file.length()) {
                if (file.delete()) {
                    return true;
                }
            }
            try {
                file2.delete();
            } catch (Throwable unused2) {
            }
            return false;
        } catch (Throwable unused4) {
            try {
                file2.delete();
            } catch (Throwable unused5) {
            }
            return false;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void openFolder(Activity activity, File file, String str) {
        if (file == null || !file.exists()) {
            file = Environment.getExternalStorageDirectory();
        }
        /* v1.15 FIX (bug: «مدیر فایل سیستم پیدا نشد»):
           زنجیرهٔ v1.14 برای همهٔ مدیرفایل‌های OEM از Uri.fromFile() استفاده
           می‌کرد. روی اندروید ۷+ (هدف ما ۳۴ است) ارسال URI از نوع file:// به
           اپ دیگر FileUriExposedException می‌دهد که در try/catch بی‌صدا قورت
           می‌شد؛ پس «همهٔ» تلاش‌های OEM شکست می‌خوردند و پیام آخر می‌آمد.

           استراتژی جدید:
           0) مدیرفایل‌های OEM با component صریح + URI «content» ساخته‌شده از
              DocumentsContract (com.android.externalstorage.documents) و mime
              vnd.android.document/directory — همان چیزی که خود سازندگان
              (سامسونگ My Files / شیائومی File Explorer / DocumentsUI) به
              رسمیت می‌شناسند.
           1) همان intent به‌صورت implicit (بدون component) برای دستگاه‌های
              استوک/پیکسل.
           2) queryIntentActivities برای پیدا کردن هر اپی که directory را
              می‌فهمد (مثل Files by Google).
           3) پیکر SAF سیستم (ACTION_OPEN_DOCUMENT + EXTRA_INITIAL_URI) که
              روی «هر» اندروید ۵+ وجود دارد و پوشهٔ درست را باز می‌کند.
           4) آخرین راه: کپی مسیر + پیام. */
        String dirPath = file.getAbsolutePath();
        String docId = toDocId(file);
        if (docId == null || docId.length() == 0) {
            docId = "primary:";
        }
        Uri dirUri;
        try {
            dirUri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", docId);
        } catch (Throwable t) {
            dirUri = null;
        }

        String[][] oemFm = {
                /* Samsung My Files */
                {"com.sec.android.app.myfiles", "com.sec.android.app.myfiles.files.FilesActivity"},
                {"com.sec.android.app.myfiles", "com.sec.android.app.myfiles.common.MainActivity"},
                /* Xiaomi / MIUI File Explorer (global + Redmi/Poco variants) */
                {"com.mi.android.globalFileexplorer", "com.mi.android.globalFileexplorer.FileExplorerTabActivity"},
                {"com.android.fileexplorer", "com.android.fileexplorer.FileExplorerTabActivity"},
                /* Huawei Files (HiDisk) */
                {"com.huawei.hidisk", "com.huawei.hidisk.fileprovider.FileExplorerTabActivity"},
                {"com.huawei.hidisk", "com.huawei.hidisk.MainActivity"},
                /* OnePlus File Manager */
                {"com.oneplus.filemanager", "com.oneplus.filemanager.FileManagerActivity"},
                /* OPPO / Realme Files */
                {"com.coloros.files", "com.coloros.files.MainActivity"},
                {"com.android.filemanager", "com.android.filemanager.FileManagerActivity"},
                /* Google Files (Pixel, stock, Files by Google) */
                {"com.google.android.documentsui", "com.android.documentsui.files.FilesActivity"},
                {"com.google.android.documentsui", "com.android.documentsui.FilesActivity"},
                /* AOSP DocumentsUI */
                {"com.android.documentsui", "com.android.documentsui.files.FilesActivity"},
                {"com.android.documentsui", "com.android.documentsui.FilesActivity"}
        };

        /* 0) explicit OEM components with a content:// directory URI */
        if (dirUri != null) {
            for (String[] target : oemFm) {
                try {
                    Intent it = new Intent(Intent.ACTION_VIEW);
                    it.setClassName(target[0], target[1]);
                    it.setDataAndType(dirUri, "vnd.android.document/directory");
                    /* v1.16: no FLAG_ACTIVITY_NEW_TASK — launching from an
                       Activity opens the file manager in OUR task, so the
                       back button returns to the reader instead of leaving
                       the app entirely. */
                    it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    activity.startActivity(it);
                    return;
                } catch (Throwable ignore) {
                }
            }
            /* 1) implicit ACTION_VIEW on the directory document URI — this is
                  the stock-Android path (DocumentsUI FilesActivity) */
            try {
                Intent it = new Intent(Intent.ACTION_VIEW);
                it.setDataAndType(dirUri, "vnd.android.document/directory");
                it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                activity.startActivity(it);
                return;
            } catch (Throwable ignore) {
            }
            /* 2) any installed app that can view a directory document */
            try {
                Intent probe = new Intent(Intent.ACTION_VIEW);
                probe.setDataAndType(dirUri, "vnd.android.document/directory");
                android.content.pm.PackageManager pm = activity.getPackageManager();
                java.util.List<android.content.pm.ResolveInfo> ris =
                        Build.VERSION.SDK_INT >= 33 ? pm.queryIntentActivities(probe, android.content.pm.PackageManager.ResolveInfoFlags.of(0))
                                : pm.queryIntentActivities(probe, 0);
                if (ris != null && !ris.isEmpty()) {
                    for (android.content.pm.ResolveInfo ri : ris) {
                        try {
                            Intent it = new Intent(Intent.ACTION_VIEW);
                            it.setPackage(ri.activityInfo.packageName);
                            it.setDataAndType(dirUri, "vnd.android.document/directory");
                            it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            activity.startActivity(it);
                            return;
                        } catch (Throwable ignore) {
                        }
                    }
                }
            } catch (Throwable ignore) {
            }
        }

        /* 3) system SAF picker opened AT the folder — works on EVERY Android
              5+ device because DocumentsUI ships with the platform.
              On Android 8+ EXTRA_INITIAL_URI opens it directly in our folder. */
        try {
            Intent it;
            if (Build.VERSION.SDK_INT >= 26 && dirUri != null) {
                it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                it.putExtra(DocumentsContract.EXTRA_INITIAL_URI, dirUri);
            } else {
                it = new Intent(Intent.ACTION_GET_CONTENT);
            }
            it.setType("*/*");
            it.addCategory(Intent.CATEGORY_OPENABLE);
            activity.startActivity(it);
            toast(activity, "پوشهٔ فایل در انتخابگر سیستم باز شد");
            return;
        } catch (Throwable ignore) {
        }

        /* 4) final fallback: copy path + tell user where to find the file */
        copy(activity, str);
        toast(activity, "مدیر فایل سیستم پیدا نشد — مسیر کپی شد ✓\n" + dirPath);
    }

    public static void renameDialog(final Activity activity, final File file, final Done done) {
        if (file == null || !file.exists()) {
            toast(activity, "فایل روی حافظه پیدا نشد");
            return;
        }
        String name = file.getName();
        int lastIndexOf = name.lastIndexOf(46);
        if (lastIndexOf > 0) {
            name = name.substring(0, lastIndexOf);
        }
        final EditText editText = new EditText(activity);
        editText.setInputType(1);
        editText.setText(name);
        editText.setSelection(name.length());
        editText.setTextColor(-1511688);
        editText.setHintTextColor(-12167046);
        FrameLayout frameLayout = new FrameLayout(activity);
        int dp = dp(activity, 20);
        frameLayout.setPadding(dp, dp(activity, 6), dp, 0);
        frameLayout.addView(editText, new FrameLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(activity).setTitle("تغییر نام فایل").setMessage("نام جدید را بنویس (پسوند " + extOf(file.getName()) + " خودکار حفظ می\u200cشود):").setView(frameLayout).setPositiveButton("ثبت", new DialogInterface.OnClickListener() { // from class: com.pdfkhwan.app.FileOps.2
            @Override // android.content.DialogInterface.OnClickListener
            public void onClick(DialogInterface dialogInterface, int i) {
                FileOps.applyRename(activity, file, editText.getText().toString().trim(), done);
            }
        }).setNegativeButton("انصراف", (DialogInterface.OnClickListener) null).show();
    }

    public static void requestWrite(Activity activity) {
        if (Build.VERSION.SDK_INT < 23 || Build.VERSION.SDK_INT >= 30) {
            return;
        }
        activity.requestPermissions(new String[]{"android.permission.WRITE_EXTERNAL_STORAGE"}, REQ_WRITE);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void scan(Context context, String[] strArr) {
        try {
            MediaScannerConnection.scanFile(context, strArr, null, null);
        } catch (Throwable unused) {
        }
    }

    private static void showDirPicker(final Activity activity, final File file, File file2, final Done done) {
        final File[] fileArr = {file2};
        final TextView textView = new TextView(activity);
        textView.setTextColor(-7495492);
        textView.setTextSize(11.0f);
        textView.setSingleLine(true);
        int dp = dp(activity, 14);
        textView.setPadding(dp, dp(activity, 8), dp, dp(activity, 8));
        ListView listView = new ListView(activity);
        final ArrayAdapter<String> arrayAdapter = new ArrayAdapter<String>(activity, android.R.layout.simple_list_item_1) { // from class: com.pdfkhwan.app.FileOps.3
            @Override // android.widget.ArrayAdapter, android.widget.Adapter
            public View getView(int i, View view, ViewGroup viewGroup) {
                View view2 = super.getView(i, view, viewGroup);
                ((TextView) view2).setTextColor(-1511688);
                return view2;
            }
        };
        listView.setAdapter((ListAdapter) arrayAdapter);
        listView.setDivider(null);
        final Runnable[] runnableArr = {new Runnable() { // from class: com.pdfkhwan.app.FileOps.4
            @Override // java.lang.Runnable
            public void run() {
                textView.setText(fileArr[0].getAbsolutePath());
                arrayAdapter.clear();
                File[] listFiles = fileArr[0].listFiles();
                ArrayList arrayList = new ArrayList();
                if (listFiles != null) {
                    Arrays.sort(listFiles);
                    for (File file3 : listFiles) {
                        if (file3.isDirectory() && !file3.getName().startsWith(".")) {
                            arrayList.add(file3.getName());
                        }
                    }
                }
                Collections.sort(arrayList);
                if (fileArr[0].getParentFile() != null) {
                    arrayAdapter.add("↑  پوشهٔ بالاتر");
                }
                arrayAdapter.addAll(arrayList);
                if (arrayList.isEmpty()) {
                    arrayAdapter.add("— پوشهٔ خالی —");
                }
                arrayAdapter.notifyDataSetChanged();
            }
        }};
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() { // from class: com.pdfkhwan.app.FileOps.5
            @Override // android.widget.AdapterView.OnItemClickListener
            public void onItemClick(AdapterView<?> adapterView, View view, int i, long j) {
                String str = (String) arrayAdapter.getItem(i);
                if (str == null) {
                    return;
                }
                if (str.startsWith("↑") || str.startsWith("—")) {
                    File parentFile = fileArr[0].getParentFile();
                    if (parentFile != null && !str.startsWith("—")) {
                        fileArr[0] = parentFile;
                    }
                } else {
                    File file3 = new File(fileArr[0], str);
                    if (file3.isDirectory()) {
                        fileArr[0] = file3;
                    }
                }
                runnableArr[0].run();
            }
        });
        runnableArr[0].run();
        new AlertDialog.Builder(activity).setTitle("انتقال «" + file.getName() + "» به پوشه…").setView(listView).setPositiveButton("انتقال به اینجا", new DialogInterface.OnClickListener() { // from class: com.pdfkhwan.app.FileOps.6
            @Override // android.content.DialogInterface.OnClickListener
            public void onClick(DialogInterface dialogInterface, int i) {
                File file3 = new File(fileArr[0], file.getName());
                if (file3.getAbsolutePath().equals(file.getAbsolutePath())) {
                    FileOps.toast(activity, "همین پوشهٔ فعلی فایل است");
                    return;
                }
                if (file3.exists()) {
                    FileOps.toast(activity, "در پوشهٔ مقصد فایلی با همین نام هست");
                    return;
                }
                /* v1.28 FIX: انتقال بین حافظه‌ها = کپی بزرگ روی نخ UI → ANR؛
                   حالا پس‌زمینه با پیام انتظار */
                FileOps.toast(activity, "در حال انتقال…");
                FileOps.mutateAsync(activity, file, file3, new Runnable() {
                    @Override public void run() {
                        FileOps.scan(activity, new String[]{file3.getAbsolutePath(), file.getParentFile().getAbsolutePath(), fileArr[0].getAbsolutePath()});
                        FileOps.toast(activity, "فایل منتقل شد ✓");
                        Done done2 = done;
                        if (done2 != null) {
                            done2.on(file3.getAbsolutePath(), file3.getName());
                        }
                    }
                }, new Runnable() {
                    @Override public void run() { FileOps.toast(activity, "انتقال ممکن نشد"); }
                });
            }
        }).setNegativeButton("انصراف", (DialogInterface.OnClickListener) null).show();
    }

    public static void showPath(final Activity activity, final File file) {
        if (file == null || !file.exists()) {
            toast(activity, "فایل روی حافظه پیدا نشد");
        } else {
            final String absolutePath = file.getAbsolutePath();
            new AlertDialog.Builder(activity).setTitle("آدرس فایل").setMessage(absolutePath).setPositiveButton("کپی مسیر", new DialogInterface.OnClickListener() { // from class: com.pdfkhwan.app.FileOps.7
                @Override // android.content.DialogInterface.OnClickListener
                public void onClick(DialogInterface dialogInterface, int i) {
                    FileOps.copy(activity, absolutePath);
                    FileOps.toast(activity, "مسیر کپی شد ✓");
                }
            }).setNeutralButton("باز کردن پوشه در فایل\u200cمنیجر", new DialogInterface.OnClickListener() { // from class: com.pdfkhwan.app.FileOps.8
                @Override // android.content.DialogInterface.OnClickListener
                public void onClick(DialogInterface dialogInterface, int i) {
                    FileOps.openFolder(activity, file.getParentFile(), absolutePath);
                }
            }).setNegativeButton("بستن", (DialogInterface.OnClickListener) null).show();
        }
    }

    private static String toDocId(File file) {
        String replaceFirst = file.getAbsolutePath().replaceFirst("/+$", "");
        String replaceFirst2 = Environment.getExternalStorageDirectory().getAbsolutePath().replaceFirst("/+$", "");
        if (replaceFirst.equals(replaceFirst2)) {
            return "primary:";
        }
        if (replaceFirst.startsWith(String.valueOf(replaceFirst2) + "/")) {
            return "primary:" + replaceFirst.substring(replaceFirst2.length() + 1);
        }
        if (replaceFirst.startsWith("/sdcard/")) {
            return "primary:" + replaceFirst.substring(8);
        }
        if (!replaceFirst.startsWith("/storage/")) {
            return null;
        }
        String substring = replaceFirst.substring(9);
        int indexOf = substring.indexOf(47);
        if (indexOf <= 0) {
            return String.valueOf(substring) + ":";
        }
        return String.valueOf(substring.substring(0, indexOf)) + ":" + substring.substring(indexOf + 1);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void toast(Context context, String str) {
        Toast.makeText(context, str, 0).show();
    }
}
