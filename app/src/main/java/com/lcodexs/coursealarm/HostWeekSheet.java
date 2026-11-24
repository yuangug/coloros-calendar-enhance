package com.lcodexs.coursealarm;

import android.app.Activity;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 周次选择面板 —— **直接用宿主自己的那一套**，不重画：
 *
 *   COUIBottomSheetDialogFragment（MapBottomSheetDialogFragment，毛玻璃 + 大圆角）
 *     └─ CurrentWeekSelectFragment（COUIPanelFragment：标题「查看周表」+ 6 列周网格，
 *        本周那格显示「本周」、当前选中格高亮）
 *
 * 宿主在独立课表页里就是这么弹的（SchoolTimeTableActivity#showWeekSelectDialog），
 * 参数顺序与含义照抄：
 *   (weekNumberOfThisTerm, currentWeek, selectedWeek(0 起), termBeginTime, calendarId, courseList, callback)
 * 回调参数是 **0 起的周序号**，回来 +1 就是周次。
 *
 * 任何一步取不到（类/构造器/方法缺失）就返回 false，由 HostTablePage 回落到自绘面板。
 */
final class HostWeekSheet {
    private static final String T = CourseAlarmEntry.TAG;

    private static final String CLS_PANEL =
            "com.coloros.calendar.widget.bottomsheetdialog.CurrentWeekSelectFragment";
    private static final String CLS_SHEET =
            "com.android.calendar.card.schooltimetable.MapBottomSheetDialogFragment";
    private static final String CLS_FN1 = "kotlin.jvm.functions.Function1";
    private static final String TAG_SHEET = "lspx_week_select";

    private HostWeekSheet() {
    }

    static boolean show(Activity a, ClassLoader host, HostTableData d) {        if (a == null || host == null || d == null) return false;
        try {
            Class<?> panelCls = Ref.cls(host, CLS_PANEL);
            Class<?> sheetCls = Ref.cls(host, CLS_SHEET);
            Class<?> fn1Cls = Ref.cls(host, CLS_FN1);
            if (panelCls == null || sheetCls == null || fn1Cls == null) {
                Logx.w(T, "HostWeekSheet: 类缺失 panel=" + (panelCls != null)
                        + " sheet=" + (sheetCls != null) + " fn1=" + (fn1Cls != null));
                return false;
            }
            Constructor<?> k = Ref.ctorOf(panelCls, int.class, int.class, int.class,
                    long.class, long.class, List.class, fn1Cls);
            if (k == null) {
                Logx.w(T, "HostWeekSheet: CurrentWeekSelectFragment 没有 7 参构造器");
                return false;
            }

            Object fm = Ref.callName(a, "getSupportFragmentManager");
            if (fm == null) {
                Logx.w(T, "HostWeekSheet: 没有 getSupportFragmentManager");
                return false;
            }

            int weeks = Math.max(1, d.weekCount);
            int current = Math.max(1, Math.min(d.currentWeek(), weeks));
            int viewed = HostTablePage.viewingWeekIndex0();
            int selected = viewed >= 0 && viewed < weeks ? viewed : current - 1;
            long term = d.termBeginMs > 0 ? d.termBeginMs : System.currentTimeMillis();
            long calId = d.calendarId;
            List<Object> courses = new ArrayList<>();      // 空 list 宿主自己会兜底

            Object cb = HostCompat.unitCallback(host, value -> {
                if (value instanceof Integer) {
                    int idx = (Integer) value;
                    Logx.i(T, "HostWeekSheet: 选中第 " + (idx + 1) + " 周");
                    HostTablePage.jumpTo(idx + 1);
                }
            });

            Object panel = k.newInstance(weeks, current, selected, term, calId, courses, cb);
            Object sheet = showPanel(a, host, panel, TAG_SHEET);
            if (sheet != null) {
                Logx.i(T, "HostWeekSheet: 宿主周次面板已弹出 weeks=" + weeks
                        + " current=" + current + " selected=" + selected);
            }
            return sheet != null;
        } catch (Throwable t) {
            Logx.e(T, "HostWeekSheet: 弹宿主面板失败", t);
            return false;
        }
    }

    /** 把任意 COUI 面板（BaseCOUIPanelFragment）装进宿主那个底部毛玻璃面板弹出来；返回面板对象 */
    static Object showPanel(Activity a, ClassLoader host, Object panel, String tag) {
        try {
            Class<?> sheetCls = Ref.cls(host, CLS_SHEET);
            if (sheetCls == null) {
                Logx.w(T, "HostWeekSheet: MapBottomSheetDialogFragment 不存在");
                return null;
            }
            Object fm = Ref.callName(a, "getSupportFragmentManager");
            if (fm == null) {
                Logx.w(T, "HostWeekSheet: 没有 getSupportFragmentManager");
                return null;
            }
            Object sheet = sheetCls.getDeclaredConstructor().newInstance();
            Ref.callName(sheet, "setShowMaxHeigth", false);
            Ref.callName(sheet, "setBlurBackground", true);
            Ref.callName(sheet, "setCanPullUp", false);
            Ref.callName(sheet, "setMainPanelFragment", panel);

            Method show = findShow(sheetCls, fm);
            if (show == null) {
                Logx.w(T, "HostWeekSheet: 找不到 show(FragmentManager,String)");
                return null;
            }
            show.invoke(sheet, fm, tag);
            return sheet;
        } catch (Throwable t) {
            Logx.e(T, "HostWeekSheet: showPanel 失败", t);
            return null;
        }
    }

    /** show(FragmentManager, String)：形参类型按「能不能装下 fm」判断，避开混淆/子类差异 */
    private static Method findShow(Class<?> sheetCls, Object fm) {
        for (Class<?> k = sheetCls; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (!"show".equals(m.getName()) || m.getParameterCount() != 2) continue;
                Class<?>[] pt = m.getParameterTypes();
                if (pt[0].isInstance(fm) && pt[1] == String.class) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        return null;
    }
}
