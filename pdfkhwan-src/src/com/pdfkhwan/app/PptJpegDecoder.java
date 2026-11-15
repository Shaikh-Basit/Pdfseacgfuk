package com.pdfkhwan.app;

/**
 * Pure-Java baseline + progressive JPEG decoder.
 *
 * WHY THIS EXISTS: Android's BitmapFactory (Skia) can only decode JPEGs
 * with 1 (grayscale) or 3 (YCbCr) components. 4-component CMYK / YCCK
 * JPEGs — the standard print/Photoshop export, extremely common in decks
 * authored in design workflows — return null from BitmapFactory, so every
 * picture that used one rendered as «تصویر خوانده نشد». This decoder
 * covers those, plus any odd-but-valid JPEG Skia happens to reject.
 *
 * SUPPORTED
 *   - SOF0 (baseline), SOF1 (extended sequential), SOF2 (progressive)
 *   - 1 component (grayscale), 3 (YCbCr), 4 (CMYK / Adobe YCCK)
 *   - Huffman coding (standard or custom DHT), restart intervals (DRI/RSTn)
 *   - chroma subsampling (all HxV ratios), Adobe APP14 transform flag
 *   - progressive spectral selection + successive approximation
 *   - interleaved AND non-interleaved (per-component) scans
 *
 * NOT SUPPORTED (returns null): arithmetic coding, 12-bit precision,
 * lossless JPEG — none of these occur in PowerPoint media parts in the
 * wild.
 *
 * The core is dependency-free (no android.* imports) so it can be tested
 * on a desktop JVM; the caller wraps {@link Result#pixels} in a Bitmap.
 */
public final class PptJpegDecoder {

    /** decoded image: ARGB packed ints, row-major, top-down */
    public static final class Result {
        public final int width, height;
        public final int[] pixels;
        Result(int w, int h, int[] p) { width = w; height = h; pixels = p; }
    }

    private PptJpegDecoder() {}

    /* -------------------------------------------------------------- tables */

    private static final int[] ZIGZAG = {
            0,  1,  8, 16,  9,  2,  3, 10,
           17, 24, 32, 25, 18, 11,  4,  5,
           12, 19, 26, 33, 40, 48, 41, 34,
           27, 20, 13,  6,  7, 14, 21, 28,
           35, 42, 49, 56, 57, 50, 43, 36,
           29, 22, 15, 23, 30, 37, 44, 51,
           58, 59, 52, 45, 38, 31, 39, 46,
           53, 60, 61, 54, 47, 55, 62, 63 };

    /* IDCT basis: COS[k*8+x] = c(k)*cos((2x+1)k*pi/16) * 0.5 */
    private static final float[] COS = new float[64];
    static {
        for (int k = 0; k < 8; k++) {
            double ck = k == 0 ? Math.sqrt(0.5) : 1.0;
            for (int x = 0; x < 8; x++) {
                COS[k * 8 + x] = (float) (ck * Math.cos((2 * x + 1) * k * Math.PI / 16.0) * 0.5);
            }
        }
    }

    /* ----------------------------------------------------------- huffman */

    private static final class Huff {
        final int[] minCode = new int[17];
        final int[] maxCode = new int[17];
        final int[] valPtr = new int[17];
        final short[] values;

        Huff(int[] counts, int[] symbols) {
            int code = 0, k = 0;
            for (int len = 1; len <= 16; len++) {
                valPtr[len] = k;
                minCode[len] = code;
                code += counts[len - 1];
                k += counts[len - 1];
                maxCode[len] = code - 1;
                code <<= 1;
            }
            values = new short[symbols.length];
            for (int i = 0; i < symbols.length; i++) values[i] = (short) symbols[i];
        }

        /** decode one symbol; -1 on invalid code */
        int decode(BitReader br) {
            int code = 0;
            for (int len = 1; len <= 16; len++) {
                code = (code << 1) | br.nextBit();
                if (maxCode[len] >= minCode[len] && code >= minCode[len] && code <= maxCode[len]) {
                    return values[valPtr[len] + (code - minCode[len])] & 0xFFFF;
                }
            }
            return -1;
        }
    }

    /** bit reader with 0xFF00 de-stuffing; halts at real markers */
    private static final class BitReader {
        final byte[] d;
        int pos;
        int bitBuf = 0, bitCnt = 0;
        boolean markerHit = false;
        int marker = -1;

        BitReader(byte[] d, int pos) { this.d = d; this.pos = pos; }

        private int readByte() {
            if (markerHit) return 0;
            if (pos >= d.length) { markerHit = true; return 0; }
            int b = d[pos] & 255;
            if (b == 0xFF) {
                if (pos + 1 >= d.length) { markerHit = true; marker = 0xD9; return 0; }
                int n = d[pos + 1] & 255;
                if (n == 0x00) { pos += 2; return 0xFF; }
                // real marker (or FF FF padding)
                if (n != 0xFF) { markerHit = true; marker = n; return 0; }
                // FF FF: skip pad, try again
                pos++;
                return readByte();
            }
            pos++;
            return b;
        }

        int nextBit() {
            if (bitCnt == 0) {
                bitBuf = readByte();
                bitCnt = 8;
            }
            bitCnt--;
            return (bitBuf >> bitCnt) & 1;
        }

        int receive(int n) {
            int v = 0;
            for (int i = 0; i < n; i++) v = (v << 1) | nextBit();
            return v;
        }

        boolean atMarker() { return markerHit && bitCnt <= 0; }

        /** restart-boundary reset: drop padding bits, then expect (and skip)
            an RSTn marker right at the current position. Must be called
            UNCONDITIONALLY at every restart boundary — at that point the
            FF has usually not been read yet, so atMarker() is still false. */
        void restart() {
            bitBuf = 0; bitCnt = 0;
            markerHit = false;
            marker = -1;
            /* tolerate FF FF padding before the real marker */
            while (pos + 1 < d.length && (d[pos] & 255) == 0xFF && (d[pos + 1] & 255) == 0xFF) pos++;
            if (pos + 1 < d.length && (d[pos] & 255) == 0xFF) {
                int m = d[pos + 1] & 255;
                if (m >= 0xD0 && m <= 0xD7) pos += 2;   // RSTn — skip
                /* anything else (EOI/next marker): leave it in place; the
                   next readByte will halt on it gracefully */
            }
        }
    }

    /* ------------------------------------------------------------ decoder */

    private static final class Comp {
        int id;
        int h = 1, v = 1;
        int tq;
        int dcTbl, acTbl;
        int dcPred;
        int[][][] coefs;
        int blocksAcross, blocksDown;
        /* v1.29 FIX: the component's OWN data-unit grid for single-component
           scans (T.81 A.2.3): width_in_blocks = ceil(W*Hi/(Hmax*8)). The MCU-
           padded blocksAcross is a SUPERSET — using it for non-interleaved
           scans desynchronized the bitstream whenever W*Hi/8 was not a
           multiple of Hmax*8/Hmax (progressive 4:2:0 photos scrambled). */
        int wib, hib;
    }

    public static Result decode(byte[] data, int maxDim) {
        try {
            return decodeInternal(data, maxDim);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Result decodeInternal(byte[] data, int maxDim) {
        if (data == null || data.length < 4) return null;
        if ((data[0] & 255) != 0xFF || (data[1] & 255) != 0xD8) return null;

        int[][] qtab = new int[4][64];
        Huff[] dcTables = new Huff[4];
        Huff[] acTables = new Huff[4];

        int width = 0, height = 0, ncomp = 0;
        Comp[] comps = null;
        boolean progressive = false;
        boolean sawAdobe = false;
        int adobeTransform = -1;
        int restartInterval = 0;
        boolean done = false;

        int p = 2;
        while (p + 1 < data.length && !done) {
            if ((data[p] & 255) != 0xFF) { p++; continue; }
            int marker = data[p + 1] & 255;
            if (marker == 0xFF) { p++; continue; }
            if (marker == 0xD9) break;                       // EOI
            if (marker >= 0xD0 && marker <= 0xD7) { p += 2; continue; } // stray RST

            if (p + 4 > data.length) break;
            int segLen = ((data[p + 2] & 255) << 8) | (data[p + 3] & 255);
            if (segLen < 2 || p + 2 + segLen > data.length) break;
            int s = p + 4;              // payload start
            int e = p + 2 + segLen;     // payload end

            switch (marker) {
                case 0xC0: case 0xC1: case 0xC2: {
                    if ((data[s] & 255) != 8) return null;   // 8-bit only
                    if (e - s < 6) return null;              // v1.29: truncated SOF
                    height = ((data[s + 1] & 255) << 8) | (data[s + 2] & 255);
                    width = ((data[s + 3] & 255) << 8) | (data[s + 4] & 255);
                    if (width <= 0 || height <= 0 || width > 40000 || height > 40000) return null;
                    /* v1.29 FIX: pixel-count cap BEFORE allocating any plane /
                       coef array — a crafted tiny file declaring 40000x40000
                       drove multi-GB allocations on the UI thread before the
                       post-decode maxDim check could run. */
                    if ((long) width * height > 64L * 1024 * 1024) return null;
                    ncomp = data[s + 5] & 255;
                    if (ncomp != 1 && ncomp != 3 && ncomp != 4) return null;
                    if (e - s < 6 + ncomp * 3) return null;  // v1.29: SOF must carry all comps
                    progressive = marker == 0xC2;
                    comps = new Comp[ncomp];
                    for (int i = 0; i < ncomp; i++) {
                        Comp c = new Comp();
                        int q = s + 6 + i * 3;
                        c.id = data[q] & 255;
                        c.h = (data[q + 1] & 255) >> 4;
                        c.v = (data[q + 1] & 255) & 15;
                        c.tq = data[q + 2] & 255;
                        if (c.h < 1 || c.h > 4 || c.v < 1 || c.v > 4) return null;
                        comps[i] = c;
                    }
                    break;
                }
                case 0xC3: case 0xC5: case 0xC6: case 0xC7:
                case 0xC9: case 0xCA: case 0xCB: case 0xCD:
                case 0xCE: case 0xCF:
                    return null;                             // unsupported SOF
                case 0xC4: {                                 // DHT
                    int q = s;
                    while (q < e) {
                        int tci = data[q] & 255;
                        int tc = tci >> 4, th = tci & 15;
                        if (th > 3) return null;
                        int[] counts = new int[16];
                        int total = 0;
                        for (int i = 0; i < 16; i++) { counts[i] = data[q + 1 + i] & 255; total += counts[i]; }
                        if (total < 1 || total > 256 || q + 17 + total > e) return null;
                        int[] syms = new int[total];
                        for (int i = 0; i < total; i++) syms[i] = data[q + 17 + i] & 255;
                        Huff h = new Huff(counts, syms);
                        if (tc == 0) dcTables[th] = h; else acTables[th] = h;
                        /* v1.29 FIX: a DHT table entry is 1 (TcTh) + 16 (counts)
                           + total = 17+total bytes — advancing 18+total swallowed
                           the NEXT table's TcTh byte, so multi-table DHT segments
                           (one DHT with DC+AC) built garbage tables or failed. */
                        q += 17 + total;
                    }
                    break;
                }
                case 0xDB: {                                 // DQT
                    int q = s;
                    while (q < e) {
                        int pq = (data[q] & 255) >> 4;
                        int tq = (data[q] & 255) & 15;
                        if (tq > 3) return null;
                        if (pq == 1) {
                            if (q + 1 + 128 > e) return null;
                            for (int i = 0; i < 64; i++)
                                qtab[tq][ZIGZAG[i]] = ((data[q + 1 + 2 * i] & 255) << 8) | (data[q + 2 + 2 * i] & 255);
                            q += 129;
                        } else {
                            if (q + 1 + 64 > e) return null;
                            for (int i = 0; i < 64; i++) qtab[tq][ZIGZAG[i]] = data[q + 1 + i] & 255;
                            q += 65;
                        }
                    }
                    break;
                }
                case 0xDD: {                                 // DRI
                    if (e - s < 2) return null;               // v1.29: truncated DRI
                    restartInterval = ((data[s] & 255) << 8) | (data[s + 1] & 255);
                    break;
                }
                case 0xEE: {                                 // APP14 Adobe
                    if (e - s >= 12 && data[s] == 'A' && data[s + 1] == 'd'
                            && data[s + 2] == 'o' && data[s + 3] == 'b' && data[s + 4] == 'e') {
                        sawAdobe = true;
                        adobeTransform = data[s + 11] & 255;
                    }
                    break;
                }
                case 0xDA: {                                 // SOS
                    if (comps == null || width == 0) return null;
                    int ns = data[s] & 255;
                    if (ns < 1 || ns > ncomp || s + 1 + ns * 2 + 3 > e) return null;
                    int[] scanIdx = new int[ns];
                    for (int i = 0; i < ns; i++) {
                        int cs = data[s + 1 + i * 2] & 255;
                        int td = (data[s + 2 + i * 2] & 255) >> 4;
                        int ta = (data[s + 2 + i * 2] & 255) & 15;
                        int found = -1;
                        for (int j = 0; j < ncomp; j++) if (comps[j].id == cs) { found = j; break; }
                        if (found < 0) return null;
                        /* v1.29 FIX: the tables are Huff[4] — an out-of-range
                           Td/Ta threw AIOOBE and killed the whole decode. */
                        if (td > 3 || ta > 3) return null;
                        scanIdx[i] = found;
                        comps[found].dcTbl = td;
                        comps[found].acTbl = ta;
                    }
                    int ss = data[s + 1 + ns * 2] & 255;
                    int se = data[s + 2 + ns * 2] & 255;
                    int a = data[s + 3 + ns * 2] & 255;
                    int ah = a >> 4, al = a & 15;
                    if (ss > 63 || se > 63 || ss > se && ss != 0) return null;
                    if (!progressive && (ss != 0 || se != 63)) return null;

                    int hmax = 1, vmax = 1;
                    for (Comp c : comps) { hmax = Math.max(hmax, c.h); vmax = Math.max(vmax, c.v); }
                    int mcuX = (width + hmax * 8 - 1) / (hmax * 8);
                    int mcuY = (height + vmax * 8 - 1) / (vmax * 8);
                    for (Comp c : comps) {
                        /* v1.29 FIX: per-component data-unit grid for
                           single-component scans (see Comp.wib). */
                        if (c.wib == 0) {
                            c.wib = (int) (((long) width * c.h + hmax * 8 - 1) / (hmax * 8));
                            c.hib = (int) (((long) height * c.v + vmax * 8 - 1) / (vmax * 8));
                        }
                        if (c.coefs == null) {
                            c.blocksAcross = mcuX * c.h;
                            c.blocksDown = mcuY * c.v;
                            c.coefs = new int[c.blocksDown][c.blocksAcross][64];
                        }
                    }

                    BitReader br = new BitReader(data, e);
                    decodeScan(br, comps, scanIdx, mcuX, mcuY, ss, se, ah, al,
                            progressive, dcTables, acTables, restartInterval);

                    /* jump to the marker the reader halted at */
                    p = br.pos;
                    if (p + 1 < data.length && (data[p] & 255) == 0xFF
                            && (data[p + 1] & 255) >= 0xD0 && (data[p + 1] & 255) <= 0xD7) {
                        p += 2;                              // trailing RST
                    }
                    // continue main loop at the marker
                    // (p may already point at 0xFF)
                    while (p + 1 < data.length && (data[p] & 255) != 0xFF) p++;
                    continue;                                // don't run p = e
                }
                default:
                    break;                                   // APPn/COM/DNL skip
            }
            p = e;
        }

        if (comps == null || width == 0 || height == 0) return null;

        /* ---------------- dequantize + IDCT into component planes -------- */
        int hmax = 1, vmax = 1;
        for (Comp c : comps) { hmax = Math.max(hmax, c.h); vmax = Math.max(vmax, c.v); }
        int mcuX = (width + hmax * 8 - 1) / (hmax * 8);
        int mcuY = (height + vmax * 8 - 1) / (vmax * 8);
        int fullW = mcuX * hmax * 8, fullH = mcuY * vmax * 8;

        byte[][] planes = new byte[ncomp][];
        for (int ci = 0; ci < ncomp; ci++) {
            Comp c = comps[ci];
            int pw = c.blocksAcross * 8, ph = c.blocksDown * 8;
            byte[] plane = new byte[pw * ph];
            int[] qt = qtab[c.tq];
            float[] block = new float[64];
            for (int by = 0; by < c.blocksDown; by++) {
                for (int bx = 0; bx < c.blocksAcross; bx++) {
                    int[] coef = c.coefs[by][bx];
                    /* coefficients AND quant table are both in natural order
                       here (DQT payload was de-zigzagged at parse time) —
                       the old ZIGZAG[i] destination silently shuffled AC
                       terms, blurring chroma on subsampled images */
                    for (int i = 0; i < 64; i++) block[i] = coef[i] * qt[i];
                    idct8x8(block);
                    int off = (by * 8) * pw + bx * 8;
                    for (int y = 0; y < 8; y++) {
                        int row = off + y * pw;
                        for (int x = 0; x < 8; x++) {
                            int v = (int) block[y * 8 + x];
                            plane[row + x] = (byte) (v < 0 ? 0 : (v > 255 ? 255 : v));
                        }
                    }
                }
            }
            planes[ci] = plane;
        }

        /* ---------------- upsample + color convert ----------------------- */
        int[] out = new int[fullW * fullH];
        if (ncomp == 1) {
            byte[] g0 = planes[0];
            for (int y = 0; y < fullH; y++) {
                int row = y * fullW, srow = y * fullW;
                for (int x = 0; x < fullW; x++) {
                    int g = g0[srow + x] & 255;
                    out[row + x] = 0xFF000000 | (g << 16) | (g << 8) | g;
                }
            }
        } else {
            /* nearest-neighbour sampling from each plane (handles any h/v) */
            int[] pw = new int[ncomp], ph = new int[ncomp];
            for (int i = 0; i < ncomp; i++) { pw[i] = comps[i].blocksAcross * 8; ph[i] = comps[i].blocksDown * 8; }
            boolean ycck = ncomp == 4 && sawAdobe && adobeTransform == 2;
            boolean adobeCmyk = ncomp == 4 && sawAdobe && adobeTransform != 1;
            for (int y = 0; y < fullH; y++) {
                int row = y * fullW;
                for (int x = 0; x < fullW; x++) {
                    int r, g, b;
                    if (ncomp == 3) {
                        int Y = sample(planes[0], pw[0], ph[0], x, y, fullW, fullH);
                        int Cb = sample(planes[1], pw[1], ph[1], x, y, fullW, fullH);
                        int Cr = sample(planes[2], pw[2], ph[2], x, y, fullW, fullH);
                        r = Y + ((91881 * (Cr - 128)) >> 16);
                        g = Y - ((22554 * (Cb - 128) + 46802 * (Cr - 128)) >> 16);
                        b = Y + ((116130 * (Cb - 128)) >> 16);
                    } else {
                        int c0 = sample(planes[0], pw[0], ph[0], x, y, fullW, fullH);
                        int c1 = sample(planes[1], pw[1], ph[1], x, y, fullW, fullH);
                        int c2 = sample(planes[2], pw[2], ph[2], x, y, fullW, fullH);
                        int k = sample(planes[3], pw[3], ph[3], x, y, fullW, fullH);
                        if (ycck) {
                            /* first three = YCbCr of the INVERTED CMY (all four
                               channels are stored inverted in Adobe files);
                               K here is the inverted black plate */
                            int Y = c0, Cb = c1, Cr = c2;
                            int ir = Y + ((91881 * (Cr - 128)) >> 16);
                            int ig = Y - ((22554 * (Cb - 128) + 46802 * (Cr - 128)) >> 16);
                            int ib = Y + ((116130 * (Cb - 128)) >> 16);
                            r = cl(ir) * k / 255;
                            g = cl(ig) * k / 255;
                            b = cl(ib) * k / 255;
                        } else if (adobeCmyk) {
                            /* Adobe CMYK (APP14 transform 0): every channel is
                               stored INVERTED (paper white = 255); the final
                               RGB is simply the stored CMY scaled by K:
                               R = C*K/255 — verified against PIL/libjpeg */
                            r = c0 * k / 255;
                            g = c1 * k / 255;
                            b = c2 * k / 255;
                        } else {
                            /* plain CMYK */
                            r = (255 - c0) * (255 - k) / 255;
                            g = (255 - c1) * (255 - k) / 255;
                            b = (255 - c2) * (255 - k) / 255;
                        }
                    }
                    out[row + x] = 0xFF000000 | (cl(r) << 16) | (cl(g) << 8) | cl(b);
                }
            }
        }

        /* ---------------- optional downscale + crop ----------------------- */
        if (maxDim > 0 && (width > maxDim || height > maxDim)) {
            double sc = Math.min((double) maxDim / width, (double) maxDim / height);
            int nw = Math.max(1, (int) Math.round(width * sc));
            int nh = Math.max(1, (int) Math.round(height * sc));
            int[] scaled = new int[nw * nh];
            for (int y = 0; y < nh; y++) {
                int y0 = y * height / nh, y1 = Math.max(y0 + 1, (y + 1) * height / nh);
                for (int x = 0; x < nw; x++) {
                    int x0 = x * width / nw, x1 = Math.max(x0 + 1, (x + 1) * width / nw);
                    long rr = 0, gg = 0, bb = 0, n = 0;
                    for (int yy = y0; yy < y1 && yy < height; yy++)
                        for (int xx = x0; xx < x1 && xx < width; xx++) {
                            int px = out[yy * fullW + xx];
                            rr += (px >> 16) & 255; gg += (px >> 8) & 255; bb += px & 255; n++;
                        }
                    if (n == 0) n = 1;
                    scaled[y * nw + x] = 0xFF000000
                            | ((int) (rr / n) << 16) | ((int) (gg / n) << 8) | (int) (bb / n);
                }
            }
            return new Result(nw, nh, scaled);
        }

        if (width == fullW && height == fullH) return new Result(width, height, out);
        int[] cropped = new int[width * height];
        for (int y = 0; y < height; y++)
            System.arraycopy(out, y * fullW, cropped, y * width, width);
        return new Result(width, height, cropped);
    }

    /** nearest-neighbour sample from a possibly smaller plane */
    private static int sample(byte[] plane, int pw, int ph, int x, int y, int fullW, int fullH) {
        int sx = x * pw / fullW; if (sx >= pw) sx = pw - 1; if (sx < 0) sx = 0;
        int sy = y * ph / fullH; if (sy >= ph) sy = ph - 1; if (sy < 0) sy = 0;
        return plane[sy * pw + sx] & 255;
    }

    private static int cl(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }

    /* ------------------------------------------------------------ scans */

    private static void decodeScan(BitReader br, Comp[] comps, int[] scanIdx,
                                   int mcuX, int mcuY, int ss, int se, int ah, int al,
                                   boolean progressive, Huff[] dcT, Huff[] acT, int rstInt) {

        boolean interleaved = scanIdx.length > 1;

        /* ---------- progressive: DC scans ---------- */
        if (progressive && ss == 0) {
            boolean refine = ah != 0;
            if (interleaved) {
                int n = 0;
                outer:
                for (int my = 0; my < mcuY; my++) {
                    for (int mx = 0; mx < mcuX; mx++) {
                        if (br.atMarker()) break outer;
                        for (int si = 0; si < scanIdx.length; si++) {
                            Comp c = comps[scanIdx[si]];
                            for (int v = 0; v < c.v; v++)
                                for (int h = 0; h < c.h; h++) {
                                    int bx = mx * c.h + h, by = my * c.v + v;
                                    if (bx >= c.blocksAcross || by >= c.blocksDown) continue;
                                    dcBlock(br, c, bx, by, refine, al, dcT[c.dcTbl]);
                                }
                        }
                        n++;
                        if (rstInt > 0 && n % rstInt == 0 && !(my == mcuY - 1 && mx == mcuX - 1)) {
                            for (int si = 0; si < scanIdx.length; si++) comps[scanIdx[si]].dcPred = 0;
                            br.restart();
                        }
                    }
                }
            } else {
                Comp c = comps[scanIdx[0]];
                /* v1.29 FIX: iterate the component's OWN grid (wib x hib),
                   not the MCU-padded blocksAcross/blocksDown. */
                int total = c.wib * c.hib, n = 0;
                while (n < total && !br.atMarker()) {
                    int bx = n % c.wib, by = n / c.wib;
                    dcBlock(br, c, bx, by, refine, al, dcT[c.dcTbl]);
                    n++;
                    if (rstInt > 0 && n % rstInt == 0 && n < total) {
                        c.dcPred = 0;
                        br.restart();
                    }
                }
            }
            return;
        }

        /* ---------- progressive: AC scans (always single-component) ------- */
        if (progressive) {
            boolean refine = ah != 0;
            for (int si = 0; si < scanIdx.length; si++) {
                Comp c = comps[scanIdx[si]];
                Huff ac = acT[c.acTbl];
                /* v1.29 FIX: the component's OWN grid (wib x hib) — the
                   MCU-padded count desynchronized entropy data on every
                   progressive 4:2:0 image whose width % 16 != 0. */
                int total = c.wib * c.hib, n = 0;
                int eobRun = 0;
                while (n < total) {
                    if (br.atMarker()) return;
                    int bx = n % c.wib, by = n / c.wib;
                    int[] blk = c.coefs[by][bx];
                    if (!refine) {
                        /* ---- AC first pass ---- */
                        if (eobRun > 0) { eobRun--; n++; continue; }
                        int k = ss;
                        while (k <= se) {
                            int rs = ac != null ? ac.decode(br) : -1;
                            if (rs < 0) return;
                            int r = rs >> 4, s = rs & 15;
                            if (s == 0) {
                                if (r != 15) {
                                    eobRun = (1 << r) - 1;
                                    if (r > 0) eobRun += br.receive(r);
                                    break;
                                }
                                k += 16;                    // ZRL
                            } else {
                                k += r;
                                if (k > se) break;
                                blk[ZIGZAG[k]] = extend(br.receive(s), s) << al;
                                k++;
                            }
                        }
                    } else {
                        /* ---- AC refinement (libjpeg jdhuff semantics) ---- */
                        if (eobRun > 0) {
                            eobRun--;
                            for (int k = ss; k <= se; k++) {
                                if (blk[ZIGZAG[k]] != 0 && br.nextBit() == 1)
                                    blk[ZIGZAG[k]] |= 1 << al;
                            }
                            n++;
                            continue;
                        }
                        int k = ss;
                        int p1 = 1 << al, m1 = -1 << al;
                        boolean eob = false;
                        while (k <= se) {
                            int rs = ac != null ? ac.decode(br) : -1;
                            if (rs < 0) return;
                            int r = rs >> 4, s = rs & 15;
                            if (s == 0) {
                                if (r != 15) {
                                    eobRun = (1 << r) - 1;
                                    if (r > 0) eobRun += br.receive(r);
                                    eob = true;
                                    break;
                                }
                                /* ZRL: fall through, new coefficient value 0 */
                            }
                            int val = 0;
                            if (s != 0) {
                                val = br.nextBit() == 1 ? p1 : m1;
                            }
                            /* advance over r zero-history coefficients,
                               reading correction bits for existing NZ ones */
                            while (k <= se) {
                                int z = ZIGZAG[k];
                                if (blk[z] == 0) {
                                    if (--r < 0) break;     // landing spot found
                                } else {
                                    if (br.nextBit() == 1) blk[z] |= p1;
                                }
                                k++;
                            }
                            if (s != 0) {
                                if (k > se) break;
                                blk[ZIGZAG[k]] = val;
                                k++;
                            }
                        }
                        if (eob) {
                            for (; k <= se; k++) {
                                if (blk[ZIGZAG[k]] != 0 && br.nextBit() == 1)
                                    blk[ZIGZAG[k]] |= 1 << al;
                            }
                        }
                    }
                    n++;
                    if (rstInt > 0 && n % rstInt == 0 && n < total) {
                        eobRun = 0;
                        c.dcPred = 0;
                        br.restart();
                    }
                }
            }
            return;
        }

        /* ---------- baseline sequential ---------- */
        if (interleaved) {
            int n = 0;
            outer2:
            for (int my = 0; my < mcuY; my++) {
                for (int mx = 0; mx < mcuX; mx++) {
                    if (br.atMarker()) break outer2;
                    for (int ci = 0; ci < scanIdx.length; ci++) {
                        Comp c = comps[scanIdx[ci]];
                        Huff dc = dcT[c.dcTbl], ac = acT[c.acTbl];
                        for (int v = 0; v < c.v; v++) {
                            for (int h = 0; h < c.h; h++) {
                                int bx = mx * c.h + h, by = my * c.v + v;
                                if (bx >= c.blocksAcross || by >= c.blocksDown) continue;
                                if (!baselineBlock(br, c, bx, by, dc, ac)) break outer2;
                            }
                        }
                    }
                    n++;
                    if (rstInt > 0 && n % rstInt == 0 && !(my == mcuY - 1 && mx == mcuX - 1)) {
                        for (int ci = 0; ci < scanIdx.length; ci++) comps[scanIdx[ci]].dcPred = 0;
                        br.restart();
                    }
                }
            }
        } else {
            Comp c = comps[scanIdx[0]];
            Huff dc = dcT[c.dcTbl], ac = acT[c.acTbl];
            /* v1.29 FIX: component's OWN grid (wib x hib) — same rationale
               as the progressive paths (baseline non-interleaved scans). */
            int total = c.wib * c.hib, n = 0;
            while (n < total && !br.atMarker()) {
                int bx = n % c.wib, by = n / c.wib;
                if (!baselineBlock(br, c, bx, by, dc, ac)) break;
                n++;
                if (rstInt > 0 && n % rstInt == 0 && n < total) {
                    c.dcPred = 0;
                    br.restart();
                }
            }
        }
    }

    private static boolean atEnd(BitReader br) { return br.markerHit; }

    /** one DC (progressive) block */
    private static void dcBlock(BitReader br, Comp c, int bx, int by,
                                boolean refine, int al, Huff dc) {
        if (!refine) {
            int s = dc != null ? dc.decode(br) : 0;
            int diff = 0;
            if (s > 0) {
                if (s > 15) s = 15;
                diff = extend(br.receive(s), s);
            }
            c.dcPred += diff;
            c.coefs[by][bx][0] = c.dcPred << al;
        } else {
            if (br.nextBit() == 1) c.coefs[by][bx][0] |= 1 << al;
        }
    }

    /** one baseline block; false on decode error (stop scan) */
    private static boolean baselineBlock(BitReader br, Comp c, int bx, int by, Huff dc, Huff ac) {
        int[] blk = c.coefs[by][bx];
        int s = dc != null ? dc.decode(br) : 0;
        if (s < 0) return false;
        int diff = 0;
        if (s > 0) {
            if (s > 15) s = 15;
            diff = extend(br.receive(s), s);
        }
        c.dcPred += diff;
        blk[0] = c.dcPred;
        int k = 1;
        while (k < 64) {
            int rs = ac != null ? ac.decode(br) : -1;
            if (rs < 0) return false;
            int r = rs >> 4, sz = rs & 15;
            if (sz == 0) {
                if (r != 15) break;                          // EOB
                k += 16;                                     // ZRL
            } else {
                k += r;
                if (k > 63) break;
                blk[ZIGZAG[k]] = extend(br.receive(sz), sz);
                k++;
            }
        }
        return true;
    }

    /** JPEG magnitude extension */
    private static int extend(int v, int s) {
        return v < (1 << (s - 1)) ? v - (1 << s) + 1 : v;
    }

    /* -------------------------------------------------------------- IDCT */

    /** in-place 8x8 float IDCT (separable), natural order in/out */
    private static void idct8x8(float[] b) {
        float[] tmp = new float[64];
        for (int u = 0; u < 8; u++) {
            int off = u * 8;
            float b0 = b[off], b1 = b[off + 1], b2 = b[off + 2], b3 = b[off + 3];
            float b4 = b[off + 4], b5 = b[off + 5], b6 = b[off + 6], b7 = b[off + 7];
            for (int x = 0; x < 8; x++) {
                tmp[off + x] = b0 * COS[x]
                        + b1 * COS[8 + x] + b2 * COS[16 + x] + b3 * COS[24 + x]
                        + b4 * COS[32 + x] + b5 * COS[40 + x] + b6 * COS[48 + x] + b7 * COS[56 + x];
            }
        }
        for (int x = 0; x < 8; x++) {
            for (int y = 0; y < 8; y++) {
                float sum = tmp[x] * COS[y]
                        + tmp[8 + x] * COS[8 + y] + tmp[16 + x] * COS[16 + y] + tmp[24 + x] * COS[24 + y]
                        + tmp[32 + x] * COS[32 + y] + tmp[40 + x] * COS[40 + y] + tmp[48 + x] * COS[48 + y]
                        + tmp[56 + x] * COS[56 + y];
                b[y * 8 + x] = sum + 128f;
            }
        }
    }
}
