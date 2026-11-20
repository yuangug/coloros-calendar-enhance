package com.lcodexs.coursealarm;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.service.notification.StatusBarNotification;
import org.json.JSONArray;
import org.json.JSONObject;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Calendar-process owner. Queries happen on changes, never in animation or minute refresh paths. */
final class CourseLiveCoordinator {
    static final String TICK = "io.github.yuangug.coursealarm.LIVE_TICK";
    static final String DISMISS = "io.github.yuangug.coursealarm.LIVE_DISMISS";
    static final String REVALIDATE = "io.github.yuangug.coursealarm.LIVE_REVALIDATE";
    static final String DND_ENABLE = "io.github.yuangug.coursealarm.LIVE_DND_ENABLE";
    static final String KEY = "lspx.live.key", START = "lspx.live.start", END = "lspx.live.end";
    static final String RECEIVER = "com.android.calendar.alerts.DeleteAlertReceiver";
    private static final String TAG = "tag_normal_remind";
    private static final String JOURNAL = "lspx_course_live_instances_v1";
    private static final HandlerThread THREAD = new HandlerThread("course-live-state", android.os.Process.THREAD_PRIORITY_BACKGROUND);
    private static Handler work;
    private static Context app;
    private static SharedPreferences journal;
    private static volatile Map<String, CourseOccurrence> occurrences = Collections.emptyMap();
    private static volatile CourseLiveSettings.State settings;
    private static volatile boolean loaded;
    private static volatile boolean validated;
    private static final Map<String, Long> dismissed = new ConcurrentHashMap<>();
    private static final Map<String, Posted> posted = new LinkedHashMap<>(); // worker only
    private static final Map<String, NativeAction> nativeActions = new HashMap<>();
    private static LocalDate loadedDate;
    private static long scheduledAt;
    private static int readFailures;
    private static long nextReadAt;
    private static boolean recovering;
    private static JSONArray pendingDnd;
    private static int dndRetries;
    private static final Runnable RETRY_DND = () -> retryDnd();
    private static final List<SharedPreferences> preferenceOwners = new ArrayList<>();
    private static final SharedPreferences.OnSharedPreferenceChangeListener tableListener = (prefs, key) -> {
        if ("sp_key_current_course_table".equals(key)) settingsChanged();
    };
    private static final Runnable RELOAD = () -> reload();
    private static final Runnable REFRESH = () -> refresh();

    private static final class Posted {
        final String key, tag;
        final int id;
        Notification original;
        PendingIntent dnd;
        Boolean dndSessionActive;
        boolean otherDnd;
        String last = "";
        Posted(String key, String tag, int id, Notification original) {
            this.key = key; this.tag = tag; this.id = id; this.original = original;
        }
    }

    private static final class NativeAction {
        PendingIntent intent;
        boolean active;
    }

    static synchronized void init(Context context) {
        if (app != null) return;
        app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        journal = app.getSharedPreferences(JOURNAL, Context.MODE_PRIVATE);
        CourseLiveSeedlingState.init(app);
        THREAD.start(); work = new Handler(THREAD.getLooper());
        work.post(() -> {
            long now = System.currentTimeMillis();
            for (Map.Entry<String, ?> entry : journal.getAll().entrySet()) {
                if (entry.getKey().startsWith("dismiss:") && entry.getValue() instanceof Long
                        && (Long) entry.getValue() > now)
                    dismissed.put(entry.getKey().substring(8), (Long) entry.getValue());
            }
            for (String name : new String[]{"com.coloros.calendar_preferences", "com.coloros.calendar_preference"}) {
                SharedPreferences prefs = app.getSharedPreferences(name, Context.MODE_PRIVATE);
                prefs.registerOnSharedPreferenceChangeListener(tableListener); preferenceOwners.add(prefs);
            }
            ContentObserver observer = new ContentObserver(work) {
                @Override public void onChange(boolean selfChange, Uri uri) { settingsChanged(); }
            };
            for (String authority : new String[]{CalendarBackend.LOCAL, CalendarBackend.MERGED})
                for (String path : new String[]{"events", "calendars"})
                    try { app.getContentResolver().registerContentObserver(Uri.parse("content://" + authority + "/" + path), true, observer); }
                    catch (RuntimeException error) { Logx.w(CourseAlarmEntry.TAG, "live observer: " + error); }
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_TIME_CHANGED); filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            filter.addAction(Intent.ACTION_DATE_CHANGED); filter.addAction(CourseDndClient.CHANGED);
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    if (CourseDndClient.CHANGED.equals(intent.getAction())) {
                        work.post(CourseLiveCoordinator::retryDnd); requestTick();
                    } else settingsChanged();
                }
            };
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            else app.registerReceiver(receiver, filter);
        });
        // The already-required page read normally supplies this snapshot; do not compete with first draw.
        new Handler(android.os.Looper.getMainLooper()).postDelayed(CourseLiveCoordinator::firstReadWhenIdle, 3000);
    }

    private static void firstReadWhenIdle() {
        if (loaded) return;
        if (HostTablePage.loadInFlight())
            new Handler(android.os.Looper.getMainLooper()).postDelayed(CourseLiveCoordinator::firstReadWhenIdle, 500);
        else work.post(RELOAD);
    }

    static void tableLoaded(HostTableData data) {
        if (work == null || android.os.Looper.myLooper() == work.getLooper()) return;
        work.removeCallbacks(RELOAD);
        work.post(() -> {
            try { if (data.sourceTableId == HostTableData.currentTableId(app)) accept(data); }
            catch (Exception error) { readFailed(error); }
        });
    }

    static void settingsChanged() {
        Handler handler = work; if (handler == null) return;
        handler.post(() -> {
            readFailures = 0; nextReadAt = 0;
            handler.removeCallbacks(RELOAD); handler.postDelayed(RELOAD, 500);
        });
    }

    static void requestTick() {
        Handler handler = work; if (handler == null) return;
        handler.removeCallbacks(REFRESH); handler.post(REFRESH);
    }

    static void execute(Runnable action) {
        if (work == null) return;
        if (android.os.Looper.myLooper() == work.getLooper()) action.run(); else work.post(action);
    }

    static CourseLiveSettings.State settings(CourseOccurrence occurrence) {
        CourseLiveSettings.State current = settings;
        return validated && occurrence != null && current != null
                && current.identity.equals(occurrence.tableIdentity) ? current : null;
    }

    static CourseOccurrence find(long eventId, long begin) {
        for (CourseOccurrence item : occurrences.values())
            if (item.eventId == eventId && (begin <= 0 || item.start == begin)
                    && settings(item) != null) return item;
        return null;
    }

    static CourseOccurrence find(String key) { return occurrences.get(key); }
    static boolean isDismissed(CourseOccurrence item) { return item != null && dismissed.containsKey(item.key); }
    static boolean suppressed(CourseOccurrence item) {
        CourseLiveSettings.State state = settings(item);
        return item != null && state != null && (isDismissed(item)
                || System.currentTimeMillis() >= item.expiresAt(state.retainMinutes));
    }

    record Click(CourseOccurrence occurrence, boolean retain) { }

    /** Only receipts from our actual publications may alter the native click lifecycle. */
    static Click handleClick(Intent intent, long eventId, long alarmTime) {
        if (eventId <= 0 || intent == null || intent.getIntExtra("view_type", 0) != 0 || journal == null) return null;
        boolean fluid = "android.intent.action.card.NOTIFICATION_FLUID_CARD".equals(intent.getAction());
        CourseOccurrence fluidItem = fluid ? CourseLiveSeedlingState.captured(intent.getStringExtra("serviceInstanceId")) : null;
        long now = System.currentTimeMillis();
        for (Map.Entry<String, ?> entry : journal.getAll().entrySet()) {
            if (!entry.getKey().startsWith("click:") || !(entry.getValue() instanceof String)) continue;
            try {
                JSONObject receipt = new JSONObject((String) entry.getValue());
                String key = entry.getKey().substring(6);
                if (receipt.getLong("event") != eventId || receipt.getLong("expires") <= now
                        || receipt.optLong("alarm", -1) != alarmTime || journal.contains("dismiss:" + key)) continue;
                if (fluid) { if (fluidItem == null || !fluidItem.key.equals(key)) continue; }
                else if (!receipt.has("backend") || intent.getBooleanExtra("view_from_calendar", false)
                        != CalendarBackend.LOCAL.equals(receipt.getString("backend"))) continue;
                CourseOccurrence item = occurrences.get(key);
                CourseLiveSettings.State state = settings(item);
                if (loaded && (item == null || state == null)) continue;
                if (item == null && receipt.has("identity")) {
                    item = new CourseOccurrence(receipt.getString("backend"), receipt.getString("identity"),
                            receipt.getLong("calendar"), eventId, receipt.getLong("start"), receipt.getLong("end"),
                            alarmTime, "", "", "");
                    if (!key.equals(item.key)) continue;
                }
                if (item == null) item = fluidItem;
                boolean retain = loaded ? state.retainOnClick : receipt.optBoolean("retain");
                if (!retain) dismissKey(key, receipt.getLong("end"));
                return new Click(item, retain);
            } catch (Exception ignored) { }
        }
        return null;
    }
    static void rememberClick(CourseOccurrence item) { rememberClick(item, item == null ? -1 : item.reminderAt); }
    static void rememberClick(CourseOccurrence item, long alarmTime) {
        if (work == null || item == null) return;
        execute(() -> {
            CourseLiveSettings.State state = settings(item); if (state == null) return;
            try {
                String value = new JSONObject().put("event", item.eventId).put("calendar", item.calendarId)
                        .put("backend", item.backend).put("identity", item.tableIdentity).put("alarm", alarmTime)
                        .put("start", item.start).put("end", item.end).put("expires", item.expiresAt(state.retainMinutes))
                        .put("retain", state.retainOnClick).toString();
                journal.edit().putString("click:" + item.key, value).apply();
            } catch (Exception error) { Logx.e(CourseAlarmEntry.TAG, "live publication receipt", error); }
        });
    }

    static void dismiss(CourseOccurrence item) {
        if (item == null || work == null) return;
        dismissKey(item.key, item.end);
    }

    private static void dismissKey(String key, long end) {
        if (key == null || key.isEmpty() || end <= 0 || work == null) return;
        dismissed.put(key, end + 86400000L);
        execute(() -> {
            if (!journal.edit().putLong("dismiss:" + key, end + 86400000L).commit())
                Logx.w(CourseAlarmEntry.TAG, "live dismissal journal commit failed");
            CourseOccurrence item = find(key);
            if (item != null) CourseLiveHooks.closeOccurrence(item);
            CourseLiveSeedlingState.closeKey(key);
            Posted old = posted.remove(key);
            if (old != null) cancelIfOwned(old);
            refresh();
        });
    }

    static void notification(Object info, Notification original) {
        if (work == null || original == null || !Boolean.TRUE.equals(Ref.callName(info, "isCourse"))) return;
        long id = number(info, "getTargetId"), begin = number(info, "getBeginTime");
        // Force alarms retain their native ringing/snooze actions. Their Seedling lifecycle is handled separately.
        if ((number(info, "getAlertFeatures") & 16) != 0) return;
        Notification copy = original.clone();
        work.post(() -> {
            if (!loaded) reload();
            if (!isStillPublished((int) id, copy)) return;
            CourseOccurrence item = find(id, begin);
            if (item == null || (copy.extras.containsKey("lspx.course.local")
                    && copy.extras.getBoolean("lspx.course.local") != CalendarBackend.LOCAL.equals(item.backend))) return;
            CourseLiveSettings.State state = settings(item);
            rememberClick(item, number(info, "getAlarmTime"));
            if (suppressed(item)) {
                if (isStillPublished((int) id, copy)) manager().cancel(TAG, (int) id);
                return;
            }
            if (CourseLiveSeedlingState.hasNotification(item)) {
                // Keep the existing Seedling instance. Removing this duplicate is not a user dismissal.
                if (isStillPublished((int) id, copy)) manager().cancel(TAG, (int) id);
                posted.remove(item.key);
                return;
            }
            if (!state.enabled) return;
            Posted previous = posted.get(item.key);
            Posted record = new Posted(item.key, TAG, (int) id, copy);
            if (previous != null) {
                record.dnd = previous.dnd;
                record.dndSessionActive = previous.dndSessionActive;
            }
            posted.put(item.key, record);
            refresh();
        });
    }

    static Notification retainImmediately(Object info, Notification notification) {
        CourseOccurrence item = find(number(info, "getTargetId"), number(info, "getBeginTime"));
        CourseLiveSettings.State state = settings(item);
        if (notification != null && state != null && state.retainOnClick) notification.flags &= ~Notification.FLAG_AUTO_CANCEL;
        return notification;
    }

    static void receive(BroadcastReceiver receiver, Intent intent) {
        BroadcastReceiver.PendingResult pending = receiver.goAsync();
        work.post(() -> {
            try {
                if (DISMISS.equals(intent.getAction())) {
                    String key = intent.getStringExtra(KEY);
                    long end = intent.getLongExtra(END, 0);
                    if (end <= 0 && key != null) try {
                        end = new JSONObject(journal.getString("click:" + key, "{}")).optLong("end", 0);
                    } catch (Exception ignored) { }
                    dismissKey(key, end);
                    PendingIntent original = intent.getParcelableExtra("originalDelete");
                    if (original != null) try { original.send(); } catch (PendingIntent.CanceledException ignored) { }
                } else {
                    if (REVALIDATE.equals(intent.getAction()) || DND_ENABLE.equals(intent.getAction())
                            || !loaded || !LocalDate.now().equals(loadedDate)) reload();
                    if (DND_ENABLE.equals(intent.getAction())) {
                        CourseOccurrence item = find(intent.getStringExtra(KEY));
                        Bundle result = item == null || settings(item) == null ? Bundle.EMPTY
                                : CourseDndClient.enable(app, item.key, item.start, item.end);
                        String message = result.getBoolean("success") && result.getBoolean("sessionActive")
                                ? "已开启本次课程免打扰，下课时自动结束"
                                : result.getString("error", "课程已变化，未开启免打扰");
                        new Handler(android.os.Looper.getMainLooper()).post(() ->
                                android.widget.Toast.makeText(app, message, android.widget.Toast.LENGTH_LONG).show());
                    }
                    refresh();
                }
            } finally { if (pending != null) pending.finish(); }
        });
    }

    static boolean acceptsRevalidation(Intent intent) {
        if (journal == null || intent == null) return false;
        String token = intent.getStringExtra("token"), expected = journal.getString("revalidateToken", null);
        return token != null && expected != null && java.security.MessageDigest.isEqual(
                token.getBytes(java.nio.charset.StandardCharsets.UTF_8), expected.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static void syncDnd(JSONArray schedule) {
        String token = journal.getString("revalidateToken", null);
        if (token == null || !token.matches("[0-9a-f]{64}")) {
            byte[] random = new byte[32]; new java.security.SecureRandom().nextBytes(random);
            StringBuilder hex = new StringBuilder(64);
            for (byte value : random) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            token = hex.toString();
            if (!journal.edit().putString("revalidateToken", token).commit()) return;
        }
        pendingDnd = schedule; dndRetries = 0; retryDnd();
    }

    private static void retryDnd() {
        if (pendingDnd == null || !validated) return;
        work.removeCallbacks(RETRY_DND);
        Bundle result = CourseDndClient.sync(app, pendingDnd, journal.getString("revalidateToken", null));
        if (result.getBoolean("success")) {
            pendingDnd = null; dndRetries = 0;
            for (Posted record : posted.values()) { record.dnd = null; record.last = ""; }
        }
        else if (++dndRetries < 3) work.postDelayed(RETRY_DND, 30000);
    }

    private static PendingIntent dndContinuation(CourseOccurrence item) {
        Intent intent = new Intent(DND_ENABLE).setClassName(app, CourseDndClient.REVALIDATE_RECEIVER)
                .setData(Uri.parse("coursealarm://dnd-enable/" + Uri.encode(item.key)))
                .putExtra(KEY, item.key).putExtra("token", journal.getString("revalidateToken", null));
        return PendingIntent.getBroadcast(app, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static Bundle stateBundle(long eventId, long begin) {
        Bundle result = new Bundle(); result.putInt("protocol", 1);
        CourseOccurrence item = find(eventId, begin);
        CourseLiveSettings.State state = settings(item);
        result.putBoolean("supported", item != null && state != null);
        if (item != null && state != null) {
            long now = System.currentTimeMillis();
            result.putString("key", item.key); result.putLong("start", item.start); result.putLong("end", item.end);
            result.putLong("expiresAt", item.expiresAt(state.retainMinutes));
            result.putInt("progress", item.progress(now)); result.putBoolean("showProgress", state.showProgress);
            result.putString("phase", item.phase(now, state.retainMinutes, isDismissed(item)).name());
        }
        return result;
    }

    static Bundle nativeCardState(CourseOccurrence item, String service, boolean includeAction) {
        Bundle result = new Bundle(); result.putInt("protocol", 1);
        CourseLiveSettings.State state = settings(item);
        if (state == null || !state.enabled || suppressed(item)) { result.putBoolean("supported", false); return result; }
        long now = System.currentTimeMillis();
        result.putBoolean("supported", true); result.putString("serviceInstanceId", service);
        result.putString("key", item.key); result.putLong("start", item.start); result.putLong("end", item.end);
        result.putLong("expires", item.expiresAt(state.retainMinutes));
        result.putString("title", item.title); result.putString("room", item.room); result.putString("teacher", item.teacher);
        result.putString("status", item.timing(now)); result.putBoolean("progressEnabled", state.showProgress);
        boolean showDnd = includeAction && state.showDndButton && now < item.end;
        result.putBoolean("dndEnabled", showDnd);
        if (showDnd) {
            Bundle status = CourseDndClient.status(app, item.key);
            boolean active = status.getBoolean("sessionActive");
            result.putBoolean("dndActive", active);
            result.putBoolean("otherDnd", status.getBoolean("systemActive") && !status.getBoolean("ownedActive"));
            NativeAction action = nativeActions.get(item.key);
            if (action == null || action.intent == null || action.active != active) {
                action = new NativeAction(); action.active = active;
                action.intent = CourseDndClient.action(app, item.key, item.start, item.end, active, dndContinuation(item));
                nativeActions.put(item.key, action);
            }
            if (action.intent != null) result.putParcelable("dndAction", action.intent);
        }
        return result;
    }

    private static void reload() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now < nextReadAt) { cleanupDeadline(); return; }
        if (HostTablePage.loadInFlight()) {
            work.removeCallbacks(RELOAD); work.postDelayed(RELOAD, 500); return;
        }
        try {
            if (HostTableData.currentTableId(app) <= 0) { clear(); return; }
            accept(HostTableData.load(app));
        } catch (Exception error) {
            readFailed(error);
        }
    }

    private static void readFailed(Exception error) {
        Logx.e(CourseAlarmEntry.TAG, "live reconcile failed; no stale schedule activated", error);
        validated = false; pendingDnd = null; work.removeCallbacks(RETRY_DND);
        CourseDndClient.invalidate(app);
        loaded = true;
        work.removeCallbacks(RELOAD);
        nextReadAt = ++readFailures < 3 ? android.os.SystemClock.elapsedRealtime() + 30000 : Long.MAX_VALUE;
        if (readFailures < 3) work.postDelayed(RELOAD, 30000);
        cleanupDeadline();
    }

    private static void cleanupDeadline() {
        CourseLiveSeedlingState.cleanupOnly();
        setAlarm(CourseLiveSeedlingState.nextExpiry());
    }

    private static void clear() {
        settings = null; occurrences = Collections.emptyMap(); loaded = true; validated = true; loadedDate = LocalDate.now();
        readFailures = 0; nextReadAt = 0; nativeActions.clear();
        CourseLiveSeedlingState.restore();
        syncDnd(new JSONArray()); refresh();
    }

    private static void accept(HostTableData data) throws Exception {
        if (data.calendarId <= 0 || data.calendar == null) { clear(); return; }
        CourseLiveSettings.State current = CourseLiveSettings.readLoaded(app, data.calendarId, data.calendar,
                CalendarBackend.LOCAL.equals(data.backendAuthority));
        JSONObject metadata = new JSONObject(data.tableJson);
        LocalDate termDate = Instant.ofEpochMilli(metadata.getLong("termBeginTime")).atZone(ZoneId.systemDefault()).toLocalDate();
        LocalDate monday = termDate.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
        int weekCount = metadata.getInt("weekNumberOfThisTerm");
        if (weekCount < 1 || weekCount > 104) throw new IllegalArgumentException("学期周数无效");
        TableCtx table = null;
        try { table = TableCtx.fromMetadata(data.calendarId, data.tableName, data.tableJson); }
        catch (IllegalArgumentException crossMidnight) { /* Real event timestamps still support a course crossing midnight. */ }
        int reminderMinutes = metadata.optInt("reminderTime", 15);
        if (reminderMinutes < 5 || reminderMinutes > 60 || reminderMinutes % 5 != 0) reminderMinutes = 15;
        boolean hasReminder = metadata.optBoolean("hasReminder");
        ZoneId zone = ZoneId.systemDefault(); LocalDate today = LocalDate.now(zone);
        Map<String, CourseOccurrence> next = new LinkedHashMap<>();
        for (int index = 0; index < data.events.size(); index++) {
            Course course = TableCtx.parseCourse(data.courseJsons.get(index));
            if (course == null || course.startNode < 0 || course.endNode < course.startNode) continue;
            Object event = data.events.get(index);
            long eventId = number(event, "getId");
            if (eventId <= 0) continue;
            long eventStart = number(event, "getDtstart"), eventEnd = number(event, "getDtend");
            Object durationValue = Ref.callName(event, "getDuration");
            long duration = durationValue instanceof String ? CourseDuration.parseSeconds((String) durationValue) * 1000L : 0;
            if (duration <= 0 && eventEnd > eventStart && eventStart > 0) duration = eventEnd - eventStart;
            ZoneId eventZone = zone;
            Object timezone = Ref.callName(event, "getEventTimezone");
            if (timezone instanceof String && !((String) timezone).isEmpty())
                try { eventZone = ZoneId.of((String) timezone); } catch (RuntimeException ignored) { }
            java.time.LocalTime startTime = eventStart > 0 ? Instant.ofEpochMilli(eventStart).atZone(eventZone).toLocalTime() : null;
            if (startTime == null && table != null && course.startNode < table.nodeCount())
                startTime = java.time.LocalTime.MIDNIGHT.plusMinutes(table.periodStart[course.startNode]);
            if (duration <= 0 && table != null && course.endNode < table.nodeCount())
                duration = (table.periodEnd[course.endNode] - table.periodStart[course.startNode]) * 60000L;
            if (startTime == null || duration <= 0 || duration > 2L * 86400000L) continue;
            for (int offset = -1; offset <= 2; offset++) {
                LocalDate date = today.plusDays(offset);
                long days = ChronoUnit.DAYS.between(monday, date);
                int week = (int) Math.floorDiv(days, 7) + 1;
                if (date.isBefore(termDate) || week < 1 || week > weekCount
                        || !course.weeks.get(week) || date.getDayOfWeek().getValue() - 1 != course.day) continue;
                long start = date.atTime(startTime).atZone(eventZone).toInstant().toEpochMilli();
                long end = start + duration;
                if (eventStart > start || excluded(event, start, eventZone)) continue;
                CourseOccurrence item = new CourseOccurrence(data.backendAuthority, current.identity, data.calendarId,
                        eventId, start, end, start - (hasReminder ? reminderMinutes * 60000L : 0),
                        course.name, course.room, course.teacher);
                next.put(item.key, item);
            }
        }
        settings = current; occurrences = Collections.unmodifiableMap(next); loaded = true; validated = true; loadedDate = today;
        readFailures = 0; nextReadAt = 0;
        nativeActions.clear();
        CourseLiveSeedlingState.restore();
        CourseLiveHooks.adoptCards();
        pruneReceipts(next);
        JSONArray sessions = new JSONArray();
        if (current.enabled) for (CourseOccurrence item : next.values())
            if (item.end > System.currentTimeMillis()) sessions.put(new JSONObject().put("key", item.key)
                    .put("start", item.start).put("end", item.end).put("automatic", current.autoDnd));
        syncDnd(sessions);
        if (!recovering) recoverNotifications();
        refresh();
    }

    private static void recoverNotifications() {
        recovering = true;
        try {
            for (StatusBarNotification active : manager().getActiveNotifications()) {
                Notification notification = active.getNotification();
                String key = notification.extras.getString(KEY);
                if (key != null && !posted.containsKey(key))
                    posted.put(key, new Posted(key, active.getTag(), active.getId(), notification));
            }
        } catch (RuntimeException error) { Logx.w(CourseAlarmEntry.TAG, "live notifications restore: " + error); }
        finally { recovering = false; }
    }

    private static void refresh() {
        if (!loaded) { reload(); return; }
        if (!validated) { cleanupDeadline(); return; }
        if (!LocalDate.now().equals(loadedDate)) { reload(); return; }
        long now = System.currentTimeMillis();
        for (Posted record : new ArrayList<>(posted.values())) {
            if (!isOwnedPublished(record)) { posted.remove(record.key); continue; }
            CourseOccurrence item = occurrences.get(record.key);
            CourseLiveSettings.State state = settings(item);
            if (item == null || isDismissed(item) || (state != null && now >= item.expiresAt(state.retainMinutes))) {
                cancelIfOwned(record); posted.remove(record.key); continue;
            }
            if (CourseLiveSeedlingState.hasNotification(item)) {
                cancelIfOwned(record); posted.remove(record.key); continue;
            }
            if (state == null || !state.enabled) {
                Notification ordinary = restore(record.original);
                if (state != null && state.retainOnClick) ordinary.flags &= ~Notification.FLAG_AUTO_CANCEL;
                manager().notify(record.tag, record.id, ordinary); posted.remove(record.key); continue;
            }
            try {
                Bundle status = state.showDndButton && now < item.end ? CourseDndClient.status(app, item.key) : Bundle.EMPTY;
                boolean active = status.getBoolean("sessionActive");
                record.otherDnd = status.getBoolean("systemActive") && !status.getBoolean("ownedActive");
                String fingerprint = item.timing(now) + ":" + state.showProgress + ":" + state.showDndButton
                        + ":" + state.retainOnClick + ":" + active + ":" + state.retainMinutes
                        + ":" + item.title + ":" + item.room + ":" + item.teacher + ":" + item.start + ":" + item.end + ":" + record.otherDnd;
                if (fingerprint.equals(record.last)) continue;
                if (state.showDndButton && now < item.end
                        && (record.dnd == null || !Boolean.valueOf(active).equals(record.dndSessionActive))) {
                    record.dnd = CourseDndClient.action(app, item.key, item.start, item.end, active, dndContinuation(item));
                    record.dndSessionActive = record.dnd == null ? null : active;
                }
                Notification decorated = decorate(record, item, state, active, now);
                manager().notify(record.tag, record.id, decorated); record.last = fingerprint;
                rememberClick(item, record.original.extras.getLong("lspx.course.alarm", item.reminderAt));
            } catch (RuntimeException error) { Logx.e(CourseAlarmEntry.TAG, "live notification update", error); }
        }
        CourseLiveHooks.refreshSeedling();
        CourseNativeCardBridge.changed(app);
        SharedPreferences.Editor prune = null;
        for (Map.Entry<String, Long> entry : dismissed.entrySet()) if (entry.getValue() <= now) {
            dismissed.remove(entry.getKey());
            if (prune == null) prune = journal.edit();
            prune.remove("dismiss:" + entry.getKey());
        }
        if (prune != null) prune.apply();
        schedule(now);
    }

    private static Notification decorate(Posted record, CourseOccurrence item, CourseLiveSettings.State state,
                                         boolean dndActive, long now) {
        Notification original = record.original;
        Notification.Builder builder = Notification.Builder.recoverBuilder(app, original);
        builder.setAutoCancel(!state.retainOnClick).setOnlyAlertOnce(true)
                .setTimeoutAfter(Math.max(1, item.expiresAt(state.retainMinutes) - now));
        builder.setContentTitle(item.title).setContentText(item.timing(now));
        String details = android.text.format.DateFormat.format("HH:mm", item.start) + "–"
                + android.text.format.DateFormat.format("HH:mm", item.end)
                + (item.room.isEmpty() ? "" : " · " + item.room) + (item.teacher.isEmpty() ? "" : " · " + item.teacher);
        builder.setSubText(details + (record.otherDnd ? " · 系统免打扰已开启" : ""));
        builder.setStyle(null);
        if (state.showProgress && now >= item.start) {
            builder.setProgress(10000, item.progress(now), false);
            if (Build.VERSION.SDK_INT >= 36) {
                // Uses the system progress template. Fixed Seedling UPKs cannot render these widgets.
                builder.setStyle(new Notification.ProgressStyle().setProgress(item.progress(now))
                        .setProgressSegments(List.of(new Notification.ProgressStyle.Segment(10000))));
            }
        } else builder.setProgress(0, 0, false);
        List<Notification.Action> actions = new ArrayList<>();
        if (original.actions != null) for (Notification.Action action : original.actions)
            if (!action.getExtras().getBoolean("lspx.live.dnd")) actions.add(action);
        if (state.showDndButton && now < item.end && record.dnd != null) {
            Bundle extras = new Bundle(); extras.putBoolean("lspx.live.dnd", true);
            actions.add(new Notification.Action.Builder((android.graphics.drawable.Icon) null,
                    dndActive ? "结束本次免打扰" : "开启免打扰", record.dnd).addExtras(extras).build());
        }
        builder.setActions(actions.toArray(new Notification.Action[0]));
        Intent delete = new Intent(DISMISS).setClassName(app, RECEIVER)
                .setData(Uri.parse("coursealarm://dismiss/" + Uri.encode(item.key))).putExtra(KEY, item.key).putExtra(END, item.end);
        PendingIntent nativeDelete = original.extras.getParcelable("lspx.live.nativeDelete");
        if (nativeDelete == null && original.extras.getString(KEY) == null) nativeDelete = original.deleteIntent;
        if (nativeDelete != null) delete.putExtra("originalDelete", nativeDelete);
        builder.setDeleteIntent(PendingIntent.getBroadcast(app, 0, delete, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        Bundle extras = new Bundle(); extras.putString(KEY, item.key); extras.putLong(START, item.start); extras.putLong(END, item.end);
        extras.putLong("lspx.live.expires", item.expiresAt(state.retainMinutes));
        extras.putParcelable("lspx.live.nativeDelete", nativeDelete);
        extras.putCharSequence("lspx.live.baseText", original.extras.containsKey("lspx.live.baseText")
                ? original.extras.getCharSequence("lspx.live.baseText") : original.extras.getCharSequence(Notification.EXTRA_TEXT));
        builder.addExtras(extras);
        return builder.build();
    }

    private static Notification restore(Notification original) {
        if (!original.extras.containsKey(KEY)) return original;
        Notification.Builder builder = Notification.Builder.recoverBuilder(app, original).setStyle(null)
                .setAutoCancel(true).setProgress(0, 0, false).setTimeoutAfter(0).setOnlyAlertOnce(true);
        builder.setContentText(original.extras.getCharSequence("lspx.live.baseText"));
        builder.setDeleteIntent(original.extras.getParcelable("lspx.live.nativeDelete"));
        List<Notification.Action> actions = new ArrayList<>();
        if (original.actions != null) for (Notification.Action action : original.actions)
            if (!action.getExtras().getBoolean("lspx.live.dnd")) actions.add(action);
        builder.setActions(actions.toArray(new Notification.Action[0]));
        Notification restored = builder.build();
        for (String key : new ArrayList<>(restored.extras.keySet())) if (key.startsWith("lspx.live.")) restored.extras.remove(key);
        return restored;
    }

    private static void cancelIfOwned(Posted record) {
        for (StatusBarNotification active : manager().getActiveNotifications())
            if (active.getId() == record.id && java.util.Objects.equals(active.getTag(), record.tag)
                    && matchesPublication(record, active.getNotification())) manager().cancel(record.tag, record.id);
    }

    private static boolean isStillPublished(int id, Notification original) {
        String token = original.extras.getString("lspx.course.publication");
        for (StatusBarNotification active : manager().getActiveNotifications())
            if (active.getId() == id && TAG.equals(active.getTag())
                    && token != null && token.equals(active.getNotification().extras.getString("lspx.course.publication"))) return true;
        return false;
    }

    private static boolean isOwnedPublished(Posted record) {
        for (StatusBarNotification active : manager().getActiveNotifications()) {
            if (active.getId() != record.id || !java.util.Objects.equals(active.getTag(), record.tag)) continue;
            if (matchesPublication(record, active.getNotification())) return true;
        }
        return false;
    }

    private static boolean matchesPublication(Posted record, Notification current) {
        if (record.key.equals(current.extras.getString(KEY))) return true;
        String token = record.original.extras.getString("lspx.course.publication");
        return token != null && token.equals(current.extras.getString("lspx.course.publication"));
    }

    private static void schedule(long now) {
        long next = LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        for (CourseOccurrence item : occurrences.values()) {
            CourseLiveSettings.State state = settings(item); if (state == null) continue;
            if (item.start > now) next = Math.min(next, item.start);
            if (item.end > now) next = Math.min(next, item.end);
            if (item.expiresAt(state.retainMinutes) > now && (posted.containsKey(item.key) || CourseLiveHooks.hasCard(item)))
                next = Math.min(next, Math.min(item.expiresAt(state.retainMinutes), now + 60000L - now % 60000L));
        }
        if (settings == null || !settings.enabled) next = 0;
        setAlarm(next);
    }

    private static void setAlarm(long next) {
        if (next == scheduledAt) return;
        AlarmManager alarm = app.getSystemService(AlarmManager.class);
        PendingIntent pending = PendingIntent.getBroadcast(app, 0x4c495645,
                new Intent(TICK).setClassName(app, RECEIVER), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        alarm.cancel(pending); scheduledAt = next;
        if (next == 0) return;
        if (Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms()) alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending);
        else alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending);
    }

    static long number(Object object, String getter) {
        Object value = Ref.callName(object, getter); return value instanceof Number ? ((Number) value).longValue() : -1;
    }
    private static boolean excluded(Object event, long start, ZoneId zone) {
        Object raw = Ref.callName(event, "getExdate");
        if (!(raw instanceof String) || ((String) raw).isEmpty()) return false;
        for (String text : ((String) raw).split(",")) {
            try {
                java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
                String value = text.trim();
                long at = java.time.LocalDateTime.parse(value.endsWith("Z") ? value.substring(0, value.length() - 1) : value,
                        formatter).atZone(value.endsWith("Z") ? ZoneId.of("UTC") : zone).toInstant().toEpochMilli();
                if (at == start) return true;
            } catch (RuntimeException invalid) { return true; } // Do not invent an occurrence from malformed exclusions.
        }
        return false;
    }

    private static void pruneReceipts(Map<String, CourseOccurrence> valid) {
        SharedPreferences.Editor edit = journal.edit(); long now = System.currentTimeMillis();
        for (String name : journal.getAll().keySet()) if (name.startsWith("click:")) {
            CourseOccurrence item = valid.get(name.substring(6));
            CourseLiveSettings.State state = settings(item);
            if (state == null || now >= item.expiresAt(state.retainMinutes)) edit.remove(name);
            else try {
                JSONObject previous = new JSONObject(journal.getString(name, "{}"));
                rememberClick(item, previous.optLong("alarm", item.reminderAt));
            } catch (Exception invalid) { edit.remove(name); }
        }
        edit.apply();
    }
    private static NotificationManager manager() { return app.getSystemService(NotificationManager.class); }
}
