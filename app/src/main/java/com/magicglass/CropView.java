package com.magicglass;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/**
 * 0.5.0-p189: 圆形图标交互裁剪视图。
 *
 * <p>用户导入任意照片后，可单指拖动决定保留哪一块、双指捏合（或外部滑块）
 * 决定裁切范围大小，圆形取景框不变形、边缘不露白。点“确定”时把当前
 * 变换烘焙成一张 {@code size x size} 的圆形 PNG，交给调用方保存。</p>
 *
 * <p>坐标约定：屏幕坐标 = 位图坐标 * scale + (tx, ty)。全程只用
 * Canvas 的 translate/scale，不构造 Matrix，避免组合顺序出错。</p>
 */
public final class CropView extends View {

    private Bitmap src;
    private float scale = 1f;
    private float tx, ty;
    private float minScale = 1f;
    private float maxScale = 4f;

    private final Paint bmpPaint = new Paint(Paint.ANTI_ALIAS_FLAG
            | Paint.FILTER_BITMAP_FLAG);
    private final Paint dimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path circle = new Path();
    private final ScaleGestureDetector scaleDetector;

    private float lastX, lastY;
    private boolean dragging;

    public CropView(Context c) {
        super(c);
        dimPaint.setColor(0xB3000000);            // 圆外压暗
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(2f);
        ringPaint.setColor(0x66FFFFFF);
        scaleDetector = new ScaleGestureDetector(c,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override public boolean onScale(ScaleGestureDetector d) {
                        zoomBy(d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                        return true;
                    }
                });
    }

    /** 设置源图并重置为“铺满圆形”的初始状态。 */
    public void setBitmap(Bitmap b) {
        src = b;
        resetTransform();
        invalidate();
    }

    public void resetTransform() {
        if (src == null) return;
        float r = radius();
        minScale = Math.max(2f * r / src.getWidth(), 2f * r / src.getHeight());
        maxScale = minScale * 4f;
        scale = minScale;
        tx = getWidth() / 2f - scale * src.getWidth() / 2f;
        ty = getHeight() / 2f - scale * src.getHeight() / 2f;
        clamp();
    }

    /** 外部滑块调用：按倍数缩放（相对 minScale）。 */
    public void setZoomFactor(float factor) {
        if (src == null) return;
        float target = Math.max(minScale, Math.min(maxScale, minScale * factor));
        float f = target / scale;
        zoomBy(f, getWidth() / 2f, getHeight() / 2f);
        invalidate();
    }

    private float radius() {
        return Math.max(1f, Math.min(getWidth(), getHeight()) / 2f - 4f);
    }

    private void zoomBy(float factor, float fx, float fy) {
        if (src == null) return;
        float ns = Math.max(minScale, Math.min(maxScale, scale * factor));
        float f = ns / scale;
        if (f == 1f) return;
        tx = fx - (fx - tx) * f;
        ty = fy - (fy - ty) * f;
        scale = ns;
        clamp();
        invalidate();
    }

    private void clamp() {
        if (src == null) return;
        float r = radius();
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float minTx = cx + r - scale * src.getWidth();
        float maxTx = cx - r;
        float minTy = cy + r - scale * src.getHeight();
        float maxTy = cy - r;
        tx = Math.max(minTx, Math.min(maxTx, tx));
        ty = Math.max(minTy, Math.min(maxTy, ty));
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (src == null || w == 0 || h == 0) return;
        float r = radius();
        float cx = w / 2f, cy = h / 2f;

        circle.reset();
        circle.addCircle(cx, cy, r, Path.Direction.CW);

        c.drawRect(0, 0, w, h, dimPaint);        // 圆外压暗
        int save = c.save();
        c.clipPath(circle);
        c.translate(tx, ty);
        c.scale(scale, scale);
        c.drawBitmap(src, 0, 0, bmpPaint);
        c.restoreToCount(save);

        c.drawCircle(cx, cy, r, ringPaint);      // 取景圆环
    }

    public boolean onTouchEvent(MotionEvent e) {
        if (src == null) return false;
        scaleDetector.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = e.getX();
                lastY = e.getY();
                dragging = true;
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (dragging && !scaleDetector.isInProgress()
                        && e.getPointerCount() == 1) {
                    tx += e.getX() - lastX;
                    ty += e.getY() - lastY;
                    lastX = e.getX();
                    lastY = e.getY();
                    clamp();
                    invalidate();
                }
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                dragging = false;                // 交给捏合
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                break;
            default:
                break;
        }
        return true;
    }

    /**
     * 把当前取景烘焙成 {@code size x size} 的圆形 PNG 位图（四角透明）。
     * 失败返回 null；成功返回新位图，调用方负责回收。
     */
    public Bitmap exportRound(int size) {
        if (src == null || size <= 0) return null;
        try {
            float r = radius();
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            if (r <= 0) return null;
            Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            float k = size / (2f * r);
            Path p = new Path();
            p.addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW);
            int save = c.save();
            c.clipPath(p);
            c.scale(k, k);
            c.translate(tx - (cx - r), ty - (cy - r));
            c.scale(scale, scale);
            c.drawBitmap(src, 0, 0, bmpPaint);
            c.restoreToCount(save);
            return out;
        } catch (Throwable t) {
            return null;
        }
    }
}
