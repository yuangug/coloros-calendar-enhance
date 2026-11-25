package com.lcodexs.coursealarm;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/** Use Calendar's normal course Seedling sender, which publishes both native surfaces. No alert row or sound. */
final class CourseWindowPublisher {
    private static final String ROOT = "com.android.calendar.seedling.notificationfluidcard.";
    private final Method supported, toInfo, setAccountType, isCourse, send;
    private final Constructor<?> normalModel;
    private final ThreadLocal<Object> ownRequest = new ThreadLocal<>();

    CourseWindowPublisher(ClassLoader loader, Method send) throws ReflectiveOperationException {
        this.send = send;
        supported = Class.forName(ROOT + "NotificationFluidSeedingHelper", false, loader)
                .getMethod("isSupportFluidCloud", boolean.class);
        Class<?> normal = Class.forName(ROOT + "entity.NormalReminderModel", false, loader);
        normalModel = normal.getConstructor(long.class, String.class, boolean.class, long.class, long.class,
                long.class, long.class, int.class, String.class, String.class, String.class, String.class,
                String.class, String.class);
        toInfo = normal.getMethod("toNotificationInfo");
        Class<?> event = Class.forName("com.android.calendar.alerts.EventNotificationInfo", false, loader);
        setAccountType = event.getMethod("setAccountType", String.class);
        isCourse = event.getMethod("isCourse");
    }

    Object info(CourseOccurrence item, String account, String type) throws ReflectiveOperationException {
        Object model = normalModel.newInstance(item.eventId, item.title, false, item.reminderAt, item.start,
                item.end, 0L, CourseWriter.COURSE_EXT_PROP, account, type, "", item.zone, item.room, item.teacher);
        Object info = toInfo.invoke(model);
        // Calendar's own conversion copies the account name but omits the type needed by isCourse().
        setAccountType.invoke(info, type);
        return Boolean.TRUE.equals(isCourse.invoke(info)) ? info : null;
    }

    boolean owns(Object info) { return ownRequest.get() == info; }

    void send(Context app, Object manager, CourseOccurrence item, String account, String type)
            throws ReflectiveOperationException {
        if (!notificationsAllowed(app) || !Boolean.TRUE.equals(supported.invoke(null, true))) return;
        Object info = info(item, account, type);
        if (manager == null || info == null) return;
        ownRequest.set(info);
        try { send.invoke(manager, info); }
        finally { ownRequest.remove(); }
    }

    static boolean notificationsAllowed(Context app) {
        NotificationManager nm = app.getSystemService(NotificationManager.class);
        if (nm == null || !nm.areNotificationsEnabled()) return false;
        NotificationChannel channel = nm.getNotificationChannel("Calendar_course_channel_default");
        if (channel == null) return true;
        if (channel.getImportance() == NotificationManager.IMPORTANCE_NONE) return false;
        if (channel.getGroup() == null || android.os.Build.VERSION.SDK_INT < 28) return true;
        android.app.NotificationChannelGroup group = nm.getNotificationChannelGroup(channel.getGroup());
        return group == null || !group.isBlocked();
    }
}
