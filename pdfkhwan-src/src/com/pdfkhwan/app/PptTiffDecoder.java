package com.pdfkhwan.app;

import android.graphics.Bitmap;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Inflater;

/* ============================================================================
   PptTiffDecoder v1.26 — a pure-Java baseline TIFF reader for PowerPoint
   media parts. Android's BitmapFactory has no TIFF codec, so TIFF pictures
   showed the "unsupported format" placeholder. This decoder covers what
   real Office decks actually carry:

     - little & big endian files (II* / MM\0*)
     - strips AND tiles
     - photometric: bilevel/gray, inverted, palette, RGB(A)
     - 1/4/8/16 bits per sample (gray + RGB), 4/8-bit palettes
     - compression: none(1), PackBits(32773), LZW(5), Adobe Deflate(8)
     - predictor: none(1) + horizontal differencing(2)
     - unassociated-alpha extra sample (4-sample RGB)

   Anything else (CCITT, JPEG-in-TIFF, planar>=2, float samples) returns
   null and the caller falls back to BitmapFactory / placeholder.

   Structure: decodePixels() is pure Java (unit-testable on the JVM);
   decode() is a thin android.graphics wrapper.
   ============================================================================ */
final class PptTiffDecoder {

    private PptTiffDecoder() {
    }

    /* ------------------------------------------------------------ entry API */

    public static Bitmap decode(byte[] data, int targetPx) {
        Pix p = decodePixels(data);
        if (p == null) return null;
        /* cap memory like the raster path: nearest-neighbour integer
           downscale toward the target size (floor 256px) */
        int w = p.width, h = p.height;
        int goal = Math.max(256, Math.min(targetPx <= 0 ? 2048 : targetPx, 4096));
        int step = 1;
        while (Math.max(w, h) / (step * 2) >= goal && Math.max(w, h) / (step * 2) >= 256) {
            step *= 2;
        }
        if (step > 1) {
            int nw = w / step, nh = h / step;
            if (nw > 0 && nh > 0) {
                int[] out = new int[nw * nh];
                for (int y = 0; y < nh; y++) {
                    for (int x = 0; x < nw; x++) {
                        out[y * nw + x] = p.pixels[(y * step) * w + (x * step)];
                    }
                }
                w = nw;
                h = nh;
                p.pixels = out;
            }
        }
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        bmp.setPixels(p.pixels, 0, w, 0, 0, w, h);
        return bmp;
    }

    static final class Pix {
        int[] pixels;
        final int width, height;

        Pix(int[] pixels, int width, int height) {
            this.pixels = pixels;
            this.width = width;
            this.height = height;
        }
    }

    /* --------------------------------------------------------------- reader */

    static Pix decodePixels(byte[] data) {
        if (data == null || data.length < 8) return null;
        boolean le;
        if ((data[0] & 255) == 0x49 && (data[1] & 255) == 0x49 && (data[2] & 255) == 0x2A && (data[3] & 255) == 0x00) {
            le = true;
        } else if ((data[0] & 255) == 0x4D && (data[1] & 255) == 0x4D && (data[2] & 255) == 0x00 && (data[3] & 255) == 0x2A) {
            le = false;
        } else {
            return null;
        }
        R r = new R(data, le);
        long ifdOff = r.u32(4);
        if (ifdOff <= 0 || ifdOff >= data.length) return null;

        int n = r.u16((int) ifdOff);
        if (n <= 0 || n > 512) return null;

        int width = 0, height = 0, compression = 1, photometric = 1, spp = 1,
                predictor = 1, planar = 1, sampleFormat = 1, extra = 0;
        int bps0 = 1;
        int[] bps = null;
        boolean bpsBad = false;   /* v1.28: BitsPerSample count == 0 */
        int[] cmap = null;
        long[] stripOffsets = null, stripCounts = null;
        long rowsPerStrip = -1;
        int tileW = 0, tileH = 0;
        long[] tileOffsets = null, tileCounts = null;

        for (int i = 0; i < n; i++) {
            int e = (int) ifdOff + 2 + i * 12;
            if (e + 12 > data.length) return null;
            int tag = r.u16(e);
            int type = r.u16(e + 2);
            long cnt = r.u32(e + 4);
            switch (tag) {
                case 256: width = (int) ifdInt(r, e, type, cnt, 0); break;
                case 257: height = (int) ifdInt(r, e, type, cnt, 0); break;
                case 258: {
                    /* v1.28 FIX: فایل خراب با count=0 قبلاً به v[0] می‌رسید
                       (AIOOBE خارج از try) */
                    if (cnt < 1) { bpsBad = true; break; }
                    int m = (int) Math.min(cnt, 4);
                    int[] v = new int[m];
                    for (int k = 0; k < m; k++) v[k] = (int) ifdInt(r, e, type, cnt, k);
                    bps0 = v[0];
                    bps = v;
                    break;
                }
                case 259: compression = (int) ifdInt(r, e, type, cnt, 0); break;
                case 262: photometric = (int) ifdInt(r, e, type, cnt, 0); break;
                case 273: stripOffsets = ifdLongs(r, e, type, cnt); break;
                case 277: spp = (int) ifdInt(r, e, type, cnt, 0); break;
                case 278: rowsPerStrip = ifdInt(r, e, type, cnt, 0); break;
                case 279: stripCounts = ifdLongs(r, e, type, cnt); break;
                case 284: planar = (int) ifdInt(r, e, type, cnt, 0); break;
                case 317: predictor = (int) ifdInt(r, e, type, cnt, 0); break;
                case 320: {
                    /* v1.28 FIX: bps0=29 با count بزرگ → تخصیص چند-گیگابایتی
                       (OOM)؛ سقف واقعی پالت = ۳×۶۵۵۳۶ ورودی است */
                    int need = 3 << Math.min(Math.max(bps0, 1), 16);
                    if (cnt >= need && need > 0 && need <= 3 * 65536) {
                        cmap = new int[need];
                        for (int k = 0; k < need; k++) cmap[k] = (int) ifdInt(r, e, type, cnt, k);
                    }
                    break;
                }
                case 322: tileW = (int) ifdInt(r, e, type, cnt, 0); break;
                case 323: tileH = (int) ifdInt(r, e, type, cnt, 0); break;
                case 324: tileOffsets = ifdLongs(r, e, type, cnt); break;
                case 325: tileCounts = ifdLongs(r, e, type, cnt); break;
                case 338: extra = (int) ifdInt(r, e, type, cnt, 0); break;
                case 339: sampleFormat = (int) ifdInt(r, e, type, cnt, 0); break;
                default: break;
            }
        }

        if (width <= 0 || height <= 0) return null;
        if (bpsBad) return null;                       /* v1.28: BitsPerSample بی‌مقدار */
        /* v1.27 FIX: the old 64-megapixel cap allowed a 256 MB int[]
           allocation on the UI thread — a legit 8000×8000 Office TIFF
           hard-crashed the app with OOM the moment its slide appeared.
           24 Mpx = 96 MB worst case, and OOM now degrades to "no image"
           instead of a crash. */
        if ((long) width * height > 24L * 1024 * 1024) return null;
        if (planar != 1) return null;                 /* chunky only */
        if (sampleFormat != 1) return null;           /* unsigned int only */
        if (compression != 1 && compression != 5 && compression != 8
                && compression != 32946 && compression != 32773) return null;
        if (bps == null) bps = new int[]{bps0, bps0, bps0, bps0};
        /* v1.28 FIX: count<3 برای RGB → آرایهٔ bps کوتاه‌تر از spp و
           bps[1]/bps[2] بیرون از محدوده بود (AIOOBE خارج از try) */
        if (photometric == 2 && spp >= 3 && bps.length < 3) return null;
        for (int v : bps) {
            if (v != 1 && v != 4 && v != 8 && v != 16) return null;
        }
        if (photometric != 0 && photometric != 1 && photometric != 2 && photometric != 3) return null;
        if (photometric == 2 && spp >= 3 && (bps[1] != bps[0] || bps[2] != bps[0])) return null;
        if (photometric == 3) {
            if (bps[0] != 4 && bps[0] != 8) return null;
            if (cmap == null) return null;
        }
        if (photometric == 0 || photometric == 1) {
            if (bps[0] != 1 && bps[0] != 4 && bps[0] != 8 && bps[0] != 16) return null;
            /* v1.29 FIX: gray/palette rows are parsed with a 1-channel stride —
               spp>1 (e.g. gray+alpha) shifted every following row into
               garbage instead of failing cleanly. */
            if (spp != 1) return null;
        }
        if (photometric == 3 && spp != 1) return null;   /* v1.29: same reason */
        /* v1.29 FIX: only 8/16-bit samples are implemented for RGB — bps 1/4
           fell into the 16-bit reader and produced garbage. */
        if (photometric == 2 && spp >= 3 && bps[0] != 8 && bps[0] != 16) return null;

        boolean tiled = tileW > 0 && tileH > 0 && tileOffsets != null && tileCounts != null;
        if (tiled && tileOffsets.length != tileCounts.length) return null;
        if (!tiled) {
            if (stripOffsets == null || stripCounts == null) return null;
            if (stripOffsets.length != stripCounts.length) return null;
            if (rowsPerStrip <= 0) rowsPerStrip = height;
        }

        int[] out;
        try {
            out = new int[width * height];
        } catch (OutOfMemoryError oom) {
            return null;
        }
        try {
            if (tiled) {
                decodeTiles(r, out, width, height, tileW, tileH, tileOffsets, tileCounts,
                        compression, photometric, bps[0], spp, cmap, predictor, extra);
            } else {
                decodeStrips(r, out, width, height, (int) Math.min(rowsPerStrip, height),
                        stripOffsets, stripCounts, compression, photometric, bps[0], spp, cmap,
                        predictor, extra);
            }
        } catch (Throwable t) {
            return null;
        }
        if (photometric == 0) {   /* white-is-zero: invert */
            for (int i = 0; i < out.length; i++) {
                int c = out[i];
                int a = (c >>> 24) & 255;
                int rb = 255 - ((c >> 16) & 255);
                int g = 255 - ((c >> 8) & 255);
                int bl = 255 - (c & 255);
                out[i] = (a << 24) | (rb << 16) | (g << 8) | bl;
            }
        }
        return new Pix(out, width, height);
    }

    /* ------------------------------------------------------------- strips */

    private static void decodeStrips(R r, int[] out, int w, int h, int rowsPer,
                                      long[] offs, long[] cnts, int comp, int photo,
                                      int bpsS, int spp, int[] cmap, int pred, int extra) {
        boolean le16 = r.le;
        int nch = photo == 2 ? Math.min(spp, 5) : 1;
        int rowBytes = (w * bpsS * nch + 7) / 8;
        int y = 0;
        for (int s = 0; s < offs.length && y < h; s++) {
            long off = offs[s], cnt = cnts[s];
            if (off <= 0 || cnt <= 0 || off >= r.b.length) continue;
            int len = (int) Math.min(cnt, r.b.length - off);
            byte[] raw = slice(r.b, (int) off, len);
            byte[] dec = decompress(raw, comp);
            if (dec == null) {
                /* v1.29 FIX: advance the row cursor on a bad strip — leaving y
                   in place emitted every SUBSEQUENT strip rowsPer rows too high
                   (the corrupt band showed the next strip's content and the
                   bottom stayed blank instead of just the bad band). */
                y += Math.min(rowsPer, h - y);
                continue;
            }
            int rows = Math.min(rowsPer, h - y);
            for (int yy = 0; yy < rows; yy++) {
                int ro = yy * rowBytes;
                if (ro >= dec.length) break;
                emitRowInto(dec, ro, out, w, y + yy, 0, w, photo, bpsS, spp, cmap, pred, extra, le16);
            }
            y += rows;
        }
    }

    private static void decodeTiles(R r, int[] out, int w, int h, int tw, int th,
                                    long[] offs, long[] cnts, int comp, int photo,
                                    int bpsS, int spp, int[] cmap, int pred, int extra) {
        int nch = photo == 2 ? Math.min(spp, 5) : 1;
        int rowBytes = (tw * bpsS * nch + 7) / 8;
        int nx = (w + tw - 1) / tw, ny = (h + th - 1) / th;
        for (int ty = 0; ty < ny; ty++) {
            for (int tx = 0; tx < nx; tx++) {
                int idx = ty * nx + tx;
                if (idx >= offs.length) continue;
                long off = offs[idx], cnt = cnts[idx];
                if (off <= 0 || cnt <= 0 || off >= r.b.length) continue;
                int len = (int) Math.min(cnt, r.b.length - off);
                byte[] raw = slice(r.b, (int) off, len);
                byte[] dec = decompress(raw, comp);
                if (dec == null) continue;
                int x0 = tx * tw, y0 = ty * th;
                int rows = Math.min(th, h - y0);
                for (int yy = 0; yy < rows; yy++) {
                    int ro = yy * rowBytes;
                    if (ro >= dec.length) break;
                    emitRowInto(dec, ro, out, w, y0 + yy, x0, Math.min(x0 + tw, w),
                            photo, bpsS, spp, cmap, pred, extra, r.le);
                }
            }
        }
    }

    /* parse one row from dec[ro..]; write pixels x in [x0, x1) of row absY */
    private static void emitRowInto(byte[] dec, int ro, int[] out, int w, int absY,
                                    int x0, int x1, int photo, int bpsS, int spp,
                                    int[] cmap, int pred, int extra, boolean le16) {
        if (x1 <= x0) return;
        int count = x1 - x0;
        int base = absY * w;
        BitR br = new BitR(dec, ro, le16);
        switch (photo) {
            case 0:
            case 1: {  /* bilevel / gray (inversion for 0 handled globally) */
                if (bpsS == 1) {
                    for (int i = 0; i < count; i++) {
                        int v = br.read(1);
                        out[base + x0 + i] = v == 0 ? 0xFF000000 : 0xFFFFFFFF;
                    }
                } else if (bpsS == 4) {
                    int prev = 0;
                    for (int i = 0; i < count; i++) {
                        int v = br.read(4);
                        if (pred == 2) { v = (v + prev) & 0xF; prev = v; }
                        v *= 17;
                        out[base + x0 + i] = 0xFF000000 | (v << 16) | (v << 8) | v;
                    }
                } else if (bpsS == 8) {
                    int prev = 0;
                    for (int i = 0; i < count; i++) {
                        int v = br.read(8);
                        if (pred == 2) { v = (v + prev) & 0xFF; prev = v; }
                        out[base + x0 + i] = 0xFF000000 | (v << 16) | (v << 8) | v;
                    }
                } else {  /* 16 */
                    int prev = 0;
                    for (int i = 0; i < count; i++) {
                        int v;
                        if (pred == 2) {
                            /* v1.27 FIX: horizontal predictor applies at
                               16-bit depth too (was ignored → noise) */
                            int d = br.read(16);
                            v = ((d + prev) & 0xFFFF) >> 8;
                            prev = (d + prev) & 0xFFFF;
                        } else {
                            v = br.read(16) >> 8;
                        }
                        out[base + x0 + i] = 0xFF000000 | (v << 16) | (v << 8) | v;
                    }
                }
                return;
            }
            case 3: {  /* palette — TIFF ColorMap = 3 planes: R[], G[], B[] */
                int plane = cmap != null ? (cmap.length / 3) : 0;   /* 2^bps */
                /* v1.27 FIX: clamp instead of drawing solid black when a
                   short ColorMap (fewer than 3·2^bps entries) sneaks in */
                for (int i = 0; i < count; i++) {
                    int idx = br.read(bpsS);
                    if (cmap == null || plane <= 0) {
                        out[base + x0 + i] = 0xFF000000;
                        continue;
                    }
                    if (idx >= plane) idx = plane - 1;
                    int r8 = cmap[idx] >> 8;
                    int g8 = cmap[plane + idx] >> 8;
                    int b8 = cmap[2 * plane + idx] >> 8;
                    out[base + x0 + i] = 0xFF000000 | (r8 << 16) | (g8 << 8) | b8;
                }
                return;
            }
            case 2: {  /* RGB(A) */
                int nch = Math.min(spp, 5);
                if (bpsS == 8) {
                    int[] pv = new int[5];
                    /* v1.27 FIX: per-pixel array allocation inside the loop
                       caused a GC storm on multi-megapixel images — hoisted */
                    int[] v = new int[5];
                    for (int i = 0; i < count; i++) {
                        for (int c = 0; c < nch; c++) {
                            int t = br.read(8);
                            if (pred == 2) { t = (t + pv[c]) & 0xFF; pv[c] = t; }
                            v[c] = t;
                        }
                        int a = (nch >= 4 && (extra == 2 || extra == 0 && nch > 4)) ? v[3] : 255;
                        out[base + x0 + i] = (a << 24) | (v[0] << 16) | (v[1] << 8) | v[2];
                    }
                } else {  /* 16 */
                    /* v1.29 FIX: honor the horizontal predictor for 16-bit RGB
                       (Photoshop/PIL/tifffile LZW+Predictor=2 files) — the
                       8-bit branch and the 16-bit GRAY branch both apply the
                       differencing; this branch alone ignored it, giving
                       wrong colors on every 16-bit RGB TIFF. */
                    int[] pv = new int[5];
                    for (int i = 0; i < count; i++) {
                        int r16, g16, b16, a16;
                        if (pred == 2) {
                            r16 = (br.read(16) + pv[0]) & 0xFFFF; pv[0] = r16;
                            g16 = (br.read(16) + pv[1]) & 0xFFFF; pv[1] = g16;
                            b16 = (br.read(16) + pv[2]) & 0xFFFF; pv[2] = b16;
                            if (nch >= 4) { a16 = (br.read(16) + pv[3]) & 0xFFFF; pv[3] = a16; } else a16 = 65535;
                        } else {
                            r16 = br.read(16); g16 = br.read(16); b16 = br.read(16);
                            if (nch >= 4) a16 = br.read(16); else a16 = 65535;
                        }
                        int r8 = r16 >> 8, g8 = g16 >> 8, b8 = b16 >> 8, a8v = a16 >> 8;
                        out[base + x0 + i] = (a8v << 24) | (r8 << 16) | (g8 << 8) | b8;
                    }
                }
                return;
            }
            default:
        }
    }

    /* --------------------------------------------------------- compression */

    private static byte[] decompress(byte[] raw, int comp) {
        switch (comp) {
            case 1:
                return raw;
            case 32773:
                return unpackBits(raw);
            case 5:
                return lzw(raw);
            case 8:
            case 32946:
                return inflate(raw);
            default:
                return null;
        }
    }

    private static byte[] unpackBits(byte[] in) {
        List<byte[]> chunks = new ArrayList<>();
        int total = 0;
        int i = 0;
        while (i < in.length) {
            int n = (int) in[i++];
            if (n >= 0) {
                int take = Math.min(n + 1, in.length - i);
                if (take <= 0) break;
                chunks.add(slice(in, i, take));
                total += take;
                i += take;
            } else if (n == -128) {
                /* v1.29 FIX: TIFF6 reserves -128 as No Operation — emitting
                   129 copies of the next byte shifted the rest of the row. */
                continue;
            } else {
                int rep = 1 - n;
                if (i >= in.length) break;
                byte b = in[i++];
                byte[] c = new byte[rep];
                for (int k = 0; k < rep; k++) c[k] = b;
                chunks.add(c);
                total += rep;
            }
            if (total > 96 * 1024 * 1024) return null;
        }
        return join(chunks, total);
    }

    /* TIFF LZW — libtiff-compatible string table (clear=256, EOI=257,
       9..12 bits, MSB-first). Returns null on a structurally bad stream. */
    private static byte[] lzw(byte[] in) {
        List<byte[]> chunks = new ArrayList<>();
        int total = 0;
        byte[][] table = new byte[4096][];
        int next = 258;
        int bits = 9;
        byte[] prevStr = null;   /* string of the previous code */
        BitR br = new BitR(in, 0, false);
        try {
            while (true) {
                int code = br.read(bits);
                if (code == 257) break;               /* EOI */
                if (code == 256) {                    /* clear */
                    next = 258;
                    bits = 9;
                    prevStr = null;
                    continue;
                }
                byte[] entry;
                if (code < 256) {
                    entry = new byte[]{(byte) code};
                } else if (code < next && table[code] != null) {
                    entry = table[code];
                } else if (code == next && prevStr != null) {
                    /* KwKwK: prevStr + first char of prevStr */
                    entry = new byte[prevStr.length + 1];
                    System.arraycopy(prevStr, 0, entry, 0, prevStr.length);
                    entry[prevStr.length] = prevStr[0];
                } else {
                    return join(chunks, total);       /* corrupt — keep what we got */
                }
                if (total + entry.length <= 96 * 1024 * 1024) {
                    chunks.add(entry);
                    total += entry.length;
                } else {
                    break;
                }
                if (prevStr != null && next < 4096) {
                    byte[] ne = new byte[prevStr.length + 1];
                    System.arraycopy(prevStr, 0, ne, 0, prevStr.length);
                    ne[prevStr.length] = entry[0];
                    table[next] = ne;
                    next++;
                    /* v1.26: PIL/libtiff use the early-change convention —
                       the width grows one entry sooner than the spec text
                       (bump when next REACHES (1<<bits)-1, not when it
                       passes it). Verified against every PIL LZW fixture. */
                    if (next >= (1 << bits) - 1 && bits < 12) bits++;
                }
                prevStr = entry;
            }
        } catch (Throwable t) {
            /* truncated stream — return what we have */
        }
        return join(chunks, total);
    }

    /* v1.26: TIFF Deflate writers differ — libtiff/PIL emit a full zlib
       stream (0x78 header); a few emit raw deflate. Detect by the CMF byte
       and fall back to the other mode when a stream yields no output.
       v1.29 FIX: try the DETECTED mode first (the swapped order ran raw-
       inflate over a zlib stream first — wasted a full failed inflate per
       strip, and a rare coincidental stored-block parse could yield
       garbage that the length>0 check accepted). */
    private static byte[] inflate(byte[] in) {
        boolean zlibFirst = in.length > 0 && (in[0] & 255) == 0x78;
        byte[] a = inflate(in, zlibFirst);
        if (a != null && a.length > 0) return a;
        byte[] b = inflate(in, !zlibFirst);
        return b != null ? b : a;
    }

    private static byte[] inflate(byte[] in, boolean raw) {
        try {
            Inflater inf = new Inflater(!raw);
            inf.setInput(in);
            List<byte[]> chunks = new ArrayList<>();
            int total = 0;
            byte[] buf = new byte[65536];
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n > 0) {
                    chunks.add(slice(buf, 0, n));
                    total += n;
                    if (total > 96 * 1024 * 1024) break;
                } else if (inf.needsInput() || inf.needsDictionary()) {
                    break;
                }
            }
            inf.end();
            return join(chunks, total);
        } catch (Throwable t) {
            return null;
        }
    }

    /* --------------------------------------------------------------- utils */

    private static byte[] join(List<byte[]> chunks, int total) {
        if (total <= 0) return null;
        byte[] out = new byte[total];
        int p = 0;
        for (byte[] c : chunks) {
            System.arraycopy(c, 0, out, p, c.length);
            p += c.length;
        }
        return out;
    }

    private static byte[] slice(byte[] src, int off, int len) {
        if (len <= 0) return new byte[0];
        if (off + len > src.length) len = src.length - off;
        if (len <= 0) return new byte[0];
        byte[] out = new byte[len];
        System.arraycopy(src, off, out, 0, len);
        return out;
    }

    static long ifdInt(R r, int e, int type, long cnt, int idx) {
        int sz = typeSize(type);
        long voff = r.u32(e + 8);
        if (sz * cnt <= 4) voff = e + 8;   /* value(s) inline */
        long at = voff + (long) idx * sz;
        if (at < 0 || at + sz > r.b.length) return 0;
        switch (type) {
            case 1: return r.b[(int) at] & 255;
            case 3: return r.u16((int) at);
            case 4: return r.u32((int) at);
            case 6: return r.b[(int) at];
            case 8: return r.s16((int) at);
            case 9: return r.s32((int) at);
            default: return 0;
        }
    }

    static long[] ifdLongs(R r, int e, int type, long cnt) {
        int n = (int) Math.min(cnt, 8192);
        long[] out = new long[n];
        for (int i = 0; i < n; i++) out[i] = ifdInt(r, e, type, cnt, i);
        return out;
    }

    static int typeSize(int type) {
        switch (type) {
            case 1: case 2: case 6: return 1;
            case 3: case 8: return 2;
            case 4: case 9: return 4;
            case 5: case 10: return 8;
            default: return 1;
        }
    }

    /* little/big endian reader */
    static final class R {
        final byte[] b;
        final boolean le;

        R(byte[] b, boolean le) {
            this.b = b;
            this.le = le;
        }

        int u16(int o) {
            if (o + 2 > b.length) return 0;
            return le ? ((b[o] & 255) | ((b[o + 1] & 255) << 8))
                    : (((b[o] & 255) << 8) | (b[o + 1] & 255));
        }

        int s16(int o) {
            int v = u16(o);
            return v > 32767 ? v - 65536 : v;
        }

        long u32(int o) {
            if (o + 4 > b.length) return 0;
            if (le) {
                return ((b[o] & 255L) | ((b[o + 1] & 255L) << 8)
                        | ((b[o + 2] & 255L) << 16) | ((b[o + 3] & 255L) << 24)) & 0xFFFFFFFFL;
            }
            return (((b[o] & 255L) << 24) | ((b[o + 1] & 255L) << 16)
                    | ((b[o + 2] & 255L) << 8) | (b[o + 3] & 255L)) & 0xFFFFFFFFL;
        }

        long s32(int o) {
            long v = u32(o);
            return v > 0x7FFFFFFFL ? v - 0x100000000L : v;
        }
    }

    /* MSB-first bit reader over a byte[] window; 16-bit reads of
       little-endian files swap the two bytes (TIFF sample order). */
    static final class BitR {
        private final byte[] b;
        private final int base;
        private int bit;
        private final boolean le16;

        BitR(byte[] b, int base, boolean le16) {
            this.b = b;
            this.base = base;
            this.le16 = le16;
        }

        int read(int n) {
            int v = 0;
            for (int i = 0; i < n; i++) {
                int byteIdx = base + (bit >> 3);
                if (byteIdx >= b.length) {
                    bit += n - i;
                    v = finishRead(v, n);
                    return v;   /* truncated: zero-pad */
                }
                int bitv = (b[byteIdx] >> (7 - (bit & 7))) & 1;
                v = (v << 1) | bitv;
                bit++;
            }
            return finishRead(v, n);
        }

        private int finishRead(int v, int n) {
            if (n == 16 && le16) {
                return ((v & 0xFF) << 8) | ((v >> 8) & 0xFF);
            }
            return v;
        }
    }
}
