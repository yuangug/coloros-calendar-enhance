package com.lcodexs.coursealarm;

import android.content.ContentValues;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.util.DisplayMetrics;
import org.json.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Isolated app_process probe. Reads APK contracts and synthetic entities; never opens a provider. */
public final class HostCompatibilityProbe {
    private static final ClassLoader LOADER = HostCompatibilityProbe.class.getClassLoader();
    private static final JSONArray errors = new JSONArray();
    private static int checked;
    private interface Check { void run() throws Exception; }
    private static void check(String label, Check action) {
        try { action.run(); checked++; }
        catch (Throwable error) {
            Throwable cause=error;
            while(cause.getCause()!=null && cause.getCause()!=cause)cause=cause.getCause();
            errors.put(label+": "+cause);
        }
    }
    public static void main(String[] args) throws Exception {
        JSONObject spec = new JSONObject(new String(Files.readAllBytes(Paths.get(args[0])), StandardCharsets.UTF_8));
        JSONObject out = new JSONObject().put("hostApk",args[1]);
        check("navigation resolver",()->out.put("changeType",HostCompat.changeType(LOADER).toString()));
        check("native create resolver",()->out.put("createSheet",HostCompat.createSheet(LOADER).toString()));
        check("week adapter resolver",()->out.put("weekAdapter",HostCompat.weekAdapter(LOADER).toString()));
        check("pager resolver",()->out.put("pagerCurrentItem",HostCompat.pagerCurrentItem(load(HostCompat.PAGER)).toString()));
        check("Kotlin callback identity and return value",()->{
            int[] calls={0};
            Object callback=HostCompat.unitCallback(LOADER,value->{
                if(!Integer.valueOf(7).equals(value))throw new IllegalArgumentException("callback argument");
                calls[0]++;
            });
            require(callback.equals(callback) && !callback.equals(new Object()),"callback equals");
            require(callback.hashCode()==System.identityHashCode(callback),"callback hashCode");
            require(callback.toString()!=null && calls[0]==0,"Object methods triggered course action");
            Object returned=load("kotlin.jvm.functions.Function1").getMethod("invoke",Object.class).invoke(callback,7);
            require(returned==HostCompat.kotlinUnit(LOADER) && calls[0]==1,"callback result");
            require(returned.getClass()==load("kotlin.s"),"callback returned OS Unit instead of host Unit");
            out.put("kotlinUnit",returned.getClass().getName());
        });
        JSONArray contracts=spec.getJSONArray("contracts");
        for(int i=0;i<contracts.length();i++){
            JSONObject contract=contracts.getJSONObject(i);
            check(contract.getString("feature")+" / "+contract.getString("owner")+"#"+contract.optString("name"),()->verify(contract));
        }
        check("APK resources",()->{
            AssetManager assets=AssetManager.class.getConstructor().newInstance();
            try {
                Method add=AssetManager.class.getDeclaredMethod("addAssetPath",String.class);add.setAccessible(true);
                require(((Integer)add.invoke(assets,args[1]))!=0,"APK assets unavailable");
                DisplayMetrics metrics=new DisplayMetrics();metrics.setToDefaults();
                Resources resources=new Resources(assets,metrics,new Configuration());
                JSONArray needed=spec.getJSONArray("resources");
                for(int i=0;i<needed.length();i++){
                    JSONObject item=needed.getJSONObject(i);
                    require(resources.getIdentifier(item.getString("name"),item.getString("type"),"com.coloros.calendar")!=0,"missing resource "+item);
                }
                out.put("resources",needed.length());
            } finally {assets.close();}
        });
        check("event identity and recurrence serialization",HostCompatibilityProbe::eventRoundTrip);
        check("reminder and period serialization",HostCompatibilityProbe::tableRoundTrip);
        check("course entry and import-preview isolation",()->{
            android.content.Intent ordinary=new android.content.Intent().setClassName("com.coloros.calendar",TableEntryRoute.LEGACY);
            require(TableEntryRoute.isTable(ordinary) && !TableEntryRoute.isPreview(ordinary),"ordinary course entry");
            android.content.Intent shortcut=new android.content.Intent(ordinary).setAction("oplus.calendar.action.SHORTCUT_KEY_COURSE_TABLE");
            require(TableEntryRoute.isTable(shortcut) && !TableEntryRoute.isPreview(shortcut),"old pinned shortcut");
            require(!TableEntryRoute.isTable(new android.content.Intent().setClassName("com.coloros.calendar",TableEntryRoute.MAIN)),"route recursion");
            for(String key:new String[]{"courseTimeEntity","course_list"})
                require(TableEntryRoute.isPreview(new android.content.Intent(ordinary).putExtra(key,"{}")),"preview redirected: "+key);
            for(String key:new String[]{"request_import_course","is_import_course"})
                require(TableEntryRoute.isPreview(new android.content.Intent(ordinary).putExtra(key,true)),"import continuation redirected: "+key);
            require(!TableEntryRoute.isPreview(new android.content.Intent(ordinary).putExtra("calendar_id",73L).putExtra("launch_type","2")),"manage entry excluded");
        });
        check("duration formats",()->{
            for(int minutes:new int[]{45,90,100}){
                require(CourseDuration.parseSeconds("P"+(minutes*60)+"S")==minutes*60,"legacy duration");
                require(CourseDuration.parseSeconds("PT"+minutes+"M")==minutes*60,"ISO duration");
            }
            for(String invalid:new String[]{"P","PT","P6000SX","P-1S","PT2147483648H"})
                require(CourseDuration.parseSeconds(invalid)==0,"invalid duration accepted");
        });
        out.put("passed",errors.length()==0).put("checksPassed",checked).put("errors",errors);
        System.out.println("COURSEALARM_COMPAT_RESULT="+out);
        System.exit(errors.length()==0?0:1);
    }
    private static void verify(JSONObject contract) throws Exception {
        Class<?> owner=load(contract.getString("owner"));
        String kind=contract.getString("kind");
        if(kind.equals("class"))return;
        String name=contract.getString("name"),descriptor=contract.getString("descriptor");
        Member member;
        if(kind.equals("field")){
            Field field=owner.getDeclaredField(name);
            require(descriptor(field.getType()).equals(descriptor),"field type changed");member=field;
        }else{
            Class<?>[] parameters=parameters(descriptor);
            if(name.equals("<init>"))member=owner.getDeclaredConstructor(parameters);
            else{
                Method method=null;
                // DEX can contain bridge methods with identical names/parameters
                // and covariant return types. Check the complete descriptor.
                for(Method candidate:owner.getDeclaredMethods()){
                    if(candidate.getName().equals(name) && Arrays.equals(candidate.getParameterTypes(),parameters)
                            && descriptor(candidate.getReturnType()).equals(descriptor.substring(descriptor.indexOf(')')+1))){
                        method=candidate;break;
                    }
                }
                require(method!=null,"method descriptor missing");
                member=method;
            }
        }
        require(Modifier.isStatic(member.getModifiers())==contract.getBoolean("static"),"static modifier changed");
    }
    private static Class<?> load(String name)throws ClassNotFoundException{return Class.forName(name,false,LOADER);}
    private static String descriptor(Class<?> cls){
        if(cls.isArray())return cls.getName().replace('.','/');
        if(!cls.isPrimitive())return "L"+cls.getName().replace('.','/')+";";
        return cls==void.class?"V":cls==boolean.class?"Z":cls==byte.class?"B":cls==char.class?"C":cls==short.class?"S":cls==int.class?"I":cls==long.class?"J":cls==float.class?"F":"D";
    }
    private static Class<?>[] parameters(String descriptor)throws Exception{
        List<Class<?>> out=new ArrayList<>();
        for(int i=1;descriptor.charAt(i)!=')';){
            int start=i;while(descriptor.charAt(i)=='[')i++;
            char c=descriptor.charAt(i++);
            if(c=='L')i=descriptor.indexOf(';',i)+1;
            String value=descriptor.substring(start,i);
            if(value.startsWith("["))out.add(load(value.replace('/','.')));
            else if(c=='L')out.add(load(value.substring(1,value.length()-1).replace('/','.')));
            else out.add(switch(c){case 'Z'->boolean.class;case 'B'->byte.class;case 'C'->char.class;case 'S'->short.class;case 'I'->int.class;case 'J'->long.class;case 'F'->float.class;case 'D'->double.class;default->throw new IllegalArgumentException(value);});
        }
        return out.toArray(new Class<?>[0]);
    }
    private static void require(boolean ok,String reason){if(!ok)throw new IllegalStateException(reason);}
    private static Object getter(Object target,String name)throws Exception{return target.getClass().getMethod(name).invoke(target);}
    private static Object roundTrip(Object value)throws Exception{
        Class<?> gsonType=load("com.google.gson.Gson");Object gson=gsonType.getConstructor().newInstance();
        String json=(String)gsonType.getMethod("toJson",Object.class).invoke(gson,value);
        return gsonType.getMethod("fromJson",String.class,Class.class).invoke(gson,json,value.getClass());
    }
    private static Object parse(String type,String json)throws Exception{
        Class<?> gsonType=load("com.google.gson.Gson");Object gson=gsonType.getConstructor().newInstance();
        return gsonType.getMethod("fromJson",String.class,Class.class).invoke(gson,json,load(type));
    }
    private static void eventRoundTrip()throws Exception{
        Class<?> eventType=load("com.coloros.calendar.foundation.databasedaolib.entities.EventEntity");
        Object event=eventType.getConstructor().newInstance();
        eventType.getMethod("setId",long.class).invoke(event,4242L);
        eventType.getMethod("setCalendarId",Long.class).invoke(event,73L);
        eventType.getMethod("setTitle",String.class).invoke(event,"CourseAlarm compatibility fixture");
        eventType.getMethod("setRrule",String.class).invoke(event,"FREQ=WEEKLY;COUNT=4");
        eventType.getMethod("setEventsJsonExtensions",String.class).invoke(event,"{\"courseName\":\"compatibility fixture\",\"color\":3}");
        Object restored=roundTrip(event);
        require(((Number)getter(restored,"getId")).longValue()==4242L,"event identity lost");
        require(((Number)getter(restored,"getCalendarId")).longValue()==73L,"calendar identity lost");
        require("FREQ=WEEKLY;COUNT=4".equals(getter(restored,"getRrule")),"recurrence lost");
        require(getter(event,"getEventsJsonExtensions").equals(getter(restored,"getEventsJsonExtensions")),"course extension lost");
        Class<?> ormType=load("com.coloros.calendar.foundation.databasedaolib.orm.MicroOrm");
        Object orm=ormType.getConstructor().newInstance();
        ContentValues values=(ContentValues)ormType.getMethod("toContentValues",Object.class).invoke(orm,restored);
        require(values.getAsLong("calendar_id")==73L,"ORM calendar mapping");
        require("FREQ=WEEKLY;COUNT=4".equals(values.getAsString("rrule")),"ORM recurrence mapping");
    }
    private static void tableRoundTrip()throws Exception{
        String json="{\"hasReminder\":true,\"reminderTime\":30,\"termBeginTime\":1793548800000,\"weekNumberOfThisTerm\":20,\"courseTableTime\":{\"morningCourseNumber\":1,\"afternoonCourseNumber\":0,\"nightCourseNumber\":0,\"morningCoursesList\":[{\"startTime\":29400000,\"endTime\":32100000}],\"afternoontCoursesList\":[],\"nightCoursesList\":[]}}";
        for(int minutes:new int[]{45,90,100}){
            for(boolean enabled:new boolean[]{true,false}){
                JSONObject fixture=new JSONObject(json).put("hasReminder",enabled);
                fixture.getJSONObject("courseTableTime").getJSONArray("morningCoursesList").getJSONObject(0)
                        .put("endTime",29400000L+minutes*60000L);
                Object table=roundTrip(parse("com.android.calendar.card.schooltimetable.entity.CourseTableEntity",fixture.toString()));
                require(Boolean.valueOf(enabled).equals(getter(table,"getHasReminder")),"reminder switch lost");
                require(((Number)getter(table,"getReminderTime")).intValue()==30,"reminder time lost");
                Object times=getter(table,"getCourseTableTime");Object period=((List<?>)getter(times,"getMorningCoursesList")).get(0);
                require(((Number)getter(period,"getEndTime")).longValue()-((Number)getter(period,"getStartTime")).longValue()==minutes*60000L,"period duration lost");
            }
        }
    }
}
