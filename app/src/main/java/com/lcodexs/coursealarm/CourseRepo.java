package com.lcodexs.coursealarm;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract.Instances;

import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.Locale;

/** First timed course per native timetable session, from the currently selected table only. */
final class CourseRepo {
    record Slot(Instant start, String title, String location) {}

    static final class Sessions {
        private final Slot[][] first = new Slot[2][AlarmPeriod.values().length];
        boolean afternoonTimingKnown = true;

        Slot first(int day, AlarmPeriod period) {
            return first[day][period.ordinal()];
        }

        void offer(int day, AlarmPeriod period, Slot slot) {
            Slot current = first(day, period);
            if (current == null || slot.start().isBefore(current.start())) {
                first[day][period.ordinal()] = slot;
            }
        }
    }

    private CourseRepo() {}

    static Sessions read(Context ctx, LocalDate today, ZoneId zone) throws Exception {
        long currentId = TableCtx.hostCurrentTableId(ctx);
        TableCtx table = TableCtx.loadForAlarm(ctx);
        if (!table.alarmTableResolved()) throw new IllegalStateException("无法读取当前课表");
        Sessions result = new Sessions();
        if (table.calendarId <= 0) return result;
        result.afternoonTimingKnown = table.alarmTimingKnown();
        Instant begin = today.atStartOfDay(zone).toInstant();
        Instant end = today.plusDays(2).atStartOfDay(zone).toInstant();
        Uri.Builder uri = Uri.parse("content://" + table.backendAuthority + "/instances/when").buildUpon();
        ContentUris.appendId(uri, begin.toEpochMilli());
        ContentUris.appendId(uri, end.toEpochMilli());
        String[] projection = {Instances.BEGIN, Instances.TITLE, Instances.EVENT_LOCATION,
                Instances.CALENDAR_ID, Instances.ALL_DAY};
        try (Cursor cursor = ctx.getContentResolver().query(uri.build(), projection, null, null, null)) {
            if (cursor == null) throw new IllegalStateException("无法读取课程安排");
            while (cursor.moveToNext()) {
                if (cursor.getLong(3) != table.calendarId || cursor.getInt(4) != 0) continue;
                Instant start = Instant.ofEpochMilli(cursor.getLong(0));
                if (start.isBefore(begin) || !start.isBefore(end)) continue;
                var time = start.atZone(zone);
                AlarmPeriod period = table.alarmPeriodAt(time.getHour() * 60 + time.getMinute());
                if (period == null) continue;
                int day = time.toLocalDate().equals(today) ? 0 : 1;
                result.offer(day, period, new Slot(start, cursor.getString(1), cursor.getString(2)));
            }
        }
        if (currentId != TableCtx.hostCurrentTableId(ctx)
                || !table.backendAuthority.equals(CalendarBackend.current(ctx).authority)) {
            throw new IllegalStateException("当前课表已切换");
        }
        return result;
    }

    static String fmt(long millis) {
        return new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new Date(millis));
    }
}
