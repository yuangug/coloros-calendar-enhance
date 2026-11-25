package com.lcodexs.coursealarm;

import android.app.AlarmManager;
import android.app.AutomaticZenRule;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.Condition;
import android.util.Log;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Runs in the installed module process, never under the injected calendar UID. */
final class CourseDndBroker {
    static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "CourseDnd"); thread.setDaemon(true); return thread;
    });
    static final String BOUNDARY = CourseDndClient.PACKAGE + ".DND_BOUNDARY";
    static final String TOKEN_ACTION = CourseDndClient.PACKAGE + ".DND_TOGGLE";
    private static final String PREFS = "course_dnd_v1";
    private static final String TAG = "CourseDnd";
    private static final Object LOCK = new Object();
    private static final long RETRY_MILLIS = 60000L;
    private static String lastBroadcastState;
    private static boolean initializedInProcess;
    private static boolean publishedInProcess;
    private static boolean alarmInitialized, lastAlarmWasValidationRetry;
    private static long lastAlarmAt;

    private CourseDndBroker() { }

    static Bundle call(Context context, String method, Bundle request) {
        synchronized (LOCK) {
            try {
                if (request == null || request.getInt("protocol") != 1)
                    throw new IllegalArgumentException("不支持的免打扰接口版本");
                switch (method) {
                    case "status": return statusLocked(context, request.getString("key"));
                    case "sync": return syncLocked(context, request);
                    case "invalidate":
                        beginValidationLocked(context, true);
                        reconcileLocked(context, false);
                        changed(context);
                        return statusLocked(context, null);
                    case "toggle": return toggleLocked(context, request.getString("key"),
                            request.getLong("start"), request.getLong("end"), null, null);
                    case "enable": return changeLocked(context, request.getString("key"),
                            request.getLong("start"), request.getLong("end"), null, null, true);
                    case "action": return actionLocked(context, request);
                    default: throw new IllegalArgumentException("未知的免打扰操作");
                }
            } catch (Exception error) { return failure(context, error); }
        }
    }

    static Bundle tokenStatus(Context context, String token) {
        synchronized (LOCK) {
            try {
                JSONObject item = validToken(context, token);
                reconcileLocked(context, false);
                Bundle result = statusLocked(context, item.getString("key"));
                result.putBoolean("validToken", true);
                result.putBoolean("requestedEnable", item.optBoolean("enable", true));
                return result;
            } catch (Exception error) { return failure(context, error); }
        }
    }

    static Bundle toggleToken(Context context, String token, String requestId) {
        synchronized (LOCK) {
            try {
                if (requestId == null || requestId.length() > 64)
                    throw new IllegalArgumentException("免打扰操作标识无效");
                JSONObject item = validToken(context, token);
                if (item.optBoolean("enable", true) && !validationReadyLocked(context))
                    return awaitingValidationLocked(context, item.getString("key"));
                if (requestId.equals(item.optString("request"))) {
                    // Activity recreation must not interpret the same tap as a second toggle.
                    if (item.optBoolean("completed") && !item.optBoolean("success"))
                        throw new IllegalStateException(item.optString("error", "上次免打扰操作失败，请重新点击"));
                    reconcileLocked(context, false);
                    Bundle result = statusLocked(context, item.getString("key"));
                    if (item.optBoolean("target") != result.getBoolean("sessionActive"))
                        throw new IllegalStateException("本次课程免打扰状态已变化，请重新点击");
                    completeToken(context, token, true, null);
                    return result;
                }
                return toggleLocked(context, item.getString("key"), item.getLong("start"),
                        item.getLong("end"), token, requestId);
            } catch (Exception error) { return failure(context, error); }
        }
    }

    static Bundle refresh(Context context) {
        synchronized (LOCK) {
            try {
                reconcileLocked(context, false);
                changed(context);
                return statusLocked(context, null);
            } catch (Exception error) { return failure(context, error); }
        }
    }

    static void onSystemEvent(Context context, Intent intent) {
        synchronized (LOCK) {
            try {
                SharedPreferences prefs = prefs(context);
                String action = intent == null ? null : intent.getAction();
                long now = System.currentTimeMillis();
                if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_TIME_CHANGED.equals(action)
                        || Intent.ACTION_TIMEZONE_CHANGED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action))
                    beginValidationLocked(context, true, true);
                else if (BOUNDARY.equals(action) && !intent.getBooleanExtra("validationRetry", false)
                        && (intent.getLongExtra("scheduledAt", 0) <= 0
                            || prefs.getLong("validatedAt", 0) < intent.getLongExtra("scheduledAt", 0)))
                    beginValidationLocked(context, false);
                if (NotificationManager.ACTION_AUTOMATIC_ZEN_RULE_STATUS_CHANGED.equals(action)
                        && prefs.getString("rule", "").equals(intent.getStringExtra(
                                NotificationManager.EXTRA_AUTOMATIC_ZEN_RULE_ID))) {
                    int state = intent.getIntExtra(NotificationManager.EXTRA_AUTOMATIC_ZEN_RULE_STATUS, -1);
                    NotificationManager manager = manager(context);
                    boolean access = manager.isNotificationPolicyAccessGranted();
                    String id = prefs.getString("rule", "");
                    AutomaticZenRule current = access ? manager.getAutomaticZenRule(id) : null;
                    boolean removed = state == NotificationManager.AUTOMATIC_RULE_STATUS_REMOVED && current == null;
                    boolean disabled = state == NotificationManager.AUTOMATIC_RULE_STATUS_DISABLED
                            && (current == null || !current.isEnabled());
                    boolean inactive = !access || current == null || Build.VERSION.SDK_INT < 35
                            || manager.getAutomaticZenRuleState(id) != Condition.STATE_TRUE;
                    if (disabled || removed
                            || (state == NotificationManager.AUTOMATIC_RULE_STATUS_DEACTIVATED
                                && inactive && prefs.getBoolean("published", false) && load(context).active(now))) {
                        // An explicit system override wins over this occurrence's automatic setting.
                        save(context, load(context).suppressActive(now), tokens(context), false);
                        if (removed)
                            commit(prefs.edit().remove("rule"));
                    }
                }
                if (NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED.equals(action)
                        && prefs.getBoolean("published", false)
                        && manager(context).getCurrentInterruptionFilter() == NotificationManager.INTERRUPTION_FILTER_ALL) {
                    save(context, load(context).suppressActive(now), tokens(context), false);
                }
                reconcileLocked(context, false);
                changed(context);
            } catch (Exception error) {
                Log.w(TAG, "Cannot reconcile course DND", error);
                retry(context);
            }
        }
    }

    private static Bundle syncLocked(Context context, Bundle request) throws JSONException {
        String serialized = request.getString("occurrences");
        if (serialized == null || serialized.length() > 1048576)
            throw new IllegalArgumentException("课程免打扰数据无效");
        JSONArray input = new JSONArray(serialized);
        if (input.length() > CourseDndSessions.MAX_SESSIONS)
            throw new IllegalArgumentException("课程免打扰实例过多");
        String validationToken = request.getString("validationToken");
        if (input.length() > 0 && !CourseDndValidation.validToken(validationToken))
            throw new IllegalArgumentException("课程免打扰缺少有效的课程校验令牌");
        if (validationToken != null && !CourseDndValidation.validToken(validationToken))
            throw new IllegalArgumentException("课程校验令牌无效");
        List<CourseDndSessions.Session> incoming = new ArrayList<>();
        for (int i = 0; i < input.length(); i++) {
            JSONObject item = input.getJSONObject(i);
            incoming.add(new CourseDndSessions.Session(item.getString("key"), item.getLong("start"),
                    item.getLong("end"), item.getBoolean("automatic"), false, false));
        }
        long now = System.currentTimeMillis();
        // replace validates every entry before any old schedule or rule can be touched.
        CourseDndSessions next = load(context).replace(incoming, now);
        JSONObject tokens = pruneTokens(tokens(context), next, now);
        save(context, next, tokens, null);
        SharedPreferences.Editor validated = prefs(context).edit().putBoolean("validationPending", false)
                .putLong("validatedAt", now).putInt("validationAttempts", 0).putLong("validationLastAttempt", 0)
                .putLong("validationLease", CourseDndValidation.activeLeaseEnd(next, now))
                .putLong("validationBoundary", CourseDndValidation.nextBoundary(next, now));
        if (validationToken != null) validated.putString("validationToken", validationToken);
        commit(validated);
        CourseIslandWake.replace(context, request.getLongArray("islandDeadlines"), validationToken);
        initializedInProcess = true;
        try { reconcileLocked(context, false); }
        catch (RuntimeException error) { retry(context); throw error; }
        changed(context);
        return statusLocked(context, null);
    }

    private static Bundle toggleLocked(Context context, String key, long start, long end,
                                       String token, String requestId) throws JSONException {
        return changeLocked(context, key, start, end, token, requestId, false);
    }

    /** A permission continuation always enables, even if the rule changes during the callback. */
    private static Bundle changeLocked(Context context, String key, long start, long end,
                                       String token, String requestId, boolean forceEnable) throws JSONException {
        Bundle availability = statusLocked(context, key);
        if (!availability.getBoolean("supported")) throw new IllegalStateException("当前系统不支持课程免打扰");
        CourseDndSessions before = load(context);
        long now = System.currentTimeMillis();
        requireCurrent(before, key, start, end, now);
        boolean stopping = availability.getBoolean("sessionActive");
        JSONObject actionTokens = tokens(context);
        // The command is bound to the label that was shown. A grant or automatic start while
        // the permission page is open must not turn an "enable" tap into a disable operation.
        boolean enabling = forceEnable || (token == null ? !stopping : actionTokens.getJSONObject(token).optBoolean("enable", true));
        if (enabling && !validationReadyLocked(context)) return awaitingValidationLocked(context, key);
        if (enabling && (!availability.getBoolean("access") || !availability.getBoolean("exactAlarmAccess"))) {
            availability.putBoolean("success", false);
            availability.putBoolean("accessRequired", true);
            availability.putString("error", "请先授予免打扰及准时结束所需的闹钟权限");
            return availability;
        }
        if (forceEnable && stopping) return availability;
        CourseDndSessions next = before.toggle(key, start, end, now, !enabling);
        if (token != null) {
            JSONObject item = actionTokens.getJSONObject(token);
            item.put("request", requestId);
            item.put("target", enabling).put("completed", false).remove("error");
        }
        save(context, next, actionTokens, null);
        try {
            reconcileLocked(context, enabling);
            Bundle result = statusLocked(context, key);
            if (enabling && !result.getBoolean("sessionActive"))
                throw new IllegalStateException("系统尚未启用本次课程免打扰，请检查系统规则状态");
            if (token != null) completeToken(context, token, true, null);
            changed(context);
            return result;
        } catch (RuntimeException error) {
            // Failed enable requests must not unexpectedly activate on a later refresh.
            // Failed disable requests keep the durable stop intent and retry only our rule.
            if (enabling) {
                CourseDndSessions recovery = availability.getBoolean("ownedActive") ? before : before.suppressActive(now);
                save(context, recovery, actionTokens, null);
                try { reconcileLocked(context, false); } catch (RuntimeException cleanup) { retry(context); }
            } else retry(context);
            if (token != null) completeToken(context, token, false, error.getMessage());
            changed(context);
            throw error;
        }
    }

    private static void completeToken(Context context, String token, boolean success, String error) throws JSONException {
        JSONObject all = tokens(context);
        JSONObject item = all.optJSONObject(token);
        if (item == null) return;
        item.put("completed", true).put("success", success);
        if (error == null) item.remove("error"); else item.put("error", error);
        commit(prefs(context).edit().putString("tokens", all.toString()));
    }

    private static Bundle actionLocked(Context context, Bundle request) throws JSONException {
        long now = System.currentTimeMillis();
        CourseDndSessions sessions = load(context);
        String key = request.getString("key");
        long start = request.getLong("start"), end = request.getLong("end");
        requireCurrent(sessions, key, start, end, now);
        boolean enable = !statusLocked(context, key).getBoolean("sessionActive");
        if (request.containsKey("enable") && request.getBoolean("enable") != enable)
            throw new IllegalStateException("免打扰状态已变化，请刷新课程卡片");
        JSONObject current = pruneTokens(tokens(context), sessions, now);
        String token = null;
        for (Iterator<String> keys = current.keys(); keys.hasNext();) {
            String candidate = keys.next();
            JSONObject item = current.getJSONObject(candidate);
            if (key.equals(item.getString("key")) && start == item.getLong("start") && end == item.getLong("end")
                    && item.optBoolean("enable", true) == enable) {
                token = candidate; break;
            }
        }
        if (token == null) {
            if (current.length() >= CourseDndSessions.MAX_SESSIONS * 2)
                throw new IllegalStateException("课程免打扰操作过多，请刷新课表");
            token = UUID.randomUUID().toString();
            current.put(token, new JSONObject().put("key", key).put("start", start).put("end", end).put("enable", enable));
            commit(prefs(context).edit().putString("tokens", current.toString()));
        }
        Intent intent = new Intent(context, CourseDndPermissionActivity.class).setAction(TOKEN_ACTION)
                .setData(new Uri.Builder().scheme("course-dnd").authority("toggle").appendPath(token).build())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Bundle result = new Bundle();
        result.putBoolean("success", true); result.putParcelable("pendingIntent", pendingIntent);
        return result;
    }

    private static JSONObject validToken(Context context, String token) throws JSONException {
        if (token == null || token.length() != 36) throw new IllegalArgumentException("免打扰操作已失效");
        JSONObject item = tokens(context).optJSONObject(token);
        if (item == null) throw new IllegalArgumentException("免打扰操作已失效，请刷新课程提醒");
        requireCurrent(load(context), item.getString("key"), item.getLong("start"), item.getLong("end"), System.currentTimeMillis());
        return item;
    }

    private static void requireCurrent(CourseDndSessions sessions, String key, long start, long end, long now) {
        CourseDndSessions.Session session = sessions.get(key);
        if (session == null || session.start != start || session.end != end || end <= now)
            throw new IllegalStateException("本次课程已结束或已变化，请刷新课程提醒");
    }

    private static void reconcileLocked(Context context, boolean explicitEnable) {
        long now = System.currentTimeMillis();
        CourseDndSessions sessions = load(context).prune(now);
        SharedPreferences prefs = prefs(context);
        ensureValidationLocked(context, now);
        NotificationManager manager = manager(context);
        boolean access = supported() && manager.isNotificationPolicyAccessGranted();
        boolean ready = access && exactAccess(context);
        if (!ready && prefs.getBoolean("ready", false)) sessions = sessions.suppressActive(now);
        AutomaticZenRule rule = access ? findRule(context, true) : null;
        if (rule != null && !rule.isEnabled() && !explicitEnable) sessions = sessions.suppressActive(now);
        save(context, sessions, pruneTokens(tokens(context), sessions, now), null);
        if (prefs.getBoolean("ready", false) != ready) commit(prefs.edit().putBoolean("ready", ready));
        boolean pending = prefs.getBoolean("validationPending", true);
        boolean existing = rule != null && rule.isEnabled() && prefs.getBoolean("published", false)
                && (Build.VERSION.SDK_INT >= 35
                    ? manager.getAutomaticZenRuleState(prefs.getString("rule", "")) == Condition.STATE_TRUE
                    : publishedInProcess);
        if (!pending) {
            long lease = CourseDndValidation.activeLeaseEnd(sessions, now);
            long boundary = CourseDndValidation.nextBoundary(sessions, now);
            if (prefs.getLong("validationLease", 0) != lease || prefs.getLong("validationBoundary", 0) != boundary)
                commit(prefs.edit().putLong("validationLease", lease).putLong("validationBoundary", boundary));
        }
        boolean active = ready && sessions.active(now) && CourseDndValidation.mayRemainActive(
                pending, existing, prefs.getLong("validationLease", 0), now);
        long nextBoundary = pending ? (active ? prefs.getLong("validationLease", 0) : 0)
                : CourseDndValidation.nextBoundary(sessions, now);
        // Install the end boundary before activating DND. When stopping, keep the previous
        // recovery alarm until STATE_FALSE is published, including across process death.
        try { if (active) schedule(context, nextBoundary); }
        catch (RuntimeException error) {
            if (access && rule != null) {
                try {
                    manager.setAutomaticZenRuleState(prefs.getString("rule", ""),
                            new Condition(conditionId(context), "课程免打扰计时不可用", Condition.STATE_FALSE));
                    commit(prefs.edit().putBoolean("published", false));
                } catch (RuntimeException cleanup) { Log.w(TAG, "Cannot release rule after alarm failure", cleanup); }
            }
            throw error;
        }
        if (access) {
            String id = prefs.getString("rule", "");
            if (active && rule == null) {
                rule = new AutomaticZenRule("课程免打扰", owner(context),
                        new ComponentName(context, CourseDndPermissionActivity.class), conditionId(context),
                        null, NotificationManager.INTERRUPTION_FILTER_PRIORITY, true);
                id = manager.addAutomaticZenRule(rule);
                if (id == null || id.isEmpty()) throw new IllegalStateException("系统未能创建课程免打扰规则");
                try { commit(prefs.edit().putString("rule", id)); }
                catch (RuntimeException error) { manager.removeAutomaticZenRule(id); throw error; }
            }
            if (rule != null) {
                if (active && !rule.isEnabled() && explicitEnable) {
                    rule.setEnabled(true);
                    if (!manager.updateAutomaticZenRule(id, rule)) throw new IllegalStateException("系统未能启用课程免打扰规则");
                }
                boolean published = prefs.getBoolean("published", false);
                int actual = Build.VERSION.SDK_INT >= 35 ? manager.getAutomaticZenRuleState(id) : -1;
                int desired = active ? Condition.STATE_TRUE : Condition.STATE_FALSE;
                if (published != active || actual != desired) {
                    manager.setAutomaticZenRuleState(id, new Condition(conditionId(context),
                            active ? "课程进行中" : "课程免打扰已结束", desired));
                }
            }
        }
        if (prefs.getBoolean("published", false) != active) commit(prefs.edit().putBoolean("published", active));
        publishedInProcess = active;
        requestValidationLocked(context, sessions, now);
        long validationRetry = CourseDndValidation.nextRetry(pending,
                CourseDndValidation.validToken(prefs.getString("validationToken", null)),
                prefs.getInt("validationAttempts", 0), prefs.getLong("validationLastAttempt", 0), now);
        if (sessions.all().isEmpty()) validationRetry = 0;
        boolean retryFirst = validationRetry > 0 && (nextBoundary == 0 || validationRetry < nextBoundary);
        long next = retryFirst ? validationRetry : nextBoundary;
        // A retry may move the alarm earlier; it can never move a valid cleanup deadline later.
        schedule(context, ready ? next : 0, retryFirst);
    }

    private static Bundle statusLocked(Context context, String key) {
        NotificationManager manager = manager(context);
        boolean supported = supported();
        boolean access = supported && manager.isNotificationPolicyAccessGranted();
        AutomaticZenRule rule = access ? findRule(context, false) : null;
        boolean enabled = rule == null || rule.isEnabled();
        int filter = manager.getCurrentInterruptionFilter();
        boolean system = filter != NotificationManager.INTERRUPTION_FILTER_ALL
                && filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN;
        boolean owned = rule != null && enabled && prefs(context).getBoolean("published", false);
        if (owned && Build.VERSION.SDK_INT >= 35)
            owned = manager.getAutomaticZenRuleState(prefs(context).getString("rule", "")) == Condition.STATE_TRUE;
        long now = System.currentTimeMillis();
        CourseDndSessions sessions = load(context);
        Bundle result = new Bundle();
        result.putBoolean("success", true); result.putBoolean("supported", supported);
        result.putBoolean("access", access); result.putBoolean("exactAlarmAccess", exactAccess(context));
        result.putBoolean("systemActive", system); result.putBoolean("ownedActive", owned);
        result.putBoolean("cleanupPending", owned && !sessions.active(now));
        result.putBoolean("ruleEnabled", enabled);
        result.putBoolean("validationPending", !initializedInProcess || prefs(context).getBoolean("validationPending", true));
        result.putLong("validatedAt", prefs(context).getLong("validatedAt", 0));
        if (key != null) {
            CourseDndSessions.Session session = sessions.get(key);
            result.putBoolean("known", session != null && session.end > now);
            result.putBoolean("sessionActive", session != null && session.active(now) && owned);
            result.putBoolean("suppressed", session != null && session.suppressed);
            if (session != null) result.putLong("end", session.end);
        }
        return result;
    }

    private static AutomaticZenRule findRule(Context context, boolean recover) {
        NotificationManager manager = manager(context);
        SharedPreferences prefs = prefs(context);
        String id = prefs.getString("rule", "");
        AutomaticZenRule rule = id.isEmpty() ? null : manager.getAutomaticZenRule(id);
        if (ours(context, rule)) return rule;
        if (!recover) return null;
        for (Map.Entry<String, AutomaticZenRule> entry : manager.getAutomaticZenRules().entrySet()) {
            if (ours(context, entry.getValue())) {
                commit(prefs.edit().putString("rule", entry.getKey()));
                return entry.getValue();
            }
        }
        if (!id.isEmpty()) commit(prefs.edit().remove("rule"));
        return null;
    }

    private static boolean ours(Context context, AutomaticZenRule rule) {
        return rule != null && owner(context).equals(rule.getOwner())
                && conditionId(context).equals(rule.getConditionId());
    }

    private static ComponentName owner(Context context) { return new ComponentName(context, CourseDndConditionService.class); }
    private static Uri conditionId(Context context) { return Condition.newId(context).appendPath("course-session-v1").build(); }
    private static NotificationManager manager(Context context) { return context.getSystemService(NotificationManager.class); }
    private static SharedPreferences prefs(Context context) { return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    private static boolean supported() { return Build.VERSION.SDK_INT >= 29; }
    private static boolean exactAccess(Context context) {
        return Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager.class).canScheduleExactAlarms();
    }

    private static CourseDndSessions load(Context context) {
        try {
            JSONArray array = new JSONArray(prefs(context).getString("sessions", "[]"));
            List<CourseDndSessions.Session> sessions = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                sessions.add(new CourseDndSessions.Session(item.getString("key"), item.getLong("start"),
                        item.getLong("end"), item.getBoolean("automatic"), item.optBoolean("manual"), item.optBoolean("suppressed")));
            }
            return new CourseDndSessions(sessions);
        } catch (RuntimeException | JSONException error) {
            // Never keep DND enabled from a corrupt recovery record.
            Log.w(TAG, "Invalid course DND recovery state; releasing owned rule", error);
            return new CourseDndSessions(new ArrayList<>());
        }
    }

    private static JSONObject tokens(Context context) {
        try { return new JSONObject(prefs(context).getString("tokens", "{}")); }
        catch (JSONException error) { return new JSONObject(); }
    }

    private static JSONObject pruneTokens(JSONObject input, CourseDndSessions sessions, long now) {
        JSONObject result = new JSONObject();
        for (Iterator<String> keys = input.keys(); keys.hasNext();) {
            String key = keys.next();
            try {
                JSONObject item = input.getJSONObject(key);
                requireCurrent(sessions, item.getString("key"), item.getLong("start"), item.getLong("end"), now);
                result.put(key, item);
            } catch (RuntimeException | JSONException ignored) { }
        }
        return result;
    }

    private static void save(Context context, CourseDndSessions sessions, JSONObject tokens, Boolean published) {
        try {
            JSONArray array = new JSONArray();
            for (CourseDndSessions.Session session : sessions.all()) array.put(new JSONObject()
                    .put("key", session.key).put("start", session.start).put("end", session.end)
                    .put("automatic", session.automatic).put("manual", session.manual).put("suppressed", session.suppressed));
            SharedPreferences.Editor editor = prefs(context).edit().putString("sessions", array.toString())
                    .putString("tokens", tokens.toString());
            if (array.toString().equals(prefs(context).getString("sessions", "[]"))
                    && tokens.toString().equals(prefs(context).getString("tokens", "{}"))
                    && (published == null || published == prefs(context).getBoolean("published", false))) return;
            if (published != null) editor.putBoolean("published", published);
            commit(editor);
        } catch (JSONException error) { throw new IllegalStateException("无法保存课程免打扰状态", error); }
    }

    private static void commit(SharedPreferences.Editor editor) {
        if (!editor.commit()) throw new IllegalStateException("无法保存课程免打扰状态，请检查存储空间");
    }

    private static void ensureValidationLocked(Context context, long now) {
        if (!initializedInProcess) {
            initializedInProcess = true;
            beginValidationLocked(context, false);
        }
        long boundary = prefs(context).getLong("validationBoundary", 0);
        if (boundary > 0 && now >= boundary) beginValidationLocked(context, false);
    }

    private static boolean validationReadyLocked(Context context) {
        ensureValidationLocked(context, System.currentTimeMillis());
        return !prefs(context).getBoolean("validationPending", true);
    }

    private static void beginValidationLocked(Context context, boolean clearLease) {
        beginValidationLocked(context, clearLease, false);
    }

    private static void beginValidationLocked(Context context, boolean clearLease, boolean newSystemEpoch) {
        SharedPreferences prefs = prefs(context);
        initializedInProcess = true;
        boolean alreadyPending = prefs.getBoolean("validationPending", false);
        SharedPreferences.Editor edit = prefs.edit().putBoolean("validationPending", true).putLong("validationBoundary", 0);
        if (!alreadyPending || newSystemEpoch) edit.putInt("validationAttempts", 0).putLong("validationLastAttempt", 0);
        if (clearLease) edit.putLong("validationLease", 0);
        commit(edit);
    }

    private static Bundle awaitingValidationLocked(Context context, String key) {
        reconcileLocked(context, false);
        Bundle result = statusLocked(context, key);
        result.putBoolean("success", false);
        result.putBoolean("revalidationRequired", true);
        result.putString("error", "正在重新核对本次课程，请稍后重试");
        return result;
    }

    private static void requestValidationLocked(Context context, CourseDndSessions sessions, long now) {
        SharedPreferences prefs = prefs(context);
        String token = prefs.getString("validationToken", null);
        long next = CourseDndValidation.nextRetry(prefs.getBoolean("validationPending", true),
                CourseDndValidation.validToken(token), prefs.getInt("validationAttempts", 0),
                prefs.getLong("validationLastAttempt", 0), now);
        if (sessions.all().isEmpty() || next == 0 || next > now) return;
        // Explicit broadcast is asynchronous: no calendar provider callback while holding LOCK.
        commit(prefs.edit().putInt("validationAttempts", prefs.getInt("validationAttempts", 0) + 1)
                .putLong("validationLastAttempt", now));
        context.sendBroadcast(new Intent(CourseDndClient.REVALIDATE)
                .setComponent(new ComponentName("com.coloros.calendar", CourseDndClient.REVALIDATE_RECEIVER))
                .putExtra("token", token));
    }

    private static void schedule(Context context, long at) {
        schedule(context, at, false);
    }

    private static void schedule(Context context, long at, boolean validationRetry) {
        if (alarmInitialized && at == lastAlarmAt && validationRetry == lastAlarmWasValidationRetry) return;
        Intent intent = new Intent(context, CourseDndReceiver.class).setAction(BOUNDARY)
                .putExtra("validationRetry", validationRetry).putExtra("scheduledAt", at);
        PendingIntent pending = PendingIntent.getBroadcast(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        if (at <= 0 || !exactAccess(context)) alarms.cancel(pending);
        else alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending);
        alarmInitialized = true; lastAlarmAt = at; lastAlarmWasValidationRetry = validationRetry;
    }

    private static void retry(Context context) {
        try {
            if (exactAccess(context)) {
                long now = System.currentTimeMillis(), at = now + RETRY_MILLIS;
                long lease = prefs(context).getLong("validationLease", 0);
                long boundary = CourseDndValidation.nextBoundary(load(context), now);
                if (lease > now) at = Math.min(at, lease);
                if (boundary > now) at = Math.min(at, boundary);
                schedule(context, at, true);
            }
        }
        catch (RuntimeException error) { Log.w(TAG, "Cannot schedule course DND recovery", error); }
    }

    private static Bundle failure(Context context, Exception error) {
        Log.w(TAG, "Course DND operation failed", error);
        Bundle result;
        try { result = statusLocked(context, null); } catch (RuntimeException ignored) { result = new Bundle(); }
        result.putBoolean("success", false);
        result.putString("error", error.getMessage() == null ? "课程免打扰操作失败" : error.getMessage());
        return result;
    }

    private static void changed(Context context) {
        try {
            Bundle status = statusLocked(context, null);
            String state = prefs(context).getString("sessions", "[]") + ":" + status.getBoolean("access")
                    + ":" + status.getBoolean("exactAlarmAccess") + ":" + status.getBoolean("ownedActive")
                    + ":" + status.getBoolean("systemActive") + ":" + status.getBoolean("ruleEnabled")
                    + ":" + status.getBoolean("validationPending");
            if (state.equals(lastBroadcastState)) return;
            lastBroadcastState = state;
            context.sendBroadcast(new Intent(CourseDndClient.CHANGED).setPackage("com.coloros.calendar"));
        } catch (RuntimeException error) { Log.w(TAG, "Cannot notify calendar of DND change", error); }
    }
}
