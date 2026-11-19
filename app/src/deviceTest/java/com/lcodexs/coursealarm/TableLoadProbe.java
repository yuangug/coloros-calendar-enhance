package com.lcodexs.coursealarm;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.ContentResolver;
import android.os.CancellationSignal;
import android.os.SystemClock;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Read-only regression checks; compiled only into the private verification APK. */
final class TableLoadProbe {
    // Only the verification build samples stacks. Keep the timestamps and PID in logcat
    // even when a timed-out task is no longer the active task for its page.
    static void traceColdStart(Context context, io.github.libxposed.api.XposedModule module) throws Exception {
        Thread main = android.os.Looper.getMainLooper().getThread();
        new Thread(() -> {
            long end = SystemClock.uptimeMillis() + 32_000;
            Thread worker = null;
            String previous = "";
            while (!committed && SystemClock.uptimeMillis() < end) {
                SystemClock.sleep(350);
                if (worker == null || !worker.isAlive()) {
                    ThreadGroup group = main.getThreadGroup();
                    Thread[] threads = new Thread[group.activeCount() + 32];
                    int count = group.enumerate(threads);
                    for (int i = 0; i < count; i++)
                        if ("lspx-table-load".equals(threads[i].getName())) worker = threads[i];
                }
                if (worker == null) continue;
                String stack = java.util.Arrays.toString(worker.getStackTrace());
                if (!stack.equals(previous)) {
                    record(context, "worker " + stack);
                    record(context, "ui " + java.util.Arrays.toString(main.getStackTrace()));
                    previous = stack;
                }
            }
        }, "verify-cold-read").start();
        Class<?> table = Class.forName("com.android.calendar.card.schooltimetable.view.TableView", false, context.getClassLoader());
        module.hook(table.getDeclaredMethod("drawCourse", android.graphics.Canvas.class)).setId("verify_course_first_frame")
                .setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    Object result = chain.proceed();
                    android.view.View v = (android.view.View)chain.getThisObject();
                    if (armed || !HostTablePage.showing() || !v.isShown() || v.getWidth() == 0) return result;
                    android.view.View pageRoot = HostTablePage.rootView();
                    boolean belongs = false;
                    for (android.view.ViewParent parent = v.getParent(); parent instanceof android.view.View; parent=parent.getParent()) {
                        if (parent == pageRoot) { belongs=true; break; }
                    }
                    if (!belongs || !v.getGlobalVisibleRect(new android.graphics.Rect())) return result;
                    Object owner=page();
                    long drawn=SystemClock.uptimeMillis();
                    Object at=Ref.field(owner,"firstShowAt"), first=Ref.field(owner,"firstLoadMs");
                    String msg="course-frame page=" + System.identityHashCode(owner)
                            + " pageMs=" + (at instanceof Long ? drawn-(long)at : -1)
                            + " firstReadMs=" + first + " timeMs=" + System.currentTimeMillis();
                    armed=true;
                    DeviceVerification.UI.post(() -> {
                        committed=true;
                        new Thread(() -> record(context,msg),"verify-frame-result").start();
                    });
                    return result;
                });
    }
    private static boolean armed;
    private static volatile boolean committed;
    private static synchronized void record(Context context,String line) {
        try (java.io.FileWriter out=new java.io.FileWriter(new java.io.File(context.getFilesDir(),"coursealarm-cold-trace.txt"),true)) {
            out.write(android.os.Process.myPid()+" "+SystemClock.uptimeMillis()+" "+line+System.lineSeparator());
        } catch (java.io.IOException error) { android.util.Log.e("TableCold","file",error); }
        android.util.Log.i("TableCold",line);
    }
    static Object page() throws Exception {
        Field f=HostTablePage.class.getDeclaredField("page");f.setAccessible(true);return f.get(null);
    }
    static void call(Object target,String name) throws Exception {
        Method method=target.getClass().getDeclaredMethod(name);method.setAccessible(true);method.invoke(target);
    }
    static void run(String mode) throws Exception {
        if(mode.equals("entities")) {
            new Thread(()->{try{
                Context ctx=DeviceVerification.app;
                HostTableData data=HostTableData.load(ctx);
                java.util.Set<Long> expected = new java.util.HashSet<>();
                CalendarBackend backend = CalendarBackend.current(ctx);
                int scanned = 0;
                try (android.database.Cursor cursor = ctx.getContentResolver().query(backend.uri("events"),
                        new String[]{"_id","calendar_id","hasExtendedProperties","deleted","events_json_extensions"},null,null,null)) {
                    while (cursor.moveToNext()) {
                        scanned++;
                        if (cursor.getLong(1) == data.calendarId && cursor.getInt(2) == CourseWriter.COURSE_EXT_PROP
                                && cursor.getInt(3) == 0 && !android.text.TextUtils.isEmpty(cursor.getString(4)))
                            expected.add(cursor.getLong(0));
                    }
                }
                Class<?> gsonClass=Class.forName("com.google.gson.Gson",false,ctx.getClassLoader());
                Object gson=gsonClass.getConstructor().newInstance();Method json=gsonClass.getMethod("toJson",Object.class);
                int compared=0;
                for(Object event:data.events) {
                    long id=((Number)Ref.callName(event,"getId")).longValue();
                    if (!expected.remove(id)) throw new AssertionError("Duplicate or unexpected event: " + id);
                    Object old=HostEntities.event(ctx,id);
                    if(!json.invoke(gson,event).equals(json.invoke(gson,old)))throw new AssertionError("Full event mismatch: "+id);
                    compared++;
                }
                if (!expected.isEmpty()) throw new AssertionError("Filtered query omitted " + expected.size() + " events");
                TableSettings.State old=TableSettings.refresh(ctx,data.calendarId);
                TableSettings.State fresh=TableSettings.refreshLoaded(ctx,data.calendarId,data.calendar,
                        CalendarBackend.LOCAL.equals(data.backendAuthority));
                if(!old.identity.equals(fresh.identity)||old.enabled!=fresh.enabled)throw new AssertionError("Settings identity mismatch");
                boolean propagated=false;
                try { HostTableData.load(new ContextWrapper(ctx) {
                    @Override public ContentResolver getContentResolver(){throw new IllegalStateException("injected provider failure");}
                }); } catch(IllegalStateException injected) { propagated=true; }
                if(!propagated)throw new AssertionError("Provider failure became empty success");
                DeviceVerification.reply(new JSONObject().put("equivalentFullEvents",compared).put("unfilteredRows",scanned).put("settingsIdentity",true)
                        .put("providerFailurePropagated",true),null);
            }catch(Throwable error){DeviceVerification.reply(null,error);}},"verify-full-events").start();return;
        }
        Object p=page();
        if(p==null || Boolean.TRUE.equals(Ref.field(p,"loading")))throw new IllegalStateException("Page must be idle");
        JSONObject before=DeviceVerification.pageInfo();
        if(mode.equals("burst")) {
            long start=SystemClock.elapsedRealtime();
            for(int i=0;i<100;i++)DeviceVerification.UI.postDelayed(HostTablePage::refresh,i*10L);
            awaitIdle(p,start+1150,start+7000,()->{
                JSONObject after=DeviceVerification.pageInfo();
                if(before.getInt("pager")!=after.getInt("pager"))throw new AssertionError("Unchanged burst rebuilt pager");
                if(after.getInt("loadCount")-before.getInt("loadCount")>=20)throw new AssertionError("Refresh not coalesced");
                DeviceVerification.reply(new JSONObject().put("before",before).put("after",after).put("notifications",100),null);
            });return;
        }
        if(!mode.equals("failure")&&!mode.equals("timeout")&&!mode.equals("duringRead"))throw new IllegalArgumentException(mode);
        Field field=HostTablePage.class.getDeclaredField("LOADER");field.setAccessible(true);
        ExecutorService loader=(ExecutorService)field.get(null);
        CountDownLatch gate=new CountDownLatch(1);
        loader.execute(()->{try{gate.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
        call(p,"reload");
        Object task=Ref.field(p,"activeLoad");
        if(mode.equals("timeout"))((Runnable)Ref.field(task,"timeout")).run();
        else if(mode.equals("failure"))((CancellationSignal)Ref.field(task,"cancel")).cancel();
        else for(int i=0;i<100;i++)HostTablePage.refresh();
        gate.countDown();
        long start=SystemClock.elapsedRealtime();
        awaitIdle(p,start+350,start+7000,()->{
            JSONObject failed=DeviceVerification.pageInfo();
            if (mode.equals("timeout")) {
                if (failed.getBoolean("loadFailed") || failed.getInt("courses") != before.getInt("courses")
                        || failed.getInt("loadCount") != before.getInt("loadCount") + 1)
                    throw new AssertionError("Slow successful read was discarded or repeated");
                DeviceVerification.reply(new JSONObject().put("lateReadPublishedOnce",true),null);
                return;
            }
            if(mode.equals("duringRead")) {
                if(failed.getBoolean("loadFailed"))throw new AssertionError("Refresh during read failed");
                if(failed.getInt("loadCount")-before.getInt("loadCount")!=2)throw new AssertionError("Expected one follow-up read");
                if(failed.getInt("pager")!=before.getInt("pager"))throw new AssertionError("Unchanged read rebuilt pager");
                DeviceVerification.reply(new JSONObject().put("before",before).put("after",failed),null);return;
            }
            if(!failed.getBoolean("loadFailed"))throw new AssertionError("Failure did not reset loader");
            call(p,"reload");
            awaitIdle(p,SystemClock.elapsedRealtime()+100,start+7000,()->{
                JSONObject after=DeviceVerification.pageInfo();
                if(after.getBoolean("loadFailed")||after.getInt("courses")!=before.getInt("courses"))
                    throw new AssertionError("Retry failed");
                DeviceVerification.reply(new JSONObject().put("before",before).put("failed",failed).put("retried",after),null);
            });
        });
    }
    interface Done { void run() throws Exception; }
    static void awaitIdle(Object page,long earliest,long deadline,Done done) {
        DeviceVerification.UI.postDelayed(()->{try{
            long now=SystemClock.elapsedRealtime();
            if(now>deadline)throw new AssertionError("Loader did not settle");
            if(now<earliest||Boolean.TRUE.equals(Ref.field(page,"loading"))||Boolean.TRUE.equals(Ref.field(page,"dirty"))) {
                awaitIdle(page,earliest,deadline,done);return;
            }
            done.run();
        }catch(Throwable error){DeviceVerification.reply(null,error);}},50);
    }
}
