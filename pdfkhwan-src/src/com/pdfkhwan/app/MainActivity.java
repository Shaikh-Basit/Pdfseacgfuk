package com.pdfkhwan.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Parcelable;
import android.os.Process;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import com.pdfkhwan.app.FileOps;
import com.pdfkhwan.app.LibThumbs;
import com.pdfkhwan.app.LibraryScanner;
import com.pdfkhwan.ppt.PptDoc;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.Collator;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/* loaded from: classes.dex */
public class MainActivity extends Activity {
    private static final int REQ_PERM = 7;
    private View btnGrant;
    private TextView btnSort;
    private TextView emptyHint;
    private TextView filterAll;
    private TextView filterPdf;
    private TextView filterPpt;
    private View filterRow;
    private LibAdapter libAdapter;
    private TextView libCount;
    private EditText libSearch;
    private ListView list;
    private View permBanner;
    private TextView permText;
    private RecentsAdapter recentAdapter;
    private RecentStore store;
    private TextView tabLib;
    private TextView tabRecent;
    private boolean libMode = true;
    private boolean hasAccess = false;
    private boolean scanning = false;
    /* v7: scan sequence counter — a new scan supersedes the previous one's
       stale callback (which set scanning=false mid-scan) */
    private int scanSeq = 0;
    private int filterKind = 0;
    private int sortMode = 2;
    /* v1.15: multi-select state for batch operations (delete/share/open) */
    private boolean selMode = false;
    private final Set<String> selPaths = new HashSet<>();
    private View selBar;
    private TextView selCount;
    private final List<LibraryScanner.Item> libAll = new ArrayList();
    private final List<LibraryScanner.Item> libShown = new ArrayList();
    private final List<JSONObject> recentItems = new ArrayList();

    private class LibAdapter extends BaseAdapter implements LibThumbs.Cb {
        private final SimpleDateFormat df;

        private LibAdapter() {
            this.df = new SimpleDateFormat("yyyy/MM/dd", Locale.US);
        }

        /* synthetic */ LibAdapter(MainActivity mainActivity, LibAdapter libAdapter) {
            this();
        }

        @Override // android.widget.Adapter
        public int getCount() {
            return MainActivity.this.libShown.size();
        }

        @Override // android.widget.Adapter
        public Object getItem(int i) {
            return MainActivity.this.libShown.get(i);
        }

        @Override // android.widget.Adapter
        public long getItemId(int i) {
            return i;
        }

        @Override // android.widget.Adapter
        public View getView(final int i, View view, ViewGroup viewGroup) {
            if (view == null) {
                view = MainActivity.this.getLayoutInflater().inflate(R.layout.item_lib, viewGroup, false);
            }
            final LibraryScanner.Item item = (LibraryScanner.Item) MainActivity.this.libShown.get(i);
            TextView title = (TextView) view.findViewById(R.id.lTitle);
            TextView meta = (TextView) view.findViewById(R.id.lMeta);
            TextView path = (TextView) view.findViewById(R.id.lPath);
            ImageView thumb = (ImageView) view.findViewById(R.id.lThumb);
            View menu = view.findViewById(R.id.lMenu);
            CheckBox check = (CheckBox) view.findViewById(R.id.lCheck);
            title.setText(item.name);
            String parentName;
            try {
                File parentFile = new File(item.path).getParentFile();
                parentName = parentFile != null ? parentFile.getName() : "";
            } catch (Exception unused) {
                parentName = "";
            }
            meta.setText(Fa.bytes(item.size) + "  ·  " + Fa.d(this.df.format(new Date(item.mtime))));
            path.setText(parentName);
            /* v1.15: selection mode UI — checkbox visible, row tinted */
            boolean picked = MainActivity.this.selMode && MainActivity.this.selPaths.contains(item.path);
            if (check != null) {
                check.setVisibility(MainActivity.this.selMode ? View.VISIBLE : View.GONE);
                check.setChecked(picked);
            }
            if (MainActivity.this.selMode) {
                view.setBackgroundColor(picked ? 0x1E2DD4BF : 0x00000000);
                if (menu != null) {
                    menu.setVisibility(View.GONE);
                }
            } else {
                view.setBackgroundResource(R.drawable.bg_bar);
                if (menu != null) {
                    menu.setVisibility(View.VISIBLE);
                }
            }
            Bitmap bm = item.kind == 1 ? null : LibThumbs.cached(item.path);
            if (bm != null && !bm.isRecycled()) {
                thumb.setImageBitmap(bm);
            } else if (item.kind == 1) {
                thumb.setImageResource(R.drawable.ic_ppt);
            } else {
                thumb.setImageResource(R.drawable.ic_doc);
                LibThumbs.get(MainActivity.this, item.path, item.size, item.mtime, this);
            }
            view.setOnClickListener(new View.OnClickListener() { // from class: com.pdfkhwan.app.MainActivity.LibAdapter.1
                @Override // android.view.View.OnClickListener
                public void onClick(View view2) {
                    if (MainActivity.this.selMode) {
                        MainActivity.this.toggleSel(item.path);
                    } else {
                        MainActivity.this.openLibItem(i);
                    }
                }
            });
            view.setOnLongClickListener(new View.OnLongClickListener() { // from class: com.pdfkhwan.app.MainActivity.LibAdapter.2
                @Override // android.view.View.OnLongClickListener
                public boolean onLongClick(View view2) {
                    /* v1.15: long-press starts multi-select instead of the
                       single-file menu (the ⋮ button still opens that menu). */
                    MainActivity.this.toggleSel(item.path);
                    return true;
                }
            });
            menu.setOnClickListener(new View.OnClickListener() { // from class: com.pdfkhwan.app.MainActivity.LibAdapter.3
                @Override // android.view.View.OnClickListener
                public void onClick(View view2) {
                    if (!MainActivity.this.selMode) {
                        MainActivity.this.showFileMenu(i, view2);
                    }
                }
            });
            return view;
        }

        @Override // com.pdfkhwan.app.LibThumbs.Cb
        public void onThumb(String str, Bitmap bitmap) {
            MainActivity.this.runOnUiThread(new Runnable() { // from class: com.pdfkhwan.app.MainActivity.LibAdapter.4
                @Override // java.lang.Runnable
                public void run() {
                    if (MainActivity.this.libMode && MainActivity.this.list.getAdapter() == MainActivity.this.libAdapter) {
                        MainActivity.this.libAdapter.notifyDataSetChanged();
                    }
                }
            });
        }
    }

    private class RecentsAdapter extends BaseAdapter {
        private RecentsAdapter() {
        }

        /* synthetic */ RecentsAdapter(MainActivity mainActivity, RecentsAdapter recentsAdapter) {
            this();
        }

        @Override // android.widget.Adapter
        public int getCount() {
            return MainActivity.this.recentItems.size();
        }

        @Override // android.widget.Adapter
        public Object getItem(int i) {
            return MainActivity.this.recentItems.get(i);
        }

        @Override // android.widget.Adapter
        public long getItemId(int i) {
            return i;
        }

        @Override // android.widget.Adapter
        public View getView(final int i, View view, ViewGroup viewGroup) {
            if (view == null) {
                view = MainActivity.this.getLayoutInflater().inflate(R.layout.item_recent, viewGroup, false);
            }
            JSONObject jSONObject = (JSONObject) MainActivity.this.recentItems.get(i);
            String optString = jSONObject.optString("name", "document.pdf");
            long optLong = jSONObject.optLong("size", 0L);
            int optInt = jSONObject.optInt("pages", 0);
            JSONObject position = MainActivity.this.store.getPosition(RecentStore.fileKey(optString, optLong));
            int optInt2 = position.optInt("page", 0);
            int optInt3 = position.optInt("pages", optInt);
            TextView textView = (TextView) view.findViewById(R.id.rTitle);
            TextView textView2 = (TextView) view.findViewById(R.id.rMeta);
            ProgressBar progressBar = (ProgressBar) view.findViewById(R.id.rProgress);
            View findViewById = view.findViewById(R.id.rDelete);
            textView.setText(optString);
            String str = "";
            if (optInt3 > 0) {
                str = "" + Fa.d(optInt3) + " صفحه · ";
            }
            String str2 = String.valueOf(str) + Fa.bytes(optLong);
            if (optInt2 > 0 && optInt3 > 0) {
                str2 = String.valueOf(str2) + "  ·  ادامه از صفحه " + Fa.d(optInt2);
            }
            textView2.setText(str2);
            if (optInt2 <= 0 || optInt3 <= 0) {
                progressBar.setVisibility(8);
            } else {
                progressBar.setVisibility(0);
                progressBar.setMax(100);
                progressBar.setProgress(Math.min(100, (int) ((optInt2 * 100.0d) / optInt3)));
            }
            view.setOnClickListener(new View.OnClickListener() { // from class: com.pdfkhwan.app.MainActivity.RecentsAdapter.1
                @Override // android.view.View.OnClickListener
                public void onClick(View view2) {
                    MainActivity.this.openRecent(i);
                }
            });
            findViewById.setOnClickListener(new View.OnClickListener() { // from class: com.pdfkhwan.app.MainActivity.RecentsAdapter.2
                @Override // android.view.View.OnClickListener
                public void onClick(View view2) {
                    MainActivity.this.confirmRemove(i);
                }
            });
            return view;
        }
    }

    /* ==========================================================================
       v1.15: multi-select / batch operations on the library list.
       Long-press an item to start selection; tap toggles; the batch bar
       offers select-all, share-many (ACTION_SEND_MULTIPLE) and delete-many.
       ========================================================================== */
    public void toggleSel(String path) {
        if (selPaths.contains(path)) {
            selPaths.remove(path);
        } else {
            selPaths.add(path);
        }
        selMode = !selPaths.isEmpty();
        updateSelUi();
        this.libAdapter.notifyDataSetChanged();
    }

    public void exitSelMode() {
        selPaths.clear();
        selMode = false;
        updateSelUi();
        if (this.libAdapter != null) {
            this.libAdapter.notifyDataSetChanged();
        }
    }

    private void updateSelUi() {
        if (selBar == null) {
            return;
        }
        selBar.setVisibility(selMode ? View.VISIBLE : View.GONE);
        if (selMode) {
            selCount.setText(Fa.d(selPaths.size()) + " فایل انتخاب شد");
        }
        /* hide the search row while picking so the list stays stable */
        if (this.filterRow != null) {
            this.filterRow.setVisibility((this.libMode && this.hasAccess && !selMode) ? View.VISIBLE : View.GONE);
        }
    }

    /* copy one library file into the cache share dir keeping its original
       name; returns the cached File or null. Collisions between two picked
       files with the same name get a numbered suffix before the extension. */
    private File stageForShare(File src, Set<String> usedNames) {
        try {
            File dir = new File(getCacheDir(), "share");
            if (!dir.exists()) {
                dir.mkdirs();
            }
            String name = src.getName();
            String base = name;
            String ext = "";
            int dot = name.lastIndexOf('.');
            if (dot > 0) {
                base = name.substring(0, dot);
                ext = name.substring(dot);
            }
            String candidate = name;
            int n = 2;
            while (usedNames.contains(candidate)) {
                candidate = base + " (" + n + ")" + ext;
                n++;
            }
            usedNames.add(candidate);
            File out = new File(dir, candidate);
            /* v1.29 FIX: the staged copy was REUSED when name+length happened
               to match — sharing a DIFFERENT file with the same name and size
               (template-derived PDFs/PPTXs) served the OLD bytes to the
               receiver. Recopy unconditionally; a stale cache file can never
               be served again. */
            if (out.exists()) out.delete();
            {
                FileInputStream in = new FileInputStream(src);
                try {
                    FileOutputStream fout = new FileOutputStream(out);
                    try {
                        byte[] buf = new byte[65536];
                        int r;
                        while ((r = in.read(buf)) > 0) {
                            fout.write(buf, 0, r);
                        }
                    } finally {
                        fout.close();
                    }
                } finally {
                    in.close();
                }
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    public void shareSelected() {
        if (selPaths.isEmpty()) {
            toast("اول فایل‌ها را انتخاب کن");
            return;
        }
        /* v1.28 FIX: کپیِ فایل‌های انتخابی روی نخ UI انجام می‌شد — با چند
           فایل بزرگ، اپ تا پیداکردن chooser منجمد می‌شد (ANR). حالا همهٔ
           stage/کپی‌ها روی نخ پس‌زمینه و فقط intent روی UI ساخته می‌شود. */
        toast("در حال آماده\u200cسازی اشتراک\u200cگذاری…");
        final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
        /* v1.29 FIX: snapshot the UI-owned collections ON THE UI THREAD —
           copying libShown / reading selPaths on the worker raced
           applyFilter()/exitSelMode() on the UI thread (ConcurrentModification
           → «اشتراک‌گذاری ممکن نشد» toast). */
        final List<LibraryScanner.Item> libSnapshot = new ArrayList<>(this.libShown);
        final Set<String> selSnapshot = new HashSet<>(this.selPaths);
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final ArrayList<Uri> uris = new ArrayList<>();
                    boolean anyPpt = false;
                    boolean anyPdf = false;
                    Set<String> usedNames = new HashSet<>();
                    for (LibraryScanner.Item it : libSnapshot) {
                        if (!selSnapshot.contains(it.path)) {
                            continue;
                        }
                        File f = new File(it.path);
                        if (!f.exists()) {
                            continue;
                        }
                        File staged = stageForShare(f, usedNames);
                        if (staged != null) {
                            uris.add(ShareProvider.put(staged));
                            if (it.kind == 1) {
                                anyPpt = true;
                            } else {
                                anyPdf = true;
                            }
                        }
                    }
                    final boolean fPpt = anyPpt, fPdf = anyPdf;
                    ui.post(new Runnable() {
                        @Override public void run() {
                            try {
                                if (uris.isEmpty()) {
                                    toast("فایل‌ها روی حافظه پیدا نشدند");
                                    return;
                                }
                                String mime = fPdf && fPpt ? "*/*"
                                        : fPpt ? "application/vnd.openxmlformats-officedocument.presentationml.presentation"
                                        : "application/pdf";
                                Intent intent = new Intent("android.intent.action.SEND_MULTIPLE");
                                intent.setType(mime);
                                intent.putParcelableArrayListExtra("android.intent.extra.STREAM", uris);
                                ClipData clip = ClipData.newRawUri("files", uris.get(0));
                                for (int i = 1; i < uris.size(); i++) {
                                    clip.addItem(new ClipData.Item(uris.get(i)));
                                }
                                intent.setClipData(clip);
                                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                startActivity(Intent.createChooser(intent, "اشتراک\u200cگذاری " + Fa.d(uris.size()) + " فایل"));
                            } catch (Throwable t) {
                                toast("اشتراک\u200cگذاری ممکن نشد");
                            }
                        }
                    });
                } catch (final Throwable t) {
                    ui.post(new Runnable() {
                        @Override public void run() { toast("اشتراک\u200cگذاری ممکن نشد"); }
                    });
                }
            }
        }, "pdfkhwan-share").start();
    }

    public void deleteSelected() {
        if (selPaths.isEmpty()) {
            toast("اول فایل‌ها را انتخاب کن");
            return;
        }
        final List<LibraryScanner.Item> victims = new ArrayList<>();
        for (LibraryScanner.Item it : this.libShown) {
            if (selPaths.contains(it.path)) {
                victims.add(it);
            }
        }
        if (victims.isEmpty()) {
            exitSelMode();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("حذف از دستگاه")
                .setMessage(Fa.d(victims.size()) + " فایل برای همیشه از حافظهٔ گوشی حذف می\u200cشوند.\nاین کار قابل بازگشت نیست.")
                .setPositiveButton("حذف کن", new DialogInterface.OnClickListener() { // from class: com.pdfkhwan.app.MainActivity.15
                    @Override // android.content.DialogInterface.OnClickListener
                    public void onClick(DialogInterface dialogInterface, int i) {
                        int ok = 0;
                        int fail = 0;
                        for (LibraryScanner.Item it : victims) {
                            try {
                                File f = new File(it.path);
                                if (f.exists() && f.delete()) {
                                    ok++;
                                    MainActivity.this.store.removeRecent(Uri.fromFile(f).toString());
                                } else {
                                    fail++;
                                }
                            } catch (Throwable unused) {
                                fail++;
                            }
                        }
                        FileOps.scan(MainActivity.this, new String[]{Environment.getExternalStorageDirectory().getAbsolutePath()});
                        MainActivity.this.exitSelMode();
                        MainActivity.this.reloadRecents();
                        MainActivity.this.startScan();
                        if (fail == 0) {
                            MainActivity.this.toast(Fa.d(ok) + " فایل از دستگاه حذف شد ✓");
                        } else {
                            MainActivity.this.toast(Fa.d(ok) + " حذف شد · " + Fa.d(fail) + " ممکن نشد — دسترسی «همهٔ فایل\u200cها» را بررسی کن");
                        }
                    }
                })
                .setNegativeButton("انصراف", (DialogInterface.OnClickListener) null)
                .show();
    }

    private void wireSelBar() {
        this.selBar = findViewById(R.id.selBar);
        this.selCount = (TextView) findViewById(R.id.selCount);
        findViewById(R.id.btnSelAll).setOnClickListener(new View.OnClickListener() { // from class: com.pdfkhwan.app.MainActivity.16
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                if (MainActivity.this.selPaths.size() == MainActivity.this.libShown.size() && !MainActivity.this.libShown.isEmpty()) {
                    MainActivity.this.selPaths.clear();
                    MainActivity.this.selMode = false;   /* v1.29 FIX: the deselect branch never cleared selMode — the app stayed stuck in selection mode with 0 selected */
                    MainActivity.this.updateSelUi();
                    MainActivity.this.libAdapter.notifyDataSetChanged();
                    return;
                }
                for (LibraryScanner.Item it : MainActivity.this.libShown) {
                    MainActivity.this.selPaths.add(it.path);
                }
                MainActivity.this.selMode = !MainActivity.this.selPaths.isEmpty();
                MainActivity.this.updateSelUi();
                MainActivity.this.libAdapter.notifyDataSetChanged();
            }
        });
        findViewById(R.id.btnSelShare).setOnClickListener(new View.OnClickListener() { // from class: com.pdfkhwan.app.MainActivity.17
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                MainActivity.this.shareSelected();
            }
        });
        findViewById(R.id.btnSelDelete).setOnClickListener(new View.OnClickListener() { // from class: com.pdfkhwan.app.MainActivity.18
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                if (FileOps.ensureWrite(MainActivity.this)) {
                    MainActivity.this.deleteSelected();
                }
            }
        });
        findViewById(R.id.btnSelClose).setOnClickListener(new View.OnClickListener() { // from class: com.pdfkhwan.app.MainActivity.19
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                MainActivity.this.exitSelMode();
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void applyFilter() {
        String lowerCase = this.libSearch.getText() == null ? "" : this.libSearch.getText().toString().trim().toLowerCase(Locale.US);
        this.libShown.clear();
        for (int i = 0; i < this.libAll.size(); i++) {
            LibraryScanner.Item item = this.libAll.get(i);
            if ((this.filterKind != 1 || item.kind != 1) && ((this.filterKind != 2 || item.kind == 1) && (lowerCase.length() == 0 || item.name.toLowerCase(Locale.US).contains(lowerCase)))) {
                this.libShown.add(item);
            }
        }
        applySort(this.libShown);
        this.libAdapter.notifyDataSetChanged();
        refreshLibraryUi();
    }

    private void applySort(List<LibraryScanner.Item> list) {
        final Collator collator = Collator.getInstance(new Locale("fa"));
        Collections.sort(list, new Comparator<LibraryScanner.Item>() { // from class: com.pdfkhwan.app.MainActivity.9
            @Override // java.util.Comparator
            public int compare(LibraryScanner.Item item, LibraryScanner.Item item2) {
                /* v1.13 FIX: the old decompiled comparator lost every
                   branch — every mode ended in the name collation, so
                   date/size/kind sorting was decorative only. */
                int r;
                switch (MainActivity.this.sortMode) {
                    case 0: // name (A → Y)
                        return collator.compare(item.name, item2.name);
                    case 1: // name (Y → A)
                        return collator.compare(item2.name, item.name);
                    case 2: // date — newest first
                        r = item.mtime < item2.mtime ? 1 : item.mtime > item2.mtime ? -1 : 0;
                        return r == 0 ? collator.compare(item.name, item2.name) : r;
                    case 3: // date — oldest first
                        r = item.mtime < item2.mtime ? -1 : item.mtime > item2.mtime ? 1 : 0;
                        return r == 0 ? collator.compare(item.name, item2.name) : r;
                    case 4: // size — largest first
                        r = item.size < item2.size ? 1 : item.size > item2.size ? -1 : 0;
                        return r == 0 ? collator.compare(item.name, item2.name) : r;
                    case 5: // size — smallest first
                        r = item.size < item2.size ? -1 : item.size > item2.size ? 1 : 0;
                        return r == 0 ? collator.compare(item.name, item2.name) : r;
                    case 6: // kind — PDF (0) before PowerPoint (1), then name
                        if (item.kind != item2.kind) {
                            return item.kind < item2.kind ? -1 : 1;
                        }
                        return collator.compare(item.name, item2.name);
                    default:
                        return collator.compare(item.name, item2.name);
                }
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void confirmRemove(int i) {
        /* v1.28 FIX: stale index guard (مثل openLibItem) */
        if (i < 0 || i >= this.recentItems.size()) {
            reloadRecents();
            return;
        }
        this.store.removeRecent(this.recentItems.get(i).optString("uri"));
        reloadRecents();
        toast("از فهرست اخیر حذف شد (خود فایل دست\u200cنخورده ماند)");
    }

    static boolean hasLibraryAccess(Activity activity) {
        return Build.VERSION.SDK_INT >= 30 ? Environment.isExternalStorageManager() : Build.VERSION.SDK_INT >= 23 ? activity.checkSelfPermission("android.permission.READ_EXTERNAL_STORAGE") == 0 : activity.checkPermission("android.permission.READ_EXTERNAL_STORAGE", Process.myPid(), Process.myUid()) == 0;
    }

    private void maybeShowCrashLog() {
        try {
            final String trim = CrashGuard.read(this).trim();
            if (trim.length() == 0) {
                return;
            }
            int min = Math.min(trim.length(), 1500);
            /* v7 FIX: showed the OLDEST 1500 chars (substring(0, min)) — the
               newest crash is what the user needs to see */
            String shown = trim.substring(trim.length() - min);
            new AlertDialog.Builder(this).setTitle("دفعهٔ قبل برنامه خطا داد").setMessage("لاگ خطا ذخیره شده:\n\n" + shown).setPositiveButton("ارسال/کپی لاگ", new DialogInterface.OnClickListener() { // from class: com.pdfkhwan.app.MainActivity.12
                @Override // android.content.DialogInterface.OnClickListener
                public void onClick(DialogInterface dialogInterface, int i) {
                    try {
                        Intent intent = new Intent("android.intent.action.SEND");
                        intent.setType("text/plain");
                        intent.putExtra("android.intent.extra.TEXT", trim);
                        intent.putExtra("android.intent.extra.SUBJECT", "PDFKhwan crash log");
                        MainActivity.this.startActivity(Intent.createChooser(intent, "ارسال لاگ"));
                    } catch (Exception unused) {
                        Toast.makeText(MainActivity.this, "ارسال ممکن نشد", 0).show();
                    }
                    CrashGuard.clear(MainActivity.this);
                }
            }).setNegativeButton("پاک کردن", new DialogInterface.OnClickListener() { // from class: com.pdfkhwan.app.MainActivity.13
                @Override // android.content.DialogInterface.OnClickListener
                public void onClick(DialogInterface dialogInterface, int i) {
                    CrashGuard.clear(MainActivity.this);
                }
            }).show();
        } catch (Throwable unused) {
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void newPptDialog() {
        final EditText editText = new EditText(this);
        editText.setText("ارائه جدید");
        editText.setSelection(editText.getText().length());
        new AlertDialog.Builder(this).setTitle("پاورپوینت جدید").setMessage("اسم ارائه را بنویس (بعداً در ویرایشگر می\u200cتوانی تغییرش دهی و ذخیره کنی)").setView(editText).setPositiveButton("ساخت", new DialogInterface.OnClickListener() { // from class: com.pdfkhwan.app.MainActivity.11
            @Override // android.content.DialogInterface.OnClickListener
            public void onClick(DialogInterface dialogInterface, int i) {
                String trim = editText.getText().toString().trim();
                if (trim.length() == 0) {
                    trim = "ارائه جدید";
                }
                Intent intent = new Intent(MainActivity.this, (Class<?>) PptEditorActivity.class);
                intent.putExtra("name", trim);
                MainActivity.this.startActivity(intent);
            }
        }).setNegativeButton("انصراف", (DialogInterface.OnClickListener) null).show();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onFileMutated(String str, String str2, String str3, long j) {
        int i;
        boolean found;
        String uri = Uri.fromFile(new File(str)).toString();
        String uri2 = Uri.fromFile(new File(str2)).toString();
        this.store.renameKey(RecentStore.fileKey(new File(str).getName(), j), RecentStore.fileKey(str3, j));
        JSONArray recents = this.store.getRecents();
        int i2 = 0;
        found = false;
        i = 0;
        while (true) {
            if (i2 < recents.length()) {
                JSONObject optJSONObject = recents.optJSONObject(i2);
                if (optJSONObject != null && uri.equals(optJSONObject.optString("uri"))) {
                    found = true;
                    i = optJSONObject.optInt("pages", 0);
                    break;
                }
                i2++;
            } else {
                break;
            }
        }
        /* v7 FIX: renaming/moving a file DELETED it from Recents — the new
           entry was re-added only when the stored pages>0, but PowerPoint
           entries always store pages=0, so they vanished. Re-add whenever
           the old entry existed. */
        if (found) {
            this.store.removeRecent(uri);
            this.store.addRecent(uri2, str3, j, i);
        }
        reloadRecents();
        startScan();
    }

    private void openDocument(String str, String str2) {
        Intent intent;
        String lowerCase = str2 == null ? "" : str2.toLowerCase();
        if (ReaderActivity.isPptFamily(lowerCase)) {
            Intent intent2 = new Intent(this, (Class<?>) PptEditorActivity.class);
            intent2.putExtra("name", str2);
            intent = intent2;
        } else {
            intent = new Intent(this, (Class<?>) ReaderActivity.class);
        }
        intent.putExtra("uri", str);
        startActivity(intent);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void openLibItem(int i) {
        /* v1.28 FIX: stale index crash — a scan/filter refresh could shrink
           libShown between bind and click (showFileMenu already had this
           guard; openLibItem didn't) */
        if (i < 0 || i >= this.libShown.size()) {
            refreshLibraryUi();
            return;
        }
        LibraryScanner.Item item = this.libShown.get(i);
        try {
            Uri fromFile = Uri.fromFile(new File(item.path));
            this.store.addRecent(fromFile.toString(), item.name, item.size, 0);
            openDocument(fromFile.toString(), item.name);
        } catch (Throwable unused) {
            toast("باز کردن فایل ممکن نشد");
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void openRecent(final int i) {
        /* v1.28 FIX: stale index guard (مثل openLibItem) */
        if (i < 0 || i >= this.recentItems.size()) {
            reloadRecents();
            return;
        }
        final JSONObject jSONObject = (JSONObject) this.recentItems.get(i);
        final String optString = jSONObject.optString("uri");
        /* v1.16 FIX: the availability check (openInputStream) used to run on
           the UI thread — a slow content provider froze the list. It now
           runs on a worker thread and opens the document on the UI thread
           only when the file is actually reachable. */
        new Thread(new Runnable() { // from class: com.pdfkhwan.app.MainActivity.20
            @Override // java.lang.Runnable
            public void run() {
                boolean ok = false;
                try {
                    java.io.InputStream is = getContentResolver().openInputStream(Uri.parse(optString));
                    if (is != null) {
                        try {
                            is.close();
                        } catch (Throwable ignored) {
                        }
                        ok = true;
                    }
                } catch (Exception unused) {
                }
                final boolean fileOk = ok;
                runOnUiThread(new Runnable() {
                    @Override // java.lang.Runnable
                    public void run() {
                        if (fileOk) {
                            MainActivity.this.openDocument(optString, jSONObject.optString("name", ""));
                        } else {
                            Toast.makeText(MainActivity.this, "فایل در دسترس نیست — دوباره انتخابش کن", 1).show();
                        }
                    }
                });
            }
        }, "recent-check").start();
    }

    private void openUri(Uri uri) {
        String[] queryMeta = queryMeta(uri);
        this.store.addRecent(uri.toString(), queryMeta[0], Long.parseLong(queryMeta[1]), 0);
        openDocument(uri.toString(), queryMeta[0]);
    }

    private String[] queryMeta(Uri uri) {
        if ("file".equals(uri.getScheme())) {
            /* v1.29 FIX: an OPAQUE file: URI (file:x, no path) made
               new File((String)null) throw NPE — remotely crashable via a
               plain ACTION_VIEW share from any app. */
            String p = uri.getPath();
            if (p == null) {
                return new String[]{"document.pdf", "0"};
            }
            File file = new File(p);
            return new String[]{file.getName(), String.valueOf(file.length())};
        }
        String str = "document.pdf";
        long j = 0;
        try {
            Cursor query = getContentResolver().query(uri, null, null, null, null);
            if (query != null) {
                try {
                    int columnIndex = query.getColumnIndex("_display_name");
                    int columnIndex2 = query.getColumnIndex("_size");
                    if (query.moveToFirst()) {
                        if (columnIndex >= 0 && query.getString(columnIndex) != null) {
                            str = query.getString(columnIndex);
                        }
                        if (columnIndex2 >= 0 && !query.isNull(columnIndex2)) {
                            j = query.getLong(columnIndex2);
                        }
                    }
                    query.close();
                } catch (Throwable th) {
                    query.close();
                    throw th;
                }
            }
        } catch (Exception unused) {
        }
        return new String[]{str, String.valueOf(j)};
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void refreshAccess() {
        this.hasAccess = hasLibraryAccess(this);
    }

    private void refreshLibraryUi() {
        String str;
        boolean z = this.libMode;
        if (!z) {
            this.emptyHint.setVisibility(this.recentItems.isEmpty() ? 0 : 8);
            return;
        }
        this.libCount.setVisibility((z && this.hasAccess && !this.libAll.isEmpty()) ? 0 : 8);
        if (this.libShown.isEmpty()) {
            this.emptyHint.setVisibility(0);
            if (!this.hasAccess) {
                this.emptyHint.setText("دسترسی به حافظه داده نشده.\nبا دکمهٔ «دادن دسترسی» همهٔ PDFها و پاورپوینت\u200cهای گوشی را اینجا ببین.");
            } else if (this.scanning) {
                this.emptyHint.setText("در حال جستجوی همهٔ فایل\u200cهای PDF و پاورپوینت گوشی…");
            } else if (this.libAll.isEmpty()) {
                this.emptyHint.setText("هیچ فایل PDF یا پاورپوینتی روی حافظهٔ گوشی پیدا نشد.\n\nبا دکمهٔ «＋ پاورپوینت» می\u200cتوانی یک ارائهٔ جدید بسازی.");
            } else {
                this.emptyHint.setText("با این فیلترها هیچ فایلی پیدا نشد.\n\nفیلتر نوع (همه / PDF / پاورپوینت) یا متن جستجو را عوض کن.");
            }
        } else {
            this.emptyHint.setVisibility(8);
        }
        this.libCount.setVisibility((this.libMode && this.hasAccess && !this.libAll.isEmpty()) ? 0 : 8);
        if (this.libAll.isEmpty()) {
            return;
        }
        TextView textView = this.libCount;
        if (this.hasAccess) {
            str = "پیدا شده: " + Fa.d(this.libShown.size()) + " از " + Fa.d(this.libAll.size());
        } else {
            str = "";
        }
        textView.setText(str);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void reloadRecents() {
        this.recentItems.clear();
        JSONArray recents = this.store.getRecents();
        for (int i = 0; i < recents.length(); i++) {
            JSONObject optJSONObject = recents.optJSONObject(i);
            if (optJSONObject != null) {
                this.recentItems.add(optJSONObject);
            }
        }
        if (!this.libMode) {
            this.emptyHint.setVisibility(this.recentItems.isEmpty() ? 0 : 8);
        }
        this.recentAdapter.notifyDataSetChanged();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void requestAccess() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                try {
                    startActivity(new Intent("android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION", Uri.parse("package:" + getPackageName())));
                } catch (Exception unused) {
                    startActivity(new Intent("android.settings.MANAGE_ALL_FILES_ACCESS_PERMISSION"));
                }
                toast("در صفحهٔ تنظیمات، گزینهٔ «اجازهٔ دسترسی به همهٔ فایل\u200cها» را روشن کن");
                return;
            }
            if (Build.VERSION.SDK_INT >= 23) {
                if (Build.VERSION.SDK_INT < 30) {
                    requestPermissions(new String[]{"android.permission.READ_EXTERNAL_STORAGE", "android.permission.WRITE_EXTERNAL_STORAGE"}, REQ_PERM);
                } else {
                    requestPermissions(new String[]{"android.permission.READ_EXTERNAL_STORAGE"}, REQ_PERM);
                }
            }
        } catch (Exception unused2) {
            toast("صفحهٔ تنظیمات باز نشد — دسترسی فایل\u200cها را دستی فعال کن");
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setFilterKind(int i) {
        this.filterKind = i;
        this.store.setInt("filterKind", i);
        styleFilters();
        applyFilter();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setMode(boolean z) {
        this.libMode = z;
        if (!z) {
            exitSelMode(); /* v1.15: leave selection when switching tabs */
        }
        styleTab(this.tabLib, z);
        styleTab(this.tabRecent, !z);
        this.libCount.setVisibility((z && this.hasAccess && !this.libAll.isEmpty()) ? 0 : 8);
        updateBanner();
        this.list.setAdapter(z ? this.libAdapter : this.recentAdapter);
        if (z && this.hasAccess) {
            startScan();
        }
        refreshLibraryUi();
        reloadRecents();
        if (z) {
            return;
        }
        this.emptyHint.setVisibility(this.recentItems.isEmpty() ? 0 : 8);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void showFileMenu(final int i, View view) {
        if (i < 0 || i >= this.libShown.size()) {
            return;
        }
        final File file = new File(this.libShown.get(i).path);
        if (!file.exists()) {
            toast("فایل روی حافظه پیدا نشد — اسکن دوباره را بزن");
            return;
        }
        PopupMenu popupMenu = new PopupMenu(this, view);
        popupMenu.getMenu().add(0, 1, 0, "باز کردن");
        popupMenu.getMenu().add(0, 2, 0, "تغییر نام");
        popupMenu.getMenu().add(0, 3, 0, "انتقال به پوشهٔ دیگر");
        popupMenu.getMenu().add(0, 4, 0, "حذف از دستگاه");
        popupMenu.getMenu().add(0, 5, 0, "نمایش آدرس فایل");
        popupMenu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() { // from class: com.pdfkhwan.app.MainActivity.14
            @Override // android.widget.PopupMenu.OnMenuItemClickListener
            public boolean onMenuItemClick(MenuItem menuItem) {
                int itemId = menuItem.getItemId();
                if (itemId == 1) {
                    MainActivity.this.openLibItem(i);
                    return true;
                }
                if (itemId == 2) {
                    if (FileOps.ensureWrite(MainActivity.this)) {
                        /* v1.16 FIX: capture the size BEFORE the rename — the
                           old code read file.length() AFTER the file was already
                           renamed away (length()=0), so the reading-position /
                           bookmark key migration silently matched NOTHING and
                           the user's progress was lost on every rename. */
                        final long sizeBefore = file.length();
                        MainActivity mainActivity = MainActivity.this;
                        final File file2 = file;
                        FileOps.renameDialog(mainActivity, file2, new FileOps.Done() { // from class: com.pdfkhwan.app.MainActivity.14.1
                            @Override // com.pdfkhwan.app.FileOps.Done
                            public void on(String str, String str2) {
                                MainActivity.this.onFileMutated(file2.getAbsolutePath(), str, str2, sizeBefore);
                            }
                        });
                    }
                    return true;
                }
                if (itemId == 3) {
                    if (FileOps.ensureWrite(MainActivity.this)) {
                        /* v1.16: same size-before-move capture as rename above. */
                        final long sizeBefore = file.length();
                        MainActivity mainActivity2 = MainActivity.this;
                        final File file3 = file;
                        FileOps.moveDialog(mainActivity2, file3, new FileOps.Done() { // from class: com.pdfkhwan.app.MainActivity.14.2
                            @Override // com.pdfkhwan.app.FileOps.Done
                            public void on(String str, String str2) {
                                MainActivity.this.onFileMutated(file3.getAbsolutePath(), str, str2, sizeBefore);
                            }
                        });
                    }
                    return true;
                }
                if (itemId != 4) {
                    if (itemId != 5) {
                        return false;
                    }
                    FileOps.showPath(MainActivity.this, file);
                    return true;
                }
                if (FileOps.ensureWrite(MainActivity.this)) {
                    MainActivity mainActivity3 = MainActivity.this;
                    final File file4 = file;
                    FileOps.deleteWithConfirm(mainActivity3, file4, new Runnable() { // from class: com.pdfkhwan.app.MainActivity.14.3
                        @Override // java.lang.Runnable
                        public void run() {
                            MainActivity.this.store.removeRecent(Uri.fromFile(file4).toString());
                            MainActivity.this.reloadRecents();
                            MainActivity.this.startScan();
                        }
                    });
                }
                return true;
            }
        });
        popupMenu.show();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void showSortMenu(View view) {
        PopupMenu popupMenu = new PopupMenu(this, view);
        popupMenu.getMenu().add(0, 0, 0, "نام (الف تا ی)");
        popupMenu.getMenu().add(0, 1, 0, "نام (ی تا الف)");
        popupMenu.getMenu().add(0, 2, 0, "تاریخ (جدیدترین اول)");
        popupMenu.getMenu().add(0, 3, 0, "تاریخ (قدیمی\u200cترین اول)");
        popupMenu.getMenu().add(0, 4, 0, "حجم (بزرگ\u200cترین اول)");
        popupMenu.getMenu().add(0, 5, 0, "حجم (کوچک\u200cترین اول)");
        popupMenu.getMenu().add(0, 6, 0, "نوع فایل (PDF اول)");
        int i = this.sortMode;
        if (i >= 0 && i <= 6) {
            popupMenu.getMenu().getItem(this.sortMode).setChecked(true);
        }
        popupMenu.getMenu().setGroupCheckable(0, true, true);
        popupMenu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() { // from class: com.pdfkhwan.app.MainActivity.8
            @Override // android.widget.PopupMenu.OnMenuItemClickListener
            public boolean onMenuItemClick(MenuItem menuItem) {
                MainActivity.this.sortMode = menuItem.getItemId();
                MainActivity.this.store.setInt("sortMode", MainActivity.this.sortMode);
                MainActivity.this.applyFilter();
                MainActivity.this.toast("ترتیب: " + ((Object) menuItem.getTitle()));
                return true;
            }
        });
        popupMenu.show();
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* v1.28: token of OUR library scan (see onDestroy) */
    private LibraryScanner.Cancel myScanToken;

    public void startScan() {
        final int seq = ++this.scanSeq;
        this.scanning = true;
        this.myScanToken = LibraryScanner.scan(this, new LibraryScanner.Callback() { // from class: com.pdfkhwan.app.MainActivity.10
            @Override // com.pdfkhwan.app.LibraryScanner.Callback
            public void onScanDone(List<LibraryScanner.Item> list, boolean z) {
                if (seq != MainActivity.this.scanSeq) {
                    return; /* v7: superseded by a newer scan */
                }
                MainActivity.this.scanning = false;
                if (z) {
                    return;
                }
                MainActivity.this.libAll.clear();
                MainActivity.this.libAll.addAll(list);
                MainActivity.this.refreshAccess();
                MainActivity.this.updateBanner();
                MainActivity.this.applyFilter();
            }
        });
        refreshLibraryUi();
    }

    private void styleFilters() {
        int[][] iArr = {new int[]{R.id.filterAll, 0}, new int[]{R.id.filterPdf, 1}, new int[]{R.id.filterPpt, 2}};
        TextView[] textViewArr = {this.filterAll, this.filterPdf, this.filterPpt};
        for (int i = 0; i < 3; i++) {
            textViewArr[i].setTextColor(this.filterKind == iArr[i][1] ? -13773633 : -7495492);
            textViewArr[i].setTypeface(null, 1);
        }
    }

    private void styleTab(TextView textView, boolean z) {
        if (z) {
            textView.setBackgroundResource(R.drawable.bg_button);
            textView.setTextColor(-13773633);
            textView.setTypeface(null, 1);
        } else {
            textView.setBackgroundResource(android.R.color.transparent);
            textView.setTextColor(-7495492);
            textView.setTypeface(null, 1);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void toast(String str) {
        Toast.makeText(this, str, 0).show();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateBanner() {
        boolean z = this.libMode && !this.hasAccess;
        this.permBanner.setVisibility(z ? 0 : 8);
        if (z) {
            this.permText.setText(Build.VERSION.SDK_INT >= 30 ? "برای دیدن همهٔ PDFهای گوشی، دسترسی «همهٔ فایل\u200cها» را در تنظیمات روشن کن" : "برای دیدن همهٔ PDFهای گوشی، اجازهٔ خواندن حافظه را تأیید کن");
        }
        EditText editText = this.libSearch;
        if (editText != null) {
            editText.setVisibility((this.libMode && this.hasAccess) ? 0 : 8);
            this.filterRow.setVisibility((this.libMode && this.hasAccess && !this.selMode) ? 0 : 8);
        }
    }

    private void wireFilterBar() {
        View.OnClickListener onClickListener = new View.OnClickListener() { // from class: com.pdfkhwan.app.MainActivity.6
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                MainActivity.this.setFilterKind(view.getId() == R.id.filterPdf ? 1 : view.getId() == R.id.filterPpt ? 2 : 0);
            }
        };
        this.filterAll.setOnClickListener(onClickListener);
        this.filterPdf.setOnClickListener(onClickListener);
        this.filterPpt.setOnClickListener(onClickListener);
        this.btnSort.setOnClickListener(new View.OnClickListener() { // from class: com.pdfkhwan.app.MainActivity.7
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                MainActivity.this.showSortMenu(view);
            }
        });
        styleFilters();
    }

    /* JADX WARN: Multi-variable type inference failed */
    @Override // android.app.Activity
    protected void onCreate(Bundle bundle) {
        CrashGuard.install(this);
        /* v1.46: منابع فونت PDFBox (afm/glyphlist) — باید قبل از هر
           ساخت فونت Standard14 مقداردهی شود (فرم‌ها/خروجی فرم) */
        try {
            com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(getApplicationContext());
        } catch (Throwable ignored) {
        }
        super.onCreate(bundle);
        setContentView(R.layout.activity_main);
        this.store = RecentStore.get(this);
        maybeShowCrashLog();
        this.list = (ListView) findViewById(R.id.mainList);
        this.tabLib = (TextView) findViewById(R.id.tabLib);
        this.tabRecent = (TextView) findViewById(R.id.tabRecent);
        this.emptyHint = (TextView) findViewById(R.id.emptyHint);
        this.libCount = (TextView) findViewById(R.id.libCount);
        this.permBanner = findViewById(R.id.permBanner);
        this.permText = (TextView) findViewById(R.id.permText);
        this.btnGrant = findViewById(R.id.btnGrant);
        this.libSearch = (EditText) findViewById(R.id.libSearch);
        this.filterRow = findViewById(R.id.filterRow);
        this.filterAll = (TextView) findViewById(R.id.filterAll);
        this.filterPdf = (TextView) findViewById(R.id.filterPdf);
        this.filterPpt = (TextView) findViewById(R.id.filterPpt);
        this.btnSort = (TextView) findViewById(R.id.btnSort);
        this.filterKind = this.store.getInt("filterKind", 0);
        this.sortMode = this.store.getInt("sortMode", 2);
        wireFilterBar();
        wireSelBar(); /* v1.15: batch select/share/delete bar */
        this.libAdapter = new LibAdapter(this, null);
        this.recentAdapter = new RecentsAdapter(this, null);
        findViewById(R.id.btnGrant).setOnClickListener(new View.OnClickListener() { // from class: com.pdfkhwan.app.MainActivity.1
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                MainActivity.this.requestAccess();
            }
        });
        this.tabLib.setOnClickListener(new View.OnClickListener() { // from class: com.pdfkhwan.app.MainActivity.2
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                MainActivity.this.setMode(true);
            }
        });
        this.tabRecent.setOnClickListener(new View.OnClickListener() { // from class: com.pdfkhwan.app.MainActivity.3
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                MainActivity.this.setMode(false);
            }
        });
        this.libSearch.addTextChangedListener(new TextWatcher() { // from class: com.pdfkhwan.app.MainActivity.4
            @Override // android.text.TextWatcher
            public void afterTextChanged(Editable editable) {
                MainActivity.this.applyFilter();
            }

            @Override // android.text.TextWatcher
            public void beforeTextChanged(CharSequence charSequence, int i, int i2, int i3) {
            }

            @Override // android.text.TextWatcher
            public void onTextChanged(CharSequence charSequence, int i, int i2, int i3) {
            }
        });
        findViewById(R.id.btnNewPpt).setOnClickListener(new View.OnClickListener() { // from class: com.pdfkhwan.app.MainActivity.5
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                MainActivity.this.newPptDialog();
            }
        });
        Intent intent = getIntent();
        if (intent != null && intent.getAction() != null) {
            /* v1.29 FIX: the manifest ADVERTISES three ACTION_EDIT filters
               («ویرایش پاورپوینت») but the intent fell through here — file
               managers' Edit action just landed in the library with the file
               silently unopened. EDIT is now handled exactly like VIEW. */
            String act = intent.getAction();
            boolean open = "android.intent.action.VIEW".equals(act)
                    || "android.intent.action.EDIT".equals(act);
            if (open && intent.getData() != null) {
                openUri(intent.getData());
            } else if ("android.intent.action.SEND".equals(intent.getAction())) {
                Parcelable parcelableExtra = intent.getParcelableExtra("android.intent.extra.STREAM");
                if (parcelableExtra instanceof Uri) {
                    openUri((Uri) parcelableExtra);
                }
            }
        }
        setMode(true);
    }

    @Override // android.app.Activity
    public void onBackPressed() {
        /* v1.15: back first leaves multi-select mode, then exits */
        if (this.selMode) {
            exitSelMode();
            return;
        }
        super.onBackPressed();
    }

    @Override // android.app.Activity
    protected void onDestroy() {
        super.onDestroy();
        /* v1.28 FIX: فقط اسکنِ خودِ این اکتیویتی لغو شود — cancel() سراسری
           اسکنِ اکتیویتیِ بازسازی‌شده (بعد از تغییر زبان/فونت) را هم
           می‌کشت و کتابخانه خالی/گیر می‌افتاد */
        try {
            LibraryScanner.Cancel token = this.myScanToken;
            if (token != null) token.stop = true;
        } catch (Throwable ignored) {
        }
    }

    @Override // android.app.Activity
    protected void onPause() {
        super.onPause();
    }

    @Override // android.app.Activity
    public void onRequestPermissionsResult(int i, String[] strArr, int[] iArr) {
        super.onRequestPermissionsResult(i, strArr, iArr);
        if (i == REQ_PERM) {
            refreshAccess();
            updateBanner();
            if (!this.hasAccess) {
                toast("بدون دسترسی، کتابخانه کامل نمایش داده نمی\u200cشود");
            } else {
                startScan();
                toast("دسترسی داده شد — در حال جستجوی PDFها…");
            }
        }
    }

    @Override // android.app.Activity
    protected void onResume() {
        super.onResume();
        refreshAccess();
        reloadRecents();
        if (this.hasAccess) {
            updateBanner();
            startScan();
            return;
        }
        updateBanner();
        this.libAll.clear();
        this.libShown.clear();
        this.libAdapter.notifyDataSetChanged();
        refreshLibraryUi();
    }
}
