package com.lcodexs.coursealarm;

import android.app.Activity;
import java.time.ZoneId;
import java.util.*;

final class XitImportPreview {
    static void open(Activity a, TableCtx frozen, JwParser.ParseResult parsed, XitTiming timing) {
        Map<String,String> defaults=new LinkedHashMap<>();
        List<String[]> fields=timing.fields(defaults);
        if(fields.isEmpty()) confirm(a,frozen,parsed,timing,fields,defaults);
        else edit(a,frozen,parsed,timing,fields,defaults);
    }
    private static void edit(Activity a,TableCtx frozen,JwParser.ParseResult parsed,XitTiming timing,
                             List<String[]> fields,Map<String,String> values){
        TransferUi.formAction(a,"核对网页未提供的时间",fields,values,v->confirm(a,frozen,parsed,timing,fields,v));
    }
    private static void confirm(Activity a,TableCtx frozen,JwParser.ParseResult parsed,XitTiming timing,
                                List<String[]> fields,Map<String,String> values){
        try {
            String config=timing.configure(frozen.tableJson,values,parsed.courses,ZoneId.systemDefault());
            TableCtx target=TableCtx.fromMetadata(frozen.calendarId,frozen.tableName,config);
            StringBuilder note=new StringBuilder(timing.term).append(" · 开学日期 ").append(target.termDate());
            for(int i=0;i<target.nodeCount();i++) {
                note.append(i%2==0 ? "\n" : "　").append("第").append(i+1).append("节 ")
                    .append(XitTiming.clock(target.periodStart[i])).append("–").append(XitTiming.clock(target.periodEnd[i]));
            }
            if(!parsed.unscheduled.isEmpty()) note.append("\n另有 ").append(parsed.unscheduled.size()).append(" 门课程缺少节次，不导入。");
            CurriculumIo.preview(a,parsed.courses,"拾光 · 厦门工学院",frozen,note.toString(),config);
        } catch(Exception e){
            CurriculumIo.toast(a,e.getMessage());
            if(!fields.isEmpty()) edit(a,frozen,parsed,timing,fields,values);
        }
    }
}
