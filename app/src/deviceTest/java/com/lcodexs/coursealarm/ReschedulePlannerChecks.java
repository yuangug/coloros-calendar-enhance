package com.lcodexs.coursealarm;

import android.content.ContentValues;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

/** Synthetic behavior checks only: no application, preferences or provider access. */
final class ReschedulePlannerChecks {
    interface Check { void run() throws Exception; }

    static Map<String, Check> cases() {
        Map<String, Check> result = new LinkedHashMap<>();
        result.put("current week splits only the displayed occurrence", ReschedulePlannerChecks::currentWeek);
        result.put("future includes displayed week and preserves sparse weeks", ReschedulePlannerChecks::futureWeeks);
        result.put("custom weeks move selected originals and add new weeks", ReschedulePlannerChecks::customWeeks);
        result.put("whole-course move keeps original identity branch", ReschedulePlannerChecks::wholeCourse);
        result.put("same-position custom additions preserve original weeks", ReschedulePlannerChecks::samePositionAdditions);
        result.put("unchanged position and date are no-ops", ReschedulePlannerChecks::noChanges);
        result.put("day movement changes selected occurrences only", ReschedulePlannerChecks::daySelection);
        result.put("same-weekday day movement retains one event", ReschedulePlannerChecks::sameWeekday);
        result.put("existing target occurrence is rejected instead of collapsed", ReschedulePlannerChecks::duplicateTarget);
        result.put("target conflicts use actual time and intersecting weeks", ReschedulePlannerChecks::actualTimeConflicts);
        result.put("adjacent clocks and different teaching weeks do not conflict", ReschedulePlannerChecks::nonConflicts);
        result.put("batch internal overlap rejects the whole plan", ReschedulePlannerChecks::batchConflict);
        result.put("45/90/100-minute legacy and ISO times survive day moves", ReschedulePlannerChecks::durationPreservation);
        result.put("invalid dates, periods and selections are rejected", ReschedulePlannerChecks::invalidRequests);
        result.put("source snapshots and extension data remain intact", ReschedulePlannerChecks::immutableInputs);
        result.put("strict metadata does not invent semester or period times", ReschedulePlannerChecks::strictMetadata);
        return result;
    }

    private static void currentWeek() throws Exception {
        Fixture f = fixture(3, entry(1, "A", 0, 0, 1, 1, 3, 5, 7));
        ReschedulePlanner.Change c = only(plan(f, RescheduleRequest.Scope.CURRENT_WEEK, 2, 2, 3, null));
        weeks(c.remainingJson, 1, 5, 7); weeks(c.movedJson, 3);
        position(c.remainingJson, 0, 0, 1); position(c.movedJson, 2, 2, 3);
        require(c.source.eventId == 1 && !c.preserveTimes, "wrong source identity/timing policy");
    }
    private static void futureWeeks() throws Exception {
        Fixture f = fixture(3, entry(1, "Odd", 0, 0, 0, 1, 3, 5, 7));
        ReschedulePlanner.Change c = only(plan(f, RescheduleRequest.Scope.FUTURE_WEEKS, 1, 2, 2, null));
        weeks(c.remainingJson, 1); weeks(c.movedJson, 3, 5, 7);
        f = fixture(4, entry(2, "Even", 0, 0, 0, 2, 4, 8));
        c = only(plan(f, RescheduleRequest.Scope.FUTURE_WEEKS, 1, 2, 2, null));
        weeks(c.remainingJson, 2); weeks(c.movedJson, 4, 8);
    }
    private static void customWeeks() throws Exception {
        Fixture f = fixture(3, entry(1, "A", 0, 0, 1, 1, 3, 5, 7));
        ReschedulePlanner.Change c = only(plan(f, RescheduleRequest.Scope.CUSTOM_WEEKS, 4, 2, 3, bits(3,4,8)));
        weeks(c.remainingJson, 1,5,7); weeks(c.movedJson, 3,4,8);
        position(c.movedJson,4,2,3);
    }
    private static void wholeCourse() throws Exception {
        Fixture f = fixture(1, entry(912, "All", 0, 0, 1, 1,3,5));
        ReschedulePlanner.Change c = only(plan(f, RescheduleRequest.Scope.FUTURE_WEEKS, 2, 1, 2, null));
        require(c.remainingJson == null && c.source.eventId == 912, "full move would allocate a replacement identity");
        weeks(c.movedJson,1,3,5);
        f = fixture(8, entry(19,"Last",0,0,0,8));
        c = only(plan(f, RescheduleRequest.Scope.CURRENT_WEEK,1,2,2,null));
        require(c.remainingJson == null && c.source.eventId == 19, "last occurrence should update its existing event");
    }
    private static void samePositionAdditions() throws Exception {
        Fixture f = fixture(3, entry(1,"A",0,0,1,1,3,5));
        ReschedulePlanner.Change c = only(plan(f,RescheduleRequest.Scope.CUSTOM_WEEKS,0,0,1,bits(3,6)));
        require(c.remainingJson == null && c.preserveTimes,"same-position additions should keep one event");
        weeks(c.movedJson,1,3,5,6);
    }
    private static void noChanges() throws Exception {
        Fixture f = fixture(3,entry(1,"A",0,0,1,1,3,5));
        for(RescheduleRequest.Scope scope:new RescheduleRequest.Scope[]{RescheduleRequest.Scope.CURRENT_WEEK,RescheduleRequest.Scope.FUTURE_WEEKS,RescheduleRequest.Scope.CUSTOM_WEEKS})
            require(plan(f,scope,0,0,1,bits(3)).isEmpty(),"same position generated a change: "+scope);
        require(ReschedulePlanner.plan(RescheduleRequest.day(f.session,3,0,3,0,List.of(1L)),f.entries).isEmpty(),"same date generated writes");
    }
    private static void daySelection() throws Exception {
        DragSession.Entry first=entry(1,"A",0,0,1,1,3,5), second=entry(2,"B",0,2,2,1,3,5);
        Fixture f=fixture(3,first,second);
        List<ReschedulePlanner.Change> changes=ReschedulePlanner.plan(RescheduleRequest.day(f.session,3,0,4,2,List.of(1L)),f.entries);
        ReschedulePlanner.Change c=only(changes);
        require(c.preserveTimes,"day movement changed time policy");
        weeks(c.remainingJson,1,5);weeks(c.movedJson,4);position(c.movedJson,2,0,1);
        require(second.course.day==0&&second.course.weeks.equals(bits(1,3,5)),"unselected source course changed");
        changes=ReschedulePlanner.plan(RescheduleRequest.day(f.session,3,0,4,2,List.of(1L,2L)),f.entries);
        require(changes.size()==2,"batch omitted a selected course");
        position(changes.get(1).movedJson,2,2,2);
    }
    private static void sameWeekday() throws Exception {
        Fixture f=fixture(3,entry(1,"A",0,0,1,1,3,5));
        ReschedulePlanner.Change c=only(ReschedulePlanner.plan(RescheduleRequest.day(f.session,3,0,4,0,List.of(1L)),f.entries));
        require(c.remainingJson==null&&c.source.eventId==1,"same weekday should retain one event");
        weeks(c.movedJson,1,4,5);
    }
    private static void duplicateTarget() throws Exception {
        Fixture f=fixture(1,entry(1,"A",0,0,0,1,3));
        rejects(()->ReschedulePlanner.plan(RescheduleRequest.day(f.session,1,0,3,0,List.of(1L)),f.entries),"冲突");
        require(f.entries.get(0).course.weeks.equals(bits(1,3)),"duplicate target removed source occurrence");
    }
    private static void actualTimeConflicts() throws Exception {
        DragSession.Entry original=entry(1,"Moved",0,0,0,3);
        DragSession.Entry other=withClock(entry(2,"Existing",1,3,3,4),8,30,"PT100M");
        Fixture f=fixture(3,original,other);
        rejects(()->ReschedulePlanner.plan(RescheduleRequest.day(f.session,3,0,4,1,List.of(1L)),f.entries),"Existing");
        other=entry(3,"Same name",1,0,1,3);
        Fixture collision=fixture(3,entry(1,"Same name",0,0,1,3),other);
        rejects(()->plan(collision,RescheduleRequest.Scope.CURRENT_WEEK,1,0,1,null),"冲突");
    }
    private static void nonConflicts() throws Exception {
        DragSession.Entry original=entry(1,"A",0,0,0,3);
        DragSession.Entry adjacent=withClock(entry(2,"Adjacent",1,1,1,4),8,45,"P2700S");
        Fixture f=fixture(3,original,adjacent);
        require(ReschedulePlanner.plan(RescheduleRequest.day(f.session,3,0,4,1,List.of(1L)),f.entries).size()==1,"touching endpoints conflict");
        Fixture distinctWeek=fixture(3,original,entry(3,"Other week",1,0,0,2,4,6));
        require(plan(distinctWeek,RescheduleRequest.Scope.CURRENT_WEEK,1,0,0,null).size()==1,"different weeks conflict");
    }
    private static void batchConflict() throws Exception {
        DragSession.Entry first=withClock(entry(1,"A",0,0,0,3),8,0,"PT90M");
        DragSession.Entry second=withClock(entry(2,"B",0,3,3,3),8,40,"P2700S");
        Fixture f=fixture(3,first,second);
        rejects(()->ReschedulePlanner.plan(RescheduleRequest.day(f.session,3,0,4,1,List.of(1L,2L)),f.entries),"冲突");
        require(first.course.weeks.equals(bits(3))&&second.course.weeks.equals(bits(3)),"batch conflict partially changed source");
    }
    private static void durationPreservation() throws Exception {
        for(int minutes:new int[]{45,90,100}) for(String duration:new String[]{"P"+(minutes*60)+"S","PT"+minutes+"M"}) {
            DragSession.Entry entry=withClock(entry(1,"Duration",0,0,0,3),9,17,duration);
            Fixture f=fixture(3,entry);
            int[] time=ReschedulePlanner.times(f.table,entry);
            require(time[0]==9*3600+17*60&&time[1]-time[0]==minutes*60,"duration altered: "+duration);
            ReschedulePlanner.Change c=only(ReschedulePlanner.plan(RescheduleRequest.day(f.session,3,0,4,2,List.of(1L)),f.entries));
            require(c.preserveTimes,"day mode recalculates custom clock");
        }
        DragSession.Entry entry=withClock(entry(1,"End",0,0,0,3),9,17,"");
        ContentValues values=new ContentValues(entry.eventValues);
        values.put("dtend",values.getAsLong("dtstart")+100*60_000L);
        entry=new DragSession.Entry(entry.eventId,entry.json,values,"end");
        Fixture f=fixture(3,entry);
        int[] time=ReschedulePlanner.times(f.table,entry);
        require(time[1]-time[0]==6000,"explicit dtend not retained");
        DragSession.Entry invalid=withClock(entry(9,"Invalid duration",0,0,0,3),9,17,"P6000SX");
        rejects(()->ReschedulePlanner.times(f.table,invalid),"时长无效");
    }
    private static void invalidRequests() throws Exception {
        Fixture f=fixture(3,entry(1,"A",0,0,0,3));
        rejects(()->plan(f,RescheduleRequest.Scope.CURRENT_WEEK,7,0,0,null),"星期");
        rejects(()->plan(f,RescheduleRequest.Scope.CURRENT_WEEK,1,-1,0,null),"节次");
        rejects(()->plan(f,RescheduleRequest.Scope.CURRENT_WEEK,1,2,1,null),"节次");
        rejects(()->plan(f,RescheduleRequest.Scope.CURRENT_WEEK,1,0,f.table.nodeCount(),null),"节次");
        rejects(()->plan(f,RescheduleRequest.Scope.CUSTOM_WEEKS,1,0,0,new BitSet()),"选择周");
        rejects(()->plan(f,RescheduleRequest.Scope.CUSTOM_WEEKS,1,0,0,bits(0,3)),"周次");
        rejects(()->plan(f,RescheduleRequest.Scope.CUSTOM_WEEKS,1,0,0,bits(9)),"周次");
        rejects(()->ReschedulePlanner.plan(RescheduleRequest.day(f.session,3,0,9,1,List.of(1L)),f.entries),"学期");
        rejects(()->ReschedulePlanner.plan(RescheduleRequest.day(f.session,3,0,4,1,List.of()),f.entries),"请选择课程");
        rejects(()->ReschedulePlanner.plan(RescheduleRequest.day(f.session,3,0,4,1,List.of(999L)),f.entries),"来源日");
        Fixture notThisWeek=fixture(2,entry(1,"A",0,0,0,1,3));
        rejects(()->plan(notThisWeek,RescheduleRequest.Scope.CURRENT_WEEK,1,0,0,null),"非本周");
    }
    private static void immutableInputs() throws Exception {
        DragSession.Entry source=entry(1,"A",0,0,1,1,3,5);
        Fixture f=fixture(3,source);String before=source.json;ContentValues event=new ContentValues(source.eventValues);
        BitSet selected=bits(3,4);
        RescheduleRequest request=RescheduleRequest.range(f.session,RescheduleRequest.Scope.CUSTOM_WEEKS,1,2,3,selected);
        selected.clear();
        ReschedulePlanner.Change c=only(ReschedulePlanner.plan(request,f.entries));
        weeks(c.movedJson,3,4);
        require(source.json.equals(before)&&source.course.weeks.equals(bits(1,3,5))&&source.eventValues.equals(event),"source snapshot mutated");
        JSONObject moved=new JSONObject(c.movedJson);
        require("Keep note".equals(moved.getString("note"))&&moved.getInt("color")==3,"course content lost");
        require("Keep extension".equals(moved.getJSONObject("vendorExtension").getString("payload")),"unknown extension lost");
        require(c.source.eventId==source.eventId,"origin identity lost");
    }
    private static void strictMetadata() throws Exception {
        JSONObject missing=new JSONObject(metadata());missing.remove("courseTableTime");
        rejectsAny(()->TableCtx.fromMetadata(73,"Test",missing.toString()));
        JSONObject noTerm=new JSONObject(metadata());noTerm.put("termBeginTime",0);
        rejects(()->TableCtx.fromMetadata(73,"Test",noTerm.toString()),"学期");
        JSONObject badTime=new JSONObject(metadata());
        badTime.getJSONObject("courseTableTime").getJSONArray("morningCoursesList").getJSONObject(0).put("endTime",470*60_000L);
        rejects(()->TableCtx.fromMetadata(73,"Test",badTime.toString()),"节次");
        TableCtx table=table();
        require(table.durationSeconds(0,0)==2700&&table.durationSeconds(2,2)==5400&&table.durationSeconds(3,3)==6000,"native period clocks replaced by a fixed length");
    }

    private static List<ReschedulePlanner.Change> plan(Fixture f,RescheduleRequest.Scope scope,int day,int start,int end,BitSet weeks)throws Exception {
        return ReschedulePlanner.plan(RescheduleRequest.range(f.session,scope,day,start,end,weeks),f.entries);
    }
    private static ReschedulePlanner.Change only(List<ReschedulePlanner.Change> changes) {
        require(changes.size()==1,"expected one event change, got "+changes.size());return changes.get(0);
    }
    private static void weeks(String json,int... expected)throws Exception {
        require(json!=null,"missing course fragment");
        require(CourseClipboard.read(json).weeks.equals(bits(expected)),"unexpected week set: "+CourseClipboard.read(json).weeks);
    }
    private static void position(String json,int day,int start,int end)throws Exception {
        Course c=CourseClipboard.read(json);require(c.day==day&&c.startNode==start&&c.endNode==end,"wrong course placement");
    }
    private static BitSet bits(int... weeks){BitSet result=new BitSet();for(int week:weeks)result.set(week);return result;}
    private static void require(boolean value,String message){if(!value)throw new IllegalStateException(message);}
    private static void rejects(Check action,String text)throws Exception {
        try {action.run();} catch(IllegalArgumentException expected) {require(expected.getMessage()!=null&&expected.getMessage().contains(text),"wrong validation: "+expected);return;}
        throw new IllegalStateException("invalid/conflicting operation accepted: "+text);
    }
    private static void rejectsAny(Check action)throws Exception {
        try {action.run();}catch(Exception expected){return;}throw new IllegalStateException("invalid metadata accepted");
    }
    private static final class Fixture {
        final TableCtx table;final List<DragSession.Entry> entries;final DragSession session;
        Fixture(TableCtx table,int week,List<DragSession.Entry> entries){this.table=table;this.entries=entries;this.session=new DragSession(table,week,entries.get(0),entries,"fixture");}
    }
    private static Fixture fixture(int week,DragSession.Entry... entries)throws Exception{return new Fixture(table(),week,List.of(entries));}
    private static DragSession.Entry entry(long id,String name,int day,int start,int end,int... weeks)throws Exception {
        JSONObject json=new JSONObject().put("courseName",name).put("dayOfWeek",day).put("startNode",start).put("endNode",end)
                .put("color",3).put("room","Room").put("teacher","Teacher").put("note","Keep note").put("weekType",2)
                .put("vendorExtension",new JSONObject().put("payload","Keep extension"));
        JSONArray mask=new JSONArray();BitSet active=bits(weeks);for(int i=1;i<=8;i++)mask.put(active.get(i));json.put("week",mask);
        TableCtx table=table();ContentValues values=new ContentValues();values.put("calendar_id",73L);
        Calendar time=Calendar.getInstance();time.setTimeInMillis(table.termBeginMs);time.add(Calendar.DATE,(weeks[0]-1)*7+day);
        time.set(Calendar.HOUR_OF_DAY,table.periodStart[start]/60);time.set(Calendar.MINUTE,table.periodStart[start]%60);
        values.put("dtstart",time.getTimeInMillis());values.put("duration","P"+table.durationSeconds(start,end)+"S");
        return new DragSession.Entry(id,json.toString(),values,"fixture-"+id);
    }
    private static DragSession.Entry withClock(DragSession.Entry entry,int hour,int minute,String duration)throws Exception {
        ContentValues values=new ContentValues(entry.eventValues);Calendar time=Calendar.getInstance();time.setTimeInMillis(values.getAsLong("dtstart"));
        time.set(Calendar.HOUR_OF_DAY,hour);time.set(Calendar.MINUTE,minute);values.put("dtstart",time.getTimeInMillis());values.put("duration",duration);
        return new DragSession.Entry(entry.eventId,entry.json,values,entry.fingerprint);
    }
    private static TableCtx table()throws Exception{return TableCtx.fromMetadata(73,"Fixture",metadata());}
    private static String metadata()throws Exception {
        int[] starts={480,540,600,700,840,900,1140,1200},ends={525,585,690,800,885,945,1185,1245};
        JSONArray morning=new JSONArray(),afternoon=new JSONArray(),night=new JSONArray();
        for(int i=0;i<starts.length;i++)(i<4?morning:i<6?afternoon:night).put(new JSONObject().put("startTime",starts[i]*60_000L).put("endTime",ends[i]*60_000L));
        Calendar semester=Calendar.getInstance();semester.clear();semester.set(2026,Calendar.SEPTEMBER,7);
        return new JSONObject().put("termBeginTime",semester.getTimeInMillis()).put("weekNumberOfThisTerm",8)
                .put("hasReminder",true).put("reminderTime",30).put("courseTableTime",new JSONObject()
                        .put("morningCoursesList",morning).put("afternoontCoursesList",afternoon).put("nightCoursesList",night)).toString();
    }
}
