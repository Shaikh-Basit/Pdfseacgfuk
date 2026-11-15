package com.pdfkhwan.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.HashMap;
import java.util.Map;

/* loaded from: classes.dex */
public class ShareProvider extends ContentProvider {
    private static final String AUTH = "com.pdfkhwan.app.share";
    /* v1.16: concurrent map — entries are inserted from the background
       staging thread while the system reads them over a binder thread;
       the old plain HashMap was cross-thread unsafe. */
    private static final Map<String, File> FILES = new java.util.concurrent.ConcurrentHashMap<>();

    public static Uri put(File file) {
        FILES.put(file.getName(), file);
        /* v1.29 FIX: build the Uri with appendPath (encodes special
           characters) — string concatenation broke on filenames containing
           '#' (the fragment separator truncated the path) and the receiver
           hit FileNotFoundException in openFile(). getLastPathSegment()
           decodes, so lookups still match the map key. */
        return new Uri.Builder().scheme("content").authority(AUTH)
                .appendPath(file.getName()).build();
    }

    @Override // android.content.ContentProvider
    public int delete(Uri uri, String str, String[] strArr) {
        return 0;
    }

    @Override // android.content.ContentProvider
    public String getType(Uri uri) {
        /* v1.15: correct MIME by extension so shared .pptx files are not
           labeled as PDF by the receiving app.
           v1.29 FIX: the full ppt-family map — .ppsx/.ppsm/.pptm/.potx/.potm
           used to fall through to application/pdf and Gmail/Drive labeled
           those shares as PDFs. */
        String name = uri == null ? "" : String.valueOf(uri.getLastPathSegment());
        String lower = name.toLowerCase(java.util.Locale.US);
        if (lower.endsWith(".pptx")) {
            return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        }
        if (lower.endsWith(".ppsx")) {
            return "application/vnd.openxmlformats-officedocument.presentationml.slideshow";
        }
        if (lower.endsWith(".ppsm")) {
            return "application/vnd.ms-powerpoint.slideshow.macroEnabled.12";
        }
        if (lower.endsWith(".pptm")) {
            return "application/vnd.ms-powerpoint.presentation.macroEnabled.12";
        }
        if (lower.endsWith(".potx")) {
            return "application/vnd.openxmlformats-officedocument.presentationml.template";
        }
        if (lower.endsWith(".potm")) {
            return "application/vnd.ms-powerpoint.template.macroEnabled.12";
        }
        if (lower.endsWith(".ppt")) {
            return "application/vnd.ms-powerpoint";
        }
        if (lower.endsWith(".pps")) {
            /* v7: .pps is the legacy PowerPoint slideshow MIME */
            return "application/vnd.ms-powerpoint.slideshow";
        }
        return "application/pdf";
    }

    @Override // android.content.ContentProvider
    public Uri insert(Uri uri, ContentValues contentValues) {
        return null;
    }

    @Override // android.content.ContentProvider
    public boolean onCreate() {
        return true;
    }

    @Override // android.content.ContentProvider
    public ParcelFileDescriptor openFile(Uri uri, String str) throws FileNotFoundException {
        File file = FILES.get(uri.getLastPathSegment());
        if (file == null || !file.exists()) {
            throw new FileNotFoundException(uri.toString());
        }
        return ParcelFileDescriptor.open(file, 268435456);
    }

    @Override // android.content.ContentProvider
    public Cursor query(Uri uri, String[] strArr, String str, String[] strArr2, String str2) {
        File file = FILES.get(uri.getLastPathSegment());
        if (strArr == null) {
            strArr = new String[]{"_display_name", "_size"};
        }
        MatrixCursor matrixCursor = new MatrixCursor(strArr);
        if (file != null) {
            Object[] objArr = new Object[strArr.length];
            for (int i = 0; i < strArr.length; i++) {
                if ("_display_name".equals(strArr[i])) {
                    objArr[i] = file.getName();
                } else if ("_size".equals(strArr[i])) {
                    objArr[i] = Long.valueOf(file.length());
                } else {
                    objArr[i] = null;
                }
            }
            matrixCursor.addRow(objArr);
        }
        return matrixCursor;
    }

    @Override // android.content.ContentProvider
    public int update(Uri uri, ContentValues contentValues, String str, String[] strArr) {
        return 0;
    }
}
