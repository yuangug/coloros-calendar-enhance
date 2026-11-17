package com.lcodexs.coursealarm;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 课程数据来源（实测：ColorOS 课程表就是系统 CalendarProvider 里的一个日历账号）。
 *
 * 证据：content://com.android.calendar/calendars 里
 *   account_name=oplus_course_default_calendar, displayName=课程表, account_type=LOCAL
 *   其事件 duration=P6000S（两节课），rrule 用 BYWEEKNO 表达上课周次。
 *
 * 因此这里不 hook 宿主的课程 Model，直接对 CalendarProvider 查 Instances（宿主已 hold
 * READ_CALENDAR），宿主改版不会带走这条链路。
 */
final class CourseRepo {
    private static final String T = CourseAlarmEntry.TAG;

    /** 上午作息（实测于课程表页面）：第1节08:10 第2节09:05 第3节10:10 第4节11:05 … */
    private static final int[][] MORNING_PERIODS = {
            {8, 10, 1}, {9, 5, 2}, {10, 10, 3}, {11, 5, 4}
    };

    static final class Slot {
        int hour;
        int minute;
        String title;
        String location;
        int periodFirst;   // 1 或 3（未知时 0）

        String label() {
            return AlarmBridge.LABEL_PREFIX + String.format(Locale.US, "%02d:%02d", hour, minute);
        }

        String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format(Locale.US, "%02d:%02d", hour, minute));
            if (title != null && !title.isEmpty()) sb.append(" · ").append(title);
            if (location != null && !location.isEmpty()) sb.append(" @").append(location);
            return sb.toString();
        }
    }

    private CourseRepo() {
    }

    /** 明天 00:00 的毫秒值 */
    static long tomorrowStart() {
        return dayStart(1);
    }

    /** 第 dayOffset 天（1 = 明天，0 = 今天）00:00 的毫秒值 */
    static long dayStart(int dayOffset) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        c.add(Calendar.DAY_OF_YEAR, dayOffset);
        return c.getTimeInMillis();
    }

    /**
     * 明天上午的**最早一节课**（0 或 1 个元素，一天最多一个闹钟）。
     *
     * 语义（产品定义）：一天只叫一次。
     *   1-2 节有课            → 那一个时刻
     *   只有 3-4 节有课        → 3-4 节的时刻
     *   1-2 和 3-4 节都有课    → **只取 1-2 节**，不再为 3-4 节建第二个闹钟
     */
    static List<Slot> tomorrowMorningSlots(Context ctx) {
        return morningSlots(ctx, 1);
    }

    /** 第 dayOffset 天的上午最早一节课（同样最多一个） */
    static List<Slot> morningSlots(Context ctx, int dayOffset) {
        long dayStart = dayStart(dayOffset);
        return morningSlots(ctx, dayStart, dayStart + 24L * 3600 * 1000);
    }

    private static List<Slot> morningSlots(Context ctx, long begin, long end) {
        List<Slot> out = new ArrayList<>();
        ContentResolver cr = ctx.getContentResolver();
        TableCtx table = TableCtx.load(ctx);
        Set<Long> courseCalIds = new LinkedHashSet<>();
        if (table.calendarId <= 0) return out;
        courseCalIds.add(table.calendarId);
        Logx.i(T, "query instances " + fmt(begin) + " .. " + fmt(end)
                + " courseCalendars=" + courseCalIds);

        Uri.Builder b = CalendarContract.Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(b, begin);
        ContentUris.appendId(b, end);

        String[] proj = new String[]{
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.EVENT_LOCATION,
                CalendarContract.Instances.CALENDAR_ID,
                CalendarContract.Instances.ALL_DAY
        };

        LinkedHashMap<String, Slot> uniq = new LinkedHashMap<>();
        Cursor c = null;
        try {
            c = cr.query(b.build(), proj, null, null, CalendarContract.Instances.BEGIN + " ASC");
            if (c == null) {
                Logx.w(T, "instances cursor null");
                return out;
            }
            int total = 0;
            while (c.moveToNext()) {
                total++;
                long calId = c.getLong(4);
                int allDay = c.getInt(5);
                if (allDay != 0) continue;
                if (!courseCalIds.contains(calId)) continue;

                long startMillis = c.getLong(0);
                Calendar cal = Calendar.getInstance();
                cal.setTimeInMillis(startMillis);
                int h = cal.get(Calendar.HOUR_OF_DAY);
                int m = cal.get(Calendar.MINUTE);
                if (h >= 12) continue;   // 只关心上午：1-2 节 / 3-4 节

                String key = h + ":" + m;
                if (uniq.containsKey(key)) continue;

                Slot s = new Slot();
                s.hour = h;
                s.minute = m;
                s.title = c.getString(2);
                s.location = c.getString(3);
                s.periodFirst = table.nodeByStartMinute(h * 60 + m) + 1;
                uniq.put(key, s);
            }
            Logx.i(T, "instances total=" + total + " morning=" + uniq.size());
        } catch (Throwable t) {
            Logx.e(T, "query instances failed", t);
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignored) { }
        }

        out.addAll(uniq.values());
        return pickEarliest(out);
    }

    /**
     * 一天只留一个：取开始时刻最早的那节。
     *
     * 不依赖 provider 的 ORDER BY —— 显式比 hour*60+minute，排序被忽略时结果也一样。
     * 1-2 节（08:10）与 3-4 节（10:10）都命中时，留下 08:10，10:10 记一条 skip 日志。
     */
    private static List<Slot> pickEarliest(List<Slot> all) {
        if (all.size() <= 1) {
            if (!all.isEmpty()) {
                Slot s = all.get(0);
                Logx.i(T, "pick earliest = " + String.format(Locale.US, "%02d:%02d", s.hour, s.minute)
                        + " (仅一节)");
            }
            return all;
        }
        Slot best = all.get(0);
        for (Slot s : all) {
            if (s.hour * 60 + s.minute < best.hour * 60 + best.minute) best = s;
        }
        StringBuilder skipped = new StringBuilder();
        for (Slot s : all) {
            if (s == best) continue;
            if (skipped.length() > 0) skipped.append(',');
            skipped.append(String.format(Locale.US, "%02d:%02d", s.hour, s.minute));
        }
        List<Slot> one = new ArrayList<>(1);
        one.add(best);
        Logx.i(T, "pick earliest = " + String.format(Locale.US, "%02d:%02d", best.hour, best.minute)
                + "，跳过 " + skipped + "（一天只设一个闹钟）");
        return one;
    }

    /** 课程表日历 id：account_name 含 course，或日历名是「课程表」 */
    private static Set<Long> courseCalendarIds(ContentResolver cr) {
        Set<Long> ids = new LinkedHashSet<>();
        String[] proj = new String[]{
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.ACCOUNT_NAME,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME
        };
        Cursor c = null;
        try {
            c = cr.query(CalendarContract.Calendars.CONTENT_URI, proj, null, null, null);
            if (c == null) return ids;
            while (c.moveToNext()) {
                long id = c.getLong(0);
                String acc = c.getString(1);
                String name = c.getString(2);
                boolean course = (acc != null && (acc.contains("course") || acc.contains("kebiao")))
                        || (name != null && (name.equals("课程表") || name.contains("课程表")));
                if (course) ids.add(id);
            }
        } catch (Throwable t) {
            Logx.e(T, "query calendars failed", t);
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignored) { }
        }
        return ids;
    }

    private static int periodOf(int h, int m) {
        for (int[] p : MORNING_PERIODS) {
            if (p[0] == h && p[1] == m) return p[2];
        }
        return 0;
    }

    /** 时间点 - 提前量 -> 闹钟时刻（跨零点时夹到 00:00） */
    static int[] alarmTimeFor(Slot s, int offsetMin) {
        int total = s.hour * 60 + s.minute - offsetMin;
        if (total < 0) total = 0;
        return new int[]{total / 60, total % 60};
    }

    static String fmt(long millis) {
        return new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new java.util.Date(millis));
    }

    /** 调试/日志用：把计划压成一行 */
    static String dumpPlan(Context ctx) {
        List<Slot> slots = tomorrowMorningSlots(ctx);
        if (slots.isEmpty()) return "明天上午无课";
        StringBuilder sb = new StringBuilder();
        for (Slot s : slots) {
            int[] a = alarmTimeFor(s, Prefs.offset());
            if (sb.length() > 0) sb.append("；");
            sb.append(String.format(Locale.US, "课程 %02d:%02d → 闹钟 %02d:%02d",
                    s.hour, s.minute, a[0], a[1]));
        }
        return sb.toString();
    }

    @SuppressWarnings("unused")
    static Map<String, Slot> debugMap(Context ctx) {
        Map<String, Slot> m = new LinkedHashMap<>();
        for (Slot s : tomorrowMorningSlots(ctx)) m.put(s.describe(), s);
        return m;
    }
}
