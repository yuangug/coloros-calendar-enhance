package com.android.providers.calendar;

// Compile-only declarations. The test DEX MUST NOT include this class: execution uses
// the SQLiteContentProvider implementation from each unmodified host APK.
public abstract class SQLiteContentProvider extends android.content.ContentProvider {
    protected android.database.sqlite.SQLiteDatabase mDb;
    public abstract android.database.sqlite.SQLiteOpenHelper getDatabaseHelper(android.content.Context context);
    public abstract android.net.Uri insertInTransaction(android.net.Uri uri, android.content.ContentValues values, boolean sync);
    public abstract int updateInTransaction(android.net.Uri uri, android.content.ContentValues values, String selection, String[] args, boolean sync);
    public abstract int deleteInTransaction(android.net.Uri uri, String selection, String[] args, boolean sync);
    public abstract void notifyChange(boolean sync, int type);
    public abstract boolean shouldSyncFor(android.net.Uri uri);
    public native android.database.sqlite.SQLiteDatabase getReadableDatabase();
    public native boolean onCreate();
    public native android.net.Uri insert(android.net.Uri uri, android.content.ContentValues values);
    public native int update(android.net.Uri uri, android.content.ContentValues values, String selection, String[] args);
    public native int delete(android.net.Uri uri, String selection, String[] args);
    public abstract void onEndTransaction(boolean sync, android.net.Uri uri);
}
