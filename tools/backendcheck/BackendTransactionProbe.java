package com.lcodexs.coursealarm;

import android.content.*;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.sqlite.*;
import android.net.Uri;
import android.os.Looper;
import org.json.*;
import java.lang.reflect.*;
import java.util.*;

/** Runs only via a separate app_process. Uses native batch code and synthetic in-memory tables.
 * No installed application's providers, database files, settings, or components are accessed. */
public final class BackendTransactionProbe {
    private static final ClassLoader LOADER=BackendTransactionProbe.class.getClassLoader();
    private static int checks;
    static void require(boolean ok,String why) { if(!ok)throw new AssertionError(why); checks++; }
    static void field(Class<?> cls,Object target,String name,Object value)throws Exception {
        Field f=cls.getDeclaredField(name);f.setAccessible(true);f.set(target,value);
    }
    static Object call(String name,Class<?>[] types,Object... args)throws Exception {
        Method m=CourseMutations.class.getDeclaredMethod(name,types);m.setAccessible(true);
        try{return m.invoke(null,args);}catch(InvocationTargetException e){throw new RuntimeException(name,e.getCause());}
    }
    static final class Sandbox extends ContextWrapper {
        ContentResolver resolver;
        final SharedPreferences prefs=(SharedPreferences)Proxy.newProxyInstance(LOADER,new Class[]{SharedPreferences.class},
            (p,m,a)->switch(m.getName()) {case "getLong"->1L;case "getAll"->Collections.emptyMap();default->a!=null&&a.length>1?a[1]:null;});
        Sandbox(Context base){super(base);}
        @Override public Context getApplicationContext(){return this;}
        @Override public String getPackageName(){return "com.coloros.calendar";}
        @Override public ClassLoader getClassLoader(){return LOADER;}
        @Override public ContentResolver getContentResolver(){return resolver;}
        @Override public SharedPreferences getSharedPreferences(String name,int mode){return prefs;}
    }
    public static final class FixtureProvider extends com.android.providers.calendar.SQLiteContentProvider {
        String authority;
        SQLiteDatabase database;
        @Override public SQLiteOpenHelper getDatabaseHelper(Context context) {
            return new SQLiteOpenHelper(context,null,null,1) {
                public void onCreate(SQLiteDatabase db) {
                    database=db;
                    db.execSQL("CREATE TABLE calendars (_id INTEGER PRIMARY KEY, account_name TEXT, account_type TEXT, calendar_displayName TEXT, calendars_json_extensions TEXT, deleted INTEGER DEFAULT 0, dirty INTEGER DEFAULT 0, mutators TEXT)");
                    db.execSQL("CREATE TABLE events (_id INTEGER PRIMARY KEY, calendar_id INTEGER, title TEXT, events_json_extensions TEXT, dtstart INTEGER, duration TEXT, rrule TEXT, hasAlarm INTEGER, hasExtendedProperties INTEGER, deleted INTEGER DEFAULT 0, dirty INTEGER DEFAULT 0, mutators TEXT)");
                    db.execSQL("CREATE TABLE reminders (_id INTEGER PRIMARY KEY, event_id INTEGER, minutes INTEGER, method INTEGER)");
                    db.execSQL("CREATE TABLE attendees (_id INTEGER PRIMARY KEY, event_id INTEGER, attendeeName TEXT)");
                    db.execSQL("CREATE TABLE extendedproperties (_id INTEGER PRIMARY KEY, event_id INTEGER, name TEXT, value TEXT)");
                    db.execSQL("CREATE TRIGGER course_children AFTER DELETE ON events BEGIN DELETE FROM reminders WHERE event_id=old._id; DELETE FROM attendees WHERE event_id=old._id; DELETE FROM extendedproperties WHERE event_id=old._id; END");
                    db.execSQL("CREATE TRIGGER table_children AFTER DELETE ON calendars BEGIN DELETE FROM events WHERE calendar_id=old._id; END");
                }
                public void onUpgrade(SQLiteDatabase db,int a,int b){throw new AssertionError();}
            };
        }
        String table(Uri uri){if(!authority.equals(uri.getAuthority()))throw new AssertionError("cross-provider access: "+uri);return uri.getPathSegments().get(0);}
        @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order) {
            String table=table(uri);
            if(uri.getPathSegments().size()==2) {selection="_id=?";args=new String[]{uri.getLastPathSegment()};}
            return getReadableDatabase().query(table,projection,selection,args,null,null,order);
        }
        @Override public String getType(Uri uri){return "vnd.android.cursor.dir/test";}
        @Override public Uri insertInTransaction(Uri uri,ContentValues values,boolean sync){return ContentUris.withAppendedId(uri,mDb.insertOrThrow(table(uri),null,values));}
        @Override public int updateInTransaction(Uri uri,ContentValues values,String selection,String[] args,boolean sync){return mDb.update(table(uri),values,selection,args);}
        @Override public int deleteInTransaction(Uri uri,String selection,String[] args,boolean sync){return mDb.delete(table(uri),selection,args);}
        @Override public void notifyChange(boolean sync,int type){}
        @Override public boolean shouldSyncFor(Uri uri){return false;}
        @Override public void onEndTransaction(boolean sync,Uri uri){}
    }
    static ContentValues values(Object... pairs){ContentValues v=new ContentValues();for(int i=0;i<pairs.length;i+=2){Object x=pairs[i+1];if(x instanceof Number)v.put((String)pairs[i],((Number)x).longValue());else v.put((String)pairs[i],(String)x);}return v;}
    static Object record(String authority)throws Exception {
        Class<?> cls=Class.forName("com.lcodexs.coursealarm.CourseMutations$Record");
        Constructor<?> c=cls.getDeclaredConstructor(String.class,long.class);c.setAccessible(true);Object r=c.newInstance("fixture",1L);field(cls,r,"state","COMMITTED");field(cls,r,"authority",authority);return r;
    }
    @SuppressWarnings("unchecked") static List<Object> list(Object record,String name)throws Exception {Field f=record.getClass().getDeclaredField(name);f.setAccessible(true);return (List<Object>)f.get(record);}
    static void image(Object record,String field,String table,long id)throws Exception {
        Object row=call("read",new Class[]{String.class,long.class},table,id);
        if(row==null){Class<?> cls=Class.forName("com.lcodexs.coursealarm.CourseMutations$Row");Method m=cls.getDeclaredMethod("absent",String.class,long.class);m.setAccessible(true);row=m.invoke(null,table,id);}
        list(record,field).add(row);
    }
    static void snapshots(Object record,String field)throws Exception {for(Object[] row:new Object[][]{{"calendars",1L},{"events",11L},{"reminders",21L},{"extendedproperties",31L},{"attendees",41L}})image(record,field,(String)row[0],(Long)row[1]);}
    @SuppressWarnings("unchecked") static void run(Context base,String authority)throws Exception {
        Sandbox context=new Sandbox(base);FixtureProvider provider=new FixtureProvider();provider.authority=authority;
        ProviderInfo info=new ProviderInfo();info.authority=authority;info.exported=false;provider.attachInfo(context,info);
        context.resolver=ContentResolver.wrap(provider);
        field(CourseMutations.class,null,"app",context);
        Constructor<CalendarBackend> c=CalendarBackend.class.getDeclaredConstructor(String.class);c.setAccessible(true);
        CalendarBackend backend=c.newInstance(authority);field(CourseMutations.class,null,"backend",backend);
        ContentResolver cr=context.resolver;
        cr.insert(backend.uri("calendars"),values("_id",1,"account_name","oplus_course_default_calendar","account_type","LOCAL","calendar_displayName","fixture","calendars_json_extensions","{\"hasReminder\":true,\"reminderTime\":30}"));
        cr.insert(backend.uri("events"),values("_id",11,"calendar_id",1,"title","fixture","events_json_extensions","{\"courseName\":\"fixture\",\"week\":[true,false,true]}","dtstart",1794960000000L,"duration","P6000S","rrule","FREQ=WEEKLY;COUNT=3","hasAlarm",1,"hasExtendedProperties",13));
        cr.insert(backend.uri("reminders"),values("_id",21,"event_id",11,"minutes",30,"method",1));
        cr.insert(backend.uri("extendedproperties"),values("_id",31,"event_id",11,"name","fixture","value","keep"));
        cr.insert(backend.uri("attendees"),values("_id",41,"event_id",11,"attendeeName","keep"));
        Object record=record(authority);snapshots(record,"before");
        ArrayList<ContentProviderOperation> failed=new ArrayList<>();
        failed.add(ContentProviderOperation.newDelete(backend.uri("calendars")).withSelection("_id=1",null).withExpectedCount(1).withYieldAllowed(false).build());
        failed.add(ContentProviderOperation.newAssertQuery(backend.uri("events")).withSelection("_id=-1",null).withExpectedCount(1).withYieldAllowed(false).build());
        boolean rolledBack=false;try{cr.applyBatch(authority,failed);}catch(OperationApplicationException expected){rolledBack=true;}
        require(rolledBack,"native batch failure not propagated");
        require((Boolean)call("unchanged",new Class[]{List.class},list(record,"before")),"native batch did not restore parents/children");
        ArrayList<ContentProviderOperation> delete=new ArrayList<>(failed.subList(0,1));cr.applyBatch(authority,delete);
        snapshots(record,"after");
        require(call("read",new Class[]{String.class,long.class},"reminders",21L)==null,"delete did not cascade");
        ArrayList<ContentProviderOperation> undo=(ArrayList<ContentProviderOperation>)call("buildUndo",new Class[]{record.getClass(),List.class},record,list(record,"after"));
        require(undo.stream().allMatch(op->authority.equals(op.getUri().getAuthority())&&!op.isYieldAllowed()),"undo escaped bound provider or allowed yield");
        cr.applyBatch(authority,undo);
        require((Boolean)call("restored",new Class[]{record.getClass()},record),"course/table/reminder identity restore failed");
        field(CourseSyncGate.class,null,"app",context);
        Method defer=CourseSyncGate.class.getDeclaredMethod("defer",String.class,String.class,Cursor.class);defer.setAccessible(true);
        try(android.database.MatrixCursor cursor=new android.database.MatrixCursor(new String[]{"_id","deleted"})) {
            cursor.addRow(new Object[]{11L,0});cursor.moveToFirst();
            CourseSyncGate.hold("fixture",authority,"events",11);
            String other=authority.equals(CalendarBackend.LOCAL)?CalendarBackend.MERGED:CalendarBackend.LOCAL;
            require((Boolean)defer.invoke(null,authority,"events",cursor),"undo sync hold missing");
            require(!(Boolean)defer.invoke(null,other,"events",cursor),"same ID in other provider was held");
            CourseSyncGate.release("fixture");
            require(!(Boolean)defer.invoke(null,null,"events",cursor),"unknown source held forever after release");
        }
        try(android.database.MatrixCursor staleCursor=new android.database.MatrixCursor(new String[]{"_id","deleted"})) {
            staleCursor.addRow(new Object[]{11L,1});staleCursor.moveToFirst();
            require((Boolean)defer.invoke(null,authority,"events",staleCursor),"stale deletion synced after restore");
        }
        // A newer edit must stop the whole undo batch before removing any associated records.
        Object changed=record(authority);snapshots(changed,"before");
        cr.update(backend.uri("events"),values("title","moved"),"_id=11",null);snapshots(changed,"after");
        ArrayList<ContentProviderOperation> stale=(ArrayList<ContentProviderOperation>)call("buildUndo",new Class[]{changed.getClass(),List.class},changed,list(changed,"after"));
        cr.update(backend.uri("events"),values("title","newer edit"),"_id=11",null);
        boolean conflict=false;try{cr.applyBatch(authority,stale);}catch(OperationApplicationException expected){conflict=true;}
        require(conflict,"stale undo overwrote newer edit");
        try(Cursor row=cr.query(backend.uri("events"),new String[]{"title"},"_id=11",null,null)){require(row.moveToFirst()&&row.getString(0).equals("newer edit"),"conflict rollback damaged course");}
        Method json=record.getClass().getDeclaredMethod("json");json.setAccessible(true);JSONObject saved=(JSONObject)json.invoke(record);
        require(saved.getInt("version")==2&&authority.equals(saved.getString("authority")),"journal lost provider identity");
        saved.remove("authority");saved.put("version",1);Method parse=record.getClass().getDeclaredMethod("parse",JSONObject.class);parse.setAccessible(true);Object legacy=parse.invoke(null,saved);
        Field source=record.getClass().getDeclaredField("authority");source.setAccessible(true);require(CalendarBackend.MERGED.equals(source.get(legacy)),"legacy journal retargeted local provider");
        // Only this isolated process changes the host's cached merge flag. Never change it
        // in the installed calendar process, where it would change real data routing.
        Class<?> merge=Class.forName("com.coloros.calendar.foundation.databasedaolib.helper.DataBaseMergeUtil");
        field(merge,null,"isMergeVersion",CalendarBackend.MERGED.equals(authority));
        require(authority.equals(CalendarBackend.current(context).authority),"backend resolver ignored native merge setting");
        field(merge,null,"isMergeVersion",CalendarBackend.LOCAL.equals(authority));
        boolean changedStore=false;try{backend.requireCurrent(context);}catch(IllegalStateException expected){changedStore=true;}
        require(changedStore,"backend switch accepted");
        field(merge,null,"isMigrating",true);
        boolean migrating=false;try{CalendarBackend.current(context);}catch(IllegalStateException expected){migrating=true;}
        require(migrating,"migration not blocked");
        field(merge,null,"isMigrating",false);
        provider.database.close();
    }
    public static void main(String[] args)throws Exception {
        try {
            if(Looper.getMainLooper()==null)Looper.prepareMainLooper();
            Class<?> at=Class.forName("android.app.ActivityThread");Object thread=at.getMethod("systemMain").invoke(null);
            Context base=(Context)at.getMethod("getSystemContext").invoke(thread);
            run(base,CalendarBackend.LOCAL);run(base,CalendarBackend.MERGED);
            System.out.println("COURSEALARM_BACKEND_RESULT="+new JSONObject().put("passed",true).put("checks",checks).put("scope","native SQLiteContentProvider batch implementation, synthetic in-memory CRUD, production undo planner and journal; no real host database"));
            System.exit(0);
        }catch(Throwable error){error.printStackTrace();System.out.println("COURSEALARM_BACKEND_RESULT="+new JSONObject().put("passed",false).put("checks",checks).put("error",error.toString()));System.exit(1);}
    }
}
