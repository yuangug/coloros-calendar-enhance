package com.lcodexs.coursealarm;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.provider.CalendarContract;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 写库之后「让宿主自己刷新」的那条通道 —— 三条一起打，缺一条就会有人看不到变化：
 *
 *  1. ContentResolver.notifyChange 标准的 Events / Calendars / Instances
 *  2. ContentResolver.notifyChange ColorOS 自己的 authority
 *     （content://com.coloros.calendar/{calendars,events}）—— 宿主 SchoolTimeTableActivity
 *     在 onResume 里注册的 ContentObserver 监听的就是这一族 URI
 *  3. 宿主进程内的 LiveDataBus：post "refresh_course_table" / "db_change_event"
 *     （宿主导入课程后自己就是这么刷的，见 ImportCourseTableHelper#o 与
 *      SchoolTimeTableActivity#registerObserver）
 *
 * 模块和宿主同进程，所以第 3 条是**同步生效**的那一条：只要当前页是宿主的课表页
 * （独立课表页 SchoolTimeTableActivity），refreshCourseData() 立刻重读数据库。
 * 主页里由模块挂上去的课表页则走 HostTablePage.refresh()。
 */
final class HostBus {
    private static final String T = CourseAlarmEntry.TAG;

    static final Uri CALENDARS_OPLUS = Uri.parse("content://com.coloros.calendar/calendars");
    static final Uri EVENTS_OPLUS = Uri.parse("content://com.coloros.calendar/events");

    private static final String CLS_BUS = "com.coloros.calendar.foundation.buslib.LiveDataBus";

    private HostBus() {
    }

    /** 只要数据库变了就打一遍通知（不弹任何 UI） */
    static void notifyUris(Context ctx) {
        if (ctx == null) return;
        try {
            android.content.ContentResolver cr = ctx.getContentResolver();
            cr.notifyChange(TableCtx.EVENTS_URI, null);
            cr.notifyChange(TableCtx.EVENTS_URI_LEGACY, null);
            cr.notifyChange(EVENTS_OPLUS, null);
            cr.notifyChange(CalendarContract.Calendars.CONTENT_URI, null);
            cr.notifyChange(CALENDARS_OPLUS, null);
            cr.notifyChange(CalendarContract.Instances.CONTENT_URI, null);
            Logx.i(T, "notifyChange: events/calendars/instances（标准 + oplus）");
        } catch (Throwable t) {
            Logx.w(T, "notifyChange failed: " + t);
        }
    }

    /**
     * 往宿主的 LiveDataBus 发一条事件。返回 false 表示宿主这一版没有这条总线
     * （那就只剩 notifyChange + 页面自刷）。
     */
    static boolean post(ClassLoader host, String key) {
        try {
            Class<?> busCls = Ref.cls(host, CLS_BUS);
            if (busCls == null) {
                Logx.w(T, "bus: 找不到 " + CLS_BUS);
                return false;
            }
            Object companion = staticField(busCls, "INSTANCE");
            if (companion == null) companion = staticField(busCls, "Companion");
            if (companion == null) {
                Logx.w(T, "bus: 没有 INSTANCE/Companion 字段");
                return false;
            }
            Object live = Ref.callName(companion, "get");          // Companion#get() → LiveDataBus
            if (live == null) {
                Logx.w(T, "bus: Companion#get() 返回 null");
                return false;
            }
            Object box = Ref.callName(live, "with", key);          // with(String) → BusMutableLiveData
            if (box == null) {
                Logx.w(T, "bus: with(\"" + key + "\") 返回 null");
                return false;
            }
            Ref.callName(box, "postValue", Boolean.TRUE);          // MutableLiveData#postValue
            Logx.i(T, "bus: post " + key);
            return true;
        } catch (Throwable t) {
            Logx.w(T, "bus post " + key + " failed: " + t);
            return false;
        }
    }

    /**
     * 课程写完（替换 / 追加 / 新建）后的统一收尾：
     * 通知数据库 → 叫醒宿主课表页 → 主页里模块挂的那层课表也重读一遍。
     */
    static void afterWrite(Activity a) {
        if (a == null) return;
        ClassLoader host = a.getClassLoader();
        notifyUris(a.getApplicationContext() == null ? a : a.getApplicationContext());
        boolean b1 = post(host, "refresh_course_table");
        boolean b2 = post(host, "db_change_event");
        boolean b3 = post(host, "db_change_calendar");
        try {
            HostTablePage.refresh();     // 主页课表页（模块注入）立即重读
        } catch (Throwable t) {
            Logx.w(T, "HostTablePage.refresh failed: " + t);
        }
        Logx.i(T, "afterWrite: bus refresh=" + b1 + "/" + b2 + "/" + b3);
    }

    private static Object staticField(Class<?> c, String name) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(null);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** 调试用：确认当前能拿到总线对象（日志里 `bus probe ok`） */
    static void probe(ClassLoader host) {
        try {
            Class<?> busCls = Ref.cls(host, CLS_BUS);
            Object companion = busCls == null ? null : staticField(busCls, "INSTANCE");
            Object live = companion == null ? null : Ref.callName(companion, "get");
            Method with = live == null ? null : Ref.methodByArity(live.getClass(), "with", 1);
            Logx.i(T, "bus probe: cls=" + (busCls != null) + " companion=" + (companion != null)
                    + " live=" + (live != null) + " with=" + (with != null));
        } catch (Throwable t) {
            Logx.w(T, "bus probe failed: " + t);
        }
    }
}
