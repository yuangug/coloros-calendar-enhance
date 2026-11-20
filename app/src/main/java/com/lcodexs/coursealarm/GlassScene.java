package com.lcodexs.coursealarm;

import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.AnimatedImageDrawable;
import android.widget.ImageView;
import android.view.*;
import java.lang.ref.WeakReference;

/** Background-only display list. Never records consumer cards or text. */
final class GlassScene {
    private final WeakReference<ViewGroup> root;
    private final ThemeWindowBackground windowBackground;
    final RenderNode node = new RenderNode("course-backdrop-source");
    private long frame = Long.MIN_VALUE;
    private String key;
    int generation;
    GlassScene(ViewGroup root) { this(root, false); }
    GlassScene(ViewGroup root, boolean fullWindow) {
        this.root = new WeakReference<>(root);
        windowBackground = fullWindow ? new ThemeWindowBackground(root, true) : null;
        node.setClipToBounds(false);
    }
    boolean animated() {
        ViewGroup view = root.get();
        if (view == null || !view.isShown()) return false;
        View background = view.findViewWithTag(HostTablePage.TAG_BG);
        if (!(background instanceof ImageView) || !background.isShown()) return false;
        Drawable drawable = ((ImageView)background).getDrawable();
        return drawable instanceof AnimatedImageDrawable && ((AnimatedImageDrawable)drawable).isRunning();
    }
    void capture(long frameId) {
        ViewGroup view = root.get();
        if (view == null || view.getWidth() <= 0 || view.getHeight() <= 0) return;
        String next = ThemeStore.type(view.getContext()) + ":" + ThemeStore.solidIndex(view.getContext()) + ":" + ThemeStore.gradientIndex(view.getContext())
                + ":" + ThemeStore.scrim(view.getContext()) + ":" + ThemeStore.imageAlpha(view.getContext()) + ":" + view.getResources().getConfiguration().hashCode()
                + ":" + view.getWidth() + ":" + view.getHeight() + ":" + System.identityHashCode(view.findViewWithTag(HostTablePage.TAG_BG));
        View window = ThemeWindowBackground.anchor(view);
        next += ":" + window.getWidth() + ":" + window.getHeight() + ":" + ThemeWindowBackground.windowToLocal(window, view);
        View image = view.findViewWithTag(HostTablePage.TAG_BG);
        if (image instanceof ImageView) next += ":" + ((ImageView)image).getImageMatrix();
        if (node.hasDisplayList() && next.equals(key) && (!animated() || frame == frameId)) return;
        key = next; frame = frameId;
        node.setPosition(0, 0, view.getWidth(), view.getHeight());
        RecordingCanvas canvas = node.beginRecording();
        try {
            if (windowBackground != null) {
                // Dialog sheets can extend below the timetable's own bounds. Draw
                // the same window-anchored theme, never any window foreground.
                windowBackground.setBounds(0, 0, view.getWidth(), view.getHeight());
                windowBackground.draw(canvas);
            } else {
            Drawable background = view.getBackground();
            if (background != null) background.draw(canvas);
            else canvas.drawColor(NingGuang.night(view.getContext()) ? 0xFF141414 : 0xFFF4F5F7);
            for (String tag : new String[]{HostTablePage.TAG_BG, HostTablePage.TAG_SCRIM}) {
                View child = view.findViewWithTag(tag);
                if (child == null || child.getVisibility() != View.VISIBLE || child.getAlpha() <= 0) continue;
                Matrix matrix = new Matrix(); child.transformMatrixToGlobal(matrix); view.transformMatrixToLocal(matrix);
                int save = canvas.save(); canvas.concat(matrix);
                int alpha = canvas.saveLayerAlpha(0, 0, child.getWidth(), child.getHeight(), Math.round(child.getAlpha() * 255));
                child.draw(canvas); canvas.restoreToCount(alpha); canvas.restoreToCount(save);
            }
            }
        } finally { node.endRecording(); }
        generation++;
    }
    void release() { node.discardDisplayList(); }
}
