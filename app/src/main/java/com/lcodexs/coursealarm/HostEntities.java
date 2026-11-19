package com.lcodexs.coursealarm;

import android.content.Context;
import android.content.ContentValues;
import android.content.Intent;
import android.os.Parcelable;
import android.app.Activity;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.lang.annotation.Annotation;
import java.util.*;

/** Uses the host's ORM so identity, extension fields and sync metadata survive editing. */
final class HostEntities {
    private static final String DAO = "com.coloros.calendar.foundation.databasedaolib.dao.";
    private static final String ENTITY = "com.coloros.calendar.foundation.databasedaolib.entities.";

    /** One worker owns this ORM/cache; decode full rows without querying each ID again. */
    static final class CursorReader {
        private final Object orm;
        private final Method fromCursor;
        private final ClassLoader loader;

        CursorReader(Context context) throws Exception {
            loader = context.getClassLoader();
            Class<?> cls = Class.forName("com.coloros.calendar.foundation.databasedaolib.orm.MicroOrm", false, loader);
            orm = cls.getConstructor().newInstance();
            fromCursor = cls.getMethod("fromCursor", android.database.Cursor.class, Class.class);
        }

        Object read(android.database.Cursor cursor, String name) throws Exception {
            return fromCursor.invoke(orm, cursor, Class.forName(ENTITY + name + "Entity", false, loader));
        }
    }

    static boolean local(Context context) throws Exception {
        Class<?> cls = Class.forName("com.coloros.calendar.foundation.databasedaolib.helper.DataBaseMergeUtil",
                false, context.getClassLoader());
        return !((Boolean) cls.getMethod("isCalendarProviderMergeVerison", Context.class).invoke(null, context));
    }

    static Object dao(Context context, String name) throws Exception {
        Class<?> cls = Class.forName(DAO + name + "Dao", false, context.getClassLoader());
        return cls.getMethod("getInstance", Context.class).invoke(null, context);
    }

    static Object calendar(Context context, long id) throws Exception {
        if (id <= 0) throw new IllegalStateException("没有选中的课表");
        Object dao = dao(context, "Calendar");
        Object result = dao.getClass().getMethod("queryCalendarById", long.class, boolean.class)
                .invoke(dao, id, local(context));
        if (result == null) throw new IllegalStateException("课表已删除或不可读取");
        return result;
    }

    static Object event(Context context, long id) throws Exception {
        Object dao = dao(context, "Event");
        Object result = dao.getClass().getMethod("queryEventById", boolean.class, long.class)
                .invoke(dao, local(context), id);
        if (result == null) throw new IllegalStateException("课程已删除或不可读取: " + id);
        return result;
    }

    static Object fromJson(Context context, String className, String json) throws Exception {
        ClassLoader cl = context.getClassLoader();
        Class<?> gsonClass = Class.forName("com.google.gson.Gson", false, cl);
        Object gson = gsonClass.getConstructor().newInstance();
        return gsonClass.getMethod("fromJson", String.class, Class.class)
                .invoke(gson, json, Class.forName(className, false, cl));
    }

    static Object newEntity(Context context, String name) throws Exception {
        return Class.forName(ENTITY + name + "Entity", false, context.getClassLoader()).getConstructor().newInstance();
    }

    static void set(Object object, String name, Object value) throws Exception {
        for (Method method : object.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != 1) continue;
            Class<?> type = method.getParameterTypes()[0];
            Object arg = value;
            if (value instanceof Number) {
                if (type == long.class || type == Long.class) arg = ((Number) value).longValue();
                else if (type == int.class || type == Integer.class) arg = ((Number) value).intValue();
            }
            method.invoke(object, arg);
            return;
        }
        throw new NoSuchMethodException(object.getClass().getName() + "." + name);
    }

    static ContentValues values(Context context, String name, Object entity) throws Exception {
        Object orm = Ref.field(dao(context, name), "uOrm");
        if (orm == null) throw new IllegalStateException("宿主 ORM 不可用");
        Method method = Ref.methodOf(orm.getClass(), "toContentValues", Object.class, boolean.class);
        if (method == null) method = Ref.methodByArity(orm.getClass(), "toContentValues", 2);
        if (method == null) throw new NoSuchMethodException("toContentValues");
        return (ContentValues) method.invoke(orm, entity, local(context));
    }

    /** Column names from the host ORM, without querying the same row a second time. */
    static Set<String> writableColumns(Context context, String name) throws Exception {
        Set<String> keys = new LinkedHashSet<>();
        Class<?> entity = Class.forName(ENTITY + name + "Entity", false, context.getClassLoader());
        for (Class<?> type = entity; type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) for (Annotation annotation : field.getDeclaredAnnotations()) {
                Class<? extends Annotation> column = annotation.annotationType();
                if (!column.getName().equals("com.coloros.calendar.foundation.databasedaolib.orm.annotations.Column")) continue;
                if (!Boolean.TRUE.equals(column.getMethod("readonly").invoke(annotation)))
                    keys.add((String)column.getMethod("value").invoke(annotation));
            }
        }
        if (keys.isEmpty()) throw new IllegalStateException("宿主字段映射不可用");
        keys.remove("_id");
        Collections.addAll(keys, "_sync_id", "dirty", "deleted", "original_sync_id", "mutators",
                "sync_data1", "sync_data2", "sync_data3", "sync_data4", "sync_data5", "sync_data6", "sync_data7", "sync_data8", "sync_data9", "sync_data10");
        return keys;
    }

    static void openReminder(Activity activity) {
        long id = TableCtx.hostCurrentTableId(activity);
        new Thread(() -> {
            try {
                Object calendar = calendar(activity, id);
                Object raw = Ref.callName(calendar, "getCalendarJsonExtensions");
                org.json.JSONObject config = new org.json.JSONObject(String.valueOf(raw));
                int minutes = config.optInt("reminderTime", -1);
                if (minutes < 5 || minutes > 60 || minutes % 5 != 0) {
                    config.put("reminderTime", 15);
                    set(calendar, "setCalendarJsonExtensions", config.toString());
                }
                if (!(calendar instanceof Parcelable)) throw new IllegalStateException("课表实体不可传递");
                activity.runOnUiThread(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    Intent intent = new Intent().setClassName(activity.getPackageName(),
                            "com.android.calendar.card.schooltimetable.CourseReminderActivity");
                    intent.putExtra("calendar_entity", (Parcelable) calendar);
                    activity.startActivity(intent);
                });
            } catch (Exception error) {
                activity.runOnUiThread(() -> CurriculumIo.toast(activity, "无法打开课程提醒：" + error.getMessage()));
                Logx.e(CourseAlarmEntry.TAG, "openReminder", error);
            }
        }, "course-reminder-load").start();
    }
}
