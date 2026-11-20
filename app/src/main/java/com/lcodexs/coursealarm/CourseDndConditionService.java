package com.lcodexs.coursealarm;

import android.net.Uri;
import android.service.notification.ConditionProviderService;

/** The system binds this rule owner after reboot; every update rechecks durable occurrence bounds. */
public final class CourseDndConditionService extends ConditionProviderService {
    @Override public void onConnected() { refresh(); }
    @Override public void onSubscribe(Uri conditionId) { refresh(); }
    @Override public void onUnsubscribe(Uri conditionId) { }

    private void refresh() {
        CourseDndReceiver.register(getApplicationContext());
        CourseDndBroker.WORKER.execute(() -> CourseDndBroker.refresh(getApplicationContext()));
    }
}
