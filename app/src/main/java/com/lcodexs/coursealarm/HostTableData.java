package com.lcodexs.coursealarm;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 主页课表页要的两样东西，全部按宿主自己的读取口径取：
 *
 *   1. tableJson —— Calendars 表的 `calendars_json_extensions`（CourseTableEntity：
 *      termBeginTime / weekNumberOfThisTerm / courseTableTime 节次时间 …）
 *   2. courseJson —— Events 表的 `events_json_extensions`（CourseEntity：courseName /
 *      dayOfWeek / startNode / endNode / color / week[Boolean] …）
 *
 * 宿主 queryCourses() 读的就是这两列（CalendarRepository#R / #p → ScheduleTableUtils），
 * 所以这里拿到的是**同一份数据**，TableView 渲染结果自然与宿主课表页一致。
 *
 * 注意：不要给 provider 传 selection（实测会返回 0 行），一律内存里筛。
 */
final class HostTableData {
    private static final String T = CourseAlarmEntry.TAG;

    private static final Uri EVENTS_URI = CalendarContract.Events.CONTENT_URI;
    private static final String COL_CAL_JSON = "calendars_json_extensions";
    private static final String COL_EVENT_JSON = "events_json_extensions";

    /** 课程表（当前课表）的配置 JSON */
    String tableJson;
    /** 一条课程一个 JSON 原文 */
    final List<String> courseJsons = new ArrayList<>();
    /** 课程表名（显示用） */
    String tableName = "";
    long calendarId = -1L;
    int weekCount = 1;
    long termBeginMs;

    private HostTableData() {
    }

    int currentWeek() {
        if (termBeginMs <= 0) return 1;
        long d = System.currentTimeMillis() - termBeginMs;
        int w = (int) (d / (7L * 86400000L)) + 1;
        return Math.max(1, Math.min(w, Math.max(weekCount, 1)));
    }

    int currentWeekIndex0() {
        return Math.max(0, currentWeek() - 1);
    }

    String dump() {
        return "table=" + tableName + " cal=" + calendarId + " weeks=" + weekCount
                + " term=" + CourseRepo.fmt(termBeginMs) + " courses=" + courseJsons.size();
    }

    // ── 读取 ───────────────────────────────────────────────────────────────

    /** 会走 provider 查询，调用方必须在后台线程 */
    static HostTableData load(Context ctx) {
        HostTableData d = new HostTableData();
        ContentResolver cr = ctx.getContentResolver();
        try {
            d.readCalendars(ctx, cr);
            d.readCourses(cr);
        } catch (Throwable t) {
            Logx.e(T, "HostTableData.load failed", t);
        }
        Logx.i(T, "HostTableData " + d.dump());
        return d;
    }

    private void readCalendars(Context ctx, ContentResolver cr) throws Exception {
        long prefer = hostCurrentTableId(ctx);
        long best = -1L;
        String bestJson = null;
        String bestName = "";
        boolean bestCourse = false;
        Cursor c = null;
        try {
            c = cr.query(CalendarContract.Calendars.CONTENT_URI,
                    new String[]{CalendarContract.Calendars._ID,
                            CalendarContract.Calendars.ACCOUNT_NAME,
                            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                            COL_CAL_JSON},
                    null, null, null);
            if (c == null) return;
            while (c.moveToNext()) {
                long id = c.getLong(0);
                String acc = c.getString(1);
                String name = c.getString(2);
                String json = c.getString(3);
                if (json == null || json.isEmpty()) continue;
                boolean isCourse = (acc != null && (acc.contains("course") || acc.contains("kebiao")))
                        || (name != null && name.contains("课程表"));
                // 优先级：宿主 prefs 记的当前课表 > 课程日历里带 json 的那个 > 任意带 json 的
                int rank = id == prefer ? 3 : (isCourse ? 2 : 1);
                boolean better = bestJson == null
                        || rank > (best == prefer ? 3 : (bestCourse ? 2 : 1));
                if (better) {
                    best = id;
                    bestJson = json;
                    bestName = name == null ? "" : name;
                    bestCourse = isCourse;
                }
            }
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignored) { }
        }
        if (bestJson == null) {
            Logx.w(T, "HostTableData: 没有找到带 calendars_json_extensions 的课程日历");
            return;
        }
        calendarId = best;
        tableJson = bestJson;
        tableName = bestName;
        JSONObject o = new JSONObject(bestJson);
        weekCount = Math.max(1, o.optInt("weekNumberOfThisTerm", 1));
        termBeginMs = o.optLong("termBeginTime", 0L);
        Logx.i(T, "HostTableData calendar id=" + best + " name=" + bestName
                + " weeks=" + weekCount + " termBegin=" + CourseRepo.fmt(termBeginMs));
    }

    private void readCourses(ContentResolver cr) {
        Cursor c = null;
        try {
            c = cr.query(EVENTS_URI, new String[]{
                    "calendar_id", COL_EVENT_JSON, "hasExtendedProperties"},
                    null, null, null);
            if (c == null) return;
            int total = 0;
            while (c.moveToNext()) {
                total++;
                long cal = c.getLong(0);
                String json = c.getString(1);
                int ext = c.getInt(2);
                if (json == null || json.isEmpty()) continue;
                // 只收本课表的（calendarId 未知时按课程扩展属性兜底）
                if (calendarId > 0 && cal != calendarId) continue;
                if (calendarId <= 0 && ext != CourseWriter.COURSE_EXT_PROP) continue;
                courseJsons.add(json);
            }
            Logx.i(T, "HostTableData events total=" + total + " kept=" + courseJsons.size());
        } catch (Throwable t) {
            Logx.w(T, "HostTableData readCourses failed: " + t);
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignored) { }
        }
    }

    /** 宿主 prefs 里记的「当前课表」id —— 课表管理里切换课表后这里会变，用来判断要不要重载 */
    static long currentTableId(Context ctx) {
        return hostCurrentTableId(ctx);
    }

    /** 宿主 prefs 里记的「当前课表」id（与 TableCtx 同一来源） */
    private static long hostCurrentTableId(Context ctx) {
        if (ctx == null) return -1;
        for (String n : new String[]{"com.coloros.calendar_preferences", "com.coloros.calendar_preference"}) {
            try {
                long v = ctx.getSharedPreferences(n, Context.MODE_PRIVATE)
                        .getLong("sp_key_current_course_table", -1);
                if (v > 0) return v;
            } catch (Throwable ignored) {
            }
        }
        return -1;
    }

    // ── 造宿主的 CourseEntity 列表 ──────────────────────────────────────────

    /**
     * 用宿主自己的 Gson + CourseEntity/EventEntity 反序列化。
     * TableView 只用「event != null」判断这格有没有课，title 取 event.getTitle()，
     * 所以每门课都补一个只带标题的 EventEntity（宿主自己也是这么兜底的）。
     */
    List<Object> buildCourseList(ClassLoader host) {
        List<Object> out = new ArrayList<>();
        Class<?> gsonCls = Ref.cls(host, "com.google.gson.Gson");
        Class<?> courseCls = Ref.cls(host,
                "com.android.calendar.card.schooltimetable.entity.CourseEntity");
        Class<?> eventCls = Ref.cls(host,
                "com.coloros.calendar.foundation.databasedaolib.entities.EventEntity");
        if (gsonCls == null || courseCls == null || eventCls == null) {
            Logx.w(T, "buildCourseList: 宿主类缺失 gson=" + (gsonCls != null)
                    + " course=" + (courseCls != null) + " event=" + (eventCls != null));
            return out;
        }
        Object gson;
        try {
            gson = gsonCls.getConstructor().newInstance();
        } catch (Throwable t) {
            Logx.e(T, "buildCourseList: new Gson failed", t);
            return out;
        }
        java.lang.reflect.Method fromJson = Ref.methodOf(gsonCls, "fromJson", String.class, Class.class);
        java.lang.reflect.Method setEvent = Ref.methodByArity(courseCls, "setEvent", 1);
        java.lang.reflect.Method getCourseName = Ref.methodByArity(courseCls, "getCourseName", 0);
        java.lang.reflect.Method setTitle = Ref.methodByArity(eventCls, "setTitle", 1);
        java.lang.reflect.Method setDeleted = Ref.methodByArity(eventCls, "setDeleted", 1);
        java.lang.reflect.Constructor<?> eventCtor = Ref.ctorByArity(eventCls, 0);

        for (String json : courseJsons) {
            Object course = null;
            try {
                course = fromJson.invoke(gson, json, courseCls);
            } catch (Throwable t) {
                Logx.w(T, "course json 解析失败: " + t);
            }
            if (course == null) continue;
            try {
                Object name = getCourseName == null ? null : getCourseName.invoke(course);
                Object ev = eventCtor == null ? null : eventCtor.newInstance();
                if (ev != null) {
                    if (setTitle != null) setTitle.invoke(ev, name == null ? "" : String.valueOf(name));
                    if (setDeleted != null) setDeleted.invoke(ev, 0);
                    if (setEvent != null) setEvent.invoke(course, ev);
                }
            } catch (Throwable t) {
                Logx.w(T, "course event 补建失败: " + t);
            }
            out.add(course);
        }
        Logx.i(T, "buildCourseList: " + out.size() + " 门课");
        return out;
    }
}

