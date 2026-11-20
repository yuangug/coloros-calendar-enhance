package com.lcodexs.coursealarm;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Serializes all writes to the Clock provider, including closing either of the two switches. */
final class NightJob {
    private static final String T = CourseAlarmEntry.TAG;
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor(
            r -> new Thread(r, "course-alarm-planning"));
    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.US);
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.US);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Context hostApp;
    private static final Runnable REPLAN_TABLE = () -> runAsync(hostApp, "course-table-changed");
    private static final SharedPreferences.OnSharedPreferenceChangeListener TABLE_CHANGED = (prefs, key) -> {
        if ("sp_key_current_course_table".equals(key)) onTableChanged();
    };

    private NightJob() {}

    static void observeTableSelection(Context ctx) {
        hostApp = ctx;
        for (String file : TableCtx.CURRENT_TABLE_PREFS) {
            ctx.getSharedPreferences(file, Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(TABLE_CHANGED);
        }
    }

    static void onTableChanged() {
        MAIN.removeCallbacks(REPLAN_TABLE);
        if (hostApp != null && Prefs.anyAlarmEnabled()) MAIN.postDelayed(REPLAN_TABLE, 400);
    }

    static void runAsync(Context ctx, String reason) {
        enqueue(ctx, reason, null, false, null);
    }

    static void runAsync(Context ctx, String reason, AlarmPeriod period) {
        runAsync(ctx, reason, period, null);
    }

    static void runAsync(Context ctx, String reason, AlarmPeriod period, Runnable onCompleted) {
        enqueue(ctx, reason, period, false, onCompleted);
    }

    static void runTodayAsync(Context ctx) {
        enqueue(ctx, "debug-today", null, true, null);
    }

    private static void enqueue(Context ctx, String reason, AlarmPeriod requested, boolean todayOnly,
                                Runnable onCompleted) {
        Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        WORK.execute(() -> {
            try {
                run(app, reason, requested, todayOnly);
            } catch (Exception error) {
                Logx.e(T, "alarm planning failed: " + reason, error);
            } finally {
                if (onCompleted != null) MAIN.post(onCompleted);
            }
        });
    }

    private static void run(Context ctx, String reason, AlarmPeriod requested, boolean todayOnly) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        CourseRepo.Sessions courses = null;
        boolean queried = false;
        for (AlarmPeriod period : AlarmPeriod.values()) {
            if (requested != null && requested != period) continue;
            try {
                if (!Prefs.enabled(period)) {
                    if (requested == period) {
                        Scheduler.followUp(ctx, period, 0);
                        Prefs.setLastResult(period, AlarmBridge.deleteOurs(ctx, period, -1) >= 0
                                ? "已关闭" : "未能移除已有闹钟");
                        Notifier.cancel(ctx, period);
                    }
                    continue;
                }
                if (!queried) {
                    queried = true;
                    courses = CourseRepo.read(ctx, today, zone);
                }
                if (courses == null) throw new IllegalStateException("无法读取课程安排");
                if (period == AlarmPeriod.AFTERNOON && !courses.afternoonTimingKnown) {
                    throw new IllegalStateException("无法读取下午节次");
                }
                if (!today.equals(LocalDate.now(zone)) || !zone.equals(ZoneId.systemDefault())) {
                    enqueue(ctx, "date-changed-during-planning", requested, todayOnly, null);
                    return;
                }
                reconcile(ctx, period, courses, zone, todayOnly);
            } catch (Exception error) {
                Logx.e(T, period.title + " planning failed: " + reason, error);
                Prefs.setLastResult(period, error.getMessage() == null ? "排程失败" : error.getMessage());
            }
        }
    }

    private static void reconcile(Context ctx, AlarmPeriod period, CourseRepo.Sessions courses,
                                  ZoneId zone, boolean todayOnly) {
        int offset = Prefs.offset(period);
        Instant now = Instant.now();
        CourseRepo.Slot today = courses.first(0, period);
        CourseRepo.Slot tomorrow = todayOnly ? null : courses.first(1, period);
        ZonedDateTime todayAt = today == null ? null : AlarmTime.forCourse(today.start(), offset, zone);
        ZonedDateTime tomorrowAt = tomorrow == null ? null : AlarmTime.forCourse(tomorrow.start(), offset, zone);
        AlarmTime.Plan plan = AlarmTime.plan(now, todayAt, tomorrowAt, Instant.ofEpochMilli(Scheduler.nextTickMillis()));
        ZonedDateTime target = plan.target();
        CourseRepo.Slot course = plan.useToday() ? today : tomorrow;
        if (!todayOnly) {
            Scheduler.followUp(ctx, period, plan.followUpMillis());
        }

        if (!plan.representable()) {
            if (AlarmBridge.deleteOurs(ctx, period, -1) < 0) {
                throw new IllegalStateException("未能移除已有闹钟");
            }
            Prefs.setLastResult(period, target != null ? "等待排程"
                    : todayOnly ? "今天" + period.session + "无待设闹钟"
                    : "明天" + period.session + "无课");
            if (target == null && !todayOnly) {
                Notifier.post(ctx, period, "未设置" + period.title, "明天" + period.session + "没有课。");
            } else if (!todayOnly) {
                Notifier.cancel(ctx, period);
            }
            return;
        }

        String label = period.labelPrefix + CLOCK.format(course.start().atZone(zone));
        boolean silent = Prefs.silent(period);
        long id = AlarmBridge.addOnce(ctx, target, label, silent);
        if (id <= 0) throw new IllegalStateException("时钟未能创建闹钟");
        if (!AlarmBridge.tune(ctx, id, Prefs.vibrate(period), silent)) {
            AlarmBridge.delete(ctx, id);
            throw new IllegalStateException("未能应用闹钟响铃或振动设置");
        }
        if (AlarmBridge.deleteOurs(ctx, period, id) < 0) {
            AlarmBridge.delete(ctx, id);
            throw new IllegalStateException("未能替换已有闹钟");
        }
        String time = DISPLAY.format(target);
        String name = course.title() == null || course.title().isEmpty() ? "" : " · " + course.title();
        String room = course.location() == null || course.location().isEmpty() ? "" : " @" + course.location();
        Prefs.setLastResult(period, "已设置 " + time);
        Notifier.post(ctx, period, "已设置" + period.title, time + name + room + "（课前 " + offset + " 分钟）");
    }
}
