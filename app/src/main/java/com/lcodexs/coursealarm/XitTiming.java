package com.lcodexs.coursealarm;

import org.json.*;
import java.time.*;
import java.util.*;

/** School facts and editable fallbacks are kept separate until the user accepts the preview. */
final class XitTiming {
    final String term;
    final LocalDate schoolStart;
    final int weeks;
    final int[] starts, ends, baseStarts, baseEnds;
    XitTiming(JSONObject data, int[] previousStarts, int[] previousEnds, List<Course> courses) throws Exception {
        term = data.getString("term");
        if (!term.matches("20\\d\\d-20\\d\\d-[12]") ||
                Integer.parseInt(term.substring(5,9)) != Integer.parseInt(term.substring(0,4))+1)
            throw new IllegalArgumentException("无法识别所选学期");
        LocalDate first = null;
        int count = 0;
        boolean conflict = false;
        JSONArray anchors = data.getJSONArray("anchors");
        for (int i=0;i<anchors.length();i++) {
            JSONArray a=anchors.getJSONArray(i);
            int w=a.getInt(0), d=a.getInt(1), y=a.getInt(2), m=a.getInt(3), day=a.getInt(4);
            if(w<1 || w>54 || d<0 || d>6) continue;
            int academic=Integer.parseInt(term.substring(0,4));
            LocalDate candidate=null;
            for(int year=academic;year<=academic+1;year++) {
                if(y!=0 && y!=year) continue;
                try {
                    LocalDate actual=LocalDate.of(year,m,day);
                    if(actual.getDayOfWeek().getValue()!=d+1) continue;
                    LocalDate monday=actual.minusDays(7L*(w-1)+d);
                    if(inTerm(monday)) candidate=monday;
                } catch(DateTimeException ignored) {}
            }
            if(candidate==null || first!=null && !first.equals(candidate)) conflict=true;
            else {first=candidate; count=Math.max(count,w);}
        }
        String explicit=data.optString("begin","");
        if(!explicit.isEmpty()) try {
            LocalDate day=LocalDate.parse(explicit);
            if(!inTerm(day) || first!=null && !day.minusDays(day.getDayOfWeek().getValue()-1L).equals(first)) conflict=true;
            else first=day;
        } catch(DateTimeException e){conflict=true;}
        schoolStart=conflict ? null : first;
        weeks=conflict ? 0 : count;
        int[] s=new int[14], e=new int[14];
        Arrays.fill(s,-1); Arrays.fill(e,-1);
        int n=0;
        JSONArray rows=data.getJSONArray("slots");
        for(int i=0;i<rows.length();i++){
            JSONArray r=rows.getJSONArray(i);
            String label=r.getString(0);
            if(!label.matches("(?:第\\s*)?[0-9一二三四五六七八九十,，、\\s~～—–至到\\-]+(?:\\s*节)?")) continue;
            int[] span=JwParser.nodesFromHeader(label);
            if(span==null) continue;
            int a=minute(r.getString(1)), b=minute(r.getString(2));
            if(a>=b) throw new IllegalArgumentException("学校页面的上课时间范围无效："+label);
            set(s,span[0],a); set(e,span[1],b); n=Math.max(n,span[1]+1);
        }
        for(Course c:courses) n=Math.max(n,c.endNode+1);
        if(n<1 || n>14) throw new IllegalArgumentException("课表节次数量超出支持范围");
        starts=Arrays.copyOf(s,n); ends=Arrays.copyOf(e,n);
        baseStarts=previousStarts.clone(); baseEnds=previousEnds.clone();
    }
    private boolean inTerm(LocalDate date) {
        int year=Integer.parseInt(term.substring(0,4));
        return term.endsWith("-1") ? date.getYear()==year && date.getMonthValue()>=7
                : date.getYear()==year+1 && date.getMonthValue()<=7;
    }
    static int minute(String value) {
        String s=value.trim().replace('：',':');
        if(!s.matches("(?:[01]?\\d|2[0-3]):[0-5]\\d")) throw new IllegalArgumentException("时间请填写 HH:mm");
        String[] p=s.split(":"); return Integer.parseInt(p[0])*60+Integer.parseInt(p[1]);
    }
    static String clock(int m){return String.format(Locale.ROOT,"%02d:%02d",m/60,m%60);}
    private static void set(int[] slots,int index,int value){
        if(slots[index]>=0 && slots[index]!=value) throw new IllegalArgumentException("学校页面包含不同作息，请先选择节次模式");
        slots[index]=value;
    }
    List<String[]> fields(Map<String,String> initial) {
        List<String[]> fields=new ArrayList<>();
        if(schoolStart==null) fields.add(new String[]{"date","开学日期（未识别，YYYY-MM-DD）"});
        for(int i=0;i<starts.length;i++){
            for(boolean start:new boolean[]{true,false}){
                if((start?starts:ends)[i]>=0) continue;
                String key=(start?"s":"e")+i;
                int[] base=start?baseStarts:baseEnds;
                fields.add(new String[]{key,"第"+(i+1)+"节"+(start?"上课":"下课")+"（"+(i<base.length?"原课表时间，请核对":"请补充 HH:mm")+"）"});
                if(i<base.length) initial.put(key,clock(base[i]));
            }
        }
        return fields;
    }
    String configure(String original, Map<String,String> answers, List<Course> courses, ZoneId zone) throws Exception {
        LocalDate date=schoolStart;
        if(date==null) try { date=LocalDate.parse(answers.getOrDefault("date","").trim()); }
        catch(DateTimeException e){throw new IllegalArgumentException("开学日期请填写 YYYY-MM-DD");}
        if(!inTerm(date)) throw new IllegalArgumentException("开学日期与所选学期不符");
        JSONObject result=new JSONObject(original), timing=result.getJSONObject("courseTableTime");
        result.put("termBeginTime",date.atStartOfDay(zone).toInstant().toEpochMilli());
        int max=0;
        for(Course c:courses) max=Math.max(max,c.lastWeek());
        result.put("weekNumberOfThisTerm",Math.max(Math.max(weeks,max),result.getInt("weekNumberOfThisTerm")));
        String[] keys={"morningCoursesList","afternoontCoursesList","nightCoursesList"};
        String[] counts={"morningCourseNumber","afternoonCourseNumber","nightCourseNumber"};
        List<JSONObject> old=new ArrayList<>();
        for(String key:keys){JSONArray arr=timing.getJSONArray(key);for(int i=0;i<arr.length();i++)old.add(arr.getJSONObject(i));}
        JSONArray[] groups={new JSONArray(),new JSONArray(),new JSONArray()};
        int last=-1;
        for(int i=0;i<starts.length;i++){
            int s=starts[i]>=0?starts[i]:minute(answers.getOrDefault("s"+i,""));
            int e=ends[i]>=0?ends[i]:minute(answers.getOrDefault("e"+i,""));
            if(s<last || e<=s) throw new IllegalArgumentException("第"+(i+1)+"节起止时间无效或与前一节重叠");
            JSONObject item=i<old.size()? old.get(i):new JSONObject();
            item.put("startTime",s*60000L).put("endTime",e*60000L);
            groups[s<720?0:s<1080?1:2].put(item); last=e;
        }
        for(int i=0;i<3;i++) timing.put(keys[i],groups[i]).put(counts[i],groups[i].length());
        return result.toString();
    }
}
