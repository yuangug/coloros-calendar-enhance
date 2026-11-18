package com.lcodexs.coursealarm;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import java.lang.reflect.Method;
import java.util.*;
import io.github.libxposed.api.XposedModule;

/** Keep a reversible local deletion out of the host's cloud queue until its deadline. */
final class CourseSyncGate {
    private static final Object LOCK = new Object();
    private static final Map<String, Set<String>> held = new HashMap<>();
    private static final LinkedHashSet<String> touched = new LinkedHashSet<>();
    private static final ThreadLocal<String> calendarSource = new ThreadLocal<>();
    private static Context app;
    private static boolean ready;

    static void install(XposedModule module, ClassLoader loader, Context context) {
        app = context;
        Class<?> dao = Ref.cls(loader, "com.coloros.calendar.foundation.databasedaolib.dao.SyncDataDao");
        String[] names = {"addEventMetaData", "addCalendarMetadata", "getEventMetaDataForEventEntity", "getMetaDataForCalendarEntity"};
        int[] arities = {4, 3, 5, 4};
        Method[] methods = new Method[names.length];
        for (int i = 0; i < names.length; i++) {
            methods[i] = dao == null ? null : Ref.methodByArity(dao, names[i], arities[i]);
            if (methods[i] == null) {
                Logx.w(CourseAlarmEntry.TAG, "安全撤回不支持此同步版本: " + names[i]);
                return;
            }
        }
        Method backup = Ref.methodOf(dao, "getBackupCalendar", boolean.class);
        Method single = Ref.methodByArity(dao, "buildMetaDataForCalendarEntity", 3);
        if (backup == null || single == null) {
            Logx.w(CourseAlarmEntry.TAG, "安全撤回无法确定课表同步的存储来源");
            return;
        }
        for (Method scope : new Method[]{backup, single}) {
            final int argument = scope == backup ? 0 : 2;
            module.hook(scope).setId("undo_sync_source_" + scope.getName()).intercept(chain -> {
                String previous = calendarSource.get();
                calendarSource.set(Boolean.TRUE.equals(chain.getArg(argument)) ? CalendarBackend.LOCAL : CalendarBackend.MERGED);
                try { return chain.proceed(); }
                finally { if (previous == null) calendarSource.remove(); else calendarSource.set(previous); }
            });
        }
        for (int i = 0; i < methods.length; i++) {
            final String table = i % 2 == 0 ? "events" : "calendars";
            final int cursorArg = i < 2 ? 0 : 2;
            final int localArg = i == 0 ? 3 : i == 2 ? 4 : -1;
            module.hook(methods[i]).setId("undo_sync_" + names[i]).intercept(chain -> {
                synchronized (LOCK) {
                    Object arg = chain.getArg(cursorArg);
                    String authority = localArg < 0 ? calendarSource.get() : Boolean.TRUE.equals(chain.getArg(localArg))
                            ? CalendarBackend.LOCAL : CalendarBackend.MERGED;
                    if (arg instanceof Cursor && defer(authority, table, (Cursor)arg)) return null;
                    return chain.proceed();
                }
            });
        }
        ready = true;
    }

    static boolean available() { return ready; }
    static void hold(String token, String authority, String table, long id) {
        if (!table.equals("events") && !table.equals("calendars")) return;
        synchronized (LOCK) {
            String key = authority + ":" + table + ":" + id;
            held.computeIfAbsent(token, ignored -> new HashSet<>()).add(key);
            touched.add(key);
        }
    }
    static void release(String token) {
        synchronized (LOCK) {
            held.remove(token);
            while (touched.size() > 4096) touched.remove(touched.iterator().next());
        }
    }
    private static boolean defer(String authority, String table, Cursor cursor) {
        if (cursor.isClosed() || cursor.isBeforeFirst() || cursor.isAfterLast()) return false;
        int idColumn = cursor.getColumnIndex("_id");
        if (idColumn < 0) return false;
        long id = cursor.getLong(idColumn);
        if (authority == null) {
            Uri notification = cursor.getNotificationUri();
            if (notification != null && (CalendarBackend.LOCAL.equals(notification.getAuthority())
                    || CalendarBackend.MERGED.equals(notification.getAuthority()))) authority = notification.getAuthority();
        }
        // Unknown cursor provenance must not let one provider's ID read another provider's row.
        // Normal host paths carry calendarSource. Unknown third-party paths are held only
        // during the undo window; they must not permanently suppress future calendar sync.
        if (authority == null) {
            String suffix = ":" + table + ":" + id;
            for (Set<String> keys : held.values()) for (String key : keys) if (key.endsWith(suffix)) return true;
            return false;
        }
        String key = authority + ":" + table + ":" + id;
        for (Set<String> keys : held.values()) if (keys.contains(key)) return true;
        // A sync cursor can outlive the three-second window. Do not upload its stale tombstone
        // after undo has restored the live row. Fresh later user deletions still pass through.
        int deletedColumn = cursor.getColumnIndex("deleted");
        if (!touched.contains(key) || deletedColumn < 0 || cursor.getInt(deletedColumn) == 0) return false;
        try (Cursor now = app.getContentResolver().query(Uri.parse("content://" + authority + "/" + table + "/" + id),
                new String[]{"deleted"}, null, null, null)) {
            return now == null || !now.moveToFirst() || now.getInt(0) == 0;
        } catch (Exception error) {
            Logx.e(CourseAlarmEntry.TAG, "暂缓过期删除同步", error);
            return true;
        }
    }
}
