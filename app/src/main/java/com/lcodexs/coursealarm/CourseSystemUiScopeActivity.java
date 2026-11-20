package com.lcodexs.coursealarm;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;

/** First launch on ROMs that block provider access until the user opens the module app. */
public final class CourseSystemUiScopeActivity extends Activity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!"com.coloros.calendar".equals(getCallingPackage())) { finish(); return; }
        if (savedInstanceState != null) { finish(); return; }
        ResultReceiver caller = getIntent().getParcelableExtra(CourseSystemUiScope.REPLY);
        if (caller == null) { finish(); return; }
        Bundle args = new Bundle();
        args.putParcelable(CourseSystemUiScope.REPLY, new ResultReceiver(new Handler(Looper.getMainLooper())) {
            @Override protected void onReceiveResult(int code, Bundle result) {
                caller.send(code, result);
                finish();
            }
        });
        Bundle accepted = CourseSystemUiScope.Broker.handle(CourseSystemUiScope.REQUEST, args);
        if (!accepted.getBoolean("success")) {
            caller.send(0, accepted);
            finish();
        }
    }
}
