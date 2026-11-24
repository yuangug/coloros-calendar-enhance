package com.lcodexs.coursealarm;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.TextView;

/** A real accessible title action above the course surfaces. The touch area spans the title row. */
final class WeekFinishButton extends FrameLayout {
    static final String LABEL = "结束本周课程";
    WeekFinishButton(Context context, Runnable click) {
        super(context);
        setClipChildren(false);
        setFocusable(true);
        setContentDescription(LABEL);
        setOnClickListener(v -> click.run());
        addView(new Capsule(context), new LayoutParams(-2, -2, Gravity.CENTER));
    }

    @Override public CharSequence getAccessibilityClassName() { return android.widget.Button.class.getName(); }

    private static final class Capsule extends TextView {
        NativeCardMaterial material;
        Capsule(Context context) {
            super(context);
            setText(LABEL);
            setSingleLine();
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            android.content.res.TypedArray colors = context.obtainStyledAttributes(new int[]{android.R.attr.textColorPrimary});
            try {
                ColorStateList ink = colors.getColorStateList(0);
                if (ink != null) setTextColor(ink);
            } finally { colors.recycle(); }
            setGravity(Gravity.CENTER);
            setMinHeight(dp(32));
            setPadding(dp(14), dp(2), dp(14), dp(2));
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            setDuplicateParentStateEnabled(true);
            setClipToOutline(true);
            setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View v, Outline outline) {
                    outline.setRoundRect(0, 0, getWidth(), getHeight(), getHeight() / 2f);
                }
            });
            setForeground(new RippleDrawable(ColorStateList.valueOf(NingGuang.night(context)
                    ? 0x30FFFFFF : 0x18000000), null, null));
        }
        private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            try {
                material = new NativeCardMaterial(this);
                material.attach();
                if (getHeight() > 0) material.update(getHeight() / 2f);
            } catch (Exception unavailable) { fallback(); }
        }
        @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
            super.onSizeChanged(w, h, oldW, oldH);
            invalidateOutline();
            if (material != null && h > 0) try { material.update(h / 2f); }
            catch (Exception unavailable) { fallback(); }
        }
        private void fallback() {
            if (material != null) { material.release(); material = null; }
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(NingGuang.night(getContext()) ? 0xF0303030 : 0xF0F8F8F8);
            bg.setCornerRadius(dp(26));
            setBackground(bg);
        }
        @Override protected void onDraw(Canvas canvas) {
            if (material != null) material.drawEdge(canvas);
            super.onDraw(canvas);
        }
        @Override protected void onDetachedFromWindow() {
            if (material != null) { material.release(); material = null; }
            super.onDetachedFromWindow();
        }
    }
}
