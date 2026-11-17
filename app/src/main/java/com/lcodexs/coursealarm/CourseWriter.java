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
        SimpleDateFormat utc = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US);
        utc.setTimeZone(TimeZone.getTimeZone("UTC"));
        sb.append(utc.format(end.getTime()));
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

    // Writes are coordinated by CourseMutations, including the automation entrypoint.

    static String today() {
        return new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
    }
}
