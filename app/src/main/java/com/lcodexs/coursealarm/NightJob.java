package com.lcodexs.coursealarm;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 真正的活：查明天上午的课 -> 清掉旧的「课程闹钟」-> 逐个在时钟里建一次性闹钟 -> 发通知。
 * 日历进程里跑（数据/权限/通知都在这个进程）。
 */
final class NightJob {
    private static final String T = CourseAlarmEntry.TAG;

    private static volatile long lastRunAt = 0L;

    private NightJob() {
    }

    /** 后台线程执行，避免在 onReceive / 点击回调里做 IO */
    static void runAsync(Context ctx, String reason) {
        runAsync(ctx, reason, 1);
    }

    /** dayOffset=1 明天（正式行为）；0 今天（仅调试用，见 Hooks 里的 lspx_days extra） */
    static void runAsync(Context ctx, String reason, int dayOffset) {
        Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        new Thread(() -> {
            try {
                run(app, reason, dayOffset);
            } catch (Throwable t) {
                Logx.e(T, "night job crashed (" + reason + ")", t);
            }
        }, "lspx-course-alarm").start();
    }

    static void run(Context ctx, String reason) {
        run(ctx, reason, 1);
    }

    static void run(Context ctx, String reason, int dayOffset) {
        lastRunAt = System.currentTimeMillis();
        Logx.i(T, "==== night job start reason=" + reason
                + " dayOffset=" + dayOffset
                + " enabled=" + Prefs.enabled()
                + " offset=" + Prefs.offset()
                + " silent=" + Prefs.silent()
                + " vibrate=" + Prefs.vibrate());

        if (!Prefs.enabled()) {
            Logx.i(T, "night job skip: 早晨闹钟未开启");
            Prefs.setLastResult("未开启");
            return;
        }

        List<CourseRepo.Slot> slots = CourseRepo.morningSlots(ctx, dayOffset);
        Logx.i(T, "day+" + dayOffset + " morning slots=" + slots.size());
        for (CourseRepo.Slot s : slots) {
            int[] a = CourseRepo.alarmTimeFor(s, Prefs.offset());
            Logx.i(T, "  slot 课 " + s.describe() + " → 闹钟 "
                    + String.format(Locale.US, "%02d:%02d", a[0], a[1]));
        }

        // 清掉上一轮我们自己设的（用户自己的闹钟不动）
        int removed = AlarmBridge.deleteOurs(ctx);

        if (slots.isEmpty()) {
            Prefs.setLastResult("明天上午无课");
            Notifier.postResult(ctx, slots, 0);
            Logx.i(T, "==== night job end: 明天上午无课, removed=" + removed);
            return;
        }

        int created = 0;
        StringBuilder verify = new StringBuilder();
        for (CourseRepo.Slot s : slots) {
            int[] a = CourseRepo.alarmTimeFor(s, Prefs.offset());
            long id = AlarmBridge.addOnce(ctx, a[0], a[1], s.label(), Prefs.silent());
            if (id > 0) {
                created++;
                AlarmBridge.tune(ctx, id, Prefs.vibrate(), Prefs.silent());
                String line = AlarmBridge.inspect(ctx, id);
                Logx.i(T, "verify " + line);
                if (verify.length() > 0) verify.append(" | ");
                verify.append(line);
            }
        }
        Prefs.setLastResult(String.format(Locale.US, "%s 建 %d 个，删旧 %d 个",
                new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new Date()), created, removed));
        Notifier.postResult(ctx, slots, created);
        Logx.i(T, "==== night job end: created=" + created + " removed=" + removed
                + " verify=" + verify);
    }

    static long lastRunAt() {
        return lastRunAt;
    }

    /** 给 UI 用的一句话现状（不含 IO） */
    static String shortState(Context ctx) {
        return Prefs.lastResult();
    }

    static void postToUi(Runnable r) {
        new Handler(Looper.getMainLooper()).post(r);
    }
}
