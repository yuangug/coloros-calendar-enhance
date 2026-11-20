package com.lcodexs.coursealarm;

import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.TextView;
import android.widget.FrameLayout;

import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/** The host detail sheet keeps its layout and input; only its background is replaced. */
final class CourseDetailMaterial {
    private static final String INFO = "com.coloros.calendar.widget.bottomsheetdialog.CourseListInfoFragment";
    private static final Map<Object, Session> SESSIONS = new IdentityHashMap<>();
    private static boolean failureLogged;

    private CourseDetailMaterial() { }

    static void install(XposedModule module, ClassLoader host) {
        Class<?> info = Ref.cls(host, INFO);
        Method start = Ref.methodOf(info, "onStart");
        if (start == null) return;
        module.hook(start).setId("course_detail_material")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object result = chain.proceed();
            Object fragment = chain.getThisObject();
            if (!INFO.equals(fragment.getClass().getName())) return result;
            Object view = Ref.callName(fragment, "getView");
            if (view instanceof View) attach(fragment, (View) view);
            return result;
        });
        Logx.i(CourseAlarmEntry.TAG, "课程详情材质已接入原生面板");
    }

    private static void attach(Object fragment, View content) {
        Session existing = SESSIONS.get(fragment);
        if (existing != null) { existing.frame.invalidate(); return; }
        ViewGroup frame = findFrame(content);
        if (frame == null) {
            // A restored fragment can start before the dialog attaches its content.
            content.post(() -> {
                ViewGroup attached = findFrame(content);
                if (attached != null && content.isAttachedToWindow() && !SESSIONS.containsKey(fragment))
                    create(fragment, content, attached);
            });
        } else create(fragment, content, frame);
    }

    private static void create(Object fragment, View content, ViewGroup frame) {
        try {
            Session session = new Session(fragment, content, frame);
            SESSIONS.put(fragment, session);
            session.start();
        } catch (Throwable error) { failure(error); }
    }

    private static ViewGroup findFrame(View content) {
        for (View view = content; view != null; ) {
            if (view instanceof ViewGroup && view.getClass().getName().equals(
                    "com.coui.appcompat.panel.COUIPanelPercentFrameLayout")) return (ViewGroup) view;
            ViewParent parent = view.getParent();
            view = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    private static Dialog findDialog(Object fragment) {
        for (Object current = fragment; current != null; current = Ref.callName(current, "getParentFragment")) {
            Method get = Ref.methodOf(current.getClass(), "getDialog");
            Object dialog = get == null ? null : Ref.call(current, get);
            if (dialog instanceof Dialog) return (Dialog) dialog;
        }
        return null;
    }

    /** View matrices are window-local; explicitly bridge the two window origins. */
    static Matrix betweenWindows(View from, View to) {
        Matrix source = screenMatrix(from), target = screenMatrix(to), inverse = new Matrix();
        if (!target.invert(inverse)) throw new IllegalStateException("详情背景坐标不可逆");
        Matrix result = new Matrix();
        result.setConcat(inverse, source);
        return result;
    }

    private static Matrix screenMatrix(View view) {
        Matrix result = new Matrix();
        view.transformMatrixToGlobal(result);
        View root = view.getRootView();
        int[] screen = new int[2], window = new int[2];
        root.getLocationOnScreen(screen);
        root.getLocationInWindow(window);
        result.postTranslate(screen[0] - window[0], screen[1] - window[1]);
        return result;
    }

    private static void replaceBackground(View view, Drawable background) {
        // setBackground can adopt Drawable padding. Never change the native layout.
        int left = view.getPaddingLeft(), top = view.getPaddingTop();
        int right = view.getPaddingRight(), bottom = view.getPaddingBottom();
        view.setBackground(background);
        if (left != view.getPaddingLeft() || top != view.getPaddingTop()
                || right != view.getPaddingRight() || bottom != view.getPaddingBottom())
            view.setPadding(left, top, right, bottom);
    }

    private static float number(Object value, float fallback) {
        return value instanceof Number ? ((Number) value).floatValue() : fallback;
    }

    private static void failure(Throwable error) {
        if (failureLogged) return;
        failureLogged = true;
        Logx.e(CourseAlarmEntry.TAG, "课程详情材质不可用，保留原生普通背景", error);
    }

    private static final class Session implements ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
        final Object fragment;
        final View content;
        final ViewGroup frame;
        final Dialog dialog;
        final ViewGroup recycler;
        final int contentId, rowId, titleId, editId;
        final Map<View, Drawable> backgrounds = new IdentityHashMap<>();
        final Map<View, Drawable> appliedBackgrounds = new IdentityHashMap<>();
        final Map<TextView, ColorStateList> textColors = new IdentityHashMap<>();
        final Map<TextView, Integer> appliedTextColors = new IdentityHashMap<>();
        final MaterialBackground background = new MaterialBackground(this);
        final boolean originalBackgroundBlurFlag;
        final Matrix previousMatrix = new Matrix();
        final Path shape = new Path();
        final ViewTreeObserver observer;
        Drawable originalFrameBackground, originalShadow;
        NativeCardMaterial material;
        ThemeLayer themeLayer;
        NativeLayer nativeLayer;
        LiquidCardEffect liquid;
        GlassScene scene;
        ViewGroup source;
        View sourcePage;
        Object blurBuilder, animationLevel;
        Method setBlur;
        boolean originalBlur, blurSuspended, shadowSuspended, released, active, failed;
        int style = ThemeStore.STYLE_CLASSIC, requestedStyle = -1, generation = -1;
        int width, height, configuration, courseColor;
        int neutralInk;
        float radius, bottomRadius;
        boolean tint;

        Session(Object fragment, View content, ViewGroup frame) {
            this.fragment = fragment;
            this.content = content;
            this.frame = frame;
            dialog = findDialog(fragment);
            contentId = id("coui_panel_content_layout");
            rowId = id("root_view");
            titleId = id("course_title");
            editId = id("edit_btn");
            Object binding = Ref.field(fragment, "binding");
            Object list = Ref.field(binding, "recyclerView");
            recycler = list instanceof ViewGroup ? (ViewGroup) list : null;
            originalBackgroundBlurFlag = Boolean.TRUE.equals(Ref.field(frame, "mUseBackgroundBlur"));
            observer = frame.getViewTreeObserver();
        }

        int id(String name) { return frame.getResources().getIdentifier(name, "id", frame.getContext().getPackageName()); }

        void start() {
            frame.addOnAttachStateChangeListener(this);
            observer.addOnPreDrawListener(this);
            frame.invalidate();
        }

        @Override public boolean onPreDraw() {
            if (released || !frame.isAttachedToWindow() || !frame.isShown()) return true;
            int next = ThemeStore.style(frame.getContext());
            int config = frame.getResources().getConfiguration().hashCode();
            if (next != requestedStyle || config != configuration) {
                resetEffect();
                requestedStyle = next;
                configuration = config;
                failed = false;
            }
            if (next == ThemeStore.STYLE_CLASSIC || failed || frame.getWidth() == 0 || frame.getHeight() == 0)
                return true;
            try {
                if (!active) enable(next);
                if (nativeLayer != null) {
                    // These are drawing-only children. MATCH_PARENT participates
                    // in COUI's wrap-content measurement and stretches a short
                    // bottom sheet to full screen, so they measure as zero and
                    // receive their real drawing bounds after native layout.
                    themeLayer.layout(0, 0, frame.getWidth(), frame.getHeight());
                    nativeLayer.layout(0, 0, frame.getWidth(), frame.getHeight());
                }
                update();
            } catch (Throwable error) {
                failed = true;
                resetEffect();
                failure(error);
            }
            return true;
        }

        void enable(int requested) throws Exception {
            if (dialog == null || recycler == null) throw new IllegalStateException("原生详情面板容器缺失");
            originalFrameBackground = frame.getBackground();
            style = requested;
            neutralInk = themedInk(android.R.attr.textColorPrimary,
                    NingGuang.night(frame.getContext()) ? 0xFFF2F2F2 : 0xFF242424);
            if (style == ThemeStore.STYLE_LIQUID) {
                View page = HostTablePage.rootView();
                if (Build.VERSION.SDK_INT < 33 || !frame.isHardwareAccelerated()
                        || !(page instanceof ViewGroup) || !page.isAttachedToWindow())
                    throw new IllegalStateException("液态玻璃需要可用的课表背景和 GPU");
                // A different Activity's retained page must never supply this sheet.
                Object activity = Ref.callName(fragment, "getActivity");
                if (!(activity instanceof android.app.Activity)
                        || ((android.app.Activity) activity).getWindow().getDecorView() != page.getRootView())
                    throw new IllegalStateException("详情与课表背景不属于同一页面");
                sourcePage = page;
                source = (ViewGroup) page.getRootView();
                scene = new GlassScene(source, true);
                liquid = new LiquidCardEffect(frame.getResources().getDisplayMetrics().density);
            } else {
                Object activity = Ref.callName(fragment, "getActivity");
                View hostRoot = activity instanceof android.app.Activity
                        ? ((android.app.Activity) activity).getWindow().getDecorView() : null;
                if (hostRoot == null) throw new IllegalStateException("详情宿主窗口缺失");
                View page = HostTablePage.rootView();
                View image = page == null ? null : page.findViewWithTag(HostTablePage.TAG_BG);
                Logx.i(CourseAlarmEntry.TAG, "详情凝光背景 type=" + ThemeStore.type(frame.getContext())
                        + " sameWindow=" + (page != null && page.getRootView() == hostRoot)
                        + " image=" + (image instanceof android.widget.ImageView
                        && ((android.widget.ImageView) image).getDrawable() != null));
                // A native backdrop effect cannot isolate the Activity beneath a
                // Dialog window. Supply only theme pixels as an opaque sibling
                // behind the effect, so timetable text and the bottom bar never
                // become part of the panel material.
                themeLayer = new ThemeLayer(this, hostRoot);
                nativeLayer = new NativeLayer(this);
                material = new NativeCardMaterial(nativeLayer, hostRoot);
                frame.addView(themeLayer, 0, layerParams());
                frame.addView(nativeLayer, 1, layerParams());
            }
            active = true;
            suspendNativeBlur();
            // COUI multiplies native blur Drawable alpha separately from View alpha.
            // Our ordinary Drawable already participates in the View's animation.
            Ref.callName(frame, "setUseBackgroundBlur", false);
            // The active material supplies its own edge; keep COUI's original for restoration.
            Object shadow = Ref.field(frame, "mShadowEdgeDrawable");
            originalShadow = shadow instanceof Drawable ? (Drawable) shadow : null;
            Method shadowSetter = Ref.methodOf(frame.getClass(), "setShadowEdgeDrawable", Drawable.class);
            if (shadowSetter != null) {
                shadowSetter.invoke(frame, new Object[]{null});
                shadowSuspended = true;
            }
            if (material != null) material.attach();
            if (liquid != null) updateTint();
        }

        FrameLayout.LayoutParams layerParams() {
            return new FrameLayout.LayoutParams(0, 0, Gravity.TOP | Gravity.LEFT);
        }

        void suspendNativeBlur() throws Exception {
            blurBuilder = Ref.field(dialog, "mBackgroundBlurBuilder");
            if (blurBuilder == null) return;
            originalBlur = Boolean.TRUE.equals(Ref.callName(blurBuilder, "useBackgroundBlur"));
            ClassLoader loader = frame.getContext().getClassLoader();
            Class<?> utility = Class.forName("com.coui.appcompat.uiutil.UIUtil", false, loader);
            animationLevel = utility.getField("ANIM_LEVEL_SUPPORT_BLUR_MIN").get(null);
            setBlur = Ref.methodOf(blurBuilder.getClass(), "setUseBackgroundBlur", boolean.class, animationLevel.getClass());
            if (setBlur == null) throw new IllegalStateException("原生背景模糊关闭接口缺失");
            setBlur.invoke(blurBuilder, false, animationLevel);
            Ref.callName(blurBuilder, "release");
            blurSuspended = true;
        }

        void updateTint() throws Exception {
            Object values = Ref.callName(fragment, "getCourseList");
            tint = values instanceof List && ((List<?>) values).size() == 1;
            if (!tint) return; // Several courses retain their own row colors; the sheet stays neutral.
            Object course = ((List<?>) values).get(0);
            int index = ((Number) Ref.callName(course, "getColor")).intValue();
            Class<?> colors = Class.forName("com.oplus.todotaskability.TodoColor", false, frame.getContext().getClassLoader());
            Object token = colors.getMethod("createByIndex", Integer.class).invoke(null, index);
            int colorId = ((Number) Ref.callName(token, "getBgColor")).intValue();
            courseColor = frame.getResources().getColor(colorId, frame.getContext().getTheme());
        }

        void update() throws Exception {
            // mCornerRadius is populated only for COUI's original blur branch;
            // it can be zero on the very Dialog we are replacing. The frame's
            // radius is the actual native panel geometry.
            float nextRadius = number(Ref.field(frame, "mRadius"), 0);
            if (nextRadius <= 0) nextRadius = number(Ref.field(dialog, "mCornerRadius"), 0);
            if (nextRadius <= 0) nextRadius = 28 * frame.getResources().getDisplayMetrics().density;
            float bottomOffset = number(Ref.callName(frame, "updateBottomCornerRadius"),
                    number(Ref.callName(frame, "getRatio"), 1) == 2 ? 0 : nextRadius);
            float nextBottom = bottomOffset == 0 ? nextRadius : 0;
            boolean resized = width != frame.getWidth() || height != frame.getHeight()
                    || radius != nextRadius || bottomRadius != nextBottom;
            width = frame.getWidth(); height = frame.getHeight(); radius = nextRadius; bottomRadius = nextBottom;
            if (resized) {
                shape.reset();
                Object nativePath = Ref.field(frame, "mPath");
                if (nativePath instanceof Path && !((Path) nativePath).isEmpty()) shape.set((Path) nativePath);
                else shape.addRoundRect(0, 0, width, height,
                        new float[]{radius, radius, radius, radius, bottomRadius, bottomRadius, bottomRadius, bottomRadius}, Path.Direction.CW);
                background.invalidateSelf();
            }
            if (material != null) {
                material.update(radius);
                // Keep this background below every native control even when
                // COUI's shadow preset changes its elevation.
                nativeLayer.setTranslationZ(-nativeLayer.getElevation());
                if (resized) nativeLayer.invalidateOutline();
                themeLayer.update(resized);
            } else {
                if (sourcePage != HostTablePage.rootView() || !sourcePage.isAttachedToWindow() || !source.isAttachedToWindow())
                    throw new IllegalStateException("详情背景页面已退出");
                scene.capture(SystemClock.uptimeMillis());
                Matrix matrix = betweenWindows(frame, source);
                if (resized || generation != scene.generation || !previousMatrix.equals(matrix) || liquid.needsRecording()) {
                    liquid.update(width, height, radius, bottomRadius, matrix, scene);
                    previousMatrix.set(matrix);
                    generation = scene.generation;
                    background.invalidateSelf();
                }
                if (scene.animated() && frame.getWindowVisibility() == View.VISIBLE)
                    frame.postInvalidateOnAnimation();
            }
            if (frame.getBackground() != background) replaceBackground(frame, background);
            clearPanelBackgrounds(content);
            for (View current = content; current != frame; ) {
                clear(current);
                ViewParent parent = current.getParent();
                if (!(parent instanceof View)) break;
                current = (View) parent;
            }
            // Recycled rows may acquire a fresh white drawable on each bind.
            for (int i = 0; i < recycler.getChildCount(); i++) {
                View item = recycler.getChildAt(i);
                View row = rowId == 0 ? null : item.findViewById(rowId);
                if (row != null && titleId != 0 && row.findViewById(titleId) != null) {
                    if (item != row) clear(item);
                    rowSurface(row);
                }
            }
            adjustText(content);
        }

        void clearPanelBackgrounds(View view) {
            // Clear the known sheet containers, never buttons or course accents.
            if (contentId != 0 && view.getId() == contentId) clear(view);
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) clearPanelBackgrounds(group.getChildAt(i));
            }
        }

        void clear(View view) {
            if (view == frame || view.getBackground() == null) return;
            backgrounds.put(view, view.getBackground());
            replaceBackground(view, null);
            appliedBackgrounds.put(view, null);
        }

        void rowSurface(View row) {
            Drawable current = row.getBackground();
            if (appliedBackgrounds.containsKey(row) && current == appliedBackgrounds.get(row)) return;
            backgrounds.put(row, current);
            Drawable surface = current != null && current.getConstantState() != null
                    ? current.getConstantState().newDrawable(row.getResources()).mutate() : null;
            int color = NingGuang.night(frame.getContext()) ? 0x70202429 : 0x8CFFFFFF;
            if (surface == null) {
                GradientDrawable rounded = new GradientDrawable();
                rounded.setCornerRadius(20 * frame.getResources().getDisplayMetrics().density);
                rounded.setColor(color);
                surface = rounded;
            } else surface.setTint(color);
            replaceBackground(row, surface);
            appliedBackgrounds.put(row, surface);
        }

        void adjustText(View view) {
            if (view instanceof TextView && view.getId() != editId && !view.isClickable()) {
                TextView text = (TextView) view;
                if (text.getText().length() > 0) {
                    Integer last = appliedTextColors.get(text);
                    if (last == null || text.getCurrentTextColor() != last) textColors.put(text, text.getTextColors());
                    // Keep each course's accent on its title/edit button, while
                    // metadata follows the host neutral text palette. Native
                    // alpha, typography, and spacing remain intact.
                    int original = textColors.get(text).getDefaultColor();
                    int next = view.getId() == titleId ? readableAccent(original) : neutralInk;
                    if (text.getCurrentTextColor() != next) text.setTextColor(next);
                    appliedTextColors.put(text, next);
                }
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) adjustText(group.getChildAt(i));
            }
        }

        int themedInk(int attr, int fallback) {
            TypedValue value = new TypedValue();
            if (!frame.getContext().getTheme().resolveAttribute(attr, value, true)) return fallback;
            if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT && value.type <= TypedValue.TYPE_LAST_COLOR_INT) return value.data;
            try { return frame.getResources().getColorStateList(value.resourceId, frame.getContext().getTheme()).getDefaultColor(); }
            catch (Exception ignored) { return fallback; }
        }

        int readableAccent(int original) {
            boolean night = NingGuang.night(frame.getContext());
            int target = night ? 255 : 0;
            // Preserve hue and distinction between overlapping courses.
            float mix = night ? 0.28f : 0.12f;
            return Color.rgb(Math.round(Color.red(original) * (1 - mix) + target * mix),
                    Math.round(Color.green(original) * (1 - mix) + target * mix),
                    Math.round(Color.blue(original) * (1 - mix) + target * mix));
        }

        void resetEffect() {
            if (material != null) {
                material.release();
                material = null;
            }
            if (themeLayer != null) { frame.removeView(themeLayer); themeLayer = null; }
            if (nativeLayer != null) { frame.removeView(nativeLayer); nativeLayer = null; }
            if (liquid != null) { liquid.release(); liquid = null; }
            if (scene != null) { scene.release(); scene = null; }
            source = null;
            sourcePage = null;
            for (Map.Entry<View, Drawable> item : backgrounds.entrySet())
                if (item.getKey().getBackground() == appliedBackgrounds.get(item.getKey())) replaceBackground(item.getKey(), item.getValue());
            backgrounds.clear();
            appliedBackgrounds.clear();
            for (Map.Entry<TextView, ColorStateList> item : textColors.entrySet()) {
                Integer last = appliedTextColors.get(item.getKey());
                if (last != null && item.getKey().getCurrentTextColor() == last) item.getKey().setTextColor(item.getValue());
            }
            textColors.clear(); appliedTextColors.clear();
            if (active) {
                Ref.callName(frame, "setUseBackgroundBlur", originalBackgroundBlurFlag);
                Object latest = Ref.field(dialog, "mPanelBackground");
                replaceBackground(frame, latest instanceof Drawable ? (Drawable) latest : originalFrameBackground);
            }
            if (shadowSuspended) {
                Method set = Ref.methodOf(frame.getClass(), "setShadowEdgeDrawable", Drawable.class);
                Ref.call(frame, set, originalShadow);
                shadowSuspended = false;
            }
            if (blurSuspended) {
                Ref.call(blurBuilder, setBlur, originalBlur, animationLevel);
                if (originalBlur && frame.isAttachedToWindow()) Ref.callName(dialog, "doBlurInternal");
                blurSuspended = false;
            }
            active = false;
            generation = -1;
            width = height = 0;
            originalFrameBackground = null;
            background.invalidateSelf();
        }

        void release() {
            if (released) return;
            released = true;
            if (observer.isAlive()) observer.removeOnPreDrawListener(this);
            frame.removeOnAttachStateChangeListener(this);
            resetEffect();
            SESSIONS.remove(fragment);
        }

        @Override public void onViewAttachedToWindow(View view) { }
        @Override public void onViewDetachedFromWindow(View view) { release(); }
    }

    /** Opaque theme-only source in the same window as the native material. */
    private static final class ThemeLayer extends View {
        final Session session;
        final View hostRoot;
        final ThemeWindowBackground theme;
        final Matrix matrix = new Matrix();
        int themeKey;
        ThemeLayer(Session session, View hostRoot) {
            super(session.frame.getContext());
            this.session = session;
            this.hostRoot = hostRoot;
            theme = new ThemeWindowBackground(hostRoot, true);
            setWillNotDraw(false);
            setForceDarkAllowed(false);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            setClickable(false);
            setFocusable(false);
        }
        void update(boolean resized) {
            Matrix next = betweenWindows(hostRoot, this);
            View page = HostTablePage.rootView();
            View image = page == null ? null : page.findViewWithTag(HostTablePage.TAG_BG);
            int nextKey = java.util.Objects.hash(ThemeStore.type(getContext()), ThemeStore.solidIndex(getContext()),
                    ThemeStore.gradientIndex(getContext()), ThemeStore.scrim(getContext()), ThemeStore.imageAlpha(getContext()),
                    image instanceof android.widget.ImageView ? ((android.widget.ImageView) image).getDrawable() : null);
            if (resized || !matrix.equals(next) || themeKey != nextKey) {
                matrix.set(next);
                themeKey = nextKey;
                invalidate();
            }
            if (image instanceof android.widget.ImageView) {
                Drawable drawable = ((android.widget.ImageView) image).getDrawable();
                if (drawable instanceof android.graphics.drawable.AnimatedImageDrawable
                        && ((android.graphics.drawable.AnimatedImageDrawable) drawable).isRunning())
                    postInvalidateOnAnimation();
            }
        }
        @Override protected void onDraw(Canvas canvas) {
            int save = canvas.save();
            try {
                canvas.clipPath(session.shape);
                canvas.concat(matrix);
                theme.setBounds(0, 0, hostRoot.getWidth(), hostRoot.getHeight());
                theme.draw(canvas);
            } finally { canvas.restoreToCount(save); }
        }
        @Override protected void onMeasure(int widthSpec, int heightSpec) { setMeasuredDimension(0, 0); }
    }

    /** Separate background View: no course text participates in the blur. */
    private static final class NativeLayer extends View {
        final Session session;
        final Paint veil = new Paint(Paint.ANTI_ALIAS_FLAG);
        NativeLayer(Session session) {
            super(session.frame.getContext());
            this.session = session;
            setWillNotDraw(false);
            setForceDarkAllowed(false);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            setClickable(false);
            setFocusable(false);
            setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline outline) {
                    outline.setPath(session.shape);
                }
            });
            setClipToOutline(true);
        }
        @Override protected void onDraw(Canvas canvas) {
            int save = canvas.save();
            try {
                canvas.clipPath(session.shape);
                // The native material remains visible, while detail text has a
                // stable neutral surface across bright and dark photographs.
                veil.setColor(NingGuang.night(getContext()) ? 0x24202429 : 0x30FFFFFF);
                canvas.drawPath(session.shape, veil);
                if (session.material != null) session.material.drawEdge(canvas);
            } finally { canvas.restoreToCount(save); }
        }
        @Override protected void onMeasure(int widthSpec, int heightSpec) { setMeasuredDimension(0, 0); }
    }

    private static final class MaterialBackground extends Drawable {
        final Session session;
        int alpha = 255;
        MaterialBackground(Session session) { this.session = session; }
        @Override public void draw(Canvas canvas) {
            int save = alpha == 255 ? canvas.save() : canvas.saveLayerAlpha(0, 0,
                    getBounds().width(), getBounds().height(), alpha);
            try {
                canvas.clipPath(session.shape);
                if (session.liquid != null && canvas.isHardwareAccelerated()) {
                    session.liquid.draw(canvas, session.courseColor, session.tint);
                } else if (session.material != null) {
                    // Native layers are siblings below the unchanged content.
                } else {
                    canvas.drawColor(NingGuang.night(session.frame.getContext()) ? 0xFF202020 : 0xFFFFFFFF);
                }
            } finally { canvas.restoreToCount(save); }
        }
        @Override public boolean getPadding(Rect out) { out.setEmpty(); return false; }
        @Override public void setAlpha(int value) { alpha = value; invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
