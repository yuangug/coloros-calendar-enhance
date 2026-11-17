package com.lcodexs.coursealarm;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.ImageView;

/** One window coordinate system for the status area, page and glass sampling. */
final class ThemeWindowBackground extends Drawable {
    private final View target;
    private final boolean includeImageAndScrim;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private int alpha = 255;

    ThemeWindowBackground(View target, boolean includeImageAndScrim) {
        this.target = target;
        this.includeImageAndScrim = includeImageAndScrim;
    }

    static View anchor(View view) {
        int id = view.getResources().getIdentifier("ll_main", "id", view.getContext().getPackageName());
        View main = id == 0 ? null : view.getRootView().findViewById(id);
        return main != null ? main : view.getRootView();
    }

    static Matrix windowToLocal(View window, View view) {
        Matrix matrix = new Matrix();
        window.transformMatrixToGlobal(matrix);
        view.transformMatrixToLocal(matrix);
        return matrix;
    }

    static Matrix imageToWindow(int imageWidth, int imageHeight, View window) {
        float scale = Math.max(window.getWidth() / (float)imageWidth, window.getHeight() / (float)imageHeight);
        Matrix matrix = new Matrix();
        matrix.setScale(scale, scale);
        matrix.postTranslate((window.getWidth() - imageWidth * scale) / 2f,
                (window.getHeight() - imageHeight * scale) / 2f);
        return matrix;
    }

    @Override public void draw(Canvas canvas) {
        Context context = target.getContext();
        boolean night = NingGuang.night(context);
        int save = alpha == 255 ? canvas.save() : canvas.saveLayerAlpha(new RectF(getBounds()), alpha);
        try {
            canvas.drawColor(night ? 0xFF101216 : 0xFFF7F8FA);
            View window = anchor(target);
            if (window.getWidth() <= 0 || window.getHeight() <= 0) return;
            canvas.concat(windowToLocal(window, target));
            float width = window.getWidth(), height = window.getHeight();
            int type = ThemeStore.type(context);
            paint.setShader(null);
            if (type == ThemeStore.TYPE_SOLID) {
                int index = Math.max(0, Math.min(ThemeStore.solidIndex(context), ThemeStore.SOLIDS.length - 1));
                paint.setColor(ThemeStore.SOLIDS[index]);
                canvas.drawRect(0, 0, width, height, paint);
            } else if (type == ThemeStore.TYPE_GRADIENT) {
                int index = Math.max(0, Math.min(ThemeStore.gradientIndex(context), ThemeStore.GRADIENTS.length - 1));
                paint.setColor(Color.WHITE);
                paint.setShader(new LinearGradient(0, 0, 0, height, ThemeStore.GRADIENTS[index], null, Shader.TileMode.CLAMP));
                canvas.drawRect(0, 0, width, height, paint);
                paint.setShader(null);
            } else if (type == ThemeStore.TYPE_IMAGE && includeImageAndScrim) {
                View root = HostTablePage.rootView();
                View image = root != null && root.getRootView() == target.getRootView()
                        ? root.findViewWithTag(HostTablePage.TAG_BG) : null;
                if (image instanceof ImageView) {
                    // Reuse the actual page drawable, including its current animation
                    // frame. Do not decode or stretch a second status-bar image.
                    Drawable drawable = ((ImageView)image).getDrawable();
                    if (drawable != null && drawable.getBounds().width() > 0 && drawable.getBounds().height() > 0) {
                        int imageSave = canvas.saveLayerAlpha(0, 0, width, height,
                                Math.round(255f * Math.max(0f, Math.min(1f, image.getAlpha()))));
                        canvas.concat(imageToWindow(drawable.getBounds().width(), drawable.getBounds().height(), window));
                        canvas.translate(-drawable.getBounds().left, -drawable.getBounds().top);
                        drawable.draw(canvas);
                        canvas.restoreToCount(imageSave);
                    }
                }
            }
            if (includeImageAndScrim && type != ThemeStore.TYPE_NONE) {
                int scrim = ThemeStore.scrim(context);
                paint.setColor(Color.argb(scrim, night ? 0 : 255, night ? 0 : 255, night ? 0 : 255));
                canvas.drawRect(0, 0, width, height, paint);
            }
        } finally { canvas.restoreToCount(save); }
    }

    @Override public void setAlpha(int alpha) { this.alpha = alpha; invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }

    static class Image extends ImageView implements ViewTreeObserver.OnPreDrawListener {
        Image(Context context) { super(context); setScaleType(ScaleType.MATRIX); }

        private void syncMatrix() {
            Drawable drawable = getDrawable();
            View window = anchor(this);
            if (drawable == null || drawable.getIntrinsicWidth() <= 0 || drawable.getIntrinsicHeight() <= 0
                    || window.getWidth() <= 0 || window.getHeight() <= 0 || !isAttachedToWindow()) return;
            Matrix matrix = windowToLocal(window, this);
            matrix.preConcat(imageToWindow(drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight(), window));
            if (!matrix.equals(getImageMatrix())) {
                setImageMatrix(matrix);
                window.invalidate();
            }
        }

        @Override public boolean onPreDraw() { if (isShown()) syncMatrix(); return true; }
        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            getViewTreeObserver().addOnPreDrawListener(this);
            syncMatrix();
        }
        @Override protected void onDetachedFromWindow() {
            if (getViewTreeObserver().isAlive()) getViewTreeObserver().removeOnPreDrawListener(this);
            super.onDetachedFromWindow();
        }
        @Override public void invalidateDrawable(Drawable drawable) {
            super.invalidateDrawable(drawable);
            if (isAttachedToWindow() && BarMode.active) {
                View window = anchor(this);
                if (window.getBackground() instanceof ThemeWindowBackground) window.invalidate();
                CourseSurfaceRenderer.invalidateHeader(HostTablePage.rootView());
            }
        }
    }
}
