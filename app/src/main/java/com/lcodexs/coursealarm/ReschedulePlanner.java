package com.lcodexs.coursealarm;

import org.json.*;
import java.util.*;

/** Pure schedule transformation. All conflict checks run before the provider receives a write. */
final class ReschedulePlanner {
    static final class Change {
        final DragSession.Entry source;
        final String remainingJson, movedJson;
        final boolean preserveTimes;
        Change(DragSession.Entry source, String remaining, String moved, boolean preserveTimes) {
            this.source = source; this.remainingJson = remaining; this.movedJson = moved;
            this.preserveTimes = preserveTimes;
        }
    }
    private static final class Placement {
        final String name; final int day, startSeconds, endSeconds; final BitSet weeks;
        Placement(String name, int day, int start, int end, BitSet weeks) {
            this.name = name; this.day = day; this.startSeconds = start; this.endSeconds = end; this.weeks = weeks;
        }
    }
    static List<Change> plan(RescheduleRequest request, List<DragSession.Entry> current) throws Exception {
        DragSession session = request.session; TableCtx table = session.table;
        if (request.targetDay < 0 || request.targetDay > 6) throw new IllegalArgumentException("目标星期无效");
        ArrayList<Change> changes = new ArrayList<>();
        if (request.scope == RescheduleRequest.Scope.DAY) {
            validWeek(table, request.sourceWeek); validWeek(table, request.targetWeek);
            table.requireTeachingDate(request.targetWeek, request.targetDay);
            if (request.sourceDay < 0 || request.sourceDay > 6) throw new IllegalArgumentException("来源星期无效");
            if (request.eventIds.isEmpty()) throw new IllegalArgumentException("请选择课程");
            if (request.sourceWeek == request.targetWeek && request.sourceDay == request.targetDay) return changes;
            for (long id : request.eventIds) {
                DragSession.Entry entry = find(current, id);
                if (entry == null || entry.course.day != request.sourceDay || !entry.course.weeks.get(request.sourceWeek))
                    throw new IllegalArgumentException("来源日课程已变化，请重新调课");
                Course course = entry.course;
                BitSet remaining = (BitSet) course.weeks.clone(); remaining.clear(request.sourceWeek);
                BitSet moved = new BitSet(); moved.set(request.targetWeek);
                // WakeUp collapses these occurrences; we must reject them instead of silently dropping a lesson.
                if (course.day == request.targetDay && remaining.get(request.targetWeek))
                    throw new IllegalArgumentException("课程冲突：" + label(table, course.name, request.targetWeek, request.targetDay,
                            times(table, entry)[0], times(table, entry)[1]));
                if (course.day == request.targetDay) {
                    remaining.or(moved);
                    changes.add(new Change(entry, null, json(entry.json, course.day, course.startNode, course.endNode, remaining, table.weekCount), true));
                } else changes.add(new Change(entry, remaining.isEmpty() ? null : json(entry.json, course.day, course.startNode, course.endNode, remaining, table.weekCount),
                        json(entry.json, request.targetDay, course.startNode, course.endNode, moved, table.weekCount), true));
            }
        } else {
            DragSession.Entry entry = find(current, session.eventId);
            if (entry == null) throw new IllegalArgumentException("课程已删除");
            Course course = entry.course;
            validWeek(table, session.displayedWeek);
            if (!course.weeks.get(session.displayedWeek)) throw new IllegalArgumentException("非本周课程禁止拖动");
            if (request.targetStart < 0 || request.targetEnd < request.targetStart || request.targetEnd >= table.nodeCount())
                throw new IllegalArgumentException("目标节次超出课表范围");
            BitSet moved = new BitSet();
            switch (request.scope) {
                case CURRENT_WEEK: moved.set(session.displayedWeek); break;
                case FUTURE_WEEKS: moved.or(course.weeks); moved.clear(0, session.displayedWeek); break;
                case CUSTOM_WEEKS: moved.or(request.targetWeeks); break;
                default: throw new IllegalArgumentException("调课范围无效");
            }
            if (moved.isEmpty()) throw new IllegalArgumentException("请先选择周");
            if (moved.get(0) || moved.length() > table.weekCount + 1) throw new IllegalArgumentException("周次超出学期范围");
            for (int week = moved.nextSetBit(1); week >= 0; week = moved.nextSetBit(week + 1))
                table.requireTeachingDate(week, request.targetDay);
            BitSet remaining = (BitSet) course.weeks.clone(); remaining.andNot(moved);
            boolean same = course.day == request.targetDay && course.startNode == request.targetStart && course.endNode == request.targetEnd;
            if (same) {
                BitSet merged = (BitSet) course.weeks.clone(); merged.or(moved);
                if (merged.equals(course.weeks)) return changes;
                changes.add(new Change(entry, null, json(entry.json, course.day, course.startNode, course.endNode, merged, table.weekCount), true));
            } else changes.add(new Change(entry, remaining.isEmpty() ? null : json(entry.json, course.day, course.startNode, course.endNode, remaining, table.weekCount),
                    json(entry.json, request.targetDay, request.targetStart, request.targetEnd, moved, table.weekCount), false));
        }
        checkConflicts(table, current, changes);
        return changes;
    }
    private static void validWeek(TableCtx table, int week) {
        if (week < 1 || week > table.weekCount) throw new IllegalArgumentException("日期超出学期范围");
    }
    private static DragSession.Entry find(List<DragSession.Entry> entries, long id) {
        for (DragSession.Entry entry : entries) if (entry.eventId == id) return entry;
        return null;
    }
    static String json(String original, int day, int start, int end, BitSet weeks, int count) throws Exception {
        JSONObject value = new JSONObject(original);
        value.remove("event"); value.put("dayOfWeek", day).put("startNode", start).put("endNode", end);
        value.put("currentWeek", weeks.nextSetBit(1)-1);
        JSONArray array = new JSONArray(); for (int i=1; i<=count; i++) array.put(weeks.get(i));
        value.put("week", array);
        // The explicit week list is authoritative, including new weeks added in the custom page.
        value.put("weekType", 0);
        return value.toString();
    }
    private static void checkConflicts(TableCtx table, List<DragSession.Entry> current, List<Change> changes) throws Exception {
        ArrayList<Placement> unchanged = new ArrayList<>(), moved = new ArrayList<>();
        for (DragSession.Entry entry : current) {
            Change change = null; for (Change c : changes) if (c.source.eventId == entry.eventId) { change=c; break; }
            if (change == null) unchanged.add(placement(table, entry, entry.json, true));
            else if (change.remainingJson != null) unchanged.add(placement(table, entry, change.remainingJson, true));
        }
        for (Change change : changes) moved.add(placement(table, change.source, change.movedJson, change.preserveTimes));
        LinkedHashSet<String> conflicts = new LinkedHashSet<>();
        for (int i=0;i<moved.size();i++) {
            Placement a = moved.get(i);
            for (Placement b : unchanged) conflict(table,a,b,conflicts);
            for (int j=0;j<i;j++) conflict(table,a,moved.get(j),conflicts);
        }
        if (!conflicts.isEmpty()) throw new IllegalArgumentException("课程冲突，未保存：\n" + String.join("\n", conflicts));
    }
    private static Placement placement(TableCtx table, DragSession.Entry entry, String json, boolean preserve) throws Exception {
        Course course = CourseClipboard.read(json);
        if (course.startNode < 0 || course.endNode < course.startNode || course.endNode >= table.nodeCount())
            throw new IllegalArgumentException("课程节次无效：" + course.name);
        int[] time = preserve ? times(table, entry) : new int[]{table.periodStart[course.startNode]*60, table.periodEnd[course.endNode]*60};
        return new Placement(course.name,course.day,time[0],time[1],course.weeks);
    }
    static int[] times(TableCtx table, DragSession.Entry entry) {
        Course course=entry.course;
        Long raw=entry.eventValues.getAsLong("dtstart");
        int start=table.periodStart[course.startNode]*60, seconds=0;
        if(raw!=null && raw>0) { Calendar c=Calendar.getInstance();c.setTimeInMillis(raw);start=c.get(Calendar.HOUR_OF_DAY)*3600+c.get(Calendar.MINUTE)*60+c.get(Calendar.SECOND); }
        String duration=entry.eventValues.getAsString("duration");
        if(duration!=null&&!duration.isEmpty()) seconds=CourseDuration.parseSeconds(duration);
        Long end=entry.eventValues.getAsLong("dtend");
        if(seconds<=0&&raw!=null&&end!=null&&end>raw) {
            long durationMillis=end-raw;
            if(durationMillis<=0||durationMillis>86400000L||durationMillis%1000!=0)
                throw new IllegalArgumentException("课程时长无效："+course.name);
            seconds=(int)(durationMillis/1000);
        }
        if(seconds<=0&&duration!=null&&!duration.isEmpty())
            throw new IllegalArgumentException("课程时长无效："+course.name);
        if(seconds<=0) seconds=(table.periodEnd[course.endNode]-table.periodStart[course.startNode])*60;
        if(start<0||start>=86400||seconds<=0||seconds>86400-start) throw new IllegalArgumentException("课程时间无效："+course.name);
        return new int[]{start,start+seconds};
    }
    private static void conflict(TableCtx table, Placement a, Placement b, Set<String> out) {
        if(a.day!=b.day||a.startSeconds>=b.endSeconds||b.startSeconds>=a.endSeconds)return;
        BitSet overlap=(BitSet)a.weeks.clone();overlap.and(b.weeks);
        for(int w=overlap.nextSetBit(1);w>0;w=overlap.nextSetBit(w+1)) {
            out.add(label(table,a.name,w,a.day,a.startSeconds,a.endSeconds)+" 与 "+b.name+" "+clock(b.startSeconds)+"–"+clock(b.endSeconds));
            if(out.size()>=12){out.add("请调整以上冲突后重试");return;}
        }
    }
    private static String label(TableCtx table,String name,int week,int day,int start,int end){
        java.time.LocalDate date=table.dateOf(week,day);
        return String.format(Locale.CHINA,"%s 第%d周 %d月%d日 周%s %s–%s",name,week,date.getMonthValue(),date.getDayOfMonth(),"一二三四五六日".charAt(day),clock(start),clock(end));
    }
    private static String clock(int seconds){return String.format(Locale.ROOT,"%02d:%02d",seconds/3600,seconds/60%60);}
}
