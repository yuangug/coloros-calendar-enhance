package com.lcodexs.coursealarm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.app.NotificationManager;
import android.os.Build;

/** Protected system broadcasts and the module-owned exact boundary PendingIntent only. */
public final class CourseDndReceiver extends BroadcastReceiver {
    private static boolean registered;

    /** Some framework policy broadcasts are registered-only; the rule owner keeps this alive. */
    static synchronized void register(Context context) {
        if (registered) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED);
        filter.addAction(NotificationManager.ACTION_NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED);
        filter.addAction(NotificationManager.ACTION_AUTOMATIC_ZEN_RULE_STATUS_CHANGED);
        if (Build.VERSION.SDK_INT >= 33)
            context.getApplicationContext().registerReceiver(new CourseDndReceiver(), filter, Context.RECEIVER_NOT_EXPORTED);
        else context.getApplicationContext().registerReceiver(new CourseDndReceiver(), filter);
        registered = true;
    }

    @Override public void onReceive(Context context, Intent intent) {
        PendingResult pending = goAsync();
        Context app = context.getApplicationContext();
        register(app);
        CourseDndBroker.WORKER.execute(() -> {
            try { CourseDndBroker.onSystemEvent(app, intent); }
            finally { pending.finish(); }
        });
    }
}
