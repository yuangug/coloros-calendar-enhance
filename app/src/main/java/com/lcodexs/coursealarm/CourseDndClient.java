package com.lcodexs.coursealarm;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import org.json.JSONArray;

/** Host-side IPC. The policy permission and automatic rule belong to the installed module UID. */
public final class CourseDndClient {
    public static final String PACKAGE = "io.github.yuangug.coursealarm";
    public static final String AUTHORITY = PACKAGE + ".course_dnd";
    public static final String CHANGED = PACKAGE + ".DND_CHANGED";
    public static final String REVALIDATE = PACKAGE + ".LIVE_REVALIDATE";
    public static final String REVALIDATE_RECEIVER = "com.android.calendar.alerts.AlertReceiver";
    private static final Uri URI = Uri.parse("content://" + AUTHORITY);
    static final String CONTINUATION = "calendarContinuation";
    private static long lastFailureLog;

    private CourseDndClient() { }

    public static Bundle status(Context context) { return status(context, null); }

    static boolean active(Bundle status) {
        return status.getBoolean("systemActive") || status.getBoolean("ownedActive") || status.getBoolean("sessionActive");
    }

    static String label(boolean active, boolean reserved, boolean before) {
        return active ? "免打扰已开启" : before ? (reserved ? "取消预约" : "预约免打扰") : "开启免打扰";
    }

    public static Bundle status(Context context, String key) {
        Bundle request = new Bundle();
        if (key != null) request.putString("key", key);
        Bundle status = call(context, "status", request);
        // A stopped module cannot report its own rule, but the calendar can still observe system DND.
        if (!status.getBoolean("success")) try {
            int filter = context.getSystemService(android.app.NotificationManager.class).getCurrentInterruptionFilter();
            status.putBoolean("systemActive", filter != android.app.NotificationManager.INTERRUPTION_FILTER_ALL
                    && filter != android.app.NotificationManager.INTERRUPTION_FILTER_UNKNOWN);
        } catch (RuntimeException ignored) { }
        return status;
    }

    /** Complete valid schedule; absence releases a deleted or no-longer-selected occurrence. */
    public static Bundle sync(Context context, JSONArray occurrences) {
        return sync(context, occurrences, null);
    }

    /** The private token authenticates later module -> calendar validation wake-ups. */
    public static Bundle sync(Context context, JSONArray occurrences, String validationToken) {
        return sync(context, occurrences, validationToken, null);
    }
    static Bundle sync(Context context, JSONArray occurrences, String validationToken, long[] islandDeadlines) {
        Bundle request = new Bundle();
        request.putString("occurrences", occurrences.toString());
        if (validationToken != null) request.putString("validationToken", validationToken);
        if (islandDeadlines != null) request.putLongArray("islandDeadlines", islandDeadlines);
        return call(context, "sync", request);
    }

    /** A failed host read is not an authoritative deletion of all occurrence/suppression state. */
    public static Bundle invalidate(Context context) { return call(context, "invalidate", new Bundle()); }

    public static Bundle toggle(Context context, String key, long start, long end) {
        return call(context, "toggle", occurrence(key, start, end));
    }

    public static Bundle enable(Context context, String key, long start, long end) {
        return call(context, "enable", occurrence(key, start, end));
    }

    public static Intent permissionIntent(Context context) {
        return new Intent(PACKAGE + ".DND_PERMISSION")
                .setComponent(new ComponentName(PACKAGE, CourseDndPermissionActivity.class.getName()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /** A module-owned action bound to its current enable/stop label, using only an opaque nonce. */
    public static PendingIntent action(Context context, String key, long start, long end) {
        return action(context, key, start, end, status(context, key).getBoolean("reserved"), null);
    }

    public static PendingIntent action(Context context, String key, long start, long end, boolean active,
                                       PendingIntent continuation) {
        Bundle request = occurrence(key, start, end);
        request.putBoolean("enable", !active);
        Bundle result = call(context, "action", request);
        if (!result.getBoolean("success")) {
            if (active || continuation == null || !result.getBoolean("initializationRequired")) return null;
            // A newly installed/stopped module may require a user-initiated launch on this ROM.
            Intent permission = permissionIntent(context).setData(Uri.parse("course-dnd://authorize/" + Uri.encode(key)))
                    .putExtra(CONTINUATION, continuation);
            return PendingIntent.getActivity(context, 0, permission, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }
        result.setClassLoader(PendingIntent.class.getClassLoader());
        return result.getParcelable("pendingIntent");
    }

    private static Bundle occurrence(String key, long start, long end) {
        Bundle request = new Bundle();
        request.putString("key", key); request.putLong("start", start); request.putLong("end", end);
        return request;
    }

    private static Bundle call(Context context, String method, Bundle request) {
        try {
            request.putInt("protocol", 1);
            Bundle result = context.getContentResolver().call(URI, method, null, request);
            if (result == null) throw new IllegalStateException("模块免打扰服务没有返回结果");
            return result;
        } catch (RuntimeException error) {
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - lastFailureLog > 30000 || lastFailureLog == 0) {
                lastFailureLog = now; android.util.Log.w("CourseDnd", "Module IPC " + method + " failed", error);
            }
            Bundle result = new Bundle();
            result.putBoolean("success", false);
            result.putBoolean("supported", false);
            result.putBoolean("initializationRequired", true);
            result.putString("error", "请先打开免打扰权限页面完成模块初始化");
            return result;
        }
    }
}
