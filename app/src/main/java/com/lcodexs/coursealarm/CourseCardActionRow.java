package com.lcodexs.coursealarm;

import android.graphics.Rect;
import android.view.Gravity;
import android.view.TouchDelegate;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

/** The signed course template's trailing section contains eventDesc2 (the countdown).
 * Keep that entire native subtree, including its IDs and animation bindings, in the status row. */
final class CourseCardActionRow extends LinearLayout {
    private final LinearLayout section;
    private final View status;
    private final View action;
    private final LinearLayout.LayoutParams original;
    private final Rect hit = new Rect();
    private TouchDelegate delegate;

    CourseCardActionRow(LinearLayout section, View status, View action) {
        super(section.getContext());
        this.section = section; this.status = status; this.action = action;
        original = (LayoutParams) status.getLayoutParams();
        setOrientation(HORIZONTAL);
        setBaselineAligned(false);
        setGravity(Gravity.CENTER_VERTICAL);
        setClipChildren(false);
        setClipToPadding(false);
    }

    void mount() {
        int index = section.indexOfChild(status);
        if (index < 0) throw new IllegalStateException("课程状态行已更新");
        LayoutParams outer = new LayoutParams(original);
        outer.width = LayoutParams.MATCH_PARENT;
        outer.height = LayoutParams.WRAP_CONTENT;
        section.removeViewAt(index);
        try {
            addView(status, new LayoutParams(0, original.height > 0 ? original.height : LayoutParams.WRAP_CONTENT, 1));
            LayoutParams end = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            end.setMarginStart(dp(8));
            addView(action, end);
            section.addView(this, index, outer);
        } catch (RuntimeException error) {
            removeAllViews();
            if (status.getParent() == null) section.addView(status, Math.min(index, section.getChildCount()), original);
            throw error;
        }
    }

    boolean containsStatus(View status) { return this.status == status && status.getParent() == this && getParent() == section; }

    void release() {
        clearDelegate();
        int index = section.indexOfChild(this);
        boolean restore = status.getParent() == this;
        removeAllViews();
        if (index >= 0) {
            section.removeViewAt(index);
            if (restore) section.addView(status, index, original);
        }
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (!action.isClickable()) { clearDelegate(); return; }
        if (section.getTouchDelegate() != null && section.getTouchDelegate() != delegate) return;
        Rect bounds = new Rect();
        action.getDrawingRect(bounds);
        section.offsetDescendantRectToMyCoords(action, bounds);
        int pad = Math.max(0, (dp(48) - bounds.height()) / 2);
        bounds.inset(0, -pad);
        if (!bounds.equals(hit) || delegate == null) {
            hit.set(bounds);
            delegate = new TouchDelegate(bounds, action);
            section.setTouchDelegate(delegate);
        }
    }
    private void clearDelegate() {
        if (delegate != null && section.getTouchDelegate() == delegate) section.setTouchDelegate(null);
        delegate = null; hit.setEmpty();
    }
    @Override protected void onDetachedFromWindow() { clearDelegate(); super.onDetachedFromWindow(); }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
}
