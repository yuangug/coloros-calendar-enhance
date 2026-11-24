package com.lcodexs.coursealarm;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.database.MatrixCursor;
import android.provider.CalendarContract.Events;
import org.json.JSONObject;
import java.io.File;
import java.lang.reflect.Constructor;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** Runs against Android's cursor, JSON and on-disk preference implementations, outside Calendar. */
final class WeekFinishDeviceChecks {
    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final long NONE = WeekFinishSchedule.NONE;
    private static final String[] COLUMNS = {Events.DTSTART, Events.DTEND, Events.DURATION, Events.EVENT_TIMEZONE,
            Events.EXDATE, Events.RDATE, Events.EXRULE, Events.STATUS};

    static void courseRows() throws Exception {
        HostTableData data = new HostTableData();
        data.calendarId = 17;
        data.tableJson = new JSONObject("{\"weekNumberOfThisTerm\":6,\"courseTableTime\":{" +
                "\"morningCoursesList\":[{\"startTime\":29400000,\"endTime\":32100000},{\"startTime\":32700000,\"endTime\":35400000}]," +
                "\"afternoontCoursesList\":[],\"nightCoursesList\":[]}}").put("termBeginTime", at("2026-11-02T00:00")).toString();
        String monday = "{\"dayOfWeek\":0,\"week\":[true,false,true],\"startNode\":0,\"endNode\":1}";
        String friday = "{\"dayOfWeek\":4,\"week\":[true,false,true],\"startNode\":0,\"endNode\":0}";
        try (MatrixCursor cursor = new MatrixCursor(COLUMNS)) {
            cursor.addRow(new Object[]{at("2026-11-02T08:10"),null,"P6000S",ZONE.getId(),null,null,null,1});
            cursor.addRow(new Object[]{at("2026-11-06T17:00"),at("2026-11-06T17:45"),null,ZONE.getId(),"20261106",null,null,1});
            cursor.addRow(new Object[]{null,null,null,null,null,null,null,Events.STATUS_CANCELED});
            HostWeekFinishReader reader = new HostWeekFinishReader(data, cursor);
            cursor.moveToFirst(); reader.add(monday);
            cursor.moveToNext(); reader.add(friday);
            cursor.moveToNext(); reader.add(null); // A cancelled native event is not an unreadable active class.
            long end = at("2026-11-02T09:50");
            require(!reader.schedule.at(end-1,true,NONE).finished(), "includes break within 100-minute course");
            require(reader.schedule.at(end,true,NONE).offer(0), "excluded Friday does not block completion");
            require(reader.schedule.at(at("2026-11-09T00:00"),true,NONE).offer(1), "odd-week course stays grey");
            long fridayEnd = at("2026-11-20T17:45");
            require(!reader.schedule.at(fridayEnd-1,true,NONE).finished()
                    && reader.schedule.at(fridayEnd,true,NONE).offer(2), "native dtend recurrence");
        }
        try (MatrixCursor cursor = new MatrixCursor(COLUMNS)) {
            HostWeekFinishReader reader = new HostWeekFinishReader(data, cursor);
            require(reader.schedule.at(at("2026-11-02T00:00"),true,NONE).offer(0), "valid empty table");
            cursor.addRow(new Object[]{null,null,null,null,null,null,null,1});
            cursor.moveToFirst(); reader.add(monday);
            require(!reader.schedule.at(at("2026-11-02T09:49"),true,NONE).finished()
                    && reader.schedule.at(at("2026-11-02T09:50"),true,NONE).offer(0), "native period end fallback");
            reader.add("{}");
            require(reader.schedule == null, "malformed active course never enables completion");
        }
    }

    static void persistence(File directory) throws Exception {
        File file = File.createTempFile("finish-week-", ".xml", directory);
        if (!file.delete()) throw new IllegalStateException("cannot initialize isolated preferences");
        try {
            Context first = preferences(file);
            String a = "table-local-17", b = "table-merged-17";
            require(TableSettings.finishWeekEnabled(first, a) && TableSettings.finishedWeek(first, a) == NONE, "default on, no automatic advance");
            TableSettings.markFinishedWeek(first, a, 20780L);
            // A following commit waits for Android's pending apply write without using app files.
            require(TableSettings.preferences(first).edit().putInt("fence",1).commit(), "preference flush");
            Context restarted = preferences(file);
            require(TableSettings.finishedWeek(restarted, a) == 20780L && TableSettings.finishedWeek(restarted, b) == NONE, "restart and backend/identity isolation");
            TableSettings.writeFinishWeekSetting(TableSettings.preferences(restarted), a, false);
            Context again = preferences(file);
            require(!TableSettings.finishWeekEnabled(again, a) && TableSettings.finishWeekEnabled(again, b)
                    && TableSettings.finishedWeek(again, a) == NONE, "disable persists and revokes receipt");
            TableSettings.writeFinishWeekSetting(TableSettings.preferences(again), b, false);
            SharedPreferences.Editor edit = TableSettings.preferences(again).edit();
            TableSettings.clearFinishWeek(edit, a);
            require(edit.commit() && TableSettings.finishWeekEnabled(again, a) && !TableSettings.finishWeekEnabled(again, b), "new table resets only its own settings");
        } finally {
            java.nio.file.Files.deleteIfExists(file.toPath());
            java.nio.file.Files.deleteIfExists(new File(file + ".bak").toPath());
        }
    }
    private static Context preferences(File file) throws Exception {
        Constructor<?> ctor = Class.forName("android.app.SharedPreferencesImpl").getDeclaredConstructor(File.class,int.class);
        ctor.setAccessible(true);
        SharedPreferences prefs = (SharedPreferences)ctor.newInstance(file,Context.MODE_PRIVATE);
        return new ContextWrapper(null) {
            @Override public SharedPreferences getSharedPreferences(String name,int mode) { return prefs; }
        };
    }
    private static long at(String local) { return LocalDateTime.parse(local).atZone(ZONE).toInstant().toEpochMilli(); }
    private static void require(boolean value, String text) { if (!value) throw new AssertionError(text); }
}
