package com.lcodexs.coursealarm;

import android.app.Activity;
import android.content.ContentValues;
import java.util.*;

/** Frozen identity and data, captured on the mutation worker before a drag can commit. */
final class DragSession {
    interface Callback { void done(DragSession session, String error); }
    static final class Entry {
        final long eventId;
        final String json;
        final Course course;
        final ContentValues eventValues;
        final String fingerprint;
        final String childFingerprint;
        Entry(long id, String json, ContentValues values, String fingerprint) throws Exception {
            this(id, json, values, fingerprint, "");
        }
        Entry(long id, String json, ContentValues values, String fingerprint, String childFingerprint) throws Exception {
            this.eventId = id; this.json = json; this.course = CourseClipboard.read(json);
            this.eventValues = new ContentValues(values); this.fingerprint = fingerprint;
            this.childFingerprint = childFingerprint;
        }
    }
    final long calendarId, eventId;
    final int displayedWeek;
    final String tableJson, calendarFingerprint;
    final TableCtx table;
    final Entry source;
    final List<Entry> courses;
    DragSession(TableCtx table, int week, Entry source, List<Entry> courses, String calendarFingerprint) {
        this.table = table; this.calendarId = table.calendarId; this.tableJson = table.tableJson;
        this.eventId = source.eventId; this.displayedWeek = week; this.source = source;
        this.courses = Collections.unmodifiableList(new ArrayList<>(courses));
        this.calendarFingerprint = calendarFingerprint;
    }
    static void capture(Activity activity, Object nativeCourse, int displayedWeek, Callback callback) {
        CourseMutations.captureDrag(activity, nativeCourse, displayedWeek, callback);
    }
    Entry entry(long id) {
        for (Entry entry : courses) if (entry.eventId == id) return entry;
        return null;
    }
    List<Entry> dayCourses(int week, int day) {
        ArrayList<Entry> result = new ArrayList<>();
        if (table.dateOf(week, day).isBefore(table.termDate())) return result;
        for (Entry entry : courses) if (entry.course.day == day && entry.course.weeks.get(week)) result.add(entry);
        result.sort(Comparator.comparingInt(e -> e.course.startNode));
        return result;
    }
}
