package com.lcodexs.coursealarm;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/** Offscreen integration check with the installed host's real resources and week adapter.
 * No Activity launch, provider, installed test package or changes to Calendar preferences. */
public final class WeekFinishPageProbe {
    static final class FixtureActivity extends Activity {
        final SharedPreferences prefs;
        FixtureActivity(Context host, File file) throws Exception {
            attachBaseContext(new android.view.ContextThemeWrapper(host, host.getApplicationInfo().theme));
            Constructor<?> ctor = Class.forName("android.app.SharedPreferencesImpl").getDeclaredConstructor(File.class,int.class);
            ctor.setAccessible(true);
            prefs = (SharedPreferences) ctor.newInstance(file,Context.MODE_PRIVATE);
        }
        @Override public android.content.res.Resources.Theme getTheme() { return getBaseContext().getTheme(); }
        @Override public Context getApplicationContext() { return this; }
        @Override public SharedPreferences getSharedPreferences(String name, int mode) { return prefs; }
        @Override public Object getSystemService(String name) {
            return LAYOUT_INFLATER_SERVICE.equals(name) ? LayoutInflater.from(getBaseContext()).cloneInContext(this)
                    : getBaseContext().getSystemService(name);
        }
    }
    private static Object makePage(FixtureActivity activity, HostTableData d, List<Object> courses) throws Exception {
        Field owner = HostTablePage.class.getDeclaredField("page"); owner.setAccessible(true);
        Class<?> type = owner.getType();
        Constructor<?> ctor = type.getDeclaredConstructor(Activity.class); ctor.setAccessible(true);
        Object page = ctor.newInstance(activity); owner.set(null,page);
        call(page,"mount",new Class<?>[]{ViewGroup.class},new FrameLayout(activity));
        field(page,"visible").setBoolean(page,true); field(page,"dirty").setBoolean(page,false);
        field(page,"data").set(page,d); field(page,"courses").set(page,courses);
        field(page,"tableIdentity").set(page,"synthetic-page");
        call(page,"updateFinishWeek",new Class<?>[0]);
        call(page,"bind",new Class<?>[]{HostTableData.class,List.class},d,courses);
        return page;
    }

    public static void main(String[] args) throws Exception {
        try { run(args); }
        catch (Throwable failure) { failure.printStackTrace(System.out); System.exit(1); }
        System.exit(0);
    }

    private static void run(String[] args) throws Exception {
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
        if (android.os.Build.VERSION.SDK_INT >= 35) {
            Class<?> memory = Class.forName("com.android.internal.os.ApplicationSharedMemory");
            // app_process has no application bind transaction. Use a fresh, process-local test region.
            try { memory.getMethod("getInstance").invoke(null); }
            catch (java.lang.reflect.InvocationTargetException missing) {
                if (!(missing.getCause() instanceof IllegalStateException)) throw missing;
                memory.getMethod("setInstance",memory).invoke(null,memory.getMethod("create").invoke(null));
            }
        }
        Class<?> thread = Class.forName("android.app.ActivityThread");
        Object localThread = thread.getMethod("systemMain").invoke(null);
        try { android.graphics.Typeface.defaultFromStyle(android.graphics.Typeface.BOLD); }
        catch (NullPointerException noZygoteFonts) {
            android.graphics.Typeface.class.getMethod("loadPreinstalledSystemFontMap").invoke(null);
        }
        Context system = (Context)thread.getMethod("getSystemContext").invoke(localThread);
        Context host = system.createPackageContext("com.coloros.calendar",Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
        File prefs = new File(args[0],"week-page-fixture.xml");
        try {
            FixtureActivity activity = new FixtureActivity(host,prefs);
            require(activity.prefs.edit().putLong("sp_key_current_course_table",73L).commit(),"fixture table selection");
            ZoneId zone = ZoneId.systemDefault();
            LocalDate weekOne = WeekFinishSchedule.mondayOf(LocalDate.now(zone)).minusWeeks(1);
            HostTableData d = new HostTableData();
            d.calendarId = d.sourceTableId = 73; d.weekCount = 4;
            d.termBeginMs = weekOne.atStartOfDay(zone).toInstant().toEpochMilli();
            d.tableJson = new JSONObject().put("termBeginTime",d.termBeginMs).put("weekNumberOfThisTerm",4)
                    .put("courseTableTime",new JSONObject().put("morningCoursesList",new JSONArray()
                            .put(new JSONObject().put("startTime",29400000).put("endTime",32100000)))
                            .put("afternoontCoursesList",new JSONArray()).put("nightCoursesList",new JSONArray())).toString();
            d.weekSchedule = new WeekFinishSchedule(d.termBeginMs,4,zone);
            d.courseJsons.add("{\"courseName\":\"仅在下周的测试课\",\"week\":[false,false,true,false],\"dayOfWeek\":0,\"startNode\":0,\"endNode\":0}");
            java.util.BitSet flags = new java.util.BitSet(); flags.set(2);
            d.weekSchedule.addCourse(flags,0,java.time.LocalTime.of(8,10),zone,0,2700000,null,null);
            d.events.add(Class.forName("com.coloros.calendar.foundation.databasedaolib.entities.EventEntity",false,host.getClassLoader()).getConstructor().newInstance());
            List<Object> courses = d.buildCourseList(host.getClassLoader());
            Object page = makePage(activity,d,courses);
            require(HostTablePage.viewingWeekIndex0() == 1,"starts on real week 2");
            WeekFinishButton button = (WeekFinishButton)field(page,"weekButton").get(page);
            require(button != null && button.isClickable() && "结束本周课程".contentEquals(button.getContentDescription()),"real accessibility action");
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams)button.getLayoutParams();
            require((lp.gravity & Gravity.END) == Gravity.END && (lp.gravity & Gravity.TOP) == Gravity.TOP,"title end alignment");
            require(button.getParent() == field(page,"root").get(page) && button.getParent() != HostTablePage.rootView(),"button above native drawing overlay");
            require(button.performClick() && HostTablePage.viewingWeekIndex0() == 2 && field(page,"weekButton").get(page) == null,"click opens week 3 and removes action");
            HostTablePage.openWeekIndex(1);
            require(HostTablePage.viewingWeekIndex0() == 1 && field(page,"weekButton").get(page) == null,"manual browse still works");
            require(activity.prefs.edit().putInt("fence",1).commit(),"flush pending receipt");
            FixtureActivity reopened = new FixtureActivity(host,prefs);
            Object second = makePage(reopened,d,courses);
            require(HostTablePage.viewingWeekIndex0() == 2,"new Activity and fresh preferences default to week 3");
            TableSettings.writeFinishWeekSetting(reopened.prefs,"synthetic-page",false);
            HostTablePage.finishWeekSettingChanged("synthetic-page");
            require(HostTablePage.viewingWeekIndex0() == 1 && field(second,"weekButton").get(second) == null,"turning off returns to real week");
            System.out.println("WEEK_FINISH_PAGE_PASS: native pager 2 -> 3; manual 2; reopened 3; disabled 2");
        } finally {
            java.nio.file.Files.deleteIfExists(prefs.toPath());
            java.nio.file.Files.deleteIfExists(new File(prefs + ".bak").toPath());
        }
    }
    private static Field field(Object target,String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f;
    }
    private static void call(Object target,String name,Class<?>[] signature,Object... args) throws Exception {
        Method m = target.getClass().getDeclaredMethod(name,signature); m.setAccessible(true); m.invoke(target,args);
    }
    private static void require(boolean value,String text) { if (!value) throw new AssertionError(text); }
}
