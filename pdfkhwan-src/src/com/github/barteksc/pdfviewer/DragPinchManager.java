/**
 * Copyright 2016 Bartosz Schiller
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.barteksc.pdfviewer;

import android.graphics.PointF;
import android.graphics.RectF;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import com.github.barteksc.pdfviewer.model.LinkTapEvent;
import com.github.barteksc.pdfviewer.scroll.ScrollHandle;
import com.github.barteksc.pdfviewer.util.SnapEdge;
import com.shockwave.pdfium.PdfDocument;
import com.shockwave.pdfium.util.SizeF;

import static com.github.barteksc.pdfviewer.util.Constants.Pinch.MAXIMUM_ZOOM;
import static com.github.barteksc.pdfviewer.util.Constants.Pinch.MINIMUM_ZOOM;

/**
 * This Manager takes care of moving the PDFView,
 * set its zoom track user actions.
 */
class DragPinchManager implements GestureDetector.OnGestureListener, GestureDetector.OnDoubleTapListener, ScaleGestureDetector.OnScaleGestureListener, View.OnTouchListener {

    private PDFView pdfView;
    private AnimationManager animationManager;

    private GestureDetector gestureDetector;
    private ScaleGestureDetector scaleGestureDetector;

    private boolean scrolling = false;
    private boolean scaling = false;
    private boolean enabled = false;

    /* ================== v1.37 ==================
       pageFlip: «ورق‌زدن» mode — one page at a time; a single-finger
       drag rubber-bands the page (22% of finger travel) and flips on
       release (quarter-screen threshold) or on fling. While zoomed in,
       normal panning works. Zooming always stays available.
       zoomLocked: pinch-zoom is ignored (two-finger pan still works).
       zoomAtHundred: fired when a pinch crosses exactly 100% — the
       activity shows a subtle ۱۰۰٪ pill + a light haptic tick. */
    private boolean pageFlip = false;
    private boolean zoomLocked = false;
    /* v1.39: level-2 double tap — the jump to the MAX zoom is opt-in
       (OFF by default); level 1 (one zoom-in step) is always on. */
    private boolean doubleTapMax = false;
    public Runnable zoomAtHundred = null;
    private float flipAccum = 0;
    private boolean flipDragging = false;
    private long lastHundredAt = 0;

    void setPageFlipMode(boolean v) { pageFlip = v; }
    void setZoomLocked(boolean v) { zoomLocked = v; }
    void setDoubleTapMax(boolean v) { doubleTapMax = v; }

    DragPinchManager(PDFView pdfView, AnimationManager animationManager) {
        this.pdfView = pdfView;
        this.animationManager = animationManager;
        gestureDetector = new GestureDetector(pdfView.getContext(), this);
        scaleGestureDetector = new ScaleGestureDetector(pdfView.getContext(), this);
        pdfView.setOnTouchListener(this);
    }

    void enable() {
        enabled = true;
    }

    void disable() {
        enabled = false;
    }

    void disableLongpress(){
        gestureDetector.setIsLongpressEnabled(false);
    }

    @Override
    public boolean onSingleTapConfirmed(MotionEvent e) {
        /* v7 FIX (link taps also toggled the reader bars): check the link
           FIRST — a tap that navigates must not also toggle the UI. */
        boolean linkTapped = checkLinkTapped(e.getX(), e.getY());
        boolean onTapHandled = false;
        if (!linkTapped) {
            onTapHandled = pdfView.callbacks.callOnTap(e);
        }
        if (!onTapHandled && !linkTapped) {
            ScrollHandle ps = pdfView.getScrollHandle();
            if (ps != null && !pdfView.documentFitsView()) {
                if (!ps.shown()) {
                    ps.show();
                } else {
                    ps.hide();
                }
            }
        }
        pdfView.performClick();
        return true;
    }

    private boolean checkLinkTapped(float x, float y) {
        PdfFile pdfFile = pdfView.pdfFile;
        if (pdfFile == null) {
            return false;
        }
        float mappedX = -pdfView.getCurrentXOffset() + x;
        float mappedY = -pdfView.getCurrentYOffset() + y;
        int page = pdfFile.getPageAtOffset(pdfView.isSwipeVertical() ? mappedY : mappedX, pdfView.getZoom());
        SizeF pageSize = pdfFile.getScaledPageSize(page, pdfView.getZoom());
        int pageX, pageY;
        if (pdfView.isSwipeVertical()) {
            pageX = (int) pdfFile.getSecondaryPageOffset(page, pdfView.getZoom());
            pageY = (int) pdfFile.getPageOffset(page, pdfView.getZoom());
        } else {
            pageY = (int) pdfFile.getSecondaryPageOffset(page, pdfView.getZoom());
            pageX = (int) pdfFile.getPageOffset(page, pdfView.getZoom());
        }
        for (PdfDocument.Link link : pdfFile.getPageLinks(page)) {
            RectF mapped = pdfFile.mapRectToDevice(page, pageX, pageY, (int) pageSize.getWidth(),
                    (int) pageSize.getHeight(), link.getBounds());
            mapped.sort();
            if (mapped.contains(mappedX, mappedY)) {
                pdfView.callbacks.callLinkHandler(new LinkTapEvent(x, y, mappedX, mappedY, mapped, link));
                return true;
            }
        }
        return false;
    }

    private void startPageFling(MotionEvent downEvent, MotionEvent ev, float velocityX, float velocityY) {
        if (!checkDoPageFling(velocityX, velocityY)) {
            return;
        }

        int direction;
        if (pdfView.isSwipeVertical()) {
            direction = velocityY > 0 ? -1 : 1;
        } else if (pdfView.isBookRtl()) {
            /* v1.47: کتاب RTL — پرتاب به راست یعنی جلو */
            direction = velocityX > 0 ? 1 : -1;
        } else {
            direction = velocityX > 0 ? -1 : 1;
        }
        // get the focused page during the down event to ensure only a single page is changed
        float delta = pdfView.isSwipeVertical() ? ev.getY() - downEvent.getY() : ev.getX() - downEvent.getX();
        float offsetX = pdfView.getCurrentXOffset() - delta * pdfView.getZoom();
        float offsetY = pdfView.getCurrentYOffset() - delta * pdfView.getZoom();
        int startingPage = pdfView.findFocusPage(offsetX, offsetY);
        int targetPage = Math.max(0, Math.min(pdfView.getPageCount() - 1, startingPage + direction));

        SnapEdge edge = pdfView.findSnapEdge(targetPage);
        float offset = pdfView.snapOffsetForPage(targetPage, edge);
        animationManager.startPageFlingAnimation(-offset);
    }

    @Override
    public boolean onDoubleTap(MotionEvent e) {
        if (!pdfView.isDoubletapEnabled()) {
            return false;
        }
        /* v1.39: the classic TWO-LEVEL model with level 2 behind an opt-in:
           Level 1 (ALWAYS ON — the default): below the mid zoom a double
           tap zooms IN one step to the mid zoom, centred on the finger —
           the good old behaviour users asked to keep.
           Level 2 (opt-in, OFF by default — doubleTapMax): while already
           at/above the mid zoom, the NEXT double tap jumps to the MAX
           zoom (8x) — the old "فوق‌العاده" level. With the switch off,
           that second double tap simply returns to the 100% fit
           baseline — the standard toggle of the best readers. */
        float z = pdfView.getZoom();
        float mid = pdfView.getMidZoom();
        float max = pdfView.getMaxZoom();
        if (z < mid * 0.98f) {
            pdfView.zoomWithAnimation(e.getX(), e.getY(), mid);
        } else if (!doubleTapMax) {
            pdfView.zoomWithAnimation(e.getX(), e.getY(), 1f);
        } else if (z < max * 0.98f) {
            pdfView.zoomWithAnimation(e.getX(), e.getY(), max);
        } else {
            pdfView.zoomWithAnimation(e.getX(), e.getY(), 1f);
        }
        return true;
    }

    @Override
    public boolean onDoubleTapEvent(MotionEvent e) {
        return false;
    }

    @Override
    public boolean onDown(MotionEvent e) {
        animationManager.stopFling();
        return true;
    }

    @Override
    public void onShowPress(MotionEvent e) {

    }

    @Override
    public boolean onSingleTapUp(MotionEvent e) {
        return false;
    }

    @Override
    public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
        /* v1.16: a running jump/zoom animation used to fight the user's
           finger for up to 400 ms (the animator kept overriding the drag
           offsets every frame). Cancelling it here makes the drag win
           immediately; the verify-and-retry in ReaderActivity still lands
           the requested page when no touch is involved. */
        animationManager.stopAll();
        scrolling = true;
        /* v1.37: ورق‌زدن — no continuous strip scrolling at fit zoom;
           the drag rubber-bands instead and flips on release/fling.
           v1.38 FIX: the old gate was !pdfView.isZooming() — but the
           activity sets minZoom to 0.5 while the fit baseline is 1.0, so
           isZooming() (zoom != minZoom) was ALWAYS true at 100% and the
           flip mode silently behaved like plain continuous scrolling —
           it never flipped. The gate is now the real "zoomed in" test.
           In addition, when a page overflows the screen on the
           non-flipping axis (tall page), that axis pans normally so the
           page bottom/top stays reachable. */
        if (pageFlip && pdfView.getZoom() <= 1.05f) {
            flipDragging = true;
            float fX, fY;
            if (pdfView.isSwipeVertical()) {
                flipAccum += distanceY;
                fY = -distanceY * 0.22f;
                fX = pdfView.pageOverflowsX() ? -distanceX : -distanceX * 0.22f;
            } else {
                flipAccum += distanceX;
                fX = -distanceX * 0.22f;
                fY = pdfView.pageOverflowsY() ? -distanceY : -distanceY * 0.22f;
            }
            pdfView.moveRelativeTo(fX, fY);
            pdfView.loadPageByOffset();
            return true;
        }
        if (pdfView.isZooming() || pdfView.isSwipeEnabled()) {
            pdfView.moveRelativeTo(-distanceX, -distanceY);
        }
        if (!scaling || pdfView.doRenderDuringScale()) {
            pdfView.loadPageByOffset();
        }
        return true;
    }

    private void onScrollEnd(MotionEvent event) {
        /* v1.37: ورق‌زدن release — flip a page when the rubber-band drag
           crossed a quarter of the screen, otherwise spring back.
           v1.38: only counts when the flip gesture was actually armed
           (fit zoom); when zoomed in, the drag is a normal pan. */
        if (pageFlip && flipDragging) {
            flipDragging = false;
            float thr = (pdfView.isSwipeVertical() ? pdfView.getHeight() : pdfView.getWidth()) * 0.25f;
            float mag = flipAccum;
            flipAccum = 0;
            /* v1.47: کتاب RTL — کشیدن به راست = صفحهٔ بعد */
            int dir = (mag > 0 ? 1 : -1) * (pdfView.isBookRtl() ? -1 : 1);
            int target = pdfView.getCurrentPage();
            /* واحد ورق‌خوردن در حالت کتاب = یک اسپرد کامل */
            if (pdfView.isBookRtl() && Math.abs(mag) > thr) {
                int unit = Math.max(0, Math.min(pdfView.getPageCount() - 1, target + dir * 2));
                flipToPage(unit);
            } else {
                flipToPage(Math.abs(mag) > thr ? target + dir : target);
            }
            hideHandle();
            return;
        }
        pdfView.loadPages();
        hideHandle();
        if (!animationManager.isFlinging()) {
            pdfView.performPageSnap();
        }
    }

    private void flipToPage(int page) {
        try {
            page = Math.max(0, Math.min(pdfView.getPageCount() - 1, page));
            SnapEdge edge = pdfView.findSnapEdge(page);
            if (edge == SnapEdge.NONE) edge = SnapEdge.CENTER;
            float offset = pdfView.snapOffsetForPage(page, edge);
            animationManager.startPageFlingAnimation(-offset);
            pdfView.loadPages();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onLongPress(MotionEvent e) {
        pdfView.callbacks.callOnLongPress(e);
    }

    @Override
    public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
        if (!pdfView.isSwipeEnabled()) {
            return false;
        }
        if (pdfView.isPageFlingEnabled()) {
            if (pdfView.pageFillsScreen()) {
                onBoundedFling(velocityX, velocityY);
            } else {
                startPageFling(e1, e2, velocityX, velocityY);
            }
            return true;
        }

        int xOffset = (int) pdfView.getCurrentXOffset();
        int yOffset = (int) pdfView.getCurrentYOffset();

        float minX, minY;
        PdfFile pdfFile = pdfView.pdfFile;
        if (pdfView.isSwipeVertical()) {
            minX = -(pdfView.toCurrentScale(pdfFile.getMaxPageWidth()) - pdfView.getWidth());
            minY = -(pdfFile.getDocLen(pdfView.getZoom()) - pdfView.getHeight());
        } else {
            minX = -(pdfFile.getDocLen(pdfView.getZoom()) - pdfView.getWidth());
            minY = -(pdfView.toCurrentScale(pdfFile.getMaxPageHeight()) - pdfView.getHeight());
        }

        animationManager.startFlingAnimation(xOffset, yOffset, (int) (velocityX), (int) (velocityY),
                (int) minX, 0, (int) minY, 0);
        return true;
    }

    private void onBoundedFling(float velocityX, float velocityY) {
        int xOffset = (int) pdfView.getCurrentXOffset();
        int yOffset = (int) pdfView.getCurrentYOffset();

        PdfFile pdfFile = pdfView.pdfFile;

        /* v1.47: در حالت کتاب مرز پرتاب = کل اسپرد (هر دو صفحه) */
        int unitPage = pdfView.isBookRtl()
                ? pdfFile.getSnapUnitStartPage(pdfView.getCurrentPage()) : pdfView.getCurrentPage();
        float pageStart = -pdfFile.getPageOffset(unitPage, pdfView.getZoom());
        float pageEnd = pageStart - pdfFile.getSnapUnitLength(pdfView.getCurrentPage(), pdfView.getZoom());
        float minX, minY, maxX, maxY;
        if (pdfView.isSwipeVertical()) {
            minX = -(pdfView.toCurrentScale(pdfFile.getMaxPageWidth()) - pdfView.getWidth());
            minY = pageEnd + pdfView.getHeight();
            maxX = 0;
            maxY = pageStart;
        } else {
            minX = pageEnd + pdfView.getWidth();
            minY = -(pdfView.toCurrentScale(pdfFile.getMaxPageHeight()) - pdfView.getHeight());
            maxX = pageStart;
            maxY = 0;
        }

        animationManager.startFlingAnimation(xOffset, yOffset, (int) (velocityX), (int) (velocityY),
                (int) minX, (int) maxX, (int) minY, (int) maxY);
    }

    @Override
    public boolean onScale(ScaleGestureDetector detector) {
        /* v1.37: قفل زوم — pinch does nothing while locked */
        if (zoomLocked) {
            return true;
        }
        float dr = detector.getScaleFactor();
        float wantedZoom = pdfView.getZoom() * dr;
        float minZoom = Math.min(MINIMUM_ZOOM, pdfView.getMinZoom());
        float maxZoom = Math.min(MAXIMUM_ZOOM, pdfView.getMaxZoom());
        if (wantedZoom < minZoom) {
            dr = minZoom / pdfView.getZoom();
        } else if (wantedZoom > maxZoom) {
            dr = maxZoom / pdfView.getZoom();
        }
        float before = pdfView.getZoom();
        pdfView.zoomCenteredRelativeTo(dr, new PointF(detector.getFocusX(), detector.getFocusY()));
        /* v1.37: subtle tick exactly when a pinch CROSSES 100% — haptic +
           tiny pill, at most once per 350 ms so it never buzzes annoyingly. */
        float after = pdfView.getZoom();
        boolean crossed = (before < 1f && after >= 1f) || (before > 1f && after <= 1f);
        if (crossed && zoomAtHundred != null) {
            long now = android.os.SystemClock.uptimeMillis();
            if (now - lastHundredAt > 350) {
                lastHundredAt = now;
                try { zoomAtHundred.run(); } catch (Throwable ignored) {}
            }
        }
        return true;
    }

    @Override
    public boolean onScaleBegin(ScaleGestureDetector detector) {
        scaling = true;
        return true;
    }

    @Override
    public void onScaleEnd(ScaleGestureDetector detector) {
        pdfView.loadPages();
        hideHandle();
        scaling = false;
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        if (!enabled) {
            return false;
        }

        boolean retVal = scaleGestureDetector.onTouchEvent(event);
        retVal = gestureDetector.onTouchEvent(event) || retVal;

        if (event.getAction() == MotionEvent.ACTION_UP) {
            if (scrolling) {
                scrolling = false;
                onScrollEnd(event);
            }
        }
        return retVal;
    }

    private void hideHandle() {
        ScrollHandle scrollHandle = pdfView.getScrollHandle();
        if (scrollHandle != null && scrollHandle.shown()) {
            scrollHandle.hideDelayed();
        }
    }

    private boolean checkDoPageFling(float velocityX, float velocityY) {
        float absX = Math.abs(velocityX);
        float absY = Math.abs(velocityY);
        return pdfView.isSwipeVertical() ? absY > absX : absX > absY;
    }
}
