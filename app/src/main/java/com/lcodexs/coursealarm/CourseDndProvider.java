package com.lcodexs.coursealarm;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;

/** Private protocol with an explicit UID check; calendar is signed by the OEM, not this module. */
public final class CourseDndProvider extends ContentProvider {
    @Override public boolean onCreate() {
        android.content.Context context = moduleContext().getApplicationContext();
        CourseSystemUiScope.Broker.init();
        CourseDndReceiver.register(context);
        CourseDndBroker.WORKER.execute(() -> CourseDndBroker.refresh(context));
        return true;
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        enforceCaller();
        long identity = Binder.clearCallingIdentity();
        try {
            if (CourseSystemUiScope.REQUEST.equals(method) || CourseSystemUiScope.STATUS.equals(method))
                return CourseSystemUiScope.Broker.handle(method, extras);
            return CourseDndBroker.call(moduleContext(), method, extras);
        }
        finally { Binder.restoreCallingIdentity(identity); }
    }

    private android.content.Context moduleContext() {
        android.content.Context context = getContext();
        if (context == null) throw new IllegalStateException("免打扰服务尚未就绪");
        return context;
    }

    private void enforceCaller() {
        int caller = Binder.getCallingUid();
        if (caller == Process.myUid()) return;
        try {
            int calendar = moduleContext().getPackageManager().getApplicationInfo("com.coloros.calendar", 0).uid;
            if (caller == calendar) return;
        } catch (PackageManager.NameNotFoundException ignored) { }
        throw new SecurityException("Only the installed calendar and module may access course DND");
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        throw new UnsupportedOperationException("Use the versioned course DND call protocol");
    }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
