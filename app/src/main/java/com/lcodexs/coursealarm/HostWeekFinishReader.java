package com.lcodexs.coursealarm;

import android.database.Cursor;
import android.provider.CalendarContract.Events;
import org.json.JSONArray;
import org.json.JSONObject;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.BitSet;

/** Reads the rows already selected by HostTableData, on its provider worker. */
final class HostWeekFinishReader {
    WeekFinishSchedule schedule;
    private TableCtx periods;
    private final Cursor rows;
    private final int start, end, duration, timezone, exdate, rdate, exrule, status;

    HostWeekFinishReader(HostTableData data, Cursor rows) {
        this.rows = rows;
        start = rows.getColumnIndex(Events.DTSTART); end = rows.getColumnIndex(Events.DTEND);
        duration = rows.getColumnIndex(Events.DURATION); timezone = rows.getColumnIndex(Events.EVENT_TIMEZONE);
        exdate = rows.getColumnIndex(Events.EXDATE); rdate = rows.getColumnIndex(Events.RDATE);
        exrule = rows.getColumnIndex(Events.EXRULE); status = rows.getColumnIndex(Events.STATUS);
        try {
            JSONObject config = new JSONObject(data.tableJson);
            schedule = new WeekFinishSchedule(config.getLong("termBeginTime"),
                    config.getInt("weekNumberOfThisTerm"), ZoneId.systemDefault());
            try { periods = TableCtx.fromMetadata(data.calendarId, data.tableName, data.tableJson); }
            catch (Exception unavailable) { /* Rows with their own event times still work. */ }
        } catch (Exception invalid) { unavailable(invalid); }
    }

    void add(String json) {
        if (schedule == null || status >= 0 && rows.getInt(status) == Events.STATUS_CANCELED) return;
        try {
            if (!string(exrule).isEmpty()) throw new IllegalArgumentException("不支持的课程例外规则");
            JSONObject course = new JSONObject(json);
            JSONArray flags = course.getJSONArray("week");
            BitSet weeks = new BitSet();
            for (int i = 0; i < flags.length(); i++) if (flags.getBoolean(i)) weeks.set(i);
            if (weeks.isEmpty() && string(rdate).isEmpty()) return;
            int day = course.getInt("dayOfWeek");
            long dtstart = millis(start), dtend = millis(end);
            String zoneId = string(timezone);
            ZoneId zone = zoneId.isEmpty() ? schedule.zone : ZoneId.of(zoneId);
            LocalTime clock = dtstart > 0 ? Instant.ofEpochMilli(dtstart).atZone(zone).toLocalTime() : null;
            long length = CourseDuration.parseSeconds(string(duration)) * 1000L;
            if (length <= 0 && dtstart > 0 && dtend > dtstart) length = dtend - dtstart;
            if (clock == null || length <= 0) {
                int a = course.getInt("startNode"), b = course.getInt("endNode");
                if (periods == null || a < 0 || b < a || b >= periods.nodeCount())
                    throw new IllegalArgumentException("课程节次无法确认");
                if (clock == null) clock = LocalTime.ofSecondOfDay(periods.periodStart[a] * 60L);
                if (length <= 0) length = (periods.periodEnd[b] - periods.periodStart[a]) * 60000L;
            }
            schedule.addCourse(weeks, day, clock, zone, dtstart, length, string(exdate), string(rdate));
        } catch (Exception invalid) { unavailable(invalid); }
    }

    private long millis(int column) { return column < 0 || rows.isNull(column) ? 0 : rows.getLong(column); }
    private String string(int column) { return column < 0 || rows.isNull(column) ? "" : rows.getString(column); }
    private void unavailable(Exception error) {
        // A missing row/time must never be interpreted as a free week. The timetable itself still works.
        schedule = null;
        Logx.w(CourseAlarmEntry.TAG, "结束本周课程：无法确认本周安排 (" + error.getClass().getSimpleName() + ")");
    }
}
