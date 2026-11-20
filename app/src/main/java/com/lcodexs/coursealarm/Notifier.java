package com.lcodexs.coursealarm;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

/** 通知用户「闹钟已设置」。宿主 com.coloros.calendar 已 hold POST_NOTIFICATIONS。 */
final class Notifier {
    private static final String T = CourseAlarmEntry.TAG;
    private static final String CHANNEL = "lspx_course_alarm";
    private static final int ID = 0x4341; // "CA"

    private Notifier() {
    }

    private static String channel(AlarmPeriod period) {
        return CHANNEL + period.uiSuffix;
    }

    static void cancel(Context ctx, AlarmPeriod period) {
        try {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (nm != null) nm.cancel(ID + period.ordinal());
        } catch (Exception error) {
            Logx.w(T, "cancel receipt failed: " + error);
        }
    }

    private static void ensureChannel(Context ctx, AlarmPeriod period) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (nm.getNotificationChannel(channel(period)) == null) {
                NotificationChannel ch = new NotificationChannel(channel(period), "课程" + period.title,
                        NotificationManager.IMPORTANCE_DEFAULT);
                ch.setDescription("设置一次性课程闹钟后的回执");
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        } catch (Throwable t) {
            Logx.w(T, "ensureChannel failed: " + t);
        }
    }

    static void post(Context ctx, AlarmPeriod period, String title, String text) {
        try {
            ensureChannel(ctx, period);
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            Intent open = new Intent();
            open.setClassName(ctx.getPackageName(), "com.android.calendar.AllInOneActivity");
            PendingIntent pi = PendingIntent.getActivity(ctx, 0, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            Notification.Builder b = new Notification.Builder(ctx, channel(period))
                    .setSmallIcon(R.drawable.ic_stat_course)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setAutoCancel(true)
                    .setContentIntent(pi)
                    .setWhen(System.currentTimeMillis())
                    .setShowWhen(true);
            nm.notify(ID + period.ordinal(), b.build());
            Logx.i(T, "notify posted: " + title + " | " + text);
        } catch (Throwable t) {
            Logx.e(T, "notify failed: " + t);
        }
    }
}
