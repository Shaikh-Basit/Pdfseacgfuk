package com.pdfkhwan.app;

import android.content.Context;

import com.tom_roush.pdfbox.cos.COSDictionary;
import com.tom_roush.pdfbox.cos.COSName;
import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.pdmodel.PDDestinationNameTreeNode;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDDocumentNameDictionary;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.font.PDFont;
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font;
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDAction;
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI;
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination;
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination;
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination;
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm;
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox;
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDChoice;
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDComboBox;
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDField;
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDFieldTree;
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDListBox;
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDRadioButton;
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDSignatureField;
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTerminalField;
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField;
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDVariableText;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v1.46 — موتور فرم‌های تعاملی (AcroForm) و لینک‌های PDF.
 *
 * وظیفهٔ این کلاس «نقشه‌برداری» است: یک بار PDF را با PDFBox می‌خواند و
 * برای هر فیلد فرم و هر لینک، مختصات نرمال‌شده (کسرِ صفحه 0..1) و نوع و
 * مقدار فعلی را برمی‌گرداند؛ ReaderActivity روی همان نقشه رابط لمسی و
 * لایهٔ رسم می‌سازد. همهٔ متدها «بدون پرتاب به بیرون» طراحی شده‌اند تا
 * یک PDF خراب هرگز بازکردن فایل را نگیرد.
 *
 * مبدأ مختصات: user-space پیدی‌اف (پایین-چپ) → نمایشی (بالا-چپ) با درنظر
 * گرفتن چرخش صفحه (0/90/180/270) و CropBox.
 */
public final class FormFiller {

    /* انواع فیلد */
    public static final int F_TEXT = 0;
    public static final int F_CHECK = 1;
    public static final int F_RADIO = 2;
    public static final int F_COMBO = 3;
    public static final int F_LIST = 4;
    public static final int F_SIGN = 5;

    /* انواع لینک */
    public static final int L_URI = 0;
    public static final int L_GOTO = 1;
    public static final int L_OTHER = 2;

    public static final class Field {
        public String name = "";          /* نام کامل (Fully Qualified) — کلید ذخیرهٔ مقدار */
        public String label = "";         /* نام کوتاه یا TU (برچسب کاربرپسند) */
        public int type = F_TEXT;
        public String value = "";         /* مقدار فعلی: متن / "1" برای روشن / گزینهٔ انتخابی */
        public ArrayList<String> options = new ArrayList<>();   /* گزینه‌ها (combo/list/radio) */
        public ArrayList<String> exports = new ArrayList<>();   /* مقدار صادراتی هم‌تراز گزینه‌ها */
        public String onState = "";       /* نام حالت روشن چک‌باکس */
        public boolean readonly = false;
        public boolean rtl = false;       /* ورودی راست‌چین */
        public int quadding = 0;          /* 0=چپ 1=وسط 2=راست */
        public int maxlen = 0;
        public int page = -1;             /* 0-مبنا */
        public float fx0, fy0, fx1, fy1;  /* ناحیهٔ ویجت — کسر صفحه (چپ/بالا/راست/پایین) */
    }

    public static final class Link {
        public int page;
        public float fx0, fy0, fx1, fy1;
        public int kind = L_OTHER;
        public String uri = "";
        public int destPage = -1;         /* 0-مبنا */
    }

    public static final class Interact {
        public boolean hasForms = false;
        public boolean xfaOnly = false;
        public int fieldCount = 0;        /* تعداد فیلدهای یکتا (بر اساس نام) */
        public int linkCount = 0;
        public ArrayList<Field> fields = new ArrayList<>();
        public ArrayList<ArrayList<Link>> links = new ArrayList<>();   /* صفحه به صفحه */
    }

    private FormFiller() {
    }

    /* ============================ بارگذاری نقشه ============================ */

    public static Interact load(File pdf) {
        Interact out = new Interact();
        PDDocument doc = null;
        try {
            doc = PDDocument.load(pdf, "", MemoryUsageSetting.setupMixed(48L * 1024 * 1024));
            ArrayList<PDPage> pages = new ArrayList<>();
            for (PDPage p : doc.getPages()) pages.add(p);
            int n = pages.size();
            for (int i = 0; i < n; i++) out.links.add(new ArrayList<Link>());

            /* ---------- لینک‌ها ---------- */
            for (int i = 0; i < n; i++) {
                try {
                    List<PDAnnotation> anns = pages.get(i).getAnnotations();
                    if (anns == null) continue;
                    for (PDAnnotation an : anns) {
                        if (!(an instanceof PDAnnotationLink)) continue;
                        Link L = mkLink(doc, pages, (PDAnnotationLink) an, i);
                        if (L != null) {
                            out.links.get(i).add(L);
                            out.linkCount++;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }

            /* ---------- فرم‌ها ---------- */
            PDAcroForm af = null;
            try {
                af = doc.getDocumentCatalog().getAcroForm();
            } catch (Throwable ignored) {
            }
            if (af != null) {
                boolean xfa = false;
                try {
                    xfa = af.hasXFA();
                } catch (Throwable ignored) {
                }
                try {
                    ArrayList<String> seenNames = new ArrayList<>();
                    PDFieldTree tree = af.getFieldTree();
                    for (PDField f : tree) {
                        if (!(f instanceof PDTerminalField)) continue;
                        try {
                            collectField(doc, pages, (PDTerminalField) f, out, seenNames);
                        } catch (Throwable perField) {
                            /* یک فیلد خراب بقیه را نمی‌گیرد */
                        }
                    }
                    out.hasForms = !out.fields.isEmpty();
                    out.fieldCount = seenNames.size();
                    out.xfaOnly = !out.hasForms && xfa;
                } catch (Throwable formErr) {
                    out.xfaOnly = xfa && out.fields.isEmpty();
                }
            }
        } catch (Throwable t) {
            /* برگرداندن همان چه که تا حالا ساخته شد */
        } finally {
            try {
                if (doc != null) doc.close();
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    /* ساخت فیلدهای یک PDTerminalField — هر ویجت یک ناحیهٔ لمس (فیلد ممکن است
       در چند صفحه تکرار شده باشد) */
    private static void collectField(PDDocument doc, ArrayList<PDPage> pages,
                                     PDTerminalField f, Interact out, ArrayList<String> seenNames) {
        int type;
        if (f instanceof PDSignatureField) type = F_SIGN;
        else if (f instanceof PDCheckBox) type = F_CHECK;
        else if (f instanceof PDRadioButton) type = F_RADIO;
        else if (f instanceof PDComboBox) type = F_COMBO;
        else if (f instanceof PDListBox) type = F_LIST;
        else if (f instanceof PDTextField) type = F_TEXT;
        else return;   /* PDPushButton و انواع ناشناخته */

        String fqn = "";
        try {
            fqn = f.getFullyQualifiedName();
        } catch (Throwable ignored) {
        }
        if (fqn == null) fqn = "";

        /* خواص مشترک */
        String partial = fqn;
        int dot = fqn.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < fqn.length()) partial = fqn.substring(dot + 1);
        String tu = "";
        try {
            String alt = f.getAlternateFieldName();
            if (alt == null) alt = f.getCOSObject().getString(COSName.TU);
            tu = alt == null ? "" : alt;
        } catch (Throwable ignored) {
        }

        boolean readonly = false;
        try {
            readonly = f.isReadOnly();
        } catch (Throwable ignored) {
        }

        int quadding = 0;
        if (f instanceof PDVariableText) {
            try {
                quadding = ((PDVariableText) f).getQ();
            } catch (Throwable ignored) {
            }
        }

        int maxlen = 0;
        String value = "";
        ArrayList<String> options = new ArrayList<>();
        ArrayList<String> exports = new ArrayList<>();
        String onState = "";

        try {
            if (type == F_TEXT) {
                PDTextField tf = (PDTextField) f;
                String v = tf.getValue();
                value = v == null ? "" : v;
                maxlen = tf.getMaxLen();
            } else if (type == F_CHECK) {
                PDCheckBox c = (PDCheckBox) f;
                try {
                    String on = c.getOnValue();
                    onState = (on == null || on.length() == 0) ? "Yes" : on;
                } catch (Throwable onErr) {
                    onState = "Yes";
                }
                value = safeIsChecked(c, onState) ? "1" : "";
            } else if (type == F_RADIO) {
                PDRadioButton rb = (PDRadioButton) f;
                try {
                    List<String> ev = rb.getExportValues();
                    if (ev != null) {
                        for (String e : ev) if (e != null) { options.add(e); exports.add(e); }
                    }
                } catch (Throwable optErr) {
                }
                if (options.isEmpty()) {
                    /* از دیکشنری ظاهر ویجت‌ها حالت‌های روشن را بیرون بکش */
                    deriveOnStates(rb, options);
                    exports.addAll(options);
                }
                try {
                    String v = rb.getValue();
                    value = (v == null || "Off".equals(v)) ? "" : v;
                } catch (Throwable valErr) {
                    value = "";
                }
            } else if (type == F_COMBO || type == F_LIST) {
                PDChoice ch = (PDChoice) f;
                try {
                    List<String> o = ch.getOptions();
                    if (o != null) options.addAll(o);
                } catch (Throwable optErr) {
                }
                try {
                    List<String> ex = ch.getOptionsExportValues();
                    if (ex != null) {
                        for (String e : ex) exports.add(e == null ? "" : e);
                    }
                } catch (Throwable exErr) {
                }
                while (exports.size() < options.size()) exports.add(options.get(exports.size()));
                try {
                    List<String> vs = ch.getValue();
                    value = (vs == null || vs.isEmpty()) ? "" : vs.get(0);
                } catch (Throwable valErr) {
                    try {
                        String v = ch.getValueAsString();
                        value = v == null ? "" : v;
                    } catch (Throwable valErr2) {
                        value = "";
                    }
                }
            } else if (type == F_SIGN) {
                try {
                    /* /V امضای موجود؟ */
                    COSDictionary vdict = (COSDictionary) f.getCOSObject().getDictionaryObject(COSName.V);
                    value = vdict != null ? "SIGNED" : "";
                } catch (Throwable signErr) {
                    value = "";
                }
            }
        } catch (Throwable valOuter) {
        }

        boolean rtl = isRtlText(partial + " " + tu) || quadding == 2
                || (type == F_COMBO || type == F_LIST) && anyRtl(options);

        /* ویجت‌ها */
        List<PDAnnotationWidget> ws = null;
        try {
            ws = f.getWidgets();
        } catch (Throwable ignored) {
        }
        if (ws == null || ws.isEmpty()) return;

        boolean nameSeen = false;
        for (PDAnnotationWidget w : ws) {
            try {
                PDPage pg = w.getPage();
                if (pg == null) continue;
                int idx = pages.indexOf(pg);
                if (idx < 0) continue;
                PDRectangle r = w.getRectangle();
                if (r == null) continue;
                float[] fr = fracRect(pg, r);
                if (fr == null) continue;
                Field F = new Field();
                F.name = fqn;
                F.label = tu != null && tu.length() > 0 ? tu : partial;
                F.type = type;
                F.value = value;
                F.options = options;
                F.exports = exports;
                F.onState = onState;
                F.readonly = readonly;
                F.rtl = rtl;
                F.quadding = quadding;
                F.maxlen = maxlen;
                F.page = idx;
                F.fx0 = fr[0];
                F.fy0 = fr[1];
                F.fx1 = fr[2];
                F.fy1 = fr[3];
                out.fields.add(F);
                if (!nameSeen) {
                    nameSeen = true;
                    seenNames.add(fqn);
                }
            } catch (Throwable perWidget) {
            }
        }
    }

    private static boolean safeIsChecked(PDCheckBox c, String onState) {
        try {
            return c.isChecked();
        } catch (Throwable e) {
            try {
                String v = c.getValue();
                return v != null && !v.equals("Off") && v.length() > 0;
            } catch (Throwable e2) {
                return false;
            }
        }
    }

    /* حالت‌های «روشن» رادیو از کلیدهای /AP /N ویجت‌ها */
    private static void deriveOnStates(PDRadioButton rb, ArrayList<String> out) {
        try {
            for (PDAnnotationWidget w : rb.getWidgets()) {
                try {
                    COSDictionary ap = w.getCOSObject().getDictionaryObject(COSName.AP) instanceof COSDictionary
                            ? (COSDictionary) w.getCOSObject().getDictionaryObject(COSName.AP) : null;
                    if (ap == null) continue;
                    COSDictionary n = ap.getDictionaryObject(COSName.N) instanceof COSDictionary
                            ? (COSDictionary) ap.getDictionaryObject(COSName.N) : null;
                    if (n == null) continue;
                    for (COSName k : n.keySet()) {
                        String s = k.getName();
                        if (s != null && !"Off".equals(s) && !out.contains(s)) out.add(s);
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        if (out.isEmpty()) out.add("Yes");
    }

    /* مختصات user-space → کسرِ نمایشی صفحه با چرخش و CropBox */
    private static float[] fracRect(PDPage page, PDRectangle r) {
        try {
            PDRectangle cb = page.getCropBox();
            if (cb == null) cb = page.getMediaBox();
            if (cb == null) return null;
            int rot = page.getRotation();
            if (rot < 0) rot = 0;
            rot = ((rot % 360) + 360) % 360;
            float cbX = cb.getLowerLeftX(), cbY = cb.getLowerLeftY();
            float cw = cb.getWidth(), ch = cb.getHeight();
            if (cw <= 0.01f || ch <= 0.01f) return null;
            /* ابعاد نمایشی پس از چرخش */
            float dw = (rot == 90 || rot == 270) ? ch : cw;
            float dh = (rot == 90 || rot == 270) ? cw : ch;
            if (dw <= 0.01f || dh <= 0.01f) return null;

            float[] xs = new float[4], ys = new float[4];
            for (int k = 0; k < 4; k++) {
                float x = (k == 0 || k == 3) ? r.getLowerLeftX() : r.getUpperRightX();
                float y = (k < 2) ? r.getLowerLeftY() : r.getUpperRightY();
                float dx, dy;
                switch (rot) {
                    case 90:
                        dx = y - cbY;
                        dy = x - cbX;
                        break;
                    case 180:
                        dx = (cbX + cw) - x;
                        dy = y - cbY;
                        break;
                    case 270:
                        dx = (cbX + cw) - y;
                        dy = (cbY + ch) - x;
                        break;
                    default:
                        dx = x - cbX;
                        dy = (cbY + ch) - y;
                        break;
                }
                xs[k] = dx;
                ys[k] = dy;
            }
            float fx0 = min(xs) / dw, fx1 = max(xs) / dw;
            float fy0 = min(ys) / dh, fy1 = max(ys) / dh;
            if (!(fx1 > fx0) || !(fy1 > fy0)) return null;
            return new float[]{
                    Math.max(-0.02f, Math.min(1.02f, fx0)),
                    Math.max(-0.02f, Math.min(1.02f, fy0)),
                    Math.max(-0.02f, Math.min(1.02f, fx1)),
                    Math.max(-0.02f, Math.min(1.02f, fy1))};
        } catch (Throwable t) {
            return null;
        }
    }

    private static float min(float[] a) {
        float m = a[0];
        for (float v : a) if (v < m) m = v;
        return m;
    }

    private static float max(float[] a) {
        float m = a[0];
        for (float v : a) if (v > m) m = v;
        return m;
    }

    /* ============================ لینک‌ها ============================ */

    private static Link mkLink(PDDocument doc, ArrayList<PDPage> pages, PDAnnotationLink ln, int pageIdx) {
        try {
            PDRectangle r = ln.getRectangle();
            if (r == null) return null;
            float[] fr = fracRect(pages.get(pageIdx), r);
            if (fr == null) return null;

            Link L = new Link();
            L.page = pageIdx;
            L.fx0 = fr[0];
            L.fy0 = fr[1];
            L.fx1 = fr[2];
            L.fy1 = fr[3];

            String uri = null;
            Integer dest = null;

            PDAction act = null;
            try {
                act = ln.getAction();
            } catch (Throwable ignored) {
            }
            if (act instanceof PDActionURI) {
                try {
                    uri = ((PDActionURI) act).getURI();
                } catch (Throwable ignored) {
                }
            } else if (act instanceof PDActionGoTo) {
                try {
                    dest = destPageIndex(doc, pages, ((PDActionGoTo) act).getDestination());
                } catch (Throwable ignored) {
                }
            } else if (act != null) {
                /* Launch / JavaScript / Named / RemoteGoTo / ... — ناحیه فعال
                   است ولی اینجا اجرا نمی‌شود */
                L.kind = L_OTHER;
                return L;
            }
            if (uri == null && dest == null) {
                try {
                    dest = destPageIndex(doc, pages, ln.getDestination());
                } catch (Throwable ignored) {
                }
            }
            if (uri != null && uri.length() > 0) {
                L.kind = L_URI;
                L.uri = uri;
                return L;
            }
            if (dest != null && dest >= 0 && dest < pages.size()) {
                L.kind = L_GOTO;
                L.destPage = dest;
                return L;
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Integer destPageIndex(PDDocument doc, ArrayList<PDPage> pages, PDDestination d) {
        if (d == null) return null;
        try {
            if (d instanceof PDPageDestination) {
                PDPageDestination pd = (PDPageDestination) d;
                PDPage tp = null;
                try {
                    tp = pd.getPage();
                } catch (Throwable ignored) {
                }
                if (tp != null) {
                    int i = pages.indexOf(tp);
                    if (i >= 0) return i;
                }
                try {
                    int num = pd.getPageNumber();   /* 0-مبنا */
                    if (num >= 0 && num < pages.size()) return num;
                } catch (Throwable ignored) {
                }
                return null;
            }
            if (d instanceof PDNamedDestination) {
                String nm = ((PDNamedDestination) d).getNamedDestination();
                if (nm == null || nm.length() == 0) return null;
                try {
                    PDDocumentNameDictionary names = doc.getDocumentCatalog().getNames();
                    if (names != null) {
                        PDDestinationNameTreeNode dn = names.getDests();
                        if (dn != null) {
                            Object v = dn.getValue(nm);
                            if (v instanceof PDDestination) {
                                return destPageIndex(doc, pages, (PDDestination) v);
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
                return null;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /* ============================ ذخیره در PDF ============================ */

    /**
     * مقادیر را داخل فیلدهای یک کپی تازه از PDF می‌نویسد.
     * flatten=true فرم را می‌پزد (متن دائمی می‌شود؛ فیلدها حذف می‌شوند).
     * فایل اصلی هرگز تغییر نمی‌کند — خروجی در outDir.
     */
    public static final class SaveResult {
        public final File file;
        public final boolean flattened;
        public final int applied;

        SaveResult(File file, boolean flattened, int applied) {
            this.file = file;
            this.flattened = flattened;
            this.applied = applied;
        }
    }

    public static SaveResult save(File src, Map<String, String> values, boolean flatten, File outDir) throws Exception {
        if (values == null || values.isEmpty()) throw new Exception("مقداری برای ذخیره کردن نیست");
        PDDocument doc = null;
        try {
            doc = PDDocument.load(src, "", MemoryUsageSetting.setupMixed(48L * 1024 * 1024));
            PDAcroForm af = doc.getDocumentCatalog().getAcroForm();
            if (af == null) throw new Exception("این فایل فرم ندارد");
            int applied = 0;
            PDFieldTree tree = af.getFieldTree();
            for (PDField f : tree) {
                if (!(f instanceof PDTerminalField)) continue;
                String fqn = "";
                try {
                    fqn = f.getFullyQualifiedName();
                } catch (Throwable ignored) {
                }
                if (fqn == null || fqn.length() == 0 || !values.containsKey(fqn)) continue;
                String v = values.get(fqn);
                boolean viaCos = false;
                try {
                    if (f instanceof PDTextField) {
                        ((PDTextField) f).setValue(v == null ? "" : v);
                        applied++;
                    } else if (f instanceof PDCheckBox) {
                        PDCheckBox c = (PDCheckBox) f;
                        if ("1".equals(v)) c.check();
                        else c.unCheck();
                        applied++;
                    } else if (f instanceof PDRadioButton) {
                        ((PDRadioButton) f).setValue(v == null || v.length() == 0 ? "Off" : v);
                        applied++;
                    } else if (f instanceof PDComboBox) {
                        PDComboBox cb = (PDComboBox) f;
                        if (v == null || v.length() == 0) cb.setValue((String) null);
                        else cb.setValue(v);
                        applied++;
                    } else if (f instanceof PDListBox) {
                        PDListBox lb = (PDListBox) f;
                        if (v == null || v.length() == 0) lb.setValue((String) null);
                        else lb.setValue(v);
                        applied++;
                    }
                    /* امضا: مقدار متنی ندارد — تصویری است و از مسیر حاشیه‌نویسی می‌رود */
                } catch (Throwable per) {
                    /* مثلاً متن فارسی با فونت Helvetica — مقدار خام نوشته می‌شود و
                       Adobe هنگام بازکردن، ظاهرش را می‌سازد (NeedAppearances) */
                    try {
                        if (v == null || v.length() == 0) {
                            f.getCOSObject().removeItem(COSName.V);
                        } else {
                            f.getCOSObject().setString(COSName.V, v);
                        }
                        applied++;
                        viaCos = true;
                    } catch (Throwable per2) {
                        /* این فیلد به‌کلی نوشته نمی‌شود */
                    }
                }
                if (viaCos) {
                    try {
                        af.setNeedAppearances(true);
                    } catch (Throwable ignored) {
                    }
                }
            }
            if (applied == 0) throw new Exception("هیچ فیلدی ذخیره نشد");
            boolean flatOk = false;
            if (flatten) {
                try {
                    af.flatten();
                    flatOk = true;
                } catch (Throwable fl) {
                    /* پختن ممکن نشد — بدون پختن ذخیره می‌کنیم */
                }
            }
            if (!outDir.exists()) outDir.mkdirs();
            File out = new File(outDir, "form-" + System.currentTimeMillis() + ".pdf");
            doc.save(out);
            return new SaveResult(out, flatOk, applied);
        } finally {
            try {
                if (doc != null) doc.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /* ============================ مقادیر جانبی (JSON) ============================ */

    /* مقادیر فرم کنار فایل ذخیره می‌شوند (نه داخل PDF) — دفعهٔ بعد پر می‌شوند */

    public static LinkedHashMap<String, String> loadValues(Context ctx, String fileKey) {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        try {
            File f = valuesFile(ctx, fileKey);
            if (f == null || !f.exists()) return m;
            FileInputStream in = new FileInputStream(f);
            try {
                byte[] buf = new byte[(int) f.length()];
                int off = 0;
                while (off < buf.length) {
                    int r = in.read(buf, off, buf.length - off);
                    if (r <= 0) break;
                    off += r;
                }
                org.json.JSONObject o = new org.json.JSONObject(new String(buf, 0, off, "UTF-8"));
                java.util.Iterator<String> it = o.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    m.put(k, o.optString(k, ""));
                }
            } finally {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return m;
    }

    public static void saveValues(Context ctx, String fileKey, Map<String, String> values) {
        try {
            File f = valuesFile(ctx, fileKey);
            if (f == null) return;
            org.json.JSONObject o = new org.json.JSONObject();
            if (values != null) {
                for (Map.Entry<String, String> e : values.entrySet()) {
                    if (e.getValue() != null && e.getValue().length() > 0 && !"SIGNED".equals(e.getValue())) {
                        o.put(e.getKey(), e.getValue());
                    }
                }
            }
            FileOutputStream fos = new FileOutputStream(f);
            try {
                fos.write(o.toString().getBytes("UTF-8"));
            } finally {
                try {
                    fos.close();
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public static void clearValues(Context ctx, String fileKey) {
        try {
            File f = valuesFile(ctx, fileKey);
            if (f != null && f.exists()) f.delete();
        } catch (Throwable ignored) {
        }
    }

    private static File valuesFile(Context ctx, String fileKey) {
        try {
            File dir = new File(ctx.getFilesDir(), "forms");
            if (!dir.exists()) dir.mkdirs();
            String safe = fileKey == null ? "doc" : fileKey.replaceAll("[^A-Za-z0-9._-]", "_");
            if (safe.length() > 80) safe = safe.substring(0, 80) + (fileKey == null ? "" : Integer.toHexString(fileKey.hashCode()));
            return new File(dir, safe + ".json");
        } catch (Throwable t) {
            return null;
        }
    }

    /* ============================ متن راست‌به‌چپ ============================ */

    public static boolean isRtlText(String s) {
        if (s == null) return false;
        int rtl = 0, ltr = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (isRtlChar(c)) rtl++;
            else if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) ltr++;
        }
        return rtl > 0 && rtl >= ltr;
    }

    private static boolean isRtlChar(char c) {
        return (c >= 0x0590 && c <= 0x05FF)      /* عبری */
                || (c >= 0x0600 && c <= 0x06FF)  /* عربی/فارسی */
                || (c >= 0x0700 && c <= 0x074F)
                || (c >= 0xFB50 && c <= 0xFDFF)  /* عرضی عربی */
                || (c >= 0xFE70 && c <= 0xFEFF)
                || (c == 0x200F);
    }

    private static boolean anyRtl(List<String> list) {
        if (list == null) return false;
        for (String s : list) if (isRtlText(s)) return true;
        return false;
    }
}
