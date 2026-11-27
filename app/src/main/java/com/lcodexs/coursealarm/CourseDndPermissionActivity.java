package com.lcodexs.coursealarm;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.widget.Toast;
import java.util.UUID;

/** Transparent permission bridge. External callers cannot supply an occurrence or DND command. */
public final class CourseDndPermissionActivity extends Activity {
    private String token, requestId;
    private boolean askedPolicy, askedExact, busy, finished;
    private int waitingForSettings;
    private long validationWaitStarted;
    private android.app.PendingIntent calendarContinuation;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable retryValidation = this::step;

    @Override protected void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        requestId = savedState == null ? UUID.randomUUID().toString() : savedState.getString("requestId");
        askedPolicy = savedState != null && savedState.getBoolean("askedPolicy");
        askedExact = savedState != null && savedState.getBoolean("askedExact");
        waitingForSettings = savedState == null ? 0 : savedState.getInt("waitingForSettings");
        calendarContinuation = verifiedContinuation();
        boolean action = CourseDndBroker.TOKEN_ACTION.equals(getIntent().getAction());
        if (action) {
            Uri data = getIntent().getData();
            if (data == null || !"course-dnd".equals(data.getScheme()) || !"toggle".equals(data.getAuthority())
                    || data.getPathSegments().size() != 1) { finish(); return; }
            token = data.getLastPathSegment();
        }
        if (waitingForSettings == 0) step();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putString("requestId", requestId); out.putBoolean("askedPolicy", askedPolicy);
        out.putBoolean("askedExact", askedExact);
        out.putInt("waitingForSettings", waitingForSettings);
        super.onSaveInstanceState(out);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 41 || requestCode == 42) { waitingForSettings = 0; step(); }
    }

    private void step() {
        if (busy || finished || isFinishing()) return;
        busy = true;
        CourseDndBroker.WORKER.execute(() -> {
            Bundle request = new Bundle(); request.putInt("protocol", 1);
            Bundle status = token == null ? CourseDndBroker.call(getApplicationContext(), "status", request)
                    : CourseDndBroker.tokenStatus(getApplicationContext(), token);
            runOnUiThread(() -> { busy = false; continueWith(status); });
        });
    }

    private void continueWith(Bundle status) {
        if (finished || isFinishing() || isDestroyed()) return;
        if (!status.getBoolean("success") || !status.getBoolean("supported")) {
            end(status.getString("error", "当前系统不支持课程免打扰")); return;
        }
        if (token != null && CourseDndClient.active(status)) { end("免打扰已开启"); return; }
        boolean needsPermissions = token == null || status.getBoolean("requestedEnable", true);
        if (needsPermissions && !status.getBoolean("access")) {
            if (askedPolicy) { end("尚未授予免打扰权限，未开启课程免打扰"); return; }
            askedPolicy = true;
            Intent policy = new Intent("android.settings.NOTIFICATION_POLICY_ACCESS_DETAIL_SETTINGS",
                    Uri.parse("package:" + getPackageName()));
            if (policy.resolveActivity(getPackageManager()) == null)
                policy = new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS);
            launch(policy, 41); return;
        }
        if (needsPermissions && !status.getBoolean("exactAlarmAccess")) {
            if (askedExact) { end("尚未允许准时结束所需的闹钟权限，未开启课程免打扰"); return; }
            askedExact = true;
            launch(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:" + getPackageName())), 42); return;
        }
        busy = true;
        CourseDndBroker.WORKER.execute(() -> {
            Bundle result = token == null ? CourseDndBroker.refresh(getApplicationContext())
                    : CourseDndBroker.toggleToken(getApplicationContext(), token, requestId);
            runOnUiThread(() -> {
                busy = false;
                if (finished || isFinishing() || isDestroyed()) return;
                if (result.getBoolean("revalidationRequired") && waitForValidation()) return;
                if (!result.getBoolean("success")) end(result.getString("error", "课程免打扰操作失败"));
                else if (token == null && (!result.getBoolean("access") || !result.getBoolean("exactAlarmAccess")))
                    end("免打扰或闹钟权限已变化，未开启课程免打扰");
                else if (token == null && calendarContinuation != null) {
                    try { calendarContinuation.send(); finished = true; finish(); }
                    catch (android.app.PendingIntent.CanceledException error) { end("课程操作已过期，请重新点击免打扰"); }
                }
                else if (token == null) end("课程免打扰权限已就绪");
                else if (CourseDndClient.active(result)) end("免打扰已开启");
                else if (result.getBoolean("reserved")) end("已预约免打扰，上课时开启");
                else end("已取消本次免打扰预约");
            });
        });
    }

    private android.app.PendingIntent verifiedContinuation() {
        // isImmutable is public from API 31. Older systems retain the normal nonce action;
        // they cannot accept an unverifiable external continuation through this fallback.
        if (android.os.Build.VERSION.SDK_INT < 31) return null;
        try {
            Object value = getIntent().getParcelableExtra(CourseDndClient.CONTINUATION);
            if (!(value instanceof android.app.PendingIntent)) return null;
            android.app.PendingIntent continuation = (android.app.PendingIntent) value;
            int calendarUid = getPackageManager().getApplicationInfo("com.coloros.calendar", 0).uid;
            if (continuation.getCreatorUid() == calendarUid
                    && "com.coloros.calendar".equals(continuation.getCreatorPackage()) && continuation.isImmutable())
                return continuation;
        } catch (android.content.pm.PackageManager.NameNotFoundException | RuntimeException ignored) { }
        return null;
    }

    private void launch(Intent intent, int request) {
        try { waitingForSettings = request; startActivityForResult(intent, request); }
        catch (ActivityNotFoundException | SecurityException error) { end("无法打开系统授权页面，请在系统设置中授予权限"); }
    }

    private void end(String message) {
        if (finished || isDestroyed()) return;
        finished = true;
        main.removeCallbacks(retryValidation);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        finish();
    }

    private boolean waitForValidation() {
        if (validationWaitStarted == 0) validationWaitStarted = SystemClock.elapsedRealtime();
        if (SystemClock.elapsedRealtime() - validationWaitStarted >= 8000) return false;
        main.removeCallbacks(retryValidation);
        main.postDelayed(retryValidation, 250);
        return true;
    }

    @Override protected void onDestroy() {
        main.removeCallbacks(retryValidation);
        super.onDestroy();
    }
}
