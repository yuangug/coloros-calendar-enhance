package com.lcodexs.coursealarm;

import android.content.Context;
import android.content.pm.ProviderInfo;
import android.net.Uri;
import android.provider.CalendarContract;

/** One course operation belongs to exactly one provider, including its recovery journal. */
final class CalendarBackend {
    static final String LOCAL = "com.coloros.calendar";
    static final String MERGED = CalendarContract.AUTHORITY;
    final String authority;

    private CalendarBackend(String authority) { this.authority = authority; }

    static CalendarBackend current(Context context) throws Exception {
        Class<?> merge = Class.forName("com.coloros.calendar.foundation.databasedaolib.helper.DataBaseMergeUtil",
                false, context.getClassLoader());
        if (Boolean.TRUE.equals(merge.getMethod("isMigrating").invoke(merge.getField("INSTANCE").get(null))))
            throw new IllegalStateException("日历正在迁移存储，请稍后重试");
        return new CalendarBackend(HostEntities.local(context) ? LOCAL : MERGED);
    }

    Uri uri(String path) { return Uri.parse("content://" + authority + "/" + path); }

    void requireCurrent(Context context) throws Exception {
        if (!authority.equals(current(context).authority))
            throw new IllegalStateException("日历存储已切换，操作已停止；恢复记录已保留");
    }

    void requireAtomicProvider(Context context) throws Exception {
        ProviderInfo info = context.getPackageManager().resolveContentProvider(authority, 0);
        if (info == null) throw new IllegalStateException("日历存储不可用，未写入数据");
        if (LOCAL.equals(authority)) {
            // All three supported APKs implement non-yield applyBatch in this SQLite provider.
            // A different implementation must be reviewed before allowing reversible writes.
            if (!context.getPackageName().equals(info.packageName)
                    || !"com.android.providers.calendar.CalendarProvider2".equals(info.name))
                throw new IllegalStateException("此本地日历存储的事务能力尚未验证，未写入数据");
            Class<?> provider = Class.forName(info.name, false, context.getClassLoader());
            if (!"com.android.providers.calendar.SQLiteContentProvider".equals(provider.getSuperclass().getName()))
                throw new IllegalStateException("此本地日历存储的事务实现不兼容，未写入数据");
        }
    }
}
