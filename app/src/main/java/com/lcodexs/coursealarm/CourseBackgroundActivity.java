package com.lcodexs.coursealarm;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Toast;

/** Opens system settings for the module UID, never for the Calendar hosting the preferences. */
public final class CourseBackgroundActivity extends Activity {
    static final String APP_DETAILS = "appDetails";
    static Boolean exempt(Context context) {
        try {
            PowerManager power = context.getSystemService(PowerManager.class);
            return power == null ? null : power.isIgnoringBatteryOptimizations(CourseDndClient.PACKAGE);
        } catch (RuntimeException unavailable) { return null; }
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!"com.coloros.calendar".equals(getCallingPackage())) { finish(); return; }
        if (state != null) return; // The settings activity is restored by Android.
        Uri self = Uri.fromParts("package", getPackageName(), null);
        if (!getIntent().getBooleanExtra(APP_DETAILS, false) && Boolean.FALSE.equals(exempt(this))) {
            try {
                startActivityForResult(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, self), 1);
                return;
            } catch (RuntimeException unavailable) { /* OEM settings may only expose the app details page. */ }
        }
        try {
            startActivityForResult(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, self), 1);
            Toast.makeText(this, "请在模块的耗电管理中检查「允许后台运行」", Toast.LENGTH_LONG).show();
        } catch (RuntimeException unavailable) {
            Toast.makeText(this, "无法打开系统设置，请在模块的应用信息中检查耗电管理", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        finish(); // The host re-reads the actual exemption on resume, even after cancellation.
    }
}
