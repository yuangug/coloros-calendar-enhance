package com.lcodexs.coursealarm;

import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** WakeUp course reminder template 1.0.4: trailing/multi-texts → E16/capsule (size34).
 * Only create a presentation component. The action still belongs to the verified Calendar occurrence. */
final class CourseShadeCapsule extends FrameLayout {
    private static final String SDK = "com.oplus.ule.lite.";
    private static final String WIDGET = SDK + "customview.modularview.widget.";
    private final View surface;
    private final Object model;
    private final Method value, colorAttr, bind;
    private String last;
    private int lastInk;

    CourseShadeCapsule(View root, View slot) throws ReflectiveOperationException {
        super(slot.getContext());
        if (!slot.getClass().getName().equals(SDK + "customview.switcher.CrossFadeViewSwitcher"))
            throw new NoSuchMethodException("E16 slot");
        View icon = byType(root, WIDGET + "LevelDView");
        if (icon == null) throw new NoSuchMethodException("D18 renderer");
        ClassLoader cl = slot.getClass().getClassLoader();
        Class<?> e = Class.forName(WIDGET + "LevelEView", false, cl);
        Class<?> component = Class.forName(SDK + "components.p1", false, cl);
        Class<?> button = Class.forName(SDK + "components.b", false, cl);
        Class<?> engine = Class.forName(SDK + "k2", false, cl);
        Class<?> images = Class.forName("a8.o", false, cl), lottie = Class.forName("a8.w", false, cl);
        Class<?> animation = Class.forName("v6.x1", false, cl);
        value = component.getMethod("O", String.class);
        colorAttr = button.getDeclaredMethod("M", String.class, String.class);
        colorAttr.setAccessible(true);
        bind = e.getMethod("B0", component, boolean.class, ViewGroup.class, boolean.class,
                Bundle.class, Class.forName("kotlin.jvm.functions.Function1", false, cl));
        model = button.getConstructor(int.class, String.class, boolean.class)
                .newInstance(View.generateViewId(), "button", true);
        component.getMethod("I", String.class).invoke(model, "E16");
        component.getMethod("G", String.class).invoke(model, "size34");
        surface = (View) e.getConstructor(Context.class, AttributeSet.class).newInstance(slot.getContext(), null);
        setClipChildren(false); setClipToPadding(false);
        addView(surface, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        Object rendering = fieldOfType(icon, engine);
        // A D18 that has never animated has no animation object. The modular card itself
        // owns the active controller shared by WakeUp's E16 and the other template elements.
        Object anim = null;
        for (View p = slot; p != null; p = p.getParent() instanceof View ? (View) p.getParent() : null) {
            if (!p.getClass().getName().equals("f7.d4")) continue;
            Method get = p.getClass().getDeclaredMethod("getRefreshAnimation");
            if (get.getReturnType() != animation) break;
            get.setAccessible(true); anim = get.invoke(p); break;
        }
        if (anim == null) {
            Object card = engine.getMethod("r").invoke(rendering);
            long instance = (long)card.getClass().getMethod("getInstanceId").invoke(card);
            anim = animation.getConstructor(Context.class, long.class).newInstance(slot.getContext(), instance);
        }
        e.getMethod("I", engine, images, lottie, animation).invoke(surface,
                rendering, engine.getMethod("m").invoke(rendering), engine.getMethod("n").invoke(rendering), anim);
        setFocusable(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
    }

    void show(String label, int nativeTitle) throws ReflectiveOperationException {
        int ink = Color.luminance(nativeTitle) > .45 ? 0xe5ffffff : 0xe5000000;
        if (label.equals(last) && ink == lastInk) return;
        value.invoke(model, label);
        colorAttr.invoke(model, "color", ink == 0xe5ffffff ? "#E5FFFFFF" : "#E5000000");
        bind.invoke(surface, model, false, null, true, null, null);
        surface.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        surface.setFocusable(false);
        setContentDescription(label);
        last = label; lastInk = ink;
    }

    // Host rendering, material policy and layout are untouched; the single accessible click
    // must dispatch our PendingIntent, never the action of another app's template.
    @Override public boolean onInterceptTouchEvent(MotionEvent event) { return true; }
    @Override protected void dispatchSetPressed(boolean pressed) { if (surface != null) surface.setPressed(pressed); }
    @Override public CharSequence getAccessibilityClassName() { return android.widget.Button.class.getName(); }

    private static Object fieldOfType(View source, Class<?> type) throws ReflectiveOperationException {
        for (Field f : source.getClass().getDeclaredFields()) if (f.getType() == type) {
            f.setAccessible(true);
            Object result = f.get(source);
            if (result != null) return result;
        }
        throw new NoSuchFieldException(type.getName());
    }
    private static View byType(View root, String name) {
        if (name.equals(root.getClass().getName())) return root;
        if (root instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); ++i) {
            View v = byType(group.getChildAt(i), name);
            if (v != null) return v;
        }
        return null;
    }
}
