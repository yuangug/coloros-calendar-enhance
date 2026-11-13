package com.lcodexs.coursealarm;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import java.util.List;
import java.util.Locale;

/** 通知用户「闹钟已设置」。宿主 com.coloros.calendar 已 hold POST_NOTIFICATIONS。 */
final class Notifier {
    private static final String T = CourseAlarmEntry.TAG;
    private static final String CHANNEL = "lspx_course_alarm";
    private static final int ID = 0x4341; // "CA"

    private Notifier() {
    }

    static void ensureChannel(Context ctx) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL, "课程早晨闹钟",
                        NotificationManager.IMPORTANCE_DEFAULT);
                ch.setDescription("每晚自动为次日早课设置一次性闹钟后的回执");
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        } catch (Throwable t) {
            Logx.w(T, "ensureChannel failed: " + t);
        }
    }

    static void post(Context ctx, String title, String text) {
        try {
            ensureChannel(ctx);
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            Intent open = new Intent();
            open.setClassName(ctx.getPackageName(), "com.android.calendar.AllInOneActivity");
            PendingIntent pi = PendingIntent.getActivity(ctx, 0, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            Notification.Builder b = new Notification.Builder(ctx, CHANNEL)
                    .setSmallIcon(ctx.getApplicationInfo().icon)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setAutoCancel(true)
                    .setContentIntent(pi)
                    .setWhen(System.currentTimeMillis())
                    .setShowWhen(true);
            nm.notify(ID, b.build());
            Logx.i(T, "notify posted: " + title + " | " + text);
        } catch (Throwable t) {
            Logx.e(T, "notify failed: " + t);
        }
    }

    static void postResult(Context ctx, List<CourseRepo.Slot> slots, int created) {
        if (slots == null || slots.isEmpty()) {
            post(ctx, "今晚没设闹钟", "明天上午没有课，明早不设闹钟。");
            return;
        }
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (CourseRepo.Slot s : slots) {
            int[] a = CourseRepo.alarmTimeFor(s, Prefs.offset());
            if (i++ > 0) sb.append("\n");
            sb.append(String.format(Locale.US, "%02d:%02d", a[0], a[1]));
            if (s.title != null && !s.title.isEmpty()) sb.append("  ").append(s.title);
            if (s.location != null && !s.location.isEmpty()) sb.append(" @").append(s.location);
            sb.append(String.format(Locale.US, "（课 %02d:%02d，提前 %d 分钟）",
                    s.hour, s.minute, Prefs.offset()));
        }
        String title = created > 0 ? "已设置明早闹钟 " + created + " 个" : "闹钟设置未完成";
        post(ctx, title, sb.toString());
    }
}
