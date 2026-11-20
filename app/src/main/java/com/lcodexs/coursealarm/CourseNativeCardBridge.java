package com.lcodexs.coursealarm;

import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Narrow, instance-scoped bridge for the calendar's existing SystemUI card. */
final class CourseNativeCardBridge {
    static final String QUERY = "lspx.course.nativecard.v1";
    static final String CAPABILITY = "lspx.course.nativecard.capability.v1";
    static final String CHANGED = "io.github.yuangug.coursealarm.NATIVE_CARD_CHANGED";
    static final String SYSTEM_UI = "com.android.systemui";
    static final String FLUID = "fluid";

    static Bundle call(Context context, String method, Bundle query) {
        int caller = Binder.getCallingUid();
        try {
            int expected = context.getPackageManager().getApplicationInfo(SYSTEM_UI, 0).uid;
            if (caller != expected) throw new SecurityException("SystemUI UID required");
        } catch (android.content.pm.PackageManager.NameNotFoundException error) {
            throw new SecurityException("SystemUI unavailable", error);
        }
        Bundle safe = query == null ? Bundle.EMPTY : new Bundle(query);
        String service = safe.getString("serviceInstanceId", "");
        String row = safe.getString("rowKey", "");
        String surface = safe.getString("surface", "notification");
        boolean fluid = FLUID.equals(surface);
        if ((!fluid && !"notification".equals(surface)) || safe.getInt("protocol", 1) != 1 || !validRow(row, service))
            return unavailable("invalid identity");
        FutureTask<Bundle> result = new FutureTask<>(() -> {
            CourseOccurrence item = fluid ? CourseLiveSeedlingState.findVisibleService(service)
                    : CourseLiveSeedlingState.confirmNotification(service);
            if (item == null) return unavailable("instance unavailable");
            if (CAPABILITY.equals(method)) {
                boolean rendered = safe.getBoolean("rendered");
                String reason = safe.getString("reason", "");
                if (reason.length() > 160) reason = reason.substring(0, 160);
                context.getSharedPreferences("lspx_course_live_capabilities", Context.MODE_PRIVATE).edit()
                        .putBoolean(fluid ? "fluidRendered" : "nativeRendered", rendered)
                        .putString(fluid ? "fluidReason" : "nativeReason", reason)
                        .putLong(fluid ? "fluidReported" : "reported", System.currentTimeMillis()).apply();
                Bundle acknowledged = new Bundle(); acknowledged.putBoolean("success", true); return acknowledged;
            }
            return CourseLiveCoordinator.nativeCardState(item, service, !fluid);
        });
        long identity = Binder.clearCallingIdentity();
        try {
            CourseLiveCoordinator.execute(result);
            return result.get(2500, TimeUnit.MILLISECONDS);
        } catch (Exception error) {
            result.cancel(false);
            return unavailable("state pending");
        } finally { Binder.restoreCallingIdentity(identity); }
    }

    static boolean validRow(String row, String service) {
        if (row.length() > 512 || service.isEmpty() || service.length() > 160) return false;
        String[] parts = row.split("\\|", -1);
        if (parts.length != 4 || !"268452047".equals(parts[0]) || !service.equals(parts[1])) return false;
        try { return Integer.parseInt(parts[2]) >= 0 && Long.parseLong(parts[3]) > 0; }
        catch (NumberFormatException ignored) { return false; }
    }

    static void changed(Context context) {
        try { context.sendBroadcast(new Intent(CHANGED).setPackage(SYSTEM_UI)); }
        catch (RuntimeException error) { Logx.w(CourseAlarmEntry.TAG, "native card state signal: " + error); }
    }

    private static Bundle unavailable(String reason) {
        Bundle result = new Bundle(); result.putInt("protocol", 1);
        result.putBoolean("supported", false); result.putString("reason", reason); return result;
    }
}
