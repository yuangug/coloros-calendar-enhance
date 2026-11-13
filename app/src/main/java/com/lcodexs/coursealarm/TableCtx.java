package com.lcodexs.coursealarm;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 课程表上下文：从宿主**本地**库读出节次时间、开学基准、周数，以及现有课程。
 *
 * 关键实测（v0.2.0 前做的实验）：
 *  - 只往 content://com.android.calendar/events 插事件，课程表**不显示**；
 *  - 课表读的是 content://com.coloros.calendar/events（CalendarContractOPlus.Events.CONTENT_URI），
 *    课程 JSON 在 events_json_extensions，hasExtendedProperties=13（EditType.COURSE）；
 *  - rrule 形如 FREQ=WEEKLY;UNTIL=…Z;INTERVAL=1;BYWEEKNO=39,40,…（教学周即 ISO 周号）。
 */
final class TableCtx {
    private static final String T = CourseAlarmEntry.TAG;

    /**
     * 课程事件的真实位置：**标准** CalendarProvider。
     * 实测：content://com.android.calendar/events 上有 hasExtendedProperties=13 与
     * events_json_extensions；而 content://com.coloros.calendar/events 查询返回 0 行
     * （app 进程内查自己那条 custom provider 拿不到），content://com.coloros.calendar/events
     * 只是 root shell 下的镜像。
     */
    static final Uri EVENTS_URI = CalendarContract.Events.CONTENT_URI;
    /** 兜底：旧版本/未合并时课程走 custom authority */
    static final Uri EVENTS_URI_LEGACY = Uri.parse("content://com.coloros.calendar/events");

    /** 兜底节次时间（分钟/日），实测于本机课表；真实值优先从现有课程推出来 */
    private static final int[] DEFAULT_START = {490, 545, 610, 665, 850, 905, 971, 1026, 1140, 1195};
    private static final int[] DEFAULT_END = {535, 590, 655, 710, 895, 950, 1016, 1071, 1185, 1240};

    long calendarId = -1;
    int weekCount = 18;
    long termBeginMs;
    int[] periodStart = DEFAULT_START.clone();
    int[] periodEnd = DEFAULT_END.clone();
    final List<Course> courses = new ArrayList<>();
    final Set<Long> courseCalIds = new LinkedHashSet<>();

    int nodeCount() {
        return periodStart.length;
    }

    long classMillis(int week, int day, int node) {
        return termBeginMs + (long) (week - 1) * 7L * 86400000L
                + (long) day * 86400000L + (long) periodStart[safeNode(node)] * 60000L;
    }

    int durationSeconds(int startNode, int endNode) {
        int a = safeNode(startNode), b = safeNode(endNode);
        return (periodEnd[b] - periodStart[a]) * 60;
    }

    int safeNode(int n) {
        if (n < 0) return 0;
        return Math.min(n, nodeCount() - 1);
    }

    /** 时间(分钟) → 节次 index，容差 6 分钟 */
    int nodeByStartMinute(int minute) {
        int best = -1, bestDiff = Integer.MAX_VALUE;
        for (int i = 0; i < periodStart.length; i++) {
            int d = Math.abs(periodStart[i] - minute);
            if (d < bestDiff) {
                bestDiff = d;
                best = i;
            }
        }
        return bestDiff <= 6 ? best : -1;
    }

    int nodeByEndMinute(int minute) {
        int best = -1, bestDiff = Integer.MAX_VALUE;
        for (int i = 0; i < periodEnd.length; i++) {
            int d = Math.abs(periodEnd[i] - minute);
            if (d < bestDiff) {
                bestDiff = d;
                best = i;
            }
        }
        return bestDiff <= 6 ? best : -1;
    }

    String dump() {
        return "calendarId=" + calendarId + " weeks=" + weekCount
                + " term=" + CourseRepo.fmt(termBeginMs)
                + " periods=" + periodStart.length + " courses=" + courses.size();
    }

    // ── 读取 ────────────────────────────────────────────────────────────────

    static TableCtx load(Context ctx) {
        TableCtx t = new TableCtx();
        t.hostCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        ContentResolver cr = ctx.getContentResolver();
        t.resolveCalendar(cr);
        t.readCourses(cr);
        t.deriveTiming();
        Logx.i(T, "TableCtx " + t.dump());
        return t;
    }
    /** 课程日历 id：账号含 course / 名字是「课程表」；优先选有课的 */
    private Context hostCtx;   // load(Context) 时带进来，用于读宿主 prefs 里的"当前课表"

    private void resolveCalendar(ContentResolver cr) {
        Cursor c = null;
        try {
            c = cr.query(CalendarContract.Calendars.CONTENT_URI,
                    new String[]{CalendarContract.Calendars._ID,
                            CalendarContract.Calendars.ACCOUNT_NAME,
                            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                            CalendarContract.Calendars.VISIBLE},
                    null, null, null);
            if (c != null) {
                while (c.moveToNext()) {
                    long id = c.getLong(0);
                    String acc = c.getString(1);
                    String nm = c.getString(2);
                    boolean course = (acc != null && (acc.contains("course") || acc.contains("kebiao")))
                            || (nm != null && nm.contains("课程表"));
                    if (course) {
                        courseCalIds.add(id);
                        boolean vis = c.getInt(3) == 1;
                        // 两台「课程表」日历同时存在时（实测 id 6/7），课表页只显示 visible=1 那个
                        if (vis || calendarId < 0) calendarId = id;
                    }
                }
            }
        } catch (Throwable e) {
            Logx.w(T, "resolveCalendar failed: " + e);
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignored) { }
        }
        if (courseCalIds.isEmpty()) courseCalIds.add(7L);
        if (calendarId < 0) calendarId = courseCalIds.iterator().next();

        // 最关键的一步：**以宿主自己记的"当前课表"为准**。
        // 它存在 com.coloros.calendar_preferences 的 sp_key_current_course_table 里（实测 =12）。
        // 不读它就会出现「界面显示 A 表、我们写进 B 表」→ 用户看到导入没反应（实测踩到）。
        long current = hostCurrentTableId(hostCtx);
        if (current > 0) {
            if (!courseCalIds.contains(current)) courseCalIds.add(current);
            calendarId = current;
            Logx.i(T, "current table from host prefs = " + current);
        }
    }

    /** 宿主 prefs 里的"当前课表"id；读不到返回 -1 */
    private static long hostCurrentTableId(Context ctx) {
        if (ctx == null) return -1;
        String[] names = {"com.coloros.calendar_preferences", "com.coloros.calendar_preference"};
        for (String n : names) {
            try {
                android.content.SharedPreferences sp = ctx.getSharedPreferences(n, Context.MODE_PRIVATE);
                long v = sp.getLong("sp_key_current_course_table", -1);
                if (v > 0) return v;
            } catch (Throwable ignored) {
            }
        }
        return -1;
    }

    /**
     * 读课程：**不**给 provider 传 selection —— 实测该 provider 带
     * "calendar_id IN (?,?)" + args 时会把行全过滤掉（返回 0 行），
     * 而不过滤时同样能拿到全部课程。改为在内存里按 calendar_id / 扩展属性筛。
     */
    private void readCourses(ContentResolver cr) {
        Cursor c = null;
        java.util.LinkedHashMap<Long, Integer> perCal = new java.util.LinkedHashMap<>();
        try {
            c = cr.query(EVENTS_URI, new String[]{
                    "_id", "calendar_id", "title", "dtstart", "duration", "rrule",
                    "events_json_extensions", "hasExtendedProperties"}, null, null, null);
            if (c == null) {
                Logx.w(T, "readCourses: null cursor");
                return;
            }
            int total = 0;
            while (c.moveToNext()) {
                total++;
                long calId = c.getLong(1);
                int ext = c.getInt(7);
                String json = c.getString(6);
                if (json == null || json.isEmpty()) continue;
                if (ext != CourseWriter.COURSE_EXT_PROP && !courseCalIds.contains(calId)) continue;
                Course co = parseCourse(json);
                if (co == null || co.name.isEmpty()) continue;
                co.dtstart = c.getLong(3);
                co.durationSec = parseDuration(c.getString(4));
                courses.add(co);
                Integer n = perCal.get(calId);
                perCal.put(calId, n == null ? 1 : n + 1);
            }
            Logx.i(T, "readCourses total=" + total + " courses=" + courses.size()
                    + " perCalendar=" + perCal);
            if (!perCal.isEmpty()) {
                long best = calendarId;
                int bestN = -1;
                for (Map.Entry<Long, Integer> e : perCal.entrySet()) {
                    if (e.getValue() > bestN) { bestN = e.getValue(); best = e.getKey(); }
                }
                calendarId = best;
            }
        } catch (Throwable e) {
            Logx.w(T, "readCourses failed: " + e);
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignored) { }
        }
    }

    static Course parseCourse(String json) {
        try {
            JSONObject o = new JSONObject(json);
            Course c = new Course();
            c.name = o.optString("courseName", "");
            c.teacher = o.optString("teacher", "");
            c.room = o.optString("room", "");
            c.day = o.optInt("dayOfWeek", 0);
            c.startNode = o.optInt("startNode", 0);
            c.endNode = o.optInt("endNode", c.startNode);
            c.color = o.optInt("color", 4);
            JSONArray w = o.optJSONArray("week");
            if (w != null) {
                for (int i = 0; i < w.length(); i++) {
                    if (w.optBoolean(i, false)) c.weeks.set(i + 1);
                }
            }
            if (c.weeks.isEmpty()) c.weeks.set(1);
            return c;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 用现有课程反推：节次起止时间（分钟/日）+ 第1周周一 + 周数。
     * 一条课程 = 首周 day 的第 startNode 节开始，持续到 endNode 结束。
     */
    private void deriveTiming() {
        if (courses.isEmpty()) {
            termBeginMs = mondayOfCurrentWeek();
            return;
        }
        int[] st = new int[periodStart.length];
        int[] en = new int[periodEnd.length];
        for (int i = 0; i < st.length; i++) { st[i] = -1; en[i] = -1; }

        List<Long> termCandidates = new ArrayList<>();
        int maxWeek = 0;

        for (Course co : courses) {
            maxWeek = Math.max(maxWeek, co.lastWeek());
            if (co.dtstart <= 0) continue;

            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(co.dtstart);
            int minute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
            int s = co.startNode, e = co.endNode;
            if (s >= 0 && s < st.length && st[s] <= 0) st[s] = minute;
            if (e >= 0 && e < en.length && en[e] <= 0) {
                en[e] = minute + Math.max(co.durationSec, 5400) / 60;
            }

            long daySize = 86400000L;
            Calendar d0 = Calendar.getInstance();
            d0.setTimeInMillis(co.dtstart);
            d0.set(Calendar.HOUR_OF_DAY, 0);
            d0.set(Calendar.MINUTE, 0);
            d0.set(Calendar.SECOND, 0);
            d0.set(Calendar.MILLISECOND, 0);
            long monday = d0.getTimeInMillis()
                    - (long) co.day * daySize
                    - (long) (co.firstWeek() - 1) * 7L * daySize;
            termCandidates.add(monday);
        }

        for (int i = 0; i < st.length; i++) {
            if (st[i] > 0) periodStart[i] = st[i];
            if (en[i] > 0) periodEnd[i] = en[i];
            if (periodEnd[i] <= periodStart[i]) periodEnd[i] = periodStart[i] + 45;
        }

        termBeginMs = mode(termCandidates);
        if (termBeginMs <= 0) termBeginMs = mondayOfCurrentWeek();
        weekCount = Math.max(maxWeek, 18);
        Logx.i(T, "deriveTiming term=" + CourseRepo.fmt(termBeginMs)
                + " weekCount=" + weekCount
                + " p1=" + periodStart[0] + "-" + periodEnd[0]);
    }

    private static long mode(List<Long> xs) {
        if (xs.isEmpty()) return 0;
        long best = xs.get(0);
        int bestN = 0;
        for (Long a : xs) {
            int n = 0;
            for (Long b : xs) if (Math.abs(a - b) < 3600_000L) n++;
            if (n > bestN) { bestN = n; best = a; }
        }
        return best;
    }

    private static int parseDuration(String d) {
        if (d == null) return 0;
        // P6000S / PT1H40M / P0DT1H40M
        try {
            int total = 0;
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("P(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?").matcher(d);
            if (m.find()) {
                if (m.group(1) != null) total += Integer.parseInt(m.group(1)) * 86400;
                if (m.group(2) != null) total += Integer.parseInt(m.group(2)) * 3600;
                if (m.group(3) != null) total += Integer.parseInt(m.group(3)) * 60;
                if (m.group(4) != null) total += Integer.parseInt(m.group(4));
            }
            return total;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 复制一份只改开学基准的上下文（导入空表时用 ICS 里最早的课当第1周锚点） */
    TableCtx copyWithTerm(long termBegin) {
        TableCtx c = new TableCtx();
        c.calendarId = this.calendarId;
        c.weekCount = this.weekCount;
        c.termBeginMs = termBegin;
        c.periodStart = this.periodStart.clone();
        c.periodEnd = this.periodEnd.clone();
        c.courseCalIds.addAll(this.courseCalIds);
        return c;
    }

    /** 复制一份只换目标日历的上下文（新建课表后写进去） */
    TableCtx copyWithCalendar(long calId) {
        TableCtx c = copyWithTerm(this.termBeginMs);
        c.calendarId = calId;
        c.courseCalIds.clear();
        c.courseCalIds.add(calId);
        return c;
    }

    static long mondayOfCurrentWeek() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        int dow = c.get(Calendar.DAY_OF_WEEK);      // 1=周日
        int back = (dow == Calendar.SUNDAY) ? 6 : dow - Calendar.MONDAY;
        c.add(Calendar.DAY_OF_YEAR, -back);
        return c.getTimeInMillis();
    }
}
