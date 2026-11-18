package com.lcodexs.coursealarm;

import android.app.Activity;
import android.view.View;
import android.view.MotionEvent;
import android.graphics.Rect;
import android.widget.PopupWindow;

import java.lang.reflect.Method;
import java.lang.ref.WeakReference;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 让叠在主页里的课表「点课程能看详情」。
 *
 * 宿主的课表是自绘 View（{@code TableView}），点中课程时回调
 * {@code TableView$TableViewCallback#showCourseInfoDialog(ArrayList<CourseEntity>, int viewType)}；
 * 这个回调是宿主编排的：{@code defpackage.b#onBindViewHolder} 里
 * <pre>
 *   SchoolTimeTableActivity stta = context instanceof SchoolTimeTableActivity ? (…) : null;
 *   if (stta != null) holder.getTableView().setTableViewCallback(new WeakReference<>(stta));
 * </pre>
 * 我们的页面是 AllInOneActivity 里的 ViewPager2 + 宿主的周适配器，context 不是
 * SchoolTimeTableActivity → 回调压根没装上 → 点了没反应（不加模块时宿主自己是好的）。
 *
 * 所以在 {@code TableView#initTableView} 之后补一个自己的回调：
 *   showCourseInfoDialog → 弹宿主的课程详情面板（{@link CourseInfoSheet}）
 *   showWeekSelectDialog → 弹宿主的周次面板（左上「11周 ▾」也能用了）
 *   refreshCourseData    → 重载课表页
 *   onCreateCourse / onCourseCopy / pasteCourse → 新建、独立复制与事务粘贴
 *
 * TableView 只存 WeakReference，代理和剪贴内容由所属 Activity 的状态强引用。
 */
final class TableViewBridge {
    private static final String T = CourseAlarmEntry.TAG;

    private static final String CLS_TABLE_VIEW =
            "com.android.calendar.card.schooltimetable.view.TableView";
    private static final String CLS_CALLBACK = CLS_TABLE_VIEW + "$TableViewCallback";
    private static final String CLS_INIT_PARAMS = CLS_TABLE_VIEW + "$InitParams";

    private static final Map<Activity, CallbackState> CALLBACKS = new WeakHashMap<>();
    private static volatile Class<?> callbackCls;

    private static final class CallbackState {
        Object callback;
        String copiedJson;
        Object copiedCourse;
        long calendarId = -1;

        Object copied(Activity activity) {
            return calendarId == TableCtx.hostCurrentTableId(activity) ? copiedCourse : null;
        }
    }

    private TableViewBridge() {
    }

    static void install(XposedModule module, ClassLoader host) {
        Class<?> tvCls = Ref.cls(host, CLS_TABLE_VIEW);
        Class<?> cbCls = Ref.cls(host, CLS_CALLBACK);
        Class<?> paramsCls = Ref.cls(host, CLS_INIT_PARAMS);
        if (tvCls == null || cbCls == null) {
            Logx.w(T, "TableViewBridge: TableView/TableViewCallback 不存在，课程详情不可用");
            return;
        }
        callbackCls = cbCls;
        CourseDragController.install(module, host);
        Method init = paramsCls == null ? null : Ref.methodOf(tvCls, "initTableView", paramsCls);
        if (init == null) {
            Logx.w(T, "TableViewBridge: initTableView(InitParams) 不存在");
            return;
        }
        module.hook(init)
                .setId("tableview_callback")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object r = chain.proceed();
                    Object self = chain.getThisObject();
                    if (self instanceof View) attach((View) self, host);
                    return r;
                });
        Method longPress = Ref.methodOf(tvCls, "onLongPress", MotionEvent.class);
        if (longPress != null) module.hook(longPress)
                .setId("tableview_long_press_actions")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    if (chain.getThisObject() instanceof View
                            && CourseDragController.deferLongPress((View) chain.getThisObject())) return null;
                    Object result = chain.proceed();
                    if (chain.getThisObject() instanceof View) configureMenu((View) chain.getThisObject(), host);
                    return result;
                });
        Logx.i(T, "hooked TableView#initTableView (课程点击回调)");
    }

    private static void attach(View table, ClassLoader host) {
        try {
            Activity act = activity(table.getContext());
            // The native timetable already owns its full callback (drag/copy/delete).
            if (act == null || !act.getClass().getName().equals("com.android.calendar.AllInOneActivity")) return;
            Object cb = ensureCallback(act, host).callback;
            if (cb == null) return;
            // WeakReference 里的强引用由所属 Activity 的 CallbackState 保住
            Object wr = new WeakReference<>(cb);
            Ref.callName(table, "setTableViewCallback", wr);
            Logx.i(T, "tableview: 课程点击回调已装（act=" + act.getClass().getSimpleName() + "）");
        } catch (Throwable t) {
            Logx.w(T, "tableview: 装回调失败 " + t);
        }
    }

    private static synchronized CallbackState ensureCallback(final Activity act, final ClassLoader host) throws Exception {
        CallbackState existing = CALLBACKS.get(act);
        if (existing != null) return existing;
        Class<?> cbCls = callbackCls;
        if (cbCls == null) throw new IllegalStateException("课程回调不可用");
        CallbackState state = new CallbackState();
        WeakReference<Activity> owner = new WeakReference<>(act);
        state.callback = Proxy.newProxyInstance(host, new Class<?>[]{cbCls},
                    (p, m, args) -> {
                        if (m.getDeclaringClass() == Object.class) return HostCompat.objectMethod(p, m, args);
                        Activity current = owner.get();
                        if (current == null || current.isDestroyed()) return null;
                        try { return dispatch(current, host, state, m.getName(), args); }
                        catch (Exception error) { failed(current, error); return null; }
                    });
        CALLBACKS.put(act, state);
        return state;
    }

    static synchronized void release(Activity activity) { CALLBACKS.remove(activity); CourseDragController.release(activity); }

    private static Object dispatch(Activity act, ClassLoader host, CallbackState state, String name, Object[] args) throws Exception {
        switch (name) {
            case "onCreateCourse":
                if (args != null && args.length == 1 && args[0] != null) {
                    requireCurrent(act);
                    boolean opened = Ref.callName(args[0], "getEvent") == null
                            ? CourseEdit.create(act, host, args[0]) : CourseEdit.edit(act, host, args[0]);
                    if (!opened) throw new IllegalStateException("无法打开课程编辑页，请重试");
                }
                return null;
            case "onCourseCopy":
                if (args != null && args.length == 1) copy(act, host, state, args[0]);
                return null;
            case "pasteCourse":
                if (args != null && args.length == 1) {
                    HostTableData data = requireCurrent(act);
                    String json = CourseClipboard.snapshot(host, args[0]);
                    Course value = CourseClipboard.read(json);
                    CourseMutations.pasteCourse(act, data.calendarId, data.tableJson,
                            CourseClipboard.at(json, value.day, value.startNode));
                }
                return null;
            case "showCourseInfoDialog":
                if (args != null && args.length >= 2 && args[0] instanceof ArrayList) {
                    int viewType = args[1] instanceof Integer ? (Integer) args[1] : 0;
                    ArrayList<?> courses = (ArrayList<?>) args[0];
                    Logx.i(T, "tableview: 点了课程（" + courses.size() + " 门同格）→ 宿主详情面板");
                    CourseInfoSheet.show(act, host, courses, viewType);
                }
                return null;
            case "showWeekSelectDialog":
                Logx.i(T, "tableview: 左上角周次 → 宿主周次面板");
                HostTablePage.showWeekSheet();
                return null;
            case "onDeleteCourse":
                if (args != null && args.length > 0) {
                    Object event = Ref.callName(args[0], "getEvent");
                    Object id = Ref.callName(event, "getId");
                    if (id instanceof Number) CourseMutations.deleteCourse(act, ((Number)id).longValue(), false);
                }
                return null;
            case "refreshCourseData":
                HostTablePage.refresh();
                return null;
            case "getCopyCourse":
                return state.copied(act);
            default:
                return null;
        }
    }

    private static HostTableData requireCurrent(Activity activity) {
        HostTableData data = HostTablePage.currentData();
        if (HostTablePage.activity() != activity || data == null || data.calendarId <= 0
                || data.tableJson == null || data.calendarId != TableCtx.hostCurrentTableId(activity))
            throw new IllegalStateException("课表正在切换，请稍后重试");
        return data;
    }

    private static void copy(Activity activity, ClassLoader host, CallbackState state, Object course) throws Exception {
        HostTableData data = requireCurrent(activity);
        Object event = Ref.callName(course, "getEvent");
        if (event == null) throw new IllegalArgumentException("请选择已有课程");
        Object calendar = event.getClass().getMethod("getCalendarId").invoke(event);
        if (!(calendar instanceof Number) || ((Number) calendar).longValue() != data.calendarId)
            throw new IllegalStateException("课程所属课表已变化，请重新选择");
        String json = CourseClipboard.snapshot(host, course);
        CourseClipboard.read(json);
        Object snapshot = HostEntities.fromJson(activity,
                "com.android.calendar.card.schooltimetable.entity.CourseEntity", json);
        state.copiedJson = json;
        state.copiedCourse = snapshot;
        state.calendarId = data.calendarId;
        CurriculumIo.toast(activity, "课程已复制");
    }

    /** Reuse the host popup; only wire actions and keep both blank-cell buttons visible. */
    private static void configureMenu(View table, ClassLoader host) {
        Activity activity = activity(table.getContext());
        if (activity == null || activity != HostTablePage.activity()) return;
        Object rawPopup = Ref.field(table, "popupWindow");
        if (!(rawPopup instanceof PopupWindow)) return;
        PopupWindow popup = (PopupWindow) rawPopup;
        Object course = Ref.field(table, "mTouchCourse");
        if (!popup.isShowing() || course == null) return;
        try {
            CallbackState state = ensureCallback(activity, host);
            View content = popup.getContentView();
            int id = activity.getResources().getIdentifier("btn_copy", "id", activity.getPackageName());
            View action = id == 0 ? null : content.findViewById(id);
            if (action == null) throw new IllegalStateException("原生复制菜单不可用");
            boolean empty = Ref.callName(course, "getEvent") == null;
            boolean hidden = action.getVisibility() != View.VISIBLE;
            action.setVisibility(View.VISIBLE);
            action.setOnClickListener(view -> {
                try {
                    if (!empty) copy(activity, host, state, course);
                    else if (state.copied(activity) == null) CurriculumIo.toast(activity, "请先复制课程");
                    else {
                        HostTableData data = requireCurrent(activity);
                        int day = ((Number) course.getClass().getMethod("getDayOfWeek").invoke(course)).intValue();
                        int start = ((Number) course.getClass().getMethod("getStartNode").invoke(course)).intValue();
                        CourseMutations.pasteCourse(activity, data.calendarId, data.tableJson,
                                CourseClipboard.at(state.copiedJson, day, start));
                    }
                } catch (Exception error) { failed(activity, error); }
                finally { popup.dismiss(); Ref.callName(table, "clearTouchCourse"); }
            });
            if (hidden) {
                float center = (((Number) course.getClass().getMethod("getMLeft").invoke(course)).floatValue()
                        + ((Number) course.getClass().getMethod("getMRight").invoke(course)).floatValue()) / 2f;
                if (Boolean.TRUE.equals(Ref.field(table, "isRtl"))) center = table.getWidth() - center;
                final float anchor = center;
                // showAtLocation has not laid out its window yet. Wait before reading screen coordinates.
                content.post(() -> {
                    if (!popup.isShowing() || !table.isAttachedToWindow()) return;
                    int[] location = new int[2]; content.getLocationOnScreen(location);
                    Rect frame = new Rect(); table.getWindowVisibleDisplayFrame(frame);
                    content.measure(View.MeasureSpec.makeMeasureSpec(frame.width(), View.MeasureSpec.AT_MOST),
                            View.MeasureSpec.makeMeasureSpec(frame.height(), View.MeasureSpec.AT_MOST));
                    int width = content.getMeasuredWidth();
                    int[] tableLocation = new int[2]; table.getLocationOnScreen(tableLocation);
                    int left = Math.max(frame.left, Math.min(frame.right - width, Math.round(tableLocation[0] + anchor - width / 2f)));
                    popup.update(left, location[1], width, content.getMeasuredHeight());
                });
            }
        } catch (Exception error) { failed(activity, error); }
    }

    private static void failed(Activity activity, Exception error) {
        Logx.e(T, "课表菜单操作失败", error);
        CurriculumIo.toast(activity, "操作失败：" + error.getMessage());
    }

    private static Activity activity(android.content.Context c) {
        for (int i = 0; i < 8 && c != null; i++) {
            if (c instanceof Activity) return (Activity) c;
            if (c instanceof android.content.ContextWrapper) {
                c = ((android.content.ContextWrapper) c).getBaseContext();
            } else {
                break;
            }
        }
        return null;
    }
}
