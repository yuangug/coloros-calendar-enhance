package com.lcodexs.coursealarm;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Native card identities and deadlines only. Updates existing cards; never recreates dismissed cards. */
final class CourseLiveSeedlingState {
    private static final String ROOT = "com.android.calendar.seedling.notificationfluidcard.";
    private static final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private static final String CLOSED = "closed:";
    private static final int MAX_CLOSE_ATTEMPTS = 3;
    private static final long CLOSE_RETRY_MILLIS = 60000L;
    private static final Map<String, Runnable> closeRetries = new LinkedHashMap<>(); // coordinator worker only
    private static Context app;
    private static SharedPreferences preferences;
    private static Handler retryHandler;
    private static int bootCount;

    private static final class Entry {
        String key, service, backend, identity;
        long event, calendar, start, end, alarm, expires;
        boolean force;
        volatile boolean closing;
        volatile boolean notificationReady;
        int subscribedBoot = -1;
        int closeAttempts;
        long closeAttemptAt;
        final Map<String, Object> cards = new LinkedHashMap<>();
        String last = "";
    }

    static void init(Context context) {
        app = context;
        preferences = app.getSharedPreferences("lspx_course_live_seedling_v1", Context.MODE_PRIVATE);
        retryHandler = new Handler(Looper.getMainLooper());
        bootCount = android.provider.Settings.Global.getInt(app.getContentResolver(), "boot_count", -2);
    }

    static void restore() {
        if (preferences == null) return;
        for (Map.Entry<String, ?> stored : preferences.getAll().entrySet()) {
            if (stored.getKey().startsWith(CLOSED) && stored.getValue() instanceof Long) {
                if ((Long) stored.getValue() <= System.currentTimeMillis()) preferences.edit().remove(stored.getKey()).apply();
                continue;
            }
            if (!(stored.getValue() instanceof String) || entries.containsKey(stored.getKey())) continue;
            try {
                JSONObject data = new JSONObject((String) stored.getValue()); Entry entry = new Entry();
                entry.service = stored.getKey(); entry.key = data.getString("key"); entry.event = data.getLong("event");
                entry.calendar = data.getLong("calendar"); entry.start = data.getLong("start"); entry.end = data.getLong("end");
                entry.alarm = data.getLong("alarm"); entry.backend = data.getString("backend"); entry.identity = data.getString("identity");
                entry.force = data.getBoolean("force");
                JSONArray widgets = data.optJSONArray("cardIds");
                if (widgets == null) widgets = data.getJSONArray("widgets");
                entry.subscribedBoot = data.optInt("subscribedBoot", -1);
                entry.expires = data.optLong("expires", entry.end + 300000L);
                entry.closing = data.optBoolean("closing");
                entry.closeAttempts = data.optInt("closeAttempts");
                entry.closeAttemptAt = data.optLong("closeAttemptAt");
                Class<?> type = Class.forName("com.oplus.pantanal.seedling.bean.SeedlingCard", false, app.getClassLoader());
                for (int i = 0; i < widgets.length(); i++) {
                    String widget = widgets.getString(i); Object card = type.getMethod("build", String.class).invoke(null, widget);
                    if (card == null) continue;
                    type.getMethod("setServiceInstanceId", String.class).invoke(card, entry.service);
                    entry.cards.put(string(Ref.callName(card, "getWidgetCode")), card);
                }
                // Persisted identity permits cleanup, but cannot prove that SystemUI still has a row.
                entry.notificationReady = false;
                entries.put(entry.service, entry);
                if (entry.closing) scheduleCloseRetry(entry);
            } catch (Exception error) { Logx.e(CourseAlarmEntry.TAG, "live native card restore", error); }
        }
    }

    static void subscribed(Object model, Object card) {
        if (app == null || model == null || card == null) return;
        CourseLiveCoordinator.execute(() -> {
            restore();
            CourseOccurrence item = CourseLiveHooks.occurrence(model);
            if (item == null) return;
            String service = string(Ref.callName(card, "getServiceInstanceId"));
            String widget = string(Ref.callName(card, "getWidgetCode"));
            if (service.isEmpty() || widget.isEmpty()) return;
            Entry entry = entries.get(service);
            // A late subscription callback is not permission to revive an END in flight.
            if (isClosing(service)) {
                if (entry != null) {
                    entry.cards.put(widget, card); save(entry, true); close(entry);
                }
                return;
            }
            if (entry == null) {
                entry = new Entry(); entry.service = service; entry.key = item.key; entry.event = item.eventId;
                entry.calendar = item.calendarId; entry.backend = item.backend; entry.identity = item.tableIdentity;
                entry.start = item.start; entry.end = item.end; entry.alarm = CourseLiveCoordinator.number(model, "getAlarmTime");
                CourseLiveSettings.State state = CourseLiveCoordinator.settings(item);
                entry.expires = item.expiresAt(state == null ? 5 : state.retainMinutes);
                entry.force = Boolean.TRUE.equals(Ref.callName(model, "isForceAlert")); entries.put(service, entry);
            }
            entry.cards.put(widget, card); entry.subscribedBoot = bootCount;
            updateNotificationReady(entry);
            save(entry); CourseLiveCoordinator.rememberClick(item, entry.alarm);
            CourseLiveCoordinator.requestTick();
        });
    }

    static long event(String service) {
        if (service == null || service.isEmpty()) return -1;
        Entry entry = entries.get(service); if (entry != null) return entry.event;
        if (preferences != null) try {
            String value = preferences.getString(service, null);
            return value == null ? -1 : new JSONObject(value).optLong("event", -1);
        } catch (Exception ignored) { }
        return -1;
    }

    static CourseOccurrence occurrence(String service) {
        if (service == null || service.isEmpty()) return null;
        Entry entry = entries.get(service); return entry == null ? null : CourseLiveCoordinator.find(entry.key);
    }

    /** A publication receipt is enough to authenticate a tap, even before the provider is ready. */
    static CourseOccurrence captured(String service) {
        if (service == null || service.isEmpty() || preferences == null) return null;
        Entry entry = entries.get(service);
        if (entry != null) return captured(entry);
        try {
            JSONObject data = new JSONObject(preferences.getString(service, "{}"));
            return new CourseOccurrence(data.getString("backend"), data.getString("identity"),
                    data.getLong("calendar"), data.getLong("event"), data.getLong("start"),
                    data.getLong("end"), data.getLong("alarm"), "", "", "");
        } catch (Exception ignored) { return null; }
    }

    static void closeKey(String key) {
        restore();
        for (Entry entry : new ArrayList<>(entries.values())) if (entry.key.equals(key)) close(entry);
    }

    static void cleanupOnly() {
        restore(); long now = System.currentTimeMillis();
        for (Entry entry : new ArrayList<>(entries.values()))
            if (entry.closing || now >= entry.expires) close(entry);
    }

    static long nextExpiry() {
        long now = System.currentTimeMillis(), next = Long.MAX_VALUE;
        for (Entry entry : entries.values())
            if (!entry.closing && entry.expires > now) next = Math.min(next, entry.expires);
        return next == Long.MAX_VALUE ? 0 : next;
    }

    /** Also covers delayed unsubscribe callbacks after a confirmed programmatic close. */
    static boolean isClosing(String service) {
        if (service == null || service.isEmpty()) return false;
        Entry entry = entries.get(service);
        if (entry != null && entry.closing) return true;
        if (preferences != null) try {
            if (preferences.getLong(CLOSED + service, 0) > System.currentTimeMillis()) return true;
            String receipt = preferences.getString(service, null);
            return receipt != null && new JSONObject(receipt).optBoolean("closing");
        } catch (Exception ignored) { }
        return false;
    }

    static void manualClose(String service) {
        if (service == null || service.isEmpty()) return;
        CourseLiveCoordinator.execute(() -> {
            restore(); Entry entry = entries.get(service); if (entry == null) return;
            CourseLiveCoordinator.dismiss(captured(entry));
        });
    }

    static void unsubscribed(Object card, boolean alreadyClosed) {
        if (card == null) return;
        String service = string(Ref.callName(card, "getServiceInstanceId"));
        String widget = string(Ref.callName(card, "getWidgetCode"));
        CourseLiveCoordinator.execute(() -> {
            // Unsubscribe can be the callback that cold-started the calendar process.
            // It must consume the persisted identity before a provider read/adoption can restore it.
            restore();
            Entry entry = entries.get(service); if (entry == null) return;
            entry.cards.remove(widget);
            updateNotificationReady(entry);
            if (entry.cards.isEmpty()) {
                if (!alreadyClosed && !entry.closing && !isClosing(service))
                    CourseLiveCoordinator.dismiss(captured(entry));
                confirmedClosed(entry);
            } else save(entry);
        });
    }

    static boolean has(CourseOccurrence item) {
        for (Entry entry : entries.values()) if (entry.key.equals(item.key)) return true;
        return false;
    }

    /** A delivered notification-center subscription, not a send request or a status-bar card. */
    static boolean hasNotification(CourseOccurrence item) {
        if (item == null) return false;
        long now = System.currentTimeMillis();
        for (Entry entry : entries.values())
            if (entry.key.equals(item.key) && entry.end == item.end && !entry.force && !entry.closing
                    && entry.notificationReady && now < entry.expires) return true;
        return false;
    }

    static CourseOccurrence findVisibleService(String service) {
        Entry entry = entries.get(service);
        if (entry == null) {
            restore();
            entry = entries.get(service);
        }
        if (entry == null || entry.force || entry.closing || System.currentTimeMillis() >= entry.expires) return null;
        CourseOccurrence item = CourseLiveCoordinator.find(entry.key);
        if (item == null || item.start != entry.start || item.end != entry.end || CourseLiveCoordinator.settings(item) == null
                || CourseLiveCoordinator.suppressed(item)) return null;
        return item;
    }

    /** Called only after the bridge authenticated a live notification-center row. */
    static CourseOccurrence confirmNotification(String service) {
        CourseOccurrence item = findVisibleService(service);
        if (item == null) return null;
        Entry entry = entries.get(service);
        boolean wasReady = entry.notificationReady;
        entry.notificationReady = true;
        if (!wasReady) CourseLiveCoordinator.requestTick();
        return item;
    }

    private static void updateNotificationReady(Entry entry) {
        boolean delivered = false;
        if (entry.subscribedBoot == bootCount) for (Object card : entry.cards.values()) {
            Object host = Ref.callName(card, "getHost");
            if (host instanceof Enum && "Notification".equals(((Enum<?>) host).name())) {
                delivered = true; break;
            }
        }
        entry.notificationReady = delivered;
    }

    static void close(CourseOccurrence item) {
        for (Entry entry : new ArrayList<>(entries.values())) if (entry.key.equals(item.key)) close(entry);
    }

    static void refresh() {
        long now = System.currentTimeMillis();
        for (Entry entry : new ArrayList<>(entries.values())) {
            if (entry.closing) { close(entry); continue; }
            CourseOccurrence item = CourseLiveCoordinator.find(entry.key);
            CourseLiveSettings.State settings = CourseLiveCoordinator.settings(item);
            if (item == null || settings == null || item.end != entry.end || CourseLiveCoordinator.isDismissed(item)
                    || now >= item.expiresAt(settings.retainMinutes)) { close(entry); continue; }
            String current = item.timing(now) + ":" + settings.retainMinutes + ":" + settings.showProgress
                    + ":" + item.title + ":" + item.room + ":" + item.teacher + ":" + item.start + ":" + item.end;
            if (current.equals(entry.last)) continue;
            entry.expires = item.expiresAt(settings.retainMinutes); save(entry);
            try {
                Object model = model(entry, item);
                Class<?> helper = Class.forName(ROOT + "NotificationFluidSeedingHelper", false, app.getClassLoader());
                for (Object card : entry.cards.values()) {
                    java.lang.reflect.Method update = Ref.methodByArity(helper, "updateFluidCard", 3);
                    update.invoke(null, card, model, Boolean.FALSE);
                }
                entry.last = current;
            } catch (Exception error) { Logx.e(CourseAlarmEntry.TAG, "live existing native card update", error); }
        }
    }

    private static void close(Entry entry) {
        if (entries.get(entry.service) != entry) return;
        long now = System.currentTimeMillis();
        entry.closing = true;
        if (entry.closeAttempts >= MAX_CLOSE_ATTEMPTS) return;
        if (entry.closeAttemptAt > 0 && now - entry.closeAttemptAt < CLOSE_RETRY_MILLIS) {
            scheduleCloseRetry(entry); return;
        }
        entry.closeAttempts++; entry.closeAttemptAt = now;
        if (!save(entry, true)) {
            // Failed persistence is a failed attempt too. Never spin or send an unjournaled END.
            scheduleCloseRetry(entry); return;
        }
        try {
            Class<?> helper = Class.forName(ROOT + "NotificationFluidSeedingHelper", false, app.getClassLoader());
            Ref.methodOf(helper, "closeFluidCard", String.class,
                    Class.forName(ROOT + "entity.FluidSeedingModel", false, app.getClassLoader()))
                    .invoke(helper.getField("INSTANCE").get(null), entry.service, model(entry, null));
            // The helper returns before its asynchronous END callback. Keep this receipt and
            // its closing marker until unsubscribe confirms removal; never re-show it.
        } catch (Exception error) { Logx.e(CourseAlarmEntry.TAG, "live native card close; receipt retained", error); }
        scheduleCloseRetry(entry);
    }

    private static void scheduleCloseRetry(Entry entry) {
        if (retryHandler == null || !entry.closing || entry.closeAttempts >= MAX_CLOSE_ATTEMPTS
                || closeRetries.containsKey(entry.service)) return;
        Runnable retry = () -> CourseLiveCoordinator.execute(() -> {
            closeRetries.remove(entry.service);
            if (entries.get(entry.service) == entry && entry.closing) close(entry);
        });
        closeRetries.put(entry.service, retry);
        long elapsed = Math.max(0L, System.currentTimeMillis() - entry.closeAttemptAt);
        retryHandler.postDelayed(retry, Math.max(1L, CLOSE_RETRY_MILLIS - elapsed));
    }

    private static void confirmedClosed(Entry entry) {
        long until = Math.max(System.currentTimeMillis(), entry.end) + 86400000L;
        // Retain the tombstone for other system hosts' delayed callbacks for this same service.
        if (!preferences.edit().remove(entry.service).putLong(CLOSED + entry.service, until).commit()) {
            entry.closing = true; save(entry, true); return;
        }
        entries.remove(entry.service);
        Runnable retry = closeRetries.remove(entry.service);
        if (retry != null) retryHandler.removeCallbacks(retry);
    }

    private static CourseOccurrence captured(Entry entry) {
        return new CourseOccurrence(entry.backend, entry.identity, entry.calendar, entry.event,
                entry.start, entry.end, entry.alarm, "", "", "");
    }

    private static Object model(Entry entry, CourseOccurrence item) throws Exception {
        Class<?> type = Class.forName(ROOT + "entity." + (entry.force ? "ForceReminderModel" : "NormalReminderModel"), false, app.getClassLoader());
        for (Constructor<?> constructor : type.getDeclaredConstructors()) if (constructor.getParameterCount() == 14)
            return constructor.newInstance(entry.event, item == null ? "" : item.title, false, entry.alarm,
                    entry.start, entry.end, 0L, CourseWriter.COURSE_EXT_PROP, "", "", "",
                    java.time.ZoneId.systemDefault().getId(), item == null ? "" : item.room, item == null ? "" : item.teacher);
        throw new NoSuchMethodException(type.getName());
    }

    private static void save(Entry entry) { save(entry, false); }

    private static boolean save(Entry entry, boolean durable) {
        try {
            JSONArray cardIds = new JSONArray();
            for (Object card : entry.cards.values()) {
                String id = string(Ref.callName(card, "getSeedlingCardId"));
                cardIds.put(id.isEmpty() ? string(Ref.callName(card, "getWidgetCode")) : id);
            }
            JSONObject data = new JSONObject().put("key", entry.key).put("event", entry.event).put("calendar", entry.calendar)
                    .put("start", entry.start).put("end", entry.end).put("alarm", entry.alarm).put("backend", entry.backend)
                    .put("expires", entry.expires)
                    .put("identity", entry.identity).put("force", entry.force).put("widgets", new JSONArray(entry.cards.keySet()))
                    .put("cardIds", cardIds).put("subscribedBoot", entry.subscribedBoot)
                    .put("closing", entry.closing).put("closeAttempts", entry.closeAttempts).put("closeAttemptAt", entry.closeAttemptAt);
            String value = data.toString();
            if (!durable && value.equals(preferences.getString(entry.service, null))) return true;
            SharedPreferences.Editor edit = preferences.edit().putString(entry.service, value);
            if (entry.closing) edit.putLong(CLOSED + entry.service, Math.max(System.currentTimeMillis(), entry.end) + 86400000L);
            if (durable) {
                if (edit.commit()) return true;
                Logx.w(CourseAlarmEntry.TAG, "live native closing receipt commit failed"); return false;
            }
            edit.apply(); return true;
        } catch (Exception error) { Logx.e(CourseAlarmEntry.TAG, "live native card receipt", error); }
        return false;
    }
    private static String string(Object value) { return value == null ? "" : value.toString(); }
}
