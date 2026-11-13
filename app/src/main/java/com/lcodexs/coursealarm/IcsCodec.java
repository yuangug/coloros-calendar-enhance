package com.lcodexs.coursealarm;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ICS 读写。导出对齐 WakeUp 的产物（用户给的 大二上.ics 就是它导的），
 * 额外写一条 X-LSPX-WEEKS 便于本模块自己无损回灌；导入时没有它也能靠 RRULE 推周次。
 */
final class IcsCodec {
    private static final String T = CourseAlarmEntry.TAG;

    private static final Pattern P_DTSTART = Pattern
            .compile("DTSTART(?:;[^:]*)?:(\\d{8}T\\d{6}Z?|\\d{8})");
    private static final Pattern P_DTEND = Pattern
            .compile("DTEND(?:;[^:]*)?:(\\d{8}T\\d{6}Z?|\\d{8})");
    private static final Pattern P_UNTIL = Pattern.compile("UNTIL=(\\d{8}T\\d{6}Z?)");
    private static final Pattern P_BYWEEKNO = Pattern.compile("BYWEEKNO=([0-9,]+)");

    private IcsCodec() {
    }

    // ── 导出 ────────────────────────────────────────────────────────────────

    static String export(TableCtx ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("BEGIN:VCALENDAR\r\n")
                .append("VERSION:2.0\r\n")
                .append("PRODID:-//lcodexs//CourseAlarm//CN\r\n")
                .append("CALSCALE:GREGORIAN\r\n")
                .append("X-WR-CALNAME:课程表\r\n");

        SimpleDateFormat local = new SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US);
        SimpleDateFormat utc = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US);
        utc.setTimeZone(TimeZone.getTimeZone("UTC"));
        String tz = TimeZone.getDefault().getID();

        for (Course c : ctx.courses) {
            long start = ctx.classMillis(c.firstWeek(), c.day, c.startNode);
            long end = start + (long) ctx.durationSeconds(c.startNode, c.endNode) * 1000L;
            long until = ctx.classMillis(c.lastWeek(), 6, 0) + 86400000L;

            sb.append("BEGIN:VEVENT\r\n");
            sb.append("DTSTAMP:").append(utc.format(new Date())).append("\r\n");
            sb.append("UID:lspx-").append(Math.abs((c.name + c.day + c.startNode
                    + c.firstWeek()).hashCode())).append('-').append(System.nanoTime()).append("\r\n");
            sb.append("SUMMARY:").append(esc(c.name)).append("\r\n");
            sb.append("DTSTART;TZID=").append(tz).append(':').append(local.format(new Date(start))).append("\r\n");
            sb.append("DTEND;TZID=").append(tz).append(':').append(local.format(new Date(end))).append("\r\n");
            sb.append("RRULE:FREQ=WEEKLY;INTERVAL=1;UNTIL=").append(utc.format(new Date(until)))
                    .append(";BYWEEKNO=");
            int[] iso = CourseWriter.isoWeeks(ctx, c);
            for (int i = 0; i < iso.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(iso[i]);
            }
            sb.append("\r\n");
            sb.append("LOCATION:").append(esc(CourseWriter.location(c))).append("\r\n");
            sb.append("DESCRIPTION:").append(esc(CourseWriter.description(c)).replace("\n", "\\n")).append("\r\n");
            sb.append("X-LSPX-WEEKS:").append(c.weeksText()).append("\r\n");
            sb.append("X-LSPX-NODES:").append(c.startNode).append('-').append(c.endNode).append("\r\n");
            sb.append("X-LSPX-DAY:").append(c.day).append("\r\n");
            sb.append("X-LSPX-COLOR:").append(c.color).append("\r\n");
            sb.append("BEGIN:VALARM\r\n")
                    .append("ACTION:DISPLAY\r\n")
                    .append("TRIGGER;RELATED=START:-PT20M\r\n")
                    .append("DESCRIPTION:").append(esc(c.name)).append("\r\n")
                    .append("END:VALARM\r\n");
            sb.append("END:VEVENT\r\n");
        }
        sb.append("END:VCALENDAR\r\n");
        return sb.toString();
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,");
    }

    private static String unesc(String s) {
        if (s == null) return "";
        return s.replace("\\n", "\n").replace("\\N", "\n")
                .replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\");
    }

    // ── 导入 ────────────────────────────────────────────────────────────────

    static List<Course> parse(InputStream in, TableCtx ctx) {
        List<Course> out = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
            String line;
            StringBuilder cur = new StringBuilder();
            while ((line = r.readLine()) != null) {
                if ((line.startsWith(" ") || line.startsWith("\t")) && cur.length() > 0) {
                    cur.append(line.substring(1));
                } else {
                    if (cur.length() > 0) lines.add(cur.toString());
                    cur = new StringBuilder(line);
                }
            }
            if (cur.length() > 0) lines.add(cur.toString());
        } catch (Throwable t) {
            Logx.e(T, "ics read failed", t);
            return out;
        }

        List<List<String>> events = new ArrayList<>();
        List<String> ev = null;
        for (String raw : lines) {
            String l = raw.trim();
            if ("BEGIN:VEVENT".equalsIgnoreCase(l)) {
                ev = new ArrayList<>();
                continue;
            }
            if ("END:VEVENT".equalsIgnoreCase(l)) {
                if (ev != null) events.add(ev);
                ev = null;
                continue;
            }
            if (ev != null) ev.add(raw);
        }

        // 空表导入（第一次导课表）时没有开学基准：用 ICS 里最早的那个 DTSTART 当第1周锚点。
        // WakeUp 导出的 ICS 第一个 VEVENT 就是第1周的上课日期，所以这个锚点是准的。
        TableCtx use = ctx;
        if (ctx != null && ctx.courses.isEmpty()) {
            long min = Long.MAX_VALUE;
            for (List<String> e : events) {
                for (String raw : e) {
                    if (raw.startsWith("DTSTART")) {
                        long t = parseIcsTime(first(P_DTSTART, raw));
                        if (t > 0 && t < min) min = t;
                    }
                }
            }
            if (min != Long.MAX_VALUE) {
                java.util.Calendar c0 = java.util.Calendar.getInstance();
                c0.setTimeInMillis(min);
                c0.set(java.util.Calendar.HOUR_OF_DAY, 0);
                c0.set(java.util.Calendar.MINUTE, 0);
                c0.set(java.util.Calendar.SECOND, 0);
                c0.set(java.util.Calendar.MILLISECOND, 0);
                int dow = c0.get(java.util.Calendar.DAY_OF_WEEK);
                int back = (dow == java.util.Calendar.SUNDAY) ? 6 : dow - java.util.Calendar.MONDAY;
                c0.add(java.util.Calendar.DAY_OF_YEAR, -back);
                use = ctx.copyWithTerm(c0.getTimeInMillis());
                Logx.i(T, "ics: 空表导入，用最早 DTSTART 推开学基准 → " + CourseRepo.fmt(use.termBeginMs));
            }
        }

        for (List<String> e : events) {
            Course c = toCourse(e, use);
            if (c != null) out.add(c);
        }
        Logx.i(T, "ics parsed courses=" + out.size());
        return out;
    }

    private static Course toCourse(List<String> ev, TableCtx ctx) {
        String summary = null, dtstart = null, dtend = null, rrule = null,
                location = null, desc = null, weeks = null, nodes = null, day = null, color = null;
        for (String raw : ev) {
            String l = raw;
            if (l.startsWith("SUMMARY")) summary = value(l);
            else if (l.startsWith("DTSTART")) dtstart = first(P_DTSTART, l);
            else if (l.startsWith("DTEND")) dtend = first(P_DTEND, l);
            else if (l.startsWith("RRULE")) rrule = value(l);
            else if (l.startsWith("LOCATION")) location = value(l);
            else if (l.startsWith("DESCRIPTION")) desc = value(l);
            else if (l.startsWith("X-LSPX-WEEKS")) weeks = value(l);
            else if (l.startsWith("X-LSPX-NODES")) nodes = value(l);
            else if (l.startsWith("X-LSPX-DAY")) day = value(l);
            else if (l.startsWith("X-LSPX-COLOR")) color = value(l);
        }
        if (summary == null || dtstart == null) return null;

        Course c = new Course();
        c.name = unesc(summary).trim();
        long dt = parseIcsTime(dtstart);
        long de = dtend == null ? dt + 6000_000L : parseIcsTime(dtend);
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(dt);
        int dow = cal.get(Calendar.DAY_OF_WEEK);
        c.day = day != null ? safeInt(day, 0) : (dow + 5) % 7;
        int startMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);

        Calendar ce = Calendar.getInstance();
        ce.setTimeInMillis(de);
        int endMin = ce.get(Calendar.HOUR_OF_DAY) * 60 + ce.get(Calendar.MINUTE);

        int s = ctx.nodeByStartMinute(startMin);
        if (s < 0) s = nearestNode(ctx, startMin);
        int e = ctx.nodeByEndMinute(endMin);
        if (e < 0) {
            int span = Math.max(1, Math.round((endMin - startMin) / 45f));
            e = Math.min(s + span - 1, ctx.nodeCount() - 1);
        }
        if (nodes != null) {
            String[] p = nodes.split("-");
            if (p.length == 2) {
                s = safeInt(p[0], s);
                e = safeInt(p[1], e);
            }
        }
        c.startNode = s;
        c.endNode = Math.max(e, s);

        // 周次
        if (weeks != null && !weeks.trim().isEmpty()) {
            applyWeeks(c, weeks.trim());
        } else {
            weeksFromRule(c, dt, rrule, ctx);
        }
        if (c.weeks.isEmpty()) c.weeks.set(1);

        // 地点/教师/节次
        String room = "", teacher = "";
        boolean descFromHost = false;
        if (desc != null) {
            String d = unesc(desc);
            String[] parts = d.split("\n");
            if (parts.length >= 1 && parts[0].matches("第\\s*\\d+\\s*-\\s*\\d+\\s*节")) descFromHost = true;
            if (parts.length >= 2) room = parts[1].trim();
            if (parts.length >= 3) teacher = parts[2].trim();
        }
        if (location != null) {
            String loc = unesc(location).trim();
            if (loc.endsWith(" " + teacher) && !teacher.isEmpty()) loc = loc.substring(0, loc.length() - teacher.length() - 1);
            if (room.isEmpty()) room = loc;
            else if (!loc.isEmpty() && !loc.contains(room)) room = room + " " + loc;
        }
        if (!descFromHost) c.endNode = Math.max(c.startNode, Math.min(c.endNode, c.startNode + 1));
        c.room = room;
        c.teacher = teacher;
        c.color = color != null ? safeInt(color, colorOf(c.name)) : colorOf(c.name);
        return c;
    }

    /** 没有颜色信息时按课程名散列到宿主调色板，避免整张表一个色 */
    static int colorOf(String name) {
        if (name == null || name.isEmpty()) return 4;
        int h = Math.abs(name.hashCode());
        return 1 + (h % 15);
    }

    private static int nearestNode(TableCtx ctx, int minute) {
        int best = 0, bestDiff = Integer.MAX_VALUE;
        for (int i = 0; i < ctx.nodeCount(); i++) {
            int d = Math.abs(ctx.periodStart[i] - minute);
            if (d < bestDiff) { bestDiff = d; best = i; }
        }
        return best;
    }

    private static void applyWeeks(Course c, String spec) {
        for (String seg : spec.split(",")) {
            String s = seg.trim();
            if (s.isEmpty()) continue;
            int dash = s.indexOf('-');
            if (dash > 0) {
                int a = safeInt(s.substring(0, dash), -1);
                int b = safeInt(s.substring(dash + 1), -1);
                if (a > 0 && b >= a) for (int w = a; w <= b; w++) c.weeks.set(w);
            } else {
                int a = safeInt(s, -1);
                if (a > 0) c.weeks.set(a);
            }
        }
    }

    /** 无 X-LSPX-WEEKS 时：按 RRULE 的 UNTIL/BYWEEKNO 推教学周 */
    private static void weeksFromRule(Course c, long dtstart, String rrule, TableCtx ctx) {
        if (rrule == null) {
            c.weeks.set(weekIndex(ctx, dtstart));
            return;
        }
        String until = first(P_UNTIL, rrule);
        long endMs = until == null ? dtstart : parseIcsTime(until);
        if (endMs < dtstart) endMs = dtstart;

        String byWeekNo = first(P_BYWEEKNO, rrule);
        if (byWeekNo != null) {
            Calendar iso = Calendar.getInstance();
            iso.setFirstDayOfWeek(Calendar.MONDAY);
            iso.setMinimalDaysInFirstWeek(4);
            int total = Math.max(ctx.weekCount, 26);
            List<Integer> want = new ArrayList<>();
            for (String s : byWeekNo.split(",")) want.add(safeInt(s.trim(), -1));
            for (int w = 1; w <= total; w++) {
                iso.setTimeInMillis(ctx.classMillis(w, 0, 0));
                int iw = iso.get(Calendar.WEEK_OF_YEAR);
                if (want.contains(iw)) c.weeks.set(w);
            }
            if (!c.weeks.isEmpty()) return;
        }
        for (long t = dtstart; t <= endMs + 86400000L; t += 7L * 86400000L) {
            int w = weekIndex(ctx, t);
            if (w > 0) c.weeks.set(w);
        }
        if (c.weeks.isEmpty()) c.weeks.set(weekIndex(ctx, dtstart));
    }

    /** 时间戳 → 教学周（第1周为 1） */
    static int weekIndex(TableCtx ctx, long t) {
        long diff = t - ctx.termBeginMs;
        int w = (int) Math.floor(diff / (7.0 * 86400000.0)) + 1;
        return Math.max(1, w);
    }

    // ── 时间/字段小工具 ───────────────────────────────────────────────────────

    static long parseIcsTime(String s) {
        try {
            String v = s.trim();
            boolean utc = v.endsWith("Z");
            if (utc) v = v.substring(0, v.length() - 1);
            SimpleDateFormat f;
            if (v.length() == 8) {
                f = new SimpleDateFormat("yyyyMMdd", Locale.US);
            } else {
                f = new SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US);
            }
            if (utc) f.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date d = f.parse(v);
            return d == null ? 0 : d.getTime();
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String value(String line) {
        int i = line.indexOf(':');
        return i < 0 ? "" : line.substring(i + 1);
    }

    private static String first(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private static int safeInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }
}
