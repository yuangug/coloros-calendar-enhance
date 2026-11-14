package com.lcodexs.coursealarm;

import android.app.Activity;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 课程详情面板 —— 用宿主自己的那个（{@code CourseListInfoFragment}），不是我们画：
 *
 *   COUI 底部面板（MapBottomSheetDialogFragment，毛玻璃 + 大圆角）
 *     └─ CourseListInfoFragment：每门课一张卡片（课名 / 教室 / 教师 / 第几周 / 时间 / 编辑按钮）
 *
 * 宿主 SchoolTimeTableActivity#showCourseInfoDialog 的等价实现，参数照抄：
 *   setCourseList(点中的课程) / setTableViewType(点击来源视图类型) / setAllCourseList(整表课程)
 * 并在弹之前把每门课的 startTime / endTime 补上（宿主用 ScheduleTableUtils.getTimeByCourseNode
 * 从课程表 JSON 里算），否则卡片上的时间会是空/1970。
 */
final class CourseInfoSheet {
    private static final String T = CourseAlarmEntry.TAG;

    private static final String CLS_INFO = "com.coloros.calendar.widget.bottomsheetdialog.CourseListInfoFragment";
    private static final String CLS_FN1 = "kotlin.jvm.functions.Function1";
    private static final String CLS_GSON = "com.google.gson.Gson";
    private static final String CLS_TABLE_ENTITY =
            "com.android.calendar.card.schooltimetable.entity.CourseTableEntity";
    private static final String CLS_ENTITY =
            "com.android.calendar.card.schooltimetable.entity.CourseEntity";
    private static final String CLS_UTIL =
            "com.android.calendar.card.schooltimetable.ScheduleTableUtils";
    private static final String TAG_SHEET = "lspx_course_info";

    private CourseInfoSheet() {
    }

    static boolean show(Activity a, ClassLoader host, ArrayList<?> courses, int viewType) {
        if (a == null || host == null || courses == null || courses.isEmpty()) return false;
        try {
            Class<?> infoCls = Ref.cls(host, CLS_INFO);
            if (infoCls == null) {
                Logx.w(T, "CourseInfoSheet: CourseListInfoFragment 不存在");
                return false;
            }
            Object fragment = infoCls.getDeclaredConstructor().newInstance();
            Ref.callName(fragment, "setCourseList", courses);
            Ref.callName(fragment, "setTableViewType", viewType);
            Ref.callName(fragment, "setAllCourseList", courses);   // 同一份即可（详情面板只用来展示）

            fillTimes(host, courses);

            Class<?> fn1 = Ref.cls(host, CLS_FN1);
            Object sheet = null;
            if (fn1 != null) {
                Object unit = null;
                Class<?> unitCls = Ref.cls(host, "kotlin.Unit");
                if (unitCls != null) unit = Ref.field(unitCls, "INSTANCE");
                final Object unitRet = unit;
                // 「编辑」→ 宿主的新建/编辑课程页（带上这门课）；「添加」→ 复制这格的星期/节次再新建
                final Object[] sheetRef = new Object[1];
                Object onEdit = java.lang.reflect.Proxy.newProxyInstance(host, new Class<?>[]{fn1},
                        (p, m, args) -> {
                            Object entity = args != null && args.length > 0 ? args[0] : null;
                            Logx.i(T, "CourseInfoSheet: 点编辑");
                            dismiss(sheetRef[0]);
                            CourseEdit.edit(a, host, entity);
                            return unitRet;
                        });
                Object onAdd = java.lang.reflect.Proxy.newProxyInstance(host, new Class<?>[]{fn1},
                        (p, m, args) -> {
                            Object entity = args != null && args.length > 0 ? args[0] : null;
                            Logx.i(T, "CourseInfoSheet: 点添加");
                            dismiss(sheetRef[0]);
                            CourseEdit.add(a, host, entity);
                            return unitRet;
                        });
                Ref.callName(fragment, "setMOnEditItemClickListener", onEdit);
                Ref.callName(fragment, "setMAddCourseBtnClickListener", onAdd);

                sheetRef[0] = HostWeekSheet.showPanel(a, host, fragment, TAG_SHEET);
                sheet = sheetRef[0];
            } else {
                sheet = HostWeekSheet.showPanel(a, host, fragment, TAG_SHEET);
            }

            Logx.i(T, "CourseInfoSheet: 课程详情面板 " + (sheet != null ? "已弹出" : "弹不出来"));
            return sheet != null;
        } catch (Throwable t) {
            Logx.e(T, "CourseInfoSheet: 失败", t);
            return false;
        }
    }

    /** 关掉详情面板（点编辑/添加后先收，再去开新页面） */
    private static void dismiss(Object sheet) {
        if (sheet == null) return;
        try {
            Ref.callName(sheet, "dismiss");
        } catch (Throwable ignored) {
        }
    }
    /** 给每门课补 startTime / endTime（宿主卡片里「08:10 - 08:55 | 教室 | 教师」那行要用） */    private static void fillTimes(ClassLoader host, List<?> courses) {
        try {
            HostTableData d = HostTablePage.currentData();
            if (d == null || d.tableJson == null) return;
            Class<?> gsonCls = Ref.cls(host, CLS_GSON);
            Class<?> tableCls = Ref.cls(host, CLS_TABLE_ENTITY);
            Class<?> entityCls = Ref.cls(host, CLS_ENTITY);
            Class<?> utilCls = Ref.cls(host, CLS_UTIL);
            if (gsonCls == null || tableCls == null || entityCls == null || utilCls == null) return;
            Method fromJson = Ref.methodByTypes(gsonCls, String.class, Class.class);
            Method getTime = Ref.methodOf(utilCls, "getTimeByCourseNode", tableCls, entityCls, boolean.class);
            if (fromJson == null || getTime == null) {
                Logx.w(T, "CourseInfoSheet: 取不到 Gson#fromJson / getTimeByCourseNode，跳过时间填充");
                return;
            }
            Object gson = gsonCls.getDeclaredConstructor().newInstance();
            Object table = fromJson.invoke(gson, d.tableJson, tableCls);
            if (table == null) return;
            int filled = 0;
            for (Object c : courses) {
                Object start = getTime.invoke(null, table, c, true);
                Object end = getTime.invoke(null, table, c, false);
                if (start instanceof Long) Ref.callName(c, "setStartTime", start);
                if (end instanceof Long) Ref.callName(c, "setEndTime", end);
                filled++;
            }
            Logx.i(T, "CourseInfoSheet: 时间已填 " + filled + " 门");
        } catch (Throwable t) {
            Logx.w(T, "CourseInfoSheet: 填时间失败 " + t);
        }
    }
}

