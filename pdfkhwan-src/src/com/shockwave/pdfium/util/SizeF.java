package com.shockwave.pdfium.util;

import android.graphics.Bitmap;

/* Reconstructed from the shipped PDFKhwan dex (v1.25) — API-compatible
   with barteksc PdfiumAndroid's util.SizeF. */
public final class SizeF {
    public final float width;
    public final float height;

    public SizeF(float width, float height) {
        this.width = width;
        this.height = height;
    }

    public SizeF(Bitmap srcBmp) {
        this.width = srcBmp.getWidth();
        this.height = srcBmp.getHeight();
    }

    public float getWidth() {
        return width;
    }

    public float getHeight() {
        return height;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SizeF sizeF = (SizeF) o;
        return Float.compare(sizeF.width, width) == 0
                && Float.compare(sizeF.height, height) == 0;
    }

    @Override
    public int hashCode() {
        int result = (width != +0.0f ? Float.floatToIntBits(width) : 0);
        result = 31 * result + (height != +0.0f ? Float.floatToIntBits(height) : 0);
        return result;
    }

    @Override
    public String toString() {
        return "SizeF[" + width + "," + height + "]";
    }

    public Size toSize() {
        return new Size((int) width, (int) height);
    }
}
