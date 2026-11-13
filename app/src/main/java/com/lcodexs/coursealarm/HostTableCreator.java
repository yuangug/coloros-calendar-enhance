package com.lcodexs.coursealarm;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;

/**
 * 用**宿主自己的建表链路**新建课表。
 *
 * 为什么不能自己插一行 calendar 就完事：课程表的"表"在宿主眼里是一个 CalendarEntity
 * （calendarJsonExtensions 里塞 CourseTableEntity：开学日、周数、节次时间…），存在宿主自己的库里。
 * 只插 calendar 行，宿主不认，课表管理里看不到。
 *
 * 宿主的真实链路（jadx 静态证据，见 analysis/findings-coursealarm.md §0.15）：
 *   CreateCourseTableActivity 从 extras["course_bundle"] 读
 *     {calendar_id=-1, extension_json=<CourseTableEntity JSON>, course_name,
 *      course_list=<CourseEntity[] JSON>, is_import_course=true, create_type}
 *   → is_import_course=true 时它的 ViewModel.saveCoursesAndTable(extensionJson, courseListJson)
 *   → 写 CalendarEntity + events + LIVE 通知，全部由宿主完成。
 *
 * 所以这里有两条路，先快后稳：
 *   A. 直接反射调 CreateCourseTableViewModel.saveCoursesAndTable（用户零操作）
 *   B. 不行就用上面的 course_bundle 打开宿主建表页（用户在宿主 UI 里点一次保存）
 *
 * 旧实现在两处是错的，导致「新建课表导入」永远失败：
 *   1. 识别 ViewModel 用 getExtensionJson()，而 ActivitySchoolTimeTableViewModel 提供的是
 *      getCourseJson() / getCourseList() —— 于是候选恒为 0（日志：table ViewModel not found among 0 candidates）；
 *   2. 构造用 (Application, CalendarRepository) 双参，宿主真实构造函数只有一个 Application 参数
 *      → NoSuchMethodException。
 */
final class HostTableCreator {
    private static final String T = CourseAlarmEntry.TAG;

    static final String CLS_CREATE_ACTIVITY =
            "com.android.calendar.card.schooltimetable.CreateCourseTableActivity";
    static final String CLS_CREATE_VM =
            "com.android.calendar.card.schooltimetable.viewmodel.CreateCourseTableViewModel";

    // 宿主自己的 bundle/extra 键（CourseTableManageListAdapter / SchoolTimeTableActivity 常量值）
    static final String K_BUNDLE = "course_bundle";
    static final String K_EXTENSION_JSON = "extension_json";
    static final String K_COURSE_NAME = "course_name";
    static final String K_COURSE_LIST = "course_list";
    static final String K_IS_IMPORT = "is_import_course";
    static final String K_CREATE_TYPE = "create_type";
    static final String K_COURSE_TIME_ENTITY = "courseTimeEntity";
    static final String K_CALENDAR_ID = "calendar_id";

    private HostTableCreator() {
    }

    // ── 找当前课表页的 ViewModel ─────────────────────────────────────────────

    /**
     * 按**能力**匹配，不按类名（宿主被 R8 改过名）：
     * 有 getCourseJson() 且 getCourseList() 的，就是 ActivitySchoolTimeTableViewModel。
     *
     * 取用顺序：① Activity 的 viewModel 字段（BaseActivity.viewModel，最直接）
     *           ② ViewModelStore 里的实例（mMap，兜底）
     */
    private static Object findTableViewModel(Activity a) {
        Object byField = readFieldDeep(a, "viewModel");
        if (isTableViewModel(byField)) {
            Logx.i(T, "table ViewModel from activity field: " + byField.getClass().getName());
            return byField;
        }
        List<Object> candidates = new ArrayList<>();
        try {
            Object store = invokeNoArg(a, "getViewModelStore");
            Object map = readFieldDeep(store, "mMap");
            if (map instanceof Map) candidates.addAll(((Map<?, ?>) map).values());
        } catch (Throwable t) {
            Logx.w(T, "read ViewModelStore failed: " + t);
        }
        for (Object vm : candidates) {
            if (isTableViewModel(vm)) {
                Logx.i(T, "table ViewModel from store: " + vm.getClass().getName()
                        + " (" + candidates.size() + " candidates)");
                return vm;
            }
        }
        Logx.w(T, "table ViewModel not found among " + candidates.size() + " store candidates"
                + (candidates.isEmpty() ? " (store empty)" : ""));
        return null;
    }

    private static boolean isTableViewModel(Object o) {
        if (o == null) return false;
        boolean hasJson = false, hasList = false;
        for (Method m : o.getClass().getMethods()) {
            if (m.getParameterCount() != 0) continue;
            if ("getCourseJson".equals(m.getName())) hasJson = true;
            else if ("getCourseList".equals(m.getName())) hasList = true;
        }
        return hasJson && hasList;
    }

    // ── 路径 A：反射调 saveCoursesAndTable ───────────────────────────────────

    /**
     * @return 成功返回空串；失败返回原因（调用方据此决定走 B 还是报错，绝不静默写到旧表）
     */
    static String createWithCourses(android.content.Context ctx, TableCtx table, List<Course> courses, String name) {
        if (courses == null || courses.isEmpty()) return "没有要导入的课程";
        Activity act = ctx instanceof Activity ? (Activity) ctx : null;
        String tableJson;
        try {
            Object vm = findTableViewModel(act);
            tableJson = vm == null ? null : (String) callNoArg(vm, "getCourseJson");
            if (tableJson == null || tableJson.isEmpty()) {
                tableJson = buildTableJson(table, name);
                Logx.i(T, "host create table: 用当前表反推的表结构（vm=" + (vm != null) + "）");
            } else {
                Logx.i(T, "host create table: 用宿主当前表的 CourseTableEntity JSON len=" + tableJson.length());
            }
        } catch (Throwable t) {
            Logx.w(T, "read table json failed, fallback to derived: " + t);
            tableJson = buildTableJson(table, name);
        }
        // 关键：宿主 saveCoursesAndTable 会把 courseTableTime 直接塞进 CourseTableEntity，
        // 节次时间表为空时 buildEventByCourseEntity 会**整门课直接 return** —— 表现就是
        // 「表建出来了，但一门课都没有」。所以这份 JSON 必须先补齐 courseTableTime。
        tableJson = ensureCourseTime(tableJson, table);

        try {
            ClassLoader hostCl = ctx.getClassLoader();
            Class<?> cls = Class.forName(CLS_CREATE_VM, false, hostCl);
            Application app = (Application) ctx.getApplicationContext();
            Object cvm = cls.getConstructor(Application.class).newInstance(app);

            // saveCoursesAndTable 的入参只有两个 JSON，但学期基准/周数/是否显示周末/节次表
            // 全是从 ViewModel 的字段上取的 —— 不 set 就是 0 / null / false（实测：表名空、课全丢）。
            JSONObject tbl = new JSONObject(tableJson);
            long termBegin = tbl.optLong("termBeginTime", table.termBeginMs);
            int weekCount = tbl.optInt("weekNumberOfThisTerm", table.weekCount);
            int curWeek = tbl.optInt("currentWeekNumber", currentWeek(table));
            boolean showWeekend = tbl.optBoolean("showWeekend", true);
            boolean showAllWeek = tbl.optBoolean("showAllWeek", false);

            callSetter(cls, cvm, "setCourseName", String.class, name);
            callSetter(cls, cvm, "setSelectedTime", long.class, termBegin);
            callSetter(cls, cvm, "setCurrentNumberOfWeeksSelectedIndex", int.class, curWeek);
            callSetter(cls, cvm, "setMTotalWeeks", int.class, Math.max(weekCount, 1));
            callSetter(cls, cvm, "setShowWeekend", boolean.class, showWeekend);
            callSetter(cls, cvm, "setShowAllWeek", boolean.class, showAllWeek);

            JSONObject timeJson = tbl.optJSONObject("courseTableTime");
            Class<?> timeCls = Class.forName(
                    "com.android.calendar.card.schooltimetable.entity.CourseTimeEntity", false, hostCl);
            Object timeEntity = gsonFromJson(hostCl,
                    (timeJson == null ? buildCourseTime(table) : timeJson).toString(), timeCls);
            cls.getMethod("setCourseTimeEntity", timeCls).invoke(cvm, timeEntity);

            Method save = cls.getMethod("saveCoursesAndTable", String.class, String.class);
            String listJson = coursesJson(table, courses, Math.max(weekCount, table.weekCount));
            save.invoke(cvm, tableJson, listJson);
            Logx.i(T, "host saveCoursesAndTable ok: name=" + name + " courses=" + courses.size()
                    + " term=" + CourseRepo.fmt(termBegin) + " weeks=" + weekCount + " curWeek=" + curWeek
                    + " tableJsonLen=" + tableJson.length() + " listJsonLen=" + listJson.length());
            return "";
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            Logx.e(T, "host saveCoursesAndTable failed", cause);
            return String.valueOf(cause);
        }
    }

    /**
     * 保证 courseTableTime.morningCoursesList / afternoontCoursesList / nightCoursesList
     * 都存在且总长 >= 节次数；不满足就用 TableCtx 反推的节次时间重建。
     */
    static String ensureCourseTime(String tableJson, TableCtx t) {
        try {
            JSONObject o = new JSONObject(tableJson);
            JSONObject ct = o.optJSONObject("courseTableTime");
            if (!courseTimeUsable(ct, t.nodeCount())) {
                o.put("courseTableTime", buildCourseTime(t));
                Logx.w(T, "courseTableTime 不完整/缺失，用反推节次时间补齐（periods=" + t.nodeCount() + "）");
            }
            return o.toString();
        } catch (Throwable e) {
            Logx.w(T, "ensureCourseTime failed: " + e);
            return buildTableJson(t, "");
        }
    }

    private static boolean courseTimeUsable(JSONObject ct, int nodes) {
        if (ct == null) return false;
        int n = 0;
        for (String k : new String[]{"morningCoursesList", "afternoontCoursesList", "nightCoursesList"}) {
            JSONArray arr = ct.optJSONArray(k);
            if (arr == null) return false;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.optJSONObject(i);
                if (item == null) return false;
                if (item.optLong("endTime", 0) <= item.optLong("startTime", 0)) return false;
            }
            n += arr.length();
        }
        return n >= nodes && n > 0;
    }

    // ── 路径 B：交给宿主的「建表 + 导入」页面 ────────────────────────────────

    /**
     * 打开宿主自己的建表页并**预置**课程：宿主会在用户点保存时调
     * saveCoursesAndTable(extension_json, course_list) —— 新课表和课程都由它自己写。
     *
     * @return 成功返回空串；失败返回原因
     */
    static String openImportActivity(Activity a, TableCtx table, List<Course> courses, String name) {
        try {
            String tableJson;
            Object vm = findTableViewModel(a);
            tableJson = vm == null ? null : (String) callNoArg(vm, "getCourseJson");
            if (tableJson == null || tableJson.isEmpty()) tableJson = buildTableJson(table, name);
            tableJson = ensureCourseTime(tableJson, table);

            Bundle b = new Bundle();
            b.putLong(K_CALENDAR_ID, -1L);                 // -1 = 新建
            b.putString(K_EXTENSION_JSON, tableJson);
            b.putString(K_COURSE_TIME_ENTITY, tableJson);
            b.putString(K_COURSE_NAME, name);
            b.putString(K_COURSE_LIST, coursesJson(table, courses, table.weekCount));
            b.putBoolean(K_IS_IMPORT, true);               // 关键：宿主据此走 saveCoursesAndTable
            b.putString(K_CREATE_TYPE, "1");

            Intent it = new Intent();
            it.setClassName(a.getPackageName(), CLS_CREATE_ACTIVITY);
            it.putExtra(K_BUNDLE, b);
            a.startActivity(it);
            Logx.i(T, "open host create-table page with prefilled courses: " + name
                    + " n=" + courses.size() + " tableJsonLen=" + tableJson.length());
            toast(a, "已打开宿主的「新建课表」，课程已预置 " + courses.size() + " 门 —— 点右上角保存即可");
            return "";
        } catch (Throwable t) {
            Logx.e(T, "openImportActivity failed", t);
            return String.valueOf(t);
        }
    }

    /** 只打开建表页（最后兜底：连预置都失败时） */
    static void openBlankCreateTable(Activity a) {
        try {
            Intent it = new Intent();
            it.setClassName(a.getPackageName(), CLS_CREATE_ACTIVITY);
            a.startActivity(it);
            Logx.i(T, "open host create-table page (blank)");
        } catch (Throwable t) {
            Logx.w(T, "open blank create-table failed: " + t);
        }
    }

    // ── 表结构 / 课程 JSON ──────────────────────────────────────────────────

    /** 用 TableCtx 反推出的表结构造一份 CourseTableEntity（宿主字段名，Gson 直接吃） */
    static String buildTableJson(TableCtx t, String name) {
        try {
            JSONObject o = new JSONObject();
            o.put("courseTableTime", buildCourseTime(t));
            o.put("currentWeekNumber", currentWeek(t));
            o.put("hasReminder", false);
            o.put("reminderTime", 0);
            o.put("showAllWeek", false);
            o.put("showWeekend", true);
            o.put("termBeginTime", t.termBeginMs);
            o.put("weekNumberOfThisTerm", Math.max(t.weekCount, 1));
            return o.toString();
        } catch (Throwable e) {
            Logx.e(T, "buildTableJson failed", e);
            return "{}";
        }
    }

    /**
     * CourseTimeEntity：上午/下午/晚上的节数与每节起止。
     * CourseItem 的 startTime/endTime 是**当日毫秒**（宿主 buildEventByCourseEntity 里
     * dtstart = CalendarUtils.e(day, termBegin, week, item.startTime)），所以分钟 × 60000。
     * 分界按时间：<12:00 上午、<18:00 下午、其余晚上。
     */
    private static JSONObject buildCourseTime(TableCtx t) {
        JSONArray morning = new JSONArray(), noon = new JSONArray(), night = new JSONArray();
        for (int i = 0; i < t.nodeCount(); i++) {
            JSONObject item = new JSONObject();
            try {
                item.put("startTime", (long) t.periodStart[i] * 60000L);
                item.put("endTime", (long) t.periodEnd[i] * 60000L);
            } catch (Throwable ignored) {
            }
            int minute = t.periodStart[i];
            if (minute < 12 * 60) morning.put(item);
            else if (minute < 18 * 60) noon.put(item);
            else night.put(item);
        }
        JSONObject o = new JSONObject();
        try {
            o.put("morningCourseNumber", morning.length());
            o.put("afternoonCourseNumber", noon.length());
            o.put("nightCourseNumber", night.length());
            o.put("morningCoursesList", morning);
            // 宿主字段名就是这个拼写（afternoont），照抄，别"修正"
            o.put("afternoontCoursesList", noon);
            o.put("nightCoursesList", night);
            o.put("breakOfCourses", 0);
            o.put("singleCourseTime", 45);
        } catch (Throwable ignored) {
        }
        return o;
    }

    private static int currentWeek(TableCtx t) {
        if (t.termBeginMs <= 0) return 1;
        long d = System.currentTimeMillis() - t.termBeginMs;
        int w = (int) (d / (7L * 86400000L)) + 1;
        return Math.max(1, Math.min(w, Math.max(t.weekCount, 1)));
    }

    /** 课程 → CourseEntity 数组 JSON（宿主用 Gson 反序列化成 List<CourseEntity>） */
    private static String coursesJson(TableCtx table, List<Course> courses, int weekCount) throws Exception {
        JSONArray arr = new JSONArray();
        for (Course c : courses) {
            arr.put(new JSONObject(CourseWriter.toJson(c, weekCount)));
        }
        return arr.toString();
    }

    // ── 反射小工具 ──────────────────────────────────────────────────────────

    private static Object invokeNoArg(Object target, String name) {
        if (target == null) return null;
        try {
            Method m = target.getClass().getMethod(name);
            m.setAccessible(true);
            return m.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object callNoArg(Object target, String name) {
        return invokeNoArg(target, name);
    }

    /** 按精确形参类型调 setter（同名重载多，不能只按参数个数找） */
    private static void callSetter(Class<?> cls, Object target, String name, Class<?> type, Object value)
            throws Exception {
        Method m = cls.getMethod(name, type);
        m.setAccessible(true);
        m.invoke(target, value);
    }

    /** 用宿主 ClassLoader 里的 Gson 反序列化（模块编译期不依赖 gson） */
    private static Object gsonFromJson(ClassLoader hostCl, String json, Class<?> type) throws Exception {
        Class<?> gsonCls = Class.forName("com.google.gson.Gson", false, hostCl);
        Object gson = gsonCls.getConstructor().newInstance();
        Method fromJson = gsonCls.getMethod("fromJson", String.class, Class.class);
        return fromJson.invoke(gson, json, type);
    }

    /** 先按名字读字段，再按类型兜一层（字段名被 R8 改掉时还能抓到） */
    private static Object readFieldDeep(Object target, String name) {
        if (target == null) return null;
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException ignored) {
            } catch (Throwable e) {
                return null;
            }
        }
        return null;
    }

    private static void toast(Activity a, String msg) {
        try {
            android.widget.Toast.makeText(a, msg, android.widget.Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
        }
    }
}
