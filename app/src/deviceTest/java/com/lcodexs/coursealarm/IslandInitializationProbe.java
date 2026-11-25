package com.lcodexs.coursealarm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Fixture only: simulate initial availability without bringing an activity over the user's app.
 * No fake course, deadline, SDK response, or dismissal is injected.
 */
public final class IslandInitializationProbe extends BroadcastReceiver {
    @Override public void onReceive(Context app, Intent intent) {
        String token = app.getSharedPreferences("course_dnd_v1", 0).getString("validationToken", null);
        if (CourseDndValidation.validToken(token)) CourseIslandWake.revalidate(app, token);
        PendingResult result = goAsync();
        new android.os.Handler(app.getMainLooper()).postDelayed(result::finish, 6000);
    }
}
