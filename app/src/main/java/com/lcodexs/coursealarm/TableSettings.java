package com.lcodexs.coursealarm;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Per-calendar interaction preferences. Calendar data and reminder settings are never written here. */
final class TableSettings {
    private static final String FILE = "lspx_course_table_settings";
    private static final ConcurrentHashMap<Long, State> STATES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, Boolean> LOADING = new ConcurrentHashMap<>();
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor(r -> new Thread(r, "course-table-settings"));

    static final class State {
        final long calendarId;
        final String identity;
        final boolean enabled;

        State(long calendarId, String identity, boolean enabled) {
            this.calendarId = calendarId;
            this.identity = identity;
            this.enabled = enabled;
        }
    }

    private TableSettings() { }

    /** Safe in the touch path: only reads a warmed in-memory value. */
    static boolean enabled(Context context, long calendarId) {
        if (calendarId <= 0) return false;
        State state = STATES.get(calendarId);
        if (state != null) return state.enabled;
        if (LOADING.putIfAbsent(calendarId, Boolean.TRUE) == null) {
            Context app = context.getApplicationContext();
            WORK.execute(() -> {
                try { refresh(app != null ? app : context, calendarId); }
                catch (Exception error) { Logx.w(CourseAlarmEntry.TAG, "课表设置读取失败: " + error); }
                finally { LOADING.remove(calendarId); }
            });
        }
        return false;
    }

    /** Read on a worker before binding the table, and whenever a settings screen resumes. */
    static State refresh(Context context, long calendarId) throws Exception {
        try {
            String identity = identity(context, calendarId);
            State result = new State(calendarId, identity, preferences(context).getBoolean(key(identity), false));
            STATES.put(calendarId, result);
            return result;
        } catch (Exception error) {
            STATES.remove(calendarId);
            throw error;
        }
    }

    static boolean setEnabled(Context context, long calendarId, boolean enabled) throws Exception {
        State previous = STATES.get(calendarId);
        return setEnabled(context, calendarId, previous == null ? null : previous.identity, enabled);
    }

    /** Reuse the full calendar row already read by the page loader. */
    static State refreshLoaded(Context context, long calendarId, Object calendar, boolean local) throws Exception {
        String identity = identity(calendarId, calendar, local);
        State result = new State(calendarId, identity, preferences(context).getBoolean(key(identity), false));
        STATES.put(calendarId, result);
        return result;
    }

    /** A screen must pass the identity captured when it opened, so ID reuse cannot retarget a write. */
    static boolean setEnabled(Context context, long calendarId, String expectedIdentity, boolean enabled) throws Exception {
        State current = refresh(context, calendarId);
        if (expectedIdentity != null && !expectedIdentity.equals(current.identity))
            throw new IllegalStateException("课表已变化，请重新打开课表设置");
        if (!preferences(context).edit().putBoolean(key(current.identity), enabled).commit())
            throw new IllegalStateException("课表设置保存失败，请重试");
        STATES.put(calendarId, new State(calendarId, current.identity, enabled));
        return true;
    }

    /** New-table creation may explicitly reset an otherwise empty legacy identity. */
    static void resetForNewTable(Context context, long calendarId) throws Exception {
        State current = refresh(context, calendarId);
        SharedPreferences.Editor edit = preferences(context).edit().remove(key(current.identity));
        CourseLiveSettings.removeForNewTable(edit, current.identity);
        if (!edit.commit())
            throw new IllegalStateException("新课表设置初始化失败");
        STATES.put(calendarId, new State(calendarId, current.identity, false));
    }

    /** Keep the identity-keyed preference for a possible undo, but never keep a deleted table active. */
    static void invalidate(long calendarId) { STATES.remove(calendarId); }

    static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    private static String key(String identity) { return "drag_" + identity; }

    private static String identity(Context context, long calendarId) throws Exception {
        if (calendarId <= 0) throw new IllegalStateException("请先选择课表");
        Object calendar = HostEntities.calendar(context, calendarId);
        return identity(calendarId, calendar, HostEntities.local(context));
    }

    private static String identity(long calendarId, Object calendar, boolean local) throws Exception {
        Object deleted = calendar.getClass().getMethod("getDeleted").invoke(calendar);
        if (deleted instanceof Number && ((Number) deleted).intValue() != 0)
            throw new IllegalStateException("课表已删除");
        String account = value(calendar, "getAccountName");
        String json = value(calendar, "getCalendarJsonExtensions");
        JSONObject table = new JSONObject(json);
        if (!table.has("courseTableTime") || !table.has("termBeginTime"))
            throw new IllegalStateException("当前日历不是可用的课表");
        // Update time, title and semester configuration are deliberately excluded: ordinary edits
        // must keep the preference. Undo restores these immutable identity fields with the row.
        String raw = new org.json.JSONArray().put(local).put(calendarId)
                .put(account).put(value(calendar, "getAccountType"))
                .put(value(calendar, "getCreateTime")).put(value(calendar, "getLocalGlobalId")).toString();
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder();
        for (byte b : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return result.toString();
    }

    private static String value(Object entity, String getter) throws Exception {
        Object value = entity.getClass().getMethod(getter).invoke(entity);
        return value == null ? "" : String.valueOf(value);
    }
}
