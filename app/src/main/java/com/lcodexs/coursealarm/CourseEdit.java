package com.lcodexs.coursealarm;

import android.app.Activity;
import android.content.Intent;
import android.os.Parcelable;

import java.lang.reflect.Method;
import java.util.List;

/**
 * 「课程详情 → 编辑 / 添加」直接开宿主自己的 {@code CreateCourseActivity}（新建/编辑课程页），
 * 参数照抄 {@code SchoolTimeTableActivity#onCreateCourse}：
 *
 *   courseTimeEntity → 课表 JSON（HostTableData.tableJson）
 *   calendar_id      → 课表 id
 *   course_list      → 整表课程 JSON 数组（CreateCourseActivity 用来算冲突）
 *   course_entity    → 被编辑/新建的那门课 JSON
 *   EVENT_ENTITY     → 该课已关联的日历事件（可选，Parcelable）
 *   is_import_course → false（非导入预览）
 *
 * 回来后在 AllInOneActivity#onActivityResult 里收到 {@link #REQ_EDIT}，刷新课表页即可。
 */
final class CourseEdit {
    private static final String T = CourseAlarmEntry.TAG;

    /** 模块自用的 requestCode（不与宿主的 1001 / 模块其它请求冲突） */
    static final int REQ_EDIT = 0x7E10;

    private static final String CLS_CREATE =
            "com.android.calendar.card.schooltimetable.CreateCourseActivity";
    private static final String CLS_ENTITY =
            "com.android.calendar.card.schooltimetable.entity.CourseEntity";
    private static final String CLS_GSON = "com.google.gson.Gson";

    // 与宿主常量同值（SchoolTimeTableActivity / CreateCourseTableFragment / CourseTableManageListAdapter）
    private static final String K_TABLE_JSON = "courseTimeEntity";
    private static final String K_CALENDAR_ID = "calendar_id";
    private static final String K_COURSE_LIST = "course_list";
    private static final String K_COURSE_ENTITY = "course_entity";
    private static final String K_EVENT_ENTITY = "EVENT_ENTITY";
    private static final String K_IMPORT = "is_import_course";

    private CourseEdit() {
    }

    /** 编辑已有课程 */
    static boolean edit(Activity a, ClassLoader host, Object entity) {
        return open(a, host, entity, "编辑");
    }

    /**
     * 添加课程：宿主在详情面板里点「添加」时，会复制被点那格的 星期/起止节次 再开新建页
     * （SchoolTimeTableActivity#showCourseInfoDialog 的 add 分支就是这个语义）。
     */
    static boolean add(Activity a, ClassLoader host, Object source) {
        Object copy = null;
        try {
            Class<?> cls = Ref.cls(host, CLS_ENTITY);
            if (cls != null && source != null) {
                copy = cls.getDeclaredConstructor().newInstance();
                copyInt(source, copy, "getDayOfWeek", "setDayOfWeek");
                copyInt(source, copy, "getStartNode", "setStartNode");
                copyInt(source, copy, "getEndNode", "setEndNode");
                copyInt(source, copy, "getColor", "setColor");
            }
        } catch (Throwable t) {
            Logx.w(T, "CourseEdit: 复制课程模板失败 " + t);
        }
        return open(a, host, copy, "添加");
    }

    private static void copyInt(Object from, Object to, String getter, String setter) {
        Object v = Ref.callName(from, getter);
        if (v instanceof Integer) Ref.callName(to, setter, v);
    }

    private static boolean open(Activity a, ClassLoader host, Object entity, String what) {
        if (a == null) return false;
        HostTableData d = HostTablePage.currentData();
        if (d == null || d.tableJson == null) {
            Logx.w(T, "CourseEdit: 课表数据还没就绪，开不了" + what + "页");
            return false;
        }
        try {
            Class<?> createCls = Ref.cls(host, CLS_CREATE);
            if (createCls == null) {
                Logx.w(T, "CourseEdit: CreateCourseActivity 不存在");
                return false;
            }
            Intent it = new Intent(a, createCls);
            it.putExtra(K_TABLE_JSON, d.tableJson);
            it.putExtra(K_CALENDAR_ID, d.calendarId);
            it.putExtra(K_COURSE_LIST, courseListJson(d));
            it.putExtra(K_IMPORT, false);
            if (entity != null) {
                String json = toJson(host, entity);
                if (json != null) it.putExtra(K_COURSE_ENTITY, json);
                Object ev = Ref.callName(entity, "getEvent");
                if (ev instanceof Parcelable) it.putExtra(K_EVENT_ENTITY, (Parcelable) ev);
            }
            a.startActivityForResult(it, REQ_EDIT);
            Logx.i(T, "CourseEdit: 打开宿主" + what + "课程页 entity=" + (entity != null)
                    + " cal=" + d.calendarId);
            return true;
        } catch (Throwable t) {
            Logx.e(T, "CourseEdit: 打开" + what + "页失败", t);
            return false;
        }
    }

    /** 整表课程 JSON 数组：直接用 HostTableData 已经解析好的每门课 JSON 拼起来 */
    private static String courseListJson(HostTableData d) {
        List<String> list = d.courseJsons;
        if (list == null || list.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(list.get(i));
        }
        return sb.append(']').toString();
    }

    private static String toJson(ClassLoader host, Object entity) {
        try {
            Class<?> gsonCls = Ref.cls(host, CLS_GSON);
            if (gsonCls == null) return null;
            Object gson = gsonCls.getDeclaredConstructor().newInstance();
            Method toJson = Ref.methodByTypes(gsonCls, Object.class);
            if (toJson == null) return null;
            Object s = toJson.invoke(gson, entity);
            return s instanceof String ? (String) s : null;
        } catch (Throwable t) {
            Logx.w(T, "CourseEdit: toJson 失败 " + t);
            return null;
        }
    }
}
