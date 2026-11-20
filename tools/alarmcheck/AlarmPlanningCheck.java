package com.lcodexs.coursealarm;

import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;

/** Run on a local JVM with the module's compiled classes and Android SDK android.jar. */
public final class AlarmPlanningCheck {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final AlarmPeriod AM = AlarmPeriod.MORNING;
    private static final AlarmPeriod PM = AlarmPeriod.AFTERNOON;

    public static void main(String[] args) throws Exception {
        checkSessions();
        checkDates();
        checkSettings();
        System.out.println("AlarmPlanningCheck passed");
    }

    private static void checkSessions() {
        // A timetable with afternoon sessions that start before noon, and an evening session.
        int[] starts = {485, 550, 710, 875, 1080};
        int[] ends = {535, 600, 760, 925, 1130};
        check(AlarmPeriod.at(550, starts, ends, 2, 2) == AM, "morning");
        check(AlarmPeriod.at(710, starts, ends, 2, 2) == PM, "native afternoon before noon");
        check(AlarmPeriod.at(875, starts, ends, 2, 2) == PM, "later afternoon");
        check(AlarmPeriod.at(1080, starts, ends, 2, 2) == null, "evening excluded");
        check(AlarmPeriod.at(1080, starts, ends, 2, 0) == null, "timetable with no afternoon");
        check(AlarmPeriod.at(875, starts, ends, -1, 0) == null, "unknown timings do not guess afternoon");
        var courses = new CourseRepo.Sessions();
        courses.offer(1, PM, new CourseRepo.Slot(at(21, 14, 35).toInstant(), "later", ""));
        courses.offer(1, AM, new CourseRepo.Slot(at(21, 8, 5).toInstant(), "morning", ""));
        courses.offer(1, PM, new CourseRepo.Slot(at(21, 11, 50).toInstant(), "first", ""));
        check("first".equals(courses.first(1, PM).title()), "earliest afternoon independent of result ordering");
        check("morning".equals(courses.first(1, AM).title()), "earliest morning kept separately");
        check(courses.first(0, PM) == null, "dates do not mix");
        check(AM.ownsLabel("课程闹钟 08:10") && !PM.ownsLabel("课程闹钟 08:10"), "legacy clock labels");
        check(PM.ownsLabel("课程下午闹钟 14:10") && !AM.ownsLabel("课程下午闹钟 14:10"), "afternoon clock labels");
        check(!AM.ownsLabel("午休") && !PM.ownsLabel("午休"), "ordinary clock label");
    }

    private static void checkDates() {
        var today = at(20, 13, 55);
        var tomorrow = at(21, 13, 55);
        var tick = at(20, 21, 0).toInstant();
        var morning = AlarmTime.plan(at(20, 9, 0).toInstant(), today, tomorrow, tick);
        check(morning.useToday() && morning.representable() && today.equals(morning.target()), "today's afternoon is still upcoming");
        var deferred = AlarmTime.plan(at(20, 9, 0).toInstant(), null, tomorrow, tick);
        check(!deferred.representable() && deferred.followUpMillis() == at(20, 13, 56).toInstant().toEpochMilli(),
                "tomorrow's afternoon cannot ring today");
        var ready = AlarmTime.plan(at(20, 13, 56).toInstant(), null, tomorrow, tick);
        check(ready.representable() && tomorrow.equals(ready.target()), "schedule tomorrow after today's time passes");
        var lateToday = at(20, 21, 45);
        var late = AlarmTime.plan(tick, lateToday, tomorrow, at(21, 21, 0).toInstant());
        check(late.useToday() && late.followUpMillis() == at(20, 21, 46).toInstant().toEpochMilli(),
                "late timetable can replan before tomorrow's afternoon");
        var early = AlarmTime.forCourse(at(21, 0, 15).toInstant(), 30, ZONE);
        check(early.equals(at(21, 0, 0)), "lead time cannot accidentally schedule yesterday");
        check(AlarmTime.plan(tick, null, early, at(21, 21, 0).toInstant()).representable(), "midnight is tomorrow");
    }

    private static void checkSettings() throws Exception {
        Map<String, Object> stored = new HashMap<>();
        stored.put("enabled", true);
        stored.put("offset_min", 25);
        stored.put("ring_silent", true);
        stored.put("vibrate", false);
        stored.put("last_result", "旧结果");
        var editor = (SharedPreferences.Editor) Proxy.newProxyInstance(AlarmPlanningCheck.class.getClassLoader(),
                new Class[]{SharedPreferences.Editor.class}, (proxy, method, args) -> {
                    if (method.getName().startsWith("put")) {
                        stored.put((String) args[0], args[1]);
                        return proxy;
                    }
                    if ("apply".equals(method.getName())) return null;
                    throw new UnsupportedOperationException(method.getName());
                });
        var prefs = (SharedPreferences) Proxy.newProxyInstance(AlarmPlanningCheck.class.getClassLoader(),
                new Class[]{SharedPreferences.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getBoolean", "getInt", "getString" -> stored.getOrDefault((String) args[0], args[1]);
                    case "edit" -> editor;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        var field = Prefs.class.getDeclaredField("sp");
        field.setAccessible(true);
        field.set(null, prefs);
        check(Prefs.enabled(AM) && Prefs.offset(AM) == 25 && Prefs.silent(AM) && !Prefs.vibrate(AM),
                "upgrade preserves existing morning settings");
        check(!Prefs.enabled(PM) && Prefs.offset(PM) == 15 && !Prefs.silent(PM) && Prefs.vibrate(PM),
                "afternoon starts with independent defaults");
        Prefs.setEnabled(PM, true);
        Prefs.setOffset(PM, 35);
        Prefs.setSilent(PM, true);
        Prefs.setVibrate(PM, false);
        Prefs.setLastResult(PM, "下午已设置");
        check(Prefs.offset(AM) == 25 && "旧结果".equals(Prefs.lastResult(AM)), "afternoon edits leave morning intact");
        Prefs.setEnabled(AM, false);
        check(Prefs.anyAlarmEnabled() && "下午已设置".equals(Prefs.lastResult(PM)), "afternoon alone keeps scheduling");
        Prefs.setEnabled(PM, false);
        check(!Prefs.anyAlarmEnabled(), "closing both periods stops nightly scheduling");
    }

    private static ZonedDateTime at(int day, int hour, int minute) {
        return ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, ZONE);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
