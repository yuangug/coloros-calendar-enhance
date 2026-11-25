package com.lcodexs.coursealarm;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Bundle;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import org.json.JSONArray;
import org.json.JSONObject;

/** Isolated ART checks. Builds notifications without posting them or changing any timetable. */
final class CourseLiveDeviceChecks {
    static JSONObject exercise(Context app, JSONObject input) throws Exception {
        String operation = input.getString("action");
        HostTableData data = HostTableData.load(app);
        if (!data.tableName.startsWith("CA_VERIFY_")) throw new IllegalStateException("Dedicated test table required");
        long id = input.optLong("id", data.events.isEmpty() ? -1 : CourseLiveCoordinator.number(data.events.get(0), "getId"));
        Object event = id <= 0 ? null : HostEntities.event(app, id);
        if (event != null && CourseLiveCoordinator.number(event, "getCalendarId") != data.calendarId)
            throw new IllegalStateException("Event outside selected test table");
        JSONObject output = new JSONObject().put("calendarId", data.calendarId).put("id", id);
        if (operation.equals("promotionCapability")) {
            NotificationManager manager = app.getSystemService(NotificationManager.class);
            output.put("sdk", android.os.Build.VERSION.SDK_INT);
            output.put("declaredPermission", app.checkSelfPermission("android.permission.POST_PROMOTED_NOTIFICATIONS"));
            if (android.os.Build.VERSION.SDK_INT >= 36) output.put("canPromote", manager.canPostPromotedNotifications());
            return output;
        }
        if (operation.equals("promotionTrial") && android.os.Build.VERSION.SDK_INT >= 36) {
            NotificationManager manager = app.getSystemService(NotificationManager.class);
            int icon = app.getResources().getIdentifier("ic_push", "drawable", app.getPackageName());
            Notification trial = new Notification.Builder(app, "Calendar_course_channel_default")
                    .setSmallIcon(icon).setContentTitle("CA_VERIFY_PROMOTION")
                    .setContentText("课程实时活动接口验证").setOngoing(true).setOnlyAlertOnce(true)
                    .setTimeoutAfter(10000)
                    .setStyle(new Notification.ProgressStyle().setProgress(4500)
                            .setProgressSegments(java.util.List.of(new Notification.ProgressStyle.Segment(10000))))
                    .build();
            trial.extras.putBoolean("android.requestPromotedOngoing", true);
            manager.notify("coursealarm-verification-promotion", 0x4c495645, trial);
            Thread.sleep(700);
            for (android.service.notification.StatusBarNotification active : manager.getActiveNotifications())
                if ("coursealarm-verification-promotion".equals(active.getTag())) {
                    Notification n = active.getNotification();
                    output.put("posted",true).put("flags",n.flags)
                            .put("promoted",(n.flags & Notification.FLAG_PROMOTED_ONGOING) != 0);
                }
            manager.cancel("coursealarm-verification-promotion", 0x4c495645);
            return output;
        }
        if (operation.equals("fixture")) {
            TableCtx table = TableCtx.load(app);
            long now = System.currentTimeMillis();
            java.time.ZoneId zone = java.time.ZoneId.systemDefault();
            java.time.LocalDate today = java.time.LocalDate.now(zone);
            java.time.LocalDate monday = java.time.Instant.ofEpochMilli(table.termBeginMs).atZone(zone).toLocalDate()
                    .with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
            int week = (int) java.time.temporal.ChronoUnit.WEEKS.between(monday, today) + 1;
            JSONObject course = new JSONObject((String) Ref.callName(event,"getEventsJsonExtensions"));
            JSONArray weeks = new JSONArray(); for(int w=1;w<=table.weekCount;w++) weeks.put(w==week);
            course.put("dayOfWeek", today.getDayOfWeek().getValue()-1).put("week",weeks).put("weekType",0);
            android.content.ContentValues values = HostEntities.values(app,"Event",event);
            long start = now-input.optInt("elapsedSeconds",2700)*1000L;
            long end = start+input.optInt("durationSeconds",6000)*1000L;
            values.put("dtstart",start); values.put("duration","P"+((end-start)/1000)+"S");
            values.putNull("dtend"); values.put("rrule","FREQ=WEEKLY;COUNT=1");
            values.put("events_json_extensions",course.toString());
            CourseRescheduleRepair.stamp(values,table.tableJson);
            android.content.ContentValues update = new android.content.ContentValues();
            update.put("dtstart",start);update.putNull("dtend");update.put("duration",values.getAsString("duration"));
            update.put("rrule",values.getAsString("rrule"));update.put("events_json_extensions",values.getAsString("events_json_extensions"));
            int count=app.getContentResolver().update(CalendarBackend.current(app).uri("events"),update,
                    "_id=? AND calendar_id=?",new String[]{Long.toString(id),Long.toString(data.calendarId)});
            CourseLiveCoordinator.settingsChanged();
            return output.put("updated",count).put("start",start).put("end",end).put("week",week);
        }
        CourseLiveSettings.State state = CourseLiveSettings.read(app,data.calendarId);
        if(operation.equals("configure")) {
            if(input.has("astra"))CourseLiveSettings.setBoolean(app,data.calendarId,state.identity,CourseLiveSettings.ASTRA_ENABLED,input.getBoolean("astra"));
            if(input.has("enabled"))CourseLiveSettings.setBoolean(app,data.calendarId,state.identity,CourseLiveSettings.ENABLED,input.getBoolean("enabled"));
            if(input.has("retain"))CourseLiveSettings.setBoolean(app,data.calendarId,state.identity,CourseLiveSettings.RETAIN_ON_CLICK,input.getBoolean("retain"));
            if(input.has("keepDuringCourse"))CourseLiveSettings.setBoolean(app,data.calendarId,state.identity,CourseLiveSettings.KEEP_DURING_COURSE,input.getBoolean("keepDuringCourse"));
            if(input.has("auto"))CourseLiveSettings.setBoolean(app,data.calendarId,state.identity,CourseLiveSettings.AUTO_DND,input.getBoolean("auto"));
            if(input.has("minutes"))CourseLiveSettings.setRetention(app,data.calendarId,state.identity,input.getInt("minutes"));
            CourseLiveCoordinator.settingsChanged();return output.put("configured",true);
        }
        CourseOccurrence occurrence = CourseLiveCoordinator.find(id,0);
        if(operation.equals("islandState")) {
            if(occurrence==null) return output.put("loaded",false);
            long sampledAt=System.currentTimeMillis();
            return output.put("loaded",true).put("key",occurrence.key)
                    .put("sampledAt",sampledAt).put("phase",occurrence.phase(sampledAt,state.retainMinutes,
                            CourseLiveCoordinator.isDismissed(occurrence)).name())
                    .put("shade",CourseLiveSeedlingState.hasNotification(occurrence))
                    .put("sdkUntil",app.getSharedPreferences("lspx_course_live_instances_v1",0).getLong("astra:"+occurrence.key,0))
                    .put("astraRouting",CourseLiveCoordinator.astraOwnsCapsule(occurrence));
        }
        if(operation.equals("arm") || operation.equals("disarm")) {
            if (event == null || (operation.equals("arm") && occurrence == null))
                throw new IllegalStateException("Test event or occurrence not loaded");
            Class<?> alarms = Class.forName("com.coloros.calendar.foundation.databasedaolib.contract.CalendarContractOPlus$CalendarAlerts",
                    false, app.getClassLoader());
            Class<?> merge = Class.forName("com.coloros.calendar.foundation.databasedaolib.helper.DataBaseMergeUtil",
                    false, app.getClassLoader());
            boolean merged = Boolean.TRUE.equals(merge.getMethod("isCalendarProviderMergeVerison", Context.class).invoke(null, app));
            android.net.Uri uri = (android.net.Uri) alarms.getMethod("getContentUri", boolean.class).invoke(null, !merged);
            int removed = app.getContentResolver().delete(uri, "event_id=?", new String[]{Long.toString(id)});
            if (operation.equals("disarm")) return output.put("disarmed", true).put("removed", removed);
            long when = System.currentTimeMillis() + 1000L * input.optInt("afterSeconds", 8);
            android.net.Uri row = (android.net.Uri) alarms.getMethod("insert", android.content.ContentResolver.class,
                    long.class,long.class,long.class,long.class,int.class)
                    .invoke(null, app.getContentResolver(), id, occurrence.start, occurrence.end, when, 0);
            if (row == null) throw new IllegalStateException("Host did not insert reminder");
            alarms.getMethod("scheduleAlarm", Context.class, android.app.AlarmManager.class, long.class)
                    .invoke(null, app, app.getSystemService(android.app.AlarmManager.class), when);
            return output.put("alarmAt", when).put("start", occurrence.start).put("end", occurrence.end)
                    .put("merged", merged).put("row", row.toString());
        }
        if(operation.equals("publish")) {
            if(occurrence==null)throw new IllegalStateException("Occurrence not loaded");
            ClassLoader host=app.getClassLoader();
            Class<?> infoClass=Class.forName("com.android.calendar.alerts.EventNotificationInfo",false,host);
            Object info=infoClass.getDeclaredConstructors()[0].newInstance(occurrence.title,occurrence.room,
                    occurrence.start,occurrence.end,occurrence.reminderAt,id,false,java.time.ZoneId.systemDefault().getId(),0,
                    System.currentTimeMillis(),"","","","","","","","","","",CourseWriter.COURSE_EXT_PROP,"");
            Ref.callName(info,"setAccountName",Ref.callName(data.calendar,"getAccountName"));
            Ref.callName(info,"setAccountType",Ref.callName(data.calendar,"getAccountType"));
            if(!Boolean.TRUE.equals(Ref.callName(info,"isCourse")))throw new IllegalStateException("Native info is not course");
            app.getSystemService(NotificationManager.class).createNotificationChannel(new android.app.NotificationChannel(
                    "Calendar_course_channel_default","课程提醒",NotificationManager.IMPORTANCE_DEFAULT));
            Notification notification=null;
            for(String owner:new String[]{"com.android.calendar.alerts.l","com.android.calendar.alerts.o"})
                for(Method method:Class.forName(owner,false,host).getDeclaredMethods()) {
                    Class<?>[] t=method.getParameterTypes();
                    if(method.getReturnType()==Notification.class&&t.length==6&&t[0]==Context.class&&t[1].getSimpleName().equals("INotificationInfo")
                            &&t[2]==int.class&&t[3]==boolean.class&&t[4]==String.class&&t[5]==boolean.class)
                        notification=(Notification)method.invoke(null,app,info,1,false,"Calendar_course_channel_default",CalendarBackend.LOCAL.equals(data.backendAuthority));
                }
            if(notification==null)throw new IllegalStateException("No native notification factory");
            android.content.ContentValues alert=new android.content.ContentValues();
            alert.put("event_id",id);alert.put("begin",occurrence.start);alert.put("end",occurrence.end);
            alert.put("alarmTime",occurrence.reminderAt);alert.put("state",1);alert.put("minutes",15);
            android.net.Uri alerts=CalendarBackend.current(app).uri("calendar_alerts");
            app.getContentResolver().delete(alerts,"event_id=?",new String[]{Long.toString(id)});
            app.getContentResolver().insert(alerts,alert);
            app.getSystemService(NotificationManager.class).notify("tag_normal_remind",(int)id,notification);
            if(input.optBoolean("fluid",true)) {
                Class<?> manager=Class.forName("com.android.calendar.seedling.notificationfluidcard.FluidSeedingManager",false,host);
                Ref.callName(manager.getField("INSTANCE").get(null),"sendFluidSeedingCard",info);
            }
            return output.put("published",true).put("key",occurrence.key);
        }
        if(operation.equals("settings")||operation.equals("detail")) {
            android.app.Activity activity=DeviceVerification.activity();
            Object course=HostEntities.fromJson(app,"com.android.calendar.card.schooltimetable.entity.CourseEntity",
                    (String)Ref.callName(event,"getEventsJsonExtensions"));
            HostEntities.set(course,"setEvent",event);
            java.util.concurrent.CountDownLatch done=new java.util.concurrent.CountDownLatch(1);
            activity.runOnUiThread(()->{try{
                if(operation.equals("settings"))TableSettingsPage.open(activity);
                else {
                    java.util.ArrayList<Object> list=new java.util.ArrayList<>();list.add(course);
                    CourseInfoSheet.show(activity,app.getClassLoader(),list,0);
                }
            }finally{done.countDown();}});
            done.await(5,java.util.concurrent.TimeUnit.SECONDS);return output.put("opened",true);
        }
        JSONArray notifications=new JSONArray();
        for(android.service.notification.StatusBarNotification active:app.getSystemService(NotificationManager.class).getActiveNotifications()) {
            Notification n=active.getNotification();
            if(active.getId()!=id)continue;
            JSONObject row=new JSONObject().put("tag",active.getTag()).put("key",n.extras.getString(CourseLiveCoordinator.KEY))
                    .put("text",n.extras.getCharSequence(Notification.EXTRA_TEXT)).put("retain",(n.flags&Notification.FLAG_AUTO_CANCEL)==0)
                    .put("style",n.extras.getString(Notification.EXTRA_TEMPLATE));
            JSONArray actions=new JSONArray();if(n.actions!=null)for(Notification.Action a:n.actions)actions.put(a.title);
            row.put("actions",actions);notifications.put(row);
            if(operation.equals("click"))n.contentIntent.send();
            if(operation.equals("dismiss")){if(n.deleteIntent!=null)n.deleteIntent.send();app.getSystemService(NotificationManager.class).cancel(active.getTag(),active.getId());}
            if(operation.equals("dnd")){for(Notification.Action a:n.actions)if(a.getExtras().getBoolean("lspx.live.dnd")){a.actionIntent.send();break;}}
        }
        output.put("notifications",notifications);
        if(occurrence!=null) {
            output.put("key",occurrence.key).put("start",occurrence.start).put("end",occurrence.end).put("dismissed",CourseLiveCoordinator.isDismissed(occurrence));
            Bundle status=CourseDndClient.status(app,occurrence.key);JSONObject dnd=new JSONObject();
            for(String k:status.keySet())dnd.put(k,String.valueOf(status.get(k)));output.put("dnd",dnd);
        }
        return output;
    }
    static JSONObject run(Context app) throws Exception {
        JSONObject result = new JSONObject();
        result.put("settings", CourseLiveSettingsChecks.run(app));
        result.put("notificationHook", CourseLiveCapabilities.notificationHook);
        result.put("fluidHook", CourseLiveCapabilities.fluidHook);
        result.put("capabilities", CourseLiveCapabilities.summary(app));
        long start = System.currentTimeMillis() - 45 * 60000L, end = start + 100 * 60000L;
        CourseOccurrence occurrence = new CourseOccurrence("test", "isolated", 99999, 99999,
                start, end, start - 15 * 60000L, "隔离课程", "测试教室", "测试教师");
        CourseLiveSettings.State state = new CourseLiveSettings.State(99999, "isolated", true, true, true, true, false, 5,
                false, CourseAstraStyle.TIMETABLE, false);
        int icon = app.getResources().getIdentifier("ic_push", "drawable", app.getPackageName());
        Notification original = new Notification.Builder(app, "Calendar_course_channel_default").setSmallIcon(icon)
                .setContentTitle("隔离课程").setContentText("原生内容").setAutoCancel(true).build();
        Class<?> postedType = Class.forName(CourseLiveCoordinator.class.getName() + "$Posted");
        Constructor<?> constructor = postedType.getDeclaredConstructors()[0]; constructor.setAccessible(true);
        Object posted = constructor.newInstance(occurrence.key, "tag_normal_remind", 99999, original);
        Method decorate = CourseLiveCoordinator.class.getDeclaredMethod("decorate", postedType, CourseOccurrence.class,
                CourseLiveSettings.State.class, boolean.class, long.class); decorate.setAccessible(true);
        Notification notification = (Notification) decorate.invoke(null, posted, occurrence, state, false, start + 45 * 60000L);
        result.put("retainClick", (notification.flags & Notification.FLAG_AUTO_CANCEL) == 0);
        result.put("title", notification.extras.getCharSequence(Notification.EXTRA_TITLE));
        result.put("timing", notification.extras.getCharSequence(Notification.EXTRA_TEXT));
        result.put("start", notification.extras.getLong(CourseLiveCoordinator.START));
        result.put("end", notification.extras.getLong(CourseLiveCoordinator.END));
        result.put("progress", occurrence.progress(start + 45 * 60000L));
        result.put("style", notification.extras.getString(Notification.EXTRA_TEMPLATE));
        result.put("timeout", notification.getTimeoutAfter());
        result.put("deleteAction", notification.deleteIntent != null);
        result.put("notificationCount", app.getSystemService(NotificationManager.class).getActiveNotifications().length);
        Bundle dnd = CourseDndClient.status(app);
        JSONObject status = new JSONObject();
        for (String key : new String[]{"supported", "access", "exactAlarmAccess", "systemActive", "ownedActive", "success"})
            status.put(key, dnd.getBoolean(key));
        if (dnd.containsKey("error")) status.put("error", dnd.getString("error"));
        result.put("dnd", status);
        try {
            android.content.pm.ProviderInfo provider = app.getPackageManager().resolveContentProvider(CourseDndClient.AUTHORITY, 0);
            result.put("dndProvider", provider == null ? "unresolved" : provider.packageName + "/" + provider.name);
            Bundle request = new Bundle(); request.putInt("protocol", 1);
            result.put("dndDirect", String.valueOf(app.getContentResolver().call(
                    android.net.Uri.parse("content://" + CourseDndClient.AUTHORITY), "status", null, request)));
        } catch (Exception error) {
            result.put("dndDirectError", android.util.Log.getStackTraceString(error));
        }
        return result;
    }
}
