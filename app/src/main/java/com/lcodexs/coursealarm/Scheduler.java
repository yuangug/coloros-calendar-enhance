package com.lcodexs.coursealarm;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * 每晚 21:00 的定时器。
 *
 * 关键点：PendingIntent 必须指向**宿主 manifest 里真实存在的组件**，否则进程被杀后
 * 广播没人生效、闹钟也不会把我们叫起来。这里借宿主的
 * com.android.calendar.alerts.AlertReceiver（exported=true，无权限要求），
 * 用自造 action 作标记，在 Hooks 里拦下来短路，不执行宿主逻辑。
 *
 * 权限：com.coloros.calendar 已 hold android.permission.USE_EXACT_ALARM（granted=true）。
 */
final class Scheduler {
    private static final String T = CourseAlarmEntry.TAG;

    static final String ACTION_TICK = "com.lcodexs.coursealarm.NIGHT_TICK";
    private static final String ACTION_FOLLOW_UP = "com.lcodexs.coursealarm.FOLLOW_UP_";
    static final String TICK_RECEIVER = "com.android.calendar.alerts.AlertReceiver";
    private static final int REQ = 0x43410001;

    private static final int TICK_HOUR = 21;
    private static final int TICK_MINUTE = 0;

    private Scheduler() {
    }

    static long nextTickMillis() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, TICK_HOUR);
        c.set(Calendar.MINUTE, TICK_MINUTE);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= System.currentTimeMillis() + 5_000L) {
            c.add(Calendar.DAY_OF_YEAR, 1);
        }
        return c.getTimeInMillis();
    }

    private static PendingIntent tickIntent(Context ctx, AlarmPeriod period) {
        Intent i = new Intent(period == null ? ACTION_TICK : ACTION_FOLLOW_UP + period.name());
        i.setClassName(ctx.getPackageName(), TICK_RECEIVER);
        i.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        return PendingIntent.getBroadcast(ctx, period == null ? REQ : REQ + 1 + period.ordinal(), i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static AlarmPeriod followUpPeriod(String action) {
        for (AlarmPeriod period : AlarmPeriod.values()) {
            if ((ACTION_FOLLOW_UP + period.name()).equals(action)) return period;
        }
        return null;
    }

    static void update(Context ctx) {
        if (Prefs.anyAlarmEnabled()) arm(ctx, null, nextTickMillis());
        else cancel(ctx, null);
    }

    /** Revisit a date-less alarm when today's airing has passed, without touching the other session. */
    static void followUp(Context ctx, AlarmPeriod period, long at) {
        if (at > 0) arm(ctx, period, at);
        else cancel(ctx, period);
    }

    private static void arm(Context ctx, AlarmPeriod period, long at) {
        PendingIntent pi = tickIntent(ctx, period);
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            Logx.w(T, "AlarmManager null");
            return;
        }
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            Logx.i(T, "alarm planning tick " + period + " armed at " + fmt(at));
        } catch (SecurityException se) {
            Logx.w(T, "exact alarm denied, fallback to inexact: " + se);
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } catch (Throwable t) {
            Logx.e(T, "arm nightly tick failed", t);
        }
    }

    private static void cancel(Context ctx, AlarmPeriod period) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.cancel(tickIntent(ctx, period));
        } catch (Throwable t) {
            Logx.w(T, "cancel tick failed: " + t);
        }
    }

    static String fmt(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(millis));
    }
}
