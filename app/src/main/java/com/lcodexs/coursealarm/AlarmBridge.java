package com.lcodexs.coursealarm;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.List;

/**
 * 时钟侧桥接：走 ColorOS 时钟自带的「AI/语音助手」ContentProvider，
 * 由时钟 App 自己完成「插入 + 计算下一次响铃 + 排闹钟」，不是我们手写 DB。
 *
 * 证据（com.coloros.alarmclock 16.18.6 反编译）：
 *   provider com.oplus.alarmclock.ai  (AiSupportContentProvider, 需 OPPO_COMPONENT_SAFE)
 *     call("add_alarm", ...)      -> AiSupportContentProvider.addAlarm -> q5.b.o(Bundle)
 *     call("get_alarm_list", ...) -> 返回 alarm_id_list / alarm_hour_list / alarm_label_list …
 *     call("delete_alarm", ...)   -> 按 alarm_id 删除
 *   add_alarm 读的 extras（AOSP 语义）：
 *     android.intent.extra.alarm.HOUR / .MINUTES / .DAYS_OF_WEEK(byte) /
 *     .DELETE_AFTER_USE / "label" / .RINGTONE / android.intent.action.ALARM_ENABLE
 *
 * 日历 App 实测已 hold：oplus.permission.OPLUS_COMPONENT_SAFE / oppo.permission.OPPO_COMPONENT_SAFE
 * （dumpsys package com.coloros.calendar：两者 granted=true），所以日历进程可以直接调。
 */
final class AlarmBridge {
    private static final String T = CourseAlarmEntry.TAG;

    static final String LABEL_PREFIX = "课程闹钟 ";

    private static final Uri AI_URI = Uri.parse("content://com.coloros.alarmclock.ai");

    /**
     * 直连 alarms 表（用于补振动/铃声这类 add_alarm 不透传的列）。
     * 注意 URI 是单数 /alarm —— ClockProvider 的 UriMatcher 注册的是
     * NotificationCompat.CATEGORY_ALARM（"alarm"），用 /alarms 会
     * UnsupportedOperationException: Cannot update URI（实测坑）。
     */
    private static final Uri[] ALARM_TABLES = new Uri[]{
            Uri.parse("content://com.coloros.alarmclock.alarmclock/alarm"),
            Uri.parse("content://com.oplus.alarmclock.alarmclock/alarm")
    };

    private static final String K_HOUR = "android.intent.extra.alarm.HOUR";
    private static final String K_MIN = "android.intent.extra.alarm.MINUTES";
    private static final String K_DOW = "android.intent.extra.alarm.DAYS_OF_WEEK";
    private static final String K_DEL = "android.intent.extra.alarm.DELETE_AFTER_USE";
    private static final String K_LABEL = "label";
    private static final String K_RING = "android.intent.extra.alarm.RINGTONE";
    private static final String K_ENABLE = "android.intent.action.ALARM_ENABLE";

    static final class Existing {
        long id;
        int hour;
        int minute;
        String label;

        @Override
        public String toString() {
            return "Alarm#" + id + " " + hour + ":" + minute + " '" + label + "'";
        }
    }

    private AlarmBridge() {
    }

    /** 时钟里当前的闹钟（读不到返回空表，不抛） */
    static List<Existing> list(Context ctx) {
        List<Existing> out = new ArrayList<>();
        try {
            Bundle res = ctx.getContentResolver().call(AI_URI, "get_alarm_list", "", null);
            if (res == null) return out;
            Object idsObj = res.get("alarm_id_list");
            Object hoursObj = res.get("alarm_hour_list");
            Object minsObj = res.get("alarm_min_list");
            Object labelsObj = res.get("alarm_label_list");

            long[] ids = asLongs(idsObj);
            int[] hours = asInts(hoursObj);
            int[] mins = asInts(minsObj);
            String[] labels = asStrings(labelsObj);
            if (ids == null) {
                Logx.w(T, "list: alarm_id_list type=" + (idsObj == null ? "null" : idsObj.getClass()));
                return out;
            }
            for (int i = 0; i < ids.length; i++) {
                Existing e = new Existing();
                e.id = ids[i];
                e.hour = hours != null && i < hours.length ? hours[i] : -1;
                e.minute = mins != null && i < mins.length ? mins[i] : -1;
                e.label = labels != null && i < labels.length ? labels[i] : "";
                out.add(e);
            }
            Logx.i(T, "list: " + out.size() + " alarms");
        } catch (Throwable t) {
            Logx.e(T, "list alarms failed: " + t);
        }
        return out;
    }

    /**
     * 删掉我们以前设过的（按 message 前缀，绝不动用户自己的闹钟）。
     * 直接对 alarms 表做一次批量 delete —— 比遍历 get_alarm_list 稳（那个 Bundle 里数组类型会变）。
     */
    static int deleteOurs(Context ctx) {
        int total = 0;
        ContentResolver cr = ctx.getContentResolver();
        for (Uri u : ALARM_TABLES) {
            try {
                int n = cr.delete(u, "message LIKE ?", new String[]{LABEL_PREFIX + "%"});
                if (n > 0) {
                    Logx.i(T, "deleteOurs removed " + n + " via " + u);
                    total += n;
                }
            } catch (Throwable t) {
                Logx.w(T, "deleteOurs via " + u + " failed: " + t);
            }
        }
        Logx.i(T, "deleteOurs removed=" + total);
        return total;
    }

    static boolean delete(Context ctx, long id) {
        try {
            Bundle in = new Bundle();
            in.putLong("alarm_id", id);
            Bundle res = ctx.getContentResolver().call(AI_URI, "delete_alarm", "", in);
            boolean ok = res != null && res.getInt("result", -1) == 1;
            Logx.i(T, "delete alarm#" + id + " ok=" + ok);
            return ok;
        } catch (Throwable t) {
            Logx.e(T, "delete alarm#" + id + " failed: " + t);
            return false;
        }
    }

    /** 建一个一次性闹钟，返回闹钟 id（失败 -1） */
    static long addOnce(Context ctx, int hour, int minute, String label, boolean silent) {
        try {
            Bundle in = new Bundle();
            in.putInt(K_HOUR, hour);
            in.putInt(K_MIN, minute);
            in.putByte(K_DOW, (byte) 0);          // 0 = 不重复 → 只响一次
            in.putInt(K_DEL, -1);                 // 交给时钟按「不重复」自动决定（= 用完即删）
            in.putString(K_LABEL, label);
            in.putString(K_RING, silent ? "silent" : "");
            in.putInt(K_ENABLE, 1);

            Bundle res = ctx.getContentResolver().call(AI_URI, "add_alarm", "", in);
            long id = -1;
            int result = res == null ? -99 : res.getInt("result", -99);
            if (res != null) {
                long[] ids = res.getLongArray("alarm_id_list");
                if (ids != null && ids.length > 0) id = ids[ids.length - 1];
            }
            Logx.i(T, "addOnce " + String.format("%02d:%02d", hour, minute)
                    + " label='" + label + "' silent=" + silent
                    + " result=" + result + " id=" + id);
            return id;
        } catch (Throwable t) {
            Logx.e(T, "addOnce failed: " + t);
            return -1;
        }
    }

    /**
     * 补写振动/铃声类型（add_alarm 不透传，直接 update alarms 表）。
     * vibrate 取值来自 com.heytap.addon.os.WaveformEffect：64 = 响铃同时振动，0 = 不振动。
     * alerttype：1 = 静音，2 = 响铃。
     */
    static boolean tune(Context ctx, long id, boolean vibrate, boolean silent) {
        if (id <= 0) return false;
        ContentValues cv = new ContentValues();
        cv.put("vibrate", vibrate ? WAVE_VIBRATE_WITH_RINGTONE : WAVE_NO_VIBRATE);
        cv.put("alerttype", silent ? 1 : 2);
        ContentResolver cr = ctx.getContentResolver();
        for (Uri u : ALARM_TABLES) {
            try {
                int n = cr.update(u, cv, "_id=" + id, null);
                if (n > 0) {
                    Logx.i(T, "tune alarm#" + id + " vibrate=" + vibrate + " silent=" + silent
                            + " via " + u);
                    return true;
                }
            } catch (Throwable t) {
                Logx.w(T, "tune via " + u + " failed: " + t);
            }
        }
        Logx.w(T, "tune alarm#" + id + " no provider accepted update");
        return false;
    }

    private static final int WAVE_VIBRATE_WITH_RINGTONE = 64;
    private static final int WAVE_NO_VIBRATE = 0;

    /** 只读校验：把 id 对应行的 hour/minutes/vibrate/deleteAfterUse 读回来 */
    static String inspect(Context ctx, long id) {        ContentResolver cr = ctx.getContentResolver();
        for (Uri u : ALARM_TABLES) {
            Cursor c = null;
            try {
                c = cr.query(u, new String[]{"_id", "hour", "minutes", "daysofweek", "enabled",
                        "vibrate", "alerttype", "deleteAfterUse", "message"}, "_id=" + id, null, null);
                if (c != null && c.moveToFirst()) {
                    String s = "id=" + c.getLong(0) + " h=" + c.getInt(1) + " m=" + c.getInt(2)
                            + " dow=" + c.getInt(3) + " enabled=" + c.getInt(4)
                            + " vibrate=" + c.getInt(5) + " alerttype=" + c.getString(6)
                            + " deleteAfterUse=" + c.getInt(7) + " label=" + c.getString(8);
                    return s;
                }
            } catch (Throwable t) {
                Logx.w(T, "inspect via " + u + " failed: " + t);
            } finally {
                if (c != null) try { c.close(); } catch (Throwable ignored) { }
            }
        }
        return "inspect failed for #" + id;
    }

    // ── Bundle 里数组类型不稳定的兼容读取（long[] / Long[] / int[] / ArrayList 都吃） ──

    private static long[] asLongs(Object o) {
        if (o instanceof long[]) return (long[]) o;
        if (o instanceof Object[]) {
            Object[] a = (Object[]) o;
            long[] r = new long[a.length];
            for (int i = 0; i < a.length; i++) r[i] = a[i] instanceof Number ? ((Number) a[i]).longValue() : -1;
            return r;
        }
        if (o instanceof List) {
            List<?> l = (List<?>) o;
            long[] r = new long[l.size()];
            for (int i = 0; i < l.size(); i++) r[i] = l.get(i) instanceof Number ? ((Number) l.get(i)).longValue() : -1;
            return r;
        }
        return null;
    }

    private static int[] asInts(Object o) {
        if (o instanceof int[]) return (int[]) o;
        if (o instanceof Object[]) {
            Object[] a = (Object[]) o;
            int[] r = new int[a.length];
            for (int i = 0; i < a.length; i++) r[i] = a[i] instanceof Number ? ((Number) a[i]).intValue() : -1;
            return r;
        }
        if (o instanceof List) {
            List<?> l = (List<?>) o;
            int[] r = new int[l.size()];
            for (int i = 0; i < l.size(); i++) r[i] = l.get(i) instanceof Number ? ((Number) l.get(i)).intValue() : -1;
            return r;
        }
        return null;
    }

    private static String[] asStrings(Object o) {
        if (o instanceof String[]) return (String[]) o;
        if (o instanceof Object[]) {
            Object[] a = (Object[]) o;
            String[] r = new String[a.length];
            for (int i = 0; i < a.length; i++) r[i] = a[i] == null ? "" : a[i].toString();
            return r;
        }
        if (o instanceof List) {
            List<?> l = (List<?>) o;
            String[] r = new String[l.size()];
            for (int i = 0; i < l.size(); i++) r[i] = l.get(i) == null ? "" : l.get(i).toString();
            return r;
        }
        return null;
    }
}
