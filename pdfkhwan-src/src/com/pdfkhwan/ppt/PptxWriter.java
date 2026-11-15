package com.pdfkhwan.ppt;

import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.zip.CRC32;

/* ============================================================================
   PptxWriter v2 (v1.15) — writes the v2 document model back to a strictly
   valid ECMA-376 .pptx.

   Strategy: slides carry fully resolved geometry (the reader already folded
   layouts/masters/groups into absolute coordinates), so the package ships
   one simple master + layout pair and every shape with explicit a:xfrm.
   That keeps PowerPoint/WPS/LibreOffice happy while preserving exactly
   what the user sees in the editor.

   Rich text: every paragraph and run round-trips (size/bold/italic/
   underline/color/font). Tables, pictures (with crop), connectors,
   rotation and gradient fills are all written.
   ============================================================================ */
public final class PptxWriter {

    /* v1.36: 64x64 soft "?" placeholder PNG (493 bytes) — the writer
       embeds it when a picture lost its bytes so the frame never
       disappears from the saved file */
    static final byte[] PLACEHOLDER_PNG = new byte[]{
            (byte) 0x89, (byte) 0x50, (byte) 0x4E, (byte) 0x47, (byte) 0x0D, (byte) 0x0A, (byte) 0x1A, (byte) 0x0A, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x0D, (byte) 0x49, (byte) 0x48, (byte) 0x44, (byte) 0x52,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x40, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x40, (byte) 0x08, (byte) 0x06, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0xAA, (byte) 0x69, (byte) 0x71,
            (byte) 0xDE, (byte) 0x00, (byte) 0x00, (byte) 0x01, (byte) 0xB4, (byte) 0x49, (byte) 0x44, (byte) 0x41, (byte) 0x54, (byte) 0x78, (byte) 0x9C, (byte) 0xED, (byte) 0x5B, (byte) 0x31, (byte) 0x6E, (byte) 0xC3,
            (byte) 0x30, (byte) 0x0C, (byte) 0x54, (byte) 0x82, (byte) 0x6C, (byte) 0x7E, (byte) 0x54, (byte) 0xD1, (byte) 0xA5, (byte) 0xEF, (byte) 0x09, (byte) 0x82, (byte) 0x3C, (byte) 0xA3, (byte) 0x28, (byte) 0xFA,
            (byte) 0x9E, (byte) 0x2E, (byte) 0x41, (byte) 0x5F, (byte) 0xE4, (byte) 0xC9, (byte) 0x73, (byte) 0x3B, (byte) 0x29, (byte) 0x50, (byte) 0x64, (byte) 0x59, (byte) 0x24, (byte) 0xD3, (byte) 0x8A, (byte) 0x67,
            (byte) 0x89, (byte) 0xBC, (byte) 0x2D, (byte) 0xB1, (byte) 0x02, (byte) 0xDF, (byte) 0x9D, (byte) 0x28, (byte) 0x52, (byte) 0xB4, (byte) 0xA3, (byte) 0xC3, (byte) 0x3C, (byte) 0xCF, (byte) 0x3F, (byte) 0xC1,
            (byte) 0x30, (byte) 0x8E, (byte) 0x68, (byte) 0x02, (byte) 0x68, (byte) 0x9C, (byte) 0xA8, (byte) 0x01, (byte) 0xDF, (byte) 0xB7, (byte) 0x2F, (byte) 0x0D, (byte) 0x1E, (byte) 0x4D, (byte) 0xF1, (byte) 0xF2,
            (byte) 0xFA, (byte) 0xB6, (byte) 0x79, (byte) 0xED, (byte) 0xB0, (byte) 0xB5, (byte) 0x04, (byte) 0x46, (byte) 0x10, (byte) 0x9E, (byte) 0xA3, (byte) 0x64, (byte) 0x44, (byte) 0xD1, (byte) 0x80, (byte) 0x54,
            (byte) 0x7C, (byte) 0xCD, (byte) 0xBD, (byte) 0x5E, (byte) 0x50, (byte) 0xD3, (byte) 0xB3, (byte) 0x32, (byte) 0x20, (byte) 0x0E, (byte) 0x1E, (byte) 0x41, (byte) 0x78, (byte) 0x8E, (byte) 0x92, (byte) 0xB6,
            (byte) 0x23, (byte) 0x35, (byte) 0x60, (byte) 0x24, (byte) 0x44, (byte) 0x5D, (byte) 0x69, (byte) 0x44, (byte) 0x98, (byte) 0xAF, (byte) 0x02, (byte) 0xF7, (byte) 0x25, (byte) 0xC0, (byte) 0x9D, (byte) 0xFD,
            (byte) 0x69, (byte) 0x9A, (byte) 0xDA, (byte) 0xB3, (byte) 0xFA, (byte) 0x23, (byte) 0x96, (byte) 0x65, (byte) 0xA9, (byte) 0x5E, (byte) 0x4F, (byte) 0xB5, (byte) 0x8A, (byte) 0x22, (byte) 0xA0, (byte) 0x07,
            (byte) 0xF1, (byte) 0x21, (byte) 0xC8, (byte) 0x78, (byte) 0xB2, (byte) 0x0D, (byte) 0xE8, (byte) 0x45, (byte) 0x7C, (byte) 0x04, (byte) 0x97, (byte) 0x2F, (byte) 0xCB, (byte) 0x80, (byte) 0xDE, (byte) 0xC4,
            (byte) 0x47, (byte) 0x70, (byte) 0x78, (byte) 0x93, (byte) 0x06, (byte) 0xF4, (byte) 0x2A, (byte) 0x3E, (byte) 0x82, (byte) 0xE2, (byte) 0x6F, (byte) 0xBE, (byte) 0x0A, (byte) 0xB8, (byte) 0x01, (byte) 0x68,
            (byte) 0x02, (byte) 0x68, (byte) 0x90, (byte) 0xDD, (byte) 0x60, (byte) 0x2B, (byte) 0x7C, (byte) 0x7E, (byte) 0xBC, (byte) 0xAF, (byte) 0xBE, (byte) 0x3B, (byte) 0x5F, (byte) 0xAE, (byte) 0xEA, (byte) 0x3C,
            (byte) 0xD4, (byte) 0x0D, (byte) 0x28, (byte) 0x09, (byte) 0xCF, (byte) 0xAF, (byte) 0x69, (byte) 0x1A, (byte) 0xA1, (byte) 0x66, (byte) 0x40, (byte) 0x4D, (byte) 0xF8, (byte) 0xD6, (byte) 0x58, (byte) 0x0D,
            (byte) 0x23, (byte) 0x54, (byte) 0x72, (byte) 0x80, (byte) 0x44, (byte) 0xFC, (byte) 0x7F, (byte) 0xFC, (byte) 0x4E, (byte) 0x02, (byte) 0x58, (byte) 0x0E, (byte) 0x28, (byte) 0xCD, (byte) 0xAE, (byte) 0x86,
            (byte) 0xE0, (byte) 0x1C, (byte) 0xCD, (byte) 0x23, (byte) 0x40, (byte) 0x92, (byte) 0xEC, (byte) 0x10, (byte) 0xA6, (byte) 0xA8, (byte) 0x97, (byte) 0x41, (byte) 0x6A, (byte) 0x5D, (byte) 0x6B, (byte) 0x57,
            (byte) 0x02, (byte) 0xF3, (byte) 0xFB, (byte) 0x00, (byte) 0x37, (byte) 0x40, (byte) 0xFB, (byte) 0x86, (byte) 0xD4, (byte) 0x9A, (byte) 0xD6, (byte) 0x4E, (byte) 0x84, (byte) 0xCD, (byte) 0x0D, (byte) 0x90,
            (byte) 0x24, (byte) 0x36, (byte) 0xC4, (byte) 0xEE, (byte) 0x70, (byte) 0x57, (byte) 0x5B, (byte) 0x61, (byte) 0x04, (byte) 0x54, (byte) 0x96, (byte) 0xC0, (byte) 0xB3, (byte) 0xB3, (byte) 0xA8, (byte) 0x51,
            (byte) 0x11, (byte) 0xD4, (byte) 0x22, (byte) 0x20, (byte) 0x8A, (byte) 0xE1, (byte) 0xCC, (byte) 0xFC, (byte) 0x90, (byte) 0xBD, (byte) 0x40, (byte) 0x44, (byte) 0xCD, (byte) 0x08, (byte) 0x44, (byte) 0x37,
            (byte) 0x08, (byte) 0x2B, (byte) 0x83, (byte) 0x08, (byte) 0xB1, (byte) 0x25, (byte) 0xF8, (byte) 0x3E, (byte) 0x00, (byte) 0x4D, (byte) 0x00, (byte) 0x0D, (byte) 0x37, (byte) 0x00, (byte) 0x4D, (byte) 0x00,
            (byte) 0x0D, (byte) 0x37, (byte) 0x00, (byte) 0x4D, (byte) 0x00, (byte) 0x0D, (byte) 0xF3, (byte) 0x06, (byte) 0xC0, (byte) 0x7A, (byte) 0x81, (byte) 0x10, (byte) 0xF6, (byte) 0xB1, (byte) 0x17, (byte) 0x30,
            (byte) 0x1F, (byte) 0x01, (byte) 0x6E, (byte) 0x00, (byte) 0x9A, (byte) 0x00, (byte) 0x1A, (byte) 0xE6, (byte) 0x0D, (byte) 0x80, (byte) 0x26, (byte) 0xC1, (byte) 0xB4, (byte) 0x23, (byte) 0x44, (byte) 0x25,
            (byte) 0x44, (byte) 0x58, (byte) 0x04, (byte) 0xE4, (byte) 0xED, (byte) 0x30, (byte) 0xEA, (byte) 0x09, (byte) 0x11, (byte) 0xC4, (byte) 0x00, (byte) 0xC9, (byte) 0x33, (byte) 0xC1, (byte) 0xD6, (byte) 0x30,
            (byte) 0x9F, (byte) 0x03, (byte) 0xDC, (byte) 0x00, (byte) 0xC4, (byte) 0x4D, (byte) 0x25, (byte) 0xEF, (byte) 0x06, (byte) 0x5B, (byte) 0x63, (byte) 0x37, (byte) 0x8F, (byte) 0xC4, (byte) 0x50, (byte) 0x55,
            (byte) 0xC0, (byte) 0x7B, (byte) 0x01, (byte) 0x34, (byte) 0x01, (byte) 0x34, (byte) 0x48, (byte) 0x03, (byte) 0xA8, (byte) 0x3F, (byte) 0x1E, (byte) 0xEF, (byte) 0x1D, (byte) 0x14, (byte) 0x7F, (byte) 0x56,
            (byte) 0x04, (byte) 0xF4, (byte) 0x6A, (byte) 0x02, (byte) 0x87, (byte) 0x37, (byte) 0x7B, (byte) 0x09, (byte) 0xF4, (byte) 0x66, (byte) 0x02, (byte) 0x97, (byte) 0xAF, (byte) 0x28, (byte) 0x07, (byte) 0xF4,
            (byte) 0x62, (byte) 0x82, (byte) 0x84, (byte) 0xE7, (byte) 0xC3, (byte) 0x99, (byte) 0xA1, (byte) 0xD1, (byte) 0x8F, (byte) 0xCC, (byte) 0x84, (byte) 0xB0, (byte) 0xD6, (byte) 0xE8, (byte) 0x55, (byte) 0x20,
            (byte) 0xFD, (byte) 0x50, (byte) 0x3A, (byte) 0x54, (byte) 0x34, (byte) 0x12, (byte) 0x4A, (byte) 0x11, (byte) 0xEE, (byte) 0xE7, (byte) 0x06, (byte) 0xFD, (byte) 0xE4, (byte) 0x28, (byte) 0x71, (byte) 0x7A,
            (byte) 0x7C, (byte) 0x04, (byte) 0x23, (byte) 0x9E, (byte) 0x3A, (byte) 0x3B, (byte) 0x6C, (byte) 0x05, (byte) 0xBF, (byte) 0x04, (byte) 0x96, (byte) 0xBC, (byte) 0x31, (byte) 0x75, (byte) 0x76, (byte) 0x6D,
            (byte) 0xF5, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x49, (byte) 0x45, (byte) 0x4E, (byte) 0x44, (byte) 0xAE, (byte) 0x42, (byte) 0x60, (byte) 0x82,
    };

    /* v1.36 (حذف‌نشدن تصاویر بی‌داده): a picture whose bytes AND url are both
       missing used to be re-emitted as a bare <a:blip/> — every consumer
       (including our own reader) then dropped it → the picture effectively
       DELETED itself on each save. Register ONE shared 64x64 placeholder
       part and point the picture at it instead: the frame survives, the
       user sees a soft "?" instead of nothing. */
    private static String ensurePlaceholder(List<byte[]> mediaOut, List<String> mediaNames) {
        for (int i = 0; i < mediaNames.size(); i++) {
            if ("imgph.png".equals(mediaNames.get(i))) return "rImg" + (i + 1);
        }
        mediaOut.add(PLACEHOLDER_PNG);
        mediaNames.add("imgph.png");
        return "rImg" + mediaOut.size();
    }


    /* real writer with central directory (files must open everywhere).
       v1.27: DEFLATE compression (the old writer stored everything raw —
       saved decks with photos could be ~10× the original size) and LONG
       offsets (int overflow past 2 GB corrupted the central directory). */
    private static final class ZipW {
        /* one central-directory record per stored entry */
        private static final class Entry {
            final String name;
            final int crc;
            final int size;       // uncompressed size
            final int csize;      // compressed size
            final long offset;
            final boolean deflate;

            Entry(String name, int crc, int size, int csize, long offset, boolean deflate) {
                this.name = name;
                this.crc = crc;
                this.size = size;
                this.csize = csize;
                this.offset = offset;
                this.deflate = deflate;
            }
        }

        private final OutputStream out;
        private final java.util.List<Entry> entries = new java.util.ArrayList<>();
        private long offset = 0;

        ZipW(OutputStream out) {
            this.out = out;
        }

        void write(String name, byte[] data) throws IOException {
            if (entries.size() >= 65000) {
                /* v1.29 FIX: silently DROPPING a part leaves dangling rels /
                 * presentation references — a corrupt package with no
                 * exception. Fail loudly instead (the caller aborts the
                 * temp-file write and the original file stays untouched). */
                throw new IOException("pptx part limit exceeded (65000 entries)");
            }
            CRC32 crc = new CRC32();
            crc.update(data);
            byte[] nb = name.getBytes("UTF-8");
            int crcV = (int) (crc.getValue() & 0xFFFFFFFFL);

            /* v1.27: DEFLATE every entry worth the header overhead; tiny
               parts stay STORED (compression would grow them) */
            boolean deflate = data.length > 64;
            byte[] body = data;
            if (deflate) {
                /* nowrap=TRUE: ZIP stores the RAW deflate stream (no zlib
                   2-byte header / adler32 trailer) */
                java.util.zip.Deflater d = new java.util.zip.Deflater(
                        java.util.zip.Deflater.BEST_SPEED, true);
                try {
                    d.setInput(data);
                    d.finish();
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(
                            Math.max(64, data.length / 2));
                    byte[] buf = new byte[65536];
                    while (!d.finished()) {
                        int n = d.deflate(buf);
                        if (n > 0) bo.write(buf, 0, n);
                    }
                    byte[] comp = bo.toByteArray();
                    if (comp.length < data.length) body = comp;
                    else deflate = false;
                } finally {
                    d.end();
                }
            }

            int method = deflate ? 8 : 0;
            le(0x04034b50, 4);
            le(20, 2);
            le(0, 2);
            le(method, 2);
            le(0, 2);          // time
            le(0x21, 2);       // date
            le(crcV, 4);
            le(body.length, 4);
            le(data.length, 4);
            le(nb.length, 2);
            le(0, 2);
            out.write(nb);
            out.write(body);
            entries.add(new Entry(name, crcV, data.length, body.length, offset, deflate));
            offset += 30 + nb.length + body.length;
        }

        void finish() throws IOException {
            long cdStart = offset;
            for (Entry e : entries) {
                byte[] nb = e.name.getBytes("UTF-8");
                le(0x02014b50, 4);
                le(20, 2);
                le(20, 2);
                le(0, 2);
                le(e.deflate ? 8 : 0, 2);
                le(0, 2);
                le(0x21, 2);
                le(e.crc, 4);
                le(e.csize, 4);
                le(e.size, 4);
                le(nb.length, 2);
                le(0, 2);
                le(0, 2);
                le(0, 2);
                le(0, 2);
                le(0, 4);
                le((int) (e.offset & 0xFFFFFFFFL), 4);
                out.write(nb);
                offset += 46 + nb.length;
            }
            long cdSize = offset - cdStart;
            le(0x06054b50, 4);
            le(0, 2);
            le(0, 2);
            le(entries.size(), 2);
            le(entries.size(), 2);
            le((int) (cdSize & 0xFFFFFFFFL), 4);
            le((int) (cdStart & 0xFFFFFFFFL), 4);
            le(0, 2);
            out.flush();
        }

        private void le(int v, int n) throws IOException {
            for (int i = 0; i < n; i++) out.write((v >>> (i * 8)) & 0xFF);
        }
    }

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '\"': sb.append("&quot;"); break;
                case '\'': sb.append("&apos;"); break;
                default:
                    if (c >= 0x20 || c == '\t' || c == '\n' || c == '\r') sb.append(c);
                    else sb.append(' ');
            }
        }
        return sb.toString();
    }

    private static String hex(int c) {
        return String.format("%06X", c & 0xFFFFFF);
    }

    /* v1.30 FIX: keep the ALPHA channel — semi-transparent fills, outlines,
       gradients and text colors (extremely common in real decks) used to be
       saved as fully opaque because only the RGB hex was ever written.
       The alpha byte becomes the OOXML child <a:alpha val="N"/> (N in
       1000ths of a percent: 100000 = opaque). */
    private static String srgb(int c) {
        int a = (c >>> 24) & 0xFF;
        /* v1.31 FIX: a == 0 (FULLY TRANSPARENT) used to fall into the
           plain-RGB branch and re-opened OPAQUE — the app rendered hard
           boxes where the deck had invisible fills (real Persian decks:
           transparent gradient stops). Emit <a:alpha val="0"/> for it. */
        if (a != 0xFF) {
            return "<a:srgbClr val=\"" + hex(c) + "\"><a:alpha val=\""
                    + Math.round(a * 100000f / 255f) + "\"/></a:srgbClr>";
        }
        return "<a:srgbClr val=\"" + hex(c) + "\"/>";
    }

    private static int clampSz(float pt) {
        int v = Math.round(pt * 100);
        if (v < 100) v = 100;
        if (v > 400000) v = 400000;
        return v;
    }

    private static String dashVal(int dash) {
        /* v1.29 FIX: match the reader's folds exactly so a preset does not
           drift on every save round-trip: reader maps dot->2, lgDashDot->3,
           lgDash->4 (writing sysDot/dashDot/sysDash changed the visual:
           sysDot dots are 3x denser than PowerPoint's "Dot" preset). */
        switch (dash) {
            case 1: return "dash";
            case 2: return "dot";
            case 3: return "lgDashDot";
            case 4: return "lgDash";
            default: return "solid";
        }
    }

    /* ------------------------------------------------------------- xml parts */
    private static String contentTypes(int slides, Map<String, String> media, int notesCount, int charts) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
          .append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
          .append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
          .append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>");
        if (media.containsKey("png")) sb.append("<Default Extension=\"png\" ContentType=\"image/png\"/>");
        /* v1.24 FIX: jpg pictures are stored as imgN.jpeg — the old check
           looked for the key "jpg" only, so decks with JPEG photos missed
           the jpeg Default and PowerPoint asked for a repair */
        if (media.containsKey("jpeg") || media.containsKey("jpg")) {
            sb.append("<Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/>");
            /* v1.29 FIX: a media part actually named *.jpg (possible for
               video poster/format edge cases) needs its OWN Default — the
               jpeg Default does not match the .jpg extension and the
               package opened with a repair prompt. */
            if (media.containsKey("jpg")) sb.append("<Default Extension=\"jpg\" ContentType=\"image/jpeg\"/>");
        }
        if (media.containsKey("gif")) sb.append("<Default Extension=\"gif\" ContentType=\"image/gif\"/>");
        if (media.containsKey("webp")) sb.append("<Default Extension=\"webp\" ContentType=\"image/webp\"/>");
        if (media.containsKey("bmp")) sb.append("<Default Extension=\"bmp\" ContentType=\"image/bmp\"/>");
        /* v1.26: decks round-tripping EMF/WMF/TIFF pictures need these
           Defaults — without them the package is invalid (repair prompt) */
        if (media.containsKey("tiff")) sb.append("<Default Extension=\"tiff\" ContentType=\"image/tiff\"/>");
        if (media.containsKey("tif")) sb.append("<Default Extension=\"tif\" ContentType=\"image/tiff\"/>");
        if (media.containsKey("emf")) sb.append("<Default Extension=\"emf\" ContentType=\"image/x-emf\"/>");
        if (media.containsKey("wmf")) sb.append("<Default Extension=\"wmf\" ContentType=\"image/x-wmf\"/>");
        if (media.containsKey("mp4")) sb.append("<Default Extension=\"mp4\" ContentType=\"video/mp4\"/>");
        if (media.containsKey("m4v")) sb.append("<Default Extension=\"m4v\" ContentType=\"video/mp4\"/>");
        if (media.containsKey("webm")) sb.append("<Default Extension=\"webm\" ContentType=\"video/webm\"/>");
        if (media.containsKey("mkv")) sb.append("<Default Extension=\"mkv\" ContentType=\"video/x-matroska\"/>");
        if (media.containsKey("3gp")) sb.append("<Default Extension=\"3gp\" ContentType=\"video/3gpp\"/>");
        if (media.containsKey("mov")) sb.append("<Default Extension=\"mov\" ContentType=\"video/quicktime\"/>");
        if (media.containsKey("wmv")) sb.append("<Default Extension=\"wmv\" ContentType=\"video/x-ms-wmv\"/>");
        if (media.containsKey("avi")) sb.append("<Default Extension=\"avi\" ContentType=\"video/x-msvideo\"/>");
        if (media.containsKey("mp3")) sb.append("<Default Extension=\"mp3\" ContentType=\"audio/mpeg\"/>");
        if (media.containsKey("m4a")) sb.append("<Default Extension=\"m4a\" ContentType=\"audio/mp4\"/>");
        if (media.containsKey("wav")) sb.append("<Default Extension=\"wav\" ContentType=\"audio/x-wav\"/>");
        if (media.containsKey("mpg") || media.containsKey("mpeg")) sb.append("<Default Extension=\"mpg\" ContentType=\"video/mpeg\"/>");
        if (media.containsKey("mpeg")) sb.append("<Default Extension=\"mpeg\" ContentType=\"video/mpeg\"/>");
        if (media.containsKey("3g2")) sb.append("<Default Extension=\"3g2\" ContentType=\"video/3gpp2\"/>");
        if (media.containsKey("ogg")) sb.append("<Default Extension=\"ogg\" ContentType=\"audio/ogg\"/>");
        if (media.containsKey("aac")) sb.append("<Default Extension=\"aac\" ContentType=\"audio/aac\"/>");
        if (media.containsKey("flac")) sb.append("<Default Extension=\"flac\" ContentType=\"audio/flac\"/>");
        if (media.containsKey("wma")) sb.append("<Default Extension=\"wma\" ContentType=\"audio/x-ms-wma\"/>");
        if (media.containsKey("opus")) sb.append("<Default Extension=\"opus\" ContentType=\"audio/opus\"/>");
        if (media.containsKey("amr")) sb.append("<Default Extension=\"amr\" ContentType=\"audio/amr\"/>");
        /* v1.27 FIX: ANY remaining media extension still needs a content type
           or the package is invalid (repair prompt). Octet-stream keeps the
           package openable everywhere. */
        java.util.Set<String> covered = new java.util.HashSet<>(java.util.Arrays.asList(
                "rels", "xml", "png", "jpeg", "jpg", "gif", "webp", "bmp", "tiff", "tif",
                "emf", "wmf", "mp4", "m4v", "webm", "mkv", "3gp", "3g2", "mov", "wmv",
                "avi", "mpg", "mpeg", "ogg", "aac", "flac", "wma", "opus", "amr", "mp3", "m4a", "wav"));
        for (String ext : media.keySet()) {
            if (!covered.contains(ext)) {
                sb.append("<Default Extension=\"").append(esc(ext))
                  .append("\" ContentType=\"application/octet-stream\"/>");
                covered.add(ext);
            }
        }
        sb.append("<Override PartName=\"/ppt/presentation.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml\"/>")
          .append("<Override PartName=\"/ppt/slideMasters/slideMaster1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml\"/>")
          .append("<Override PartName=\"/ppt/slideLayouts/slideLayout1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml\"/>")
          .append("<Override PartName=\"/ppt/theme/theme1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.theme+xml\"/>");
        for (int i = 1; i <= charts; i++) {
            sb.append("<Override PartName=\"/ppt/charts/chart").append(i)
              .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.drawingml.chart+xml\"/>");
        }
        for (int i = 1; i <= slides; i++) {
            sb.append("<Override PartName=\"/ppt/slides/slide").append(i)
              .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>");
        }
        if (notesCount > 0) {
            sb.append("<Override PartName=\"/ppt/notesMasters/notesMaster1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.notesMaster+xml\"/>");
            /* v1.27 FIX: notesSlides are numbered SEQUENTIALLY 1..notesCount,
               not 1..slides — decks with notes on only SOME slides got
               dangling Overrides for parts that don't exist (repair risk). */
            for (int i = 1; i <= notesCount; i++) {
                sb.append("<Override PartName=\"/ppt/notesSlides/notesSlide").append(i)
                  .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.notesSlide+xml\"/>");
            }
        }
        sb.append("<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>")
          .append("<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/>")
          .append("</Types>");
        return sb.toString();
    }

    private static String rootRels() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"ppt/presentation.xml\"/>"
                + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>"
                + "<Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties\" Target=\"docProps/app.xml\"/>"
                + "</Relationships>";
    }

    private static String coreProps(String title) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        String now = f.format(new Date());
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" "
                + "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\" "
                + "xmlns:dcmitype=\"http://purl.org/dc/dcmitype/\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">"
                + "<dc:title>" + esc(title) + "</dc:title>"
                + "<dc:creator>PDFKhwan</dc:creator>"
                + "<cp:lastModifiedBy>PDFKhwan</cp:lastModifiedBy>"
                + "<dcterms:created xsi:type=\"dcterms:W3CDTF\">" + now + "</dcterms:created>"
                + "<dcterms:modified xsi:type=\"dcterms:W3CDTF\">" + now + "</dcterms:modified>"
                + "</cp:coreProperties>";
    }

    private static String appProps(int slides, String title) {
        StringBuilder t = new StringBuilder();
        for (int i = 0; i < slides && i < 100; i++) t.append("<vt:lpstr>").append(esc(title)).append("</vt:lpstr>");
        int nTitles = Math.max(1, Math.min(slides, 100));
        /* v1.27 FIX: CT_Properties element order — Company before Slides,
           HeadingPairs before TitlesOfParts, Application LAST; the count in
           HeadingPairs must match the TitlesOfParts vector size. */
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\" "
                + "xmlns:vt=\"http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes\">"
                + "<Company></Company>"
                + "<Slides>" + slides + "</Slides>"
                + "<HeadingPairs><vt:vector size=\"2\" baseType=\"variant\">"
                + "<vt:variant><vt:lpstr>Slide Titles</vt:lpstr></vt:variant>"
                + "<vt:variant><vt:i4>" + nTitles + "</vt:i4></vt:variant>"
                + "</vt:vector></HeadingPairs>"
                + "<TitlesOfParts><vt:vector size=\"" + nTitles + "\" baseType=\"lpstr\">"
                + (t.length() == 0 ? "<vt:lpstr>" + esc(title) + "</vt:lpstr>" : t)
                + "</vt:vector></TitlesOfParts>"
                + "<Application>Microsoft Office PowerPoint</Application>"
                + "</Properties>";
    }

    private static String themeXml(PptDoc doc) {
        int accent = doc.themeAccent;
        /* v1.27 FIX: emit the deck's REAL theme fonts (the reader captures
           fontMajor/fontMinor) — hardcoding Calibri changed every inherited
           run's typeface on save */
        String major = doc.fontMajor != null && doc.fontMajor.length() > 0 ? doc.fontMajor : "Calibri";
        String minor = doc.fontMinor != null && doc.fontMinor.length() > 0 ? doc.fontMinor : "Calibri";
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<a:theme xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" name=\"PDFKhwan\">"
                + "<a:themeElements>"
                + "<a:clrScheme name=\"PDFKhwan\">"
                + "<a:dk1><a:sysClr val=\"windowText\" lastClr=\"000000\"/></a:dk1>"
                + "<a:lt1><a:sysClr val=\"window\" lastClr=\"FFFFFF\"/></a:lt1>"
                + "<a:dk2><a:srgbClr val=\"1F497D\"/></a:dk2>"
                + "<a:lt2><a:srgbClr val=\"EEECE1\"/></a:lt2>"
                + "<a:accent1><a:srgbClr val=\"" + hex(accent) + "\"/></a:accent1>"
                + "<a:accent2><a:srgbClr val=\"" + hex(accent) + "\"/></a:accent2>"
                + "<a:accent3><a:srgbClr val=\"9BBB59\"/></a:accent3>"
                + "<a:accent4><a:srgbClr val=\"8064A2\"/></a:accent4>"
                + "<a:accent5><a:srgbClr val=\"4BACC6\"/></a:accent5>"
                + "<a:accent6><a:srgbClr val=\"F79646\"/></a:accent6>"
                + "<a:hlink><a:srgbClr val=\"0000FF\"/></a:hlink>"
                + "<a:folHlink><a:srgbClr val=\"800080\"/></a:folHlink>"
                + "</a:clrScheme>"
                + "<a:fontScheme name=\"PDFKhwan\">"
                + "<a:majorFont><a:latin typeface=\"" + esc(major) + "\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:majorFont>"
                + "<a:minorFont><a:latin typeface=\"" + esc(minor) + "\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:minorFont>"
                + "</a:fontScheme>"
                + "<a:fmtScheme name=\"PDFKhwan\">"
                + "<a:fillStyleLst>"
                + "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>"
                + "<a:solidFill><a:schemeClr val=\"phClr\"><a:tint val=\"60000\"/></a:schemeClr></a:solidFill>"
                + "<a:solidFill><a:schemeClr val=\"phClr\"><a:shade val=\"80000\"/></a:schemeClr></a:solidFill>"
                + "</a:fillStyleLst>"
                + "<a:lnStyleLst>"
                + lnScheme(6350) + lnScheme(12700) + lnScheme(19050)
                + "</a:lnStyleLst>"
                + "<a:effectStyleLst>"
                + "<a:effectStyle><a:effectLst/></a:effectStyle>"
                + "<a:effectStyle><a:effectLst/></a:effectStyle>"
                + "<a:effectStyle><a:effectLst/></a:effectStyle>"
                + "</a:effectStyleLst>"
                + "<a:bgFillStyleLst>"
                + "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>"
                + "<a:solidFill><a:schemeClr val=\"phClr\"><a:tint val=\"95000\"/></a:schemeClr></a:solidFill>"
                + "<a:solidFill><a:schemeClr val=\"phClr\"><a:shade val=\"90000\"/></a:schemeClr></a:solidFill>"
                + "</a:bgFillStyleLst>"
                + "</a:fmtScheme>"
                + "</a:themeElements>"
                + "</a:theme>";
    }

    private static String lnScheme(int w) {
        return "<a:ln w=\"" + w + "\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>"
                + "<a:prstDash val=\"solid\"/></a:ln>";
    }

    private static String presentationXml(PptDoc doc, boolean hasNotes) {
        int n = doc.slides.size();
        StringBuilder ids = new StringBuilder();
        for (int i = 1; i <= n; i++) {
            ids.append("<p:sldId id=\"").append(256 + i).append("\" r:id=\"rId").append(i).append("\"/>");
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<p:presentation xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" "
                + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" "
                + "xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\">"
                + "<p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rId" + (n + 1) + "\"/></p:sldMasterIdLst>"
                + (hasNotes ? "<p:notesMasterIdLst><p:notesMasterId r:id=\"rId" + (n + 2) + "\"/></p:notesMasterIdLst>" : "")
                + "<p:sldIdLst>" + ids + "</p:sldIdLst>"
                + "<p:sldSz cx=\"" + doc.slideW + "\" cy=\"" + doc.slideH + "\"/>"
                + "<p:notesSz cx=\"" + doc.slideH + "\" cy=\"" + doc.slideW + "\"/>"
                + "<p:defaultTextStyle><a:defRPr/></p:defaultTextStyle>"
                + "</p:presentation>";
    }

    private static String presentationRels(int slides, boolean hasNotes) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
          .append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
        for (int i = 1; i <= slides; i++) {
            sb.append("<Relationship Id=\"rId").append(i)
              .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide\" ")
              .append("Target=\"slides/slide").append(i).append(".xml\"/>");
        }
        sb.append("<Relationship Id=\"rId").append(slides + 1)
          .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster\" ")
          .append("Target=\"slideMasters/slideMaster1.xml\"/>");
        if (hasNotes) {
            sb.append("<Relationship Id=\"rId").append(slides + 2)
              .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/notesMaster\" ")
              .append("Target=\"notesMasters/notesMaster1.xml\"/>");
        }
        sb.append("</Relationships>");
        return sb.toString();
    }

    private static String masterXml(PptDoc doc) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<p:sldMaster xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" "
                + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" "
                + "xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\">"
                + "<p:cSld><p:bg><p:bgPr><a:solidFill>" + srgb(doc.themeBgTop) + "</a:solidFill><a:effectLst/></p:bgPr></p:bg>"
                + "<p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>"
                + "<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/>"
                + "<a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr></p:spTree></p:cSld>"
                + "<p:clrMap bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" accent1=\"accent1\" accent2=\"accent2\" "
                + "accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" hlink=\"hlink\" folHlink=\"folHlink\"/>"
                + "<p:sldLayoutIdLst><p:sldLayoutId id=\"2147483649\" r:id=\"rId1\"/></p:sldLayoutIdLst>"
                + "</p:sldMaster>";
    }

    private static String masterRels() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/>"
                + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme\" Target=\"../theme/theme1.xml\"/>"
                + "</Relationships>";
    }

    private static String layoutXml(PptDoc doc) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<p:sldLayout xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" "
                + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" "
                + "xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\" type=\"blank\" preserve=\"1\">"
                + "<p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>"
                + "<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"" + doc.slideW + "\" cy=\"" + doc.slideH + "\"/>"
                + "<a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"" + doc.slideW + "\" cy=\"" + doc.slideH + "\"/></a:xfrm></p:grpSpPr>"
                + "</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>";
    }

    private static String layoutRels() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster\" Target=\"../slideMasters/slideMaster1.xml\"/>"
                + "</Relationships>";
    }

    private static String slideRels(List<String> mediaRefs, List<String> links, int notesNum,
                                    int chartCount, int chartOffset) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
          .append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
          .append("<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/>");
        for (int i = 0; i < mediaRefs.size(); i++) {
            String nm = mediaRefs.get(i);
            /* v1.27 FIX: the media KIND comes from the part-name prefix the
               writer itself assigns (vidN.* = embedded media, imgN.* = image),
               NOT from an extension whitelist — a video with an unknown ext
               (mpg/ogg/…) used to get an IMAGE relationship while the slide
               XML referenced rVidN → dangling r:id → repair prompt. */
            boolean isMedia = nm.startsWith("vid");
            String ridType = isMedia ? "rVid" : "rImg";
            String relType = isMedia
                    ? "http://schemas.openxmlformats.org/officeDocument/2006/relationships/media"
                    : "http://schemas.openxmlformats.org/officeDocument/2006/relationships/image";
            sb.append("<Relationship Id=\"").append(ridType).append(i + 1)
              .append("\" Type=\"").append(relType).append("\" ")
              .append("Target=\"../media/").append(nm).append("\"/>");
        }
        /* v1.22: chart parts */
        for (int i = 0; i < chartCount; i++) {
            sb.append("<Relationship Id=\"rChart").append(chartOffset + i + 1)
              .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/chart\" ")
              .append("Target=\"../charts/chart").append(chartOffset + i + 1).append(".xml\"/>");
        }
        /* v1.21: external hyperlink targets.
           v1.29 NEW: "#slide/N" pseudo-targets become INTERNAL
           relationships (no TargetMode) targeting the written slideN.xml —
           "go to slide" action links round-trip through PowerPoint.
           v1.30 NEW: "#media/URL" pseudo-targets become EXTERNAL media
           relationships so LINKED videos keep their <a:videoFile r:link>. */
        for (int i = 0; i < links.size(); i++) {
            String lk = links.get(i);
            if (lk.startsWith("#media/")) {
                sb.append("<Relationship Id=\"rLink").append(i + 1)
                  .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/media\" ")
                  .append("Target=\"").append(esc(lk.substring("#media/".length())))
                  .append("\" TargetMode=\"External\"/>");
                continue;
            }
            /* v1.35: "#img/URL" — EXTERNAL image relationship so linked
               pictures keep their <a:blip r:link> through saves */
            if (lk.startsWith("#img/")) {
                sb.append("<Relationship Id=\"rLink").append(i + 1)
                  .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" ")
                  .append("Target=\"").append(esc(lk.substring("#img/".length())))
                  .append("\" TargetMode=\"External\"/>");
                continue;
            }
            sb.append("<Relationship Id=\"rLink").append(i + 1)
              .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink\" ");
            if (lk.startsWith("#slide/")) {
                String n = lk.substring("#slide/".length());
                sb.append("Target=\"slide").append(n).append(".xml\"/>");
            } else {
                sb.append("Target=\"").append(esc(lk)).append("\" TargetMode=\"External\"/>");
            }
        }
        /* v1.21: this slide's notesSlide */
        if (notesNum > 0) {
            sb.append("<Relationship Id=\"rNotes")
              .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/notesSlide\" ")
              .append("Target=\"../notesSlides/notesSlide").append(notesNum).append(".xml\"/>");
        }
        sb.append("</Relationships>");
        return sb.toString();
    }

    /* v1.27: kept for compatibility — rel kind is decided by the vid/img
       name prefix in slideRels now */
    static boolean isMediaExt(String ext) {
        return "mp4".equals(ext) || "m4v".equals(ext) || "webm".equals(ext) || "mkv".equals(ext)
                || "3gp".equals(ext) || "3g2".equals(ext) || "mov".equals(ext) || "wmv".equals(ext)
                || "avi".equals(ext) || "mpg".equals(ext) || "mpeg".equals(ext) || "ogg".equals(ext)
                || "mp3".equals(ext) || "m4a".equals(ext) || "wav".equals(ext) || "aac".equals(ext)
                || "flac".equals(ext) || "wma".equals(ext) || "opus".equals(ext) || "amr".equals(ext);
    }

    /* ------------------------------------------------------------ notes parts */
    /* minimal valid notesSlide: one body placeholder carrying the text.
       sz=1200 (12pt) matches PowerPoint's default notes size. */
    private static String notesSlideXml(String notes) {
        StringBuilder paras = new StringBuilder();
        String[] lines = notes.split("\n");
        for (String ln : lines) {
            paras.append("<a:p><a:r><a:rPr lang=\"fa-IR\" sz=\"1200\" dirty=\"0\"/><a:t>")
                 .append(esc(ln)).append("</a:t></a:r></a:p>");
        }
        if (paras.length() == 0) paras.append("<a:p><a:endParaRPr lang=\"fa-IR\"/></a:p>");
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<p:notes xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" "
                + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" "
                + "xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\">"
                + "<p:cSld><p:spTree>"
                + "<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>"
                + "<p:grpSpPr/>"
                + "<p:sp><p:nvSpPr><p:cNvPr id=\"2\" name=\"Notes Placeholder\"/>"
                + "<p:cNvSpPr><a:spLocks noGrp=\"1\"/></p:cNvSpPr>"
                + "<p:nvPr><p:ph type=\"body\" idx=\"1\"/></p:nvPr></p:nvSpPr>"
                + "<p:spPr/>"
                + "<p:txBody><a:bodyPr/><a:lstStyle/>" + paras + "</p:txBody></p:sp>"
                + "</p:spTree></p:cSld>"
                + "<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>"
                + "</p:notes>";
    }

    private static String notesSlideRels(int slideNum) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/notesMaster\" Target=\"../notesMasters/notesMaster1.xml\"/>"
                + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide\" Target=\"../slides/slide" + slideNum + ".xml\"/>"
                + "</Relationships>";
    }

    private static String notesMasterXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<p:notesMaster xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" "
                + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" "
                + "xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\">"
                + "<p:cSld><p:spTree>"
                + "<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>"
                + "<p:grpSpPr/>"
                + "</p:spTree></p:cSld>"
                + "<p:clrMap bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" accent1=\"accent1\" accent2=\"accent2\" "
                + "accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" hlink=\"hlink\" folHlink=\"folHlink\"/>"
                + "<p:notesStyle><a:lvl1pPr><a:defRPr sz=\"1200\"/></a:lvl1pPr>"
                + "<a:lvl2pPr marL=\"0\" indent=\"0\"><a:buNone/><a:defRPr sz=\"1200\"/></a:lvl2pPr>"
                + "</p:notesStyle>"
                + "</p:notesMaster>";
    }

    private static String notesMasterRels() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme\" Target=\"../theme/theme1.xml\"/>"
                + "</Relationships>";
    }

    /* ------------------------------------------------------------ slide XML */
    private static String slideXml(PptDoc.Slide s, int slideW, int slideH,
                                   List<byte[]> mediaOut, List<String> mediaNames,
                                   List<String> links, List<PptDoc.Chart> allCharts,
                                   List<String> chartRids) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
          .append("<p:sld xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" ")
          .append("xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" ")
          .append("xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\" ")
          .append("xmlns:c=\"http://schemas.openxmlformats.org/drawingml/2006/chart\">")
          .append("<p:cSld>");
        /* background */
        if (s.background.type != PptDoc.Fill.NONE) {
            sb.append("<p:bg><p:bgPr>").append(fillXml(s.background)).append("<a:effectLst/></p:bgPr></p:bg>");
        }
        sb.append("<p:spTree>")
          .append("<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>")
          .append("<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"").append(slideW)
          .append("\" cy=\"").append(slideH).append("\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"")
          .append(slideW).append("\" cy=\"").append(slideH).append("\"/></a:xfrm></p:grpSpPr>");

        int id = 2;
        /* baked master/layout chrome first (beneath editable shapes) so
           re-saved decks keep the same visual */
        for (PptDoc.Shape sh : s.bgShapes) {
            String rid = null, vidRid = null, chartRid = null;
            if (sh instanceof PptDoc.Picture) {
                PptDoc.Picture pic = (PptDoc.Picture) sh;
                if (pic.imgData != null) {
                    /* v1.27 FIX: sniff the REAL format from bytes — a wrong
                       declared format used to write imgN.png parts holding
                       JPEG data (content-type mismatch) */
                    String fmt = sniffImg(pic.imgData);
                    String ext = "jpg".equals(fmt) ? "jpeg" : fmt;
                    String name = "img" + (mediaOut.size() + 1) + "." + ext;
                    mediaOut.add(pic.imgData);
                    mediaNames.add(name);
                    rid = "rImg" + mediaOut.size();
                } else if (pic.imgUrl == null || pic.imgUrl.length() == 0) {
                    /* v1.36: bytes lost (unsupported/unreadable source) — keep
                       the frame alive with the shared placeholder instead of a
                       bare <a:blip/> that deletes the picture on save */
                    rid = ensurePlaceholder(mediaOut, mediaNames);
                }
            } else if (sh.picFillData != null) {
                String pfExt = "jpg".equals(sh.picFillFormat) ? "jpeg" : sh.picFillFormat;
                String name = "img" + (mediaOut.size() + 1) + "." + pfExt;
                mediaOut.add(sh.picFillData);
                mediaNames.add(name);
                rid = "rImg" + mediaOut.size();
            }
            /* v1.29 FIX: charts (and posters) baked into the chrome used to
               be written with a DANGLING r:id="" — a chart on a layout or
               master triggered the PowerPoint repair prompt. Register the
               chart part / media exactly like the editable-shape loop does. */
            if (sh instanceof PptDoc.Chart) {
                allCharts.add((PptDoc.Chart) sh);
                chartRid = "rChart" + allCharts.size();
            } else if (sh instanceof PptDoc.Video) {
                PptDoc.Video vid = (PptDoc.Video) sh;
                if (vid.videoData != null) {
                    String name = "vid" + (mediaOut.size() + 1) + "." + vid.videoFormat;
                    mediaOut.add(vid.videoData);
                    mediaNames.add(name);
                    vidRid = "rVid" + mediaOut.size();
                }
                if (vid.posterData != null) {
                    String pext = "jpg".equals(sniffImg(vid.posterData)) ? "jpeg" : sniffImg(vid.posterData);
                    String pname = "img" + (mediaOut.size() + 1) + "." + pext;
                    mediaOut.add(vid.posterData);
                    mediaNames.add(pname);
                    rid = "rImg" + mediaOut.size();
                }
            }
            sb.append(shapeXml(sh, id++, rid, vidRid, chartRid, links));
        }
        for (PptDoc.Shape sh : s.shapes) {
            String rid = null, vidRid = null, chartRid = null;
            if (sh instanceof PptDoc.Picture) {
                PptDoc.Picture pic = (PptDoc.Picture) sh;
                if (pic.imgData != null) {
                    /* v1.27 FIX: sniff the REAL format from bytes */
                    String fmt = sniffImg(pic.imgData);
                    String ext = "jpg".equals(fmt) ? "jpeg" : fmt;
                    String name = "img" + (mediaOut.size() + 1) + "." + ext;
                    mediaOut.add(pic.imgData);
                    mediaNames.add(name);
                    rid = "rImg" + mediaOut.size();
                } else if (pic.imgUrl == null || pic.imgUrl.length() == 0) {
                    /* v1.36: keep the frame — see ensurePlaceholder */
                    rid = ensurePlaceholder(mediaOut, mediaNames);
                }
            } else if (sh instanceof PptDoc.Video) {
                PptDoc.Video vid = (PptDoc.Video) sh;
                if (vid.videoData != null) {
                    String name = "vid" + (mediaOut.size() + 1) + "." + vid.videoFormat;
                    mediaOut.add(vid.videoData);
                    mediaNames.add(name);
                    vidRid = "rVid" + mediaOut.size();
                }
                if (vid.posterData != null) {
                    String pext = "jpg".equals(sniffImg(vid.posterData)) ? "jpeg" : sniffImg(vid.posterData);
                    String pname = "img" + (mediaOut.size() + 1) + "." + pext;
                    mediaOut.add(vid.posterData);
                    mediaNames.add(pname);
                    rid = "rImg" + mediaOut.size();
                }
            } else if (sh instanceof PptDoc.Chart) {
                allCharts.add((PptDoc.Chart) sh);
                chartRid = "rChart" + allCharts.size();
                chartRids.add(chartRid);
            }
            /* v1.27: picture-fill media rel for any shape carrying one */
            if (rid == null && sh.picFillData != null) {
                String pfExt = "jpg".equals(sh.picFillFormat) ? "jpeg" : sh.picFillFormat;
                String name = "img" + (mediaOut.size() + 1) + "." + pfExt;
                mediaOut.add(sh.picFillData);
                mediaNames.add(name);
                rid = "rImg" + mediaOut.size();
            }
            sb.append(shapeXml(sh, id++, rid, vidRid, chartRid, links));
        }
        sb.append("</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>");
        /* v1.22: slide transition round-trip */
        if (s.transition != null && s.transition.length() > 0) {
            sb.append("<p:transition spd=\"med\">").append(transitionXml(s.transition))
              .append("</p:transition>");
        }
        /* v1.23: entrance animations + auto-advance seconds — persisted via
           p:extLst (unknown ext URIs are ignored by PowerPoint per spec,
           no repair prompt; round-trips through our own reader) */
        boolean anyAnim = s.autoSec > 0f;
        for (int ai = 0; ai < s.shapes.size(); ai++) {
            if (s.shapes.get(ai).anim != 0) { anyAnim = true; break; }
        }
        if (anyAnim) {
            StringBuilder ex = new StringBuilder();
            ex.append("<p:extLst><p:ext uri=\"{5A0E2C91-8F4B-4D3A-9E7C-6F1D2B3A4C5D}\">")
              .append("<pk:anims xmlns:pk=\"https://pdfkhwan.app/anim\"");
            if (s.autoSec > 0f) ex.append(" auto=\"").append(String.valueOf(s.autoSec)).append("\"");
            ex.append(">");
            for (int ai = 0; ai < s.shapes.size(); ai++) {
                PptDoc.Shape ash = s.shapes.get(ai);
                if (ash.anim != 0) {
                    /* v1.29 FIX: ix must index the WRITTEN spTree order —
                       bgShapes are emitted BEFORE editable shapes, so the
                       reader (which sees one merged list) resolves ix against
                       shapes[ix - bgShapes.size()] — animations used to attach
                       to the WRONG shapes whenever chrome was baked in. */
                    ex.append("<pk:s ix=\"").append(s.bgShapes.size() + ai).append("\" id=\"")
                      .append(esc(ash.name == null ? "" : ash.name)).append("\" a=\"")
                      .append(ash.anim).append("\"/>");
                }
            }
            ex.append("</pk:anims></p:ext></p:extLst>");
            sb.append(ex);
        }
        sb.append("</p:sld>");
        return sb.toString();
    }

    private static String transitionXml(String t) {
        if ("push".equals(t)) return "<p:push dir=\"l\"/>";
        if ("wipe".equals(t)) return "<p:wipe dir=\"l\"/>";
        if ("cover".equals(t)) return "<p:cover dir=\"l\"/>";
        if ("pull".equals(t)) return "<p:pull dir=\"l\"/>";
        if ("cut".equals(t)) return "<p:cut/>";
        if ("dissolve".equals(t)) return "<p:dissolve/>";
        if ("split".equals(t)) return "<p:split orient=\"horz\" dir=\"out\"/>";
        if ("wheel".equals(t)) return "<p:wheel spokes=\"4\"/>";
        if ("zoom".equals(t)) return "<p:zoom dir=\"in\"/>";
        if ("newsflash".equals(t)) return "<p:newsflash/>";
        if ("checker".equals(t)) return "<p:checker dir=\"horz\"/>";
        if ("blinds".equals(t)) return "<p:blinds dir=\"vert\"/>";
        if ("comb".equals(t)) return "<p:comb dir=\"horz\"/>";
        if ("randomBar".equals(t)) return "<p:randomBar dir=\"vert\"/>";
        if ("strips".equals(t)) return "<p:strips dir=\"ld\"/>";
        if ("wedge".equals(t)) return "<p:wedge/>";
        if ("random".equals(t)) return "<p:random/>";
        /* v1.31 FIX: base-schema transitions the reader knows but the writer
           silently replaced with <p:fade/> */
        if ("circle".equals(t)) return "<p:circle/>";
        if ("diamond".equals(t)) return "<p:diamond/>";
        if ("plus".equals(t)) return "<p:plus/>";
        /* v1.31 FIX: PowerPoint-2010+ (p14) transitions — the reader keeps
           their local names but the writer degraded EVERY one of them to
           fade, so a real deck (fa-namaz.ppsx: p14:warp, p14:pan) changed
           its transitions on every save. PowerPoint itself writes these as
           p14:* children of <p:transition>; re-emit exactly that shape
           (namespace declared inline = schema-valid, no repair prompt). */
        final String P14 = " xmlns:p14=\"http://schemas.microsoft.com/office/powerpoint/2010/main\"";
        if ("warp".equals(t)) return "<p14:warp" + P14 + " dir=\"in\"/>";
        if ("warpReverse".equals(t)) return "<p14:warp" + P14 + " dir=\"out\"/>";
        if ("pan".equals(t)) return "<p14:pan" + P14 + " dir=\"l\"/>";
        if ("ferris".equals(t)) return "<p14:ferris" + P14 + " dir=\"l\"/>";
        if ("gallery".equals(t)) return "<p14:gallery" + P14 + " dir=\"l\"/>";
        if ("conveyor".equals(t)) return "<p14:conveyor" + P14 + " dir=\"l\"/>";
        if ("switch".equals(t)) return "<p14:switch" + P14 + " dir=\"l\"/>";
        if ("flip".equals(t)) return "<p14:flip" + P14 + " dir=\"l\"/>";
        if ("ripple".equals(t)) return "<p14:ripple" + P14 + " dir=\"ld\"/>";
        if ("honeycomb".equals(t)) return "<p14:honeycomb" + P14 + "/>";
        if ("glitter".equals(t)) return "<p14:glitter" + P14 + " dir=\"l\" pattern=\"diamond\"/>";
        if ("prism".equals(t)) return "<p14:prism" + P14 + " isContent=\"1\" isInverted=\"0\"/>";
        if ("vortex".equals(t)) return "<p14:vortex" + P14 + " dir=\"l\"/>";
        if ("doors".equals(t)) return "<p14:doors" + P14 + " dir=\"vert\"/>";
        if ("window".equals(t)) return "<p14:window" + P14 + " dir=\"vert\"/>";
        if ("reveal".equals(t)) return "<p14:reveal" + P14 + " thruBlk=\"0\" dir=\"l\"/>";
        if ("wheelReverse".equals(t)) return "<p14:wheelReverse" + P14 + "/>";
        return "<p:fade/>";
    }

    /* v1.26: one source of truth — delegate to the reader's sniffer so
       poster/extension naming can never drift from the reader again. */
    private static String sniffImg(byte[] b) {
        return PptxReader.sniffImgFormat(b);
    }

    private static String shapeXml(PptDoc.Shape sh, int id, String imgRid, String vidRid,
                                    String chartRid, List<String> links) {
        StringBuilder sb = new StringBuilder();
        if (sh instanceof PptDoc.Video) {
            /* v1.22: embedded video/audio — p:pic + a:videoFile + poster */
            PptDoc.Video vid = (PptDoc.Video) sh;
            sb.append("<p:pic>")
              .append("<p:nvPicPr><p:cNvPr id=\"").append(id).append("\" name=\"")
              .append(esc(vid.videoName == null || vid.videoName.length() == 0 ? "Media " + id : vid.videoName)).append("\">")
              .append("<a:hlinkClick r:id=\"\" action=\"ppaction://media\"/></p:cNvPr>")
              .append("<p:cNvPicPr><a:picLocks noChangeAspect=\"1\"/></p:cNvPicPr>")
              .append("<p:nvPr>");
            boolean isAudio = vid.mimeType != null && vid.mimeType.startsWith("audio");
            /* v7 FIX: <a:videoFile/> without r:link is schema-INVALID (r:link
               is use="required") — PowerPoint shows the repair dialog. Only
               emit the element when we actually have a relationship. */
            if (vidRid != null) {
                sb.append(isAudio ? "<a:audioFile" : "<a:videoFile");
                sb.append(" r:link=\"").append(vidRid).append("\"");
                sb.append("/>");
            }
            sb.append("</p:nvPr></p:nvPicPr>")
              .append("<p:blipFill>");
            if (imgRid != null) {
                sb.append("<a:blip r:embed=\"").append(imgRid).append("\"/>");
            } else {
                sb.append("<a:blip/>");
            }
            sb.append("<a:stretch><a:fillRect/></a:stretch></p:blipFill>")
              .append("<p:spPr>").append(xfrmXml(vid))
              .append("<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom>")
              /* v1.30 FIX: video poster fill/line round-trip like pictures */
              .append(fillXml(vid.fill))
              .append(lnXml(vid.line))
              /* v1.31 FIX: the video's drop shadow never round-tripped
                 (<a:effectLst> was only written for sp shapes) */
              .append(shadowXml(vid.shadow))
              .append("</p:spPr>")
              .append("</p:pic>");
            /* v1.30 FIX: LINKED media — emit the external relationship reference
               so a linked (non-embedded) video survives the round-trip as a
               video instead of degrading to a picture. The "#media/URL"
               pseudo-target is converted to an External media relationship by
               slideRels(). */
            if (vidRid == null && vid.videoUrl != null && vid.videoUrl.length() > 0 && links != null) {
                String target = "#media/" + vid.videoUrl;
                int li = links.indexOf(target);
                if (li < 0) { li = links.size(); links.add(target); }
                boolean linkedAudio = vid.mimeType != null && vid.mimeType.startsWith("audio");
                /* the a:videoFile element belongs INSIDE <p:nvPr> */
                int nvPrEnd = sb.lastIndexOf("</p:nvPr>");
                if (nvPrEnd >= 0) {
                    sb.insert(nvPrEnd,
                            (linkedAudio ? "<a:audioFile" : "<a:videoFile")
                                    + " r:link=\"rLink" + (li + 1) + "\"" + "/>");
                }
            }
            return sb.toString();
        }
        if (sh instanceof PptDoc.Chart) {
            /* v1.22: chart graphicFrame → ppt/charts/chartN.xml */
            PptDoc.Chart ch = (PptDoc.Chart) sh;
            sb.append("<p:graphicFrame>")
              .append("<p:nvGraphicFramePr><p:cNvPr id=\"").append(id).append("\" name=\"Chart ").append(id)
              .append("\"/><p:cNvGraphicFramePr/><p:nvPr/></p:nvGraphicFramePr>")
              /* v1.24: charts keep their rotation (p:xfrm/@rot) */
              .append("<p:xfrm")
              .append(ch.rot != 0 ? " rot=\"" + ch.rot + "\"" : "")
              .append("><a:off x=\"").append(ch.x).append("\" y=\"").append(ch.y)
              .append("\"/><a:ext cx=\"").append(Math.max(1, ch.w)).append("\" cy=\"").append(Math.max(1, ch.h)).append("\"/></p:xfrm>")
              .append("<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/chart\">")
              .append("<c:chart xmlns:c=\"http://schemas.openxmlformats.org/drawingml/2006/chart\" ")
              .append("xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" ")
              .append("r:id=\"").append(chartRid == null ? "" : chartRid).append("\"/>")
              .append("</a:graphicData></a:graphic></p:graphicFrame>");
            return sb.toString();
        }
        if (sh instanceof PptDoc.Picture) {
            PptDoc.Picture pic = (PptDoc.Picture) sh;
            sb.append("<p:pic>")
              .append("<p:nvPicPr><p:cNvPr id=\"").append(id).append("\" name=\"")
              /* v1.31 FIX: keep the author's shape name (was hardcoded
                 "Picture N", so every save renamed the author's objects) */
              .append(esc(pic.name != null && pic.name.length() > 0 ? pic.name : "Picture " + id)).append("\"/>")
              .append("<p:cNvPicPr><a:picLocks noChangeAspect=\"1\"/></p:cNvPicPr><p:nvPr/></p:nvPicPr>")
              .append("<p:blipFill>");
            /* v7 FIX: a null imgRid produced the literal r:embed="null" →
               invalid rels → PowerPoint repair prompt */
            if (imgRid != null) {
                sb.append("<a:blip r:embed=\"").append(imgRid).append("\"/>");
            } else if (pic.imgUrl != null && pic.imgUrl.length() > 0 && links != null) {
                /* v1.35 FIX (حذف تصاویر پیوندی): a picture whose bytes are not
                   in the package (linked image, TargetMode=External) used to be
                   re-emitted as a bare <a:blip/> — the link was DELETED and
                   the picture vanished from the file on every save. Emit the
                   external image relationship instead; the "#img/URL"
                   pseudo-target is converted by slideRels(). */
                String target = "#img/" + pic.imgUrl;
                int li = links.indexOf(target);
                if (li < 0) { li = links.size(); links.add(target); }
                sb.append("<a:blip r:link=\"rLink").append(li + 1).append("\"/>");
            } else {
                sb.append("<a:blip/>");
            }
            /* v1.31 FIX: negative srcRect crops (the image extends BEYOND its
               frame — PowerPoint's "zoom out" crop, real fa-namaz.ppsx frames
               carry -0.3%) used to be dropped by the "> 0" test and re-opened
               as uncropped; any NON-ZERO crop now round-trips */
            boolean crop = pic.cropL != 0 || pic.cropT != 0 || pic.cropR != 0 || pic.cropB != 0;
            if (crop) {
                sb.append("<a:srcRect l=\"").append(Math.round(pic.cropL * 100000))
                  .append("\" t=\"").append(Math.round(pic.cropT * 100000))
                  .append("\" r=\"").append(Math.round(pic.cropR * 100000))
                  .append("\" b=\"").append(Math.round(pic.cropB * 100000)).append("\"/>");
            }
            sb.append("<a:stretch><a:fillRect/></a:stretch></p:blipFill>")
              .append("<p:spPr>").append(xfrmXml(pic))
              /* v1.30 FIX: picture frame geometry + background fill + outline
                 round-trip (were hardcoded to rect / dropped) */
              .append("<a:prstGeom prst=\"").append(prstName(pic.type)).append("\"><a:avLst/></a:prstGeom>")
              .append(fillXml(pic.fill))
              .append(lnXml(pic.line))
              /* v1.31 FIX: picture drop shadows were silently dropped on every
                 save (real decks: alterman_security.png frames with shadow
                 re-opened shadowless) */
              .append(shadowXml(pic.shadow))
              .append("</p:spPr>")
              .append("</p:pic>");
            return sb.toString();
        }
        if (sh instanceof PptDoc.Table) {
            PptDoc.Table t = (PptDoc.Table) sh;
            sb.append("<p:graphicFrame>")
              .append("<p:nvGraphicFramePr><p:cNvPr id=\"").append(id).append("\" name=\"Table ").append(id).append("\"/>")
              .append("<p:cNvGraphicFramePr><a:graphicFrameLocks noGrp=\"1\"/></p:cNvGraphicFramePr><p:nvPr/></p:nvGraphicFramePr>")
              /* v1.24: tables keep their rotation (p:xfrm/@rot) */
              .append("<p:xfrm")
              .append(t.rot != 0 ? " rot=\"" + t.rot + "\"" : "")
              .append("><a:off x=\"").append(t.x).append("\" y=\"").append(t.y)
              .append("\"/><a:ext cx=\"").append(t.w).append("\" cy=\"").append(t.h).append("\"/></p:xfrm>")
              .append("<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/table\">")
              .append("<a:tbl><a:tblPr firstRow=\"").append(t.firstRow ? "1" : "0")
              .append("\" bandRow=\"").append(t.bandRow ? "1" : "0")
              .append("\"><a:tableStyleId>{5940675A-B579-460E-94D1-54222C63F5DA}</a:tableStyleId></a:tblPr>")
              .append("<a:tblGrid>");
            for (float cw : t.colWidths) {
                sb.append("<a:gridCol w=\"").append(Math.round(cw)).append("\"/>");
            }
            sb.append("</a:tblGrid>");
            for (PptDoc.TableRow r : t.rows) {
                sb.append("<a:tr h=\"").append(Math.round(r.heightEmu)).append("\">");
                for (PptDoc.TableCell c : r.cells) {
                    sb.append("<a:tc");
                    if (c.spanR > 1) sb.append(" gridSpan=\"").append(c.spanR).append("\"");
                    if (c.spanD > 1) sb.append(" rowSpan=\"").append(c.spanD).append("\"");
                    if (c.skipR) sb.append(" hMerge=\"1\"");
                    if (c.skipD) sb.append(" vMerge=\"1\"");
                    sb.append(">");
                    /* v1.22 BUGFIX: ECMA-376 CT_TableCell requires the
                       paragraphs inside <a:txBody> (txBody THEN tcPr).
                       The old writer emitted bare <a:p> + tcPr first, so
                       table text was LOST on save/reload round-trips. */
                    sb.append("<a:txBody><a:bodyPr/><a:lstStyle/>");
                    if (c.paras.isEmpty()) {
                        sb.append("<a:p><a:endParaRPr lang=\"fa-IR\"/></a:p>");
                    } else {
                        for (PptDoc.Para para : c.paras) sb.append(paraXml(para, true, links, sh.textShadow));
                    }
                    sb.append("</a:txBody>");
                    /* v1.27: cell margins, anchor and BORDERS round-trip in
                       a:tcPr (spec order: lnL,lnR,lnT,lnB then fills then
                       margins/anchor attrs on tcPr itself) */
                    boolean anyEdge = false;
                    for (PptDoc.Line e : c.edges) { if (e != null && e.enabled) { anyEdge = true; break; } }
                    boolean defMargins = c.insetL == 91440f && c.insetR == 91440f && c.insetT == 45720f && c.insetB == 45720f;
                    if (c.fill.type != PptDoc.Fill.NONE || anyEdge || c.anchor >= 0 || !defMargins) {
                        sb.append("<a:tcPr");
                        if (!defMargins) {
                            sb.append(" marL=\"").append(Math.round(c.insetL))
                              .append("\" marR=\"").append(Math.round(c.insetR))
                              .append("\" marT=\"").append(Math.round(c.insetT))
                              .append("\" marB=\"").append(Math.round(c.insetB)).append("\"");
                        }
                        if (c.anchor == 1) sb.append(" anchor=\"ctr\"");
                        else if (c.anchor == 2) sb.append(" anchor=\"b\"");
                        else if (c.anchor == 0) sb.append(" anchor=\"t\"");
                        sb.append(">");
                        /* v1.29 FIX: TableCell.edges is L,T,R,B (the reader's
                           lnL->0/lnT->1/lnR->2/lnB->3 contract) — the old
                           {"lnL","lnR","lnT","lnB"} order wrote TOP borders as
                           lnR and RIGHT borders as lnT: every save round-trip
                           swapped a cell's top and right borders. */
                        String[] edgeNames = {"lnL", "lnT", "lnR", "lnB"};
                        boolean anyEdgeP = false;
                        for (int ei = 0; ei < 4; ei++) {
                            if (c.edges[ei] != null) { anyEdgeP = true; break; }
                        }
                        for (int ei = 0; ei < 4; ei++) {
                            PptDoc.Line e = c.edges[ei];
                            if (e != null && e.enabled) {
                                sb.append("<a:").append(edgeNames[ei]).append(" w=\"")
                                  .append(Math.max(0, Math.round(e.widthPt * 12700f))).append("\">")
                                  .append("<a:solidFill>").append(srgb(e.color)).append("</a:solidFill>")
                                  .append("</a:").append(edgeNames[ei]).append(">");
                            } else if (anyEdgeP && e != null) {
                                /* v1.31 FIX: a border the author EXPLICITLY
                                   removed (<a:lnL><a:noFill/>) must stay
                                   removed — writing nothing let the table
                                   style re-supply a visible border in
                                   PowerPoint; disabled edges now round-trip
                                   as explicit noFill like enabled ones */
                                sb.append("<a:").append(edgeNames[ei]).append(" w=\"")
                                  .append(Math.max(0, Math.round(e.widthPt * 12700f))).append("\">")
                                  .append("<a:noFill/>")
                                  .append("</a:").append(edgeNames[ei]).append(">");
                            }
                        }
                        if (c.fill.type != PptDoc.Fill.NONE) sb.append(fillXml(c.fill));
                        sb.append("</a:tcPr>");
                    }
                    sb.append("</a:tc>");
                }
                sb.append("</a:tr>");
            }
            sb.append("</a:tbl></a:graphicData></a:graphic></p:graphicFrame>");
            return sb.toString();
        }
        /* sp: connector, autoshape or text box */
        boolean connector = sh instanceof PptDoc.Connector;
        int geomType = connector ? PptDoc.Shape.TYPE_LINE
                : (sh instanceof PptDoc.AutoShape ? ((PptDoc.AutoShape) sh).type : PptDoc.Shape.TYPE_RECT);
        boolean hasParas = sh instanceof PptDoc.TextShape
                ? !((PptDoc.TextShape) sh).paras.isEmpty()
                : (sh instanceof PptDoc.AutoShape && !((PptDoc.AutoShape) sh).paras.isEmpty());
        /* v1.27 FIX: only a real TEXT BOX (KIND_TEXT) is a txBox — a
           text-less rectangle AUTOSHAPE used to morph into a text box on
           save and changed type in PowerPoint */
        boolean isTxBox = sh instanceof PptDoc.TextShape;

        sb.append(connector ? "<p:cxnSp>" : "<p:sp>");
        if (connector) {
            sb.append("<p:nvCxnSpPr><p:cNvPr id=\"").append(id).append("\" name=\"Connector ").append(id).append("\"/>")
              .append("<p:cNvCxnSpPr/>").append("<p:nvPr/></p:nvCxnSpPr>");
        } else {
            sb.append("<p:nvSpPr><p:cNvPr id=\"").append(id).append("\" name=\"Shape ").append(id).append("\"/>")
              .append("<p:cNvSpPr").append(isTxBox ? " txBox=\"1\"" : "").append("/>").append("<p:nvPr/></p:nvSpPr>");
        }
        sb.append("<p:spPr>").append(xfrmXml(sh));
        /* v1.27: freeform geometry round-trips as a real custGeom polygon */
        boolean custGeom = sh instanceof PptDoc.AutoShape
                && ((PptDoc.AutoShape) sh).pathX != null && ((PptDoc.AutoShape) sh).pathX.length >= 3;
        if (!connector) {
            if (custGeom) {
                PptDoc.AutoShape a = (PptDoc.AutoShape) sh;
                sb.append("<a:custGeom><a:avLst/><a:gdLst/><a:ahLst/><a:rect l=\"0\" t=\"0\" r=\"0\" b=\"0\"/>")
                  .append("<a:pathLst><a:path w=\"100000\" h=\"100000\"")
                  .append(a.pathClosed ? ">" : " fill=\"none\">");
                for (int pi = 0; pi < a.pathX.length; pi++) {
                    long px = Math.round(a.pathX[pi] * 100000.0);
                    long py = Math.round(a.pathY[pi] * 100000.0);
                    if (pi == 0) {
                        sb.append("<a:moveTo><a:pt x=\"").append(px).append("\" y=\"").append(py).append("\"/></a:moveTo>");
                    } else {
                        sb.append("<a:lnTo><a:pt x=\"").append(px).append("\" y=\"").append(py).append("\"/></a:lnTo>");
                    }
                }
                if (a.pathClosed) sb.append("<a:close/>");
                sb.append("</a:path></a:pathLst></a:custGeom>");
            } else {
                sb.append("<a:prstGeom prst=\"").append(prstName(geomType)).append("\">");
                /* v1.27 FIX: shape adjustments round-trip — roundRect corner
                   radius etc. were reset to the default on every save */
                if (sh instanceof PptDoc.AutoShape) {
                    PptDoc.AutoShape a = (PptDoc.AutoShape) sh;
                    if (a.type == PptDoc.Shape.TYPE_ROUND_RECT
                            && Math.abs(a.adj - 0.16667f) > 0.0005f) {
                        sb.append("<a:avLst><a:gd name=\"adj\" fmla=\"val ")
                          .append(Math.round(a.adj * 50000f)).append("\"/></a:avLst>");
                    } else {
                        sb.append("<a:avLst/>");
                    }
                } else {
                    sb.append("<a:avLst/>");
                }
                sb.append("</a:prstGeom>");
            }
        } else {
            sb.append("<a:prstGeom prst=\"line\"><a:avLst/></a:prstGeom>");
        }
        /* v1.27: picture fill on the shape (spPr/a:blipFill) round-trips */
        if (sh.picFillData != null && imgRid != null) {
            sb.append("<a:blipFill><a:blip r:embed=\"").append(imgRid)
              .append("\"/><a:stretch><a:fillRect/></a:stretch></a:blipFill>");
        } else {
            /* v1.29 FIX: EG_FillProperties is an XSD CHOICE — at most ONE fill
               element per spPr. blipFill used to be followed by the regular
               fillXml (a:noFill), schema-invalid XML that makes PowerPoint
               show the repair dialog on photo-filled shapes. */
            sb.append(fillXml(sh.fill));
        }
        sb.append(lnXml(sh.line));
        /* v1.23: shape drop shadow (spPr/a:effectLst — after ln, spec order) */
        if (sh.shadow) {
            sb.append("<a:effectLst><a:outerShdw blurRad=\"63500\" dist=\"25400\" dir=\"2700000\" rotWithShape=\"0\"><a:srgbClr val=\"000000\"><a:alpha val=\"42000\"/></a:srgbClr></a:outerShdw></a:effectLst>");
        }
        sb.append("</p:spPr>");
        if (!connector) {
            PptDoc.BodyPr body = sh instanceof PptDoc.TextShape ? ((PptDoc.TextShape) sh).body
                    : (sh instanceof PptDoc.AutoShape ? ((PptDoc.AutoShape) sh).body : new PptDoc.BodyPr());
            List<PptDoc.Para> paras = sh instanceof PptDoc.TextShape ? ((PptDoc.TextShape) sh).paras
                    : (sh instanceof PptDoc.AutoShape ? ((PptDoc.AutoShape) sh).paras : null);
            sb.append("<p:txBody>");
            sb.append("<a:bodyPr");
            sb.append(" lIns=\"").append(Math.round(body.insetL)).append("\"");
            sb.append(" tIns=\"").append(Math.round(body.insetT)).append("\"");
            sb.append(" rIns=\"").append(Math.round(body.insetR)).append("\"");
            sb.append(" bIns=\"").append(Math.round(body.insetB)).append("\"");
            if (body.anchor == 1) sb.append(" anchor=\"ctr\"");
            else if (body.anchor == 2) sb.append(" anchor=\"b\"");
            if (!body.wrap) sb.append(" wrap=\"none\"");
            /* v1.27: vertical text + text columns round-trip */
            if (body.vert == 1) sb.append(" vert=\"vert270\"");
            else if (body.vert == 2) sb.append(" vert=\"vert\"");
            else if (body.vert == 3) sb.append(" vert=\"eaVert\"");
            if (body.numCol > 1) sb.append(" numCol=\"").append(Math.max(2, Math.min(16, body.numCol))).append("\"");
            /* v1.27 FIX: rtlCol was forced on EVERY text body — LTR decks
            re-opened with wrong default direction in PowerPoint */
            boolean anyRtl = false;
            if (paras != null) {
                for (PptDoc.Para pr : paras) { if (pr.rtl) { anyRtl = true; break; } }
            }
            sb.append(anyRtl ? " rtlCol=\"1\">" : ">");
            if (body.autofit == 1) {
                if (body.fontScale != 1f) {
                    sb.append("<a:normAutofit fontScale=\"")
                      .append(Math.round(body.fontScale * 100000f)).append("\"");
                    if (body.lnSpcReduction > 0f) {
                        sb.append(" lnSpcReduction=\"")
                          .append(Math.round(body.lnSpcReduction * 100000f)).append("\"");
                    }
                    sb.append("/>");
                } else {
                    sb.append("<a:normAutofit/>");
                }
            }
            else if (body.autofit == 2) sb.append("<a:spAutoFit/>");
            sb.append("</a:bodyPr>");
            sb.append("<a:lstStyle/>");
            if (paras == null || paras.isEmpty()) {
                sb.append("<a:p><a:endParaRPr lang=\"fa-IR\"/></a:p>");
            } else {
                for (PptDoc.Para para : paras) sb.append(paraXml(para, false, links, sh.textShadow));
            }
            sb.append("</p:txBody>");
        }
        sb.append(connector ? "</p:cxnSp>" : "</p:sp>");
        return sb.toString();
    }

    private static String xfrmXml(PptDoc.Shape sh) {
        StringBuilder sb = new StringBuilder();
        sb.append("<a:xfrm");
        if (sh.rot != 0) sb.append(" rot=\"").append(sh.rot).append("\"");
        if (sh.flipH) sb.append(" flipH=\"1\"");
        if (sh.flipV) sb.append(" flipV=\"1\"");
        /* v1.27 FIX: connectors legally carry cx=0/cy=0 (purely vertical/
           horizontal lines) — clamping to 1 distorted them; only OTHER
           shapes get the >=1 guard */
        int minW = sh instanceof PptDoc.Connector ? 0 : 1;
        sb.append("><a:off x=\"").append(sh.x).append("\" y=\"").append(sh.y).append("\"/>")
          .append("<a:ext cx=\"").append(Math.max(minW, sh.w)).append("\" cy=\"").append(Math.max(minW, sh.h)).append("\"/></a:xfrm>");
        return sb.toString();
    }

    private static String fillXml(PptDoc.Fill f) {
        switch (f.type) {
            case PptDoc.Fill.SOLID:
                return "<a:solidFill>" + srgb(f.color) + "</a:solidFill>";
            case PptDoc.Fill.GRADIENT: {
                String ang;
                if (f.gradAngleDeg >= 0f) {
                    ang = String.valueOf(Math.round(f.gradAngleDeg * 60000f));
                } else {
                    ang = f.gradHorizontal ? "0" : "5400000";
                }
                return "<a:gradFill><a:gsLst>"
                        + "<a:gs pos=\"0\">" + srgb(f.color) + "</a:gs>"
                        + "<a:gs pos=\"100000\">" + srgb(f.color2) + "</a:gs>"
                        + "</a:gsLst><a:lin ang=\"" + ang + "\" scaled=\"0\"/></a:gradFill>";
            }
            default:
                return "<a:noFill/>";
        }
    }

    /* v1.31: shared outer-shadow emitter (sp + pic shapes) */
    private static String shadowXml(boolean on) {
        if (!on) return "";
        return "<a:effectLst><a:outerShdw blurRad=\"63500\" dist=\"25400\" dir=\"2700000\" rotWithShape=\"0\">"
                + "<a:srgbClr val=\"000000\"><a:alpha val=\"42000\"/></a:srgbClr></a:outerShdw></a:effectLst>";
    }

    private static String lnXml(PptDoc.Line l) {
        /* v1.31 FIX: always emit w + prstDash — a DISABLED line
           (<a:ln><a:noFill/>) used to lose BOTH its width and its dash
           pattern, so a hidden 1pt sysDash outline re-read as a solid
           0.75pt default and enabling it back gave the wrong pen */
        int w = Math.max(3175, Math.round(l.widthPt * 12700f));
        String dash = "<a:prstDash val=\"" + dashVal(l.dash) + "\"/>";
        if (!l.enabled) return "<a:ln w=\"" + w + "\"><a:noFill/>" + dash + "</a:ln>";
        return "<a:ln w=\"" + w + "\">" + fillXml(PptDoc.Fill.solid(l.color))
                + "<a:prstDash val=\"" + dashVal(l.dash) + "\"/></a:ln>";
    }

    private static String paraXml(PptDoc.Para para, boolean inCell, List<String> links, boolean tshadow) {
        StringBuilder sb = new StringBuilder();
        sb.append("<a:p><a:pPr");
        String algn = "ctr";
        switch (para.align) {
            case PptDoc.Para.ALIGN_CTR: algn = "ctr"; break;
            case PptDoc.Para.ALIGN_R: algn = "r"; break;
            case PptDoc.Para.ALIGN_JUST: algn = "just"; break;
            case PptDoc.Para.ALIGN_L: algn = "l"; break;
        }
        sb.append(" algn=\"").append(algn).append("\"");
        if (para.rtl) sb.append(" rtl=\"1\"");
        if (para.lvl > 0) sb.append(" lvl=\"").append(para.lvl).append("\"");
        if (para.marL != 0) sb.append(" marL=\"").append(para.marL).append("\"");
        if (para.indent != 0) sb.append(" indent=\"").append(para.indent).append("\"");
        sb.append(">");
        if (para.lnSpcPct > 0) {
            sb.append("<a:lnSpc><a:spcPct val=\"").append(Math.round(para.lnSpcPct * 100000f))
              .append("\"/></a:lnSpc>");
        } else if (para.lnSpcPt > 0) {
            sb.append("<a:lnSpc><a:spcPts val=\"").append(Math.round(para.lnSpcPt * 100f))
              .append("\"/></a:lnSpc>");
        }
        /* v1.31 FIX: an EXPLICIT zero (spcBef/spcAft pts=0 written by an
           author to kill inherited spacing) round-trips now — the old
           "> 0" test dropped it and the next open re-inherited the layout
           spacing (headline baselines drifting apart on every save). */
        if (para.spcBefPt > 0 || (para.hasSpcBef && para.spcBefPt == 0)) {
            sb.append("<a:spcBef><a:spcPts val=\"").append(Math.round(para.spcBefPt * 100f))
              .append("\"/></a:spcBef>");
        }
        if (para.spcAftPt > 0 || (para.hasSpcAft && para.spcAftPt == 0)) {
            sb.append("<a:spcAft><a:spcPts val=\"").append(Math.round(para.spcAftPt * 100f))
              .append("\"/></a:spcAft>");
        }
        /* v1.24: bullet color + size round-trip (buClr / buSzPct / buSzPts),
           emitted in schema order: buClr, buSz, then the bullet itself.
           PowerPoint reads these on bulleted AND numbered paragraphs. */
        boolean buClrOut = para.buClr != 0 && (para.buAutoNum || para.buChar != null);
        if (buClrOut) {
            sb.append("<a:buClr>").append(srgb(para.buClr)).append("</a:buClr>");
        }
        if (para.buAutoNum || para.buChar != null) {
            if (para.buSzPct > 0f) {
                sb.append("<a:buSzPct val=\"").append(Math.round(para.buSzPct * 100000f)).append("\"/>");
            } else if (para.buSizePt > 0f) {
                sb.append("<a:buSzPts val=\"").append(Math.round(para.buSizePt * 100f)).append("\"/>");
            }
        }
        if (para.buAutoNum) {
            /* v1.24: real numbering format + start index (was hardcoded
               arabicPeriod) */
            sb.append("<a:buAutoNum type=\"").append(PptDoc.Para.buTypeName(para.buType)).append("\"");
            if (para.buStart > 1) sb.append(" startAt=\"").append(para.buStart).append("\"");
            sb.append("/>");
        } else if (para.buChar != null) {
            sb.append("<a:buChar char=\"").append(esc(para.buChar)).append("\"/>");
        } else if (para.hasBullet && !para.bullet) {
            /* v1.24: explicit buNone round-trips for cells and bodies alike */
            sb.append("<a:buNone/>");
        } else if (!para.bullet && para.marL > 0) {
            /* keep master list styles from kicking in on indented paras */
            sb.append("<a:buNone/>");
        }
        sb.append("</a:pPr>");
        if (para.runs.isEmpty()) {
            sb.append("<a:endParaRPr lang=\"fa-IR\"/></a:p>");
            return sb.toString();
        }
        for (PptDoc.Run r : para.runs) {
            /* v1.27 FIX: slide-number fields round-trip as REAL fields
               (<a:fld type="slidenum">) — flattening them to plain text froze
               the number forever (slide 5 moved to slide 2 kept saying 5) */
            if (r.fld == 1) {
                sb.append("<a:fld id=\"{C9ED2DA6-CA4F-4B84-AFAC-3426281FD072}\" type=\"slidenum\">")
                  .append("<a:rPr lang=\"fa-IR\" sz=\"").append(clampSz(r.sizePt)).append("\"");
                if (r.bold) sb.append(" b=\"1\"");
                if (r.italic) sb.append(" i=\"1\"");
                if (r.underline) sb.append(" u=\"sng\"");
                if (r.caps == 1) sb.append(" cap=\"all\"");
                else if (r.caps == 2) sb.append(" cap=\"small\"");
                /* v1.31 FIX: field runs lost their letter-spacing — a slidenum
                   with spc=\"0\" re-opened with the layout's tracking back on */
                if (!Float.isNaN(r.spcPt)) {
                    sb.append(" spc=\"").append(Math.round(r.spcPt * 100f)).append("\"");
                }
                if (r.font != null && r.font.length() > 0) {
                    sb.append(" dirty=\"0\"><a:solidFill>").append(srgb(r.color))
                      .append("</a:solidFill><a:latin typeface=\"").append(esc(r.font))
                      .append("\"/><a:cs typeface=\"").append(esc(r.font)).append("\"/>");
                } else {
                    sb.append(" dirty=\"0\"><a:solidFill>").append(srgb(r.color))
                      .append("</a:solidFill>");
                }
                sb.append("</a:rPr><a:t>").append(esc(r.text)).append("</a:t></a:fld>");
                continue;
            }
            sb.append("<a:r><a:rPr lang=\"fa-IR\" sz=\"").append(clampSz(r.sizePt)).append("\"");
            if (r.bold) sb.append(" b=\"1\"");
            if (r.italic) sb.append(" i=\"1\"");
            if (r.underline) sb.append(" u=\"sng\"");
            if (r.caps == 1) sb.append(" cap=\"all\"");
            else if (r.caps == 2) sb.append(" cap=\"small\"");
            if (!Float.isNaN(r.spcPt)) {
                /* v1.31 FIX: an explicit spc="0" (kill inherited tracking)
                   used to be silently dropped ("!= 0" test), so the file
                   re-opened with the layout's letter-spacing back on */
                sb.append(" spc=\"").append(Math.round(r.spcPt * 100f)).append("\"");
            }
            sb.append(" dirty=\"0\">")
              .append("<a:solidFill>").append(srgb(r.color)).append("</a:solidFill>");
            /* v1.23: text drop shadow (rPr/a:effectLst — after fill, before
               latin/cs per CT_TextCharacterProperties order) */
            if (tshadow) {
                sb.append("<a:effectLst><a:outerShdw blurRad=\"38100\" dist=\"19050\" dir=\"2700000\"><a:srgbClr val=\"000000\"><a:alpha val=\"55000\"/></a:srgbClr></a:outerShdw></a:effectLst>");
            }
            if (r.font != null && r.font.length() > 0) {
                sb.append("<a:latin typeface=\"").append(esc(r.font)).append("\"/>")
                  .append("<a:cs typeface=\"").append(esc(r.font)).append("\"/>");
            }
            /* v1.21: hyperlink round-trip — external URL runs reference the
               slide's rels (rLinkN).
               v1.29 NEW: internal "go to slide N" action links round-trip too
               (they used to be silently DROPPED on every save) — the rels list
               carries a "#slide/N" pseudo-target that slideRels turns into an
               INTERNAL relationship to slideN.xml + ppaction://hlinksldjump. */
            if (r.link) {
                String linkTarget = null;
                boolean jump = false;
                if (r.linkUrl != null && r.linkUrl.length() > 0) {
                    linkTarget = r.linkUrl;
                } else if (r.linkSlide > 0) {
                    linkTarget = "#slide/" + Math.max(1, r.linkSlide);
                    jump = true;
                }
                if (linkTarget != null) {
                    int li = links.indexOf(linkTarget);
                    if (li < 0) { li = links.size(); links.add(linkTarget); }
                    sb.append("<a:hlinkClick r:id=\"rLink").append(li + 1).append("\"");
                    if (jump) {
                        sb.append(" action=\"ppaction://hlinksldjump\"");
                    }
                    sb.append("/>");
                } else {
                    /* v1.31 FIX: an ACTION-ONLY link (ppaction://hlinkshowjump
                       Jump=NextSlide etc. — link=true, no URL, no slide
                       target) used to be dropped completely, so its link
                       styling and its next/previous behavior vanished on
                       every save. Keep the run marked as a link with an
                       empty r:id (exactly what PowerPoint writes for pure
                       action links that carry no relationship). */
                    sb.append("<a:hlinkClick r:id=\"\"/>");
                }
            }
            sb.append("</a:rPr><a:t>").append(esc(r.text)).append("</a:t></a:r>");
        }
        sb.append("</a:p>");
        return sb.toString();
    }

    private static String prstName(int type) {
        switch (type) {
            case PptDoc.Shape.TYPE_ROUND_RECT: return "roundRect";
            case PptDoc.Shape.TYPE_OVAL: return "ellipse";
            case PptDoc.Shape.TYPE_LINE: return "line";
            case PptDoc.Shape.TYPE_TRIANGLE: return "triangle";
            case PptDoc.Shape.TYPE_RIGHT_ARROW: return "rightArrow";
            case PptDoc.Shape.TYPE_STAR: return "star5";
            case PptDoc.Shape.TYPE_DIAMOND: return "diamond";
            case PptDoc.Shape.TYPE_PENTAGON: return "pentagon";
            case PptDoc.Shape.TYPE_HEXAGON: return "hexagon";
            case PptDoc.Shape.TYPE_PARALLELOGRAM: return "parallelogram";
            case PptDoc.Shape.TYPE_HEART: return "heart";
            case PptDoc.Shape.TYPE_CLOUD: return "cloud";
            case PptDoc.Shape.TYPE_LIGHTNING: return "lightningBolt";
            case PptDoc.Shape.TYPE_PLUS: return "mathPlus";
            case PptDoc.Shape.TYPE_CHEVRON: return "chevron";
            default: return "rect";
        }
    }

    /* v1.27: content hash for media deduplication (CRC64-ish fold of
       java's array hash + length — collision odds are negligible for
       accidental duplicates of the same bytes)
       v1.28 FIX: the loop only folded COMPLETE 8-byte chunks, so the last
       1..7 bytes of a media part never entered the hash — two images that
       differed ONLY in those tail bytes collided and one silently replaced
       the other on save. The tail is now folded in. */
    private static String mediaHash(byte[] b) {
        long h = 1125899906842597L;
        int n = b.length;
        int i = 0;
        for (; i + 7 < n; i += 8) {
            h = 31 * h + ((b[i] & 255L) | (b[i + 1] & 255L) << 8 | (b[i + 2] & 255L) << 16 | (b[i + 3] & 255L) << 24
                    | (b[i + 4] & 255L) << 32 | (b[i + 5] & 255L) << 40 | (b[i + 6] & 255L) << 48 | (b[i + 7] & 255L) << 56);
        }
        for (; i < n; i++) {                       /* v1.28: tail bytes */
            h = 31 * h + (b[i] & 255L);
        }
        h = 31 * h + n;
        return Long.toHexString(h) + "_" + n;
    }

    /* -------------------------------------------------------------- entry API */
    public static void write(OutputStream out, PptDoc doc, String title) throws IOException {
        ZipW zip = new ZipW(out);
        int slides = doc.slides.size();
        int mediaGlobal = 0; /* v7: global media counter (see fix below) */

        /* collect media + hyperlink rels per slide while building slide XML */
        List<String> slideXmls = new java.util.ArrayList<>();
        List<List<String>> slideMediaNames = new java.util.ArrayList<>();
        List<List<String>> slideLinks = new java.util.ArrayList<>();
        List<Integer> slideChartCount = new java.util.ArrayList<>();
        List<PptDoc.Chart> allCharts = new java.util.ArrayList<>();
        Map<String, byte[]> media = new HashMap<>();
        Map<String, String> mediaDedup = new HashMap<>();
        for (PptDoc.Slide s : doc.slides) {
            List<byte[]> mo = new java.util.ArrayList<>();
            List<String> mn = new java.util.ArrayList<>();
            List<String> lk = new java.util.ArrayList<>();
            List<String> chartRids = new java.util.ArrayList<>();
            int chartBefore = allCharts.size();
            slideXmls.add(slideXml(s, doc.slideW, doc.slideH, mo, mn, lk, allCharts, chartRids));
            slideChartCount.add(allCharts.size() - chartBefore);
            /* v7 FIX (CRITICAL round-trip corruption): every slide's
               slideXml() numbered its media from 1 (per-slide list), so all
               slides' first picture was "img1.png" — the global map
               overwrote earlier slides' bytes and every slide's rels
               pointed at the LAST slide's media. Renumber the per-slide
               names GLOBALLY before merging (slide XML only references
               rImg ids, never the names — safe to rename here).
               v1.27 FIX (a): PRESERVE the vid/img prefix — the global rename
               used to turn embedded videos (vidN.mpg) into imgN.mpg, so the
               rels got an IMAGE type for actual media.
               v1.27 FIX (b): DEDUPLICATE identical media bytes — a logo used
               on 30 slides used to be stored 30 times (10× file size). */
            for (int i = 0; i < mo.size(); i++) {
                String oldName = mn.get(i);
                int dot = oldName.lastIndexOf('.');
                String ext = dot >= 0 ? oldName.substring(dot + 1) : "bin";
                String prefix = oldName.startsWith("vid") ? "vid" : "img";
                String hash = mediaHash(mo.get(i));
                String key = hash + "." + ext;
                String existing = mediaDedup.get(key);
                if (existing != null) {
                    mn.set(i, existing);
                    continue;
                }
                mediaGlobal++;
                String newName = prefix + mediaGlobal + "." + ext;
                mn.set(i, newName);
                mediaDedup.put(key, newName);
                media.put(newName, mo.get(i));
            }
            slideMediaNames.add(mn);
            slideLinks.add(lk);
        }
        int charts = allCharts.size();

        /* v1.21: speaker notes — emit notesSlide parts for slides that have
           them (sequential numbering, referenced from the slide's rels) */
        List<Integer> notesSlideOf = new java.util.ArrayList<>();
        int notesCount = 0;
        for (PptDoc.Slide s : doc.slides) {
            if (s.notes != null && s.notes.length() > 0) {
                notesCount++;
                notesSlideOf.add(notesCount);
            } else {
                notesSlideOf.add(0);
            }
        }
        boolean hasNotes = notesCount > 0;

        /* extensions present */
        Map<String, String> extMap = new HashMap<>();
        for (String n : media.keySet()) {
            String ext = n.substring(n.lastIndexOf('.') + 1);
            extMap.put(ext, "1");
        }

        zip.write("[Content_Types].xml", contentTypes(slides, extMap, notesCount, charts).getBytes("UTF-8"));
        zip.write("_rels/.rels", rootRels().getBytes("UTF-8"));
        zip.write("docProps/core.xml", coreProps(title).getBytes("UTF-8"));
        zip.write("docProps/app.xml", appProps(slides, title).getBytes("UTF-8"));
        zip.write("ppt/presentation.xml", presentationXml(doc, hasNotes).getBytes("UTF-8"));
        zip.write("ppt/_rels/presentation.xml.rels", presentationRels(slides, hasNotes).getBytes("UTF-8"));
        zip.write("ppt/theme/theme1.xml", themeXml(doc).getBytes("UTF-8"));
        zip.write("ppt/slideMasters/slideMaster1.xml", masterXml(doc).getBytes("UTF-8"));
        zip.write("ppt/slideMasters/_rels/slideMaster1.xml.rels", masterRels().getBytes("UTF-8"));
        zip.write("ppt/slideLayouts/slideLayout1.xml", layoutXml(doc).getBytes("UTF-8"));
        zip.write("ppt/slideLayouts/_rels/slideLayout1.xml.rels", layoutRels().getBytes("UTF-8"));
        if (hasNotes) {
            zip.write("ppt/notesMasters/notesMaster1.xml", notesMasterXml().getBytes("UTF-8"));
            zip.write("ppt/notesMasters/_rels/notesMaster1.xml.rels", notesMasterRels().getBytes("UTF-8"));
        }
        int chartOffset = 0;
        for (int i = 0; i < slides; i++) {
            zip.write("ppt/slides/slide" + (i + 1) + ".xml", slideXmls.get(i).getBytes("UTF-8"));
            zip.write("ppt/slides/_rels/slide" + (i + 1) + ".xml.rels",
                    slideRels(slideMediaNames.get(i), slideLinks.get(i), notesSlideOf.get(i),
                            slideChartCount.get(i), chartOffset).getBytes("UTF-8"));
            chartOffset += slideChartCount.get(i);
            if (notesSlideOf.get(i) > 0) {
                int nn = notesSlideOf.get(i);
                zip.write("ppt/notesSlides/notesSlide" + nn + ".xml",
                        notesSlideXml(doc.slides.get(i).notes).getBytes("UTF-8"));
                zip.write("ppt/notesSlides/_rels/notesSlide" + nn + ".xml.rels",
                        notesSlideRels(i + 1).getBytes("UTF-8"));
            }
        }
        /* v1.22: chart parts */
        for (int i = 0; i < charts; i++) {
            zip.write("ppt/charts/chart" + (i + 1) + ".xml",
                    chartXml(allCharts.get(i)).getBytes("UTF-8"));
        }
        for (Map.Entry<String, byte[]> en : media.entrySet()) {
            zip.write("ppt/media/" + en.getKey(), en.getValue());
        }
        zip.finish();
    }

    /* ================================================================== v1.22
       chartXml — emits a real DrawingML chart part (the same structure
       PowerPoint/python-pptx write) so other apps see an EDITABLE chart:
       c:chartSpace > c:chart > title + plotArea(barChart|lineChart|
       pieChart|doughnutChart|areaChart|scatterChart with ser caches) +
       axes + legend.
       ======================================================================== */
    private static final long AX1 = 111111111L, AX2 = 222222222L;

    private static String chartXml(PptDoc.Chart ch) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
          .append("<c:chartSpace xmlns:c=\"http://schemas.openxmlformats.org/drawingml/2006/chart\" ")
          .append("xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" ")
          .append("xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">")
          .append("<c:chart>");
        if (ch.title != null && ch.title.length() > 0) {
            sb.append("<c:title><c:tx><c:rich><a:bodyPr/><a:lstStyle/><a:p>")
              .append("<a:r><a:rPr lang=\"fa-IR\"/><a:t>").append(esc(ch.title)).append("</a:t></a:r>")
              .append("</a:p></c:rich></c:tx><c:layout/><c:overlay val=\"0\"/></c:title>");
        }
        sb.append("<c:autoTitleDeleted val=\"0\"/><c:plotArea><c:layout/>");

        int t = ch.chartType;
        boolean pie = t == PptDoc.Chart.PIE || t == PptDoc.Chart.DOUGHNUT;
        if (pie) {
            sb.append(t == PptDoc.Chart.PIE ? "<c:pieChart>" : "<c:doughnutChart>")
              .append("<c:varyColors val=\"1\"/>");
            sb.append(serXml(ch, 0, true));
            sb.append(dLblsXml(ch));
            if (t == PptDoc.Chart.PIE) sb.append("<c:firstSliceAng val=\"0\"/>");
            /* v1.31 FIX: the hole clamp was 10..90 here but 5..90 in the
               reader — a real 5% hole (LibreOffice decks) ballooned to 10%
               on every save */
            else sb.append("<c:holeSize val=\"").append(Math.max(5, Math.min(90, ch.holePct))).append("\"/>");
            sb.append(t == PptDoc.Chart.PIE ? "</c:pieChart>" : "</c:doughnutChart>");
        } else {
            String plot;
            if (t == PptDoc.Chart.LINE || t == PptDoc.Chart.LINE_MARKER) {
                plot = "lineChart";
            } else if (t == PptDoc.Chart.AREA) {
                plot = "areaChart";
            } else if (t == PptDoc.Chart.SCATTER) {
                plot = "scatterChart";
            } else {
                plot = "barChart";
            }
            sb.append("<c:").append(plot).append(">");
            if (plot.equals("barChart")) {
                sb.append("<c:barDir val=\"").append(ch.isHorizontal() ? "bar" : "col").append("\"/>");
                String g = "clustered";
                if (t == PptDoc.Chart.BAR_STACKED || t == PptDoc.Chart.BARH_STACKED) g = "stacked";
                else if (t == PptDoc.Chart.BAR_PERCENT || t == PptDoc.Chart.BARH_PERCENT) g = "percentStacked";
                sb.append("<c:grouping val=\"").append(g).append("\"/>");
                sb.append("<c:varyColors val=\"0\"/>");
            } else if (plot.equals("lineChart") || plot.equals("areaChart")) {
                sb.append("<c:grouping val=\"standard\"/><c:varyColors val=\"0\"/>");
            } else {
                sb.append("<c:scatterStyle val=\"lineMarker\"/><c:varyColors val=\"0\"/>");
            }
            for (int i = 0; i < ch.series.size(); i++) {
                sb.append(serXml(ch, i, false));
            }
            sb.append(dLblsXml(ch));
            /* v1.27 FIX: CT_LineChart element order — dLbls BEFORE marker */
            if (plot.equals("lineChart") && t == PptDoc.Chart.LINE_MARKER) {
                sb.append("<c:marker val=\"1\"/>");
            }
            if (plot.equals("barChart")) {
                sb.append("<c:gapWidth val=\"").append(Math.max(0, Math.min(500, ch.gapWidthPct))).append("\"/>");
                /* v1.27 FIX: stacked bar charts need overlap=100 or the
                   bars render side-by-side instead of stacked */
                if (t == PptDoc.Chart.BAR_STACKED || t == PptDoc.Chart.BARH_STACKED
                        || t == PptDoc.Chart.BAR_PERCENT || t == PptDoc.Chart.BARH_PERCENT) {
                    sb.append("<c:overlap val=\"100\"/>");
                }
            }
            if (plot.equals("barChart") || plot.equals("lineChart") || plot.equals("areaChart")) {
                sb.append("<c:axId val=\"").append(AX1).append("\"/>")
                  .append("<c:axId val=\"").append(AX2).append("\"/>");
                sb.append("</c:").append(plot).append(">");
                sb.append(catAxXml(ch)).append(valAxXml(ch, false));
            } else {
                sb.append("<c:axId val=\"").append(AX1).append("\"/>")
                  .append("<c:axId val=\"").append(AX2).append("\"/>");
                sb.append("</c:").append(plot).append(">");
                sb.append(valAxXml(ch, true)).append(valAxXml(ch, false));
            }
        }

        sb.append("</c:plotArea>");
        if (ch.legend) {
            String pos = "r";
            if (ch.legendPos == 1) pos = "b";
            else if (ch.legendPos == 2) pos = "l";
            else if (ch.legendPos == 3) pos = "t";
            sb.append("<c:legend><c:legendPos val=\"").append(pos).append("\"/><c:overlay val=\"0\"/></c:legend>");
        }
        sb.append("<c:plotVisOnly val=\"1\"/><c:dispBlanksAs val=\"gap\"/></c:chart>")
          .append("<c:txPr><a:bodyPr/><a:lstStyle/><a:p><a:pPr><a:defRPr sz=\"1000\"/></a:pPr>")
          .append("<a:endParaRPr lang=\"fa-IR\"/></a:p></c:txPr>")
          .append("</c:chartSpace>");
        return sb.toString();
    }

    private static String dLblsXml(PptDoc.Chart ch) {
        if (!ch.dataLabels) return "";
        return "<c:dLbls><c:showLegendKey val=\"0\"/><c:showVal val=\"1\"/>"
                + "<c:showCatName val=\"0\"/><c:showSerName val=\"0\"/>"
                + "<c:showPercent val=\"0\"/><c:showBubbleSize val=\"0\"/>"
                + "<c:showLeaderLines val=\"1\"/></c:dLbls>";
    }

    private static String axisTitleXml(String text) {
        return "<c:title><c:tx><c:rich><a:bodyPr/><a:lstStyle/><a:p>"
                + "<a:r><a:rPr lang=\"fa-IR\"/><a:t>" + esc(text) + "</a:t></a:r>"
                + "</a:p></c:rich></c:tx><c:layout/><c:overlay val=\"0\"/></c:title>";
    }

    private static String catAxXml(PptDoc.Chart ch) {
        StringBuilder sb = new StringBuilder();
        sb.append("<c:catAx><c:axId val=\"").append(AX1).append("\"/>")
          .append("<c:scaling><c:orientation val=\"minMax\"/></c:scaling>")
          .append("<c:delete val=\"0\"/><c:axPos val=\"b\"/>");
        if (ch.catTitle != null && ch.catTitle.length() > 0) sb.append(axisTitleXml(ch.catTitle));
        sb.append("<c:majorTickMark val=\"out\"/><c:minorTickMark val=\"none\"/>")
          .append("<c:tickLblPos val=\"nextTo\"/>")
          .append("<c:crossAx val=\"").append(AX2).append("\"/><c:crosses val=\"autoZero\"/>")
          .append("<c:auto val=\"1\"/><c:lblAlgn val=\"ctr\"/><c:lblOffset val=\"100\"/>")
          .append("<c:noMultiLvlLbl val=\"0\"/></c:catAx>");
        return sb.toString();
    }

    private static String valAxXml(PptDoc.Chart ch, boolean xAxis) {
        StringBuilder sb = new StringBuilder();
        sb.append("<c:valAx><c:axId val=\"").append(xAxis ? AX1 : AX2).append("\"/>")
          .append("<c:scaling><c:orientation val=\"minMax\"/></c:scaling>")
          .append("<c:delete val=\"0\"/><c:axPos val=\"").append(xAxis ? "b" : "l").append("\"/>");
        if (!xAxis && ch.gridlines) sb.append("<c:majorGridlines/>");
        if (!xAxis && ch.valTitle != null && ch.valTitle.length() > 0) sb.append(axisTitleXml(ch.valTitle));
        sb.append("<c:majorTickMark val=\"out\"/><c:minorTickMark val=\"none\"/>")
          .append("<c:tickLblPos val=\"nextTo\"/>")
          .append("<c:crossAx val=\"").append(xAxis ? AX2 : AX1).append("\"/>")
          .append("<c:crosses val=\"autoZero\"/></c:valAx>");
        return sb.toString();
    }

    /* one c:ser — cached refs make the chart editable/data-complete in
       PowerPoint without an embedded workbook */
    private static String serXml(PptDoc.Chart ch, int si, boolean circular) {
        PptDoc.ChartSeries s = ch.series.get(si);
        int n = Math.max(ch.categories.size(), s.values.size());
        /* v1.27 FIX: column A holds the CATEGORIES — series data starts at
           column B (series 0's values used to collide with the c:cat ref) */
        String col = colLetter(si + 2);
        StringBuilder sb = new StringBuilder();
        sb.append("<c:ser><c:idx val=\"").append(si).append("\"/>")
          .append("<c:order val=\"").append(si).append("\"/>");
        /* series name */
        sb.append("<c:tx><c:strRef><c:f>Sheet1!$").append(col).append("$1</c:f>")
          .append("<c:strCache><c:ptCount val=\"1\"/><c:pt idx=\"0\"><c:v>")
          .append(esc(s.name == null ? "" : s.name)).append("</c:v></c:pt></c:strCache></c:strRef></c:tx>");
        int color = circular ? 0 : ch.seriesColor(si);
        /* v1.31 FIX: a PIE/DOUGHNUT series with an EXPLICIT series-level color
           (LibreOffice decks) lost it — circular charts skipped the c:spPr
           entirely, so the whole pie fell back to the auto palette. Keep the
           author's color; dPt per-point colors still override it. */
        if (circular && s.color != 0) color = s.color;
        if (color != 0) {
            sb.append("<c:spPr><a:solidFill>").append(srgb(color)).append("</a:solidFill></c:spPr>");
        }
        /* pie per-point colors */
        if (circular) {
            int pts = Math.max(ch.categories.size(), s.values.size());
            for (int i = 0; i < pts; i++) {
                int pc = i < s.pointColors.size() && s.pointColors.get(i) != null
                        && s.pointColors.get(i) != 0 ? s.pointColors.get(i)
                        : PptDoc.Chart.PALETTE[i % PptDoc.Chart.PALETTE.length];
                sb.append("<c:dPt><c:idx val=\"").append(i).append("\"/><c:bubble3D val=\"0\"/>")
                  .append("<c:spPr><a:solidFill>").append(srgb(pc))
                  .append("</a:solidFill></c:spPr></c:dPt>");
            }
        }
        if (ch.chartType == PptDoc.Chart.LINE_MARKER) {
            sb.append("<c:marker><c:symbol val=\"circle\"/><c:size val=\"6\"/></c:marker>");
        }
        /* categories / x values */
        if (ch.chartType == PptDoc.Chart.SCATTER) {
            sb.append("<c:xVal><c:numRef><c:f>Sheet1!$A$2:$A$").append(n + 1).append("</c:f>")
              .append("<c:numCache><c:formatCode>General</c:formatCode><c:ptCount val=\"").append(n).append("\"/>");
            for (int i = 0; i < n; i++) {
                String v = i < ch.categories.size() ? ch.categories.get(i) : "";
                Double d = parseD(v);
                if (d != null) sb.append(ptXml(i, d));
            }
            sb.append("</c:numCache></c:numRef></c:xVal>");
            sb.append("<c:yVal><c:numRef><c:f>Sheet1!$").append(col).append("$2:$").append(col)
              .append("$").append(n + 1).append("</c:f>")
              .append("<c:numCache><c:formatCode>General</c:formatCode><c:ptCount val=\"").append(n).append("\"/>");
            for (int i = 0; i < n && i < s.values.size(); i++) {
                Double d = s.values.get(i);
                if (d != null && !Double.isNaN(d) && !Double.isInfinite(d)) sb.append(ptXml(i, d));
            }
            sb.append("</c:numCache></c:numRef></c:yVal>");
        } else {
            sb.append("<c:cat><c:strRef><c:f>Sheet1!$A$2:$A$").append(n + 1).append("</c:f>")
              .append("<c:strCache><c:ptCount val=\"").append(n).append("\"/>");
            for (int i = 0; i < n; i++) {
                String v = i < ch.categories.size() ? ch.categories.get(i) : "";
                sb.append("<c:pt idx=\"").append(i).append("\"><c:v>").append(esc(v)).append("</c:v></c:pt>");
            }
            sb.append("</c:strCache></c:strRef></c:cat>");
            sb.append("<c:val><c:numRef><c:f>Sheet1!$").append(col).append("$2:$").append(col)
              .append("$").append(n + 1).append("</c:f>")
              .append("<c:numCache><c:formatCode>General</c:formatCode><c:ptCount val=\"").append(n).append("\"/>");
            for (int i = 0; i < n && i < s.values.size(); i++) {
                Double d = s.values.get(i);
                if (d != null && !Double.isNaN(d) && !Double.isInfinite(d)) sb.append(ptXml(i, d));
            }
            sb.append("</c:numCache></c:numRef></c:val>");
        }
        sb.append("</c:ser>");
        return sb.toString();
    }

    private static String ptXml(int idx, double d) {
        return "<c:pt idx=\"" + idx + "\"><c:v>" + numStr(d) + "</c:v></c:pt>";
    }

    static String numStr(double d) {
        if (d == Math.floor(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
            return String.valueOf((long) d);
        }
        return String.valueOf(Math.round(d * 100000.0) / 100000.0);
    }

    static Double parseD(String s) {
        if (s == null) return null;
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return null; }
    }

    private static String colLetter(int i) {
        StringBuilder sb = new StringBuilder();
        while (i > 0) {
            int r = (i - 1) % 26;
            sb.insert(0, (char) ('A' + r));
            i = (i - 1) / 26;
        }
        return sb.length() == 0 ? "A" : sb.toString();
    }
}
