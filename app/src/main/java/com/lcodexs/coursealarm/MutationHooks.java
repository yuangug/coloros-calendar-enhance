package com.lcodexs.coursealarm;

import android.app.Activity;
import android.content.Context;
import java.lang.reflect.Method;
import java.util.*;
import io.github.libxposed.api.*;

final class MutationHooks {
    static void install(XposedModule module, ClassLoader loader, Context app) {
        CourseSyncGate.install(module, loader, app);
        CourseMutations.init(app);
        // Hook base lifecycle too: host startup can resume before callback registration.
        for (String name : new String[]{"onResume", "onPause"}) {
            Method lifecycle = Ref.methodOf(Activity.class, name);
            module.hook(lifecycle).setId("undo_lifecycle_" + name).intercept(chain -> {
                Object result=chain.proceed(); Activity activity=(Activity)chain.getThisObject();
                if (name.equals("onResume")) UndoBar.resumed(activity); else UndoBar.paused(activity);
                return result;
            });
        }
        hook(module, loader, "CreateCourseActivity", "deleteCourse", false);
        hook(module, loader, "CourseTableManageActivity", "deleteSelectedItem", true);
        Class<?> nativePage = Ref.cls(loader, "com.android.calendar.card.schooltimetable.SchoolTimeTableActivity");
        Method nativeDelete = nativePage == null ? null : Ref.methodByArity(nativePage, "onDeleteCourse", 1);
        if (nativeDelete != null) module.hook(nativeDelete).setId("undo_native_course_delete").intercept(chain -> {
            Activity activity = (Activity)chain.getThisObject();
            if (Boolean.TRUE.equals(Ref.field(activity, "isImportCourse"))) return chain.proceed();
            Object event = Ref.callName(chain.getArg(0), "getEvent");
            Object id = Ref.callName(event, "getId");
            if (!(id instanceof Number) || ((Number)id).longValue() <= 0) {
                CurriculumIo.toast(activity, "无法识别原始课程，请重新打开课表");
                return null;
            }
            CourseMutations.deleteCourse(activity, ((Number)id).longValue(), false);
            return null;
        });
    }
    private static void hook(XposedModule module, ClassLoader loader, String cls, String name, boolean tables) {
        Class<?> type = Ref.cls(loader, "com.android.calendar.card.schooltimetable." + cls);
        Method method = Ref.methodOf(type, name);
        if (method == null) { Logx.w(CourseAlarmEntry.TAG, "撤回入口不兼容: " + cls); return; }
        module.hook(method).setId("undo_" + name).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Activity activity = (Activity) chain.getThisObject();
            try {
                Object vm = Ref.field(activity, "viewModel");
                if (tables) {
                    Object items = Ref.callName(vm, "getItems");
                    if (!(items instanceof List)) throw new IllegalStateException("无法读取待删除课表");
                    List<Long> ids = new ArrayList<>();
                    for (Object item : (List<?>)items) if (Boolean.TRUE.equals(Ref.callName(item, "getSelected"))) {
                        Object calendar = Ref.callName(item, "getCalendarEntity");
                        Object id = Ref.callName(calendar, "getId");
                        if (!(id instanceof Number)) throw new IllegalStateException("无法识别课表");
                        ids.add(((Number)id).longValue());
                    }
                    CourseMutations.deleteTables(activity, ids);
                } else {
                    Object course = Ref.callName(vm, "getSelectedCourseEntity");
                    Object event = Ref.callName(course, "getEvent");
                    Object id = Ref.callName(event, "getId");
                    if (!(id instanceof Number) || ((Number)id).longValue() <= 0) throw new IllegalStateException("无法识别原始课程");
                    CourseMutations.deleteCourse(activity, ((Number)id).longValue());
                }
            } catch (Exception e) { CurriculumIo.toast(activity, "未删除：" + e.getMessage()); Logx.e(CourseAlarmEntry.TAG, "delete entry", e); }
            return null;
        });
    }
}
