package com.lcodexs.coursealarm;

import android.app.Activity;
import android.content.Intent;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Opens the same native sheet as a course in the timetable, using a verified click receipt. */
final class CourseLiveDetail {
    private static final String EXTRA = "lspx_course_live_detail_token";
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor(r -> new Thread(r, "course-live-detail"));
    private static Pending pending;
    private record Pending(String token, CourseOccurrence item, long deadline) { }

    static boolean open(Activity alert, CourseOccurrence item) {
        if (TableCtx.hostCurrentTableId(alert) != item.calendarId) return false;
        Pending request = new Pending(UUID.randomUUID().toString(), item, SystemClock.uptimeMillis() + 10000);
        pending = request;
        try {
            alert.startActivity(TableEntryRoute.mainIntent(alert, null).putExtra(EXTRA, request.token));
            return true;
        } catch (RuntimeException error) {
            if (pending == request) pending = null;
            Logx.e(CourseAlarmEntry.TAG, "course notification details route", error);
            return false;
        }
    }

    static Runnable consume(Activity activity, Intent intent) {
        String token = intent.getStringExtra(EXTRA);
        intent.removeExtra(EXTRA);
        Pending request = pending;
        if (request == null || !request.token.equals(token)) return () -> { };
        pending = null;
        if (SystemClock.uptimeMillis() > request.deadline) return () -> { };
        return () -> WORK.execute(() -> read(activity, request.item));
    }

    private static void read(Activity activity, CourseOccurrence item) {
        try {
            HostTableData data = HostTableData.load(activity);
            if (data.calendarId != item.calendarId || !item.backend.equals(data.backendAuthority)
                    || !CourseLiveSettings.readLoaded(activity, data.calendarId, data.calendar,
                            CalendarBackend.LOCAL.equals(data.backendAuthority)).identity.equals(item.tableIdentity))
                throw new IllegalStateException("当前课表已变化");
            ClassLoader host = activity.getClassLoader();
            ArrayList<Object> course = new ArrayList<>(1);
            for (Object entity : data.buildCourseList(host)) {
                if (CourseLiveCoordinator.number(Ref.callName(entity, "getEvent"), "getId") == item.eventId) {
                    course.add(entity);
                    break;
                }
            }
            if (course.isEmpty()) throw new IllegalStateException("该课程已不存在");
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed() || !HostTablePage.showing()
                        || TableCtx.hostCurrentTableId(activity) != item.calendarId) return;
                java.time.ZoneId zone = java.time.ZoneId.systemDefault();
                long days = java.time.temporal.ChronoUnit.DAYS.between(
                        java.time.Instant.ofEpochMilli(data.termBeginMs).atZone(zone).toLocalDate(),
                        java.time.Instant.ofEpochMilli(item.start).atZone(zone).toLocalDate());
                if (data.termBeginMs > 0 && days >= 0 && days / 7 < data.weekCount)
                    HostTablePage.openWeekIndex((int) (days / 7));
                if (!CourseInfoSheet.show(activity, host, course, 0, data.tableJson))
                    CurriculumIo.toast(activity, "无法打开课程详情");
            });
        } catch (Exception error) {
            Logx.e(CourseAlarmEntry.TAG, "course notification details read", error);
            activity.runOnUiThread(() -> {
                if (!activity.isDestroyed() && !activity.isFinishing()) CurriculumIo.toast(activity, "无法打开课程详情：" + error.getMessage());
            });
        }
    }
}
