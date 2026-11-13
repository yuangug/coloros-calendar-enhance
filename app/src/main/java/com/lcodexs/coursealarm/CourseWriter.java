package com.lcodexs.coursealarm;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.database.Cursor;
import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 把课程写进宿主本地库（课表真正读的地方）。
 *
 * 实测（v0.2.0）：content://com.coloros.calendar/events + events_json_extensions +
 * hasExtendedProperties=13 才是课表认的课程；只写 com.android.calendar 的标准事件不显示。
 *
 * 写入字段完全照抄宿主自己插的样式（_id=1 的实测行）：
 *   title / dtstart / duration=P6000S / rrule=FREQ=WEEKLY;UNTIL=…Z;INTERVAL=1;BYWEEKNO=…
 *   eventLocation="地点 教师" / description="第1 - 2节\n地点\n教师"
 *   hasExtendedProperties=13 / events_json_extensions={CourseEntity}
 */
final class CourseWriter {
    private static final String T = CourseAlarmEntry.TAG;

    static final int COURSE_EXT_PROP = 13;

    private CourseWriter() {
    }

    // ── 课程 JSON（宿主 CourseEntity 形状） ────────────────────────────────────

    static String toJson(Course c, int weekCount) {
        try {
            JSONObject o = new JSONObject();
            o.put("color", c.color);
            o.put("courseName", c.name);
            o.put("createTime", System.currentTimeMillis());
            o.put("currentWeek", Math.max(c.firstWeek() - 1, 0));
            o.put("dayOfWeek", c.day);
            o.put("endNode", c.endNode);
            o.put("endTime", 0);
            o.put("note", "");
            o.put("room", c.room == null ? "" : c.room);
            o.put("startNode", c.startNode);
            o.put("startTime", 0);
            o.put("teacher", c.teacher == null ? "" : c.teacher);
            o.put("timeDuration", "");
            o.put("timeType", "24");
            JSONArray w = new JSONArray();
            int n = Math.max(weekCount, c.lastWeek());
            for (int i = 1; i <= n; i++) w.put(c.weeks.get(i));
            o.put("week", w);
            o.put("weekType", 0);
            return o.toString();
        } catch (Throwable t) {
            Logx.e(T, "toJson failed", t);
            return "{}";
        }
    }

    // ── rrule ────────────────────────────────────────────────────────────────

    /** 教学周 → ISO 周号（宿主 rrule 里 BYWEEKNO 用的就是 ISO 周号） */
    static int[] isoWeeks(TableCtx ctx, Course c) {
        List<Integer> out = new ArrayList<>();
        Calendar cal = Calendar.getInstance();
        cal.setFirstDayOfWeek(Calendar.MONDAY);
        cal.setMinimalDaysInFirstWeek(4);
        for (int w = 1; w <= Math.max(ctx.weekCount, c.lastWeek()); w++) {
            if (!c.weeks.get(w)) continue;
            cal.setTimeInMillis(ctx.classMillis(w, 0, 0));
            int iso = cal.get(Calendar.WEEK_OF_YEAR);
            if (!out.contains(iso)) out.add(iso);
        }
        int[] a = new int[out.size()];
        for (int i = 0; i < a.length; i++) a[i] = out.get(i);
        return a;
    }

    static String buildRrule(TableCtx ctx, Course c) {
        int[] iso = isoWeeks(ctx, c);
        if (iso.length == 0) return "";
        StringBuilder sb = new StringBuilder("FREQ=WEEKLY;UNTIL=");
        Calendar end = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        end.setTimeInMillis(ctx.classMillis(c.lastWeek(), 6, 0) + 86400000L);
        sb.append(new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).format(end.getTime()));
        sb.append(";INTERVAL=1;BYWEEKNO=");
        for (int i = 0; i < iso.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(iso[i]);
        }
        return sb.toString();
    }

    static String durationString(TableCtx ctx, Course c) {
        return "P" + ctx.durationSeconds(c.startNode, c.endNode) + "S";
    }

    static String description(Course c) {
        return "第" + (c.startNode + 1) + " - " + (c.endNode + 1) + "节\n"
                + safe(c.room) + "\n" + safe(c.teacher);
    }

    static String location(Course c) {
        String l = safe(c.room);
        String t = safe(c.teacher);
        if (l.isEmpty()) return t;
        if (t.isEmpty()) return l;
        return l + " " + t;
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }

    // ── 写入 ────────────────────────────────────────────────────────────────

    /** @return 成功写入条数 */
    static int insert(Context ctx, TableCtx table, List<Course> list) {
        if (list.isEmpty()) return 0;
        ContentResolver cr = ctx.getContentResolver();
        int ok = 0;
        for (Course c : list) {
            try {
                ContentValues cv = new ContentValues();
                cv.put("calendar_id", table.calendarId);
                cv.put("title", c.name);
                cv.put("dtstart", table.classMillis(c.firstWeek(), c.day, c.startNode));
                cv.put("duration", durationString(table, c));
                cv.put("eventTimezone", TimeZone.getDefault().getID());
                cv.put("allDay", 0);
                cv.put("eventLocation", location(c));
                cv.put("description", description(c));
                String rrule = buildRrule(table, c);
                if (!rrule.isEmpty()) cv.put("rrule", rrule);
                cv.put("hasAlarm", 0);
                cv.put("eventStatus", 1);
                cv.put("hasExtendedProperties", COURSE_EXT_PROP);
                cv.put("events_json_extensions", toJson(c, table.weekCount));

                Uri u = insertTolerant(cr, cv);
                Logx.i(T, "insert course " + c + " → " + u
                        + " rrule=" + rrule);
                if (u != null) ok++;
            } catch (Throwable t) {
                Logx.e(T, "insert course failed: " + c, t);
            }
        }
        try {
            cr.notifyChange(TableCtx.EVENTS_URI, null);
            cr.notifyChange(android.provider.CalendarContract.Instances.CONTENT_URI, null);
        } catch (Throwable t) {
            Logx.w(T, "notifyChange failed: " + t);
        }
        Logx.i(T, "insert done: " + ok + "/" + list.size());
        return ok;
    }

    /**
     * 插入时对"当前 ROM 不认识的列"容错：报错信息里带列名就从 ContentValues 里删掉重试。
     * 实测 ColorOS 16 的 CalendarProvider 会拒绝 transparency / availability 这类列。
     */
    private static Uri insertTolerant(ContentResolver cr, ContentValues cv) {
        ContentValues work = new ContentValues(cv);
        for (int attempt = 0; attempt < 6; attempt++) {
            try {
                return cr.insert(TableCtx.EVENTS_URI, work);
            } catch (IllegalArgumentException e) {
                String col = columnFromError(e.getMessage());
                if (col == null || !work.containsKey(col)) {
                    Logx.e(T, "insert rejected (no column parsed): " + e.getMessage(), e);
                    return null;
                }
                Logx.w(T, "insert: dropping unsupported column '" + col + "' and retrying");
                work.remove(col);
            } catch (Throwable e) {
                Logx.e(T, "insert failed: " + e, e);
                return null;
            }
        }
        return null;
    }

    private static String columnFromError(String msg) {
        if (msg == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("column '([A-Za-z_0-9]+)' is invalid").matcher(msg);
        return m.find() ? m.group(1) : null;
    }

    /**
     * 新建一张课表：克隆当前课程日历那一行的所有列，只改名字与可见性。
     * 宿主自己「创建课表」会同时写 CalendarEntity（课表实体），那部分它放在自己的 DB 里，
     * 模块读不到也写不了；克隆日历是最小可用的做法 —— 课程数据有了，表也能在日历里看到。
     *
     * @return 新日历 id，失败 -1
     */
    static long createCalendar(Context ctx, TableCtx table, String displayName) {
        android.content.ContentResolver cr = ctx.getContentResolver();
        Cursor c = null;
        try {
            c = cr.query(android.provider.CalendarContract.Calendars.CONTENT_URI, null,
                    "_id=?", new String[]{String.valueOf(table.calendarId)}, null);
            if (c == null || !c.moveToFirst()) {
                Logx.w(T, "createCalendar: source calendar not found");
                return -1;
            }
            ContentValues cv = new ContentValues();
            for (int i = 0; i < c.getColumnCount(); i++) {
                String col = c.getColumnName(i);
                if (col == null) continue;
                if ("_id".equals(col) || "_sync_id".equals(col) || "calendar_displayName".equals(col)) continue;
                int type = c.getType(i);
                try {
                    switch (type) {
                        case Cursor.FIELD_TYPE_NULL: break;
                        case Cursor.FIELD_TYPE_INTEGER: cv.put(col, c.getLong(i)); break;
                        case Cursor.FIELD_TYPE_FLOAT: cv.put(col, c.getDouble(i)); break;
                        default: cv.put(col, c.getString(i)); break;
                    }
                } catch (Throwable ignored) {
                }
            }
            cv.put("calendar_displayName", displayName);
            cv.put("visible", 1);
            cv.put("sync_events", 1);
            // 插日历必须走 sync-adapter 形式，否则 "Only sync adapters may write to account_name"
            String accName = c.getString(Math.max(0, c.getColumnIndex("account_name")));
            String accType = c.getString(Math.max(0, c.getColumnIndex("account_type")));
            Uri syncUri = android.provider.CalendarContract.Calendars.CONTENT_URI.buildUpon()
                    .appendQueryParameter(android.provider.CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                    .appendQueryParameter(android.provider.CalendarContract.Calendars.ACCOUNT_NAME, accName)
                    .appendQueryParameter(android.provider.CalendarContract.Calendars.ACCOUNT_TYPE, accType)
                    .build();
            Uri u = cr.insert(syncUri, cv);
            Logx.i(T, "createCalendar '" + displayName + "' → " + u);
            if (u == null) return -1;
            long id = ContentUris.parseId(u);
            cr.notifyChange(android.provider.CalendarContract.Calendars.CONTENT_URI, null);
            return id;
        } catch (Throwable t) {
            Logx.e(T, "createCalendar failed", t);
            return -1;
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignored) { }
        }
    }

    /** 清空课表里所有课程（导入前可选用） */    static int clearAll(Context ctx, TableCtx table) {
        try {
            int n = ctx.getContentResolver().delete(TableCtx.EVENTS_URI,
                    "calendar_id=? AND hasExtendedProperties=?",
                    new String[]{String.valueOf(table.calendarId), String.valueOf(COURSE_EXT_PROP)});
            Logx.i(T, "clearAll deleted=" + n);
            return n;
        } catch (Throwable t) {
            Logx.e(T, "clearAll failed", t);
            return 0;
        }
    }

    static String today() {
        return new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
    }
}
