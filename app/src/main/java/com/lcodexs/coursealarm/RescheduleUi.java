package com.lcodexs.coursealarm;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import org.json.JSONObject;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * WakeUp 6.7.2's rescheduling content, presented with calendar-owned page/COUI widgets.
 * Source audit: activity_edit_week_course, activity_edit_day_course, item_edit_day_course,
 * custom_edit_day_course_week_day_picker, custom_edit_day_course_date_picker and their controllers.
 * All editing is a draft. Only the single mutation command passed to CourseMutations writes data.
 */
final class RescheduleUi {
    private static final Map<Activity, Flow> ACTIVE = new WeakHashMap<>();
    private static final String[] DAYS = {"星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"};
    private static final String[] SCOPES = {"针对本周课程生效", "针对以后全部课程生效", "跨周调课", "按天调课"};
    private RescheduleUi() { }

    static void show(Activity activity, DragSession session, int targetDay, int targetStart, Runnable onDismiss) {
        DismissOnce completion = new DismissOnce(onDismiss);
        Flow flow = null;
        try {
            Flow old = ACTIVE.get(activity);
            if (old != null) old.finish();
            flow = new Flow(activity, session, targetDay, targetStart, completion);
            ACTIVE.put(activity, flow);
            flow.start();
        } catch (Exception error) {
            if (flow != null) flow.unavailable(error);
            else {
                try { reportUnavailable(activity, error); }
                finally { completion.run(); }
            }
        }
    }
    static void dismiss(Activity activity) {
        Flow flow = ACTIVE.get(activity); if (flow != null) flow.finish();
    }
    private static final class DismissOnce implements Runnable {
        final Runnable callback;
        boolean delivered;
        DismissOnce(Runnable callback) { this.callback = callback; }
        @Override public void run() {
            if (delivered) return;
            delivered = true;
            if (callback != null) try { callback.run(); }
            catch (Exception error) { Logx.e(CourseAlarmEntry.TAG, "释放调课预览", error); }
        }
    }
    private static void reportUnavailable(Activity activity, Exception error) {
        Logx.e(CourseAlarmEntry.TAG, "调课原生界面", error);
        if (activity != null && !activity.isFinishing() && !activity.isDestroyed()) {
            try { CurriculumIo.toast(activity, "无法打开调课界面，请返回重试"); }
            catch (Exception ignored) { }
        }
    }

    private static final class Flow implements Application.ActivityLifecycleCallbacks {
        final Activity activity;
        final DragSession session;
        final RescheduleNativeUi ui;
        final Runnable onDismiss;
        final int dropDay, dropStart;
        final BitSet weeks;
        final Set<Long> selected = new LinkedHashSet<>();
        final List<View> scopeRows = new ArrayList<>();
        final List<Dialog> children = new ArrayList<>();
        int day, start, end, sourceWeek, sourceDay, targetWeek, targetDay;
        boolean finished, busy, registered;
        Dialog main;
        RescheduleNativeUi.Page page;
        RescheduleNativeUi.Row weeksRow, dayRow, startRow, endRow, sourceWeekRow, sourceDateRow, targetWeekRow, targetDateRow;
        TextView weekCount, dayCount, clearWeeks, allWeeks;
        View allCourses;
        LinearLayout courseList;
        final Map<Integer, TextView> weekButtons = new LinkedHashMap<>();
        final Map<Long, View> courseChecks = new LinkedHashMap<>();
        final LocalDate termStart;

        Flow(Activity activity, DragSession session, int targetDay, int targetStart, Runnable onDismiss) {
            this.activity = activity; this.session = session; this.ui = new RescheduleNativeUi(activity);
            this.onDismiss = onDismiss; this.dropDay = targetDay; this.dropStart = targetStart;
            this.day = targetDay; this.start = targetStart;
            this.end = targetStart + session.source.course.endNode - session.source.course.startNode;
            this.weeks = (BitSet) session.source.course.weeks.clone();
            sourceWeek = session.displayedWeek; sourceDay = session.source.course.day;
            this.targetWeek = session.displayedWeek; this.targetDay = targetDay;
            termStart = session.table.weekMonday();
        }
        void start() throws Exception {
            if (activity.isFinishing() || activity.isDestroyed()) { finish(); return; }
            activity.getApplication().registerActivityLifecycleCallbacks(this); registered = true;
            LinearLayout actions = ui.column();
            for (int i = 0; i < SCOPES.length; i++) {
                final int index = i;
                RescheduleNativeUi.Row row = ui.row(SCOPES[i], "", () -> chooseScope(index));
                actions.addView(row.view, new LinearLayout.LayoutParams(-1, -2)); scopeRows.add(row.view);
            }
            main = ui.sheet("此更改应用范围？", actions, "取消", this::finish, this::mainDismissed);
        }
        void chooseScope(int index) {
            if (busy || finished) return;
            try {
                switch (index) {
                    case 0 -> save(RescheduleRequest.range(session, RescheduleRequest.Scope.CURRENT_WEEK,
                            dropDay, dropStart, end, null));
                    case 1 -> save(RescheduleRequest.range(session, RescheduleRequest.Scope.FUTURE_WEEKS,
                            dropDay, dropStart, end, null));
                    case 2 -> { transition(); openWeekPage(); }
                    case 3 -> { transition(); openDayPage(); }
                    default -> throw new IllegalArgumentException("未知调课范围");
                }
            } catch (Exception error) { unavailable(error); }
        }
        void transition() {
            // Dialog dispatches its dismiss callback asynchronously. Detach the old page's
            // listener before the transition so it cannot close the newly opened editor.
            if (main != null) { main.setOnDismissListener(null); main.dismiss(); }
            main = null;
        }
        void mainDismissed() { finish(); }
        void openWeekPage() throws Exception {
            page = ui.page("选择要调整的周次", () -> {
                if (weeks.isEmpty()) { error("请先选择周"); return; }
                if (start > end) { error("开始节次必须小于结束节次"); return; }
                save(RescheduleRequest.range(session, RescheduleRequest.Scope.CUSTOM_WEEKS, day, start, end, weeks));
            }, this::mainDismissed); main = page.dialog;
            ScrollView scroll = new ScrollView(ui.context); scroll.setFillViewport(false); scroll.setClipToPadding(false);
            LinearLayout content = ui.column(); content.setPadding(0, ui.space(8), 0, ui.space(16)); scroll.addView(content);
            page.body.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
            sourceCard(content);
            LinearLayout controls = ui.line(); controls.setPadding(ui.space(32), ui.space(4), ui.space(24), 0);
            weekCount = ui.text("", 14, true); controls.addView(weekCount, new LinearLayout.LayoutParams(0, -2, 1));
            clearWeeks = ui.action("清空", () -> { weeks.clear(); updateWeeks(); }); controls.addView(clearWeeks);
            allWeeks = ui.action("全选", () -> { weeks.set(1, session.table.weekCount + 1); updateWeeks(); }); controls.addView(allWeeks);
            content.addView(controls);
            LinearLayout gridCard = ui.card(); GridLayout grid = new GridLayout(ui.context); grid.setColumnCount(5);
            grid.setPadding(ui.space(8), ui.space(8), ui.space(8), ui.space(8));
            int selector = ui.id("drawable", "bg_course_day_selector");
            if (selector == 0) throw new IllegalStateException("宿主周次选择样式不可用");
            for (int week = 1; week <= session.table.weekCount; week++) {
                final int number = week;
                TextView cell = ui.text(Integer.toString(week), 16, false); cell.setGravity(Gravity.CENTER);
                cell.setBackgroundResource(selector); cell.setFocusable(true); cell.setClickable(true);
                cell.setOnClickListener(v -> { if (!busy) { weeks.flip(number); updateWeeks(); } });
                // The calendar's selection background is circular. Stretching it to a
                // weighted grid cell made each week an oversized oval on wide phones.
                FrameLayout slot = new FrameLayout(ui.context);
                slot.addView(cell, new FrameLayout.LayoutParams(ui.space(44), ui.space(44), Gravity.CENTER));
                GridLayout.LayoutParams lp = new GridLayout.LayoutParams(GridLayout.spec((week - 1) / 5), GridLayout.spec((week - 1) % 5, 1f));
                lp.width = 0; lp.height = ui.space(56);
                grid.addView(slot, lp); weekButtons.put(week, cell);
            }
            gridCard.addView(grid); content.addView(gridCard);
            TextView hint = ui.text("同时调整多个周次到相同的新时间", 13, true);
            hint.setPadding(ui.space(32), ui.space(4), ui.space(32), ui.space(8)); content.addView(hint);
            ui.heading(content, "新上课时间");
            LinearLayout times = ui.card();
            weeksRow = ui.row("周次", "", null); times.addView(weeksRow.view);
            dayRow = ui.row("星期", DAYS[day], () -> pickDay()); times.addView(dayRow.view);
            startRow = ui.row("开始节次", node(start), () -> pickNode(true)); times.addView(startRow.view);
            endRow = ui.row("结束节次", node(end), () -> pickNode(false)); times.addView(endRow.view);
            content.addView(times); updateWeeks();
        }
        void sourceCard(LinearLayout parent) throws Exception {
            Course course = session.source.course; JSONObject json = new JSONObject(session.source.json);
            LinearLayout card = ui.card(); card.setPadding(ui.space(16), ui.space(16), ui.space(16), ui.space(16));
            LinearLayout name = ui.line();
            View color = new View(ui.context); color.setBackgroundColor(ui.courseColor(course.color));
            LinearLayout.LayoutParams stripe = new LinearLayout.LayoutParams(ui.space(4), ui.space(20)); stripe.setMarginEnd(ui.space(12)); name.addView(color, stripe);
            TextView title = ui.text(course.name, 16, false); name.addView(title, new LinearLayout.LayoutParams(0, -2, 1)); card.addView(name);
            detail(card, weekText(course.weeks));
            String time = DAYS[course.day] + " 第" + (course.startNode + 1) + "-" + (course.endNode + 1) + "节";
            long first = json.optLong("startTime"), last = json.optLong("endTime");
            if (first > 0 && last > 0) time += " " + clock(first) + "-" + clock(last);
            detail(card, time); optionalDetail(card, course.room); optionalDetail(card, course.teacher);
            optionalDetail(card, json.optString("note", ""));
            double credit = json.optDouble("credit", 0); if (credit > 0) detail(card, credit + "学分");
            parent.addView(card);
        }
        void optionalDetail(LinearLayout parent, String value) { if (value != null && !value.trim().isEmpty()) detail(parent, value); }
        void detail(LinearLayout parent, String value) {
            TextView text = ui.text(value, 14, true); text.setPaddingRelative(ui.space(16), ui.space(4), 0, 0); parent.addView(text);
        }
        void updateWeeks() {
            weekCount.setText("已选 " + weeks.cardinality() + " 周");
            clearWeeks.setEnabled(!busy && !weeks.isEmpty()); allWeeks.setEnabled(!busy && weeks.cardinality() != session.table.weekCount);
            for (Map.Entry<Integer, TextView> entry : weekButtons.entrySet()) {
                boolean on = weeks.get(entry.getKey()); TextView view = entry.getValue();
                view.setSelected(on); view.setTextColor(on ? ui.attrColor("couiColorOnPrimary", 0xffffffff) : ui.foreground);
                view.setContentDescription("第" + entry.getKey() + "周" + (on ? "，已选" : "，未选"));
            }
            weeksRow.value(weekText(weeks));
        }
        void pickDay() {
            if (busy || finished) return;
            picker("星期", DAYS, day, value -> { day = value; dayRow.value(DAYS[day]); });
        }
        void pickNode(boolean beginning) {
            if (busy || finished) return;
            // WakeUp's end selector starts at the selected start; its start selector spans all nodes.
            int minimum = beginning ? 0 : start;
            String[] labels = new String[session.table.nodeCount() - minimum];
            for (int i = 0; i < labels.length; i++) labels[i] = node(minimum + i);
            picker(beginning ? "开始节次" : "结束节次", labels,
                    Math.max(0, Math.min(labels.length - 1, (beginning ? start : end) - minimum)), value -> {
                        if (beginning) { start = minimum + value; startRow.value(node(start)); }
                        else { end = minimum + value; endRow.value(node(end)); }
                    });
        }
        void openDayPage() throws Exception {
            page = ui.page("按天调课", () -> {
                if (selected.isEmpty()) { error("请选择课程"); return; }
                save(RescheduleRequest.day(session, sourceWeek, sourceDay, targetWeek, targetDay, selected));
            }, this::mainDismissed); main = page.dialog;
            ScrollView scroll = new ScrollView(ui.context); scroll.setFillViewport(true); scroll.setClipToPadding(false);
            LinearLayout content = ui.column(); content.setPadding(0, ui.space(8), 0, ui.space(16)); scroll.addView(content);
            page.body.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
            ui.heading(content, "调整的上课时间");
            LinearLayout source = ui.card();
            sourceWeekRow = ui.row("周次", dayText(sourceWeek, sourceDay), () -> pickWeekDay(true)); source.addView(sourceWeekRow.view);
            sourceDateRow = ui.row("时间", dateText(sourceWeek, sourceDay), () -> pickDate(true)); source.addView(sourceDateRow.view);
            content.addView(source);
            LinearLayout listHead = ui.line(); listHead.setPadding(ui.space(32), 0, ui.space(32), 0);
            dayCount = ui.text("", 14, true); listHead.addView(dayCount, new LinearLayout.LayoutParams(0, ui.dp(48), 1));
            LinearLayout all = ui.line(); allCourses = ui.check(true); all.addView(allCourses, new LinearLayout.LayoutParams(ui.dp(32), ui.dp(40)));
            all.addView(ui.text("全选", 14, true)); all.setMinimumHeight(ui.dp(48)); all.setFocusable(true);
            all.setOnClickListener(v -> {
                if (busy) return;
                List<DragSession.Entry> entries = session.dayCourses(sourceWeek, sourceDay);
                if (selected.size() == entries.size()) selected.clear();
                else { selected.clear(); for (DragSession.Entry entry : entries) selected.add(entry.eventId); }
                updateDayChecks();
            }); listHead.addView(all); content.addView(listHead);
            courseList = ui.column(); content.addView(courseList, new LinearLayout.LayoutParams(-1, -2));
            ui.heading(content, "新上课时间");
            LinearLayout target = ui.card();
            targetWeekRow = ui.row("周次", dayText(targetWeek, targetDay), () -> pickWeekDay(false)); target.addView(targetWeekRow.view);
            targetDateRow = ui.row("时间", dateText(targetWeek, targetDay), () -> pickDate(false)); target.addView(targetDateRow.view);
            content.addView(target); loadDay();
        }
        void loadDay() throws Exception {
            // The snapshot is fixed at drag start. Saving revalidates it on the serial writer.
            courseList.removeAllViews(); courseChecks.clear(); selected.clear();
            List<DragSession.Entry> entries = session.dayCourses(sourceWeek, sourceDay);
            if (entries.isEmpty()) {
                TextView empty = ui.text("当天无课程", 16, true); empty.setGravity(Gravity.CENTER);
                courseList.addView(empty, new LinearLayout.LayoutParams(-1, ui.dp(128)));
            }
            for (DragSession.Entry entry : entries) {
                selected.add(entry.eventId); Course course = entry.course;
                JSONObject json = new JSONObject(entry.json); LinearLayout card = ui.card();
                LinearLayout row = ui.line(); row.setPadding(ui.dp(12), ui.dp(14), ui.dp(12), ui.dp(14));
                LinearLayout times = ui.column(); times.setGravity(Gravity.CENTER);
                long from = json.optLong("startTime"), to = json.optLong("endTime");
                times.addView(ui.text(from > 0 ? clock(from) : node(course.startNode), 14, false));
                TextView separator = ui.text("│", 12, true); separator.setGravity(Gravity.CENTER); times.addView(separator);
                times.addView(ui.text(to > 0 ? clock(to) : node(course.endNode), 14, true));
                row.addView(times, new LinearLayout.LayoutParams(ui.dp(66), -2));
                View stripe = new View(ui.context); stripe.setBackgroundColor(ui.courseColor(course.color));
                LinearLayout.LayoutParams stripeParams = new LinearLayout.LayoutParams(ui.dp(3), ui.dp(56));
                stripeParams.setMarginEnd(ui.dp(12)); row.addView(stripe, stripeParams);
                LinearLayout info = ui.column(); info.addView(ui.text(course.name, 16, false));
                LinearLayout metadata = ui.column();
                if (course.room != null && !course.room.trim().isEmpty()) {
                    TextView room = ui.text(course.room, 13, true); room.setPadding(0, ui.dp(6), ui.dp(12), 0); metadata.addView(room);
                }
                if (course.teacher != null && !course.teacher.trim().isEmpty()) {
                    TextView teacher = ui.text(course.teacher, 13, true); teacher.setPadding(0, ui.dp(6), 0, 0); metadata.addView(teacher);
                }
                info.addView(metadata); row.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
                View checkbox = ui.check(true); row.addView(checkbox, new LinearLayout.LayoutParams(ui.dp(40), ui.dp(48)));
                courseChecks.put(entry.eventId, checkbox); card.addView(row); card.setFocusable(true);
                card.setContentDescription(course.name + "，" + node(course.startNode) + "至" + node(course.endNode));
                card.setOnClickListener(v -> {
                    if (busy) return;
                    if (!selected.add(entry.eventId)) selected.remove(entry.eventId);
                    updateDayChecks();
                }); courseList.addView(card);
            }
            page.save.setEnabled(!entries.isEmpty() && !busy); updateDayChecks();
        }
        void updateDayChecks() {
            dayCount.setText("当天共 " + selected.size() + " 节课");
            ui.checked(allCourses, !courseChecks.isEmpty() && selected.size() == courseChecks.size());
            for (Map.Entry<Long, View> entry : courseChecks.entrySet()) ui.checked(entry.getValue(), selected.contains(entry.getKey()));
        }
        void pickWeekDay(boolean source) {
            if (busy || finished) return;
            try {
                String[] weekLabels = new String[session.table.weekCount];
                for (int i = 0; i < weekLabels.length; i++) weekLabels[i] = "第" + (i + 1) + "周";
                View week = ui.wheel(weekLabels, (source ? sourceWeek : targetWeek) - 1);
                View day = ui.wheel(DAYS, source ? sourceDay : targetDay);
                LinearLayout row = ui.line(); row.addView(week, new LinearLayout.LayoutParams(0, ui.dim("coui_numberpicker_height", 207), 1));
                row.addView(day, new LinearLayout.LayoutParams(0, ui.dim("coui_numberpicker_height", 207), 1));
                LinearLayout content = ui.column(); content.addView(row); final Dialog[] picker = new Dialog[1];
                picker[0] = ui.sheet("周次和星期", content, "保存", () -> {
                    try { setDate(source, ui.wheelValue(week) + 1, ui.wheelValue(day)); picker[0].dismiss(); }
                    catch (IllegalArgumentException invalid) { error(invalid.getMessage()); }
                    catch (Exception e) { unavailable(e); }
                }, () -> children.remove(picker[0])); children.add(picker[0]);
            } catch (Exception error) { unavailable(error); }
        }
        void pickDate(boolean source) {
            if (busy || finished) return;
            try {
                LocalDate date = date(source ? sourceWeek : targetWeek, source ? sourceDay : targetDay);
                if (date.isBefore(session.table.termDate())) date = session.table.termDate();
                LocalDate last = termStart.plusDays(session.table.weekCount * 7L - 1);
                View pickerView = ui.nativeView("picker.COUIDatePicker");
                RescheduleNativeUi.call(pickerView, "setMinDate", midnight(session.table.termDate()));
                RescheduleNativeUi.call(pickerView, "setMaxDate", midnight(last));
                RescheduleNativeUi.call(pickerView, "init", date.getYear(), date.getMonthValue() - 1, date.getDayOfMonth(), null);
                LinearLayout content = ui.column(); content.addView(pickerView, new LinearLayout.LayoutParams(-1, -2));
                final Dialog[] picker = new Dialog[1];
                picker[0] = ui.sheet("日期", content, "保存", () -> {
                    try {
                        LocalDate value = LocalDate.of(((Number) RescheduleNativeUi.call(pickerView, "getYear")).intValue(),
                                ((Number) RescheduleNativeUi.call(pickerView, "getMonth")).intValue() + 1,
                                ((Number) RescheduleNativeUi.call(pickerView, "getDayOfMonth")).intValue());
                        long offset = ChronoUnit.DAYS.between(termStart, value);
                        if (offset < 0 || offset >= session.table.weekCount * 7L) { error("日期范围无效，请重试"); return; }
                        setDate(source, (int) (offset / 7) + 1, (int) (offset % 7)); picker[0].dismiss();
                    } catch (IllegalArgumentException invalid) { error(invalid.getMessage()); }
                    catch (Exception e) { unavailable(e); }
                }, () -> children.remove(picker[0])); children.add(picker[0]);
            } catch (Exception error) { unavailable(error); }
        }
        void setDate(boolean source, int week, int day) throws Exception {
            if (finished || busy) return;
            session.table.requireTeachingDate(week, day);
            if (source) {
                sourceWeek = week; sourceDay = day; sourceWeekRow.value(dayText(week, day)); sourceDateRow.value(dateText(week, day)); loadDay();
            } else {
                targetWeek = week; targetDay = day; targetWeekRow.value(dayText(week, day)); targetDateRow.value(dateText(week, day));
            }
        }
        interface Pick { void value(int index); }
        void picker(String title, String[] labels, int selected, Pick picked) {
            if (busy || finished) return;
            try {
                View wheel = ui.wheel(labels, selected); LinearLayout content = ui.column();
                content.addView(wheel, new LinearLayout.LayoutParams(-1, ui.dim("coui_numberpicker_height", 207))); final Dialog[] picker = new Dialog[1];
                picker[0] = ui.sheet(title, content, "保存", () -> {
                    try { if (!finished && !busy) picked.value(ui.wheelValue(wheel)); picker[0].dismiss(); }
                    catch (Exception error) { unavailable(error); }
                }, () -> children.remove(picker[0])); children.add(picker[0]);
            } catch (Exception error) { unavailable(error); }
        }
        void save(RescheduleRequest request) {
            if (busy || finished) return;
            if (TableCtx.hostCurrentTableId(activity) != session.calendarId) { error("当前课表已切换，请重新调课"); return; }
            try {
                setBusy(true);
                CourseMutations.reschedule(activity, request, result -> {
                    if (finished || activity.isDestroyed()) return;
                    try {
                        if (result.success) finish();
                        else { setBusy(false); error(result.message); }
                    } catch (Exception failure) { unavailable(failure); }
                });
            } catch (Exception failure) { unavailable(failure); }
        }
        void setBusy(boolean value) {
            busy = value; if (main != null) { main.setCancelable(!value); main.setCanceledOnTouchOutside(!value); }
            if (main != null && main.getWindow() != null) disableTree(main.getWindow().getDecorView(), value);
            for (View row : scopeRows) row.setEnabled(!value);
            if (page != null) {
                page.save.setEnabled(!value && (courseList == null || !courseChecks.isEmpty()));
                if (!value && weeksRow != null) updateWeeks();
            }
        }
        void disableTree(View view, boolean disabled) {
            view.setEnabled(!disabled);
            if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) disableTree(group.getChildAt(i), disabled);
        }
        void error(String message) {
            if (finished || activity.isFinishing() || activity.isDestroyed()) return;
            try {
                String detail = message == null || message.trim().isEmpty() ? "无法完成调课，请返回重试" : message;
                final Dialog[] error = new Dialog[1];
                error[0] = ui.error(detail.contains("冲突") ? "课程冲突" : "无法调课", detail, () -> children.remove(error[0]));
                children.add(error[0]);
            } catch (Exception exception) { unavailable(exception); }
        }
        void unavailable(Exception error) {
            try { reportUnavailable(activity, error); }
            finally { finish(); }
        }
        void finish() {
            if (finished) return; finished = true;
            for (Dialog child : new ArrayList<>(children)) try { child.dismiss(); } catch (Exception ignored) { }
            children.clear(); if (main != null) try { main.dismiss(); } catch (Exception ignored) { }
            try {
                if (registered) activity.getApplication().unregisterActivityLifecycleCallbacks(this);
            } catch (Exception error) { Logx.e(CourseAlarmEntry.TAG, "释放调课页面监听", error); }
            finally {
                registered = false;
                if (ACTIVE.get(activity) == this) ACTIVE.remove(activity);
                if (onDismiss != null) onDismiss.run();
            }
        }
        LocalDate date(int week, int day) { return termStart.plusDays((week - 1) * 7L + day); }
        String dateText(int week, int day) { LocalDate value = date(week, day); return value.getYear() + "年" + value.getMonthValue() + "月" + value.getDayOfMonth() + "号"; }
        String dayText(int week, int day) { return "第" + week + "周 " + DAYS[day]; }
        static String node(int zeroBased) { return "第" + (zeroBased + 1) + "节"; }
        static long midnight(LocalDate date) { return date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(); }
        static String clock(long millis) { int minute = TableCtx.minuteOfDay(millis); return String.format(Locale.ROOT, "%02d:%02d", minute / 60, minute % 60); }
        static String weekText(BitSet weeks) {
            StringBuilder out = new StringBuilder();
            for (int first = weeks.nextSetBit(1); first >= 0; ) {
                int end = weeks.nextClearBit(first) - 1;
                if (out.length() > 0) out.append(','); out.append('第').append(first).append('-').append(end).append('周');
                first = weeks.nextSetBit(end + 1);
            }
            return out.toString();
        }
        @Override public void onActivityDestroyed(Activity a) { if (a == activity) finish(); }
        @Override public void onActivityStopped(Activity a) { if (a == activity) finish(); }
        @Override public void onActivityCreated(Activity a, Bundle b) { }
        @Override public void onActivityStarted(Activity a) { }
        @Override public void onActivityResumed(Activity a) { }
        @Override public void onActivityPaused(Activity a) { }
        @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
    }
}
