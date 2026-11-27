package com.lcodexs.coursealarm;
import org.json.*;
import java.time.*;
import java.util.*;

public final class XitCheck {
    static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    static final String ORIGINAL="{\"termBeginTime\":1,\"weekNumberOfThisTerm\":18,\"reminderTime\":30,"+
        "\"courseTableTime\":{\"morningCoursesList\":[],\"afternoontCoursesList\":[],\"nightCoursesList\":[],\"custom\":13}}";
    static void check(boolean b){if(!b)throw new AssertionError();}
    public static void main(String[] args)throws Exception{
        List<Course> courses=new ArrayList<>(); Course c=new Course();c.endNode=1;c.weeks.set(21);courses.add(c);
        JSONObject payload=new JSONObject().put("term","2026-2027-1")
                .put("slots",new JSONArray("[['第一二节','08:10','09:50'],['第一节','08:10','08:55'],['第二节','09:05','09:50']]"))
                .put("anchors",new JSONArray("[[1,5,0,9,5],[2,0,2026,9,7],[18,1,0,12,29]]"));
        XitTiming draft=new XitTiming(payload,new int[]{490,555},new int[]{530,590},courses);
        check(draft.schoolStart.equals(LocalDate.of(2026,8,31)) && draft.fields(new HashMap<>()).isEmpty());
        JSONObject config=new JSONObject(draft.configure(ORIGINAL,new HashMap<>(),courses,ZONE));
        check(config.getLong("termBeginTime")==draft.schoolStart.atStartOfDay(ZONE).toInstant().toEpochMilli());
        check(config.getInt("reminderTime")==30 && config.getInt("weekNumberOfThisTerm")==21);
        JSONObject clock=config.getJSONObject("courseTableTime");
        check(clock.getInt("custom")==13 && clock.getInt("morningCourseNumber")==2);
        check(clock.getJSONArray("morningCoursesList").getJSONObject(1).getLong("startTime")==545*60000L);
        payload.put("slots",new JSONArray("[['第一二节','08:10','09:50']]")).put("anchors",new JSONArray());
        draft=new XitTiming(payload,new int[]{490,545},new int[]{535,590},courses);
        Map<String,String> answers=new HashMap<>();
        check(draft.fields(answers).size()==3 && "08:55".equals(answers.get("e0")) && !answers.containsKey("date"));
        answers.put("date","2026-08-31");answers.put("s1","08:40");
        try{draft.configure(ORIGINAL,answers,courses,ZONE);throw new AssertionError("overlap accepted");}
        catch(IllegalArgumentException expected){}
        payload.put("anchors",new JSONArray("[[1,0,2026,8,31],[1,1,2026,9,8]]"));
        check(new XitTiming(payload,new int[0],new int[0],courses).schoolStart==null);
        payload.put("term","2026-2027-2").put("anchors",new JSONArray("[[2,1,0,3,9]]"));
        check(new XitTiming(payload,new int[0],new int[0],courses).schoolStart.equals(LocalDate.of(2027,3,1)));
        payload.put("begin","2027-03-03");
        check(new XitTiming(payload,new int[0],new int[0],courses).schoolStart.equals(LocalDate.of(2027,3,3)));
        payload.put("begin","2027-02-22");
        check(new XitTiming(payload,new int[0],new int[0],courses).schoolStart==null);
        check(Arrays.equals(JwParser.nodesFromHeader("第九十11节"),new int[]{8,10}));
        if(args.length>0){
            JSONObject captured=new JSONObject(java.nio.file.Files.readString(java.nio.file.Path.of(args[0])));
            var p=JwParser.parse(captured.getString("table"));
            check(p.courses.size()==22);
            draft=new XitTiming(captured,new int[0],new int[0],p.courses);
            check(draft.schoolStart.equals(LocalDate.of(2026,8,31)) && draft.starts.length==11);
            check(draft.starts[0]==490 && draft.ends[1]==590 && draft.ends[10]==1295 && draft.starts[1]<0);
            System.out.println("Captured timetable: 22 courses, 11 periods, 5 published block times; simulated calendar: 2026-08-31");
        }
        System.out.println("PASS: XIT semester, host configuration, real and missing bell boundaries");
    }
}
