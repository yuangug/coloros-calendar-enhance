package com.lcodexs.coursealarm;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Toast;

/** Explicit, user-selected recovery when the ROM blocks Calendar's first provider connection. */
public final class CourseIslandSetupActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            if (state != null || !"com.coloros.calendar".equals(getCallingPackage())) return;
            long[] times = getIntent().getLongArrayExtra("times");
            String token = getIntent().getStringExtra("token");
            if (times == null || times.length == 0 || !CourseDndValidation.validToken(token)) {
                Toast.makeText(this, "课表尚未就绪，请稍后重试", Toast.LENGTH_SHORT).show(); return;
            }
            CourseIslandWake.replace(this, times, token);
            CourseIslandWake.revalidate(this, token);
            Toast.makeText(this, "已登记课程提醒时间，正在核对星河岛状态", Toast.LENGTH_SHORT).show();
        } catch (RuntimeException error) {
            Toast.makeText(this, "课程提醒时间登记失败，请检查模块的准时提醒权限", Toast.LENGTH_LONG).show();
        } finally { finish(); }
    }
}
