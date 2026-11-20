package com.lcodexs.coursealarm;

import android.content.Context;
import android.content.SharedPreferences;

/** Identity-scoped course activity preferences; never stores timetable or reminder rows. */
final class CourseLiveSettings {
    static final String ENABLED = "enabled";
    static final String RETAIN_ON_CLICK = "retain_on_click";
    static final String SHOW_PROGRESS = "show_progress";
    static final String SHOW_DND_BUTTON = "show_dnd_button";
    static final String AUTO_DND = "auto_dnd";
    static final String RETAIN_MINUTES = "retain_minutes";
    private static final String SHOW_EXPERIMENTS = "show_course_live_experiments";
    private static final String[] FIELDS = { ENABLED, RETAIN_ON_CLICK, SHOW_PROGRESS,
            SHOW_DND_BUTTON, AUTO_DND, RETAIN_MINUTES };

    static final class State {
        final long calendarId;
        final String identity;
        final boolean enabled, retainOnClick, showProgress, showDndButton, autoDnd;
        final int retainMinutes;

        State(long calendarId, String identity, boolean enabled, boolean retainOnClick,
              boolean showProgress, boolean showDndButton, boolean autoDnd, int retainMinutes) {
            this.calendarId = calendarId;
            this.identity = identity;
            this.enabled = enabled;
            this.retainOnClick = retainOnClick;
            this.showProgress = showProgress;
            this.showDndButton = showDndButton;
            this.autoDnd = autoDnd;
            this.retainMinutes = validRetention(retainMinutes) ? retainMinutes : 5;
        }

        boolean flag(String field) {
            return switch (field) {
                case ENABLED -> enabled;
                case RETAIN_ON_CLICK -> retainOnClick;
                case SHOW_PROGRESS -> showProgress;
                case SHOW_DND_BUTTON -> showDndButton;
                case AUTO_DND -> autoDnd;
                default -> throw new IllegalArgumentException("未知的课程实时活动设置");
            };
        }
    }

    private CourseLiveSettings() { }

    /** Revalidates identity; call on a worker. */
    static State read(Context context, long calendarId) throws Exception {
        return fromTableState(context, TableSettings.refresh(context, calendarId));
    }

    /** Reuse the complete row from a current-table query, without another provider query. */
    static State readLoaded(Context context, long calendarId, Object calendar, boolean local) throws Exception {
        return fromTableState(context, TableSettings.refreshLoaded(context, calendarId, calendar, local));
    }

    static State fromTableState(Context context, TableSettings.State table) {
        SharedPreferences prefs = TableSettings.preferences(context);
        String id = table.identity;
        return new State(table.calendarId, id,
                bool(prefs, id, ENABLED, false), bool(prefs, id, RETAIN_ON_CLICK, true),
                bool(prefs, id, SHOW_PROGRESS, true), bool(prefs, id, SHOW_DND_BUTTON, true),
                bool(prefs, id, AUTO_DND, false), retention(prefs, id));
    }

    static boolean showExperiments(Context context) {
        return TableSettings.preferences(context).getBoolean(SHOW_EXPERIMENTS, false);
    }

    static void setShowExperiments(Context context, boolean visible) {
        if (!TableSettings.preferences(context).edit().putBoolean(SHOW_EXPERIMENTS, visible).commit())
            throw new IllegalStateException("无法保存测试功能显示状态");
    }

    /** Updates exactly one field after verifying the identity captured by the page. */
    static State setBoolean(Context context, long calendarId, String expectedIdentity,
                            String field, boolean value) throws Exception {
        if (!ENABLED.equals(field) && !RETAIN_ON_CLICK.equals(field) && !SHOW_PROGRESS.equals(field)
                && !SHOW_DND_BUTTON.equals(field) && !AUTO_DND.equals(field))
            throw new IllegalArgumentException("未知的课程实时活动设置");
        TableSettings.State table = requireIdentity(context, calendarId, expectedIdentity);
        if (!TableSettings.preferences(context).edit().putBoolean(key(table.identity, field), value).commit())
            throw new IllegalStateException("课程实时活动设置保存失败，请重试");
        return fromTableState(context, table);
    }

    static State setRetention(Context context, long calendarId, String expectedIdentity, int minutes) throws Exception {
        if (!validRetention(minutes)) throw new IllegalArgumentException("不支持的课后保留时间");
        TableSettings.State table = requireIdentity(context, calendarId, expectedIdentity);
        if (!TableSettings.preferences(context).edit().putInt(key(table.identity, RETAIN_MINUTES), minutes).commit())
            throw new IllegalStateException("课后保留时间保存失败，请重试");
        return fromTableState(context, table);
    }

    /** Included in the same private preference commit as resetting the drag switch. */
    static void removeForNewTable(SharedPreferences.Editor editor, String identity) {
        for (String field : FIELDS) editor.remove(key(identity, field));
    }

    static boolean validRetention(int minutes) { return minutes == 0 || minutes == 1 || minutes == 5 || minutes == 10; }

    static String retentionLabel(int minutes) { return minutes == 0 ? "立即移除" : minutes + " 分钟"; }

    private static TableSettings.State requireIdentity(Context context, long calendarId, String expected) throws Exception {
        TableSettings.State table = TableSettings.refresh(context, calendarId);
        if (expected == null || !expected.equals(table.identity))
            throw new IllegalStateException("课表已变化，请重新打开课表设置");
        return table;
    }

    private static String key(String identity, String field) { return "live_" + identity + "_" + field; }

    private static boolean bool(SharedPreferences preferences, String identity, String field, boolean fallback) {
        try { return preferences.getBoolean(key(identity, field), fallback); }
        catch (ClassCastException invalid) { return fallback; }
    }

    private static int retention(SharedPreferences preferences, String identity) {
        try {
            int value = preferences.getInt(key(identity, RETAIN_MINUTES), 5);
            return validRetention(value) ? value : 5;
        } catch (ClassCastException invalid) { return 5; }
    }
}
