package com.lcodexs.coursealarm;

import android.content.Context;
import android.content.SharedPreferences;

/** 模块自己的设置（写在宿主 calendar 进程的私有 prefs 里，跨进程单份）。 */
final class Prefs {
    static final String FILE = "lspx_course_alarm";

    static final String KEY_ENABLED = "enabled";
    static final String KEY_OFFSET = "offset_min";
    static final String KEY_SILENT = "ring_silent";
    static final String KEY_VIBRATE = "vibrate";

    static final int DEFAULT_OFFSET = 15;

    private static volatile SharedPreferences sp;

    private Prefs() {
    }

    static synchronized void init(Context ctx) {
        if (sp == null && ctx != null) {
            sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        }
    }

    private static SharedPreferences p() {
        return sp;
    }

    static boolean enabled() {
        return enabled(AlarmPeriod.MORNING);
    }

    static boolean anyAlarmEnabled() {
        return enabled(AlarmPeriod.MORNING) || enabled(AlarmPeriod.AFTERNOON);
    }

    static boolean enabled(AlarmPeriod period) {
        SharedPreferences s = p();
        return s != null && s.getBoolean(period.prefPrefix + KEY_ENABLED, false);
    }

    static int offset(AlarmPeriod period) {
        SharedPreferences s = p();
        int v = s == null ? DEFAULT_OFFSET : s.getInt(period.prefPrefix + KEY_OFFSET, DEFAULT_OFFSET);
        return Math.max(0, Math.min(120, v));
    }

    static boolean silent(AlarmPeriod period) {
        SharedPreferences s = p();
        return s != null && s.getBoolean(period.prefPrefix + KEY_SILENT, false);
    }

    static boolean vibrate(AlarmPeriod period) {
        SharedPreferences s = p();
        return s == null || s.getBoolean(period.prefPrefix + KEY_VIBRATE, true);
    }

    static void setEnabled(boolean v) {
        setEnabled(AlarmPeriod.MORNING, v);
    }

    static void setEnabled(AlarmPeriod period, boolean v) {
        SharedPreferences s = p();
        if (s != null) s.edit().putBoolean(period.prefPrefix + KEY_ENABLED, v).apply();
    }

    static void setOffset(AlarmPeriod period, int v) {
        SharedPreferences s = p();
        if (s != null) s.edit().putInt(period.prefPrefix + KEY_OFFSET, Math.max(0, Math.min(120, v))).apply();
    }

    static void setSilent(AlarmPeriod period, boolean v) {
        SharedPreferences s = p();
        if (s != null) s.edit().putBoolean(period.prefPrefix + KEY_SILENT, v).apply();
    }

    static void setVibrate(AlarmPeriod period, boolean v) {
        SharedPreferences s = p();
        if (s != null) s.edit().putBoolean(period.prefPrefix + KEY_VIBRATE, v).apply();
    }

    /** 教务系统上次打开的地址（网页登录版用） */
    static String jwLastUrl() {
        SharedPreferences s = p();
        return s == null ? "" : s.getString("jw_last_url", "");
    }

    static void setJwLastUrl(String url) {
        SharedPreferences s = p();
        if (s != null) s.edit().putString("jw_last_url", url == null ? "" : url).apply();
    }

    /** 记录上一次成功结果，用于设置页摘要与排错 */
    static void setLastResult(AlarmPeriod period, String text) {
        SharedPreferences s = p();
        if (s != null) s.edit().putString(period.prefPrefix + "last_result", text).apply();
    }

    static String lastResult(AlarmPeriod period) {
        SharedPreferences s = p();
        return s == null ? "" : s.getString(period.prefPrefix + "last_result", "");
    }

    // ── 课表主题 ────────────────────────────────────────────────────────────

    /** 背景图绝对路径；空串表示没有背景 */
    static String themeBg() {
        SharedPreferences s = p();
        return s == null ? "" : s.getString("theme_bg", "");
    }

    static void setThemeBg(String path) {
        SharedPreferences s = p();
        if (s != null) s.edit().putString("theme_bg", path == null ? "" : path).apply();
    }

    /** 遮罩浓度 0~230（越大背景越淡、文字越清楚） */
    static int themeScrim() {
        SharedPreferences s = p();
        int v = s == null ? 120 : s.getInt("theme_scrim", 120);
        return Math.max(0, Math.min(230, v));
    }

    static void setThemeScrim(int v) {
        SharedPreferences s = p();
        if (s != null) s.edit().putInt("theme_scrim", Math.max(0, Math.min(230, v))).apply();
    }
}
