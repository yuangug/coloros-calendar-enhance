package com.lcodexs.coursealarm;

import android.content.ContentValues;
import android.content.Context;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.Objects;
import java.util.TreeSet;
import org.json.JSONArray;
import org.json.JSONObject;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/** Keeps explicitly preserved course clocks from being mistaken for broken native course data. */
final class CourseRescheduleRepair {
    private static final String KEY = "_coursealarm_preserved_time_v1";
    private static final String COURSE = "com.android.calendar.card.schooltimetable.entity.CourseEntity";
    private static final String TABLE = "com.android.calendar.card.schooltimetable.entity.CourseTableEntity";
    private static final String EVENT = "com.coloros.calendar.foundation.databasedaolib.entities.EventEntity";
    private static final String UTILS = "com.android.calendar.card.schooltimetable.ScheduleTableUtils";
    private static final ThreadLocal<Boolean> RECHECK = new ThreadLocal<>();
    private static volatile boolean available;
    private static Method build, repair, cloneEvent;

    private CourseRescheduleRepair() { }

    static void install(XposedModule module, ClassLoader host) {
        available = false;
        try {
            Class<?> course = Class.forName(COURSE, false, host);
            Class<?> table = Class.forName(TABLE, false, host);
            Class<?> event = Class.forName(EVENT, false, host);
            Class<?> utils = Class.forName(UTILS, false, host);
            build = utils.getDeclaredMethod("buildEventByCourseEntity", long.class, table, event, course);
            repair = utils.getDeclaredMethod("isUselessCourse", course, table);
            cloneEvent = event.getMethod("clone");
            if (!Modifier.isStatic(build.getModifiers()) || !Modifier.isStatic(repair.getModifiers())
                    || repair.getReturnType() != boolean.class)
                throw new NoSuchMethodException("课程修复接口签名不兼容");
            course.getMethod("getEvent"); course.getMethod("setEvent", event);
            event.getMethod("getId"); event.getMethod("getCalendarId");
            event.getMethod("getEventsJsonExtensions");
            event.getMethod("getDtstart"); event.getMethod("getDuration");
            event.getMethod("setDtstart", Long.class); event.getMethod("setDuration", String.class);
            build.setAccessible(true); repair.setAccessible(true);
            module.hook(repair).setId("course_preserved_time_repair")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object original = chain.proceed();
                if (!Boolean.TRUE.equals(original) || Boolean.TRUE.equals(RECHECK.get())) return original;
                try {
                    Object courseArg = chain.getArg(0), tableArg = chain.getArg(1);
                    Object copy = repairCopy(courseArg, tableArg);
                    if (copy == null) return original;
                    // Invoke the same host check again. The guard skips only our own adjustment;
                    // native validation and any other hooks still run against private objects.
                    RECHECK.set(true);
                    try { return repair.invoke(null, copy, tableArg); }
                    finally { RECHECK.remove(); }
                } catch (Exception error) {
                    Logx.e(CourseAlarmEntry.TAG, "保留课程时间的修复标记未通过核对", error);
                    return original;
                }
            });
            available = true;
        } catch (Exception error) {
            Logx.e(CourseAlarmEntry.TAG, "此宿主无法安全保留自定义课程时间", error);
        }
    }

    /** Called only after final clocks, recurrence, extension and split identity are ready. */
    static void stamp(ContentValues values, String tableJson) throws Exception {
        Context app = context();
        long calendarId = calendarId(values);
        JSONObject courseJson = new JSONObject(values.getAsString("events_json_extensions"));
        courseJson.remove(KEY);
        values.put("events_json_extensions", courseJson.toString());
        Object course = HostEntities.fromJson(app, COURSE, courseJson.toString());
        Object table = HostEntities.fromJson(app, TABLE, tableJson);
        Object expected = expectedEvent(app, calendarId, course, table);
        Long expectedStart = (Long) required(expected, "getDtstart");
        String expectedDuration = (String) required(expected, "getDuration");
        if (Objects.equals(values.getAsLong("dtstart"), expectedStart)
                && Objects.equals(values.getAsString("duration"), expectedDuration)
                && (expectedDuration != null || Objects.equals(values.getAsLong("dtend"), required(expected, "getDtend")))) return;
        if (!available) throw new IllegalStateException("此日历版本暂不支持安全保留课程原时间，未保存调课");
        if (hasException(values)) throw new IllegalArgumentException("重复例外课程不支持保留时间调课");
        Object calendar = HostEntities.calendar(app, calendarId);
        String actualTable = (String) required(calendar, "getCalendarJsonExtensions");
        if (!config(tableJson).equals(config(actualTable)))
            throw new IllegalStateException("课表配置已变化，请重新调课");
        JSONObject marker = new JSONObject().put("version", 1).put("calendarId", calendarId)
                .put("calendar", calendarIdentity(app, calendarId, calendar))
                .put("table", hash(config(tableJson)))
                .put("event", eventSignature(values, courseJson));
        courseJson.put(KEY, marker);
        values.put("events_json_extensions", courseJson.toString());
    }

    private static Object repairCopy(Object course, Object table) throws Exception {
        if (!available || course == null || table == null) return null;
        Context app = context();
        Object event = required(course, "getEvent");
        if (event == null || ((Number) required(event, "getId")).longValue() <= 0) return null;
        String raw = (String) required(event, "getEventsJsonExtensions");
        if (raw == null || !raw.contains(KEY)) return null;
        JSONObject json = new JSONObject(raw), marker = json.optJSONObject(KEY);
        if (marker == null || marker.optInt("version") != 1) return null;
        ContentValues values = HostEntities.values(app, "Event", event);
        long calendarId = calendarId(values);
        if (marker.optLong("calendarId", -1) != calendarId || hasException(values)) return null;
        if (!marker.optString("event").equals(eventSignature(values, json))) return null;
        Object calendar = HostEntities.calendar(app, calendarId);
        if (!marker.optString("calendar").equals(calendarIdentity(app, calendarId, calendar))) return null;
        String tableJson = (String) required(calendar, "getCalendarJsonExtensions");
        if (!marker.optString("table").equals(hash(config(tableJson)))) return null;
        Object recordedTable = HostEntities.fromJson(app, TABLE, tableJson);
        if (!config(serialized(recordedTable)).equals(config(serialized(table)))) return null;
        Object recordedCourse = HostEntities.fromJson(app, COURSE, raw);
        if (!courseContent(recordedCourse).equals(courseContent(course))) return null;

        // The host builder may normalize week/currentWeek and other fields. Give it its own
        // CourseEntity and preserve the actual input entity for the subsequent native check.
        Object builderCourse = HostEntities.fromJson(app, COURSE, raw);
        Object expected = expectedEvent(app, calendarId, builderCourse, recordedTable);
        Object eventCopy = cloneEvent.invoke(event);
        if (eventCopy == event) return null;
        HostEntities.set(eventCopy, "setDtstart", required(expected, "getDtstart"));
        HostEntities.set(eventCopy, "setDuration", required(expected, "getDuration"));
        Object courseCopy = HostEntities.fromJson(app, COURSE, raw);
        HostEntities.set(courseCopy, "setEvent", eventCopy);
        return courseCopy;
    }

    private static Object expectedEvent(Context app, long calendarId, Object course, Object table) throws Exception {
        Method builder = build;
        if (builder == null) {
            Class<?> utils = Class.forName(UTILS, false, app.getClassLoader());
            builder = utils.getDeclaredMethod("buildEventByCourseEntity", long.class, table.getClass(),
                    Class.forName(EVENT, false, app.getClassLoader()), course.getClass());
            builder.setAccessible(true);
        }
        Object event = HostEntities.newEntity(app, "Event");
        HostEntities.set(event, "setId", 1L); // Avoid new-event reminder defaults; no ID is persisted here.
        HostEntities.set(event, "setEventsJsonExtensions", "{}");
        builder.invoke(null, calendarId, table, event, course);
        Object start = required(event, "getDtstart");
        if (!(start instanceof Number) || ((Number) start).longValue() <= 0)
            throw new IllegalStateException("无法核对原生课程时间");
        return event;
    }

    private static boolean hasException(ContentValues values) {
        for (String key : new String[]{"exdate", "exrule", "rdate", "original_sync_id", "original_event_local_global_id"}) {
            String value = values.getAsString(key); if (value != null && !value.isEmpty()) return true;
        }
        for (String key : new String[]{"original_id", "originalInstanceTime"}) {
            Long value = values.getAsLong(key); if (value != null && value > 0) return true;
        }
        return false;
    }
    private static String eventSignature(ContentValues values, JSONObject course) throws Exception {
        JSONObject identity = new JSONObject();
        // Exclude allocated IDs, provider-generated defaults and transport metadata. A split
        // has no event ID at stamp time; its calendar identity and entire course content bind it.
        for (String key : new String[]{"calendar_id", "dtstart", "duration", "rrule",
                "eventTimezone", "eventEndTimezone", "title", "description", "eventLocation"})
            identity.put(key, Objects.toString(values.getAsString(key), ""));
        // Host LongAdapter reads SQL NULL with Cursor.getLong(), producing 0L.
        // Recurring events normally have NULL dtend; sign the same representation
        // before the write and after the host ORM reads it, retaining real end times.
        Long end = values.getAsLong("dtend");
        identity.put("dtend", end == null ? 0L : end);
        for (String key : new String[]{"allDay", "hasExtendedProperties", "deleted"}) {
            Long number = values.getAsLong(key); identity.put(key, number == null ? 0 : number);
        }
        JSONObject content = new JSONObject(course.toString()); content.remove(KEY); content.remove("sysVersion");
        identity.put("course", content);
        return hash(canonical(identity).toString());
    }
    private static String calendarIdentity(Context app, long id, Object calendar) throws Exception {
        ContentValues values = HostEntities.values(app, "Calendar", calendar);
        JSONObject identity = new JSONObject().put("id", id);
        for (String key : new String[]{"account_name", "account_type", "calendar_local_global_id", "calendar_create_time"})
            identity.put(key, Objects.toString(values.getAsString(key), ""));
        return hash(canonical(identity).toString());
    }
    private static long calendarId(ContentValues values) {
        Long id = values.getAsLong("calendar_id");
        if (id == null || id <= 0) throw new IllegalArgumentException("课程所属课表无效");
        return id;
    }
    private static String config(String raw) throws Exception {
        JSONObject json = new JSONObject(raw), timing = new JSONObject();
        // Reminder switches and presentation settings may change independently of course
        // clocks. Bind only the configuration used by native recurrence/time validation.
        for (String key : new String[]{"termBeginTime", "weekNumberOfThisTerm", "courseTableTime"})
            timing.put(key, json.get(key));
        return canonical(timing).toString();
    }
    private static String courseContent(Object course) throws Exception {
        JSONObject content = new JSONObject(serialized(course));
        // A visible CourseEntity can have display-week clocks and cached drawing state.
        // The event signature separately binds persisted clocks and the complete raw JSON.
        for (String key : new String[]{"event", "currentWeek", "startTime", "endTime", "timeDuration", "timeType",
                "mTop", "mBottom", "mLeft", "mRight", "notThisWeek", "isClassing", "isConflictCourse"})
            content.remove(key);
        return canonical(content).toString();
    }
    private static String serialized(Object value) throws Exception {
        return canonical(new JSONObject(CourseClipboard.snapshot(context().getClassLoader(), value))).toString();
    }
    private static Object required(Object target, String name) throws Exception {
        return target.getClass().getMethod(name).invoke(target);
    }
    private static Context context() {
        if (Hooks.appCtx == null) throw new IllegalStateException("日历上下文未就绪");
        return Hooks.appCtx;
    }
    private static JSONObject canonical(JSONObject value) throws Exception {
        JSONObject result = new JSONObject(); TreeSet<String> keys = new TreeSet<>();
        for (Iterator<String> it = value.keys(); it.hasNext();) keys.add(it.next());
        for (String key : keys) result.put(key, canonicalValue(value.get(key)));
        return result;
    }
    private static Object canonicalValue(Object value) throws Exception {
        if (value instanceof JSONObject) return canonical((JSONObject) value);
        if (value instanceof JSONArray) {
            JSONArray input = (JSONArray) value, output = new JSONArray();
            for (int i = 0; i < input.length(); i++) output.put(canonicalValue(input.get(i)));
            return output;
        }
        return value;
    }
    private static String hash(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte part : digest) result.append(Character.forDigit((part >>> 4) & 15, 16)).append(Character.forDigit(part & 15, 16));
        return result.toString();
    }
}
