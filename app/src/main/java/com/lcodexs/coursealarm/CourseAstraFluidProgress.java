package com.lcodexs.coursealarm;

import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

import org.json.JSONObject;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Adapts the official Calendar card when AstraFlow 2.2.8 renders the expanded island. */
final class CourseAstraFluidProgress {
    private static final String PACKAGE = "com.astraflow.tool";
    private static final String ROOT = "com.astraisland.render.x2";
    private static final String OFFICIAL = "com.astraisland.render.official.";
    private static final String CLOUD = "com.astraflow.fluidcloud.";
    private static final String START = "courseStartTime";
    private static final String END = "courseEndTime";
    private static final String MARKER = "lspxCourseProgress:" + java.util.UUID.randomUUID();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static void install(XposedModule module, Context context) {
        try {
            if (context.getPackageManager().getPackageInfo(PACKAGE, 0).getLongVersionCode() != 20208L) return;
            Method addView = Class.forName("android.view.WindowManagerImpl")
                    .getMethod("addView", View.class, ViewGroup.LayoutParams.class);
            Map<Class<?>, Boolean> attempted = new WeakHashMap<>();
            module.hook(addView).setId("course_astra_window_v1")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        if (chain.getArg(0) instanceof View view && ROOT.equals(view.getClass().getName())
                                && chain.getArg(1) instanceof WindowManager.LayoutParams params
                                && params.getTitle() != null && "AstraIsland".contentEquals(params.getTitle())) {
                            synchronized (attempted) {
                                if (attempted.put(view.getClass(), true) == null) {
                                    try {
                                        new Adapter(view.getClass().getClassLoader()).install(module);
                                        Logx.i(CourseAlarmEntry.TAG, "AstraFlow course divider hook ready");
                                    } catch (Exception | LinkageError error) {
                                        Logx.e(CourseAlarmEntry.TAG, "AstraFlow course divider unavailable", error);
                                    }
                                }
                            }
                        }
                        return chain.proceed();
                    });
        } catch (Exception | LinkageError ignored) { /* AstraFlow is optional. */ }
    }

    private record Timing(long start, long end, long deadline, int color) { }

    private static final class Adapter {
        private final Class<?> dividerDraw;
        private final Field cloudSnapshot, cloudPage, cloudViews, pkg, hostPkg, service, instance, entrance, payload;
        private final Field type, level, attrs, children, images, events, stroke;
        private final Constructor<?> record, node;
        private final Method buildEvent, renderTexts, renderDivider, rememberDraw, draw, drawSize, direction, drawLine;
        private final Method newState, getState, setState;
        private final Object unit;
        private final float density;
        private final ThreadLocal<Timing> rendering = new ThreadLocal<>();
        private final ThreadLocal<Timing> divider = new ThreadLocal<>();
        // The renderer gives each remembered draw callback its own identity.
        private final Map<Object, DrawState> drawStates = new WeakHashMap<>();
        private volatile boolean ready;
        private boolean drewProgress;

        Adapter(ClassLoader loader) throws ReflectiveOperationException {
            Class<?> cloudRecord = load(loader, CLOUD + "f");
            Class<?> snapshot = load(loader, CLOUD + "g");
            Class<?> page = load(loader, "com.astraisland.events.v");
            Class<?> theme = load(loader, OFFICIAL + "b3");
            Class<?> composer = load(loader, "defpackage.pp0");
            Class<?> modifier = load(loader, "defpackage.x23");
            Class<?> drawScope = load(loader, "defpackage.mi1");
            Class<?> state = load(loader, "defpackage.rh3");
            dividerDraw = load(loader, OFFICIAL + "j3");
            cloudSnapshot = cloudRecord.getField("with_key");
            cloudPage = cloudRecord.getField("should_skip");
            cloudViews = cloudRecord.getField("get_state");
            record = cloudRecord.getConstructor();
            pkg = snapshot.getField("get_state");
            hostPkg = snapshot.getField("AUX");
            service = snapshot.getField("on_start");
            instance = snapshot.getField("with_index");
            entrance = snapshot.getField("load_item");
            payload = snapshot.getField("do_close");
            type = page.getField("with_key");
            level = page.getField("should_skip");
            attrs = page.getField("get_state");
            children = page.getField("on_start");
            images = page.getField("with_index");
            events = page.getField("on_success");
            node = page.getConstructor(String.class, String.class, Map.class, List.class, Map.class, Map.class);
            stroke = dividerDraw.getField("load_item");
            buildEvent = load(loader, CLOUD + "e3").getMethod("on_start", cloudRecord);
            renderTexts = load(loader, OFFICIAL + "w4").getMethod("do_stop", page, theme, composer, int.class);
            renderDivider = load(loader, OFFICIAL + "u3").getMethod("pop_value", float.class, Integer.class,
                    boolean.class, modifier, load(loader, "defpackage.cg1"), composer, int.class, int.class);
            rememberDraw = load(loader, "defpackage.ec").getMethod("load_item", modifier,
                    load(loader, "defpackage.lz1"), composer, int.class);
            draw = dividerDraw.getMethod("invoke", Object.class);
            drawSize = drawScope.getMethod("on_success");
            direction = drawScope.getMethod("getLayoutDirection");
            drawLine = drawScope.getMethod("poll_key", long.class, long.class, long.class, float.class,
                    int.class, load(loader, "defpackage.je"), int.class);
            newState = load(loader, "defpackage.x84").getMethod("find_index", Object.class);
            getState = state.getMethod("getValue");
            setState = state.getMethod("setValue", Object.class);
            unit = load(loader, "defpackage.xc5").getField("with_key").get(null);
            density = android.content.res.Resources.getSystem().getDisplayMetrics().density;
        }

        void install(XposedModule module) {
            // Install the draw handoff first. If another signature is incompatible, none of the
            // callbacks can receive our course-only marker until installation completes.
            hook(module, draw, "draw", chain -> {
                DrawState progress = drawStates.get(chain.getThisObject());
                if (progress != null) try {
                    if (progress.draw(chain.getThisObject(), chain.getArg(0))) {
                        if (!drewProgress) {
                            drewProgress = true;
                            Logx.i(CourseAlarmEntry.TAG, "AstraFlow expanded course progress drawn");
                        }
                        return unit;
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) { }
                return chain.proceed();
            });
            hook(module, rememberDraw, "remember", chain -> {
                Object callback = chain.getArg(1);
                if (ready && callback != null && callback.getClass() == dividerDraw) {
                    Timing timing = divider.get();
                    if (timing == null) drawStates.remove(callback);
                    else try {
                        DrawState existing = drawStates.get(callback);
                        if (existing == null || !existing.timing.equals(timing))
                            drawStates.put(callback, new DrawState(timing));
                    } catch (ReflectiveOperationException | RuntimeException ignored) { drawStates.remove(callback); }
                }
                return chain.proceed();
            });
            hook(module, renderDivider, "divider", chain -> {
                Timing timing = rendering.get();
                if (timing == null) return chain.proceed();
                divider.set(timing);
                try {
                    Object[] args = chain.getArgs().toArray();
                    args[0] = 3.0f;
                    // Mark thickness changed for Compose even when a reused divider keeps its size.
                    args[6] = ((Integer) args[6] & ~6) | 4;
                    return chain.proceed(args);
                }
                finally { divider.remove(); }
            });
            hook(module, renderTexts, "trailing", chain -> {
                Timing timing = null;
                if (ready) try {
                    Map<?, ?> properties = (Map<?, ?>) attrs.get(chain.getArg(0));
                    if (properties.containsKey(MARKER)) timing = timing(properties);
                } catch (ReflectiveOperationException | RuntimeException ignored) { }
                if (timing == null) return chain.proceed();
                rendering.set(timing);
                try { return chain.proceed(); }
                finally { rendering.remove(); }
            });
            hook(module, buildEvent, "page", chain -> {
                Object decorated = null;
                if (ready) try { decorated = decorate(chain.getArg(0)); }
                catch (ReflectiveOperationException | RuntimeException ignored) { }
                return decorated == null ? chain.proceed() : chain.proceed(new Object[]{decorated});
            });
            ready = true;
        }

        private Object decorate(Object original) throws ReflectiveOperationException {
            Object snapshot = cloudSnapshot.get(original);
            if (!"com.coloros.calendar".equals(pkg.get(snapshot)) || !"com.coloros.calendar".equals(hostPkg.get(snapshot))
                    || !"268452047".equals(service.get(snapshot)) || !"ENTRY_STATUS_BAR".equals(entrance.get(snapshot)))
                return null;
            String receipt = (String) instance.get(snapshot);
            if (receipt.isEmpty()) return null;
            Map<?, ?> source = (Map<?, ?>) payload.get(snapshot);
            JSONObject initial = json(source.get("initData"));
            if (initial == null) initial = new JSONObject();
            if (!receipt.equals(text(source.get("serviceInstanceId"), initial.optString("serviceInstanceId")))
                    || !"1".equals(text(source.get("courseActivityVersion"), initial.optString("courseActivityVersion")))
                    || !"true".equals(text(source.get("courseProgressEnabled"), initial.optString("courseProgressEnabled"))))
                return null;

            Object page = cloudPage.get(original);
            if (page == null || !"card-template".equals(type.get(page))) return null;
            List<?> sections = (List<?>) children.get(page);
            int expandedIndex = section(sections, "expanded");
            if (expandedIndex < 0) return null;
            Object expanded = sections.get(expandedIndex);
            List<?> contents = (List<?>) children.get(expanded);
            int trailingIndex = section(contents, "trailing");
            if (trailingIndex < 0) return null;
            Object trailing = contents.get(trailingIndex);
            Map<?, ?> old = (Map<?, ?>) attrs.get(trailing);
            if (!"multi-texts".equals(old.get("category")) || "false".equals(old.get("divider"))) return null;

            Map<Object, Object> extra = new LinkedHashMap<>(old);
            extra.put(MARKER, receipt);
            extra.put(START, text(source.get(START), initial.optString(START)));
            extra.put(END, text(source.get(END), initial.optString(END)));
            extra.put("courseActivityDeadline", text(source.get("courseActivityDeadline"), initial.optString("courseActivityDeadline")));
            if (timing(extra) == null) return null;
            for (Object child : (List<?>) children.get(trailing)) {
                if ("C25".equals(level.get(child))) {
                    extra.put("courseProgressColor", ((Map<?, ?>) attrs.get(child)).get("color"));
                    break;
                }
            }
            List<Object> newContents = new ArrayList<>(contents);
            newContents.set(trailingIndex, copy(trailing, extra, (List<?>) children.get(trailing)));
            List<Object> newSections = new ArrayList<>(sections);
            newSections.set(expandedIndex, copy(expanded, (Map<?, ?>) attrs.get(expanded), newContents));
            Object decorated = record.newInstance();
            cloudSnapshot.set(decorated, snapshot);
            cloudViews.set(decorated, cloudViews.get(original));
            cloudPage.set(decorated, copy(page, (Map<?, ?>) attrs.get(page), newSections));
            return decorated;
        }

        private int section(List<?> nodes, String name) throws ReflectiveOperationException {
            for (int i = 0; i < nodes.size(); i++) if (name.equals(type.get(nodes.get(i)))) return i;
            return -1;
        }

        private Object copy(Object original, Map<?, ?> properties, List<?> descendants) throws ReflectiveOperationException {
            return node.newInstance(type.get(original), level.get(original), properties, descendants,
                    images.get(original), events.get(original));
        }

        private final class DrawState implements Runnable {
            final Timing timing;
            final Object clock;
            boolean posted;

            DrawState(Timing timing) throws ReflectiveOperationException {
                this.timing = timing;
                clock = newState.invoke(null, System.currentTimeMillis());
            }

            boolean draw(Object callback, Object scope) throws ReflectiveOperationException {
                getState.invoke(clock); // Register this draw layer for invalidation by the clock.
                long now = System.currentTimeMillis();
                if (now < timing.end() && !posted) {
                    posted = true;
                    MAIN.postAtTime(this, SystemClock.uptimeMillis() + Math.min(1000, timing.end() - now));
                }
                if (now < timing.start() || now >= timing.deadline()) return false;
                long size = (Long) drawSize.invoke(scope);
                float width = Float.intBitsToFloat((int) (size >>> 32));
                float height = Float.intBitsToFloat((int) size);
                float weight = Math.min(Math.min(stroke.getFloat(callback), height), density * 3);
                if (!Float.isFinite(width) || !Float.isFinite(weight) || width <= weight || weight <= 0f) return false;
                float inset = weight / 2;
                float fill = CourseNativeCardContract.fraction(now, timing.start(), timing.end()) * (width - weight);
                long left = point(inset, height / 2);
                long right = point(width - inset, height / 2);
                boolean rtl = direction.invoke(scope) instanceof Enum<?> layoutDirection && "Rtl".equals(layoutDirection.name());
                int color = timing.color();
                line(scope, (color & 0xffffff) | 0x38000000, left, right, weight);
                if (fill > 0f) line(scope, color, rtl ? right : left,
                        point(rtl ? width - inset - fill : inset + fill, height / 2), weight);
                return true;
            }

            private void line(Object scope, int color, long start, long finish, float weight)
                    throws ReflectiveOperationException {
                drawLine.invoke(scope, (long) color << 32, start, finish, weight, 1, null, 3);
            }

            @Override public void run() {
                posted = false;
                try { setState.invoke(clock, System.currentTimeMillis()); }
                catch (ReflectiveOperationException | RuntimeException ignored) { }
            }
        }
    }

    private static Timing timing(Map<?, ?> data) {
        try {
            long start = Long.parseLong(text(data.get(START), ""));
            long end = Long.parseLong(text(data.get(END), ""));
            long deadline = Long.parseLong(text(data.get("courseActivityDeadline"), ""));
            if (start <= 0 || end <= start || deadline < end || System.currentTimeMillis() >= deadline) return null;
            int color = 0xff5c9dff;
            Object value = data.get("courseProgressColor");
            try { if (value instanceof String hex) color = Color.parseColor(hex) | 0xff000000; }
            catch (IllegalArgumentException ignored) { }
            return new Timing(start, end, deadline, color);
        } catch (NumberFormatException ignored) { return null; }
    }

    private static JSONObject json(Object value) {
        if (value instanceof JSONObject object) return object;
        try { return value instanceof String s ? new JSONObject(s) : null; }
        catch (org.json.JSONException ignored) { return null; }
    }

    private static String text(Object value, String fallback) {
        return value == null ? fallback : value.toString();
    }

    private static long point(float x, float y) {
        return ((long) Float.floatToRawIntBits(x) << 32) | (Float.floatToRawIntBits(y) & 0xffffffffL);
    }

    private static Class<?> load(ClassLoader loader, String name) throws ClassNotFoundException {
        return Class.forName(name, false, loader);
    }

    private static void hook(XposedModule module, Method method, String id, XposedInterface.Hooker hook) {
        module.hook(method).setId("course_astra_" + id + "_v1")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(hook);
    }
}
