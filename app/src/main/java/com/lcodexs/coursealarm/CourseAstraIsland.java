package com.lcodexs.coursealarm;

import android.app.PendingIntent;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/** Calendar-side requests; the SDK must run as the installed module, which holds the publish permission. */
final class CourseAstraIsland {
    static final String PREFIX = "coursealarm.course.";
    static final String PUBLISH = "astra.publish.v1", END = "astra.end.v1";
    static final String ACCEPTED = "accepted", CONNECTING = "connecting", UNAVAILABLE = "unavailable";
    private static final Uri ENDPOINT = Uri.parse("content://" + CourseDndClient.AUTHORITY);

    private CourseAstraIsland() { }

    static String publish(Context app, CourseOccurrence item, CourseLiveSettings.State settings,
                          PendingIntent open, PendingIntent dismiss, PendingIntent dnd, boolean dndActive) {
        Bundle request = request(item.key);
        request.putString("title", limit(item.title, 128));
        request.putString("room", limit(item.room.trim(), 128));
        request.putString("teacher", limit(item.teacher.trim(), 128));
        request.putLong("start", item.start);
        request.putLong("end", item.end);
        request.putBoolean("showProgress", settings.showProgress);
        request.putString("style", settings.astraStyle.id);
        request.putLong("expires", item.expiresAt(settings.retainMinutes));
        request.putParcelable("open", open);
        request.putParcelable("dismiss", dismiss);
        if (settings.showDndButton && dnd != null) {
            request.putParcelable("dnd", dnd);
            request.putBoolean("dndActive", dndActive);
        }
        return call(app, PUBLISH, request);
    }

    static void end(Context app, String key) {
        if (key != null && !key.isEmpty()) call(app, END, request(key));
    }

    private static String call(Context app, String method, Bundle request) {
        try {
            Bundle result = app.getContentResolver().call(ENDPOINT, method, null, request);
            return result == null ? UNAVAILABLE : result.getString("result", UNAVAILABLE);
        } catch (RuntimeException error) {
            Logx.w(CourseAlarmEntry.TAG, "AstraFlow module IPC " + method + ": " + error.getClass().getSimpleName());
            return UNAVAILABLE;
        }
    }

    private static Bundle request(String key) {
        Bundle request = new Bundle();
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            request.putString("id", PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(hash));
            return request;
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    static String limit(String text, int max) {
        int count = text.codePointCount(0, text.length());
        return count <= max ? text : text.substring(0, text.offsetByCodePoints(0, max));
    }
}
