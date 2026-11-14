package com.lcodexs.coursealarm;

import android.app.Activity;
import android.view.View;

import java.lang.reflect.Method;
import java.lang.ref.WeakReference;
import java.lang.reflect.Proxy;
import java.util.ArrayList;

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
 * 其余（增删课/拖动/复制）先不接管，保持安静。
 *
 * 注意 TableView 只存 {@code WeakReference}，所以代理必须由我们强引用住（{@link #callback}）。
 */
final class TableViewBridge {
    private static final String T = CourseAlarmEntry.TAG;

    private static final String CLS_TABLE_VIEW =
            "com.android.calendar.card.schooltimetable.view.TableView";
    private static final String CLS_CALLBACK = CLS_TABLE_VIEW + "$TableViewCallback";
    private static final String CLS_INIT_PARAMS = CLS_TABLE_VIEW + "$InitParams";

    /** 强引用住回调代理（TableView 里只有 WeakReference） */
    private static volatile Object callback;
    private static volatile Class<?> callbackCls;
    private static volatile Object unit;

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
        Logx.i(T, "hooked TableView#initTableView (课程点击回调)");
    }

    private static void attach(View table, ClassLoader host) {
        try {
            Activity act = activity(table.getContext());
            if (act == null) return;
            Object cb = ensureCallback(act, host);
            if (cb == null) return;
            // WeakReference 里的强引用由 callback 字段保住
            Object wr = new WeakReference<>(cb);
            Ref.callName(table, "setTableViewCallback", wr);
            Logx.i(T, "tableview: 课程点击回调已装（act=" + act.getClass().getSimpleName() + "）");
        } catch (Throwable t) {
            Logx.w(T, "tableview: 装回调失败 " + t);
        }
    }

    private static synchronized Object ensureCallback(final Activity act, final ClassLoader host) {
        if (callback != null) return callback;
        Class<?> cbCls = callbackCls;
        if (cbCls == null) return null;
        Class<?> unitCls = Ref.cls(host, "kotlin.Unit");
        unit = unitCls == null ? null : Ref.field(unitCls, "INSTANCE");
        try {
            callback = Proxy.newProxyInstance(host, new Class<?>[]{cbCls},
                    (p, m, args) -> dispatch(act, host, m.getName(), args));
            Logx.i(T, "tableview: 回调代理已建");
            return callback;
        } catch (Throwable t) {
            Logx.e(T, "tableview: 建回调代理失败", t);
            return null;
        }
    }

    private static Object dispatch(Activity act, ClassLoader host, String name, Object[] args) {
        switch (name) {
            case "showCourseInfoDialog":
                if (args != null && args.length >= 2 && args[0] instanceof ArrayList) {
                    int viewType = args[1] instanceof Integer ? (Integer) args[1] : 0;
                    ArrayList<?> courses = (ArrayList<?>) args[0];
                    Logx.i(T, "tableview: 点了课程（" + courses.size() + " 门同格）→ 宿主详情面板");
                    CourseInfoSheet.show(act, host, courses, viewType);
                }
                return unit;
            case "showWeekSelectDialog":
                Logx.i(T, "tableview: 左上角周次 → 宿主周次面板");
                HostTablePage.showWeekSheet();
                return unit;
            case "refreshCourseData":
                HostTablePage.refresh();
                return unit;
            case "getCopyCourse":
                return null;
            default:
                return name.startsWith("get") ? null : unit;
        }
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
