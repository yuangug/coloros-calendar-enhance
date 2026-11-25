package com.lcodexs.coursealarm;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;
import java.util.TreeSet;

/** Module-owned alarm: ColorOS can refuse Calendar's provider cold start during a game. */
public final class CourseIslandWake extends BroadcastReceiver {
    private static final String ACTION = CourseDndClient.PACKAGE + ".ISLAND_BOUNDARY";
    private static final String PREFS = "course_island_deadlines_v1";

    static synchronized void replace(Context app, long[] times, String token) {
        if (times == null || !CourseDndValidation.validToken(token)) return;
        TreeSet<Long> future = new TreeSet<>();
        long now = System.currentTimeMillis();
        for (long t : times) if (t > now && t <= now + 7 * 86400000L) future.add(t);
        StringBuilder encoded = new StringBuilder();
        for (long t : future) encoded.append(t).append(',');
        SharedPreferences p = app.getSharedPreferences(PREFS, 0);
        if (!encoded.toString().equals(p.getString("times","")) || !token.equals(p.getString("token",""))) {
            p.edit().putString("times", encoded.toString()).putString("token", token).apply();
        }
        arm(app, p, now, false);
    }

    private static void arm(Context app, SharedPreferences p, long now, boolean force) {
        long next = 0;
        for (String part : p.getString("times", "").split(",")) {
            if (part.isEmpty()) continue;
            long at = Long.parseLong(part);
            if (at > now) { next = at; break; }
        }
        AlarmManager alarm = app.getSystemService(AlarmManager.class);
        boolean exact = Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms();
        if (!force && next == p.getLong("armed",0) && exact == p.getBoolean("exact", false)) return;
        PendingIntent pi = PendingIntent.getBroadcast(app, 0x49534c44,
                new Intent(app, CourseIslandWake.class).setAction(ACTION),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        if (next == 0) alarm.cancel(pi);
        else if (exact) {
            try { alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi); }
            catch (SecurityException revoked) {
                exact = false;
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi);
            }
        }
        else alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi);
        p.edit().putLong("armed", next).putBoolean("exact", exact).apply();
    }

    static void revalidate(Context app, String token) {
        app.sendBroadcast(new Intent(CourseDndClient.REVALIDATE)
                .setComponent(new ComponentName("com.coloros.calendar", CourseDndClient.REVALIDATE_RECEIVER))
                .putExtra("token", token).putExtra("islandBoundary", true));
    }

    @Override public void onReceive(Context app, Intent intent) {
        synchronized (CourseIslandWake.class) {
            SharedPreferences p = app.getSharedPreferences(PREFS, 0);
            String token = p.getString("token", "");
            if (!CourseDndValidation.validToken(token)) return;
            long now = System.currentTimeMillis();
            boolean boundary = ACTION.equals(intent.getAction());
            boolean hadPlan = p.getLong("armed",0) != 0;
            arm(app, p, now, true);
            if (!hadPlan && p.getString("times", "").isEmpty()) return;
            Log.i("CourseAstraIsland", boundary ? "module alarm: course boundary" : "module alarm: revalidate");
            revalidate(app, token);
            CourseDndBroker.WORKER.execute(CourseAstraBroker::onBoundary);
            // Give Calendar time to revalidate the table and deliver this phase while
            // the module has a live broadcast. The alarm never publishes stale content.
            PendingResult pending = goAsync();
            if (pending != null) new android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed(pending::finish, 6000);
        }
    }
}
