package com.lcodexs.coursealarm;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Method;
import java.util.List;

/**
 * 「分享课表」—— 把宿主自己的 SchoolShareTableActivity 需要的 extras 补齐再打开。
 *
 * 之前模块直接 startActivity(SchoolShareTableActivity)，一个 extra 都没带，
 * 于是标题变成 `null`、周次变成 `第 0 周`、表格全空（用户看到的就是那张空白分享图）。
 *
 * 宿主的原生入口（SchoolTimeTableActivity#createShareTableDialog）传的是这五个：
 *   course_name    = 课表名                 （CourseTableManageListAdapter.COURSE_NAME）
 *   current_week   = 当前周（1 起）
 *   selected_week  = 当前选中周（0 起）
 *   extension_json = calendars_json_extensions（CourseTableEntity）
 *   course_list    = Gson(List&lt;CourseEntity&gt;)
 * 这里逐条照抄：数据全部来自 HostTableData（宿主同一口径 query 出来的），
 * 所以分享页画出来的表就是用户在课表页看到的那一张。
 */
final class HostShareTable {
    private static final String T = CourseAlarmEntry.TAG;

    private static final String CLS_SHARE =
            "com.android.calendar.card.schooltimetable.SchoolShareTableActivity";

    // 宿主 Intent extra 键（CourseTableManageListAdapter / SchoolTimeTableActivity 常量值）
    private static final String K_COURSE_NAME = "course_name";
    private static final String K_CURRENT_WEEK = "current_week";
    private static final String K_SELECTED_WEEK = "selected_week";
    private static final String K_EXTENSION_JSON = "extension_json";
    private static final String K_COURSE_LIST = "course_list";

    private static final Handler UI = new Handler(Looper.getMainLooper());

    private HostShareTable() {
    }

    /** 主线程调用：读库在后台，读完回主线程起页面 */
    static void start(Activity a) {
        if (a == null) return;
        ClassLoader host = a.getClassLoader();
        if (Ref.cls(host, CLS_SHARE) == null) {
            Logx.w(T, "分享课表：找不到 " + CLS_SHARE);
            CurriculumIo.toast(a, "这个版本的日历没有分享课表页");
            return;
        }
        final Activity act = a;
        new Thread(() -> {
            try {
                HostTableData d = HostTableData.load(act);
                if (d.tableJson == null || d.tableJson.isEmpty()) {
                    Logx.w(T, "分享课表：没有 calendars_json_extensions，无法构造分享数据");
                    UI.post(() -> CurriculumIo.toast(act, "还没读到课表，先在课表页刷新一下再分享"));
                    return;
                }
                List<Object> courses = d.buildCourseList(host);
                String courseListJson = toJson(host, courses);
                int selected0 = HostTablePage.viewingWeekIndex0();
                if (selected0 < 0) selected0 = d.currentWeekIndex0();
                final int sel = Math.max(0, Math.min(selected0, Math.max(0, d.weekCount - 1)));
                final String listJson = courseListJson;
                final int cur = sel + 1;
                UI.post(() -> launch(act, d, listJson, cur, sel));
            } catch (Throwable t) {
                Logx.e(T, "分享课表：准备数据失败", t);
                UI.post(() -> CurriculumIo.toast(act, "分享课表失败：" + t));
            }
        }, "lspx-share-table").start();
    }

    private static void launch(Activity a, HostTableData d, String courseListJson, int cur, int sel) {
        try {
            Class<?> shareCls = Ref.cls(a.getClassLoader(), CLS_SHARE);
            Intent it = new Intent(a, shareCls);
            String name = d.tableName == null || d.tableName.isEmpty() ? "课程表" : d.tableName;
            it.putExtra(K_COURSE_NAME, name);
            it.putExtra(K_CURRENT_WEEK, cur);
            it.putExtra(K_SELECTED_WEEK, sel);
            it.putExtra(K_EXTENSION_JSON, d.tableJson);
            it.putExtra(K_COURSE_LIST, courseListJson);
            // 宿主这份 Intent 是同一 App 内组件，不需要 FLAG_ACTIVITY_NEW_TASK
            a.startActivity(it);
            Logx.i(T, "分享课表：已打开宿主分享页 name=" + name + " week=" + cur
                    + " sel0=" + sel + " courses=" + d.courseJsons.size()
                    + " listJsonLen=" + (courseListJson == null ? 0 : courseListJson.length())
                    + " tableJsonLen=" + d.tableJson.length());
        } catch (Throwable t) {
            Logx.e(T, "分享课表：启动失败", t);
            CurriculumIo.toast(a, "打不开分享课表：" + t);
        }
    }

    /** 宿主 Gson.toJson(Object) —— 生成 List&lt;CourseEntity&gt; 的 JSON 文本 */
    private static String toJson(ClassLoader host, List<Object> courses) {
        try {
            Class<?> gsonCls = Ref.cls(host, "com.google.gson.Gson");
            if (gsonCls == null) return "[]";
            Object gson = gsonCls.getConstructor().newInstance();
            // 必须精确取 toJson(Object)：Gson 里还有一个 toJson(JsonElement)，
            // 按参数个数挑会挑到它 → 传 List 直接 ClassCastException → course_list 变成 "[]"，
            // 分享页于是画出一张空表（实测踩到，日志里 listJsonLen=2）。
            Method m = Ref.methodOf(gsonCls, "toJson", Object.class);
            if (m == null) {
                Logx.w(T, "Gson#toJson(Object) 没找到，course_list 只能给空数组");
                return "[]";
            }
            Object out = m.invoke(gson, courses);
            String s = out == null ? "[]" : String.valueOf(out);
            Logx.i(T, "course_list json len=" + s.length() + " courses=" + courses.size());
            return s;
        } catch (Throwable t) {
            Logx.w(T, "course_list toJson failed: " + t);
            return "[]";
        }
    }
}
