package com.lcodexs.coursealarm;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import org.json.JSONObject;

/** Device-only contract checks using an isolated preference file; no timetable rows are changed. */
final class CourseLiveSettingsChecks {
    private CourseLiveSettingsChecks() { }

    static JSONObject run(Context host) throws Exception {
        String testFile = "lspx_live_settings_verification_" + java.util.UUID.randomUUID();
        Context isolated = new ContextWrapper(host) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return host.getSharedPreferences(testFile, Context.MODE_PRIVATE);
            }
        };
        SharedPreferences preferences = TableSettings.preferences(isolated);
        try {
            TableSettings.State first = new TableSettings.State(1, "first", false);
            TableSettings.State second = new TableSettings.State(2, "second", false);
            TableSettings.State reusedId = new TableSettings.State(1, "replacement", false);
            CourseLiveSettings.State defaults = CourseLiveSettings.fromTableState(isolated, first);
            require(!defaults.enabled && defaults.retainOnClick && defaults.showProgress
                    && defaults.showDndButton && !defaults.autoDnd && defaults.retainMinutes == 5, "defaults");
            require(!CourseLiveSettings.showExperiments(isolated), "experimental settings hidden by default");
            CourseLiveSettings.setShowExperiments(isolated, true);
            require(CourseLiveSettings.showExperiments(isolated), "show experimental settings persists");
            require(preferences.edit().putBoolean("live_first_show_progress", false)
                    .putBoolean("live_first_enabled", true)
                    .putBoolean("live_first_auto_dnd", true).putInt("live_first_retain_minutes", 10)
                    .putBoolean("drag_first", true).commit(), "seed isolated preferences");
            CourseLiveSettings.State customized = CourseLiveSettings.fromTableState(isolated, first);
            require(!customized.showProgress && customized.autoDnd && customized.retainMinutes == 10
                    && customized.enabled && customized.retainOnClick, "independent fields");
            require(CourseLiveSettings.fromTableState(isolated, second).showProgress, "separate table");
            require(CourseLiveSettings.fromTableState(isolated, reusedId).showProgress, "reused row ID");
            require(preferences.edit().putInt("live_first_retain_minutes", -5)
                    .putString("live_first_enabled", "bad legacy type").commit(), "seed invalid values");
            CourseLiveSettings.State repaired = CourseLiveSettings.fromTableState(isolated, first);
            require(repaired.retainMinutes == 5 && !repaired.enabled && repaired.retainOnClick, "invalid values use defaults");
            SharedPreferences.Editor reset = preferences.edit();
            CourseLiveSettings.removeForNewTable(reset, "first");
            require(reset.commit(), "new identity reset");
            CourseLiveSettings.State newTable = CourseLiveSettings.fromTableState(isolated, first);
            require(newTable.showProgress && !newTable.autoDnd && newTable.retainMinutes == 5,
                    "new table live defaults");
            require(preferences.getBoolean("drag_first", false), "live reset leaves unrelated settings");
            require(CourseLiveSettings.showExperiments(isolated), "table reset does not hide experiments");
            CourseLiveSettings.setShowExperiments(isolated, false);
            require(!CourseLiveSettings.showExperiments(isolated), "hide experimental settings persists");
            for (int valid : new int[]{0, 1, 5, 10}) require(CourseLiveSettings.validRetention(valid), "valid retention");
            for (int invalid : new int[]{-5, -1, 2, 30, Integer.MAX_VALUE})
                require(!CourseLiveSettings.validRetention(invalid), "invalid retention");
            return new JSONObject().put("defaults", true).put("experimentalVisibility", true).put("identityIsolation", true)
                    .put("reusedIdIsolation", true).put("invalidValueFallback", true)
                    .put("newTableReset", true).put("unrelatedPreferencePreserved", true)
                    .put("retentionValidation", true);
        } finally {
            preferences.edit().clear().commit();
            host.deleteSharedPreferences(testFile);
        }
    }

    private static void require(boolean value, String label) {
        if (!value) throw new AssertionError("Course live settings: " + label);
    }
}
