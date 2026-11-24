package com.lcodexs.coursealarm;

import android.content.Context;
import android.content.ContentProviderClient;
import android.database.Cursor;
import android.database.ContentObserver;
import android.os.CancellationSignal;
import android.os.SystemClock;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Objects;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;

/** A page snapshot: two provider queries, full host ORM entities, one frozen table identity. */
final class HostTableData {
    private static final String T = CourseAlarmEntry.TAG;
    private static final InFlightReads<ReadKey, HostTableData> READS = new InFlightReads<>(
            Executors.newFixedThreadPool(2, task -> {
                Thread thread = new Thread(task, "course-provider-read"); thread.setDaemon(true); return thread;
            }), Executors.newCachedThreadPool(task -> {
                Thread thread = new Thread(task, "course-provider-cancel"); thread.setDaemon(true); return thread;
            }));
    private static final Set<String> observed = new HashSet<>();
    private static final ContentObserver changes = new ContentObserver(null) {
        @Override public void onChange(boolean selfChange) { READS.invalidate(); }
        @Override public void onChange(boolean selfChange, android.net.Uri uri) { READS.invalidate(); }
    };
    private static final class ReadKey {
        final String packageName, authority;
        final int uid;
        final long tableId;
        final ClassLoader loader;
        final Class<?> resolverType;
        ReadKey(String packageName, int uid, ClassLoader loader, String authority, long tableId, Class<?> resolverType) {
            this.packageName = packageName; this.uid = uid; this.loader = loader;
            this.authority = authority; this.tableId = tableId; this.resolverType = resolverType;
        }
        @Override public int hashCode() { return Objects.hash(packageName, uid, loader, authority, tableId, resolverType); }
        @Override public boolean equals(Object value) {
            if (!(value instanceof ReadKey)) return false;
            ReadKey other = (ReadKey) value;
            return uid == other.uid && tableId == other.tableId && loader == other.loader && resolverType == other.resolverType
                    && packageName.equals(other.packageName) && authority.equals(other.authority);
        }
    }
    String backendAuthority;
    String tableJson;
    final List<String> courseJsons = new ArrayList<>();
    final List<Object> events = new ArrayList<>();
    private final Map<Long, String> eventRows = new TreeMap<>();
    private String calendarRow;
    Object calendar;
    String tableName = "";
    long calendarId = -1L;
    long sourceTableId;
    int weekCount = 1;
    long termBeginMs;
    WeekFinishSchedule weekSchedule;

    int currentWeek() {
        if (termBeginMs <= 0) return 1;
        java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        java.time.LocalDate monday = WeekFinishSchedule.mondayOf(
                java.time.Instant.ofEpochMilli(termBeginMs).atZone(zone).toLocalDate());
        int w = (int) Math.floorDiv(java.time.LocalDate.now(zone).toEpochDay() - monday.toEpochDay(), 7L) + 1;
        return Math.max(1, Math.min(w, Math.max(weekCount, 1)));
    }

    int currentWeekIndex0() { return Math.max(0, currentWeek() - 1); }

    static HostTableData load(Context ctx) throws Exception { return load(ctx, new CancellationSignal()); }

    static HostTableData load(Context ctx, CancellationSignal cancel) throws Exception {
        cancel.throwIfCanceled();
        long selected = currentTableId(ctx);
        CalendarBackend backend = CalendarBackend.current(ctx);
        HostTableData result;
        if (observeChanges(ctx, backend)) {
            CancellationSignal sharedCancel = new CancellationSignal();
            ReadKey key = new ReadKey(ctx.getPackageName(), ctx.getApplicationInfo().uid, ctx.getClassLoader(),
                    backend.authority, selected, ctx.getContentResolver().getClass());
            try {
                result = READS.read(key, () -> readWithProviderRetry(ctx, sharedCancel, selected, backend),
                        sharedCancel::cancel, () -> cancel.isCanceled() || Thread.currentThread().isInterrupted());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new android.os.OperationCanceledException();
            } catch (CancellationException cancelled) { throw new android.os.OperationCanceledException(); }
        } else result = readWithProviderRetry(ctx, cancel, selected, backend);
        cancel.throwIfCanceled();
        backend.requireCurrent(ctx);
        if (selected != currentTableId(ctx)) throw new IllegalStateException("当前课表已切换，请重新读取");
        CourseLiveCoordinator.tableLoaded(result);
        return result;
    }

    private static boolean observeChanges(Context ctx, CalendarBackend backend) {
        String key = ctx.getApplicationInfo().uid + ":" + backend.authority;
        synchronized (observed) {
            if (observed.contains(key)) return true;
            try {
                // No Handler: invalidate on callback delivery, without waiting for a busy UI thread.
                ctx.getContentResolver().registerContentObserver(backend.uri("events"), true, changes);
                ctx.getContentResolver().registerContentObserver(backend.uri("calendars"), true, changes);
                observed.add(key); return true;
            } catch (RuntimeException unavailable) {
                // Without write invalidation, a later caller must not join an older pending read.
                Logx.w(T, "table read sharing unavailable: " + unavailable); return false;
            }
        }
    }

    private static HostTableData readWithProviderRetry(Context ctx, CancellationSignal cancel, long selected,
                                                      CalendarBackend backend) throws Exception {
        try {
            return read(ctx, cancel, selected, backend);
        } catch (android.os.DeadObjectException providerRestarted) {
            cancel.throwIfCanceled();
            backend.requireCurrent(ctx);
            return read(ctx, cancel, selected, backend);
        }
    }

    private static HostTableData read(Context ctx, CancellationSignal cancel, long selected,
                                      CalendarBackend backend) throws Exception {
        long start = SystemClock.elapsedRealtime();
        HostTableData d = new HostTableData();
        d.sourceTableId = selected;
        d.backendAuthority = backend.authority;
        HostEntities.CursorReader reader = new HostEntities.CursorReader(ctx);
        long acquired, metadata;
        try (ContentProviderClient client = ctx.getContentResolver().acquireUnstableContentProviderClient(backend.authority)) {
            if (client == null) throw new IllegalStateException("日历存储不可用，请重试");
            acquired = SystemClock.elapsedRealtime();
            cancel.throwIfCanceled();
            d.readCalendar(client, backend, reader, cancel);
            metadata = SystemClock.elapsedRealtime();
            d.readCourses(client, backend, reader, cancel);
        }
        cancel.throwIfCanceled();
        backend.requireCurrent(ctx);
        Logx.i(T, "table read cal=" + d.calendarId + " courses=" + d.events.size()
                + " connectMs=" + (acquired-start) + " metadataMs=" + (metadata-acquired)
                + " eventsMs=" + (SystemClock.elapsedRealtime()-metadata));
        return d;
    }

    private void readCalendar(ContentProviderClient client, CalendarBackend backend, HostEntities.CursorReader reader,
                              CancellationSignal cancel) throws Exception {
        int candidates = 0;
        String selection = sourceTableId > 0 ? "_id=?" : null;
        String[] args = sourceTableId > 0 ? new String[]{Long.toString(sourceTableId)} : null;
        try (Cursor c = client.query(backend.uri("calendars"), null, selection, args, null, cancel)) {
            if (c == null) throw new IllegalStateException("无法读取课表配置，请重试");
            int id = c.getColumnIndexOrThrow("_id"), deleted = c.getColumnIndexOrThrow("deleted");
            int account = c.getColumnIndexOrThrow("account_name"), name = c.getColumnIndexOrThrow("calendar_displayName");
            int json = c.getColumnIndexOrThrow("calendars_json_extensions");
            while (c.moveToNext()) {
                cancel.throwIfCanceled();
                if (c.getInt(deleted) != 0) continue;
                String acc = c.getString(account), title = c.getString(name);
                boolean course = (acc != null && (acc.contains("course") || acc.contains("kebiao")))
                        || (title != null && title.contains("课程表"));
                if (!course || (sourceTableId > 0 && c.getLong(id) != sourceTableId)) continue;
                candidates++;
                calendarId = c.getLong(id); tableName = title; tableJson = c.getString(json);
                calendar = reader.read(c, "Calendar"); calendarRow = rowKey(c);
                if (sourceTableId > 0) break;
            }
        }
        if (candidates != 1) {
            calendarId = -1; calendar = null; tableJson = null; calendarRow = null;
            return; // Never guess another table when the selected ID is missing.
        }
        JSONObject config = new JSONObject(tableJson);
        termBeginMs = config.optLong("termBeginTime", 0);
        weekCount = Math.max(1, config.optInt("weekNumberOfThisTerm", 18));
    }

    private void readCourses(ContentProviderClient client, CalendarBackend backend, HostEntities.CursorReader reader,
                             CancellationSignal cancel) throws Exception {
        if (calendarId <= 0) return;
        TableCtx legacy = termBeginMs <= 0 ? new TableCtx() : null;
        try (Cursor c = client.query(backend.uri("events"), null, "calendar_id=?",
                new String[]{Long.toString(calendarId)}, null, cancel)) {
            if (c == null) throw new IllegalStateException("无法读取课程，请重试");
            int cal = c.getColumnIndexOrThrow("calendar_id"), json = c.getColumnIndexOrThrow("events_json_extensions");
            int ext = c.getColumnIndexOrThrow("hasExtendedProperties"), id = c.getColumnIndexOrThrow("_id");
            int deleted = c.getColumnIndexOrThrow("deleted");
            HostWeekFinishReader finishWeek = new HostWeekFinishReader(this, c);
            while (c.moveToNext()) {
                cancel.throwIfCanceled();
                if (c.getLong(cal) != calendarId || c.getInt(ext) != CourseWriter.COURSE_EXT_PROP || c.getInt(deleted) != 0) continue;
                String raw = c.getString(json);
                finishWeek.add(raw);
                if (raw == null || raw.isEmpty()) continue;
                events.add(reader.read(c, "Event"));
                courseJsons.add(raw);
                eventRows.put(c.getLong(id), rowKey(c));
                if (legacy != null) {
                    Course course = TableCtx.parseCourse(raw);
                    if (course != null) {
                        course.dtstart = c.getLong(c.getColumnIndexOrThrow("dtstart"));
                        legacy.courses.add(course);
                    }
                }
            }
            weekSchedule = finishWeek.schedule;
        }
        if (legacy != null) {
            legacy.deriveTiming();
            termBeginMs = legacy.termBeginMs;
        }
    }

    private static String rowKey(Cursor cursor) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < cursor.getColumnCount(); i++) {
            String value = cursor.isNull(i) ? null : cursor.getType(i) == Cursor.FIELD_TYPE_BLOB
                    ? android.util.Base64.encodeToString(cursor.getBlob(i), android.util.Base64.NO_WRAP) : cursor.getString(i);
            out.append(value == null ? -1 : value.length()).append(':');
            if (value != null) out.append(value);
        }
        return out.toString();
    }

    boolean sameContents(HostTableData other) {
        return other != null && calendarId == other.calendarId && Objects.equals(backendAuthority, other.backendAuthority)
                && Objects.equals(calendarRow, other.calendarRow) && eventRows.equals(other.eventRows);
    }

    static long currentTableId(Context ctx) { return TableCtx.hostCurrentTableId(ctx); }

    /** Preserve the original complete event; failed reads must never publish a partial table. */
    List<Object> buildCourseList(ClassLoader host) throws Exception {
        List<Object> out = new ArrayList<>();
        Class<?> gsonClass = Class.forName("com.google.gson.Gson", false, host);
        Class<?> courseClass = Class.forName("com.android.calendar.card.schooltimetable.entity.CourseEntity", false, host);
        Class<?> eventClass = Class.forName("com.coloros.calendar.foundation.databasedaolib.entities.EventEntity", false, host);
        Object gson = gsonClass.getConstructor().newInstance();
        java.lang.reflect.Method fromJson = gsonClass.getMethod("fromJson", String.class, Class.class);
        java.lang.reflect.Method setEvent = courseClass.getMethod("setEvent", eventClass);
        for (int i = 0; i < courseJsons.size(); i++) {
            Object course = fromJson.invoke(gson, courseJsons.get(i), courseClass);
            if (course == null) throw new IllegalStateException("课程数据无法解析，请重试");
            setEvent.invoke(course, events.get(i));
            out.add(course);
        }
        return out;
    }
}
