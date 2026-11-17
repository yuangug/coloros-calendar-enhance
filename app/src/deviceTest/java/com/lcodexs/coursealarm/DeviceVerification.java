package com.lcodexs.coursealarm;

import android.app.Activity;
import android.content.*;
import android.database.Cursor;
import android.os.*;
import android.net.Uri;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.lang.ref.WeakReference;
import java.util.*;

/** Included only with -PcoursealarmVerification. App-private command/response files. */
public final class DeviceVerification {
    static Context app; static String lastToken;
    static final Handler UI=new Handler(Looper.getMainLooper());
    static JSONObject pageInfo() throws Exception {
        java.lang.reflect.Field field=HostTablePage.class.getDeclaredField("page");field.setAccessible(true);
        Object page=field.get(null);JSONObject out=new JSONObject().put("activity",activity().getClass().getName())
                .put("showing",HostTablePage.showing()).put("page",System.identityHashCode(page));
        if(page!=null) {
            out.put("pager",System.identityHashCode(Ref.field(page,"pager")))
                    .put("loading",Ref.field(page,"loading")).put("dirty",Ref.field(page,"dirty"))
                    .put("generation",Ref.field(page,"generation")).put("week",HostTablePage.viewingWeekIndex0());
            HostTableData data=(HostTableData)Ref.field(page,"data");
            if(data!=null)out.put("calendarId",data.calendarId).put("courses",data.courseJsons.size());
        }
        return out;
    }
    public static void schedule(Context context){app=context;UI.postDelayed(new Runnable(){public void run(){
        File command=new File(app.getFilesDir(),"coursealarm-test-command.json");
        if(command.exists())try{JSONObject input=new JSONObject(new String(java.nio.file.Files.readAllBytes(command.toPath()),StandardCharsets.UTF_8));command.delete();DeviceVerification.run(input);}catch(Exception e){reply(null,e);}
        UI.postDelayed(this,350);
    }},1000);}
    static Activity activity()throws Exception{java.lang.reflect.Field field=UndoBar.class.getDeclaredField("resumed");field.setAccessible(true);Activity a=(Activity)((WeakReference<?>)field.get(null)).get();if(a==null)throw new IllegalStateException("No resumed Activity");return a;}
    static void run(JSONObject input)throws Exception{
        String op=input.getString("op");
        if(op.equals("pageInfo")) { reply(pageInfo(),null); return; }
        if(op.equals("invalidatePage")) { HostTablePage.refresh();reply(pageInfo(),null);return; }
        if(op.equals("openCourseEntry")) {
            Activity current=activity();
            current.startActivity(new Intent().setClassName(app.getPackageName(),TableEntryRoute.LEGACY));
            UI.postDelayed(()->{try{reply(pageInfo(),null);}catch(Exception e){reply(null,e);}},800);
            return;
        }
        if(op.equals("syncProbe")){
            long id=input.getLong("id");
            new Thread(()->{try{
                Object dao=Class.forName("com.coloros.calendar.foundation.databasedaolib.dao.SyncDataDao",false,app.getClassLoader()).getField("INSTANCE").get(null);
                List<Object> a=new ArrayList<>(),b=new ArrayList<>();
                try(Cursor c=app.getContentResolver().query(android.content.ContentUris.withAppendedId(android.provider.CalendarContract.Events.CONTENT_URI,id),null,null,null,null)){
                    if(c!=null&&c.moveToFirst()){
                        java.lang.reflect.Method m=Ref.methodByArity(dao.getClass(),"addEventMetaData",4);
                        m.invoke(dao,c,a,b,false);
                    }
                }
                reply(new JSONObject().put("cloudRecords",a.size()+b.size()),null);
            }catch(Exception e){reply(null,e);}}).start();return;
        }
        if(op.equals("nativeInfo")){
            Class<?> c=Class.forName("com.oplus.view.material.OplusMaterialCornerParams",false,app.getClassLoader());
            JSONArray methods=new JSONArray();for(java.lang.reflect.Method m:c.getDeclaredMethods())methods.put(m.toString());
            Object instance=c.getConstructor(float.class,float.class).newInstance(11.9f,1f);JSONArray fields=new JSONArray();for(java.lang.reflect.Field f:c.getDeclaredFields()){f.setAccessible(true);fields.put(f.getName()+"="+f.get(instance));}
            reply(new JSONObject().put("methods",methods).put("fields",fields),null);return;
        }
        if(op.equals("renderInfo")){
            java.lang.reflect.Field sf=CourseSurfaceRenderer.class.getDeclaredField("SESSIONS");sf.setAccessible(true);
            JSONArray sessions=new JSONArray();for(Object session:((Map<?,?>)sf.get(null)).values()){
                JSONArray cards=new JSONArray();for(Object card:((Map<?,?>)Ref.field(session,"cards")).values()){
                    android.view.View v=(android.view.View)card;
                    android.graphics.RenderNode foreground=(android.graphics.RenderNode)Ref.field(card,"foreground");
                    cards.put(new JSONObject().put("width",v.getWidth()).put("height",v.getHeight()).put("radius",Ref.field(card,"radius"))
                            .put("visible",v.getVisibility()).put("attached",v.isAttachedToWindow()).put("foregroundRecorded",foreground.hasDisplayList())
                            .put("foregroundWidth",foreground.getWidth()).put("foregroundHeight",foreground.getHeight())
                            .put("liquidRecorded",Ref.field(card,"liquid")==null || !((LiquidCardEffect)Ref.field(card,"liquid")).needsRecording())
                            .put("native",Ref.field(card,"nativeMaterial")!=null).put("matrix",Ref.field(card,"cardToRoot").toString()));
                }
                android.view.View table=(android.view.View)((WeakReference<?>)Ref.field(session,"table")).get();
                android.view.View layer=(android.view.View)Ref.field(session,"layer");
                android.view.View header=(android.view.View)Ref.field(session,"header");
                sessions.put(new JSONObject().put("style",Ref.field(session,"style")).put("layerVisible",layer.getVisibility())
                        .put("viewport",Ref.field(session,"viewport").toString()).put("week",Ref.field(table,"currentWeek"))
                        .put("scrollY",Ref.field(table,"scrollY")).put("contentStartY",Ref.field(table,"contentStartY"))
                        .put("headerWidth",header.getWidth()).put("headerHeight",header.getHeight()).put("headerElevation",header.getElevation())
                        .put("headerAttached",header.isAttachedToWindow()).put("headerBackground",String.valueOf(header.getBackground()))
                        .put("cards",cards));
            }reply(new JSONObject().put("sessions",sessions),null);return;
        }
        if(op.equals("backgroundInfo")){
            android.view.View root=HostTablePage.rootView();
            android.view.View window=ThemeWindowBackground.anchor(root);
            android.view.View source=root.findViewWithTag(HostTablePage.TAG_BG);
            JSONObject out=new JSONObject().put("style",ThemeStore.style(app)).put("scrim",ThemeStore.scrim(app))
                    .put("type",ThemeStore.type(app))
                    .put("rootToWindow",ThemeWindowBackground.windowToLocal(window,root).toString())
                    .put("windowWidth",window.getWidth()).put("windowHeight",window.getHeight());
            if(source instanceof android.widget.ImageView){android.widget.ImageView img=(android.widget.ImageView)source;
                out.put("imageMatrix",img.getImageMatrix().toString()).put("imageAlpha",img.getAlpha());}
            reply(out,null);return;
        }
        if(op.equals("themeSheet")){ThemeSheet.show(activity());reply(new JSONObject().put("opened",true),null);return;}
        if(op.equals("today")){HostTablePage.gotoToday();reply(new JSONObject().put("requested",true),null);return;}
        if(op.equals("inspect")){new Thread(()->{try{reply(inspect(),null);}catch(Exception e){reply(null,e);}}).start();return;}
        if(op.equals("select")){app.getSharedPreferences("com.coloros.calendar_preferences",0).edit().putLong("sp_key_current_course_table",input.getLong("id")).commit();HostBus.afterWrite(activity());reply(inspect(),null);return;}
        if(op.equals("reminder")){HostEntities.openReminder(activity());reply(new JSONObject().put("opened",true),null);return;}
        if(op.equals("theme")){if(input.has("style"))ThemeStore.setStyle(app,input.getInt("style"));if(input.has("type"))ThemeStore.setType(app,input.getInt("type"));if(input.has("scrim"))ThemeStore.setScrim(app,input.getInt("scrim"));ThemeBg.apply(activity());reply(new JSONObject().put("style",ThemeStore.style(app)).put("scrim",ThemeStore.scrim(app)),null);return;}
        if(op.equals("undo")){CourseMutations.undo(input.optString("token",lastToken));UI.postDelayed(()->{try{reply(inspect(),null);}catch(Exception e){reply(null,e);}},800);return;}
        if(op.equals("deleteCourse")){CourseMutations.deleteCourse(activity(),input.getLong("id"),false);UI.postDelayed(()->{try{reply(inspect(),null);}catch(Exception e){reply(null,e);}},800);return;}
        if(op.equals("deleteTable")){CourseMutations.deleteTables(activity(),Collections.singletonList(input.getLong("id")),false);UI.postDelayed(()->{try{reply(inspect(),null);}catch(Exception e){reply(null,e);}},800);return;}
        if(op.equals("atomicFailure")){
            new Thread(()->{try{long id=input.getLong("id");Uri uri=android.provider.CalendarContract.Events.CONTENT_URI;
                String old=title(id);ArrayList<ContentProviderOperation> ops=new ArrayList<>();
                ops.add(ContentProviderOperation.newDelete(uri).withSelection("_id=?",new String[]{Long.toString(id)}).withExpectedCount(1).withYieldAllowed(false).build());
                ops.add(ContentProviderOperation.newAssertQuery(uri).withSelection("_id=?",new String[]{"-999999"}).withExpectedCount(1).withYieldAllowed(false).build());
                boolean failed=false;int resultCount=-1;try{ContentProviderResult[] results=app.getContentResolver().applyBatch(android.provider.CalendarContract.AUTHORITY,ops);resultCount=results.length;}catch(Exception expected){failed=true;}
                reply(new JSONObject().put("rejected",failed).put("resultCount",resultCount).put("originalPreserved",Objects.equals(old,title(id))),null);
            }catch(Exception e){reply(null,e);}}).start();return;
        }
        if(op.equals("import")){
            TableCtx table=TableCtx.load(app);List<Course> list=new ArrayList<>();
            int count=input.optInt("count",2);for(int i=0;i<count;i++){Course c=new Course();c.name="CA_VERIFY_"+input.optString("suffix","A")+"_"+i;c.room="TEST";c.teacher="TEST";c.day=i%7;c.startNode=i%2*2;c.endNode=c.startNode+1;c.color=i%12;for(int w=1;w<=table.weekCount;w++)c.weeks.set(w);list.add(c);}
            if(input.optBoolean("invalid")&&!list.isEmpty())list.get(0).endNode=999;
            CourseMutations.Mode mode=CourseMutations.Mode.valueOf(input.optString("mode","APPEND"));
            CourseMutations.importCourses(app,table,list,mode,"CA_VERIFY_"+input.optString("suffix","TABLE"),result->{
                lastToken=result.undoToken;try{JSONObject out=inspect().put("success",result.success).put("message",result.message).put("token",result.undoToken).put("resultCalendarId",result.calendarId);reply(out,null);}
                catch(Exception e){reply(null,e);}
                if(result.success&&input.optBoolean("autoUndo"))UI.postDelayed(()->CourseMutations.undo(result.undoToken),500);
            });return;
        }
        throw new IllegalArgumentException(op);
    }
    static String title(long id)throws Exception{try(Cursor c=app.getContentResolver().query(android.provider.CalendarContract.Events.CONTENT_URI,new String[]{"title"},"_id=? AND deleted=0",new String[]{Long.toString(id)},null)){return c!=null&&c.moveToFirst()?c.getString(0):null;}}
    static JSONObject inspect()throws Exception{
        TableCtx t=TableCtx.load(app);JSONObject out=new JSONObject().put("calendarId",t.calendarId).put("name",t.tableName).put("courseCount",t.courses.size()).put("term",t.termBeginMs).put("weeks",t.weekCount).put("hasReminder",t.hasReminder).put("minutes",t.reminderMinutes).put("periodStart",new JSONArray(t.periodStart)).put("periodEnd",new JSONArray(t.periodEnd));
        JSONArray events=new JSONArray();try(Cursor c=app.getContentResolver().query(android.provider.CalendarContract.Events.CONTENT_URI,new String[]{"_id","calendar_id","title","deleted","hasAlarm","duration"},null,null,null)){while(c!=null&&c.moveToNext())if(c.getLong(1)==t.calendarId)events.put(new JSONObject().put("id",c.getLong(0)).put("title",c.getString(2)).put("deleted",c.getInt(3)).put("alarm",c.getInt(4)).put("duration",c.getString(5)));}
        out.put("events",events);
        JSONArray reminders=new JSONArray();try(Cursor c=app.getContentResolver().query(android.provider.CalendarContract.Reminders.CONTENT_URI,null,null,null,null)){while(c!=null&&c.moveToNext()){long event=c.getLong(c.getColumnIndexOrThrow("event_id"));for(int i=0;i<events.length();i++)if(events.getJSONObject(i).getLong("id")==event)reminders.put(new JSONObject().put("event",event).put("id",c.getLong(c.getColumnIndexOrThrow("_id"))).put("minutes",c.getInt(c.getColumnIndexOrThrow("minutes"))));}}
        out.put("reminders",reminders);
        java.lang.reflect.Field dl=UndoBar.class.getDeclaredField("deadline"),bv=UndoBar.class.getDeclaredField("bar");dl.setAccessible(true);bv.setAccessible(true);out.put("undoVisible",bv.get(null)!=null).put("undoRemaining",Math.max(0,dl.getLong(null)-SystemClock.elapsedRealtime()));
        java.lang.reflect.Field pending=CourseMutations.class.getDeclaredField("pending");pending.setAccessible(true);Object record=pending.get(null);
        if(record!=null)out.put("pendingToken",Ref.field(record,"token"));
        return out;
    }
    static synchronized void reply(JSONObject json,Throwable error){try{if(error!=null){while(error.getCause()!=null)error=error.getCause();json=new JSONObject().put("error",error.toString());}
        java.nio.file.Files.write(new File(app.getFilesDir(),"coursealarm-test-result.json").toPath(),json.toString(2).getBytes(StandardCharsets.UTF_8));
        Logx.i(CourseAlarmEntry.TAG,"DEVICE_TEST "+json);
    }catch(Exception e){Logx.e(CourseAlarmEntry.TAG,"device test reply",e);}}
}
