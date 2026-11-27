package com.lcodexs.coursealarm;

import android.app.Activity;
import android.app.Application;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** All module imports and confirmed course/table deletions share one atomic writer. */
final class CourseMutations {
    enum Mode { REPLACE, APPEND, NEW_TABLE }
    interface Completion { void done(Result result); }
    static final class Result {
        final boolean success;
        final String message;
        final long calendarId;
        final String undoToken;
        Result(boolean ok, String message, long calendarId, String token) {
            this.success = ok; this.message = message; this.calendarId = calendarId; this.undoToken = token;
        }
    }
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor(r -> new Thread(r, "course-mutations"));
    private static final Handler UI = new Handler(Looper.getMainLooper());
    // Accessed only on WORK; each task binds a backend before reading or constructing operations.
    private static CalendarBackend backend;
    private static Context app;
    private static volatile Record pending;

    static void init(Context context) {
        if (app != null) return;
        Context application = context.getApplicationContext();
        app = application != null ? application : context;
        UI.post(() -> { Context ready = app instanceof Application ? app : app.getApplicationContext(); if (ready instanceof Application) UndoBar.init((Application)ready); });
        WORK.execute(() -> {
            File[] files = journalDir().listFiles((dir, name) -> name.endsWith(".json"));
            if (files == null) return;
            for (File file : files) try {
                JSONObject journal = new JSONObject(new String(new AtomicFile(file).readFully(), StandardCharsets.UTF_8));
                String state = journal.optString("state");
                if (state.equals("COMMITTED") || state.equals("EXPIRED") || state.equals("UNDONE")) {
                    // Undo deadlines never restart when the process is recreated.
                    if (state.equals("COMMITTED")) {
                        Record record = Record.parse(journal);
                        requireRecordBackend(record);
                        boolean same = unchanged(record.after);
                        journal.put("state", same ? "EXPIRED" : "CHANGED_AFTER_RESTART");
                        saveJson(file, journal);
                    }
                } else if (state.equals("PREPARED")) {
                    // A crash can occur after applyBatch commits but before its result is journaled.
                    // An unchanged before-image (especially an empty import) cannot prove rollback.
                    journal.put("state", "NEEDS_REVIEW");
                    saveJson(file, journal);
                    Logx.w(CourseAlarmEntry.TAG, "恢复记录已核对，不重放写入: " + journal.optString("state"));
                } else if (state.equals("UNDOING") || state.equals("UNDO_APPLIED")) {
                    Record record = Record.parse(journal);
                    requireRecordBackend(record);
                    journal.put("state", restored(record) ? "UNDONE_VERIFIED" : "NEEDS_REVIEW");
                    saveJson(file, journal);
                } else if (state.equals("APPLIED")) {
                    journal.put("state", "NEEDS_REVIEW"); saveJson(file, journal);
                }
            } catch (Exception error) { Logx.e(CourseAlarmEntry.TAG, "recovery record", error); }
        });
    }

    static void importCourses(Context context, TableCtx frozen, List<Course> courses, Mode mode,
                              String name, Completion completion) {
        submit(context, completion, () -> performImport(frozen, courses, mode, name));
    }

    static void captureDrag(Activity activity, Object nativeCourse, int week, DragSession.Callback callback) {
        if (app == null) init(activity);
        try {
            Object event = Ref.callName(nativeCourse, "getEvent");
            Object value = Ref.callName(event, "getId");
            if (!(value instanceof Number)) throw new IllegalArgumentException("课程身份不可读取");
            long id = ((Number)value).longValue();
            HostTableData shown = HostTablePage.currentData();
            if (shown == null || shown.calendarId <= 0) throw new IllegalStateException("课表尚未加载");
            long calendarId = shown.calendarId;
            String shownTable = shown.tableJson;
            String shownCourse = String.valueOf(Ref.callName(event, "getEventsJsonExtensions"));
            WORK.execute(() -> {
                try {
                    requireBackend();
                    if (TableCtx.hostCurrentTableId(app) != calendarId) throw new IllegalStateException("当前课表已切换");
                    Row calendar = read("calendars", calendarId);
                    if (calendar == null || !isCourseCalendar(calendar.values)) throw new IllegalStateException("课表已删除");
                    if (shown.backendAuthority != null && !backend.authority.equals(shown.backendAuthority))
                        throw new IllegalStateException("日历存储已切换，请刷新课表");
                    String tableJson = calendar.values.getAsString("calendars_json_extensions");
                    if (!sameConfig(shownTable, tableJson)) throw new IllegalStateException("课表配置已变化，请刷新后重试");
                    TableCtx table = TableCtx.fromMetadata(calendarId, calendar.values.getAsString("calendar_displayName"), tableJson);
                    table.backendAuthority = backend.authority;
                    if (week < 1 || week > table.weekCount) throw new IllegalArgumentException("展示周无效");
                    List<DragSession.Entry> courses = captureEntries(calendarId, true);
                    DragSession.Entry source = null;
                    for (DragSession.Entry entry : courses) if (entry.eventId == id) source = entry;
                    if (source == null || !sameConfig(shownCourse, source.json)) throw new IllegalStateException("课程已变化，请刷新后重试");
                    if (!source.course.weeks.get(week)) throw new IllegalArgumentException("非本周课程禁止拖动");
                    if (table.dateOf(week, source.course.day).isBefore(table.termDate()))
                        throw new IllegalArgumentException("此位置早于开学日期，没有可调动的课程");
                    DragSession session = new DragSession(table, week, source, courses, comparable(calendar));
                    UI.post(() -> { if (!activity.isDestroyed() && !activity.isFinishing()) callback.done(session, null); });
                } catch (Exception error) { UI.post(() -> callback.done(null, rootMessage(error))); }
            });
        } catch (Exception error) { UI.post(() -> callback.done(null, rootMessage(error))); }
    }

    private static List<DragSession.Entry> captureEntries(long calendarId, boolean includeChildren) throws Exception {
        ArrayList<DragSession.Entry> entries = new ArrayList<>();
        Map<Long,List<Row>> children = new HashMap<>();
        if (includeChildren) for (String child : CHILDREN) for (Row row : rows(child, null, 0))
            children.computeIfAbsent(number(row.values,"event_id"), ignored -> new ArrayList<>()).add(row);
        for (Row row : rows("events", "calendar_id", calendarId)) {
            if (number(row.values,"deleted") != 0 || number(row.values,"hasExtendedProperties") != CourseWriter.COURSE_EXT_PROP) continue;
            entries.add(new DragSession.Entry(row.id, row.values.getAsString("events_json_extensions"), row.values,
                    comparable(row), childFingerprint(children.getOrDefault(row.id, Collections.emptyList()))));
        }
        return entries;
    }

    private static String childFingerprint(List<Row> children) throws Exception {
        ArrayList<String> values = new ArrayList<>();
        for (Row row : children) values.add(row.key()+":"+comparable(row));
        Collections.sort(values); return String.join("\n",values);
    }
    private static boolean sameConfig(String first, String second) throws Exception {
        JSONObject a = new JSONObject(first), b = new JSONObject(second);
        a.remove("sysVersion"); b.remove("sysVersion");
        return canonical(a).toString().equals(canonical(b).toString());
    }

    static void reschedule(Activity activity, RescheduleRequest request, Completion completion) {
        submit(activity, result -> {
            if (result.success && !result.message.isEmpty()) queueAlarmRefresh();
            if (completion != null) completion.done(result);
        }, () -> performReschedule(request), true);
    }

    private static Result performReschedule(RescheduleRequest request) throws Exception {
        requireBackend();
        DragSession session = request.session;
        if (session.table.backendAuthority != null && !backend.authority.equals(session.table.backendAuthority))
            throw new IllegalStateException("日历存储已切换，请重新调课");
        if (TableCtx.hostCurrentTableId(app) != session.calendarId) throw new IllegalStateException("当前课表已切换，请重新调课");
        Row calendar = read("calendars",session.calendarId);
        if (calendar == null || !isCourseCalendar(calendar.values) || !comparable(calendar).equals(session.calendarFingerprint))
            throw new IllegalStateException("课表配置已变化，请重新调课");
        List<DragSession.Entry> current = captureEntries(session.calendarId,true);
        Set<Long> selected = request.scope == RescheduleRequest.Scope.DAY ? request.eventIds : Collections.singleton(session.eventId);
        for (long id : selected) {
            DragSession.Entry frozen = session.entry(id), now = null;
            for (DragSession.Entry entry : current) if(entry.eventId==id) now=entry;
            if(frozen==null || now==null || !frozen.fingerprint.equals(now.fingerprint) || !frozen.childFingerprint.equals(now.childFingerprint))
                throw new IllegalStateException("课程或提醒已变化，请重新调课");
        }
        List<ReschedulePlanner.Change> changes = ReschedulePlanner.plan(request,current);
        if (changes.isEmpty()) return new Result(true,"",session.calendarId,null);
        Record record = new Record(request.scope == RescheduleRequest.Scope.DAY ? "已调整 " + changes.size() + " 门课程" : "已调整课程",session.calendarId);
        ArrayList<ContentProviderOperation> ops = new ArrayList<>();
        ops.add(assertion(calendar));
        // Protect all conflict inputs, including additions and deletions between planning and applyBatch.
        for(DragSession.Entry entry:current) ops.add(assertion(new Row("events",entry.eventId,entry.eventValues,false)));
        ops.add(ContentProviderOperation.newAssertQuery(uri("events"))
                .withSelection("calendar_id=? AND deleted=0 AND hasExtendedProperties=?",new String[]{Long.toString(session.calendarId),Integer.toString(CourseWriter.COURSE_EXT_PROP)})
                .withExpectedCount(current.size()).withYieldAllowed(false).build());
        for(ReschedulePlanner.Change change:changes) {
            List<Row> snapshot = new ArrayList<>(); snapshotEvent(snapshot,change.source.eventId);
            if (!comparable(snapshot.get(0)).equals(change.source.fingerprint)
                    || !childFingerprint(snapshot.subList(1,snapshot.size())).equals(change.source.childFingerprint))
                throw new IllegalStateException("课程或提醒已变化，请重新调课");
            record.before.addAll(snapshot);
        }
        ops.addAll(assertions(record.before));
        ArrayList<Integer> inserted = new ArrayList<>();
        for(ReschedulePlanner.Change change:changes) {
            if(change.remainingJson!=null) {
                ContentValues remaining = rescheduledValues(session,change.source,change.remainingJson,true,false);
                addEventUpdate(ops,change.source.eventId,change.source.eventValues,remaining,calendar.values);
                ContentValues moved = rescheduledValues(session,change.source,change.movedJson,change.preserveTimes,true);
                int index=ops.size();
                ops.add(ContentProviderOperation.newInsert(sync(uri("events"),calendar.values)).withValues(moved).withYieldAllowed(false).build());
                inserted.add(index);
                // Preserve each relation exactly; reminders are not reconstructed from a global default.
                for(Row child:record.before) if(!child.table.equals("events") && number(child.values,"event_id")==change.source.eventId) {
                    ContentValues values=new ContentValues(child.values);values.remove("_id");values.remove("event_id");
                    ops.add(ContentProviderOperation.newInsert(sync(uri(child.table),calendar.values)).withValues(values)
                            .withValueBackReference("event_id",index).withYieldAllowed(false).build());
                    inserted.add(ops.size()-1);
                }
            } else {
                ContentValues moved=rescheduledValues(session,change.source,change.movedJson,change.preserveTimes,false);
                addEventUpdate(ops,change.source.eventId,change.source.eventValues,moved,calendar.values);
            }
        }
        if(ops.size()>1500) throw new IllegalArgumentException("单次调课事务过大，请减少课程后重试");
        return commit(record,ops,inserted,false);
    }

    private static void addEventUpdate(List<ContentProviderOperation> ops,long id,ContentValues before,ContentValues after,ContentValues calendar) {
        ContentValues changed=new ContentValues(after);
        for(String key:new ArrayList<>(changed.keySet())) if(Objects.equals(changed.get(key),before.get(key))) changed.remove(key);
        if (changed.size() == 0) return; // The source row is already guarded by the batch assertions.
        ops.add(ContentProviderOperation.newUpdate(sync(uri("events"),calendar)).withSelection("_id=?",args(id)).withValues(changed)
                .withExpectedCount(1).withYieldAllowed(false).build());
    }

    private static ContentValues rescheduledValues(DragSession session,DragSession.Entry source,String courseJson,
                                                    boolean preserveTimes,boolean insert) throws Exception {
        String prefix="com.android.calendar.card.schooltimetable.entity.";
        JSONObject sourceJson=new JSONObject(courseJson);
        if(!preserveTimes) sourceJson.put("startTime",0).put("endTime",0).put("timeDuration","");
        Object course=HostEntities.fromJson(app,prefix+"CourseEntity",sourceJson.toString());
        Object table=HostEntities.fromJson(app,prefix+"CourseTableEntity",session.tableJson);
        Object event=HostEntities.newEntity(app,"Event");
        HostEntities.set(event,"setId",source.eventId);
        HostEntities.set(event,"setEventsJsonExtensions",source.json);
        Class<?> utils=Class.forName("com.android.calendar.card.schooltimetable.ScheduleTableUtils",false,app.getClassLoader());
        java.lang.reflect.Method builder=Ref.methodByArity(utils,"buildEventByCourseEntity",4);
        if(builder==null)throw new NoSuchMethodException("buildEventByCourseEntity");
        builder.invoke(null,session.calendarId,table,event,course);
        ContentValues built=HostEntities.values(app,"Event",event);
        Long begin=built.getAsLong("dtstart");
        if(begin==null||begin<=0)throw new IllegalStateException("宿主未能构建课程时间");
        ContentValues result=new ContentValues(source.eventValues);
        for(String key:new String[]{"original_id","original_sync_id","originalInstanceTime","original_event_local_global_id"}) {
            String original=result.getAsString(key);
            if(original!=null&&!original.isEmpty()&&!original.equals("0")&&!original.equals("-1"))
                throw new IllegalStateException("此课程是重复日程的例外，请先在课程编辑页确认周次后再调课");
        }
        for(String key:new String[]{"exdate","exrule","rdate"}) {
            String extra=result.getAsString(key);
            if(extra!=null&&!extra.isEmpty()) throw new IllegalStateException("此课程包含额外重复例外，请先在课程编辑页确认周次后再调课");
        }
        for(String key:new String[]{"dtstart","dtend","duration","rrule","eventTimezone","eventEndTimezone"}) {
            if(built.containsKey(key)) { Object value=built.get(key); if(value==null)result.putNull(key);else if(value instanceof Number)result.put(key,((Number)value).longValue());else result.put(key,value.toString()); }
            else if(key.equals("dtend")||key.equals("duration")||key.equals("rrule")) result.putNull(key);
        }
        // CourseEntity serialization discards fields unknown to this host; merge back the original extension.
        JSONObject nativeJson=new JSONObject(built.getAsString("events_json_extensions"));
        for(Iterator<String> it=nativeJson.keys();it.hasNext();) {String key=it.next();sourceJson.put(key,nativeJson.get(key));}
        result.put("events_json_extensions",sourceJson.toString());
        if(preserveTimes) {
            int[] time=ReschedulePlanner.times(session.table,source);
            Calendar date=Calendar.getInstance();date.setTimeInMillis(begin);
            date.set(Calendar.HOUR_OF_DAY,time[0]/3600);date.set(Calendar.MINUTE,time[0]/60%60);date.set(Calendar.SECOND,time[0]%60);date.set(Calendar.MILLISECOND,0);
            long adjusted=date.getTimeInMillis();result.put("dtstart",adjusted);
            String recurrence=result.getAsString("rrule");
            if(recurrence!=null&&!recurrence.isEmpty()){result.put("duration","P"+(time[1]-time[0])+"S");result.putNull("dtend");}
            else {result.putNull("duration");result.put("dtend",adjusted+(time[1]-time[0])*1000L);}
        }
        long finalStart = result.getAsLong("dtstart");
        int finalDuration = CourseDuration.parseSeconds(result.getAsString("duration"));
        Long finalEnd = result.getAsLong("dtend");
        if (finalDuration <= 0 && finalEnd != null && finalEnd > finalStart)
            finalDuration = Math.toIntExact((finalEnd - finalStart) / 1000L);
        if (finalDuration <= 0 || finalDuration > 86400) throw new IllegalStateException("宿主生成的课程时长无效");
        // timeDuration is the host's localized morning/afternoon label, not an ISO duration.
        sourceJson.put("startTime",finalStart).put("endTime",finalStart + finalDuration * 1000L);
        result.put("events_json_extensions",sourceJson.toString());
        result.put("calendar_id",session.calendarId);result.put("dirty",1);result.put("deleted",0);
        result.put("mutators",app.getPackageName());
        if(result.containsKey("event_update_time"))result.put("event_update_time",System.currentTimeMillis());
        result.putNull("lastDate");
        if(insert) {
            for(String key:new ArrayList<>(result.keySet())) if(key.equals("_id")||key.equals("_sync_id")||key.equals("local_global_id")
                    ||key.equals("uid2445")
                    ||key.equals("event_local_global_id")||key.equals("original_event_local_global_id")
                    ||key.equals("original_id")||key.equals("original_sync_id")||key.equals("originalInstanceTime")||key.equals("originalAllDay")
                    ||key.equals("lastSynced")||key.startsWith("sync_data"))result.remove(key);
            if(result.containsKey("event_create_time"))result.put("event_create_time",System.currentTimeMillis());
            // Nullable provider columns are commands on update, but omitted on insert.
            for(String key:new ArrayList<>(result.keySet()))if(result.get(key)==null)result.remove(key);
        }
        CourseRescheduleRepair.stamp(result, session.tableJson);
        return result;
    }

    static void pasteCourse(Context context, long calendarId, String tableJson, String courseJson) {
        submit(context, null, () -> {
            TableCtx target = TableCtx.load(app);
            if (calendarId <= 0 || target.calendarId != calendarId)
                throw new IllegalStateException("当前课表已切换，请重新粘贴");
            // Freeze the configuration shown when the user clicked, then revalidate in the writer.
            target.tableJson = tableJson;
            return performImport(target, Collections.singletonList(CourseClipboard.read(courseJson)),
                    Mode.APPEND, null, courseJson);
        });
    }

    static void deleteCourse(Activity activity, long eventId) {
        deleteCourse(activity, eventId, true);
    }

    static void deleteCourse(Activity activity, long eventId, boolean closeEditor) {
        submit(activity, result -> { if (result.success && closeEditor) activity.finish(); }, () -> {
            requireBackend();
            Row event = read("events", eventId);
            if (event == null || number(event.values, "deleted") != 0 || number(event.values, "hasExtendedProperties") != CourseWriter.COURSE_EXT_PROP)
                throw new IllegalStateException("课程已删除或无法识别");
            Record record = new Record("已删除课程", number(event.values, "calendar_id"));
            snapshotEvent(record.before, eventId);
            ArrayList<ContentProviderOperation> ops = assertions(record.before);
            ops.add(ContentProviderOperation.newDelete(uri("events")).withSelection("_id=?", args(eventId))
                    .withExpectedCount(1).withYieldAllowed(false).build());
            return commit(record, ops, Collections.emptyList(), false);
        });
    }

    static void deleteTables(Activity activity, List<Long> ids) { deleteTables(activity, ids, true); }

    static void deleteTables(Activity activity, List<Long> ids, boolean closeManager) {
        submit(activity, result -> { if (result.success && closeManager) activity.finish(); }, () -> {
            requireBackend();
            if (ids.isEmpty()) throw new IllegalArgumentException("没有选择课表");
            Record record = new Record("已删除 " + ids.size() + " 张课表", ids.get(0));
            for (long id : new LinkedHashSet<>(ids)) {
                Row cal = read("calendars", id);
                if (cal == null || !isCourseCalendar(cal.values)) throw new IllegalStateException("课表已变更");
                record.before.add(cal);
                for (Row event : rows("events", "calendar_id", id)) snapshotEvent(record.before, event.id);
            }
            ArrayList<ContentProviderOperation> ops = assertions(record.before);
            for (long id : new LinkedHashSet<>(ids)) {
                ops.add(ContentProviderOperation.newDelete(uri("events")).withSelection("calendar_id=?", args(id)).withYieldAllowed(false).build());
                ops.add(ContentProviderOperation.newDelete(uri("calendars")).withSelection("_id=?", args(id)).withExpectedCount(1).withYieldAllowed(false).build());
            }
            return commit(record, ops, Collections.emptyList(), ids.contains(record.selectedBefore));
        });
    }

    private interface Task { Result run() throws Exception; }
    private static void submit(Context context, Completion completion, Task task) {
        submit(context, completion, task, false);
    }
    private static void submit(Context context, Completion completion, Task task, boolean reportsErrors) {
        if (app == null) init(context);
        WORK.execute(() -> {
            Result result;
            try {
                for (int attempt = 0; ; attempt++) {
                    try { result = task.run(); break; }
                    catch (OperationApplicationException rollback) {
                        if (attempt >= 2) throw rollback;
                        Logx.w(CourseAlarmEntry.TAG, "写入事务已回滚，重新读取后重试 (" + (attempt + 1) + ")");
                    }
                }
            }
            catch (Exception error) {
                Logx.e(CourseAlarmEntry.TAG, "课程操作失败", error);
                result = new Result(false, reportsErrors ? rescheduleError(error) : "操作失败：" + rootMessage(error), -1, null);
            }
            Result outcome = result;
            UI.post(() -> {
                if (outcome.success) {
                    if (!outcome.message.isEmpty()) refresh();
                    if (outcome.undoToken != null) UndoBar.show(outcome.message, outcome.undoToken);
                    else if (!outcome.message.isEmpty()) CurriculumIo.toast(app, outcome.message);
                } else { refresh(); if (!reportsErrors || completion == null) CurriculumIo.toast(app, outcome.message); }
                if (completion != null) completion.done(outcome);
            });
        });
    }

    private static Result performImport(TableCtx frozen, List<Course> courses, Mode mode, String name) throws Exception {
        return performImport(frozen, courses, mode, name, null);
    }

    private static Result performImport(TableCtx frozen, List<Course> courses, Mode mode, String name,
                                        String pastedJson) throws Exception {
        return performImport(frozen,courses,mode,name,pastedJson,null);
    }
    static void importWithTiming(Context context,TableCtx frozen,List<Course> courses,Mode mode,String name,String config) {
        submit(context,null,()->performImport(frozen,courses,mode,name,null,config));
    }
    private static Result performImport(TableCtx frozen,List<Course> courses,Mode mode,String name,
                                        String pastedJson,String proposedConfig) throws Exception {
        requireBackend();
        if (courses == null || courses.isEmpty()) throw new IllegalArgumentException("没有有效课程，原课表未改动");
        if (frozen.backendAuthority != null && !backend.authority.equals(frozen.backendAuthority))
            throw new IllegalStateException("日历存储已切换，请重新预览导入");
        if (frozen.calendarId <= 0) throw new IllegalStateException("请先选择课表");
        Row current = read("calendars", frozen.calendarId);
        if (current == null || !isCourseCalendar(current.values)) throw new IllegalStateException("目标课表已删除");
        String currentJson = current.values.getAsString("calendars_json_extensions");
        JSONObject currentConfig = new JSONObject(currentJson), frozenConfig = new JSONObject(frozen.tableJson);
        currentConfig.remove("sysVersion"); frozenConfig.remove("sysVersion");
        if (!canonical(currentConfig).toString().equals(canonical(frozenConfig).toString()))
            throw new IllegalStateException("课表配置已变化，请重新预览导入");
        boolean timingChanged=false;
        String effectiveJson=currentJson;
        if(proposedConfig!=null) {
            long selected=TableCtx.hostCurrentTableId(app);
            if(selected>0 && selected!=frozen.calendarId)
                throw new IllegalStateException("当前课表已切换，请重新预览导入");
            TableCtx old=TableCtx.fromMetadata(frozen.calendarId,frozen.tableName,currentJson);
            TableCtx incoming=TableCtx.fromMetadata(frozen.calendarId,frozen.tableName,proposedConfig);
            timingChanged=!old.termDate().equals(incoming.termDate()) || old.weekCount!=incoming.weekCount ||
                    !Arrays.equals(old.periodStart,incoming.periodStart) || !Arrays.equals(old.periodEnd,incoming.periodEnd);
            if(timingChanged) {
                JSONObject merged=new JSONObject(currentJson), proposed=new JSONObject(proposedConfig);
                for(String k:new String[]{"termBeginTime","weekNumberOfThisTerm","courseTableTime"}) merged.put(k,proposed.get(k));
                effectiveJson=merged.toString();
            }
            frozen=TableCtx.fromMetadata(frozen.calendarId,frozen.tableName,effectiveJson);
        }
        for (Course course : courses) {
            if (course.name == null || course.name.trim().isEmpty() || course.day < 0 || course.day > 6
                    || course.startNode < 0 || course.endNode < course.startNode || course.endNode >= frozen.nodeCount()
                    || course.weeks.isEmpty() || course.firstWeek() < 1 || course.lastWeek() > frozen.weekCount)
                throw new IllegalArgumentException("课程时间或周次超出目标课表：" + course.name);
        }
        boolean create = mode == Mode.NEW_TABLE;
        Record record = new Record(pastedJson != null ? "已粘贴课程" : create ? "已新建课表并导入" : mode == Mode.REPLACE ? "已替换课程" : "已追加课程", frozen.calendarId);
        ArrayList<ContentProviderOperation> operations = new ArrayList<>();
        ArrayList<Integer> inserted = new ArrayList<>();
        Object calendar;
        String tableJson = proposedConfig==null ? frozen.tableJson : effectiveJson;
        if (create) {
            JSONObject config = new JSONObject(tableJson);
            config.remove("sysVersion");
            config.put("hasReminder", true); config.put("reminderTime", 15);
            config.put("termBeginTime", frozen.termBeginMs);
            tableJson = config.toString();
            calendar = HostEntities.newEntity(app, "Calendar");
            HostEntities.set(calendar, "setCalendarJsonExtensions", tableJson);
            HostEntities.set(calendar, "setCalendarDisplayName", name);
            HostEntities.set(calendar, "setAccountName", "oplus_course_default_calendar");
            HostEntities.set(calendar, "setAccountType", "LOCAL");
            HostEntities.set(calendar, "setOwnerAccount", "oplus_course_default_calendar");
            Integer calendarColor=current.values.getAsInteger("calendar_color");
            if(calendarColor!=null)HostEntities.set(calendar,"setCalendarColor",calendarColor);
            HostEntities.set(calendar, "setDirty", 1);
            HostEntities.set(calendar, "setUpdateTime", System.currentTimeMillis());
            ContentValues values = HostEntities.values(app, "Calendar", calendar);
            values.remove("_id"); values.put("sync_events", 1); values.put("visible", 1);
            operations.add(ContentProviderOperation.newInsert(sync(uri("calendars"), values)).withValues(values).withYieldAllowed(false).build());
            inserted.add(0);
        } else {
            calendar = HostEntities.calendar(app, frozen.calendarId);
            operations.add(assertion(current));
            if(timingChanged) {
                int active=0;
                for(Row row:rows("events","calendar_id",frozen.calendarId))
                    if(number(row.values,"deleted")==0 && number(row.values,"hasExtendedProperties")==CourseWriter.COURSE_EXT_PROP) active++;
                if(active>0 && mode==Mode.APPEND)
                    throw new IllegalArgumentException("学校作息或学期与目标课表不同，请改选新建或替换，以保留原课程时间");
                operations.add(activeCoursesAssertion(frozen.calendarId,active));
            }
            if (mode == Mode.REPLACE) {
                for (Row event : rows("events", "calendar_id", frozen.calendarId)) {
                    if (number(event.values, "hasExtendedProperties") == CourseWriter.COURSE_EXT_PROP && number(event.values, "deleted") == 0)
                        snapshotEvent(record.before, event.id);
                }
                operations.addAll(assertions(record.before));
                for (Row row : record.before) if (row.table.equals("events")) {
                    operations.add(ContentProviderOperation.newDelete(uri("events")).withSelection("_id=?", args(row.id))
                            .withExpectedCount(1).withYieldAllowed(false).build());
                }
            }
            if(timingChanged) {
                record.before.add(current);
                ContentValues delta=new ContentValues();
                delta.put("calendars_json_extensions",tableJson);
                delta.put("dirty",1);
                if(current.values.containsKey("calendar_update_time")) delta.put("calendar_update_time",System.currentTimeMillis());
                operations.add(ContentProviderOperation.newUpdate(uri("calendars")).withSelection("_id=?",args(current.id))
                        .withValues(delta).withExpectedCount(1).withYieldAllowed(false).build());
                HostEntities.set(calendar,"setCalendarJsonExtensions",tableJson);
            }
        }
        String entityPackage = "com.android.calendar.card.schooltimetable.entity.";
        Object tableEntity = HostEntities.fromJson(app, entityPackage + "CourseTableEntity", tableJson);
        Class<?> utils = Class.forName("com.android.calendar.card.schooltimetable.ScheduleTableUtils", false, app.getClassLoader());
        if (pastedJson != null) {
            Course pasted = courses.get(0);
            Object nativeCourse = HostEntities.fromJson(app, entityPackage + "CourseEntity", pastedJson);
            Object timing = tableEntity.getClass().getMethod("getCourseTableTime").invoke(tableEntity);
            Object parts = utils.getMethod("splitCourse", int.class, int.class, int.class, nativeCourse.getClass())
                    .invoke(null, timing.getClass().getMethod("getMorningCourseNumber").invoke(timing),
                            timing.getClass().getMethod("getAfternoonCourseNumber").invoke(timing),
                            timing.getClass().getMethod("getNightCourseNumber").invoke(timing), nativeCourse);
            if (!(parts instanceof List) || ((List<?>) parts).size() != 1)
                throw new IllegalArgumentException("粘贴位置跨越上下午或晚间，请选择其他节次");
            int count = 0;
            for (Row row : rows("events", "calendar_id", frozen.calendarId)) {
                if (number(row.values, "deleted") != 0 || number(row.values, "hasExtendedProperties") != CourseWriter.COURSE_EXT_PROP) continue;
                if (CourseClipboard.overlaps(pasted, CourseClipboard.read(row.values.getAsString("events_json_extensions"))))
                    throw new IllegalArgumentException("课程时间冲突，请选择其他位置");
                operations.add(assertion(row));
                count++;
            }
            // Reject changes between conflict checking and insertion, including newly added courses.
            operations.add(ContentProviderOperation.newAssertQuery(uri("events"))
                    .withSelection("calendar_id=? AND deleted=0 AND hasExtendedProperties=?",
                            new String[]{Long.toString(frozen.calendarId), Integer.toString(CourseWriter.COURSE_EXT_PROP)})
                    .withExpectedCount(count).withYieldAllowed(false).build());
        }
        java.lang.reflect.Method build = Ref.methodByArity(utils, "buildEventByCourseEntity", 4);
        if (build == null) throw new NoSuchMethodException("buildEventByCourseEntity");
        for (Course course : courses) {
            Object entity = HostEntities.fromJson(app, entityPackage + "CourseEntity",
                    pastedJson != null ? pastedJson : CourseWriter.toJson(course, frozen.weekCount));
            Object event = HostEntities.newEntity(app, "Event");
            HostEntities.set(event, "setTitle", course.name);
            HostEntities.set(event, "setEventLocation", course.room);
            HostEntities.set(event, "setDescription", course.teacher);
            HostEntities.set(event, "setCalendar", calendar);
            build.invoke(null, create ? 0L : frozen.calendarId, tableEntity, event, entity);
            ContentValues values = HostEntities.values(app, "Event", event);
            values.remove("_id");
            if (values.getAsLong("dtstart") == null || values.getAsString("events_json_extensions") == null)
                throw new IllegalStateException("宿主无法构建课程：" + course.name);
            values.put("calendar_id", frozen.calendarId);
            boolean reminder = create || frozen.hasReminder;
            values.put("hasAlarm", reminder ? 1 : 0);
            int eventIndex = operations.size();
            ContentProviderOperation.Builder insert = ContentProviderOperation.newInsert(uri("events")).withValues(values).withYieldAllowed(false);
            if (create) insert.withValueBackReference("calendar_id", 0);
            operations.add(insert.build()); inserted.add(eventIndex);
            if (reminder) {
                int method = 1;
                Object nativeReminders = Ref.callName(event, "getReminders");
                if (nativeReminders instanceof List && !((List<?>) nativeReminders).isEmpty()) {
                    Object methodValue = Ref.callName(((List<?>) nativeReminders).get(0), "getMethod");
                    if (methodValue instanceof Number) method = ((Number) methodValue).intValue();
                }
                operations.add(ContentProviderOperation.newInsert(uri("reminders"))
                        .withValueBackReference("event_id", eventIndex).withValue("minutes", create ? 15 : frozen.reminderMinutes)
                        .withValue("method", method).withYieldAllowed(false).build());
            }
        }
        // Do not split a replacement into separate provider transactions to bypass Binder limits.
        if (operations.size() > 1500) throw new IllegalArgumentException("单次事务过大，请减少课程后重试");
        return commit(record, operations, inserted, create);
    }

    private static Result commit(Record record, ArrayList<ContentProviderOperation> operations,
                                 List<Integer> inserted, boolean changeSelection) throws Exception {
        requireRecordBackend(record);
        for (ContentProviderOperation operation : operations)
            if (!record.authority.equals(operation.getUri().getAuthority()) || operation.isYieldAllowed())
                throw new IllegalStateException("事务包含其他存储或可中断操作，未写入数据");
        record.state = "PREPARED"; save(record);
        // Protect the rows before publishing tombstones to the provider's observers.
        for (Row row : record.before) CourseSyncGate.hold(record.token, record.authority, row.table, row.id);
        ContentProviderResult[] results;
        try {
            backend.requireCurrent(app);
            results = app.getContentResolver().applyBatch(record.authority, operations);
            if (results == null || results.length != operations.size())
                throw new IllegalStateException("宿主未确认事务结果，保留恢复记录，请勿重复操作");
        }
        catch (OperationApplicationException error) {
            record.state = "NOT_APPLIED"; save(record); CourseSyncGate.release(record.token); throw error;
        } catch (Exception error) {
            record.state = "NEEDS_REVIEW"; save(record); CourseSyncGate.release(record.token); throw error;
        }
        record.state = "APPLIED";
        try {
        for (int index : inserted) {
            Uri uri = results[index].uri;
            if (uri == null) throw new IllegalStateException("事务返回缺少新增记录 ID");
            Row insertedId = Row.absent(uri.getPathSegments().get(0), ContentUris.parseId(uri));
            record.inserted.add(insertedId);
            CourseSyncGate.hold(record.token, record.authority, insertedId.table, insertedId.id);
        }
        save(record);
        Set<String> keys = new HashSet<>();
        for (Row before : record.before) {
            Row after = read(before.table, before.id);
            record.after.add(after == null ? Row.absent(before.table, before.id) : after);
            keys.add(before.key());
        }
        for (int index : inserted) {
            Uri uri = results[index].uri;
            if (uri == null) throw new IllegalStateException("事务返回缺少新增记录 ID");
            String table = uri.getPathSegments().get(0);
            long id = ContentUris.parseId(uri);
            Row row = read(table, id);
            if (row == null) throw new IllegalStateException("新增记录读取失败，保留恢复记录");
            if (table.equals("calendars")) record.calendarId = id;
            if (keys.add(row.key())) record.after.add(row);
            if (table.equals("events")) for (String child : CHILDREN) {
                for (Row childRow : rows(child, "event_id", id)) if (keys.add(childRow.key())) record.after.add(childRow);
            }
        }
        // Invalidate cached settings for deleted/restored identities; retain their private preferences for undo.
        for (Row row : record.before) if (row.table.equals("calendars")) TableSettings.invalidate(row.id);
        for (Row row : record.after) if (row.table.equals("calendars") && !row.absent && !contains(record.before,row.key()))
            TableSettings.resetForNewTable(app,row.id);
        if (changeSelection) {
            Row created = null;
            for (Row row : record.after) if (row.table.equals("calendars") && !row.absent
                    && !contains(record.before, row.key()) && number(row.values, "deleted") == 0) created = row;
            if (created != null) select(created.id);
            else {
                long next = -1;
                for (Row row : rows("calendars", null, 0)) if (isCourseCalendar(row.values)) { next = row.id; break; }
                select(next);
            }
        }
        record.selectedAfter = TableCtx.hostCurrentTableId(app);
        record.state = "COMMITTED"; save(record);
        if (pending != null) {
            pending.state = "EXPIRED"; save(pending); CourseSyncGate.release(pending.token);
        }
        pending = record;
        return new Result(true, record.message, record.calendarId, record.token);
        } catch (Exception postCommitError) {
            // The provider has already committed. Never report this as an ordinary write failure.
            record.state = "APPLIED_NEEDS_REVIEW";
            try { save(record); } catch (Exception journalError) { Logx.e(CourseAlarmEntry.TAG, "保存已提交操作", journalError); }
            CourseSyncGate.release(record.token);
            if (pending != null) { CourseSyncGate.release(pending.token); pending = null; }
            Logx.e(CourseAlarmEntry.TAG, "操作已提交，撤回记录核对失败", postCommitError);
            return new Result(true, "操作已写入，但撤回记录核对失败，请勿重复操作", record.calendarId, null);
        }
    }

    static void displayed(String token, long deadline) {
        Record record = pending;
        if (record != null && record.token.equals(token) && record.deadline == 0) record.deadline = deadline;
    }

    static void undo(String token) {
        long clickedAt = SystemClock.elapsedRealtime();
        WORK.execute(() -> {
            Record record = pending;
            if (record == null || !record.token.equals(token) || record.deadline == 0 || clickedAt >= record.deadline) {
                UI.post(() -> UndoBar.finish(token, "撤回已到期或已被下一次操作替换"));
                return;
            }
            boolean applied = false;
            try {
                requireRecordBackend(record);
                record.state = "UNDOING"; save(record);
                for (int attempt = 0; ; attempt++) {
                    // One frozen image drives both the conflict check and insert/update decisions.
                    List<Row> current = currentRows(record.after);
                    if (!unchanged(record.after, current)) throw new IllegalStateException("课程已被再次修改，无法撤回；恢复记录已保留");
                    ArrayList<ContentProviderOperation> ops = buildUndo(record, current);
                    try {
                        requireRecordBackend(record);
                        ContentProviderResult[] results = app.getContentResolver().applyBatch(record.authority, ops);
                        if (results == null || results.length != ops.size())
                            throw new IllegalStateException("宿主未确认恢复事务，恢复记录已保留");
                        applied = true;
                        break;
                    } catch (OperationApplicationException rollback) {
                        // This provider rolls back a non-yield batch completely. Revalidate before retrying.
                        if (attempt >= 2) throw rollback;
                        Logx.w(CourseAlarmEntry.TAG, "撤回事务已回滚，重新核对同步状态 (" + (attempt + 1) + ")");
                    }
                }
                record.state = "UNDO_APPLIED"; save(record);
                if (!restored(record)) throw new IllegalStateException("恢复写入已提交，但结果核对不一致；恢复记录已保留");
                if (record.message.startsWith("已调整")) UI.post(CourseMutations::queueAlarmRefresh);
                if (TableCtx.hostCurrentTableId(app) == record.selectedAfter) select(record.selectedBefore);
                record.state = "UNDONE"; save(record); pending = null;
                CourseSyncGate.release(record.token);
                UI.post(() -> { refresh(); UndoBar.finish(token, "已撤回"); });
            } catch (Exception error) {
                record.state = applied ? "UNDO_NEEDS_REVIEW" : "UNDO_FAILED";
                record.failure = error.getClass().getName() + ": " + rootMessage(error);
                try { save(record); } catch (Exception journalError) { Logx.e(CourseAlarmEntry.TAG, "保存撤回失败记录", journalError); }
                pending = null;
                CourseSyncGate.release(record.token);
                Logx.e(CourseAlarmEntry.TAG, "撤回失败", error);
                UI.post(() -> { refresh(); UndoBar.finish(token, "撤回失败：" + rootMessage(error)); });
            }
        });
    }

    private static ArrayList<ContentProviderOperation> buildUndo(Record record, List<Row> current) throws Exception {
        ArrayList<ContentProviderOperation> ops = assertions(current);
        for (Row row:current) if(!row.absent && row.table.equals("calendars")) {
            Row before=findRow(record.before,row.key());
            if(before==null || before.absent ||
                    Objects.equals(before.values.getAsString("calendars_json_extensions"),row.values.getAsString("calendars_json_extensions"))) continue;
            int count=0;
            for(Row event:rows("events","calendar_id",row.id)) if(number(event.values,"deleted")==0 &&
                    number(event.values,"hasExtendedProperties")==CourseWriter.COURSE_EXT_PROP) {
                if(!contains(record.after,event.key())) throw new IllegalStateException("课表已有新课程，无法撤回作息变更");
                count++;
            }
            ops.add(activeCoursesAssertion(row.id,count));
        }
        // Guard the complete child sets too: another writer must not lose a new course/reminder.
        for (Row row : current) if (!row.absent && row.table.equals("calendars") && !contains(record.before, row.key())) {
            List<Row> events = rows("events", "calendar_id", row.id);
            for (Row event : events) if (!contains(record.after, event.key()))
                throw new IllegalStateException("新课表已有其他课程，无法撤回");
            ops.add(countAssertion("events", "calendar_id", row.id, events.size()));
        }
        for (Row row : current) if (!row.absent && row.table.equals("events")) {
            for (String child : CHILDREN) {
                List<Row> children = rows(child, "event_id", row.id);
                for (Row c : children) if (!contains(record.after, c.key()))
                    throw new IllegalStateException("课程关联数据已变化，无法撤回");
                ops.add(countAssertion(child, "event_id", row.id, children.size()));
            }
        }
        // Parents retained outside this operation must still exist when restoring their children.
        Set<Long> parents = new HashSet<>();
        for (Row row : record.before) if (row.table.equals("events") && !row.absent) {
            long id = number(row.values, "calendar_id");
            if (!contains(record.before, "calendars:" + id) && parents.add(id)) {
                Row parent = read("calendars", id);
                if (parent == null || number(parent.values, "deleted") != 0) throw new IllegalStateException("原课表已删除，无法撤回");
                ops.add(assertion(parent));
            }
        }
        for (int pass = 0; pass < 3; pass++) for (Row row : current) {
            int rank = row.table.equals("calendars") ? 2 : row.table.equals("events") ? 1 : 0;
            if (rank != pass || row.absent || contains(record.before, row.key())) continue;
            // Use native tombstone semantics for synced imports so undo also reaches the cloud.
            Uri deleteUri = row.table.equals("events") || row.table.equals("calendars") ? uri(row.table) : sync(uri(row.table), row.values);
            ops.add(ContentProviderOperation.newDelete(deleteUri)
                    .withSelection("_id=?", args(row.id)).withYieldAllowed(false).build());
        }
        for (int pass = 0; pass < 3; pass++) for (Row row : record.before) {
            int rank = row.table.equals("calendars") ? 0 : row.table.equals("events") ? 1 : 2;
            if (rank != pass || row.absent) continue;
            Row now = findRow(current, row.key());
            boolean insert = now == null || now.absent || removedByUndo(now, record);
            ContentValues values = new ContentValues(row.values);
            // Restore native sync identity, but publish undo as a fresh local mutation.
            if (row.table.equals("events") || row.table.equals("calendars")) {
                values.put("dirty", 1);
                String time = row.table.equals("events") ? "event_update_time" : "calendar_update_time";
                if (values.containsKey(time)) values.put(time, System.currentTimeMillis());
                values.put("mutators", app.getPackageName());
            }
            if (insert) {
                // The native provider treats some nullable columns as optional commands.
                // Omit nulls on insert; on update write only the fields that actually differ.
                for (String key : new ArrayList<>(values.keySet())) if (values.get(key) == null) values.remove(key);
                values.put("_id", row.id);
                ops.add(ContentProviderOperation.newInsert(sync(uri(row.table), values)).withValues(values).withYieldAllowed(false).build());
            } else {
                values.remove("_id");
                for (String key : new ArrayList<>(values.keySet()))
                    if (Objects.equals(values.get(key), now.values.get(key))) values.remove(key);
                // Unchanged reminder/association rows still participate in the assertions,
                // but Android rejects an UPDATE operation with an empty ContentValues.
                if (values.size() == 0) continue;
                ops.add(ContentProviderOperation.newUpdate(sync(uri(row.table), values)).withSelection("_id=?", args(row.id))
                        .withValues(values).withExpectedCount(1).withYieldAllowed(false).build());
            }
        }
        return ops;
    }

    private static boolean restored(Record record) throws Exception {
        for (Row expected : record.before) {
            Row actual = read(expected.table, expected.id);
            if (actual == null || !contentWithoutUpdateTime(expected).equals(contentWithoutUpdateTime(actual))) return false;
        }
        for (Row row : record.after) if (!contains(record.before, row.key()) && !row.absent) {
            Row actual = read(row.table, row.id);
            if (actual != null && !tombstone(actual, Collections.singletonList(actual))) return false;
        }
        return true;
    }
    private static String contentWithoutUpdateTime(Row row) throws JSONException {
        JSONObject content = new JSONObject(comparable(row));
        content.remove("event_update_time"); content.remove("calendar_update_time"); content.remove("mutators");
        return canonical(content).toString();
    }
    private static Row findRow(List<Row> image, String key) {
        for (Row row : image) if (row.key().equals(key)) return row;
        return null;
    }
    private static ContentProviderOperation activeCoursesAssertion(long id,int count) {
        return ContentProviderOperation.newAssertQuery(uri("events"))
                .withSelection("calendar_id=? AND deleted=0 AND hasExtendedProperties=?",
                        new String[]{Long.toString(id), Integer.toString(CourseWriter.COURSE_EXT_PROP)})
                .withExpectedCount(count).withYieldAllowed(false).build();
    }
    private static ContentProviderOperation countAssertion(String table, String foreignKey, long id, int count) {
        return ContentProviderOperation.newAssertQuery(uri(table)).withSelection(foreignKey + "=?", args(id))
                .withExpectedCount(count).withYieldAllowed(false).build();
    }

    private static boolean removedByUndo(Row row, Record record) {
        if (!contains(record.before,row.key()) && contains(record.after,row.key())) return true;
        if (!row.table.equals("calendars")) {
            String parent=row.table.equals("events")?"calendars":"events";
            long id=number(row.values,row.table.equals("events")?"calendar_id":"event_id");
            for(Row candidate:record.after) if(candidate.table.equals(parent)&&candidate.id==id&&!candidate.absent)
                return removedByUndo(candidate,record);
        }
        return false;
    }
    static void expire(String token) {
        WORK.execute(() -> {
            if (pending == null || !pending.token.equals(token)) return;
            try { pending.state = "EXPIRED"; save(pending); } catch (Exception e) { Logx.e(CourseAlarmEntry.TAG, "expire", e); }
            CourseSyncGate.release(token);
            pending = null;
            UI.post(CourseMutations::refresh);
        });
    }
    private static boolean contains(List<Row> rows, String key) { for (Row row : rows) if (row.key().equals(key)) return true; return false; }
    private static void requireBackend() throws Exception {
        backend = CalendarBackend.current(app);
        backend.requireAtomicProvider(app);
        if (!CourseSyncGate.available()) throw new IllegalStateException("此日历版本缺少安全撤回的同步保护，未写入数据");
    }
    private static void requireRecordBackend(Record record) throws Exception {
        requireBackend();
        if (!backend.authority.equals(record.authority))
            throw new IllegalStateException("日历存储已切换，操作已停止；恢复记录已保留");
    }
    private static final String[] CHILDREN = {"reminders", "extendedproperties", "attendees"};
    private static void snapshotEvent(List<Row> out, long id) throws Exception {
        Row row = read("events", id);
        if (row == null) throw new IllegalStateException("课程在提交前已变化");
        out.add(row);
        for (String child : CHILDREN) out.addAll(rows(child, "event_id", id));
    }
    private static ArrayList<ContentProviderOperation> assertions(List<Row> rows) {
        ArrayList<ContentProviderOperation> ops = new ArrayList<>();
        for (Row row : rows) ops.add(assertion(row));
        // Deleting a parent can cascade to rows added after the snapshot. Assert each child set.
        for (Row row : rows) if (!row.absent) {
            if (row.table.equals("events")) for (String child : CHILDREN) {
                int count = 0;
                for (Row c : rows) if (!c.absent && c.table.equals(child) && number(c.values, "event_id") == row.id) count++;
                ops.add(countAssertion(child, "event_id", row.id, count));
            }
            if (row.table.equals("calendars")) {
                int count = 0;
                for (Row event : rows) if (!event.absent && event.table.equals("events") && number(event.values, "calendar_id") == row.id) count++;
                ops.add(countAssertion("events", "calendar_id", row.id, count));
            }
        }
        return ops;
    }
    private static ContentProviderOperation assertion(Row row) {
        ContentProviderOperation.Builder b = ContentProviderOperation.newAssertQuery(uri(row.table))
                .withSelection("_id=?", args(row.id)).withExpectedCount(row.absent ? 0 : 1).withYieldAllowed(false);
        if (!row.absent) b.withValues(row.values);
        return b.build();
    }
    private static boolean unchanged(List<Row> rows) throws Exception {
        return unchanged(rows, currentRows(rows));
    }
    private static boolean unchanged(List<Row> rows, List<Row> actualRows) throws Exception {
        for (Row expected : rows) {
            Row actual = findRow(actualRows, expected.key());
            if (actual != null && actual.absent) actual = null;
            if (actual==null && !expected.absent && tombstone(expected,rows)) continue;
            if (expected.absent ? actual != null : actual == null || !comparable(expected).equals(comparable(actual))) {
                if(actual!=null){JSONObject a=new JSONObject(comparable(expected)),b=new JSONObject(comparable(actual));Set<String> keys=new TreeSet<>();a.keys().forEachRemaining(keys::add);b.keys().forEachRemaining(keys::add);keys.removeIf(k->Objects.equals(a.opt(k),b.opt(k)));Logx.w(CourseAlarmEntry.TAG,"撤回冲突字段 "+expected.key()+" "+keys);}
                return false;
            }
        }
        return true;
    }
    private static boolean tombstone(Row row,List<Row> image) {
        if ((row.table.equals("events") || row.table.equals("calendars")) && number(row.values,"deleted")!=0) return true;
        if (!row.table.equals("calendars")) {
            String parent=row.table.equals("events")?"calendars":"events";
            long id=number(row.values,row.table.equals("events")?"calendar_id":"event_id");
            for(Row p:image)if(p.table.equals(parent)&&p.id==id) return tombstone(p,image);
        }
        return false;
    }
    // Native cloud sync assigns these transport fields without changing course content.
    // Re-read them for the transactional assertion; never ignore user-facing fields.
    private static String comparable(Row row) throws JSONException {
        JSONObject object = row.jsonValues();
        for (String key : new String[]{"_sync_id", "dirty", "lastSynced"}) object.remove(key);
        for (String key : new String[]{"events_json_extensions", "calendars_json_extensions"}) {
            String raw = object.optString(key, "");
            if (raw.startsWith("{")) {
                JSONObject json = new JSONObject(raw); json.remove("sysVersion");
                object.put(key, canonical(json));
            }
        }
        return canonical(object).toString();
    }
    private static JSONObject canonical(JSONObject value) throws JSONException {
        JSONObject sorted = new JSONObject(); java.util.TreeSet<String> keys = new java.util.TreeSet<>();
        value.keys().forEachRemaining(keys::add);
        for (String key : keys) { Object item = value.get(key); sorted.put(key, item instanceof JSONObject ? canonical((JSONObject)item) : item); }
        return sorted;
    }
    private static List<Row> currentRows(List<Row> expected) throws Exception {
        List<Row> actual = new ArrayList<>();
        for (Row row : expected) { Row now=read(row.table,row.id); actual.add(now==null?Row.absent(row.table,row.id):now); }
        return actual;
    }
    private static Row read(String table, long id) throws Exception {
        for (Row row : rows(table, "_id", id)) return row;
        return null;
    }
    private static List<Row> rows(String table, String filter, long value) throws Exception {
        List<Row> out = new ArrayList<>();
        Set<String> columns = table.equals("events") || table.equals("calendars")
                ? HostEntities.writableColumns(app, table.equals("events") ? "Event" : "Calendar")
                : new LinkedHashSet<>(Arrays.asList(childColumns(table)));
        try (Cursor cursor = app.getContentResolver().query(uri(table), null, null, null, "_id ASC")) {
            if (cursor == null) throw new IllegalStateException("无法读取 " + table);
            while (cursor.moveToNext()) {
                if (filter != null && cursor.getLong(cursor.getColumnIndexOrThrow(filter)) != value) continue;
                long id = cursor.getLong(cursor.getColumnIndexOrThrow("_id"));
                ContentValues values = new ContentValues();
                for (String key : columns) {
                    int i = cursor.getColumnIndex(key);
                    if (i < 0 || key.equals("_id")) continue;
                    if (cursor.isNull(i)) values.putNull(key);
                    else if (cursor.getType(i) == Cursor.FIELD_TYPE_INTEGER) values.put(key, cursor.getLong(i));
                    else if (cursor.getType(i) == Cursor.FIELD_TYPE_FLOAT) values.put(key, cursor.getDouble(i));
                    else values.put(key, cursor.getString(i));
                }
                out.add(new Row(table, id, values, false));
            }
        }
        return out;
    }
    private static String[] childColumns(String table) {
        if (table.equals("reminders")) return new String[]{"event_id", "minutes", "method"};
        if (table.equals("extendedproperties")) return new String[]{"event_id", "name", "value"};
        return new String[]{"event_id", "attendeeName", "attendeeEmail", "attendeeRelationship", "attendeeType", "attendeeStatus", "attendeeIdentity", "attendeeIdNamespace"};
    }
    private static Uri uri(String table) {
        if (backend == null) throw new IllegalStateException("日历存储尚未固定");
        return backend.uri(table);
    }
    private static Uri sync(Uri uri, ContentValues values) {
        return uri.buildUpon().appendQueryParameter("caller_is_syncadapter", "true")
                .appendQueryParameter("account_name", Optional.ofNullable(values.getAsString("account_name")).orElse("oplus_course_default_calendar"))
                .appendQueryParameter("account_type", Optional.ofNullable(values.getAsString("account_type")).orElse("LOCAL")).build();
    }
    private static boolean isCourseCalendar(ContentValues values) {
        String name = values.getAsString("account_name");
        return name != null && (name.contains("course") || name.contains("kebiao")) && number(values, "deleted") == 0;
    }
    private static long number(ContentValues values, String key) { Long n = values.getAsLong(key); return n == null ? 0 : n; }
    private static String[] args(long id) { return new String[]{Long.toString(id)}; }
    private static void select(long id) { app.getSharedPreferences("com.coloros.calendar_preferences", Context.MODE_PRIVATE).edit().putLong("sp_key_current_course_table", id).commit(); }
    private static void refresh() {
        HostBus.notifyUris(app);
        for (String key : new String[]{"refresh_course_table", "db_change_event", "db_change_calendar"}) HostBus.post(app.getClassLoader(), key);
        HostTablePage.refresh();
    }
    private static void queueAlarmRefresh() {
        NightJob.onTableChanged();
    }
    private static String rootMessage(Throwable error) { while (error.getCause() != null) error = error.getCause(); return String.valueOf(error.getMessage()); }
    private static String rescheduleError(Throwable error) {
        while (error.getCause() != null) error = error.getCause();
        String message = error.getMessage();
        StackTraceElement[] trace = error.getStackTrace();
        if (message != null && trace.length > 0 && trace[0].getClassName().startsWith("com.lcodexs.coursealarm.")
                && message.codePoints().anyMatch(c -> c >= 0x4e00 && c <= 0x9fff)) return message;
        return "未能确认调课保存结果，请返回课表检查当前安排。";
    }
    private static File journalDir() { File dir = new File(app.getFilesDir(), "coursealarm-recovery"); dir.mkdirs(); return dir; }
    private static void save(Record record) throws Exception { saveJson(new File(journalDir(), record.token + ".json"), record.json()); }
    private static void saveJson(File path, JSONObject json) throws Exception {
        AtomicFile file = new AtomicFile(path);
        FileOutputStream out = null;
        try { out = file.startWrite(); out.write(json.toString().getBytes(StandardCharsets.UTF_8)); file.finishWrite(out); }
        catch (Exception error) { if (out != null) file.failWrite(out); throw error; }
    }
    private static final class Row {
        final String table; final long id; final ContentValues values; final boolean absent;
        Row(String table, long id, ContentValues values, boolean absent) { this.table = table; this.id = id; this.values = values; this.absent = absent; }
        static Row absent(String table, long id) { return new Row(table, id, new ContentValues(), true); }
        String key() { return table + ":" + id; }
        JSONObject jsonValues() throws JSONException {
            JSONObject obj = new JSONObject();
            for (String key : new TreeSet<>(values.keySet())) obj.put(key, values.get(key) == null ? JSONObject.NULL : values.get(key));
            return obj;
        }
        JSONObject json() throws JSONException { return new JSONObject().put("table", table).put("id", id).put("absent", absent).put("values", jsonValues()); }
        static Row parse(JSONObject json) throws JSONException {
            JSONObject object = json.getJSONObject("values"); ContentValues values = new ContentValues();
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) { String key = keys.next(); Object value = object.get(key);
                if (value == JSONObject.NULL) values.putNull(key); else if (value instanceof Number) values.put(key, ((Number) value).longValue()); else values.put(key, String.valueOf(value)); }
            return new Row(json.getString("table"), json.getLong("id"), values, json.optBoolean("absent"));
        }
    }
    private static final class Record {
        String authority = backend == null ? CalendarBackend.MERGED : backend.authority;
        String token = UUID.randomUUID().toString(); String state; String message; String failure; long calendarId;
        volatile long deadline;
        long selectedBefore = TableCtx.hostCurrentTableId(app), selectedAfter;
        final List<Row> before = new ArrayList<>(), after = new ArrayList<>(), inserted = new ArrayList<>();
        Record(String message, long calendarId) { this.message = message; this.calendarId = calendarId; }
        JSONObject json() throws JSONException {
            JSONArray a = new JSONArray(), b = new JSONArray(), c = new JSONArray(); for (Row row : before) a.put(row.json()); for (Row row : after) b.put(row.json()); for (Row row : inserted) c.put(row.json());
            return new JSONObject().put("version", 2).put("authority", authority).put("token", token).put("state", state).put("message", message).put("calendarId", calendarId)
                    .put("selectedBefore", selectedBefore).put("selectedAfter", selectedAfter).put("before", a).put("after", b).put("inserted", c)
                    .put("failure", failure);
        }
        static Record parse(JSONObject json) throws JSONException {
            Record r = new Record(json.optString("message"), json.optLong("calendarId")); r.token = json.getString("token"); r.state = json.getString("state");
            r.authority = json.optString("authority", CalendarBackend.MERGED);
            r.selectedBefore = json.optLong("selectedBefore", -1); r.selectedAfter = json.optLong("selectedAfter", -1);
            r.failure = json.optString("failure", null);
            for (int i = 0; i < json.getJSONArray("before").length(); i++) r.before.add(Row.parse(json.getJSONArray("before").getJSONObject(i)));
            for (int i = 0; i < json.getJSONArray("after").length(); i++) r.after.add(Row.parse(json.getJSONArray("after").getJSONObject(i)));
            JSONArray inserted = json.optJSONArray("inserted");
            if (inserted != null) for (int i = 0; i < inserted.length(); i++) r.inserted.add(Row.parse(inserted.getJSONObject(i)));
            return r;
        }
    }
}
