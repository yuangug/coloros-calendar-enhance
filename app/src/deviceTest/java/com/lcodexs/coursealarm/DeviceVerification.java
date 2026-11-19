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
    static DragSession lastDrag;
    static final Handler UI=new Handler(Looper.getMainLooper());
    static JSONObject pageInfo() throws Exception {
        java.lang.reflect.Field field=HostTablePage.class.getDeclaredField("page");field.setAccessible(true);
        Object page=field.get(null);JSONObject out=new JSONObject().put("activity",activity().getClass().getName())
                .put("showing",HostTablePage.showing()).put("page",System.identityHashCode(page));
        if(page!=null) {
            out.put("pager",System.identityHashCode(Ref.field(page,"pager")))
                    .put("loading",Ref.field(page,"loading")).put("dirty",Ref.field(page,"dirty"))
                    .put("generation",Ref.field(page,"generation")).put("week",HostTablePage.viewingWeekIndex0());
            for(String key:new String[]{"loadCount","bindCount","lastLoadMs","firstLoadMs","maxLoadMs","loadFailed"})out.put(key,Ref.field(page,key));
            Object active=Ref.field(page,"activeLoad");if(active!=null)out.put("phase",Ref.field(active,"phase"));
            HostTableData data=(HostTableData)Ref.field(page,"data");
            if(data!=null)out.put("calendarId",data.calendarId).put("courses",data.courseJsons.size());
        }
        return out;
    }
    public static void schedule(Context context, io.github.libxposed.api.XposedModule module) throws Exception {
        if (Build.VERSION.SDK_INT >= 28 && !context.getPackageName().equals(android.app.Application.getProcessName())) return;
        app=context;
        TableLoadProbe.traceColdStart(context, module);
        UI.postDelayed(new Runnable(){public void run(){
        File command=new File(app.getFilesDir(),"coursealarm-test-command.json");
        if(command.exists())try{JSONObject input=new JSONObject(new String(java.nio.file.Files.readAllBytes(command.toPath()),StandardCharsets.UTF_8));command.delete();DeviceVerification.run(input);}catch(Throwable e){reply(null,e);}
        UI.postDelayed(this,350);
    }},1000);}
    static Activity activity()throws Exception{java.lang.reflect.Field field=UndoBar.class.getDeclaredField("resumed");field.setAccessible(true);Activity a=(Activity)((WeakReference<?>)field.get(null)).get();if(a==null)throw new IllegalStateException("No resumed Activity");return a;}
    static void run(JSONObject input)throws Exception{
        String op=input.getString("op");
        if(op.equals("loadChecks")){TableLoadProbe.run(input.optString("mode","burst"));return;}
        if(op.equals("profileRead")) {
            new Thread(()->{try{
                JSONArray runs=new JSONArray();
                for(int i=0;i<5;i++) {
                    long start=SystemClock.elapsedRealtime();ThemeBg.warm(app);long theme=SystemClock.elapsedRealtime();
                    HostTableData data=HostTableData.load(app);long read=SystemClock.elapsedRealtime();
                    if(data.calendarId>0)TableSettings.refresh(app,data.calendarId);long settings=SystemClock.elapsedRealtime();
                    List<Object> courses=data.buildCourseList(app.getClassLoader());long built=SystemClock.elapsedRealtime();
                    runs.put(new JSONObject().put("themeMs",theme-start).put("readMs",read-theme)
                            .put("settingsMs",settings-read).put("buildMs",built-settings).put("totalMs",built-start)
                            .put("calendarId",data.calendarId).put("courses",courses.size()));
                }
                reply(new JSONObject().put("runs",runs),null);
            }catch(Throwable error){reply(null,error);}},"verify-table-read").start();return;
        }
        if(op.equals("restoreVerifiedEvent")) {
            new Thread(()->{try{
                JSONObject expected=input.getJSONObject("expected"),original=input.getJSONObject("original");
                long id=input.getLong("id"),calendarId=input.getLong("calendarId");
                ContentValues values=new ContentValues();
                for(String key:new String[]{"lastDate","events_json_extensions","dtstart","duration","event_update_time"}) {
                    if(original.isNull(key))values.putNull(key);
                    else if(key.equals("events_json_extensions")||key.equals("duration"))values.put(key,original.getString(key));
                    else values.put(key,original.getLong(key));
                }
                int changed=app.getContentResolver().update(android.provider.CalendarContract.Events.CONTENT_URI,values,
                        "_id=? AND calendar_id=? AND events_json_extensions=? AND dtstart=? AND event_update_time=?",
                        new String[]{Long.toString(id),Long.toString(calendarId),expected.getString("events_json_extensions"),
                                expected.getString("dtstart"),expected.getString("event_update_time")});
                if(changed!=1)throw new IllegalStateException("Restore rejected: event changed after backup comparison");
                UI.post(HostTablePage::refresh);
                reply(new JSONObject().put("restored",id),null);
            }catch(Exception error){reply(null,error);}},"verify-restore-event").start();return;
        }
        if(op.equals("renderChurn")){SurfacePerformanceProbe.sample(input.optInt("frames",30));return;}
        if(op.equals("gotoWeek")){HostTablePage.jumpTo(input.getInt("week"));reply(new JSONObject().put("requested",true),null);return;}
        if(op.equals("fixtureCourse")||op.equals("nativeRepairCheck")) {
            new Thread(()->{try{
                long id=input.getLong("id"); Object event=HostEntities.event(app,id);
                long calendarId=((Number)Ref.callName(event,"getCalendarId")).longValue();
                TableCtx selected=TableCtx.load(app);
                if(selected.calendarId!=calendarId||!selected.tableName.startsWith("CA_VERIFY_"))
                    throw new IllegalStateException("Fixture operations require the selected dedicated test table");
                String prefix="com.android.calendar.card.schooltimetable.entity.";
                JSONObject json=new JSONObject((String)Ref.callName(event,"getEventsJsonExtensions"));
                Object table=HostEntities.fromJson(app,prefix+"CourseTableEntity",selected.tableJson);
                if(op.equals("nativeRepairCheck")) {
                    Object course=HostEntities.fromJson(app,prefix+"CourseEntity",json.toString());HostEntities.set(course,"setEvent",event);
                    Class<?> utils=Class.forName("com.android.calendar.card.schooltimetable.ScheduleTableUtils",false,app.getClassLoader());
                    boolean invalid=(Boolean)utils.getMethod("isUselessCourse",course.getClass(),table.getClass()).invoke(null,course,table);
                    reply(new JSONObject().put("id",id).put("invalid",invalid).put("marked",json.has("_coursealarm_preserved_time_v1")),null);return;
                }
                for(String key:new String[]{"dayOfWeek","startNode","endNode","note"})if(input.has(key))json.put(key,input.get(key));
                if(input.has("weeks")){
                    Set<Integer> weeks=new HashSet<>();JSONArray raw=input.getJSONArray("weeks");for(int i=0;i<raw.length();i++)weeks.add(raw.getInt(i));
                    JSONArray mask=new JSONArray();for(int i=1;i<=selected.weekCount;i++)mask.put(weeks.contains(i));json.put("week",mask).put("weekType",0);
                }
                json.remove("_coursealarm_preserved_time_v1");
                Object course=HostEntities.fromJson(app,prefix+"CourseEntity",json.toString());
                Class<?> utils=Class.forName("com.android.calendar.card.schooltimetable.ScheduleTableUtils",false,app.getClassLoader());
                utils.getMethod("buildEventByCourseEntity",long.class,table.getClass(),event.getClass(),course.getClass()).invoke(null,calendarId,table,event,course);
                ContentValues all=HostEntities.values(app,"Event",event),values=new ContentValues();
                for(String key:new String[]{"dtstart","dtend","duration","rrule","events_json_extensions"}){
                    Object value=all.get(key);if(value==null)values.putNull(key);else if(value instanceof Number)values.put(key,((Number)value).longValue());else values.put(key,value.toString());
                }
                if(input.has("durationSeconds"))values.put("duration","P"+input.getInt("durationSeconds")+"S");
                if(input.has("startOffsetSeconds"))values.put("dtstart",all.getAsLong("dtstart")+input.getInt("startOffsetSeconds")*1000L);
                Uri uri=android.provider.CalendarContract.Events.CONTENT_URI;
                int count=app.getContentResolver().update(uri,values,"_id=? AND calendar_id=?",new String[]{Long.toString(id),Long.toString(calendarId)});
                UI.post(HostTablePage::refresh);reply(new JSONObject().put("updated",count),null);
            }catch(Exception e){reply(null,e);}},"test-fixture-course").start();return;
        }
        if(op.equals("pageInfo")) { reply(pageInfo(),null); return; }
        if(op.equals("alarmEnabled")){boolean before=Prefs.enabled();if(input.has("enabled"))Prefs.setEnabled(input.getBoolean("enabled"));reply(new JSONObject().put("before",before).put("enabled",Prefs.enabled()),null);return;}
        if(op.equals("tableSettings")) {
            Activity owner=activity();long id=input.optLong("id",TableCtx.hostCurrentTableId(app));
            if(input.optBoolean("open")){TableSettingsPage.open(owner);reply(new JSONObject().put("opened",true),null);return;}
            new Thread(()->{try{
                if(input.has("enabled"))TableSettings.setEnabled(app,id,input.getBoolean("enabled"));
                TableSettings.State state=TableSettings.refresh(app,id);
                reply(new JSONObject().put("id",id).put("enabled",state.enabled).put("identity",state.identity),null);
            }catch(Exception error){reply(null,error);}},"verify-settings").start();return;
        }
        if(op.equals("captureDrag")||op.equals("reschedule")||op.equals("rescheduleUi")) {
            Activity owner=activity();HostTableData data=HostTablePage.currentData();
            if(data==null)throw new IllegalStateException("No table data");
            Object selected=null;for(Object course:data.buildCourseList(app.getClassLoader())) {
                Object id=Ref.callName(Ref.callName(course,"getEvent"),"getId");
                if(id instanceof Number&&((Number)id).longValue()==input.getLong("id")){selected=course;break;}
            }
            if(selected==null)throw new IllegalStateException("Event not in current table");
            DragSession.capture(owner,selected,input.optInt("week",HostTablePage.viewingWeekIndex0()+1),(session,error)->{
                try{
                    if(session==null)throw new IllegalStateException(error);lastDrag=session;
                    if(op.equals("captureDrag")){reply(new JSONObject().put("eventId",session.eventId).put("calendarId",session.calendarId).put("week",session.displayedWeek),null);return;}
                    if(op.equals("rescheduleUi")){RescheduleUi.show(owner,session,input.getInt("day"),input.getInt("start"),()->{});reply(new JSONObject().put("opened",true),null);return;}
                    reschedule(owner,session,input);
                }catch(Exception failure){reply(null,failure);}
            });return;
        }
        if(op.equals("rescheduleFrozen")){if(lastDrag==null)throw new IllegalStateException("No captured session");reschedule(activity(),lastDrag,input);return;}
        if(op.equals("tableCells")) { JSONArray tables=new JSONArray(); collectTables(HostTablePage.rootView(),tables); reply(new JSONObject().put("tables",tables),null); return; }
        if(op.equals("providerSnapshot")) {
            new Thread(()->{try{reply(providerSnapshot(input.optLong("id",TableCtx.hostCurrentTableId(app))),null);}catch(Exception e){reply(null,e);}}).start();return;
        }
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
    static void reschedule(Activity owner,DragSession session,JSONObject input)throws Exception {
        RescheduleRequest.Scope scope=RescheduleRequest.Scope.valueOf(input.optString("scope","CURRENT_WEEK"));
        RescheduleRequest request;
        if(scope==RescheduleRequest.Scope.DAY){
            List<Long> ids=new ArrayList<>();JSONArray selected=input.optJSONArray("ids");
            if(selected==null)ids.add(session.eventId);else for(int i=0;i<selected.length();i++)ids.add(selected.getLong(i));
            request=RescheduleRequest.day(session,input.optInt("sourceWeek",session.displayedWeek),input.optInt("sourceDay",session.source.course.day),input.getInt("targetWeek"),input.getInt("day"),ids);
        }else{
            BitSet weeks=new BitSet();JSONArray chosen=input.optJSONArray("weeks");if(chosen!=null)for(int i=0;i<chosen.length();i++)weeks.set(chosen.getInt(i));
            int start=input.getInt("start");request=RescheduleRequest.range(session,scope,input.getInt("day"),start,input.optInt("end",start+session.source.course.endNode-session.source.course.startNode),weeks);
        }
        CourseMutations.reschedule(owner,request,result->{try{
            lastToken=result.undoToken;
            reply(inspect().put("success",result.success).put("message",result.message).put("token",result.undoToken),null);
            if(result.success&&result.undoToken!=null&&input.optBoolean("autoUndo"))UI.postDelayed(()->CourseMutations.undo(result.undoToken),input.optInt("undoDelay",500));
        }catch(Exception error){reply(null,error);}});
    }
    static void collectTables(android.view.View view,JSONArray out)throws Exception {
        if(view==null || !view.isShown())return;
        if(view.getClass().getName().equals("com.android.calendar.card.schooltimetable.view.TableView")){
            android.graphics.Rect visible=new android.graphics.Rect();if(!view.getGlobalVisibleRect(visible))return;
            int[] origin=new int[2];view.getLocationOnScreen(origin);
            JSONObject table=new JSONObject().put("x",origin[0]).put("y",origin[1]).put("width",view.getWidth()).put("height",view.getHeight())
                    .put("week",Ref.field(view,"currentWeek")).put("scrollY",Ref.field(view,"scrollY"))
                    .put("cellHeight",Ref.field(view,"cellHeigt")).put("cellWidth",Ref.field(view,"cellWidth"))
                    .put("startX",Ref.field(view,"contentStartX")).put("startY",Ref.field(view,"contentStartY"));
            JSONArray courses=new JSONArray();Object list=Ref.field(view,"showingCourseList");
            if(list instanceof List)for(Object course:(List<?>)list){
                JSONObject item=new JSONObject(CourseClipboard.snapshot(app.getClassLoader(),course));
                item.put("left",Ref.callName(course,"getMLeft")).put("top",Ref.callName(course,"getMTop"))
                        .put("right",Ref.callName(course,"getMRight")).put("bottom",Ref.callName(course,"getMBottom"))
                        .put("eventId",Ref.callName(Ref.callName(course,"getEvent"),"getId"));courses.put(item);
            }
            table.put("courses",courses);Object raw=Ref.field(view,"popupWindow");
            if(raw instanceof android.widget.PopupWindow && ((android.widget.PopupWindow)raw).isShowing()) {
                android.view.View content=((android.widget.PopupWindow)raw).getContentView();JSONArray buttons=new JSONArray();
                for(String name:new String[]{"btn_copy","btn_delete"}){
                    android.view.View button=content.findViewById(app.getResources().getIdentifier(name,"id",app.getPackageName()));
                    int[] position=new int[2];button.getLocationOnScreen(position);
                    buttons.put(new JSONObject().put("text",((android.widget.TextView)button).getText()).put("visible",button.getVisibility())
                            .put("x",position[0]+button.getWidth()/2).put("y",position[1]+button.getHeight()/2));
                }table.put("buttons",buttons);
            }
            Object callback=((WeakReference<?>)Ref.field(view,"mTableViewCallback")).get();
            table.put("hasCopy",Ref.callName(callback,"getCopyCourse")!=null);
            out.put(table);
        }
        if(view instanceof android.view.ViewGroup)for(int i=0;i<((android.view.ViewGroup)view).getChildCount();i++)collectTables(((android.view.ViewGroup)view).getChildAt(i),out);
    }
    static JSONObject providerSnapshot(long id)throws Exception{
        JSONObject result=new JSONObject().put("calendarId",id);Set<Long> ids=new HashSet<>();
        for(String table:new String[]{"calendars","events","reminders","extendedproperties","attendees"}){
            JSONArray records=new JSONArray();
            try(Cursor cursor=app.getContentResolver().query(Uri.parse("content://com.android.calendar/"+table),null,null,null,"_id ASC")){
                if(cursor==null)throw new IllegalStateException("No cursor: "+table);
                while(cursor.moveToNext()){
                    String filter=table.equals("calendars")?"_id":table.equals("events")?"calendar_id":"event_id";
                    long value=cursor.getLong(cursor.getColumnIndexOrThrow(filter));
                    if(table.equals("calendars") || table.equals("events")){if(value!=id)continue;}else if(!ids.contains(value))continue;
                    if(table.equals("events"))ids.add(cursor.getLong(cursor.getColumnIndexOrThrow("_id")));
                    JSONObject row=new JSONObject();for(String column:cursor.getColumnNames()){
                        int i=cursor.getColumnIndex(column);row.put(column,cursor.isNull(i)?JSONObject.NULL:cursor.getString(i));
                    }records.put(row);
                }
            }result.put(table,records);
        }return result;
    }
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
