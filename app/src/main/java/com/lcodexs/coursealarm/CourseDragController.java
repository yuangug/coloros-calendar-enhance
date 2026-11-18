package com.lcodexs.coursealarm;

import android.app.Activity;
import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.*;
import android.widget.PopupWindow;
import java.lang.ref.WeakReference;
import java.lang.reflect.*;
import java.util.*;
import io.github.libxposed.api.*;

/** Owns only an enabled timetable's course gesture. Preview geometry never changes a host entity. */
final class CourseDragController {
    private static final Map<View, State> STATES = new WeakHashMap<>();
    private static final ThreadLocal<Object> MENU_COURSE = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> FALLBACK_DRAW = new ThreadLocal<>();
    private static final Map<String, Field> FIELDS = new HashMap<>();
    private static Method courseX, courseY, nativeLongPress, drawItem;
    private static boolean available;
    private static final long HOLD_MS = 500;

    private CourseDragController() { }

    static void install(XposedModule module, ClassLoader host) {
        Class<?> table = Ref.cls(host, "com.android.calendar.card.schooltimetable.view.TableView");
        if (table == null) return;
        Method touch = Ref.methodOf(table, "onTouchEvent", MotionEvent.class);
        Method hit = Ref.methodOf(table, "getCourseByPosition", float.class, float.class);
        Method draw = Ref.methodOf(table, "onDraw", Canvas.class);
        courseX = Ref.methodOf(table, "computeCourseX", boolean.class, int.class);
        courseY = Ref.methodOf(table, "computeCourseY", boolean.class, int.class);
        nativeLongPress = Ref.methodOf(table, "onLongPress", MotionEvent.class);
        drawItem = Ref.methodByArity(table, "drawCourse$drawCourseItem", 6);
        if (touch == null || hit == null || draw == null || courseX == null || courseY == null
                || nativeLongPress == null || drawItem == null) return;
        try {
            for (String name : new String[]{"scrollY", "maxScrollY", "bottomSpace", "contentHeight", "currentWeek", "mCourseList", "showingCourseList",
                    "mTableType", "isShareMode", "isImportMode", "isClickable", "isRtl", "courseCount",
                    "dayNumberOfWeek", "contentStartY", "cellHeigt", "cellWidth", "courseRadius", "mGestureDetector",
                    "isDragTouchCourseTop", "isDragTouchCourseBottom", "isDraggingCourse", "mTouchCourse",
                    "mTouchColorAlpha", "mSumMoveDx", "mSumMoveDy", "longClicking", "touchingMove",
                    "multiTouching", "isVerticalScroll", "popupWindow"}) {
                Field field = table.getDeclaredField(name); field.setAccessible(true); FIELDS.put(name, field);
            }
        } catch (ReflectiveOperationException error) {
            Logx.e(CourseAlarmEntry.TAG, "拖动调课所需的宿主几何接口不完整", error); return;
        }
        available = true;
        module.hook(touch).setId("course_drag_touch").setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
            View view = (View) chain.getThisObject(); MotionEvent event = (MotionEvent) chain.getArg(0);
            if (event == null) return chain.proceed();
            try {
                Boolean consumed = beforeTouch(view, event);
                if (consumed != null) return consumed;
                Object result = chain.proceed();
                State state = STATES.get(view);
                if (state != null && state.tracking) disableResize(view);
                return result;
            } catch (Exception error) {
                State state = STATES.get(view); if (state != null) state.cancel(true);
                Logx.e(CourseAlarmEntry.TAG, "拖动调课手势已取消", error);
                return true;
            }
        });
        module.hook(hit).setId("course_drag_menu_identity").setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> MENU_COURSE.get() == null ? chain.proceed() : MENU_COURSE.get());
        Method handles = Ref.methodOf(table, "drawDragArea", Canvas.class);
        if (handles != null) module.hook(handles).setId("course_drag_hide_resize_handles")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            View view = (View) chain.getThisObject();
            if (eligible(view) && hasEvent(get(view, "mTouchCourse"))) return null;
            return chain.proceed();
        });
        // On older graphics stacks the same native card is drawn once, above the grid, on a private clone.
        module.hook(drawItem).setId("course_drag_software_source")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            View view = (View) chain.getArg(0); State state = STATES.get(view);
            if (state != null && state.dragging && state.source == chain.getArg(4)
                    && !Boolean.TRUE.equals(FALLBACK_DRAW.get()) && !CourseSurfaceRenderer.hasSurface(view)) return null;
            return chain.proceed();
        });
        module.hook(draw).setId("course_drag_software_preview")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            View view = (View) chain.getThisObject(); State state = STATES.get(view);
            DrawBounds bounds = null;
            try {
                if (state != null && state.dragging) bounds = state.extendDrawBounds(view);
                Object result = chain.proceed();
                if (state != null && state.dragging && !CourseSurfaceRenderer.hasSurface(view))
                    state.drawFallback((Canvas) chain.getArg(0));
                return result;
            } finally {
                if (bounds != null) {
                    bounds.restore(view);
                    if (!state.dragging)
                        set(view, "scrollY", Math.max(0, Math.min(bounds.maxScroll, number(get(view, "scrollY")))));
                }
            }
        });
        Method hidden = Ref.methodOf(table, "onVisibilityChanged", View.class, int.class);
        if (hidden != null) module.hook(hidden).setId("course_drag_visibility")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object result = chain.proceed(); State state = STATES.get(chain.getThisObject());
            if (state != null && ((Integer) chain.getArg(1)) != View.VISIBLE) state.cancel(true);
            return result;
        });
        Method focus = Ref.methodOf(table, "onWindowFocusChanged", boolean.class);
        if (focus != null) module.hook(focus).setId("course_drag_window_focus")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object result = chain.proceed(); State state = STATES.get(chain.getThisObject());
            // Range/page dialogs intentionally take window focus after ACTION_UP.
            if (state != null && !((Boolean) chain.getArg(0)) && !state.waiting) state.cancel(true);
            return result;
        });
        Logx.i(CourseAlarmEntry.TAG, "拖动调课手势已接入；课表独立开关默认关闭");
    }

    static boolean available() { return available; }

    /** Called by the bridge before its existing native popup hook. */
    static boolean deferLongPress(View table) {
        if (MENU_COURSE.get() != null) return false;
        State state = STATES.get(table);
        if (state == null || !state.tracking) return false;
        state.arm();
        return true;
    }

    /** Rectangles use the host's unmirrored, unscrolled content coordinates. */
    static boolean previewRect(View table, Object course, RectF destination) {
        State state = STATES.get(table);
        if (state == null || !state.dragging || state.source != course) return false;
        destination.set(state.ghost); return true;
    }

    static void release(Activity activity) {
        RescheduleUi.dismiss(activity);
        for (State state : new ArrayList<>(STATES.values())) if (state.activity.get() == activity) state.release();
    }

    static void cancelActive(Activity activity) {
        RescheduleUi.dismiss(activity);
        for (State state : new ArrayList<>(STATES.values())) if (state.activity.get() == activity) state.cancel(true);
    }

    static float previewBottom(View table) {
        State state = STATES.get(table);
        return state == null ? table.getHeight() : state.contentBottom;
    }

    private static Boolean beforeTouch(View table, MotionEvent event) throws Exception {
        State state = STATES.get(table);
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            if (state != null) state.cancel(true);
            if (!eligible(table)) return null;
            if (state == null) { state = new State(table); STATES.put(table, state); }
            state.down(event);
            return null;
        }
        if (state == null) return null;
        if (state.consumeTail) {
            if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL)
                state.consumeTail = false;
            return true;
        }
        if (!state.tracking) return state.waiting ? Boolean.TRUE : null;
        boolean owned = state.armed || state.dragging;
        if (!state.current() || event.getPointerCount() != 1 || event.getActionMasked() == MotionEvent.ACTION_CANCEL
                || event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN) {
            state.cancel(true);
            state.consumeTail = event.getActionMasked() != MotionEvent.ACTION_UP && event.getActionMasked() != MotionEvent.ACTION_CANCEL;
            return owned ? Boolean.TRUE : null;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                state.x = event.getX(); state.y = event.getY();
                boolean moved = Math.hypot(state.x - state.downX, state.y - state.downY) > state.slop;
                if (!state.armed) {
                    if (moved) state.cancel(false); // Native scroll/fling/weekly paging keeps the original event stream.
                    return null;
                }
                if (moved) {
                    state.moved = true;
                    if (state.candidates.size() > 1 && state.source == null) return true;
                    if (!state.thisWeek) {
                        if (!state.warned) { state.warned = true; CurriculumIo.toast(table.getContext(), "非本周课程禁止拖动"); }
                        return true;
                    }
                    if (state.session != null && !state.dragging) state.beginDrag();
                }
                if (state.dragging) state.move();
                return true;
            case MotionEvent.ACTION_UP:
                if (!state.armed) { state.cancel(false); return null; }
                state.up(event); return true;
            default:
                return state.armed ? Boolean.TRUE : null;
        }
    }

    private static boolean eligible(View table) {
        HostTableData data = HostTablePage.currentData();
        if (!available || !HostTablePage.showing() || data == null || data.calendarId <= 0 || !table.isShown()
                || Boolean.TRUE.equals(get(table, "isShareMode")) || Boolean.TRUE.equals(get(table, "isImportMode"))
                || !Boolean.TRUE.equals(get(table, "isClickable")) || integer(get(table, "mTableType")) != 0
                || integer(get(table, "currentWeek")) != HostTablePage.viewingWeekIndex0() + 1) return false;
        View root = HostTablePage.rootView(); ViewParent parent = table.getParent();
        while (parent != null && parent != root) parent = parent.getParent();
        return parent == root && TableSettings.enabled(table.getContext(), data.calendarId);
    }

    private static final class Geometry {
        final float[] left, right, top, bottom;
        final float header, cellHeight;
        final int width, height;
        final boolean rtl;
        Geometry(View table) throws Exception {
            int days = integer(get(table, "dayNumberOfWeek")), nodes = integer(get(table, "courseCount"));
            if (days < 1 || days > 7 || nodes < 1 || nodes > 100) throw new IllegalStateException("课表网格尚未就绪");
            left = new float[days]; right = new float[days]; top = new float[nodes]; bottom = new float[nodes];
            for (int i = 0; i < days; i++) { left[i] = number(courseX.invoke(table, true, i)); right[i] = number(courseX.invoke(table, false, i)); }
            for (int i = 0; i < nodes; i++) { top[i] = number(courseY.invoke(table, true, i)); bottom[i] = number(courseY.invoke(table, false, i)); }
            header = number(get(table, "contentStartY")); cellHeight = number(get(table, "cellHeigt"));
            width = table.getWidth(); height = table.getHeight(); rtl = Boolean.TRUE.equals(get(table, "isRtl"));
        }
        float logicalX(float x) { return rtl ? width - x : x; }
        int day(float x) { for (int i = 0; i < left.length; i++) if (x >= left[i] && x <= right[i]) return i; return -1; }
        int node(float y) { for (int i = 0; i < top.length; i++) if (y >= top[i] && y <= bottom[i]) return i; return -1; }
        RectF rect(int day, int start, int end) { return new RectF(left[day], top[start], right[day], bottom[end]); }
    }

    /** Values changed by native onDraw must not leak into normal scrolling or accumulate per frame. */
    private static final class DrawBounds {
        final float bottomSpace, contentHeight, maxScroll;
        DrawBounds(View view) {
            bottomSpace = number(get(view, "bottomSpace"));
            contentHeight = number(get(view, "contentHeight"));
            maxScroll = number(get(view, "maxScrollY"));
        }
        void restore(View view) {
            set(view, "bottomSpace", bottomSpace);
            set(view, "contentHeight", contentHeight);
            set(view, "maxScrollY", maxScroll);
        }
    }

    private static final class State implements View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
        final WeakReference<View> table;
        final WeakReference<Activity> activity;
        final int slop;
        final Runnable hold = this::arm, frame = this::scrollFrame;
        final RectF origin = new RectF(), ghost = new RectF(), target = new RectF();
        final Outline outline = new Outline(this);
        HostTableData data;
        Geometry geometry;
        List<Object> candidates = Collections.emptyList();
        Object source, fallbackClone;
        DragSession session;
        ViewGroup overlayRoot;
        WeakReference<View> navigation = new WeakReference<>(null);
        final Matrix navigationToTable = new Matrix();
        final RectF navigationBounds = new RectF();
        MotionEvent downEvent;
        float downX, downY, x, y, offsetX, offsetY, contentBottom;
        long generation, selectedId = -1, captureId, calendarId, lastFrame;
        int week, targetDay, targetStart;
        boolean tracking, armed, moved, dragging, waiting, valid, thisWeek, warned, consumeTail, frameScheduled;
        State(View table) {
            this.table = new WeakReference<>(table); this.activity = new WeakReference<>(HostTablePage.activity());
            slop = ViewConfiguration.get(table.getContext()).getScaledTouchSlop();
            table.addOnAttachStateChangeListener(this); table.getViewTreeObserver().addOnPreDrawListener(this);
        }
        boolean down(MotionEvent event) throws Exception {
            View view = table.get(); if (view == null) return false;
            geometry = new Geometry(view); data = HostTablePage.currentData();
            Activity owner = activity.get();
            int barId = view.getResources().getIdentifier("sliding_date_picker", "id", view.getContext().getPackageName());
            navigation = new WeakReference<>(owner == null || barId == 0 ? null : owner.findViewById(barId));
            updateBottom();
            if (calendarId != data.calendarId) selectedId = -1;
            calendarId = data.calendarId; week = integer(get(view, "currentWeek"));
            downX = x = event.getX(); downY = y = event.getY();
            float logicalX = geometry.logicalX(x), contentY = y + number(get(view, "scrollY"));
            int day = geometry.day(logicalX), node = geometry.node(contentY);
            if (y < geometry.header || y >= contentBottom || day < 0 || node < 0) return false;
            ArrayList<Object> found = new ArrayList<>(); HashSet<Long> ids = new HashSet<>();
            Object raw = get(view, "showingCourseList");
            if (raw instanceof List<?>) for (Object course : (List<?>) raw) {
                if (hasEvent(course) && integer(Ref.callName(course, "getDayOfWeek")) == day
                        && integer(Ref.callName(course, "getStartNode")) <= node
                        && integer(Ref.callName(course, "getEndNode")) >= node && ids.add(eventId(course))) found.add(course);
            }
            if (found.isEmpty()) return false;
            candidates = found; source = found.size() == 1 ? found.get(0) : null;
            if (source == null) for (Object course : found) if (eventId(course) == selectedId) source = course;
            tracking = true; downEvent = MotionEvent.obtain(event); captureId = ++generation;
            // The old selection may have resize handles. Only existing courses are intercepted;
            // the native blank-cell add/paste and range selection path remains intact.
            Ref.callName(view, "clearTouchCourse"); disableResize(view);
            if (source != null) {
                int start = integer(Ref.callName(source, "getStartNode"));
                int end = integer(Ref.callName(source, "getEndNode"));
                if (start < 0 || end < start || end >= geometry.top.length) { cancel(false); return false; }
                origin.set(geometry.rect(day, start, end)); ghost.set(origin);
                offsetX = logicalX - origin.left; offsetY = contentY - origin.top;
                thisWeek = inWeek(source, week);
            }
            view.postDelayed(hold, HOLD_MS); return true;
        }
        void capture(Object course, long request) {
            Activity act = activity.get(); if (act == null) return;
            DragSession.capture(act, course, week, (loaded, error) -> {
                if (request != generation || !tracking || !current()) return;
                if (loaded == null) {
                    warned = true; CurriculumIo.toast(act, error == null ? "无法读取课程，请重试" : error);
                    boolean owned = armed; cancel(true); consumeTail = owned; return;
                }
                session = loaded;
                if (armed && moved) { beginDrag(); move(); }
            });
        }
        void arm() {
            View view = table.get();
            if (!tracking || armed || view == null || !current()) return;
            armed = true; view.removeCallbacks(hold); cancelNativeDetector(view);
            Ref.callName(view, "clearTouchCourse"); disableResize(view);
            view.getParent().requestDisallowInterceptTouchEvent(true);
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            // Ordinary taps and swipes never enqueue a database snapshot.
            if (source != null && thisWeek) capture(source, captureId);
        }
        void beginDrag() {
            View view = table.get();
            if (view == null || !current() || session == null || source == null || dragging) return;
            View root = HostTablePage.rootView(); if (!(root instanceof ViewGroup)) { cancel(true); return; }
            dragging = true; overlayRoot = (ViewGroup) root;
            outline.setBounds(0, 0, root.getWidth(), root.getHeight()); overlayRoot.getOverlay().add(outline);
            try {
                String json = CourseClipboard.snapshot(view.getContext().getClassLoader(), source);
                fallbackClone = HostEntities.fromJson(view.getContext(), source.getClass().getName(), json);
                Ref.callName(fallbackClone, "setEvent", Ref.callName(source, "getEvent"));
            } catch (Exception error) { Logx.e(CourseAlarmEntry.TAG, "普通卡片拖动预览", error); }
            move();
        }
        void move() {
            View view = table.get(); if (view == null || !dragging) return;
            updateBottom();
            float scroll = number(get(view, "scrollY"));
            ghost.set(origin); ghost.offsetTo(geometry.logicalX(x) - offsetX, y + scroll - offsetY);
            targetDay = geometry.day(ghost.centerX());
            targetStart = geometry.node(ghost.top + geometry.cellHeight / 2);
            int length = session.source.course.endNode - session.source.course.startNode;
            int end = targetStart + length;
            valid = x >= 0 && x <= geometry.width && y >= geometry.header && y < contentBottom
                    && geometry.day(geometry.logicalX(x)) >= 0 && geometry.node(y + scroll) >= 0
                    && targetDay >= 0 && targetStart >= 0 && end < geometry.top.length;
            if (valid) target.set(geometry.rect(targetDay, targetStart, end)); else target.setEmpty();
            invalidate(); scheduleScroll();
        }
        void up(MotionEvent event) {
            View view = table.get(); Activity act = activity.get();
            if (view == null || act == null) { cancel(true); return; }
            view.removeCallbacks(hold); view.removeCallbacks(frame); frameScheduled = false;
            view.getParent().requestDisallowInterceptTouchEvent(false);
            if (source == null && candidates.size() > 1) { chooseCourse(); return; }
            if (!moved) { Object chosen = source; MotionEvent menuEvent = MotionEvent.obtain(downEvent); cancel(true); showMenu(view, chosen, menuEvent); return; }
            if (!dragging || session == null) {
                if (thisWeek && !warned) CurriculumIo.toast(act, "课程正在读取，请重新长按拖动");
                cancel(true); return;
            }
            x = event.getX(); y = event.getY(); move(); view.removeCallbacks(frame); frameScheduled = false;
            if (!valid || targetDay == session.source.course.day && targetStart == session.source.course.startNode) { cancel(true); return; }
            ghost.offsetTo(target.left, target.top); tracking = false; waiting = true; invalidate();
            long dialogGeneration = generation;
            try { RescheduleUi.show(act, session, targetDay, targetStart, () -> { if (generation == dialogGeneration) cancel(true); }); }
            catch (Exception error) { cancel(true); CurriculumIo.toast(act, "无法打开调课范围，请重试"); Logx.e(CourseAlarmEntry.TAG, "调课范围", error); }
        }
        void chooseCourse() {
            Activity act = activity.get(); List<Object> choices = new ArrayList<>(candidates); cancel(true);
            if (act == null) return;
            ArrayList<String> labels = new ArrayList<>();
            for (Object course : choices) labels.add(String.valueOf(Ref.callName(course, "getCourseName")) + " · 第"
                    + (integer(Ref.callName(course, "getStartNode")) + 1) + "—"
                    + (integer(Ref.callName(course, "getEndNode")) + 1) + "节");
            if (!HostSheet.show(act, "选择要调动的课程", labels, index -> {
                if (index >= 0 && index < choices.size()) {
                    selectedId = eventId(choices.get(index));
                    CurriculumIo.toast(act, "已选择课程，请再次长按拖动");
                }
            })) CurriculumIo.toast(act, "无法打开原生课程选择面板");
        }
        boolean current() {
            View view = table.get(); Activity act = activity.get();
            return view != null && act != null && !act.isFinishing() && !act.isDestroyed()
                    && HostTablePage.activity() == act && HostTablePage.showing() && view.isShown()
                    && view.isAttachedToWindow() && HostTablePage.currentData() == data
                    && data != null && data.calendarId == calendarId && TableCtx.hostCurrentTableId(act) == calendarId
                    && integer(get(view, "currentWeek")) == week && HostTablePage.viewingWeekIndex0() + 1 == week
                    && TableSettings.enabled(act, calendarId)
                    && view.getWidth() == geometry.width && view.getHeight() == geometry.height;
        }
        void scheduleScroll() {
            View view = table.get();
            if (view != null && dragging && !waiting && !frameScheduled && scrollSpeed() != 0) {
                frameScheduled = true; view.postOnAnimation(frame);
            }
        }
        float scrollSpeed() {
            View view = table.get(); if (view == null || geometry == null) return 0;
            float density = view.getResources().getDisplayMetrics().density, edge = 48 * density;
            if (y < geometry.header + edge) return -12 * density * Math.min(1, (geometry.header + edge - y) / edge);
            if (y > contentBottom - edge) return 12 * density * Math.min(1, (y - contentBottom + edge) / edge);
            return 0;
        }
        void updateBottom() {
            View view = table.get(); if (view == null) return;
            contentBottom = view.getHeight();
            WindowInsets insets = view.getRootWindowInsets();
            if (insets != null) {
                int[] position = new int[2]; view.getLocationOnScreen(position);
                int[] rootPosition = new int[2]; View root = view.getRootView(); root.getLocationOnScreen(rootPosition);
                contentBottom = Math.min(contentBottom, rootPosition[1] + root.getHeight() - insets.getSystemWindowInsetBottom() - position[1]);
            }
            View bar = navigation.get();
            if (bar != null && bar.isShown() && bar.getHeight() > 0) {
                navigationToTable.reset(); bar.transformMatrixToGlobal(navigationToTable); view.transformMatrixToLocal(navigationToTable);
                navigationBounds.set(0, 0, bar.getWidth(), bar.getHeight()); navigationToTable.mapRect(navigationBounds);
                if (navigationBounds.right > 0 && navigationBounds.left < view.getWidth())
                    contentBottom = Math.min(contentBottom, navigationBounds.top);
            }
            contentBottom = Math.max(geometry.header, contentBottom);
        }
        void scrollFrame() {
            frameScheduled = false; View view = table.get();
            if (!dragging || waiting || view == null || !current()) { if (dragging && !waiting) cancel(true); return; }
            updateBottom();
            long now = SystemClock.uptimeMillis(); float step = Math.min(2, lastFrame == 0 ? 1 : (now - lastFrame) / 16.67f); lastFrame = now;
            float old = number(get(view, "scrollY"));
            float next = Math.max(0, Math.min(dragScrollLimit(view), old + scrollSpeed() * step));
            if (next == old) return;
            set(view, "scrollY", next); move();
        }
        float dragScrollLimit(View view) {
            float margin = 12 * view.getResources().getDisplayMetrics().density;
            return Math.max(number(get(view, "maxScrollY")),
                    geometry.bottom[geometry.bottom.length - 1] + margin - contentBottom);
        }
        DrawBounds extendDrawBounds(View view) {
            if (geometry == null) return null;
            updateBottom();
            if (dragScrollLimit(view) <= number(get(view, "maxScrollY"))) return null;
            DrawBounds bounds = new DrawBounds(view);
            // Native onDraw recomputes maxScrollY from bottomSpace and clamps scrollY.
            // Merely increasing our auto-scroll limit loses that offset every frame.
            // Cover the occluded viewport plus the drop margin even when native content
            // is shorter than the viewport (where maxScrollY was rounded up to zero).
            float margin = 12 * view.getResources().getDisplayMetrics().density;
            float extra = Math.max(0, view.getHeight() - contentBottom) + margin + 1;
            set(view, "bottomSpace", bounds.bottomSpace + extra);
            return bounds;
        }
        void invalidate() { View view = table.get(); if (view != null) view.invalidate(); outline.invalidateSelf(); }
        void cancel(boolean cancelNative) {
            View view = table.get(); ++generation;
            if (view != null) {
                view.removeCallbacks(hold); view.removeCallbacks(frame);
                if (dragging) {
                    float nativeMax = Math.max(0, number(get(view, "maxScrollY")));
                    set(view, "scrollY", Math.max(0, Math.min(nativeMax, number(get(view, "scrollY")))));
                }
                if (cancelNative && (armed || dragging || waiting)) {
                    cancelNativeDetector(view); Ref.callName(view, "clearTouchCourse"); disableResize(view);
                    set(view, "longClicking", false); set(view, "touchingMove", false);
                    if (view.getParent() != null) view.getParent().requestDisallowInterceptTouchEvent(false);
                }
            }
            if (overlayRoot != null) { overlayRoot.getOverlay().remove(outline); overlayRoot = null; }
            if (downEvent != null) { downEvent.recycle(); downEvent = null; }
            tracking = armed = moved = dragging = waiting = valid = thisWeek = warned = frameScheduled = consumeTail = false;
            session = null; source = fallbackClone = null; candidates = Collections.emptyList(); lastFrame = 0;
            invalidate();
        }
        void release() {
            cancel(true); View view = table.get();
            if (view != null) {
                view.removeOnAttachStateChangeListener(this);
                if (view.getViewTreeObserver().isAlive()) view.getViewTreeObserver().removeOnPreDrawListener(this);
                STATES.remove(view);
            }
        }
        void drawFallback(Canvas canvas) {
            View view = table.get(); if (view == null || fallbackClone == null) return;
            int save = canvas.save();
            try {
                canvas.clipRect(0, geometry.header, geometry.width, contentBottom);
                if (geometry.rtl) canvas.scale(-1, 1, geometry.width / 2f, geometry.height / 2f);
                canvas.translate(ghost.left - origin.left, ghost.top - origin.top - number(get(view, "scrollY")));
                FALLBACK_DRAW.set(true);
                drawItem.invoke(null, view, week, canvas, Collections.emptyList(), fallbackClone, false);
            } catch (Exception error) { Logx.e(CourseAlarmEntry.TAG, "拖动预览绘制", error); }
            finally {
                FALLBACK_DRAW.remove(); canvas.restoreToCount(save);
                Object showing = get(view, "showingCourseList");
                // CourseEntity.equals compares course content; remove only our drawing
                // clone, otherwise an equal real course can lose its hit-test entry.
                if (showing instanceof List<?>) {
                    List<?> courses = (List<?>) showing;
                    for (int i = courses.size() - 1; i >= 0; i--)
                        if (courses.get(i) == fallbackClone) courses.remove(i);
                }
            }
        }
        @Override public void onViewAttachedToWindow(View view) { }
        @Override public void onViewDetachedFromWindow(View view) { release(); }
        @Override public boolean onPreDraw() {
            if ((tracking || dragging || waiting) && !current()) {
                boolean owned = tracking && armed; cancel(true); consumeTail = owned;
            }
            return true;
        }
    }

    private static final class Outline extends Drawable {
        final WeakReference<State> owner;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Matrix matrix = new Matrix();
        Outline(State state) { owner = new WeakReference<>(state); }
        @Override public void draw(Canvas canvas) {
            State state = owner.get(); if (state == null || !state.dragging || !state.valid) return;
            View table = state.table.get(); ViewGroup root = state.overlayRoot;
            if (table == null || root == null) return;
            matrix.reset(); table.transformMatrixToGlobal(matrix); root.transformMatrixToLocal(matrix);
            if (state.geometry.rtl) matrix.preScale(-1, 1, table.getWidth() / 2f, table.getHeight() / 2f);
            float scroll = number(get(table, "scrollY")); matrix.preTranslate(0, -scroll);
            int save = canvas.save(); canvas.concat(matrix);
            canvas.clipRect(state.geometry.left[0], state.geometry.header + scroll,
                    state.geometry.right[state.geometry.right.length - 1], state.contentBottom + scroll);
            TypedValue value = new TypedValue();
            int attr = table.getResources().getIdentifier("couiColorPrimary", "attr", table.getContext().getPackageName());
            paint.setColor(attr != 0 && table.getContext().getTheme().resolveAttribute(attr, value, true) ? value.data : 0xFF1D74F5);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2 * table.getResources().getDisplayMetrics().density);
            float radius = number(get(table, "courseRadius")); canvas.drawRoundRect(state.target, radius, radius, paint);
            canvas.restoreToCount(save);
        }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private static void showMenu(View table, Object course, MotionEvent event) {
        if (course == null) { event.recycle(); return; }
        try {
            set(table, "mSumMoveDx", 0f); set(table, "mSumMoveDy", 0f); set(table, "isVerticalScroll", true);
            set(table, "multiTouching", false); MENU_COURSE.set(course);
            nativeLongPress.invoke(table, event); disableResize(table);
            Object popup = get(table, "popupWindow");
            if (popup instanceof PopupWindow && ((PopupWindow) popup).isShowing())
                ((PopupWindow) popup).setOnDismissListener(event::recycle);
            else event.recycle();
        } catch (Exception error) { event.recycle(); Logx.e(CourseAlarmEntry.TAG, "课程长按菜单", error); }
        finally { MENU_COURSE.remove(); }
    }
    private static void disableResize(View table) {
        set(table, "isDragTouchCourseTop", false); set(table, "isDragTouchCourseBottom", false); set(table, "isDraggingCourse", false);
    }
    private static void cancelNativeDetector(View table) {
        Object detector = get(table, "mGestureDetector"); if (!(detector instanceof GestureDetector)) return;
        long now = SystemClock.uptimeMillis(); MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0, 0, 0);
        try { ((GestureDetector) detector).onTouchEvent(cancel); } finally { cancel.recycle(); }
    }
    private static boolean inWeek(Object course, int week) {
        Object raw = Ref.callName(course, "getWeek");
        return raw instanceof List<?> && week > 0 && week <= ((List<?>) raw).size() && Boolean.TRUE.equals(((List<?>) raw).get(week - 1));
    }
    private static boolean hasEvent(Object course) { return course != null && Ref.callName(course, "getEvent") != null; }
    private static long eventId(Object course) {
        Object value = Ref.callName(Ref.callName(course, "getEvent"), "getId"); return value instanceof Number ? ((Number) value).longValue() : -1;
    }
    private static Object get(View view, String name) {
        try { Field field = FIELDS.get(name); return field == null ? null : field.get(view); }
        catch (IllegalAccessException ignored) { return null; }
    }
    private static void set(View view, String name, Object value) {
        try { Field field = FIELDS.get(name); if (field != null) field.set(view, value); }
        catch (IllegalAccessException error) { Logx.e(CourseAlarmEntry.TAG, "拖动状态 " + name, error); }
    }
    private static float number(Object value) { return value instanceof Number ? ((Number) value).floatValue() : 0; }
    private static int integer(Object value) { return value instanceof Number ? ((Number) value).intValue() : 0; }
}
