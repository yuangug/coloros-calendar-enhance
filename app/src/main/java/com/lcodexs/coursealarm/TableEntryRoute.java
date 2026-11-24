package com.lcodexs.coursealarm;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import java.lang.reflect.Method;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/** Unifies existing shortcuts and in-app course links, while keeping native import previews. */
final class TableEntryRoute {
    static final String MAIN = "com.android.calendar.AllInOneActivity";
    static final String LEGACY = "com.android.calendar.card.schooltimetable.SchoolTimeTableActivity";
    static final String OPEN = "lspx_open_course_table";
    private static long request;

    static boolean isTable(Intent intent) {
        return intent != null && intent.getComponent() != null
                && LEGACY.equals(intent.getComponent().getClassName());
    }

    static boolean isPreview(Intent intent) {
        return intent != null && (intent.hasExtra("courseTimeEntity")
                || intent.hasExtra("course_list") || intent.getBooleanExtra("is_import_course", false));
        // request_import_course is emitted AFTER native creation has committed. It must
        // return to the enhanced page; only the actual recognition preview stays native.
    }

    static Intent mainIntent(Activity activity, Intent source) {
        Intent target = new Intent(Intent.ACTION_MAIN).setClassName(activity.getPackageName(), MAIN)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION).putExtra(OPEN, true);
        if (source != null) {
            if (source.hasExtra("calendar_id")) target.putExtra("calendar_id", source.getLongExtra("calendar_id", -1));
            if (source.hasExtra("selected_week")) target.putExtra("selected_week", source.getIntExtra("selected_week", -1));
        }
        return target;
    }

    static void install(XposedModule module, ClassLoader host) {
        // Normal in-app entries never need to construct the obsolete Activity.
        Method start = Ref.methodOf(Activity.class, "startActivityForResult", Intent.class, int.class, Bundle.class);
        if (start != null) module.hook(start).setId("course_entry_start")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    Intent intent = (Intent)chain.getArg(0);
                    if (chain.getThisObject() instanceof Activity) {
                        int requestCode = (Integer)chain.getArg(1);
                        int routed = HostCourseImport.routeRequest((Activity)chain.getThisObject(), requestCode);
                        if (routed != requestCode) return chain.proceed(new Object[]{intent, routed, chain.getArg(2)});
                    }
                    if (!(chain.getThisObject() instanceof Activity) || !isTable(intent) || isPreview(intent)
                            || ((Integer)chain.getArg(1)) >= 0) return chain.proceed();
                    Activity activity = (Activity)chain.getThisObject();
                    MainTabHook.keepTableMode();
                    Intent target = mainIntent(activity, intent);
                    if (MAIN.equals(activity.getClass().getName())) {
                        consume(activity, target);
                        return null;
                    }
                    return chain.proceed(new Object[]{target, chain.getArg(1), chain.getArg(2)});
                });

        // Old pinned shortcuts are launched by the system, outside the above hook.
        Class<?> legacy = Ref.cls(host, LEGACY);
        if (legacy == null) return;
        for (String name : new String[]{"onCreate", "onNewIntent"}) {
            Method method = Ref.methodOf(legacy, name, name.equals("onCreate") ? Bundle.class : Intent.class);
            if (method == null) continue;
            module.hook(method).setId("course_entry_" + name)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                        Activity activity = (Activity)chain.getThisObject();
                        Intent intent = name.equals("onCreate") ? activity.getIntent() : (Intent)chain.getArg(0);
                        boolean redirect = !isPreview(intent);
                        if (redirect) MainTabHook.keepTableMode();
                        // Preserve super.onCreate/onNewIntent and the host permission lifecycle.
                        Object result = chain.proceed();
                        if (redirect) {
                            activity.startActivity(mainIntent(activity, intent));
                            activity.finish();
                            activity.overridePendingTransition(0, 0);
                            Logx.i(CourseAlarmEntry.TAG, "course entry: legacy " + name + " -> enhanced main");
                        }
                        return result;
                    });
        }
    }

    static boolean consume(Activity activity, Intent intent) {
        if (intent == null || !intent.getBooleanExtra(OPEN, false)) return false;
        intent.removeExtra(OPEN); // onResume must not replay a previously consumed route.
        long token = ++request;
        long id = intent.getLongExtra("calendar_id", -1);
        int week = intent.getIntExtra("selected_week", -1);
        Runnable detail = CourseLiveDetail.consume(activity, intent);
        MainTabHook.keepTableMode();
        Runnable open = () -> {
            if (request != token || activity.isFinishing() || activity.isDestroyed()) return;
            if (week < 0) HostTablePage.defaultEntry(activity);
            MainTabHook.openCoursePage(activity);
            if (week >= 0) HostTablePage.openWeekIndex(week);
            detail.run();
            Logx.i(CourseAlarmEntry.TAG, "course entry: enhanced main ready calendar=" + id + " week=" + week);
        };
        if (id <= 0 || id == TableCtx.hostCurrentTableId(activity)) {
            open.run();
        } else {
            // Never accept an arbitrary/deleted calendar ID from an external Intent.
            new Thread(() -> {
                TableCtx tables = TableCtx.load(activity);
                activity.runOnUiThread(() -> {
                    if (request != token || activity.isDestroyed() || activity.isFinishing()) return;
                    if (!tables.courseCalIds.contains(id)) {
                        CurriculumIo.toast(activity, "目标课表已不存在");
                        return;
                    }
                    activity.getSharedPreferences("com.coloros.calendar_preferences", 0).edit()
                            .putLong("sp_key_current_course_table", id).apply();
                    open.run();
                });
            }, "course-entry-load").start();
        }
        return true;
    }
}
